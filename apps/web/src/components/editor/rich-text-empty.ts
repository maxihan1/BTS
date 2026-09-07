// 리치 에디터 HTML 이 「빈 본문」인지 판정한다 — 서버 MarkdownRenderer.isBlankHtml 과 같은 식
/**
 * 텍스트를 담지 않아도 **화면에 무언가를 그리는** 태그.
 *
 * 서버 `MarkdownRenderer.VISUAL_VOID_TAGS` 와 **같은 목록**이어야 한다. 갈리면 프론트가
 * 키를 보냈는데 서버가 빈 본문으로 읽거나 그 반대가 된다.
 */
const VISUAL_VOID_TAGS = ['<img', '<hr', '<table', '<input'] as const

/** HTML 태그 한 개. 텍스트만 남기려고 쓴다 — 정화 목적이 아니다(그 일은 서버가 한다). */
const HTML_TAG = /<[^>]*>/g

/** `&nbsp;` 엔티티 표기. */
const NBSP_ENTITY = /&nbsp;/g

/**
 * `&nbsp;` 의 문자 표기(U+00A0).
 *
 * ★**이스케이프로 쓴다.** 리터럴을 그대로 두면 `no-irregular-whitespace` lint 에 걸리고,
 * 무엇보다 소스에서 일반 공백과 눈으로 구별되지 않아 나중에 누가 지워도 알 수 없다.
 */
const NBSP_CHAR = /\u00a0/g

/**
 * HTML 본문이 비어 있나.
 *
 * ## 왜 필요한가
 *
 * **TipTap 은 빈 문서를 빈 문자열이 아니라 `<p></p>` 로 직렬화한다.** 그래서
 * `html.trim() !== ''` 같은 판정은 「빈 본문」을 영영 만나지 못한다. 생성 폼에서 그대로 두면
 * 본문을 비운 채 제출해도 서버가 「내용 있음」으로 읽어 **프로젝트 템플릿(FR-TM-01)이 죽는다.**
 *
 * ## `<img>` 등의 예외
 *
 * 이미지 한 장짜리 본문은 텍스트가 0자이지만 **비어 있지 않다.** 이 예외가 없으면
 * 「이미지만 넣고 저장했더니 템플릿이 덮어썼다」가 된다. 구분선·표·체크박스도 같다.
 *
 * ## 서버가 정본이다
 *
 * 이 함수는 **보조**다. 서버 `MarkdownRenderer.isBlankHtml` 이 같은 판정을 다시 하며,
 * 그것이 모바일·API·자동화까지 지키는 진짜 방어선이다. 두 판정은 같은 식을 쓴다.
 *
 * @param html 검사할 HTML. null·undefined 면 빈 본문
 * @returns 빈 본문이면 true
 */
export function isBlankHtml(html: string | null | undefined): boolean {
  if (html === null || html === undefined || html.trim() === '') return true
  const lower = html.toLowerCase()
  if (VISUAL_VOID_TAGS.some((tag) => lower.includes(tag))) return false
  return html.replace(HTML_TAG, '').replace(NBSP_ENTITY, ' ').replace(NBSP_CHAR, ' ').trim() === ''
}
