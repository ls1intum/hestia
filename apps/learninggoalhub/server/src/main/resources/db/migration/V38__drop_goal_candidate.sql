-- goal_candidate held the raw, per-chunk output of the chunked extraction fallback, kept so a
-- consolidated goal could be traced back to the candidates it was merged from. That fallback is
-- gone: every extraction unit now runs the direct two-tier path, which grounds each goal in source
-- line numbers and needs no intermediate candidate rows. Nothing reads the table, and it is
-- server-internal — no API or client ever exposed it.
DROP TABLE IF EXISTS goal_candidate;
