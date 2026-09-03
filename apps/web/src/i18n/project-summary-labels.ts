// 프로젝트 요약 화면 i18n 라벨 단일 출처 (Jira 패리티 J4 · 캠페인 PR ④)

/**
 * 프로젝트 요약 화면이 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / cards / delta / distribution / activity / status
 *
 * 규칙.
 * - 모든 한국어 문장 끝은 `.` `?` `!` 또는 명사 종결 (콜론 종결 금지, 글로벌 §5)
 * - 카드·위젯 제목은 e2e 가 접근성 이름으로 참조하므로 글자 변경 시 짝 테스트를 함께 고친다
 */
export const projectSummaryLabels = {
  /** 페이지 헤더 영역 */
  page: {
    /** 페이지 설명 — 목업 v2 부제 그대로 */
    description: '최근 7일 활동과 현재 작업 분포입니다.',
    /** 보드로 이동하는 주요 CTA */
    goToBoard: '보드로 가기',
    /** 백로그로 이동하는 보조 CTA */
    goToBacklog: '백로그로 가기',
  },

  /** 상단 카드 4종 */
  cards: {
    /** 최근 7일 완료 — 상태 이력의 DONE 진입 기준 */
    completed: '최근 7일 완료',
    /** 최근 7일 업데이트 */
    updated: '최근 7일 업데이트',
    /** 최근 7일 생성 */
    created: '최근 7일 생성',
    /** 향후 7일 마감 예정 */
    due: '마감 예정',
  },

  /** 카드 하단 델타 문구 */
  delta: {
    /** 직전 7일과 같을 때 */
    flat: '변동 없음',
    /** 델타 문구가 무엇과 비교한 값인지 밝히는 접미 */
    comparedTo: '직전 7일 대비',
    /** 마감 카드의 지연 건수 접미 — `{n}건 지연` */
    overdueSuffix: '건 지연',
    /** 지연이 0 일 때 */
    noOverdue: '지연 없음',
  },

  /** 분포 위젯 4종 */
  distribution: {
    /** 상태 개요 — DONE 은 최근 2주 완료분만 (Jira 원문 스펙) */
    statusOverview: '상태 개요',
    /** 상태 개요의 DONE 창을 밝히는 각주 */
    statusOverviewNote: '완료는 최근 2주 안에 완료된 항목만 셉니다.',
    /** 우선순위 분포 */
    priority: '우선순위',
    /** 작업 유형 분포 */
    typesOfWork: '작업 유형',
    /** 담당자 분포 */
    teamWorkload: '담당자별 작업량',
    /** 담당자 미할당 묶음 표시명 */
    unassigned: '미할당',
    /** 표시명을 못 얻었을 때의 담당자 표시 */
    unknownAssignee: '알 수 없는 사용자',
    /** 분포 막대의 접근성 이름 접미 — `{name} {count}건` */
    countSuffix: '건',
  },

  /** 활동 피드 위젯 */
  activity: {
    /** 위젯 제목 */
    title: '최근 활동',
    /** 항목이 없을 때 */
    empty: '아직 기록된 활동이 없습니다.',
    /** 행위자를 모를 때 */
    unknownActor: '시스템',
    /** 변경 항목이 여러 개일 때의 요약 — `외 {n}건` */
    moreItemsPrefix: '외 ',
    /** 변경 항목 요약 접미 */
    moreItemsSuffix: '건',
  },

  /** 로딩·에러·빈 상태 */
  status: {
    /** 조회 중 */
    loading: '요약을 불러오는 중입니다.',
    /** 403 — BROWSE 권한 없음 */
    forbidden: '이 프로젝트를 볼 권한이 없습니다.',
    /** 그 밖의 실패 */
    loadFailed: '요약을 불러오지 못했습니다.',
    /** 활동 피드만 실패했을 때 — 요약은 살아 있다 */
    activityFailed: '활동을 불러오지 못했습니다.',
    /** 분포가 하나도 없을 때 */
    empty: '아직 집계할 이슈가 없습니다.',
  },
} as const
