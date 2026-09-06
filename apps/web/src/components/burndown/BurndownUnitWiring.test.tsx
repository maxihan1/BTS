// 번다운 세로축 **단위** 배선 테스트 — 응답 스키마 왕복 + 목 파생 + 차트 포맷 분기 (부채 177 Task 38)
//
// ## 무엇이 결함이었나
//
// 백엔드가 `time_tracking = 'NONE'` 인 보드의 번다운을 **이슈 개수**로 계산하고 응답에
// `unit: 'SECONDS' | 'ISSUE_COUNT'` 을 싣기 시작했다(task-35 · `BurndownUnit.kt`).
// **프론트는 그 필드를 몰랐다.** 그래서 셋이 동시에 성립했다.
//
//   ① `burndownResponseSchema` 가 `z.object` 라 unknown key 를 **조용히 버린다**(zod strip).
//      파싱은 안 깨지고 **값만 사라진다** — 이 PR 이 T32 에서 이미 한 번 겪은 양식이다.
//   ② 차트 Y축이 `formatSeconds` **고정**이라 개수 축 보드에서 「12개」가 **「12초」**로 그려진다.
//   ③ MSW 목이 `unit` 을 아예 안 실어 **목이 백엔드에 대해 거짓말**을 한다.
//
// ## 이 파일이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 축 | 무엇을 재나 | 느슨한 구현 ↔ 올바른 구현 |
// |---|---|---|
// | ① 스키마 생존 | 응답 JSON 의 `unit` 이 **파싱 뒤에도** 있다 (T-BU-1·2) | zod strip 이 조용히 버림 ↔ 스키마가 키를 앎 |
// | ② 부재 판정 | `unit` 이 **없는** 응답은 **파싱이 실패한다** (T-BU-3) | `?? 'SECONDS'` 로 뭉갬 ↔ 모르는 축을 아는 척하지 않음 |
// | ③ 값 판정 | 백엔드 enum 밖 값은 거절한다 (T-BU-4) | `z.string()` ↔ `z.enum` |
// | ④ 초 축 포맷 | `SECONDS` 는 시간 표기다 (T-BU-5) | — |
// | ⑤ 개수 축 포맷 | `ISSUE_COUNT` 는 개수 표기다 (T-BU-6) | — |
// | ⑥ **분기 짝** | 같은 숫자 12 가 두 축에서 **다르게** 그려지고 축 제목도 다르다 (T-BU-7) | 항상 초 / 항상 개수 ↔ 단위가 가른다 |
// | ⑦ 정수 | 개수 축에 소수점이 없다 (T-BU-8) | `2.5개` ↔ 정수 tick |
// | ⑧ 축↔툴팁 일치 | 툴팁도 같은 포맷터를 쓴다 (T-BU-9) | 축은 개수·툴팁은 시간 ↔ 한 벌 |
// | ⑨ 목 파생(초) | `REMAINING_AND_SPENT` 저장 → 목이 `SECONDS` 를 낸다 (T-BU-10) | 하드코딩 ↔ `timeTracking` 파생 |
// | ⑩ 목 파생(개수) | `NONE` 저장 → 목이 `ISSUE_COUNT` 를 낸다 (T-BU-11) | 하드코딩 ↔ `timeTracking` 파생 |
//
// ★**⑥ 이 이 파일의 핵심이다.** ④ 나 ⑤ 를 **하나만** 두면 「항상 개수로 그리는」 구현이
//   통과한다. 두 축이 **같은 숫자**를 서로 다르게 그린다는 것을 짝으로 재야 분기가 강제된다.
// ★**⑨⑩ 도 짝이다.** 하나만 두면 `unit` 을 상수로 박은 목이 통과한다.
// ★**①은 타입으로 못 잰다.** zod strip 은 값을 조용히 버리므로 「스키마에 필드가 있다」가 아니라
//   **실제 응답 JSON 을 진짜 fetch 함수로 파싱해 값이 살아남는지**를 재야 한다
//   (`BoardCardWiring.test.tsx` T-W-1 이 본이다). 그래서 이 파일에는 **손으로 쓴
//   `BurndownResponse` 리터럴이 하나도 없다** — 차트에 넣는 값까지 전부 파싱을 거쳐 온다.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fetchSprintBurndown } from '@/api/burndown'
import { updateTimeTracking } from '@/api/board-settings'
import { burndownLabels } from '@/i18n/burndown-labels'
import { boardHandlers } from '@/mocks/board-handlers'
import { burndownHandlers, seedBurndown, resetBurndownStore, DEFAULT_BURNDOWN, DEFAULT_SPRINT_ID } from '@/mocks/burndown-handlers'
import { DEFAULT_BOARD, resetBoardStore, seedBoard } from '@/mocks/board-fixtures'
import { DEFAULT_BACKLOG, resetBacklogStore, seedBacklog } from '@/mocks/backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// recharts 하네스 — **prop 을 캡처한다**
//
// ★기존 `BurndownChart.test.tsx` 의 스텁은 `YAxis: () => <div/>` 라 **prop 을 통째로 삼킨다.**
//   그 위에서는 `tickFormatter` 를 어떻게 바꿔도 아무 테스트도 안 깨진다
//   (memory `mock-swallowed-prop-is-invisible-to-unit-tests` — 「유닛 전부 초록인데 실물은 red」의
//   서명이다). 그래서 여기서는 포맷터를 **꺼내 직접 호출**해 문자열을 잰다.
// ─────────────────────────────────────────────────────────────────────────────

