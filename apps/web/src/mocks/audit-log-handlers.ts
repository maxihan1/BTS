// 감사 로그 MSW mock 핸들러 — GET /api/v1/admin/auth-audit-logs (필터·페이지네이션·정렬)
import { http, HttpResponse } from 'msw'
import { auditLogFixtures } from './audit-log-fixtures'
import type { AuditLogEntry } from '@/api/audit-logs'

// ─────────────────────────────────────────────────────────────────────────────
// 필터 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * fixture 목록에 필터 조건을 적용한다. 백엔드 AND 결합과 동일.
 * - eventType: 정확 일치
 * - userId: 정확 일치 (null 이벤트는 제외됨)
 * - from: createdAt >= from (경계 포함)
 * - to: createdAt <= to (경계 포함)
 */
function applyFilters(
  entries: AuditLogEntry[],
  params: {
    eventType?: string | null
    userId?: string | null
    from?: string | null
    to?: string | null
  },
): AuditLogEntry[] {
  const fromMs = params.from ? new Date(params.from).getTime() : null
  const toMs = params.to ? new Date(params.to).getTime() : null

  return entries.filter((entry) => {
    if (params.eventType !== null && params.eventType !== undefined && params.eventType !== '') {
      if (entry.eventType !== params.eventType) return false
    }
    if (params.userId !== null && params.userId !== undefined && params.userId !== '') {
      if (entry.userId !== params.userId) return false
    }
    const entryMs = new Date(entry.createdAt).getTime()
    if (fromMs !== null && entryMs < fromMs) return false
    if (toMs !== null && entryMs > toMs) return false
    return true
  })
}

/**
 * 페이지 계산 헬퍼.
 * @param totalElements 전체 항목 수
 * @param size 페이지 크기
 */
function calcTotalPages(totalElements: number, size: number): number {
  if (size <= 0) return 0
  return Math.ceil(totalElements / size)
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/admin/auth-audit-logs
 *
 * 백엔드 동일 로직:
 * - 필터: eventType/userId/from/to (AND 결합)
 * - 정렬: created_at DESC (id DESC tiebreaker)
 * - 페이지네이션: page(0-base)/size
 * - 응답: { items, page, size, totalElements, totalPages }
 *
 * 조회 전용 — store 변이 없음 (stateful 불요).
 */
const listAuditLogsHandler = http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
  const url = new URL(request.url)

  const eventType = url.searchParams.get('eventType')
  const userId = url.searchParams.get('userId')
  const from = url.searchParams.get('from')
  const to = url.searchParams.get('to')
  const page = parseInt(url.searchParams.get('page') ?? '0', 10)
  const size = parseInt(url.searchParams.get('size') ?? '50', 10)

  // 필터 적용
  const filtered = applyFilters(auditLogFixtures, { eventType, userId, from, to })

  // created_at DESC 정렬 (id DESC tiebreaker — fixture는 이미 id DESC)
  const sorted = [...filtered].sort((a, b) => {
    if (b.createdAt !== a.createdAt) return b.createdAt.localeCompare(a.createdAt)
    return b.id - a.id
  })

  const totalElements = sorted.length
  const totalPages = calcTotalPages(totalElements, size)
  const offset = page * size
  const items = sorted.slice(offset, offset + size)

  return HttpResponse.json({
    items,
    page,
    size,
    totalElements,
    totalPages,
  })
})

export const auditLogHandlers = [listAuditLogsHandler]
