// 이슈 상태 전환 가용목록 조회 + 전환 실행 TanStack Query 훅 + 409 모호 전환 후보 선택 · 전환 플로우
import { useCallback, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchIssueTransitions,
  transitionIssue,
  parseAmbiguousTransitionError,
} from '@/api/issues'
import type { AmbiguousTransitionCandidate, TransitionIssueInput } from '@/api/issues'
import { ApiError } from '@/api/client'
import { issueDetailStrings } from '@/i18n/ko'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'

/** 이슈 전환 관련 queryKey 팩토리 */
export const issueTransitionKeys = {
  /** 특정 이슈의 가용 전환 목록 queryKey */
  list: (key: string) => ['issue-transitions', key] as const,
}

/**
 * 이슈의 현재 상태에서 가용한 전환 목록을 조회한다.
 * GET /api/v1/issues/{key}/transitions
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 */
export function useIssueTransitions(key: string) {
  return useQuery({
    queryKey: issueTransitionKeys.list(key),
    queryFn: () => fetchIssueTransitions(key),
  })
}

/**
 * 이슈 상태를 전환한다.
 * POST /api/v1/issues/{key}/transition
 *
 * onSuccess 시 해당 이슈 캐시('issue', key)와 가용 전환 목록 캐시를 모두 무효화해
 * 상태 배지 및 전환 버튼이 최신 상태로 갱신되도록 한다.
 *
 * @param key 전환할 이슈 식별 키
 */
