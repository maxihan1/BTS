// AttachmentPreviewModal 단위 테스트 — 렌더/revoke/prop 전환/cleanup 검증
import { render, screen } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AttachmentResponse } from '@/api/attachments'
import { AttachmentPreviewModal } from './AttachmentPreviewModal'

// ─────────────────────────────────────────────────────────────────────────────
// downloadAttachment mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/attachments', () => ({
  downloadAttachment: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// URL.createObjectURL / revokeObjectURL mock
// URL 전체 교체 시 URL.parse 등이 소실되므로 두 메서드만 교체한다 (C3)
// ─────────────────────────────────────────────────────────────────────────────

let createSpy: ReturnType<typeof vi.fn>
let revokeSpy: ReturnType<typeof vi.fn>

beforeEach(async () => {
  createSpy = vi.fn().mockReturnValue('blob:mock')
  revokeSpy = vi.fn()
  vi.stubGlobal('URL', { ...URL, createObjectURL: createSpy, revokeObjectURL: revokeSpy })

  // 각 테스트마다 fresh mock으로 초기화
  const { downloadAttachment } = await import('@/api/attachments')
  vi.mocked(downloadAttachment).mockReset()
  vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeAttachment(overrides?: Partial<AttachmentResponse>): AttachmentResponse {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    filename: 'test-image.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    uploadedBy: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

interface RenderProps {
  attachment?: AttachmentResponse
  open?: boolean
  onOpenChange?: (o: boolean) => void
}

function renderModal({
  attachment = makeAttachment(),
  open = true,
  onOpenChange = vi.fn(),
}: RenderProps = {}) {
  return render(
    <AttachmentPreviewModal
      issueKey="ATLAS-1"
      attachment={attachment}
      open={open}
      onOpenChange={onOpenChange}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TC-0: ui/dialog 래퍼 흡수 — 우상단 X 닫기 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-0: ui/dialog 래퍼 흡수', () => {
  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    renderModal()

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: image 첨부 → <img> 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: image 첨부', () => {
  it('open=true 시 비동기 로드 후 <img> 렌더 (alt=filename, src=blob:mock)', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    renderModal({ attachment: makeAttachment({ contentType: 'image/png', filename: 'test-image.png' }) })

    const img = await screen.findByRole('img', { name: 'test-image.png' })
    expect(img).toBeInTheDocument()
    expect(img).toHaveAttribute('src', 'blob:mock')
    expect(createSpy).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: pdf 첨부 → <iframe> 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: pdf 첨부', () => {
  it('open=true 시 비동기 로드 후 <iframe> 렌더 (title=filename, blob src). sandbox 미부여(G3)', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }))

    renderModal({
      attachment: makeAttachment({
        id: '22222222-2222-4222-8222-222222222222',
        contentType: 'application/pdf',
        filename: 'document.pdf',
      }),
    })

    const iframe = await screen.findByTitle('document.pdf')
    expect(iframe.tagName).toBe('IFRAME')
    expect(iframe).toHaveAttribute('src', 'blob:mock')
    // sandbox=""는 브라우저 PDF 뷰어를 막으므로 부여하지 않는다 (G3). 화이트리스트가 1차 방어선.
    expect(iframe).not.toHaveAttribute('sandbox')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-3: video 첨부 → <video controls> 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-3: video 첨부', () => {
  it('open=true 시 비동기 로드 후 <video controls> 렌더', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['video'], { type: 'video/mp4' }))

    renderModal({
      attachment: makeAttachment({
        id: '33333333-3333-4333-8333-333333333333',
        contentType: 'video/mp4',
        filename: 'clip.mp4',
      }),
    })

    // video 요소는 role이 없으므로 querySelector로 확인
    const video = await screen.findByTestId('preview-video')
    expect(video.tagName).toBe('VIDEO')
    expect(video).toHaveAttribute('controls')
    expect(video).toHaveAttribute('src', 'blob:mock')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-4: 로딩 상태 및 에러 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-4: 로딩/에러 상태', () => {
  it('다운로드 진행 중 로딩 라벨이 표시된다', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    // 영원히 pending인 Promise로 로딩 상태 유지
    vi.mocked(downloadAttachment).mockReturnValue(new Promise(() => undefined))

    renderModal()

    expect(await screen.findByText('미리보기 불러오는 중')).toBeInTheDocument()
  })

  it('downloadAttachment reject 시 에러 라벨이 표시된다', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    vi.mocked(downloadAttachment).mockRejectedValue(new Error('network error'))

    renderModal()

    expect(
      await screen.findByText('미리보기를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-5: 닫기 → revokeObjectURL 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-5: 닫기 시 revoke', () => {
  it('open을 false로 전환하면 revokeObjectURL이 호출된다', async () => {
    // 닫기 버튼은 Radix DialogPrimitive.Close → onOpenChange(false) 호출
    // 테스트에서 실제 open prop 변경이 이뤄져야 useEffect cleanup이 실행된다
    const attachment = makeAttachment()
    const { rerender } = render(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachment={attachment}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )

    // blob URL 생성 완료 대기
    await screen.findByRole('img', { name: 'test-image.png' })
    expect(createSpy).toHaveBeenCalledOnce()
    expect(revokeSpy).not.toHaveBeenCalled()

    // open=false로 rerender → useEffect cleanup 실행
    rerender(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachment={attachment}
        open={false}
        onOpenChange={vi.fn()}
      />,
    )

    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-6: C1 prop 전환 — open 유지 채 attachment id 교체
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-6: C1 prop 전환', () => {
  it('open 유지한 채 attachment prop을 다른 id로 rerender 시 이전 revoke 후 새 create 호출', async () => {
    const { downloadAttachment } = await import('@/api/attachments')
    vi.mocked(downloadAttachment).mockResolvedValue(new Blob(['x'], { type: 'image/png' }))

    const attachment1 = makeAttachment({
      id: '11111111-1111-4111-8111-111111111111',
      filename: 'first.png',
    })
    const attachment2 = makeAttachment({
      id: '22222222-2222-4222-8222-222222222222',
      filename: 'second.png',
    })

    const { rerender } = render(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachment={attachment1}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )

    // 첫 번째 이미지 로드 완료 대기
    await screen.findByRole('img', { name: 'first.png' })
    expect(createSpy).toHaveBeenCalledTimes(1)

    // attachment prop을 다른 id로 교체 (open 유지)
    rerender(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachment={attachment2}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )

    // 두 번째 이미지 로드 완료 대기
    await screen.findByRole('img', { name: 'second.png' })

    // 이전 blob URL이 revoke된 후 새 blob URL이 생성되어야 한다
    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
    expect(createSpy).toHaveBeenCalledTimes(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-7: 언마운트 시 cleanup
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-7: 언마운트 cleanup', () => {
  it('언마운트 시 revokeObjectURL이 호출된다', async () => {
    const { unmount } = renderModal()

    // blob URL 생성 완료 대기
    await screen.findByRole('img', { name: 'test-image.png' })
    expect(createSpy).toHaveBeenCalledOnce()

    unmount()

    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
  })
})
