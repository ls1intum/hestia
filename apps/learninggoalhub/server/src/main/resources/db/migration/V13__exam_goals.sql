-- Exam-task goals (ExamLens integration): a course gets one EXAM hierarchy root (sibling of the
-- MODULE root) holding goals of origin EXAM, generated from consumer-submitted exam tasks.
ALTER TABLE hierarchy_node
    DROP CONSTRAINT hierarchy_node_level_check;
ALTER TABLE hierarchy_node
    ADD CONSTRAINT hierarchy_node_level_check
        CHECK (level IN ('MODULE', 'SESSION', 'EXERCISE', 'EXAM'));

ALTER TABLE learning_goal
    DROP CONSTRAINT learning_goal_origin_check;
ALTER TABLE learning_goal
    ADD CONSTRAINT learning_goal_origin_check
        CHECK (origin IN ('EXTRACTED', 'SYNTHESIZED', 'EXAM'));
