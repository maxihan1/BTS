// WorklogSection 컴포넌트 단위 테스트 — FR-TT-01 D6 Task 6 TDD RED
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'
import { WorklogSection } from './WorklogSection'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// useUsersByIds mock — 실제 GET /api/v1/users/batch 없이 displayName 반환
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/hooks/use-users', () => ({
  useUsersByIds: vi.fn(),
}))

import { useUsersByIds } from '@/hooks/use-users'
import type { WorklogResponse } from '@/api/worklogs'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 — RFC4122 v4 형식 UUID (Zod v4 검증 통과)
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_UUID = 'a0000000-0000-4000-a000-000000000001'
const BOB_UUID = 'b0000000-0000-4000-a000-000000000002'
const WL_ID_1 = 'c0000000-0000-4000-a000-000000000001'
const WL_ID_2 = 'c0000000-0000-4000-a000-000000000002'

/** alice 워크로그 픽스처 (본인 worklog) */
const aliceWorklog: WorklogResponse = {
  id: WL_ID_1,
  issueKey: 'ATLAS-1',
  authorId: ALICE_UUID,
  timeSpentSeconds: 3600, // 1h 0m
  startedAt: '2026-06-20T09:00:00Z',
  comment: '작업 완료',
  createdAt: '2026-06-20T10:00:00Z',
  updatedAt: '2026-06-20T10:00:00Z',
}

/** bob 워크로그 픽스처 (타인 worklog) */
const bobWorklog: WorklogResponse = {
  id: WL_ID_2,
  issueKey: 'ATLAS-1',
  authorId: BOB_UUID,
  timeSpentSeconds: 1800, // 30m
  startedAt: '2026-06-20T14:00:00Z',
  comment: null,
  createdAt: '2026-06-20T14:30:00Z',
  updatedAt: '2026-06-20T14:30:00Z',
}

/** 집계 요약 픽스처 */
const summary = {
  originalEstimateSeconds: 7200, // 2h
  timeSpentSeconds: 5400,        // 1h 30m
  remainingEstimateSeconds: 1800, // 30m
}

/** 빈 집계 요약 */
const emptySummary = {
  originalEstimateSeconds: null,
  timeSpentSeconds: 0,
  remainingEstimateSeconds: null,
}

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