const capture = vi.hoisted(() => ({
  /** YAxis 가 받은 tick 포맷터. */
  yAxisTickFormatter: undefined as ((value: unknown) => string) | undefined,
  /** YAxis 가 받은 축 제목(`label.value`). */
  yAxisLabelValue: undefined as unknown,
  /** YAxis 가 받은 `allowDecimals`. 개수 축에서 소수점 tick 생성 자체를 막는 손잡이다. */
  yAxisAllowDecimals: undefined as unknown,
  /** Tooltip 이 받은 값 포맷터. */
  tooltipFormatter: undefined as ((value: unknown) => string) | undefined,
  reset(): void {
    this.yAxisTickFormatter = undefined
    this.yAxisLabelValue = undefined
    this.yAxisAllowDecimals = undefined
    this.tooltipFormatter = undefined
  },
}))

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  Line: () => <div />,
  XAxis: () => <div />,
  YAxis: (props: {
    tickFormatter?: (value: unknown) => string
    label?: { value?: unknown }
    allowDecimals?: unknown
  }) => {
    capture.yAxisTickFormatter = props.tickFormatter
    capture.yAxisLabelValue = props.label?.value
    capture.yAxisAllowDecimals = props.allowDecimals
    return <div data-testid="yaxis" />
  },
  Tooltip: (props: { formatter?: (value: unknown) => string }) => {
    capture.tooltipFormatter = props.formatter
    return <div data-testid="tooltip" />
  },
  Legend: () => <div />,
  CartesianGrid: () => <div />,
}))

const { BurndownChart } = await import('./BurndownChart')

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — **서버가 보내는 모양 그대로의 JSON** 이다(파싱 전이라 타입을 붙이지 않는다)
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_ID = '11111111-1111-4111-8111-111111111111'

/**
 * 값을 전부 **12** 로 맞춘 응답 JSON.
 *
 * ★12 를 고른 이유. 초로 읽으면 `formatSeconds(12)` = `0m`, 개수로 읽으면 `12` 다 —
 * **같은 숫자가 두 축에서 확실히 다른 글자**가 되어 ⑥ 축이 성립한다.
 */
function rawBurndown(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sprintId: SPRINT_ID,
    projectKey: 'BTS',
    status: 'ACTIVE',
    startDate: '2026-07-01',
    endDate: '2026-07-02',
    totalScopeSeconds: 12,
    unit: 'ISSUE_COUNT',
    points: [
      { date: '2026-07-01', remainingSeconds: 12, idealSeconds: 12, completedSeconds: 0, scopeSeconds: 12 },
      { date: '2026-07-02', remainingSeconds: null, idealSeconds: 0, completedSeconds: null, scopeSeconds: 12 },
    ],
    ...overrides,
  }
}

