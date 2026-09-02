// 스프린트 시작 다이얼로그 테스트 — PATCH→start 2단계 순서·실패 4갈래·E8·E10 (FR-UX-13 F15 FR-3·FR-4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import type { JSX } from 'react'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'
import { backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta } from '@/api/backlog'
import { StartSprintDialog } from './StartSprintDialog'

// sonner toast mock — E10 은 다이얼로그를 닫으므로 문구가 토스트로만 남는다 (BacklogBoard.test.tsx 선례)
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    warning: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

/**
 * 보드 축이 **없는** 옛 백로그 키. 미끼를 심는 자리다.
 *
 * ★ 팩토리(`backlogKeys.detail`)로 만들지 않는 것이 요점이다 — board 축이 붙기 전에는 두 키가
 *   같은 배열로 붕괴해 **같은 (틀린) 키로 심고 같은 키로 읽는** 가짜 그린이 된다
 *   (`two-lists-never-check-each-other`). 리터럴이라야 「보드 스코프 키에서 읽었는가」를 잰다.
 */
const UNSCOPED_BACKLOG_KEY = ['backlog', PROJECT_KEY] as const

/** 기간·목표가 **비어 있는** PLANNED 스프린트 — 「빈 값 → 입력」 변경분을 만들기 위한 기준 */
const EMPTY_SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  boardId: '10000000-0000-4000-8000-000000000001',
  name: 'Sprint 1',
  goal: null,
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 3,
}

/** 기간·목표가 이미 채워진 스프린트 — 「값 무변경 → PATCH 미호출」을 재기 위한 기준 */
const FILLED_SPRINT: SprintMeta = {
  ...EMPTY_SPRINT,
  goal: '기존 목표',
  startDate: '2026-08-01',
  endDate: '2026-08-14',
}

const L = backlogLabels.startDialog

// ─────────────────────────────────────────────────────────────────────────────
// MSW 시나리오 — 요청 순서·횟수를 직접 기록한다
// ─────────────────────────────────────────────────────────────────────────────

/** 관측된 요청 순서. `'PATCH'` · `'START'` 만 들어간다 */
let calls: string[] = []

/** `PATCH` 가 실제로 보낸 body 전수 — 「바뀐 필드만 담았는가」를 잰다 */
let patchBodies: Record<string, unknown>[] = []

/** 목 서버가 들고 있는 스프린트 상태. `PATCH` 가 실제로 반영해야 T-DL-4 가 공허해지지 않는다 */
let storedSprint: SprintMeta = { ...EMPTY_SPRINT }

/** 한 요청의 결과 종류 */
type Outcome = 'ok' | 'conflict' | 'error'

/** {@link installScenario} 옵션 */
interface ScenarioOptions {
  /** `PATCH` 결과를 **호출 순서대로** 지정한다. 모자라면 마지막 값을 반복한다. 기본 `['ok']` */
  readonly patch?: readonly Outcome[]
  /** `start` 결과를 **호출 순서대로** 지정한다. 모자라면 마지막 값을 반복한다 */
  readonly start?: readonly Outcome[]
}

/** 백엔드 `JsonNullable` 3-state 를 그대로 흉내 낸다 — 키가 있을 때만 반영한다 */
const PATCHABLE_FIELDS = ['name', 'goal', 'startDate', 'endDate'] as const

/**
 * 3-state partial 을 반영한 새 SprintMeta 를 만든다 (`version` +1).
 *
 * 목이 **실제로 반영해야** 「재시도 시 PATCH 가 늘지 않는다」(T-DL-4)가 의미를 갖는다.
 * 응답이 요청을 무시하면 컴포넌트가 기준값을 갱신해도 갱신 자체가 무의미해진다.
 */
function applyPatch(base: SprintMeta, body: Record<string, unknown>): SprintMeta {
  const next: SprintMeta = { ...base, version: base.version + 1 }
  for (const field of PATCHABLE_FIELDS) {
    if (!Object.prototype.hasOwnProperty.call(body, field)) continue
    const value = body[field]
    if (field === 'name') {
      if (typeof value === 'string') next.name = value
      continue
    }
    next[field] = typeof value === 'string' ? value : null
  }
  return next
}

