// 워크로그 집계 보고 페이지 i18n 라벨 (FR-TT-02 D6)

/**
 * 워크로그 집계 보고 페이지가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / filter / summary / chart / table / empty / error
 *
 * 규칙.
 * - 모든 한국어 문장 끝은 `.` `?` `!` 또는 명사 종결 (콜론 종결 금지, 글로벌 §5)
 * - chart.topNHint 의 N 자리는 `{count}` 플레이스홀더로 표기
 */
export const worklogAggregateLabels = {
  /** 페이지 헤더 영역 */
  page: {
    /** 페이지 h1 제목 */
    title: '워크로그 집계 보고',
    /** 페이지 설명 문구 */
    description: '프로젝트 내 워크로그를 이슈·사용자·기간 기준으로 집계합니다.',
  },

  /** 필터 컨트롤 영역 */
  filter: {
    /** 집계 차원 셀렉터 label */
    dimensionLabel: '집계 기준',
    /** 차원 옵션 — 이슈별 */
    dimensionIssue: '이슈별',
    /** 차원 옵션 — 사용자별 */
    dimensionUser: '사용자별',
    /** 차원 옵션 — 기간별 */
    dimensionPeriod: '기간별',
    /** 집계 단위(granularity) 셀렉터 label */
    granularityLabel: '집계 단위',
    /** 단위 옵션 — 일 */
    granularityDay: '일',
    /** 단위 옵션 — 주 */
    granularityWeek: '주',
    /** 단위 옵션 — 월 */
    granularityMonth: '월',
    /** 시작일 입력 label */
    fromLabel: '시작일',
    /** 종료일 입력 label */
    toLabel: '종료일',
  },

  /** 요약 수치 영역 */
  summary: {
    /** 총 소요 시간 label */
    totalTimeLabel: '총 소요 시간',
  },

  /** 차트 영역 */
  chart: {
    /** 차트 섹션 제목 */
    title: '집계 차트',
    /** 차트 컨테이너 aria-label */
    ariaLabel: '워크로그 집계 막대 차트',
    /**
     * 상위 N개만 표시한다는 안내 문구.
     * `{count}` 를 실제 숫자로 교체해 사용한다.
     * 예) "상위 10개 항목만 표시됩니다."
     */
    topNHint: '상위 {count}개 항목만 표시됩니다.',
  },

  /** 테이블 영역 */
  table: {
    /** 레이블 열 헤더 */
    headerLabel: '레이블',
    /** 소요 시간 열 헤더 */
    headerTimeSpent: '소요 시간',
    /** 건수 열 헤더 */
    headerCount: '건수',
    /** 합계 행 라벨 */
    totalRow: '합계',
    /** displayName 이 빈 문자열일 때 표시할 placeholder */
    unknownDisplayName: '(알 수 없음)',
  },

  /** 로딩 상태 */
  loading: {
    /** 집계 데이터를 불러오는 중 표시하는 문구 */
    message: '불러오는 중…',
  },

  /** 빈 상태 */
  empty: {
    /** 집계 결과가 없을 때 표시하는 안내 문구 */
    message: '기록된 워크로그가 없습니다.',
  },

  /** 에러 상태 */
  error: {
    /** 일반 에러 안내 문구 (403 권한 안내는 ProjectNotFoundScreen 재사용) */
    generalMessage: '데이터를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  },
} as const

/** 라벨 const 추론 타입 */
export type WorklogAggregateLabels = typeof worklogAggregateLabels
