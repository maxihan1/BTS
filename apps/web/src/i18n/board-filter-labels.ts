// 보드 필터 바 UI 한국어 라벨 단일 출처 — FR-BD-02 Task-5

/**
 * 보드 필터 바가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — filter / chip / count
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const boardFilterLabels = {
  /** 필터 컨트롤 라벨 */
  filter: {
    /** 담당자 typeahead 필드 라벨 */
    assigneeLabel: '담당자',
    /** 담당자 입력 placeholder */
    assigneePlaceholder: '담당자 검색...',
    /** 미배정 체크박스 라벨 */
    unassigned: '미배정',
    /** 라벨 필터 필드 라벨 */
    labelLabel: '라벨',
    /** 라벨 입력 placeholder */
    labelPlaceholder: '라벨 검색...',
    /** 컴포넌트 필터 섹션 라벨 */
    componentLabel: '컴포넌트',
    /** 초기화 버튼 텍스트 */
    reset: '초기화',
  },

  /** 칩 제거 버튼 */
  chip: {
    /**
     * 칩 제거 버튼 aria-label 생성 함수.
     * @param name 칩 표시 이름 (담당자 이름, 라벨명 등)
     * @returns "{name} 제거"
     */
    removeAriaLabel: (name: string): string => `${name} 제거`,
  },

  /** 활성 필터 카운트 */
  count: {
    /**
     * 활성 필터 개수 표시 문자열 생성.
     * @param n 활성 필터 개수
     * @returns "N개 적용 중"
     */
    applied: (n: number): string => `${n}개 적용 중`,
  },

  /** 검색 결과 없음 */
  search: {
    /** 담당자 검색 결과 없음 텍스트 */
    noResults: '검색 결과 없음',
    /** 담당자 검색 중 텍스트 */
    loading: '검색 중...',
  },
} as const

/** boardFilterLabels const 추론 타입 */
export type BoardFilterLabels = typeof boardFilterLabels
