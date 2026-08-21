// workflows.$key 라우트 통합 테스트 — 다이어그램 렌더 + PostActionConfigSection 게이팅
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { softwareDefaultFixture } from '@/mocks/workflow-fixtures'
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
   * T5-7. MSW 픽스처(= 실제 응답 모양)의 INITIAL 전환이 화면까지 닿고, 전환 선택의 값이
   * 응답에 실린 전환 `id` 그대로다.
   *
   * 마이그레이션 V207 ⑨ 가 워크플로우마다 `fromStateKey: null` 인 INITIAL 1건을 백필하므로
   * 모든 실제 응답에 null 원소가 섞인다. 그 원소가 렌더 계층에서 TypeError 를 내던 결함의 재현 단언이다.
   *
   * 지목값이 `id` 인 이유. 전환의 1급 식별자는 `workflow_transitions.id` 이고 `key`(`INITIAL__open`)는
   * 하위호환용 계산 프로퍼티로 강등돼 더는 유일하지 않다 (ADR 2026-08-18 §D1). backend 는 합성 키가
   * 2건 이상에 걸리면 대상을 특정할 수 없다고 보고 404 로 거절하므로, 화면이 `key` 를 보내면
   * 같은 구간에 전환이 둘 생기는 순간 조용히 못 쓰게 된다.
   *
   * 재조립 판정은 **option 전량**으로 잰다. INITIAL 한 건만 보면 나머지 전환에서 프론트가 값을
   * 다시 만들어 내도 통과한다 — 기대값은 하드코딩이 아니라 픽스처(= 응답)에서 뽑는다.
   */
  it('T5-7: admin이면 INITIAL 전환이 응답 전환 id 로 전환 선택에 나타난다', async () => {
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

    // 재현 대상(출발 상태 없는 INITIAL)이 응답에 실제로 있어야 아래 단언이 공허하지 않다
    const initial = softwareDefaultFixture.transitions.find((t) => t.kind === 'INITIAL')
    expect(initial).toBeDefined()
    expect(initial?.fromStateKey).toBeNull()

    const option = await screen.findByRole('option', { name: '이슈 생성' })
    expect((option as HTMLOptionElement).value).toBe(initial?.id)

    // 응답을 그대로 쓴다 = option value 전량이 (placeholder + 응답 전환 id 목록) 과 같다
    const values = screen.getAllByRole('option').map((o) => (o as HTMLOptionElement).value)
    expect(values).toEqual(['', ...softwareDefaultFixture.transitions.map((t) => t.id)])
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
