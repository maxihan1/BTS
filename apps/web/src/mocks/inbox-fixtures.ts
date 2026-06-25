// 개인 알림 보관함(Inbox) MSW 테스트 픽스처 — 다양한 시나리오 커버 (FR-UX-03 D6)
import type { InboxItem } from '@/api/inbox'

// ─────────────────────────────────────────────────────────────────────────────
// 발신자 UUID 상수
// ─────────────────────────────────────────────────────────────────────────────

/** alice의 userId — auth-fixtures.aliceUser.userId와 일치 (RFC4122 v4) */
export const INBOX_FIXTURE_ALICE_ID = '00000000-0000-4000-8000-000000000001'

/** bob의 userId — auth-fixtures.bobUser.userId와 일치 (RFC4122 v4) */
export const INBOX_FIXTURE_BOB_ID = '00000000-0000-4000-8000-000000000002'

/** 발신자 bob (actorUserId가 있는 시나리오용) */
export const INBOX_FIXTURE_BOB_ACTOR_ID = '00000000-0000-4000-8000-000000000002'

// ─────────────────────────────────────────────────────────────────────────────
// 단건 픽스처 — 개별 시나리오 검증용
// ─────────────────────────────────────────────────────────────────────────────

/** 미읽음 + 미보관 + actorUserId 있음 + issueKey 있음 */
export const inboxFixtureUnread: InboxItem = {
  id: 'f0000000-0000-4000-8000-000000000001',
  eventType: 'ISSUE_ASSIGNED',
  issueKey: 'ATLAS-1',
  title: '이슈 담당자로 지정됨',
  body: 'ATLAS-1 이슈의 담당자로 지정되었습니다.',
  actorUserId: INBOX_FIXTURE_BOB_ACTOR_ID,
  readAt: null,
  archivedAt: null,
  createdAt: '2026-06-25T10:00:00Z',
}

/** 읽음 + 미보관 + actorUserId 있음 + issueKey 있음 */
export const inboxFixtureRead: InboxItem = {
  id: 'f0000000-0000-4000-8000-000000000002',
  eventType: 'ISSUE_COMMENTED',
  issueKey: 'ATLAS-2',
  title: '코멘트가 추가됨',
  body: null,
  actorUserId: INBOX_FIXTURE_BOB_ACTOR_ID,
  readAt: '2026-06-24T09:00:00Z',
  archivedAt: null,
  createdAt: '2026-06-24T08:00:00Z',
}

/** 읽음 + 보관됨 + actorUserId null (시스템) + issueKey null */
export const inboxFixtureArchived: InboxItem = {
  id: 'f0000000-0000-4000-8000-000000000003',
  eventType: 'SYSTEM_NOTICE',
  issueKey: null,
  title: '시스템 공지',
  body: '서비스 점검 예정입니다.',
  actorUserId: null,
  readAt: '2026-06-23T10:00:00Z',
  archivedAt: '2026-06-23T11:00:00Z',
  createdAt: '2026-06-23T09:00:00Z',
}

/** 미읽음 + 미보관 + actorUserId null (시스템) + issueKey 있음 */
export const inboxFixtureUnreadSystem: InboxItem = {
  id: 'f0000000-0000-4000-8000-000000000004',
  eventType: 'ISSUE_STATUS_CHANGED',
  issueKey: 'ATLAS-3',
  title: 'ATLAS-3 상태가 변경됨',
  body: null,
  actorUserId: null,
  readAt: null,
  archivedAt: null,
  createdAt: '2026-06-25T09:00:00Z',
}

/** 미읽음 + 보관됨 (보관 중에도 미읽음 가능 — 2축 독립) */
export const inboxFixtureUnreadArchived: InboxItem = {
  id: 'f0000000-0000-4000-8000-000000000005',
  eventType: 'ISSUE_MENTIONED',
  issueKey: 'ATLAS-4',
  title: 'ATLAS-4 코멘트에서 멘션됨',
  body: '멘션 내용입니다.',
  actorUserId: INBOX_FIXTURE_BOB_ACTOR_ID,
  readAt: null,
  archivedAt: '2026-06-25T08:00:00Z',
  createdAt: '2026-06-25T07:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 테스트용 — 20건+ 대량 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 페이지네이션 테스트용 미보관 항목 25건 (기본 size=20 초과 검증용).
 * 모두 미읽음 + 미보관. createdAt은 역순 (최신→구형).
 */
export const inboxFixturePageItems: InboxItem[] = Array.from({ length: 25 }, (_, idx) => {
  const index = idx + 1
  // 날짜를 역순으로 생성해 createdAt DESC 정렬 검증에 활용
  const day = String(25 - idx).padStart(2, '0')
  // idx 24 → day = "01", idx 0 → day = "25"
  const month = idx < 25 ? '06' : '05'
  return {
    id: `e${String(index).padStart(7, '0')}-0000-4000-8000-000000000000`,
    eventType: 'ISSUE_ASSIGNED',
    issueKey: `ATLAS-${100 + index}`,
    title: `페이지네이션 테스트 알림 ${index}`,
    body: null,
    actorUserId: index % 2 === 0 ? INBOX_FIXTURE_BOB_ACTOR_ID : null,
    readAt: null,
    archivedAt: null,
    createdAt: `2026-${month}-${day}T10:00:00Z`,
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 표준 시드 셋 — 일반 시나리오용 (alice 기본 시드)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice의 기본 inbox 시드 셋.
 * - 미읽음 2건 (actorUserId 있음/없음)
 * - 읽음 1건
 * - 보관됨 1건
 * - 미읽음+보관됨 1건
 */
export const defaultInboxFixtures: InboxItem[] = [
  inboxFixtureUnread,
  inboxFixtureRead,
  inboxFixtureArchived,
  inboxFixtureUnreadSystem,
  inboxFixtureUnreadArchived,
]
