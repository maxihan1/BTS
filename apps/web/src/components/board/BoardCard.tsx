// 칸반 보드 카드 컴포넌트 — 이슈 요약 표시 + @dnd-kit/sortable 정렬 가능 드래그 핸들
import { memo } from 'react'
import { Link } from '@tanstack/react-router'
import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { cn } from '@/lib/utils'
import type { BoardCard as BoardCardType } from '@/api/boards'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { CardLabelChips } from '@/components/issue/CardLabelChips'
import { CardEstimateBadge } from '@/components/issue/CardEstimateBadge'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 표시 3-상태 discriminated union
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드 담당자 표시 상태.
 *
 * - `unassigned`: assigneeId가 null — 아직 배정되지 않음.
 * - `named`: assigneeId가 있고 displayName/username을 해석함.
 * - `unknown`: assigneeId가 있으나 userMap에서 이름을 찾지 못함(fetchUsers 상한 초과 등).
 *   미배정과 시각적으로 구분해 "?" 아바타로 표시한다.
 */
export type CardAssigneeDisplay =
  | { state: 'unassigned' }
  | { state: 'named'; name: string }
  | { state: 'unknown' }

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardCard 컴포넌트 Props */
export interface BoardCardProps {
  /** 카드에 표시할 이슈 데이터 */
  card: BoardCardType
  /** 카드가 속한 컬럼 UUID */
  columnId: string
  /**
   * 담당자 표시 상태 (3-상태 discriminated union).
   * 페이지가 userId → displayName 해석 후 주입한다.
   */
  assignee: CardAssigneeDisplay
  /**
   * 이슈 타입 아이콘 식별자 (IssueTypeIcon 원시 prop).
   * BoardColumn이 issueTypesByKey로 card.typeKey를 해석해 주입한다.
   * 해석 실패(매핑 없음)면 null — IssueTypeIcon이 Circle로 fallback한다.
   * 객체가 아닌 원시 값으로 받는 이유는 FR3 — memo 얕은 비교가 매 렌더 새 객체로 무력화되지 않도록 한다.
   */
  typeIconName: string | null
  /**
   * 이슈 타입 표시 이름. IssueTypeIcon의 aria-label로 그대로 쓰인다.
   * 해석 실패 시 card.typeKey 원문을 그대로 전달한다(FR6) — 빈 문자열이나 "알 수 없음"으로 뭉개지 않는다.
   */
  typeName: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 아바타 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 담당자 표시 상태에 따른 아바타 또는 "미배정" 텍스트를 렌더한다. */
function AssigneeSlot({ assignee }: { assignee: CardAssigneeDisplay }): React.ReactElement {
  if (assignee.state === 'named') {
    const initial = assignee.name[0] ?? ''
    return (
      <span
        title={assignee.name}
        aria-label={`담당자: ${assignee.name}`}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-primary text-xs font-medium text-primary-foreground',
        )}
      >
        {initial}
      </span>
    )
  }

  if (assignee.state === 'unknown') {
    return (
      <span
        title="담당자 (이름 미확인)"
        aria-label="담당자 이름 미확인"
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-muted border border-border text-xs font-medium text-muted-foreground',
        )}
      >
        ?
      </span>
    )
  }

  // unassigned
  return (
    <span className="text-xs text-muted-foreground" aria-label="담당자 미배정">
      미배정
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BoardCardInner({ card, columnId, assignee, typeIconName, typeName }: BoardCardProps) {
  const openIssueDetail = useOpenIssueDetail()
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: card.issueKey,
    data: { fromColumnId: columnId },
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      aria-roledescription="draggable card"
      aria-label={`${card.issueKey} — ${card.summary}`}
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-sm',
        'cursor-grab active:cursor-grabbing',
        isDragging && 'opacity-50',
      )}
    >
      {/* 주 정보: summary (2줄 truncate). 드래그 중 클릭 방지 */}
      <Link
        to="/issues/$key"
        params={{ key: card.issueKey }}
        className="line-clamp-2 text-sm font-medium leading-snug text-foreground hover:underline"
        onClick={(e) => {
          if (isDragging) {
            e.preventDefault()
          }
          // 평범한 좌클릭이면 모달로 가로챈다(J1). 드래그 중이면 위에서 이미
          // preventDefault 됐고 훅이 `defaultPrevented` 를 보고 물러난다.
          openIssueDetail(card.issueKey, e)
        }}
      >
        {card.summary}
      </Link>

      {/* 라벨 칩 행 — labels가 있을 때만 생성(FR9), 빈 배열이면 DOM 미생성 */}
      <CardLabelChips labels={card.labels} />

      {/* 하단 행: [유형 아이콘][issueKey] … [추정][담당자] (FR7) */}
      <div className="flex items-center justify-between gap-2">
        {/* min-w-0 — 이슈 키가 길어도 우측(추정·담당자)을 밀어내지 않게 한다. 백로그 카드와 동형. */}
        <span className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
          <IssueTypeIcon iconName={typeIconName} typeName={typeName} />
          {card.issueKey}
        </span>
        <div className="flex items-center gap-1">
          <CardEstimateBadge seconds={card.originalEstimateSeconds} />
          <AssigneeSlot assignee={assignee} />
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — 보드 전체 재렌더 시 props 변화 없는 카드 렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 이슈 카드.
 *
 * - summary를 주 정보로(2줄 truncate), issueKey를 보조 정보로 표시한다.
 * - assignee 3-상태에 따라 이니셜 아바타(named) / "?" 아바타(unknown) / "미배정"(unassigned)을 표시한다.
 * - `useSortable`(`@dnd-kit/sortable`)로 정렬 가능한 드래그 핸들을 제공한다.
 *   부모(BoardColumn)가 셀(컬럼 × 스윔레인 그룹) 단위 `SortableContext`로 감싸면
 *   같은 셀 내 포인터/키보드 순서변경에 참여한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동하며, 드래그 중엔 네비게이션이 막힌다.
 * - 드래그 중(`isDragging`)에는 원위치 카드에 `opacity-50`을 적용해 placeholder처럼 흐리게 표시한다.
 * - FR-UX-14 F14 3요소 — `CardLabelChips`(FR9, 라벨 없으면 DOM 미생성) · 하단 행의
 *   `IssueTypeIcon`(FR6, 해석 실패 시 typeKey 원문 + Circle fallback) · `CardEstimateBadge`
 *   (FR10, null이면 DOM 미생성)를 렌더한다. 카드 루트의 `aria-label`/`aria-roledescription`은
 *   Jira 패리티 계약 §2에 따라 변경하지 않는다(FR13).
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다. `typeIconName`/`typeName`을
 *   객체가 아닌 원시 값 2개로 받는 이유도 이 얕은 비교(shallow compare)가 매 렌더 새 객체로
 *   무력화되지 않게 하기 위함이다(FR3).
 */
export const BoardCard = memo(BoardCardInner)
