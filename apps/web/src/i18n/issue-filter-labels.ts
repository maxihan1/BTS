// 이슈 필터 바 라벨 — filterBarLabels 파생(상태 라벨 추가) — FR-UX-06 PR17

import { filterBarLabels } from './filter-bar-labels'

/**
 * 이슈 필터 바가 노출하는 한국어 라벨/텍스트.
 *
 * `filterBarLabels`(이슈·보드 공용 단일 출처)에 이슈 전용 `statusLabel`을 더한 파생이다.
 *
 * 그룹 — filter / chip / count / search
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const issueFilterLabels = {
  ...filterBarLabels,
  filter: {
    /** 상태 섹션 라벨 */
    statusLabel: '상태',
    ...filterBarLabels.filter,
  },
} as const

/** issueFilterLabels const 추론 타입 */
export type IssueFilterLabels = typeof issueFilterLabels
