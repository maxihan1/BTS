// workflows.$key 라우트 통합 테스트 — 다이어그램 렌더 + PostActionConfigSection 게이팅
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { useAuthStore } from '@/auth/authStore'
import { WorkflowDetailPage } from '@/routes/workflows.$key'

// ─────────────────────────────────────────────────────────────────────────────
// @tanstack/react-router mock — useParams만 스텁
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ key: 'software-default' }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderPage(workflowKey = 'software-default') {
  server.use(...workflowHandlers)
  return render(
    <QueryClientProvider client={makeClient()}>
      <WorkflowDetailPage workflowKey={workflowKey} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// teardown — authStore 리셋
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-1 ~ T5-2: 기존 read-only 다이어그램 회귀 0
// ─────────────────────────────────────────────────────────────────────────────

describe('WorkflowDetailPage — 기존 read-only 다이어그램 (회귀 0)', () => {
  /**
   * T5-1. 데이터 로드 후 워크플로우 이름이 표시된다.
   * admin/비admin 모두 다이어그램은 렌더되어야 한다.
   */
  it('T5-1: admin이 아닌 경우에도 워크플로우 이름이 표시된다', async () => {
    useAuthStore.setState({
      accessToken: 'token',
      user: {
        userId: 'u2',
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })

    renderPage()

    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 워크플로우')).toBeInTheDocument(),
    )
  })

  /**
   * T5-2. admin인 경우에도 워크플로우 이름이 표시된다 (다이어그램 회귀 0).
   */
  it('T5-2: admin인 경우에도 워크플로우 이름이 표시된다', async () => {
    useAuthStore.setState({
      accessToken: 'token',
      user: {
        userId: 'u1',
        username: 'admin',
        email: 'admin@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: true,
        mfaEnrollmentRequired: false,
      },
    })

    renderPage()

    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 워크플로우')).toBeInTheDocument(),
    )
  })

  /**
   * T5-3. 잘못된 key로 조회 시 에러 메시지가 표시된다 (기존 동작 유지).
   */
  it('T5-3: 존재하지 않는 워크플로우 key는 에러 메시지를 표시한다', async () => {
    renderPage('nonexistent-key')

    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    )
    expect(screen.getByText(/워크플로우를 찾을 수 없습니다/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5-4 ~ T5-5: PostActionConfigSection 게이팅
// ─────────────────────────────────────────────────────────────────────────────

describe('WorkflowDetailPage — PostActionConfigSection 게이팅', () => {
  /**
   * T5-4. isSystemAdmin=true이면 워크플로우 로드 후 PostActionConfigSection이 렌더된다.
   * 섹션 내부의 전환 선택 combobox로 존재 여부를 확인한다.
   */
  it('T5-4: admin이면 데이터 로드 후 PostActionConfigSection이 렌더된다', async () => {
    useAuthStore.setState({
      accessToken: 'token',
      user: {
        userId: 'u1',
        username: 'admin',
        email: 'admin@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: true,
        mfaEnrollmentRequired: false,
      },
    })

    renderPage()

    // 워크플로우 이름이 먼저 나타나야 함 (data 로드 완료)
    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 워크플로우')).toBeInTheDocument(),
    )

    // PostActionConfigSection 내부의 전환 선택 combobox가 있어야 함
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  /**
   * T5-5. isSystemAdmin=false이면 PostActionConfigSection이 렌더되지 않는다.
   * 다이어그램(워크플로우 이름)은 여전히 표시되어야 한다.
   */
  it('T5-5: 비admin이면 PostActionConfigSection이 렌더되지 않는다', async () => {
    useAuthStore.setState({
      accessToken: 'token',
      user: {
        userId: 'u2',
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })

    renderPage()

    // 다이어그램은 렌더되어야 함
    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 워크플로우')).toBeInTheDocument(),
    )

    // PostActionConfigSection의 combobox가 없어야 함
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  /**
   * T5-6. user=null(미인증)이면 PostActionConfigSection이 렌더되지 않는다.
   */
  it('T5-6: 미인증(user=null)이면 PostActionConfigSection이 렌더되지 않는다', async () => {
    useAuthStore.setState({ accessToken: null, user: null })

    renderPage()

    await waitFor(() =>
      expect(screen.getByText('소프트웨어 개발 기본 워크플로우')).toBeInTheDocument(),
    )

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})
