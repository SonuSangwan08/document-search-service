-- Fixed, dropdown-driven facet fields (department/category/docType are
-- client-supplied from a known set; sizeBucket is server-derived from
-- content length at create time, never client-settable). Deliberately NOT
-- the same mechanism as the freeform `metadata` blob - these are bounded,
-- known-cardinality fields safe to map as keyword facets in Elasticsearch.
ALTER TABLE documents ADD COLUMN department VARCHAR(128);
ALTER TABLE documents ADD COLUMN category VARCHAR(128);
ALTER TABLE documents ADD COLUMN doc_type VARCHAR(128);
ALTER TABLE documents ADD COLUMN size_bucket VARCHAR(16);
