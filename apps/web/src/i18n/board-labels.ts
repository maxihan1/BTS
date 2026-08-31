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
