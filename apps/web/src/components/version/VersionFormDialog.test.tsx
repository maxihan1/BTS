// VersionFormDialog 단위 테스트 — 생성/수정 모드, 변경 감지 4케이스, 날짜 null 정규화, 409 에러
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { Version } from '@/api/versions.types'
import { VersionFormDialog } from './VersionFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const VERSION_UUID = '00000000-0000-4000-8000-000000000001'
const PROJECT_UUID = '00000000-0000-4000-8000-000000000010'

const EXISTING_VERSION: Version = {
  id: VERSION_UUID,
  projectId: PROJECT_UUID,
  name: '1.0.0',
  description: '첫 릴리즈',
  startDate: '2026-01-01',
  releaseDate: '2026-06-30',
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 생성 모드
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — 생성 모드', () => {
  it('open=true 시 dialog가 렌더된다', () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={vi.fn()}
      />,
      { wrapper: Wrapper },
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('이름을 비운 채 저장 시 onSubmit이 호출되지 않고 오류 메시지가 표시된다', async () => {
    const mockCreate = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={vi.fn()}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(mockCreate).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('생성 모드: name/desc/startDate/releaseDate 입력 후 저장 시 createVersion 호출', async () => {
    const mockCreate = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={vi.fn()}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/ }), '2.0.0')
    await user.type(screen.getByRole('textbox', { name: /설명/ }), '새 버전')
    // date input은 type="date" — labelText로 탐색
    const startDateInput = screen.getByLabelText('시작일')
    const releaseDateInput = screen.getByLabelText('릴리즈 예정일')
    await user.type(startDateInput, '2026-07-01')
    await user.type(releaseDateInput, '2026-12-31')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledWith(
        expect.objectContaining({ name: '2.0.0' }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정 모드 — 변경 감지 4케이스 (C1 핵심)
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — 수정 모드 변경 감지', () => {
  let mockUpdate: ReturnType<typeof vi.fn>
  let mockChangeDates: ReturnType<typeof vi.fn>

  beforeEach(() => {
    mockUpdate = vi.fn()
    mockChangeDates = vi.fn()
  })

  it('(a) 둘 다 미변경 저장 → 0 mutation 호출', async () => {
    const mockClose = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={EXISTING_VERSION}
        onClose={mockClose}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockClose).toHaveBeenCalled()
    })
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(mockChangeDates).not.toHaveBeenCalled()
  })

  it('(b) 날짜만 변경 → useChangeVersionDates 1회만 호출', async () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={EXISTING_VERSION}
        onClose={vi.fn()}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    // releaseDate를 비워서 null로 변경
    const releaseDateInput = screen.getByLabelText('릴리즈 예정일')
    await user.clear(releaseDateInput)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockChangeDates).toHaveBeenCalledWith(
        expect.objectContaining({ releaseDate: null }),
      )
    })
    expect(mockUpdate).not.toHaveBeenCalled()
  })

  it('(c) name/desc만 변경 → useUpdateVersion 1회만 호출', async () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={EXISTING_VERSION}
        onClose={vi.fn()}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '1.1.0')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalledWith(
        expect.objectContaining({ input: expect.objectContaining({ name: '1.1.0' }) }),
      )
    })
    expect(mockChangeDates).not.toHaveBeenCalled()
  })

  it('(d) 둘 다 변경 → useUpdateVersion + useChangeVersionDates 모두 호출', async () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={EXISTING_VERSION}
        onClose={vi.fn()}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()

    // name 변경
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '1.1.0')

    // startDate 비우기
    const startDateInput = screen.getByLabelText('시작일')
    await user.clear(startDateInput)

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalled()
      expect(mockChangeDates).toHaveBeenCalled()
    })
  })

  it('날짜 비우기 → changeVersionDates에 null 전송', async () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={EXISTING_VERSION}
        onClose={vi.fn()}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.clear(screen.getByLabelText('시작일'))
    await user.clear(screen.getByLabelText('릴리즈 예정일'))

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockChangeDates).toHaveBeenCalledWith(
        expect.objectContaining({
          id: VERSION_UUID,
          startDate: null,
          releaseDate: null,
        }),
      )
    })
  })

  it('역순 날짜(startDate > releaseDate) 입력 허용 — 저장 성공', async () => {
    // initial에 날짜 없는 버전으로 시작해서 역순 날짜 입력
    const versionNoDates: Version = { ...EXISTING_VERSION, startDate: null, releaseDate: null }
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="edit"
        projectKey={PROJECT_KEY}
        initial={versionNoDates}
        onClose={vi.fn()}
        _testUpdateMutate={mockUpdate}
        _testChangeDatesMutate={mockChangeDates}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('시작일'), '2026-12-31')
    await user.type(screen.getByLabelText('릴리즈 예정일'), '2026-01-01')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockChangeDates).toHaveBeenCalledWith(
        expect.objectContaining({
          startDate: '2026-12-31',
          releaseDate: '2026-01-01',
        }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 409 에러 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — 409 에러 표시', () => {
  it('submitError prop이 있으면 폼 내 에러 메시지를 표시한다', () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={vi.fn()}
        submitError="이미 같은 이름의 버전이 있습니다."
      />,
      { wrapper: Wrapper },
    )
    expect(screen.getByText('이미 같은 이름의 버전이 있습니다.')).toBeInTheDocument()
  })

  it('submitError가 있어도 Dialog가 열린 채로 유지된다', () => {
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={vi.fn()}
        submitError="이미 같은 이름의 버전이 있습니다."
      />,
      { wrapper: Wrapper },
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 취소 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — 취소', () => {
  it('취소 버튼 클릭 시 onClose가 호출된다', async () => {
    const mockClose = vi.fn()
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={mockClose}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(mockClose).toHaveBeenCalled()
  })
})
