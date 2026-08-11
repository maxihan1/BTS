// 전역 권한 부여 다이얼로그의 한국어 라벨 — 하드코딩 한글 placeholder 금지(R3 래칫)

/**
 * 전역 권한 부여/회수 UI(`components/global-permissions/`)가 노출하는 한국어 문구.
 *
 * 지금은 대상 검색 placeholder 하나뿐이다 — 이 화면의 나머지 문자열은 아직
 * 컴포넌트 안에 있고, 옮길 때 이 파일에 그룹을 추가한다.
 * 형식은 `project-not-found-labels.ts` 와 같다.
 */
export const globalPermissionLabels = {
  /** 권한 부여 대상(사용자/그룹) 검색 input placeholder — 최소 입력 길이를 문구로 안내한다 */
  subjectSearchPlaceholder: '이름 또는 아이디로 검색 (2자 이상)',
} as const

/** globalPermissionLabels const 추론 타입 */
export type GlobalPermissionLabels = typeof globalPermissionLabels
