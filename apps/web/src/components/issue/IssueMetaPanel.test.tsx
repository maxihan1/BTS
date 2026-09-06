// IssueMetaPanel 유형 행 + 셀렉터 + 상태전환 + 우선순위/영향도/환경/라벨 + 담당자 + 즐겨찾기 단위 테스트 — FR-IS-04 D6 Task-5, FR-IS-03 D6 Task-3, FR-IS-09 Task-6, FR-UX-02 D6 Task-6
//
// ## 부채 177 Task 21 — 보드 상세 보기 구성 읽기 (파일 하단 describe). 판정축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 모달 | 모달로 연 상세가 구성을 읽는다 (T21-1) | 고정 목록 ↔ 보드 구성 |
// | ② **사이드패널** | 사이드패널로 연 상세가 **같은** 구성을 읽는다 (T21-2) | 모달에만 반영(R7c 위반) ↔ 두 표현 공통 |
// | ③ 그룹 | 4종에 **서로 다른** 구성이 각자 앉는다 (T21-1·T21-2) | 한 덩어리 목록 ↔ `GENERAL/DATE/PEOPLE/LINKS` (J47) |
// | ④ **순서** | 구성 **순서 그대로** 그린다 (T21-1·T21-2) | `sorted()`·집합 ↔ 저장된 순서 (J48) |
// | ⑤ 빈 그룹 | 구성이 없는 그룹은 **빈 칸을 그리지 않는다** (T21-1·T21-2 의 `LINKS`) | 빈 목록/빈 제목 ↔ 아예 없음 |
// | ⑥ 미설정 대조군 | 구성이 **하나도 없는** 보드는 구획 자체가 없다 (T21-3) | 기본값을 채움 ↔ 없으면 없는 대로(현행 유지) |
// | ⑦ 모르는 키 | 카탈로그 밖 키도 **원문 그대로** 그려진다 (T21-4) | 숨김 ↔ 다른 경로로 저장된 키 보존 |
// | ⑧ 상한 없음 | 한 그룹에 4개 이상도 전부 그린다 (T21-4) | 카드의 0..2(J17) 복사 ↔ 상세는 상한 없음(J48) |
// | ⑨ 열람 제한 | `restrictedFields` 의 필드는 구성에 있어도 안 그린다 (T21-5) | 구성만 보고 값 노출(FR-PM-07 우회) ↔ 제한 우선 |
// | ⑩ 에러 | 구성 조회 실패가 화면에 남고 재시도가 있다 (T21-6) | 조용한 빈 화면 ↔ 안내 + 재시도 |
// | ⑪ 보드 없음 | 보드가 없는 프로젝트는 **아무것도** 그리지 않는다 (T21-7) | 「실패」로 읽어 오류 표시 ↔ 정상 부재 |
//
// ★**① 과 ② 가 이 task 의 판정축이다.** ① 만 두면 「모달에만 반영되고 사이드패널은 그대로인」
// 구현이 통과한다 — 그것이 스펙 **R7c** 가 막으려는 「같은 이슈가 열기 방식에 따라 다르게 보인다」다.
// 실제로 두 축이 갈리는지는 뮤테이션으로 확인했다: `IssueMetaPanel` 이
// `presentation === 'modal'` 일 때만 구획을 그리게 바꾸면 **② 만** red 가 된다(① 은 초록).
//
// ★**④ 는 기대값을 원래 순서와도 사전순과도 다르게 잡았다**(Task 13·19 와 같은 수법).
// `GENERAL: [priority, environment, status]` 는 카탈로그 순서(status→priority→environment)와도
// 사전순(environment→priority→status)과도 다르므로, `sorted()` 를 끼운 구현과 선착순을 유지하는
// 구현이 **동시에** 죽는다.
//
// ★**⑤ 와 ⑥ 은 짝이다.** ⑤ 만 두면 「아무것도 안 그리는」 구현이 통과하고, ⑥ 이 없으면
// 「무엇이든 기본값을 채우는」 구현이 통과한다. ⑩ 과 ⑪ 도 같은 짝이다 — ⑩ 만 두면
// 「구성이 없어도 오류를 띄우는」 구현이, ⑪ 만 두면 「실패를 조용히 삼키는」 구현이 산다.
//
// ★**MSW 는 스텁을 새로 짓지 않고 `mocks/board-handlers.ts` 의 실제 핸들러를 그대로 쓴다** —
// 보드를 시드하고 `replaceDetailViewFields`(실제 PATCH 창구)로 구성을 심는다. 스텁을 손으로
// 지으면 응답 모양이 갈려 「유닛은 초록인데 e2e 를 쓰자마자 red」가 된다.
import type { ReactElement } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import type { IssueResponse, IssueTransition } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { UserSummary } from '@/api/users'
import { IssueMetaPanel } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'
import { nonMemberProjectPermissions } from '@/mocks/project-permission-fixtures'
import { IssueDetailModal } from '@/components/issue/IssueDetailModal'
import { IssueDetailSidePanel } from '@/components/issue/IssueDetailSidePanel'
import { useIssueDetailModalStore } from '@/components/issue/issueDetailModalStore'
import type { IssueDetailPresentation } from '@/components/issue/issueDetailModalStore'
import type { DetailViewFieldGroup } from '@/api/board-settings'
import { replaceDetailViewFields } from '@/api/board-settings'
import { boardLabels } from '@/i18n/board-labels'
import { DEFAULT_BOARD, generateUUID, resetBoardStore, seedBoard } from '@/mocks/board-fixtures'
import { handlers as appHandlers } from '@/mocks/handlers'

// useIssuePermissions를 mock — 기존 테스트는 권한 관련 동작을 검증하지 않으므로 UPDATE/SOFT_DELETE=true로 고정
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))
import { useIssuePermissions } from '@/hooks/use-issue-permissions'

// useLabels를 mock — LabelAutocompleteInput 내부에서 호출, 자동완성 후보 제어
// 기본값: 빈 후보 배열(후보 없음) — 자동완성 테스트에서 setupLabelsMock으로 오버라이드
vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn().mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    status: 'success',
    fetchStatus: 'idle',
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
  }),
}))
import { useLabels } from '@/hooks/use-labels'

// useDebounce를 mock — debounce 없이 즉시 반환해 테스트 단순화
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

// ── T21 대조군 하네스 — 모달/사이드패널 껍데기를 실제 `IssueDetailPage` 와 함께 마운트한다 ──
//
// ★세 mock 은 **껍데기를 띄우기 위한 최소한**이다. 안쪽 상세는 대역으로 갈지 않는다 —
//   `IssueDetailPage` 를 mock 으로 바꾸면 「껍데기는 맞는데 메타패널이 무엇을 그렸는지」가
//   유닛에서 영영 안 보이고, 그것이 이 task 가 재려는 바로 그 자리다.

// 라우터 — 두 껍데기가 쓰는 훅 둘만 갈아 끼운다 (`IssueDetailPresentation.test` 와 같은 배치).
// 이 파일의 기존 테스트는 라우터를 쓰지 않으므로 나머지는 원본 그대로 통과시킨다.
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => () => undefined,
    useRouterState: ({ select }: { select: (state: unknown) => unknown }) =>
      select({ location: { pathname: '/issues' } }),
  }
})

// jsdom 에는 matchMedia 가 없어 `useMediaQuery` 가 항상 false 다 —
// 그러면 사이드패널이 스스로 물러나(좁은 화면 폴백) **대조군의 절반이 아예 안 뜬다**.
vi.mock('@/hooks/use-media-query', () => ({ useMediaQuery: () => true }))

// RichTextEditor 대역 — jsdom 에서 ProseMirror 가 재현되지 않는다 (`routes/issues.$key.test` 와 동일).
vi.mock('@/components/editor/RichTextEditor', async () => ({
  RichTextEditor: (await import('@/test/rich-text-editor-mock')).RichTextEditorMock,
}))

