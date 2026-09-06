// 상세 보기 탭 동반 테스트 — 그룹 4종 · 추가/삭제 · 드래그 순서 · 연속 드롭 잠금 (부채 177 Task 19 · J46~J48)
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 그룹 존재 | 4종이 다 그려진다 (T-DV-1) | 한 덩어리 목록 ↔ `General/Date/People/Links` (J47) |
// | ② 그룹 축 | 4종에 **서로 다른** 구성이 각자 앉는다 (T-DV-2) | 한 목록을 넷이 공유 ↔ 그룹별 상태 |
// | ③ 미설정 대조군 | 구성이 **없는** 보드는 4종 다 빈 상태 (T-DV-3) | 기본값을 채움 ↔ 없으면 없는 대로 |
// | ④ 추가 | 고른 후보가 그 그룹 **끝**에 붙어 전송된다 (T-DV-4) | 앞에 끼움·정렬 ↔ J48 의 *Add* |
// | ⑤ 그룹 격리 | 한 그룹을 바꿔도 **다른 그룹은 요청에 없다** (T-DV-5) | 4종을 매번 통째 전송(옛 값 덮어씀) ↔ 한 그룹씩 |
// | ⑥ 삭제 | 그 필드만 빠진 목록이 전송된다 (T-DV-6) | 통째 비움 ↔ J48 의 *Delete* |
// | **⑦ 순서** | 드래그 뒤 **멤버십은 같고 순서만 다른** 목록이 전송된다 (T-DV-7) | `sorted()`·집합·화면만 바꾸고 저장 안 함 ↔ 순서를 그대로 저장 |
// | ⑧ 그룹 넘나듦 | 다른 그룹으로 끌면 아무것도 저장되지 않는다 (T-DV-8) | 엉뚱한 그룹을 덮음 ↔ *"up or down in the list"* |
// | ⑨ 제자리 | 제자리 드롭은 요청을 만들지 않는다 (T-DV-9) | 드롭마다 저장 ↔ 바뀔 때만 |
// | ⑩ 덮어쓰기 | 저장돼 있던 구성 **위에** 하나 추가 (T-DV-10) | 빈 값에서 시작해 그룹을 통째로 덮음(**데이터 소실**) ↔ 초기값을 읽고 더함 |
// | ⑪ 응답 반영 | 응답에만 있는 다른 그룹 구성이 화면에 뜬다 (T-DV-11) | 요청 echo 신뢰 ↔ 응답을 읽음 |
// | ⑫ 무효화 | 저장 성공 시 보드 조회를 무효화한다 (T-DV-12) | 로컬 state 만 믿음 ↔ 다음 마운트가 서버 값 |
// | ⑬ 재마운트 | 탭 복귀(언마운트→재마운트) 후에도 구성이 뜬다 (T-DV-13) | 마운트마다 빈 값 ↔ `board.detailViewFields` |
// | **⑭ 연속 드롭** | in-flight 중 두 번째 드롭이 **먹히지 않는다** (T-DV-14) | 두 요청이 같은 낡은 목록에서 파생(lost update) ↔ E8 잠금 |
// | ⑮ 실패 되돌림 | 실패하면 값이 복원되고 오류가 화면에 남는다 (T-DV-15) | 낙관 유지·토스트 단독 ↔ 되돌림 + `role="alert"` |
// | ⑯ 오류 판정축 | 본문이 비어도 403 문구 (T-DV-16) | 봉투 본문 파싱 ↔ 상태 코드 판정 |
// | ⑰ 권한 잠금 | `canConfigure=false` 면 조작 전부 잠긴다 (T-DV-17) | 항상 편집 가능 ↔ CREATE 로만 열림 (S7) |
// | ⑱ 모르는 키 | 카탈로그 밖 키도 그려지고 살아남는다 (T-DV-18) | 숨겨서 다음 저장에 소실 ↔ 원문으로 표시 |
//
// ★**⑦ 이 이 파일의 RED 다.** 「드래그하면 저장된다」만 재면 **집합으로 다루는** 구현이 통과한다.
// 그래서 T-DV-7 은 **멤버십이 같고 순서만 다른** 입력을 쓰고, 기대값을 원래 순서와도 **사전순과도**
// 다르게 잡았다 — `sorted()` 를 끼운 구현과 선착순을 유지하는 구현이 동시에 죽는다
// (Task 13 이 백엔드에서 뮤턴트 M1b 로 같은 축을 잡았다).
//
// ★**⑩ 이 그 다음으로 비싸다.** `replaceDetailViewFields` 는 **그룹 통째 교체**라, 초기값을 읽지
// 않는 구현은 「필드 하나를 더했을 뿐인데 그 그룹의 나머지가 사라지는」 데이터 소실을 낸다.
// ② 는 **화면**을 재고 ⑩ 은 **요청 바디**를 잰다 — 둘은 다른 축이고, ② 만 두면 「그리기는 하는데
// 요청은 빈 목록에서 만드는」 구현이 통과한다. Task 16 이 실제로 밟은 자리다.
//
// ★**② 와 ③ 은 짝으로만 산다.** ③ 이 없으면 「무엇이든 기본값을 채우는」 구현이 ② 를 통과한다.
// ★**⑦ 과 ⑧⑨ 도 짝이다.** ⑦ 만 두면 「모든 드롭을 저장하는」 구현이 통과하고, ⑧⑨ 만 두면
// 「아무 드롭도 저장하지 않는」 구현이 통과한다.
//
// ★**MSW 스텁은 Task 13 의 실제 응답 형태를 따른다** — `{ data: { groups: { GENERAL: [...] , … } } }`.
// 그룹 단위 교체(요청에 없는 그룹은 그대로)와 **그룹 4종을 항상 채우는** 정규화(R7c)까지
// `mocks/board-handlers.ts` 의 핸들러와 같게 흉내 낸다. 형태가 어긋나면
// `boardDetailViewFieldsSchema.parse` 가 이 파일에서 먼저 죽는다.
//
// ★**드래그는 `DndContext` 를 mock 으로 갈아 끼워 `onDragEnd` 를 직접 부른다**(`KanbanBoard.test`
// 선례). 다만 그 하네스의 알려진 사각지대가 있다 — `over.data` 를 테스트가 손으로 지어내면
// 컴포넌트가 실제로 무엇을 싣는지 아무도 관측하지 않는다(`BacklogBoard.test` 가 이름 붙인 자리).
// 그래서 `useDraggable`·`useDroppable` 호출을 가로채 **화면이 실제로 등록한 id·data 로만**
// 드롭 이벤트를 만든다. 컴포넌트가 id 체계를 바꾸면 [registeredNode] 가 던져 red 가 된다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { DragEndEvent } from '@dnd-kit/core'
import { server } from '@/test/server'
import type { BoardDetail, BoardDetailViewFields } from '@/api/boards'
import type { DetailViewFieldGroup } from '@/api/board-settings'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// dnd-kit 하네스
// ─────────────────────────────────────────────────────────────────────────────

