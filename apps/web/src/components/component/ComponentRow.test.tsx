// ComponentRow 단위 테스트 — 렌더/수정콜백/삭제확인/aria-label/leadUserId null 분기
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
import type { Component } from '@/api/components.types'
import { ComponentRow } from './ComponentRow'

// ─────────────────────────────────────────────────────────────────────────────
// sonner mock — 토스트 중복 발사 검증용 (행에서 쏘면 FAIL)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** leadUserId가 지정된 컴포넌트 픽스처 */
const componentWithLead: Component = {
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectId: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
  name: 'Frontend',
  description: '프론트엔드 컴포넌트',
  leadUserId: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f', // userAliceFixture.id
}

/** leadUserId가 null인 컴포넌트 픽스처 */
const componentNoLead: Component = {
  id: 'b2c3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6',
  projectId: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
  name: 'Backend',
  description: null,
  leadUserId: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
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
  // componentHandlers + 기본 users 핸들러 등록 (빈 쿼리 MSW 매칭 없음 방지)
  server.use(
    ...componentHandlers,
    http.get('/api/v1/users', () => HttpResponse.json([])),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentRow — 렌더', () => {
  it('컴포넌트 이름과 설명을 표시한다', async () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText('Frontend')).toBeInTheDocument()
    expect(screen.getByText('프론트엔드 컴포넌트')).toBeInTheDocument()
  })

  it('leadUserId가 있으면 사용자 이름을 표시한다 (useUsersByIds 조회)', async () => {
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        const url = new URL(request.url)
        const ids = url.searchParams.get('ids')
        if (ids !== null) {
          return HttpResponse.json([
            {
              id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
              username: 'alice',
              displayName: '김앨리스',
              email: null,
            },
          ])
        }
        return HttpResponse.json([])
      }),
    )

    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByText('김앨리스')).toBeInTheDocument()
    })
  })

  it('leadUserId가 null이면 "미지정"을 표시한다', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentNoLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText('미지정')).toBeInTheDocument()
  })

  it('설명이 null이면 설명 영역을 표시하지 않는다', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentNoLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    // description이 null이므로 "프론트엔드 컴포넌트" 같은 텍스트 없음
    expect(screen.queryByText('프론트엔드 컴포넌트')).not.toBeInTheDocument()
  })
})

describe('ComponentRow — 수정 버튼', () => {
  it('수정 버튼 클릭 시 onEdit(component)를 호출한다', async () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    // 행 컨테이너 한정 셀렉터 — strict mode 회피
    const row = screen.getByRole('listitem')
    const editBtn = within(row).getByRole('button', {
      name: `${componentWithLead.name} 수정`,
    })
    await user.click(editBtn)

    expect(onEdit).toHaveBeenCalledWith(componentWithLead)
    expect(onEdit).toHaveBeenCalledTimes(1)
  })
})