// IssueSecurityLevelSelect 내부 useQuery가 호출하는 security-levels API 핸들러 등록
// 기존 테스트는 보안등급 동작을 검증하지 않으므로 빈 배열로 응답해 UI에 영향 없이 동작하게 한다.
// FavoriteButton 내부 useFavorites()가 GET /api/v1/favorites를 호출한다.
// 단위 테스트 환경에서는 Authorization 헤더가 없어 favoriteHandlers가 401을 반환하므로,
// onUnhandledRequest:'error' 설정과 무관하게 인증 없이 빈 목록을 반환하는 핸들러를 직접 등록한다.
beforeEach(() => {
  server.use(
    http.get('/api/v1/projects/:projectKey/issue-security-scheme/levels', () =>
      HttpResponse.json({ levels: [] }),
    ),
    http.get('/api/v1/favorites', () =>
      HttpResponse.json({ data: { items: [] } }),
    ),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트용 전환 목록 픽스처 — open 상태에서 2개 */
const transitionsFixture: IssueTransition[] = [
  { key: 'open__in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
  { key: 'open__closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
]

/** 테스트용 이슈 픽스처 — typeId=1(bug), priority=3, impact=null, labels=[], environment=null, assigneeId=null */
const issueFixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

/** 테스트용 사용자 목록 픽스처 */
const usersFixture: UserSummary[] = [
  { id: '00000000-0000-4000-8000-000000000001', username: 'alice', displayName: '김앨리스', email: null },
  { id: '00000000-0000-4000-8000-000000000002', username: 'bob', displayName: null, email: null },
]

/** 테스트용 이슈 타입 목록 픽스처 */
const availableTypes: IssueTypeResponse[] = [
  { id: 1, key: 'bug', name: '버그', description: '버그', iconName: 'bug' },
  { id: 2, key: 'story', name: '스토리', description: '스토리', iconName: 'story' },
  { id: 3, key: 'task', name: '작업', description: '작업', iconName: 'task' },
]

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 기존 테스트 기본값 — 모든 권한 true (UPDATE/SOFT_DELETE/TRANSITION) */
function setupFullPermissions() {
  vi.mocked(useIssuePermissions).mockReturnValue({
    data: {
      issueKey: 'ATLAS-1',
      permissions: { UPDATE: true, SOFT_DELETE: true, TRANSITION: true },
    },
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    status: 'success',
    fetchStatus: 'idle',
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
  } as unknown as ReturnType<typeof useIssuePermissions>)
}

function renderPanel(
  issue: IssueResponse = issueFixture,
  types: IssueTypeResponse[] = availableTypes,
  onTypeChange = vi.fn(),
  onDeleteClick = vi.fn(),
  transitions: IssueTransition[] = transitionsFixture,
  onTransition = vi.fn(),
  isTransitioning = false,
  onPriorityChange = vi.fn(),
  onImpactChange = vi.fn(),
  onEnvironmentSave = vi.fn(),
  onLabelsSave = vi.fn(),
  users: UserSummary[] = [],
  onAssigneeSearch = vi.fn(),
  onAssigneeChange = vi.fn(),
  currentAssignee: UserSummary | null = null,
) {
  // 기존 테스트는 권한 제어를 검증하지 않으므로 모든 권한 true로 세팅
  setupFullPermissions()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  function buildPanel(panelIssue: IssueResponse = issue) {
    return (
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={panelIssue}
          reporter={null}
          availableTypes={types}
          onTypeChange={onTypeChange}
          onDeleteClick={onDeleteClick}
          onCloneClick={vi.fn()}
          transitions={transitions}
          onTransition={onTransition}
          isTransitioning={isTransitioning}
          onPriorityChange={onPriorityChange}
          onImpactChange={onImpactChange}
          onEnvironmentSave={onEnvironmentSave}
          onLabelsSave={onLabelsSave}
          users={users}
          onAssigneeSearch={onAssigneeSearch}
          onAssigneeChange={onAssigneeChange}
          currentAssignee={currentAssignee}
        />
      </QueryClientProvider>
    )
  }

  const result = render(buildPanel())

  return {
    ...result,
    // rerender를 래핑해 QueryClientProvider 컨텍스트를 유지한다.
    // 기존 테스트에서 rerender(element)는 QueryClientProvider 없이 호출하므로
    // 래핑 rerender는 element에서 IssueMetaPanel props를 추출하지 않고
    // 전체 재렌더를 위해 buildPanel(issue)를 사용한다.
    rerender: (element: ReactElement) => result.rerender(
      <QueryClientProvider client={client}>{element}</QueryClientProvider>,
    ),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// IMP-1. 유형 행 — IssueTypeIcon + typeName 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 유형 행 렌더', () => {
  /**
   * IMP-1: 유형 레이블("유형")이 렌더된다.
   */
  it('IMP-1: 유형 레이블이 렌더된다', () => {
    renderPanel()
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText('유형')).toBeInTheDocument()
  })

  /**
   * IMP-2: 현재 이슈의 typeName이 span으로 표시된다.
   * issueFixture.typeId=1 → availableTypes에서 id=1 → name="버그"
   * 셀렉터 option에도 "버그"가 있으므로 span 요소로 범위를 좁힌다.
   */
  it('IMP-2: 현재 이슈의 typeName을 표시한다', () => {
    renderPanel()
    // data-testid="issue-type-name" span으로 정확하게 찾음 (option 텍스트와 구분)
    const typeNameSpan = screen.getByTestId('issue-type-name')
    expect(typeNameSpan).toBeInTheDocument()
    expect(typeNameSpan.textContent).toBe('버그')
  })

  /**
   * IMP-3: IssueTypeIcon이 렌더된다 — issue.typeId에 해당하는 iconName을 사용.
   * iconName="bug" → role="img" aria-label="버그"
   */
  it('IMP-3: 현재 타입의 아이콘이 렌더된다', () => {
    renderPanel()
    // IssueTypeIcon은 role="img" aria-label={typeName}으로 렌더됨
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-4. 셀렉터 — availableTypes 옵션 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 셀렉터 옵션', () => {
  /**
   * IMP-4: 셀렉터에 availableTypes 옵션이 모두 노출된다.
   */
  it('IMP-4: 셀렉터에 availableTypes 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '버그' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '스토리' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '작업' })).toBeInTheDocument()
  })

  /**
   * IMP-5: 셀렉터의 현재 선택값은 issue.typeId로 props 파생된다.
   * issue.typeId=1 → value="1"
   */
  it('IMP-5: 셀렉터 현재 선택값이 issue.typeId와 일치한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ }) as HTMLSelectElement
    expect(select.value).toBe('1')
  })

  /**
   * IMP-6: issue가 바뀌면(다른 typeId props) 셀렉터 값도 따라 바뀐다 — stale key prop 회귀 가드.
   * useState(issue.typeId) 초기화 패턴이 있으면 이 테스트가 실패한다.
   */
  it('IMP-6: issue props가 바뀌면 셀렉터 선택값도 따라 바뀐다 (stale 회귀 가드)', () => {
    const { rerender } = renderPanel()

    const issueTypeChanged: IssueResponse = { ...issueFixture, typeId: 2, typeKey: 'story', typeName: '스토리' }
    rerender(
      <IssueMetaPanel
        issue={issueTypeChanged}
        reporter={null}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        onCloneClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        currentAssignee={null}
      />,
    )

    const select = screen.getByRole('combobox', { name: /유형/ }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-7. 타입 변경 — onTypeChange 콜백 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 타입 변경 콜백', () => {
  /**
   * IMP-7: 다른 타입 선택 시 onTypeChange(typeId)가 해당 typeId(number)로 호출된다.
   */
  it('IMP-7: 다른 타입 선택 시 onTypeChange가 typeId(number)로 호출된다', async () => {
    const onTypeChange = vi.fn()
    renderPanel(issueFixture, availableTypes, onTypeChange)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: /유형/ })
    await user.selectOptions(select, '2')

    expect(onTypeChange).toHaveBeenCalledOnce()
    expect(onTypeChange).toHaveBeenCalledWith(2)
  })

  /**
   * IMP-8: 현재와 같은 타입 선택 시에는 onTypeChange가 호출되지 않는다.
   */
  it('IMP-8: 현재와 같은 타입 선택 시 onTypeChange가 호출되지 않는다', async () => {
    const onTypeChange = vi.fn()
    renderPanel(issueFixture, availableTypes, onTypeChange)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: /유형/ })
    await user.selectOptions(select, '1') // 현재 typeId=1과 동일

    expect(onTypeChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-9. 접근성 — WCAG AA
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 접근성', () => {
  /**
   * IMP-9: 셀렉터에 aria-label이 있다.
   */
  it('IMP-9: 셀렉터에 aria-label이 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select).toHaveAttribute('aria-label')
  })

  /**
   * IMP-10: 셀렉터의 min-height가 44px 이상이다 (WCAG AA 터치 타깃).
   */
  it('IMP-10: 셀렉터에 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-11~17. 상태전환 컨트롤 — FR-IS-01 Task-4
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 상태전환 컨트롤 렌더', () => {
  /**
   * IMP-11: 가용전환 목록이 있으면 전환 셀렉터가 렌더된다.
   */
  it('IMP-11: 가용전환이 있을 때 전환 셀렉터가 렌더된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toBeInTheDocument()
  })

  /**
   * IMP-12: 전환 셀렉터에 가용전환 name이 옵션으로 노출된다.
   * options: placeholder + "Start Work" + "Cancel"
   */
  it('IMP-12: 전환 셀렉터에 가용전환 name 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: 'Start Work' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: 'Cancel' })).toBeInTheDocument()
  })

  /**
   * IMP-13: 전환 선택 시 onTransition 이 **고른 전환 자체**로 호출된다.
   *
   * `toStateKey` 만 통과시키면 같은 상태쌍의 두 전환을 호출부가 되찾을 수 없다
   * (ADR 2026-08-18 로 `UNIQUE(workflow_id, from, to)` 해제).
   */
  it('IMP-13: 전환 선택 시 onTransition 이 고른 전환으로 호출된다', async () => {
    const onTransition = vi.fn()
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, onTransition)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    // ★값 문자열을 리터럴로 쓰지 않는다 — 형식은 `lib/transition-key.ts` 정본 소관이다.
    await user.selectOptions(
      select,
      screen.getByRole('option', { name: transitionsFixture[0]!.name }),
    )

    expect(onTransition).toHaveBeenCalledOnce()
    expect(onTransition).toHaveBeenCalledWith(transitionsFixture[0])
  })

  /**
   * IMP-14: isTransitioning=true 시 전환 셀렉터가 disabled 상태가 된다 (중복클릭 방지, NFR3).
   */
  it('IMP-14: isTransitioning=true 시 전환 셀렉터가 disabled가 된다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, vi.fn(), true)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toBeDisabled()
  })
})

describe('IssueMetaPanel — 가용전환 0건 (종료상태 S6)', () => {
  /**
   * IMP-15: 가용전환 0건이면 전환 셀렉터가 렌더되지 않는다.
   */
  it('IMP-15: 가용전환 0건이면 전환 셀렉터가 렌더되지 않는다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), [])
    expect(screen.queryByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).not.toBeInTheDocument()
  })

  /**
   * IMP-16: 가용전환 0건이면 "더 진행할 전환 없음" 안내 문구가 렌더된다.
   */
  it('IMP-16: 가용전환 0건이면 "더 진행할 전환 없음" 안내가 렌더된다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), [])
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E5 구분 검증 — unavailableReason prop: 'no-workflow' vs 'terminal' vs null
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — E5 미설정(no-workflow) vs 종료상태(terminal) 구분', () => {
  /**
   * IMP-18: unavailableReason='no-workflow' 시 미설정 안내문구가 렌더된다.
   */
  it('IMP-18: unavailableReason=no-workflow 시 transitionWorkflowNotConfiguredError 문구가 렌더된다', () => {
    setupFullPermissions()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueFixture}
          reporter={null}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={[]}
          onTransition={vi.fn()}
          isTransitioning={false}
          unavailableReason="no-workflow"
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={null}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  /**
   * IMP-19: unavailableReason='terminal'(또는 null) 시 noTransitionsAvailable 문구가 렌더된다.
   */
  it('IMP-19: unavailableReason=terminal 시 noTransitionsAvailable 문구가 렌더된다', () => {
    setupFullPermissions()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueFixture}
          reporter={null}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={[]}
          onTransition={vi.fn()}
          isTransitioning={false}
          unavailableReason="terminal"
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={null}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).not.toBeInTheDocument()
  })

  /**
   * IMP-20: 전환이 있으면 unavailableReason과 무관하게 셀렉터가 렌더된다.
   */
  it('IMP-20: 전환이 있으면 unavailableReason=no-workflow여도 셀렉터가 렌더된다', () => {
    setupFullPermissions()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueFixture}
          reporter={null}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={transitionsFixture}
          onTransition={vi.fn()}
          isTransitioning={false}
          unavailableReason="no-workflow"
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={null}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument()
  })
})

