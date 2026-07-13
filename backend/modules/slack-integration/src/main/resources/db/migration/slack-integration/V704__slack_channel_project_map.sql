-- slack-integration BC 채널↔프로젝트 매핑 — slack_channel_project_map 테이블 (FR-SL-06 PR-A)
-- V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1). V703 다음 = V704.

CREATE TABLE slack_channel_project_map (
    id           UUID PRIMARY KEY,
    team_id      TEXT NOT NULL,
    project_key  TEXT NOT NULL,
    channel_id   TEXT NOT NULL,
    channel_name TEXT,
    event_types  TEXT[] NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_slack_channel_project_map_team_project_channel
        UNIQUE (team_id, project_key, channel_id)
);

CREATE INDEX idx_slack_channel_project_map_project_key
    ON slack_channel_project_map (project_key);
