-- V5__add_course_default_template.sql

ALTER TABLE courses ADD COLUMN template_id VARCHAR(255);

ALTER TABLE courses
    ADD CONSTRAINT fk_courses_template
    FOREIGN KEY (template_id) REFERENCES slide_templates(id) ON DELETE SET NULL;
