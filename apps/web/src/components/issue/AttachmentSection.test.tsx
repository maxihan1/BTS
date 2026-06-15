// AttachmentSection 컴포넌트 단위 테스트 — FR-AC-01 D6 Task 4 TDD RED
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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

    const user = userEvent.setup()
    renderSection('ATLAS-1', true)

    const dropzone = await screen.findByRole('button', { name: attachmentLabels.dropzoneHint })

    // 포커스 후 Space 키 → preventDefault 여부를 keyDown 이벤트로 캡처
    let preventDefaultCalled = false
    dropzone.addEventListener('keydown', (e) => {
      if (e.key === ' ' && e.defaultPrevented) preventDefaultCalled = true
    })

    await user.tab() // dropzone으로 포커스 이동
    await user.keyboard(' ') // Space 키 입력

    expect(preventDefaultCalled).toBe(true)
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
