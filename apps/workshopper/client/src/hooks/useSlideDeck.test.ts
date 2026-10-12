import { describe, expect, it } from "vitest";
import { deckSlides, remapSlidesToBlocks } from "./useSlideDeck";
import type { SlideData } from "@/lib/workshop-generator";

const slide = (title: string, fixedInstructionFor?: string): SlideData => ({ title, fixedInstructionFor });
const titles = (cache: Record<number, SlideData[]> | null) =>
  cache && Object.fromEntries(Object.entries(cache).map(([k, v]) => [k, v.map(s => s.title)]));

describe("deckSlides", () => {
  it("shows a fixed instruction slide only the first time its activity appears", () => {
    const cache = {
      0: [slide("Welcome")],
      1: [slide("How Think-Pair-Share works", "tps"), slide("Prompt 1")],
      2: [slide("How Think-Pair-Share works", "tps"), slide("Prompt 2")],
    };
    const deck = deckSlides(cache, 3);
    expect(deck.map(d => d.slide.title)).toEqual(["Welcome", "How Think-Pair-Share works", "Prompt 1", "Prompt 2"]);
    // each entry says where it is stored, so the editor changes the slide that is shown
    expect(deck[3]).toMatchObject({ blockIndex: 2, slideIndex: 1 });
  });
});

describe("remapSlidesToBlocks", () => {
  const cache = { 0: [slide("arrive")], 1: [slide("A")], 2: [slide("B")], 3: [slide("C")] };
  // the first block was saved before blocks had ids; the timetable names it by position
  const before = [{}, { blockId: "A" }, { blockId: "B" }, { blockId: "C" }];

  it("returns null when nothing moved", () => {
    expect(remapSlidesToBlocks(before, [{ blockId: "block-0" }, { blockId: "A" }, { blockId: "B" }, { blockId: "C" }], cache)).toBeNull();
  });

  it("follows moved, deleted and added blocks", () => {
    expect(titles(remapSlidesToBlocks(before, [{ blockId: "block-0" }, { blockId: "B" }, { blockId: "A" }, { blockId: "C" }], cache)))
      .toEqual({ 0: ["arrive"], 1: ["B"], 2: ["A"], 3: ["C"] });
    expect(titles(remapSlidesToBlocks(before, [{ blockId: "block-0" }, { blockId: "B" }, { blockId: "C" }], cache)))
      .toEqual({ 0: ["arrive"], 1: ["B"], 2: ["C"] });
    expect(titles(remapSlidesToBlocks(before, [{ blockId: "block-0" }, { blockId: "A" }, { blockId: "new" }, { blockId: "B" }, { blockId: "C" }], cache)))
      .toEqual({ 0: ["arrive"], 1: ["A"], 3: ["B"], 4: ["C"] });
  });

  it("does nothing without slides", () => {
    expect(remapSlidesToBlocks(before, [], {})).toBeNull();
  });
});
