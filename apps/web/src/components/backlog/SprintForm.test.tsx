// 스프린트 공용 폼 테스트 — 부분 저장 유실 방어 · COMPLETED 날짜 잠금 · 이름 칸 (FR-BL-02 D6 NFR-1)
import { describe, it, expect } from 'vitest'
import type { JSX } from 'react'
import { useRef } from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { backlogKeys } from '@/hooks/use-backlog'
import type { BacklogView, SprintMeta, UpdateSprintBody } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import { SprintForm } from './SprintForm'
import type { SprintFormActions } from './SprintForm'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

/** 화면이 보고 있는 보드 — `?board=` 로 들어온 값 (FR-BD-04) */
const BOARD_ID = 'b0000000-0000-4000-8000-00000000000b'

/** 기간·목표가 비어 있는 PLANNED 스프린트 */
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
 *   겹치면 「남이 바꿨는데 나는 안 건드린 필드」라는 조합이 원리적으로 존재하지 않아,
 *   무슨 단언을 써도 재시도가 남의 저장분을 지우는 결함을 잡을 수 없다.
 */
const OTHERS_SAVED: SprintMeta = {
  ...PLANNED_SPRINT,
  version: 4,
  goal: 'Q3 목표',
  endDate: '2026-09-30',
}

const F = backlogLabels.sprintForm

// ─────────────────────────────────────────────────────────────────────────────
// 하네스 — 폼이 넘겨준 body 와 actions 를 붙잡아 DOM 조작으로 노출한다
// ─────────────────────────────────────────────────────────────────────────────

/** 제출 버튼 이름. 폼 자체는 제출 버튼을 갖지 않으므로 하네스가 준다 */
const SUBMIT_LABEL = '하네스 제출'

/** 409 복구 트리거 이름 — 실제 다이얼로그에서는 mutation 의 catch 가 부른다 */
const RECOVER_LABEL = '하네스 충돌 복구'

/** 성공 응답 흡수 트리거 이름 — 실제 다이얼로그에서는 mutation 의 then 이 부른다 */
const ABSORB_LABEL = '하네스 응답 흡수'

/** {@link Harness} props */
interface HarnessProps {
  readonly sprint: SprintMeta
  readonly showName: boolean
  readonly onSubmit: (body: UpdateSprintBody | null) => void
  /** {@link ABSORB_LABEL} 버튼이 폼에 되먹일 저장 응답 */
  readonly serverResponse: SprintMeta
}

function Harness({ sprint, showName, onSubmit, serverResponse }: HarnessProps): JSX.Element {
  const actionsRef = useRef<SprintFormActions | null>(null)

  return (
    <SprintForm
      sprint={sprint}
      projectKey={PROJECT_KEY}
      boardId={BOARD_ID}
      showName={showName}
      disabled={false}
      onSubmit={(body, actions) => {
        actionsRef.current = actions
        onSubmit(body)
      }}
    >
      <button type="submit">{SUBMIT_LABEL}</button>
      <button
        type="button"
        onClick={() => {
          const actions = actionsRef.current
          if (actions !== null) void actions.recoverFromConflict()
        }}
      >
        {RECOVER_LABEL}
      </button>
      <button
        type="button"
        onClick={() => {
          actionsRef.current?.applyServerResponse(serverResponse)
        }}
      >
        {ABSORB_LABEL}
      </button>
    </SprintForm>
  )
}

/** {@link renderForm} 반환값 */
interface RenderResult {
  /** 제출할 때마다 쌓이는 변경분 전수. 폼이 가드로 막으면 아무것도 쌓이지 않는다 */
  readonly bodies: Array<UpdateSprintBody | null>
}

/**
 * @param sprint 폼의 기준값이 될 스프린트
 * @param options.showName 이름 칸을 그릴지. 시작 다이얼로그는 `false` 다
 * @param options.cachedSprint 보드 스코프 백로그 캐시가 들고 있는 **서버 최신** 값
 * @param options.serverResponse 「응답 흡수」 버튼이 폼에 되먹일 저장 응답
 */
