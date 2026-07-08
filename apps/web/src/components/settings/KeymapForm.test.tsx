// KeymapForm 컴포넌트 테스트 — 키 캡처·leader 시퀀스·실시간 충돌 배지·기본값 복원·저장·서버 409 처리 (FR-PF-03 Task 9)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { keymapHandlers, resetKeymapStore } from '@/mocks/keymap-handlers'
import { KeymapForm } from './KeymapForm'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

function renderForm() {
  const { wrapper } = createWrapper()
  return render(<KeymapForm />, { wrapper })
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  resetKeymapStore()
  server.use(...keymapHandlers)
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 — 5종 action의 현재 키 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 렌더', () => {
  it('T1: 로딩 중에는 안내 문구가 렌더된다', () => {
    renderForm()
    expect(screen.getByText('단축키 설정을 불러오는 중입니다.')).toBeInTheDocument()
  })

  it('T2: 로딩 완료 후 5종 action 키 캡처 input이 기본값으로 렌더된다', async () => {
    renderForm()
    expect(await screen.findByLabelText('단축키 도움말 단축키 입력')).toHaveValue('?')
    expect(screen.getByLabelText('새 이슈 생성 단축키 입력')).toHaveValue('c')
    expect(screen.getByLabelText('검색으로 이동 단축키 입력')).toHaveValue('/')
    expect(screen.getByLabelText('내 이슈로 이동 단축키 입력')).toHaveValue('g i')
    expect(screen.getByLabelText('대시보드로 이동 단축키 입력')).toHaveValue('g d')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 키 캡처 — single / leader 시퀀스 / Esc / Enter 제외
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 키 캡처', () => {
  it('T3: 단일 키 입력 시 해당 action의 key_combo가 갱신된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'k' })

    expect(searchInput).toHaveValue('k')
  })

  it('T4: g 다음 x 입력 시 leader 시퀀스 "g x"로 갱신된다', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'g' })
    fireEvent.keyDown(createIssueInput, { key: 'x' })

    expect(createIssueInput).toHaveValue('g x')
  })

  it('T5: Escape 입력 시 대기 중인 leader 캡처가 취소된다(다음 키는 single로 취급)', async () => {
    renderForm()
    const createIssueInput = await screen.findByLabelText('새 이슈 생성 단축키 입력')

    fireEvent.keyDown(createIssueInput, { key: 'g' })
    fireEvent.keyDown(createIssueInput, { key: 'Escape' })
    fireEvent.keyDown(createIssueInput, { key: 'x' })

    expect(createIssueInput).toHaveValue('x')
  })

  it('T6: Enter 입력은 캡처 대상에서 제외되어 값이 바뀌지 않는다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'Enter' })

    expect(searchInput).toHaveValue('/')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 실시간 충돌 배지 — 완전중복 / dead leader
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 실시간 충돌 배지', () => {
  it('T7: 완전중복(search를 create-issue와 같은 "c"로) 시 배지가 즉시 표시된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')

    fireEvent.keyDown(searchInput, { key: 'c' })

    // 완전중복은 관련된 두 action(create-issue/search) 행 모두에 동일 메시지가 배지로 표시된다.
    const badges = await screen.findAllByText('"c"가 새 이슈 생성, 검색으로 이동에 중복 배정되었습니다.')
    expect(badges).toHaveLength(2)
  })

  it('T8: dead leader("g g") 시 배지가 즉시 표시된다', async () => {
    renderForm()
    const myIssuesInput = await screen.findByLabelText('내 이슈로 이동 단축키 입력')

    fireEvent.keyDown(myIssuesInput, { key: 'g' })
    fireEvent.keyDown(myIssuesInput, { key: 'g' })

    expect(myIssuesInput).toHaveValue('g g')
    expect(await screen.findByText('"g g"는 사용할 수 없습니다. (내 이슈로 이동)')).toBeInTheDocument()
  })

  it('T9: 충돌이 있으면 저장 버튼이 비활성화된다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'c' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '단축키 설정 저장' })).toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 기본값 복원
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 기본값 복원', () => {
  it('T10: action별 복원 버튼 클릭 시 DEFAULT_KEYMAP 값으로 되돌아간다', async () => {
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'k' })
    expect(searchInput).toHaveValue('k')

    fireEvent.click(screen.getByRole('button', { name: '검색으로 이동 기본값 복원' }))

    expect(searchInput).toHaveValue('/')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 저장 — mutate 호출(5종 replace-all)
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapForm — 저장', () => {
  it('T11: 저장 버튼 클릭 시 PATCH가 action 5종 완비 바디로 호출된다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/keymap', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({
          bindings: [
            { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
            { action: 'create-issue', keyCombo: 'c', trigger: 'single', customized: false },
            { action: 'search', keyCombo: 'k', trigger: 'single', customized: true },
            { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
            { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
          ],
        })
      }),
    )
    renderForm()
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'k' })

    fireEvent.click(screen.getByRole('button', { name: '단축키 설정 저장' }))

    await waitFor(() => {
      expect(capturedBody).toEqual({
        bindings: [
          { action: 'help', keyCombo: '?' },
          { action: 'create-issue', keyCombo: 'c' },
          { action: 'search', keyCombo: 'k' },
          { action: 'goto-my-issues', keyCombo: 'g i' },
          { action: 'goto-dashboard', keyCombo: 'g d' },
        ],
      })
    })
  })

  it('T12: 서버 409(KeymapConflictError) 응답 시 conflicts가 화면에 표시된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/keymap', () =>
        HttpResponse.json(
          {
            code: 'KEYMAP_CONFLICT',
            message: '겹치는 단축키가 있습니다.',
            conflicts: [{ type: 'duplicate', actions: ['create-issue', 'search'], keyCombo: 'x' }],
          },
          { status: 409 },
        ),
      ),
    )
    renderForm()
    // 로컬 검증을 통과하는 무해한 변경(고유 단일 키) — 서버가 409를 내려도 로컬은 충돌을 못 잡는
    // 시나리오를 재현해 저장 버튼이 활성 상태로 클릭 가능함을 보장한다.
    const searchInput = await screen.findByLabelText('검색으로 이동 단축키 입력')
    fireEvent.keyDown(searchInput, { key: 'x' })

    fireEvent.click(screen.getByRole('button', { name: '단축키 설정 저장' }))

    expect(await screen.findByText('겹치는 단축키가 있습니다.')).toBeInTheDocument()
    expect(
      await screen.findByText('"x"가 새 이슈 생성, 검색으로 이동에 중복 배정되었습니다.'),
    ).toBeInTheDocument()
  })
})
