// 백로그 미할당 칸 컴포넌트 — 드롭 영역 + 이슈 카드 목록 (FR-BL-01/02 D6/D7)
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogIssue } from '@/api/backlog'
import type { IssueTypeResponse } from '@/api/issue-types'
import { Button } from '@/components/ui/button'
import { CreateIssueEntryButton } from '@/components/issue/CreateIssueEntryButton'
import { useBacklogCollapsed } from '@/hooks/use-backlog-collapsed'
import { BacklogCard } from './BacklogCard'
import { CreateSprintForm } from './CreateSprintForm'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 칸의 고정 droppable id.
 *
 * 접힘 상태를 저장하는 **섹션 id 로도 그대로 쓴다** — 두 벌로 갈리면 접힌 섹션과
 * 드롭이 막힌 섹션이 어긋난다 (F15 FR-2).
 */
const BACKLOG_DROPPABLE_ID = 'backlog'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogColumn 컴포넌트 Props */
export interface BacklogColumnProps {
  /**
   * 소속 프로젝트 키 — 섹션 접힘 상태를 프로젝트별로 영속하는 데 쓴다 (F15 FR-2).
   *
   * 키가 프로젝트마다 분리돼 있어야 A 프로젝트에서 접은 기억이 B 프로젝트를 접지 않는다.
   */
  projectKey: string
  /** rank 순으로 정렬된 미할당 이슈 목록 (부모가 정렬해 전달) */
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
  /** 드래그 카드가 이 칸 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
  /**
   * 이슈 생성 진입점 클릭 콜백 (FR-UX-09 F3 FR-1).
   *
   * **미전달이면 진입점을 렌더하지 않는다** — 기존 소비처의 동작이 바뀌지 않는다.
   * 모달은 이 칸이 아니라 **부모가 하나만** 소유한다 (FR-15).
   */
  onCreateIssue?: () => void
  /**
   * CREATE 권한 보유 여부. **fail-closed** — 로딩·에러·미보유는 전부 `false` 다.
   * 부모가 `permissions.CREATE === true` 로만 `true` 를 만든다.
   */
  canCreateIssue?: boolean
  /**
   * 스프린트 생성 제출 콜백 — 폼이 입력받은 이름을 그대로 넘긴다 (FR-UX-13 F16 F16-11).
   *
   * **미전달이면 폼을 렌더하지 않는다** — `onCreateIssue` 와 같은 결이고, 기존 소비처의
   * 동작이 바뀌지 않는다.
   *
   * ★**`ReactNode` 슬롯이 아니라 원시 콜백인 이유.** 이 컴포넌트는 `memo` 인데 JSX 노드
   * prop 은 매 렌더 새 참조라 memo 를 통째로 무력화한다. 백로그는 최대 1,000건이고
   * 필터 입력은 250ms 디바운스로 부모를 반복 재렌더한다. 부모는 이 콜백을
   * **`useCallback` 으로 감싼 안정 참조**로 넘겨야 memo 가 산다.
   */
  onCreateSprint?: (name: string) => void
  /**
   * 스프린트 생성 폼 비활성화 여부 (권한 없음 또는 생성 진행 중).
   *
   * 권한 플래그(`canManageSprint`)를 받지 않고 **계산된 결과**를 받는다 — 「권한 없음 OR
   * 진행 중」 판정을 부모와 이 컴포넌트 두 곳에 두면 규칙이 두 벌로 갈라진다.
   */
  createSprintDisabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BacklogColumnInner({
  projectKey,
  issues,
  assigneeNames,
  issueTypesByKey,
  isOver = false,
  onCreateIssue,
  canCreateIssue = false,
  onCreateSprint,
  createSprintDisabled = false,
}: BacklogColumnProps) {
  const orderedKeys = issues.map((i) => i.key)

  // 접힘은 **권한과 무관**하다 (FR-12) — 읽기 전용 사용자도 시야를 좁힐 수 있어야 한다.
  const { isCollapsed, toggle } = useBacklogCollapsed(projectKey)
  const collapsed = isCollapsed(BACKLOG_DROPPABLE_ID)

  // ★훅을 조건부로 부르지 않는다 (E4). 접힘은 `disabled` + 카드 목록 미렌더로 표현한다 —
  //   렌더되지 않은 droppable 은 rect 가 없어 충돌 감지에서 어차피 건너뛴다.
  const { setNodeRef } = useDroppable({
    id: BACKLOG_DROPPABLE_ID,
    disabled: collapsed,
    data: { context: 'backlog', orderedKeys },
  })

  const issueCount = issues.length

  return (
    <div
      className="flex w-full flex-col gap-2"
      role="region"
      aria-label={backlogLabels.columnAriaLabel(backlogLabels.backlogTitle, issueCount)}
    >
      {/* 헤더 — 접기 토글 + 제목 + 카드 수 + 액션. 세로 스택에서는 폭이 남으므로 한 줄이다.
          좁은 화면에서는 `flex-wrap` 이 2행을 허용한다 (§반응형 sm). */}
      <div className="sticky top-0 z-10 flex flex-wrap items-center gap-2 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2">
        {/* 접기 토글 — 이름은 **`aria-label` 로만** 준다 (FR-2).
            `sr-only` 텍스트를 넣으면 섹션 textContent 맨 앞에 글자가 끼어
            `backlog.spec.ts` 의 `^` 앵커 정규식이 즉사한다.
            ★`aria-controls` 는 넣지 않는다 — 접히면 대상 id 가 DOM 에서 사라져
            dangling IDREF 가 된다 (스펙 §리뷰 반영 C-3). 상태는 `aria-expanded` 가 말한다. */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="min-h-11 min-w-11 text-muted-foreground md:min-h-0 md:min-w-0"
          aria-label={backlogLabels.collapseSection(backlogLabels.backlogTitle)}
          aria-expanded={!collapsed}
          onClick={() => { toggle(BACKLOG_DROPPABLE_ID) }}
        >
          {collapsed ? <ChevronRight /> : <ChevronDown />}
        </Button>
        <span className="text-sm font-semibold text-foreground">
          {backlogLabels.backlogTitle}
        </span>
        <span
          className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
          aria-label={`이슈 ${issueCount}개`}
        >
          {issueCount}
        </span>
        <div className="flex-1" />
        {/* 이슈 생성 진입점 — 제목 행에 둔다. 헤더를 세로로 늘리지 않는다 (design 리뷰 DR-2).
            드롭 영역(아래 div) 밖이라 드래그 앤 드롭에 영향이 없다 (NFR-5). */}
        {onCreateIssue !== undefined && (
          <CreateIssueEntryButton
            label={backlogLabels.createIssueInBacklog}
            variant="icon"
            canCreate={canCreateIssue}
            onClick={onCreateIssue}
          />
        )}
        {/* 스프린트 생성 폼 — Jira 와 같이 백로그 섹션 헤더에 붙는다 (F16 F16-11).
            ★**제목 span 뒤(오른쪽)가 계약이다.** 앞에 두면 섹션 textContent 선두가
            「스프린트 생성…」이 되어 `backlog.spec.ts:253` 의 `hasText: /^백로그/` 칸
            locator 가 통째로 즉사한다. 접기 토글의 sr-only 금지(위 주석)와 같은 함정이다.
            드롭 영역(아래 div) 밖이라 드래그 앤 드롭에 영향이 없다.
            헤더는 접혀도 남으므로 접힌 상태에서도 스프린트를 만들 수 있다 — 접기는
            카드 목록을 접는 것이지 생성 진입을 없애는 것이 아니다. */}
        {onCreateSprint !== undefined && (
          <CreateSprintForm
            projectKey={projectKey}
            onSubmit={onCreateSprint}
            disabled={createSprintDisabled}
          />
        )}
      </div>

      {/* 드롭 영역 + 카드 목록 — 접히면 **렌더하지 않는다** (FR-2). 헤더는 남는다. */}
      {!collapsed && (
        <div
          ref={setNodeRef}
          data-droppable={BACKLOG_DROPPABLE_ID}
          data-ordered-keys={orderedKeys.join(',')}
          className={cn(
            'flex flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
            isOver && 'bg-accent ring-2 ring-primary',
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
              const type = issueTypesByKey.get(issue.typeKey)
              return (
                <BacklogCard
                  key={issue.key}
                  issue={issue}
                  context="backlog"
                  assigneeName={assigneeNames.get(issue.key)}
                  typeIconName={type?.iconName ?? null}
                  typeName={type?.name ?? issue.typeKey}
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
// memo 래핑 — issues·assigneeNames·isOver가 변하지 않으면 재렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그(스프린트 미할당) 이슈 칸.
 *
 * - 헤더에 접기 토글 · "백로그" 제목 · 이슈 수 · 이슈 생성 진입점 · 스프린트 생성 폼을
 *   한 줄로 표시한다. 헤더 자식의 **순서가 계약**이다 (제목이 맨 앞 — F16 §헤더 순서).
 * - 세로 스택의 한 칸이므로 폭은 `w-full` 이다 (F15 FR-1).
 * - `useDroppable`로 droppable id="backlog" 영역을 제공한다. 빈 목록에도 드롭 가능.
 * - 접히면 카드 목록을 렌더하지 않고 droppable 도 `disabled` 가 된다 (F15 FR-2 · E4).
 * - 이슈가 없으면 "이슈 없음" placeholder를 표시한다.
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다.
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 *   접힘 상태는 props 가 아니라 zustand 구독이라 memo 가 막지 않는다.
 */
export const BacklogColumn = memo(BacklogColumnInner)
