// 이슈 목록 상태 셀 — 클릭해 가용 전이를 고른다 (FR-UX-11 F9)
import type { JSX } from 'react'
import type { IssueTransition } from '@/api/issues'
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { CELL_OPTION_CLASS } from './EditableCell'

/**
 * 닫힌 상태에서 보이는 상태 배지.
 *
 * ★`role="status"` 는 **즉사 계약**이다(패리티 계약 §2). `issue-columns.ts` 의 기존
 * `renderStatusCell` 이 달고 있었고 e2e 가 셀렉터로 쓴다(`issue-filter.spec.ts` 는
 * 체크박스를 `role=checkbox` 로 한정해 이 배지와의 충돌을 피하고 있다). 편집 트리거로
 * 감싸도 이 role 과 className 이 배지에 그대로 남아야 한다.
 *
 * @param props 표시할 현재 상태 키
 * @returns 상태 배지 span
 */
export function StatusCellDisplay({ currentStateKey }: { currentStateKey: string }): JSX.Element {
  return (
    <span
      role="status"
      className="inline-block shrink-0 rounded-full bg-(--bg-neutral) px-2 py-0.5 text-xs font-medium text-(--text-subtle)"
    >
      {currentStateKey}
    </span>
  )
}

/** StatusCellEditor props */
export interface StatusCellEditorProps {
  /** 현재 상태에서 가용한 전이 목록 — props 파생, useState 초기화 금지 (stale state 회귀 방지) */
  transitions: IssueTransition[]
  /** 전이 0건일 때의 사유 — 미설정(422)과 종료 상태(빈 배열)를 가른다 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
  /** 전이 권한 여부 — false 면 전 선택지 disabled (fail-closed) */
  canTransition: boolean
  /** 저장 진행 중 — true 면 중복 제출을 막기 위해 disabled (NFR3) */
  isSaving: boolean
  /** 전이 조회 진행 중 — true 면 "전이 없음" 을 띄우지 않는다 (AssigneeCellEditor 와 동일 판단) */
  isLoading: boolean
  /** 비종료 전이 — 즉시 실행. toStateKey 전달 (IssueStateTransition 과 동일 시그니처) */
  onTransition: (toStateKey: string) => void
  /**
   * ★종료 전이(`toCategory === 'DONE'`) — 즉시 실행하지 않고 결의안 선택을 요청한다.
   *
   * 해결 결과는 종료 상태 전이의 **필수** 입력이다(glossary 「해결 결과」). 상세 화면
   * `issues.$key.tsx` 의 `handleTransition` 이 같은 분기로 `ResolutionModal` 을 띄운다 (FR14).
   * 선택(optional)으로 두면 호출자가 빠뜨렸을 때 종료 전이가 조용히 아무 일도 하지 않으므로
   * **필수**로 둔다.
   */
  onDoneTransition: (transition: IssueTransition) => void
}

/**
 * popover 안에 뜨는 가용 전이 목록.
 *
 * 상세 화면의 `IssueStateTransition` 은 `min-h-[44px]` 네이티브 `<select>` 라 목록 셀
 * popover 안에서는 과하다. 안내 문구 정본(`transitionWorkflowNotConfiguredError` ·
 * `noTransitionsAvailable`)은 **같은 것을 쓰되** 표현만 목록에 맞춘다.
 *
 * 조립(`EditableCell` 로 감싸기 + 조회 훅 배선 + `ResolutionModal`)은 다음 task 소관이다 —
 * 이 컴포넌트는 순수 프레젠테이션이라 조회 훅을 갖지 않는다.
 *
 * @param props 전이 목록 · 불가 사유 · 권한/저장/조회 상태 · 전이 콜백 2종
 * @returns 가용 전이 버튼 목록 또는 사유 안내 문구
 */
export function StatusCellEditor({
  transitions,
  unavailableReason,
  canTransition,
  isSaving,
  isLoading,
  onTransition,
  onDoneTransition,
}: StatusCellEditorProps): JSX.Element {
  // ★조회 중에는 "전이 없음" 을 띄우지 않는다 — 아직 모르는 것을 없다고 말하면 거짓이다.
  // 로딩과 빈 결과는 서로 다른 상태다 (AssigneeCellEditor 와 같은 처방).
  if (isLoading) {
    return <p className="text-xs text-(--text-subtle)">불러오는 중…</p>
  }

  if (transitions.length === 0) {
    return (
      <p className="text-xs text-(--text-subtle)">
        {unavailableReason === 'no-workflow'
          ? issueDetailStrings.transitionWorkflowNotConfiguredError
          : issueDetailStrings.noTransitionsAvailable}
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-0.5">
      {!canTransition && (
        <p className="px-2 py-1 text-xs text-(--text-subtle)">전이 권한이 없습니다.</p>
      )}
      {transitions.map((t) => (
        <Button
          key={t.key}
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canTransition || isSaving}
          // 종료 전이는 결의안을 먼저 받는다 — 여기서 바로 전이하면 필수 입력이 빠진다(FR14)
          onClick={() => (t.toCategory === 'DONE' ? onDoneTransition(t) : onTransition(t.toStateKey))}
          className={CELL_OPTION_CLASS}
        >
          {t.name}
        </Button>
      ))}
    </div>
  )
}
