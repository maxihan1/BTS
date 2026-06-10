// 이슈 버전 연결 변경 mutation 훅 — affectsVersions / fixVersions PATCH (FR-VR-03)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError, apiFetch } from '@/api/client'
import type { IssueResponse } from '@/api/issues'
import { issueResponseSchema } from '@/api/issues'
import { z } from 'zod'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 연결 변경 입력 타입.
 * PATCH /api/v1/issues/{key}/affects-versions 또는 fix-versions body 형태.
 */
export interface ChangeVersionsInput {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  key: string
  /** 연결할 버전 UUID 목록. 빈 배열이면 전체 제거. */
  versionIds: string[]
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 API 함수
// ─────────────────────────────────────────────────────────────────────────────

const dataResponseSchema = z.object({ data: issueResponseSchema })

/**
 * 이슈 영향 버전 목록을 변경한다.
 * PATCH /api/v1/issues/{key}/affects-versions body { versionIds, expectedVersion }
 */
async function changeAffectsVersions(
  key: string,
  versionIds: string[],
  expectedVersion: number,
): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/affects-versions`, {
    method: 'PATCH',
    body: { versionIds, expectedVersion },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema.parse(raw)
  return wrapped.data
}

/**
 * 이슈 수정 버전 목록을 변경한다.
 * PATCH /api/v1/issues/{key}/fix-versions body { versionIds, expectedVersion }
 */
async function changeFixVersions(
  key: string,
  versionIds: string[],
  expectedVersion: number,
): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/fix-versions`, {
    method: 'PATCH',
    body: { versionIds, expectedVersion },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema.parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 연결 변경 에러를 분기하여 toast.error를 발사한다.
 *
 * - 409 → versionConflictError
 * - 422 → versionLinkedNotFoundError
 * - 기타 → 전달받은 fallbackMessage
 */
function notifyVersionsError(error: ApiError, fallbackMessage: string): void {
  if (error.status === 409) {
    toast.error(issueDetailStrings.versionConflictError)
  } else if (error.status === 422) {
    toast.error(issueDetailStrings.versionLinkedNotFoundError)
  } else {
    toast.error(fallbackMessage)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeAffectsVersions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 영향 버전 다중 연결 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key}/affects-versions 호출
 * 2. onError: 409 → 버전 충돌 toast.error / 422 → 버전 미존재 toast.error
 * 3. onSettled: invalidateQueries(['issue', key])로 서버 최신 상태 재조회 (setQueryData 금지 — 교훈)
 *
 * @returns UseMutationResult — mutate({ key, versionIds, expectedVersion }) 호출로 연결 변경 실행
 */
export function useChangeAffectsVersions() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, ChangeVersionsInput>({
    mutationFn: ({ key, versionIds, expectedVersion }: ChangeVersionsInput) =>
      changeAffectsVersions(key, versionIds, expectedVersion),

    onError: (error) => {
      if (error instanceof ApiError) {
        notifyVersionsError(error, issueDetailStrings.affectsVersionsChangeError)
      }
    },

    onSettled: (_data, _error, { key }: ChangeVersionsInput) => {
      // 성공/실패 무관하게 서버 상태와 동기화 — setQueryData 금지 (descriptionHtml 플리커 방지)
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(key) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeFixVersions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 수정 버전 다중 연결 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key}/fix-versions 호출
 * 2. onError: 409 → 버전 충돌 toast.error / 422 → 버전 미존재 toast.error
 * 3. onSettled: invalidateQueries(['issue', key])로 서버 최신 상태 재조회 (setQueryData 금지 — 교훈)
 *
 * @returns UseMutationResult — mutate({ key, versionIds, expectedVersion }) 호출로 연결 변경 실행
 */
export function useChangeFixVersions() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, ChangeVersionsInput>({
    mutationFn: ({ key, versionIds, expectedVersion }: ChangeVersionsInput) =>
      changeFixVersions(key, versionIds, expectedVersion),

    onError: (error) => {
      if (error instanceof ApiError) {
        notifyVersionsError(error, issueDetailStrings.fixVersionsChangeError)
      }
    },

    onSettled: (_data, _error, { key }: ChangeVersionsInput) => {
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(key) })
    },
  })
}
