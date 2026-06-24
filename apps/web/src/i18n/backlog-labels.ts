// 백로그·스프린트 UI i18n 라벨 (FR-BL-01/02 D6/D7)

/**
 * 백로그·스프린트 칸 및 카드가 노출하는 한국어 라벨.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const backlogLabels = {
  /** 백로그 페이지 — 헤더 및 접근 거부 메시지 */
  page: {
    title: '백로그',
    boardLink: '보드',
    accessDenied: '접근 권한이 없습니다',
    accessDeniedDetail: '해당 프로젝트의 백로그에 접근할 권한이 없습니다.',
  },
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

  /** 스프린트 생성 폼 aria-label */
  createSprintFormLabel: '스프린트 생성 폼',

  /** 스프린트 이름 입력 placeholder */
  sprintNamePlaceholder: '스프린트 이름',

  /** 스프린트 생성 버튼 텍스트 */
  createSprint: '스프린트 생성',

  /** truncated 경고 배너 메시지 */
  truncatedWarning: '이슈 수가 많아 일부만 표시됩니다. 필터를 적용해 범위를 줄이세요.',

  /** 순서 반영 실패 경고 메시지 */
  rerankFailedWarning: '이슈 이동은 완료됐지만 순서 반영이 실패했습니다. 잠시 후 새로 고침 시 위치가 기본값으로 보일 수 있습니다.',

  /** 이동 실패 에러 메시지 */
  moveFailedError: '이슈 이동에 실패했습니다. 다시 시도해 주세요.',
} as const

/** backlogLabels const 추론 타입 */
export type BacklogLabels = typeof backlogLabels
