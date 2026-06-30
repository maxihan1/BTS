// 대시보드 i18n 라벨 — 목록·카드·상세·폼·플레이스홀더 그룹

/**
 * 대시보드 화면 전반에서 사용하는 한국어 라벨/텍스트.
 *
 * 그룹 — list / card / detail / form / placeholder
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 * 주의: 내부 FR 식별자(FR-DB-01 등)를 사용자에게 노출하지 않는다 (DESIGN.md §10).
 */
export const dashboardLabels = {
  /** 대시보드 목록 화면 라벨 */
  list: {
    /** 목록 페이지 제목 */
    title: '대시보드',

    /** 빈 상태 안내 */
    empty: {
      /** 빈 상태 제목 */
      title: '대시보드가 없습니다',

      /** 빈 상태 맥락 문구 */
      description: '새 대시보드를 만들어 이슈 현황을 한눈에 확인하세요',

      /** 빈 상태 CTA 버튼 텍스트 */
      cta: '대시보드 만들기',
    },

    /** 로딩 상태 텍스트 */
    loading: '대시보드 목록을 불러오는 중입니다',

    /** 오류 상태 텍스트 */
    error: '대시보드 목록을 불러오지 못했습니다',
  },

  /** 대시보드 목록 카드 라벨 */
  card: {
    /** 공개 범위 배지 */
    visibility: {
      /** 나만 볼 수 있음 */
      PRIVATE: '나만 보기',

      /** 팀 공유 */
      TEAM: '팀 공유',

      /** 조직 전체 공유 */
      ORG: '전체 공유',
    },

    /** 카드 소유자 표시 배지 */
    ownerBadge: '내 대시보드',
  },

  /** 대시보드 상세/편집 화면 라벨 */
  detail: {
    /** 저장 버튼 */
    save: '저장',

    /** 삭제 버튼 */
    delete: '삭제',

    /** 설정 버튼/메뉴 */
    settings: '설정',

    /** 가젯 추가 버튼 (DashboardGrid 빈 상태 1차 CTA — 헤더 버튼과 동일 라벨, C4 가젯 일원화) */
    addWidget: '가젯 추가',

    /** 그리드가 비어있을 때 안내 문구 */
    emptyGrid: '가젯 추가 버튼을 눌러 대시보드를 채워보세요',

    /** 저장 중 상태 텍스트 */
    saving: '저장 중입니다',

    /** 미저장 변경 사항 알림 */
    unsavedChanges: '저장되지 않은 변경 사항이 있습니다',

    /** 삭제 인라인 확인 문구 */
    deleteConfirm: '정말 삭제하시겠습니까?',

    /** 삭제 확인 버튼 */
    confirmButton: '확인',

    /** 삭제 취소 버튼 */
    cancelButton: '취소',

    /** 삭제 확인 버튼 aria-label */
    confirmDeleteAriaLabel: '삭제 확인',

    /** 삭제 취소 버튼 aria-label */
    cancelDeleteAriaLabel: '삭제 취소',
  },

  /** 대시보드 생성/수정 폼 라벨 */
  form: {
    /** 이름 필드 레이블 */
    name: '이름',

    /** 설명 필드 레이블 */
    description: '설명',

    /** 공개 범위 필드 레이블 */
    visibility: '공개 범위',

    /** 공유 설정 섹션 레이블 */
    share: '공유',
  },

  /** 위젯 자리 표시 타일 문구 — 사용자에게 노출되는 중립 한국어 */
  placeholder: {
    /** 플레이스홀더 타일 제목 */
    title: '위젯 자리',

    /** 플레이스홀더 타일 안내 문구 */
    description: '이 자리에 위젯을 추가할 수 있습니다',
  },
} as const

/** dashboardLabels const 추론 타입 */
export type DashboardLabels = typeof dashboardLabels

/**
 * gadgetType → 한국어 라벨 매핑 (12종).
 *
 * 백엔드 GadgetType enum의 snake_case 직렬화 키를 한국어 표시명으로 매핑한다.
 * DashboardTile 헤더에서 raw gadgetType 대신 이 라벨을 표시한다.
 * 미지 타입은 호출측에서 gadgetType 자체를 fallback으로 사용한다.
 *
 * ⚠️ 백엔드 GadgetType.kt 신규 타입 추가 시 이 매핑도 동기화할 것.
 */
export const gadgetLabels: Readonly<Record<string, string>> = {
  assigned_to_me: '내게 할당된 이슈',
  recently_created: '최근 생성',
  filter_result: '필터 결과',
  issue_count: '이슈 건수',
  text_widget: '텍스트',
  link_list: '링크 목록',
  pie_chart: '파이 차트',
  bar_chart: '막대 차트',
  created_vs_resolved: '생성/해결 추이',
  sprint_burndown: '스프린트 번다운',
  activity_stream: '활동 스트림',
  comments_recent: '최근 댓글',
} as const
