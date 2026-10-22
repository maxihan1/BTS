// 이슈 목록 상태 셀 — 클릭해 가용 전환을 고른다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import type { IssueResponse, IssueTransition, TransitionScreenValues } from '@/api/issues'
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'
import { Badge } from '@/components/ui/badge'
import { statusBadgeVariant } from '../issue-visuals'
import type { StateCategory } from '../issue-visuals'
import type { StatusMeta } from '../IssueTable'
import { TransitionScreenDialog } from '@/components/issue/TransitionScreenDialog'
import { TransitionMenuList } from '@/components/issue/meta/TransitionMenuList'
import { useIssueTransitions } from '@/hooks/use-issue-transitions'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { useIssueListCellField } from '@/hooks/use-issue-list-cell-field'
import { useStatusMetaByKey } from '@/hooks/use-workflows'
import { resolveTransitionUnavailableReason } from '@/lib/transition-availability'
import { EditableCell } from './EditableCell'

/**
 * 닫힌 상태에서 보이는 상태 배지.
 *
 * ★`role="status"` 는 **즉사 계약**이다(패리티 계약 §2). `issue-columns.ts` 의 기존
 * `renderStatusCell` 이 달고 있었고 e2e 가 셀렉터로 쓴다(`issue-filter.spec.ts` 는
 * 체크박스를 `role=checkbox` 로 한정해 이 배지와의 충돌을 피하고 있다). 편집 트리거로
 * 감싸도 이 role 과 className 이 배지에 그대로 남아야 한다.
 *
 * 배지 자체는 `ui/badge.tsx` 프리미티브에 위임한다(계약 §4 — 새로 만들지 않는다). 종전의
 * 손수 만든 span 은 `variant="neutral"` 과 같은 모양이라 회귀 0 이다.
 *
 * @param props 현재 상태 키 · 해석된 이름 · 해석된 카테고리
 * @returns 상태 배지
 */
export function StatusCellDisplay({
  currentStateKey,
  statusName,
  category,
}: {
  /** 백엔드가 준 원시 상태 키 — 이름 해석 실패 시의 폴백 표기다 */
  currentStateKey: string
  /** 워크플로우에서 해석한 표시 이름. 미해석이면 undefined */
  statusName?: string
  /** 워크플로우에서 해석한 상태 카테고리. 미해석이면 undefined → 중립색 */
  category?: StateCategory
}): JSX.Element {
  const label = statusName ?? currentStateKey

  return (
    // 배지는 `inline-flex` 라 배지 자신에게는 `text-ellipsis` 가 걸리지 않는다. 폭 상한을
    // 셀에 맞추고(`max-w-full`) 안쪽 텍스트를 잘라야 좁힌 열에서 줄임표가 나온다.
    // `title` — 잘린 전체 이름을 hover 로 볼 수 있게 한다(요약 셀과 같은 처방).
    <Badge role="status" variant={statusBadgeVariant(category)} className="max-w-full" title={label}>
      <span className="truncate">{label}</span>
    </Badge>
  )
}

/** StatusCellEditor props */
export interface StatusCellEditorProps {
  /** 현재 상태에서 가용한 전환 목록 — props 파생, useState 초기화 금지 (stale state 회귀 방지) */
  transitions: IssueTransition[]
  /** 상태 키 → 표시 이름·카테고리 — 도착 상태 배지의 출처 (상세 화면 메뉴와 같은 맵) */
  statusMetaByKey: Map<string, StatusMeta>
  /** 전환 0건일 때의 사유 — 미설정(422)과 종료 상태(빈 배열)를 가른다 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
  /** 전환 권한 여부 — false 면 전 선택지 disabled (fail-closed) */
  canTransition: boolean
  /** 저장 진행 중 — true 면 중복 제출을 막기 위해 disabled (NFR3) */
  isSaving: boolean
  /** 전환 조회 진행 중 — true 면 "전환 없음" 을 띄우지 않는다 (AssigneeCellEditor 와 동일 판단) */
  isLoading: boolean
  /** 화면 없는 전환 — 즉시 실행. toStateKey 전달 (IssueStateTransition 과 동일 시그니처) */
  onTransition: (toStateKey: string) => void
  /**
   * ★화면이 있는 전환(`screen` 이 비지 않음) — 즉시 실행하지 않고 전환 화면 입력을 요청한다 (FR-WF-01 D11).
   *
   * 상세 화면 `issues.$key.tsx` 의 `handleTransition` 이 같은 분기로 `TransitionScreenDialog` 를 띄운다.
   * 선택(optional)으로 두면 호출자가 빠뜨렸을 때 화면 있는 전환이 조용히 아무 일도 하지 않으므로
   * **필수**로 둔다.
   */
  onScreenTransition: (transition: IssueTransition) => void
}

