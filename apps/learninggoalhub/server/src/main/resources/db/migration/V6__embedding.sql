CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE learning_goal
    ADD COLUMN embedding vector(4096);
