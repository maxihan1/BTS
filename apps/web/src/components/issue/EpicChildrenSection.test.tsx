// EpicChildrenSection 컴포넌트 단위 테스트 — FR-EP-01 D6 Task-4 TDD RED
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { EpicChildrenSection } from './EpicChildrenSection'

// sonner toast mock
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

/** 빈 자식 목록 응답 */
const emptyChildrenResponse = { data: { children: [] } }

/** 자식 이슈 목록 포함 응답 */
const childrenWithItemsResponse = {
  data: {
    children: [
      { key: 'ATLAS-2', summary: '로그인 페이지 구현', typeKey: 'task', currentStateKey: 'open' },
      { key: 'ATLAS-3', summary: '이슈 목록 구현', typeKey: 'story', currentStateKey: 'in_progress' },
    ],
  },
}

/** typeKey가 null인 자식 포함 응답 */
const childrenWithNullTypeResponse = {
  data: {
    children: [
      { key: 'ATLAS-4', summary: '타입 없는 이슈', typeKey: null, currentStateKey: 'open' },
    ],
  },
}

beforeEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 목록 렌더 — 자식 이슈 키 + 요약 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S1 목록 렌더', () => {
  it('S1a: 자식 이슈 키와 요약이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(childrenWithItemsResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    expect(await screen.findByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getByText('로그인 페이지 구현')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
    expect(screen.getByText('이슈 목록 구현')).toBeInTheDocument()
  })

  it('S1b: 자식 이슈 상태 칩이 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(childrenWithItemsResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    expect(await screen.findByText('open')).toBeInTheDocument()
    expect(screen.getByText('in_progress')).toBeInTheDocument()
  })

  it('S1c: typeKey가 null이면 타입 칩을 표시하지 않는다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(childrenWithNullTypeResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    expect(await screen.findByText('ATLAS-4')).toBeInTheDocument()
    // typeKey null이면 칩 없음 — null 텍스트 자체도 없어야 함
    expect(screen.queryByTestId('child-type-chip-ATLAS-4')).not.toBeInTheDocument()
  })

  it('S1d: 자식이 없으면 빈 상태 메시지가 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    expect(await screen.findByText('연결된 자식 이슈가 없습니다.')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 자식 추가 — POST 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S2 자식 추가', () => {
  it('S2a: 키 입력 후 추가 버튼 클릭 시 POST가 호출되고 목록이 갱신된다', async () => {
    const user = userEvent.setup()

    let getCallCount = 0
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json(emptyChildrenResponse)
        }
        return HttpResponse.json(childrenWithItemsResponse)
      }),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          {
            data: {
              key: 'ATLAS-2',
              summary: '로그인 페이지 구현',
              typeKey: 'task',
              currentStateKey: 'open',
            },
          },
          { status: 201 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')

    const input = screen.getByRole('textbox', { name: '자식 이슈 키' })
    await user.type(input, 'ATLAS-2')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    })
  })

  it('S2b: 입력이 비어 있으면 추가 버튼이 disabled다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    const btn = screen.getByRole('button', { name: '추가' })
    expect(btn).toBeDisabled()
  })

  it('S2c: 성공 시 toast.success가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          {
            data: {
              key: 'ATLAS-2',
              summary: '로그인 페이지 구현',
              typeKey: 'task',
              currentStateKey: 'open',
            },
          },
          { status: 201 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    const input = screen.getByRole('textbox', { name: '자식 이슈 키' })
    await user.type(input, 'ATLAS-2')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalledTimes(1)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 자식 해제 — DELETE 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S3 자식 해제', () => {
  it('S3a: 해제 버튼 클릭 시 DELETE가 호출되고 목록에서 제거된다', async () => {
    const user = userEvent.setup()

    let getCallCount = 0
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json(childrenWithItemsResponse)
        }
        return HttpResponse.json({
          data: {
            children: [
              { key: 'ATLAS-3', summary: '이슈 목록 구현', typeKey: 'story', currentStateKey: 'in_progress' },
            ],
          },
        })
      }),
      http.delete('/api/v1/issues/ATLAS-EPIC/epic-children/ATLAS-2', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('ATLAS-2')
    const disconnectBtn = screen.getByRole('button', { name: 'ATLAS-2 연결 해제' })
    await user.click(disconnectBtn)

    await waitFor(() => {
      expect(screen.queryByText('ATLAS-2')).not.toBeInTheDocument()
    })
  })

  it('S3b: 해제 성공 시 toast.success가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(childrenWithItemsResponse),
      ),
      http.delete('/api/v1/issues/ATLAS-EPIC/epic-children/ATLAS-2', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('ATLAS-2')
    await user.click(screen.getByRole('button', { name: 'ATLAS-2 연결 해제' }))

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalledTimes(1)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 권한 비활성 — disabled=true
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S4 권한 비활성', () => {
  it('S4a: disabled=true이면 추가 버튼과 해제 버튼이 disabled다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(childrenWithItemsResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={true} />, { wrapper: Wrapper })

    await screen.findByText('ATLAS-2')

    // 추가 버튼 disabled
    expect(screen.getByRole('button', { name: '추가' })).toBeDisabled()
    // 해제 버튼 disabled
    expect(screen.getByRole('button', { name: 'ATLAS-2 연결 해제' })).toBeDisabled()
  })

  it('S4b: disabled=true이면 자식 키 입력 필드가 disabled다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={true} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    expect(screen.getByRole('textbox', { name: '자식 이슈 키' })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 인라인 에러 — errorCode 한국어 메시지
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S5 인라인 에러 한국어 메시지', () => {
  it('S5a: ISSUE_EPIC_CHILD_ALREADY_LINKED(409) — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_ALREADY_LINKED', message: '이미 연결됨' },
          { status: 409 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'ATLAS-5')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('이미 이 에픽에 연결된 이슈입니다.')
    })
  })

  it('S5b: ISSUE_EPIC_CHILD_INVALID_TYPE(422) — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_INVALID_TYPE', message: '타입 불가' },
          { status: 422 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'ATLAS-EPIC2')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('에픽 타입 이슈는 자식이 될 수 없습니다.')
    })
  })

  it('S5c: ISSUE_EPIC_CHILD_CROSS_PROJECT(422) — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_CROSS_PROJECT', message: '다른 프로젝트' },
          { status: 422 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'OTHER-1')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('같은 프로젝트의 이슈만 자식으로 연결할 수 있습니다.')
    })
  })

  it('S5d: ISSUE_EPIC_CHILD_SELF_REFERENCE(422) — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_SELF_REFERENCE', message: '자기참조' },
          { status: 422 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'ATLAS-EPIC')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('자기 자신을 자식으로 연결할 수 없습니다.')
    })
  })

  it('S5e: ISSUE_EPIC_OR_CHILD_NOT_FOUND(404) — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: '없음' },
          { status: 404 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'ATLAS-99')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('이슈를 찾을 수 없습니다.')
    })
  })

  it('S5f: 400 ISSUE_EPIC_VALIDATION_FAILED — 인라인 에러 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_VALIDATION_FAILED', message: '유효성 오류' },
          { status: 400 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), '!!!')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('입력 값이 올바르지 않습니다.')
    })
  })

  it('S5g: 인라인 에러는 role="alert"를 가진다', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
      http.post('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_ALREADY_LINKED', message: '이미 연결됨' },
          { status: 409 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    await user.type(screen.getByRole('textbox', { name: '자식 이슈 키' }), 'ATLAS-5')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 섹션 제목 + data-testid
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicChildrenSection — S6 섹션 제목 + testid', () => {
  it('S6a: 섹션 제목 "자식 이슈"가 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    expect(screen.getByText('자식 이슈')).toBeInTheDocument()
  })

  it('S6b: data-testid="epic-children-section"이 존재한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-EPIC/epic-children', () =>
        HttpResponse.json(emptyChildrenResponse),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicChildrenSection epicKey="ATLAS-EPIC" disabled={false} />, { wrapper: Wrapper })

    await screen.findByText('연결된 자식 이슈가 없습니다.')
    expect(screen.getByTestId('epic-children-section')).toBeInTheDocument()
  })
})