/**
 * popover 안에 뜨는 가용 전환 목록.
 *
 * 전환 목록 자체는 상세 화면 `IssueStateTransition` 과 **같은 컴포넌트**({@link TransitionMenuList})
 * 다 — 두 경로가 같은 순서 · 같은 범주 색 · 같은 자기 루프 묶음을 보인다 (FR-WF-01 D9).
 * 안내 문구 정본(`transitionWorkflowNotConfiguredError` · `noTransitionsAvailable`)도 같은 것을 쓴다.
 *
 * 조립(`EditableCell` 로 감싸기 + 조회 훅 배선 + `TransitionScreenDialog`)은 {@link StatusCell} 소관이다 —
 * 이 컴포넌트는 순수 프레젠테이션이라 조회 훅을 갖지 않는다.
 *
 * @param props 전환 목록 · 불가 사유 · 권한/저장/조회 상태 · 전환 콜백 2종
 * @returns 가용 전환 버튼 목록 또는 사유 안내 문구
 */
export function StatusCellEditor({
  transitions,
  statusMetaByKey,
  unavailableReason,
  canTransition,
  isSaving,
  isLoading,
  onTransition,
  onScreenTransition,
}: StatusCellEditorProps): JSX.Element {
  // ★조회 중에는 "전환 없음" 을 띄우지 않는다 — 아직 모르는 것을 없다고 말하면 거짓이다.
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
        <p className="px-2 py-1 text-xs text-(--text-subtle)">전환 권한이 없습니다.</p>
      )}
      <TransitionMenuList
        transitions={transitions}
        statusMetaByKey={statusMetaByKey}
        disabled={!canTransition || isSaving}
        // 화면이 있는 전환은 입력을 먼저 받는다 — 여기서 바로 전환하면 필수 입력이 빠진다(D11)
        onSelect={(t) => ((t.screen ?? []).length > 0 ? onScreenTransition(t) : onTransition(t.toStateKey))}
      />
    </div>
  )
}

/** StatusCell props */
export interface StatusCellProps {
  /** 대상 이슈 — 현재 상태·`expectedVersion`·기존 결의안의 출처 */
  issue: IssueResponse
  /** 목록 queryKey — mutation 이 이 캐시를 낙관적으로 patch 한다 */
  listQueryKey: QueryKey
  /** 워크플로우에서 해석한 상태 표시 이름. 미해석이면 원시 키로 폴백한다 */
  statusName?: string
  /** 워크플로우에서 해석한 상태 카테고리 — 배지 색의 근거 */
  category?: StateCategory
}

/** StatusCellPopoverBody props */
interface StatusCellPopoverBodyProps {
  issue: IssueResponse
  isSaving: boolean
  onTransition: (toStateKey: string) => void
  onScreenTransition: (transition: IssueTransition) => void
}

/**
 * popover 가 열렸을 때만 마운트된다 — 전환·권한 조회가 여기서만 발생한다 (FR12·NFR1).
 *
 * 이 컴포넌트를 `EditableCell` **밖으로** 끌어올리면 목록 초기 렌더에서 행 수만큼 전환·권한
 * 조회가 터진다(D-3 가 막으려던 N+1 그 자체). `IssueTable.test.tsx` 의 FR12 가드가 잡는다.
 *
 * @param props 대상 이슈 · 저장 진행 여부 · 전환 콜백 2종
 * @returns 가용 전환 목록 또는 사유 안내
 */
function StatusCellPopoverBody({
  issue,
  isSaving,
  onTransition,
  onScreenTransition,
}: StatusCellPopoverBodyProps): JSX.Element {
  const { data: transitions = [], isLoading, isError, error } = useIssueTransitions(issue.key)
  const permissions = useIssuePermissions(issue.key)
  const statusMetaByKey = useStatusMetaByKey()

  return (
    <StatusCellEditor
      transitions={transitions}
      statusMetaByKey={statusMetaByKey}
      // ★공용 헬퍼 — 422(워크플로우 미설정)와 200+빈 배열(종료 상태)을 가른다 (FR8·E16)
      unavailableReason={resolveTransitionUnavailableReason({
        isError,
        error,
        transitionCount: transitions.length,
      })}
      // fail-closed — 권한이 확정되기 전에는 false (D-6)
      canTransition={permissions.data?.permissions.TRANSITION === true}
      isLoading={isLoading || permissions.isLoading}
      isSaving={isSaving}
      onTransition={onTransition}
      onScreenTransition={onScreenTransition}
    />
  )
}

