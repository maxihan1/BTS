// PostActionConfigSection 컴포넌트 단위 테스트 — 게이팅/전이선택/목록/추가/수정/삭제
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import type { WorkflowTransitionView } from '@/components/workflow/workflow.types'
import { PostActionConfigSection } from '@/components/workflow/PostActionConfigSection'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const WORKFLOW_KEY = 'software-default'

const sampleTransitions: WorkflowTransitionView[] = [
  { key: 'open__in-progress', name: '진행 시작', fromStateKey: 'open', toStateKey: 'in-progress' },
  { key: 'in-progress__done', name: '완료 처리', fromStateKey: 'in-progress', toStateKey: 'done' },
]

const sampleAction = {
  id: '550e8400-e29b-41d4-a716-446655440001',
  type: 'CALL_WEBHOOK',
  config: { url: 'https://example.com/hook', method: 'POST' },
  displayOrder: 0,
}

const TX_KEY = 'open__in-progress'
const BASE_PATH = `/api/v1/workflows/${WORKFLOW_KEY}/transitions/${TX_KEY}/post-actions`

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function renderSection(props: { workflowKey?: string; transitions?: WorkflowTransitionView[] } = {}) {
  const Wrapper = createWrapper()
  return render(
    <Wrapper>
      <PostActionConfigSection
        workflowKey={props.workflowKey ?? WORKFLOW_KEY}
        transitions={props.transitions ?? sampleTransitions}
      />
    </Wrapper>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-1 ~ PACS-2: isSystemAdmin 게이팅
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — isSystemAdmin 게이팅', () => {
  /**
   * PACS-1. isSystemAdmin=true이면 섹션이 렌더된다.
   */
  it('PACS-1: isSystemAdmin=true이면 섹션이 렌더된다', () => {
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

    renderSection()

    // 섹션 제목 또는 전이 선택 select가 DOM에 있어야 함
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  /**
   * PACS-2. isSystemAdmin=false이면 섹션이 렌더되지 않는다(null 반환).
   */
  it('PACS-2: isSystemAdmin=false이면 섹션이 렌더되지 않는다', () => {
    useAuthStore.setState({
      accessToken: 'token',
      user: {
        userId: 'u1',
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })

    renderSection()

    // combobox(전이 선택)도 없어야 하고, 특정 헤딩도 없어야 함
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  /**
   * PACS-2b. user가 null(미인증)이면 섹션이 렌더되지 않는다.
   */
  it('PACS-2b: 미인증(user=null)이면 섹션이 렌더되지 않는다', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    renderSection()

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-3 ~ PACS-5: 전이 선택
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — 전이 선택', () => {
  beforeEach(() => {
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
  })

  /**
   * PACS-3. 전이 선택 select에 transitions props가 옵션으로 렌더된다.
   */
  it('PACS-3: transitions props가 select 옵션으로 렌더된다', () => {
    renderSection()

    const select = screen.getByRole('combobox')
    expect(select).toBeInTheDocument()

    // 전이 이름이 옵션으로 있어야 함
    expect(screen.getByText('진행 시작')).toBeInTheDocument()
    expect(screen.getByText('완료 처리')).toBeInTheDocument()
  })

  /**
   * PACS-4. 전이 미선택 시 목록이 렌더되지 않는다(query 비활성).
   */
  it('PACS-4: 전이 미선택 시 목록이 렌더되지 않는다', () => {
    renderSection()

    // 테이블 또는 로딩 인디케이터가 없어야 함
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  /**
   * PACS-5. 전이를 선택하면 해당 전이의 post-action 목록 API가 호출된다.
   * MSW 응답 1건 → 테이블 행 확인.
   */
  it('PACS-5: 전이 선택 시 post-action 목록이 렌더된다', async () => {
    server.use(
      http.get(BASE_PATH, () =>
        HttpResponse.json({ data: [sampleAction] }),
      ),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() =>
      expect(screen.getByRole('table')).toBeInTheDocument(),
    )

    // sampleAction의 type 컬럼이 보여야 함
    expect(screen.getByText('CALL_WEBHOOK')).toBeInTheDocument()
  })

  /**
   * PACS-5b. 전이 선택 후 post-action이 0건이면 빈 상태 안내가 렌더된다.
   */
  it('PACS-5b: post-action 0건이면 빈 상태 안내가 렌더된다', async () => {
    server.use(
      http.get(BASE_PATH, () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() =>
      expect(screen.queryByRole('table')).not.toBeInTheDocument(),
    )
    // 빈 상태 문구가 있어야 함
    expect(screen.getByText(/post-action|등록된/i)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-6 ~ PACS-7: Webhook 추가 버튼 + dialog 열림
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — Webhook 추가', () => {
  beforeEach(() => {
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
  })

  /**
   * PACS-6. "Webhook 추가" 버튼이 렌더된다.
   */
  it('PACS-6: "Webhook 추가" 버튼이 존재한다', () => {
    renderSection()

    expect(screen.getByRole('button', { name: /Webhook 추가/ })).toBeInTheDocument()
  })

  /**
   * PACS-7. "Webhook 추가" 버튼 클릭 시 PostActionFormDialog(create 모드)가 열린다.
   */
  it('PACS-7: 추가 버튼 클릭 시 dialog가 열린다', () => {
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: /Webhook 추가/ }))

    // create 모드 dialog 제목
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Post-Action 추가')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-8 ~ PACS-9: 수정/삭제 버튼 (전이 선택 후)
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — 수정/삭제', () => {
  beforeEach(() => {
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
  })

  /**
   * PACS-8. CALL_WEBHOOK 행에 수정 버튼이 있고 클릭 시 edit 모드 dialog가 열린다.
   */
  it('PACS-8: CALL_WEBHOOK 행 수정 버튼 클릭 시 edit dialog가 열린다', async () => {
    server.use(
      http.get(BASE_PATH, () =>
        HttpResponse.json({ data: [sampleAction] }),
      ),
    )

    renderSection()

    // 전이 선택
    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() =>
      expect(screen.getByRole('table')).toBeInTheDocument(),
    )

    // 수정 버튼 클릭
    const editBtn = screen.getByRole('button', { name: /수정/ })
    fireEvent.click(editBtn)

    // edit 모드 dialog 확인
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Post-Action 수정')).toBeInTheDocument()
  })

  /**
   * PACS-9. 삭제 버튼 클릭 시 useRemovePostAction.mutate가 호출된다 (MSW DELETE 핸들러).
   */
  it('PACS-9: 삭제 버튼 클릭 시 DELETE API가 호출된다', async () => {
    let deleteCalled = false

    server.use(
      http.get(BASE_PATH, () =>
        HttpResponse.json({ data: [sampleAction] }),
      ),
      http.delete(`${BASE_PATH}/${sampleAction.id}`, () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderSection()

    // 전이 선택
    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() =>
      expect(screen.getByRole('table')).toBeInTheDocument(),
    )

    // 삭제 버튼 클릭
    const deleteBtn = screen.getByRole('button', { name: /삭제/ })
    fireEvent.click(deleteBtn)

    await waitFor(() => expect(deleteCalled).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-10: create 모드 onSubmit → mutation 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — create onSubmit', () => {
  beforeEach(() => {
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
  })

  /**
   * PACS-10. 추가 dialog에서 onSubmit 시 POST API가 호출된다.
   * 전이 선택 없이 추가버튼 → dialog → 폼 입력 → 제출.
   */
  it('PACS-10: dialog create onSubmit 시 POST API가 호출된다', async () => {
    let postCalled = false

    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [] })),
      http.post(BASE_PATH, async () => {
        postCalled = true
        return HttpResponse.json({ data: sampleAction }, { status: 201 })
      }),
    )

    renderSection()

    // 전이 선택
    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    // 추가 버튼 → dialog 열기
    fireEvent.click(screen.getByRole('button', { name: /Webhook 추가/ }))

    await waitFor(() =>
      expect(screen.getByRole('dialog')).toBeInTheDocument(),
    )

    // 폼 입력
    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'https://example.com/new' },
    })
    fireEvent.change(screen.getByLabelText(/메서드|Method/i), {
      target: { value: 'POST' },
    })

    // 제출
    fireEvent.click(screen.getByRole('button', { name: /추가/ }))

    await waitFor(() => expect(postCalled).toBe(true))
  })
})
