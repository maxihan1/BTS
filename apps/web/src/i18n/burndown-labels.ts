// 번다운/번업 차트 페이지 i18n 라벨 (FR-RP-01 D6)

/**
 * 번다운/번업 차트 페이지가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / toggle / series / status / chart
 *
 * 규칙.
 * - 모든 한국어 문장 끝은 `.` `?` `!` 또는 명사 종결 (콜론 종결 금지, 글로벌 §5)
 */
export const burndownLabels = {
  /** 페이지 헤더 영역 */
  page: {
    /** 페이지 h1 제목 */
    title: '번다운 / 번업 차트',
  },

  /** 번다운/번업 뷰 전환 토글(segmented control) */
  toggle: {
    /** 번다운 뷰 라벨 */
    burndown: '번다운',
    /** 번업 뷰 라벨 */
    burnup: '번업',
  },

  /** 차트 시리즈명 (범례·툴팁 공용) */
  series: {
    /** 잔여 추정시간 라인 */
    remaining: '잔여',
    /** 이상적 소진 기준선 */
    ideal: '이상선',
    /** 스프린트 범위(총 추정시간) 라인 */
    scope: '범위',
    /** 완료 추정시간 라인(번업 뷰) */
    completed: '완료',
  },

  /** 로딩/에러/빈 상태 안내 문구 */
  status: {
    /** 데이터를 불러오는 중 표시하는 문구 */
    loading: '불러오는 중…',
    /** 403 — 프로젝트 접근 권한이 없을 때 */
    forbidden: '접근 권한이 없습니다.',
    /** 404 — 스프린트를 찾을 수 없을 때 */
    sprintNotFound: '스프린트를 찾을 수 없습니다.',
    /** 422(SPRINT_DATES_REQUIRED) — 스프린트 시작일/종료일 미설정 안내 */
    datesRequired: '스프린트 시작일과 종료일을 설정해야 번다운을 볼 수 있습니다.',
    /** points 배열이 빈 경우의 warm 톤 안내 문구 */
    empty: '아직 표시할 데이터가 없습니다.',
    /** 예기치 못한 조회 실패(네트워크·5xx 등) 시 일반 안내 */
    loadFailed: '데이터를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  },

  /** 차트 접근성/설명 */
  chart: {
    /** 차트 컨테이너 aria-label — 차트 목적과 시리즈를 서술 */
    ariaLabel: '번다운 차트, 스프린트의 잔여 작업량과 이상적인 소진 추이, 범위 변화를 선으로 보여줍니다',
    /** Y축 제목 — 값 단위(시간) */
    yAxisTitle: '시간',
  },
} as const

/** 라벨 const 추론 타입 */
export type BurndownLabels = typeof burndownLabels
