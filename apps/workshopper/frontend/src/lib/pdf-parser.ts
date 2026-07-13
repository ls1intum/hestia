import * as pdfjsLib from "pdfjs-dist";

// pdfjs-dist v4: worker is at build/pdf.worker.mjs
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.mjs",
  import.meta.url
).toString();

/**
 * Extract plain text from a PDF File object.
 * Concatenates all pages, separated by double newlines.
 */
export async function extractPdfText(file: File): Promise<string> {
  const arrayBuffer = await file.arrayBuffer();
  const pdf = await pdfjsLib.getDocument({ data: new Uint8Array(arrayBuffer) }).promise;

  const pages: string[] = [];
  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i);
    const content = await page.getTextContent();
    const text = content.items
      .map((item) => ("str" in item ? item.str : ""))
      .join(" ");
    pages.push(text);
  }

  return pages.join("\n\n").replace(/\s+/g, " ").trim();
}

/**
 * Extract plain text from a PPTX File object.
 */
import JSZip from "jszip";

export async function extractPptxText(file: File): Promise<string> {
  const arrayBuffer = await file.arrayBuffer();
  const zip = new JSZip();
  const loadedZip = await zip.loadAsync(arrayBuffer);
  
  // Find all slide files
  const slideFiles = Object.keys(loadedZip.files).filter(
    (name) => name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
  );
  
  let allText = [];
  
  for (const filename of slideFiles) {
    const content = await loadedZip.files[filename].async("string");
    // Extract text from <a:t>...</a:t>
    const matches = content.matchAll(/<a:t[^>]*>(.*?)<\/a:t>/g);
    for (const match of matches) {
      if (match[1]) {
        allText.push(match[1]);
      }
    }
  }
  
  return allText.join(" ").replace(/\s+/g, " ").trim();
}
