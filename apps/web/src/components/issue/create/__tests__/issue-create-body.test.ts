// 생성 폼이 리치 에디터 HTML 을 보내고, 빈 문서는 키 자체를 빼는지 재는 판별식 (FR-TM-01)
import { describe, it, expect } from 'vitest'
import { buildCreateIssuePayload } from '../issue-create-schema'
import { isBlankHtml } from '@/components/editor/rich-text-empty'

/**
 * 생성 페이로드 본문 판별식.
 *
 * ## 왜 있나
 *
 * 생성 폼이 plain textarea 에서 리치 에디터로 바뀌면서 본문이 마크다운 문자열에서 HTML 이 됐다.
 * 그런데 **TipTap 은 빈 문서를 빈 문자열이 아니라 `<p></p>` 로 직렬화한다.** 종전 판정
 * (`description.trim() !== ''`)을 그대로 두면 빈 본문이 영영 「내용 있음」으로 읽혀,
 * 서버가 프로젝트 템플릿으로 채우는 FR-TM-01 이 **조용히 죽는다.**
 *
 * 서버도 같은 판정을 하지만(`MarkdownRenderer.isBlankHtml`) 프론트가 키를 빼 주는 편이
 * 의도를 분명히 한다 — 서버 판정은 다른 클라이언트를 위한 방어선이다.
 */

const selections = {
  typeId: null,
  assigneeIntent: undefined,
  priority: 3,
  labels: [] as string[],
  componentIds: [] as string[],
  securityLevelId: null,
  customFields: {},
} as unknown as Parameters<typeof buildCreateIssuePayload>[1]

function values(descriptionHtml: string) {
  return { projectKey: 'PROJ', summary: '제목', descriptionHtml } as unknown as Parameters<
    typeof buildCreateIssuePayload
  >[0]
}

describe('생성 페이로드 — 본문 HTML', () => {
  it('내용이 있으면 descriptionHtml 을 싣는다', () => {
    const payload = buildCreateIssuePayload(values('<p>본문</p>'), selections)
    expect(payload.descriptionHtml).toBe('<p>본문</p>')
  })

  it('빈 에디터는 descriptionHtml 키 자체를 빼 템플릿 fallback 을 살린다', () => {
    // ★TipTap 의 빈 문서. 이 키가 실리면 서버가 「본문 있음」으로 읽어 템플릿을 안 쓴다.
    const payload = buildCreateIssuePayload(values('<p></p>'), selections)
    expect('descriptionHtml' in payload).toBe(false)
  })

  it('공백만 든 문단도 빈 본문이다', () => {
    expect('descriptionHtml' in buildCreateIssuePayload(values('<p>&nbsp;</p>'), selections)).toBe(false)
    expect('descriptionHtml' in buildCreateIssuePayload(values('<p><br></p>'), selections)).toBe(false)
  })

  it('이미지만 든 본문은 실린다 — 텍스트가 0자여도 내용이다', () => {
    const html = '<p><img src="attachment:11111111-1111-1111-1111-111111111111"></p>'
    expect(buildCreateIssuePayload(values(html), selections).descriptionHtml).toBe(html)
  })

  it('레거시 description 키를 보내지 않는다 — 서버가 동시 전달을 400 으로 막는다', () => {
    const payload = buildCreateIssuePayload(values('<p>본문</p>'), selections)
    expect('description' in payload).toBe(false)
  })
})

describe('빈 문서 판정 — 서버 규칙과 같은 식', () => {
  // 서버 `MarkdownRenderer.isBlankHtml` 과 **같은 판정**이어야 한다. 갈리면 프론트가
  // 키를 보냈는데 서버가 빈 본문으로 읽거나 그 반대가 된다.
  const blank = ['', '   ', '<p></p>', '<p><br></p>', '<p>&nbsp;</p>', '<blockquote></blockquote>']
  const filled = [
    '<p>가</p>',
    '<ul><li>항목</li></ul>',
    '<hr>',
    '<p><img src="attachment:11111111-1111-1111-1111-111111111111"></p>',
  ]

  blank.forEach((html) => {
    it(`빈 본문: ${JSON.stringify(html)}`, () => {
      expect(isBlankHtml(html)).toBe(true)
    })
  })

  filled.forEach((html) => {
    it(`내용 있음: ${JSON.stringify(html)}`, () => {
      expect(isBlankHtml(html)).toBe(false)
    })
  })
})