/** 500 ProblemDetail. 반환 타입은 msw 의 `StrictResponse` 라 추론에 맡긴다 */
function serverError() {
  return HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
}

/** 409 — errorCode 는 목 핸들러(`backlog-handlers.ts`)와 같은 값을 쓴다 */
function conflict(errorCode: string) {
  return HttpResponse.json({ errorCode, message: '충돌', status: 409 }, { status: 409 })
}

/** 시나리오 핸들러를 덮어쓴다. 전역 MSW 가 이미 떠 있으므로 `server.use` 로 얹는다 */
function installScenario(options: ScenarioOptions = {}): void {
  const patchPlan: readonly Outcome[] = options.patch ?? ['ok']
  const startPlan: readonly Outcome[] = options.start ?? ['ok']

  server.use(
    http.patch('/api/v1/sprints/:id', async ({ request }) => {
      const attempt = calls.filter((call) => call === 'PATCH').length
      calls.push('PATCH')
      patchBodies.push((await request.json()) as Record<string, unknown>)
      const outcome = patchPlan[Math.min(attempt, patchPlan.length - 1)] ?? 'ok'
      if (outcome === 'error') return serverError()
      if (outcome === 'conflict') return conflict('SPRINT_VERSION_CONFLICT')
      storedSprint = applyPatch(storedSprint, patchBodies[patchBodies.length - 1] ?? {})
      return HttpResponse.json({ data: storedSprint })
    }),
    http.post('/api/v1/sprints/:id/start', () => {
      const attempt = calls.filter((call) => call === 'START').length
      calls.push('START')
      const outcome = startPlan[Math.min(attempt, startPlan.length - 1)] ?? 'ok'
      if (outcome === 'error') return serverError()
      if (outcome === 'conflict') return conflict('SPRINT_INVALID_TRANSITION')
      storedSprint = { ...storedSprint, status: 'ACTIVE', version: storedSprint.version + 1 }
      return HttpResponse.json({ data: storedSprint })
    }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 하네스 — 열림 상태를 실제로 쥔다 (닫힘을 「스파이 호출」이 아니라 **DOM 부재**로 잰다)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link renderDialog} 반환값 */
interface RenderResult {
  /** `onOpenChange` 스파이 */
  readonly onOpenChange: ReturnType<typeof vi.fn>
  /** `invalidateQueries` 스파이 — 닫을 때 백로그를 새로 받는지 잰다 */
  readonly invalidateSpy: ReturnType<typeof vi.spyOn>
}

/**
 * 백로그 캐시에 심을 뷰.
 *
 * ★ `replaceBaselineFromCache` 는 `getQueryData` 가 값을 돌려줘야 비로소 기준값 교체까지
 *   실행된다. 심지 않으면 조기 반환이라 **프로덕션에서만 도달하는 분기**가 되어,
 *   테스트가 아무리 초록이어도 그 경로를 한 번도 지나지 않는다.
 */
function backlogViewOf(sprint: SprintMeta): BacklogView {
  return { backlog: [], sprints: [{ sprint, issues: [] }], truncated: false }
}

/**
 * @param sprint 다이얼로그에 넘길 대상 스프린트 (초기값의 출처)
 * @param cachedSprint 백로그 캐시가 들고 있는 **서버 최신** 스프린트. 주면 보드 스코프 키에 심는다
 * @param unscopedDecoy 보드 축 없는 옛 키({@link UNSCOPED_BACKLOG_KEY})에 심을 미끼.
 *   주면 「어느 키에서 읽었는가」가 결과로 갈린다
 */
function renderDialog(
  sprint: SprintMeta = EMPTY_SPRINT,
  cachedSprint?: SprintMeta,
  unscopedDecoy?: SprintMeta,
): RenderResult {
  const onOpenChange = vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  if (cachedSprint !== undefined) {
    queryClient.setQueryData(backlogKeys.detail(PROJECT_KEY, BOARD_ID), backlogViewOf(cachedSprint))
  }
  if (unscopedDecoy !== undefined) {
    queryClient.setQueryData(UNSCOPED_BACKLOG_KEY, backlogViewOf(unscopedDecoy))
  }
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

  function Harness(): JSX.Element {
    const [open, setOpen] = useState(true)
    return (
      <StartSprintDialog
        open={open}
        onOpenChange={(next) => {
          onOpenChange(next)
          setOpen(next)
        }}
        sprint={sprint}
        projectKey={PROJECT_KEY}
        boardId={BOARD_ID}
      />
    )
  }

  render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  )

  return { onOpenChange, invalidateSpy }
}

/** 날짜 input 은 userEvent 로 한 글자씩 치면 중간값이 무효라 jsdom 이 삼킨다 — change 로 넣는다 */
function setField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

/** 제출 버튼 */
function submitButton(): HTMLElement {
  return screen.getByRole('button', { name: backlogLabels.startSprint })
}

/**
 * 시작 다이얼로그.
 *
 * ★ **이름 없는 `getByRole('dialog')` 를 쓰지 않는다.** 같은 화면에 다른 스프린트
 *   다이얼로그(편집·완료)가 함께 뜨는 날 다중 매치로 즉사한다 — 그날 red 가 되는 것은
 *   구현이 아니라 **이 조회**라서 원인을 엉뚱한 곳에서 찾게 된다.
 *   `e2e/backlog.spec.ts` 가 잡는 이름과 **같은 값**을 쓴다 (즉사 계약 §2).
 */
function startDialog(): HTMLElement {
  return screen.getByRole('dialog', { name: backlogLabels.startSprint })
}

/** 시작 다이얼로그 부재 조회. 이름을 붙이는 이유는 {@link startDialog} 와 같다 */
function queryStartDialog(): HTMLElement | null {
  return screen.queryByRole('dialog', { name: backlogLabels.startSprint })
}

/** `PATCH` 호출 횟수 */
function patchCount(): number {
  return calls.filter((call) => call === 'PATCH').length
}

beforeEach(() => {
  calls = []
  patchBodies = []
  storedSprint = { ...EMPTY_SPRINT }
  vi.mocked(toast.error).mockClear()
})

// ─────────────────────────────────────────────────────────────────────────────
// 기본 렌더 (FR-3 · FR-10)
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — 기본 렌더', () => {
  it('필드 3종이 백로그 응답의 sprintMeta 로 채워진다 (별도 조회 0)', () => {
    installScenario()
    renderDialog(FILLED_SPRINT)

    expect(screen.getByLabelText(L.startDateLabel)).toHaveValue('2026-08-01')
    expect(screen.getByLabelText(L.endDateLabel)).toHaveValue('2026-08-14')
    expect(screen.getByLabelText(L.goalLabel)).toHaveValue('기존 목표')
    // 조회 요청이 전혀 나가지 않았다 — 초기값의 출처가 props 임을 잰다
    expect(calls).toEqual([])
  })

  it('다이얼로그 이름과 제출 버튼 이름이 모두 「스프린트 시작」이고 보이는 버튼은 1개다', () => {
    installScenario()
    renderDialog()

    expect(screen.getByRole('dialog', { name: backlogLabels.startSprint })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: backlogLabels.startSprint })).toHaveLength(1)
    expect(
      screen.getByRole('button', { name: issueCreateStrings.cancelButton }),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-DL-2 — 2단계 요청
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — T-DL-2 2단계 요청', () => {
  it('값을 바꾸면 PATCH → start 순서로 보내고 body 에 바뀐 필드 + version 만 담는다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog()

    setField(L.startDateLabel, '2026-08-10')
    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())

    await waitFor(() => expect(calls).toEqual(['PATCH', 'START']))
    // 종료일은 안 건드렸으므로 **키 자체가 없어야** 한다 (3-state partial)
    expect(patchBodies[0]).toEqual({ version: 3, startDate: '2026-08-10', goal: '목표 A' })
  })

  it('값을 안 바꾸면 PATCH 를 보내지 않는다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog(FILLED_SPRINT)

    await user.click(submitButton())

    await waitFor(() => expect(calls).toEqual(['START']))
    expect(patchCount()).toBe(0)
  })

  it('값을 지우면 null 을 보내고, 원래 없던 값은 변경분이 아니다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog(FILLED_SPRINT)

    setField(L.goalLabel, '')
    await user.click(submitButton())

    await waitFor(() => expect(calls).toEqual(['PATCH', 'START']))
    expect(patchBodies[0]).toEqual({ version: 3, goal: null })
  })

  it('두 요청이 모두 성공하면 다이얼로그를 닫는다', async () => {
    const user = userEvent.setup()
    installScenario()
    const { onOpenChange } = renderDialog(FILLED_SPRINT)

    await user.click(submitButton())

    await waitFor(() => expect(queryStartDialog()).not.toBeInTheDocument())
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-DL-3 / T-DL-4 — 중간 실패와 재시도
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — T-DL-3 중간 실패', () => {
  it('PATCH 200 + start 500 이면 다이얼로그를 유지하고 「기간·목표는 저장했지만」 을 띄운다', async () => {
    const user = userEvent.setup()
    installScenario({ start: ['error'] })
    const { onOpenChange } = renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())

    expect(await screen.findByText(L.startFailed)).toBeInTheDocument()
    expect(startDialog()).toBeInTheDocument()
    // 「전부 실패」 문구가 화면에 없다 — 하나로 뭉뚱그리면 거짓말이 된다
    expect(screen.queryByText(L.patchFailed)).not.toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })
})

