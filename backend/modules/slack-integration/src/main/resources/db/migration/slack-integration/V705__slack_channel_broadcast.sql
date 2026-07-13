-- slack-integration BC 채널 브로드캐스트 — q_slack_channel_broadcasts 큐 + slack_channel_broadcast_log 테이블 (FR-SL-06 PR-B)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). V704 다음 = V705.
--
-- FR-SL-06 PR-B 프로젝트 활동 피드 라우팅. notification BC 가 이벤트당 브로드캐스트 메시지를
-- q_slack_channel_broadcasts 로 발행하고, slack-integration 채널 워커가 소비해 매핑된 채널에 게시한다.
--
-- 1) q_slack_channel_broadcasts — pgmq 큐(notification 발행 → slack 채널 워커 소비).
--    V701(q_slack_deliveries)과 동일하게 slack 모듈에서 확장+큐를 생성한다 — notification 테스트 다수가
--    vanilla postgres 이미지(pgmq 미포함)라 notification 마이그레이션에 pgmq 확장을 요구할 수 없고,
--    slack 은 이 워커 때문에 어차피 pgmq 이미지가 필요하다(producer-creates 관례의 의도적 예외, V701 선례).
--
-- 2) slack_channel_broadcast_log — 채널 브로드캐스트 dedup 로그(append-only).
--    dedup_key(이벤트레벨 해시 + channel_id) 를 PK 로 두어 같은 (이벤트, 채널) 조합의 재삽입을 거부한다.
--    pgmq 는 at-least-once 배달이라 큐 메시지가 재전달될 수 있는데, 워커가 게시 전 이 로그로 중복을 차단해
--    같은 이벤트가 같은 채널에 두 번 게시되지 않게 하는 단일 방어선이다(slack_delivery_log[V701]와 동형).
--    로그(dedup) 테이블이므로 소프트 삭제(deleted_at) 대상이 아니다(감사/로그 성격 — DATA.md §1.2 예외군).

CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;
SELECT pgmq.create('q_slack_channel_broadcasts');

CREATE TABLE slack_channel_broadcast_log (
    dedup_key TEXT PRIMARY KEY,                        -- 이벤트레벨 해시 + channel_id — 재삽입 거부로 (이벤트,채널) 중복 게시 차단
    posted_at TIMESTAMPTZ NOT NULL DEFAULT now()       -- 채널 게시 기록 시각(TIMESTAMP without tz 금지, DATA.md §4)
);

COMMENT ON TABLE  slack_channel_broadcast_log           IS 'Slack 채널 브로드캐스트 dedup 로그(append-only) — 큐 재처리 중복 게시 차단(FR-SL-06 PR-B)';
COMMENT ON COLUMN slack_channel_broadcast_log.dedup_key IS '이벤트레벨 해시 + channel_id(PK) — 재삽입 거부로 (이벤트,채널) 중복 게시 차단';
COMMENT ON COLUMN slack_channel_broadcast_log.posted_at IS '채널 게시 기록 시각';
