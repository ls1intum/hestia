#!/usr/bin/env node
// One-off: copy stored files (PDFs + figures) from Supabase Storage onto the
// backend's local-FS storage volume, PRESERVING the object paths so the
// `source_file_url` / `storage_path` column values imported by import-rows.sh
// keep resolving (the backend reads `<base>/<bucket>/<path>`).
//
// Zero dependencies (uses Node 18+ global fetch). Run WHILE the Supabase
// project is still alive.
//
// Usage:
//   export SUPABASE_URL='https://<REF>.supabase.co'
//   export SUPABASE_SERVICE_ROLE_KEY='<service-role key from Dashboard → Project Settings → API>'
//   node ./import-storage.mjs
//
// Env overrides:
//   STORAGE_DEST  (default ../../backend/data/storage — matches STORAGE_LOCAL_BASE_PATH)
//   BUCKETS       (default "exam-pdfs,exam-figures")
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SUPABASE_URL = process.env.SUPABASE_URL;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
const here = dirname(fileURLToPath(import.meta.url));
const DEST = resolve(process.env.STORAGE_DEST || join(here, "../../backend/data/storage"));
const BUCKETS = (process.env.BUCKETS || "exam-pdfs,exam-figures").split(",").map((b) => b.trim());

if (!SUPABASE_URL || !SERVICE_KEY) {
  console.error("Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (see header).");
  process.exit(1);
}

const headers = { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };
const base = SUPABASE_URL.replace(/\/$/, "");

// List one "directory" level. Supabase returns folder entries with id === null
// and file entries with a non-null id; we recurse into folders.
async function list(bucket, prefix) {
  const out = [];
  for (let offset = 0; ; offset += 1000) {
    const res = await fetch(`${base}/storage/v1/object/list/${bucket}`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({ prefix, limit: 1000, offset, sortBy: { column: "name", order: "asc" } }),
    });
    if (!res.ok) throw new Error(`list ${bucket}/${prefix}: ${res.status} ${await res.text()}`);
    const page = await res.json();
    out.push(...page);
    if (page.length < 1000) break;
  }
  return out;
}

async function walk(bucket, prefix, files) {
  for (const e of await list(bucket, prefix)) {
    const path = prefix ? `${prefix}/${e.name}` : e.name;
    if (e.id === null || e.metadata == null) await walk(bucket, path, files); // folder
    else files.push(path); // file
  }
}

async function download(bucket, path) {
  const res = await fetch(`${base}/storage/v1/object/${bucket}/${encodeURI(path)}`, { headers });
  if (!res.ok) throw new Error(`download ${bucket}/${path}: ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

for (const bucket of BUCKETS) {
  const files = [];
  await walk(bucket, "", files);
  console.log(`${bucket}: ${files.length} file(s)`);
  for (const path of files) {
    const bytes = await download(bucket, path);
    const dest = join(DEST, bucket, path);
    await mkdir(dirname(dest), { recursive: true });
    await writeFile(dest, bytes);
    console.log(`  ↓ ${bucket}/${path} (${bytes.length} b)`);
  }
}
console.log(`\nStorage import complete → ${DEST}`);
