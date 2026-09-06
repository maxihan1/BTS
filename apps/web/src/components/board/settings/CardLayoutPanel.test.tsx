// 카드 레이아웃 탭 동반 테스트 — 뷰 축 · 3개 상한 · 커스텀 필드 접두사 (부채 177 Task 16 · J17·J18)
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 뷰 축 | 보드/백로그에 **서로 다른** 구성 (T-CL-1) | 한 목록을 두 뷰가 공유 ↔ 뷰별 상태 (J18) |
// | ② 뷰 토글 존재 | 스크럼에 토글이 **있다** (T-CL-2) | 아무에게도 안 그림 ↔ 스크럼에 그림 |
// | ③ 뷰 토글 부재 | 칸반에 토글이 **없다** (T-CL-3) | 모두에게 그림 ↔ 보드 종류로 갈림 (R3) |
// | ④ 상한 하한 | 3개까지는 고를 수 있다 (T-CL-4) | off-by-one(2개 제한) ↔ 정확히 3 |
// | ⑤ 상한 상한 | 4번째 후보가 **비활성** (T-CL-5) | 상한 없음(서버 400) ↔ 화면이 먼저 막음 |
// | ⑥ 커스텀 접두사 | `cf_` 를 붙여 보낸다 (T-CL-6) | 접두사 없이 전송(400) ↔ 규약대로 부착 |
// | ⑦ 표준 무접두사 | 표준 키는 그대로 보낸다 (T-CL-7) | 모든 키에 `cf_` ↔ 커스텀만 접두 |
// | ⑧ 응답 반영 | 응답에만 있는 뷰 구성이 화면에 뜬다 (T-CL-8) | 요청 echo 신뢰 ↔ 응답을 읽음 |
// | ⑨ 실패 되돌림 | 저장 실패 시 체크가 풀리고 오류가 남는다 (T-CL-9) | 낙관 상태 유지·토스트 단독 ↔ 되돌림 + 화면 표시 |
// | ⑩ 오류 판정축 | 본문이 비어도 403 문구 (T-CL-10) | 봉투 본문 파싱 ↔ 상태 코드 판정 |
// | ⑪ 권한 잠금 | `canConfigure=false` 면 후보 비활성 (T-CL-11) | 항상 편집 가능 ↔ CREATE 로만 열림 (S7) |
// | ⑫ 후보 3상태 | 커스텀 로딩·에러·빈 (T-CL-12·13·14) | 빈 `<div/>` 침묵 ↔ 세 상태 전부 |
// | ⑬ 덮어쓰기 | 저장돼 있던 구성 위에 **한 필드만** 토글 (T-CL-15) | `{}` 에서 시작해 그 뷰를 통째로 덮음(**데이터 소실**) ↔ 초기값을 읽고 더함 |
// | ⑭ 초기 렌더 | 보드가 실어 온 구성이 체크된 채 뜬다 (T-CL-16) | 응답을 무시하고 빈 화면 ↔ `board.cardLayout` 을 읽음 |
// | ⑮ 미설정 대조군 | 구성이 **없는** 보드는 빈 상태 (T-CL-17) | 항상 기본값을 채움 ↔ 없으면 없는 대로 |
// | ⑯ 재마운트 | 탭 복귀(언마운트→재마운트) 후에도 유지 (T-CL-18·19) | 로컬 state 에만 의존 ↔ 보드 조회를 무효화하고 초기값에서 다시 읽음 |
//
// ★**⑬ 이 이 파일의 가장 비싼 판정이다.** `replaceCardLayout` 은 **뷰 통째 교체**라, 초기값을
// 읽지 않는 구현은 「필드 하나를 켰을 뿐인데 나머지 둘이 사라지는」 **데이터 소실**을 낸다.
// ⑭ 만 두면 「그리기는 하는데 요청은 로컬 state 로 만드는」 구현이 통과한다 — ⑬ 은 **요청 바디**를
// 재고 ⑭ 는 **화면**을 잰다. 둘은 다른 축이다.
// ★**⑮ 는 ⑭ 의 짝이다.** 없으면 「무엇이든 기본 3개를 채우는」 구현이 ⑭ 를 통과한다.
//
// ★**②③ 과 ④⑤ 는 짝으로만 산다.** 「칸반은 토글이 없다」만 두면 **아무에게도 토글을 안 그리는**
// 구현이 통과하고, 「4번째가 비활성」만 두면 **2개에서 이미 막는** 구현이 통과한다. 짝을 지운
// 상태로는 어느 쪽도 판정이 되지 않는다.
//
// ★**MSW 스텁은 Task 8 의 실제 응답 형태를 따른다** — `{ data: { cardLayout: { BOARD: [...] } } }`.
// 뷰 단위 교체(요청에 없는 뷰는 그대로)까지 흉내 내므로, 목이 실제 서버와 갈려 「유닛만 초록」이
// 되는 자리를 줄인다. 형태가 어긋나면 `cardLayoutSchema.parse` 가 이 파일에서 먼저 죽는다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import type { BoardDetail } from '@/api/boards'
import { boardKeys } from '@/hooks/use-boards'
import { CardLayoutPanel } from './CardLayoutPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const PROJECT_KEY = 'ATLAS'