describe('ComponentRow — 삭제 액션', () => {
  it('삭제 버튼 클릭 후 확인 시 DELETE API를 호출한다', async () => {
    // MSW에 컴포넌트를 미리 등록
    server.use(
      http.delete(
        `/api/v1/projects/${PROJECT_KEY}/components/${componentWithLead.id}`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    )

    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${componentWithLead.name} 삭제`,
    })
    await user.click(deleteBtn)

    // 삭제 확인 UI가 나타나면 확인 버튼 클릭
    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    const confirmBtn = screen.getByRole('button', { name: '삭제' })
    await user.click(confirmBtn)

    // DELETE 요청이 발생하면 성공 (목록 invalidate 후 재조회)
    await waitFor(() => {
      // 확인 UI가 사라지면 mutation이 완료된 것
      expect(screen.queryByText('정말 삭제하시겠습니까?')).not.toBeInTheDocument()
    })
  })

  it('삭제 취소 시 DELETE API를 호출하지 않는다', async () => {
    const deleteSpy = vi.fn()
    server.use(
      http.delete(
        `/api/v1/projects/${PROJECT_KEY}/components/${componentWithLead.id}`,
        () => {
          deleteSpy()
          return new HttpResponse(null, { status: 204 })
        },
      ),
    )

    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()
    const row = screen.getByRole('listitem')
    const deleteBtn = within(row).getByRole('button', {
      name: `${componentWithLead.name} 삭제`,
    })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    const cancelBtn = screen.getByRole('button', { name: '취소' })
    await user.click(cancelBtn)

    expect(deleteSpy).not.toHaveBeenCalled()
    expect(screen.queryByText('정말 삭제하시겠습니까?')).not.toBeInTheDocument()
  })
})

describe('ComponentRow — aria-label', () => {
  it('수정 버튼의 aria-label에 컴포넌트 이름이 포함된다', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    expect(
      screen.getByRole('button', { name: `${componentWithLead.name} 수정` }),
    ).toBeInTheDocument()
  })

  it('삭제 버튼의 aria-label에 컴포넌트 이름이 포함된다', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    expect(
      screen.getByRole('button', { name: `${componentWithLead.name} 삭제` }),
    ).toBeInTheDocument()
  })
})

describe('ComponentRow — 인라인 리드 변경', () => {
  it('ComponentLeadSelect를 통해 리드를 변경하면 changeComponentLead mutation을 호출한다', async () => {
    const patchLeadSpy = vi.fn()
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        const url = new URL(request.url)
        const ids = url.searchParams.get('ids')
        if (ids !== null) {
          return HttpResponse.json([
            {
              id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
              username: 'alice',
              displayName: '김앨리스',
              email: null,
            },
          ])
        }
        // 검색 결과
        return HttpResponse.json([
          {
            id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
            username: 'bob',
            displayName: null,
            email: null,
          },
        ])
      }),
      http.patch(
        `/api/v1/projects/${PROJECT_KEY}/components/${componentWithLead.id}/lead`,
        async ({ request }) => {
          const body = await request.json()
          patchLeadSpy(body)
          return HttpResponse.json({
            data: { ...componentWithLead, leadUserId: (body as { leadUserId: string }).leadUserId },
          })
        },
      ),
    )

    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    const user = userEvent.setup()

    // 리드 검색 input에 입력
    const searchInput = screen.getByRole('textbox', { name: '리드 검색' })
    await user.type(searchInput, 'bob')

    // 검색 결과에서 bob 선택
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'bob' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'bob' }))

    await waitFor(() => {
      expect(patchLeadSpy).toHaveBeenCalledWith({
        leadUserId: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
      })
    })
  })

  it('canManage=false이면 리드 검색 input이 비활성화된다(fail-closed)', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={false}
      />,
      { wrapper: Wrapper },
    )

    const searchInput = screen.getByRole('textbox', { name: '리드 검색' })
    expect(searchInput).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ComponentRow — 권한 게이팅 (FR-PM-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentRow — 권한 게이팅', () => {
  it('canManage=true이면 수정/삭제 버튼이 활성화된다', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={true}
      />,
      { wrapper: Wrapper },
    )

    const row = screen.getByRole('listitem')
    expect(within(row).getByRole('button', { name: `${componentWithLead.name} 수정` })).not.toBeDisabled()
    expect(within(row).getByRole('button', { name: `${componentWithLead.name} 삭제` })).not.toBeDisabled()
  })

  it('canManage=false이면 수정/삭제 버튼이 비활성화된다(fail-closed)', () => {
    const onEdit = vi.fn()
    const Wrapper = createWrapper()
    render(
      <ComponentRow
        component={componentWithLead}
        projectKey={PROJECT_KEY}
        onEdit={onEdit}
        canManage={false}
      />,
      { wrapper: Wrapper },
    )

    const row = screen.getByRole('listitem')
    expect(within(row).getByRole('button', { name: `${componentWithLead.name} 수정` })).toBeDisabled()
    expect(within(row).getByRole('button', { name: `${componentWithLead.name} 삭제` })).toBeDisabled()
  })
})
