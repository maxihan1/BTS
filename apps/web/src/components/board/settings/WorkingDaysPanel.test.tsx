// 작업일 탭 동반 테스트 — 미설정↔0개 대조군 · 초기값 · PUT 교체 (부채 177 Task 18 · J38·J39·J40)
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 0개 차단 | 요일을 전부 해제하면 **저장이 막힌다** (T-WD-1) | `[]` 를 보내 400(ideal 선 0 나눗셈) ↔ 화면이 먼저 막는다 (E1) |
// | ② 미설정 대조군 | **미설정 보드는 저장할 수 있다**. 바디가 `null` 이다 (T-WD-2) | 「비면 무조건 막는」 구현(미설정 보드를 아무도 설정 못 함) ↔ null 과 `[]` 를 가른다 (R6) |
// | ③ 초기값 — 요일 | 저장돼 있던 요일이 체크된 채 뜬다 (T-WD-3) | 빈 상태에서 시작 ↔ `board.workingDays` 를 읽음 |
// | ④ 초기값 대조군 | `workingDays` 가 없는 보드는 **아무 요일도 안 켠다** (T-WD-4) | 무엇이든 월~금을 채움(배포 순간 번다운이 바뀐다) ↔ 없으면 없는 대로 (R6) |
// | ⑤ 초기값 — 날짜·타임존 | 비근무일과 타임존도 초기값으로 뜬다 (T-WD-5) | 요일 축만 읽음 ↔ 세 값 전부 |
// | ⑥ 재마운트 | 탭 복귀(언마운트→재마운트) 후에도 유지 (T-WD-6) | 로컬 state 에만 의존 ↔ 매 마운트가 board prop 에서 다시 읽음 |
// | ⑦ PUT 교체 | 요일만 바꿔도 **날짜·타임존이 함께** 실린다 (T-WD-7) | 바뀐 축만 전송(나머지 소실) ↔ 세 값을 통째로 |
// | ⑧ 응답 신뢰 | 정규화된 **응답**이 화면 상태가 된다 (T-WD-8) | 요청 echo 신뢰 ↔ 응답을 읽음 |
// | ⑨ 날짜 추가 | 고른 날짜가 목록과 요청에 실린다 (T-WD-9) | 입력만 받고 안 실음 ↔ 실음 (J39) |
// | ⑩ 날짜 삭제 | 삭제하면 목록과 요청에서 빠진다 (T-WD-10) | 화면만 지움 ↔ 요청에서도 빠짐 (J39) |
// | ⑪ E8 대조군 | 스프린트 **기간 밖** 날짜도 등록된다 (T-WD-11) | 기간 안만 허용(스프린트가 바뀌면 설정 소실) ↔ 보드는 기간을 모른다 (E8) |
// | ⑫ 타임존 유효축 | `Asia/Seoul` 이 **200 으로 저장된다** (T-WD-12) | 라벨·오프셋 전송으로 「전부 400」 ↔ IANA 키 전송 (J40) |
// | ⑬ 400 판정축 | 본문이 비어도 400 이면 값 확인 문구 · **재시도 없음** (T-WD-13) | 봉투 본문 파싱 ↔ 상태 코드 판정 |
// | ⑭ 실패 되돌림 | 500 이면 오류가 화면에 남고 재시도가 같은 값을 다시 보낸다 (T-WD-14) | 토스트 단독 ↔ 화면에 남는 상태 |
// | ⑮ 403 판정축 | 본문이 비어도 403 이면 권한 문구 (T-WD-15) | 400 과 뭉뚱그림 ↔ 상태 코드별 문구 |
// | ⑯ 권한 잠금 | `canConfigure=false` 면 조작이 잠긴다 (T-WD-16) | 항상 편집 가능 ↔ CREATE 로만 열림 (S7) |
// | ⑰ 무효화 | 저장이 정착하면 보드 조회를 무효화 (T-WD-17) | 로컬 state 에만 반영 ↔ 다음 마운트가 서버 값을 읽음 |
// | ⑱ 미설정 가독 | 미설정은 **안내를 보이고 요일 7개를 그리지 않는다** (T-WD-18) | 7개 다 꺼진 화면(=0개로 오해) ↔ 「달력일 전부」라고 말함 (R6) |
// | ⑲ 설정 대조군 | 설정된 보드는 안내 없이 요일을 그린다 (T-WD-19) | 항상 안내만 ↔ 두 상태를 가름 |
// | ⑳ 미설정 출구 | 미설정에서 요일 고르기를 시작할 수 있다 (T-WD-20) | 안내만 두고 조작을 막음 ↔ 막다른 골목이 아니다 |
// | ㉑ 0개 출구 | 0개에서 **미설정으로 되돌려** 저장할 수 있다 (T-WD-21) | 저장만 막고 출구 없음 ↔ 「값을 비우지 말고 미설정으로」(백엔드 KDoc) |
//
// ★★**①과 ② 는 짝으로만 산다 — 이 파일에서 가장 비싼 판정이다.** 「0개면 저장 비활성」만 두면
// **미설정까지 막는** 구현이 통과하고, 그러면 설정을 한 번도 안 한 보드를 **아무도 설정할 수
// 없다.** 반대로 ② 만 두면 「빈 값을 null 로 뭉개 보내는」 구현이 통과하고, 그것은 사용자가
// 「근무일 0개」를 고른 순간 조용히 「미설정」으로 저장해 버린다 — 뜻이 정반대인 두 상태다.
// Task 10 이 백엔드에서 같은 짝(`0개는 400` ↔ `NULL 은 200`)으로 이 축을 지켰다.
//
// ★**③④ 도 짝이다.** ③ 만 두면 「무엇이든 월~금을 채우는」 구현이 통과하고, 그 구현은
// **설정을 만지지 않은 보드의 번다운을 배포 순간 바꾼다**(R6 · 백엔드가 `working_days` 를
// `NOT NULL DEFAULT` 로 두지 않은 이유 그 자체다).
//
// ★**⑦ 은 T16 이 카드 레이아웃에서 밟은 함정의 이 탭 판본이다.** 저장이 **PUT 교체**라,
// 빈 상태에서 시작하거나 바뀐 축만 보내는 구현은 「요일 하나를 켰을 뿐인데 비근무일과 타임존이
// 사라지는」 **데이터 소실**을 낸다. ③⑤ 는 **화면**을 재고 ⑦ 은 **요청 바디**를 잰다.
//
// ★**⑫ 는 「전부 400」인 구현을 잡는다.** 무효 타임존만 재면 아무것도 저장하지 못하는 구현이
// 통과한다 — 스텁이 백엔드와 같은 IANA 멤버십 판정을 하므로 라벨이나 오프셋 표기를 보내면 red 다.
//
// ★**MSW 스텁은 Task 31 의 실제 핸들러(`mocks/board-handlers.ts` `putWorkingDaysHandler`) 형태를
// 따른다** — **PUT**(PATCH 가 아니다) · 요청 `{ standardDays, nonWorkingDates, timezone }` ·
// 응답 `{ data: { … } }` · 0개 400 · 비-IANA 400 · 요일 주 순서 정렬 + 날짜 중복 제거·오름차순.
// 형태가 어긋나면 `boardWorkingDaysSchema.parse` 가 이 파일에서 먼저 죽는다(유닛만 초록인 자리를 줄인다).
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import type { BoardDetail, BoardWorkingDays } from '@/api/boards'
import { boardKeys } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import { WorkingDaysPanel } from './WorkingDaysPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

