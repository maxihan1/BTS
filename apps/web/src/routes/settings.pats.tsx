// PAT(Personal Access Token) 셀프서비스 설정 페이지 — /settings/pats, 발급 폼·목록·1회 토큰 모달 조립 (FR-API-04 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { PatCreateForm } from '@/components/settings/PatCreateForm'
import { PatList } from '@/components/settings/PatList'
import { PatTokenModal } from '@/components/settings/PatTokenModal'
import { createPat, fetchPats, revokePat, type CreatePatRequest, type PatIssued } from '@/api/pats'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey / 문구 — 캐시 키를 한 곳에서 관리해 오타·drift 방지(WEBHOOKS_QUERY_KEY 관례)
// ─────────────────────────────────────────────────────────────────────────────

const PATS_QUERY_KEY = ['pats'] as const

const labels = {
  heading: 'Personal Access Token',
  description: 'API 호출에 사용할 개인 액세스 토큰을 발급·관리합니다.',
  revokeGenericError: 'PAT 폐기에 실패했습니다. 잠시 후 다시 시도해 주세요.',
} as const

/**
 * PAT 백엔드 고정 에러코드를 한국어 메시지로 매핑한다.
 *
 * `PersonalAccessTokenController`의 로컬 `@ExceptionHandler` 코드(`invalid_name`/`invalid_scope`/
 * `invalid_expiry`/`quota_exceeded`/`not_found`)와 1:1 정합. 매핑되지 않은 코드는 일반 메시지로 폴백한다
 * (accountLinkErrorMessage/mfaErrorMessage와 동형의 BC 로컬 매핑 함수 관례).
 */
function patErrorMessage(code: string | null): string {
  switch (code) {
    case 'invalid_name':
      return '이름은 공백일 수 없습니다.'
    case 'invalid_scope':
      return 'scope를 확인해 주세요. 최소 1개 이상 선택해야 합니다.'
    case 'invalid_expiry':
      return '만료 기간이 올바르지 않습니다.'
    case 'quota_exceeded':
      return '발급 가능한 PAT 개수를 초과했습니다. 기존 토큰을 폐기한 뒤 다시 시도해 주세요.'
    case 'not_found':
      return '이미 폐기되었거나 존재하지 않는 PAT입니다.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PAT(Personal Access Token) 셀프서비스 설정 페이지.
 *
 * - 목록 조회. `useQuery(['pats'], fetchPats)`.
 * - 발급. `useMutation(createPat)` — 성공 시 raw token을 {@link PatTokenModal}에 1회 노출하고
 *   목록은 `invalidateQueries`로만 갱신한다(응답을 `setQueryData`로 캐시에 그대로 덮어쓰지 않음 —
 *   발급 응답에는 `lastUsedAt` 필드가 없어 부분 응답으로 캐시를 덮으면 플리커가 난다,
 *   mutation-setquerydata-partial-response-flicker 회귀 방지).
 * - 폐기. `useMutation(revokePat)` — 성공 시 목록을 invalidate하고, 실패 시 매핑된 메시지를 토스트로 알린다.
 * - 에러. 발급 400/403은 {@link patErrorMessage}로 매핑해 폼 내부 배너(`submitError`)에 표시하고,
 *   폐기 에러는 sonner toast로 알린다.
 */
export function SettingsPatsPage(): JSX.Element {
  const queryClient = useQueryClient()

  const { data: pats, isLoading } = useQuery({
    queryKey: PATS_QUERY_KEY,
    queryFn: fetchPats,
    staleTime: 30_000,
  })

  const [issued, setIssued] = useState<PatIssued | null>(null)
  const [submitError, setSubmitError] = useState<string | undefined>(undefined)

  const createMutation = useMutation({
    mutationFn: createPat,
    onSuccess: (data) => {
      setSubmitError(undefined)
      setIssued(data)
      void queryClient.invalidateQueries({ queryKey: PATS_QUERY_KEY })
    },
    onError: (err) => {
      const code = err instanceof ApiError ? extractErrorCode(err.body) : null
      setSubmitError(patErrorMessage(code))
    },
  })

  const revokeMutation = useMutation({
    mutationFn: revokePat,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: PATS_QUERY_KEY })
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        toast.error(patErrorMessage(extractErrorCode(err.body)))
      } else {
        toast.error(labels.revokeGenericError)
      }
    },
  })

  function handleCreate(payload: CreatePatRequest): void {
    setSubmitError(undefined)
    createMutation.mutate(payload)
  }

  function handleRevoke(id: string): void {
    revokeMutation.mutate(id)
  }

  function handleModalClose(): void {
    setIssued(null)
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{labels.heading}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{labels.description}</p>
      </div>

      <div className="mb-8">
        <PatCreateForm
          onSubmit={handleCreate}
          submitError={submitError}
          isSubmitting={createMutation.isPending}
        />
      </div>

      <PatList
        pats={pats ?? []}
        isLoading={isLoading}
        onRevoke={handleRevoke}
        isRevoking={revokeMutation.isPending}
      />

      <PatTokenModal issued={issued} onClose={handleModalClose} />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용 — Task 8)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다(SessionsSettingsRouteAdapter 선례).
 *
 * 실제 라우터 등록(`/settings/pats` path·`requireAuth` 가드·nav 링크)은 Task 8에서 수행한다.
 */
export function SettingsPatsRouteAdapter(): JSX.Element {
  return <SettingsPatsPage />
}
