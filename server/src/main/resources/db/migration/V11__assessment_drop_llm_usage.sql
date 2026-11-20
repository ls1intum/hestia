-- Per-user job limits now live in the ai module (ai.llm_job); the per-organisation ceiling is gone.
drop table if exists assessment.llm_usage;
