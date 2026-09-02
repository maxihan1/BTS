// 스프린트 공용 폼 테스트 — 부분 저장 유실 방어 · COMPLETED 날짜 잠금 · 이름 칸 (FR-BL-02 D6 NFR-1)
import { describe, it, expect } from 'vitest'
import type { JSX } from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import { SprintForm, useSprintForm } from './SprintForm'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

/** 기간·목표가 비어 있는 PLANNED 스프린트 */
const PLANNED_SPRINT: SprintMeta = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  name: 'Sprint 1',
  goal: null,
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 3,
}

/** 완료된 스프린트 — 날짜가 잠기는 유일한 상태 (FR-2 · J1) */
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
 * ★ 남이 바꾼 필드(`goal`·`endDate`)와 **내가 칠 필드(`startDate`)가 겹치지 않는다.**
 *   겹치면 「남이 바꿨는데 나는 안 건드린 필드」라는 조합이 원리적으로 없어서, 무슨 단언을
 *   써도 재시도가 남의 저장분을 지우는 결함을 잡을 수 없다 (StartSprintDialog.test 선례).
 */
const OTHERS_SAVED: SprintMeta = {
  ...PLANNED_SPRINT,
  version: 4,
  goal: 'Q3 목표',
  endDate: '2026-09-30',
}

const F = backlogLabels.sprintForm

// ─────────────────────────────────────────────────────────────────────────────
// 하네스 — 컨트롤러의 세 조작(편집 · 충돌 복구 · body 생성)을 DOM 으로 노출한다
// ─────────────────────────────────────────────────────────────────────────────

/** 제출 버튼 이름. 폼 자체는 제출 버튼을 갖지 않으므로 하네스가 준다 */
const SUBMIT_LABEL = '하네스 제출'

/** 409 복구 트리거 이름 */
const RECOVER_LABEL = '하네스 충돌 복구'

/** {@link Harness} props */
interface HarnessProps {
  readonly sprint: SprintMeta
  readonly showName: boolean
  readonly onSubmit: (body: UpdateSprintBody | null) => void
}

function Harness({ sprint, showName, onSubmit }: HarnessProps): JSX.Element {
  const form = useSprintForm(sprint, PROJECT_KEY, BOARD_ID)
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit(form.buildPatchBody())
      }}
    >
      <SprintForm form={form} disabled={false} showName={showName} />
      <button type="submit">{SUBMIT_LABEL}</button>
      <button
        type="button"
        onClick={() => {
          void form.recoverFromConflict()
        }}
      >
        {RECOVER_LABEL}
      </button>
    </form>
  )
}

/** {@link renderForm} 반환값 */
interface RenderResult {
  /** 제출할 때마다 쌓이는 `buildPatchBody()` 결과 전수 */
  readonly bodies: Array<UpdateSprintBody | null>
}

/**
 * @param sprint 폼의 기준값이 될 스프린트
 * @param options.showName 이름 칸을 그릴지. 시작 다이얼로그는 `false` 다
 * @param options.cachedSprint 보드 스코프 백로그 캐시가 들고 있는 **서버 최신** 값
 */
function renderForm(
  sprint: SprintMeta,
  options: { showName?: boolean; cachedSprint?: SprintMeta } = {},
): RenderResult {
  const bodies: Array<UpdateSprintBody | null> = []
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const cached = options.cachedSprint
  if (cached !== undefined) {
    const view: BacklogView = { backlog: [], sprints: [{ sprint: cached, issues: [] }], truncated: false }
    queryClient.setQueryData(backlogKeys.detail(PROJECT_KEY, BOARD_ID), view)
  }

  render(
    <QueryClientProvider client={queryClient}>
      <Harness
        sprint={sprint}
        showName={options.showName ?? true}
        onSubmit={(body) => {
          bodies.push(body)
        }}
      />
    </QueryClientProvider>,
  )

  return { bodies }
}

/** 날짜 input 은 userEvent 로 한 글자씩 치면 중간값이 무효라 jsdom 이 삼킨다 — change 로 넣는다 */
function setField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

function submit(): void {
  fireEvent.click(screen.getByRole('button', { name: SUBMIT_LABEL }))
}

