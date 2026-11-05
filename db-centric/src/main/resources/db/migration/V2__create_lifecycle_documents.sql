-- Optional JSONB implementation; relational state remains in the V1 tables.
CREATE TABLE lifecycle_documents (
    hierarchy_key text PRIMARY KEY,
    state jsonb NOT NULL,
    completed_at timestamptz,
    completed_by text,
    published_at timestamptz,
    CHECK (jsonb_typeof(state) = 'object'),
    CHECK ((completed_at IS NULL) = (completed_by IS NULL)),
    CHECK (published_at IS NULL OR completed_at IS NOT NULL)
);
CREATE INDEX lifecycle_documents_pending_publication_idx
    ON lifecycle_documents (completed_at, hierarchy_key)
    WHERE completed_at IS NOT NULL AND published_at IS NULL;