describe('IssueMetaPanel — 전환 셀렉터 접근성 (WCAG AA)', () => {
  /**
   * IMP-17: 전환 셀렉터에 aria-label이 있고 min-h-[44px] 클래스가 있다.
   */
  it('IMP-17: 전환 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 우선순위 셀렉터 (IMP-21~26)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 우선순위 셀렉터', () => {
  /**
   * IMP-21: 우선순위 레이블이 렌더된다.
   */
  it('IMP-21: 우선순위 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.priorityLabel)).toBeInTheDocument()
  })

  /**
   * IMP-22: 우선순위 셀렉터에 1~5 옵션이 모두 있다.
   */
  it('IMP-22: 우선순위 셀렉터에 1~5 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[1] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[2] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[3] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[4] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[5] })).toBeInTheDocument()
  })

  /**
   * IMP-23: 셀렉터 현재값이 issue.priority props에서 파생된다 (priority=3 → '3').
   */
  it('IMP-23: 셀렉터 현재값이 issue.priority와 일치한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('3')
  })

  /**
   * IMP-24: issue.priority가 바뀌면 셀렉터 값도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-24: issue.priority props가 바뀌면 셀렉터 값도 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issuePriorityChanged: IssueResponse = { ...issueFixture, priority: 1, priorityName: 'Highest' }
    rerender(
      <IssueMetaPanel
        issue={issuePriorityChanged}
        reporter={null}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        onCloneClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        currentAssignee={null}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('1')
  })

  /**
   * IMP-25: 다른 우선순위 선택 시 onPriorityChange(number)가 호출된다.
   */
  it('IMP-25: 우선순위 변경 시 onPriorityChange가 number로 호출된다', async () => {
    const onPriorityChange = vi.fn()
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, vi.fn(), false, onPriorityChange)
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    await user.selectOptions(select, '1')
    expect(onPriorityChange).toHaveBeenCalledOnce()
    expect(onPriorityChange).toHaveBeenCalledWith(1)
  })

  /**
   * IMP-26: 우선순위 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다 (WCAG AA).
   */
  it('IMP-26: 우선순위 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 영향도 셀렉터 (IMP-27~34)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 영향도 셀렉터', () => {
  /**
   * IMP-27: 영향도 레이블이 렌더된다.
   */
  it('IMP-27: 영향도 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.impactLabel)).toBeInTheDocument()
  })

  /**
   * IMP-28: impact=null이면 "미지정" 옵션이 활성화(enabled) 상태로 있다.
   */
  it('IMP-28: impact=null이면 미지정 옵션이 enabled 상태로 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    const unsetOption = within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).toBeInTheDocument()
    expect(unsetOption).not.toBeDisabled()
  })

  /**
   * IMP-29: impact=null이면 셀렉터 현재값이 '' (미지정 선택됨).
   */
  it('IMP-29: impact=null이면 셀렉터가 미지정을 표시한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('')
  })

  /**
   * IMP-30: impact가 설정된 상태(impact=2)이면 "미지정" 옵션이 disabled이다 (클리어 불가 제약).
   */
  it('IMP-30: impact가 설정된 상태이면 미지정 옵션이 disabled이다', () => {
    const issueWithImpact: IssueResponse = { ...issueFixture, impact: 2, impactName: 'Medium' }
    renderPanel(issueWithImpact)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    const unsetOption = within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).toBeDisabled()
  })

  /**
   * IMP-31: 영향도 셀렉터에 1~3 옵션이 모두 있다.
   */
  it('IMP-31: 영향도 셀렉터에 1~3 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[1] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[2] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[3] })).toBeInTheDocument()
  })

  /**
   * IMP-32: impact=2이면 셀렉터 현재값이 '2'이다.
   */
  it('IMP-32: impact=2이면 셀렉터 현재값이 "2"이다', () => {
    const issueWithImpact: IssueResponse = { ...issueFixture, impact: 2, impactName: 'Medium' }
    renderPanel(issueWithImpact)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })

  /**
   * IMP-33: 영향도 선택 시 onImpactChange(number)가 호출된다.
   */
  it('IMP-33: 영향도 변경 시 onImpactChange가 number로 호출된다', async () => {
    const onImpactChange = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      onImpactChange,
    )
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    await user.selectOptions(select, '1')
    expect(onImpactChange).toHaveBeenCalledOnce()
    expect(onImpactChange).toHaveBeenCalledWith(1)
  })

  /**
   * IMP-34: 영향도 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다 (WCAG AA).
   */
  it('IMP-34: 영향도 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 환경(environment) 편집 (IMP-35~39)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 환경 편집', () => {
  /**
   * IMP-35: 환경 레이블이 렌더된다.
   */
  it('IMP-35: 환경 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.environmentLabel)).toBeInTheDocument()
  })

  /**
   * IMP-36: environment=null이면 textarea가 비어 있다.
   */
  it('IMP-36: environment=null이면 textarea가 비어 있다', () => {
    renderPanel()
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('')
  })

  /**
   * IMP-37: issue.environment가 설정된 경우 textarea에 현재값이 표시된다.
   */
  it('IMP-37: environment 값이 있으면 textarea에 표시된다', () => {
    const issueWithEnv: IssueResponse = { ...issueFixture, environment: 'Chrome 125 / macOS 14' }
    renderPanel(issueWithEnv)
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Chrome 125 / macOS 14')
  })

  /**
   * IMP-38: 환경 편집 후 저장 버튼 클릭 시 onEnvironmentSave가 호출된다.
   */
  it('IMP-38: 저장 버튼 클릭 시 onEnvironmentSave가 편집값으로 호출된다', async () => {
    const onEnvironmentSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      onEnvironmentSave,
    )
    const user = userEvent.setup()
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder)
    await user.clear(textarea)
    await user.type(textarea, 'Firefox 126')
    const envSection = screen.getByTestId('environment-section')
    const saveBtn = within(envSection).getByRole('button', { name: issueDetailStrings.environmentSaveButton })
    await user.click(saveBtn)
    expect(onEnvironmentSave).toHaveBeenCalledOnce()
    expect(onEnvironmentSave).toHaveBeenCalledWith('Firefox 126')
  })

  /**
   * IMP-39: issue.environment props가 바뀌면 textarea 값도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-39: issue.environment props가 바뀌면 textarea 값이 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issueEnvChanged: IssueResponse = { ...issueFixture, environment: 'Safari 17' }
    rerender(
      <IssueMetaPanel
        issue={issueEnvChanged}
        reporter={null}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        onCloneClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        currentAssignee={null}
      />,
    )
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Safari 17')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 라벨(labels) 칩 (IMP-40~48)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 라벨 칩', () => {
  /**
   * IMP-40: 라벨 레이블이 렌더된다.
   */
  it('IMP-40: 라벨 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.labelsLabel)).toBeInTheDocument()
  })

  /**
   * IMP-41: issue.labels에 있는 칩이 렌더된다.
   */
  it('IMP-41: issue.labels의 각 라벨이 칩으로 렌더된다', () => {
    const issueWithLabels: IssueResponse = { ...issueFixture, labels: ['bug', 'urgent'] }
    renderPanel(issueWithLabels)
    expect(screen.getByText('bug')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
  })

  /**
   * IMP-42: 라벨 추가 입력 필드가 있다.
   */
  it('IMP-42: 라벨 추가 입력 필드가 있다', () => {
    renderPanel()
    expect(screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)).toBeInTheDocument()
  })

  /**
   * IMP-43: 라벨 추가 후 저장 시 onLabelsSave가 새 라벨 배열로 호출된다.
   */
  it('IMP-43: 라벨 추가 후 저장 시 onLabelsSave가 새 배열로 호출된다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    await user.type(input, 'new-label')
    await user.keyboard('{Enter}')
    const labelsSection = screen.getByTestId('labels-section')
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledOnce()
    expect(onLabelsSave).toHaveBeenCalledWith(['new-label'])
  })

  /**
   * IMP-44: 라벨 제거 버튼 클릭 후 저장 시 해당 라벨이 제거된 배열로 onLabelsSave가 호출된다.
   */
  it('IMP-44: 라벨 제거 후 저장 시 해당 라벨이 빠진 배열로 onLabelsSave가 호출된다', async () => {
    const onLabelsSave = vi.fn()
    const issueWithLabels: IssueResponse = { ...issueFixture, labels: ['bug', 'urgent'] }
    renderPanel(
      issueWithLabels,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    // 'bug' 라벨 제거 버튼 클릭 (aria-label='라벨 제거' 버튼 중 첫 번째)
    const removeBtns = within(labelsSection).getAllByRole('button', { name: issueDetailStrings.labelRemoveLabel })
    await user.click(removeBtns[0] as HTMLElement)
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledOnce()
    expect(onLabelsSave).toHaveBeenCalledWith(['urgent'])
  })

  /**
   * IMP-45: 라벨이 50자를 초과하면 추가되지 않는다 (클라이언트 검증).
   */
  it('IMP-45: 50자 초과 라벨은 추가되지 않는다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    const longLabel = 'a'.repeat(51)
    await user.type(input, longLabel)
    await user.keyboard('{Enter}')
    // 저장해도 onLabelsSave 호출 안 됨 (또는 []로 호출됨 — 칩이 추가 안 됐으므로)
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledWith([])
  })

  /**
   * IMP-46: 이미 20개 라벨이 있으면 추가 입력 필드가 disabled이다.
   */
  it('IMP-46: 라벨이 20개이면 추가 입력 필드가 disabled이다', () => {
    const labels = Array.from({ length: 20 }, (_, i) => `label-${i}`)
    const issueMaxLabels: IssueResponse = { ...issueFixture, labels }
    renderPanel(issueMaxLabels)
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    expect(input).toBeDisabled()
  })

  /**
   * IMP-47: 공백만인 라벨은 추가되지 않는다 (trim 검증).
   */
  it('IMP-47: 공백만인 라벨은 추가되지 않는다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    await user.type(input, '   ')
    await user.keyboard('{Enter}')
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledWith([])
  })

  /**
   * IMP-48: issue.labels props가 바뀌면 표시 라벨도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-48: issue.labels props가 바뀌면 표시 라벨도 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issueLabelsChanged: IssueResponse = { ...issueFixture, labels: ['refactored'] }
    rerender(
      <IssueMetaPanel
        issue={issueLabelsChanged}
        reporter={null}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        onCloneClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        currentAssignee={null}
      />,
    )
    expect(screen.getByText('refactored')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-IS-03 Task 3 — 담당자 셀렉터 (IMP-50~57)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 담당자 셀렉터', () => {
  /**
   * IMP-50: 담당자 레이블이 렌더된다.
   */
  it('IMP-50: 담당자 레이블이 렌더된다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).getByText(issueDetailStrings.assigneeLabel)).toBeInTheDocument()
  })

  /**
   * IMP-51: assigneeId=null이면 "미지정" 텍스트가 표시된다.
   */
  it('IMP-51: assigneeId=null이면 미지정 텍스트가 표시된다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).getByText(issueDetailStrings.assigneeUnassigned)).toBeInTheDocument()
  })

  /**
   * IMP-52: currentAssignee prop이 주어지면 displayName을 표시한다.
   * C1 수정: 검색결과(users)가 아니라 currentAssignee prop에서 이름을 읽는다.
   */
  it('IMP-52: currentAssignee prop이 있으면 displayName을 표시한다', () => {
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users는 빈 배열 — 검색결과에 담당자가 없어도 currentAssignee로 표시돼야 한다 (C1 회귀가드)
      vi.fn(),
      vi.fn(),
      alice, // currentAssignee prop
    )
    // data-testid="assignee-current-name" span으로 정확히 확인 (목록 버튼과 중복 방지)
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('김앨리스')
  })

  /**
   * IMP-53: currentAssignee prop이 있고 displayName이 null이면 username을 표시한다.
   */
  it('IMP-53: currentAssignee가 있고 displayName=null이면 username을 표시한다', () => {
    const bob = usersFixture[1]
    if (!bob) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: bob.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users 빈 배열 — C1 회귀가드
      vi.fn(),
      vi.fn(),
      bob, // currentAssignee prop
    )
    // data-testid="assignee-current-name" span으로 정확히 확인 (목록 버튼과 중복 방지)
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('bob')
  })

  /**
   * IMP-58: C1 회귀가드 — 현재 담당자가 검색결과 목록(users)에 없어도
   * currentAssignee prop으로 이름이 표시된다.
   * 이것이 핵심 버그 수정 검증이다: 검색어를 바꾸거나 초기 로드 시
   * 담당자가 50건(MAX_RESULTS) 밖에 있어도 미지정으로 잘못 표시되지 않는다.
   */
  it('IMP-58: C1 회귀가드 — users에 없어도 currentAssignee prop으로 이름이 표시된다', () => {
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users 완전히 비어 있음 (검색결과 없음)
      vi.fn(),
      vi.fn(),
      alice, // currentAssignee prop으로 이름 제공
    )
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('김앨리스')
    // "미지정"이 표시되면 안 된다
    expect(currentNameEl.textContent).not.toBe(issueDetailStrings.assigneeUnassigned)
  })

  /**
   * IMP-54: 검색 input에 입력 시 onAssigneeSearch 콜백이 호출된다.
   */
  it('IMP-54: 검색 input 입력 시 onAssigneeSearch가 호출된다', async () => {
    const onAssigneeSearch = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [],
      onAssigneeSearch,
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const searchInput = within(assigneeSection).getByPlaceholderText(issueDetailStrings.assigneeSearchPlaceholder)
    await user.type(searchInput, 'ali')
    expect(onAssigneeSearch).toHaveBeenCalled()
  })

  /**
   * IMP-55: 사용자 목록에서 항목 선택 시 onAssigneeChange(userId)가 호출된다.
   */
  it('IMP-55: 사용자 선택 시 onAssigneeChange(userId)가 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      usersFixture,
      vi.fn(),
      onAssigneeChange,
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const aliceBtn = within(assigneeSection).getByRole('button', { name: '김앨리스' })
    await user.click(aliceBtn)
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(usersFixture[0]?.id)
  })

  /**
   * IMP-56: 담당자가 있을 때 해제 버튼 클릭 시 onAssigneeChange(null)이 호출된다.
   */
  it('IMP-56: 담당자 해제 버튼 클릭 시 onAssigneeChange(null)이 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      usersFixture,
      vi.fn(),
      onAssigneeChange,
      alice, // currentAssignee prop
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const unassignBtn = within(assigneeSection).getByRole('button', { name: issueDetailStrings.assigneeUnassignButton })
    await user.click(unassignBtn)
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(null)
  })

  /**
   * IMP-57: assigneeId=null이면 담당자 해제 버튼이 표시되지 않는다 (미할당 상태).
   */
  it('IMP-57: assigneeId=null이면 해제 버튼이 없다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).queryByRole('button', { name: issueDetailStrings.assigneeUnassignButton })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-IS-07 B9b — 해결 결과(resolution) 표시 (IMP-60~62)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 해결 결과(resolution) 표시 (FR-IS-07 B9b)', () => {
  /**
   * IMP-60: issue.resolution이 있으면 data-testid="issue-resolution"이 렌더되고
   * resolution.name이 표시된다.
   */
  it('IMP-60: resolution이 있으면 issue-resolution 요소에 resolution.name이 표시된다', () => {
    const issueWithResolution: IssueResponse = {
      ...issueFixture,
      currentStateKey: 'done',
      resolution: { id: '00000000-0000-4000-8000-000000000001', key: 'fixed', name: 'Fixed' },
    }
    renderPanel(issueWithResolution)
    const resolutionEl = screen.getByTestId('issue-resolution')
    expect(resolutionEl).toBeInTheDocument()
    expect(resolutionEl).toHaveTextContent('Fixed')
  })

  /**
   * IMP-61: issue.resolution이 null이면 data-testid="issue-resolution"이 렌더되지 않는다.
   */
  it('IMP-61: resolution이 null이면 issue-resolution 요소가 렌더되지 않는다', () => {
    renderPanel(issueFixture) // issueFixture.resolution은 undefined (미설정)
    expect(screen.queryByTestId('issue-resolution')).not.toBeInTheDocument()
  })

  /**
   * IMP-62: issue.resolution이 undefined이면 data-testid="issue-resolution"이 렌더되지 않는다.
   */
  it('IMP-62: resolution이 undefined이면 issue-resolution 요소가 렌더되지 않는다', () => {
    const issueNoResolution: IssueResponse = { ...issueFixture, resolution: undefined }
    renderPanel(issueNoResolution)
    expect(screen.queryByTestId('issue-resolution')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-PM-02 C1 — 담당자 canEdit 게이트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 담당자 canEdit 게이트 (FR-PM-02 C1)', () => {
  /** UPDATE=false 권한 mock 설정 헬퍼 */
  function setupNoEditPermissions() {
    vi.mocked(useIssuePermissions).mockReturnValue({
      data: {
        issueKey: 'ATLAS-1',
        permissions: { UPDATE: false, SOFT_DELETE: false, TRANSITION: false },
      },
      isLoading: false,
      isError: false,
      isPending: false,
      isSuccess: true,
      error: null,
      status: 'success',
      fetchStatus: 'idle',
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
    } as unknown as ReturnType<typeof useIssuePermissions>)
  }

  /**
   * IMP-58: UPDATE=false이면 담당자 검색 input이 disabled된다.
   */
  it('IMP-58: UPDATE=false이면 담당자 검색 input이 disabled된다', () => {
    setupNoEditPermissions()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueFixture}
          reporter={null}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={transitionsFixture}
          onTransition={vi.fn()}
          isTransitioning={false}
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={null}
        />
      </QueryClientProvider>,
    )
    const assigneeSection = screen.getByTestId('assignee-section')
    const searchInput = within(assigneeSection).getByRole('textbox', { name: issueDetailStrings.assigneeSearchPlaceholder })
    expect(searchInput).toBeDisabled()
  })

  /**
   * IMP-59: UPDATE=false이고 담당자가 있을 때 해제 버튼이 disabled된다.
   */
  it('IMP-59: UPDATE=false이면 담당자 해제 버튼이 disabled된다', () => {
    setupNoEditPermissions()
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueWithAssignee}
          reporter={null}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={transitionsFixture}
          onTransition={vi.fn()}
          isTransitioning={false}
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={alice}
        />
      </QueryClientProvider>,
    )
    const assigneeSection = screen.getByTestId('assignee-section')
    const unassignBtn = within(assigneeSection).getByRole('button', { name: issueDetailStrings.assigneeUnassignButton })
    expect(unassignBtn).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-IS-09 Task-6 — LabelAutocompleteInput 자동완성 통합 (IMP-70~74)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 라벨 자동완성 통합 (FR-IS-09 Task-6)', () => {
  /** useLabels mock을 자동완성 후보 배열로 설정하는 헬퍼 */
  function setupLabelsMock(candidates: string[]) {
    vi.mocked(useLabels).mockReturnValue({
      data: candidates,
      isLoading: false,
      isError: false,
      isPending: false,
      isSuccess: true,
      error: null,
      status: 'success',
      fetchStatus: 'idle',
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
    } as unknown as ReturnType<typeof useLabels>)
  }

  /**
   * IMP-70: 라벨 편집 영역에 LabelAutocompleteInput이 렌더된다.
   * data-testid="label-autocomplete-input"으로 확인.
   */
  it('IMP-70: 라벨 편집 영역에 LabelAutocompleteInput이 렌더된다', () => {
    setupLabelsMock([])
    renderPanel()
    expect(screen.getByTestId('label-autocomplete-input')).toBeInTheDocument()
  })

  /**
   * IMP-71: 입력 시 useLabels 후보가 드롭다운으로 표시된다.
   */
  it('IMP-71: 입력 시 useLabels 후보가 드롭다운으로 표시된다', async () => {
    setupLabelsMock(['bug', 'urgent', 'backend'])
    renderPanel()
    const user = userEvent.setup()
    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'bu')
    // 드롭다운 후보 목록이 나타난다
    expect(screen.getByTestId('label-autocomplete-dropdown')).toBeInTheDocument()
    expect(screen.getByTestId('label-option-bug')).toBeInTheDocument()
  })

  /**
   * IMP-72: 후보 클릭(onCommit) 시 칩이 추가된다.
   */
  it('IMP-72: 자동완성 후보 클릭 시 칩이 추가된다', async () => {
    setupLabelsMock(['bug', 'urgent'])
    renderPanel()
    const user = userEvent.setup()
    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'b')
    const option = screen.getByTestId('label-option-bug')
    await user.click(option)
    // 칩이 추가됐는지 확인
    const labelsSection = screen.getByTestId('labels-section')
    expect(within(labelsSection).getByText('bug')).toBeInTheDocument()
  })

  /**
   * IMP-73: 신규 라벨 입력 후 Enter(free-form) 시 칩이 추가된다.
   */
  it('IMP-73: 신규 라벨 입력 후 Enter 시 칩이 추가된다 (free-form)', async () => {
    setupLabelsMock([])
    renderPanel()
    const user = userEvent.setup()
    const input = screen.getByTestId('label-autocomplete-input')
    await user.type(input, 'new-label')
    await user.keyboard('{Enter}')
    const labelsSection = screen.getByTestId('labels-section')
    expect(within(labelsSection).getByText('new-label')).toBeInTheDocument()
  })

  /**
   * IMP-74: 저장 버튼(data-testid="labels-save") 클릭 시 onLabelsSave가 칩 배열로 호출된다.
   */
  it('IMP-74: 저장 버튼 클릭 시 onLabelsSave가 추가된 칩 배열로 호출된다', async () => {
    setupLabelsMock([])
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const input = screen.getByTestId('label-autocomplete-input')
    await user.type(input, 'autocomplete-label')
    await user.keyboard('{Enter}')
    const labelsSection = screen.getByTestId('labels-section')
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledOnce()
    expect(onLabelsSave).toHaveBeenCalledWith(['autocomplete-label'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-02 Task-6 — 즐겨찾기 버튼 (IMP-80)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 즐겨찾기 버튼 (FR-UX-02 Task-6)', () => {
  /**
   * IMP-80: IssueMetaPanel이 FavoriteButton을 targetType="ISSUE", targetId=issue.key로 렌더한다.
   * data-testid="favorite-button"으로 존재를 확인하고
   * aria-pressed=false (즐겨찾기 미등록 초기 상태)임을 단언한다.
   */
  it('IMP-80: FavoriteButton이 targetType=ISSUE, targetId=issue.key로 렌더된다', async () => {
    renderPanel()
    // FavoriteButton은 data-testid="favorite-button"을 갖는다 (FavoriteButton.tsx L98)
    const btn = await screen.findByTestId('favorite-button')
    expect(btn).toBeInTheDocument()
    // 즐겨찾기 미등록 초기 상태 — GET /api/v1/favorites → items:[] 이므로 aria-pressed=false
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-PF-01 Task 8 — 생성일/수정일 표시가 사용자 dateFormat 프리셋을 따른다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 날짜 표시 프리셋 반영 (FR-PF-01 Task 8)', () => {
  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  /**
   * IMP-81: 로그인 사용자의 dateFormat='us'이면 생성일이 MM/DD/YYYY 순서로 표시된다.
   * createdAt='2026-07-08T03:00:00Z' → KST 2026-07-08T12:00:00+09:00 → us 프리셋 "07/08/2026".
   */
  it('IMP-81: dateFormat=us이면 생성일이 MM/DD/YYYY로 표시된다', () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-token',
      user: { ...aliceUser, dateFormat: 'us' },
    })
    const issueUs: IssueResponse = { ...issueFixture, createdAt: '2026-07-08T03:00:00Z' }
    renderPanel(issueUs)
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText(/07\/08\/2026/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 클론 버튼 CREATE 게이트
//
// 이 버튼은 「권한 게이트 부재는 의도적」이라는 주석과 함께 무게이트로 살아 있었다.
// 그 주석의 근거는 **「프론트 권한 API 가 CREATE 를 안 준다」였고, 지금은 거짓이다** —
// `project-permissions.ts:24-34` 가 `CREATE: z.boolean()` 을 내준다.
//
// 결과. 「UI 가 서버가 403 할 생성 액션을 내놓는다」가 남는다. 이슈 생성 폼에 게이트를 넣어도
// 클론은 그 통로를 지나지 않는다(클론·이동·임포트 셋 다 CREATE 를 요구하는데
// `IssueCreateForm` 을 안 지난다).
//
// ★판정식은 `permissions.CREATE === false`(명시 거부만)다. 생성 폼과 같은 형태 —
//   `!isLoading && === true`(미지=거부)는 로딩·조회실패 구간에서 정상 사용자를 막는다.
//   미지에서는 지금처럼 버튼을 열어 두고 서버 403 + 토스트가 최종 판정한다(fail-safe 유지).
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 클론 버튼 CREATE 게이트', () => {
  it('CREATE:false(명시 거부)면 클론 버튼이 disabled 이고 사유를 말한다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ projectKey: 'ATLAS', permissions: nonMemberProjectPermissions }),
      ),
    )
    renderPanel()

    const clone = await screen.findByTestId('issue-clone')
    await waitFor(() => expect(clone).toBeDisabled())
    expect(clone.getAttribute('aria-label')).toBe(issueDetailStrings.cloneButtonNoPermission)
  })

  it('CREATE:true 면 클론 버튼이 활성이다 (대조군)', async () => {
    renderPanel()

    const clone = await screen.findByTestId('issue-clone')
    await waitFor(() => expect(clone).not.toBeDisabled())
    expect(clone.getAttribute('aria-label')).toBe(issueDetailStrings.cloneButton)
  })

  it('★권한 조회가 **아직 진행 중**인 프레임에서도 클론이 열려 있다 (로딩 프레임 계약 open-while-loading · 부채 매핑 15)', async () => {
    // ★계약명 `open-while-loading` — 소비처 레지스트리
    //   (`components/issue/create/__tests__/permission-gate-loading-contract.test.ts`)가 선언한 그 계약이다.
    //   임포트 라우트의 `no-verdict-while-loading` 을 여기 복제하면 회귀다.
    //   로딩 중 액션을 숨기면 권한 있는 사용자에게서 버튼이 깜빡였다 나타난다.
    //
    // ★지연은 벽시계가 아니라 테스트가 여는 게이트다 — 벽시계는 러너 부하로 간헐 실패가 된다.
    let releasePermissions: () => void = () => undefined
    const permissionGate = new Promise<void>((resolve) => {
      releasePermissions = resolve
    })
    server.use(
      http.get('/api/v1/users/me/project-permissions', async () => {
        await permissionGate
        return HttpResponse.json({ projectKey: 'ATLAS', permissions: nonMemberProjectPermissions })
      }),
    )
    renderPanel()

    const clone = await screen.findByTestId('issue-clone')
    // 아직 게이트를 열지 않았다 — 권한은 pending 이다. 여기서 막으면 미지를 거부로 읽은 것이다.
    expect(clone).not.toBeDisabled()
    expect(clone.getAttribute('aria-label')).toBe(issueDetailStrings.cloneButton)

    // 지연 쿼리를 남기지 않는다 — 열고 정착까지 기다린 뒤 끝낸다.
    releasePermissions()
    await waitFor(() => expect(screen.getByTestId('issue-clone')).toBeDisabled())
  })

  it('권한 조회가 실패해도 클론 버튼을 막지 않는다 (미지 ≠ 거부)', async () => {
    // 미지에서 막으면 CREATE 를 실제로 가진 사용자가 영구 차단된다 —
    // `use-project-permissions.ts` 에 retry 도 에러 폴백도 없다.
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )
    renderPanel()

    const clone = await screen.findByTestId('issue-clone')
    expect(clone).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T21. 보드 상세 보기 구성 — 모달 + 사이드패널 양쪽 (부채 177 Task 21 · 스펙 R7·R7c · J15·J46~J48)
//
// 판정축 표는 이 파일 **상단 주석**에 있다.
// ─────────────────────────────────────────────────────────────────────────────

/** 상세 보기 문구 정본 — 설정 화면과 **같은 카탈로그**를 쓴다. 갈리면 두 화면의 이름이 어긋난다. */
const DV_L = boardLabels.settings.detailView

/**
 * 판정용 구성 — **순서 축이 살아 있도록** 원래 순서와도 사전순과도 다르게 잡았다(Task 13·19 와 같은 수법).
 *
 * `GENERAL` 은 카탈로그 순서(status→priority→environment)와도 사전순(environment→priority→status)
 * 과도 다르다. `LINKS` 는 **빈 그룹 대조군**이다 — 빈 칸을 그리는 구현이 여기서 죽는다.
 */
const T21_CONFIG: Record<DetailViewFieldGroup, string[]> = {
  GENERAL: ['priority', 'environment', 'status'],
  DATE: ['dueDate', 'createdAt'],
  PEOPLE: ['reporter', 'assignee'],
  LINKS: [],
}

/** 위 구성이 화면에 그려져야 하는 이름 — 순서까지 포함한다. */
const T21_EXPECTED: Record<'GENERAL' | 'DATE' | 'PEOPLE', string[]> = {
  GENERAL: ['우선순위', '환경', '상태'],
  DATE: ['마감일', '생성일'],
  PEOPLE: ['보고자', '담당자'],
}

/**
 * 이 테스트가 시드한 보드 id — **테스트마다 새로 뽑는다.**
 *
 * ★목의 설정 store 는 `boardStore` 에 종속하지만(CASCADE), 고아를 터는 시점이 「설정을 꺼낼 때」라
 * **같은 id 를 다시 시드하면 앞 테스트의 구성이 되살아난다**(실측 — T21-3 이 T21-2 의 구성을 봤다).
 * id 를 갈면 그 되살아남 자체가 불가능해진다.
 */
let t21BoardId = ''

/** 보드 목록 GET 이 다녀간 횟수 — 「아무것도 안 그린다」를 재기 전의 정착 신호. */
let t21BoardListFetches = 0
/** 상세 보기 구성 GET 이 다녀간 횟수. */
let t21DetailViewFetches = 0

/**
 * 구성을 **실제 PATCH 창구**로 심는다.
 *
 * 스텁을 손으로 짓지 않는 이유 — `mocks/board-handlers.ts` 의 핸들러가 그룹 4종을 항상 채우는
 * 정규화(R7c)까지 백엔드와 같게 흉내 낸다. 손으로 지은 스텁은 그 계약과 조용히 갈린다.
 */
async function configureBoard(groups: Partial<Record<DetailViewFieldGroup, string[]>>): Promise<void> {
  for (const [group, fields] of Object.entries(groups)) {
    await replaceDetailViewFields(t21BoardId, group as DetailViewFieldGroup, fields)
  }
}

/**
 * 두 껍데기를 **함께** 마운트하고 스토어의 `presentation` 이 어느 쪽이 뜰지 정하게 한다.
 *
 * 한쪽만 마운트하면 「모달에만 반영」을 재는 대조군이 성립하지 않는다 —
 * 두 표현이 같은 하네스·같은 데이터를 쓰고 **껍데기만** 다른 것이 이 판정의 전제다.
 */
function renderIssueShells(presentation: IssueDetailPresentation) {
  setupFullPermissions()
  useIssueDetailModalStore.setState({ openKey: 'ATLAS-1', presentation })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <IssueDetailModal />
      <IssueDetailSidePanel />
    </QueryClientProvider>,
  )
}

/** 그룹 하나의 목록. **없으면 null** — 「빈 칸을 그리지 않는다」를 재는 자리다. */
function groupList(group: DetailViewFieldGroup): HTMLElement | null {
  return screen.queryByRole('list', { name: DV_L.listLabel(DV_L.groupLabels[group]) })
}

/** 그 그룹에 실제로 그려진 필드 이름을 **화면 순서 그대로** 뽑는다. */
function renderedFieldNames(group: DetailViewFieldGroup): string[] {
  const list = groupList(group)
  if (list === null) return []
  return within(list)
    .getAllByRole('listitem')
    .map((row) => within(row).getByTestId('detail-view-field-name').textContent ?? '')
}

/** 구성 3그룹이 순서까지 그대로 그려졌는지 — 두 표현이 **같은 단언**을 공유한다. */
async function expectConfiguredFields(): Promise<void> {
  await waitFor(() => {
    expect(renderedFieldNames('GENERAL')).toEqual(T21_EXPECTED.GENERAL)
  })
  expect(renderedFieldNames('DATE')).toEqual(T21_EXPECTED.DATE)
  expect(renderedFieldNames('PEOPLE')).toEqual(T21_EXPECTED.PEOPLE)
  // 빈 그룹은 제목도 목록도 그리지 않는다.
  expect(groupList('LINKS')).toBeNull()
}

/** 구성 조회가 다녀가고 그 응답이 화면에 반영될 때까지 기다린다 — **부재**를 재기 전의 정착점. */
async function settleDetailView(): Promise<void> {
  await waitFor(() => {
    expect(t21BoardListFetches).toBeGreaterThan(0)
  })
  await waitFor(() => {
    expect(screen.getByRole('complementary')).toBeInTheDocument()
  })
}

describe('IssueMetaPanel — 보드 상세 보기 구성 (부채 177 T21 · R7c)', () => {
  beforeEach(() => {
    t21BoardListFetches = 0
    t21DetailViewFetches = 0
    resetBoardStore()
    t21BoardId = generateUUID()
    seedBoard({ ...DEFAULT_BOARD, boardId: t21BoardId })
    // ★이 스위트의 MSW 는 기본 핸들러가 refresh 하나뿐이다(`test/handlers.ts`) — 앱 핸들러
    //   전량을 여기서 켠다. 보드 설정 응답을 손으로 짓지 않으려면 `mocks/board-handlers.ts`
    //   가 실제로 돌아야 하고, 모달/사이드패널은 상세 본문 전체를 그리므로 그 뒷단도 필요하다.
    // ★순서가 곧 우선순위다(`use` 는 앞에 끼운다). 파일 상단 beforeEach 가 세운 두 스텁을
    //   먼저 두어 앱 핸들러가 그것을 덮지 않게 한다 — favorites 는 인증 없는 유닛 환경에서
    //   401 을 내고, 그러면 refresh 왕복이 끼어들어 authStore 가 다음 테스트로 샌다.
    server.use(
      http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
      http.get('/api/v1/projects/:projectKey/issue-security-scheme/levels', () =>
        HttpResponse.json({ levels: [] }),
      ),
      // 세는 것만 하고 응답은 뒤 핸들러에 넘긴다(resolver 가 undefined 면 다음 핸들러로 간다).
      http.get('/api/v1/boards', () => {
        t21BoardListFetches += 1
      }),
      http.get('/api/v1/boards/:id/detail-view-fields', () => {
        t21DetailViewFetches += 1
      }),
      ...appHandlers,
    )
  })

  afterEach(() => {
    // 설정 store 는 boardStore 에 종속한다 — 보드를 지우면 구성도 함께 사라진다(핸들러의 CASCADE 규칙).
    resetBoardStore()
    useIssueDetailModalStore.setState({ openKey: null, presentation: 'modal' })
  })

  it('T21-1: 모달로 연 상세가 보드 구성을 그룹·순서 그대로 그린다', async () => {
    await configureBoard(T21_CONFIG)
    renderIssueShells('modal')

    expect(await screen.findByRole('dialog', { name: '이슈 상세 ATLAS-1' })).toBeInTheDocument()
    await expectConfiguredFields()
  })

  it('T21-2: 사이드패널로 연 상세도 **같은** 구성을 그룹·순서 그대로 그린다 (R7c)', async () => {
    await configureBoard(T21_CONFIG)
    renderIssueShells('sidePanel')

    expect(
      await screen.findByRole('region', { name: issueDetailStrings.sidePanelLabel }),
    ).toBeInTheDocument()
    await expectConfiguredFields()
  })

  it('T21-3: 구성이 하나도 없는 보드는 구획 자체를 그리지 않는다 (미설정 대조군)', async () => {
    renderPanel()

    await settleDetailView()
    await waitFor(() => {
      expect(t21DetailViewFetches).toBeGreaterThan(0)
    })
    expect(groupList('GENERAL')).toBeNull()
    expect(groupList('DATE')).toBeNull()
    expect(groupList('PEOPLE')).toBeNull()
    expect(groupList('LINKS')).toBeNull()
  })

  it('T21-4: 모르는 키도 원문 그대로 그리고, 한 그룹의 개수 상한은 없다', async () => {
    // ★카드 레이아웃의 0..2(J17)를 복사해 오면 뒤 3개가 잘려 죽는다.
    // ★`cf_severity` 는 카탈로그 밖 키다 — 숨기면 그 그룹을 한 번 건드리는 순간 소실된다.
    await configureBoard({
      GENERAL: ['cf_severity', 'status', 'priority', 'impact', 'resolution', 'labels'],
    })
    renderPanel()

    await waitFor(() => {
      expect(renderedFieldNames('GENERAL')).toEqual([
        'cf_severity',
        '상태',
        '우선순위',
        '영향도',
        '해결',
        '라벨',
      ])
    })
  })

  it('T21-5: 열람 제한 필드는 구성에 있어도 그리지 않는다 (FR-PM-07)', async () => {
    await configureBoard({ GENERAL: ['status', 'environment', 'priority'] })
    renderPanel({ ...issueFixture, restrictedFields: ['environment'] })

    await waitFor(() => {
      expect(renderedFieldNames('GENERAL')).toEqual(['상태', '우선순위'])
    })
  })

  it('T21-6: 구성 조회가 실패하면 안내와 재시도가 화면에 남는다', async () => {
    server.use(
      http.get('/api/v1/boards/:id/detail-view-fields', () =>
        HttpResponse.json({ errorCode: 'AGILE_INTERNAL', message: 'boom' }, { status: 500 }),
      ),
    )
    renderPanel()

    expect(await screen.findByText(boardLabels.settings.loadError)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: boardLabels.settings.retry })).toBeInTheDocument()
  })

  it('T21-7: 보드가 없는 프로젝트는 아무것도 그리지 않는다 (실패가 아니다)', async () => {
    // 보드를 지운다 — 목록이 빈 배열이라 읽을 구성 자체가 없다. 오류로 읽으면 안 된다.
    resetBoardStore()
    renderPanel()

    await settleDetailView()
    expect(groupList('GENERAL')).toBeNull()
    expect(screen.queryByText(boardLabels.settings.loadError)).toBeNull()
    expect(t21DetailViewFetches).toBe(0)
  })
})


describe('IMP-RP 보고자 표시', () => {
  const reporterUser: UserSummary = {
    id: issueFixture.reporterId,
    username: 'hong',
    displayName: '홍길동',
    email: 'hong@bts.test',
  }

  /** 보고자 prop 만 바꿔 패널을 렌더한다. */
  function renderWithReporter(reporter: UserSummary | null) {
    setupFullPermissions()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IssueMetaPanel
          issue={issueFixture}
          availableTypes={availableTypes}
          onTypeChange={vi.fn()}
          onDeleteClick={vi.fn()}
          onCloneClick={vi.fn()}
          transitions={[]}
          onTransition={vi.fn()}
          isTransitioning={false}
          onPriorityChange={vi.fn()}
          onImpactChange={vi.fn()}
          onEnvironmentSave={vi.fn()}
          onLabelsSave={vi.fn()}
          users={[]}
          onAssigneeSearch={vi.fn()}
          onAssigneeChange={vi.fn()}
          currentAssignee={null}
          reporter={reporter}
        />
      </QueryClientProvider>,
    )
  }

  it('보고자를 UUID 가 아니라 표시 이름으로 보여준다', () => {
    // 「보고자 / 86ef7063-4532-…」는 사람에게 아무 정보도 주지 않는다. 담당자는 이미
    // `currentAssignee` 로 이름을 해석해 그리는데 보고자만 원시 UUID 로 남아 있었다.
    renderWithReporter(reporterUser)

    expect(screen.getByTestId('issue-reporter-name')).toHaveTextContent('홍길동')
    expect(screen.queryByText(issueFixture.reporterId)).toBeNull()
  })

  it('displayName 이 없으면 username 으로 내려간다', () => {
    // 담당자 표시(`getDisplayName`)와 같은 사다리를 쓴다 — 두 자리가 다른 규칙으로
    // 갈라지면 같은 사람이 화면 위치에 따라 다른 이름으로 보인다.
    renderWithReporter({ ...reporterUser, displayName: null })

    expect(screen.getByTestId('issue-reporter-name')).toHaveTextContent('hong')
  })

  it('보고자를 해석하지 못하면 UUID 를 그대로 보여준다', () => {
    // 탈퇴·비활성 사용자이거나 조회가 아직 안 끝난 경우. 빈칸으로 두면 「보고자 없는 이슈」로
    // 읽히고, 그건 UUID 보다 나쁜 거짓말이다.
    renderWithReporter(null)

    expect(screen.getByTestId('issue-reporter-name')).toHaveTextContent(issueFixture.reporterId)
  })
})
