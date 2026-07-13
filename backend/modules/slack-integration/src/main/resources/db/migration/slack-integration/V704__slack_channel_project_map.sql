-- slack-integration BC 채널↔프로젝트 매핑 — slack_channel_project_map 테이블 (FR-SL-06 PR-A)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). V703 다음 = V704.
--
-- FR-SL-06 프로젝트 활동 피드. 한 프로젝트의 이벤트를 특정 Slack 채널로 브로드캐스트하기 위한 채널↔프로젝트 매핑을
-- 저장한다. 다대다(한 프로젝트→여러 채널, 한 채널→여러 프로젝트)이며, event_types 로 라우팅할 이벤트 종류를 필터한다.
--   - team_id      — Slack workspace(team) id. 유일 SlackInstall 에서 해석(단일 설치 가정). cross-BC 참조라 FK 없음.
--   - project_key  — 대상 프로젝트 키. 라우팅 시점 projectKey→UUID cross-BC 조회를 없애려 UUID 아닌 key 로 스코프. FK 없음.
--   - channel_id   — 게시 대상 Slack 채널 id(Cxxxx).
--   - channel_name — 표시용 채널명(옵션). null 허용.
--   - event_types  — 이 매핑으로 라우팅할 이벤트 종류 집합(NotificationEventType.wireValue 문자열, 비어있지 않음).
--                    BC 격리로 notification enum 직접 import 금지 — wire 문자열로 미러(SlackChannelEventType).
--
-- 소프트 삭제(deleted_at) 없음 — 설정성 매핑 행으로 DELETE API 가 물리 삭제한다(스펙 S8: 삭제 후 이후 게시만 중단,
-- 과거 게시는 소급 삭제 없음). audit/로그 성격이 아니라 소프트 삭제 기본(DATA.md §1.2)의 스펙 명시 예외다.
--
-- UNIQUE(team_id, project_key, channel_id) — 같은 워크스페이스에서 (프로젝트,채널) 매핑 중복 방지(멱등 CRUD 근거).
-- 인덱스 (project_key) — 채널 워커가 projectKey 로 매핑을 조회(PR-B 라우팅). PostgreSQL 은 FK/조회 인덱스를
-- 자동 생성하지 않는다(DATA.md §4.1#6).

CREATE TABLE slack_channel_project_map (
    id           UUID PRIMARY KEY,                    -- 매핑 식별자(애플리케이션 생성 UUID)
    team_id      TEXT NOT NULL,                       -- Slack workspace(team) id (cross-BC, FK 아님)
    project_key  TEXT NOT NULL,                       -- 대상 프로젝트 키 (cross-BC, FK 아님)
    channel_id   TEXT NOT NULL,                       -- 게시 대상 Slack 채널 id(Cxxxx)
    channel_name TEXT,                                -- 표시용 채널명(옵션) — 없으면 null
    event_types  TEXT[] NOT NULL,                     -- 라우팅 이벤트 wireValue 집합(비어있지 않음)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 생성 시각(TIMESTAMP without tz 금지, DATA.md §4)
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 최종 수정 시각
    CONSTRAINT uq_slack_channel_project_map_team_project_channel
        UNIQUE (team_id, project_key, channel_id)     -- (워크스페이스, 프로젝트, 채널) 매핑 중복 방지
);

COMMENT ON TABLE  slack_channel_project_map              IS 'Slack 채널↔프로젝트 매핑 — 프로젝트 활동 피드 브로드캐스트 라우팅(FR-SL-06)';
COMMENT ON COLUMN slack_channel_project_map.id           IS '매핑 식별자(PK, 애플리케이션 생성 UUID)';
COMMENT ON COLUMN slack_channel_project_map.team_id      IS 'Slack workspace(team) id — 유일 SlackInstall 에서 해석(cross-BC, FK 아님)';
COMMENT ON COLUMN slack_channel_project_map.project_key  IS '대상 프로젝트 키 — 라우팅 시점 cross-BC 조회 회피 위해 key 스코프(FK 아님)';
COMMENT ON COLUMN slack_channel_project_map.channel_id   IS '게시 대상 Slack 채널 id(Cxxxx)';
COMMENT ON COLUMN slack_channel_project_map.channel_name IS '표시용 채널명(옵션) — 없으면 null';
COMMENT ON COLUMN slack_channel_project_map.event_types  IS '라우팅할 이벤트 종류 집합(NotificationEventType.wireValue, 비어있지 않음) — BC 격리로 wire 문자열 미러';
COMMENT ON COLUMN slack_channel_project_map.created_at   IS '매핑 생성 시각';
COMMENT ON COLUMN slack_channel_project_map.updated_at   IS '매핑 최종 수정 시각';

-- 채널 워커가 projectKey 로 매핑을 조회하는 라우팅 인덱스(PR-B 소비).
CREATE INDEX idx_slack_channel_project_map_project_key
    ON slack_channel_project_map (project_key);

COMMENT ON INDEX idx_slack_channel_project_map_project_key IS '채널 워커 라우팅 조회(project_key) — FR-SL-06 PR-B 매핑 팬아웃';
