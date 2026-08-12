// 프로젝트 키 형식 판정 순수 함수 — 생성 화면과 이동 화면이 공유하는 단일 정본

/**
 * 프로젝트 key 형식 정규식 — backend `PROJECT_KEY_REGEX`(대문자로 시작, 대문자+숫자 2~10자)와
 * 동일. DB CHECK(`projects_key_check`)와도 정합한다(dual 검증, 프론트는 UX 편의).
 *
 * **여기 말고 다른 곳에 같은 정규식을 두지 말 것.** 두 벌이 되는 순간 화면마다 판정이
 * 갈린다 — 그 갈림이 곧 「이동 화면은 소문자를 서버까지 보내고 생성 화면은 안 보낸다」였다.
 *
 * `g` 플래그를 붙이지 않는다. 붙이면 `lastIndex` 가 호출 간에 남아 같은 입력이 번갈아
 * 통과/거절된다(`project-key.test.ts` 의 상태 누수 테스트가 이것을 지킨다).
 */
const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/

/**
 * 프로젝트 키가 형식에 맞는지 판정한다.
 *
 * **존재 여부는 모른다.** 형식이 맞아도 없는 프로젝트일 수 있고, 그 경우는 서버가
 * 403 으로 답한다(`IdentityAccessIssuePermissionResolver` 가 미존재 프로젝트를 권한
 * 거부로 판정하기 때문). 이 함수로 서버 응답을 예측하지 말 것.
 *
 * @param value 앞뒤 공백이 **이미 제거된** 키 문자열. 공백이 남아 있으면 거절된다.
 */
export function isValidProjectKey(value: string): boolean {
  return PROJECT_KEY_PATTERN.test(value)
}
