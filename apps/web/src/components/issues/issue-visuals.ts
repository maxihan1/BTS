// 이슈 목록의 상태·우선순위·타입을 색과 아이콘으로 옮기는 순수 매핑 정본
import { ChevronsUp, ChevronUp, Equal, ChevronDown, ChevronsDown, type LucideIcon } from 'lucide-react'
import type { WorkflowView } from '@/api/workflows'

/** 워크플로우 상태 카테고리 — `api/workflows.ts` 의 `stateCategorySchema` 파생 */
export type StateCategory = WorkflowView['states'][number]['category']

/** `ui/badge.tsx` 의 색 variant 중 이 모듈이 쓰는 것 */
type StatusBadgeVariant = 'neutral' | 'blue' | 'green'

/**
 * 상태 카테고리 → 배지 색.
 *
 * ★Jira Cloud 의 색 규칙을 그대로 따른다 — 「All statuses ... must belong to one of three
 * status categories – To do, In progress, or Done. These categories are represented by the
 * colors grey, blue, and green respectively」
 * (support.atlassian.com/jira-cloud-administration/docs/what-is-a-workflow-status/, 2026-09-07 조회).
 * 같은 문서가 「this color scheme can't be customized」라고 못박으므로 **설정으로 열지 않는다**.
 *
 * @param category 상태 카테고리. 워크플로우 조회 실패·상태 미해석이면 undefined
 * @returns Badge variant. 미해석이면 중립 — 모르는 것에 색을 지어내지 않는다
 */
export function statusBadgeVariant(category: StateCategory | undefined): StatusBadgeVariant {
  switch (category) {
    case 'IN_PROGRESS':
      return 'blue'
    case 'DONE':
      return 'green'
    default:
      return 'neutral'
  }
}

/** 우선순위 한 단계의 시각 표현 */
export interface PriorityVisual {
  /** 방향·강도를 나타내는 lucide 아이콘 */
  Icon: LucideIcon
  /** `text-*` 색 유틸리티 — §C `-text` 토큰(tint 표면 AA≥4.9 실측, DESIGN.md §10) */
  colorClass: string
}

/**
 * 우선순위 1~5 의 시각 표현.
 *
 * ★**색 밴드는 3개, 단계는 5개다.** 겹화살표(`Chevrons*`)와 홑화살표(`Chevron*`)가 같은
 * 밴드 안의 두 단계를 색 없이도 가른다. Atlassian 자신이 「Priority icons on new projects
 * are not accessible for red-green [color blindness]」(JRASERVER-61773)를 화살표 겹침으로
 * 처방했고, 여기서 그 처방을 따른다.
 *
 * ★**색은 §C `-text` 토큰만 쓴다.** `--prio-*` 5종은 `state-tokens.test.ts` 의
 * `UNCONSUMED_TOKENS` 가 **미정의를 고정**하고 있다(ADR §D7 — 소비자 0인 토큰은 만들지
 * 않는다). 새로 정의하면 그 음성 테스트가 즉사한다.
 *
 * **Jira 와 다른 지점(편차).** Jira Cloud 문서는 Low/Lowest 를 회색(dark grey / light grey)
 * 으로 적는다(support.atlassian.com, 2026-09-07 조회). BTS 는 파랑(`--info-text`)을 쓴다 —
 * 목록의 보조 텍스트(`--text-subtle`·`--text-subtlest`)가 이미 그 회색 두 단계를 쓰고 있어,
 * 우선순위를 같은 회색으로 칠하면 「색이 없는 값」과 구분되지 않는다. 파랑은 Jira Cloud 의
 * 실제 우선순위 아이콘과도 어긋나지 않는다.
 *
 * @param priority 우선순위 1(가장 높음)~5(가장 낮음)
 * @returns 아이콘과 색. 정본 범위 밖이면 보통(3)의 시각으로 폴백한다
 */
export function priorityVisual(priority: number): PriorityVisual {
  switch (priority) {
    case 1:
      return { Icon: ChevronsUp, colorClass: 'text-danger-text' }
    case 2:
      return { Icon: ChevronUp, colorClass: 'text-danger-text' }
    case 4:
      return { Icon: ChevronDown, colorClass: 'text-info-text' }
    case 5:
      return { Icon: ChevronsDown, colorClass: 'text-info-text' }
    default:
      return { Icon: Equal, colorClass: 'text-warning-text' }
  }
}

/**
 * 이슈 타입 키 → 아이콘 색 클래스.
 *
 * 색 배정은 `TimelineRow.tsx` 의 `ISSUE_TYPE_COLORS`(`bg-type-*`)와 **같은 토큰**을 쓴다 —
 * 같은 이슈 타입이 타임라인 막대와 목록 아이콘에서 다른 색이면 색이 의미를 잃는다.
 * 여기는 배경이 아니라 아이콘이라 `text-` 접두만 다르다.
 *
 * `subtask` 는 `--type-subtask` 토큰이 없어 중립으로 떨어진다 — 이 역시 `UNCONSUMED_TOKENS`
 * 가 미정의로 고정한 토큰이다.
 *
 * @param typeKey 이슈 타입 키(epic·story·task·bug·subtask …)
 * @returns `text-type-*` 색 유틸리티. 매핑에 없으면 중립 폴백
 */
export function issueTypeColorClass(typeKey: string): string {
  switch (typeKey) {
    case 'epic':
      return 'text-type-epic'
    case 'story':
      return 'text-type-story'
    case 'task':
      return 'text-type-task'
    case 'bug':
      return 'text-type-bug'
    default:
      return 'text-type-default'
  }
}
