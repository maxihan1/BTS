// IssueCreateForm 신규 필드 단위 테스트 — 프로젝트 셀렉터·유형·본문·제목 상한 (FR-UX-09 F2 T5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectHandlers, LS_KEY_PROJECT_LIST_EMPTY } from '@/mocks/project-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { issueHandlers } from '@/mocks/issue-handlers'
// 담당자 검색(GET /api/v1/users) — IssueAssigneeSelect 후보 목록의 출처
import { userHandlers } from '@/mocks/user-handlers'
// 고른 담당자 id 단언용 — user-fixtures 의 alice(표시 이름 '김앨리스') id
import { ALICE_USER_ID } from '@/mocks/auth-fixtures'
import { IssueCreateForm } from '@/components/issue/IssueCreateForm'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
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
  server.use(...issueHandlers, ...projectHandlers, ...issueTypeHandlers, ...userHandlers)
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
      const el = screen.getByLabelText(issueDetailStrings.typeSelectLabel) as HTMLSelectElement
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

// ─────────────────────────────────────────────────────────────────────────────
// FR-6/FR-7/FR-8 — 담당자 3-state · 우선순위 · 라벨 (T6)
//
// 3-state 는 화면상 「비어 있음」이 두 가지 뜻을 갖는다는 게 핵심이다.
// 안 건드림 = 서버 자동 배정 유지 / 해제 = 자동 배정 끄고 미할당 확정.
// 요청 본문의 **키 존재 여부**로만 구분되므로 본문을 직접 캡처해 단언한다.
// ─────────────────────────────────────────────────────────────────────────────

/** POST /api/v1/issues 본문을 캡처하는 핸들러를 등록한다. */
function captureSubmitBody(): { get: () => Record<string, unknown> } {
  let captured: Record<string, unknown> = {}
  server.use(
    http.post('/api/v1/issues', async ({ request }) => {
      captured = await request.json() as Record<string, unknown>
      return HttpResponse.json({ data: { key: 'ATLAS-1' } }, { status: 201 })
    }),
  )
  return { get: () => captured }
}

/** 제목만 채우고 제출한다 (프로젝트는 활성 프로젝트가 기본 선택돼 있다). */
async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await waitForProjectSelect()
  await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
  await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))
}

describe('IssueCreateForm — 담당자 3-state (FR-6)', () => {
  it('담당자를 한 번도 건드리지 않으면 본문에 assigneeId 키가 없다 (자동 배정 유지)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    await fillAndSubmit(user)

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
    expect('assigneeId' in body.get()).toBe(false)
  })

  it('담당자를 고른 뒤 해제하면 본문에 assigneeId=null 이 실린다 (미할당 확정)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    await waitForProjectSelect()
    // 검색 → 후보 선택 → 해제. 「해제」 버튼은 담당자가 있을 때만 노출된다.
    await user.type(
      screen.getByLabelText(issueDetailStrings.assigneeSearchPlaceholder),
      'al',
    )
    // user-fixtures 의 alice 는 displayName '김앨리스' 라 버튼 접근성 이름이 그 값이다
    const candidate = await screen.findByRole('button', { name: '김앨리스' })
    await user.click(candidate)
    await user.click(
      await screen.findByRole('button', { name: issueDetailStrings.assigneeUnassignButton }),
    )

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect('assigneeId' in body.get()).toBe(true))
    expect(body.get()['assigneeId']).toBeNull()
  })

  it('담당자 칸에 자동 배정 안내가 보인다', async () => {
    renderForm()

    expect(await screen.findByText(issueCreateStrings.assigneeAutoHint)).toBeInTheDocument()
  })
})

describe('IssueCreateForm — 우선순위와 라벨 (FR-7/FR-8)', () => {
  it('우선순위 기본값이 3(보통)이다', async () => {
    renderForm()

    const select = await screen.findByLabelText(issueDetailStrings.prioritySelectLabel)
    expect((select as HTMLSelectElement).value).toBe('3')
  })

  it('우선순위를 바꾸면 본문에 그 값이 실린다', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    await waitForProjectSelect()
    await user.selectOptions(
      await screen.findByLabelText(issueDetailStrings.prioritySelectLabel),
      '1',
    )
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['priority']).toBe(1))
  })

  it('라벨을 추가하면 본문 labels 에 실린다', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    await waitForProjectSelect()
    await user.type(
      screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder),
      'backend{Enter}',
    )
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['labels']).toEqual(['backend']))
  })

  it('★폼 안에 라벨 「저장」 버튼이 없다 — 「이슈 생성」과 헷갈리면 안 된다', async () => {
    renderForm()

    await waitForProjectSelect()
    expect(screen.queryByRole('button', { name: issueDetailStrings.labelsSaveButton })).toBeNull()
  })
})

