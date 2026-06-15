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
// PACS-B1: stale 프리필 회귀 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — B1 stale 프리필 방지', () => {
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
   * PACS-B1. A행으로 edit dialog를 열었다가 닫은 뒤, B행으로 edit dialog를 열면
   * B의 url이 프리필되어야 한다 (A의 값이 남으면 안 된다).
   */
  it('PACS-B1: A행 edit 후 B행 edit 시 B 값이 프리필된다', async () => {
    const actionA = {
      id: '550e8400-e29b-41d4-a716-446655440001',
      type: 'CALL_WEBHOOK',
      config: { url: 'https://action-a.example.com/hook', method: 'POST' },
      displayOrder: 0,
    }
    const actionB = {
      id: '550e8400-e29b-41d4-a716-446655440002',
      type: 'CALL_WEBHOOK',
      config: { url: 'https://action-b.example.com/hook', method: 'PUT' },
      displayOrder: 1,
    }

    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [actionA, actionB] })),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())

    // A행 수정 버튼들 중 첫 번째(A행) 클릭
    const editBtns = screen.getAllByRole('button', { name: /CALL_WEBHOOK post-action 수정/ })
    fireEvent.click(editBtns[0])

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())

    // A의 url이 프리필됐는지 확인
    const urlInputAfterA = screen.getByLabelText(/URL/i) as HTMLInputElement
    expect(urlInputAfterA.value).toBe('https://action-a.example.com/hook')

    // 취소로 닫음
    fireEvent.click(screen.getByRole('button', { name: /취소/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // B행 수정 버튼(두 번째) 클릭
    const editBtnsAfterClose = screen.getAllByRole('button', { name: /CALL_WEBHOOK post-action 수정/ })
    fireEvent.click(editBtnsAfterClose[1])

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())

    // B의 url이 프리필되어야 함 (A값이 stale하게 남으면 안 됨)
    const urlInputAfterB = screen.getByLabelText(/URL/i) as HTMLInputElement
    expect(urlInputAfterB.value).toBe('https://action-b.example.com/hook')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-D1: 전이 미선택 시 Webhook 추가 버튼 disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — D1 전이 미선택 시 추가 버튼 disabled', () => {
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
   * PACS-D1a. 전이 미선택 상태에서 "Webhook 추가" 버튼이 disabled다.
   */
  it('PACS-D1a: 전이 미선택 시 추가 버튼이 disabled다', () => {
    renderSection()

    const addBtn = screen.getByRole('button', { name: /Webhook 추가/ })
    expect(addBtn).toBeDisabled()
  })

  /**
   * PACS-D1b. 전이 선택 후 "Webhook 추가" 버튼이 enabled된다.
   */
  it('PACS-D1b: 전이 선택 후 추가 버튼이 enabled된다', () => {
    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    const addBtn = screen.getByRole('button', { name: /Webhook 추가/ })
    expect(addBtn).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-C1: mutation 에러 시 toast.error 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — C1 mutation 에러 toast', () => {
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
   * PACS-C1a. add mutation 실패 시 toast.error가 호출된다.
   */
  it('PACS-C1a: add mutation 실패 시 toast.error가 호출된다', async () => {
    const toastError = vi.fn()
    vi.mock('sonner', () => ({ toast: { error: toastError } }))

    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [] })),
      http.post(BASE_PATH, () =>
        HttpResponse.json({ error: { code: 'FORBIDDEN' } }, { status: 403 }),
      ),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() => expect(screen.queryByRole('table')).not.toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Webhook 추가/ }))
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'https://example.com/webhook' },
    })
    fireEvent.change(screen.getByLabelText(/메서드|Method/i), {
      target: { value: 'POST' },
    })

    fireEvent.click(screen.getByRole('button', { name: /추가/ }))

    await waitFor(() => expect(toastError).toHaveBeenCalled())

    vi.restoreAllMocks()
  })

  /**
   * PACS-C1b. delete mutation 실패 시 toast.error가 호출된다.
   */
  it('PACS-C1b: delete mutation 실패 시 toast.error가 호출된다', async () => {
    const sampleActionForError = {
      id: '550e8400-e29b-41d4-a716-446655440001',
      type: 'CALL_WEBHOOK',
      config: { url: 'https://example.com/hook', method: 'POST' },
      displayOrder: 0,
    }
    const toastError = vi.fn()
    vi.mock('sonner', () => ({ toast: { error: toastError } }))

    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [sampleActionForError] })),
      http.delete(`${BASE_PATH}/${sampleActionForError.id}`, () =>
        HttpResponse.json({ error: { code: 'FORBIDDEN' } }, { status: 403 }),
      ),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /CALL_WEBHOOK post-action 삭제/ }))

    await waitFor(() => expect(toastError).toHaveBeenCalled())

    vi.restoreAllMocks()
  })

  /**
   * PACS-D9. list query 에러 시 에러 UI가 렌더된다.
   * 에러 문구는 에러 상황임을 알리는 텍스트 — role=alert 또는 특정 문자 패턴.
   */
  it('PACS-D9: list query 에러 시 에러 UI가 렌더된다', async () => {
    server.use(
      http.get(BASE_PATH, () =>
        HttpResponse.json({ error: { code: 'INTERNAL_ERROR' } }, { status: 500 }),
      ),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    // 에러 UI — role=alert 또는 에러 관련 텍스트 존재
    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PACS-C2: displayOrder 충돌 방지
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionConfigSection — C2 displayOrder 중복 방지', () => {
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
   * PACS-C2. order [0, 2] 상태(1이 삭제된 상태)에서 추가 시
   * displayOrder=3이 POST body에 담겨야 한다 (length=2가 아님).
   */
  it('PACS-C2: order [0,2] 상태에서 추가 시 displayOrder=3으로 POST된다', async () => {
    const actionsWithGap = [
      {
        id: '550e8400-e29b-41d4-a716-446655440001',
        type: 'CALL_WEBHOOK',
        config: { url: 'https://a.example.com', method: 'POST' },
        displayOrder: 0,
      },
      {
        id: '550e8400-e29b-41d4-a716-446655440003',
        type: 'CALL_WEBHOOK',
        config: { url: 'https://c.example.com', method: 'POST' },
        displayOrder: 2,
      },
    ]

    let capturedDisplayOrder: number | undefined

    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: actionsWithGap })),
      http.post(BASE_PATH, async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        capturedDisplayOrder = body['displayOrder'] as number
        return HttpResponse.json(
          {
            data: {
              id: '550e8400-e29b-41d4-a716-446655440099',
              type: 'CALL_WEBHOOK',
              config: { url: 'https://new.example.com', method: 'POST' },
              displayOrder: capturedDisplayOrder,
            },
          },
          { status: 201 },
        )
      }),
    )

    renderSection()

    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: TX_KEY } })

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Webhook 추가/ }))
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'https://new.example.com/webhook' },
    })
    fireEvent.change(screen.getByLabelText(/메서드|Method/i), {
      target: { value: 'POST' },
    })

    fireEvent.click(screen.getByRole('button', { name: /추가/ }))

    await waitFor(() => expect(capturedDisplayOrder).toBe(3))
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
