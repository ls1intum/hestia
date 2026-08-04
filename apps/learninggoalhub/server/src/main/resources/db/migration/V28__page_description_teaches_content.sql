-- Title slides, section headers and summary slides are text-poor and therefore eligible for
-- description, but they teach nothing. Only the vision model can tell them apart from a real
-- figure: the distinction is semantic, and no deterministic text feature separates them (page
-- length, position and repeated-boilerplate share were all tested and do not).
-- Rows written before this column existed were all offered to the extractor, hence the default.
ALTER TABLE page_description
    ADD COLUMN teaches_content BOOLEAN NOT NULL DEFAULT TRUE;
