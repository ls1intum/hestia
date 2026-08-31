ALTER TABLE goal_relationship
    DROP CONSTRAINT goal_relationship_type_check;

ALTER TABLE goal_relationship
    ADD CONSTRAINT goal_relationship_type_check
        CHECK (type IN ('CONTRIBUTES_TO', 'SUPPORTS', 'PREREQUISITE_OF', 'OVERLAPS_WITH'));
