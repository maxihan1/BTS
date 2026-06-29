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

  /** 403 접근 거부 안내 (S6 — AGILE_ACCESS_DENIED) */
  accessDenied: {
    /** 접근 거부 제목 */
    title: '접근 권한이 없습니다',

    /** 접근 거부 설명 */
    description: '해당 프로젝트의 타임라인에 접근할 권한이 없습니다.',
  },

  /** 이슈 일부 누락 배너 안내 (S5 — truncated) */
  truncated: {
    /** TIMELINE_FETCH_LIMIT 초과 시 표시하는 안내 메시지 */
    message: '표시되지 않은 이슈가 있습니다. 이슈 목록에서 전체를 확인하세요.',
  },

  /** 타임라인 줌 레벨 관련 라벨 (FR-TL-03 D6) */
  zoom: {
    week: '주',
    month: '월',
    quarter: '분기',
    groupAriaLabel: '타임라인 줌 레벨',
    zoomInAriaLabel: '확대',
    zoomOutAriaLabel: '축소',
  },

  /** blocks 의존 라인 관련 라벨 (FR-TL-02 D6) */
  deps: {
    /**
     * 의존 라인 aria-label 생성 함수.
     *
     * blockerKey가 blockedKey를 차단하는 관계를 설명한다 (NFR3 접근성).
     */
    lineAriaLabel: (blocker: string, blocked: string): string =>
      `${blocker}가 ${blocked}을 차단`,

    /**
     * deps truncated 시 누락 경고 메시지.
     *
     * 기존 timeline 이슈 누락 배너(truncated.message)와 구분되는 문구.
     */
    truncatedMessage: '일부 의존 라인이 생략되었습니다',
  },
} as const

/** timelineLabels const 추론 타입 */
export type TimelineLabels = typeof timelineLabels
