// triggerBlobDownload 헬퍼 단위 테스트 — createObjectURL/revokeObjectURL mock 검증
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { triggerBlobDownload } from './download'

describe('triggerBlobDownload', () => {
  const MOCK_URL = 'blob:http://localhost/mock-object-url'

  let createObjectURLSpy: ReturnType<typeof vi.fn>
  let revokeObjectURLSpy: ReturnType<typeof vi.fn>
  let originalClick: () => void

  beforeEach(() => {
    // jsdom은 URL.createObjectURL / revokeObjectURL을 구현하지 않아 mock 필요
    createObjectURLSpy = vi.fn().mockReturnValue(MOCK_URL)
    revokeObjectURLSpy = vi.fn()
    vi.stubGlobal('URL', {
      createObjectURL: createObjectURLSpy,
      revokeObjectURL: revokeObjectURLSpy,
    })

    // HTMLAnchorElement.prototype.click을 vi.fn으로 교체 — 실제 클릭 동작 방지
    originalClick = HTMLAnchorElement.prototype.click
    HTMLAnchorElement.prototype.click = vi.fn()
  })

  afterEach(() => {
    // 원래 click 복원
    HTMLAnchorElement.prototype.click = originalClick
    vi.unstubAllGlobals()
  })

  it('createObjectURL을 전달받은 blob으로 호출한다', () => {
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    triggerBlobDownload(blob, 'ATLAS-1.pdf')
    expect(createObjectURLSpy).toHaveBeenCalledOnce()
    expect(createObjectURLSpy).toHaveBeenCalledWith(blob)
  })

  it('생성된 앵커의 href가 mock URL이고 download 속성이 filename과 일치한다', () => {
    const anchors: HTMLAnchorElement[] = []
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') anchors.push(el as HTMLAnchorElement)
      return el
    })

    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    triggerBlobDownload(blob, 'ATLAS-1.pdf')

    expect(anchors).toHaveLength(1)
    const anchor = anchors[0]
    expect(anchor).toBeDefined()
    expect(anchor!.href).toBe(MOCK_URL)
    expect(anchor!.download).toBe('ATLAS-1.pdf')

    vi.restoreAllMocks()
  })

  it('앵커의 click을 호출한다', () => {
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    triggerBlobDownload(blob, 'ATLAS-1.pdf')
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce()
  })

  it('revokeObjectURL을 mock URL로 호출해 메모리 누수를 방지한다', () => {
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    triggerBlobDownload(blob, 'ATLAS-1.pdf')
    expect(revokeObjectURLSpy).toHaveBeenCalledOnce()
    expect(revokeObjectURLSpy).toHaveBeenCalledWith(MOCK_URL)
  })
})
