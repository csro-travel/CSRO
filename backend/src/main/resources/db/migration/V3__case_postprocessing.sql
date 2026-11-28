CREATE TABLE case_postprocessing (
    draft_id uuid PRIMARY KEY,
    case_id bigint NOT NULL REFERENCES cases(id),
    created_by bigint NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    case_input jsonb NOT NULL,
    draft jsonb NOT NULL,
    submitted jsonb,
    request_hash char(64),
    finalized_by bigint REFERENCES users(id),
    finalized_at timestamptz,
    task_ids jsonb,
    document bytea,
    CONSTRAINT ck_case_postprocessing_finalization CHECK (
        (submitted IS NULL AND request_hash IS NULL AND finalized_by IS NULL
            AND finalized_at IS NULL AND task_ids IS NULL AND document IS NULL)
        OR (submitted IS NOT NULL AND request_hash IS NOT NULL AND finalized_by IS NOT NULL
            AND finalized_at IS NOT NULL AND task_ids IS NOT NULL AND document IS NOT NULL)
    )
);
CREATE INDEX ix_case_postprocessing_latest ON case_postprocessing(case_id, created_at DESC);
