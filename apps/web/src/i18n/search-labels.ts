// AQL 검색 화면의 한국어 라벨 — 하드코딩 한글 placeholder 금지(R3 래칫)

/**
 * 검색 화면(`routes/search.tsx`)이 노출하는 한국어 문구.
 *
 * 지금은 AQL 입력창 placeholder 하나뿐이다 — 그 화면의 나머지 문자열은 아직
 * 라우트 안에 있고, 옮길 때 이 파일에 그룹을 추가한다.
 * 형식은 `project-not-found-labels.ts` 와 같다.
 */
export const searchLabels = {
  /**
   * AQL(Atlas Query Language) 입력창 placeholder.
   *
   * ★큰따옴표는 **실제 `"` 문자**다. 원래 JSX 속성에 `&quot;` 로 적혀 있었는데,
   *   JSX 는 문자열 속성의 HTML 엔티티를 파싱 단계에서 디코드하므로 화면에 나오던 값은
   *   처음부터 `"` 였다(TypeScript 트랜스파일 출력으로 확인). 엔티티를 그대로 옮기면
   *   TS 문자열은 디코드를 안 하므로 화면 문구가 바뀐다.
   */
  aqlPlaceholder:
    'AQL 쿼리를 입력하세요. 예: status = open AND priority IN (1, 2), text ~ "로그인"',
} as const

/** searchLabels const 추론 타입 */
export type SearchLabels = typeof searchLabels
