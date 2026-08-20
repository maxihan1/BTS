// 이슈 상태 전환 가용목록 조회 + 전환 실행 TanStack Query 훅 + 409 모호 전환 후보 선택 상태
import { useCallback, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchIssueTransitions,
  transitionIssue,
  parseAmbiguousTransitionError,
} from '@/api/issues'
import type { AmbiguousTransitionCandidate, TransitionIssueInput } from '@/api/issues'

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
        queryClient.invalidateQueries({ queryKey: ['issue', key] }),
        queryClient.invalidateQueries({ queryKey: issueTransitionKeys.list(key) }),
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
