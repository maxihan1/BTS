// 스프린트 칸 컴포넌트 — 헤더 위임 + 드롭 영역 + 카드 목록 (FR-BL-02 D6/D7, FR-RP-01 D6/D7)
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { CardLayout } from '@/api/boards'
import { resolveCardType } from '@/components/issue/resolve-card-type'
import { useBacklogCollapsed } from '@/hooks/use-backlog-collapsed'
import { SprintColumnHeader } from './SprintColumnHeader'
import { BacklogCard } from './BacklogCard'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SprintColumn 컴포넌트 Props */
export interface SprintColumnProps {
  /** 소속 프로젝트 키 — 번다운 링크 경로 params에 사용 */
  projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 헤더의 `⋯` → 편집 다이얼로그까지 그대로 흘러가고, 거기서
   * 409 복구가 보드 스코프 캐시를 **완전 일치** 키로 읽는다. 여기서 `undefined` 를 임의로
   * 넣으면 재시도가 409 를 되풀이하는데 화면은 똑같아 보인다.
   */
  boardId: string | undefined
  /** 스프린트 메타 정보 */
  sprint: SprintMeta
  /** rank 순으로 정렬된 스프린트 이슈 목록 (부모가 정렬해 전달) */
  issues: BacklogIssue[]
  /**
   * 이슈 키 → 담당자 표시 이름 맵.
   * 맵에 없는 키는 assigneeId 유무에 따라 미확인/미배정으로 fallback한다.
   */
  assigneeNames: Map<string, string>
  /**
   * 이슈 타입 키 → 응답 맵 — 카드의 유형 아이콘·이름을 해석한다 (FR-UX-14 F14 Task 4).
   * 조회 실패·로딩 중이면 부모가 빈 맵을 넘긴다 — 이 경우 전 카드가 `issue.typeKey`
   * 원문 fallback 경로를 탄다 (FR6).
   */
  issueTypesByKey: Map<string, IssueTypeResponse>
  /**
   * 이 보드의 **뷰별** 카드 레이아웃 구성 (부채 177 Task 32 · J18).
   *
   * 칸은 값을 **읽지 않고** 카드에 그대로 넘긴다 — 스코프(`BACKLOG`) 판정은 카드가 한 곳에서만
   * 한다(Task 20). 스프린트 칸과 백로그 칸은 **같은 뷰**라 같은 구성을 그린다.
   */
  cardLayout?: CardLayout
  /** 드래그 카드가 이 칸 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
  /**
   * PLANNED 상태 시 표시하는 "스프린트 시작" 버튼 콜백.
   * undefined이면 버튼이 렌더되지 않는 게 아니라 클릭 시 아무 동작도 하지 않는다.
   * 버튼 자체는 Task 8 부모가 구체 동작을 주입한다.
   */
  onStart?: () => void
  /**
   * ACTIVE 상태 시 표시하는 "스프린트 완료" 버튼 콜백.
   * Task 8 부모가 주입한다.
   */
  onComplete?: () => void
  /**
   * 이슈 생성 진입점 클릭 콜백 (FR-UX-09 F3 FR-2).
   *
   * **미전달이면 진입점을 렌더하지 않는다** — 기존 소비처 무회귀.
   * `COMPLETED` 스프린트에서도 렌더하지 않는다 — 드롭이 막힌 것과 같은 기준이다 (E-3).
   * 모달은 이 칸이 아니라 **부모가 하나만** 소유한다 (FR-15).
   */
  onCreateIssue?: () => void
  /**
   * CREATE 권한 보유 여부. **fail-closed** — 로딩·에러·미보유는 전부 `false` 다.
   */
  canCreateIssue?: boolean
  /**
   * 스프린트 관리 권한(CREATE) — 헤더 `⋯` 메뉴(편집·삭제)의 게이트 (FR-5).
   *
   * `canCreateIssue` 와 값의 출처는 같지만(`permissions.CREATE`) **이름을 분리한다** —
   * 「이슈 생성」과 「스프린트 관리」는 다른 행위이고, 한쪽 권한이 갈라지는 날 같은 prop 을
   * 쓰고 있으면 두 조작이 한꺼번에 잘못된다 (`BacklogBoardProps` 가 세운 관례).
   */
  canManageSprint?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function SprintColumnInner({
  projectKey,
  boardId,
  sprint,
  issues,
  assigneeNames,
  issueTypesByKey,
  cardLayout,
  isOver = false,
  onStart,
  onComplete,
  onCreateIssue,
  canCreateIssue = false,
  canManageSprint = false,
}: SprintColumnProps) {
  const isCompleted = sprint.status === 'COMPLETED'
  // droppable id 를 접힘 저장의 섹션 id 로도 그대로 쓴다 (F15 FR-2) — 두 벌로 갈리면
  // 「접힌 섹션」과 「드롭이 막힌 섹션」이 어긋난다.
  const droppableId = `sprint-${sprint.sprintId}`
  const orderedKeys = issues.map((i) => i.key)

  // 접힘은 **권한·상태와 무관**하다 (FR-12) — COMPLETED 스프린트도 접을 수 있어야 한다.
  const { isCollapsed, toggle } = useBacklogCollapsed(projectKey)
  const collapsed = isCollapsed(droppableId)

  // ★훅을 조건부로 부르지 않는다 (E4). 접힘은 `disabled` + 카드 목록 미렌더로 표현한다.
  const { setNodeRef } = useDroppable({
    id: droppableId,
    disabled: isCompleted || collapsed,
    data: { context: 'sprint', sprintId: sprint.sprintId, orderedKeys },
  })

  const issueCount = issues.length

  return (
    <div
      className="flex w-full flex-col gap-2"
      role="region"
      aria-label={backlogLabels.columnAriaLabel(sprint.name, issueCount)}
    >
      <SprintColumnHeader
        projectKey={projectKey}
        boardId={boardId}
        sprint={sprint}
        issueCount={issueCount}
        collapsed={collapsed}
        onToggleCollapsed={() => { toggle(droppableId) }}
        onStart={onStart}
        onComplete={onComplete}
        onCreateIssue={onCreateIssue}
        canCreateIssue={canCreateIssue}
        canManageSprint={canManageSprint}
      />

      {/* 드롭 영역 + 카드 목록 — 접히면 **렌더하지 않는다** (FR-2). 헤더는 남는다. */}
      {!collapsed && (
        <div
          ref={setNodeRef}
          data-droppable={isCompleted ? undefined : droppableId}
          data-droppable-disabled={isCompleted ? 'true' : undefined}
          data-ordered-keys={isCompleted ? undefined : orderedKeys.join(',')}
          className={cn(
            'flex flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
            isOver && !isCompleted && 'bg-accent ring-2 ring-primary',
            isCompleted && 'bg-muted/50 opacity-75',
          )}
        >
          {issueCount === 0 ? (
            <div
              className="flex items-center justify-center rounded-md py-8 text-xs text-muted-foreground"
              aria-label="이슈 없음"
            >
              {backlogLabels.emptyIssues}
            </div>
          ) : (
            issues.map((issue) => {
              const cardType = resolveCardType(issueTypesByKey, issue.typeKey)
              return (
                <BacklogCard
                  key={issue.key}
                  issue={issue}
                  context="sprint"
                  sprintId={sprint.sprintId}
                  assigneeName={assigneeNames.get(issue.key)}
                  typeIconName={cardType.iconName}
                  typeName={cardType.typeName}
                  cardLayout={cardLayout}
                />
              )
            })
          )}
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 이슈 칸.
 *
 * - 헤더(접기 토글·이름·상태 배지·이슈 수·생성 진입점·시작/완료·번다운)는 [SprintColumnHeader] 가 그린다.
 *   200줄 규칙(`DEVELOPMENT.md §2.2`)에 맞춰 **화면의 경계(sticky 헤더)** 를 파일 경계로 삼았다.
 * - 세로 스택의 한 칸이므로 폭은 `w-full` 이다 (F15 FR-1).
 * - `useDroppable`로 droppable id=`sprint-{sprintId}` 영역을 제공한다.
 * - COMPLETED 상태이면 `useDroppable`의 disabled=true로 드롭을 거부하고 시각적으로 비활성 처리한다.
 * - 접히면 카드 목록을 렌더하지 않고 droppable 도 `disabled` 가 된다 (F15 FR-2 · E4).
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다 (COMPLETED이면 무시).
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 *   접힘 상태는 props 가 아니라 zustand 구독이라 memo 가 막지 않는다.
 */
export const SprintColumn = memo(SprintColumnInner)