/** 화면 문구의 정본. 리터럴을 복제하면 문구를 고친 날 테스트만 조용히 낡는다. */
const L = boardLabels.settings.workingDays

/** 요일 키 — 백엔드 `WEEK_ORDER`. */
const MON = L.dayLabels.MON
const TUE = L.dayLabels.TUE

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

/** 세 축이 전부 저장돼 있는 보드 — 초기값 축과 PUT 교체 축이 함께 쓴다. */
function configuredBoard(overrides: Partial<BoardWorkingDays> = {}): BoardDetail {
  return board({
    workingDays: {
      standardDays: ['MON', 'TUE', 'WED', 'THU', 'FRI'],
      nonWorkingDates: ['2026-10-03'],
      timezone: 'Asia/Seoul',
      ...overrides,
    },
  })
}

/** 요청 바디 — 백엔드 `WorkingDaysRequest` 미러. */
interface WorkingDaysBody {
  standardDays?: string[] | null
  nonWorkingDates?: string[]
  timezone?: string | null
}

/** PUT 스텁이 관찰한 것. */
interface WorkingDaysStub {
  /** 받은 요청 바디 전량. 순서가 곧 호출 순서다. */
  requests: WorkingDaysBody[]
  /** 서버 측 저장 상태(정규화 후). */
  stored: BoardWorkingDays
}