describe('IssueCreateForm — 422 ASSIGNEE_NOT_FOUND (스펙 §API 오류 계약)', () => {
  it('422 응답 시 담당자를 찾을 수 없다는 에러가 보인다', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/issues', () =>
        HttpResponse.json({ errorCode: 'ASSIGNEE_NOT_FOUND' }, { status: 422 }),
      ),
    )
    renderForm()

    await fillAndSubmit(user)

    expect(
      await screen.findByText(issueCreateStrings.errorAssigneeNotFound),
    ).toBeInTheDocument()
  })
})

describe('IssueCreateForm — 필드 3덩어리 구분 (FR-15, design 리뷰 D7)', () => {
  it('기본·배정·추가 소제목과 구분선 2개가 렌더된다', async () => {
    const { container } = renderForm()

    await waitForProjectSelect()
    expect(screen.getByText(issueCreateStrings.groupBasicLabel)).toBeInTheDocument()
    expect(screen.getByText(issueCreateStrings.groupAssignmentLabel)).toBeInTheDocument()
    expect(screen.getByText(issueCreateStrings.groupExtraLabel)).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="separator"]').length).toBe(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 눈확인이 잡은 결함 — 검색 전에 사용자 목록이 통째로 뜬다
//
// `useUsers('')` 는 **전체 사용자 목록**을 돌려준다. 그대로 넘기면 아직 아무것도 검색하지
// 않았는데 후보가 쌓여 모달 세로를 잡아먹는다. 1,000명 규모에서는 더 나쁘다.
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 담당자 후보는 검색해야 나온다', () => {
  it('검색어가 비어 있으면 사용자 후보 목록이 렌더되지 않는다', async () => {
    renderForm()

    await waitForProjectSelect()
    // user-fixtures 의 alice 표시 이름
    expect(screen.queryByRole('button', { name: '김앨리스' })).toBeNull()
  })

  it('검색어를 입력하면 후보가 나온다', async () => {
    const user = userEvent.setup()
    renderForm()

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueDetailStrings.assigneeSearchPlaceholder), 'al')

    expect(await screen.findByRole('button', { name: '김앨리스' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 게이트 2 B-1 — 담당자 검색창 Enter 가 폼을 제출하면 안 된다
//
// `IssueAssigneeSelect` 의 검색 input 은 이슈 상세에서는 `<form>` 밖이라 Enter 가 무해했다.
// F2 가 **처음으로 폼 안에** 넣으면서 HTML 암묵적 제출(implicit submission)이 살아났다.
// 사용자가 이름을 검색하려고 Enter 를 치면 이슈가 그대로 만들어진다.
//
// ★가드가 과잉이면 안 된다 — 제목 칸의 Enter 제출은 살아 있어야 한다. 두 테스트가 짝이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 담당자 검색창 Enter (게이트 2 B-1)', () => {
  it('담당자 검색창에서 Enter 를 눌러도 이슈가 만들어지지 않는다', async () => {
    const user = userEvent.setup()
    const onSuccess = vi.fn()
    renderForm(onSuccess)

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '검색 중 제출 금지')
    await user.type(
      screen.getByLabelText(issueDetailStrings.assigneeSearchPlaceholder),
      '김{Enter}',
    )

    // 제출이 걸렸다면 이 사이에 mutation 이 끝나 onSuccess 가 불린다
    await waitFor(() => expect(screen.getByLabelText(issueCreateStrings.summaryLabel)).toBeInTheDocument())
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('★제목 칸에서 Enter 는 그대로 제출한다 — 가드가 과잉 차단하면 안 된다', async () => {
    const user = userEvent.setup()
    const onSuccess = vi.fn()
    renderForm(onSuccess)

    await waitForProjectSelect()
    await user.type(
      screen.getByLabelText(issueCreateStrings.summaryLabel),
      '제목에서 엔터{Enter}',
    )

    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 게이트 2 B-2 — 고른 담당자 이름이 검색어 변경에 흔들리면 안 된다
//
// 현재 담당자를 `useUsers(검색어)` 결과에서 find() 하면, 검색어를 바꾸는 순간 골라둔 사람이
// 목록에서 빠져 화면이 「미지정」으로 뒤집힌다. 전송값은 멀쩡한데 **표시만 거짓말**을 한다.
// ★이슈 상세가 이미 겪고 고친 결함이다 (`routes/issues.$key.tsx` 의 `C1 버그 수정` 주석).
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 고른 담당자 표시 유지 (게이트 2 B-2)', () => {
  /** 담당자 검색 → 김앨리스 선택까지. */
  async function pickAlice(user: ReturnType<typeof userEvent.setup>): Promise<void> {
    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueDetailStrings.assigneeSearchPlaceholder), 'al')
    await user.click(await screen.findByRole('button', { name: '김앨리스' }))
  }

  it('고른 직후 담당자 이름이 즉시 보인다 (깜빡임 없음)', async () => {
    const user = userEvent.setup()
    renderForm()

    await pickAlice(user)

    expect(screen.getByTestId('assignee-current-name').textContent).toBe('김앨리스')
  })

  it('다른 이름으로 검색어를 바꿔도 현재 담당자 이름이 유지된다', async () => {
    const user = userEvent.setup()
    renderForm()

    await pickAlice(user)
    const search = screen.getByLabelText(issueDetailStrings.assigneeSearchPlaceholder)
    await user.clear(search)
    await user.type(search, 'bob')

    // 검색 결과가 bob 으로 갈린 뒤에도 현재 담당자는 그대로여야 한다
    await screen.findByRole('button', { name: 'bob' })
    expect(screen.getByTestId('assignee-current-name').textContent).toBe('김앨리스')
  })

  it('고른 담당자의 id 가 본문 assigneeId 로 실린다 (3-state 「값」 경로)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    await pickAlice(user)
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
    expect(body.get()['assigneeId']).toBe(ALICE_USER_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TODOS 「이슈 제목 placeholder 만 i18n 키 없이 하드코딩돼 있다」 봉합 (2026-08-09)
//
// 같은 폼 안에서 본문·프로젝트 셀렉터는 i18n 정본을 쓰는데 제목만 리터럴이었다.
// ★렌더 단언만으로는 하드코딩 복귀를 못 잡는다 — i18n 값과 리터럴이 바이트 동일하면
//   DOM 의 placeholder 속성 문자열이 두 경우 완전히 같다(속성값은 출처를 싣지 않는다).
//   그래서 소스 단언을 짝으로 둔다. 앱 전역 28곳에 대한 ESLint 래칫은 별도 TODOS 항목.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — 제목 placeholder i18n 배선', () => {
  it('제목 입력의 placeholder 가 i18n 정본에서 온다', async () => {
    renderForm()

    // ★red-first 의 핵심. 키가 없으면 undefined 라 여기서 터진다.
    // 이 단언이 없으면 아래 toHaveAttribute 가 undefined 를 「속성 존재만 확인」으로
    // 해석해 가짜 그린이 된다 (jest-dom 의 알려진 동작).
    expect(typeof issueCreateStrings.summaryPlaceholder).toBe('string')
    expect(issueCreateStrings.summaryPlaceholder.length).toBeGreaterThan(0)

    const summary = await screen.findByLabelText(issueCreateStrings.summaryLabel)
    expect(summary).toHaveAttribute('placeholder', issueCreateStrings.summaryPlaceholder)
  })

  it('★컴포넌트 소스에 한글 리터럴 placeholder 가 남아 있지 않다 (하드코딩 복귀 차단)', async () => {
    // jsdom 환경에서는 import.meta.url 이 file: 스킴이 아니라 http: 라 URL 기반 해석이 안 된다.
    // vitest 는 apps/web 을 cwd 로 돌므로 거기서 해석한다 — 경로가 틀리면 아래 length 단언이 잡는다.
    const { readFile } = await import('node:fs/promises')
    const { resolve } = await import('node:path')
    const source = await readFile(
      resolve(process.cwd(), 'src/components/issue/create/IssueCreateBasicFields.tsx'),
      'utf-8',
    )

    // 비-공허 짝 — 파일을 실제로 읽었고 기대한 배선이 그 안에 있는지 먼저 못박는다.
    // 이게 없으면 경로가 틀려 빈 문자열을 읽어도 아래 not.toMatch 가 조용히 통과한다.
    expect(source.length).toBeGreaterThan(500)
    expect(source).toMatch(/placeholder=\{issueCreateStrings\.summaryPlaceholder\}/)

    const koreanLiteralPlaceholder = /placeholder\s*=\s*"[^"]*[가-힣][^"]*"/
    expect(source).not.toMatch(koreanLiteralPlaceholder)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TODOS 「required MULTI_SELECT 커스텀 필드가 클라이언트 검증을 그냥 통과한다」 봉합 (2026-08-09)
//
// 옛 판정은 분기마다 빈값 조건을 다시 적었고 MULTI_SELECT 분기가
// `Array.isArray(raw) && raw.length === 0` 이라 **한 번도 안 건드린 필드(undefined)**를
// 「빈값 아님」으로 통과시켰다. CHECKBOX(`return false`)도 같은 구멍이었다.
//
// 데이터는 백엔드가 지켰지만(CustomFieldValueValidator) 사용자에게는 폼 안 경고 대신
// 서버 왕복 후 「잠시 후 다시 시도해 주세요」가 떴다 — 재시도해도 안 되는데 재시도를 권하는 문구.
//
// 2026-08-09 Maxi 확정 — MULTI_SELECT + CHECKBOX 둘 다 차단.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — required 커스텀 필드 빈값 판정 (스펙 E-3)', () => {
  function mockRequiredField(fieldType: CustomField['fieldType'], key: string): void {
    const field = {
      id: 1,
      key,
      name: `필수 ${key}`,
      fieldType,
      required: true,
      // MULTI_SELECT 위젯은 CustomFieldOption{value,label} 을 읽는다 — 문자열 배열이 아니다
      options:
        fieldType === 'MULTI_SELECT'
          ? [
              { value: 'A', label: 'A' },
              { value: 'B', label: 'B' },
            ]
          : [],
      displayOrder: 0,
      projectKey: 'ATLAS',
    } as unknown as CustomField
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [field],
    } as never)
  }

  /** 제목만 채우고 제출한다 — 커스텀 필드는 일부러 건드리지 않는다. */
  async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>): Promise<void> {
    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))
  }

  it('★한 번도 건드리지 않은 required MULTI_SELECT 는 폼 안에서 막힌다 (undefined 경로)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockRequiredField('MULTI_SELECT', 'ms')
    renderForm()

    await fillAndSubmit(user)

    // 폼 안 경고가 떠야 한다 — 서버 왕복 후 일반 에러가 아니라.
    expect(await screen.findByTestId('custom-fields-required-error')).toBeInTheDocument()
    // 그리고 요청이 아예 나가지 않아야 한다. 「경고가 떴다」만 보면
    // 경고와 제출이 동시에 일어나도 초록이라 두 단언을 짝으로 둔다.
    expect(body.get()).toEqual({})
  })

  it('★한 번도 건드리지 않은 required CHECKBOX 도 폼 안에서 막힌다 (undefined 경로)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockRequiredField('CHECKBOX', 'cb')
    renderForm()

    await fillAndSubmit(user)

    expect(await screen.findByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(body.get()).toEqual({})
  })

  it('required CHECKBOX 를 체크하면 통과한다 (비-공허 짝 — 항상 막히는 게 아님을 증명)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockRequiredField('CHECKBOX', 'cb')
    renderForm()

    await waitForProjectSelect()
    await user.click(screen.getByTestId('custom-field-cb'))
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
    expect(screen.queryByTestId('custom-fields-required-error')).toBeNull()
  })

  it('required MULTI_SELECT 를 고르면 통과한다 (비-공허 짝)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockRequiredField('MULTI_SELECT', 'ms')
    renderForm()

    await waitForProjectSelect()
    await user.click(await screen.findByRole('checkbox', { name: 'A' }))
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
    expect(screen.queryByTestId('custom-fields-required-error')).toBeNull()
  })
})
