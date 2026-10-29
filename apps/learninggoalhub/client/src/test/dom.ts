import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Imported by the component tests, which run under jsdom (`@vitest-environment jsdom`).
// Vitest runs without globals, so Testing Library cannot register its own cleanup.
afterEach(cleanup);

// jsdom lays nothing out, so every element reports no boxes; the focus trap would treat all of
// them as hidden. One box each stands in for "rendered".
HTMLElement.prototype.getClientRects = function () {
  return [new DOMRect(0, 0, 1, 1)] as unknown as DOMRectList;
};
