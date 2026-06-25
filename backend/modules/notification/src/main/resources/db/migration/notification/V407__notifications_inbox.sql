-- notifications 테이블에 Inbox(보관함) 확장 — 보관 시각(archived_at) + 발신자(actor_user_id) 컬럼 + 미읽음 부분 인덱스 추가 (FR-UX-03)

-- ── notifications 확장 (FR-UX-03 Inbox) ──────────────────────────────────────
-- FR-NT-02(V402)에서 생성한 수신자별 fanout 알림에 read/archive 2축 상태와 발신자 검색 토대를 더한다.
-- 둘 다 NULL 허용 — 기존 행은 backfill 없이 NULL 로 graceful(소급 갱신 없음). 신규 알림부터 worker 가 actor_user_id 채움.
ALTER TABLE notifications ADD COLUMN archived_at   TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN actor_user_id UUID;

COMMENT ON COLUMN notifications.archived_at   IS '보관 시각 (NULL=미보관). FR-UX-03 Inbox 보관함';
COMMENT ON COLUMN notifications.actor_user_id IS '알림 발신자 userId (NULL=시스템/없음). FR-UX-03 발신자 검색';

-- 미읽음 카운트/안읽음 탭 전용 부분 인덱스 — 미읽음(read_at NULL) + 미보관(archived_at NULL) + IN_APP 채널만 색인.
-- channel='IN_APP' 조건을 포함해 countUnread(IN_APP 한정) 경로를 완전 커버한다(CONCERN-4).
-- notifications 는 신생/1K 규모라 일반 CREATE INDEX 로 충분하다(CONCURRENTLY 는 Flyway 트랜잭션과 충돌하므로 미사용).
CREATE INDEX ix_notifications_recipient_unread
    ON notifications (recipient_user_id)
    WHERE read_at IS NULL AND archived_at IS NULL AND channel = 'IN_APP';