export function useTransitionIssue(key: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (input: TransitionIssueInput) => transitionIssue(key, input),
    onSuccess: async () => {
      await Promise.all([
        invalidateIssueViews(queryClient, key),
      ])
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 409 AMBIGUOUS_TRANSITION — 후보 선택 상태 (ADR 2026-08-18 §D3)
// ─────────────────────────────────────────────────────────────────────────────

/** 후보 선택 UI 에 필요한 것 전부 — 안내 문구 · 후보 전량 · 재요청에 쓸 원 요청. */
export interface AmbiguousTransitionPrompt {
  /** 서버가 만든 안내 문구. 후보 개수가 들어 있어 클라이언트가 다시 조립하지 않는다. */
  readonly message: string
  /** 사용자가 지목할 수 있는 전환 후보 전량. 서버가 준 순서를 보존한다. */
  readonly candidates: AmbiguousTransitionCandidate[]
  /**
   * 409 를 받은 **원 요청 그대로**.
   *
   * 재요청은 여기에 `transitionId` 만 덧붙이면 된다. `expectedVersion`·`resolutionId` 를
   * 호출자가 다시 조립하게 두면 한 쪽이라도 빠졌을 때 OCC 409 나 결의안 누락으로 되돌아온다 —
   * 사용자 눈에는 「골랐는데 또 실패」로만 보이는 형태다.
   */
  readonly input: TransitionIssueInput
}

/** {@link useAmbiguousTransition} 반환값. */
export interface AmbiguousTransitionController {
  /** 열려 있는 후보 선택 프롬프트. 없으면 null. */
  readonly prompt: AmbiguousTransitionPrompt | null
  /**
   * 전환 실패를 받아 모호 전환인지 판정한다.
   *
   * @param error mutation 이 던진 값
   * @param input 그 실패를 부른 전환 요청
   * @returns 모호 전환이라 프롬프트를 열었으면 true. false 면 호출자가 기존 에러 처리를 이어간다
   */
  readonly capture: (error: unknown, input: TransitionIssueInput) => boolean
  /** 프롬프트를 닫는다 (취소 또는 재요청 직전). */
  readonly clear: () => void
}

/**
 * 409 `AMBIGUOUS_TRANSITION` 후보 선택 상태를 관리한다.
 *
 * 같은 (from, to) 상태쌍에 전환이 여럿일 수 있어 `toStatusKey` 만으로는 못 가른다.
 * 서버는 조용히 아무거나 고르지 않고 후보를 돌려주므로(ADR §D3), 클라이언트도 조용히
 * 첫 후보를 고르면 안 된다 — 사용자가 지목한 후보의 `transitionId` 로 재요청해야 한다.
 *
 * mutation 과 분리해 둔다. 이슈 상세·목록 셀·일괄 전환이 각자 다른 mutation 을 쓰는데
 * 이 상태 기계는 셋 다 같기 때문이다.
 *
 * @returns 프롬프트 상태 + 판정기 + 닫기
 */
export function useAmbiguousTransition(): AmbiguousTransitionController {
  const [prompt, setPrompt] = useState<AmbiguousTransitionPrompt | null>(null)

  const capture = useCallback((error: unknown, input: TransitionIssueInput): boolean => {
    const ambiguous = parseAmbiguousTransitionError(error)
    if (ambiguous === null) return false
    setPrompt({ message: ambiguous.message, candidates: ambiguous.candidates, input })
    return true
  }, [])

  const clear = useCallback(() => {
    setPrompt(null)
  }, [])

  return { prompt, capture, clear }
}

// ─────────────────────────────────────────────────────────────────────────────
// 전환 실행 플로우 — 전환 mutation + 409 후보 선택을 한 덩어리로 (이슈 상세용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 단건 캐시와 가용 전환 목록 캐시를 함께 무효화한다.
 *
 * 전환은 두 캐시를 동시에 낡게 만든다 — 상태 배지는 이슈 단건에서, 다음에 고를 수 있는
 * 전환은 목록에서 온다. 한쪽만 무효화하면 배지는 바뀌었는데 셀렉터는 옛 전환을 계속 보여 준다.
 *
 * @param queryClient 무효화를 수행할 TanStack Query 클라이언트
 * @param key 전환한 이슈 식별 키
 */
async function invalidateIssueTransitionCaches(
  queryClient: QueryClient,
  key: string,
): Promise<void> {
  await Promise.all([
    invalidateIssueViews(queryClient, key),
  ])
}

/**
 * 모호 전환이 아닌 전환 실패를 사용자에게 알린다 (스펙 E5 S3·S4·S5).
 *
 * @param error mutation 이 던진 값
 * @param invalidate VERSION_CONFLICT 일 때 최신 데이터 재조회를 유도하는 무효화기
 */
function notifyTransitionFailure(error: unknown, invalidate: () => void): void {
  if (error instanceof ApiError && error.status === 409) {
    // errorCode 구분: TRANSITION_NOT_ALLOWED(S3) vs VERSION_CONFLICT(S4)
    const body = error.body as Record<string, unknown> | undefined
    const errorCode = typeof body?.['errorCode'] === 'string' ? body['errorCode'] : ''
    if (errorCode === 'TRANSITION_NOT_ALLOWED') {
      // ★목록도 재조회한다. 화면이 가용전환을 받은 **뒤** 워크플로우가 바뀌면 우리가 실어 보낸
      //   `transitionId` 가 후보에서 사라지고, backend `WorkflowEngine.resolveById` 가 그것을
      //   `WorkflowNotFoundException` → 이 코드로 돌려준다. 재조회하지 않으면 드롭다운이 사라진
      //   전환을 계속 내밀어 그 이슈는 **새로고침 전까지 상태를 못 바꾼다.**
      //   아래 VERSION_CONFLICT 분기가 같은 이유로 이미 같은 것을 한다.
      invalidate()
      toast.error(issueDetailStrings.transitionNotAllowedError)
      return
    }
    // VERSION_CONFLICT(S4) — 최신 데이터 + 전환 목록 재조회 유도
    invalidate()
    toast.error(issueDetailStrings.transitionVersionConflictError)
    return
  }
  if (error instanceof ApiError && error.status === 422) {
    toast.error(issueDetailStrings.transitionWorkflowNotConfiguredError)
    return
  }
  toast.error(issueDetailStrings.transitionNotAllowedError)
}

/** {@link useIssueTransitionFlow} 반환값 — 화면이 필요한 전환 배선 전부. */
export interface IssueTransitionFlow {
  /** 전환을 실행한다. 409 모호 전환이면 토스트 대신 후보 프롬프트가 열린다 */
  readonly mutate: (input: TransitionIssueInput) => void
  /** 전환 요청 진행 중 여부 — 셀렉터·후보 버튼 disabled 에 쓴다 (NFR3) */
  readonly isPending: boolean
  /** 열려 있는 409 후보 선택 프롬프트. 없으면 null */
  readonly prompt: AmbiguousTransitionPrompt | null
  /** 후보를 지목해 재요청한다 — 원 요청에 `transitionId` 만 덧붙인다 */
  readonly selectCandidate: (transitionId: string) => void
  /** 후보 선택을 취소하고 프롬프트를 닫는다 (전환 미실행) */
  readonly cancelPrompt: () => void
}

/**
 * 이슈 상세의 전환 실행 배선 전부 — mutation · 캐시 무효화 · 에러 토스트 ·
 * 409 `AMBIGUOUS_TRANSITION` 후보 선택 왕복(ADR 2026-08-18 §D3).
 *
 * 화면이 아니라 여기에 모아 둔 이유는 네 조각(실행·무효화·에러 분기·후보 왕복)이 **하나의
 * 프로토콜**이기 때문이다. 화면에 흩어 두면 후보를 고른 뒤 원 요청의 `expectedVersion` 을
 * 다시 조립하는 식으로 갈라지고, 그때 사용자에게는 「골랐는데 또 실패」로만 보인다.
 *
 * @param issueKey 전환할 이슈 식별 키 (예: "ATLAS-1")
 * @returns 전환 실행기 + 진행 상태 + 후보 프롬프트 상태
 */
export function useIssueTransitionFlow(issueKey: string): IssueTransitionFlow {
  const queryClient = useQueryClient()
  const ambiguous = useAmbiguousTransition()

  const mutation = useMutation({
    mutationFn: (input: TransitionIssueInput) => transitionIssue(issueKey, input),
    onSuccess: () => invalidateIssueTransitionCaches(queryClient, issueKey),
    onError: (error: unknown, input: TransitionIssueInput) => {
      // ★모호 전환(409 AMBIGUOUS_TRANSITION)은 토스트로 닫지 않는다 — 후보를 고르면 실행할 수
      //   있는 상태라 "허용되지 않는 전환" 이라고 말하면 거짓이고, 사용자는 그 상태 변경을
      //   영영 못 하게 된다. 원 요청을 그대로 들고 후보 선택 프롬프트로 넘긴다.
      if (ambiguous.capture(error, input)) return
      notifyTransitionFailure(error, () => {
        void invalidateIssueTransitionCaches(queryClient, issueKey)
      })
    },
  })

  const { prompt, clear } = ambiguous
  const { mutate } = mutation

  const selectCandidate = useCallback(
    (transitionId: string) => {
      if (prompt === null) return
      // 원 요청(`prompt.input`)에 고른 후보의 `transitionId` 만 덧붙인다 — 여기서
      // `expectedVersion`·`resolutionId` 를 다시 조립하면 한 쪽이 빠졌을 때 또 실패한다.
      clear()
      mutate({ ...prompt.input, transitionId })
    },
    [prompt, clear, mutate],
  )

  return {
    mutate,
    isPending: mutation.isPending,
    prompt,
    selectCandidate,
    cancelPrompt: clear,
  }
}
