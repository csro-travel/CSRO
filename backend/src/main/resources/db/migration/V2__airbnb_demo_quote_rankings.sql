ALTER TABLE quote_rankings
    ADD COLUMN IF NOT EXISTS ranking_kind varchar(20) NOT NULL DEFAULT 'API_025',
    DROP CONSTRAINT IF EXISTS ck_quote_rankings_1;

ALTER TABLE quote_rankings
    ADD CONSTRAINT ck_quote_rankings_1 CHECK (
        (ranking_kind = 'API_025' AND validation_status IN ('VALID', 'REJECTED'))
        OR (ranking_kind = 'AIRBNB_DEMO' AND validation_status = 'DEMO_ONLY')
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_quote_rankings_airbnb_demo_job
    ON quote_rankings (search_job_id)
    WHERE ranking_kind = 'AIRBNB_DEMO';
