// 프로젝트 리드 관리 UI의 한국어 라벨 단일 출처

/**
 * 프로젝트 리드 관리 UI가 노출하는 한국어 라벨/텍스트.
 *
 * - component-labels.ts 패턴 동일 적용
 * - 백엔드 ProblemDetail detail 필드를 직접 노출하지 않음 (단일 출처)
 *
 * 그룹 — page / form
 */
export const projectLeadLabels = {
  /** 페이지/섹션 영역 */
  page: {
    /** 섹션 heading */
    heading: '프로젝트 리드',
    /** 섹션 설명 문구 */
    description: '이 프로젝트의 리드 담당자를 설정합니다.',
  },

  /** 폼 필드 라벨 / placeholder */
  form: {
    /** 리드 필드 label */
    leadLabel: '프로젝트 리드',
    /** 리드 미지정 표시 텍스트 */
    leadUnassigned: '미지정',
    /** 검색 input placeholder / aria-label */
    searchLabel: '리드 검색',
    /** 현재 리드 섹션 title */
    currentLeadTitle: '현재 리드',
  },
} as const

/** 라벨 const 추론 타입 */
export type ProjectLeadLabels = typeof projectLeadLabels
