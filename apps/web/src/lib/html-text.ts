// 에디터가 만든 HTML 에서 평문을 뽑는다 — 검색·알림·이력이 쓰는 표현
/**
 * HTML 문자열에서 사람이 읽는 평문을 추출한다.
 *
 * ## 왜 필요한가
 *
 * 댓글은 서식(HTML)과 평문을 **둘 다** 저장한다(V039). 서버가 `body`(평문)로 멘션을 찾고,
 * 알림 미리보기를 만들고, 검색을 색인하기 때문이다. 에디터는 HTML 만 갖고 있으므로 보내기
 * 직전에 평문을 만들어야 한다.
 *
 * ## 왜 정규식이 아닌가
 *
 * `replace(/<[^>]*>/g, '')` 는 `<p>a</p><p>b</p>` 를 `ab` 로 붙여 두 문단을 한 낱말로 만든다.
 * 브라우저 파서를 쓰면 블록 경계가 줄바꿈으로 남아 원문에 가까운 평문이 나온다.
 * `DOMParser` 로 만든 문서는 **스크립트를 실행하지 않고 리소스도 불러오지 않는다** —
 * `innerHTML` 로 임시 요소를 만드는 방식과 달리 부작용이 없다.
 *
 * @param html 에디터가 만든 HTML
 * @returns 앞뒤 공백을 정리한 평문. 빈 문서면 빈 문자열
 */
export function htmlToPlainText(html: string): string {
  if (html.trim() === '') return ''

  const doc = new DOMParser().parseFromString(html, 'text/html')

  // 블록 요소 뒤에 줄바꿈을 심는다 — 문단·목록 항목이 한 줄로 뭉치지 않게.
  doc.body.querySelectorAll('p, div, li, h1, h2, h3, h4, h5, h6, br, tr').forEach((el) => {
    el.after(doc.createTextNode('\n'))
  })

  return (doc.body.textContent ?? '')
    // 연속 줄바꿈을 두 개까지로 줄인다 — 문단 구분은 남기고 빈 줄 더미는 없앤다.
    .replace(/\n{3,}/g, '\n\n')
    .split('\n')
    .map((line) => line.trim())
    .join('\n')
    .trim()
}

/**
 * 평문을 HTML 에 넣을 수 있게 escape 한다.
 *
 * V039 이전에 쌓인 댓글은 HTML 컬럼이 비어 있어 평문만 있다. 그것을 편집기에 넣으려면
 * 문단으로 감싸야 하는데, 원문에 `<` 가 있으면 태그로 해석돼 내용이 사라지거나 깨진다.
 *
 * @param text escape 할 평문
 * @returns HTML 안에 안전하게 놓을 수 있는 문자열
 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
