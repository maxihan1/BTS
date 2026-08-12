// 개인 알림 보관함(Inbox) MSW 핸들러 stateful 동작 단위 테스트 (FR-UX-03 D6)
import { server } from '@/test/server'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { inboxHandlers, resetInboxStore, seedInbox } from '../inbox-handlers'
import { mockAccessToken } from '../auth-fixtures'
import type { InboxItem } from '@/api/inbox'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 전용 MSW 서버 (handlers.ts 공유 서버와 독립)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...inboxHandlers)
})
afterEach(() => {
  resetInboxStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 시나리오별 항목
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_TOKEN = mockAccessToken('alice')
const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const ACTOR_BOB_ID = '00000000-0000-4000-8000-000000000002'

function authHeaders(token: string): HeadersInit {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

function inboxUrl(suffix = ''): string {
  return `/api/v1/users/me/inbox${suffix}`
}

/** 미읽음 + 미보관 항목 */
const UNREAD_ITEM: InboxItem = {
  id: '10000000-0000-4000-8000-000000000001',
  eventType: 'ISSUE_ASSIGNED',
  issueKey: 'ATLAS-1',
  title: '이슈 담당자로 지정됨',
  body: '이슈 ATLAS-1의 담당자로 지정되었습니다.',
  actorUserId: ACTOR_BOB_ID,
  readAt: null,
  archivedAt: null,
  createdAt: '2026-06-25T10:00:00Z',
}

/** 읽음 + 미보관 항목 */
const READ_ITEM: InboxItem = {
  id: '10000000-0000-4000-8000-000000000002',
  eventType: 'ISSUE_COMMENTED',
  issueKey: 'ATLAS-2',
  title: '코멘트가 추가됨',
  body: null,
  actorUserId: ACTOR_BOB_ID,
  readAt: '2026-06-24T09:00:00Z',
  archivedAt: null,
  createdAt: '2026-06-24T08:00:00Z',
}

/** 보관됨 항목 */
const ARCHIVED_ITEM: InboxItem = {
  id: '10000000-0000-4000-8000-000000000003',
  eventType: 'ISSUE_MENTIONED',
  issueKey: null,
  title: '멘션됨',
  body: '코멘트에서 멘션되었습니다.',
  actorUserId: null,
  readAt: '2026-06-23T10:00:00Z',
  archivedAt: '2026-06-23T11:00:00Z',
  createdAt: '2026-06-23T09:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/inbox — 탭 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/inbox — 탭 필터', () => {
  beforeEach(() => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM, ARCHIVED_ITEM])
  })

  it('tab=ALL 이면 미보관 항목만 반환한다 (보관됨 제외)', async () => {
    const res = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { content: InboxItem[]; totalElements: number }
    expect(body.content).toHaveLength(2)
    expect(body.content.every((i) => i.archivedAt === null)).toBe(true)
  })

  it('tab 파라미터 없으면 ALL과 동일하게 미보관 항목만 반환한다', async () => {
    const res = await fetch(inboxUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { content: InboxItem[]; totalElements: number }
    expect(body.content).toHaveLength(2)
  })

  it('tab=UNREAD 이면 미읽음+미보관 항목만 반환한다', async () => {
    const res = await fetch(inboxUrl('?tab=UNREAD'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content).toHaveLength(1)
    expect(body.content[0]?.id).toBe(UNREAD_ITEM.id)
  })

  it('tab=ARCHIVED 이면 보관된 항목만 반환한다', async () => {
    const res = await fetch(inboxUrl('?tab=ARCHIVED'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content).toHaveLength(1)
    expect(body.content[0]?.id).toBe(ARCHIVED_ITEM.id)
  })

  it('정렬은 createdAt DESC — 최근 항목이 먼저 온다', async () => {
    const res = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    const dates = body.content.map((i) => i.createdAt)
    const sorted = [...dates].sort((a, b) => b.localeCompare(a))
    expect(dates).toEqual(sorted)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/inbox — 검색 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/inbox — 검색 필터', () => {
  beforeEach(() => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM, ARCHIVED_ITEM])
  })

  it('q 파라미터로 title 부분일치 필터링한다', async () => {
    const res = await fetch(inboxUrl('?q=담당자'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content).toHaveLength(1)
    expect(body.content[0]?.id).toBe(UNREAD_ITEM.id)
  })

  it('senderId 파라미터로 actorUserId 일치 필터링한다', async () => {
    const res = await fetch(inboxUrl(`?senderId=${ACTOR_BOB_ID}`), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    // UNREAD_ITEM, READ_ITEM만 actorUserId가 ACTOR_BOB_ID
    expect(body.content.every((i) => i.actorUserId === ACTOR_BOB_ID)).toBe(true)
    expect(body.content.length).toBeGreaterThan(0)
  })

  it('issueKey 파라미터로 issueKey 일치 필터링한다', async () => {
    const res = await fetch(inboxUrl('?issueKey=ATLAS-1'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content).toHaveLength(1)
    expect(body.content[0]?.id).toBe(UNREAD_ITEM.id)
  })

  it('from 파라미터로 createdAt 이후 항목만 반환한다', async () => {
    const res = await fetch(inboxUrl('?from=2026-06-24T00:00:00Z'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    // 2026-06-24 이후: UNREAD_ITEM(06-25), READ_ITEM(06-24) — ARCHIVED는 tab=ALL에서도 제외됨
    expect(body.content.every((i) => i.createdAt >= '2026-06-24T00:00:00Z')).toBe(true)
  })

  it('to 파라미터로 createdAt 이전 항목만 반환한다', async () => {
    const res = await fetch(inboxUrl('?to=2026-06-24T23:59:59Z'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content.every((i) => i.createdAt <= '2026-06-24T23:59:59Z')).toBe(true)
    expect(body.content.length).toBeGreaterThan(0)
  })

  it('from ISO Instant(T00:00:00.000Z) 형식으로 날짜 기준 필터가 적용된다', async () => {
    // 2026-06-25T00:00:00.000Z 이후 — UNREAD_ITEM(06-25T10:00:00Z)만 해당
    const res = await fetch(inboxUrl('?from=2026-06-25T00:00:00.000Z'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content.length).toBe(1)
    expect(body.content[0]?.id).toBe(UNREAD_ITEM.id)
  })

  it('to ISO Instant(T23:59:59.999Z) 형식으로 날짜 기준 필터가 적용된다', async () => {
    // 2026-06-24T23:59:59.999Z 이전 — READ_ITEM(06-24T08:00:00Z)만 tab=ALL 대상
    const res = await fetch(inboxUrl('?to=2026-06-24T23:59:59.999Z'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[] }
    expect(body.content.every((i) => new Date(i.createdAt) <= new Date('2026-06-24T23:59:59.999Z'))).toBe(true)
    expect(body.content.length).toBeGreaterThan(0)
  })

  it('bare date(2026-06-25)를 from에 전달하면 ISO Instant와 결과가 다를 수 있다 — ISO Instant 형식이 정석이다', async () => {
    // bare date는 lexical 비교라 '2026-06-25T10:00:00Z' >= '2026-06-25' 가 우연히 통과하지만,
    // '2026-06-24T08:00:00Z' >= '2026-06-25' 는 false — 이 케이스는 from ISO로 받아야 정확
    // 이 테스트는 ISO Instant 형식의 from이 올바른 Date 비교를 사용함을 문서화한다
    const isoRes = await fetch(inboxUrl('?from=2026-06-25T00:00:00.000Z'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const isoBody = (await isoRes.json()) as { content: InboxItem[] }
    // ISO Instant 비교: Date 파싱 기반, 2026-06-25T10:00:00Z >= 2026-06-25T00:00:00.000Z → 1건
    expect(isoBody.content.length).toBe(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/inbox — 페이지네이션
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/inbox — 페이지네이션', () => {
  it('size=2, page=0이면 첫 2건과 totalElements를 반환한다', async () => {
    // 5건 시드
    const items: InboxItem[] = Array.from({ length: 5 }, (_, i) => ({
      id: `20000000-0000-4000-8000-00000000000${i + 1}`,
      eventType: 'ISSUE_ASSIGNED',
      issueKey: `ATLAS-${i + 1}`,
      title: `알림 ${i + 1}`,
      body: null,
      actorUserId: null,
      readAt: null,
      archivedAt: null,
      createdAt: `2026-06-${String(25 - i).padStart(2, '0')}T10:00:00Z`,
    }))
    seedInbox(ALICE_ID, items)

    const res = await fetch(inboxUrl('?page=0&size=2'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as {
      content: InboxItem[]
      totalElements: number
      totalPages: number
      number: number
      size: number
    }
    expect(res.status).toBe(200)
    expect(body.content).toHaveLength(2)
    expect(body.totalElements).toBe(5)
    expect(body.totalPages).toBe(3)
    expect(body.number).toBe(0)
    expect(body.size).toBe(2)
  })

  it('page=1, size=2이면 3~4번째 항목을 반환한다', async () => {
    const items: InboxItem[] = Array.from({ length: 5 }, (_, i) => ({
      id: `30000000-0000-4000-8000-00000000000${i + 1}`,
      eventType: 'ISSUE_ASSIGNED',
      issueKey: null,
      title: `항목 ${i + 1}`,
      body: null,
      actorUserId: null,
      readAt: null,
      archivedAt: null,
      createdAt: `2026-06-${String(25 - i).padStart(2, '0')}T10:00:00Z`,
    }))
    seedInbox(ALICE_ID, items)

    const res = await fetch(inboxUrl('?page=1&size=2'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[]; number: number }
    expect(body.content).toHaveLength(2)
    expect(body.number).toBe(1)
  })

  it('기본 size는 20이다', async () => {
    const items: InboxItem[] = Array.from({ length: 25 }, (_, i) => ({
      id: `40000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
      eventType: 'ISSUE_ASSIGNED',
      issueKey: null,
      title: `알림 ${i + 1}`,
      body: null,
      actorUserId: null,
      readAt: null,
      archivedAt: null,
      createdAt: '2026-06-25T10:00:00Z',
    }))
    seedInbox(ALICE_ID, items)

    const res = await fetch(inboxUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { content: InboxItem[]; size: number }
    expect(body.content).toHaveLength(20)
    expect(body.size).toBe(20)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/inbox/unread-count
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/inbox/unread-count', () => {
  it('미읽음+미보관 항목 수를 반환한다', async () => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM, ARCHIVED_ITEM])

    const res = await fetch(inboxUrl('/unread-count'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { count: number } }
    expect(body.data.count).toBe(1)
  })

  it('빈 inbox면 count=0을 반환한다', async () => {
    const res = await fetch(inboxUrl('/unread-count'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { count: number } }
    expect(body.data.count).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /{id}/read — stateful 읽음 상태 변경
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /{id}/read — stateful', () => {
  beforeEach(() => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM])
  })

  it('read=true 로 PATCH 하면 204를 반환하고 재조회 시 readAt이 설정된다', async () => {
    const res = await fetch(inboxUrl(`/${UNREAD_ITEM.id}/read`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ read: true }),
    })
    expect(res.status).toBe(204)

    // 재조회 — readAt이 채워져야 한다
    const listRes = await fetch(inboxUrl('?tab=UNREAD'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await listRes.json()) as { content: InboxItem[] }
    // UNREAD 탭에서 사라져야 한다 (readAt이 설정되었으므로)
    expect(body.content.find((i) => i.id === UNREAD_ITEM.id)).toBeUndefined()
  })

  it('read=false 로 PATCH 하면 readAt이 null로 돌아온다', async () => {
    // READ_ITEM은 이미 readAt이 있음
    const res = await fetch(inboxUrl(`/${READ_ITEM.id}/read`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ read: false }),
    })
    expect(res.status).toBe(204)

    // 재조회 — UNREAD 탭에 나타나야 한다
    const listRes = await fetch(inboxUrl('?tab=UNREAD'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await listRes.json()) as { content: InboxItem[] }
    expect(body.content.find((i) => i.id === READ_ITEM.id)).toBeDefined()
  })

  it('이미 readAt이 있는 항목에 read=true 재적용 시 기존 readAt을 보존한다 (COALESCE 모사)', async () => {
    // 먼저 읽음 처리
    await fetch(inboxUrl(`/${UNREAD_ITEM.id}/read`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ read: true }),
    })

    // 목록에서 readAt 첫 값 확인
    const listRes1 = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body1 = (await listRes1.json()) as { content: InboxItem[] }
    const firstReadAt = body1.content.find((i) => i.id === UNREAD_ITEM.id)?.readAt

    // 다시 read=true PATCH — 최초 시각이 보존되어야 한다
    await fetch(inboxUrl(`/${UNREAD_ITEM.id}/read`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ read: true }),
    })

    const listRes2 = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body2 = (await listRes2.json()) as { content: InboxItem[] }
    const secondReadAt = body2.content.find((i) => i.id === UNREAD_ITEM.id)?.readAt

    expect(firstReadAt).not.toBeNull()
    expect(secondReadAt).toBe(firstReadAt)
  })

  it('존재하지 않는 id에 PATCH 하면 404를 반환한다', async () => {
    const res = await fetch(inboxUrl('/99999999-0000-4000-8000-000000000099/read'), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ read: true }),
    })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /{id}/archive — stateful 보관 상태 변경
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /{id}/archive — stateful', () => {
  beforeEach(() => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM, ARCHIVED_ITEM])
  })

  it('archive=true 로 PATCH 하면 204를 반환하고 ALL 탭에서 사라진다', async () => {
    const res = await fetch(inboxUrl(`/${UNREAD_ITEM.id}/archive`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ archived: true }),
    })
    expect(res.status).toBe(204)

    const listRes = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await listRes.json()) as { content: InboxItem[] }
    expect(body.content.find((i) => i.id === UNREAD_ITEM.id)).toBeUndefined()
  })

  it('archive=true 후 ARCHIVED 탭에 나타난다', async () => {
    await fetch(inboxUrl(`/${UNREAD_ITEM.id}/archive`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ archived: true }),
    })

    const listRes = await fetch(inboxUrl('?tab=ARCHIVED'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await listRes.json()) as { content: InboxItem[] }
    expect(body.content.find((i) => i.id === UNREAD_ITEM.id)).toBeDefined()
  })

  it('archive=false 로 PATCH 하면 보관 해제되어 ALL 탭에 다시 나타난다', async () => {
    const res = await fetch(inboxUrl(`/${ARCHIVED_ITEM.id}/archive`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ archived: false }),
    })
    expect(res.status).toBe(204)

    const listRes = await fetch(inboxUrl('?tab=ALL'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await listRes.json()) as { content: InboxItem[] }
    expect(body.content.find((i) => i.id === ARCHIVED_ITEM.id)).toBeDefined()
  })

  it('이미 archivedAt이 있는 항목에 archive=true 재적용 시 기존 archivedAt 보존 (COALESCE 모사)', async () => {
    const listRes1 = await fetch(inboxUrl('?tab=ARCHIVED'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body1 = (await listRes1.json()) as { content: InboxItem[] }
    const firstArchivedAt = body1.content.find((i) => i.id === ARCHIVED_ITEM.id)?.archivedAt

    await fetch(inboxUrl(`/${ARCHIVED_ITEM.id}/archive`), {
      method: 'PATCH',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ archived: true }),
    })

    const listRes2 = await fetch(inboxUrl('?tab=ARCHIVED'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body2 = (await listRes2.json()) as { content: InboxItem[] }
    const secondArchivedAt = body2.content.find((i) => i.id === ARCHIVED_ITEM.id)?.archivedAt

    expect(firstArchivedAt).not.toBeNull()
    expect(secondArchivedAt).toBe(firstArchivedAt)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /read-all — 일괄 읽음 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /read-all — 일괄 읽음', () => {
  beforeEach(() => {
    seedInbox(ALICE_ID, [UNREAD_ITEM, READ_ITEM, ARCHIVED_ITEM])
  })

  it('ids 없으면 미읽음+미보관 항목 전체를 읽음 처리하고 updated 카운트를 반환한다', async () => {
    const res = await fetch(inboxUrl('/read-all'), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({}),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { updated: number } }
    expect(body.data.updated).toBe(1) // UNREAD_ITEM만 미읽음

    // 재조회 — 미읽음 0건
    const countRes = await fetch(inboxUrl('/unread-count'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const countBody = (await countRes.json()) as { data: { count: number } }
    expect(countBody.data.count).toBe(0)
  })

  it('ids 지정 시 해당 항목만 읽음 처리한다', async () => {
    const res = await fetch(inboxUrl('/read-all'), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ ids: [UNREAD_ITEM.id] }),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { updated: number } }
    expect(body.data.updated).toBe(1)
  })

  it('이미 모두 읽음이면 updated=0을 반환한다', async () => {
    // 먼저 전체 읽음
    await fetch(inboxUrl('/read-all'), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({}),
    })

    // 다시 read-all
    const res = await fetch(inboxUrl('/read-all'), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({}),
    })
    const body = (await res.json()) as { data: { updated: number } }
    expect(body.data.updated).toBe(0)
  })

  it('보관된 항목은 read-all에서 제외된다', async () => {
    // ARCHIVED_ITEM은 readAt이 있지만 archived도 있음 — read-all 대상 아님
    // 새로운 미읽음+보관 항목 추가
    const UNREAD_ARCHIVED: InboxItem = {
      id: '50000000-0000-4000-8000-000000000001',
      eventType: 'ISSUE_ASSIGNED',
      issueKey: null,
      title: '미읽음 보관됨',
      body: null,
      actorUserId: null,
      readAt: null,
      archivedAt: '2026-06-25T12:00:00Z',
      createdAt: '2026-06-25T11:00:00Z',
    }
    resetInboxStore()
    seedInbox(ALICE_ID, [UNREAD_ITEM, UNREAD_ARCHIVED])

    const res = await fetch(inboxUrl('/read-all'), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({}),
    })
    const body = (await res.json()) as { data: { updated: number } }
    // UNREAD_ITEM만 처리됨 (UNREAD_ARCHIVED는 보관됨 제외)
    expect(body.data.updated).toBe(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 인증 오류 — 미인증 시 401
// ─────────────────────────────────────────────────────────────────────────────

describe('인증 오류 — 401', () => {
  it('Authorization 헤더 없이 GET 하면 401을 반환한다', async () => {
    const res = await fetch(inboxUrl())
    expect(res.status).toBe(401)
  })

  it('Authorization 헤더 없이 unread-count를 GET 하면 401을 반환한다', async () => {
    const res = await fetch(inboxUrl('/unread-count'))
    expect(res.status).toBe(401)
  })
})
