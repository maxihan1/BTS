// 대시보드 생성/편집 공용 폼 단위 테스트 — 검증·visibility 연동·페이로드 분기 (FR-DB-01 Task 6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// ─────────────────────────────────────────────────────────────────────────────
// mock — use-users(useUserSearch), sonner toast
// ─────────────────────────────────────────────────────────────────────────────

const mockUsers = [
  { id: 'aaaaaaaa-0000-4000-8000-000000000001', username: 'alice', displayName: 'Alice', email: 'alice@example.com' },
  { id: 'aaaaaaaa-0000-4000-8000-000000000002', username: 'bob', displayName: 'Bob', email: 'bob@example.com' },
]

vi.mock('@/hooks/use-user-directory', () => ({
  useUserSearch: () => ({
    data: mockUsers,
    isLoading: false,
  }),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

type FormProps = {
  mode?: 'create' | 'edit'
  initialValues?: {
    name: string
    description?: string
    visibility: string
    sharedUserIds: string[]
    version?: number
  }
  onSubmit?: (payload: Record<string, unknown>) => void
  isPending?: boolean
}

async function renderForm(props: FormProps = {}) {
  const { DashboardForm } = await import('@/components/dashboard/DashboardForm')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const onSubmit = props.onSubmit ?? vi.fn()
  return {
    onSubmit,
    ...render(
      <QueryClientProvider client={client}>
        <DashboardForm
          mode={props.mode ?? 'create'}
          initialValues={props.initialValues}
          onSubmit={onSubmit}
          isPending={props.isPending ?? false}
        />
      </QueryClientProvider>,
    ),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ── EC8: name 검증 ──────────────────────────────────────────────────────────

  /**
   * T-DB6-1. 이름이 빈 값이면 제출을 차단하고 인라인 에러를 표시한다 (EC8).
   */
  it('T-DB6-1: 빈 이름으로 제출하면 에러 메시지를 표시하고 onSubmit을 호출하지 않는다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(onSubmit).not.toHaveBeenCalled()
  })

  /**
   * T-DB6-2. 이름이 201자이면 제출을 차단하고 인라인 에러를 표시한다 (EC8).
   */
  it('T-DB6-2: 201자 이름으로 제출하면 에러 메시지를 표시하고 onSubmit을 호출하지 않는다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    const longName = 'a'.repeat(201)
    await user.type(screen.getByLabelText(/이름/i), longName)
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(onSubmit).not.toHaveBeenCalled()
  }, 30_000)

  /**
   * T-DB6-3. 이름이 200자이면 제출이 통과한다.
   */
  it('T-DB6-3: 200자 이름은 유효하여 onSubmit이 호출된다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    const maxName = 'a'.repeat(200)
    await user.type(screen.getByLabelText(/이름/i), maxName, { delay: null })
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled()
    })
  }, 30_000)

  /**
   * T-DB6-4. 이름이 1자이면 제출이 통과한다.
   */
  it('T-DB6-4: 1자 이름은 유효하여 onSubmit이 호출된다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    await user.type(screen.getByLabelText(/이름/i), 'X')
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled()
    })
  })

  // ── description 선택 입력 ───────────────────────────────────────────────────

  /**
   * T-DB6-5. 설명 필드는 선택 항목이며 빈 값이어도 제출된다.
   */
  it('T-DB6-5: 설명을 비워도 제출이 통과한다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    await user.type(screen.getByLabelText(/이름/i), '내 대시보드')
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled()
    })
  })

  // ── EC7: visibility ↔ 공유 사용자 연동 ─────────────────────────────────────

  /**
   * T-DB6-6. visibility가 PRIVATE이면 공유 사용자 입력이 렌더되지 않거나 비활성이다.
   */
  it('T-DB6-6: PRIVATE 선택 시 공유 사용자 입력이 없거나 비활성이다', async () => {
    await renderForm()

    // 기본 visibility는 PRIVATE. 공유 입력 섹션이 없어야 한다.
    const shareInput = screen.queryByRole('textbox', { name: /공유/i })
    expect(shareInput).toBeNull()
  })

  /**
   * T-DB6-7. visibility가 TEAM이면 공유 사용자 입력 UI가 활성화된다 (EC7).
   */
  it('T-DB6-7: TEAM 선택 시 공유 사용자 입력이 활성화된다', async () => {
    const user = userEvent.setup()
    await renderForm()

    // visibility를 TEAM으로 변경
    const visibilitySelect = screen.getByRole('combobox', { name: /공개 범위/i })
    await user.selectOptions(visibilitySelect, 'TEAM')

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: /공유/i })).toBeInTheDocument()
    })
  })

  /**
   * T-DB6-8. visibility가 ORG이면 공유 사용자 입력이 없거나 비활성이다.
   */
  it('T-DB6-8: ORG 선택 시 공유 사용자 입력이 없거나 비활성이다', async () => {
    const user = userEvent.setup()
    await renderForm()

    const visibilitySelect = screen.getByRole('combobox', { name: /공개 범위/i })
    await user.selectOptions(visibilitySelect, 'ORG')

    const shareInput = screen.queryByRole('textbox', { name: /공유/i })
    expect(shareInput).toBeNull()
  })

  /**
   * T-DB6-9. TEAM 선택 후 공유 사용자 0명이어도 제출이 통과한다 (EC7).
   */
  it('T-DB6-9: TEAM 선택 후 공유 사용자 0명이어도 제출이 통과한다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm()

    await user.type(screen.getByLabelText(/이름/i), '팀 대시보드')
    const visibilitySelect = screen.getByRole('combobox', { name: /공개 범위/i })
    await user.selectOptions(visibilitySelect, 'TEAM')

    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ visibility: 'TEAM', sharedUserIds: [] }),
      )
    })
  })

  // ── 생성 모드 페이로드 ──────────────────────────────────────────────────────

  /**
   * T-DB6-10. 생성 모드에서 제출 시 전체 필드가 페이로드에 포함된다.
   */
  it('T-DB6-10: 생성 모드 제출 시 전체 필드가 페이로드에 포함된다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm({ mode: 'create' })

    await user.type(screen.getByLabelText(/이름/i), '신규 대시보드')
    await user.type(screen.getByLabelText(/설명/i), '대시보드 설명')
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          name: '신규 대시보드',
          description: '대시보드 설명',
          visibility: 'PRIVATE',
          sharedUserIds: [],
        }),
      )
    })
  })

  // ── 편집 모드 페이로드 (3-state diff) ──────────────────────────────────────

  /**
   * T-DB6-11. 편집 모드에서 변경 없이 제출 시 빈 diff(version 제외)가 된다.
   */
  it('T-DB6-11: 편집 모드에서 변경 없이 제출 시 변경 필드만 포함된 페이로드가 호출된다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm({
      mode: 'edit',
      initialValues: {
        name: '기존 대시보드',
        description: '기존 설명',
        visibility: 'PRIVATE',
        sharedUserIds: [],
        version: 3,
      },
    })

    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled()
    })
    // 변경 없으면 이름/설명/visibility/sharedUserIds 포함하지 않음 (3-state diff)
    const payload = (onSubmit as ReturnType<typeof vi.fn>).mock.calls[0]?.[0] as Record<string, unknown>
    expect(payload).not.toHaveProperty('name')
    expect(payload).not.toHaveProperty('description')
    expect(payload).not.toHaveProperty('visibility')
    expect(payload).not.toHaveProperty('sharedUserIds')
  })

  /**
   * T-DB6-12. 편집 모드에서 이름만 변경하면 이름만 페이로드에 포함된다.
   */
  it('T-DB6-12: 편집 모드에서 이름만 변경하면 이름만 페이로드에 포함된다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = await renderForm({
      mode: 'edit',
      initialValues: {
        name: '기존 대시보드',
        description: '',
        visibility: 'PRIVATE',
        sharedUserIds: [],
        version: 2,
      },
    })

    const nameInput = screen.getByLabelText(/이름/i)
    await user.clear(nameInput)
    await user.type(nameInput, '수정된 대시보드')
    await user.click(screen.getByRole('button', { name: /저장/i }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: '수정된 대시보드' }),
      )
    })
    const payload = (onSubmit as ReturnType<typeof vi.fn>).mock.calls[0]?.[0] as Record<string, unknown>
    expect(payload).not.toHaveProperty('visibility')
    expect(payload).not.toHaveProperty('description')
  })

  // ── isPending 상태 ──────────────────────────────────────────────────────────

  /**
   * T-DB6-13. isPending이 true이면 제출 버튼이 disabled이고 "저장 중" 텍스트가 표시된다.
   */
  it('T-DB6-13: isPending=true이면 버튼이 disabled이고 저장 중 텍스트가 표시된다', async () => {
    await renderForm({ isPending: true })

    const btn = screen.getByRole('button', { name: /저장 중/i })
    expect(btn).toBeDisabled()
  })
})
