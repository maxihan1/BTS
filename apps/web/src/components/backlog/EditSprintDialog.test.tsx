// 스프린트 편집 다이얼로그 테스트 — 응답 흡수 · 409 재시도 · COMPLETED 날짜 잠금 (FR-BL-02 D6 FR-1·FR-2)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import type { JSX } from 'react'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'
import { backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta } from '@/api/backlog'
import { EditSprintDialog } from './EditSprintDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

/** PLANNED 스프린트 — 네 칸 모두 편집 가능 */
const PLANNED_SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  boardId: '10000000-0000-4000-8000-000000000001',
  name: 'Sprint 1',
  goal: null,
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 3,
}

/** COMPLETED 스프린트 — 날짜가 잠긴다 (FR-2 · J1) */
const COMPLETED_SPRINT: SprintMeta = {
  ...PLANNED_SPRINT,
  status: 'COMPLETED',
  startDate: '2026-08-01',
  endDate: '2026-08-14',
  goal: '기존 목표',
}

/**
 * 남이 먼저 저장해 서버가 들고 있는 값.
 *
 * 남이 바꾼 필드(`goal`·`endDate`)와 내가 칠 필드(`startDate`)가 **겹치지 않는다** —
 * 겹치면 「남이 바꿨는데 나는 안 건드린 필드」 조합이 없어 방어를 잴 수 없다.
 */
const OTHERS_SAVED: SprintMeta = {
  ...PLANNED_SPRINT,
  version: 4,
  goal: 'Q3 목표',
  endDate: '2026-09-30',
}

const F = backlogLabels.sprintForm
const E = backlogLabels.editDialog

// ─────────────────────────────────────────────────────────────────────────────
// MSW 시나리오 — 요청 횟수와 body 를 직접 기록한다
// ─────────────────────────────────────────────────────────────────────────────

/** `PATCH` 가 실제로 보낸 body 전수 */
let patchBodies: Record<string, unknown>[] = []

/** 목 서버가 들고 있는 스프린트 상태. `PATCH` 를 실제로 반영해야 재시도 단언이 공허해지지 않는다 */
let storedSprint: SprintMeta = { ...PLANNED_SPRINT }

/** 한 요청의 결과 종류 */
type Outcome = 'ok' | 'conflict' | 'error'

/** 백엔드 `JsonNullable` 3-state 를 그대로 흉내 낸다 — 키가 있을 때만 반영한다 */
const PATCHABLE_FIELDS = ['name', 'goal', 'startDate', 'endDate'] as const

/** 3-state partial 을 반영한 새 SprintMeta 를 만든다 (`version` +1) */
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

