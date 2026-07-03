// 퀵필터 저장/편집 Dialog 단위 테스트 (FR-UX-01 Task 9)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { QuickFilter } from '@/api/board-quick-filters'
import { quickFilterLabels } from '@/i18n/quick-filter-labels'
import { SaveQuickFilterDialog } from './SaveQuickFilterDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 (RFC4122 v4 UUID 형식 — Zod v4 엄격 검증 대응, zod-v4-uuid-fixture-strictness)
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const FILTER_ID = 'f1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

const filterFixture: QuickFilter = {
  filterId: FILTER_ID,
  name: '내 버그',
  query: 'assignee=abc&label=bug',
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient 래퍼 팩토리 — 테스트 간 캐시 격리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: qc }, children)
  }
}

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 생성 모드 — createQuickFilter 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — 생성 모드(a)', () => {
  it('name 입력 후 저장 시 POST .../quick-filters에 {name, query} 전송', async () => {
    let capturedBody: unknown = null
    const onSaved = vi.fn()
    server.use(
      http.post(`/api/v1/boards/${BOARD_ID}/quick-filters`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: { ...filterFixture, name: '내 버그' } }, { status: 201 })
      }),
    )

    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery="assignee=abc&label=bug"
        onSaved={onSaved}
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), '내 버그')
    await user.click(screen.getByRole('button', { name: quickFilterLabels.form.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toEqual({ name: '내 버그', query: 'assignee=abc&label=bug' })
    })
    expect(onSaved).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 편집 모드 — 프리필 + updateQuickFilter 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — 편집 모드(b)', () => {
  it('filter.name이 이름 입력란에 프리필된다', () => {
    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        boardId={BOARD_ID}
        currentQuery={filterFixture.query}
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByRole('textbox', { name: /이름/i })).toHaveValue('내 버그')
  })

  it('name 변경 후 저장 시 PATCH .../quick-filters/{filterId}에 {name, query} 전송', async () => {
    let capturedBody: unknown = null
    const onSaved = vi.fn()
    server.use(
      http.patch(`/api/v1/boards/${BOARD_ID}/quick-filters/${FILTER_ID}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: { ...filterFixture, name: '긴급 버그' } })
      }),
    )

    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        boardId={BOARD_ID}
        currentQuery={filterFixture.query}
        onSaved={onSaved}
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    const input = screen.getByRole('textbox', { name: /이름/i })
    await user.clear(input)
    await user.type(input, '긴급 버그')
    await user.click(screen.getByRole('button', { name: quickFilterLabels.form.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toEqual({ name: '긴급 버그', query: filterFixture.query })
    })
    expect(onSaved).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 유효성 검사 — 제출 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — 유효성(c)', () => {
  it('빈 name → 저장 버튼 비활성', () => {
    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery="assignee=abc"
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByRole('button', { name: quickFilterLabels.form.saveButton })).toBeDisabled()
  })

  it('name 51자 → 저장 버튼 비활성', () => {
    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery="assignee=abc"
      />,
      { wrapper: createWrapper() },
    )

    // fireEvent로 HTML maxLength 제약 우회 — 51자 강제 입력
    fireEvent.change(screen.getByRole('textbox', { name: /이름/i }), {
      target: { value: 'a'.repeat(51) },
    })

    expect(screen.getByRole('button', { name: quickFilterLabels.form.saveButton })).toBeDisabled()
  })

  it('EC1: currentQuery가 빈 문자열이면 name을 입력해도 저장 버튼이 비활성이다', async () => {
    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery=""
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), '전체 보기')

    expect(screen.getByRole('button', { name: quickFilterLabels.form.saveButton })).toBeDisabled()
    expect(screen.getByText(quickFilterLabels.form.emptyQueryHint)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) 409 이름 중복 → 인라인 오류(EC2)
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — 이름 중복(d)', () => {
  it('409 → 인라인 오류 표시(EC2)', async () => {
    server.use(
      http.post(`/api/v1/boards/${BOARD_ID}/quick-filters`, () =>
        HttpResponse.json({ errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT' }, { status: 409 }),
      ),
    )

    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery="assignee=abc"
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), '기존 이름')
    await user.click(screen.getByRole('button', { name: quickFilterLabels.form.saveButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(quickFilterLabels.errors.nameConflict)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) 400 잘못된 query → 인라인 오류(EC4)
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — 잘못된 query(e)', () => {
  it('400 → 인라인 오류 표시(EC4)', async () => {
    server.use(
      http.post(`/api/v1/boards/${BOARD_ID}/quick-filters`, () =>
        HttpResponse.json({ errorCode: 'AGILE_VALIDATION_FAILED' }, { status: 400 }),
      ),
    )

    render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        boardId={BOARD_ID}
        currentQuery="assignee=abc"
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), '새 필터')
    await user.click(screen.getByRole('button', { name: quickFilterLabels.form.saveButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(quickFilterLabels.errors.invalidQuery)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) filterId key 재마운트 — react-usestate-stale-key-prop
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveQuickFilterDialog — filterId 변경 시 stale state 방지(f)', () => {
  it('편집 대상 filter가 바뀌면 이름 입력값이 새 filter.name으로 갱신된다', () => {
    const otherFilter: QuickFilter = {
      filterId: 'a2b3c4d5-e6f7-4a8b-9c0d-e1f2a3b4c5d6',
      name: '다른 필터',
      query: 'label=feature',
    }

    const { rerender } = render(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        boardId={BOARD_ID}
        currentQuery={filterFixture.query}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('textbox', { name: /이름/i })).toHaveValue('내 버그')

    rerender(
      <SaveQuickFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={otherFilter}
        boardId={BOARD_ID}
        currentQuery={otherFilter.query}
      />,
    )

    expect(screen.getByRole('textbox', { name: /이름/i })).toHaveValue('다른 필터')
  })
})
