// 이슈 상세 페이지 권한별 제목/본문 편집 버튼 disabled 단위 테스트 (FR-PM-02 Task 5)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import { issuePermissionHandlers } from '@/mocks/issue-permission-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { favoriteHandlers } from '@/mocks/favorite-handlers'
import {
  issueWatcherHandlers,
  resetIssueWatcherStore,
  setCurrentWatcherUserId,
} from '@/mocks/issue-watcher-handlers'
import { commentHandlers } from '@/mocks/comment-handlers'
import { labelHandlers } from '@/mocks/label-handlers'
import { keymapHandlers } from '@/mocks/keymap-handlers'
import { componentHandlers } from '@/mocks/component-handlers'
import { versionHandlers } from '@/mocks/version-handlers'
import { customFieldHandlers } from '@/mocks/custom-field-handlers'
import { changelogHandlers } from '@/mocks/changelog-handlers'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import type { IssueResponse } from '@/api/issues'
import { useKeyboardShortcuts } from '@/components/keyboard-shortcuts/useKeyboardShortcuts'
import {
  getRegisteredContexts,
  useContextShortcutsStore,
} from '@/components/keyboard-shortcuts/useContextShortcuts'
import { IssueDetailPage } from '@/routes/issues.$key'
import { useRecentIssues, RECENT_ISSUES_STORAGE_KEY } from '@/hooks/use-recent-issues'

// TanStack Router useNavigate mock
vi.mock('@tanstack/react-router', () => ({
  useParams: vi.fn(),
  useNavigate: () => vi.fn(),
}))

// useIssuePermissions 훅을 vi.mock으로 모킹 — 권한 시나리오를 자유롭게 제어한다
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))