describe('StartSprintDialog — T-DL-4 재시도', () => {
  it('재시도해도 PATCH 호출 수가 늘지 않는다 (기준값이 PATCH 응답으로 갱신됐다)', async () => {
    const user = userEvent.setup()
    installScenario({ start: ['error', 'ok'] })
    renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())
    await screen.findByText(L.startFailed)
    expect(patchCount()).toBe(1)

    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(queryStartDialog()).not.toBeInTheDocument())
    expect(patchCount()).toBe(1)
    expect(calls).toEqual(['PATCH', 'START', 'START'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-4 나머지 두 갈래 — PATCH 비-409 · PATCH 409(E9)
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — PATCH 실패 갈래', () => {
  it('PATCH 가 500 이면 start 를 보내지 않고 「저장하지 못했습니다」 를 띄운다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['error'] })
    renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchFailed)).toBeInTheDocument()
    expect(calls).toEqual(['PATCH'])
    expect(screen.queryByText(L.startFailed)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: backlogLabels.retry })).toBeInTheDocument()
  })

  it('E9. PATCH 가 409 면 충돌 문구를 띄우고 start 를 보내지 않는다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict'] })
    const { invalidateSpy } = renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    expect(calls).toEqual(['PATCH'])
    expect(startDialog()).toBeInTheDocument()
    // 최신 값을 받아오지 않으면 사용자가 「재확인」할 대상이 없다
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['backlog', PROJECT_KEY] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E9 — 409 뒤에도 **내가 친 값**이 남는다 (입력 파기 금지)
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — E9 충돌 이후 입력 보존', () => {
  /** 남이 먼저 고쳐 서버가 들고 있는 값. `version` 이 올랐고 목표도 다르다 */
  const SERVER_SIDE: SprintMeta = { ...EMPTY_SPRINT, version: 9, goal: '남의 목표' }

  /**
   * 남이 바꾼 필드(`goal`·`endDate`)와 **내가 친 필드(`startDate`)가 서로 다른** 상태.
   *
   * ★ {@link SERVER_SIDE} 는 둘이 `goal` 하나로 겹쳐 있어서 「남이 바꿨는데 나는 안 건드린
   *   필드」라는 조합이 **원리적으로 존재하지 않는다** — 그 픽스처 위에서는 무슨 단언을 써도
   *   재시도가 남의 저장분을 지우는 결함을 잡을 수 없다. 그래서 픽스처를 따로 둔다.
   */
  const OTHERS_SAVED: SprintMeta = {
    ...EMPTY_SPRINT,
    version: 4,
    goal: 'Q3 목표',
    endDate: '2026-09-30',
  }

  it('기준값만 최신으로 갈아끼우고 폼 값은 그대로 둔다 — 재시도가 내 값을 다시 보낸다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict', 'ok'] })
    storedSprint = { ...SERVER_SIDE }
    renderDialog(EMPTY_SPRINT, SERVER_SIDE)

    setField(L.goalLabel, '내 목표')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    // ★ 폼을 서버 값으로 덮으면 재시도의 변경분이 0이 되어(`buildPatchBody` → null)
    //   PATCH 를 건너뛰고 **남의 값으로** 스프린트가 시작된다 — 입력의 조용한 파기다
    expect(screen.getByLabelText(L.goalLabel)).toHaveValue('내 목표')

    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(calls).toEqual(['PATCH', 'PATCH', 'START']))
    // 짝 단언 — `version` 이 9(서버 최신)면 기준값 교체는 실제로 일어났고,
    // `goal` 이 '내 목표'면 그러면서도 입력은 살아남았다. 둘 중 하나만으로는 증명이 안 된다
    expect(patchBodies[1]).toEqual({ version: 9, goal: '내 목표' })
  })

  it('T15 — 안내가 입력 보존을 말한다. 「최신 값을 불러왔으니」는 화면에 없다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict'] })
    storedSprint = { ...SERVER_SIDE }
    renderDialog(EMPTY_SPRINT, SERVER_SIDE)

    setField(L.goalLabel, '내 목표')
    await user.click(submitButton())

    const alert = await screen.findByRole('alert')
    // 값이 화면에 남아 있는데 문구가 「불러왔다」고 하면 안내가 거짓이다 —
    // 두 단언은 짝이다. 부재 단언만 두면 alert 가 아예 안 떠도 통과한다.
    expect(alert).toHaveTextContent('그대로')
    expect(screen.getByLabelText(L.goalLabel)).toHaveValue('내 목표')
    expect(screen.queryByText(/최신 값을 불러왔/)).not.toBeInTheDocument()
  })

  it('T16 — 내가 편집하지 않은 필드는 재시도 body 에서 키 자체가 빠진다 (남의 저장분 보존)', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict', 'ok'] })
    storedSprint = { ...OTHERS_SAVED }
    renderDialog(EMPTY_SPRINT, OTHERS_SAVED)

    // 나는 **시작일만** 친다. 종료일·목표는 손대지 않았다
    setField(L.startDateLabel, '2026-09-01')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(calls).toEqual(['PATCH', 'PATCH', 'START']))
    const retry = patchBodies[1]
    // 3-state partial — **미전송이라야 무변경**이다. `null` 로 실리면 남의 값이 삭제된다
    expect(retry).not.toHaveProperty('endDate')
    expect(retry).not.toHaveProperty('goal')
    // 짝 단언 — 「아무것도 안 보낸다」가 아니다. 내가 친 필드는 최신 version 으로 나간다
    expect(retry).toEqual({ version: 4, startDate: '2026-09-01' })
    // 목이 3-state 를 그대로 반영하므로 이 둘이 곧 「남의 저장분이 살아남았다」다
    expect(storedSprint.goal).toBe('Q3 목표')
    expect(storedSprint.endDate).toBe('2026-09-30')
  })

  it('T16 — 409 뒤 미편집 필드는 서버 최신 값으로 갱신돼 남이 뭘 바꿨는지 보인다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict'] })
    storedSprint = { ...OTHERS_SAVED }
    renderDialog(EMPTY_SPRINT, OTHERS_SAVED)

    setField(L.startDateLabel, '2026-09-01')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    // 내가 친 필드는 내 값 그대로, 안 건드린 둘은 남이 저장한 값을 보여준다.
    // 「표시 갱신이 편집으로 세지 않는다」는 위 T16 단언(키 부재)이 짝으로 증명한다
    expect(screen.getByLabelText(L.startDateLabel)).toHaveValue('2026-09-01')
    expect(screen.getByLabelText(L.endDateLabel)).toHaveValue('2026-09-30')
    expect(screen.getByLabelText(L.goalLabel)).toHaveValue('Q3 목표')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-BD-04 — 409 복구는 **그 보드의** 백로그 캐시에서 기준값을 가져온다
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — 보드 스코프 백로그 캐시 (FR-BD-04)', () => {
  /** `?board=B` 백로그가 들고 있는 서버 최신 값 */
  const SCOPED_FRESH: SprintMeta = { ...EMPTY_SPRINT, version: 9 }

  /**
   * 보드 축 없는 옛 키에 남아 있는 **낡은** 값.
   *
   * 이 미끼가 있어야 「키가 어긋나면 `getQueryData` 가 조용히 남의(또는 없는) 값을 준다」가
   * 결과로 드러난다. 미끼 없이 재면 캐시 미스로 조기 반환해 **아무 일도 안 일어난 것**과
   * 구별되지 않는다.
   */
  const UNSCOPED_STALE: SprintMeta = { ...EMPTY_SPRINT, version: 1 }

  it('409 뒤 재시도가 보드 스코프 캐시의 version으로 나간다 — 옛 키의 값을 쓰지 않는다', async () => {
    const user = userEvent.setup()
    installScenario({ patch: ['conflict', 'ok'] })
    storedSprint = { ...SCOPED_FRESH }
    renderDialog(EMPTY_SPRINT, SCOPED_FRESH, UNSCOPED_STALE)

    setField(L.goalLabel, '내 목표')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(calls).toEqual(['PATCH', 'PATCH', 'START']))
    // ★ version 9 = 보드 스코프 캐시. 1이면 옛 키를 읽은 것이고, 3(props 초기값)이면
    //   `getQueryData` 가 undefined 를 돌려줘 **기준값 교체가 무음으로 멈춘** 것이다
    expect(patchBodies[1]).toEqual({ version: 9, goal: '내 목표' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E8 — 종료일 < 시작일
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — E8 기간 검증', () => {
  it('종료일이 시작일보다 빠르면 제출을 막고, 고치면 그대로 제출된다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog()

    setField(L.startDateLabel, '2026-08-10')
    setField(L.endDateLabel, '2026-08-01')

    expect(await screen.findByText(L.endBeforeStart)).toBeInTheDocument()
    await user.click(submitButton())
    // 백엔드 왕복을 만들지 않는다
    expect(calls).toEqual([])
    expect(startDialog()).toBeInTheDocument()

    // 양성 대조 — 고치면 같은 클릭이 실제로 나간다. 「0건」이 타이밍 착시가 아님을 증명한다
    setField(L.endDateLabel, '2026-08-20')
    expect(screen.queryByText(L.endBeforeStart)).not.toBeInTheDocument()
    await user.click(submitButton())

    await waitFor(() => expect(calls).toEqual(['PATCH', 'START']))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E10 — start 409
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — E10 이미 시작된 스프린트', () => {
  it('start 가 409 면 다이얼로그를 닫고 재시도 버튼을 주지 않는다', async () => {
    const user = userEvent.setup()
    installScenario({ start: ['conflict'] })
    const { onOpenChange, invalidateSpy } = renderDialog(FILLED_SPRINT)

    await user.click(submitButton())

    await waitFor(() => expect(queryStartDialog()).not.toBeInTheDocument())
    expect(vi.mocked(toast.error)).toHaveBeenCalledWith(L.startConflict)
    // 재시도해도 반드시 409 다 — 나머지 세 갈래와 정반대 처방
    expect(screen.queryByRole('button', { name: backlogLabels.retry })).not.toBeInTheDocument()
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['backlog', PROJECT_KEY] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-4 — PATCH 만 성공한 채로 닫을 때
// ─────────────────────────────────────────────────────────────────────────────

describe('StartSprintDialog — 닫을 때의 정합', () => {
  it('PATCH 만 성공한 상태로 취소하면 백로그를 invalidate 한다', async () => {
    const user = userEvent.setup()
    installScenario({ start: ['error'] })
    const { invalidateSpy } = renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())
    await screen.findByText(L.startFailed)

    const before = invalidateSpy.mock.calls.length
    await user.click(screen.getByRole('button', { name: issueCreateStrings.cancelButton }))

    await waitFor(() => expect(invalidateSpy.mock.calls.length).toBeGreaterThan(before))
    expect(invalidateSpy).toHaveBeenLastCalledWith({ queryKey: ['backlog', PROJECT_KEY] })
  })

  it('아무것도 보내지 않고 취소하면 invalidate 하지 않는다', async () => {
    const user = userEvent.setup()
    installScenario()
    const { invalidateSpy } = renderDialog(FILLED_SPRINT)

    await user.click(screen.getByRole('button', { name: issueCreateStrings.cancelButton }))

    await waitFor(() => expect(queryStartDialog()).not.toBeInTheDocument())
    expect(invalidateSpy).not.toHaveBeenCalled()
    expect(calls).toEqual([])
  })
})