/** `PATCH` 결과를 **호출 순서대로** 지정한다. 모자라면 마지막 값을 반복한다 */
function installScenario(plan: readonly Outcome[] = ['ok']): void {
  server.use(
    http.patch('/api/v1/sprints/:id', async ({ request }) => {
      const attempt = patchBodies.length
      patchBodies.push((await request.json()) as Record<string, unknown>)
      const outcome = plan[Math.min(attempt, plan.length - 1)] ?? 'ok'
      if (outcome === 'error') {
        return HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
      }
      if (outcome === 'conflict') {
        return HttpResponse.json(
          { errorCode: 'SPRINT_VERSION_CONFLICT', message: '충돌', status: 409 },
          { status: 409 },
        )
      }
      storedSprint = applyPatch(storedSprint, patchBodies[patchBodies.length - 1] ?? {})
      return HttpResponse.json({ data: storedSprint })
    }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 하네스 — 열림 상태를 실제로 쥔다 (닫힘을 DOM 부재로 잰다)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link renderDialog} 반환값 */
interface RenderResult {
  /** `onOpenChange` 스파이 — 닫힘을 스파이 호출과 DOM 부재 양쪽으로 잰다 */
  readonly onOpenChange: ReturnType<typeof vi.fn>
}

function backlogViewOf(sprint: SprintMeta): BacklogView {
  return { backlog: [], sprints: [{ sprint, issues: [] }], truncated: false }
}

/**
 * @param sprint 다이얼로그에 넘길 대상 스프린트 (초기값의 출처)
 * @param cachedSprint 보드 스코프 백로그 캐시가 들고 있는 **서버 최신** 값
 */
function renderDialog(sprint: SprintMeta = PLANNED_SPRINT, cachedSprint?: SprintMeta): RenderResult {
  const onOpenChange = vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  if (cachedSprint !== undefined) {
    queryClient.setQueryData(backlogKeys.detail(PROJECT_KEY, BOARD_ID), backlogViewOf(cachedSprint))
  }

  function Harness(): JSX.Element {
    const [open, setOpen] = useState(true)
    return (
      <EditSprintDialog
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

  return { onOpenChange }
}

/** 날짜 input 은 userEvent 로 한 글자씩 치면 중간값이 무효라 jsdom 이 삼킨다 — change 로 넣는다 */
function setField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

/** 제출 버튼. 다이얼로그 이름과 같은 문자열이므로 role 로 가른다 */
function submitButton(): HTMLElement {
  return screen.getByRole('button', { name: backlogLabels.editSprint })
}

beforeEach(() => {
  patchBodies = []
  storedSprint = { ...PLANNED_SPRINT }
})

// ─────────────────────────────────────────────────────────────────────────────
// 즉사 계약 §2 — 다이얼로그 이름은 화면 내 고유다
// ─────────────────────────────────────────────────────────────────────────────

describe('EditSprintDialog — 다이얼로그 이름 (즉사 계약 §2)', () => {
  it('이름이 「스프린트 편집」이고 시작·완료 다이얼로그 이름과 부분문자열 관계가 아니다', () => {
    installScenario()
    renderDialog()

    expect(
      screen.getByRole('dialog', { name: backlogLabels.editSprint }),
    ).toBeInTheDocument()

    // ★ 위험은 동등이 아니라 **부분문자열**이다 — Playwright 는 부분 일치가 기본이라
    //   한쪽이 다른 쪽을 포함하면 `getByRole('dialog', { name })` 이 2개를 잡는다.
    const names = [
      backlogLabels.editSprint,
      backlogLabels.startSprint,
      backlogLabels.completeSprint,
    ]
    for (const a of names) {
      for (const b of names) {
        if (a === b) continue
        expect(b.includes(a), `'${a}' ⊂ '${b}'`).toBe(false)
      }
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-1 — 이름·목표·기간 편집
// ─────────────────────────────────────────────────────────────────────────────

describe('EditSprintDialog — FR-1 편집 저장', () => {
  it('네 칸이 sprint props 로 채워지고, 바꾼 필드 + version 만 PATCH 로 나간다', async () => {
    const user = userEvent.setup()
    installScenario()
    const { onOpenChange } = renderDialog(COMPLETED_SPRINT)

    expect(screen.getByLabelText(F.nameLabel)).toHaveValue('Sprint 1')
    expect(screen.getByLabelText(F.startDateLabel)).toHaveValue('2026-08-01')
    expect(screen.getByLabelText(F.endDateLabel)).toHaveValue('2026-08-14')
    expect(screen.getByLabelText(F.goalLabel)).toHaveValue('기존 목표')

    await user.clear(screen.getByLabelText(F.nameLabel))
    await user.type(screen.getByLabelText(F.nameLabel), '새 이름')
    await user.click(submitButton())

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    // 안 건드린 세 필드는 **키 자체가 없어야** 한다 (3-state partial)
    expect(patchBodies[0]).toEqual({ version: 3, name: '새 이름' })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('아무것도 안 바꾸면 PATCH 를 보내지 않고 닫는다', async () => {
    const user = userEvent.setup()
    installScenario()
    const { onOpenChange } = renderDialog(COMPLETED_SPRINT)

    await user.click(submitButton())

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(patchBodies).toHaveLength(0)
  })

  it('이름을 비우면 제출을 막고 사유를 보여준다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog(COMPLETED_SPRINT)

    await user.clear(screen.getByLabelText(F.nameLabel))
    await user.click(submitButton())

    expect(await screen.findByText(F.nameRequired)).toBeInTheDocument()
    expect(patchBodies).toHaveLength(0)
    expect(screen.getByRole('dialog', { name: backlogLabels.editSprint })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★ 계약 두 개가 같은 왕복에서 만난다.
//
// ① 부분 저장 유실 방어(NFR-1) — 재시도 body 에 미편집 필드의 키가 없다.
// ② 응답을 버리지 않는다(`useUpdateSprint` KDoc) — 재시도가 **낡은 version 으로 409 를
//    되풀이하지 않는다.** 409 뒤에는 폼이 백로그 캐시의 최신 SprintMeta 로 기준값을
//    갈아끼우고, 재시도는 그 version 을 쓴다. 여기서 invalidate 만 하고 기준값을 안 고치면
//    재조회가 비동기라 그 사이의 재시도가 그대로 3 을 들고 나간다.
// ─────────────────────────────────────────────────────────────────────────────

describe('EditSprintDialog — 409 재시도가 남의 저장분을 지우지 않는다 (NFR-1)', () => {
  it('재시도 body 에 미편집 필드의 키가 없고, 내가 친 필드만 최신 version 으로 나간다', async () => {
    const user = userEvent.setup()
    installScenario(['conflict', 'ok'])
    storedSprint = { ...OTHERS_SAVED }
    renderDialog(PLANNED_SPRINT, OTHERS_SAVED)

    // 나는 **시작일만** 친다. 종료일·목표는 손대지 않았다
    setField(F.startDateLabel, '2026-09-01')
    await user.click(submitButton())

    expect(await screen.findByText(E.saveConflict)).toBeInTheDocument()
    // 409 뒤에도 창이 열려 있고 내가 친 값이 살아 있다
    expect(screen.getByRole('dialog', { name: backlogLabels.editSprint })).toBeInTheDocument()
    expect(screen.getByLabelText(F.startDateLabel)).toHaveValue('2026-09-01')
    // 미편집 칸은 남이 저장한 값을 보여준다 — 무엇이 바뀌었는지 확인할 수 있어야 한다
    await waitFor(() => expect(screen.getByLabelText(F.endDateLabel)).toHaveValue('2026-09-30'))
    expect(screen.getByLabelText(F.goalLabel)).toHaveValue('Q3 목표')

    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(patchBodies).toHaveLength(2))
    const retry = patchBodies[1]
    // 3-state partial — **미전송이라야 무변경**이다. `null` 로 실리면 남의 값이 삭제된다
    expect(retry).not.toHaveProperty('endDate')
    expect(retry).not.toHaveProperty('goal')
    expect(retry).toEqual({ version: 4, startDate: '2026-09-01' })
    // 목이 3-state 를 그대로 반영하므로 이 둘이 곧 「남의 저장분이 살아남았다」다
    expect(storedSprint.goal).toBe('Q3 목표')
    expect(storedSprint.endDate).toBe('2026-09-30')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-2 · J1 — COMPLETED 는 이름·목표만
// ─────────────────────────────────────────────────────────────────────────────

describe('EditSprintDialog — FR-2 COMPLETED 날짜 잠금', () => {
  it('날짜 두 칸이 잠기고 사유가 보이며, 저장 body 에 날짜 키가 실리지 않는다', async () => {
    const user = userEvent.setup()
    installScenario()
    renderDialog(COMPLETED_SPRINT)

    expect(screen.getByLabelText(F.startDateLabel)).toBeDisabled()
    expect(screen.getByLabelText(F.endDateLabel)).toBeDisabled()
    expect(screen.getByText(F.datesLocked)).toBeInTheDocument()

    setField(F.goalLabel, '새 목표')
    await user.click(submitButton())

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    // 백엔드가 COMPLETED + 날짜 present 를 400 으로 거부한다 (Task 1) — 키가 있으면 저장 자체가 실패한다
    expect(patchBodies[0]).not.toHaveProperty('startDate')
    expect(patchBodies[0]).not.toHaveProperty('endDate')
    expect(patchBodies[0]).toEqual({ version: 3, goal: '새 목표' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 상태 3종 — 에러는 화면에 남고 재시도가 있다 (E-3 결)
// ─────────────────────────────────────────────────────────────────────────────

describe('EditSprintDialog — 저장 실패', () => {
  it('500 이면 창이 열린 채 사유와 재시도 버튼을 보여주고, 재시도가 실제로 다시 보낸다', async () => {
    const user = userEvent.setup()
    installScenario(['error', 'ok'])
    const { onOpenChange } = renderDialog()

    setField(F.goalLabel, '목표 A')
    await user.click(submitButton())

    expect(await screen.findByText(E.saveFailed)).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: backlogLabels.editSprint })).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    // 409 문구가 함께 뜨면 갈래가 뭉개진 것이다
    expect(screen.queryByText(E.saveConflict)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: backlogLabels.retry }))

    await waitFor(() => expect(patchBodies).toHaveLength(2))
    // 실패한 요청은 version 을 올리지 않았으므로 재시도도 같은 version 이다
    expect(patchBodies[1]).toEqual({ version: 3, goal: '목표 A' })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('취소하면 요청 없이 닫힌다', async () => {
    const user = userEvent.setup()
    installScenario()
    const { onOpenChange } = renderDialog()

    await user.click(screen.getByRole('button', { name: issueCreateStrings.cancelButton }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(patchBodies).toHaveLength(0)
  })
})
