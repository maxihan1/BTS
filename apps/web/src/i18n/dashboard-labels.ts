// 대시보드 i18n 라벨 — 목록·카드·상세·폼·플레이스홀더 그룹

/**
 * 대시보드 화면 전반에서 사용하는 한국어 라벨/텍스트.
 *
 * 그룹 — 입력 placeholder(최상위) / list / card / detail / form / placeholder
 *
 * 주의: 최상위 `*Placeholder` 키와 `placeholder` 그룹은 다른 것이다 —
 * 앞은 input/textarea 의 `placeholder` 속성, 뒤는 「위젯 자리」 타일 문구다.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 * 주의: 내부 FR 식별자(FR-DB-01 등)를 사용자에게 노출하지 않는다 (DESIGN.md §10).
 */
export const dashboardLabels = {
  /** 공유 대상 사용자 검색 input placeholder (`DashboardForm.tsx` 공유 섹션) */
  ownerSearchPlaceholder: '사용자 이름 검색',
  /** 대시보드 이름 input placeholder */
  namePlaceholder: '대시보드 이름',
  /** 대시보드 설명 input placeholder — 선택 항목임을 알린다 */
  descriptionPlaceholder: '대시보드 설명 (선택)',
  /** 마크다운 가젯 설정 textarea placeholder (`GadgetConfigForm.tsx`) */
  markdownGadgetPlaceholder: '마크다운 텍스트를 입력하세요...',

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

  /** 대시보드 공유 모달 및 익명 열람 화면 라벨 */
  share: {
    /** 공유 모달 제목 */
    modalTitle: '대시보드 공유',

    /** 링크 생성 버튼 */
    generateLink: '링크 생성',

    /** 복사 버튼 */
    copy: '복사',

    /** 복사 완료 후 일시 전환되는 버튼 라벨 (BackupCodesSection 패턴) */
    copied: '복사됨',

    /** 임베드 코드 섹션 제목 */
    embedCode: '임베드 코드',

    /** 발급된 링크 목록 헤더 */
    issuedLinks: '발급된 링크',

    /** 개별 링크 항목의 취소(회수) 버튼 */
    revoke: '취소',

    /** 만료 라벨 */
    expiresAt: '만료',

    /** 만료일이 없는 링크의 표시 문구 */
    noExpiry: '만료 없음',

    /** PRIVATE/TEAM 공개 범위 대시보드의 공유 링크 경고 배너 */
    visibilityWarning: '링크가 있는 누구나 읽을 수 있습니다',

    /** 원문 토큰이 발급 시점에만 노출됨을 알리는 재조회 불가 안내 */
    copyOnceNotice: '이 링크는 지금만 복사할 수 있습니다',

    /** 발급된 공유 링크가 없을 때 빈 상태 문구 */
    empty: '아직 발급된 공유 링크가 없습니다',

    /** 링크 취소 인라인 확인 문구 */
    revokeConfirm: '정말 이 링크를 취소하시겠습니까?',

    /** 무효/만료/삭제된 토큰으로 익명 열람 시도 시 표시하는 404 문구 */
    notFound: '공유된 대시보드를 찾을 수 없습니다',

    /** 익명 열람 화면에서 로그인이 필요한 데이터 가젯의 플레이스홀더 문구 */
    authRequiredGadget: '로그인이 필요한 가젯입니다',
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
/**
 * 가젯 설정 선택기 문구.
 *
 * 스코프성 필드(프로젝트·보드·필터)는 자유 입력이 아니라 드롭다운이다 —
 * 사용자가 UUID 를 손으로 타이핑하지 않게 한다.
 */
export const gadgetPickerLabels = {
  loading: '불러오는 중...',
  selectProject: '프로젝트를 선택하세요',
  selectBoard: '보드를 선택하세요',
  selectFilter: '필터를 선택하세요',
  /** 보드는 프로젝트에 종속이라 프로젝트를 먼저 골라야 한다 */
  selectProjectFirst: '먼저 프로젝트를 선택하세요',
  /** 조용히 비우지 않는다 — 값이 사라진 것처럼 보이면 안 된다 */
  boardResetByProjectChange: '프로젝트를 바꿔 보드 선택을 지웠습니다. 보드를 다시 골라 주세요.',
  /** 보드 종류 — 번다운은 스크럼에만 있다 */
  scrum: '스크럼',
  kanban: '칸반',
} as const

/**
 * 가젯 본문의 상태 문구 — 로딩·빈 상태·오류.
 *
 * ★가젯마다 문구를 따로 쓰지 않는다. 「데이터가 없습니다」가 가젯마다 다르게 적히면
 * 사용자는 그 차이를 의미로 읽는다(다른 이유로 비어 있다고 오해한다).
 */
export const gadgetStateLabels = {
  /** 이슈가 0건이라 그릴 분포가 없다 */
  noDistribution: '표시할 이슈가 없습니다',
  /** 프로젝트/보드를 못 읽었다 — 404 를 포함해 여기로 흡수한다 */
  loadFailed: '데이터를 불러오지 못했습니다',
  /** 스크럼이 아니거나 스프린트를 시작하지 않았다 */
  noActiveSprint: '활성 스프린트가 없습니다',
  /** 변경 이력이 0건 */
  noActivity: '아직 활동이 없습니다',
  /** 설정이 비어 가젯이 무엇을 그릴지 모른다 */
  notConfigured: '가젯 설정이 필요합니다',
  /** 로딩 중 — 스크린리더용 */
  loading: '불러오는 중',
} as const

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
