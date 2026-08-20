// 409 모호 전환 후보 선택 프롬프트 — 전환 플로우 상태를 후보 선택 다이얼로그에 잇는 커넥터
import type { JSX } from 'react'
import type { IssueTransitionFlow } from '@/hooks/use-issue-transitions'
import { AmbiguousTransitionDialog } from '@/components/issue/meta/IssueStateTransition'

/** AmbiguousTransitionPrompt props */
export interface AmbiguousTransitionPromptProps {
  /** 전환 실행 + 409 후보 선택 상태를 함께 들고 있는 플로우 */
  flow: IssueTransitionFlow
}

/**
 * 409 `AMBIGUOUS_TRANSITION` 후보 선택 프롬프트 (ADR 2026-08-18 §D3).
 *
 * 열림 여부·후보·재요청은 전부 {@link IssueTransitionFlow} 가 들고 있고, 이 컴포넌트는
 * 그것을 표현 전용 `AmbiguousTransitionDialog` 에 잇기만 한다. 화면(이슈 상세)이 프롬프트
 * 상태를 직접 알면 「후보를 고른 뒤 원 요청을 다시 조립」하는 갈래가 화면마다 생긴다.
 *
 * @param props 전환 플로우
 * @returns 열려 있는 프롬프트가 있으면 다이얼로그, 없으면 null (닫힘)
 */
export function AmbiguousTransitionPrompt({
  flow,
}: AmbiguousTransitionPromptProps): JSX.Element | null {
  const { prompt } = flow
  if (prompt === null) return null

  return (
    <AmbiguousTransitionDialog
      candidates={prompt.candidates}
      message={prompt.message}
      isTransitioning={flow.isPending}
      onSelect={flow.selectCandidate}
      onCancel={flow.cancelPrompt}
    />
  )
}