const WEEK_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

/**
 * `PUT /api/v1/boards/{boardId}/working-days` 스텁.
 *
 * 백엔드 `WorkingDaysSettingsService` 와 같은 판정을 한다 — **0개는 400**(E1) ·
 * **비-IANA 타임존은 400**(J40) · 요일 주 순서 정렬 · 날짜 중복 제거 후 오름차순.
 * 판정을 흉내 내지 않으면 「`[]` 를 보내는 구현」과 「라벨을 보내는 구현」이 여기서 초록이 되고,
 * 그것이 이 저장소가 이름 붙인 「유닛은 초록인데 e2e 를 쓰자마자 red」의 자리다.
 *
 * @param status 지정하면 그 상태 코드로 실패시킨다. **본문은 비운다** — 본문 구조에 기대는
 *   구현을 여기서 잡는다(탭마다 봉투가 다르다 · 부채 177 Task 29 가 통일 예정).
 */
function stubWorkingDaysApi(status?: number): WorkingDaysStub {
  const stub: WorkingDaysStub = {
    requests: [],
    stored: { standardDays: null, nonWorkingDates: [], timezone: null },
  }
  server.use(
    http.put('/api/v1/boards/:boardId/working-days', async ({ request }) => {
      const body = (await request.json().catch(() => ({}))) as WorkingDaysBody
      stub.requests.push(body)
      if (status !== undefined) return new HttpResponse(null, { status })

      const rawDays = body.standardDays ?? null
      if (rawDays !== null && rawDays.length === 0) {
        return HttpResponse.json({ errorCode: 'AGILE_WORKING_DAYS_INVALID' }, { status: 400 })
      }
      const rawTimezone = body.timezone ?? null
      if (rawTimezone !== null && !Intl.supportedValuesOf('timeZone').includes(rawTimezone)) {
        return HttpResponse.json({ errorCode: 'AGILE_WORKING_DAYS_INVALID' }, { status: 400 })
      }

      stub.stored = {
        standardDays:
          rawDays === null
            ? null
            : [...new Set(rawDays)].sort((a, b) => WEEK_ORDER.indexOf(a) - WEEK_ORDER.indexOf(b)),
        nonWorkingDates: [...new Set(body.nonWorkingDates ?? [])].sort(),
        timezone: rawTimezone,
      }
      return HttpResponse.json({ data: stub.stored })
    }),
  )
  return stub
}

/** 렌더 결과 — 재마운트 축과 무효화 축이 각각 `unmount`·`queryClient` 를 본다. */
interface RenderedPanel {
  queryClient: QueryClient
  unmount: () => void
}

function renderPanel(detail: BoardDetail = configuredBoard(), canConfigure = true): RenderedPanel {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <WorkingDaysPanel board={detail} canConfigure={canConfigure} />
    </QueryClientProvider>,
  )
  return { queryClient, unmount: view.unmount }
}

/** 요일 체크박스. */
function dayBox(name: string): HTMLElement {
  return screen.getByRole('checkbox', { name })
}

