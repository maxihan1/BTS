// 본문 첨부 이미지 치환 판별식 — attachment:uuid → blob · a[href] 미치환 (Jira 패리티 J7 · FR-AC-02)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AttachmentHtml } from '../AttachmentHtml'

vi.mock('@/api/attachments', () => ({ downloadAttachment: vi.fn() }))
import { downloadAttachment } from '@/api/attachments'

/**
 * `AttachmentHtml` 판별식.
 *
 * ## 무엇을 재나
 *
 * 서버가 정화한 HTML 안의 `<img src="attachment:<uuid>">` 를 마운트 후 DOM 에서 찾아
 * blob URL 로 바꿔 끼우는 층이다. #449 가 넣었지만 증인이 없었다.
 *
 * ## a[href] 를 함께 재는 이유 (#446 결함의 짝)
 *
 * #446 에서 `allowUrlProtocols("attachment")` 가 **정책 전역**이라 `img` 용으로 연 스킴이
 * `a[href]` 에도 열렸다. UUID 술어는 `img[src]` 에만 있었다. 서버 쪽은 그때 막았지만,
 * 프론트 치환기가 `a` 까지 손대기 시작하면 같은 구멍이 이쪽에 다시 생긴다.
 * **`img` 통과와 `a` 미치환을 한 판별식에서 함께 본다** — 한쪽만 보면 나머지가 조용히 썩는다.
 */

const ISSUE_KEY = 'BTS-1'
const UUID = '11111111-2222-3333-4444-555555555555'
const BLOB_URL = 'blob:http://localhost/fake-1'

let createObjectURL: ReturnType<typeof vi.fn>
let revokeObjectURL: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.clearAllMocks()
  createObjectURL = vi.fn(() => BLOB_URL)
  revokeObjectURL = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
  vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['img']))
})

afterEach(() => { vi.unstubAllGlobals() })

function renderHtml(html: string | null) {
  return render(<AttachmentHtml html={html} issueKey={ISSUE_KEY} testId="body" />)
}

/** 컨테이너 안의 첫 `<img>` 를 잡는다 — alt 가 없을 수 있어 role 로는 못 잡는다. */
function firstImg(): HTMLImageElement {
  const img = screen.getByTestId('body').querySelector('img')
  if (img === null) throw new Error('img 가 렌더되지 않았다')
  return img
}

describe('AttachmentHtml — attachment: 참조 치환 (J7)', () => {
  it('img[src=attachment:uuid] 를 내려받아 blob URL 로 바꿔 끼운다', async () => {
    renderHtml(`<p><img src="attachment:${UUID}" alt="스크린샷"></p>`)

    await waitFor(() => { expect(firstImg().src).toBe(BLOB_URL) })
    expect(downloadAttachment).toHaveBeenCalledWith(ISSUE_KEY, UUID)
  })

  it('a[href=attachment:uuid] 는 건드리지 않는다 — #446 결함(정책 전역 스킴)의 짝', async () => {
    renderHtml(
      `<p><a href="attachment:${UUID}">파일</a><img src="attachment:${UUID}"></p>`,
    )

    // img 는 치환된다 — 이 단언이 있어야 아래 a 단언이 「아무것도 안 돌았다」로 공허해지지 않는다.
    await waitFor(() => { expect(firstImg().src).toBe(BLOB_URL) })

    const link = screen.getByTestId('body').querySelector('a')
    expect(link?.getAttribute('href')).toBe(`attachment:${UUID}`)
    // img 1건만 내려받았다 — a 를 위해 한 번 더 부르지 않았다.
    expect(downloadAttachment).toHaveBeenCalledOnce()
  })

  it('uuid 형태가 아닌 attachment: 값은 내려받지 않는다', async () => {
    renderHtml('<p><img src="attachment:not-a-uuid"></p>')

    await waitFor(() => { expect(firstImg().getAttribute('src')).toBe('attachment:not-a-uuid') })
    expect(downloadAttachment).not.toHaveBeenCalled()
  })

  it('일반 URL 이미지는 그대로 둔다', async () => {
    renderHtml('<p><img src="https://cdn.example.test/a.png"></p>')

    expect(downloadAttachment).not.toHaveBeenCalled()
    expect(firstImg().getAttribute('src')).toBe('https://cdn.example.test/a.png')
  })

  it('본문에 평문으로 쓴 attachment:uuid 는 치환하지 않는다 — 문자열 치환이 아니라 DOM 순회다', async () => {
    renderHtml(`<p>이 참조는 평문이다 attachment:${UUID}</p>`)

    expect(downloadAttachment).not.toHaveBeenCalled()
    expect(screen.getByTestId('body')).toHaveTextContent(`attachment:${UUID}`)
  })

  it('이미지가 여럿이면 각각 내려받는다', async () => {
    const uuid2 = '99999999-8888-7777-6666-555555555555'
    renderHtml(`<p><img src="attachment:${UUID}"><img src="attachment:${uuid2}"></p>`)

    await waitFor(() => { expect(downloadAttachment).toHaveBeenCalledTimes(2) })
    expect(downloadAttachment).toHaveBeenCalledWith(ISSUE_KEY, UUID)
    expect(downloadAttachment).toHaveBeenCalledWith(ISSUE_KEY, uuid2)
  })

  it('언마운트하면 만든 objectURL 을 해제한다 — 이슈를 넘겨 볼 때 blob 이 쌓이지 않는다', async () => {
    const { unmount } = renderHtml(`<p><img src="attachment:${UUID}"></p>`)
    await waitFor(() => { expect(createObjectURL).toHaveBeenCalledOnce() })

    unmount()

    expect(revokeObjectURL).toHaveBeenCalledWith(BLOB_URL)
  })

  it('내려받기가 실패하면 참조를 그대로 두고 토스트도 띄우지 않는다', async () => {
    vi.mocked(downloadAttachment).mockRejectedValue(new Error('403'))
    renderHtml(`<p><img src="attachment:${UUID}"></p>`)

    await waitFor(() => { expect(downloadAttachment).toHaveBeenCalledOnce() })
    expect(firstImg().getAttribute('src')).toBe(`attachment:${UUID}`)
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it('html 이 null 이거나 빈 문자열이면 아무것도 그리지 않는다', () => {
    const { rerender } = renderHtml(null)
    expect(screen.queryByTestId('body')).not.toBeInTheDocument()

    rerender(<AttachmentHtml html="" issueKey={ISSUE_KEY} testId="body" />)
    expect(screen.queryByTestId('body')).not.toBeInTheDocument()
  })
})
