// IssueCreateForm 신규 필드 단위 테스트 — 프로젝트 셀렉터·유형·본문·제목 상한 (FR-UX-09 F2 T5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { LS_KEY_PROJECT_LIST_EMPTY } from '@/mocks/project-handlers'
import { IssueCreateForm } from '@/components/issue/IssueCreateForm'
import { issueCreateStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

// useNavigate mock — 폼은 라우터 비의존이지만 하위 컴포넌트가 쓸 수 있어 방어적으로 둔다
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({}),
  useSearch: () => ({}),
}))

// useCustomFields mock — 네트워크 없이 커스텀필드 정의 제어 (형제 issues.new.test.tsx 관례)
vi.mock('@/hooks/use-custom-fields', () => ({
  useCustomFields: vi.fn(),
  CUSTOM_FIELD_KEYS: { list: (k: string) => ['custom-fields', k] },
}))

import { useCustomFields } from '@/hooks/use-custom-fields'

const EMPTY_CUSTOM_FIELDS_RESULT = {
  data: [] as CustomField[],
  isLoading: false,
  isError: false,
  isPending: false,
  isSuccess: true,
  error: null,
  status: 'success' as const,
  fetchStatus: 'idle' as const,
  dataUpdatedAt: 0,
  errorUpdatedAt: 0,
  failureCount: 0,
  failureReason: null,
  isFetched: true,
  isFetchedAfterMount: true,
  isFetching: false,
  isInitialLoading: false,
  isLoadingError: false,
  isPlaceholderData: false,
  isRefetchError: false,
  isRefetching: false,
  isStale: false,
  refetch: vi.fn(),
}

beforeEach(() => {
  vi.mocked(useCustomFields).mockReturnValue(EMPTY_CUSTOM_FIELDS_RESULT as never)
  localStorage.clear()
})

/** 폼을 QueryClient 로 감싸 렌더한다. */
function renderForm(onSuccess?: (key: string) => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <IssueCreateForm onSuccess={onSuccess} />
    </QueryClientProvider>,
  )
}

/** 프로젝트 셀렉터가 옵션을 받아 렌더될 때까지 기다린다. */
async function waitForProjectSelect(): Promise<HTMLSelectElement> {
  return await waitFor(() => {
    const el = screen.getByLabelText(issueCreateStrings.projectKeyLabel) as HTMLSelectElement
    expect(el.querySelectorAll('option').length).toBeGreaterThan(1)
    return el
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// FR-3 — 프로젝트를 자유 텍스트가 아니라 셀렉터로 고른다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 프로젝트 셀렉터 (FR-3)', () => {
  it('프로젝트가 자유 텍스트 입력이 아니라 셀렉터로 렌더된다', async () => {
    renderForm()

    const select = await waitForProjectSelect()
    expect(select.tagName).toBe('SELECT')
  })

  it('접근 가능한 프로젝트가 옵션으로 나온다', async () => {
    renderForm()

    const select = await waitForProjectSelect()
    const optionValues = Array.from(select.querySelectorAll('option')).map((o) => o.value)
    expect(optionValues).toContain('ATLAS')
    expect(optionValues).toContain('MIDDLE')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-17 — 접근 가능한 프로젝트가 0개면 폼 대신 빈 상태 (design 리뷰 D9)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 프로젝트 0개 빈 상태 (FR-17)', () => {
  it('프로젝트가 없으면 폼 대신 빈 상태 안내가 나온다', async () => {
    localStorage.setItem(LS_KEY_PROJECT_LIST_EMPTY, 'true')
    renderForm()

    expect(await screen.findByText(issueCreateStrings.noProjectsTitle)).toBeInTheDocument()
    // 폼 제출 버튼 자체가 없어야 한다 — 채울 수 없는 폼을 보여주지 않는다
    expect(screen.queryByRole('button', { name: issueCreateStrings.submitButton })).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-4 / FR-5 — 이슈 유형 · 본문
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 이슈 유형과 본문 (FR-4/FR-5)', () => {
  it('이슈 유형 셀렉터가 렌더되고 기본값이 task 다', async () => {
    renderForm()

    const typeSelect = await waitFor(() => {
      const el = screen.getByLabelText(issueCreateStrings.typeLabel) as HTMLSelectElement
      expect(el.querySelectorAll('option').length).toBeGreaterThan(0)
      return el
    })
    // task fixture 의 id — 서버 fallback(typeId 미전달 시 task)과 같은 기본값
    const taskOption = Array.from(typeSelect.querySelectorAll('option')).find(
      (o) => o.textContent === '작업',
    )
    expect(typeSelect.value).toBe(taskOption?.value)
  })

  it('본문에 입력한 값이 제출 본문 description 으로 실린다', async () => {
    const user = userEvent.setup()
    let captured: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        captured = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: { key: 'ATLAS-1' } }, { status: 201 })
      }),
    )
    renderForm()

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.type(screen.getByLabelText(issueCreateStrings.descriptionLabel), '본문입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(captured['description']).toBe('본문입니다'))
  })

  it('본문 칸에 템플릿 안내가 보인다 (FR-TM-01)', async () => {
    renderForm()

    expect(await screen.findByText(issueCreateStrings.descriptionTemplateHint)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// B-2 (선재 결함 정렬) — 제목 상한이 백엔드와 같은 200
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 제목 상한 200 (B-2 선재 결함 정렬)', () => {
  it('201자 제목은 폼 검증에서 거부된다 — 백엔드 @Size(max=200) 와 정렬', async () => {
    const user = userEvent.setup()
    renderForm()

    await waitForProjectSelect()
    const summary = screen.getByLabelText(issueCreateStrings.summaryLabel)
    // paste 로 넣는다 — 201자를 한 글자씩 타이핑하면 테스트가 매우 느려진다
    await user.click(summary)
    await user.paste('a'.repeat(201))
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    expect(await screen.findByText(issueCreateStrings.summaryTooLong)).toBeInTheDocument()
  })
})
