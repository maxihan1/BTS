// 카드 레이아웃 **배선** 테스트 — 응답 스키마 왕복 + 보드/백로그 두 화면이 구성을 카드까지 나르는가 (부채 177 Task 32)
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나 (J17·J18·J19 · R2·R3 · E4)
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 스키마 생존 | 응답 JSON 의 `customFields` 가 **파싱 뒤에도** 있다 (T-W-1) | zod `z.object` strip 이 조용히 버림 ↔ 스키마가 키를 앎 |
// | ② 스키마 무회귀 | 그 키가 **없는** 응답도 여전히 파싱된다 (T-W-2) | required 로 넓혀 인라인 mock 전량 파괴 ↔ optional |
// | ③ 보드 체인 | 보드 **화면**을 열면 카드에 추가 필드 층이 생긴다 (T-W-3) | 카드만 능력 보유·부모가 안 넘김 ↔ 라우트→칸→카드 배선 |
// | ④ 커스텀 왕복 | `cf_story_points` 가 화면에 값으로 뜬다 (T-W-4) | 스키마 strip 또는 접두사 미제거로 전멸 ↔ 값 도달 |
// | ⑤ E4 대조군 | 같은 화면에서 값이 **있는** 카드만 그 칸을 갖는다 (T-W-5) | 전부 생략 / 빈 칸 렌더 ↔ 카드별 판정 |
// | ⑥ 보드 스코프 격리 | `BACKLOG` 구성만 있으면 보드 카드에 층 DOM 이 **없다** (T-W-6) | 스코프를 잘못 넘김·`BOARD ?? BACKLOG` ↔ 자기 스코프만 |
// | ⑦ 스윔레인 분기 | 스윔레인 그룹 경로에서도 뜬다 (T-W-7) | `BoardColumn` 의 두 분기 중 하나만 배선 ↔ 둘 다 |
// | ⑧ 백로그 체인(백로그 칸) | 백로그 **화면**의 백로그 칸 카드에 층이 생긴다 (T-W-8) | 보드만 배선 ↔ 두 체인 |
// | ⑨ 백로그 체인(스프린트 칸) | 같은 화면의 **스프린트 칸** 카드에도 생긴다 (T-W-9) | `BacklogColumn` 만 배선 ↔ `SprintColumn` 도 |
// | ⑩ 백로그 스코프 격리 | `BOARD` 구성만 있으면 백로그 카드에 층 DOM 이 **없다** (T-W-10) | 스코프 혼동 ↔ `BACKLOG` 만 |
// | ⑪ 뷰 교차 | **같은** 구성 객체로 두 화면을 열면 서로 다른 필드가 뜬다 (T-W-11) | 한 목록을 두 뷰가 공유 ↔ 스코프를 갈라 나름 |
//
// ★**③⑧은 짝으로만 산다.** 한 체인만 배선한 구현은 반대쪽에서 죽어야 한다 — 그래서 보드와
//   백로그를 **각각 실제 화면 컴포넌트로** 연다. 카드에 직접 prop 을 주는 시험대는
//   `BoardCard.test.tsx`(Task 20)가 이미 갖고 있고, **그 파일이 전량 초록인 채로 앱에서는
//   아무것도 안 뜨는 것**이 이 task 가 닫는 결함이다.
// ★**①은 타입으로 못 잰다.** zod strip 은 값을 조용히 버리므로 「스키마에 필드가 있다」를
//   재면 안 되고, **실제 응답 JSON 을 파싱해 값이 살아남는지**를 재야 한다. 그래서 이 파일은
//   MSW 로 진짜 응답을 세우고 진짜 `fetchBoard`/화면 훅을 지나게 한다 (mock 훅 금지).
// ★**알려진 한계 — 백로그에는 커스텀 필드가 없다.** 백로그 조회 응답(`BacklogIssueResponse`)은
//   `customFields` 를 아예 싣지 않는다(백엔드 T26 은 `BoardCardResponse` 만 넓혔다). 그래서
//   ⑧⑨⑩⑪의 백로그 쪽은 **표준 필드**로 잰다 — 여기에 `cf_*` 기대를 쓰면 그것은 배선이 아니라
//   서버 계약을 재는 가짜 실패가 된다. 커스텀 필드의 백로그 노출은 후속 별건이다.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { fetchBoard } from '@/api/boards'