let capturedOnDragEnd: ((event: DragEndEvent) => void) | undefined
/** 화면이 실제로 등록한 draggable — `id → data`. 테스트가 이벤트를 지어내지 않게 한다. */
const draggableNodes = new Map<string, unknown>()
/** 화면이 실제로 등록한 droppable — `id → data`. */
const droppableNodes = new Map<string, unknown>()

vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
    }: {
      children: ReactNode
      onDragEnd?: (event: DragEndEvent) => void
    }) => {
      capturedOnDragEnd = onDragEnd
      return createElement('div', { 'data-testid': 'dnd-context' }, children)
    },
    useDraggable: (args: Parameters<typeof actual.useDraggable>[0]) => {
      draggableNodes.set(String(args.id), args.data)
      return actual.useDraggable(args)
    },
    useDroppable: (args: Parameters<typeof actual.useDroppable>[0]) => {
      droppableNodes.set(String(args.id), args.data)
      return actual.useDroppable(args)
    },
  }
})

// mock 정의 뒤에 import 한다 — `KanbanBoard.test` 와 같은 배치다.
import { DetailViewPanel } from './DetailViewPanel'

// jsdom 은 scrollIntoView 를 구현하지 않는다 — 후보 드롭다운이 쓰는 cmdk 가 활성 항목이
// 바뀔 때마다 부른다 (`ui/command.test.tsx:16` · 형제 `WorkingDaysPanel.test` 와 같은 처방).
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = (): void => {}
}

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const L = boardLabels.settings.detailView
const G = L.groupLabels

