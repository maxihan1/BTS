// 백로그·스프린트 이슈 행(40px 한 줄) 컴포넌트 — 이슈 요약 · 상태 드롭다운 + @dnd-kit 드래그/드롭 핸들
import { memo, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { useDraggable, useDroppable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import { cardDroppableId } from '@/lib/backlog-drag'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { CardLabelChips } from '@/components/issue/CardLabelChips'
import { CardEstimateBadge } from '@/components/issue/CardEstimateBadge'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import { IssueActionsMenu } from '@/components/issue/IssueActionsMenu'
import { StatusCell } from '@/components/issues/cells/StatusCell'
import type { StateCategory } from '@/components/issues/issue-visuals'
import { CardExtraFields } from '@/components/board/BoardCard'
import type { CardCustomFieldValues } from '@/components/board/BoardCard'
import type { BacklogIssue } from '@/api/backlog'
import type { CardLayout } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// 드래그 컨텍스트 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드가 위치한 컨텍스트.
 *
 * - `backlog`: 스프린트 미할당 백로그 칸
 * - `sprint`: 특정 스프린트 칸
 *
 * onDragEnd 핸들러가 드롭 대상과 조합해 이동 방향을 판단하는 데 사용된다.
 */
export type BacklogCardContext = 'backlog' | 'sprint'

/**
 * 드래그 data — `useDraggable`에 넣어 DndContext 이벤트에서 읽는다.
 *
 * - `issueKey`: 이동할 이슈 키
 * - `context`: 카드가 위치한 컨텍스트
 * - `sprintId`: context='sprint'일 때 스프린트 UUID, 'backlog'이면 null
 */
export interface BacklogDragData {
  issueKey: string
  context: BacklogCardContext
  sprintId: string | null
}

/**
 * 카드 droppable data — `useDroppable`에 넣어 onDragEnd에서 over 대상이 카드임을 식별한다.
 *
 * - `type`: 'card' — 칸 droppable과 구분하는 식별자
 * - `key`: 이 카드의 이슈 키
 * - `context`: 카드가 위치한 컨텍스트
 * - `sprintId`: context='sprint'일 때 스프린트 UUID, 'backlog'이면 null
 */
export interface BacklogCardDropData {
  type: 'card'
  key: string
  context: BacklogCardContext
  sprintId: string | null
}


// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogCard 컴포넌트 Props */
export interface BacklogCardProps {
  /**
   * 카드에 표시할 이슈 데이터.
   *
   * `CardCustomFieldValues` 를 교차한 이유는 보드 카드와 같다 — 커스텀 필드 값이 실려 오면
   * 카드 2층이 그것을 그리고, 아직 없으면 그 칸만 생략된다(E4). 백로그 조회 응답
   * (`BacklogIssueResponse`)은 아직 이 키를 싣지 않는다.
   */
  issue: BacklogIssue & CardCustomFieldValues
  /** 카드가 위치한 컨텍스트 (백로그 또는 스프린트) */
  context: BacklogCardContext
  /**
   * context='sprint'일 때 스프린트 UUID.
   * 백로그 컨텍스트이면 undefined (data에는 null로 저장).
   */
  sprintId?: string
  /**
   * 담당자 표시 이름.
   * - undefined이고 assigneeId=null → 미배정
   * - undefined이고 assigneeId!=null → 이름 미확인 ("?" 아바타)
   * - string → 이니셜 아바타
   */
  assigneeName?: string
  /**
   * 이슈 타입 아이콘 식별자 — `IssueTypeIcon`에 그대로 전달한다 (FR-UX-14 F14 Task 4).
   * 부모(`BacklogColumn`/`SprintColumn`)가 `issueTypesByKey` 맵으로 미리 해석해 넘긴다.
   * 미해석(맵에 없음)이면 `null` — fallback 아이콘(`Circle`)으로 렌더된다 (FR6).
   *
   * 원시 문자열인 이유. 객체(`IssueTypeResponse`) 통째로 받으면 부모가 매 렌더 새
   * 참조를 만들어 `memo` 비교가 깨진다 — `typeName`도 같은 이유로 원시 문자열이다.
   */
  typeIconName: string | null
  /**
   * 이슈 타입 표시 이름 — `IssueTypeIcon`의 접근성 이름(`aria-label`)이 된다.
   * 부모가 맵에서 못 찾으면 `issue.typeKey` 원문을 그대로 넘긴다 (FR6 fallback).
   */
  typeName: string
  /**
   * 이 보드의 **뷰별** 카드 레이아웃 구성 (`BoardDetail.cardLayout` · R3 · J18).
   *
   * 백로그 카드는 이 중 **`BACKLOG` 스코프만** 읽는다 — 보드 카드와 **다른 구성**을 가질 수
   * 있는 것이 J18 이다. 미지정이거나 그 뷰의 키가 없으면 추가 필드 층의 DOM 이 생기지 않는다.
   */
  cardLayout?: CardLayout
  /**
   * 워크플로우에서 해석한 상태 표시 이름 · 카테고리 (JB-L-2). 부모가 `statusMetaByKey` 로 미리
   * 해석해 원시 값으로 넘긴다 — `typeName` 과 같은 memo 이유. 미해석이면 상태 키 원문 · 중립색.
   */
  statusName?: string
  statusCategory?: StateCategory
}

/** 행 안 컨트롤의 이벤트가 행 드래그 핸들(@dnd-kit listeners)까지 올라가지 않게 한다 */
function stopPropagation(event: SyntheticEvent): void {
  event.stopPropagation()
}

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 아바타 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 담당자 표시 상태에 따른 아바타 또는 "미배정" 텍스트를 렌더한다. */
function AssigneeSlot({
  assigneeId,
  assigneeName,
}: {
  assigneeId: string | null
  assigneeName: string | undefined
}): React.ReactElement {
  if (assigneeName !== undefined) {
    const initial = assigneeName[0] ?? ''
    return (
      <span
        title={assigneeName}
        aria-label={`담당자: ${assigneeName}`}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-primary text-xs font-medium text-primary-foreground',
        )}
      >
        {initial}
      </span>
    )
  }

  if (assigneeId !== null) {
    // assigneeId는 있으나 이름을 못 찾은 경우
    return (
      <span
        title={backlogLabels.unknownAssigneeTitle}
        aria-label={backlogLabels.unknownAssigneeAriaLabel}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-muted border border-border text-xs font-medium text-muted-foreground',
        )}
      >
        ?
      </span>
    )
  }

  // 미배정
  return (
    <span className="text-xs text-muted-foreground" aria-label="담당자 미배정">
      {backlogLabels.unassigned}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BacklogCardInner({
  issue,
  context,
  sprintId,
  assigneeName,
  typeIconName,
  typeName,
  cardLayout,
  statusName,
  statusCategory,
}: BacklogCardProps) {
  const openIssueDetail = useOpenIssueDetail()
  const resolvedSprintId = sprintId ?? null
  // 작업 메뉴 — `⋯` 와 우클릭이 같은 메뉴를 연다 (JCB-17)
  const [menuOpen, setMenuOpen] = useState(false)

  const dragData: BacklogDragData = {
    issueKey: issue.key,
    context,
    sprintId: resolvedSprintId,
  }

  const dropData: BacklogCardDropData = {
    type: 'card',
    key: issue.key,
    context,
    sprintId: resolvedSprintId,
  }

  const droppableId = cardDroppableId(context, issue.key)

  const { attributes, listeners, setNodeRef: setDragRef, transform, isDragging } = useDraggable({
    id: `${context}:${issue.key}`,
    data: dragData,
  })

  const { setNodeRef: setDropRef } = useDroppable({
    id: droppableId,
    // 자기 자신이 드래그 중일 때 droppable을 비활성화한다.
    // pointerWithin 충돌 감지 시 드래그 중인 카드의 droppable이 자신 위에
    // 충돌 대상으로 잡히는 것을 방지한다.
    disabled: isDragging,
    data: dropData,
  })

  // draggable과 droppable ref를 동일 요소에 합성한다
  function setRef(node: HTMLDivElement | null): void {
    setDragRef(node)
    setDropRef(node)
  }

  const style = transform
    ? { transform: CSS.Translate.toString(transform) }
    : undefined

  return (
    <div
      ref={setRef}
      style={style}
      {...listeners}
      {...attributes}
      data-drag-context={context}
      data-card-droppable={droppableId}
      aria-roledescription={backlogLabels.draggableCard}
      aria-label={backlogLabels.cardAriaLabel(issue.key, issue.summary)}
      onContextMenu={(e) => {
        e.preventDefault()
        setMenuOpen(true)
      }}
      // 한 줄 40px 행 (JV-12). 행 사이 선은 부모 목록의 `divide-y` 가 긋는다
      className={cn(
        'group flex h-10 items-center gap-2 bg-card px-3 hover:bg-accent/50',
        'cursor-grab active:cursor-grabbing',
        isDragging && 'opacity-50',
      )}
    >
      <IssueTypeIcon iconName={typeIconName} typeName={typeName} />
      <span className="shrink-0 text-xs text-muted-foreground">{issue.key}</span>

      {/* summary — 주 정보. 남는 폭을 다 쓰고 한 줄로 자른다. 드래그 중 클릭 방지 */}
      <Link
        to="/issues/$key"
        params={{ key: issue.key }}
        title={issue.summary}
        className="min-w-0 flex-1 truncate text-sm text-foreground hover:underline"
        onClick={(e) => {
          if (isDragging) {
            e.preventDefault()
          }
          // 평범한 좌클릭이면 모달로 가로챈다(J1). BoardCard 와 같은 형태다.
          openIssueDetail(issue.key, e)
        }}
      >
        {issue.summary}
      </Link>

      {/* J19 2층(구성이 고른 추가 필드) + 라벨 칩 — 한 줄을 넘치면 잘린다. 좁은 화면에선 숨긴다 */}
      <div className="hidden max-h-6 min-w-0 shrink items-center gap-1 overflow-hidden md:flex">
        <CardExtraFields
          layout={cardLayout}
          view="BACKLOG"
          source={{
            epicKey: issue.epicKey,
            priority: issue.priority,
            labels: issue.labels,
            originalEstimateSeconds: issue.originalEstimateSeconds,
            assigneeName: assigneeName ?? null,
            typeName,
            customFields: issue.customFields,
          }}
        />
        {/* 라벨이 없으면 DOM 자체가 없다 (`CardLabelChips` FR9) */}
        <CardLabelChips labels={issue.labels} />
      </div>

      {/* 상태 드롭다운 (JB-L-2) — 목록 F9 셀 그대로. Space · 포인터가 행 드래그를 시작하지 않게 끊는다 */}
      <span className="shrink-0" onKeyDown={stopPropagation} onPointerDown={stopPropagation}>
        <StatusCell issue={issue} statusName={statusName} category={statusCategory} />
      </span>

      {/* 추정 배지 — 미추정(null)이면 DOM 자체가 없다 (`CardEstimateBadge` FR10) */}
      <CardEstimateBadge seconds={issue.originalEstimateSeconds} />
      <span
        // FR19 — 맨 span 은 role=generic 이라 aria-label 이 무효고 스크린리더가 "P3" 만 읽는다
        role="img"
        aria-label={`우선순위 ${issue.priority}`}
        className="shrink-0 rounded-sm px-1 py-0.5 text-xs text-muted-foreground ring-1 ring-border"
      >
        P{issue.priority}
      </span>
      <AssigneeSlot assigneeId={issue.assigneeId} assigneeName={assigneeName} />
      <IssueActionsMenu
        issueKey={issue.key}
        version={issue.version}
        priority={issue.priority}
        assigneeId={issue.assigneeId}
        open={menuOpen}
        onOpenChange={setMenuOpen}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — 보드 전체 재렌더 시 props 변화 없는 카드 렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 칸의 개별 이슈 행 — Jira 백로그처럼 **40px 한 줄**이다 (R3 · JV-12).
 *
 * - 한 줄 순서: 유형 아이콘 · issueKey · summary(남는 폭, 1줄 truncate) · 추가 필드 + 라벨(md 이상) ·
 *   상태 드롭다운(F9 `StatusCell`, JB-L-2) · 추정 · 우선순위 · 담당자 · `⋯` 작업 메뉴.
 *   종전 3단 카드(≈110px)는 보드 카드 문법이라 한 화면에 이슈가 약 1/2.6 만 보였다.
 * - 보드 카드(`BoardCard`)와 대부분 동형이지만 **`P{priority}` 칩을 그대로 유지**한다 —
 *   보드는 컬럼 자체가 우선순위 정보를 대신하지 않지만, 백로그는 스프린트 계획 단계에서
 *   우선순위를 한눈에 봐야 하므로 칩을 없애지 않는다.
 * - `CardExtraFields`(보드와 **공유**하는 J19 2층 조각)는 한 줄 안에 인라인으로 들어간다.
 *   `cardLayout` 의 **`BACKLOG` 스코프만** 읽는다 —
 *   같은 보드의 보드 카드와 **다른 필드 집합**을 가질 수 있는 것이 J18 이다.
 * - 라벨·추정 배지는 각각 `CardLabelChips`/`CardEstimateBadge`(보드와 공유) 에 위임한다 —
 *   값이 없으면 두 컴포넌트 모두 DOM 자체를 만들지 않는다.
 * - `typeIconName`/`typeName`은 부모가 `issueTypesByKey` 맵으로 미리 해석해 원시 문자열로
 *   넘긴다 — 이 컴포넌트는 맵을 모른다.
 * - assigneeName 유무·assigneeId 유무에 따라 이니셜/미확인(?)/미배정 세 상태를 표시한다.
 * - `useDraggable`로 드래그 핸들을 제공한다. data에 context·sprintId를 담아 부모가 이동 방향을 계산한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동하며, 드래그 중엔 네비게이션이 막힌다.
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 */
export const BacklogCard = memo(BacklogCardInner)
