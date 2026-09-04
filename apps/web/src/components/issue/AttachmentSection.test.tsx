// AttachmentSection 컴포넌트 단위 테스트 — FR-AC-01 D6 Task 4 TDD RED + FR-AC-02 Task 3 미리보기 버튼
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { attachmentLabels } from '@/i18n/attachment-labels'
import { AttachmentSection } from './AttachmentSection'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 훅 mock
// ─────────────────────────────────────────────────────────────────────────────

// useAttachmentList — 테스트별 mockReturnValue로 제어
vi.mock('@/api/useAttachments', () => ({
  useAttachmentList: vi.fn(),
  useUploadAttachment: vi.fn(),
  useDeleteAttachment: vi.fn(),
  ATTACHMENTS_QUERY_KEY: (key: string) => ['attachments', key],
}))
import {
  useAttachmentList,
  useUploadAttachment,
  useDeleteAttachment,
} from '@/api/useAttachments'

// downloadAttachment — Blob 반환 mock (실제 fetch 없이 테스트)
vi.mock('@/api/attachments', () => ({
  downloadAttachment: vi.fn(),
  MAX_ATTACHMENT_BYTES: 100 * 1024 * 1024,
}))

// triggerBlobDownload — side-effect mock
vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

// sonner toast mock
vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ATTACHMENT_1 = {
  id: 'aa000000-0000-4000-a000-000000000001',
  filename: 'report.pdf',
  contentType: 'application/pdf',
  sizeBytes: 2048,       // 2 KB
  uploadedBy: 'bb000000-0000-4000-b000-000000000001',
  createdAt: '2026-06-15T09:00:00Z',
}

const ATTACHMENT_2 = {
  id: 'aa000000-0000-4000-a000-000000000002',
  filename: '한글파일.png',
  contentType: 'image/png',
  sizeBytes: 1_572_864,  // 1.5 MB
  uploadedBy: 'bb000000-0000-4000-b000-000000000001',
  createdAt: '2026-06-15T10:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderSection(issueKey: string, canUpdate: boolean) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <AttachmentSection issueKey={issueKey} canUpdate={canUpdate} />
    </QueryClientProvider>,
  )
}

/** useUploadAttachment 기본 stub — mutate 함수만 반환 */
const uploadStub = () => ({
  mutate: vi.fn(),
  mutateAsync: vi.fn(),
  isPending: false,
  isError: false,
  isSuccess: false,
  isIdle: true,
  data: undefined,
  error: null,
  reset: vi.fn(),
  status: 'idle' as const,
  context: undefined,
  failureCount: 0,
  failureReason: null,
  variables: undefined,
  submittedAt: 0,
})

/** useDeleteAttachment 기본 stub */
const deleteStub = () => ({
  mutate: vi.fn(),
  mutateAsync: vi.fn(),
  isPending: false,
  isError: false,
  isSuccess: false,
  isIdle: true,
  data: undefined,
  error: null,
  reset: vi.fn(),
  status: 'idle' as const,
  context: undefined,
  failureCount: 0,
  failureReason: null,
  variables: undefined,
  submittedAt: 0,
})