/** 그룹 4종이 항상 있는 모양 — 백엔드 `readNormalized` · 목 `normalizeDetailViewFields` 와 같다. */
function normalize(stored: Record<string, string[]>): Record<string, string[]> {
  return {
    GENERAL: stored['GENERAL'] ?? [],
    DATE: stored['DATE'] ?? [],
    PEOPLE: stored['PEOPLE'] ?? [],
    LINKS: stored['LINKS'] ?? [],
  }
}

/**
 * 보드 조회가 실어 오는 구성 — **그룹 4종이 항상 있다**(R7c).
 *
 * 서버가 빈 그룹도 빈 배열로 채워 보내므로 픽스처도 그렇게 만든다. 여기서 키를 빼면
 * 테스트만 「키 부재」라는 실제로 오지 않는 모양을 다루게 된다.
 */
function fields(partial: Partial<BoardDetailViewFields> = {}): BoardDetailViewFields {
  return { GENERAL: [], DATE: [], PEOPLE: [], LINKS: [], ...partial }
}

function board(overrides: Partial<BoardDetail> = {}): BoardDetail {
  return {
    boardId: BOARD_ID,
    projectKey: 'ATLAS',
    name: 'ATLAS 보드',
    columns: [],
    truncated: false,
    unplacedCount: 0,
    unmappedStates: [],
    swimlaneField: 'NONE',
    quickFilters: [],
    boardType: 'SCRUM',
    activeSprint: null,
    ...overrides,
  }
}

/** PATCH 스텁이 관찰한 것. */
interface DetailViewStub {
  /** 받은 요청 바디 전량. 순서가 곧 호출 순서다. */
  requests: { groups?: Record<string, string[]> }[]
  /** 서버 측 저장 상태. **그룹 단위 교체** — 요청에 없는 그룹은 그대로 둔다. */
  stored: Record<string, string[]>
  /** `hold` 스텁의 응답을 푼다. in-flight 상태를 만들어 두는 축(⑭)이 쓴다. */
  release: () => void
}

/**
 * `PATCH /api/v1/boards/{boardId}/detail-view-fields` 스텁.
 *
 * @param initial 이미 저장돼 있는 구성. 「응답에만 있는 그룹」을 만들 때 쓴다.
 * @param options `status` 를 주면 그 코드로 실패시킨다(**본문은 비운다** — 본문 구조에 기대는
 *   구현을 여기서 잡는다). `hold` 면 [DetailViewStub.release] 를 부를 때까지 응답을 붙잡아
 *   in-flight 상태를 만든다.
 */
function stubDetailViewApi(
  initial: Record<string, string[]> = {},
  options: { status?: number; hold?: boolean } = {},
): DetailViewStub {
  let releaseGate = (): void => {}
  const gate = new Promise<void>((resolve) => {
    releaseGate = resolve
  })
  const stub: DetailViewStub = {
    requests: [],
    stored: normalize(initial),
    release: () => {
      releaseGate()
    },
  }
  server.use(
    http.patch('/api/v1/boards/:boardId/detail-view-fields', async ({ request }) => {
      const body = (await request.json()) as { groups?: Record<string, string[]> }
      stub.requests.push(body)
      if (options.hold === true) await gate
      if (options.status !== undefined) return new HttpResponse(null, { status: options.status })
      for (const [group, fields] of Object.entries(body.groups ?? {})) {
        stub.stored[group] = fields
      }
      return HttpResponse.json({ data: { groups: normalize(stub.stored) } })
    }),
  )
  return stub
}

/** 렌더 결과 — 재마운트 축과 무효화 축이 각각 `unmount`·`queryClient` 를 본다. */
interface RenderedPanel {
  queryClient: QueryClient
  unmount: () => void
}

function renderPanel(detail: BoardDetail = board(), canConfigure = true): RenderedPanel {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <DetailViewPanel board={detail} canConfigure={canConfigure} />
    </QueryClientProvider>,
  )
  return { queryClient, unmount: view.unmount }
}

