-- The generated wording of a goal, kept on its first rename, so the evaluation can tell a goal
-- accepted as generated from one an instructor reworded. NULL means never renamed, or created by
-- an instructor. No backfill: earlier renames left no trace.
ALTER TABLE learning_goal
    ADD COLUMN original_text TEXT;
