// AddMemberDialog 컴포넌트 단위 테스트 — 검색 typeahead + 사용자 선택 + 역할 선택 + 추가 + EC-4 0건
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { toast } from 'sonner'
import { useAuthStore } from '@/auth/authStore'
import { projectMemberLabels } from '@/i18n/project-member-labels'
import { AddMemberDialog } from './AddMemberDialog'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

const SEARCH_RESULTS = [
  { id: '00000000-0000-4000-8000-000000000003', username: 'carol', displayName: '캐럴', email: null },
  { id: 'e745adab-f152-44cb-987e-aff965d3db4f', username: 'dave', displayName: '데이브', email: null },
]

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

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  const trigger = screen.getByRole('button', { name: projectMemberLabels.addDialog.triggerButton })
  await user.click(trigger)
  // Dialog 열림 확인
  await waitFor(() => {
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
}

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'test-token',
    user: { userId: 'fixture-alice-uuid', username: 'alice', email: 'alice@example.com', authMethod: 'local', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
  })
  vi.clearAllMocks()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AddMemberDialog — 기본 렌더', () => {
  it('트리거 버튼이 렌더된다', () => {
    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    expect(
      screen.getByRole('button', { name: projectMemberLabels.addDialog.triggerButton }),
    ).toBeInTheDocument()
  })

  it('트리거 클릭 시 Dialog가 열리고 제목이 표시된다', async () => {
    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    expect(screen.getByRole('heading', { name: projectMemberLabels.addDialog.title })).toBeInTheDocument()
  })

  it('Dialog에 취소 버튼이 있다', async () => {
    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    expect(
      screen.getByRole('button', { name: projectMemberLabels.addDialog.cancelButton }),
    ).toBeInTheDocument()
  })
})

describe('AddMemberDialog — 사용자 검색 typeahead', () => {
  it('2자 미만 입력 시 검색을 실행하지 않는다', async () => {
    let requestCount = 0
    server.use(
      http.get('/api/v1/users', () => {
        requestCount++
        return HttpResponse.json([])
      }),
    )

    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    const input = screen.getByPlaceholderText(projectMemberLabels.addDialog.searchPlaceholder)
    await user.type(input, 'c')

    // 짧은 입력으로는 API를 호출하지 않아야 한다
    await waitFor(() => {
      expect(requestCount).toBe(0)
    })
  })

  it('2자 이상 입력 시 검색 결과를 표시한다', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json(SEARCH_RESULTS),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    const input = screen.getByPlaceholderText(projectMemberLabels.addDialog.searchPlaceholder)
    await user.type(input, 'ca')

    await waitFor(() => {
      expect(screen.getByText('캐럴')).toBeInTheDocument()
    })
    expect(screen.getByText('데이브')).toBeInTheDocument()
  })

  it('검색 결과가 없으면 "검색 결과 없음"을 표시한다 (EC-4)', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json([]),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    const input = screen.getByPlaceholderText(projectMemberLabels.addDialog.searchPlaceholder)
    await user.type(input, 'zzz')

    await waitFor(() => {
      expect(screen.getByText(projectMemberLabels.addDialog.noResults)).toBeInTheDocument()
    })
  })
})

describe('AddMemberDialog — 멤버 추가 플로우', () => {
  it('사용자 선택 → 역할 선택 → 추가 버튼 클릭 시 addMember mutation이 실행된다', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json(SEARCH_RESULTS),
      ),
      http.post('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json(
          {
            projectId: 'project-atlas-uuid',
            userId: '00000000-0000-4000-8000-000000000003',
            role: 'MEMBER',
            createdAt: '2026-06-01T00:00:00Z',
            updatedAt: '2026-06-01T00:00:00Z',
            displayName: '캐럴',
            username: 'carol',
          },
          { status: 201 },
        ),
      ),
      http.get('/api/v1/projects/:projectKey/members', () =>
        HttpResponse.json({ members: [] }),
      ),
    )

    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    // 검색
    const input = screen.getByPlaceholderText(projectMemberLabels.addDialog.searchPlaceholder)
    await user.type(input, 'ca')

    await waitFor(() => {
      expect(screen.getByText('캐럴')).toBeInTheDocument()
    })

    // 사용자 선택
    await user.click(screen.getByText('캐럴'))

    // 추가 버튼 클릭
    const confirmBtn = screen.getByRole('button', { name: projectMemberLabels.addDialog.confirmButton })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalled()
    })
  })

  it('취소 버튼 클릭 시 Dialog가 닫힌다', async () => {
    const Wrapper = createWrapper()
    render(
      <AddMemberDialog projectKey={PROJECT_KEY} />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    await openDialog(user)

    const cancelBtn = screen.getByRole('button', { name: projectMemberLabels.addDialog.cancelButton })
    await user.click(cancelBtn)

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})