/** 한 번의 GET 에 대해 주어진 JSON 을 그대로 내는 스텁. */
function stubBurndown(body: Record<string, unknown>): void {
  server.use(
    http.get('/api/v1/sprints/:id/burndown', () => HttpResponse.json({ data: body })),
  )
}

/** 스텁 응답을 **진짜 fetch 함수로** 파싱해 차트에 넣는다. 손으로 쓴 리터럴을 쓰지 않는 이유는 파일 머리 ★. */
async function renderChartWithUnit(unit: string): Promise<void> {
  stubBurndown(rawBurndown({ unit }))
  const response = await fetchSprintBurndown(SPRINT_ID)
  render(<BurndownChart response={response} view="burndown" />)
}

/** 캡처한 Y축 tick 포맷터. 없으면 던진다 — 조용히 통과하는 것보다 red 가 낫다. */
function yAxisTick(value: number): string {
  const formatter = capture.yAxisTickFormatter
  if (formatter === undefined) throw new Error('YAxis 가 tickFormatter 를 받지 않았다')
  return formatter(value)
}

beforeEach(() => {
  capture.reset()
})

afterEach(() => {
  resetBurndownStore()
  resetBoardStore()
  resetBacklogStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// ①②③ 스키마 — zod strip 이 값을 삼키는지 **값으로** 잰다
// ─────────────────────────────────────────────────────────────────────────────

describe('번다운 응답 스키마 — unit 이 파싱을 살아남는다', () => {
  it('T-BU-1: ISSUE_COUNT 가 파싱 뒤에도 남는다', async () => {
    // ★타입 단언이 아니라 **값**을 잰다. `z.object` 는 모르는 키를 조용히 버리므로
    //   스키마 선언만 보면 결함이 안 보인다.
    stubBurndown(rawBurndown({ unit: 'ISSUE_COUNT' }))
    const parsed = await fetchSprintBurndown(SPRINT_ID)
    expect(parsed.unit).toBe('ISSUE_COUNT')
  })

  it('T-BU-2: SECONDS 도 그대로 남는다 (한 값만 재면 상수 반환이 통과한다)', async () => {
    stubBurndown(rawBurndown({ unit: 'SECONDS' }))
    const parsed = await fetchSprintBurndown(SPRINT_ID)
    expect(parsed.unit).toBe('SECONDS')
  })

  it('T-BU-3: unit 이 **없는** 응답은 파싱이 실패한다 — 기본값으로 뭉개지 않는다', async () => {
    // ★백엔드 `BurndownResponse.unit` 은 **non-null** 이다(실측 · agile-planning
    //   `web/dto/BurndownResponse.kt`). 즉 「없음」은 정상 응답이 아니라 계약 위반이다.
    //   `.optional()` + `?? 'SECONDS'` 로 받으면 **모르는 축을 아는 척**하게 되고, 그것이
    //   이 task 가 닫는 결함(개수를 시간으로 그린다)과 정확히 같은 거짓말이다.
    const body = rawBurndown()
    delete body['unit']
    stubBurndown(body)
    await expect(fetchSprintBurndown(SPRINT_ID)).rejects.toThrow()
  })

  it('T-BU-4: 백엔드 enum 밖 값은 거절한다', async () => {
    // `z.string()` 으로 받으면 서버가 오타를 내도 화면이 그것을 축 이름으로 믿는다.
    stubBurndown(rawBurndown({ unit: 'HOURS' }))
    await expect(fetchSprintBurndown(SPRINT_ID)).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ④⑤⑥⑦⑧ 차트 — 단위가 포맷을 가른다
// ─────────────────────────────────────────────────────────────────────────────

describe('번다운 차트 — 단위가 축 포맷과 제목을 가른다', () => {
  it('T-BU-5: SECONDS 축은 시간 표기이고 축 제목이 시간이다', async () => {
    await renderChartWithUnit('SECONDS')
    expect(yAxisTick(9000)).toBe('2h 30m')
    expect(capture.yAxisLabelValue).toBe(burndownLabels.chart.yAxisTitle.SECONDS)
  })

  it('T-BU-6: ISSUE_COUNT 축은 개수 표기이고 축 제목이 개수다', async () => {
    await renderChartWithUnit('ISSUE_COUNT')
    expect(yAxisTick(12)).toBe('12')
    expect(capture.yAxisLabelValue).toBe(burndownLabels.chart.yAxisTitle.ISSUE_COUNT)
  })

  it('T-BU-7: ★같은 숫자 12 가 두 축에서 다르게 그려진다 (분기 짝)', async () => {
    // 이 짝이 없으면 「항상 개수로 그리는」 구현도 「항상 초로 그리는」 구현도 살아남는다.
    await renderChartWithUnit('SECONDS')
    const secondsTick = yAxisTick(12)
    const secondsTitle = capture.yAxisLabelValue

    capture.reset()
    await renderChartWithUnit('ISSUE_COUNT')
    const countTick = yAxisTick(12)
    const countTitle = capture.yAxisLabelValue

    expect(secondsTick).toBe('0m')
    expect(countTick).toBe('12')
    expect(secondsTick).not.toBe(countTick)
    expect(secondsTitle).not.toBe(countTitle)
  })

  it('T-BU-8: 개수 축은 소수점을 그리지 않는다', async () => {
    // recharts 는 도메인이 좁으면 `2.5` 같은 tick 을 만든다. 「2.5개」는 존재하지 않는 값이다.
    await renderChartWithUnit('ISSUE_COUNT')
    expect(yAxisTick(2.5)).not.toContain('.')
    // 포맷터만으로는 tick **생성**을 막지 못한다 — 같은 값이 두 tick 으로 접힐 수 있다.
    expect(capture.yAxisAllowDecimals).toBe(false)
  })

  it('T-BU-9: 툴팁이 축과 **같은** 포맷터를 쓴다', async () => {
    // 둘이 갈리면 축은 「12」인데 툴팁은 「0m」이라고 말한다 — 한 화면이 두 단위를 주장한다.
    await renderChartWithUnit('ISSUE_COUNT')
    // ★먼저 **존재**를 못 박는다. 둘 다 undefined 면 `toBe` 가 공짜로 통과한다.
    expect(capture.yAxisTickFormatter).toBeTypeOf('function')
    expect(capture.tooltipFormatter).toBe(capture.yAxisTickFormatter)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑨⑩ 목 — `timeTracking` 에서 **파생**한다 (하드코딩 금지)
// ─────────────────────────────────────────────────────────────────────────────

describe('MSW 목 — 번다운 unit 을 보드 timeTracking 에서 파생한다', () => {
  beforeEach(() => {
    resetBoardStore()
    resetBacklogStore()
    resetBurndownStore()
    // ★**스크럼**으로 심는다. 칸반 보드의 추정 PATCH 는 409 다(J37 · E5) — 저장 자체가 안 되면
    //   파생을 잴 수 없다. boardId 는 그대로 둔다(스프린트가 그 보드에 귀속돼 있다).
    seedBoard({ ...DEFAULT_BOARD, boardType: 'SCRUM' })
    seedBacklog(DEFAULT_BACKLOG)
    seedBurndown(DEFAULT_BURNDOWN)
    server.use(...boardHandlers, ...burndownHandlers)
  })

  it('T-BU-10: REMAINING_AND_SPENT 를 저장하면 번다운이 SECONDS 를 낸다', async () => {
    // 저장은 **실제 PATCH 창구**로 한다. store 를 직접 심으면 저장 경로와 조회 경로가
    // 서로를 검사하지 않는 두 번째 진실이 된다(T33 이 같은 파일에서 세운 규율).
    await updateTimeTracking(DEFAULT_BOARD.boardId, 'REMAINING_AND_SPENT')
    const parsed = await fetchSprintBurndown(DEFAULT_SPRINT_ID)
    expect(parsed.unit).toBe('SECONDS')
  })

  it('T-BU-11: NONE 을 저장하면 번다운이 ISSUE_COUNT 를 낸다 (파생 짝)', async () => {
    // 하나만 두면 `unit` 을 상수로 박은 목이 통과한다.
    await updateTimeTracking(DEFAULT_BOARD.boardId, 'NONE')
    const parsed = await fetchSprintBurndown(DEFAULT_SPRINT_ID)
    expect(parsed.unit).toBe('ISSUE_COUNT')
  })
})
