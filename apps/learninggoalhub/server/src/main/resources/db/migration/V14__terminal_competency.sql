-- Competency-tree view (alongside the module goals): terminal competencies are persisted as
-- TERMINAL-origin learning goals under their own COMPETENCY hierarchy node. Widen the two enum
-- check constraints so the new values are accepted; existing rows are unaffected.
ALTER TABLE hierarchy_node DROP CONSTRAINT hierarchy_node_level_check;
ALTER TABLE hierarchy_node ADD CONSTRAINT hierarchy_node_level_check
    CHECK (level IN ('MODULE', 'SESSION', 'EXERCISE', 'EXAM', 'COMPETENCY'));

ALTER TABLE learning_goal DROP CONSTRAINT learning_goal_origin_check;
ALTER TABLE learning_goal ADD CONSTRAINT learning_goal_origin_check
    CHECK (origin IN ('EXTRACTED', 'SYNTHESIZED', 'EXAM', 'TERMINAL'));
