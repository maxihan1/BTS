// 이슈 상태 전환 셀렉터 + 409 모호 전환 후보 선택 다이얼로그 (IssueMetaPanel 분해 B, FR-IS-01)
import type { JSX } from 'react'
import type { AmbiguousTransitionCandidate, IssueTransition } from '@/api/issues'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useReportModalOpen } from '@/components/keyboard-shortcuts/useOpenModalRegistry'
// IssueMetaPanel.tsx가 정본으로 export하는 타입을 재사용 — type-only import라 컴파일 시
// 소거되므로 IssueMetaPanel.tsx가 이 파일을 값으로 import해도 런타임 순환 문제는 없다
// (meta/IssueCustomFieldsEdit.tsx의 isFieldHidden/isFieldDisabled 재사용 선례 동형).
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'
import { transitionElementKey } from '@/lib/transition-key'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueStateTransition props */
export interface IssueStateTransitionProps {
  /** 현재 상태에서 가용한 전환 목록 */
  transitions: IssueTransition[]
  /**
   * 전환 실행 콜백 — **고른 전환 자체**를 전달한다.
   *
   * `toStateKey` 만 넘기면 같은 상태쌍에 이름만 다른 전환이 둘 있을 때 호출부가 어느 쪽인지
   * 되찾을 수 없다(ADR 2026-08-18 로 `UNIQUE(workflow_id, from, to)` 가 해제됐다).
   * 그러면 서버가 409 `AMBIGUOUS_TRANSITION` 을 내고, 사용자는 **방금 이름으로 고른 것을
   * 다시 고르게** 된다. 전환 객체를 통째로 넘겨 그 왕복을 없앤다.
   */
  onTransition: (transition: IssueTransition) => void
  /** 전환 진행 중 여부 — true 시 셀렉터 disabled (NFR3) */
  isTransitioning: boolean
  /** 전환 컨트롤을 노출할 수 없는 사유 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
  /** 제어값 — `optionValueOf` 가 만든 옵션 값. 전환 시도 후 리셋에 쓴다 (C1 회귀 방지) */
  selectedValue: string
  /** 제어값 변경 콜백 */
  onSelectedValueChange: (value: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 옵션 값
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `<option>` 의 값 — React key 와 **같은 규칙**을 쓴다.
 *
 * 규칙을 둘로 나누면 key 는 전환을 가르는데 value 는 못 가르는 상태가 생기고, 그때 화면은
 * 두 옵션을 그리면서 어느 쪽을 골라도 같은 값을 보낸다. 한 함수로 묶어 그 자리를 없앤다.
 *
 * ★**정본은 `@/lib/transition-key` 의 {@link transitionElementKey} 다.** 종전에는 이 파일이
 * `transitionId ?? key` 를 자기 손으로 갖고 있었는데, 그 폴백은 두 군데서 무너진다 —
 * 같은 상태쌍의 전환 둘은 `key` 가 서로 같고, 서버 계약상 `key` 는 null 일 수도 있다.
 * 정본은 목록 위치를 최후 수단으로 덧붙여 형제 사이의 고유성을 보장한다.
 *
 * 위치가 섞이므로 **값을 렌더 밖으로 들고 나가면 안 된다.** 여기서는 같은 렌더의 같은
 * `transitions` 배열로 옵션을 그리고 되찾으므로 일관된다.
 */
function optionValueOf(transition: IssueTransition, index: number): string {
  return transitionElementKey(transition, index)
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상태 전환 셀렉터 컴포넌트.
 *
 * - 가용전환 0건 + unavailableReason='no-workflow' → 미설정 안내 (스펙 E5 S5)
 * - 가용전환 0건 + unavailableReason='terminal'|null → "더 진행할 전환 없음" 안내 (스펙 E5 S6)
 * - 가용전환 있으면 네이티브 select — IssueTypeSelect 동일 패턴
 * - 첫 옵션은 placeholder(비선택 상태), 전환 선택 시 onTransition(전환 객체) 호출
 * - isTransitioning=true → disabled (중복클릭 방지, NFR3)
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
export function IssueStateTransition({
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason,
  selectedValue,
  onSelectedValueChange,
}: IssueStateTransitionProps): JSX.Element {
  // 가용전환 0건 → 사유에 따라 안내문구 분기 (스펙 E5)
  if (transitions.length === 0) {
    if (unavailableReason === 'no-workflow') {
      return (
        <p className="text-xs text-muted-foreground mt-1">
          {issueDetailStrings.transitionWorkflowNotConfiguredError}
        </p>
      )
    }
    return (
      <p className="text-xs text-muted-foreground mt-1">
        {issueDetailStrings.noTransitionsAvailable}
      </p>
    )
  }

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const optionValue = e.target.value
    // placeholder 옵션 선택 무시
    if (optionValue === '') return
    const selected = transitions.find((t, i) => optionValueOf(t, i) === optionValue)
    // ★가드가 아니라 타입 좁히기다. 옵션은 같은 렌더의 같은 `transitions` 로 그려지므로
    //   `find` 가 빗나갈 수 없다 — 이 분기를 겨냥한 테스트를 쓰려면 도달 불가 상태를
    //   지어내야 하고, 그것이 곧 가짜 그린이다.
    if (selected === undefined) return
    onSelectedValueChange(optionValue)
    onTransition(selected)
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={selectedValue}
      onChange={handleChange}
      disabled={isTransitioning}
      aria-label={issueDetailStrings.transitionSelectLabel}
    >
      <option value="" disabled>
        {issueDetailStrings.transitionSelectLabel}
      </option>
      {transitions.map((t, i) => (
        // ★key 도 value 도 `optionValueOf` 하나를 쓴다. `key`(`from__to`)는 같은 상태쌍의
        //   전환 둘이 서로 **같은 값**이라(ADR 2026-08-18 로 UNIQUE 해제) 중복이 나고,
        //   value 가 `toStateKey` 이던 종전에는 어느 쪽을 골라도 같은 값이 나갔다.
        <option key={optionValueOf(t, i)} value={optionValueOf(t, i)}>
          {t.name}
        </option>
      ))}
    </select>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 409 AMBIGUOUS_TRANSITION 후보 선택 다이얼로그
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 후보 선택 다이얼로그 표시 문구.
 *
 * 안내 본문은 **서버가 준 문구**를 그대로 쓴다(후보 개수가 들어 있어 여기서 다시 조립하면
 * 서버와 갈린다). 여기 있는 것은 서버가 주지 않는 껍데기 라벨뿐이다.
 * 같은 화면의 `ResolutionModal` 이 쓰는 방식과 같다.
 */
// eslint-disable-next-line react-refresh/only-export-components -- 다이얼로그 접근성 이름을 유닛 테스트가 정본으로 참조한다. 문자열만 별도 파일로 빼면 이 파일과 갈라질 자리가 하나 더 생긴다 (IssueMetaPanel.tsx 의 isFieldHidden 공개 선례 동형)
export const ambiguousTransitionStrings = {
  /**
   * 다이얼로그 접근성 이름.
   * ★패리티 계약 §2 — 신규 다이얼로그는 **고유** 이름이어야 e2e strict mode 가 안 깨진다.
   * 기존 이름(`종료 결의안 선택` · `새 이슈 만들기` 등)과 겹치지 않는다.
   */
  dialogTitle: '이동할 전환 선택',
  /** 취소 버튼 라벨. */
  cancel: '취소',
} as const

/** AmbiguousTransitionDialog props */
export interface AmbiguousTransitionDialogProps {
  /** 서버가 돌려준 전환 후보 전량. 순서를 보존해 그대로 그린다 */
  candidates: AmbiguousTransitionCandidate[]
  /** 서버가 만든 안내 문구 — 후보가 몇 개인지까지 이 문구가 말한다 */
  message: string
  /** 재요청 진행 중 여부 — true 면 후보 버튼 disabled (중복 제출 방지, NFR3) */
  isTransitioning: boolean
  /** 후보 선택 콜백 — 고른 후보의 transitionId 를 전달 */
  onSelect: (transitionId: string) => void
  /** 취소 콜백 — 전환을 실행하지 않고 닫는다 */
  onCancel: () => void
}

/**
 * 409 `AMBIGUOUS_TRANSITION` 후보 선택 다이얼로그.
 *
 * 같은 도착 상태로 가는 전환이 여럿이라 서버가 실행을 보류하고 후보를 돌려줬을 때 뜬다
 * (ADR 2026-08-18 §D3). 사용자가 고른 후보의 `transitionId` 로 재요청하면 전환이 실행된다.
 *
 * - 후보는 버튼 목록이다 — 이름 말고 가를 근거가 없어 `<select>` 로 감추면 비교가 어렵다
 * - WCAG AA: 후보 버튼 `min-h-[44px]` 터치 타깃
 * - 열림 상태는 부모가 마운트로 제어한다 (`ResolutionModal` 과 같은 계약)
 *
 * @param props 후보 전량 · 안내 문구 · 진행 상태 · 선택/취소 콜백
 * @returns 후보 선택 다이얼로그
 */
export function AmbiguousTransitionDialog({
  candidates,
  message,
  isTransitioning,
  onSelect,
  onCancel,
}: AmbiguousTransitionDialogProps): JSX.Element {
  // ★열림을 레지스트리에 보고한다. 이 다이얼로그는 입력 요소가 없어 `shouldIgnoreEvent` 를
  //   그대로 통과하므로, 보고하지 않으면 후보를 고르는 중에 이슈 상세 단축키(`i`·`a` …)가
  //   실제 PATCH 를 발행한다 (FR-UX-10 F11 이 같은 형태로 뚫렸던 자리다).
  //   부모가 조건부 렌더로 열림을 제어하므로 마운트 = 열림이다.
  useReportModalOpen(true)

  function handleOpenChange(next: boolean): void {
    if (!next) onCancel()
  }

  return (
    <Dialog open onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{ambiguousTransitionStrings.dialogTitle}</DialogTitle>
          <DialogDescription>{message}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-1.5">
          {candidates.map((candidate) => (
            <Button
              key={candidate.transitionId}
              type="button"
              variant="outline"
              disabled={isTransitioning}
              onClick={() => onSelect(candidate.transitionId)}
              className="w-full justify-start min-h-[44px]"
            >
              {candidate.name}
            </Button>
          ))}
        </div>

        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={onCancel}>
            {ambiguousTransitionStrings.cancel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
