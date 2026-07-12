// 프로젝트 멤버 선택 드롭다운 테스트 — 멤버 렌더/선택/담당자 해제/미해석 fallback/로딩·에러 (FR-AT-02 D6 Task 3)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { server } from '@/test/server'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import { ProjectMemberSelect } from './ProjectMemberSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — atlasInitialMembers(alice=PROJECT_ADMIN, bob=MEMBER) 재사용
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const UNKNOWN_ID = 'ffffffff-ffff-4fff-8fff-ffffffffffff'

function renderWithClient(ui: JSX.Element) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(ui, {
    wrapper: ({ children }: { readonly children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  })
}

beforeEach(() => {
  server.use(...projectMemberHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 멤버 목록 렌더 + 선택
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectMemberSelect — 멤버 목록', () => {
  it('프로젝트 멤버 목록을 select 옵션으로 렌더한다 (displayName 표시)', async () => {
    renderWithClient(
      <ProjectMemberSelect projectKey={PROJECT_KEY} value={null} onChange={vi.fn()} label="담당자" />,
    )

    await waitFor(() => {
      expect(screen.getByRole('option', { name: '앨리스' })).toBeInTheDocument()
    })
    expect(screen.getByRole('option', { name: '밥' })).toBeInTheDocument()
  })

  it('멤버 선택 시 onChange(userId)를 호출한다', async () => {
    const onChange = vi.fn()
    renderWithClient(
      <ProjectMemberSelect projectKey={PROJECT_KEY} value={null} onChange={onChange} label="담당자" />,
    )
    const user = userEvent.setup()

    const select = await screen.findByLabelText('담당자')
    const aliceOption = await screen.findByRole('option', { name: '앨리스' })
    await user.selectOptions(select, aliceOption)

    expect(onChange).toHaveBeenCalledWith(ALICE_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// allowUnassign — "담당자 해제"(value=null) 옵션 토글
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectMemberSelect — allowUnassign', () => {
  it('allowUnassign=true면 "담당자 해제" 옵션이 노출되고 선택 시 onChange(null)을 호출한다', async () => {
    const onChange = vi.fn()
    renderWithClient(
      <ProjectMemberSelect
        projectKey={PROJECT_KEY}
        value={ALICE_ID}
        onChange={onChange}
        allowUnassign
        label="담당자"
      />,
    )
    const user = userEvent.setup()

    const select = await screen.findByLabelText('담당자')
    const unassignOption = await screen.findByRole('option', { name: '담당자 해제' })
    await user.selectOptions(select, unassignOption)

    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('allowUnassign 미지정(기본값)이면 "담당자 해제" 옵션이 없다', async () => {
    renderWithClient(
      <ProjectMemberSelect projectKey={PROJECT_KEY} value={null} onChange={vi.fn()} label="실행 주체" />,
    )

    await waitFor(() => {
      expect(screen.getByRole('option', { name: '앨리스' })).toBeInTheDocument()
    })
    expect(screen.queryByRole('option', { name: '담당자 해제' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// EC4 — value가 멤버 목록에 없을 때 "알 수 없는 사용자" fallback (값 보존)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectMemberSelect — 알 수 없는 사용자 fallback (EC4)', () => {
  it('value가 멤버 목록에 없으면 "알 수 없는 사용자"로 표시하되 값을 보존한다', async () => {
    const onChange = vi.fn()
    renderWithClient(
      <ProjectMemberSelect projectKey={PROJECT_KEY} value={UNKNOWN_ID} onChange={onChange} label="실행 주체" />,
    )

    const select = await screen.findByLabelText('실행 주체')
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '알 수 없는 사용자' })).toBeInTheDocument()
    })
    expect((select as HTMLSelectElement).value).toBe(UNKNOWN_ID)
    expect(onChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩/에러 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectMemberSelect — 로딩/에러 상태', () => {
  it('멤버 목록 로딩 중에는 select가 비활성화된 상태로 렌더된다', () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ members: [] })
      }),
    )

    renderWithClient(
      <ProjectMemberSelect projectKey={PROJECT_KEY} value={null} onChange={vi.fn()} label="담당자" />,
    )

    expect(screen.getByLabelText('담당자')).toBeDisabled()
  })

  it('멤버 목록 조회 실패 시 빈 목록으로 은폐하지 않고 에러를 안내한다', async () => {
    renderWithClient(
      <ProjectMemberSelect projectKey="NONEXISTENT" value={null} onChange={vi.fn()} label="담당자" />,
    )

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('담당자')).toBeDisabled()
  })
})
