// 필터 바(이슈·보드 공용) 한국어 라벨 단일 출처 — FR-UX-06 PR17

/**
 * 이슈 필터 바 / 보드 필터 바가 공통으로 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — filter / chip / count / search
 *
 * `issueFilterLabels`(상태 라벨 추가)와 `boardFilterLabels`(별칭 재export)가
 * 이 단일 출처에서 파생된다.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const filterBarLabels = {
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
    /**
     * 필터 드롭다운 트리거의 접근성 이름.
     *
     * 안쪽 컨트롤(`담당자` 입력 등)과 **다른 이름**이라야 한다 — 같으면 드롭다운을 연 순간
     * 이름이 겹쳐 `getByRole` 이 strict 위반으로 죽는다.
     *
     * @param name 필터 이름 (상태·담당자·라벨·컴포넌트)
     * @returns "{name} 필터"
     */
    dropdownAriaLabel: (name: string): string => `${name} 필터`,
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

/** filterBarLabels const 추론 타입 */
export type FilterBarLabels = typeof filterBarLabels
