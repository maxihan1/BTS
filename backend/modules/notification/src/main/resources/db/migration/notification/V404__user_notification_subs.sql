-- 사용자별 알림 구독(이벤트×채널 opt-out) 저장 테이블 (FR-NT-04 V404)

-- ── user_notification_subs ────────────────────────────────────────────────────
-- 사용자별 알림 구독 설정. (user_id, event_type, channel) 단위로 enabled 를 저장한다.
-- 기본 정책은 opt-out — 행이 없으면 기본 발송이고, enabled=false 행이 있으면 그 조합을 끈다.
-- user_id 는 identity-access users.id 의 UUID 를 직접 저장한다 (FK 없음).
-- notification BC 는 BC 격리상 users 테이블을 참조할 수 없어 FK 를 걸지 않는다
-- (ADR 2026-06-19, Notification.recipient_user_id 선례 — V402).
CREATE TABLE user_notification_subs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    event_type  TEXT         NOT NULL,
    channel     TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- (user_id, event_type, channel) 조합은 사용자당 단 하나 — upsert(ON CONFLICT)의 충돌 키.
    -- 세 컬럼 모두 NOT NULL 이므로 NULLS NOT DISTINCT 불필요.
    CONSTRAINT uq_user_notif_subs UNIQUE (user_id, event_type, channel)
);

COMMENT ON TABLE  user_notification_subs            IS '사용자별 알림 구독 설정 — (사용자×이벤트×채널) opt-out 저장';
COMMENT ON COLUMN user_notification_subs.user_id    IS '구독자 사용자 ID (identity-access users.id). FK 없음 — BC 격리';
COMMENT ON COLUMN user_notification_subs.event_type IS '이벤트 유형 wireValue (예: issue.created) — NotificationEventType';
COMMENT ON COLUMN user_notification_subs.channel    IS '알림 채널 name (예: IN_APP, EMAIL) — 구성 가능 채널만 저장';
COMMENT ON COLUMN user_notification_subs.enabled    IS '구독 활성 여부 — FALSE 이면 해당 (이벤트×채널) 발송 안 함(opt-out)';

-- 워커가 발송 직전 opt-out(꺼진 구독)을 배치 조회하는 경로 최적화 — enabled=false 행만 인덱싱(부분 인덱스).
-- 컬럼 순서 (event_type, channel, user_id): 워커는 이벤트/채널을 먼저 알고 수신자 userId 집합을 거른다.
CREATE INDEX idx_user_notif_subs_disabled
    ON user_notification_subs (event_type, channel, user_id)
    WHERE enabled = false;
