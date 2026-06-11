// 알림 정책 관리자 페이지 — /admin/notification-policies, SYSTEM_ADMIN 전용 (FR-NT-01)
import type { JSX } from 'react'
import { useState, useCallback } from 'react'
import { toast } from 'sonner'
import { NotificationPolicyTable } from '@/components/admin/NotificationPolicyTable'
import { NotificationPolicyForm } from '@/components/admin/NotificationPolicyForm'
import {
  useCatalogQuery,
  usePoliciesQuery,
  useCreatePolicy,
  useTogglePolicy,
  useDeletePolicy,
} from '@/api/useNotificationPolicies'
import { notificationPolicyLabels } from '@/i18n/notification-policy-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 409 중복 판정 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * API 에러가 알림 정책 중복(409 NOTIF_POLICY_DUPLICATE)인지 판정한다.
 *
 * notification BC는 대문자 errorCode를 사용한다 (C3 eng-review).
 * mfa.ts 소문자 패턴과 다르므로 복붙 금지.
 * - `error.status===409` 또는 `error.body?.errorCode==='NOTIF_POLICY_DUPLICATE'`
 *
 * @remarks
 * instanceof 대신 name 속성 + duck-typing으로 판정.
 * Vitest 모듈 격리 환경에서 클래스 참조가 달라질 수 있어 instanceof가 false를 반환하는 경우 방지.
 */
function isDuplicatePolicyError(error: unknown): boolean {
  if (error === null || typeof error !== 'object') return false
  // ApiError duck-typing: name + status 속성 확인
  const err = error as Record<string, unknown>
  if (err['name'] !== 'ApiError') return false
  if (err['status'] === 409) return true
  // body의 errorCode 확인 (대문자 — notification BC 전용 패턴, C3)
  const body = err['body']
  if (body !== null && typeof body === 'object') {
    const code = (body as Record<string, unknown>)['errorCode']
    return code === 'NOTIF_POLICY_DUPLICATE'
  }
  return false
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리자 알림 정책 관리 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드 적용.
 * - Form(T6) + Table(T5) 조립.
 * - Form `onSubmit` → useCreatePolicy. 409 → submitError prop으로 Form에 전달.
 * - Table `onToggle` → useTogglePolicy, `onDelete` → useDeletePolicy.
 * - mutation 에러(생성 409 제외) → sonner toast.error.
 * - code-based 라우트 패턴:
 *   ```ts
 *   import { AdminNotificationPoliciesRouteAdapter } from './routes/admin.notification-policies'
 *   const adminNotificationPoliciesRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/admin/notification-policies',
 *     component: AdminNotificationPoliciesRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged),
 *   })
 *   ```
 *
 * @see NotificationPolicyTable 목록 테이블 컴포넌트
 * @see NotificationPolicyForm 생성 폼 컴포넌트
 * @see requireSystemAdmin SYSTEM_ADMIN 가드 함수
 */
export function AdminNotificationPoliciesPage(): JSX.Element {
  const [submitError, setSubmitError] = useState<string | undefined>(undefined)

  const { data: catalog } = useCatalogQuery()
  const { data: policies } = usePoliciesQuery()

  const createMutation = useCreatePolicy()
  const toggleMutation = useTogglePolicy()
  const deleteMutation = useDeletePolicy()

  const isMutating =
    createMutation.isPending || toggleMutation.isPending || deleteMutation.isPending

  const handleFormSubmit = useCallback(
    (payload: { eventType: string; recipientRole: string; channel: string }) => {
      // 이전 submitError 초기화
      setSubmitError(undefined)

      createMutation.mutate(
        { ...payload, enabled: true },
        {
          onError: (error) => {
            if (isDuplicatePolicyError(error)) {
              // 409 중복 → Form submitError prop으로 전달 (dead-path 회피 — dialog-submiterror-ownership-dead-path)
              setSubmitError(notificationPolicyLabels.error.duplicate)
            } else {
              // 그 외 에러 → sonner toast
              toast.error(notificationPolicyLabels.error.generic)
            }
          },
        },
      )
    },
    [createMutation],
  )

  const handleToggle = useCallback(
    (id: string, nextEnabled: boolean) => {
      toggleMutation.mutate(
        { id, enabled: nextEnabled },
        {
          onError: () => {
            toast.error(notificationPolicyLabels.error.generic)
          },
        },
      )
    },
    [toggleMutation],
  )

  const handleDelete = useCallback(
    (id: string) => {
      deleteMutation.mutate(id, {
        onError: () => {
          toast.error(notificationPolicyLabels.error.generic)
        },
      })
    },
    [deleteMutation],
  )

  const resolvedPolicies = policies ?? []
  const resolvedCatalog = catalog ?? {
    eventTypes: [],
    recipientRoles: [],
    channels: [],
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">
          {notificationPolicyLabels.page.heading}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {notificationPolicyLabels.page.description}
        </p>
      </div>

      <div className="mb-6">
        <NotificationPolicyForm
          catalog={resolvedCatalog}
          onSubmit={handleFormSubmit}
          submitError={submitError}
          isSubmitting={createMutation.isPending}
        />
      </div>

      <NotificationPolicyTable
        policies={resolvedPolicies}
        onToggle={handleToggle}
        onDelete={handleDelete}
        isMutating={isMutating}
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
export function AdminNotificationPoliciesRouteAdapter(): JSX.Element {
  return <AdminNotificationPoliciesPage />
}
