// 첨부 썸네일 타일 판별식 — 임계·타입 분기와 blob 생명주기 (Jira 패리티 J6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { AttachmentResponse } from '@/api/attachments'
import { AttachmentThumbnail } from '../AttachmentThumbnail'
import { THUMBNAIL_MAX_BYTES } from '@/api/use-attachment-blob'
import { attachmentLabels } from '@/i18n/attachment-labels'

// downloadAttachment mock — 실제 네트워크 없이 blob 경로만 잰다.
vi.mock('@/api/attachments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/attachments')>()),
  downloadAttachment: vi.fn(),
}))
import { downloadAttachment } from '@/api/attachments'

/**
 * `AttachmentThumbnail` 판별식.
 *
 * ## 무엇을 재나
 *
 * (a) 언제 **실제 그림**을 그리는가 — 이미지이면서 임계 이하일 때만.
 *     서버에 리사이즈가 없어 원본을 통째로 받으므로(편차 X5), 이 판정이 무너지면
 *     100MB 이미지가 목록을 여는 것만으로 내려받힌다.
 * (b) blob URL 생명주기 — 언마운트 시 `revokeObjectURL`.
 *     빠뜨리면 이슈를 넘겨 볼 때마다 blob 이 쌓인다.
 * (c) 미리보기 불가 타입은 클릭 어포던스를 주지 않는다 — 열 수 없는 것에 포인터를 주면
 *     거짓 신호다.
 */

const BASE: AttachmentResponse = {
  id: '3f8a1c2e-5b6d-4e7f-9a0b-1c2d3e4f5a6b',
  filename: '화면.png',
  contentType: 'image/png',
  sizeBytes: 1024,
  uploadedBy: 'a0000000-0000-4000-a000-000000000001',
  createdAt: '2026-09-04T00:00:00Z',
}

function make(overrides: Partial<AttachmentResponse> = {}): AttachmentResponse {
  return { ...BASE, ...overrides }
}

let createSpy: ReturnType<typeof vi.spyOn>
let revokeSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))
  createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-url')
  revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})

afterEach(() => {
  vi.clearAllMocks()
  createSpy.mockRestore()
  revokeSpy.mockRestore()
})

describe('AttachmentThumbnail — 언제 그림을 그리나 (J6)', () => {
  it('임계 이하 이미지는 blob 을 받아 <img> 로 그린다', async () => {
    render(<AttachmentThumbnail issueKey="ATLAS-1" attachment={make()} onOpen={vi.fn()} />)

    const img = await screen.findByRole('img', { name: '화면.png' })
    expect(img).toHaveAttribute('src', 'blob:fake-url')
    expect(downloadAttachment).toHaveBeenCalledWith('ATLAS-1', BASE.id)
  })

  it('임계 초과 이미지는 **내려받지 않는다** — 목록을 여는 것만으로 100MB 를 받지 않는다', () => {
    render(
      <AttachmentThumbnail
        issueKey="ATLAS-1"
        attachment={make({ sizeBytes: THUMBNAIL_MAX_BYTES + 1 })}
        onOpen={vi.fn()}
      />,
    )

    expect(downloadAttachment).not.toHaveBeenCalled()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('임계 경계값(정확히 THUMBNAIL_MAX_BYTES)은 그린다 — 경계 단언', async () => {
    render(
      <AttachmentThumbnail
        issueKey="ATLAS-1"
        attachment={make({ sizeBytes: THUMBNAIL_MAX_BYTES })}
        onOpen={vi.fn()}
      />,
    )

    await waitFor(() => { expect(downloadAttachment).toHaveBeenCalled() })
  })

  it('PDF 는 미리보기 가능하지만 썸네일은 그리지 않는다', () => {
    render(
      <AttachmentThumbnail
        issueKey="ATLAS-1"
        attachment={make({ contentType: 'application/pdf', filename: '문서.pdf' })}
        onOpen={vi.fn()}
      />,
    )

    expect(downloadAttachment).not.toHaveBeenCalled()
    // 미리보기 가능 타입이라 버튼은 있다 — 클릭하면 모달이 뜬다.
    expect(
      screen.getByRole('button', { name: attachmentLabels.thumbnailButton('문서.pdf') }),
    ).toBeInTheDocument()
  })

  it('미리보기 불가 타입은 클릭 어포던스를 주지 않는다', () => {
    render(
      <AttachmentThumbnail
        issueKey="ATLAS-1"
        attachment={make({ contentType: 'application/zip', filename: '자료.zip' })}
        onOpen={vi.fn()}
      />,
    )

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(downloadAttachment).not.toHaveBeenCalled()
  })
})

describe('AttachmentThumbnail — blob 생명주기', () => {
  it('언마운트하면 objectURL 을 해제한다', async () => {
    const { unmount } = render(
      <AttachmentThumbnail issueKey="ATLAS-1" attachment={make()} onOpen={vi.fn()} />,
    )

    await screen.findByRole('img', { name: '화면.png' })
    unmount()

    await waitFor(() => { expect(revokeSpy).toHaveBeenCalledWith('blob:fake-url') })
  })

  it('다운로드가 실패해도 아이콘으로 떨어질 뿐 터지지 않는다', async () => {
    vi.mocked(downloadAttachment).mockRejectedValue(new Error('403'))

    render(<AttachmentThumbnail issueKey="ATLAS-1" attachment={make()} onOpen={vi.fn()} />)

    // 버튼은 남고(미리보기는 여전히 시도 가능) 이미지만 없다.
    expect(
      await screen.findByRole('button', { name: attachmentLabels.thumbnailButton('화면.png') }),
    ).toBeInTheDocument()
    await waitFor(() => { expect(screen.queryByRole('img')).not.toBeInTheDocument() })
  })
})
