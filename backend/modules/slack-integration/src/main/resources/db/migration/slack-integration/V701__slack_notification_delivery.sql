-- slack-integration BC Slack 알림 발송 지원 — user_slack_mapping + slack_delivery_log 테이블 (FR-SL-02)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). V700(slack_installs) 다음 = V701.
--
-- 두 테이블 모두 Slack 비동기 알림 발송 파이프라인(pgmq q_slack_deliveries — notification BC 소관)의 지원 테이블이다.
--
-- 1) user_slack_mapping — BTS 사용자(user_id)↔Slack(slack_user_id/team_id) 매핑.
--    Slack DM 발송 시 수신 대상 BTS 사용자를 Slack 사용자 id 로 해석하는 조회 테이블이다.
--    사용자당 매핑 1행이므로 user_id 를 PK 로 둔다. 조회는 항상 PK(user_id) 경유이므로 slack_user_id 별도 인덱스 불요.
--    소프트 삭제(deleted_at) 없음 — 매핑은 연결/해제로 최신 상태만 유지하며(재연결=upsert, 해제=행 제거),
--    slack_installs(V700) 와 동형의 last-write-wins 자격 매핑 성격이다(DATA.md §3 하드 삭제 선례).
--
-- 2) slack_delivery_log — Slack 전송 멱등 dedup 로그(append-only).
--    dedup_key(예: "<issueKey>:<eventType>:<recipientUserId>") 를 PK 로 두어 같은 논리 발송의 재삽입을 거부한다.
--    워커가 큐 메시지 재처리(at-least-once 배달) 시 중복 Slack 발송을 차단하는 단일 방어선이다.
--    로그(dedup) 테이블이므로 소프트 삭제(deleted_at) 대상이 아니다(감사/로그 성격 — db-engineer 체크리스트 §5 제외군).

CREATE TABLE user_slack_mapping (
    user_id       UUID PRIMARY KEY,                    -- BTS user id (cross-BC, BC 격리로 FK 아님) — DM 대상 해석 조회 키
    slack_user_id TEXT NOT NULL,                       -- Slack 사용자 id(Uxxxx) — DM 수신 대상
    team_id       TEXT NOT NULL,                       -- Slack workspace(team) id — slack_installs 워크스페이스 축
    linked_at     TIMESTAMPTZ NOT NULL DEFAULT now()   -- 매핑 연결 시각
);

COMMENT ON TABLE  user_slack_mapping               IS '사용자↔Slack 매핑 — Slack DM 대상 해석용 조회 테이블(FR-SL-02)';
COMMENT ON COLUMN user_slack_mapping.user_id       IS 'BTS user id(cross-BC, BC 격리로 FK 아님) — PK, DM 대상 해석 조회 키';
COMMENT ON COLUMN user_slack_mapping.slack_user_id IS 'Slack 사용자 id(Uxxxx) — DM 수신 대상';
COMMENT ON COLUMN user_slack_mapping.team_id       IS 'Slack workspace(team) id';
COMMENT ON COLUMN user_slack_mapping.linked_at     IS '매핑 연결 시각';

CREATE TABLE slack_delivery_log (
    dedup_key TEXT PRIMARY KEY,                        -- 논리 발송 식별자 — 재삽입 거부로 중복 발송 차단(전송 멱등)
    sent_at   TIMESTAMPTZ NOT NULL DEFAULT now()       -- 발송 기록 시각
);

COMMENT ON TABLE  slack_delivery_log           IS 'Slack 전송 멱등 dedup 로그(append-only) — 큐 재처리 중복 발송 차단(FR-SL-02)';
COMMENT ON COLUMN slack_delivery_log.dedup_key IS '논리 발송 식별자(PK) — 재삽입 거부로 중복 Slack 발송 차단';
COMMENT ON COLUMN slack_delivery_log.sent_at   IS '발송 기록 시각';

-- q_slack_deliveries pgmq 큐 — notification BC(SlackChannelSender)가 SLACK 수신자를 이 큐로 발행하고,
-- slack-integration SlackDeliveryWorker 가 소비해 chat.postMessage 로 전송한다(ADR 2026-07-10 D1/D2).
-- 큐를 slack 모듈에 두는 이유. notification 테스트 다수가 vanilla postgres 이미지(pgmq 미포함)를 쓰므로
-- notification 마이그레이션에 pgmq 확장을 요구할 수 없다. slack 은 이 워커 때문에 어차피 pgmq 이미지가 필요해
-- 여기서 확장+큐를 생성한다(producer-creates 관례의 의도적 예외 — 테스트 인프라 비파괴 우선).
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;
SELECT pgmq.create('q_slack_deliveries');
