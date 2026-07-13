-- Learning goals overhaul: goals are now derived per task by LearningGoalHub at
-- section-confirm time instead of picked exam-wide at creation.
ALTER TABLE exams ADD COLUMN lgh_course_id bigint;
ALTER TABLE exams DROP COLUMN learning_goal_ids;
-- CAS lock for the async goal-generation run (mirrors sections.solve_started_at).
ALTER TABLE sections ADD COLUMN goals_started_at timestamptz;