/**
 * 화면이 **실제로 등록한** 드래그 노드를 그대로 이벤트 조각으로 만든다.
 *
 * 손으로 지어낸 `data` 를 쓰면 컴포넌트가 무엇을 싣는지 아무도 관측하지 않게 된다
 * (`BacklogBoard.test` 가 이름 붙인 하네스 사각지대). 등록되지 않은 노드면 **던진다** —
 * 조용히 통과하는 것보다 red 가 낫다.
 */
function registeredNode(
  registry: Map<string, unknown>,
  group: DetailViewFieldGroup,
  fieldKey: string,
): { id: string; data: { current: unknown } } {
  const id = `detail-view:${group}:${fieldKey}`
  const data = registry.get(id)
  if (data === undefined) {
    throw new Error(`화면에 등록되지 않은 드래그 노드다: ${id}`)
  }
  return { id, data: { current: data } }
}

/** 드래그 한 번 — `from` 을 `to` 자리에 놓는다. */
async function drag(
  from: { group: DetailViewFieldGroup; key: string },
  to: { group: DetailViewFieldGroup; key: string },
): Promise<void> {
  const active = registeredNode(draggableNodes, from.group, from.key)
  const over = registeredNode(droppableNodes, to.group, to.key)
  await act(async () => {
    capturedOnDragEnd?.({ active, over } as unknown as DragEndEvent)
    await Promise.resolve()
  })
}

/** 드롭다운에서 후보를 고르고 「추가」를 누른다 (J48). */
async function addField(group: keyof typeof G, fieldName: string): Promise<void> {
  const groupLabel = G[group]
  await userEvent.click(screen.getByRole('combobox', { name: L.candidateLabel(groupLabel) }))
  await userEvent.click(screen.getByRole('option', { name: fieldName }))
  await userEvent.click(screen.getByRole('button', { name: L.addField(groupLabel) }))
}

/** 한 그룹의 목록에 보이는 필드 이름들 — 순서 그대로. */
function listedFields(group: keyof typeof G): string[] {
  const list = screen.queryByRole('list', { name: L.listLabel(G[group]) })
  if (list === null) return []
  return [...list.querySelectorAll('li')].map((li) => li.querySelector('span')?.textContent ?? '')
}

/** 마지막 요청의 그룹 목록. */
function lastGroups(stub: DetailViewStub): Record<string, string[]> {
  const last = stub.requests.at(-1)
  return last?.groups ?? {}
}

beforeEach(() => {
  capturedOnDragEnd = undefined
  draggableNodes.clear()
  droppableNodes.clear()
})

