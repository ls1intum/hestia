-- Competency-tree LAYER 2 (sub-skill → knowledge): the gap analysis adds "should-be-taught"
-- knowledge aspects it judges MISSING from the material as UNANCHORED learning goals. Widen the
-- origin check constraint so the new GAP value is accepted; existing rows are unaffected.
ALTER TABLE learning_goal DROP CONSTRAINT learning_goal_origin_check;
ALTER TABLE learning_goal ADD CONSTRAINT learning_goal_origin_check
    CHECK (origin IN ('EXTRACTED', 'SYNTHESIZED', 'EXAM', 'TERMINAL', 'GAP'));