/**
 * 상태 셀 조립 — 배지(닫힘) + 전환 목록(열림) + 전환 화면 대화상자.
 *
 * mutation 훅은 popover **밖**(이 컴포넌트)에 둔다. 저장은 popover 를 닫은 뒤 시작하고
 * 화면 있는 전환은 popover 가 닫힌 뒤 대화상자에서 확정되므로, 훅이 popover 안에 있으면 관찰자가
 * 이미 언마운트된 상태가 된다. `useMutation` 은 네트워크를 유발하지 않으므로 밖에 두어도
 * FR12(조회 지연)와 무관하다.
 *
 * @param props 대상 이슈 · 목록 queryKey
 * @returns 편집 가능한 상태 셀
 */
export function StatusCell({ issue, listQueryKey, statusName, category }: StatusCellProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const [pendingScreen, setPendingScreen] = useState<IssueTransition | null>(null)
  const mutation = useIssueListCellField(listQueryKey)

  /**
   * 전환 실행 — 화면이 있던 전환이면 대화상자 값을 함께 싣는다.
   *
   * @param toStateKey 목표 상태 키
   * @param screenValues 전환 화면 값. 화면 없는 전환이면 undefined
   */
  function runTransition(toStateKey: string, screenValues?: TransitionScreenValues): void {
    mutation.mutate({
      issueKey: issue.key,
      field: 'status',
      toStatusKey: toStateKey,
      expectedVersion: issue.version,
      screenValues,
    })
  }

  /** 화면 없는 전환 — 먼저 닫고 저장한다. 낙관적 patch 라 닫아도 결과가 배지에 즉시 보인다 */
  function handleTransition(toStateKey: string): void {
    setOpen(false)
    runTransition(toStateKey)
  }

  /** 화면 있는 전환 선택 — popover 를 닫고 전환 화면으로 넘긴다 (D11) */
  function handleScreenTransition(transition: IssueTransition): void {
    setOpen(false)
    setPendingScreen(transition)
  }

  /** 전환 화면 확정 — 입력값을 실어 전환한다 */
  function handleScreenConfirm(values: TransitionScreenValues): void {
    if (pendingScreen === null) return
    runTransition(pendingScreen.toStateKey, values)
    setPendingScreen(null)
  }

  return (
    <>
      <EditableCell
        open={open}
        onOpenChange={setOpen}
        // ★목록은 행이 여러 개다. 이슈 키를 접두로 붙이지 않으면 e2e strict mode 로 즉사한다
        label={`${issue.key} 상태 변경`}
        // 접근성 이름에 현재 값을 함께 싣는다 (리뷰 C4) — 배지에 보이는 것과 같은 문자열.
        // ★배지가 이름으로 바뀌었으므로 여기도 같은 폴백을 타야 둘이 어긋나지 않는다.
        valueLabel={statusName ?? issue.currentStateKey}
        display={
          <StatusCellDisplay
            currentStateKey={issue.currentStateKey}
            statusName={statusName}
            category={category}
          />
        }
      >
        <StatusCellPopoverBody
          issue={issue}
          isSaving={mutation.isPending}
          onTransition={handleTransition}
          onScreenTransition={handleScreenTransition}
        />
      </EditableCell>

      {pendingScreen !== null && (
        // ★행 클릭 전파 차단. 모달은 portal 로 행 밖에 그려지지만 React 합성 이벤트는 **트리
        //   기준**으로 버블링하므로, 모달 안 클릭이 `<TableRow onClick>` 까지 올라가 상세로
        //   튄다. `EditableCell` 의 `PopoverContent` 와 같은 처방이다.
        //   `contents` = 이 래퍼가 박스를 만들지 않게 해 셀 레이아웃을 건드리지 않는다.
        <div className="contents" onClick={(event) => event.stopPropagation()}>
          <TransitionScreenDialog
            open
            transitionName={pendingScreen.name}
            screen={pendingScreen.screen ?? []}
            // 이슈의 현재 값으로 미리 채운다 (상세 화면과 같은 계약)
            prefill={{ resolutionId: issue.resolution?.id ?? null, assigneeId: issue.assigneeId ?? null }}
            onConfirm={handleScreenConfirm}
            onCancel={() => setPendingScreen(null)}
          />
        </div>
      )}
    </>
  )
}
