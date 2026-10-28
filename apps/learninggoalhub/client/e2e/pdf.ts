// Builds small text PDFs for the browser tests, so no binary fixture is committed. One page per
// entry of `pages`, one line of Helvetica per string.

function escape(text: string): string {
  return text.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
}

export function textPdf(pages: string[][]): Buffer {
  const objects: string[] = [];
  const pageIds = pages.map((_, i) => 4 + i * 2);
  objects[1] = "<< /Type /Catalog /Pages 2 0 R >>";
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(" ")}] /Count ${pages.length} >>`;
  objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>";
  pages.forEach((lines, i) => {
    const pageId = pageIds[i];
    const stream = [
      "BT /F1 14 Tf 16 TL 50 780 Td",
      ...lines.map((line) => `(${escape(line)}) Tj T*`),
      "ET",
    ].join("\n");
    objects[pageId] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${pageId + 1} 0 R >>`;
    objects[pageId + 1] = `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`;
  });

  let out = "%PDF-1.4\n";
  const offsets: number[] = [];
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = Buffer.byteLength(out);
    out += `${id} 0 obj\n${objects[id]}\nendobj\n`;
  }
  const xref = Buffer.byteLength(out);
  out += `xref\n0 ${objects.length}\n0000000000 65535 f \n`;
  for (let id = 1; id < objects.length; id++) {
    out += `${String(offsets[id]).padStart(10, "0")} 00000 n \n`;
  }
  out += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, "latin1");
}

// Two lectures whose gerund lines the fake model turns into skills. The headings are not gerunds,
// so they stay text.
export const SORTING_LECTURE = [
  [
    "Week 1",
    "Implementing merge sort on an array of integers.",
    "Implementing insertion sort on a linked list.",
  ],
  [
    "Week 1, continued",
    "Comparing the running time of insertion sort and merge sort.",
    "Analysing the stability of a sorting algorithm.",
  ],
];

export const SEARCHING_LECTURE = [
  [
    "Week 2",
    "Applying binary search to a sorted array.",
    "Applying linear search to an unsorted list.",
    "Comparing binary search with linear search.",
    "Analysing the worst case of binary search.",
  ],
];
