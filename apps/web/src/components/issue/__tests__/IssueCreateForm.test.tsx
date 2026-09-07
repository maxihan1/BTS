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
import { SUMMARY_MAX_LENGTH } from '@/components/issue/create/issue-create-schema'

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

  // ★본문이 plain textarea 에서 **리치 에디터**로 바뀌었다(J23). 그래서 실리는 키도
  //   `description`(마크다운)이 아니라 `descriptionHtml`(정화 HTML)이다.
  //   두 키를 함께 보내면 서버가 400 을 내므로 어느 하나만 실려야 한다.
  it('본문에 입력한 값이 제출 본문 descriptionHtml 로 실린다', async () => {
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

    await waitFor(() => {
      expect(String(captured['descriptionHtml'] ?? '')).toContain('본문입니다')
    })
    // 레거시 마크다운 키는 실리지 않는다 — 서버 `isBodyExclusive` 가 동시 전달을 막는다.
    expect('description' in captured).toBe(false)
  })

  it('본문 칸에 템플릿 안내가 보인다 (FR-TM-01)', async () => {
    renderForm()

    expect(await screen.findByText(issueCreateStrings.descriptionTemplateHint)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// B-2 (선재 결함 정렬) — 제목 상한이 백엔드와 같다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 제목 상한 (B-2 선재 결함 정렬)', () => {
  it('상한 +1 자 제목은 폼 검증에서 거부된다 — 백엔드 IssueTextConstraints.SUMMARY_MAX 와 정렬', async () => {
    const user = userEvent.setup()
    renderForm()

    await waitForProjectSelect()
    const summary = screen.getByLabelText(issueCreateStrings.summaryLabel)
    // ★숫자를 적지 않는다 — 상한이 200 → 255 로 움직이며 이 테스트가 무관하게 깨졌다.
    // paste 로 넣는다 — 한 글자씩 타이핑하면 테스트가 매우 느려진다.
    await user.click(summary)
    await user.paste('a'.repeat(SUMMARY_MAX_LENGTH + 1))
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

// ─────────────────────────────────────────────────────────────────────────────
// ★뮤테이션이 적발한 공허 가드 보강 (2026-08-09)
//
// 위 「한 번도 건드리지 않은 required CHECKBOX」 테스트는 선판정
// (`raw === undefined → 빈값`)만으로 통과한다. 그래서 CHECKBOX 분기를 옛
// `return false` 로 되돌려도 red 가 되지 않았다 — `raw !== true` 가 **무검증**이었다.
//
// 두 판정이 갈리는 유일한 입력은 `raw === false`(체크했다 해제한 상태)다.
// 그 케이스를 직접 친다.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — required CHECKBOX 는 「체크됨」만 충족이다', () => {
  it('★체크했다 해제하면(false) 여전히 막힌다 — undefined 와 같은 판정', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    const field = {
      id: 1,
      key: 'cb',
      name: '필수 cb',
      fieldType: 'CHECKBOX',
      required: true,
      options: [],
      displayOrder: 0,
      projectKey: 'ATLAS',
    } as unknown as CustomField
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [field],
    } as never)
    renderForm()

    await waitForProjectSelect()
    const checkbox = screen.getByTestId('custom-field-cb')
    // 켰다가 끈다 — 값이 undefined 가 아니라 명시적 false 가 된다.
    await user.click(checkbox)
    await user.click(checkbox)
    expect(checkbox).not.toBeChecked()

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    expect(await screen.findByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(body.get()).toEqual({})
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TODOS 「상단바 「만들기」 버튼만 CREATE 권한 게이트가 없다」 봉합 (2026-08-09)
//
// 실측하니 무게이트 경로는 상단바 하나가 아니라 4개였고(상단바 · /issues/new 딥링크 ·
// `c` 단축키 · 명령 팔레트), 게이트된 버튼으로 열어도 폼 안에서 무권한 프로젝트로
// 갈아탈 수 있어 버튼 게이트만으로는 닫히지 않는다.
//
// 2026-08-09 Maxi 확정 — 게이트를 버튼이 아니라 **폼의 선택된 프로젝트**에 둔다.
// 모든 진입 경로가 예외 없이 IssueCreateForm 하나를 지난다.
//
// ★판정식이 `!isLoading && CREATE === true`(미지=거부)면 안 된다. 그건 버튼을
//   회색으로 만드는 용도라 아무 주장도 하지 않지만, 제출 차단 + 문구 노출은
//   **사실 주장**이라 로딩 중·조회 실패 구간에서 거짓말이 된다.
//   `CREATE === false`(명시 거부만 차단)로 둔다.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — 선택된 프로젝트의 CREATE 게이트', () => {
  /**
   * 지정한 projectKey 에만 CREATE 를 바꿔 주는 권한 핸들러.
   *
   * ★permissions 맵은 **전 키를 다 채워야 한다** — `projectPermissionsSchema` 가
   * 7개 키를 전부 요구하므로 부분 응답은 Zod 에서 거부되고, 그러면 쿼리가 에러로
   * 떨어져 「데이터 없음」이 된다. 그 상태로는 이 테스트가 게이트를 재는 게 아니라
   * **조회 실패 경로**를 재게 되어 「엉뚱한 걸 쟀다」가 된다.
   */
  function setupPermissions(createByProject: Record<string, boolean>): void {
    server.use(
      http.get('/api/v1/users/me/project-permissions', ({ request }) => {
        const key = new URL(request.url).searchParams.get('projectKey') ?? ''
        const canCreate = createByProject[key] ?? true
        // ★`{data:…}` 봉투를 씌우지 않는다 — parseResponse 가 응답 본문을 그대로
        //   Zod 에 넘긴다(BC 별로 봉투 관례가 다르다). 봉투를 씌우면 파싱이 실패해
        //   쿼리가 에러로 떨어지고, 이 테스트는 게이트가 아니라 조회 실패 경로를 잰다.
        return HttpResponse.json({
          projectKey: key,
          permissions: {
            CREATE: canCreate,
            UPDATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        })
      }),
    )
  }

  it('★CREATE 가 명시적으로 false 인 프로젝트로는 제출이 서버로 나가지 않는다', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    setupPermissions({ ATLAS: false })
    renderForm()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    // 권한 조회가 도착할 때까지 기다린다 — 이걸 안 하면 「게이트가 막았다」가 아니라
    // 「아직 몰라서 통과했다」를 재게 되어 판정이 타이밍에 좌우된다.
    await screen.findByTestId('create-permission-denied')

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    // 제출을 실제로 시도한다. 버튼이 비활성이어도 Enter 제출 경로가 남아 있으므로
    // 「버튼이 회색이다」가 아니라 **네트워크가 나갔는지**로 단언한다.
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '{Enter}')

    expect(body.get()).toEqual({})
  })

  it('CREATE 가 true 면 그대로 제출된다 (비-공허 짝)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    setupPermissions({ ATLAS: true })
    renderForm()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
  })

  it('★권한 조회가 **아직 진행 중**인 프레임에서도 제출이 나간다 (로딩 프레임 계약 open-while-loading · 부채 매핑 15)', async () => {
    // ★이 프레임이 저장소 전체에서 무검증이었다. 기존 게이트 3테스트는 성공·에러 **정착만**
    //   덮고, pending 을 붙잡는 단언은 임포트 테스트 4곳뿐이었다(실측).
    //   `IssueCreateForm` 의 계약은 임포트와 **다르다** — 로딩 중에도 폼은 열려 있어야 한다.
    //   프로젝트를 바꿀 때마다 queryKey 가 바뀌어 이 프레임이 **반복 재발**하기 때문이다.
    //
    // ★지연은 벽시계가 아니라 **테스트가 여는 게이트**로 만든다. 벽시계 지연은 러너 부하로
    //   「먼저 정착」이 뒤집혀 간헐 실패가 된다.
    let releasePermissions: () => void = () => undefined
    const permissionGate = new Promise<void>((resolve) => {
      releasePermissions = resolve
    })
    server.use(
      http.get('/api/v1/users/me/project-permissions', async ({ request }) => {
        await permissionGate
        const key = new URL(request.url).searchParams.get('projectKey') ?? ''
        return HttpResponse.json({
          projectKey: key,
          permissions: {
            CREATE: false,
            UPDATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        })
      }),
    )
    const user = userEvent.setup()
    const body = captureSubmitBody()
    renderForm()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    // 아직 게이트를 열지 않았다 — 권한은 pending 이고 거부 문구도 없어야 한다.
    expect(screen.queryByTestId('create-permission-denied')).not.toBeInTheDocument()

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    // 미지를 거부로 읽으면 여기서 막힌다. 최종 판정은 서버가 한다.
    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))

    // 지연 쿼리를 남기지 않는다 — 열고 정착까지 기다린 뒤 끝낸다.
    releasePermissions()
    expect(await screen.findByTestId('create-permission-denied')).toBeInTheDocument()
  })

  it('★권한 조회가 실패해도 제출을 막지 않는다 (미지 ≠ 거부 — 오탐 거부 방지)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 }),
      ),
    )
    renderForm()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    // 조회 실패로 CREATE 를 모르는 상태다. 여기서 막으면 권한이 있는 사용자를
    // 「권한 없음」이라는 틀린 이유로 영구 차단한다 — 서버가 최종 판정하게 둔다.
    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
  })

  it('★서버가 403 을 주면 「잠시 후 다시 시도」가 아니라 권한 문구가 뜬다', async () => {
    const user = userEvent.setup()
    setupPermissions({ ATLAS: true })
    server.use(
      http.post('/api/v1/issues', () =>
        HttpResponse.json({ errorCode: 'ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    renderForm()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    // 기존에는 errorDefault(「잠시 후 다시 시도해 주세요」)가 떴다 —
    // 재시도해도 안 되는데 재시도를 권하는 문구다.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      issueCreateStrings.errorCreateForbidden,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★게이트 2 리뷰가 적발한 공허 가드 보강 (2026-08-10)
//
// (1) CREATE 게이트의 **핵심인 handleSubmit 이른 반환이 무검증**이었다.
//     기존 테스트는 `{Enter}` 로만 제출을 시도하는데, 같은 변경이 건 disabled 버튼
//     때문에 jsdom 이 폼 제출을 아예 하지 않아 이른 반환이 실행되지 않는다.
//     ★실사용에서 이 3줄이 유일한 방어인 경로가 있다 — 모달(CreateIssueDialog)은
//     `formId` 를 넘겨 제출 버튼을 **폼 밖 푸터**에 두고 그 버튼에는 권한 게이트가 없다.
//     상단바·`c` 단축키·명령 팔레트가 전부 그 경로다.
//
// (2) 선판정(`undefined|null → 빈값`)이 switch 뒤 분기를 가려 MULTI_SELECT `[]` ·
//     텍스트류 `''` · NUMBER `NaN` 세 분기가 무검증이 됐다. CHECKBOX 에만 짝을 붙였었다.
//     특히 MULTI_SELECT `[]` 는 **원 결함과 같은 필드**의 다른 입력 경로다 —
//     옵션을 체크했다 해제하면 `undefined` 가 아니라 `[]` 가 된다.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — 폼 밖 제출(모달 경로)에서도 CREATE 게이트가 막는다', () => {
  const EXTERNAL_FORM_ID = 'test-external-create-form'

  function renderWithExternalSubmit() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    return render(
      <QueryClientProvider client={client}>
        <IssueCreateForm formId={EXTERNAL_FORM_ID} />
        {/* CreateIssueDialog 푸터와 같은 구조 — 폼 밖 버튼이 form 속성으로 제출한다.
            그 버튼에는 권한 게이트가 없으므로 handleSubmit 이른 반환이 유일한 방어다. */}
        <button type="submit" form={EXTERNAL_FORM_ID}>
          폼 밖 제출
        </button>
      </QueryClientProvider>,
    )
  }

  it('★CREATE=false 면 폼 밖 버튼으로 제출해도 서버로 안 나간다 (이른 반환 실행 경로)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    server.use(
      http.get('/api/v1/users/me/project-permissions', ({ request }) => {
        const key = new URL(request.url).searchParams.get('projectKey') ?? ''
        return HttpResponse.json({
          projectKey: key,
          permissions: {
            CREATE: false,
            UPDATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        })
      }),
    )
    renderWithExternalSubmit()

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    await screen.findByTestId('create-permission-denied')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')

    // 폼 밖 버튼은 비활성이 아니다 — 눌린다. 막는 것은 handleSubmit 이른 반환뿐이다.
    const external = screen.getByRole('button', { name: '폼 밖 제출' })
    expect(external).not.toBeDisabled()
    await user.click(external)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      issueCreateStrings.errorCreateForbidden,
    )
    expect(body.get()).toEqual({})
  })
})

describe('IssueCreateForm — required 빈값 판정의 유형별 분기 (선판정에 가려지지 않는다)', () => {
  function mockField(fieldType: CustomField['fieldType'], key: string): void {
    const field = {
      id: 1,
      key,
      name: `필수 ${key}`,
      fieldType,
      required: true,
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

  it('★MULTI_SELECT 를 골랐다 해제하면(빈 배열) 막힌다 — undefined 가 아닌 경로', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockField('MULTI_SELECT', 'ms')
    renderForm()

    await waitForProjectSelect()
    const optionA = await screen.findByRole('checkbox', { name: 'A' })
    // 켰다 끈다 — 값이 undefined 가 아니라 [] 가 된다 (CustomFieldInput 의 filter 결과).
    await user.click(optionA)
    await user.click(optionA)
    expect(optionA).not.toBeChecked()

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    expect(await screen.findByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(body.get()).toEqual({})
  })

  it('★SHORT_TEXT 에 입력했다 지우면(빈 문자열) 막힌다 — undefined 가 아닌 경로', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockField('SHORT_TEXT', 'st')
    renderForm()

    await waitForProjectSelect()
    const input = await screen.findByTestId('custom-field-st')
    await user.type(input, 'x')
    await user.clear(input)
    expect(input).toHaveValue('')

    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    expect(await screen.findByTestId('custom-fields-required-error')).toBeInTheDocument()
    expect(body.get()).toEqual({})
  })

  it('SHORT_TEXT 에 값이 있으면 통과한다 (비-공허 짝)', async () => {
    const user = userEvent.setup()
    const body = captureSubmitBody()
    mockField('SHORT_TEXT', 'st')
    renderForm()

    await waitForProjectSelect()
    await user.type(await screen.findByTestId('custom-field-st'), 'x')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(body.get()['summary']).toBe('제목입니다'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★게이트2 간극 리뷰가 적발한 신규 결함 2건 (2026-08-10)
//
// (1) CREATE 게이트가 세운 `serverError` 가 **프로젝트를 바꿔도 안 지워진다.**
//     「이 프로젝트에 이슈를 만들 권한이 없습니다」는 프로젝트 A 에 대한 주장이라
//     B 로 바꾸는 순간 거짓이 되는데, 빨간 alert 이 그대로 남아 계속 거짓말한다.
//     이 PR 이 만든 결함이다 — 그 전에는 그 문구 자체가 없었다.
//
// (2) TODOS 가 「같은 PR 에서 함께 볼 것」으로 못박은 커스텀 필드 422 매핑이 빠져 있었다.
//     클라이언트가 못 잡는 형식 오류(URL 타입에 `abc` 등)가 `errorDefault` 로 떨어져
//     **재시도해도 안 되는데 재시도를 권하는** 문구가 나간다.
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueCreateForm — 프로젝트를 바꾸면 이전 프로젝트의 에러 주장을 버린다', () => {
  function permissionsByProject(map: Record<string, boolean>): void {
    server.use(
      http.get('/api/v1/users/me/project-permissions', ({ request }) => {
        const key = new URL(request.url).searchParams.get('projectKey') ?? ''
        return HttpResponse.json({
          projectKey: key,
          permissions: {
            CREATE: map[key] ?? true,
            UPDATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        })
      }),
    )
  }

  it('★거부 프로젝트에서 뜬 권한 alert 이 허용 프로젝트로 바꾸면 사라진다', async () => {
    const user = userEvent.setup()
    permissionsByProject({ ATLAS: false, MIDDLE: true })
    // ★폼 밖 제출 버튼으로 렌더한다. 폼 안 버튼은 거부 시 disabled 라 jsdom 이 제출을
    //   하지 않아 `handleSubmit` 이 실행되지 않고, 그러면 serverError 자체가 안 세워진다.
    //   (게이트2 리뷰가 적발한 것과 같은 함정 — 이 테스트도 그대로 공허해질 뻔했다.)
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <IssueCreateForm formId="switch-project-form" />
        <button type="submit" form="switch-project-form">
          폼 밖 제출
        </button>
      </QueryClientProvider>,
    )

    const select = await waitForProjectSelect()
    await user.selectOptions(select, 'ATLAS')
    await screen.findByTestId('create-permission-denied')
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: '폼 밖 제출' }))

    // 거부 문구가 실제로 떴는지 먼저 확인한다 — 안 떴으면 아래 단언이 공허하다.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      issueCreateStrings.errorCreateForbidden,
    )

    await user.selectOptions(select, 'MIDDLE')

    // 프로젝트 B 는 CREATE 가 있다. A 에 대한 주장이 남아 있으면 안 된다.
    await waitFor(() => {
      expect(screen.queryByTestId('create-permission-denied')).toBeNull()
    })
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })
})

describe('IssueCreateForm — 커스텀 필드 422 는 전용 문구로 안내한다', () => {
  it('★CUSTOM_FIELD_VALIDATION_FAILED 는 「잠시 후 다시 시도」가 아니다', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/issues', () =>
        HttpResponse.json(
          { errorCode: 'CUSTOM_FIELD_VALIDATION_FAILED', detail: 'invalid url' },
          { status: 422 },
        ),
      ),
    )
    renderForm()

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(issueCreateStrings.errorCustomFieldInvalid)
    // 되던 문구가 아니어야 한다 — 이 짝이 없으면 「alert 이 떴다」만 보는 공허 단언이 된다.
    expect(alert).not.toHaveTextContent(issueCreateStrings.errorDefault)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 폼 밖 제출 경로의 계약 2종 (부채 매핑 8 — 상태·제출을 훅으로 가르면서 못 박았다).
//
// ★둘 다 **선재 동작에 대한 신규 커버리지**다. 분할 전에도 같은 두 줄이 있었고 그때도
//   아무 테스트가 안 걸렸다(뮤테이션 실측 — 두 줄을 지워도 60/60 초록이었다).
//   분할이 이 두 줄을 훅으로 옮겼으므로, 옮긴 자리에서 독립적으로 지워질 수 있게 됐다.
//   지금 못 박아 두지 않으면 「분할은 동작 불변」이라는 주장의 근거가 이 두 줄에 대해서만 없다.
//
// ★`CreateIssueDialog` 자체에는 테스트 파일이 없다 — 푸터 버튼의 비활성·문구 전환은
//   여전히 무검증이다. 그건 이 PR 범위 밖이라 장부에 등재했다.
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueCreateForm — 폼 밖 제출 경로의 계약 (게이트 2 C-2 · E7)', () => {
  const EXTERNAL_FORM_ID = 'test-external-contract-form'

  /** 응답을 손으로 풀 수 있는 POST 핸들러. 「제출 중」 프레임을 붙잡아야 잴 수 있다. */
  function holdCreateIssue(): { release: () => void; count: () => number } {
    let posts = 0
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.post('/api/v1/issues', async () => {
        posts += 1
        await held
        return HttpResponse.json({ data: { key: 'ATLAS-1' } }, { status: 201 })
      }),
    )
    return { release: () => release(), count: () => posts }
  }

  function renderWithExternalSubmit(onPendingChange: (pending: boolean) => void) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    return render(
      <QueryClientProvider client={client}>
        <IssueCreateForm formId={EXTERNAL_FORM_ID} onPendingChange={onPendingChange} />
        {/* CreateIssueDialog 푸터와 같은 구조. 이 버튼에는 아무 방어가 없다 */}
        <button type="submit" form={EXTERNAL_FORM_ID}>
          폼 밖 제출
        </button>
      </QueryClientProvider>,
    )
  }

  it('★제출 진행이 폼 밖으로 흘러나온다 — onPendingChange 가 true 를 거쳐 false 로 끝난다', async () => {
    const user = userEvent.setup()
    const onPendingChange = vi.fn()
    const gate = holdCreateIssue()
    renderWithExternalSubmit(onPendingChange)

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    await user.click(screen.getByRole('button', { name: '폼 밖 제출' }))

    // 진행 중임을 알렸는가. 이 신호가 모달 푸터 버튼의 **유일한** 상태원이다 —
    // 끊기면 버튼을 눌러도 아무 반응이 없어 사용자가 다시 누른다.
    await waitFor(() => {
      expect(onPendingChange).toHaveBeenCalledWith(true)
    })

    gate.release()

    // 끝났음도 알려야 한다. true 만 보내면 버튼이 영구히 「생성 중…」에 갇힌다.
    await waitFor(() => {
      expect(onPendingChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('★제출 중 다시 눌러도 서버로 두 번 나가지 않는다 (E7 — 폼 밖 버튼은 비활성이 아니다)', async () => {
    const user = userEvent.setup()
    const onPendingChange = vi.fn()
    const gate = holdCreateIssue()
    renderWithExternalSubmit(onPendingChange)

    await waitForProjectSelect()
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '제목입니다')
    const external = screen.getByRole('button', { name: '폼 밖 제출' })
    await user.click(external)
    await waitFor(() => {
      expect(gate.count()).toBe(1)
    })

    // 비-공허 짝. 버튼이 비활성이면 두 번째 클릭이 애초에 없었던 것이라
    // 「두 번 안 나갔다」가 이른 반환의 공로가 아니게 된다.
    expect(external).not.toBeDisabled()
    await user.click(external)

    gate.release()
    await waitFor(() => {
      expect(onPendingChange).toHaveBeenLastCalledWith(false)
    })
    expect(gate.count()).toBe(1)
  })
})
