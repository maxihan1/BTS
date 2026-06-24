// 백로그·스프린트 UI i18n 라벨 (FR-BL-01/02 D6/D7)

/**
 * 백로그·스프린트 칸 및 카드가 노출하는 한국어 라벨.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const backlogLabels = {
  /** 백로그 칸 헤더 */
  backlogTitle: '백로그',

  /** 스프린트 상태 배지 라벨 */
  status: {
    PLANNED: 'PLANNED',
    ACTIVE: 'ACTIVE',
    COMPLETED: 'COMPLETED',
  },

  /** 시작 버튼 */
  startSprint: '스프린트 시작',

  /** 완료 버튼 */
  completeSprint: '스프린트 완료',

  /** 빈 이슈 목록 placeholder */
  emptyIssues: '이슈 없음',

  /** 담당자 미배정 텍스트 */
  unassigned: '미배정',

  /** 담당자 이름 미확인 title */
  unknownAssigneeTitle: '담당자 (이름 미확인)',

  /** 담당자 이름 미확인 aria-label */
  unknownAssigneeAriaLabel: '담당자 이름 미확인',

  /** 드래그 가능 카드 aria-roledescription */
  draggableCard: 'draggable card',

  /** 칸 aria-label 생성 함수 */
  columnAriaLabel: (name: string, count: number): string =>
    `${name} 칸, ${count}개 이슈`,

  /** 카드 aria-label 생성 함수 */
  cardAriaLabel: (key: string, summary: string): string =>
    `${key} — ${summary}`,
} as const

/** backlogLabels const 추론 타입 */
export type BacklogLabels = typeof backlogLabels
