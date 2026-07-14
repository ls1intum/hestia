import { describe, it, expect } from "vitest";
import { fuzzyMatch, fuzzyFilter } from "./fuzzy";

describe("fuzzyMatch", () => {
  it("matches an exact substring and a subsequence", () => {
    expect(fuzzyMatch("mid", "Midterm")).not.toBeNull();
    expect(fuzzyMatch("mtm", "Midterm")).not.toBeNull(); // subsequence
  });

  it("returns null when characters are missing or out of order", () => {
    expect(fuzzyMatch("xyz", "Midterm")).toBeNull();
    expect(fuzzyMatch("term mid", "Midterm")).toBeNull();
  });

  it("is case-insensitive and treats empty query as a match", () => {
    expect(fuzzyMatch("MID", "midterm exam")).not.toBeNull();
    expect(fuzzyMatch("", "anything")).toBe(0);
  });

  it("ranks a start-of-string/contiguous hit above a scattered one", () => {
    const contiguous = fuzzyMatch("exam", "Exam 1")!;
    const scattered = fuzzyMatch("exam", "Extra assignment material")!;
    expect(contiguous).toBeGreaterThan(scattered);
  });
});

describe("fuzzyFilter", () => {
  const titles = ["Final Exam", "Midterm Exam", "Homework 3", "Exam Review"];

  it("returns all items (unchanged order) for an empty query", () => {
    expect(fuzzyFilter(titles, "  ", (t) => t)).toEqual(titles);
  });

  it("filters to matches ranked best-first", () => {
    const result = fuzzyFilter(titles, "exam", (t) => t);
    expect(result).not.toContain("Homework 3");
    expect(result[0]).toBe("Exam Review"); // leading match ranks first
  });
});