/** 저장 버튼. */
function saveButton(): HTMLElement {
  return screen.getByRole('button', { name: L.save })
}

/** 저장하고 요청이 도착할 때까지 기다린다. */
async function save(stub: WorkingDaysStub): Promise<void> {
  const before = stub.requests.length
  await userEvent.click(saveButton())
  await waitFor(() => {
    expect(stub.requests).toHaveLength(before + 1)
  })
}

/** 마지막 요청 바디. 없으면 실패시킨다 — `undefined` 를 조용히 통과시키지 않는다. */
function lastRequest(stub: WorkingDaysStub): WorkingDaysBody {
  const body = stub.requests.at(-1)
  if (body === undefined) throw new Error('요청이 한 건도 없다')
  return body
}

/** 비근무일 날짜를 입력하고 추가한다 (J39 — 날짜 선택 후 「날짜 추가」). */
async function addDate(value: string): Promise<void> {
  fireEvent.change(screen.getByLabelText(L.dateInputLabel), { target: { value } })
  await userEvent.click(screen.getByRole('button', { name: L.addDate }))
}

/** 비근무일 목록에 남아 있는 날짜. */
function listedDates(): string[] {
  const list = screen.queryByRole('list', { name: L.nonWorkingHeading })
  if (list === null) return []
  return within(list)
    .getAllByRole('listitem')
    .map((item) => item.getAttribute('data-date') ?? '')
}

// ─────────────────────────────────────────────────────────────────────────────
// ①② 미설정(null) ↔ 근무일 0개([]) — 뜻이 정반대인 두 상태 (R6 · E1)
// ─────────────────────────────────────────────────────────────────────────────

