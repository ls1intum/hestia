ALTER TABLE learning_goal ADD COLUMN lecture_order INTEGER;

-- Existing rows did not carry an explicit order. Their creation order is the only stable ordering
-- signal available, and preserves the order users saw before this migration.
WITH ranked AS (
    SELECT id,
           CAST(ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY id) - 1 AS INTEGER) AS lecture_order
    FROM learning_goal
)
UPDATE learning_goal
SET lecture_order = ranked.lecture_order
FROM ranked
WHERE learning_goal.id = ranked.id;

CREATE INDEX idx_learning_goal_course_lecture_order
    ON learning_goal (course_id, lecture_order);
