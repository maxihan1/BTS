// 타임라인/Gantt 뷰 i18n 라벨 (FR-TL-01 D6)

/**
 * 타임라인/Gantt 컴포넌트가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — group / row / axis / empty
 *
 * 주의: 모든 정적 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const timelineLabels = {
  /** 에픽 그룹 관련 라벨 */
  group: {
    /** 미분류 그룹 헤더 레이블 */
    unclassifiedHeader: '미분류',

    /** 에픽 그룹 접기 버튼 aria-label (현재 펼쳐진 상태에서 사용) */
    collapseAriaLabel: '그룹 접기',

    /** 에픽 그룹 펼치기 버튼 aria-label (현재 접힌 상태에서 사용) */
    expandAriaLabel: '그룹 펼치기',
  },

  /** 행 레이블 관련 라벨 */
  row: {
    /** 담당자 미배정(assigneeId=null) 폴백 텍스트 (EC11) */
    unassigned: '미배정',

    /** 담당자 ID가 있으나 이름을 알 수 없는 경우 폴백 텍스트 (EC11) */
    unknownAssignee: '알 수 없음',

    /** targetDate 마일스톤 ◆ 접근성 라벨 생성 함수 */
    milestoneAriaLabel: (key: string): string => `${key} 목표일`,

    /** 막대 aria-label 생성 함수 */
    barAriaLabel: (key: string, start: string | null, due: string | null): string =>
      `${key} ${start ?? '미정'} ~ ${due ?? '미정'}`,
  },

  /** 시간 축 관련 라벨 */
  axis: {
    /** 주 눈금 접두사 */
    weekPrefix: 'W',
  },

  /** 빈 상태 라벨 */
  empty: {
    /** 표시할 아이템이 없는 경우 */
    noItems: '표시할 이슈가 없습니다',

    /** 날짜 범위를 계산할 수 없는 경우 */
    noDateRange: '날짜 정보가 없습니다',
  },
} as const

/** timelineLabels const 추론 타입 */
export type TimelineLabels = typeof timelineLabels
