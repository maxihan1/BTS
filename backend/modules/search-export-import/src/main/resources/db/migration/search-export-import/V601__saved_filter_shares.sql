-- search-export-import BC 저장된 필터 공유 대상 — saved_filter_shares (FR-SR-03 PR2)

CREATE TABLE saved_filter_shares (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filter_id  UUID        NOT NULL REFERENCES saved_filters(id) ON DELETE CASCADE,
    share_type VARCHAR(20) NOT NULL CHECK (share_type IN ('PROJECT','GROUP','AUTHENTICATED')),
    target_id  VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_filter_shares UNIQUE NULLS NOT DISTINCT (filter_id, share_type, target_id),
    CONSTRAINT ck_saved_filter_shares_target
        CHECK ((share_type = 'AUTHENTICATED') = (target_id IS NULL))
);

CREATE INDEX idx_saved_filter_shares_filter ON saved_filter_shares (filter_id);
CREATE INDEX idx_saved_filter_shares_lookup ON saved_filter_shares (share_type, target_id);
