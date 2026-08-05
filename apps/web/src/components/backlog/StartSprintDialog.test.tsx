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
import type { SprintMeta } from '@/api/backlog'
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

/** 기간·목표가 **비어 있는** PLANNED 스프린트 — 「빈 값 → 입력」 변경분을 만들기 위한 기준 */
const EMPTY_SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
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
  /** `PATCH` 결과. 기본 `'ok'` */
  readonly patch?: Outcome
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

/** 500 ProblemDetail */
function serverError(): HttpResponse {
  return HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
}

/** 409 — errorCode 는 목 핸들러(`backlog-handlers.ts`)와 같은 값을 쓴다 */
function conflict(errorCode: string): HttpResponse {
  return HttpResponse.json({ errorCode, message: '충돌', status: 409 }, { status: 409 })
}

/** 시나리오 핸들러를 덮어쓴다. 전역 MSW 가 이미 떠 있으므로 `server.use` 로 얹는다 */
function installScenario(options: ScenarioOptions = {}): void {
  const startPlan: readonly Outcome[] = options.start ?? ['ok']

  server.use(
    http.patch('/api/v1/sprints/:id', async ({ request }) => {
      calls.push('PATCH')
      patchBodies.push((await request.json()) as Record<string, unknown>)
      if (options.patch === 'error') return serverError()
      if (options.patch === 'conflict') return conflict('SPRINT_VERSION_CONFLICT')
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

function renderDialog(sprint: SprintMeta = EMPTY_SPRINT): RenderResult {
  const onOpenChange = vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
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

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
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
    expect(screen.getByRole('dialog')).toBeInTheDocument()
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

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
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
    installScenario({ patch: 'error' })
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
    installScenario({ patch: 'conflict' })
    const { invalidateSpy } = renderDialog()

    setField(L.goalLabel, '목표 A')
    await user.click(submitButton())

    expect(await screen.findByText(L.patchConflict)).toBeInTheDocument()
    expect(calls).toEqual(['PATCH'])
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    // 최신 값을 받아오지 않으면 사용자가 「재확인」할 대상이 없다
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['backlog', PROJECT_KEY] })
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
    expect(screen.getByRole('dialog')).toBeInTheDocument()

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

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
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

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(invalidateSpy).not.toHaveBeenCalled()
    expect(calls).toEqual([])
  })
})
