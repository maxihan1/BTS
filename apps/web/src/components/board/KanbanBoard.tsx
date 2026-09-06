// 칸반 보드 루트 컴포넌트 — DndContext + 컬럼 배치 + 드래그 이동 오케스트레이션 (FR-BD-01)
import type { JSX } from 'react'
import { useMemo, useRef, useState } from 'react'
import { DndContext, DragOverlay } from '@dnd-kit/core'
import type { Announcements, DragEndEvent, DragStartEvent, DragOverEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import type { BoardDetail, BoardCardFilterParams } from '@/api/boards'
import type { IssueTypeResponse } from '@/api/issue-types'
import { useMoveCard } from '@/hooks/use-move-card'
import type { MoveCardVars } from '@/hooks/use-move-card'
import { useReorderCard } from '@/hooks/use-reorder-card'
import type { ReorderCardVars } from '@/hooks/use-reorder-card'
import { useChangeCardField } from '@/hooks/use-change-card-field'
import type { ChangeCardFieldVars } from '@/hooks/use-change-card-field'
import { useBoardDragSensors } from './board-drag-sensors'
import { BoardColumn } from './BoardColumn'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { ResolutionPickerModal } from './ResolutionPickerModal'
import { resolveDropAction } from './board-drop'
import type { DragActiveMin, DragOverMin, DropAction } from './board-drop'

// board-drop.ts로 이전된 순수 헬퍼 — 기존 소비자(KanbanBoard.test.tsx) 호환을 위해 재노출
// eslint-disable-next-line react-refresh/only-export-components
export { resolveDropAction } from './board-drop'
export type { DragActiveMin, DragOverMin, DropAction } from './board-drop'

// ─────────────────────────────────────────────────────────────────────────────
// KanbanBoard — 대기 이동 정보 타입
// ─────────────────────────────────────────────────────────────────────────────

/** resolution 선택 모달이 열린 동안 보관하는 이동 정보 */
interface PendingMove {
  issueKey: string
  fromColumnId: string
  /** 낙관적 캐시 이동용. 서버 요청이 지목하는 것은 [toStateKey] 다. */
  toColumnId: string
  /** 서버가 읽는 대상 상태 키(R6·R12). 이 상태의 category 가 DONE 이라 모달이 열렸다(R13). */
  toStateKey: string
  expectedVersion: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 접근성 공지(DR2) — resolveDropAction과 동일 판정을 재사용해 실제 결과와 문구를 일치시킨다
// ─────────────────────────────────────────────────────────────────────────────

/** board에서 issueKey에 해당하는 카드 summary를 찾는다. 없으면 undefined. */
function findCardSummary(board: BoardDetail, issueKey: string): string | undefined {
  for (const column of board.columns) {
    const card = column.cards.find((c) => c.issueKey === issueKey)
    if (card !== undefined) return card.summary
  }
  return undefined
}

/** board에서 columnId에 해당하는 컬럼 이름을 찾는다. 못 찾으면 columnId를 그대로 반환한다(방어적 fallback). */
function findColumnName(board: BoardDetail, columnId: string): string {
  return board.columns.find((c) => c.columnId === columnId)?.name ?? columnId
}

// ─────────────────────────────────────────────────────────────────────────────
// 필드변경 announcement 문구 (FR-7, FR-UX-06 PR21b Task 4)
// ─────────────────────────────────────────────────────────────────────────────

/** field-change DropAction 변형만 추출한 타입 별칭 — describeXxxFieldChange 헬퍼 시그니처에 재사용 */
type FieldChangeAction = Extract<DropAction, { type: 'field-change' }>

/** 필드변경 announcement 시제 — onDragOver(예고형 "~변경합니다")·onDragEnd(완료형 "~변경했습니다") 구분 */
type FieldChangeTense = 'preview' | 'done'

/** 우선순위 값을 읽지 못했을 때(방어적) 대체 표시 텍스트 — findColumnName 방어적 fallback 관례와 동일 */
const PRIORITY_VALUE_FALLBACK = '알 수 없음'

/**
 * 시제(tense)에 따라 예고형/완료형 동사 문구 중 하나를 고른다.
 * describeXxxFieldChange 3곳에서 반복되던 `tense === 'preview' ? ... : ...` 분기를 한 곳에 모은다.
 */
function pickByTense(tense: FieldChangeTense, preview: string, done: string): string {
  return tense === 'preview' ? preview : done
}

// ─────────────────────────────────────────────────────────────────────────────
// 한국어 조사("으로"/"로") 판별 헬퍼 (리뷰 S1 — describeXxxFieldChange 3곳의 조사 불일치 통일)
// ─────────────────────────────────────────────────────────────────────────────

/** 한글 음절 블록 시작 코드포인트('가') — 받침 유무 계산의 기준점 */
const HANGUL_SYLLABLE_START = 0xac00
/** 한글 음절 블록 끝 코드포인트('힣') */
const HANGUL_SYLLABLE_END = 0xd7a3
/** 한글 음절 하나가 가질 수 있는 종성(받침) 조합 가짓수 — 종성 없음(1가지) 포함 28가지 */
const HANGUL_JONGSEONG_COUNT = 28

/** 숫자 한 글자(0~9)의 한글 발음 — 우선순위처럼 숫자로 끝나는 단어의 받침 판정에 사용(예: '3' → '삼') */
const DIGIT_KOREAN_READING: Readonly<Record<string, string>> = {
  '0': '영',
  '1': '일',
  '2': '이',
  '3': '삼',
  '4': '사',
  '5': '오',
  '6': '육',
  '7': '칠',
  '8': '팔',
  '9': '구',
}

/**
 * 단어 끝에 붙일 한국어 조사("으로" 또는 "로")를 마지막 글자의 받침 유무로 결정한다.
 *
 * 한국어는 받침(종성)이 있는 글자 뒤엔 "으로", 없는 글자 뒤엔 "로"를 쓴다
 * (예: "박밥으로"는 자연스럽지만 "박밥로"는 어색하다). 이전엔 describeAssigneeFieldChange가
 * 항상 "(으)로", describePriorityFieldChange·describeEpicFieldChange가 항상 bare "로"를
 * 하드코딩해 조사가 불일치했다(리뷰 S1) — 이 헬퍼로 담당자 이름·에픽 키·우선순위 숫자 표기를 통일한다.
 *
 * - 마지막 글자가 숫자(0~9)면 그 숫자의 한글 발음(DIGIT_KOREAN_READING)의 마지막 글자로 판정한다
 *   (예: 우선순위 "1" → "일"의 받침 ㄹ 있음 → "으로").
 * - 마지막 글자가 한글 음절(가~힣)이면 유니코드 코드포인트로 받침 유무를 계산한다
 *   ((코드 - 0xAC00) % 28 !== 0 이면 받침 있음 — 종성 없는 음절이 각 초성×중성 조합의 첫 번째다).
 * - 그 외(빈 문자열·한글도 숫자도 아닌 문자)는 안전한 기본값 "로"를 반환한다.
 *
 * @param word 조사를 붙일 대상 단어(담당자 이름·에픽 키·우선순위 숫자 등)
 * @returns '으로' 또는 '로'
 */
function josaEuro(word: string): string {
  const lastChar = word.at(-1)
  if (lastChar === undefined) return '로'

  const digitReading = DIGIT_KOREAN_READING[lastChar]
  const charToCheck = digitReading !== undefined ? digitReading.at(-1) : lastChar
  const code = charToCheck?.codePointAt(0)
  if (code === undefined || code < HANGUL_SYLLABLE_START || code > HANGUL_SYLLABLE_END) return '로'

  const hasBatchim = (code - HANGUL_SYLLABLE_START) % HANGUL_JONGSEONG_COUNT !== 0
  return hasBatchim ? '으로' : '로'
}

/**
 * assigneeId(UUID)를 가진 카드를 board에서 찾아 assigneeNames 표시 이름을 조회한다.
 * 그룹 key(이름 기반)가 아니라 카드 자신의 실제 assigneeId로 조회하므로
 * 이름 기반 그룹 오분류(스펙 G1: unknown/동명이인 혼재)에 영향받지 않는다.
 *
 * @returns named 상태의 이름. 카드를 못 찾거나 named 상태가 아니면 undefined
 */
function findAssigneeDisplayName(
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  assigneeId: string,
): string | undefined {
  for (const column of board.columns) {
    const card = column.cards.find((c) => c.assigneeId === assigneeId)
    if (card === undefined) continue
    const display = assigneeNames.get(card.issueKey)
    if (display?.state === 'named') return display.name
  }
  return undefined
}

/**
 * ASSIGNEE 필드변경 announcement 문구 — 담당자 재할당/해제.
 *
 * toAssigneeId가 있어도(재할당) assigneeNames에서 이름을 못 찾으면(unknown·그룹 미확인)
 * 이름 없이 중립 문구로 안내한다 — "미배정"으로 대체하면 실제 담당자 해제(toAssigneeId===null)와
 * 같은 단어가 되어 스크린리더 사용자가 두 상황을 구분할 수 없다(리뷰 S2).
 */
function describeAssigneeFieldChange(
  action: FieldChangeAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  tense: FieldChangeTense,
): string {
  if (action.toAssigneeId === null || action.toAssigneeId === undefined) {
    return `${action.issueKey}의 담당자를 ${pickByTense(tense, '해제합니다', '해제했습니다')}.`
  }
  const changeVerb = pickByTense(tense, '변경합니다', '변경했습니다')
  const name = findAssigneeDisplayName(board, assigneeNames, action.toAssigneeId)
  if (name === undefined) {
    return `${action.issueKey}의 담당자를 ${changeVerb}`
  }
  return `${action.issueKey}을(를) 담당자 ${name}${josaEuro(name)} ${changeVerb}`
}

/** PRIORITY 필드변경 announcement 문구 */
function describePriorityFieldChange(action: FieldChangeAction, tense: FieldChangeTense): string {
  const priorityLabel = action.toPriority !== undefined ? String(action.toPriority) : PRIORITY_VALUE_FALLBACK
  const changeVerb = pickByTense(tense, '변경합니다', '변경했습니다')
  return `${action.issueKey}의 우선순위를 ${priorityLabel}${josaEuro(priorityLabel)} ${changeVerb}`
}

/** EPIC 필드변경 announcement 문구 — 에픽 재배치/해제 */
function describeEpicFieldChange(action: FieldChangeAction, tense: FieldChangeTense): string {
  if (action.toEpicKey === null || action.toEpicKey === undefined) {
    return `${action.issueKey}의 에픽 연결을 ${pickByTense(tense, '해제합니다', '해제했습니다')}.`
  }
  const moveVerb = pickByTense(tense, '이동합니다', '이동했습니다')
  return `${action.issueKey}을(를) 에픽 ${action.toEpicKey}${josaEuro(action.toEpicKey)} ${moveVerb}`
}

/**
 * field-change 액션을 시제(tense)에 맞춘 한국어 announcement 문구로 변환한다 (FR-7).
 * 필드별 문구는 describeAssigneeFieldChange/describePriorityFieldChange/describeEpicFieldChange에 위임한다.
 */
function describeFieldChangeAction(
  action: FieldChangeAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  tense: FieldChangeTense,
): string {
  switch (action.field) {
    case 'assignee':
      return describeAssigneeFieldChange(action, board, assigneeNames, tense)
    case 'priority':
      return describePriorityFieldChange(action, tense)
    case 'epic':
      return describeEpicFieldChange(action, tense)
    default: {
      const exhaustiveCheck: never = action.field
      return exhaustiveCheck
    }
  }
}

/**
 * DropAction을 드래그 진행 중(present) 공지 문구로 변환한다 — onDragOver announcement용.
 * 아직 확정되지 않은 위치를 안내한다.
 */
function describeDragOverAction(
  action: DropAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼 위에 있습니다.`
    case 'reorder':
      return `${findColumnName(board, action.columnId)} 안에서 순서를 조정하고 있습니다.`
    case 'field-change':
      return describeFieldChangeAction(action, board, assigneeNames, 'preview')
    case 'noop':
      return '이동할 수 없는 위치입니다.'
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

/**
 * DropAction을 드래그 완료(past) 공지 문구로 변환한다 — onDragEnd announcement용.
 * 실제로 반영될 변경 결과를 안내한다.
 */
function describeDragEndAction(
  action: DropAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼으로 이동했습니다.`
    case 'reorder':
      return '순서를 변경했습니다.'
    case 'field-change':
      return describeFieldChangeAction(action, board, assigneeNames, 'done')
    case 'noop':
      return '변경 사항이 없습니다.'
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

/**
 * DndContext `accessibility.announcements` — 드래그 상호작용을 한국어로 스크린리더에 공지한다(DR2).
 *
 * resolveDropAction과 동일한 판정 로직을 그대로 재사용해, 실제로 반영되는 동작(move/reorder/noop)과
 * 공지 문구가 어긋나지 않도록 한다.
 *
 * @param board 현재 BoardDetail — 카드 summary/컬럼 이름 조회에 사용
 * @param assigneeNames 스윔레인 그룹 판정용 담당자 표시 맵(resolveDropAction과 동일 인자)
 */
function buildDragAnnouncements(
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): Announcements {
  return {
    onDragStart({ active }) {
      const label = findCardSummary(board, String(active.id)) ?? String(active.id)
      return `${label} 카드를 집었습니다.`
    },
    onDragOver({ active, over }) {
      if (over === null) return '드롭 가능한 영역을 벗어났습니다.'
      const action = resolveDropAction(board, active as DragActiveMin, over as DragOverMin, assigneeNames)
      return describeDragOverAction(action, board, assigneeNames)
    },
    onDragEnd({ active, over }) {
      const action = resolveDropAction(board, active as DragActiveMin, over as DragOverMin | null, assigneeNames)
      return describeDragEndAction(action, board, assigneeNames)
    },
    onDragCancel() {
      return '취소했습니다.'
    },
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 카드 레이아웃 배선 — prop 이 네 겹을 지난다 (부채 177 Task 32 · J17·J18·J19)
//
// 보드 체인은 이렇다.
//   `routes/projects.$projectKey.board.tsx`(`useBoard` 로 설정을 읽는다)
//     → `KanbanBoard`(여기 · `board.cardLayout` 을 꺼낸다)
//       → `BoardColumn`(두 렌더 분기 + 드래그 고스트)
//         → `BoardCard`
//           → `CardExtraFields`(`BOARD` 스코프만 읽어 2층을 그린다)
// 백로그 체인은 `BacklogBoard` 가 같은 구성을 자기 조회로 읽어 두 칸에 나른다(그 파일 참조).
//
// ## 왜 context 로 안 바꾸나
//
// 겹이 깊다는 이유로 여기만 context 로 바꾸면 **관례가 갈린다.** 이 화면의 다른 축
// (`assigneeNames` · `issueTypesByKey` · `isFilterActive` · `swimlaneField`)이 전부 같은
// 깊이의 prop drilling 이고, 백로그도 마찬가지다. 하나만 context 가 되면 다음 사람이
// 「무엇이 context 이고 무엇이 prop 인가」를 매번 확인해야 하고, 그 확인을 빠뜨린 자리가
// 다시 배선 부재가 된다 — 이 task 가 닫는 결함이 정확히 그것이다.
// 옮기려면 네 축을 **함께** 옮겨야 하고, 그것은 이 PR 의 범위가 아니다.
//
// ## 중간 겹이 값을 읽지 않는다는 규약
//
// `KanbanBoard`·`BoardColumn` 은 `cardLayout` 을 **해석하지 않는다.** 어느 스코프를 그릴지는
// 카드 한 곳에서만 판정한다(Task 20). 중간에서 `?? []` 나 `BOARD ?? BACKLOG` 같은 보정을
// 얹는 순간 판정이 두 곳이 되고, 그때부터 어느 쪽이 옳은지 아무도 모른다.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** KanbanBoard Props */
export interface KanbanBoardProps {
  /** 보드 UUID */
  boardId: string
  /** 보드 상세 데이터 (컬럼 + 카드 포함) */
  board: BoardDetail
  /**
   * 이슈 키 → 담당자 표시 상태 맵 (3-상태 discriminated union).
   * 페이지가 userId → displayName 해석 후 주입한다.
   */
  assigneeNames: Map<string, CardAssigneeDisplay>
  /**
   * 현재 적용된 카드 필터 (FR-BD-02).
   * useMoveCard로 전달해 filter-aware queryKey와 낙관적 업데이트가 정합되도록 한다.
   * 옵셔널 — 없으면 undefined로 전달 (기존 호출 호환).
   */
  filter?: BoardCardFilterParams
  /**
   * 보드 필터 활성 여부 (FR-BD-03 hotfix-p2).
   * true이면 각 BoardColumn의 WIP 초과 경고를 약화 + "(필터됨)" 표시.
   * 이동/판정 로직에는 영향 없음 — 표시만 변경.
   */
  isFilterActive?: boolean
  /**
   * 이슈 타입 key → IssueTypeResponse 맵 (FR-UX-14 F14, E9).
   * 라우트가 `useIssueTypes()` 조회 결과로 1회만 구성해 주입한다(NFR2) — 각 BoardColumn과
   * 드래그 고스트 카드(DragOverlay) 양쪽에 그대로 전달한다.
   */
  issueTypesByKey: Map<string, IssueTypeResponse>
}

// ─────────────────────────────────────────────────────────────────────────────
// 드래그 시각 힌트 (FR-8, FR-UX-06 PR21b Task 5)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * resolveDropAction 판정 결과로부터 하이라이트할 컬럼 id를 계산한다 — handleDragOver 전용.
 *
 * onDragEnd와 동일하게 resolveDropAction을 재사용해, 실제로 드롭 가능한 대상일 때만
 * 컬럼을 하이라이트한다(noop이면 하이라이트하지 않는다).
 *
 * field-change(스윔레인 그룹 간 필드변경)는 셀은 다르지만 항상 같은 컬럼 내부에서 일어나므로
 * DropAction에 columnId가 없다 — 드래그 시작 컬럼(activeFromColumnId)을 그대로 쓴다.
 * 이전(PR21)엔 그룹 경계 드래그를 noop 취급해 컬럼 하이라이트를 억제했지만, field-change가
 * 유효한 동작이 된 지금은 그 억제를 반전해 정상적으로 하이라이트한다.
 *
 * @param action resolveDropAction이 반환한 판정 결과
 * @param activeFromColumnId 드래그 시작 컬럼 id (handleDragStart가 기록한 state)
 * @returns 하이라이트할 컬럼 id. 하이라이트하지 않으면 null
 */
function resolveHighlightColumnId(action: DropAction, activeFromColumnId: string | null): string | null {
  switch (action.type) {
    case 'noop':
      return null
    case 'move':
    case 'needs-resolution':
      return action.toColumnId
    case 'reorder':
      return action.columnId
    case 'field-change':
      return activeFromColumnId
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 드롭 액션 실행 (dispatchDropAction) — 컴포넌트 밖으로 분리해 KanbanBoard 함수 길이를 줄인다.
// mutate 함수들은 컴포넌트 안 state/훅을 직접 참조하지 않고 deps로 주입받는다(순수 함수 유지).
// ─────────────────────────────────────────────────────────────────────────────

type MoveCardMutate = ReturnType<typeof useMoveCard>['mutate']
type ReorderCardMutate = ReturnType<typeof useReorderCard>['mutate']
type ChangeCardFieldMutate = ReturnType<typeof useChangeCardField>['mutate']

/** dispatchDropAction이 실제 부수효과를 실행하는 데 필요한 의존성 묶음. */
interface DropActionDeps {
  moveCardMutate: MoveCardMutate
  reorderCardMutate: ReorderCardMutate
  changeCardFieldMutate: ChangeCardFieldMutate
  setPendingMove: (move: PendingMove) => void
}

/** 카드를 다른 컬럼으로 이동한다(useMoveCard.mutate). 409 등 에러는 toast로 안내한다. */
function executeMutate(vars: MoveCardVars, moveCardMutate: MoveCardMutate): void {
  moveCardMutate(vars, {
    onError: (err: unknown) => {
      // 409 OCC 충돌 등 에러 — 사용자에게 안내 (롤백+invalidate는 useMoveCard 내부 처리)
      void err
      toast.error('다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요.')
    },
  })
}

/**
 * 셀(컬럼 × 스윔레인 그룹) 내에서 카드 순서를 변경한다(useReorderCard.mutate).
 * 409 충돌 등 에러 toast는 useReorderCard 내부에서 처리한다(중복 안내 방지).
 */
function executeReorder(action: Extract<DropAction, { type: 'reorder' }>, reorderCardMutate: ReorderCardMutate): void {
  const vars: ReorderCardVars = {
    issueKey: action.issueKey,
    columnId: action.columnId,
    previousIssueKey: action.previousIssueKey,
    nextIssueKey: action.nextIssueKey,
  }
  reorderCardMutate(vars)
}

/**
 * 스윔레인 그룹 간 드롭으로 카드의 담당자·우선순위·에픽을 변경한다(useChangeCardField.mutate).
 * action의 필드를 그대로 매핑한다 — field별로 쓰이지 않는 값은 undefined로 전달되며
 * useChangeCardField가 field로 분기해 처리한다(FR-UX-06 PR21b Task 5).
 * 409 충돌 등 에러 toast는 useChangeCardField 내부에서 처리한다(중복 안내 방지).
 */
function executeChangeField(
  action: Extract<DropAction, { type: 'field-change' }>,
  changeCardFieldMutate: ChangeCardFieldMutate,
): void {
  const vars: ChangeCardFieldVars = {
    issueKey: action.issueKey,
    field: action.field,
    toAssigneeId: action.toAssigneeId,
    toPriority: action.toPriority,
    toEpicKey: action.toEpicKey,
    fromEpicKey: action.fromEpicKey,
    expectedVersion: action.expectedVersion,
  }
  changeCardFieldMutate(vars)
}

/**
 * resolveDropAction 판정 결과(DropAction)에 따라 실제 부수효과를 실행한다.
 *
 * - `noop` → 아무 것도 하지 않는다.
 * - `reorder` → executeReorder(useReorderCard.mutate 즉시 호출) — 셀 내 순서변경.
 * - `needs-resolution` → pendingMove를 채워 ResolutionPickerModal을 연다(확인 시 executeMutate).
 * - `move` → executeMutate(useMoveCard.mutate 즉시 호출) — 다른 컬럼(non-DONE)으로 이동.
 * - `field-change` → executeChangeField(useChangeCardField.mutate 즉시 호출) — 스윔레인 그룹
 *   간 드롭으로 담당자·우선순위·에픽을 변경(FR-UX-06 PR21b Task 5).
 *
 * @param action resolveDropAction이 반환한 판정 결과
 * @param deps mutate 함수 3종 + setPendingMove 묶음(handleDragEnd가 주입)
 */
function dispatchDropAction(action: DropAction, deps: DropActionDeps): void {
  switch (action.type) {
    case 'noop':
      return
    case 'reorder':
      executeReorder(action, deps.reorderCardMutate)
      return
    case 'needs-resolution':
      deps.setPendingMove({
        issueKey: action.issueKey,
        fromColumnId: action.fromColumnId,
        toColumnId: action.toColumnId,
        toStateKey: action.toStateKey,
        expectedVersion: action.expectedVersion,
      })
      return
    case 'move':
      executeMutate(
        {
          issueKey: action.issueKey,
          fromColumnId: action.fromColumnId,
          toColumnId: action.toColumnId,
          toStateKey: action.toStateKey,
          expectedVersion: action.expectedVersion,
        },
        deps.moveCardMutate,
      )
      return
    case 'field-change':
      executeChangeField(action, deps.changeCardFieldMutate)
      return
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// KanbanBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

const UNASSIGNED: CardAssigneeDisplay = { state: 'unassigned' }

/**
 * 칸반 보드 루트 컴포넌트.
 *
 * - DndContext 안에 컬럼들을 displayOrder asc 정렬로 가로 배치한다.
 * - DragOverlay로 드래그 중 카드 미리보기를 제공한다.
 * - onDragEnd에서 resolveDropAction을 호출해 이동 유형을 판단하고 dispatchDropAction에 위임한다.
 *   - move → useMoveCard.mutate 즉시 호출
 *   - needs-resolution → ResolutionPickerModal 오픈, 확인 시 mutate
 *   - reorder → useReorderCard.mutate 즉시 호출(셀 내 순서변경)
 *   - field-change → useChangeCardField.mutate 즉시 호출(스윔레인 그룹 간 담당자·우선순위·에픽 변경, FR-UX-06 PR21b)
 *   - noop → 아무 동작 없음
 * - onDragOver도 resolveDropAction을 재사용해, 실제로 드롭 가능한 대상일 때만 컬럼을
 *   하이라이트한다(FR-8) — field-change도 유효한 드롭이므로 하이라이트된다.
 * - 409 충돌 등 에러 시 toast.error를 표시한다(reorder·field-change는 각 훅 내부에서 처리).
 * - accessibility.announcements로 드래그 상호작용을 한국어로 스크린리더에 공지한다(DR2).
 * - 센서: PointerSensor(distance:5) + KeyboardSensor — 클릭과 드래그 구분(D-2).
 */
export function KanbanBoard({ boardId, board, assigneeNames, filter, isFilterActive = false, issueTypesByKey }: KanbanBoardProps): JSX.Element {
  const [activeId, setActiveId] = useState<string | null>(null)
  const [activeFromColumnId, setActiveFromColumnId] = useState<string | null>(null)
  const [overColumnId, setOverColumnId] = useState<string | null>(null)
  const [pendingMove, setPendingMove] = useState<PendingMove | null>(null)

  // filter-aware useMoveCard/useReorderCard/useChangeCardField — filter와 동일한 queryKey를
  // 공유해 낙관적 업데이트 정합 (field-change는 FR-UX-06 PR21b Task 5)
  const moveCard = useMoveCard(boardId, filter)
  const reorderCard = useReorderCard(boardId, filter)
  const changeCardField = useChangeCardField(boardId, filter)

  // PointerSensor: distance 5px 이상 이동해야 드래그 시작 → 카드 Link 클릭 보존 (D-2)
  // 구성 자체는 `board-drag-sensors.ts` 가 정본이다 — 보드 설정 화면도 같은 것을 쓴다.
  // 복사본을 두 벌 두면 한쪽만 고쳐지고, 고쳐지지 않은 쪽은 키보드 사용자에게만 고장 난다.
  const sensors = useBoardDragSensors()

  // displayOrder asc 정렬 — 원본 board.columns 변경 금지 (immutable)
  const sortedColumns = [...board.columns].sort((a, b) => a.displayOrder - b.displayOrder)

  // 드래그 중 active 카드 데이터
  const activeCard = activeId !== null && activeFromColumnId !== null
    ? board.columns
        .find((c) => c.columnId === activeFromColumnId)
        ?.cards.find((c) => c.issueKey === activeId)
    : undefined

  // 접근성 공지(DR2) — board/assigneeNames가 바뀔 때만 재계산(불필요한 재구독 방지)
  const announcements = useMemo(() => buildDragAnnouncements(board, assigneeNames), [board, assigneeNames])

  // handleDragOver 재계산 가드(리뷰 S4) — dnd-kit onDragOver는 포인터가 조금만 움직여도 자주
  // 발생하는데, active/over id 쌍이 직전 호출과 같으면 resolveDropAction(내부적으로 대형 컬럼에서
  // groupCardsBySwimlane을 2~3회 호출) 재계산 결과도 항상 같다 — 무의미한 재계산을 건너뛴다.
  // 드래그 시작/종료마다 초기화해 이전 드래그의 값과 우연히 겹치지 않게 한다.
  const lastDragOverRef = useRef<{ activeId: string; overId: string | null } | null>(null)

  /** dnd-kit onDragStart — 드래그 중인 카드의 출발 컬럼을 기록한다. */
  function handleDragStart(event: DragStartEvent): void {
    setActiveId(String(event.active.id))
    const current = event.active.data.current as { fromColumnId?: string } | undefined
    setActiveFromColumnId(current?.fromColumnId ?? null)
    lastDragOverRef.current = null
  }

  /**
   * dnd-kit onDragOver — 하이라이트할 컬럼 id를 계산한다.
   *
   * resolveDropAction과 동일 판정을 재사용해(handleDragEnd와 같은 로직), 실제로 드롭 가능한
   * 대상일 때만 하이라이트한다(FR-8). field-change(스윔레인 그룹 간 드롭)도 유효한 드롭이므로
   * 이제 하이라이트된다 — PR21이 그룹 경계 드래그를 noop 취급해 억제하던 로직을 반전했다.
   *
   * active/over id 쌍이 직전 호출과 동일하면 resolveDropAction을 다시 부르지 않는다(리뷰 S4).
   */
  function handleDragOver(event: DragOverEvent): void {
    const activeId = String(event.active.id)
    const overId = event.over !== null ? String(event.over.id) : null
    const last = lastDragOverRef.current
    if (last !== null && last.activeId === activeId && last.overId === overId) return
    lastDragOverRef.current = { activeId, overId }

    const action = resolveDropAction(
      board,
      event.active as DragActiveMin,
      event.over as DragOverMin | null,
      assigneeNames,
    )
    setOverColumnId(resolveHighlightColumnId(action, activeFromColumnId))
  }

  /**
   * dnd-kit onDragEnd — 드래그 상태를 초기화하고 resolveDropAction 판정 결과를
   * dispatchDropAction에 위임한다.
   */
  function handleDragEnd(event: DragEndEvent): void {
    setActiveId(null)
    setActiveFromColumnId(null)
    setOverColumnId(null)
    lastDragOverRef.current = null

    const action = resolveDropAction(
      board,
      event.active as DragActiveMin,
      event.over as DragOverMin | null,
      assigneeNames,
    )

    dispatchDropAction(action, {
      moveCardMutate: moveCard.mutate,
      reorderCardMutate: reorderCard.mutate,
      changeCardFieldMutate: changeCardField.mutate,
      setPendingMove,
    })
  }

  function handleResolutionConfirm(resolutionId: string): void {
    if (pendingMove === null) return
    executeMutate({ ...pendingMove, resolutionId }, moveCard.mutate)
    setPendingMove(null)
  }

  function handleResolutionCancel(): void {
    setPendingMove(null)
  }

  return (
    <>
      <DndContext
        sensors={sensors}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        accessibility={{ announcements }}
      >
        {/* 가로 스크롤 컨테이너 — 모바일 first, 반응형 가로 스크롤 (D-3) */}
        <div className="flex gap-4 overflow-x-auto pb-4">
          {sortedColumns.map((column) => (
            <BoardColumn
              key={column.columnId}
              column={column}
              assigneeNames={assigneeNames}
              isOver={overColumnId === column.columnId}
              swimlaneField={board.swimlaneField}
              isFilterActive={isFilterActive}
              issueTypesByKey={issueTypesByKey}
              // 카드 레이아웃 구성 — 컬럼을 지나 카드까지 내려간다 (부채 177 Task 32 · J17).
              // ★새 조회가 아니라 **이미 손에 있는 `board`** 에서 꺼낸다. 보드 조회 응답이
              //   설정을 함께 싣기 때문이다(Task 31 · N1) — 탭마다 왕복을 만들지 않는다.
              cardLayout={board.cardLayout}
            />
          ))}
        </div>

        {/* 드래그 중 카드 미리보기 */}
        <DragOverlay>
          {activeCard !== undefined && activeFromColumnId !== null ? (
            <BoardCard
              card={activeCard}
              columnId={activeFromColumnId}
              assignee={assigneeNames.get(activeCard.issueKey) ?? UNASSIGNED}
              typeIconName={issueTypesByKey.get(activeCard.typeKey)?.iconName ?? null}
              typeName={issueTypesByKey.get(activeCard.typeKey)?.name ?? activeCard.typeKey}
              // 드래그 고스트도 **같은 구성**을 받는다 — 빠뜨리면 집어 올리는 순간 카드가
              // 한 층 줄어 들며 크기가 튄다 (`issueTypesByKey` 를 여기에도 넘기는 것과 같은 이유).
              cardLayout={board.cardLayout}
            />
          ) : null}
        </DragOverlay>
      </DndContext>

      {/* DONE 컬럼 이동 시 resolution 선택 모달 */}
      <ResolutionPickerModal
        open={pendingMove !== null}
        onConfirm={handleResolutionConfirm}
        onCancel={handleResolutionCancel}
      />
    </>
  )
}
