// 이슈 상태 전환 메뉴(현재 상태 배지 → 전환 목록) + 409 모호 전환 후보 선택 다이얼로그 (IssueMetaPanel 분해 B, FR-IS-01 · FR-WF-01 D9)
import type { JSX } from 'react'
import { useState } from 'react'
import { ChevronDownIcon } from 'lucide-react'
import type { AmbiguousTransitionCandidate, IssueTransition } from '@/api/issues'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useReportModalOpen } from '@/components/keyboard-shortcuts/useOpenModalRegistry'
// IssueMetaPanel.tsx가 정본으로 export하는 타입을 재사용 — type-only import라 컴파일 시
// 소거되므로 IssueMetaPanel.tsx가 이 파일을 값으로 import해도 런타임 순환 문제는 없다
// (meta/IssueCustomFieldsEdit.tsx의 isFieldHidden/isFieldDisabled 재사용 선례 동형).
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { statusBadgeVariant } from '@/components/issues/issue-visuals'
import { useStatusMetaByKey } from '@/hooks/use-workflows'
import { issueDetailStrings } from '@/i18n/ko'
import { TransitionMenuList } from './TransitionMenuList'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueStateTransition props */
export interface IssueStateTransitionProps {
  /** 이슈의 현재 상태 키 — 트리거 배지의 이름·색을 여기서 해석한다 */
  currentStateKey: string
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
  /** 전환 진행 중 여부 — true 시 트리거 disabled (NFR3) */
  isTransitioning: boolean
  /** 전환 컨트롤을 노출할 수 없는 사유 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상태 전환 메뉴.
 *
 * - 현재 상태를 범주 색 배지로 보이고, 전환이 있으면 그 배지가 메뉴 트리거다 (Jira 상태 버튼)
 * - 메뉴 본문은 목록 셀과 같은 {@link TransitionMenuList} — 같은 순서 · 같은 자기 루프 묶음
 * - 가용전환 0건 → 배지 + 사유 안내 (스펙 E5 S5·S6)
 * - 고르면 메뉴를 닫고 onTransition(전환 객체). DONE 범주 분기(해결 모달)는 호출부 몫이다
 * - isTransitioning=true → 트리거 disabled (중복클릭 방지, NFR3)
 * - WCAG AA: 트리거 min-h-[44px], 접근성 이름에 현재 상태를 싣는다
 */
export function IssueStateTransition({
  currentStateKey,
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason,
}: IssueStateTransitionProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const statusMetaByKey = useStatusMetaByKey()
  // ★메뉴가 열린 동안 이슈 상세 단축키를 막는다. 본문이 입력 요소 없는 버튼뿐이라
  //   `shouldIgnoreEvent` 를 그대로 통과한다 — AmbiguousTransitionDialog 와 같은 처방.
  useReportModalOpen(open)

  const current = statusMetaByKey.get(currentStateKey)
  const currentName = current?.name ?? currentStateKey
  const badge = (
    <Badge data-testid="issue-state-badge" variant={statusBadgeVariant(current?.category)} className="max-w-full">
      <span className="truncate">{currentName}</span>
    </Badge>
  )

  // 가용전환 0건 → 사유에 따라 안내문구 분기 (스펙 E5)
  if (transitions.length === 0) {
    return (
      <div className="flex flex-col items-start gap-1">
        {badge}
        <p className="text-xs text-muted-foreground">
          {unavailableReason === 'no-workflow'
            ? issueDetailStrings.transitionWorkflowNotConfiguredError
            : issueDetailStrings.noTransitionsAvailable}
        </p>
      </div>
    )
  }

  function handleSelect(transition: IssueTransition): void {
    setOpen(false)
    onTransition(transition)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={isTransitioning}
          aria-label={`${issueDetailStrings.transitionSelectLabel}, ${currentName}`}
          className="min-h-[44px] max-w-full justify-start gap-1 px-1"
        >
          {badge}
          <ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 text-(--text-subtle)" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-2">
        <TransitionMenuList
          transitions={transitions}
          statusMetaByKey={statusMetaByKey}
          disabled={isTransitioning}
          onSelect={handleSelect}
        />
      </PopoverContent>
    </Popover>
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
 * 같은 화면의 `TransitionScreenDialog` 이 쓰는 방식과 같다.
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
 * - 열림 상태는 부모가 마운트로 제어한다 (`TransitionScreenDialog` 과 같은 계약)
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
