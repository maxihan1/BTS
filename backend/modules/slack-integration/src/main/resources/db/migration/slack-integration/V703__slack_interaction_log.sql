-- slack-integration BC Slack 인터랙티브 상호작용 감사 로그 — slack_interaction_log 테이블 (FR-SL-05)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). V702 다음 = V703.
--
-- FR-SL-05 Slack 인터랙티브(버튼/셀렉트 등 Block Kit 액션)로 발생하는 상호작용의 처리 결과를 append-only 로
-- 기록하는 감사 로그다. 어떤 Slack 사용자(team_id/slack_user_id)가 어떤 액션(action_type)을 시도했고, 그 결과가
-- 무엇이었는지(outcome)를 남겨 미연결/권한거부/충돌/오류 같은 실패를 사후 추적한다.
--   - bts_user_id — Slack 사용자가 BTS 계정에 연결되지 않았으면(UNMAPPED) null 이다. cross-BC(BC 격리)라 FK 없음.
--   - action_type — COMPLETE / ASSIGN / COMMENT / VIEW (애플리케이션 레벨 값, enum 제약은 두지 않는다 — 로그 원문 보존).
--   - outcome     — SUCCESS / UNMAPPED / PERMISSION_DENIED / CONFLICT / ERROR / NOT_APPLICABLE.
--   - issue_key   — 대상 이슈 키(있으면). cross-BC 참조라 FK 없음.
--
-- 소프트 삭제(deleted_at) 없음 — 감사/로그 성격이라 append-only 이며 소프트 삭제 대상이 아니다
-- (db-engineer 체크리스트 §5 감사/로그 제외군, slack_delivery_log(V701) 동형).
--
-- 인덱스: created_at DESC — 최근 상호작용부터 조회하는 감사 뷰의 시간축 정렬을 지원한다(DATA.md §4 FK/조회 인덱스).

CREATE TABLE slack_interaction_log (
    id            UUID PRIMARY KEY,                    -- 상호작용 로그 식별자(애플리케이션 생성 UUID)
    team_id       TEXT NOT NULL,                       -- Slack workspace(team) id
    slack_user_id TEXT NOT NULL,                       -- 액션을 실행한 Slack 사용자 id(Uxxxx)
    bts_user_id   UUID,                                -- 연결된 BTS user id(cross-BC, FK 아님) — 미연결이면 null
    action_type   TEXT NOT NULL,                       -- COMPLETE / ASSIGN / COMMENT / VIEW (로그 원문)
    outcome       TEXT NOT NULL,                       -- SUCCESS / UNMAPPED / PERMISSION_DENIED / CONFLICT / ERROR / NOT_APPLICABLE
    issue_key     TEXT,                                -- 대상 이슈 키(cross-BC, FK 아님) — 없으면 null
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()   -- 상호작용 기록 시각(TIMESTAMP without tz 금지, DATA.md §4)
);

COMMENT ON TABLE  slack_interaction_log               IS 'Slack 인터랙티브 상호작용 감사 로그(append-only) — 액션 처리 결과 추적(FR-SL-05)';
COMMENT ON COLUMN slack_interaction_log.id            IS '상호작용 로그 식별자(PK, 애플리케이션 생성 UUID)';
COMMENT ON COLUMN slack_interaction_log.team_id       IS 'Slack workspace(team) id';
COMMENT ON COLUMN slack_interaction_log.slack_user_id IS '액션을 실행한 Slack 사용자 id(Uxxxx)';
COMMENT ON COLUMN slack_interaction_log.bts_user_id   IS '연결된 BTS user id(cross-BC, BC 격리로 FK 아님) — 미연결이면 null';
COMMENT ON COLUMN slack_interaction_log.action_type   IS 'COMPLETE / ASSIGN / COMMENT / VIEW (로그 원문, enum 제약 없음)';
COMMENT ON COLUMN slack_interaction_log.outcome       IS 'SUCCESS / UNMAPPED / PERMISSION_DENIED / CONFLICT / ERROR / NOT_APPLICABLE';
COMMENT ON COLUMN slack_interaction_log.issue_key     IS '대상 이슈 키(cross-BC, BC 격리로 FK 아님) — 없으면 null';
COMMENT ON COLUMN slack_interaction_log.created_at    IS '상호작용 기록 시각';

-- 최근 상호작용부터 조회하는 감사 뷰의 시간축 정렬 인덱스.
CREATE INDEX idx_slack_interaction_log_created ON slack_interaction_log (created_at DESC);

COMMENT ON INDEX idx_slack_interaction_log_created IS '감사 뷰 시간축 정렬(created_at DESC) — 최근 상호작용 우선 조회';
