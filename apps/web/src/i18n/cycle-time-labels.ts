// Cycle Time / Lead Time 분포 리포트 페이지 i18n 라벨 (FR-RP-04 D6/D7)

/**
 * Cycle Time / Lead Time 분포 리포트 페이지가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / metric / stats / status / metricEmpty / chart / window
 *
 * 규칙.
 * - 모든 한국어 문장 끝은 `.` `?` `!` 또는 명사 종결 (콜론 종결 금지, 글로벌 §5)
 */
export const cycleTimeLabels = {
  /** 페이지 헤더 영역 */
  page: {
    /** 페이지 h1 제목 */
    title: 'Cycle / Lead Time 분포',
    /** 페이지 설명 — 완료 이슈 소요 시간 분포 안내 */
    description:
      '완료된 이슈가 소요된 시간의 분포를 히스토그램과 박스플롯으로 보여줍니다.',
  },

  /** Cycle/Lead 두 지표 섹션 제목·설명 */
  metric: {
    /** Cycle Time 섹션 제목 */
    cycleTitle: 'Cycle Time',
    /** Lead Time 섹션 제목 */
    leadTitle: 'Lead Time',
    /** Cycle Time 설명 — 첫 진행 착수 → 완료 */
    cycleDesc: '첫 진행 착수부터 완료까지 걸린 시간입니다.',
    /** Lead Time 설명 — 생성 → 완료 */
    leadDesc: '이슈 생성부터 완료까지 걸린 시간입니다.',
  },

  /** 요약 통계 타일 라벨 */
  stats: {
    /** 표본 개수 */
    count: '표본 수',
    /** 최소값 */
    min: '최소',
    /** 최대값 */
    max: '최대',
    /** 평균값 */
    avg: '평균',
    /** 25백분위수 */
    p25: '25백분위',
    /** 중앙값(50백분위수) */
    p50: '중앙값',
    /** 75백분위수 */
    p75: '75백분위',
    /** 90백분위수 */
    p90: '90백분위',
  },

  /** 로딩/에러/빈 상태 안내 문구 */
  status: {
    /** 데이터를 불러오는 중 표시하는 문구 */
    loading: '불러오는 중…',
    /** 403 — 프로젝트 접근 권한이 없을 때 */
    forbidden: '접근 권한이 없습니다.',
    /** 전체 빈 상태 — 창 내 완료 이슈가 하나도 없는 경우 */
    empty: '최근 30일 내 완료된 이슈가 없습니다.',
    /** 예기치 못한 조회 실패(네트워크·5xx 등) 시 일반 안내 */
    loadFailed: '데이터를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  },

  /** 지표별 표본 없음 안내(전체 빈 상태와 구분) */
  metricEmpty: {
    /** Cycle Time 표본 없음 — 완료 이슈는 있으나 IN_PROGRESS 미경유 */
    cycle: '진행 중 상태를 거친 완료 이슈가 없습니다.',
    /** Lead Time 표본 없음 — lead≥cycle이라 실제로는 도달 불가한 방어 분기(의미 정합용) */
    lead: '완료된 이슈가 없습니다.',
  },

  /** 차트 접근성/축 제목 */
  chart: {
    /** 히스토그램 컨테이너 aria-label */
    histogramAriaLabel: '소요 시간 구간별 이슈 수 분포 히스토그램',
    /** 박스플롯 컨테이너 aria-label */
    boxPlotAriaLabel: '최소, 25백분위, 중앙값, 75백분위, 최대값을 보여주는 박스플롯',
    /** X축 제목 */
    xAxisTitle: '소요 시간',
    /** Y축 제목 */
    yAxisTitle: '이슈 수',
  },

  /** 조회 창(from~to) 표기 */
  window: {
    /** from~to 텍스트 표기 접두 */
    prefix: '조회 기간',
  },
} as const

/** 라벨 const 추론 타입 */
export type CycleTimeLabels = typeof cycleTimeLabels