// ─────────────────────────────────────────────────────────────────────────────
// FR-2 · J1 — COMPLETED 는 이름·목표만 (Sanity ❓1: 숨기지 않고 비활성 + 사유)
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — COMPLETED 날짜 잠금 (FR-2 · J1)', () => {
  it('완료된 스프린트는 날짜 두 칸이 disabled 이고 사유 문구가 보인다', () => {
    renderForm(COMPLETED_SPRINT)

    expect(screen.getByLabelText(F.startDateLabel)).toBeDisabled()
    expect(screen.getByLabelText(F.endDateLabel)).toBeDisabled()
    expect(screen.getByText(F.datesLocked)).toBeInTheDocument()
    // 짝 단언 — 전부 잠그면 위 두 줄만으로는 통과하지만 편집 자체가 불가능해진다.
    // 「무엇이 열려 있는가」를 함께 재야 J1(이름·목표는 편집 가능)이 실제로 지켜진다.
    expect(screen.getByLabelText(F.nameLabel)).toBeEnabled()
    expect(screen.getByLabelText(F.goalLabel)).toBeEnabled()
  })

  it('PLANNED 스프린트는 날짜가 열려 있고 잠금 사유도 없다', () => {
    // 음성 대조 — 잠금 문구가 늘 떠 있으면 위 테스트는 공허하다
    renderForm(PLANNED_SPRINT)

    expect(screen.getByLabelText(F.startDateLabel)).toBeEnabled()
    expect(screen.getByLabelText(F.endDateLabel)).toBeEnabled()
    expect(screen.queryByText(F.datesLocked)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// NFR-1 — 시작 다이얼로그가 쓰던 3칸 폼이 그대로 남는다
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — 이름 칸 노출 (showName)', () => {
  it('showName=false 면 이름 칸이 DOM 에 없다 (시작 다이얼로그는 3칸이다)', () => {
    renderForm(PLANNED_SPRINT, { showName: false })

    expect(screen.queryByLabelText(F.nameLabel)).not.toBeInTheDocument()
    expect(screen.getByLabelText(F.startDateLabel)).toBeInTheDocument()
    expect(screen.getByLabelText(F.endDateLabel)).toBeInTheDocument()
    expect(screen.getByLabelText(F.goalLabel)).toBeInTheDocument()
  })

  it('이름을 비우면 사유 문구가 뜨고 body 에 name 키가 실리지 않는다', () => {
    // 백엔드는 name 에 null 을 허용하지 않는다 — 빈 값을 그대로 실으면 400 이다
    const { bodies } = renderForm(PLANNED_SPRINT)

    setField(F.nameLabel, '')
    submit()

    expect(screen.getByText(F.nameRequired)).toBeInTheDocument()
    expect(bodies[0]).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★ NFR-1 핵심 — 부분 저장 유실 방어가 추출을 타고 흘러내리지 않았다
//
// `StartSprintDialog.tsx:75` 주석이 이미 푼 문제다. 기준값이 409 로 갈리는 순간
// 「내가 고친 필드」와 「기준값과 다른 필드」가 서로 다른 집합이 되고, 후자로 변경분을
// 세면 재시도가 `endDate: null`·`goal: null` 을 실어 **남의 저장분을 조용히 지운다**.
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — 부분 저장 유실 방어 (NFR-1)', () => {
  it('충돌 복구로 기준값이 바뀌어도 미편집 필드는 body 에서 키 자체가 빠진다', async () => {
    const user = userEvent.setup()
    const { bodies } = renderForm(PLANNED_SPRINT, { cachedSprint: OTHERS_SAVED })

    // 나는 **시작일만** 친다. 종료일·목표는 손대지 않았다
    setField(F.startDateLabel, '2026-09-01')
    await user.click(screen.getByRole('button', { name: RECOVER_LABEL }))

    // 기준값이 남의 저장분으로 갈아끼워졌다 — 미편집 칸이 서버 최신 값을 보여준다
    await waitFor(() => expect(screen.getByLabelText(F.endDateLabel)).toHaveValue('2026-09-30'))
    expect(screen.getByLabelText(F.goalLabel)).toHaveValue('Q3 목표')
    // 내가 친 값은 그대로다 — 덮으면 재시도의 변경분이 0이 되어 입력이 조용히 파기된다
    expect(screen.getByLabelText(F.startDateLabel)).toHaveValue('2026-09-01')

    submit()

    const body = bodies[0]
    // 3-state partial — **미전송이라야 무변경**이다. `null` 로 실리면 남의 값이 삭제된다
    expect(body).not.toHaveProperty('endDate')
    expect(body).not.toHaveProperty('goal')
    // 짝 단언 — 「아무것도 안 보낸다」가 아니다. 내가 친 필드는 최신 version 으로 나간다
    expect(body).toEqual({ version: 4, startDate: '2026-09-01' })
  })

  it('편집한 필드가 기준값과 같아지면 담지 않는다 (불필요한 version 증가 금지)', () => {
    const { bodies } = renderForm(COMPLETED_SPRINT)

    setField(F.goalLabel, '바꿨다')
    setField(F.goalLabel, '기존 목표')
    submit()

    expect(bodies[0]).toBeNull()
  })

  it('응답을 반영하면 다음 body 가 갱신된 version 으로 나간다', async () => {
    // `useUpdateSprint` KDoc 계약 — 호출자가 응답 SprintMeta 로 기준값과 version 을 갱신한다
    const user = userEvent.setup()
    const { bodies } = renderForm(PLANNED_SPRINT, { cachedSprint: OTHERS_SAVED })

    setField(F.goalLabel, '내 목표')
    submit()
    expect(bodies[0]).toEqual({ version: 3, goal: '내 목표' })

    await user.click(screen.getByRole('button', { name: RECOVER_LABEL }))
    await waitFor(() => expect(screen.getByLabelText(F.endDateLabel)).toHaveValue('2026-09-30'))
    submit()

    expect(bodies[1]).toEqual({ version: 4, goal: '내 목표' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E8 — 종료일 < 시작일
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — E8 기간 검증', () => {
  it('종료일이 시작일보다 빠르면 필드 에러를 붙이고, 고치면 사라진다', () => {
    renderForm(PLANNED_SPRINT)

    setField(F.startDateLabel, '2026-08-10')
    setField(F.endDateLabel, '2026-08-01')
    expect(screen.getByText(F.endBeforeStart)).toBeInTheDocument()
    expect(screen.getByLabelText(F.endDateLabel)).toHaveAttribute('aria-invalid', 'true')

    setField(F.endDateLabel, '2026-08-20')
    expect(screen.queryByText(F.endBeforeStart)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 캐시 미스 — 조용히 아무 일도 안 하는 것이 정상 동작이다
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — 충돌 복구의 캐시 미스', () => {
  it('보드 스코프 캐시에 값이 없으면 기준값을 그대로 둔다', async () => {
    const user = userEvent.setup()
    const { bodies } = renderForm(PLANNED_SPRINT)

    setField(F.goalLabel, '내 목표')
    await user.click(screen.getByRole('button', { name: RECOVER_LABEL }))
    submit()

    // props 초기값의 version 이 그대로 남는다 — 없는 값으로 덮어 쓰지 않는다
    await waitFor(() => expect(bodies[0]).toEqual({ version: 3, goal: '내 목표' }))
  })
})
