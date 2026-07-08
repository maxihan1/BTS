// 개인 캘린더 월/주 뷰 i18n 라벨 (FR-CA-01 Task 7)

/**
 * 캘린더(월/주 뷰) 컴포넌트가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / toolbar / category / overflow / worklog / empty / error / a11y
 *
 * 주의: 모든 정적 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 * `currentStateKey`(API 원문, 예. `"in_progress"`) 자체를 화면에 노출하지 않는다 — 반드시
 * `category` 그룹의 한국어 라벨로 치환한다(설계 스펙 §11, 스네이크/영문 키 노출 금지).
 */
export const calendarLabels = {
  /** 페이지 헤더 */
  page: {
    /** 페이지 제목(h1) */
    title: '캘린더',
  },

  /** 상단 툴바(이전/다음/오늘, 월↔주 토글) */
  toolbar: {
    /** 이전 기간 이동 버튼 aria-label */
    prev: '이전',
    /** 다음 기간 이동 버튼 aria-label */
    next: '다음',
    /** 오늘로 이동 버튼 텍스트 */
    today: '오늘',
    /** 월 뷰 토글 버튼 텍스트 */
    monthView: '월',
    /** 주 뷰 토글 버튼 텍스트 */
    weekView: '주',
    /** 재조회 중 스피너 aria-label */
    loadingAriaLabel: '불러오는 중',
    /** 이전/오늘/다음 버튼 그룹 aria-label */
    periodGroupAriaLabel: '기간 이동',
    /** 월/주 토글 버튼 그룹 aria-label */
    viewToggleGroupAriaLabel: '보기 전환',
  },

  /** 워크플로우 상태 카테고리(TODO/IN_PROGRESS/DONE) 한국어 라벨 */
  category: {
    TODO: '할 일',
    IN_PROGRESS: '진행중',
    DONE: '완료',
  },

  /** 셀 오버플로 "+N개" 관련 라벨 */
  overflow: {
    /** "+N개" 버튼 텍스트 생성 함수 */
    more: (n: number): string => `+${n}개`,
    /** "+N개" 버튼 aria-label 생성 함수 */
    moreAriaLabel: (date: string, n: number): string => `${date} 이벤트 ${n}개 더 보기`,
  },

  /** Worklog 칩 관련 라벨 */
  worklog: {
    /** issueSummary가 null(비가시 이슈 마스킹)인 Worklog 칩 aria-label 생성 함수 */
    maskedAriaLabel: (key: string): string => `${key}, 비공개 이슈`,
  },

  /** 빈 상태(이벤트 0건) 안내 */
  empty: {
    message: '이 기간에 일정이 없습니다.',
  },

  /** 조회 실패 에러 배너 */
  error: {
    /** DESIGN.md §10 기존 문구 재사용 */
    message: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.',
    retry: '다시 시도',
  },

  /** 접근성(ARIA) 라벨 생성 함수 모음 */
  a11y: {
    /** 그리드(role=grid) aria-label 생성 함수 */
    gridAriaLabel: (label: string): string => `${label} 캘린더`,

    /** 날짜 셀(role=gridcell) aria-label 생성 함수 — 0건이면 "이벤트 없음" */
    cellAriaLabel: (date: string, count: number): string =>
      count > 0 ? `${date}, 이벤트 ${count}건` : `${date}, 이벤트 없음`,

    /**
     * 이슈 이벤트(기간 막대/마감일 칩) aria-label 생성 함수.
     * 있는 필드(시작일/마감일)만 조합한다.
     */
    eventAriaLabel: (
      key: string,
      summary: string,
      categoryKo: string,
      start: string | null,
      due: string | null,
    ): string => {
      const parts = [key, summary, categoryKo]
      if (start !== null) parts.push(`시작일 ${start}`)
      if (due !== null) parts.push(`마감일 ${due}`)
      return parts.join(', ')
    },
  },
} as const

/** calendarLabels const 추론 타입 */
export type CalendarLabels = typeof calendarLabels
