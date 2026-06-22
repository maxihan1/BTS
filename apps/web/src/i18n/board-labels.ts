// 칸반 보드 WIP/스윔레인 i18n 라벨

/**
 * 칸반 보드 컬럼 헤더가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — wip / column
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const boardLabels = {
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
} as const

/** boardLabels const 추론 타입 */
export type BoardLabels = typeof boardLabels
