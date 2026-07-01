// 아웃바운드 Webhook 구독 관리자 목록 페이지 — /admin/webhooks, SYSTEM_ADMIN 전용 (FR-API-03 PR4 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { WebhookTable } from '@/components/admin/WebhookTable'
import { WebhookForm } from '@/components/admin/WebhookForm'
import {
  useWebhooksQuery,
  useCreateWebhook,
  useUpdateWebhook,
  useDeleteWebhook,
  WEBHOOKS_QUERY_KEY,
} from '@/api/useWebhooks'
import type { CreateWebhookRequest, UpdateWebhookRequest, WebhookResponse } from '@/api/webhooks'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 / 문구 — BC 내 고정 한국어 (WebhookForm.tsx 관례)
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 20

const labels = {
  heading: '아웃바운드 Webhook',
  description: '외부 시스템에 이슈 이벤트를 통지하는 Webhook 구독을 관리합니다.',
  newButton: '새 구독',
  pagination: { previous: '이전', next: '다음' },
  error: {
    conflict: '다른 곳에서 먼저 변경되었습니다. 목록을 다시 불러오세요.',
    generic: 'Webhook 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  },
} as const

/** search BC의 OCC 충돌 errorCode — 대문자 패턴 (notification-policies와 동류, mfa 소문자 패턴 복붙 금지) */
const CONFLICT_ERROR_CODE = 'SEARCH_WEBHOOK_CONFLICT'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 판정 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** OCC 충돌(409) 여부 — status===409 또는 errorCode===SEARCH_WEBHOOK_CONFLICT */
function isConflictError(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  if (error.status === 409) return true
  return extractErrorCode(error.body) === CONFLICT_ERROR_CODE
}

/** 400 유효성 실패 시 서버가 내려준 ProblemDetail.detail 메시지를 추출한다 (SSRF 차단 등) */
function extractValidationDetail(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.status !== 400) return undefined
  const body = error.body
  if (body !== null && typeof body === 'object' && 'detail' in body) {
    const detail = (body as Record<string, unknown>)['detail']
    return typeof detail === 'string' ? detail : undefined
  }
  return undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 폼 표시 상태 — 닫힘/생성/수정 3-state 토글 (notification-policies 조립 관례 변형)
// ─────────────────────────────────────────────────────────────────────────────

type WebhookFormState =
  | { readonly kind: 'closed' }
  | { readonly kind: 'create' }
  | { readonly kind: 'edit'; readonly webhook: WebhookResponse }

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface PaginationControlsProps {
  /** 현재 페이지 번호 (0-base) */
  readonly page: number
  /** 다음 버튼 활성화 여부 — 직전 응답 길이가 페이지 크기와 같을 때만 true */
  readonly hasNext: boolean
  /** 이전 페이지 클릭 핸들러 */
  readonly onPrevious: () => void
  /** 다음 페이지 클릭 핸들러 */
  readonly onNext: () => void
}

/**
 * size 기반 offset 페이지네이션 컨트롤 (EC-2 — raw List 응답, 총 개수 없음).
 *
 * audit-logs의 "N개 중 X–Y" 표시는 totalElements에 의존하므로 여기서는 쓸 수 없다.
 * 대신 "페이지 {page+1}" 표기 + 이전/다음 버튼으로 구성한다.
 * - 이전. page===0이면 비활성.
 * - 다음. 직전 응답 길이가 페이지 크기와 같을 때만(더 있을 가능성) 활성.
 */
function PaginationControls({ page, hasNext, onPrevious, onNext }: PaginationControlsProps): JSX.Element {
  return (
    <div className="flex items-center justify-between px-2 py-3">
      <span className="text-sm text-muted-foreground">{`페이지 ${page + 1}`}</span>
      <div className="flex gap-2">
        <Button type="button" variant="outline" size="sm" onClick={onPrevious} disabled={page === 0}>
          {labels.pagination.previous}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onNext} disabled={!hasNext}>
          {labels.pagination.next}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리자 아웃바운드 Webhook 구독 관리 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드는 router.ts 등록(Task 9)에서 적용된다.
 * - Table(Task 3) + Form(Task 5) 조립. "새 구독" 버튼 또는 행 편집으로 Form을 토글 표시한다.
 * - 생성 `useCreateWebhook` / 수정 `useUpdateWebhook`({id, body}) / 삭제 `useDeleteWebhook`.
 * - 이력 보기 → `/admin/webhooks/{id}/deliveries`로 navigate (해당 라우트도 Task 9 등록 예정).
 * - size 기반 offset 페이지네이션(EC-2, raw List·총개수 없음) — 다음 버튼은 응답 길이가
 *   페이지 크기와 같을 때만 활성화한다.
 * - mutation 에러. 409(OCC 충돌) → Form submitError + 목록 invalidate,
 *   400 → Form submitError(서버 상세 메시지), 그 외 → sonner toast.error.
 *
 * @see WebhookTable 목록 테이블 컴포넌트
 * @see WebhookForm 생성/수정 겸용 폼 컴포넌트
 */
export function AdminWebhooksPage(): JSX.Element {
  const [page, setPage] = useState(0)
  const [formState, setFormState] = useState<WebhookFormState>({ kind: 'closed' })
  const [submitError, setSubmitError] = useState<string | undefined>(undefined)

  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data, isLoading } = useWebhooksQuery(page, PAGE_SIZE)
  const webhooks = data ?? []
  const hasNext = webhooks.length === PAGE_SIZE

  const createMutation = useCreateWebhook()
  const updateMutation = useUpdateWebhook()
  const deleteMutation = useDeleteWebhook()
  const isMutating = createMutation.isPending || updateMutation.isPending

  function handleMutationError(error: unknown): void {
    if (isConflictError(error)) {
      setSubmitError(labels.error.conflict)
      void queryClient.invalidateQueries({ queryKey: WEBHOOKS_QUERY_KEY })
      return
    }
    const validationDetail = extractValidationDetail(error)
    if (validationDetail !== undefined) {
      setSubmitError(validationDetail)
      return
    }
    toast.error(labels.error.generic)
  }

  function handleSubmit(payload: CreateWebhookRequest | UpdateWebhookRequest): void {
    setSubmitError(undefined)
    if ('version' in payload) {
      if (formState.kind !== 'edit') return
      updateMutation.mutate(
        { id: formState.webhook.id, body: payload },
        {
          onSuccess: () => { setFormState({ kind: 'closed' }) },
          onError: handleMutationError,
        },
      )
      return
    }
    createMutation.mutate(payload, {
      onSuccess: () => { setFormState({ kind: 'closed' }) },
      onError: handleMutationError,
    })
  }

  function handleNew(): void {
    setSubmitError(undefined)
    setFormState({ kind: 'create' })
  }

  function handleEdit(webhook: WebhookResponse): void {
    setSubmitError(undefined)
    setFormState({ kind: 'edit', webhook })
  }

  function handleCancel(): void {
    setSubmitError(undefined)
    setFormState({ kind: 'closed' })
  }

  function handleDelete(id: string): void {
    deleteMutation.mutate(id, {
      onError: () => { toast.error(labels.error.generic) },
    })
  }

  function handleViewDeliveries(id: string): void {
    // '/admin/webhooks/{id}/deliveries' 경로는 router.ts 등록 완료 시 타입 추론됨 — 현재 string cast로 우회
    void navigate({ to: `/admin/webhooks/${id}/deliveries` as string })
  }

  function handlePrevious(): void {
    setPage((p) => Math.max(0, p - 1))
  }

  function handleNext(): void {
    setPage((p) => p + 1)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">{labels.heading}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{labels.description}</p>
        </div>
        {formState.kind === 'closed' && (
          <Button type="button" onClick={handleNew}>{labels.newButton}</Button>
        )}
      </div>

      {formState.kind !== 'closed' && (
        <div className="mb-6">
          <WebhookForm
            mode={formState.kind === 'edit' ? 'edit' : 'create'}
            initialValue={formState.kind === 'edit' ? formState.webhook : undefined}
            onSubmit={handleSubmit}
            submitError={submitError}
            isSubmitting={isMutating}
            onCancel={handleCancel}
          />
        </div>
      )}

      <WebhookTable
        webhooks={webhooks}
        isLoading={isLoading}
        onEdit={handleEdit}
        onDelete={handleDelete}
        onViewDeliveries={handleViewDeliveries}
      />

      <PaginationControls
        page={page}
        hasNext={hasNext}
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
 *
 * 등록 방법 (code-based 패턴, Task 9):
 * ```ts
 * import { AdminWebhooksRouteAdapter } from './routes/admin.webhooks'
 * const adminWebhooksRoute = createRoute({
 *   getParentRoute: () => rootRoute,
 *   path: '/admin/webhooks',
 *   component: AdminWebhooksRouteAdapter,
 *   staticData: { requireAuth: true },
 *   beforeLoad: composeGuards(requireAuth, requireSystemAdmin),
 * })
 * ```
 */
export function AdminWebhooksRouteAdapter(): JSX.Element {
  return <AdminWebhooksPage />
}