function renderForm(
  sprint: SprintMeta,
  options: { showName?: boolean; cachedSprint?: SprintMeta; serverResponse?: SprintMeta } = {},
): RenderResult {
  const bodies: Array<UpdateSprintBody | null> = []
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const cached = options.cachedSprint
  if (cached !== undefined) {
    const view: BacklogView = {
      backlog: [],
      sprints: [{ sprint: cached, issues: [] }],
      truncated: false,
    }
    queryClient.setQueryData(backlogKeys.detail(PROJECT_KEY, BOARD_ID), view)
  }

  render(
    <QueryClientProvider client={queryClient}>
      <Harness
        sprint={sprint}
        showName={options.showName ?? true}
        serverResponse={options.serverResponse ?? sprint}
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
    // 짝 단언 — 전부 잠그면 위 세 줄만으로도 통과하지만 편집 자체가 불가능해진다.
    // 「무엇이 열려 있는가」를 함께 재야 J1(이름·목표는 편집 가능)이 실제로 지켜진다
    expect(screen.getByLabelText(F.nameLabel)).toBeEnabled()
    expect(screen.getByLabelText(F.goalLabel)).toBeEnabled()
  })

  it('잠긴 날짜 칸이 사유 문구를 aria-describedby 로 가리킨다', () => {
    renderForm(COMPLETED_SPRINT)

    const hintId = screen.getByText(F.datesLocked).id
    expect(hintId).not.toBe('')
    expect(screen.getByLabelText(F.startDateLabel)).toHaveAttribute('aria-describedby', hintId)
    expect(screen.getByLabelText(F.endDateLabel)).toHaveAttribute('aria-describedby', hintId)
  })

  it('PLANNED 스프린트는 날짜가 열려 있고 잠금 사유도 없다', () => {
    // 음성 대조 — 잠금 문구가 늘 떠 있으면 위 두 테스트는 공허하다
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

  it('이름을 비우면 사유가 뜨고 제출 자체가 막힌다', () => {
    // 백엔드는 이름에 null 을 허용하지 않는다 — 빈 값을 그대로 실으면 400 이다
    const { bodies } = renderForm(PLANNED_SPRINT)

    setField(F.nameLabel, '   ')
    submit()

    expect(screen.getByText(F.nameRequired)).toBeInTheDocument()
    expect(screen.getByLabelText(F.nameLabel)).toHaveAttribute('aria-invalid', 'true')
    expect(bodies).toHaveLength(0)

    // 양성 대조 — 다시 채우면 같은 클릭이 실제로 지나간다
    setField(F.nameLabel, '새 이름')
    submit()
    expect(bodies[0]).toEqual({ version: 3, name: '새 이름' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★ NFR-1 핵심 — 부분 저장 유실 방어가 추출을 타고 흘러내리지 않았다
//
// `StartSprintDialog.tsx:75`(추출 전) 주석이 이미 푼 문제다. 기준값이 409 로 갈리는 순간
// 「내가 고친 필드」와 「기준값과 다른 필드」가 서로 다른 집합이 되고, 후자로 변경분을
// 세면 재시도가 `endDate: null`·`goal: null` 을 실어 **남의 저장분을 조용히 지운다**.
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — 부분 저장 유실 방어 (NFR-1)', () => {
  it('충돌 복구로 기준값이 바뀌어도 미편집 필드는 body 에서 키 자체가 빠진다', async () => {
    const user = userEvent.setup()
    const { bodies } = renderForm(PLANNED_SPRINT, { cachedSprint: OTHERS_SAVED })

    // 나는 **시작일만** 친다. 종료일·목표는 손대지 않았다
    setField(F.startDateLabel, '2026-09-01')
    submit()
    expect(bodies[0]).toEqual({ version: 3, startDate: '2026-09-01' })

    await user.click(screen.getByRole('button', { name: RECOVER_LABEL }))

    // 기준값이 남의 저장분으로 갈아끼워졌다 — 미편집 칸이 서버 최신 값을 보여준다
    await waitFor(() => expect(screen.getByLabelText(F.endDateLabel)).toHaveValue('2026-09-30'))
    expect(screen.getByLabelText(F.goalLabel)).toHaveValue('Q3 목표')
    // 내가 친 값은 그대로다 — 덮으면 재시도의 변경분이 0이 되어 입력이 조용히 파기된다
    expect(screen.getByLabelText(F.startDateLabel)).toHaveValue('2026-09-01')

    submit()

    const retry = bodies[1]
    // 3-state partial — **미전송이라야 무변경**이다. `null` 로 실리면 남의 값이 삭제된다
    expect(retry).not.toHaveProperty('endDate')
    expect(retry).not.toHaveProperty('goal')
    // 짝 단언 — 「아무것도 안 보낸다」가 아니다. 내가 친 필드는 최신 version 으로 나간다
    expect(retry).toEqual({ version: 4, startDate: '2026-09-01' })
  })

  it('편집한 필드가 기준값과 같아지면 담지 않는다 (불필요한 version 증가 금지)', () => {
    const { bodies } = renderForm(COMPLETED_SPRINT)

    setField(F.goalLabel, '바꿨다')
    setField(F.goalLabel, '기존 목표')
    submit()

    expect(bodies[0]).toBeNull()
  })

  it('값을 지우면 명시 null 을 담는다 (원래 없던 값은 변경분이 아니다)', () => {
    const { bodies } = renderForm(COMPLETED_SPRINT)

    setField(F.goalLabel, '')
    submit()

    expect(bodies[0]).toEqual({ version: 3, goal: null })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★★ 계약 — mutation 응답을 버리지 않는다 (`useUpdateSprint` KDoc)
//
// 「재시도가 낡은 version 으로 409 를 받지 않으려면 호출자가 이 mutation 의 응답
//  SprintMeta 로 자기 기준값과 version 을 갱신해야 한다. 여기서 invalidate 를 하더라도
//  재조회는 비동기라 그 사이의 재시도를 막아주지 못한다.」
//
// 두 소비자 모두 이 경로를 실제로 지난다 — 시작 다이얼로그는 `PATCH` 성공 뒤 `start` 가
// 실패하면 **창을 열어 둔 채** 재시도를 받고(StartSprintDialog.test T-DL-4 가 그 왕복을
// 통째로 잰다), 편집 다이얼로그는 저장 성공 응답을 그대로 흡수한 뒤 닫는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — 저장 응답 흡수 (useUpdateSprint 계약)', () => {
  /** 서버가 내 변경분을 반영해 돌려준 값. `version` 이 올랐다 */
  const SAVED: SprintMeta = { ...PLANNED_SPRINT, version: 4, goal: '내 목표' }

  it('응답을 되먹이면 기준값과 version 이 갈리고 재시도의 변경분이 0이 된다', async () => {
    const user = userEvent.setup()
    const { bodies } = renderForm(PLANNED_SPRINT, { serverResponse: SAVED })

    setField(F.goalLabel, '내 목표')
    submit()
    expect(bodies[0]).toEqual({ version: 3, goal: '내 목표' })

    await user.click(screen.getByRole('button', { name: ABSORB_LABEL }))
    submit()

    // 서버가 이미 그 값이다 — 다시 보낼 것이 없으므로 `null`. 응답을 버렸다면 편집 집합이
    // 남아 `{ version: 3, ... }` 가 또 나가고 실제 서버라면 409 다
    await waitFor(() => expect(bodies[1]).toBeNull())
    // 짝 단언 — 흡수 뒤 새로 고친 필드는 **갱신된 version** 으로 나간다.
    // 이것 없이는 「아무것도 안 보낸다」와 「기준값이 갱신됐다」가 구별되지 않는다
    setField(F.goalLabel, '더 고친 목표')
    submit()
    expect(bodies[2]).toEqual({ version: 4, goal: '더 고친 목표' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E8 — 종료일 < 시작일
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintForm — E8 기간 검증', () => {
  it('종료일이 시작일보다 빠르면 제출을 막고, 고치면 그대로 제출된다', () => {
    const { bodies } = renderForm(PLANNED_SPRINT)

    setField(F.startDateLabel, '2026-08-10')
    setField(F.endDateLabel, '2026-08-01')
    expect(screen.getByText(F.endBeforeStart)).toBeInTheDocument()
    expect(screen.getByLabelText(F.endDateLabel)).toHaveAttribute('aria-invalid', 'true')

    submit()
    expect(bodies).toHaveLength(0)

    // 양성 대조 — 고치면 같은 클릭이 실제로 지나간다
    setField(F.endDateLabel, '2026-08-20')
    expect(screen.queryByText(F.endBeforeStart)).not.toBeInTheDocument()
    submit()

    expect(bodies[0]).toEqual({ version: 3, startDate: '2026-08-10', endDate: '2026-08-20' })
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
    submit()
    await user.click(screen.getByRole('button', { name: RECOVER_LABEL }))
    submit()

    // props 초기값의 version 이 그대로 남는다 — 없는 값으로 덮어 쓰지 않는다
    await waitFor(() => expect(bodies[1]).toEqual({ version: 3, goal: '내 목표' }))
  })
})
