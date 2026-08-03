// 칸반 보드 WIP/스윔레인 i18n 라벨

/**
 * 칸반 보드 컬럼 헤더가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — wip / column
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const boardLabels = {
  /** 보드 페이지 — 헤더 및 뷰 전환 링크 */
  page: {
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