/** useUsersByIds 기본 stub — alice/bob displayName 반환 */
function stubUsersByIds() {
  vi.mocked(useUsersByIds).mockReturnValue({
    data: [
      { id: ALICE_UUID, displayName: 'Alice Kim', email: 'alice@bts.local', username: 'alice' },
      { id: BOB_UUID, displayName: 'Bob Lee', email: 'bob@bts.local', username: 'bob' },
    ],
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
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useUsersByIds>)
}

/** GET /api/v1/issues/:key/worklogs MSW 핸들러 등록 */
function useWorklogGetHandler(
  issueKey: string,
  opts: {
    worklogs: WorklogResponse[]
    summary: typeof summary | typeof emptySummary
  },
) {
  server.use(
    http.get(`/api/v1/issues/${issueKey}/worklogs`, () =>
      HttpResponse.json({
        data: { worklogs: opts.worklogs, summary: opts.summary },
      }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup / Teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // alice 로그인 상태 시드
  useAuthStore.getState().setSession({
    accessToken: 'test-token',
    user: { ...aliceUser, userId: ALICE_UUID },
  })
  stubUsersByIds()
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 목록 표시 — formatSeconds 시간 + displayName + startedAt + comment
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (a) 목록 렌더', () => {
  it('a1: worklog 목록을 formatSeconds로 포맷한 시간과 함께 렌더한다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog, bobWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    // alice: 3600초 → "1h 0m", bob: 1800초 → "30m"
    expect(await screen.findByText('1h 0m')).toBeInTheDocument()
    expect(screen.getByText('30m')).toBeInTheDocument()
  })

  it('a2: authorId를 useUsersByIds로 조회한 displayName으로 표시한다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog, bobWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')
    expect(screen.getByText(/Alice Kim/)).toBeInTheDocument()
    expect(screen.getByText(/Bob Lee/)).toBeInTheDocument()
  })

  it('a3: comment가 있는 worklog는 comment를 표시한다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    expect(await screen.findByText('작업 완료')).toBeInTheDocument()
  })

  it('a4: 빈 상태(worklogs 0건)에 worklogEmptyState 메시지를 표시한다 (E7)', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    expect(await screen.findByText('기록된 작업이 없습니다.')).toBeInTheDocument()
  })

  it('a5: 로딩 중에는 로딩 텍스트를 표시한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-LOADING/worklogs', () => new Promise<never>(() => {})),
    )
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-LOADING" canUpdate />, { wrapper: Wrapper })

    expect(screen.getByText('작업 기록 불러오는 중')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 추가 폼 — 시간 h+m 필수, 시작시각 datetime-local, 코멘트(선택)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (b) 추가 폼', () => {
  it('b1: canUpdate=true 이면 추가 폼이 렌더된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')
    // 시간/분 입력 필드 존재 확인
    expect(screen.getByLabelText(/시간/)).toBeInTheDocument()
    expect(screen.getByLabelText(/분/)).toBeInTheDocument()
  })

  it('b2: 0h 0m 이면 추가 버튼이 disabled다 (E3)', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')
    const addBtn = screen.getByRole('button', { name: '작업 기록 추가' })
    expect(addBtn).toBeDisabled()
  })

  it('b3: 시간을 입력하면 추가 버튼이 활성화된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')
    const hoursInput = screen.getByLabelText(/시간/)
    await userEvent.clear(hoursInput)
    await userEvent.type(hoursInput, '2')

    const addBtn = screen.getByRole('button', { name: '작업 기록 추가' })
    expect(addBtn).not.toBeDisabled()
  })

  it('b4: 시작 시각 입력 필드가 datetime-local 타입으로 렌더된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')
    const startedAtInput = screen.getByLabelText('시작 시각')
    expect(startedAtInput).toHaveAttribute('type', 'datetime-local')
  })

  it('b5: 코멘트 입력 필드가 렌더된다 (선택)', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')
    expect(screen.getByLabelText('코멘트')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 자동차감 미리보기 — "잔여 직접 지정" 미체크 시 미리보기 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (c) 자동차감 미리보기 (FR6)', () => {
  it('c1: 자동 모드에서 시간 입력 시 "기록 후 잔여 = max(0, remaining - 입력초)" 미리보기를 표시한다', async () => {
    // summary.remainingEstimateSeconds = 1800 (30m)
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // 30분(1800초) 입력
    const minutesInput = screen.getByLabelText(/분/)
    await userEvent.clear(minutesInput)
    await userEvent.type(minutesInput, '30')

    // 미리보기: max(0, 1800 - 1800) = 0 → "0m"
    await waitFor(() => {
      expect(screen.getByText(/0m/)).toBeInTheDocument()
    })
  })

  it('c2: 입력 초가 잔여보다 크면 미리보기는 0으로 표시된다', async () => {
    // summary.remainingEstimateSeconds = 1800 (30m)
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // 2시간 입력 → max(0, 1800 - 7200) = 0
    const hoursInput = screen.getByLabelText(/시간/)
    await userEvent.clear(hoursInput)
    await userEvent.type(hoursInput, '2')

    await waitFor(() => {
      // "0m" 미리보기가 렌더됨
      expect(screen.getByText(/0m/)).toBeInTheDocument()
    })
  })

  it('c3: summary.remainingEstimateSeconds가 null이면 미리보기를 숨긴다 (E2)', async () => {
    // emptySummary.remainingEstimateSeconds = null
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // 1시간 입력해도 미리보기 텍스트가 없어야 함
    const hoursInput = screen.getByLabelText(/시간/)
    await userEvent.clear(hoursInput)
    await userEvent.type(hoursInput, '1')

    // 자동조정 미리보기 안내 문자열은 나타나지 않아야 한다
    expect(screen.queryByText(/기록 후 잔여/)).not.toBeInTheDocument()
  })

  it('c4: "잔여 직접 지정" 체크 시 미리보기가 숨겨진다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // 시간 입력으로 미리보기 활성화
    const minutesInput = screen.getByLabelText(/분/)
    await userEvent.clear(minutesInput)
    await userEvent.type(minutesInput, '30')

    // "잔여 추정 직접 지정" 체크박스 체크
    const adjustCheckbox = screen.getByRole('checkbox', { name: '잔여 추정 직접 지정' })
    await userEvent.click(adjustCheckbox)

    // 미리보기가 사라져야 한다
    await waitFor(() => {
      expect(screen.queryByText(/기록 후 잔여/)).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) cross-invalidate — 추가/수정/삭제 성공 시 worklog 쿼리 + issueQueryKey 둘 다 invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (d) cross-invalidate (FR9)', () => {
  it('d1: 추가 성공 시 POST가 호출되고 토스트가 표시된다', async () => {
    const user = userEvent.setup()
    let postCalled = false

    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', () => {
        postCalled = true
        return HttpResponse.json(
          {
            data: {
              ...aliceWorklog,
              id: 'c0000000-0000-4000-a000-000000000099',
            },
          },
          { status: 201 },
        )
      }),
    )

    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // 1시간 입력
    const hoursInput = screen.getByLabelText(/시간/)
    await userEvent.clear(hoursInput)
    await userEvent.type(hoursInput, '1')

    // 시작 시각 입력
    const startedAtInput = screen.getByLabelText('시작 시각')
    await userEvent.clear(startedAtInput)
    await userEvent.type(startedAtInput, '2026-06-20T09:00')

    // 추가 버튼 클릭
    const addBtn = screen.getByRole('button', { name: '작업 기록 추가' })
    await user.click(addBtn)

    await waitFor(() => {
      expect(postCalled).toBe(true)
      expect(toast.success).toHaveBeenCalled()
    })
  })

  it('d2: 추가 실패 시 toast.error가 호출된다', async () => {
    const user = userEvent.setup()

    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary: emptySummary })
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    const hoursInput = screen.getByLabelText(/시간/)
    await userEvent.clear(hoursInput)
    await userEvent.type(hoursInput, '1')

    const startedAtInput = screen.getByLabelText('시작 시각')
    await userEvent.clear(startedAtInput)
    await userEvent.type(startedAtInput, '2026-06-20T09:00')

    const addBtn = screen.getByRole('button', { name: '작업 기록 추가' })
    await user.click(addBtn)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) 본인 worklog만 수정/삭제 버튼 노출 (S7/E5)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (e) 본인 게이팅', () => {
  it('e1: 본인(alice) worklog 행에는 수정/삭제 버튼이 표시된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')

    // 수정 버튼 노출
    expect(
      screen.getByRole('button', { name: /작업 기록 수정/ }),
    ).toBeInTheDocument()
    // 삭제 버튼 노출
    expect(
      screen.getByRole('button', { name: /작업 기록 삭제/ }),
    ).toBeInTheDocument()
  })

  it('e2: 타인(bob) worklog 행에는 수정/삭제 버튼이 표시되지 않는다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [bobWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('30m')

    // 수정/삭제 버튼 미노출
    expect(
      screen.queryByRole('button', { name: /작업 기록 수정/ }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /작업 기록 삭제/ }),
    ).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) 수정 + 삭제 — updateWorklog(PATCH) + deleteWorklog(DELETE)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (f) 수정/삭제', () => {
  it('f1: 수정 버튼 클릭 시 인라인 수정 폼이 표시된다', async () => {
    const user = userEvent.setup()
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')
    const editBtn = screen.getByRole('button', { name: /작업 기록 수정/ })
    await user.click(editBtn)

    // 저장 버튼이 나타나야 한다
    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument()
  })

  it('f2: 수정 저장 성공 시 PATCH가 호출되고 toast.success가 표시된다', async () => {
    const user = userEvent.setup()
    let patchCalled = false

    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    server.use(
      http.patch(`/api/v1/issues/ATLAS-1/worklogs/${WL_ID_1}`, () => {
        patchCalled = true
        return HttpResponse.json({ data: { ...aliceWorklog, timeSpentSeconds: 7200 } })
      }),
    )

    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')
    const editBtn = screen.getByRole('button', { name: /작업 기록 수정/ })
    await user.click(editBtn)

    // 시간을 2로 변경 후 저장
    const hoursInputs = screen.getAllByLabelText(/시간/)
    const editHoursInput = hoursInputs[hoursInputs.length - 1]!
    await userEvent.clear(editHoursInput)
    await userEvent.type(editHoursInput, '2')

    const saveBtn = screen.getByRole('button', { name: '저장' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(patchCalled).toBe(true)
      expect(toast.success).toHaveBeenCalled()
    })
  })

  it('f3: 삭제 버튼 클릭 → 확인 클릭 시 DELETE가 호출되고 toast.success가 표시된다', async () => {
    const user = userEvent.setup()
    let deleteCalled = false

    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    server.use(
      http.delete(`/api/v1/issues/ATLAS-1/worklogs/${WL_ID_1}`, () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')
    const deleteBtn = screen.getByRole('button', { name: /작업 기록 삭제/ })
    await user.click(deleteBtn)

    // 인라인 확인 버튼이 나타나야 한다
    const confirmBtn = await screen.findByRole('button', { name: '확인' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
      expect(toast.success).toHaveBeenCalled()
    })
  })

  it('f4: 삭제 취소 클릭 시 DELETE가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    let deleteCalled = false

    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    server.use(
      http.delete(`/api/v1/issues/ATLAS-1/worklogs/${WL_ID_1}`, () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')
    const deleteBtn = screen.getByRole('button', { name: /작업 기록 삭제/ })
    await user.click(deleteBtn)

    const cancelBtn = await screen.findByRole('button', { name: '취소' })
    await user.click(cancelBtn)

    expect(deleteCalled).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (g) canUpdate=false — 추가/수정/삭제 모두 미노출 (S8)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (g) canUpdate=false (S8)', () => {
  it('g1: canUpdate=false 이면 추가 폼이 미노출된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate={false} />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')

    // 추가 버튼 미노출
    expect(screen.queryByRole('button', { name: '작업 기록 추가' })).not.toBeInTheDocument()
  })

  it('g2: canUpdate=false 이면 본인 worklog도 수정/삭제 버튼이 미노출된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [aliceWorklog], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate={false} />, { wrapper: Wrapper })

    await screen.findByText('1h 0m')

    // 수정/삭제 버튼 미노출 (canUpdate=false가 본인 여부보다 우선)
    expect(screen.queryByRole('button', { name: /작업 기록 수정/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /작업 기록 삭제/ })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (h) "잔여 직접 지정" 체크 시 직접 지정 입력 필드 조건부 렌더 (design-C3)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogSection — (h) 잔여 직접 지정 토글 (design-C3)', () => {
  it('h1: 기본(미체크) 상태에서 직접 지정 입력 필드가 없다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    // "잔여" 레이블의 직접 지정 입력 필드는 존재하지 않아야 한다
    expect(screen.queryByLabelText('잔여 시간')).not.toBeInTheDocument()
  })

  it('h2: 체크 시 직접 지정 입력 필드(시간/분)가 렌더된다', async () => {
    useWorklogGetHandler('ATLAS-1', { worklogs: [], summary })
    const Wrapper = createWrapper()
    render(<WorklogSection issueKey="ATLAS-1" canUpdate />, { wrapper: Wrapper })

    await screen.findByText('기록된 작업이 없습니다.')

    const adjustCheckbox = screen.getByRole('checkbox', { name: '잔여 추정 직접 지정' })
    await userEvent.click(adjustCheckbox)

    // 직접 지정 시간/분 입력 필드가 나타나야 한다
    await waitFor(() => {
      expect(screen.getByLabelText('잔여 시간')).toBeInTheDocument()
    })
  })
})