// ─────────────────────────────────────────────────────────────────────────────
// ①②③ 그룹 4종 (J47)
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 그룹 4종 (J47)', () => {
  it('T-DV-1: 일반·날짜·사람·링크 네 구획이 그려진다', () => {
    renderPanel()

    for (const name of [G.GENERAL, G.DATE, G.PEOPLE, G.LINKS]) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
  })

  it('T-DV-2: 그룹 4종에 서로 다른 구성이 각자 자리에 뜬다', () => {
    // ★넷에 **다른** 값을 넣는다 — 같은 값이면 한 목록을 넷이 공유하는 구현도 통과한다.
    renderPanel(
      board({
        detailViewFields: {
          GENERAL: ['status', 'priority'],
          DATE: ['dueDate'],
          PEOPLE: ['assignee'],
          LINKS: ['epic'],
        },
      }),
    )

    expect(listedFields('GENERAL')).toEqual(['상태', '우선순위'])
    expect(listedFields('DATE')).toEqual(['마감일'])
    expect(listedFields('PEOPLE')).toEqual(['담당자'])
    expect(listedFields('LINKS')).toEqual(['에픽'])
  })

  it('T-DV-3: 구성이 없는 보드는 4종 다 빈 상태다 — T-DV-2 의 짝', () => {
    // 「무엇이든 기본값을 채우는」 구현이 여기서 죽는다.
    renderPanel(board())

    for (const group of ['GENERAL', 'DATE', 'PEOPLE', 'LINKS'] as const) {
      expect(listedFields(group)).toEqual([])
      expect(screen.getByText(L.emptyGroup(G[group]))).toBeInTheDocument()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ④⑤⑥ 추가 · 그룹 격리 · 삭제 (J48)
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 추가와 삭제 (J48)', () => {
  it('T-DV-4: 드롭다운에서 고른 필드가 그 그룹 끝에 붙어 전송된다', async () => {
    const stub = stubDetailViewApi({ DATE: ['dueDate'] })
    renderPanel(board({ detailViewFields: fields({ DATE: ['dueDate'] }) }))

    await addField('DATE', '시작일')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    // 앞에 끼우거나 정렬하는 구현이 여기서 죽는다 — 고른 순서가 곧 상세 화면의 자리다.
    expect(lastGroups(stub)).toEqual({ DATE: ['dueDate', 'startDate'] })
  })

  it('T-DV-5: 한 그룹을 바꿔도 다른 그룹은 요청에 실리지 않고 화면에 그대로 남는다', async () => {
    // ★4종을 매번 통째로 보내는 구현은 그 사이 남이 바꾼 그룹을 옛 값으로 덮는다.
    // 스텁의 저장 상태를 보드 응답과 **같게** 맞춘다 — 응답이 전체 구성이라, 어긋나 있으면
    // 저장 뒤 화면이 서버 상태를 따라가는 정상 동작이 「사라졌다」로 오독된다.
    const stub = stubDetailViewApi({ GENERAL: ['status'], PEOPLE: ['reporter'] })
    renderPanel(
      board({
        detailViewFields: { GENERAL: ['status'], DATE: [], PEOPLE: ['reporter'], LINKS: [] },
      }),
    )

    await addField('PEOPLE', '감시자')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    expect(Object.keys(lastGroups(stub))).toEqual(['PEOPLE'])
    expect(lastGroups(stub)['PEOPLE']).toEqual(['reporter', 'watchers'])
    expect(listedFields('GENERAL')).toEqual(['상태'])
  })

  it('T-DV-6: 삭제하면 그 필드만 빠진 목록이 전송된다', async () => {
    const stub = stubDetailViewApi({ GENERAL: ['status', 'priority', 'labels'] })
    renderPanel(
      board({ detailViewFields: fields({ GENERAL: ['status', 'priority', 'labels'] }) }),
    )

    await userEvent.click(screen.getByRole('button', { name: L.removeField(G.GENERAL, '우선순위') }))

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    // 통째로 비우는 구현이 여기서 죽는다.
    expect(lastGroups(stub)).toEqual({ GENERAL: ['status', 'labels'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑦⑧⑨ 순서 축 (J48 — *"drag and drop the field up or down in the list"*)
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 드래그 순서 (J48)', () => {
  it('T-DV-7: 드래그로 순서를 바꾸면 멤버십이 같고 순서만 다른 목록이 저장된다', async () => {
    // ★이 파일의 RED 다. 기대값 `['labels','status','priority']` 는 **원래 순서와도**
    //   **사전순(labels·priority·status)과도** 다르다 — 그래서 다음 셋이 한꺼번에 죽는다.
    //   ① 화면만 바꾸고 저장하지 않는 구현 ② 저장을 집합으로 하는 구현(멤버십만 같으면 통과)
    //   ③ `sorted()` 를 끼운 구현.
    const before = ['status', 'priority', 'labels']
    const stub = stubDetailViewApi({ GENERAL: before })
    renderPanel(board({ detailViewFields: fields({ GENERAL: before }) }))

    await drag({ group: 'GENERAL', key: 'labels' }, { group: 'GENERAL', key: 'status' })

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    const saved = lastGroups(stub)['GENERAL'] ?? []
    expect(saved).toEqual(['labels', 'status', 'priority'])
    // 멤버십은 그대로다 — 「순서만 다른」 저장임을 이 짝이 못 박는다.
    expect([...saved].sort()).toEqual([...before].sort())
    expect(saved).not.toEqual(before)
    // 화면도 같은 순서를 보여야 한다 — 저장만 되고 화면이 안 따라오면 다음 조작이 옛 순서에서 파생된다.
    expect(listedFields('GENERAL')).toEqual(['라벨', '상태', '우선순위'])
  })

  it('T-DV-8: 다른 그룹으로 끌면 아무것도 저장되지 않는다 — J48 은 목록 안의 위아래다', async () => {
    const stub = stubDetailViewApi()
    renderPanel(
      board({
        detailViewFields: { GENERAL: ['status'], DATE: ['dueDate'], PEOPLE: [], LINKS: [] },
      }),
    )

    await drag({ group: 'GENERAL', key: 'status' }, { group: 'DATE', key: 'dueDate' })

    expect(stub.requests).toEqual([])
    // 엉뚱한 그룹을 덮는 구현이 여기서 죽는다.
    expect(listedFields('GENERAL')).toEqual(['상태'])
    expect(listedFields('DATE')).toEqual(['마감일'])
  })

  it('T-DV-9: 제자리에 놓으면 요청을 만들지 않는다 — T-DV-7 의 대조군', async () => {
    const stub = stubDetailViewApi()
    renderPanel(
      board({ detailViewFields: fields({ GENERAL: ['status', 'priority'] }) }),
    )

    await drag({ group: 'GENERAL', key: 'status' }, { group: 'GENERAL', key: 'status' })

    // 「모든 드롭을 저장하는」 구현이 여기서 죽는다.
    expect(stub.requests).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑩⑪⑫⑬ 초기값 · 응답 · 재마운트
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 저장돼 있던 구성을 읽는다 (N1 · Task 31)', () => {
  it('T-DV-10: 저장돼 있던 구성 위에 하나 추가하면 기존 구성이 요청에 남는다 — 덮어쓰기 방지', async () => {
    // ★`PATCH` 는 **그룹 통째 교체**라, 초기값을 안 읽는 구현은 「필드 하나를 더했을 뿐인데
    //   상태·우선순위가 사라지는」 **데이터 소실**을 낸다. 화면이 아니라 **요청 바디**를 잰다.
    const stub = stubDetailViewApi({ GENERAL: ['status', 'priority'] })
    renderPanel(
      board({ detailViewFields: fields({ GENERAL: ['status', 'priority'] }) }),
    )

    await addField('GENERAL', '라벨')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    expect(lastGroups(stub)).toEqual({ GENERAL: ['status', 'priority', 'labels'] })
  })

  it('T-DV-11: 응답에만 있는 다른 그룹 구성이 화면에 반영된다', async () => {
    // 요청 echo 를 화면 상태로 삼는 구현이 여기서 죽는다 — 응답은 저장 후 다시 읽은 전체 구성이다.
    const stub = stubDetailViewApi({ LINKS: ['epic'] })
    renderPanel(board())

    await addField('DATE', '마감일')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    await waitFor(() => {
      expect(listedFields('LINKS')).toEqual(['에픽'])
    })
  })

  it('T-DV-12: 저장에 성공하면 보드 조회를 무효화한다 — 다음 마운트가 서버 값을 읽는다', async () => {
    // ★로컬 state 는 탭을 옮기는 순간 사라진다(`TabsContent` 는 `forceMount` 가 아니다).
    //   무효화하지 않으면 다시 들어왔을 때 **저장 전 캐시**가 초기값이 되어, 그 다음 조작이
    //   방금 저장한 것을 도로 덮는다.
    const stub = stubDetailViewApi()
    const { queryClient } = renderPanel()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await addField('PEOPLE', '담당자')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    await waitFor(() => {
      expect(invalidate).toHaveBeenCalledWith({ queryKey: boardKeys.detail(BOARD_ID) })
    })
  })

  it('T-DV-13: 언마운트 후 다시 마운트해도 보드가 실어 온 구성이 다시 뜬다', () => {
    // 탭 전환의 실제 모습이다 — Radix 는 비활성 탭 본문을 언마운트하므로 로컬 state 는 사라진다.
    const detail = board({
      detailViewFields: fields({ GENERAL: ['status'], PEOPLE: ['assignee'] }),
    })
    const first = renderPanel(detail)
    expect(listedFields('GENERAL')).toEqual(['상태'])

    first.unmount()
    renderPanel(detail)

    expect(listedFields('GENERAL')).toEqual(['상태'])
    expect(listedFields('PEOPLE')).toEqual(['담당자'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑭ 연속 드롭 잠금 (스펙 E8 · `#452` BLOCKER B2 와 같은 처방)
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 연속 드롭 lost update 차단 (E8)', () => {
  it('T-DV-14: 저장 중에는 두 번째 드롭이 먹지 않고 핸들이 잠긴다', async () => {
    // ★잠금이 없으면 두 드롭이 **같은 낡은 목록**에서 파생돼 나중 것이 앞 변경을 덮는다.
    //   서버는 둘 다 200 이라 아무도 오류를 못 본다 — `#452` 가 B2 로 닫은 자리와 같다.
    const initial = ['status', 'priority', 'labels']
    const stub = stubDetailViewApi({ GENERAL: initial }, { hold: true })
    renderPanel(board({ detailViewFields: fields({ GENERAL: initial }) }))

    await drag({ group: 'GENERAL', key: 'labels' }, { group: 'GENERAL', key: 'status' })
    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })

    // 첫 저장이 아직 서버에 가 있다 — 핸들이 잠겨 있어야 한다.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: L.reorderHandle(G.GENERAL, '상태') })).toBeDisabled()
    })

    // 그럼에도 드롭이 들어오면(키보드 센서·경합) 무시해야 한다.
    await drag({ group: 'GENERAL', key: 'priority' }, { group: 'GENERAL', key: 'labels' })
    expect(stub.requests).toHaveLength(1)

    stub.release()
    await waitFor(() => {
      expect(stub.stored['GENERAL']).toEqual(['labels', 'status', 'priority'])
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑮⑯⑰⑱ 실패 · 권한 · 모르는 키
// ─────────────────────────────────────────────────────────────────────────────

describe('상세 보기 탭 — 실패와 잠금', () => {
  it('T-DV-15: 저장에 실패하면 값이 되돌아가고 오류가 화면에 남는다', async () => {
    const stub = stubDetailViewApi({ GENERAL: ['status'] }, { status: 500 })
    renderPanel(board({ detailViewFields: fields({ GENERAL: ['status'] }) }))

    await addField('GENERAL', '라벨')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    // 토스트 단독은 쓰지 않는다 — 화면에 남는 상태 표시가 있어야 한다.
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(L.saveFailed)
    })
    // 낙관 반영을 되돌린다. 그대로 두면 사용자는 저장된 줄 안다.
    expect(listedFields('GENERAL')).toEqual(['상태'])
    expect(screen.getByRole('button', { name: L.saveRetry })).toBeEnabled()
  })

  it('T-DV-16: 403 은 본문이 비어도 권한 문구를 낸다 — 상태 코드로만 가른다', async () => {
    // 봉투 본문을 파싱하는 구현이 여기서 죽는다 — 구현은 상태 코드로만 갈라야 한다.
    const stub = stubDetailViewApi({}, { status: 403 })
    renderPanel(board())

    await addField('LINKS', '에픽')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(L.saveForbidden)
    })
  })

  it('T-DV-17: 편집 권한이 없으면 추가·삭제·드래그가 모두 잠긴다 (S7)', () => {
    renderPanel(
      board({ detailViewFields: fields({ GENERAL: ['status'] }) }),
      false,
    )

    // 구성 자체는 읽을 수 있어야 한다 — 진입을 막지 않고 편집만 잠근다.
    expect(listedFields('GENERAL')).toEqual(['상태'])
    expect(screen.getByRole('combobox', { name: L.candidateLabel(G.GENERAL) })).toBeDisabled()
    expect(screen.getByRole('button', { name: L.addField(G.GENERAL) })).toBeDisabled()
    expect(screen.getByRole('button', { name: L.removeField(G.GENERAL, '상태') })).toBeDisabled()
    expect(screen.getByRole('button', { name: L.reorderHandle(G.GENERAL, '상태') })).toBeDisabled()
  })

  it('T-DV-18: 카탈로그에 없는 키도 원문으로 그려지고 다음 저장에 살아남는다', async () => {
    // ★숨기면 그 그룹을 한 번 건드리는 순간 그 키가 사라진다 — 저장이 그룹 통째 교체이기 때문이다.
    const stored = ['cf_severity', 'status']
    const stub = stubDetailViewApi({ GENERAL: stored })
    renderPanel(board({ detailViewFields: fields({ GENERAL: stored }) }))

    expect(listedFields('GENERAL')).toEqual(['cf_severity', '상태'])

    await addField('GENERAL', '라벨')

    await waitFor(() => {
      expect(stub.requests).toHaveLength(1)
    })
    expect(lastGroups(stub)).toEqual({ GENERAL: ['cf_severity', 'status', 'labels'] })
  })
})
