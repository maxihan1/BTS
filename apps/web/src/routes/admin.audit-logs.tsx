// 감사 로그 관리자 조회 페이지 — /admin/audit-logs, SYSTEM_ADMIN 전용
import type { JSX } from 'react'
import { useState } from 'react'
import { AuditLogFilters } from '@/components/admin/AuditLogFilters'
import { AuditLogTable } from '@/components/admin/AuditLogTable'
import { useAuditLogsQuery } from '@/auth/useAuditLogsQuery'
import type { AuditLogQueryParams } from '@/api/audit-logs'
import { auditLogLabels } from '@/i18n/audit-log-labels'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 크기 상수
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 50

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface PaginationControlsProps {
  /** 현재 페이지 번호 (0-base) */
  readonly page: number
  /** 전체 페이지 수 */
  readonly totalPages: number
  /** 전체 항목 수 */
  readonly totalElements: number
  /** 페이지 크기 */
  readonly size: number
  /** 이전 페이지 클릭 핸들러 */
  readonly onPrevious: () => void
  /** 다음 페이지 클릭 핸들러 */
  readonly onNext: () => void
}

/**
 * 페이지네이션 컨트롤.
 * "N개 중 X–Y" 표시 + 이전/다음 버튼 (경계 disabled).
 */
function PaginationControls({
  page,
  totalPages,
  totalElements,
  size,
  onPrevious,
  onNext,
}: PaginationControlsProps): JSX.Element {
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  const rangeText = totalElements > 0
    ? auditLogLabels.pagination.rangeOf(from, to, totalElements)
    : `0개`

  return (
    <div className="flex items-center justify-between px-2 py-3">
      <span className="text-sm text-muted-foreground">{rangeText}</span>
      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onPrevious}
          disabled={page === 0}
        >
          {auditLogLabels.pagination.previous}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onNext}
          disabled={page >= totalPages - 1}
        >
          {auditLogLabels.pagination.next}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리자 인증 감사 로그 조회 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드 적용.
 * - Filters + Table + Pagination 조립.
 * - 필터 변경 시 page=0 리셋.
 * - code-based 라우트 패턴:
 *   ```ts
 *   import { AdminAuditLogsRouteAdapter } from './routes/admin.audit-logs'
 *   const adminAuditLogsRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/admin/audit-logs',
 *     component: AdminAuditLogsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: composeGuards(requireAuth, requireSystemAdmin),
 *   })
 *   ```
 *
 * @see AuditLogFilters 필터 컴포넌트
 * @see AuditLogTable 결과 테이블 컴포넌트
 * @see requireSystemAdmin SYSTEM_ADMIN 가드 함수
 */
export function AdminAuditLogsPage(): JSX.Element {
  const [filters, setFilters] = useState<Omit<AuditLogQueryParams, 'page' | 'size'>>({})
  const [page, setPage] = useState(0)

  const { data, isLoading } = useAuditLogsQuery({
    ...filters,
    page,
    size: PAGE_SIZE,
  })

  const handleFilterChange = (partial: Partial<AuditLogQueryParams>) => {
    setFilters((prev) => ({ ...prev, ...partial }))
    setPage(0) // 필터 변경 시 page=0 리셋
  }

  const handlePrevious = () => {
    setPage((p) => Math.max(0, p - 1))
  }

  const handleNext = () => {
    setPage((p) => p + 1)
  }

  const entries = data?.items ?? []
  const totalPages = data?.totalPages ?? 0
  const totalElements = data?.totalElements ?? 0

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{auditLogLabels.page.heading}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {auditLogLabels.page.description}
        </p>
      </div>

      <div className="mb-4">
        <AuditLogFilters
          params={{ ...filters, page, size: PAGE_SIZE }}
          onFilterChange={handleFilterChange}
        />
      </div>

      <AuditLogTable entries={entries} isLoading={isLoading} />

      <PaginationControls
        page={page}
        totalPages={totalPages}
        totalElements={totalElements}
        size={PAGE_SIZE}
        onPrevious={handlePrevious}
        onNext={handleNext}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다.
 */
export function AdminAuditLogsRouteAdapter(): JSX.Element {
  return <AdminAuditLogsPage />
}