describe('작업일 탭 — 미설정과 0개는 다른 상태다 (R6 · E1)', () => {
  it('T-WD-1: 요일을 전부 해제하면 저장이 막힌다 — T-WD-2 의 짝 (E1)', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ standardDays: ['MON'] }))

    await userEvent.click(dayBox(MON))

    expect(saveButton()).toBeDisabled()
    // 비활성만으로 끝내지 않는다 — 실제로 서버에 `[]` 가 안 갔는지까지 잰다.
    await userEvent.click(saveButton())
    expect(stub.requests).toHaveLength(0)
  })

  it('T-WD-2: 미설정 보드는 저장할 수 있고 `standardDays: null` 을 보낸다 — T-WD-1 의 짝 (R6)', async () => {
    // ★이 짝이 없으면 「비면 무조건 막는」 구현이 T-WD-1 을 통과하고, 그러면 설정을 한 번도
    //   만지지 않은 보드를 **아무도 설정할 수 없다**.
    const stub = stubWorkingDaysApi()
    renderPanel(board())

    expect(saveButton()).toBeEnabled()
    await save(stub)

    // `[]` 가 아니라 `null` 이다 — 뭉개면 서버가 400 을 내고, 그전에 뜻이 다르다.
    expect(lastRequest(stub).standardDays).toBeNull()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ③④⑤⑥ 저장돼 있던 값을 초기값으로 읽는다 (Task 31 · N1)
// ─────────────────────────────────────────────────────────────────────────────

describe('작업일 탭 — 저장된 값을 초기값으로 읽는다 (N1)', () => {
  it('T-WD-3: 보드가 실어 온 요일이 체크된 채 뜬다 — T-WD-4 의 짝', () => {
    renderPanel(configuredBoard({ standardDays: ['MON', 'TUE'] }))

    expect(dayBox(MON)).toBeChecked()
    expect(dayBox(TUE)).toBeChecked()
    expect(dayBox(L.dayLabels.SAT)).not.toBeChecked()
  })

  it('T-WD-4: 미설정 보드는 어떤 요일도 켜지 않는다 — 월~금을 채우는 구현을 잡는다 (R6)', () => {
    renderPanel(board())

    const checked = screen.queryAllByRole('checkbox').filter((box) => box.getAttribute('aria-checked') === 'true')
    expect(checked).toHaveLength(0)
  })

  it('T-WD-5: 비근무일과 타임존도 초기값으로 뜬다 — 요일 축만 읽는 구현을 잡는다', () => {
    renderPanel(configuredBoard())

    expect(listedDates()).toEqual(['2026-10-03'])
    expect(screen.getByRole('combobox', { name: L.timezoneLabel })).toHaveTextContent('Seoul')
  })

  it('T-WD-6: 언마운트 후 다시 마운트해도 보드가 실어 온 값이 그대로다', () => {
    // 탭 전환의 실제 모습이다 — Radix 는 비활성 탭 본문을 언마운트하므로 로컬 state 는 사라진다.
    const detail = configuredBoard({ standardDays: ['MON'] })
    const first = renderPanel(detail)
    expect(dayBox(MON)).toBeChecked()

    first.unmount()
    renderPanel(detail)

    expect(dayBox(MON)).toBeChecked()
    expect(listedDates()).toEqual(['2026-10-03'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑦⑧⑨⑩⑪⑫ 저장 요청 — PUT 은 교체다 (Task 10 계약)
// ─────────────────────────────────────────────────────────────────────────────

describe('작업일 탭 — 저장 (PUT 교체 · Task 10 계약)', () => {
  it('T-WD-7: 요일만 바꿔도 비근무일과 타임존이 함께 실린다 — PUT 교체 데이터 소실 방지', async () => {
    // ★요청에 없는 비근무일은 서버가 지운다. 바뀐 축만 보내는 구현은 사용자가 요일 하나를
    //   켰을 뿐인데 비근무일과 타임존을 날린다(T16 이 카드 레이아웃에서 밟은 함정의 판본).
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ standardDays: ['MON'] }))

    await userEvent.click(dayBox(TUE))
    await save(stub)

    expect(lastRequest(stub)).toEqual({
      standardDays: ['MON', 'TUE'],
      nonWorkingDates: ['2026-10-03'],
      timezone: 'Asia/Seoul',
    })
  })

  it('T-WD-8: 정규화된 응답이 화면 상태가 된다 — 요청 echo 를 믿지 않는다', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ nonWorkingDates: [] }))

    await addDate('2026-12-25')
    await addDate('2026-01-01')
    // 보낸 순서는 내림차순이 아니지만 서버는 오름차순으로 정규화해 돌려준다.
    await save(stub)

    await waitFor(() => {
      expect(listedDates()).toEqual(['2026-01-01', '2026-12-25'])
    })
  })

  it('T-WD-9: 고른 날짜가 목록과 요청에 실린다 (J39)', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ nonWorkingDates: [] }))

    await addDate('2026-05-05')

    expect(listedDates()).toEqual(['2026-05-05'])
    await save(stub)
    expect(lastRequest(stub).nonWorkingDates).toEqual(['2026-05-05'])
  })

  it('T-WD-10: 삭제한 날짜는 목록과 요청에서 빠진다 (J39)', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ nonWorkingDates: ['2026-10-03', '2026-12-25'] }))

    await userEvent.click(screen.getByRole('button', { name: L.removeDate('2026-10-03') }))

    expect(listedDates()).toEqual(['2026-12-25'])
    await save(stub)
    expect(lastRequest(stub).nonWorkingDates).toEqual(['2026-12-25'])
  })

  it('T-WD-11: 스프린트 기간 밖 날짜도 등록된다 (E8)', async () => {
    // ★보드는 스프린트 기간을 모른다. 화면이 기간으로 걸러 버리면 스프린트가 바뀔 때마다
    //   설정이 소실된다 — 백엔드도 같은 이유로 거르지 않는다(`WorkingDaysSettingsService` KDoc).
    const stub = stubWorkingDaysApi()
    renderPanel(
      board({
        activeSprint: {
          sprintId: 'b1b2c3d4-e5f6-4890-abcd-ef1234567890',
          name: 'Sprint 3',
          startDate: '2026-09-01',
          endDate: '2026-09-14',
        },
        workingDays: { standardDays: ['MON'], nonWorkingDates: [], timezone: null },
      }),
    )

    await addDate('2030-01-01')

    expect(listedDates()).toEqual(['2030-01-01'])
    await save(stub)
    expect(lastRequest(stub).nonWorkingDates).toEqual(['2030-01-01'])
  })

  it('T-WD-12: 유효한 IANA 타임존은 200 으로 저장된다 — 「전부 400」인 구현을 잡는다 (J40)', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ timezone: null }))

    // J40 — *"select a Region, then Timezone from the dropdowns"*
    await userEvent.click(screen.getByRole('combobox', { name: L.regionLabel }))
    await userEvent.click(screen.getByRole('option', { name: 'Asia' }))
    await userEvent.click(screen.getByRole('combobox', { name: L.timezoneLabel }))
    await userEvent.click(screen.getByRole('option', { name: 'Seoul' }))

    await save(stub)

    // 라벨(`서울`)이나 오프셋(`UTC+09:00`)을 보내면 스텁이 400 을 낸다.
    expect(lastRequest(stub).timezone).toBe('Asia/Seoul')
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(L.saved)
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑬⑭⑮⑯⑰ 실패와 잠금
// ─────────────────────────────────────────────────────────────────────────────

