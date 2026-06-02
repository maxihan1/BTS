// ComponentList 단위 테스트 — 4분기(로딩/에러/빈/목록) + 생성/수정 Dialog 연동 (FR-CM-01)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
import { componentLabels } from '@/i18n/component-labels'
import { ComponentList } from './ComponentList'

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
const PROJECT_UUID = '00000000-0000-4000-8000-000000000010'

const COMPONENT_A = {
  id: '00000000-0000-4000-8000-000000000021',
  projectId: PROJECT_UUID,
  name: 'Backend',
  description: '백엔드 서비스',
  leadUserId: null,
}

const COMPONENT_B = {
  id: '00000000-0000-4000-8000-000000000022',
  projectId: PROJECT_UUID,
  name: 'Frontend',
  description: '프론트엔드',
  leadUserId: null,
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

beforeEach(() => {
  resetComponentStore()
  vi.clearAllMocks()
  server.use(...componentHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentList — 4분기 렌더', () => {
  it('로딩 중에는 스켈레톤(role=status)을 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/components', async () => {
        await new Promise(() => { /* pending forever */ })
        return HttpResponse.json({ data: [] })
      }),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    expect(
      screen.getByRole('status', { name: componentLabels.page.loadingStatus }),
    ).toBeInTheDocument()
  })

  it('에러 발생 시 에러 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/components', () =>
        HttpResponse.json({ error: 'internal' }, { status: 500 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })
  })

  it('컴포넌트가 없으면 빈 상태 안내와 추가 버튼을 표시한다', async () => {
    // componentHandlers의 GET 핸들러 — 빈 목록 반환 (resetComponentStore 후 초기 상태)
    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(componentLabels.page.emptyMessage)).toBeInTheDocument()
    })

    expect(
      screen.getByRole('button', { name: componentLabels.actions.addButton }),
    ).toBeInTheDocument()
  })

  it('서버가 name 오름차순으로 내려준 목록을 그대로 렌더한다', async () => {
    // 서버(componentHandlers)가 정렬된 순서로 응답 — [Backend, Frontend] 순
    server.use(
      http.get('/api/v1/projects/:projectKey/components', () =>
        HttpResponse.json({
          data: [COMPONENT_A, COMPONENT_B], // Backend, Frontend (오름차순)
        }),
      ),
      http.get('/api/v1/users', () => HttpResponse.json({ data: [] })),
      http.get('/api/v1/users/batch', () => HttpResponse.json({ data: [] })),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })
    expect(screen.getByText('Frontend')).toBeInTheDocument()

    // Backend가 Frontend보다 DOM 앞에 위치하는지 확인
    const backendEl = screen.getByText('Backend')
    const frontendEl = screen.getByText('Frontend')
    const position = backendEl.compareDocumentPosition(frontendEl)
    // DOCUMENT_POSITION_FOLLOWING(4): frontendEl이 backendEl 뒤에 있음
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})

describe('ComponentList — "컴포넌트 추가" 버튼', () => {
  it('목록 있을 때도 "컴포넌트 추가" 버튼을 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/components', () =>
        HttpResponse.json({ data: [COMPONENT_A] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })

    expect(
      screen.getByRole('button', { name: componentLabels.actions.addButton }),
    ).toBeInTheDocument()
  })

  it('"컴포넌트 추가" 클릭 시 create 모드 Dialog가 열린다', async () => {
    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(componentLabels.page.emptyMessage)).toBeInTheDocument()
    })

    const user = userEvent.setup()
    const addButton = screen.getByRole('button', { name: componentLabels.actions.addButton })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    // 생성 모드 — 이름 필드가 빈 상태
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('')
  })

  it('생성 Dialog에서 저장 후 목록에 새 컴포넌트가 반영된다', async () => {
    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(componentLabels.page.emptyMessage)).toBeInTheDocument()
    })

    const user = userEvent.setup()
    // Dialog 열기
    const addButton = screen.getByRole('button', { name: componentLabels.actions.addButton })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // 이름 입력
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.type(nameInput, '새 컴포넌트', { delay: null })

    // 저장
    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    // 목록 반영 확인
    await waitFor(() => {
      expect(screen.getByText('새 컴포넌트')).toBeInTheDocument()
    })
  })
})

describe('ComponentList — 행 수정 버튼', () => {
  it('수정 버튼 클릭 시 edit 모드 Dialog가 열리고 initial 값이 prefill된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/components', () =>
        HttpResponse.json({ data: [COMPONENT_A] }),
      ),
    )
    // 유저 목록 조회 mock (ComponentLeadSelect용)
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json({ data: [] })),
      http.get('/api/v1/users/batch', () => HttpResponse.json({ data: [] })),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })

    const user = userEvent.setup()

    // 행 컨테이너에서 수정 버튼 탐색 — strict-mode 회피 (행마다 수정/삭제 버튼 존재)
    const backendRow = screen.getByText('Backend').closest('li')
    if (backendRow === null) throw new Error('Backend 행을 찾을 수 없습니다')

    const editButton = within(backendRow).getByRole('button', {
      name: `Backend ${componentLabels.actions.editButton}`,
    })
    await user.click(editButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // edit 모드 — 이름 prefill 확인
    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    expect(nameInput).toHaveValue('Backend')
  })

  it('수정 Dialog에서 저장 시 컴포넌트 이름이 갱신된다', async () => {
    // componentHandlers stateful 저장소에 컴포넌트 추가
    server.use(
      http.get('/api/v1/projects/:projectKey/components', () =>
        HttpResponse.json({ data: [COMPONENT_A] }),
      ),
    )
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json({ data: [] })),
      http.get('/api/v1/users/batch', () => HttpResponse.json({ data: [] })),
    )
    // PATCH 핸들러 — 수정 적용 + GET 재응답에 반영
    server.use(
      http.patch('/api/v1/projects/:projectKey/components/:id', async ({ request }) => {
        const body = (await request.json()) as { name?: string; description?: string }
        const updated = {
          ...COMPONENT_A,
          name: body.name ?? COMPONENT_A.name,
          description: body.description ?? COMPONENT_A.description,
        }
        // GET도 업데이트된 값 반환하도록 재등록
        server.use(
          http.get('/api/v1/projects/:projectKey/components', () =>
            HttpResponse.json({ data: [updated] }),
          ),
        )
        return HttpResponse.json({ data: updated })
      }),
    )

    const Wrapper = createWrapper()
    render(<ComponentList projectKey={PROJECT_KEY} />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    const backendRow = screen.getByText('Backend').closest('li')
    if (backendRow === null) throw new Error('Backend 행을 찾을 수 없습니다')

    const editButton = within(backendRow).getByRole('button', {
      name: `Backend ${componentLabels.actions.editButton}`,
    })
    await user.click(editButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    const nameInput = screen.getByRole('textbox', { name: /이름/ })
    await user.clear(nameInput)
    await user.type(nameInput, 'Backend v2', { delay: null })

    const saveButton = screen.getByRole('button', { name: '저장' })
    await user.click(saveButton)

    await waitFor(() => {
      expect(screen.getByText('Backend v2')).toBeInTheDocument()
    })
  })
})
