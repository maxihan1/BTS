// 칸반 보드 WIP/스윔레인 i18n 라벨

/**
 * 칸반 보드 컬럼 헤더가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — placeholder(최상위) / page / switcher / wip / column / swimlane
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const boardLabels = {
  /** 보드 생성 폼(`board/CreateBoardForm.tsx`) 이름 input 의 예시 placeholder */
  createFormNamePlaceholder: '스프린트 보드',

  /** 보드 페이지 상단 보드 스위처가 현재 보드를 못 찾았을 때의 대체 표기 */
  boardSelectPlaceholder: '보드 선택',

  /** 보드 페이지 — 헤더 및 뷰 전환 링크 */
  page: {
    /**
     * 보드 화면 h1.
     *
     * 형제 뷰(`backlogLabels.page.title` = '백로그')와 같은 규칙이다 — 지금 보고 있는 뷰의
     * 이름을 쓴다. 이 키가 없던 동안 보드만 h1 이 0개라 헤더에 즐겨찾기 별 아이콘 하나만
     * 떠 있었고, 문서당 h1 계약도 그 화면에서만 비어 있었다.
     */
    title: '보드',
    /** 백로그 뷰 전환 링크 텍스트 */
    backlogLink: '백로그',
    /** 타임라인 뷰 전환 링크 텍스트 */
    timelineLink: '타임라인',
    /**
     * 보드 헤더 이슈 생성 진입점 (FR-UX-09 F3 FR-3).
     *
     * 🛑 **컬럼별이 아니라 보드 1곳**이다 — 생성 계약에 상태(`stateKey`)가 없어
     * 컬럼별 버튼은 지키지 못할 약속이 된다 (ADR 2026-08-03 D-3).
     *
     * ⚠️ 값을 고칠 때 `i18n/__tests__/create-entry-point-names.test.ts` 를 먼저 읽을 것 —
     * 상단바 `만들기` · 모달 제출 `이슈 생성` 을 부분 문자열로 포함하면 기존 e2e 가 깨진다.
     */
    createIssue: '이슈 추가',
  },

  /**
   * 보드 스위처 — 보드가 1개여도 상시 노출되는 전환 드롭다운 (FR-BD-01-2c · Jira 근거 J1·J2).
   *
   * Jira Cloud company-managed 는 프로젝트당 보드를 N개 갖고 생성 진입점을 보드 존재 여부와
   * 무관하게 제공한다. BTS 에는 전역 Boards 디렉터리도 사이드바 hover `+` 도 없어
   * 진입점 2곳을 이 드롭다운 하나로 접었다 (plan 의 의도적 편차 X1).
   */
  switcher: {
    /**
     * 스위처 트리거의 aria-label.
     *
     * 트리거의 보이는 텍스트는 현재 보드 **이름**이라 프로젝트마다 접근성 이름이 달라진다.
     * 역할을 앞에 고정해 「이 버튼이 무엇인가」를 이름만으로 알 수 있게 하고, 현재 값도 함께 읽힌다.
     *
     * @param currentName 현재 선택된 보드 이름
     * @returns "보드 선택, 현재 {currentName}"
     */
    triggerAriaLabel: (currentName: string): string => `보드 선택, 현재 ${currentName}`,

    /** 드롭다운 안 보드 라디오 그룹의 섹션 라벨 */
    groupLabel: '보드',

    /**
     * 보드 생성 진입 메뉴 항목.
     *
     * 🛑 「보드 만들기」로 쓰지 마라 — `CreateBoardForm` 제출 버튼이 그 문자열이고
     *    `e2e/board-kanban.spec.ts` 가 `exact: true` 로 그것을 잡는다. 이 항목이 열어 주는
     *    다이얼로그 안에 바로 그 버튼이 들어가므로, 같은 문구를 쓰면 「어느 쪽을 눌렀는가」가
     *    화면에서도 테스트에서도 흐려진다.
     */
    createItem: '새 보드',

    /** 보드 생성 다이얼로그 제목 — Radix 가 이 값을 다이얼로그 접근성 이름으로 쓴다 */
    createDialogTitle: '새 보드',
  },

  /**
   * 보드 관리 `⋯` 메뉴 — 이름 변경 · 삭제 (FR-BD-01-2a/2b · Jira 근거 J3·J4).
   *
   * Jira Cloud 는 이름 변경을 **보드 설정 화면**의 연필로, 삭제를 **Boards 디렉터리**의 행 `⋯`
   * 로 한다. BTS 에는 그 두 화면이 없어 plan 의 의도적 편차 X2 대로 조작을 보드 헤더의 `⋯`
   * 하나로 모았다. 권한이 없으면 항목을 **렌더하지 않는다**(비활성이 아니다 · J5).
   */
  actions: {
    /**
     * `⋯` 트리거의 aria-label.
     *
     * 보이는 것은 점 세 개뿐이라 이름이 없으면 「메뉴」로만 읽힌다. 무엇에 대한 메뉴인지와
     * 대상 보드를 함께 싣는다 — 스위처 트리거와 나란히 있어 구별이 필요하다.
     *
     * @param boardName 현재 보드 이름
     * @returns "보드 관리, {boardName}"
     */
    triggerAriaLabel: (boardName: string): string => `보드 관리, ${boardName}`,

    /** 이름 변경 메뉴 항목 */
    renameItem: '이름 변경',

    /** 삭제 메뉴 항목 */
    deleteItem: '보드 삭제',

    /** 이름 변경 다이얼로그 제목 — Radix 가 접근성 이름으로 쓴다 */
    renameDialogTitle: '보드 이름 변경',

    /** 이름 변경 입력 라벨 */
    renameNameLabel: '보드 이름',

    /** 이름 변경 제출 버튼 */
    renameSubmit: '저장',

    /** 이름 변경 취소 버튼 */
    renameCancel: '취소',

    /** 이름 변경 실패 — 모르는 코드일 때의 기본 문구 */
    renameFailed: '보드 이름을 바꾸지 못했습니다.',

    /**
     * 삭제 확인 다이얼로그 제목.
     *
     * 🛑 **화면 안에서 고유해야 한다** — 같은 이름의 dialog 가 둘이면 Playwright
     * `getByRole('dialog', { name })` 가 strict mode 로 즉사한다 (계약 §2).
     */
    deleteDialogTitle: '보드 삭제',

    /**
     * 삭제 확인 설명.
     *
     * 「이슈는 삭제되지 않습니다」가 **소프트 삭제의 유일한 고지**다(J4). 이 문장이 빠지면
     * 사용자는 보드를 지우면 이슈도 사라진다고 읽는다.
     *
     * 이름은 따옴표로 감싼다 — 「ATLAS 보드 보드를 지웁니다」처럼 이름 끝의 「보드」가 겹쳐 읽히는
     * 것을 실제 화면에서 확인하고 고쳤다. 조사는 이름의 받침에 따라 갈리므로 `을(를)` 형태를 쓴다.
     *
     * @param boardName 지울 보드 이름
     */
    deleteDialogDescription: (boardName: string): string =>
      `「${boardName}」을(를) 지웁니다. 이 보드의 이슈는 삭제되지 않습니다.`,

    /** 삭제 확인 버튼 */
    deleteConfirm: '삭제',

    /** 삭제 취소 버튼 */
    deleteCancel: '취소',

    /** 삭제 실패 — 모르는 코드일 때의 기본 문구 */
    deleteFailed: '보드를 지우지 못했습니다.',

    /**
     * 상태 코드조차 없는 실패 — 연결이 끊겼거나 응답이 상한 안에 오지 않았다.
     *
     * `useDeleteBoard` 의 타임아웃도 여기로 온다. 사용자가 할 다음 행동이 같기 때문에
     * 두 경우를 한 문구로 묶는다 (`lib/move-error-message.ts` 가 세운 세 갈래와 같은 규칙).
     */
    noResponse: '서버 응답이 없습니다. 연결을 확인하고 다시 시도해 주세요.',
  },

  /** WIP(Work In Progress) 제한 관련 라벨 */
  wip: {
    /**
     * 카드 수/WIP 한도 표기 문자열 생성.
     * @param count 현재 카드 수
     * @param limit WIP 한도
     * @returns "{count}/{limit}"
     */
    countLabel: (count: number, limit: number): string => `${count}/${limit}`,

    /** WIP 초과 경고 aria-label — 스크린리더 및 시각 경고 용도 */
    exceededAriaLabel: 'WIP 초과',

    /** WIP 초과 경고 툴팁/보조 텍스트 */
    exceededTooltip: 'WIP 제한을 초과했습니다',

    /** 필터 활성 시 WIP 배지 "(필터됨)" 접미 라벨 */
    filteredSuffix: '(필터됨)',

    /** 필터 활성 시 WIP 배지 aria-label — 전체 WIP 상태 미반영 고지 */
    filteredAriaLabel: '필터 적용 중, 전체 WIP 상태 아님',
  },

  /** 컬럼 헤더 일반 라벨 */
  column: {
    /**
     * 컬럼 aria-label 생성 함수.
     * @param name 컬럼 이름
     * @param count 카드 수
     * @returns "{name} 컬럼, {count}개 카드"
     */
    ariaLabel: (name: string, count: number): string =>
      `${name} 컬럼, ${count}개 카드`,

    /**
     * 카드 수 배지 aria-label 생성 함수.
     * @param count 카드 수
     * @returns "카드 {count}개"
     */
    cardCountAriaLabel: (count: number): string => `카드 ${count}개`,

    /** 카테고리 배지 aria-label 접두사 */
    categoryAriaLabel: (category: string): string => `카테고리: ${category}`,
  },
  /** 스윔레인 셀렉터 관련 라벨 */
  swimlane: {
    /** 셀렉터 접근성 레이블 */
    selectorLabel: '스윔레인',

    /** 셀렉터 옵션 라벨 — SwimlaneField enum 값에 대응 */
    options: {
      /** NONE — 스윔레인 없음 */
      NONE: '없음',
      /** ASSIGNEE — 담당자별 그룹 */
      ASSIGNEE: '담당자',
      /** PRIORITY — 우선순위별 그룹 */
      PRIORITY: '우선순위',
      /** EPIC — 에픽별 그룹 */
      EPIC: '에픽',
    },

    /** 변경 실패 시 토스트 오류 메시지 */
    updateError: '스윔레인 기준 변경에 실패했습니다',
  },
} as const