import { useIssuePermissions } from '@/hooks/use-issue-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function mockPermissions(overrides: {
  canEdit?: boolean
  canDelete?: boolean
  isLoading?: boolean
  isError?: boolean
} = {}) {
  const { canEdit = true, canDelete = true, isLoading = false, isError = false } = overrides

  vi.mocked(useIssuePermissions).mockReturnValue({
    data: isLoading || isError ? undefined : {
      issueKey: 'ATLAS-1',
      permissions: {
        UPDATE: canEdit,
        SOFT_DELETE: canDelete,
        TRANSITION: true,
      },
    },
    isLoading,
    isError,
    isPending: isLoading,
    isSuccess: !isLoading && !isError,
    error: null,
    status: isLoading ? 'pending' : isError ? 'error' : 'success',
    fetchStatus: isLoading ? 'fetching' : 'idle',
    dataUpdatedAt: 0,
    errorUpdatedAt: 0,
    failureCount: 0,
    failureReason: null,
    isFetched: !isLoading,
    isFetchedAfterMount: !isLoading,
    isFetching: isLoading,
    isInitialLoading: isLoading,
    isLoadingError: false,
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useIssuePermissions>)
}

function renderPage(issueKey = 'ATLAS-1') {
  return render(
    <QueryClientProvider client={makeClient()}>
      <IssueDetailPage issueKey={issueKey} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 권한별 제목/편집 버튼 제어 (FR-PM-02 Task 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    server.use(
      ...issueHandlers,
      ...issuePermissionHandlers,
      ...issueTypeHandlers,
      ...workflowHandlers,
      ...userHandlers,
    )
  })

  it('T5-D1: UPDATE=true → 이슈 로드 후 제목 수정 버튼이 활성이어야 한다', async () => {
    mockPermissions({ canEdit: true })
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('✎ 제목 수정')).not.toBeDisabled()
    })
  })

  it('T5-D2: UPDATE=false → 제목 수정 버튼이 disabled이어야 한다', async () => {
    mockPermissions({ canEdit: false })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  it('T5-D3: UPDATE=true → 제목 편집 진입 후 저장 버튼이 활성이어야 한다', async () => {
    mockPermissions({ canEdit: true })
    const user = userEvent.setup()
    renderPage()

    // 제목 수정 버튼 클릭 → 편집 모드 진입
    await waitFor(() => screen.getByLabelText('✎ 제목 수정'))
    await user.click(screen.getByLabelText('✎ 제목 수정'))

    const saveButton = screen.getByTestId('issue-title-save')
    expect(saveButton).not.toBeDisabled()
  })

  it('T5-D4: UPDATE=false 로딩 중(isLoading=true) → 제목 수정 버튼이 disabled이어야 한다 (fail-closed)', async () => {
    mockPermissions({ isLoading: true })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  it('T5-D5: UPDATE=false 에러(isError=true) → 제목 수정 버튼이 disabled이어야 한다 (fail-closed)', async () => {
    mockPermissions({ isError: true })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-08 PR-B — 최근 본 이슈 기록 (FR4 · S8/S9 · E5)
  //
  // 새 파일(`issues.$key.recent.test.tsx`)이 아니라 이 파일에 넣는다 — 같은 대상을 두
  // 파일이 나눠 보면 한쪽을 고치는 사람이 다른 쪽을 못 본다(「두 목록이 서로를 안 본다」).
  // 같은 PR의 FR15-b가 `nav-labels` 테스트 2벌을 통합한 것과 같은 판단이다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('최근 본 이슈 기록 (FR-UX-08 PR-B FR4)', () => {
    beforeEach(() => {
      localStorage.clear()
      useRecentIssues.setState({ recentIssueKeys: [] })
      mockPermissions()
    })

    it('T-RV-1 (S8): 이슈 조회에 성공하면 그 키가 최근 목록 맨 앞에 기록된다', async () => {
      renderPage('ATLAS-1')

      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
    })

    it('T-RV-2 (FR4): 조회가 404로 실패하면 기록하지 않는다', async () => {
      // 조회 전에 기록하면 죽은 키가 목록을 오염시키고, 그 키는 다음 마운트에서 또 실패한다.
      renderPage('ATLAS-NOSUCH')

      // 조회가 끝날 시간을 준 뒤에도 목록이 비어 있어야 한다.
      await waitFor(() => {
        expect(screen.queryByText('로딩 중...')).not.toBeInTheDocument()
      })
      expect(useRecentIssues.getState().recentIssueKeys).toEqual([])
    })

    it('T-RV-3 (E5): 같은 이슈를 다시 열어도 목록이 늘어나지 않는다', async () => {
      const first = renderPage('ATLAS-1')
      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
      first.unmount()

      renderPage('ATLAS-1')
      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
    })

    it('T-RV-4 (NFR1): 기록되는 값은 이슈 키뿐이다 — 제목이 저장되지 않는다', async () => {
      renderPage('ATLAS-1')

      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })

      const raw = localStorage.getItem(RECENT_ISSUES_STORAGE_KEY) ?? '[]'
      const parsed: unknown = JSON.parse(raw)
      for (const entry of parsed as unknown[]) {
        expect(entry as string).toMatch(/^[A-Z][A-Z0-9]*-\d+$/)
      }
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-10 F11 Task 5 — 이슈 상세 액션 단축키 7종 등록
  //
  // 판별 파이프라인 자체(Task 1·2)는 keyboard-shortcuts 모듈 테스트가 덮는다.
  // 여기서 재는 것은 **라우트가 무엇을 등록하고 무엇을 거절하는가** 세 가지다.
  //   ① 필드 권한 **두 목록을 모두** 보는가 (FR8 · E6)
  //      — 백엔드가 restrictedFields 와 noneditableFields 를 배타적으로 만들므로
  //        한쪽만 보면 숨긴 필드를 단축키로 열어준다(PR #338 이 실제로 낸 결함).
  //   ② 모달/삭제 확인 중에는 **등록 자체**를 끊는가 (ADR D-5-a · E2)
  //      — 콜백만 끊으면 키를 삼키고(preventDefault) 아무 일도 안 일어난다.
  //   ③ `s`/`w` 가 **포커스 → 클릭** 순서인가 (리뷰 F-2)
  //      — 포커스가 없으면 스크린리더가 aria-pressed 변화를 읽지 않아, 화면을 못 보는
  //        사용자에게는 "아무 일도 안 일어난 것"과 구분되지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('상세 액션 단축키 7종 (FR-UX-10 F11 Task 5)', () => {
    /**
     * 단축키 판별 파이프라인을 테스트 트리에 붙이는 최소 하네스.
     * 실제 앱에서는 RootLayout 이 이 훅을 단 한 번 마운트한다(ADR D-2).
     */
    function ShortcutPipelineHarness(): null {
      useKeyboardShortcuts(true)
      return null
    }

    /** 담당자 PATCH 호출을 세는 스파이 — `i` 의 필드 권한 가드 검증용 */
    let assigneePatchBodies: unknown[] = []

    function renderDetail(issue: IssueResponse = issueAtlas1Fixture) {
      server.use(
        http.get(`/api/v1/issues/${issue.key}`, () => HttpResponse.json({ data: issue })),
        http.patch(`/api/v1/issues/${issue.key}/assignee`, async ({ request }) => {
          const body: unknown = await request.json()
          assigneePatchBodies.push(body)
          const parsed = body as { assigneeId: string | null }
          return HttpResponse.json({
            data: { ...issue, assigneeId: parsed.assigneeId, version: issue.version + 1 },
          })
        }),
      )
      return render(
        <QueryClientProvider client={makeClient()}>
          <ShortcutPipelineHarness />
          <IssueDetailPage issueKey={issue.key} />
        </QueryClientProvider>,
      )
    }

    /** 이슈 로드 완료를 기다린다 — 로드 전에는 컨텍스트가 등록되지 않는다 */
    async function waitForDetailLoaded(): Promise<void> {
      await waitFor(() => {
        expect(screen.getByLabelText('✎ 제목 수정')).toBeInTheDocument()
      })
    }

    /** document 에 keydown 을 흘린다 — 실제 앱과 같은 경로(전역 리스너) */
    async function pressKey(key: string): Promise<void> {
      await act(async () => {
        fireEvent.keyDown(document, { key })
      })
    }

    beforeEach(() => {
      assigneePatchBodies = []
      useContextShortcutsStore.setState({ handlers: {} })
      useAuthStore.setState({ accessToken: mockAccessToken('alice'), user: aliceUser })
      resetIssueWatcherStore()
      setCurrentWatcherUserId(aliceUser.userId)
      mockPermissions({ canEdit: true })
      server.use(
        ...favoriteHandlers,
        ...issueWatcherHandlers,
        ...commentHandlers,
        ...labelHandlers,
        ...keymapHandlers,
        ...componentHandlers,
        ...versionHandlers,
        ...customFieldHandlers,
        ...changelogHandlers,
      )
    })

    afterEach(() => {
      resetIssueWatcherStore()
      useAuthStore.setState({ accessToken: null, user: null })
    })

    // ── 등록 / 게이트 (E2 · ADR D-5-a) ────────────────────────────────────────

    it('T-F11-0: 상세가 로드되면 issue-detail 레이어를 등록한다 (비-공허 짝)', async () => {
      renderDetail()
      await waitForDetailLoaded()

      expect(getRegisteredContexts().has('issue-detail')).toBe(true)
    })

    it('T-F11-1 (E2): 클론 다이얼로그가 열려 있으면 issue-detail 을 등록하지 않는다', async () => {
      const user = userEvent.setup()
      renderDetail()
      await waitForDetailLoaded()

      await user.click(screen.getByLabelText('이슈 클론'))

      expect(getRegisteredContexts().has('issue-detail')).toBe(false)
    })

    it('T-F11-2 (E2): 삭제 확인 중이면 issue-detail 을 등록하지 않는다', async () => {
      const user = userEvent.setup()
      renderDetail()
      await waitForDetailLoaded()

      await user.click(screen.getByLabelText('이슈 삭제'))

      expect(getRegisteredContexts().has('issue-detail')).toBe(false)
    })

    // ── 포커스 이동 4종 (S1 · S3 · S4 · S5) ──────────────────────────────────

    it('T-F11-3 (S1): a 가 담당자 검색 입력에 포커스를 준다', async () => {
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('a')

      expect(screen.getByLabelText('사용자 검색')).toHaveFocus()
    })

    it('T-F11-4 (S5): l 이 라벨 입력에 포커스를 준다', async () => {
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('l')

      expect(screen.getByTestId('label-autocomplete-input')).toHaveFocus()
    })

    it('T-F11-5 (S4): e 가 제목 편집을 열고 현재 제목을 채운다', async () => {
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('e')

      const input = await screen.findByLabelText('제목 편집')
      // 빈 값으로 열리면 Enter 한 번에 제목이 지워진다 — 값 채움까지가 계약이다.
      expect(input).toHaveValue(issueAtlas1Fixture.summary)
    })

    it('T-F11-6 (S3): m 이 댓글 탭을 열고 댓글 입력에 포커스를 준다', async () => {
      // ★비활성 탭 콘텐츠는 Radix Tabs 가 **언마운트**한다. 기본 활성 탭은 「이력」이라
      //   ref 통과만으로는 댓글 입력이 DOM 에 없어 `m` 이 조용히 무동작이 된다.
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('m')

      const textarea = await screen.findByLabelText('댓글 입력')
      expect(textarea).toHaveFocus()
    })

    // ── 토글 2종 — 포커스 먼저, 클릭 나중 (리뷰 F-2) ────────────────────────

    it('T-F11-7 (S6 · F-2): s 는 즐겨찾기 버튼에 포커스를 준 뒤 누른다', async () => {
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('s')

      // 포커스가 먼저 — 이게 없으면 스크린리더가 aria-pressed 변화를 읽지 않는다.
      expect(screen.getByTestId('favorite-button')).toHaveFocus()
      await waitFor(() => {
        expect(screen.getByTestId('favorite-button')).toHaveAttribute('aria-pressed', 'true')
      })
    })

    it('T-F11-8 (S7 · F-2): w 는 관심 버튼에 포커스를 준 뒤 누른다', async () => {
      renderDetail()
      await waitForDetailLoaded()
      // 워처 GET 이 도착하기 전에는 버튼이 disabled 라 헛 POST 가 나가지 않는다(E8).
      await waitFor(() => {
        expect(screen.getByTestId('watch-toggle-button')).toBeEnabled()
      })

      await pressKey('w')

      expect(screen.getByTestId('watch-toggle-button')).toHaveFocus()
      await waitFor(() => {
        expect(screen.getByTestId('watch-toggle-button')).toHaveAttribute('aria-pressed', 'true')
      })
    })

    // ── 나에게 할당 / 해제 (S2) ──────────────────────────────────────────────

    it('T-F11-9 (S2): i 가 담당자를 나로 지정한다', async () => {
      renderDetail()
      await waitForDetailLoaded()

      await pressKey('i')

      await waitFor(() => {
        expect(assigneePatchBodies).toEqual([
          { assigneeId: aliceUser.userId, expectedVersion: issueAtlas1Fixture.version },
        ])
      })
    })

    it('T-F11-10 (S2): 담당자가 이미 나면 i 가 할당을 해제한다', async () => {
      renderDetail({ ...issueAtlas1Fixture, assigneeId: aliceUser.userId })
      await waitForDetailLoaded()

      await pressKey('i')

      await waitFor(() => {
        expect(assigneePatchBodies).toEqual([
          { assigneeId: null, expectedVersion: issueAtlas1Fixture.version },
        ])
      })
    })

    // ── ★필드 권한 두 목록 (FR8 · E6) ────────────────────────────────────────
    //
    // 백엔드 `buildNoneditableKeys` 가 restrictedSet 을 filter 하므로 **열람 숨김 필드는
    // 수정 금지 목록에 절대 오지 않는다.** 따라서 한 목록만 보는 구현은 다른 목록
    // 시나리오에서 그대로 뚫린다 — 두 시나리오를 각각 재야 가드가 증명된다.

    it('T-F11-11 (FR8 · E6 · restrictedFields): 담당자가 열람 숨김이면 a 가 포커스를 옮기지 않는다', async () => {
      renderDetail({ ...issueAtlas1Fixture, restrictedFields: ['assigneeId'] })
      await waitForDetailLoaded()

      await pressKey('a')

      expect(document.body).toHaveFocus()
    })

    it('T-F11-12 (FR8 · E6 · restrictedFields): 담당자가 열람 숨김이면 i 가 할당 요청을 보내지 않는다', async () => {
      // ★`noneditableFields` 만 보는 구현이 정확히 여기서 뚫린다 — 화면에서 숨긴 필드를
      //   단축키가 서버로 바꿔 보내 403 과 원인 불명 토스트가 난다(PR #338 재발 방지).
      renderDetail({ ...issueAtlas1Fixture, restrictedFields: ['assigneeId'] })
      await waitForDetailLoaded()

      await pressKey('i')

      await new Promise((resolve) => setTimeout(resolve, 50))
      expect(assigneePatchBodies).toEqual([])
    })

    it('T-F11-13 (FR8 · E6 · noneditableFields): 담당자가 수정 금지면 i 가 할당 요청을 보내지 않는다', async () => {
      // ★`restrictedFields` 만 보는 구현이 정확히 여기서 뚫린다.
      renderDetail({ ...issueAtlas1Fixture, noneditableFields: ['assigneeId'] })
      await waitForDetailLoaded()

      await pressKey('i')

      await new Promise((resolve) => setTimeout(resolve, 50))
      expect(assigneePatchBodies).toEqual([])
    })

    it('T-F11-14 (FR8 · E6 · noneditableFields): 라벨이 수정 금지면 l 이 포커스를 옮기지 않는다', async () => {
      // 라벨 입력 자체는 `canEdit` 로 disabled 되지 않는다(저장 버튼만 잠긴다).
      // 그래서 라우트가 막지 않으면 커서가 실제로 들어가 「쓸 수 있는 것처럼」 보인다.
      renderDetail({ ...issueAtlas1Fixture, noneditableFields: ['labels'] })
      await waitForDetailLoaded()

      await pressKey('l')

      expect(screen.getByTestId('label-autocomplete-input')).not.toHaveFocus()
    })

    it('T-F11-15 (FR8 · E6 · noneditableFields): 제목이 수정 금지면 e 가 편집을 열지 않는다', async () => {
      renderDetail({ ...issueAtlas1Fixture, noneditableFields: ['summary'] })
      await waitForDetailLoaded()

      await pressKey('e')

      expect(screen.queryByLabelText('제목 편집')).not.toBeInTheDocument()
    })
  })
})
