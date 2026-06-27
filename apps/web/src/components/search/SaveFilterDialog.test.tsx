// 저장 필터 생성/편집 Dialog 단위 테스트 (FR-SR-03 Task-3)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { SAVED_FILTER_ERROR_CODES } from '@/api/saved-filters'
import type { SavedFilterResponse } from '@/api/saved-filters'
import { savedFilterLabels } from '@/i18n/saved-filter-labels'
import { SaveFilterDialog } from './SaveFilterDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 (RFC4122 v4 UUID 형식 — Zod v4 엄격 검증 대응)
// ─────────────────────────────────────────────────────────────────────────────

const FILTER_ID = '00000000-0000-4000-8000-000000000001'
const OWNER_ID = '00000000-0000-4000-8000-000000000002'

const filterFixture: SavedFilterResponse = {
  id: FILTER_ID,
  ownerId: OWNER_ID,
  name: 'Old Name',
  aqlQuery: 'status = open',
  projectKey: 'PROJ',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  version: 3,
  isOwner: true,
  shares: [],
}

const createdResponse: SavedFilterResponse = {
  ...filterFixture,
  name: 'My Filter',
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

// ─────────────────────────────────────────────────────────────────────────────
// 셋업 / 정리
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'test-token',
    user: {
      userId: OWNER_ID,
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    },
  })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 생성 모드 — createFilter 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveFilterDialog — 생성 모드(a)', () => {
  it('name 입력 후 저장 시 POST /api/v1/filters에 {name, aqlQuery, projectKey} 전송', async () => {
    let capturedBody: unknown = null
    const onSaved = vi.fn()
    server.use(
      http.post('/api/v1/filters', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(createdResponse, { status: 201 })
      }),
    )

    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        aqlQuery="status = open"
        projectKey="PROJ"
        onSaved={onSaved}
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), 'My Filter')
    await user.click(screen.getByRole('button', { name: savedFilterLabels.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toEqual({
        name: 'My Filter',
        aqlQuery: 'status = open',
        projectKey: 'PROJ',
      })
    })
    expect(onSaved).toHaveBeenCalledWith(createdResponse)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 편집 모드 — 프리필 + updateFilter 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveFilterDialog — 편집 모드(b)', () => {
  it('filter.name이 이름 입력란에 프리필된다', () => {
    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        aqlQuery="status = open"
        projectKey="PROJ"
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByRole('textbox', { name: /이름/i })).toHaveValue('Old Name')
  })

  it('name 변경 후 저장 시 PUT /api/v1/filters/{id}에 {name, aqlQuery, version} 전송', async () => {
    let capturedBody: unknown = null
    const onSaved = vi.fn()
    server.use(
      http.put(`/api/v1/filters/${FILTER_ID}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ ...filterFixture, name: 'New Name' })
      }),
    )

    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        aqlQuery="status = open"
        projectKey="PROJ"
        onSaved={onSaved}
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    const input = screen.getByRole('textbox', { name: /이름/i })
    await user.clear(input)
    await user.type(input, 'New Name')
    await user.click(screen.getByRole('button', { name: savedFilterLabels.saveButton }))

    await waitFor(() => {
      expect(capturedBody).toEqual({
        name: 'New Name',
        aqlQuery: 'status = open',
        version: filterFixture.version,
      })
    })
    expect(onSaved).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 유효성 검사 — 제출 비활성(EC1)
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveFilterDialog — 유효성(c)', () => {
  it('빈 name → 저장 버튼 비활성(EC1)', () => {
    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        aqlQuery="status = open"
        projectKey="PROJ"
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByRole('button', { name: savedFilterLabels.saveButton })).toBeDisabled()
  })

  it('name 101자 → 저장 버튼 비활성(EC1)', () => {
    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        aqlQuery="status = open"
        projectKey="PROJ"
      />,
      { wrapper: createWrapper() },
    )

    // fireEvent로 HTML maxLength 제약 우회 — 101자 강제 입력
    fireEvent.change(screen.getByRole('textbox', { name: /이름/i }), {
      target: { value: 'a'.repeat(101) },
    })

    expect(screen.getByRole('button', { name: savedFilterLabels.saveButton })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) 409 NAME_CONFLICT → 인라인 오류(EC2)
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveFilterDialog — 이름 중복(d)', () => {
  it('409 SEARCH_FILTER_NAME_CONFLICT → 인라인 오류 표시(EC2)', async () => {
    server.use(
      http.post('/api/v1/filters', () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.NAME_CONFLICT, message: 'conflict' },
          { status: 409 },
        ),
      ),
    )

    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="create"
        aqlQuery="status = open"
        projectKey="PROJ"
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /이름/i }), 'Existing Name')
    await user.click(screen.getByRole('button', { name: savedFilterLabels.saveButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(savedFilterLabels.nameConflictError)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) 409 CONFLICT → 오류 표시 + onConflict(EC3)
// ─────────────────────────────────────────────────────────────────────────────

describe('SaveFilterDialog — OCC 충돌(e)', () => {
  it('409 SEARCH_FILTER_CONFLICT → 오류 표시 + onConflict 콜백(EC3)', async () => {
    const onConflict = vi.fn()
    server.use(
      http.put(`/api/v1/filters/${FILTER_ID}`, () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.CONFLICT, message: 'occ' },
          { status: 409 },
        ),
      ),
    )

    render(
      <SaveFilterDialog
        open={true}
        onOpenChange={vi.fn()}
        mode="edit"
        filter={filterFixture}
        aqlQuery="status = open"
        projectKey="PROJ"
        onConflict={onConflict}
      />,
      { wrapper: createWrapper() },
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: savedFilterLabels.saveButton }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(savedFilterLabels.conflictError)
      expect(onConflict).toHaveBeenCalledTimes(1)
    })
  })
})
