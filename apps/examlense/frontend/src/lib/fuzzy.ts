/**
 * Tiny dependency-free fuzzy matcher used for the exam-list Title search.
 *
 * `fuzzyMatch` returns a numeric score when every character of `query` appears
 * in `text` in order (a subsequence match), or `null` when it doesn't match.
 * Higher scores are better; contiguous runs, start-of-word hits, and a leading
 * match are rewarded so the most relevant titles rank first. Matching is
 * case-insensitive. An empty query matches everything with a neutral score.
 */
export const fuzzyMatch = (query: string, text: string): number | null => {
  const q = query.trim().toLowerCase();
  if (q.length === 0) return 0;
  const t = text.toLowerCase();
  if (t.length === 0) return null;

  let score = 0;
  let ti = 0;
  let prevMatchIdx = -2; // so the first match isn't treated as "contiguous"
  for (let qi = 0; qi < q.length; qi++) {
    const ch = q[qi];
    const found = t.indexOf(ch, ti);
    if (found === -1) return null;

    score += 1;
    if (found === prevMatchIdx + 1) score += 5; // contiguous run
    if (found === 0) score += 10; // matches very start
    else if (!/[a-z0-9]/.test(t[found - 1])) score += 3; // start of a word

    prevMatchIdx = found;
    ti = found + 1;
  }
  // Prefer shorter targets when scores otherwise tie (a full-word hit in a
  // short title beats the same letters scattered through a long one).
  return score - t.length * 0.01;
};

/**
 * Filter `items` to those whose `key` fuzzily matches `query`, ranked best
 * first. An empty query returns the items unchanged (stable order preserved).
 */
export const fuzzyFilter = <T>(
  items: T[],
  query: string,
  key: (item: T) => string,
): T[] => {
  if (query.trim().length === 0) return items;
  return items
    .map((item) => ({ item, score: fuzzyMatch(query, key(item)) }))
    .filter((r): r is { item: T; score: number } => r.score !== null)
    .sort((a, b) => b.score - a.score)
    .map((r) => r.item);
};
