-- Hierarchy nodes are now built from a per-document outline pass, so each session/exercise
-- node originates from a specific document. The module root stays document-less (null).
ALTER TABLE hierarchy_node
    ADD COLUMN document_id BIGINT REFERENCES document (id) ON DELETE SET NULL;

CREATE INDEX idx_hierarchy_node_document ON hierarchy_node (document_id);
