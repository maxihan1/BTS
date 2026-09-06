// 추정 탭 동반 테스트 — 칸반 잠금 · 저장된 값 초기화 · 409 판정 (부채 177 Task 17 · J36·J37)
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 스크럼 편집 | 스크럼에서 값을 **바꿀 수 있다** (T-ES-1) | 아무에게도 편집을 안 줌 ↔ 스크럼만 열림 |
// | ② 칸반 잠금 | 칸반은 비활성이고 **요청이 안 나간다** (T-ES-2) | 모두에게 편집 허용 ↔ 보드 종류로 갈림 (J37) |
// | ③ 잠금 사유 | 칸반에 **왜 잠겼는지**가 화면에 뜬다 (T-ES-3) | 조용히 비활성 ↔ 사유 표시 |
// | ④ E6 값 보존 | 칸반이어도 **저장된 값이 보인다** (T-ES-4) | 잠그며 기본값으로 되돌림(값이 지워진 것처럼 보임) ↔ 값 보존 |
// | ⑤ 초기값 | 저장된 값이 선택된 채 뜬다 (T-ES-5) | 항상 `NONE` 에서 시작 ↔ `board.timeTracking` 을 읽음 |
// | ⑥ 미설정 대조군 | `timeTracking` 이 없는 보드는 `없음` (T-ES-6) | 무엇이든 기본값을 채움 ↔ 없으면 없는 대로 |
// | ⑦ 재마운트 | 탭 복귀(언마운트→재마운트) 후에도 유지 (T-ES-7) | 로컬 state 에만 의존 ↔ 매 마운트가 board prop 에서 다시 읽음 |
// | ⑧ 요청 바디 | `{ timeTracking }` 한 키를 보낸다 (T-ES-8) | 카드 레이아웃 봉투 복제 ↔ Task 9 계약 |
// | ⑨ 실패 되돌림 | 저장 실패 시 선택이 돌아오고 오류가 남는다 (T-ES-9) | 낙관 상태 유지·토스트 단독 ↔ 되돌림 + 화면 표시 |
// | ⑩ 409 판정축 | **본문이 비어도** 409 면 잠금 사유 (T-ES-10) | 봉투 본문 파싱 ↔ 상태 코드 판정 |
// | ⑪ 403 판정축 | 본문이 비어도 403 이면 권한 문구 (T-ES-11) | 409 와 뭉뚱그림 ↔ 상태 코드별 문구 |
// | ⑫ 409 재시도 부재 | 409 에는 **재시도 버튼이 없다** (T-ES-14) | 모든 실패에 재시도(눌러도 안 됨) ↔ 풀리지 않는 실패는 재시도를 안 준다 |
// | ⑬ 권한 잠금 | `canConfigure=false` 면 비활성 (T-ES-12) | 항상 편집 가능 ↔ CREATE 로만 열림 (S7) |
// | ⑭ 무효화 | 저장 성공 시 보드 조회를 무효화 (T-ES-13) | 로컬 state 에만 반영 ↔ 다음 마운트가 서버 값을 읽음 |
//
// ★**①②③ 은 짝으로만 산다.** 「칸반은 잠긴다」만 두면 **아무에게도 편집을 안 주는** 구현이
// 통과한다 — 화면이 통째로 비활성이어도 ② 는 초록이다. ① 이 그 구현을 죽인다.
// 반대로 ① 만 두면 **모두에게 편집을 주는** 구현이 통과한다. ③ 은 또 다른 축이다 —
// 사유 없이 비활성만 하면 사용자는 「고장」으로 읽는다.
//
// ★**④ 가 이 파일에서 가장 비싼 판정이다(스펙 E6).** 백엔드는 칸반으로 바꿔도 `time_tracking`
// 을 **지우지 않는다** — 되돌리면 살아나야 한다. 「잠그면서 기본값으로 되돌리는」 UI 는 그
// 계약을 화면에서 깨뜨린다(사용자는 값이 지워졌다고 읽는다). ⑤ 는 스크럼에서 같은 것을 재고
// ④ 는 **잠긴 채로도** 재므로 둘은 다른 축이다.
//
// ★**⑤ 와 ⑥ 은 짝이다.** ⑤ 만 두면 「무엇이든 `REMAINING_AND_SPENT` 를 채우는」 구현이 통과한다.
//
// ★**⑦ 은 T16 의 T-CL-19 와 같은 축이다.** `SettingsTabs` 는 `forceMount` 없는 Radix
// `TabsContent` 라 탭을 옮겼다 돌아오기만 해도 이 패널이 다시 마운트된다. 초기값을 읽지 않는
// 구현은 그때마다 `없음` 으로 되돌아가고, 그 상태에서의 첫 조작이 저장돼 있던 값을 덮는다.
//
// ★**MSW 스텁은 Task 31 의 실제 핸들러(`mocks/board-handlers.ts` `patchEstimationHandler`)
// 형태를 따른다** — 요청 `{ timeTracking }` · 응답 `{ data: { timeTracking } }`. 형태가 어긋나면
// `estimationResponseSchema.parse` 가 이 파일에서 먼저 죽는다(유닛만 초록인 자리를 줄인다).
import { describe, it, expect, expectTypeOf, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import type { BoardDetail, TimeTracking } from '@/api/boards'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import { EstimationPanel } from './EstimationPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

/**
 * 옵션 라벨 — 화면 문구가 곧 접근성 이름이다.
 *
 * ★리터럴을 복제하지 않고 `board-labels.ts` 를 읽는다(Task 17 REFACTOR). 복제하면 문구를
 * 고친 날 테스트만 조용히 낡는다. 이 축이 재는 것은 **문구 자체가 아니라 「그 문구가 화면에
 * 있는가」** 이므로 정본을 참조하는 편이 정확하다.
 */
const { estimation } = boardLabels.settings
const NONE_LABEL = estimation.optionNone
const SPENT_LABEL = estimation.optionRemainingAndSpent

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
interface EstimationStub {
  /** 받은 요청 바디 전량. 순서가 곧 호출 순서다. */
  requests: { timeTracking?: string }[]
  /** 서버 측 저장 상태. */
  stored: string
}

/**
 * `PATCH /api/v1/boards/{boardId}/estimation` 스텁.
 *
 * @param initial 이미 저장돼 있는 값.
 * @param status 지정하면 그 상태 코드로 실패시킨다. **본문은 비운다** — 본문 구조에 기대는
 *   구현을 여기서 잡는다(구현은 상태 코드로만 갈라야 한다 · `api/board-settings.ts` 「오류 본문」).
 */
function stubEstimationApi(initial: TimeTracking = 'NONE', status?: number): EstimationStub {
  const stub: EstimationStub = { requests: [], stored: initial }
  server.use(
    http.patch('/api/v1/boards/:boardId/estimation', async ({ request }) => {
      const body = (await request.json()) as { timeTracking?: string }
      stub.requests.push(body)
      if (status !== undefined) return new HttpResponse(null, { status })
      stub.stored = body.timeTracking ?? stub.stored
      return HttpResponse.json({ data: { timeTracking: stub.stored } })
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
      <EstimationPanel board={detail} canConfigure={canConfigure} />
    </QueryClientProvider>,
  )
  return { queryClient, unmount: view.unmount }
}

/** 옵션 라디오. */
function option(name: string): HTMLElement {
  return screen.getByRole('radio', { name })
}

/** 옵션을 고르고 요청이 도착할 때까지 기다린다. */
async function choose(name: string, stub: EstimationStub): Promise<void> {
  const before = stub.requests.length
  await userEvent.click(option(name))
  await waitFor(() => {
    expect(stub.requests).toHaveLength(before + 1)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// ①②③ 스크럼만 편집 가능 · 칸반은 사유를 보이며 잠근다 (J37 · R4)
// ─────────────────────────────────────────────────────────────────────────────

describe('추정 탭 — 스크럼/칸반 대조군 (J37)', () => {
  it('T-ES-1: 스크럼 보드는 시간 추적을 바꿀 수 있다 — T-ES-2 의 짝', async () => {
    // ★이 짝이 없으면 **아무에게도 편집을 안 주는** 구현이 T-ES-2 를 통과한다.
    const stub = stubEstimationApi()
    renderPanel(board({ boardType: 'SCRUM' }))

    expect(option(SPENT_LABEL)).toBeEnabled()
    await choose(SPENT_LABEL, stub)

    expect(option(SPENT_LABEL)).toBeChecked()
    expect(option(NONE_LABEL)).not.toBeChecked()
  })

  it('T-ES-2: 칸반 보드는 잠기고 조작해도 요청이 나가지 않는다 (J37)', async () => {
    const stub = stubEstimationApi()
    renderPanel(board({ boardType: 'KANBAN' }))

    expect(option(SPENT_LABEL)).toBeDisabled()
    expect(option(NONE_LABEL)).toBeDisabled()

    await userEvent.click(option(SPENT_LABEL))
    // 비활성만으로 끝내지 않는다 — 실제로 서버에 아무것도 안 갔는지까지 잰다.
    expect(stub.requests).toHaveLength(0)
  })

  it('T-ES-3: 칸반 보드는 잠긴 사유를 화면에 보인다 — 조용히 비활성하지 않는다', () => {
    renderPanel(board({ boardType: 'KANBAN' }))

    expect(screen.getByText(estimation.kanbanLocked)).toBeInTheDocument()
  })

  it('T-ES-4: 칸반이어도 저장돼 있던 값이 선택된 채 보인다 (E6)', () => {
    // ★백엔드는 칸반으로 바꿔도 `time_tracking` 을 지우지 않는다 — 되돌리면 살아난다.
    //   화면이 기본값으로 되돌리면 사용자는 값이 지워졌다고 읽고, 그것이 E6 계약 위반이다.
    renderPanel(board({ boardType: 'KANBAN', timeTracking: 'REMAINING_AND_SPENT' }))

    expect(option(SPENT_LABEL)).toBeChecked()
    expect(option(NONE_LABEL)).not.toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑤⑥⑦ 저장된 값을 초기값으로 읽는다 (부채 177 Task 31 · N1)
// ─────────────────────────────────────────────────────────────────────────────

describe('추정 탭 — 저장된 값을 초기값으로 읽는다 (N1)', () => {
  it('T-ES-5: 보드가 실어 온 값이 선택된 채 뜬다 — T-ES-6 의 짝', () => {
    renderPanel(board({ timeTracking: 'REMAINING_AND_SPENT' }))

    expect(option(SPENT_LABEL)).toBeChecked()
  })

  it('T-ES-6: 값이 없는 보드는 「없음」이다 — 무엇이든 채우는 구현을 잡는다', () => {
    renderPanel(board())

    expect(option(NONE_LABEL)).toBeChecked()
    expect(option(SPENT_LABEL)).not.toBeChecked()
  })

  it('T-ES-7: 언마운트 후 다시 마운트해도 보드가 실어 온 값이 그대로다', () => {
    // 탭 전환의 실제 모습이다 — Radix 는 비활성 탭 본문을 언마운트하므로 로컬 state 는 사라진다.
    const detail = board({ timeTracking: 'REMAINING_AND_SPENT' })
    const first = renderPanel(detail)
    expect(option(SPENT_LABEL)).toBeChecked()

    first.unmount()
    renderPanel(detail)

    expect(option(SPENT_LABEL)).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑧⑨⑩⑪⑫⑭ 저장 요청과 실패
// ─────────────────────────────────────────────────────────────────────────────

describe('추정 탭 — 저장 (Task 9 계약)', () => {
  it('T-ES-8: 요청 바디는 `{ timeTracking }` 한 키다', async () => {
    const stub = stubEstimationApi()
    renderPanel()

    await choose(SPENT_LABEL, stub)

    expect(stub.requests).toEqual([{ timeTracking: 'REMAINING_AND_SPENT' }])
  })

  it('T-ES-9: 저장이 실패하면 선택이 되돌아가고 화면에 오류가 남는다', async () => {
    const stub = stubEstimationApi('NONE', 500)
    renderPanel()

    await choose(SPENT_LABEL, stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // ★토스트만 띄우고 화면 상태를 그대로 두면 사용자는 저장된 줄 안다.
    expect(option(NONE_LABEL)).toBeChecked()

    // 재시도는 화면에 남는 액션이어야 한다 — 다시 누르면 같은 값을 다시 보낸다.
    await userEvent.click(screen.getByRole('button', { name: estimation.saveRetry }))
    await waitFor(() => {
      expect(stub.requests).toEqual([
        { timeTracking: 'REMAINING_AND_SPENT' },
        { timeTracking: 'REMAINING_AND_SPENT' },
      ])
    })
  })

  it('T-ES-10: 409 는 본문이 비어도 잠금 사유를 낸다 — 상태 코드로 가른다', async () => {
    // 칸반 판정을 서버가 뒤늦게 낸 경우다(다른 사람이 보드 종류를 바꿨다).
    // 구현은 상태 코드로만 갈라야 하므로, 본문 구조에 기대는 구현이 여기서 죽는다.
    const stub = stubEstimationApi('NONE', 409)
    renderPanel()

    await choose(SPENT_LABEL, stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(estimation.kanbanLocked)
    })
  })

  it('T-ES-11: 403 은 본문이 비어도 권한 문구를 낸다 — 409 와 뭉뚱그리지 않는다', async () => {
    const stub = stubEstimationApi('NONE', 403)
    renderPanel()

    await choose(SPENT_LABEL, stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(estimation.saveForbidden)
    })
    expect(screen.getByRole('alert')).not.toHaveTextContent(estimation.kanbanLocked)
  })

  it('T-ES-12: CREATE 권한이 없으면 고를 수 없다 — 목록은 보인다 (S7)', () => {
    renderPanel(board(), false)

    expect(option(NONE_LABEL)).toBeDisabled()
    expect(option(SPENT_LABEL)).toBeDisabled()
    expect(option(SPENT_LABEL)).toBeInTheDocument()
  })

  it('T-ES-13: 저장에 성공하면 보드 조회를 무효화한다 — 다음 마운트가 서버 값을 읽는다', async () => {
    const stub = stubEstimationApi()
    const { queryClient } = renderPanel()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await choose(SPENT_LABEL, stub)

    await waitFor(() => {
      expect(invalidate).toHaveBeenCalledWith({ queryKey: boardKeys.detail(BOARD_ID) })
    })
  })

  it('T-ES-14: 409 에는 재시도 버튼을 두지 않는다 — 재시도로 풀리지 않는 실패다', async () => {
    const stub = stubEstimationApi('NONE', 409)
    renderPanel()

    await choose(SPENT_LABEL, stub)

    // ★오류가 **뜬 상태에서** 재시도가 없음을 잰다. 앞 단언이 없으면 「오류를 아예 안 그리는」
    //   구현도 이 테스트를 통과한다(공허 판정).
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: estimation.saveRetry })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 계약
// ─────────────────────────────────────────────────────────────────────────────

describe('추정 탭 — 타입 계약', () => {
  it('T-ES-15: 시간 추적 값은 두 가지뿐이다 — 백엔드 CHECK 미러', () => {
    expectTypeOf<TimeTracking>().toEqualTypeOf<'NONE' | 'REMAINING_AND_SPENT'>()
  })
})
