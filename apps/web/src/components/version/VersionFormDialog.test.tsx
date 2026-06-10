// VersionFormDialog 단위 테스트 — 생성/수정 모드, 변경 감지 4케이스, 날짜 null 정규화, 409 에러
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { Version } from '@/api/versions.types'
import type {
  UpdateVersionMutationInput,
  ChangeVersionDatesMutationInput,
} from '@/hooks/use-versions'
import type { CreateVersionInput } from '@/api/versions.types'
import { ApiError } from '@/api/client'
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
  status: 'UNRELEASED',
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
    const mockCreate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: CreateVersionInput,
    ) => Promise<void>
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
    const mockCreate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: CreateVersionInput,
    ) => Promise<void>
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
  let mockUpdate: (input: UpdateVersionMutationInput) => Promise<void>
  let mockChangeDates: (input: ChangeVersionDatesMutationInput) => Promise<void>

  beforeEach(() => {
    mockUpdate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    mockChangeDates = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
// 409 에러 표시 — mutation reject → 인라인 에러 (H3 수정)
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — 409 에러 인라인 표시', () => {
  it('생성 실패(VERSION_NAME_DUPLICATE) → 폼 내 인라인 에러 메시지가 표시되고 Dialog가 닫히지 않는다', async () => {
    const mockClose = vi.fn()
    // ApiError를 흉내내는 에러 객체: extractVersionErrorCode가 body.errorCode를 읽는다
    const dupError = new ApiError(409, { errorCode: 'VERSION_NAME_DUPLICATE' })
    const mockCreate = vi.fn().mockRejectedValue(dupError) as unknown as (
      input: CreateVersionInput,
    ) => Promise<void>
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={mockClose}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/ }), '기존버전')
    await user.click(screen.getByRole('button', { name: '저장' }))

    // 인라인 에러 메시지가 폼 내에 표시되어야 한다
    await waitFor(() => {
      expect(screen.getByText('이미 같은 이름의 버전이 있습니다.')).toBeInTheDocument()
    })
    // Dialog는 닫히지 않아야 한다
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(mockClose).not.toHaveBeenCalled()
  })

  it('수정 실패(meta reject, VERSION_NAME_DUPLICATE) → 폼 내 인라인 에러 메시지가 표시되고 Dialog가 닫히지 않는다', async () => {
    const mockClose = vi.fn()
    const dupError = new ApiError(409, { errorCode: 'VERSION_NAME_DUPLICATE' })
    const mockUpdate = vi.fn().mockRejectedValue(dupError) as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    const mockChangeDates = vi.fn() as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '중복버전')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.getByText('이미 같은 이름의 버전이 있습니다.')).toBeInTheDocument()
    })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(mockClose).not.toHaveBeenCalled()
  })

  it('이전 에러 표시 후 새 저장 시도 시작 시 에러 메시지가 초기화된다', async () => {
    const mockClose = vi.fn()
    const dupError = new ApiError(409, { errorCode: 'VERSION_NAME_DUPLICATE' })
    // 첫 번째는 실패, 두 번째는 성공
    const mockCreate = vi
      .fn()
      .mockRejectedValueOnce(dupError)
      .mockResolvedValueOnce(undefined) as unknown as (input: CreateVersionInput) => Promise<void>
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={mockClose}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/ }), '기존버전')
    await user.click(screen.getByRole('button', { name: '저장' }))

    // 첫 번째 실패 후 에러 표시 확인
    await waitFor(() => {
      expect(screen.getByText('이미 같은 이름의 버전이 있습니다.')).toBeInTheDocument()
    })

    // 두 번째 저장 클릭 → onClose 호출됨
    await user.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => {
      expect(mockClose).toHaveBeenCalledTimes(1)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// mutation 성공/실패 → onClose 여부 (C1 코드리뷰 결함)
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionFormDialog — mutation 성공/실패 후 onClose', () => {
  it('생성 성공(Promise resolve) → onClose 호출', async () => {
    const mockClose = vi.fn()
    const mockCreate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: CreateVersionInput,
    ) => Promise<void>
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={mockClose}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/ }), '2.0.0')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockClose).toHaveBeenCalledTimes(1)
    })
  })

  it('생성 실패(Promise reject) → onClose 미호출', async () => {
    const mockClose = vi.fn()
    const mockCreate = vi
      .fn()
      .mockRejectedValue(new Error('VERSION_NAME_DUPLICATE')) as unknown as (
      input: CreateVersionInput,
    ) => Promise<void>
    const Wrapper = createWrapper()
    render(
      <VersionFormDialog
        open={true}
        mode="create"
        projectKey={PROJECT_KEY}
        onClose={mockClose}
        _testCreateMutate={mockCreate}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/ }), '기존버전')
    await user.click(screen.getByRole('button', { name: '저장' }))

    // reject 처리 완료 후에도 onClose 미호출
    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledTimes(1)
    })
    expect(mockClose).not.toHaveBeenCalled()
  })

  it('수정 성공(meta only, Promise resolve) → onClose 호출', async () => {
    const mockClose = vi.fn()
    const mockUpdate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    const mockChangeDates = vi.fn() as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '1.1.0')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockClose).toHaveBeenCalledTimes(1)
    })
  })

  it('수정 성공(dates only, Promise resolve) → onClose 호출', async () => {
    const mockClose = vi.fn()
    const mockUpdate = vi.fn() as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    const mockChangeDates = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
    await user.clear(screen.getByLabelText('릴리즈 예정일'))
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockClose).toHaveBeenCalledTimes(1)
    })
  })

  it('수정 성공(meta+dates 둘 다, Promise resolve) → onClose 호출', async () => {
    const mockClose = vi.fn()
    const mockUpdate = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    const mockChangeDates = vi.fn().mockResolvedValue(undefined) as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '1.1.0')
    await user.clear(screen.getByLabelText('릴리즈 예정일'))
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockClose).toHaveBeenCalledTimes(1)
    })
  })

  it('수정 실패(meta reject) → onClose 미호출', async () => {
    const mockClose = vi.fn()
    const mockUpdate = vi
      .fn()
      .mockRejectedValue(new Error('VERSION_NAME_DUPLICATE')) as unknown as (
      input: UpdateVersionMutationInput,
    ) => Promise<void>
    const mockChangeDates = vi.fn() as unknown as (
      input: ChangeVersionDatesMutationInput,
    ) => Promise<void>
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
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, '기존버전')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalledTimes(1)
    })
    expect(mockClose).not.toHaveBeenCalled()
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
