ALTER TABLE document
    ADD COLUMN language VARCHAR(16);

ALTER TABLE course
    ADD COLUMN output_language VARCHAR(16);