/** boardLabels const 추론 타입 */
export type BoardLabels = typeof boardLabels

/**
 * agile-planning 의 backend errorCode 를 보드 관리 조작의 사용자 문구로 바꾼다.
 *
 * ### 왜 화면이 아니라 여기인가
 * 화면마다 인라인 매핑을 만들면 키가 갈려 raw 코드가 그대로 노출되는 「가짜 그린」이 난다
 * (PR #106). 형제 `validatorErrorMessage` · `componentErrorMessage` 가 세운 관례를 따른다.
 *
 * 코드 값의 정본은 backend `BoardController` 의 예외 매핑이다.
 *
 * `fallback` 은 조작별로 기본 문구를 갈아 끼우는 자리다 — 「지우지 못했습니다」와
 * 「이름을 바꾸지 못했습니다」는 같은 코드에서도 서로 다른 다음 행동을 뜻한다.
 *
 * @param errorCode 응답 body 에서 꺼낸 errorCode. 못 읽었으면 `null`.
 * @param fallback 모르는 코드일 때 쓸 문구.
 * @returns 사용자에게 보여줄 한국어 문구.
 */
export function boardManageErrorMessage(errorCode: string | null, fallback: string): string {
  switch (errorCode) {
    case 'AGILE_ACCESS_DENIED':
      return '이 보드를 관리할 권한이 없습니다.'
    case 'AGILE_BOARD_NOT_FOUND':
      return '보드를 찾을 수 없습니다. 이미 지워졌을 수 있습니다.'
    case 'AGILE_VALIDATION_FAILED':
      return '보드 이름을 확인해 주세요. 비어 있을 수 없습니다.'
    case 'AGILE_UNAUTHENTICATED':
      return '로그인이 필요합니다. 다시 로그인해 주세요.'
    default:
      return fallback
  }
}
