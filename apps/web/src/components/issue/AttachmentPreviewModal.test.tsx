// AttachmentPreviewModal 단위 테스트 — 렌더/revoke/prop 전환/cleanup 검증
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AttachmentResponse } from '@/api/attachments'
import { AttachmentPreviewModal } from './AttachmentPreviewModal'
import { attachmentLabels } from '@/i18n/attachment-labels'

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

/**
 * 첨부 1건짜리 갤러리로 감싸 렌더한다.
 *
 * 갤러리 도입(D) 전의 테스트들은 「이 첨부 하나」를 재던 것이므로 목록 1건이 같은 의미다.
 * 좌우 이동 자체는 아래 TC-7 이 여러 건으로 따로 잰다.
 */
function renderModal({
  attachment = makeAttachment(),
  open = true,
  onOpenChange = vi.fn(),
}: RenderProps = {}) {
  return render(
    <AttachmentPreviewModal
      issueKey="ATLAS-1"
      attachments={[attachment]}
      startIndex={0}
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
        attachments={[attachment]}
        startIndex={0}
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
        attachments={[attachment]}
        startIndex={0}
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
        attachments={[attachment1]}
        startIndex={0}
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
        attachments={[attachment2]}
        startIndex={0}
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

// ─────────────────────────────────────────────────────────────────────────────
// TC-7: 갤러리 좌우 이동 (J6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 갤러리 이동 판별식.
 *
 * ## 경계 처리가 왜 없나
 *
 * 호출부(`AttachmentSection`)가 **미리보기 가능한 첨부만** 걸러 넘긴다. 그래서 「미리보기
 * 안 되는 항목을 건너뛸까」라는 분기가 아예 없다 — 건너뛸 것이 목록에 없다. 이 계약이
 * 깨지면 이동으로 닿을 수 없는 자리가 생기고 위치 표시(「2 / 5」)가 거짓말을 한다.
 * 그 계약은 `AttachmentSection.test.tsx` 의 대역이 `attachments`·`startIndex` 를 실제로 써서
 * 지킨다.
 */
describe('TC-7: 갤러리 좌우 이동', () => {
  const first = makeAttachment({
    id: '11111111-1111-4111-8111-111111111111',
    filename: 'first.png',
  })
  const second = makeAttachment({
    id: '22222222-2222-4222-8222-222222222222',
    filename: 'second.png',
  })
  const third = makeAttachment({
    id: '33333333-3333-4333-8333-333333333333',
    filename: 'third.png',
  })

  function renderGallery(startIndex = 0) {
    return render(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachments={[first, second, third]}
        startIndex={startIndex}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )
  }

  it('startIndex 가 가리키는 첨부부터 연다 — 누른 썸네일이 뜬다', async () => {
    renderGallery(1)
    expect(await screen.findByRole('img', { name: 'second.png' })).toBeInTheDocument()
  })

  it('현재 위치를 보여준다 — 세는 대상은 미리보기 가능한 첨부다', async () => {
    renderGallery(1)
    await screen.findByRole('img', { name: 'second.png' })

    expect(screen.getByTestId('attachment-preview-position')).toHaveTextContent(
      attachmentLabels.previewPosition(2, 3),
    )
  })

  it('다음 버튼이 다음 첨부로 넘긴다', async () => {
    const user = userEvent.setup()
    renderGallery(0)
    await screen.findByRole('img', { name: 'first.png' })

    await user.click(screen.getByRole('button', { name: attachmentLabels.previewNext }))

    expect(await screen.findByRole('img', { name: 'second.png' })).toBeInTheDocument()
  })

  it('이전 버튼이 앞 첨부로 돌아간다', async () => {
    const user = userEvent.setup()
    renderGallery(2)
    await screen.findByRole('img', { name: 'third.png' })

    await user.click(screen.getByRole('button', { name: attachmentLabels.previewPrevious }))

    expect(await screen.findByRole('img', { name: 'second.png' })).toBeInTheDocument()
  })

  it('→ 키가 다음으로 넘긴다', async () => {
    const user = userEvent.setup()
    renderGallery(0)
    await screen.findByRole('img', { name: 'first.png' })

    await user.keyboard('{ArrowRight}')

    expect(await screen.findByRole('img', { name: 'second.png' })).toBeInTheDocument()
  })

  it('← 키가 앞으로 돌아간다', async () => {
    const user = userEvent.setup()
    renderGallery(2)
    await screen.findByRole('img', { name: 'third.png' })

    await user.keyboard('{ArrowLeft}')

    expect(await screen.findByRole('img', { name: 'second.png' })).toBeInTheDocument()
  })

  it('첫 장에서는 이전이 잠기고 다음은 열려 있다', async () => {
    renderGallery(0)
    await screen.findByRole('img', { name: 'first.png' })

    expect(screen.getByRole('button', { name: attachmentLabels.previewPrevious })).toBeDisabled()
    expect(screen.getByRole('button', { name: attachmentLabels.previewNext })).toBeEnabled()
  })

  it('마지막 장에서는 다음이 잠기고 이전은 열려 있다', async () => {
    renderGallery(2)
    await screen.findByRole('img', { name: 'third.png' })

    expect(screen.getByRole('button', { name: attachmentLabels.previewNext })).toBeDisabled()
    expect(screen.getByRole('button', { name: attachmentLabels.previewPrevious })).toBeEnabled()
  })

  it('첫 장에서 ← 를 눌러도 넘어가지 않는다 — 경계를 감싸지 않는다', async () => {
    const user = userEvent.setup()
    renderGallery(0)
    await screen.findByRole('img', { name: 'first.png' })

    await user.keyboard('{ArrowLeft}')

    expect(screen.getByRole('img', { name: 'first.png' })).toBeInTheDocument()
  })

  it('첨부가 하나뿐이면 이동 UI 를 그리지 않는다 — 늘 비활성인 버튼을 남기지 않는다', async () => {
    render(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachments={[first]}
        startIndex={0}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )
    await screen.findByRole('img', { name: 'first.png' })

    expect(
      screen.queryByRole('button', { name: attachmentLabels.previewNext }),
    ).not.toBeInTheDocument()
    expect(screen.queryByTestId('attachment-preview-position')).not.toBeInTheDocument()
  })

  it('이동하면 이전 blob 을 해제하고 새로 받는다 — 넘겨 볼수록 blob 이 쌓이지 않는다', async () => {
    const user = userEvent.setup()
    renderGallery(0)
    await screen.findByRole('img', { name: 'first.png' })
    expect(createSpy).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: attachmentLabels.previewNext }))
    await screen.findByRole('img', { name: 'second.png' })

    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
    expect(createSpy).toHaveBeenCalledTimes(2)
  })

  it('닫았다 다시 열면 startIndex 로 돌아간다 — 지난번 넘겨 본 자리에서 열리지 않는다', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachments={[first, second, third]}
        startIndex={0}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )
    await screen.findByRole('img', { name: 'first.png' })
    await user.click(screen.getByRole('button', { name: attachmentLabels.previewNext }))
    await screen.findByRole('img', { name: 'second.png' })

    const closed = (
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachments={[first, second, third]}
        startIndex={0}
        open={false}
        onOpenChange={vi.fn()}
      />
    )
    rerender(closed)
    rerender(
      <AttachmentPreviewModal
        issueKey="ATLAS-1"
        attachments={[first, second, third]}
        startIndex={0}
        open={true}
        onOpenChange={vi.fn()}
      />,
    )

    expect(await screen.findByRole('img', { name: 'first.png' })).toBeInTheDocument()
  })
})