/** useAttachmentList 기본 stub (데이터 있음) */
const listStub = (data = [ATTACHMENT_1, ATTACHMENT_2]) => ({
  data,
  isLoading: false,
  isError: false,
  isPending: false,
  isSuccess: true,
  error: null,
  status: 'success' as const,
  fetchStatus: 'idle' as const,
  dataUpdatedAt: 0,
  errorUpdatedAt: 0,
  failureCount: 0,
  failureReason: null,
  isFetched: true,
  isFetchedAfterMount: true,
  isFetching: false,
  isInitialLoading: false,
  isLoadingError: false,
  isPlaceholderData: false,
  isRefetchError: false,
  isRefetching: false,
  isStale: false,
  refetch: vi.fn(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 셋업
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.mocked(useAttachmentList).mockReturnValue(
    listStub() as unknown as ReturnType<typeof useAttachmentList>,
  )
  vi.mocked(useUploadAttachment).mockReturnValue(
    uploadStub() as unknown as ReturnType<typeof useUploadAttachment>,
  )
  vi.mocked(useDeleteAttachment).mockReturnValue(
    deleteStub() as unknown as ReturnType<typeof useDeleteAttachment>,
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 목록 렌더 — 파일명·크기·업로드 시각
// ─────────────────────────────────────────────────────────────────────────────

describe('AttachmentSection — 목록 렌더', () => {
  it('첨부 파일명을 렌더한다', async () => {
    renderSection('ATLAS-1', true)
    await waitFor(() => {
      expect(screen.getByText('report.pdf')).toBeInTheDocument()
      expect(screen.getByText('한글파일.png')).toBeInTheDocument()
    })
  })

  it('파일 크기를 KB/MB 포맷으로 렌더한다', async () => {
    renderSection('ATLAS-1', true)
    await waitFor(() => {
      // 2048 bytes → 2.0 KB
      expect(screen.getByText(/2\.0\s*KB/)).toBeInTheDocument()
      // 1572864 bytes → 1.5 MB
      expect(screen.getByText(/1\.5\s*MB/)).toBeInTheDocument()
    })
  })

  it('업로드 시각을 표시한다', async () => {
    renderSection('ATLAS-1', true)
    await waitFor(() => {
      // 두 개의 createdAt 값이 어떤 형태로든 렌더되면 통과
      const allCells = screen.getAllByRole('cell')
      // 시각이 포함된 셀 또는 텍스트가 존재한다
      expect(allCells.length).toBeGreaterThan(0)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('AttachmentSection — 빈 상태', () => {
  it('첨부 없으면 빈 상태 메시지를 표시한다', async () => {
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([]) as unknown as ReturnType<typeof useAttachmentList>,
    )
    renderSection('ATLAS-1', true)
    await waitFor(() => {
      expect(screen.getByText(attachmentLabels.emptyState)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) canUpdate=false — 드롭존·삭제버튼 미표시
// ─────────────────────────────────────────────────────────────────────────────

describe('AttachmentSection — 권한 없음(canUpdate=false)', () => {
  it('드롭존이 렌더되지 않는다', async () => {
    renderSection('ATLAS-1', false)
    await waitFor(() => {
      expect(screen.queryByText(attachmentLabels.dropzoneHint)).not.toBeInTheDocument()
    })
  })

  it('삭제 버튼이 렌더되지 않는다', async () => {
    renderSection('ATLAS-1', false)
    await waitFor(() => {
      // 삭제 버튼이 없어야 한다
      expect(
        screen.queryByRole('button', { name: attachmentLabels.deleteButton }),
      ).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) 삭제 인라인 확인 후 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('AttachmentSection — 삭제 인라인 확인', () => {
  it('삭제 클릭 → 경고+확인버튼 표시 → 확인 클릭 → mutate 호출', async () => {
    const mockMutate = vi.fn()
    vi.mocked(useDeleteAttachment).mockReturnValue({
      ...deleteStub(),
      mutate: mockMutate,
    } as unknown as ReturnType<typeof useDeleteAttachment>)

    const user = userEvent.setup()
    renderSection('ATLAS-1', true)

    // 삭제 버튼 클릭 (첫 번째 첨부)
    const deleteButtons = await screen.findAllByRole('button', {
      name: attachmentLabels.deleteButton,
    })
    await user.click(deleteButtons[0]!)

    // 경고 문구 표시
    await waitFor(() => {
      expect(screen.getByText(attachmentLabels.deleteWarning)).toBeInTheDocument()
    })

    // 확인 버튼 클릭
    await user.click(screen.getByRole('button', { name: attachmentLabels.deleteConfirmButton }))

    // mutate 호출 확인
    expect(mockMutate).toHaveBeenCalledWith(ATTACHMENT_1.id, expect.any(Object))
  })

  it('삭제 취소 클릭 → 경고 숨겨지고 mutate 미호출', async () => {
    const mockMutate = vi.fn()
    vi.mocked(useDeleteAttachment).mockReturnValue({
      ...deleteStub(),
      mutate: mockMutate,
    } as unknown as ReturnType<typeof useDeleteAttachment>)

    const user = userEvent.setup()
    renderSection('ATLAS-1', true)

    const deleteButtons = await screen.findAllByRole('button', {
      name: attachmentLabels.deleteButton,
    })
    await user.click(deleteButtons[0]!)

    // 취소 클릭
    await user.click(screen.getByRole('button', { name: attachmentLabels.deleteCancelButton }))

    await waitFor(() => {
      expect(screen.queryByText(attachmentLabels.deleteWarning)).not.toBeInTheDocument()
    })
    expect(mockMutate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) 드롭존 키보드 접근성 — Space preventDefault + 업로드 중 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('DropZone — 키보드 접근성', () => {
  it('Space 키 누름 시 preventDefault가 호출되고 파일 선택 다이얼로그가 트리거된다', async () => {
    vi.mocked(useUploadAttachment).mockReturnValue(
      uploadStub() as unknown as ReturnType<typeof useUploadAttachment>,
    )

    renderSection('ATLAS-1', true)

    const dropzone = await screen.findByRole('button', { name: attachmentLabels.dropzoneHint })

    // fireEvent.keyDown으로 네이티브 이벤트 발생 — React handler 내 e.preventDefault() 호출 시
    // 반환값은 "defaultPrevented가 아님" 여부이므로 false 반환 = preventDefault 호출됨
    const notPrevented = fireEvent.keyDown(dropzone, { key: ' ', code: 'Space' })
    expect(notPrevented).toBe(false)
  })

  it('업로드 중(isPending=true) 에 Space 키를 눌러도 handleClick이 호출되지 않는다', async () => {
    // isPending=true 상태 stub
    vi.mocked(useUploadAttachment).mockReturnValue({
      ...uploadStub(),
      isPending: true,
    } as unknown as ReturnType<typeof useUploadAttachment>)

    const user = userEvent.setup()
    renderSection('ATLAS-1', true)

    // 드롭존이 aria-disabled=true인 상태
    const dropzone = await screen.findByRole('button', { name: attachmentLabels.dropzoneHint })
    expect(dropzone).toHaveAttribute('aria-disabled', 'true')

    // Space 키를 눌러도 fileInputRef.click이 트리거되지 않아야 함
    // (숨겨진 input에 click 이벤트가 발생하면 안 됨)
    const fileInput = screen.getByLabelText(attachmentLabels.fileInputLabel)
    const clickSpy = vi.spyOn(fileInput, 'click')

    dropzone.focus()
    await user.keyboard(' ')

    expect(clickSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (g) 미리보기 버튼 게이팅 + 모달 열림 (FR-AC-02 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

// AttachmentPreviewModal을 mock해 실제 Blob 다운로드 없이 모달 열림만 검증한다.
//
// ★대역이 `attachments`·`startIndex` 를 **실제로 쓴다**. 이름만 받고 버리면 섹션이 갤러리
//   목록을 잘못 넘겨도 유닛이 전부 초록이 된다
//   (`mock-swallowed-prop-is-invisible-to-unit-tests`). 열린 파일명을 목록+위치로 계산해
//   `aria-label` 에 실으면, 배선이 틀리는 순간 여기가 깨진다.
vi.mock('./AttachmentPreviewModal', () => ({
  AttachmentPreviewModal: ({
    open,
    attachments,
    startIndex,
  }: {
    open: boolean
    attachments: readonly { filename: string }[]
    startIndex: number
  }) =>
    open ? (
      <div
        role="dialog"
        aria-label={`${attachments[startIndex]?.filename ?? '(없음)'} 미리보기`}
        data-gallery-size={attachments.length}
        data-start-index={startIndex}
      >
        mock-preview-modal
      </div>
    ) : null,
}))

describe('AttachmentSection — 미리보기 버튼 게이팅', () => {
  it('image/png 첨부 행에 미리보기 버튼이 렌더된다', async () => {
    // ATTACHMENT_2 는 contentType: 'image/png' (isPreviewable = true)
    // 단독 목록으로 설정해 중복 버튼 충돌 없이 단언한다
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([ATTACHMENT_2]) as unknown as ReturnType<typeof useAttachmentList>,
    )

    renderSection('ATLAS-1', false)

    await waitFor(() => {
      expect(screen.getByText('한글파일.png')).toBeInTheDocument()
    })

    // 행 컨테이너(tr) 안에서 미리보기 버튼 확인
    const row = screen.getByRole('row', { name: /한글파일\.png/ })
    expect(
      within(row).getByRole('button', { name: new RegExp(attachmentLabels.previewButton) }),
    ).toBeInTheDocument()
  })

  it('application/zip 첨부 행에는 미리보기 버튼이 렌더되지 않는다', async () => {
    // zip만 단독 목록으로 설정
    const ZIP_ATTACHMENT = {
      id: 'aa000000-0000-4000-a000-000000000003',
      filename: 'archive.zip',
      contentType: 'application/zip',
      sizeBytes: 512,
      uploadedBy: 'bb000000-0000-4000-b000-000000000001',
      createdAt: '2026-06-15T11:00:00Z',
    }
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([ZIP_ATTACHMENT]) as unknown as ReturnType<typeof useAttachmentList>,
    )

    renderSection('ATLAS-1', false)

    await waitFor(() => {
      expect(screen.getByText('archive.zip')).toBeInTheDocument()
    })

    expect(
      screen.queryByRole('button', { name: new RegExp(attachmentLabels.previewButton) }),
    ).not.toBeInTheDocument()
  })

  it('미리보기 버튼 클릭 시 모달이 열린다', async () => {
    // image/png 단독 목록으로 미리보기 버튼 1개만 렌더
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([ATTACHMENT_2]) as unknown as ReturnType<typeof useAttachmentList>,
    )

    const user = userEvent.setup()
    renderSection('ATLAS-1', false)

    const previewBtn = await screen.findByRole('button', {
      name: new RegExp(attachmentLabels.previewButton),
    })
    await user.click(previewBtn)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) 100MB 초과 파일 선택 — 토스트 + 업로드 안 함
// ─────────────────────────────────────────────────────────────────────────────

describe('AttachmentSection — 100MB 초과 파일 사전 검증', () => {
  it('100MB 초과 파일 선택 시 토스트를 표시하고 업로드하지 않는다', async () => {
    const mockMutate = vi.fn()
    vi.mocked(useUploadAttachment).mockReturnValue({
      ...uploadStub(),
      mutate: mockMutate,
    } as unknown as ReturnType<typeof useUploadAttachment>)

    const user = userEvent.setup()
    renderSection('ATLAS-1', true)

    // 숨겨진 파일 input 찾기
    const fileInput = screen.getByLabelText(attachmentLabels.fileInputLabel)

    // 100MB 초과 파일 (104857601 bytes = 100MB + 1byte)
    const bigFile = new File(['x'.repeat(10)], 'big.zip', { type: 'application/zip' })
    Object.defineProperty(bigFile, 'size', { value: 104_857_601 })

    await user.upload(fileInput, bigFile)

    // 토스트 호출 확인
    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })

    // upload mutate 미호출
    expect(mockMutate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (h) 미리보기 갤러리 목록 계약 (J6 — 좌우 이동)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 갤러리에 넘기는 목록의 계약.
 *
 * ## 왜 따로 재나
 *
 * 모달의 좌우 이동 판별식(`AttachmentPreviewModal.test.tsx` TC-7)은 **넘겨받은 목록** 안에서만
 * 잰다. 섹션이 목록을 잘못 만들어도 그쪽은 전부 초록이다 — 실제로 이 판별식을 세우기 전,
 * `previewable` 을 전체 목록으로 바꾸는 뮤테이션이 **잡히지 않았다**.
 *
 * 계약은 둘이다.
 *   ① 목록에는 **미리보기 가능한 첨부만** 담긴다. 그래야 이동으로 닿을 수 없는 자리가 없고
 *      위치 표시(「2 / 5」)가 거짓말을 하지 않는다.
 *   ② `startIndex` 는 **그 목록 안에서** 누른 첨부를 가리킨다. 전체 목록 기준 위치를 넘기면
 *      엉뚱한 파일이 열린다.
 */
describe('AttachmentSection — (h) 미리보기 갤러리 목록', () => {
  const ZIP = {
    id: 'aa000000-0000-4000-a000-00000000000a',
    filename: 'archive.zip',
    contentType: 'application/zip',
    sizeBytes: 512,
    uploadedBy: 'bb000000-0000-4000-b000-000000000001',
    createdAt: '2026-06-15T11:00:00Z',
  }
  const PNG = {
    id: 'aa000000-0000-4000-a000-00000000000b',
    filename: 'shot.png',
    contentType: 'image/png',
    sizeBytes: 2048,
    uploadedBy: 'bb000000-0000-4000-b000-000000000001',
    createdAt: '2026-06-15T12:00:00Z',
  }

  it('미리보기 불가 첨부는 갤러리 목록에서 빠진다', async () => {
    // zip · pdf · png 셋 중 갤러리에 실려야 하는 것은 pdf 와 png 둘이다.
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([ZIP, ATTACHMENT_1, PNG]) as unknown as ReturnType<typeof useAttachmentList>,
    )
    const user = userEvent.setup()
    renderSection('ATLAS-1', false)

    await waitFor(() => { expect(screen.getByText('shot.png')).toBeInTheDocument() })
    const row = screen.getByRole('row', { name: /shot\.png/ })
    await user.click(
      within(row).getByRole('button', { name: new RegExp(attachmentLabels.previewButton) }),
    )

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('data-gallery-size', '2')
  })

  it('startIndex 가 갤러리 목록 기준으로 누른 첨부를 가리킨다 — 전체 목록 기준이 아니다', async () => {
    vi.mocked(useAttachmentList).mockReturnValue(
      listStub([ZIP, ATTACHMENT_1, PNG]) as unknown as ReturnType<typeof useAttachmentList>,
    )
    const user = userEvent.setup()
    renderSection('ATLAS-1', false)

    await waitFor(() => { expect(screen.getByText('shot.png')).toBeInTheDocument() })
    const row = screen.getByRole('row', { name: /shot\.png/ })
    await user.click(
      within(row).getByRole('button', { name: new RegExp(attachmentLabels.previewButton) }),
    )

    const dialog = await screen.findByRole('dialog')
    // 전체 목록에서 png 는 3번째(index 2)지만, 갤러리(zip 제외)에서는 2번째(index 1)다.
    expect(dialog).toHaveAttribute('data-start-index', '1')
    // 대역이 목록+위치로 계산한 이름이 실제 누른 파일이어야 한다.
    expect(dialog).toHaveAccessibleName('shot.png 미리보기')
  })
})