// TanStack Router mock — 라우트 페이지 단위 렌더용.
// `projects.$projectKey.board.test.tsx` · `projects.$projectKey.backlog.test.tsx` 와 같은 형태다.
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
  useRouterState: () => ({}),
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => {
    const resolvedTo = params !== undefined
      ? Object.entries(params).reduce((acc, [key, val]) => acc.replace(`$${key}`, val), to)
      : to
    return <a href={resolvedTo} className={className} onClick={onClick}>{children}</a>
  },
}))

import { BoardPage } from '@/routes/projects.$projectKey.board'
import { BacklogPage } from '@/routes/projects.$projectKey.backlog'
import { emptyBacklogFilter } from '@/lib/backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — **서버가 보내는 모양 그대로의 JSON**이다 (파싱 전)
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = '00000000-0000-4000-8000-0000000000c1'
const COLUMN_ID = '00000000-0000-4000-8000-0000000000d1'
const SPRINT_ID = '00000000-0000-4000-8000-0000000000e1'

/** 값이 **있는** 카드 — 에픽·추정·커스텀 필드를 전부 가진다 */
const CARD_WITH_VALUES = {
  issueKey: 'ATLAS-1',
  summary: '값이 있는 카드',
  assigneeId: null,
  version: 0,
  priority: 2,
  epicKey: 'ATLAS-99',
  rank: '0|a:',
  typeKey: 'task',
  labels: ['ui'],
  originalEstimateSeconds: 3600,
  // ★백엔드 `BoardCardResponse.customFields` (T26). 키에 `cf_` 가 **없다**.
  customFields: { story_points: 8 },
}

/** 값이 **없는** 카드 — E4 대조군. 같은 구성인데 이 카드에서는 칸이 사라져야 한다 */
const CARD_WITHOUT_VALUES = {
  issueKey: 'ATLAS-2',
  summary: '값이 없는 카드',
  assigneeId: null,
  version: 0,
  priority: 3,
  epicKey: null,
  rank: '0|b:',
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
  customFields: {},
}

/** 뷰별 구성 — 서버 `cardLayout` 그대로. 구성이 없는 뷰는 **키 자체가 없다** */
type CardLayoutJson = Partial<Record<'BOARD' | 'BACKLOG', string[]>>

/** 보드 단건 조회 응답 JSON. `swimlaneField` 는 `BoardColumn` 의 두 렌더 분기를 가른다 */
function boardDetailJson(
  cardLayout: CardLayoutJson,
  options: { swimlaneField?: string; boardType?: string } = {},
): Record<string, unknown> {
  return {
    boardId: BOARD_ID,
    projectKey: 'ATLAS',
    name: '배선 보드',
    boardType: options.boardType ?? 'KANBAN',
    activeSprint: null,
    swimlaneField: options.swimlaneField ?? 'NONE',
    truncated: false,
    unplacedCount: 0,
    unmappedStates: [],
    quickFilters: [],
    columns: [
      {
        columnId: COLUMN_ID,
        name: '할 일',
        category: 'TODO',
        displayOrder: 1,
        wipLimit: null,
        wipExceeded: false,
        states: [{ key: 'open', name: '열림', category: 'TODO' }],
        // ★**복제해서 넣는다.** 상수를 그대로 실으면 응답을 손보는 테스트(T-W-2 의 `delete`)가
        //   **다른 테스트의 픽스처까지** 바꾼다 — 실제로 그 순서 의존 때문에 커스텀 필드 축
        //   두 개가 거짓 실패했다. 픽스처는 테스트마다 자기 것이어야 한다.
        cards: [structuredClone(CARD_WITH_VALUES), structuredClone(CARD_WITHOUT_VALUES)],
      },
    ],
    cardLayout,
    timeTracking: 'NONE',
    workingDays: { standardDays: null, nonWorkingDates: [], timezone: null },
    detailViewFields: { GENERAL: [], DATE: [], PEOPLE: [], LINKS: [] },
  }
}

