// 감사 로그 MSW 핸들러 단위 테스트 — 필터·페이지네이션·정렬 검증
import { server } from '@/test/server'
import { describe, it, expect } from 'vitest'
import { auditLogHandlers } from './audit-log-handlers'
import { ALICE_USER_ID, BOB_USER_ID } from './audit-log-fixtures'
import { fetchAuditLogs } from '@/api/audit-logs'

beforeEach(() => {
  server.use(...auditLogHandlers)
})

describe('auditLogHandlers — GET /api/v1/admin/auth-audit-logs', () => {
  it('무필터 조회 시 전체 22건을 반환한다', async () => {
    const result = await fetchAuditLogs({})
    expect(result.totalElements).toBe(22)
  })

  it('무필터 조회 시 created_at DESC 정렬 — 첫 항목 id=22', async () => {
    const result = await fetchAuditLogs({ page: 0, size: 50 })
    expect(result.items[0]?.id).toBe(22)
  })

  it('eventType=LOGIN_SUCCESS 필터 시 해당 이벤트만 반환한다', async () => {
    const result = await fetchAuditLogs({ eventType: 'LOGIN_SUCCESS' })
    expect(result.items.every((e) => e.eventType === 'LOGIN_SUCCESS')).toBe(true)
    expect(result.totalElements).toBeGreaterThan(0)
  })

  it('eventType=LOGIN_FAILURE 필터 시 LOGIN_FAILURE만 반환한다', async () => {
    const result = await fetchAuditLogs({ eventType: 'LOGIN_FAILURE' })
    expect(result.items.every((e) => e.eventType === 'LOGIN_FAILURE')).toBe(true)
  })

  it('userId=ALICE 필터 시 alice 이벤트만 반환한다', async () => {
    const result = await fetchAuditLogs({ userId: ALICE_USER_ID })
    expect(result.items.every((e) => e.userId === ALICE_USER_ID)).toBe(true)
    expect(result.totalElements).toBeGreaterThan(0)
  })

  it('userId=BOB 필터 시 bob 이벤트만 반환한다', async () => {
    const result = await fetchAuditLogs({ userId: BOB_USER_ID })
    expect(result.items.every((e) => e.userId === BOB_USER_ID)).toBe(true)
  })

  it('from 필터 시 해당 시각 이후 이벤트만 반환한다', async () => {
    const result = await fetchAuditLogs({ from: '2026-06-10T00:00:00.000Z' })
    expect(result.items.every((e) => e.createdAt >= '2026-06-10T00:00:00.000Z')).toBe(true)
  })

  it('to 필터 시 해당 시각 이전 이벤트만 반환한다', async () => {
    const result = await fetchAuditLogs({ to: '2026-06-09T23:59:59.999Z' })
    expect(result.items.every((e) => e.createdAt <= '2026-06-09T23:59:59.999Z')).toBe(true)
  })

  it('from/to 경계 포함 검증 — 딱 맞는 경계 이벤트가 포함된다', async () => {
    // id=1: createdAt='2026-06-09T10:00:00Z'
    const result = await fetchAuditLogs({
      from: '2026-06-09T10:00:00.000Z',
      to: '2026-06-09T10:00:00.000Z',
    })
    expect(result.items.some((e) => e.id === 1)).toBe(true)
  })

  it('페이지네이션 — size=5, page=0이면 5건 반환', async () => {
    const result = await fetchAuditLogs({ page: 0, size: 5 })
    expect(result.items).toHaveLength(5)
    expect(result.page).toBe(0)
    expect(result.size).toBe(5)
  })

  it('페이지네이션 — size=5, page=1이면 다음 5건 반환', async () => {
    const page0 = await fetchAuditLogs({ page: 0, size: 5 })
    const page1 = await fetchAuditLogs({ page: 1, size: 5 })
    const page0Ids = page0.items.map((e) => e.id)
    const page1Ids = page1.items.map((e) => e.id)
    expect(page0Ids.some((id) => page1Ids.includes(id))).toBe(false)
  })

  it('totalPages 계산이 정확하다 — size=5, total=22 → totalPages=5', async () => {
    const result = await fetchAuditLogs({ page: 0, size: 5 })
    expect(result.totalPages).toBe(Math.ceil(result.totalElements / 5))
  })

  it('복합 필터 — eventType+userId AND 결합', async () => {
    const result = await fetchAuditLogs({ eventType: 'LOGIN_SUCCESS', userId: ALICE_USER_ID })
    expect(result.items.every((e) => e.eventType === 'LOGIN_SUCCESS' && e.userId === ALICE_USER_ID)).toBe(true)
  })

  it('username/displayName이 fixture와 일치 — alice는 displayName 있음', async () => {
    const result = await fetchAuditLogs({ userId: ALICE_USER_ID, size: 1 })
    const entry = result.items[0]
    expect(entry?.username).toBe('alice')
    expect(entry?.displayName).toBe('김앨리스')
  })

  it('userId=null 이벤트가 포함 — username/displayName null', async () => {
    const result = await fetchAuditLogs({ eventType: 'LOGIN_FAILURE' })
    const nullEntry = result.items.find((e) => e.userId === null)
    expect(nullEntry).toBeDefined()
    expect(nullEntry?.username).toBeNull()
    expect(nullEntry?.displayName).toBeNull()
  })

  it('빈 결과 — 존재하지 않는 eventType 필터 시 items:[]', async () => {
    const result = await fetchAuditLogs({ eventType: 'UNKNOWN_EVENT_TYPE_NEVER_EXISTS' })
    expect(result.items).toHaveLength(0)
    expect(result.totalElements).toBe(0)
  })

  it('응답 shape 검증 — items/page/size/totalElements/totalPages 존재', async () => {
    const result = await fetchAuditLogs({})
    expect(result).toHaveProperty('items')
    expect(result).toHaveProperty('page')
    expect(result).toHaveProperty('size')
    expect(result).toHaveProperty('totalElements')
    expect(result).toHaveProperty('totalPages')
  })
})
