// A stand-in for the OpenAI-compatible model gateway, so the browser tests run the real server
// without a model. It recognises each prompt the server sends by the JSON shape the prompt asks
// for and answers deterministically from the prompt's own numbered lines.
//
// Control endpoints for the tests:
//   POST /__fail {"times": n}  the next n chat calls answer 400, which the server does not retry
//   POST /__reset              clears the failures and the call log
//   GET  /__calls              the kinds of the calls answered so far
import { createServer } from "node:http";

const port = Number(process.env.FAKE_LLM_PORT ?? 4010);

let failuresLeft = 0;
let calls = [];

/** "[3] some text" lines of a prompt, as {index, text}. */
function numbered(prompt) {
  return [...prompt.matchAll(/^\[(\d+)\] (.*)$/gm)].map((m) => ({ index: Number(m[1]), text: m[2].trim() }));
}

/** The numbered lines between the last pair of "---" fences, where the tree prompts put their items. */
function fencedItems(prompt) {
  const parts = prompt.split(/^---$/m);
  return numbered(parts.length >= 3 ? parts[parts.length - 2] : prompt);
}

// The fixture PDFs phrase every skill as a gerund, as the server's validation demands.
const SKILL_VERBS = /^[A-Z][a-z]+ing\b/;

function topicOf(text) {
  return /search/i.test(text) ? "Searching algorithms" : "Sorting algorithms";
}

function extraction(prompt) {
  return numbered(prompt)
    .filter((line) => SKILL_VERBS.test(line.text))
    .map((line) => ({
      text: line.text.replace(/\.$/, ""),
      shortLabel: line.text.split(" ").slice(0, 3).join(" "),
      kind: "EXPLICIT",
      bloom: "APPLY",
      solo: "MULTISTRUCTURAL",
      sourceStartLine: line.index,
      sourceEndLine: line.index,
      knowledge: [{
        text: `Recalling the terms behind ${line.text.charAt(0).toLowerCase()}${line.text.slice(1).replace(/\.$/, "")}`,
        shortLabel: `Terms of ${line.text.split(" ")[1]}`,
        kind: "IMPLICIT",
        bloom: "UNDERSTAND",
        solo: "UNISTRUCTURAL",
        sourceStartLine: line.index,
        sourceEndLine: line.index,
      }],
    }));
}

function naming(prompt) {
  const topics = [...new Set(fencedItems(prompt).map((item) => topicOf(item.text)))];
  return { capabilities: topics.length > 0 ? topics : ["General topic"] };
}

/** Each outcome under the label named after its subject; the labels come before the outcome list. */
function assignment(prompt) {
  const parts = prompt.split(/^---$/m);
  const labels = numbered(parts.slice(0, -2).join("\n"));
  const outcomes = fencedItems(prompt);
  return {
    assignments: outcomes.map((outcome) => {
      const wanted = topicOf(outcome.text);
      const label = labels.find((l) => l.text.includes(wanted)) ?? labels[0];
      return [outcome.index, label ? label.index : -1];
    }),
  };
}

/** Two skills per topic: the first half of its outcomes and the second, which the server keeps. */
function structure(prompt) {
  const items = fencedItems(prompt).map((item) => item.index);
  const label = prompt.match(/^Topic: (.*)$/m)?.[1] ?? "this topic";
  const half = Math.ceil(items.length / 2);
  return {
    subCapabilities: [
      { name: `Implementing ${label.toLowerCase()}`, outcomes: items.slice(0, half) },
      { name: `Analysing ${label.toLowerCase()}`, outcomes: items.slice(half) },
    ],
    direct: [],
  };
}

function shortLabels(prompt) {
  return {
    labels: fencedItems(prompt).map((item) => ({ index: item.index, shortLabel: item.text.split(" ").slice(0, 3).join(" ") })),
  };
}

/** [kind, reply] for a prompt, or null when the prompt is not one this fake knows. */
function answer(prompt) {
  if (prompt.includes("sourceStartLine")) return ["extraction", extraction(prompt)];
  if (prompt.includes("subCapabilities")) return ["structure", structure(prompt)];
  if (prompt.includes(`"labels"`) && prompt.includes("shortLabel")) return ["short-labels", shortLabels(prompt)];
  if (prompt.includes(`"assignments"`)) return ["assignment", assignment(prompt)];
  if (prompt.includes(`"capabilities"`)) return ["naming", naming(prompt)];
  return null;
}

function textOf(message) {
  if (typeof message.content === "string") return message.content;
  if (Array.isArray(message.content)) return message.content.map((part) => part.text ?? "").join("\n");
  return "";
}

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function completion(model, content) {
  return {
    id: `fake-${calls.length}`,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, message: { role: "assistant", content }, finish_reason: "stop" }],
    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
  };
}

createServer((req, res) => {
  let raw = "";
  req.on("data", (chunk) => { raw += chunk; });
  req.on("end", () => {
    if (req.method === "GET" && req.url === "/__calls") return send(res, 200, calls);
    if (req.method === "POST" && req.url === "/__reset") {
      failuresLeft = 0;
      calls = [];
      return send(res, 200, {});
    }
    if (req.method === "POST" && req.url === "/__fail") {
      failuresLeft = Number(JSON.parse(raw || "{}").times ?? 1);
      return send(res, 200, {});
    }
    if (req.method !== "POST" || !req.url.endsWith("/chat/completions")) {
      return send(res, 404, { error: { message: `fake-llm: no route ${req.method} ${req.url}` } });
    }
    const body = JSON.parse(raw);
    const prompt = body.messages.map(textOf).join("\n");
    if (failuresLeft > 0) {
      failuresLeft -= 1;
      calls.push("failed");
      return send(res, 400, { error: { message: "fake-llm: failure requested by the test" } });
    }
    const result = answer(prompt);
    if (!result) {
      console.error(`fake-llm: unknown prompt:\n${prompt.slice(0, 600)}`);
      calls.push("unknown");
      return send(res, 400, { error: { message: "fake-llm: unknown prompt" } });
    }
    calls.push(result[0]);
    return send(res, 200, completion(body.model, JSON.stringify(result[1])));
  });
}).listen(port, () => console.log(`fake-llm listening on ${port}`));
