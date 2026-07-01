// 아웃바운드 webhook 구독 발송 이력 조회 페이지 — /admin/webhooks/$id/deliveries, SYSTEM_ADMIN 전용
import type { JSX } from 'react'
import { useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { useWebhookDeliveriesQuery } from '@/api/useWebhooks'
import { WebhookDeliveryTable } from '@/components/admin/WebhookDeliveryTable'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 크기 상수 — raw List라 총 개수 API가 없다(EC-2). "받은 개수 == size"를
// 다음 페이지 존재 추정으로 사용한다.
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 20

/** 이력 목록 쿼리 실패 시 표시할 일반 안내 문구 (내부 오류 원문 노출 금지, FINDING3) */
const DELIVERIES_LOAD_FAILED_MESSAGE = '발송 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 서브컴포넌트 (admin.audit-logs.tsx `PaginationControls` 선례 — size 기반 변형)
// ─────────────────────────────────────────────────────────────────────────────

interface DeliveriesPaginationControlsProps {
  /** 현재 페이지 번호 (0-base) */
  readonly page: number
  /** 이전 페이지 클릭 핸들러 */
  readonly onPrevious: () => void
  /** 다음 페이지 클릭 핸들러 */
  readonly onNext: () => void
  /** 이전 버튼 disabled 여부 — `page === 0` */
  readonly isPreviousDisabled: boolean
  /** 다음 버튼 disabled 여부 — 받은 이력 개수가 size 미만이면 true(EC-2) */
  readonly isNextDisabled: boolean
}

/**
 * size 기반 prev/next 페이지네이션 컨트롤.
 *
 * raw List 응답(총 개수 없음)이라 "받은 개수 == size"를 다음 페이지 존재 추정으로 쓴다 —
 * offset 페이지네이션 envelope(totalPages 보유)를 쓰는 admin.audit-logs.tsx와 달리
 * 이 화면은 개수 기반 추정만 가능하다(EC-2).
 */
function DeliveriesPaginationControls({
  page,
  onPrevious,
  onNext,
  isPreviousDisabled,
  isNextDisabled,
}: DeliveriesPaginationControlsProps): JSX.Element {
  return (
    <div className="flex items-center justify-between px-2 py-3">
      <span className="text-sm text-muted-foreground">페이지 {page + 1}</span>
      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onPrevious}
          disabled={isPreviousDisabled}
        >
          이전
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onNext}
          disabled={isNextDisabled}
        >
          다음
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WebhookDeliveriesPageProps {
  /** 발송 이력을 조회할 webhook 구독 UUID */
  readonly webhookId: string
}

/**
 * 아웃바운드 webhook 구독의 발송 이력 조회 페이지.
 *
 * - `useWebhookDeliveriesQuery(webhookId, page, size)` + `WebhookDeliveryTable`.
 * - raw List 응답(총 개수 없음)이라 size 기반 prev/next: 이전은 `page>0`,
 *   다음은 "받은 이력 개수 === size"일 때만 활성화한다(EC-2).
 * - 쿼리(`isError`)가 실패하면 "발송 이력이 없습니다" 빈 목록 문구로 은폐하지 않고
 *   role="alert" 에러 배너를 대신 표시한다(테이블·페이지네이션은 숨김, FINDING3).
 * - "← 목록으로"는 형제 라우트(`/admin/webhooks`, Task 9에서 등록 완료)를 가리키는
 *   타입 안전 `Link`를 사용한다 — plain `<a href>`는 전체 페이지 리로드를 일으켜
 *   SPA 이동이 아니게 되고 E2E에서 MSW 상태가 초기화되는 문제가 있다(EC-7).
 */
export function WebhookDeliveriesPage({ webhookId }: WebhookDeliveriesPageProps): JSX.Element {
  const [page, setPage] = useState(0)

  const { data, isLoading, isError } = useWebhookDeliveriesQuery(webhookId, page, PAGE_SIZE)
  const deliveries = data ?? []

  const isPreviousDisabled = page === 0
  const isNextDisabled = deliveries.length !== PAGE_SIZE

  const handlePrevious = (): void => {
    setPage((current) => Math.max(0, current - 1))
  }

  const handleNext = (): void => {
    setPage((current) => current + 1)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6">
        <Link to="/admin/webhooks" className="text-sm text-muted-foreground hover:text-foreground">
          ← 목록으로
        </Link>
        <h1 className="mt-2 text-xl font-semibold">Webhook 발송 이력</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          구독 ID {webhookId}의 최근 발송 시도 이력입니다.
        </p>
      </div>

      {isError ? (
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {DELIVERIES_LOAD_FAILED_MESSAGE}
        </div>
      ) : (
        <>
          <WebhookDeliveryTable deliveries={deliveries} isLoading={isLoading} />

          <DeliveriesPaginationControls
            page={page}
            onPrevious={handlePrevious}
            onNext={handleNext}
            isPreviousDisabled={isPreviousDisabled}
            isNextDisabled={isNextDisabled}
          />
        </>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useParams로 URL의 `$id` param을 추출해 WebhookDeliveriesPage에 전달한다
 * (workflows.$key.tsx `WorkflowDetailRouteAdapter` 선례).
 *
 * router.ts에 `/admin/webhooks/$id/deliveries` 경로로 등록 완료 (Task 9) —
 * `beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`.
 */
export function WebhookDeliveriesRouteAdapter(): JSX.Element {
  // strict: false — 라우트 트리 어느 위치에서나 param을 추출 가능. 등록된 라우트 기준으로
  // 병합 params 타입에 `id`가 포함되어 캐스팅 없이 타입 추론된다.
  const { id } = useParams({ strict: false })
  return <WebhookDeliveriesPage webhookId={id ?? ''} />
}
