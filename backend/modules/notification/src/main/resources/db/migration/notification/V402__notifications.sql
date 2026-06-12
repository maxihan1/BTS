-- 알림 발송 기록 테이블 (인앱/이메일/Webhook 채널 공통, FR-NT-02 V402)

-- ── notifications ─────────────────────────────────────────────────────────────
-- 알림 Aggregate. 평가 엔진(NotificationPolicyEvaluator)의 출력을 수신자×채널로 fanout 한 뒤,
-- 렌더링한 단건 알림을 기록한다. 이번 PR 은 IN_APP 채널만 기록하나 channel 컬럼은 일반(이메일/Webhook 공통).
-- DATA.md §3: notifications 는 하드 삭제 허용 영역이라 deleted_at(소프트 삭제) 컬럼을 두지 않는다.
-- read_at 만 두어 FR-UX-03 Inbox(읽음 처리)의 데이터 기반이 된다(이번 PR 은 항상 NULL 생성).
CREATE TABLE notifications (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id UUID         NOT NULL,
    event_type        TEXT         NOT NULL,
    channel           TEXT         NOT NULL,
    issue_key         TEXT,
    title             TEXT         NOT NULL,
    body              TEXT,
    payload           JSONB,
    status            TEXT         NOT NULL,
    dedup_key         TEXT         NOT NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- dedup_key 는 NOT NULL 이므로 NULLS NOT DISTINCT 불필요. 멱등 발송(중복 알림 방지)의 진실 출처.
    CONSTRAINT uq_notifications_dedup_key UNIQUE (dedup_key)
);

COMMENT ON TABLE  notifications                   IS '알림 발송 기록 — 평가 엔진 출력을 수신자×채널로 fanout 한 단건 알림';
COMMENT ON COLUMN notifications.recipient_user_id IS '수신자 사용자 ID (identity-access users.id)';
COMMENT ON COLUMN notifications.event_type        IS '이벤트 유형 wireValue (예: issue.created) — NotificationEventType';
COMMENT ON COLUMN notifications.channel           IS '발송 채널 name (예: IN_APP, EMAIL, WEBHOOK) — Channel';
COMMENT ON COLUMN notifications.issue_key         IS '소스 이슈 키 (예: ATLAS-123). 이슈 비연관 알림은 NULL';
COMMENT ON COLUMN notifications.title             IS '렌더링된 알림 제목';
COMMENT ON COLUMN notifications.body              IS '렌더링된 알림 본문 (선택)';
COMMENT ON COLUMN notifications.payload           IS '원본 이벤트 컨텍스트 JSONB (Inbox 딥링크/재렌더링용)';
COMMENT ON COLUMN notifications.status            IS '발송 상태 — PENDING / SENT / FAILED';
COMMENT ON COLUMN notifications.dedup_key         IS '멱등 키 — (event+issue+occurredAt+recipient+channel) hash. 중복 알림 차단';
COMMENT ON COLUMN notifications.read_at           IS '읽은 시각 (NULL=미읽음). FR-UX-03 Inbox 읽음 처리용';

-- 수신자별 최신순 조회 인덱스 — Inbox(FR-UX-03) 및 미읽음 카운트 경로 최적화
CREATE INDEX ix_notifications_recipient
    ON notifications (recipient_user_id, created_at DESC);