describe('작업일 탭 — 실패 처리 (상태 코드로만 가른다)', () => {
  it('T-WD-13: 400 은 본문이 비어도 값 확인 문구를 내고 재시도를 주지 않는다', async () => {
    // 같은 값을 다시 보내도 같은 400 이다 — 눌러도 안 되는 버튼을 주지 않는다.
    const stub = stubWorkingDaysApi(400)
    renderPanel()

    await save(stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(L.saveInvalid)
    })
    expect(screen.queryByRole('button', { name: L.saveRetry })).not.toBeInTheDocument()
  })

  it('T-WD-14: 500 이면 오류가 화면에 남고 재시도가 같은 값을 다시 보낸다', async () => {
    const stub = stubWorkingDaysApi(500)
    renderPanel(configuredBoard({ standardDays: ['MON'] }))

    await save(stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(L.saveFailed)
    })

    await userEvent.click(screen.getByRole('button', { name: L.saveRetry }))
    await waitFor(() => {
      expect(stub.requests).toHaveLength(2)
    })
    expect(stub.requests[1]).toEqual(stub.requests[0])
  })

  it('T-WD-15: 403 은 본문이 비어도 권한 문구를 낸다 — 400 과 뭉뚱그리지 않는다', async () => {
    const stub = stubWorkingDaysApi(403)
    renderPanel()

    await save(stub)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(L.saveForbidden)
    })
  })

  it('T-WD-16: `canConfigure=false` 면 조작이 잠긴다 (S7)', async () => {
    const stub = stubWorkingDaysApi()
    renderPanel(configuredBoard({ standardDays: ['MON'] }), false)

    expect(dayBox(MON)).toBeDisabled()
    expect(saveButton()).toBeDisabled()
    expect(screen.getByRole('button', { name: L.addDate })).toBeDisabled()

    await userEvent.click(saveButton())
    expect(stub.requests).toHaveLength(0)
  })

  it('T-WD-17: 저장이 정착하면 보드 조회를 무효화한다', async () => {
    // 이 무효화가 없으면 탭을 옮겼다 돌아왔을 때 **저장 전 캐시**가 초기값이 된다.
    const stub = stubWorkingDaysApi()
    const { queryClient } = renderPanel(configuredBoard({ standardDays: ['MON'] }))
    queryClient.setQueryData(boardKeys.detail(BOARD_ID), { stale: true })

    await save(stub)

    await waitFor(() => {
      const state = queryClient.getQueryState(boardKeys.detail(BOARD_ID))
      expect(state?.isInvalidated).toBe(true)
    })
  })
})