/** 보드 목록 응답 JSON — 라우트가 `currentBoardId` 를 고르는 근거다 */
function boardSummaryJson(boardType = 'KANBAN'): Record<string, unknown>[] {
  return [{ boardId: BOARD_ID, projectKey: 'ATLAS', name: '배선 보드', boardType }]
}

/** 백로그 이슈 JSON — `BacklogIssueResponse` 그대로. **`customFields` 가 없다**(알려진 한계) */
function backlogIssueJson(key: string, summary: string): Record<string, unknown> {
  return {
    key,
    summary,
    currentStateKey: 'open',
    assigneeId: null,
    priority: 2,
    rank: '0|a:',
    version: 0,
    epicKey: 'ATLAS-99',
    typeKey: 'task',
    labels: ['ui'],
    originalEstimateSeconds: 3600,
  }
}

/** 백로그 조회 응답 JSON — 백로그 칸 1건 + 스프린트 칸 1건 (두 칸을 각각 잰다) */
function backlogViewJson(): Record<string, unknown> {
  return {
    backlog: [backlogIssueJson('ATLAS-10', '백로그 칸 이슈')],
    sprints: [
      {
        sprint: {
          sprintId: SPRINT_ID,
          boardId: BOARD_ID,
          name: '스프린트 1',
          goal: null,
          status: 'ACTIVE',
          startDate: '2026-06-01',
          endDate: '2026-06-14',
          version: 0,
        },
        issues: [backlogIssueJson('ATLAS-11', '스프린트 칸 이슈')],
      },
    ],
    truncated: false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 하네스
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 단건·목록 응답을 세운다. 나머지(권한·사용자·이슈 유형)는 기본 핸들러가 받는다 */
function serveBoard(cardLayout: CardLayoutJson, options: { swimlaneField?: string } = {}): void {
  server.use(
    http.get('/api/v1/boards', () => HttpResponse.json({ data: boardSummaryJson() })),
    http.get('/api/v1/boards/:id', () =>
      HttpResponse.json({ data: boardDetailJson(cardLayout, options) }),
    ),
  )
}

/** 백로그 화면이 쓰는 두 응답(보드 단건 + 백로그)을 세운다 */
function serveBacklog(cardLayout: CardLayoutJson): void {
  server.use(
    http.get('/api/v1/boards', () => HttpResponse.json({ data: boardSummaryJson('SCRUM') })),
    http.get('/api/v1/boards/:id', () =>
      HttpResponse.json({ data: boardDetailJson(cardLayout, { boardType: 'SCRUM' }) }),
    ),
    http.get('/api/v1/projects/:projectKey/backlog', () =>
      HttpResponse.json({ data: backlogViewJson() }),
    ),
  )
}

function renderWithQuery(ui: ReactNode): void {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>)
}

/** 카드 루트를 이슈 키로 찾는다 — 두 카드의 `aria-label` 형식이 같다(`{key} — {summary}`) */
async function findCard(issueKey: string, summary: string): Promise<HTMLElement> {
  return screen.findByLabelText(`${issueKey} — ${summary}`)
}

/** 카드 2층(추가 필드)이 그린 필드 키 목록. 층 자체가 없으면 빈 배열 */
function extraFieldKeys(card: HTMLElement): string[] {
  return Array.from(card.querySelectorAll('[data-card-field]')).map(
    (el) => el.getAttribute('data-card-field') ?? '',
  )
}

/** 카드 2층의 한 칸 텍스트(라벨+값). 그 칸이 없으면 null */
function extraFieldText(card: HTMLElement, field: string): string | null {
  const el = card.querySelector(`[data-card-field="${field}"]`)
  return el === null ? null : el.textContent
}

beforeEach(() => {
  // MSW 기본 핸들러(권한·사용자)가 200 을 주려면 인식 가능한 토큰이 필요하다
  useAuthStore.setState({ accessToken: 'mock-access-token-alice', user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// ① ② 응답 스키마 왕복 — strip 은 조용히 버린다
// ─────────────────────────────────────────────────────────────────────────────

describe('boardCardSchema — 커스텀 필드가 파싱을 살아서 지난다 (T26 미러)', () => {
  it('T-W-1. 응답의 customFields 값이 파싱 뒤에도 남는다 (strip 판정)', async () => {
    server.use(
      http.get('/api/v1/boards/:id', () =>
        HttpResponse.json({ data: boardDetailJson({ BOARD: ['cf_story_points'] }) }),
      ),
    )

    const board = await fetchBoard(BOARD_ID)
    const card = board.columns[0]?.cards[0]

    // ★타입이 아니라 **값**을 잰다. 스키마가 키를 모르면 zod 가 조용히 버려 undefined 다.
    expect(card?.customFields).toEqual({ story_points: 8 })
  })

  it('T-W-2. customFields 가 없는 응답도 그대로 파싱된다 (인라인 mock 무회귀)', async () => {
    const withoutKey = boardDetailJson({})
    const columns = withoutKey['columns'] as { cards: Record<string, unknown>[] }[]
    for (const column of columns) {
      for (const card of column.cards) {
        delete card['customFields']
      }
    }
    server.use(http.get('/api/v1/boards/:id', () => HttpResponse.json({ data: withoutKey })))

    const board = await fetchBoard(BOARD_ID)

    expect(board.columns[0]?.cards).toHaveLength(2)
    expect(board.columns[0]?.cards[0]?.customFields).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ③ ④ ⑤ ⑥ ⑦ 보드 체인 — 라우트 → KanbanBoard → BoardColumn → BoardCard
// ─────────────────────────────────────────────────────────────────────────────

describe('보드 화면 — 카드 레이아웃 배선 (J17·J19)', () => {
  it('T-W-3. 보드 구성이 카드 2층으로 뜬다', async () => {
    serveBoard({ BOARD: ['PRIORITY', 'EPIC'] })
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const card = await findCard('ATLAS-1', '값이 있는 카드')

    expect(extraFieldKeys(card)).toEqual(['PRIORITY', 'EPIC'])
    expect(extraFieldText(card, 'PRIORITY')).toContain('P2')
    expect(extraFieldText(card, 'EPIC')).toContain('ATLAS-99')
  })

  it('T-W-4. cf_ 커스텀 필드 값이 화면까지 도달한다 (접두사 왕복)', async () => {
    serveBoard({ BOARD: ['cf_story_points'] })
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const card = await findCard('ATLAS-1', '값이 있는 카드')

    // 라벨은 접두사를 뗀 `story_points`, 값은 응답의 8 이다.
    expect(extraFieldKeys(card)).toEqual(['cf_story_points'])
    expect(extraFieldText(card, 'cf_story_points')).toContain('story_points')
    expect(extraFieldText(card, 'cf_story_points')).toContain('8')
  })

  it('T-W-5. 같은 구성에서 값이 없는 카드는 그 칸만 생략한다 (E4 대조군)', async () => {
    serveBoard({ BOARD: ['EPIC', 'cf_story_points'] })
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const withValues = await findCard('ATLAS-1', '값이 있는 카드')
    const withoutValues = await findCard('ATLAS-2', '값이 없는 카드')

    expect(extraFieldKeys(withValues)).toEqual(['EPIC', 'cf_story_points'])
    // 에픽도 커스텀 값도 없는 카드 — 층 자체가 생기지 않는다
    expect(extraFieldKeys(withoutValues)).toEqual([])
    expect(within(withoutValues).queryByTestId('card-extra-fields')).toBeNull()
  })

  it('T-W-6. BACKLOG 구성만 있으면 보드 카드에는 층 DOM 자체가 없다 (스코프 격리)', async () => {
    serveBoard({ BACKLOG: ['PRIORITY', 'EPIC'] })
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const card = await findCard('ATLAS-1', '값이 있는 카드')

    expect(within(card).queryByTestId('card-extra-fields')).toBeNull()
  })

  it('T-W-7. 스윔레인 그룹 경로에서도 뜬다 (BoardColumn 두 분기)', async () => {
    serveBoard({ BOARD: ['PRIORITY'] }, { swimlaneField: 'PRIORITY' })
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const card = await findCard('ATLAS-1', '값이 있는 카드')

    expect(extraFieldKeys(card)).toEqual(['PRIORITY'])
    expect(extraFieldText(card, 'PRIORITY')).toContain('P2')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑧ ⑨ ⑩ 백로그 체인 — 라우트 → BacklogBoard → 칸 → BacklogCard
// ─────────────────────────────────────────────────────────────────────────────

describe('백로그 화면 — 카드 레이아웃 배선 (J18·J19)', () => {
  it('T-W-8. 백로그 칸 카드에 BACKLOG 구성이 뜬다', async () => {
    serveBacklog({ BACKLOG: ['ESTIMATE', 'EPIC'] })
    renderWithQuery(
      <BacklogPage projectKey="ATLAS" boardId={BOARD_ID} filter={emptyBacklogFilter()} />,
    )

    const card = await findCard('ATLAS-10', '백로그 칸 이슈')

    expect(extraFieldKeys(card)).toEqual(['ESTIMATE', 'EPIC'])
    expect(extraFieldText(card, 'ESTIMATE')).toContain('1h 0m')
  })

  it('T-W-9. 같은 화면의 스프린트 칸 카드에도 뜬다 (SprintColumn 배선)', async () => {
    serveBacklog({ BACKLOG: ['ESTIMATE'] })
    renderWithQuery(
      <BacklogPage projectKey="ATLAS" boardId={BOARD_ID} filter={emptyBacklogFilter()} />,
    )

    const card = await findCard('ATLAS-11', '스프린트 칸 이슈')

    expect(extraFieldKeys(card)).toEqual(['ESTIMATE'])
    expect(extraFieldText(card, 'ESTIMATE')).toContain('1h 0m')
  })

  it('T-W-10. BOARD 구성만 있으면 백로그 카드에는 층 DOM 자체가 없다 (스코프 격리)', async () => {
    serveBacklog({ BOARD: ['ESTIMATE', 'EPIC'] })
    renderWithQuery(
      <BacklogPage projectKey="ATLAS" boardId={BOARD_ID} filter={emptyBacklogFilter()} />,
    )

    const card = await findCard('ATLAS-10', '백로그 칸 이슈')

    expect(within(card).queryByTestId('card-extra-fields')).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑪ 뷰 교차 — 한 보드의 **같은** 구성 객체로 두 화면을 연다
// ─────────────────────────────────────────────────────────────────────────────

describe('뷰 교차 — 한 보드, 두 화면, 다른 필드 (R3 · J18)', () => {
  /** 두 뷰가 서로 다른 필드를 갖는 **한 벌**의 구성 */
  const CROSS_LAYOUT: CardLayoutJson = { BOARD: ['PRIORITY'], BACKLOG: ['ESTIMATE'] }

  it('T-W-11. 보드는 BOARD 것만, 백로그는 BACKLOG 것만 그린다', async () => {
    serveBoard(CROSS_LAYOUT)
    renderWithQuery(<BoardPage projectKey="ATLAS" selectedBoardId={BOARD_ID} />)

    const boardCard = await findCard('ATLAS-1', '값이 있는 카드')
    expect(extraFieldKeys(boardCard)).toEqual(['PRIORITY'])

    serveBacklog(CROSS_LAYOUT)
    renderWithQuery(
      <BacklogPage projectKey="ATLAS" boardId={BOARD_ID} filter={emptyBacklogFilter()} />,
    )

    const backlogCard = await findCard('ATLAS-10', '백로그 칸 이슈')
    expect(extraFieldKeys(backlogCard)).toEqual(['ESTIMATE'])
  })
})