function board(overrides: Partial<BoardDetail> = {}): BoardDetail {
  return {
    boardId: BOARD_ID,
    projectKey: PROJECT_KEY,
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
interface CardLayoutStub {
  /** 받은 요청 바디 전량. 순서가 곧 호출 순서다. */
  requests: { cardLayout?: Record<string, string[]> }[]
  /** 서버 측 저장 상태. **뷰 단위 교체** — 요청에 없는 뷰는 그대로 둔다(J18). */
  stored: Record<string, string[]>
}

/**
 * `PATCH /api/v1/boards/{boardId}/card-layout` 스텁.
 *
 * @param initial 이미 저장돼 있는 구성. 「응답에만 있는 뷰」를 만들 때 쓴다.
 * @param status 지정하면 그 상태 코드로 실패시킨다. **본문은 비운다** — 본문 구조에 기대는
 *   구현을 여기서 잡는다(구현은 상태 코드로만 갈라야 한다 · `api/board-settings.ts` 「오류 본문」).
 */
function stubCardLayoutApi(
  initial: Record<string, string[]> = {},
  status?: number,
): CardLayoutStub {
  const stub: CardLayoutStub = { requests: [], stored: { ...initial } }
  server.use(
    http.patch('/api/v1/boards/:boardId/card-layout', async ({ request }) => {
      const body = (await request.json()) as { cardLayout?: Record<string, string[]> }
      stub.requests.push(body)
      if (status !== undefined) return new HttpResponse(null, { status })
      for (const [view, fields] of Object.entries(body.cardLayout ?? {})) {
        stub.stored[view] = fields
      }
      return HttpResponse.json({ data: { cardLayout: stub.stored } })
    }),
  )
  return stub
}

/** 커스텀 필드 목록 스텁 — `customFieldResponseSchema` 전 필드를 채운다. */
function stubCustomFields(fields: { key: string; name: string }[], status?: number): void {
  server.use(
    http.get('/api/v1/projects/:projectIdOrKey/custom-fields', () => {
      if (status !== undefined) return new HttpResponse(null, { status })
      return HttpResponse.json({
        data: fields.map((field, index) => ({
          id: `00000000-0000-4000-8000-00000000000${String(index + 1)}`,
          projectId: '00000000-0000-4000-8000-0000000000ff',
          key: field.key,
          name: field.name,
          description: null,
          fieldType: 'NUMBER',
          required: false,
          displayOrder: index,
          options: [],
        })),
      })
    }),
  )
}

/** 렌더 결과 — 재마운트 축과 무효화 축이 각각 `unmount`·`queryClient` 를 본다. */
interface RenderedPanel {
  queryClient: QueryClient
  unmount: () => void
  rerender: (detail: BoardDetail) => void
}

function renderPanel(detail: BoardDetail = board(), canConfigure = true): RenderedPanel {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <CardLayoutPanel board={detail} canConfigure={canConfigure} />
    </QueryClientProvider>,
  )
  return {
    queryClient,
    unmount: view.unmount,
    rerender: (next) => {
      view.rerender(
        <QueryClientProvider client={queryClient}>
          <CardLayoutPanel board={next} canConfigure={canConfigure} />
        </QueryClientProvider>,
      )
    },
  }
}

/**
 * 후보를 누르고 저장이 정착할 때까지 기다린다.
 *
 * 저장 중에는 후보 전체가 잠긴다(연속 토글 lost update 방어 — `#452` E8 과 같은 처방).
 * 그래서 다음 클릭 전에 **요청이 도착했고 잠금이 풀렸음**을 함께 기다려야 한다.
 */
async function toggleField(name: string | RegExp, stub: CardLayoutStub): Promise<void> {
  const before = stub.requests.length
  // ★`findBy` 로 기다린다 — 커스텀 필드 후보는 조회가 끝난 뒤에야 목록에 붙는다.
  await userEvent.click(await screen.findByRole('checkbox', { name }))
  await waitFor(() => {
    expect(stub.requests).toHaveLength(before + 1)
  })
  await waitFor(() => {
    expect(screen.getByRole('checkbox', { name })).toBeEnabled()
  })
}

/** 뷰 토글의 라디오. 스크럼에만 있다. */
function viewRadio(name: string): HTMLElement {
  return screen.getByRole('radio', { name })
}

beforeEach(() => {
  stubCustomFields([{ key: 'story_points', name: '스토리 포인트' }])
})

// ─────────────────────────────────────────────────────────────────────────────
// ①②③ 뷰 축 (R3 · J18)
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 뷰 축 (J18)', () => {
  it('T-CL-1: 보드와 백로그에 서로 다른 구성을 담는다', async () => {
    // 일부러 **다른** 필드를 넣는다 — 같은 값을 넣으면 두 뷰가 한 목록을 공유하는 구현도 통과한다.
    const stub = stubCardLayoutApi()
    renderPanel()

    await toggleField('에픽', stub)
    await userEvent.click(viewRadio('백로그'))

    // 백로그는 아직 비어 있다. 공유 구현이면 여기서 이미 에픽이 켜져 있다.
    expect(screen.getByRole('checkbox', { name: '에픽' })).not.toBeChecked()
    await toggleField('추정치', stub)

    expect(stub.requests).toEqual([
      { cardLayout: { BOARD: ['EPIC'] } },
      { cardLayout: { BACKLOG: ['ESTIMATE'] } },
    ])

    // 보드로 되돌아가면 에픽이 그대로 있고 추정치는 꺼져 있다.
    await userEvent.click(viewRadio('보드'))
    expect(screen.getByRole('checkbox', { name: '에픽' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '추정치' })).not.toBeChecked()
  })

  it('T-CL-2: 스크럼 보드는 뷰 토글을 그린다 — T-CL-3 의 짝', () => {
    renderPanel(board({ boardType: 'SCRUM' }))

    expect(screen.getByRole('radiogroup')).toBeInTheDocument()
    expect(viewRadio('보드')).toBeInTheDocument()
    expect(viewRadio('백로그')).toBeInTheDocument()
  })

  it('T-CL-3: 칸반 보드는 뷰 토글을 그리지 않는다 — 백로그 스코프 자체가 없다 (R3)', () => {
    renderPanel(board({ boardType: 'KANBAN' }))

    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    // ★패널이 통째로 사라진 것이 아님을 함께 잰다 — 토글만 없고 후보는 그대로다.
    expect(screen.getByRole('checkbox', { name: '에픽' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ④⑤ 상한 3개 (J17)
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 뷰당 3개 상한 (J17)', () => {
  it('T-CL-4: 3개까지는 고를 수 있다 — T-CL-5 의 짝', async () => {
    // 2개에서 막는 off-by-one 구현이 여기서 죽는다.
    const stub = stubCardLayoutApi()
    renderPanel()

    await toggleField('에픽', stub)
    await toggleField('우선순위', stub)
    await toggleField('라벨', stub)

    expect(screen.getByRole('checkbox', { name: '라벨' })).toBeChecked()
    expect(stub.requests.at(-1)).toEqual({ cardLayout: { BOARD: ['EPIC', 'PRIORITY', 'LABELS'] } })
  })

  it('T-CL-5: 3개를 고르면 4번째 후보는 비활성이고, 고른 것은 여전히 해제할 수 있다', async () => {
    const stub = stubCardLayoutApi()
    renderPanel()

    await toggleField('에픽', stub)
    await toggleField('우선순위', stub)
    await toggleField('라벨', stub)

    expect(screen.getByRole('checkbox', { name: '담당자' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '스토리 포인트' })).toBeDisabled()
    // ★고른 것까지 잠그면 상한에 닿은 사용자가 아무것도 못 바꾼다 — 막다른 골목이 된다.
    expect(screen.getByRole('checkbox', { name: '라벨' })).toBeEnabled()

    await toggleField('라벨', stub)
    expect(screen.getByRole('checkbox', { name: '담당자' })).toBeEnabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑥⑦ 커스텀 필드 접두사 (R2 · Task 8 규약)
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 커스텀 필드 (R2)', () => {
  it('T-CL-6: 커스텀 필드를 후보로 보이고 `cf_` 를 붙여 보낸다', async () => {
    // `custom_field_definitions.key` 에는 접두사가 없다 — 붙이는 것은 이 화면의 몫이다.
    const stub = stubCardLayoutApi()
    renderPanel()

    await toggleField('스토리 포인트', stub)

    expect(stub.requests).toEqual([{ cardLayout: { BOARD: ['cf_story_points'] } }])
  })

  it('T-CL-7: 표준 필드는 접두사 없이 보낸다 — T-CL-6 의 짝', async () => {
    // 「모든 키에 cf_ 를 붙이는」 구현이 여기서 죽는다. 서버 카탈로그는 `EPIC` 를 그대로 받는다.
    const stub = stubCardLayoutApi()
    renderPanel()

    await toggleField('에픽', stub)

    expect(stub.requests).toEqual([{ cardLayout: { BOARD: ['EPIC'] } }])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑧⑨⑩ 저장 응답과 실패
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 저장 응답 (N1)', () => {
  it('T-CL-8: 응답에만 있는 뷰 구성이 화면에 반영된다 — 요청 echo 를 믿지 않는다', async () => {
    // 서버에는 백로그 구성이 이미 있다(다른 사람이 저장했다). 응답을 읽지 않고 자기가 보낸
    // 값만 화면 상태로 삼는 구현은 백로그를 영영 빈 것으로 그린다.
    const stub = stubCardLayoutApi({ BACKLOG: ['LABELS'] })
    renderPanel()

    await toggleField('에픽', stub)
    await userEvent.click(viewRadio('백로그'))

    expect(screen.getByRole('checkbox', { name: '라벨' })).toBeChecked()
  })

  it('T-CL-9: 저장이 실패하면 체크가 되돌아가고 화면에 오류가 남는다', async () => {
    const stub = stubCardLayoutApi({}, 500)
    renderPanel()

    await userEvent.click(screen.getByRole('checkbox', { name: '에픽' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // ★토스트만 띄우고 화면 상태를 그대로 두면 사용자는 저장된 줄 안다.
    expect(screen.getByRole('checkbox', { name: '에픽' })).not.toBeChecked()
    expect(stub.requests).toHaveLength(1)

    // 재시도는 화면에 남는 액션이어야 한다 — 다시 누르면 같은 구성을 다시 보낸다.
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => {
      expect(stub.requests).toEqual([
        { cardLayout: { BOARD: ['EPIC'] } },
        { cardLayout: { BOARD: ['EPIC'] } },
      ])
    })
  })

  it('T-CL-10: 403 은 본문이 비어도 권한 문구를 낸다 — 상태 코드로 가른다', async () => {
    // 구현은 상태 코드로만 갈라야 한다. 본문 구조에 기대는 구현이 여기서 죽는다.
    stubCardLayoutApi({}, 403)
    renderPanel()

    await userEvent.click(screen.getByRole('checkbox', { name: '에픽' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('권한')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑪ 권한 · ⑫ 후보 목록 3상태
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 권한과 상태 3종 (S7)', () => {
  it('T-CL-11: CREATE 권한이 없으면 후보를 고를 수 없다 — 목록은 보인다', () => {
    renderPanel(board(), false)

    expect(screen.getByRole('checkbox', { name: '에픽' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '에픽' })).toBeInTheDocument()
  })

  it('T-CL-12: 커스텀 필드를 불러오는 동안 스켈레톤을 그린다', async () => {
    renderPanel()

    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
    // 조회가 끝나면 스켈레톤이 걷힌다 — 남아 있으면 영구 로딩이다.
    expect(await screen.findByRole('checkbox', { name: '스토리 포인트' })).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(0)
  })

  it('T-CL-13: 커스텀 필드 조회 실패는 화면에 남고 재시도할 수 있다', async () => {
    stubCustomFields([], 500)
    renderPanel()

    expect(await screen.findByText('커스텀 필드를 불러오지 못했습니다.')).toBeInTheDocument()
    // ★저장 재시도 버튼(「다시 시도」)과 **서로 substring 이 아니어야 한다** — Playwright
    //   `getByRole('button', { name })` 은 기본이 부분 일치라, 둘이 함께 뜨는 순간(서버 다운)
    //   한쪽 이름이 다른 쪽을 품으면 strict mode 로 즉사한다.
    expect(screen.getByRole('button', { name: '커스텀 필드 다시 불러오기' })).toBeInTheDocument()
    // ★표준 필드는 그대로 고를 수 있다 — 커스텀 조회 실패가 탭 전체를 막지 않는다.
    expect(screen.getByRole('checkbox', { name: '에픽' })).toBeEnabled()
  })

  it('T-CL-14: 커스텀 필드가 0개면 그렇다고 말한다 — 빈 채로 두지 않는다', async () => {
    stubCustomFields([])
    renderPanel()

    expect(
      await screen.findByText('이 프로젝트에는 커스텀 필드가 없습니다.'),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑬⑭⑮⑯ 저장돼 있던 구성을 초기값으로 읽는다 (부채 177 Task 31 · N1)
// ─────────────────────────────────────────────────────────────────────────────

describe('카드 레이아웃 탭 — 저장된 구성을 초기값으로 읽는다 (N1)', () => {
  it('T-CL-15: 저장돼 있던 구성 위에 한 필드만 켜면 기존 구성이 요청에 남는다 — 덮어쓰기 방지', async () => {
    // ★이 파일에서 가장 비싼 판정이다. `PATCH` 는 **뷰 통째 교체**라, 초기값을 안 읽는 구현은
    //   「라벨 하나를 켰을 뿐인데 에픽·우선순위가 사라지는」 **데이터 소실**을 낸다.
    //   화면(⑭)이 아니라 **요청 바디**를 재는 것이 요점이다.
    const stub = stubCardLayoutApi({ BOARD: ['EPIC', 'PRIORITY'] })
    renderPanel(board({ cardLayout: { BOARD: ['EPIC', 'PRIORITY'] } }))

    await toggleField('라벨', stub)

    expect(stub.requests).toEqual([{ cardLayout: { BOARD: ['EPIC', 'PRIORITY', 'LABELS'] } }])
  })

  it('T-CL-16: 보드가 실어 온 구성이 뷰마다 체크된 채 뜬다', async () => {
    // 두 뷰에 **다른** 값을 넣는다 — 같은 값이면 한 뷰만 읽는 구현도 통과한다.
    renderPanel(
      board({ cardLayout: { BOARD: ['EPIC'], BACKLOG: ['LABELS'] } }),
    )

    expect(await screen.findByRole('checkbox', { name: '에픽' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '라벨' })).not.toBeChecked()

    await userEvent.click(viewRadio('백로그'))
    expect(screen.getByRole('checkbox', { name: '라벨' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '에픽' })).not.toBeChecked()
  })

  it('T-CL-17: 구성이 없는 보드는 빈 상태로 뜬다 — T-CL-16 의 짝', async () => {
    // 「무엇이든 기본값을 채우는」 구현이 여기서 죽는다. 빈 구성은 「현행 카드를 그린다」는 뜻이다.
    const stub = stubCardLayoutApi()
    renderPanel(board())

    // 커스텀 후보까지 다 붙은 뒤에 센다 — 조회 전에 세면 「아직 안 온 것」을 「안 켜진 것」으로 읽는다.
    expect(await screen.findByRole('checkbox', { name: '스토리 포인트' })).not.toBeChecked()
    for (const name of ['에픽', '우선순위', '담당자', '라벨', '추정치', '이슈 종류']) {
      expect(screen.getByRole('checkbox', { name })).not.toBeChecked()
    }

    await toggleField('에픽', stub)
    expect(stub.requests).toEqual([{ cardLayout: { BOARD: ['EPIC'] } }])
  })

  it('T-CL-18: 저장에 성공하면 보드 조회를 무효화한다 — 다음 마운트가 서버 값을 읽는다', async () => {
    // ★로컬 state 는 탭을 옮기는 순간 사라진다(`TabsContent` 는 `forceMount` 가 아니다).
    //   무효화하지 않으면 다시 들어왔을 때 **저장 전 캐시**가 초기값이 되어, 그 다음 토글이
    //   방금 저장한 것을 도로 덮는다. 재마운트가 안전한 유일한 근거가 이 무효화다.
    const stub = stubCardLayoutApi()
    const { queryClient } = renderPanel()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await toggleField('에픽', stub)

    expect(invalidate).toHaveBeenCalledWith({ queryKey: boardKeys.detail(BOARD_ID) })
  })

  it('T-CL-19: 언마운트 후 다시 마운트해도 보드가 실어 온 구성이 다시 체크된다', async () => {
    // 탭 전환의 실제 모습이다 — Radix 는 비활성 탭 본문을 언마운트하므로 로컬 state 는 사라진다.
    const detail = board({ cardLayout: { BOARD: ['EPIC'] } })
    const first = renderPanel(detail)
    expect(await screen.findByRole('checkbox', { name: '에픽' })).toBeChecked()

    first.unmount()
    renderPanel(detail)

    expect(await screen.findByRole('checkbox', { name: '에픽' })).toBeChecked()
  })
})
