// IssueStateTransition 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3) · 상태 메뉴 (FR-WF-01 D9)
import type { ReactElement } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { IssueTransition } from '@/api/issues'
import type { IssueStateTransitionProps } from '@/components/issue/meta/IssueStateTransition'
import {
  IssueStateTransition,
  AmbiguousTransitionDialog,
  ambiguousTransitionStrings,
} from '@/components/issue/meta/IssueStateTransition'
import { AmbiguousTransitionPrompt } from '@/components/issue/meta/AmbiguousTransitionPrompt'
import type { IssueTransitionFlow } from '@/hooks/use-issue-transitions'
import { issueDetailStrings } from '@/i18n/ko'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { server } from '@/test/server'

const transitionsFixture: IssueTransition[] = [
  { key: 'open__in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress', toCategory: 'IN_PROGRESS' },
  { key: 'open__closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed', toCategory: 'DONE' },
]

// 상태 이름 해석이 `GET /api/v1/workflows` 를 부른다 — 픽스처 워크플로우(Open · In Progress …)를 준다
beforeEach(() => {
  server.use(...workflowHandlers)
})

/** 상태 이름 해석이 `useWorkflows`(react-query) 를 거치므로 Provider 로 감싼다 */
function withQuery(ui: ReactElement): ReactElement {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

function renderTransition(overrides: Partial<IssueStateTransitionProps> = {}) {
  const props: IssueStateTransitionProps = {
    currentStateKey: 'open',
    transitions: transitionsFixture,
    onTransition: vi.fn(),
    isTransitioning: false,
    unavailableReason: null,
    ...overrides,
  }
  const utils = render(withQuery(<IssueStateTransition {...props} />))
  return { ...utils, props }
}

/** 트리거(현재 상태 배지)를 눌러 메뉴를 연다 */
async function openMenu(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('button', { name: new RegExp(`^${issueDetailStrings.transitionSelectLabel}`) }))
}

/** 메뉴 항목 이름 — 「전환 이름 + 도착 상태 배지」라 접두로 찾는다 */
function item(name: string): RegExp {
  return new RegExp(`^${name}`)
}

describe('IssueStateTransition', () => {
  it('현재 상태 배지가 트리거이고 이름·범주를 워크플로우에서 해석한다', async () => {
    renderTransition()
    // 픽스처 워크플로우의 `open` 은 「Open」(TODO) 이다 — 원시 키가 아니라 이름이 보여야 한다
    expect(await screen.findByRole('button', { name: `${issueDetailStrings.transitionSelectLabel}, Open` })).toBeInTheDocument()
    expect(screen.getByTestId('issue-state-badge')).toHaveTextContent('Open')
  })

  it('메뉴를 열면 전환이 서버 순서대로 「이름 → 도착 상태」로 나온다', async () => {
    const user = userEvent.setup()
    // 일부러 DONE 을 앞에 둔다 — 화면이 범주로 다시 정렬하면 순서가 뒤집힌다
    renderTransition({ transitions: [transitionsFixture[1]!, transitionsFixture[0]!] })
    await openMenu(user)

    const names = screen.getAllByRole('button').map((b) => b.textContent ?? '')
    const cancel = names.findIndex((n) => n.startsWith('Cancel'))
    const start = names.findIndex((n) => n.startsWith('Start Work'))
    expect(cancel).toBeGreaterThanOrEqual(0)
    expect(cancel).toBeLessThan(start)
    expect(await screen.findByRole('button', { name: 'Start Work In Progress' })).toBeInTheDocument()
  })

  it('항목을 고르면 그 전환으로 onTransition 이 불리고 메뉴가 닫힌다', async () => {
    const user = userEvent.setup()
    const { props } = renderTransition()
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: item('Start Work') }))

    expect(props.onTransition).toHaveBeenCalledOnce()
    expect(props.onTransition).toHaveBeenCalledWith(transitionsFixture[0])
    expect(screen.queryByRole('button', { name: item('Cancel') })).not.toBeInTheDocument()
  })

  it('같은 항목을 연달아 다시 고를 수 있다 (실패 뒤 재시도 — select 의 onChange 미발화 회귀 없음)', async () => {
    const user = userEvent.setup()
    const { props } = renderTransition()
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: item('Start Work') }))
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: item('Start Work') }))
    expect(props.onTransition).toHaveBeenCalledTimes(2)
  })

  it('자기 루프 전환은 따로 묶여 아래에 나오고 고를 수 있다', async () => {
    const user = userEvent.setup()
    const loop: IssueTransition = { key: 'open__open', name: '코멘트 요청', fromStateKey: 'open', toStateKey: 'open', toCategory: 'TODO' }
    const { props } = renderTransition({ transitions: [loop, ...transitionsFixture] })
    await openMenu(user)

    const heading = screen.getByText(issueDetailStrings.transitionLoopHeading)
    const loopButton = screen.getByRole('button', { name: '코멘트 요청' })
    // 루프는 서버 순서상 첫째지만 묶음 제목 뒤 — 상태를 옮기는 항목들 아래에 있어야 한다
    expect(heading.compareDocumentPosition(loopButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(
      screen.getByRole('button', { name: item('Cancel') }).compareDocumentPosition(heading) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()

    await user.click(loopButton)
    expect(props.onTransition).toHaveBeenCalledWith(loop)
  })

  it('isTransitioning=true이면 트리거가 disabled된다', () => {
    renderTransition({ isTransitioning: true })
    expect(screen.getByRole('button', { name: new RegExp(`^${issueDetailStrings.transitionSelectLabel}`) })).toBeDisabled()
  })

  it("가용 전환 0건 + unavailableReason='no-workflow'이면 배지와 워크플로우 미설정 문구가 렌더된다", () => {
    renderTransition({ transitions: [], unavailableReason: 'no-workflow' })
    expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
    expect(screen.getByTestId('issue-state-badge')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it("가용 전환 0건 + unavailableReason='terminal'이면 더 이상 전환 없음 문구가 렌더된다", () => {
    renderTransition({ transitions: [], unavailableReason: 'terminal' })
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })

  it('가용 전환 0건 + unavailableReason=null이면 더 이상 전환 없음 문구가 렌더된다', () => {
    renderTransition({ transitions: [] })
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T21. 409 AMBIGUOUS_TRANSITION 후보 선택 다이얼로그 (ADR 2026-08-18 §D3)
// ─────────────────────────────────────────────────────────────────────────────

/** 후보 전환 1번의 1급 식별자. */
const CANDIDATE_ONE_ID = '33333333-3333-4333-8333-333333333333'
/** 후보 전환 2번의 1급 식별자. */
const CANDIDATE_TWO_ID = '44444444-4444-4444-8444-444444444444'

const CANDIDATES = [
  { transitionId: CANDIDATE_ONE_ID, name: '조건부 승인' },
  { transitionId: CANDIDATE_TWO_ID, name: '즉시 완료' },
]

const AMBIGUOUS_MESSAGE = '이동할 수 있는 전환이 2개입니다. 어느 전환인지 골라 주세요.'

describe('IssueStateTransition — 같은 도착 상태 전환이 둘일 때 (T21)', () => {
  const duplicated: IssueTransition[] = [
    { key: 'open__done', name: '조건부 승인', fromStateKey: 'open', toStateKey: 'done', transitionId: CANDIDATE_ONE_ID },
    { key: 'open__done', name: '즉시 완료', fromStateKey: 'open', toStateKey: 'done', transitionId: CANDIDATE_TWO_ID },
  ]

  it('T21-C0: 도착 상태가 같아도 두 항목이 모두 렌더된다 (React key 중복 없음)', async () => {
    const user = userEvent.setup()
    renderTransition({ transitions: duplicated })
    await openMenu(user)
    expect(screen.getByRole('button', { name: item('조건부 승인') })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: item('즉시 완료') })).toBeInTheDocument()
  })

  it('T21-C5: 도착 상태가 같은 두 전환 중 둘째를 고르면 그 전환이 onTransition 에 전달된다', async () => {
    const user = userEvent.setup()
    const { props } = renderTransition({ transitions: duplicated })
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: item('즉시 완료') }))

    // ★도착 상태(`done`)로는 두 전환을 가를 수 없다. 고른 전환 **자체**가 넘어와야
    //   호출부가 `transitionId` 를 실어 보내고 409 왕복이 사라진다.
    expect(props.onTransition).toHaveBeenCalledOnce()
    expect(props.onTransition).toHaveBeenCalledWith(duplicated[1])
  })

  it('T21-C6: `transitionId` 가 없는 구 데이터도 고른 전환이 그대로 전달된다 (폴백)', async () => {
    const user = userEvent.setup()
    const legacy: IssueTransition[] = [
      { key: 'open__in_progress', name: '작업 시작', fromStateKey: 'open', toStateKey: 'in_progress' },
    ]
    const { props } = renderTransition({ transitions: legacy })
    await openMenu(user)
    await user.click(screen.getByRole('button', { name: item('작업 시작') }))
    expect(props.onTransition).toHaveBeenCalledWith(legacy[0])
  })

  it('T21-C7: `transitionId` 가 **둘 다** 없는 같은 상태쌍도 두 항목이 살아남는다', async () => {
    // ★폴백의 최악 경우다. key 가 `from__to` 로 떨어지는데 같은 상태쌍이면 그 값이 겹친다.
    //   정본(`lib/transition-key`)이 목록 위치를 덧붙여 둘 다 남긴다 — 재조정을 지나서도.
    const user = userEvent.setup()
    const bothNull: IssueTransition[] = [
      { key: 'open__done', name: '조건부 승인', fromStateKey: 'open', toStateKey: 'done' },
      { key: 'open__done', name: '즉시 완료', fromStateKey: 'open', toStateKey: 'done' },
    ]
    const { rerender, props } = renderTransition({ transitions: bothNull })
    await openMenu(user)
    const menu = screen.getByRole('dialog')
    expect(within(menu).getAllByRole('button')).toHaveLength(2)

    rerender(withQuery(<IssueStateTransition {...props} transitions={[...bothNull]} />))
    expect(screen.getByRole('button', { name: item('조건부 승인') })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: item('즉시 완료') })).toBeInTheDocument()
  })
})

describe('AmbiguousTransitionDialog', () => {
  it('T21-C1: 고유 aria-label 을 가진 다이얼로그와 후보 전량이 렌더된다', () => {
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(
      screen.getByRole('dialog', { name: ambiguousTransitionStrings.dialogTitle }),
    ).toBeInTheDocument()
    // 서버가 준 안내 문구를 그대로 보여 준다 — 후보 개수가 문구에 들어 있다
    expect(screen.getByText(AMBIGUOUS_MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeInTheDocument()
  })

  it('T21-C2: 후보를 고르면 그 transitionId 로 onSelect 가 호출된다', async () => {
    const onSelect = vi.fn()
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={onSelect}
        onCancel={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '즉시 완료' }))
    expect(onSelect).toHaveBeenCalledOnce()
    expect(onSelect).toHaveBeenCalledWith(CANDIDATE_TWO_ID)
  })

  it('T21-C3: 취소하면 onCancel 이 호출되고 onSelect 는 호출되지 않는다', async () => {
    const onSelect = vi.fn()
    const onCancel = vi.fn()
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={onSelect}
        onCancel={onCancel}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: ambiguousTransitionStrings.cancel }))
    expect(onCancel).toHaveBeenCalledOnce()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('T21-C4: 재요청 진행 중이면 후보 버튼이 disabled 된다 (중복 제출 방지)', () => {
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning
        onSelect={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// AmbiguousTransitionPrompt — 같은 409 프롬프트의 「상태 ↔ 표현」 이음매라 여기서 함께 잰다.
//   커넥터가 prop 하나를 흘려도 표현 컴포넌트 테스트(T21-C*)는 그대로 초록이다.
// ─────────────────────────────────────────────────────────────────────────────

/** 커넥터 계약만 재려고 만든 가짜 플로우. 훅 자체는 use-issue-transitions.test 가 잰다. */
function createFlowStub(overrides: Partial<IssueTransitionFlow> = {}): IssueTransitionFlow {
  return {
    mutate: vi.fn(),
    isPending: false,
    prompt: null,
    selectCandidate: vi.fn(),
    cancelPrompt: vi.fn(),
    ...overrides,
  }
}

/** 열려 있는 프롬프트 — 서버가 돌려준 안내 문구 + 후보 전량 + 재요청에 쓸 원 요청. */
const OPEN_PROMPT = {
  message: AMBIGUOUS_MESSAGE,
  candidates: CANDIDATES,
  input: { toStatusKey: 'done', expectedVersion: 7 },
}

describe('AmbiguousTransitionPrompt', () => {
  it('T26-P1: 열린 프롬프트가 없으면 다이얼로그를 그리지 않는다', () => {
    render(<AmbiguousTransitionPrompt flow={createFlowStub()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('T26-P2: 프롬프트가 열리면 서버 문구와 후보 전량이 다이얼로그로 노출된다', () => {
    render(<AmbiguousTransitionPrompt flow={createFlowStub({ prompt: OPEN_PROMPT })} />)
    expect(
      screen.getByRole('dialog', { name: ambiguousTransitionStrings.dialogTitle }),
    ).toBeInTheDocument()
    expect(screen.getByText(AMBIGUOUS_MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeInTheDocument()
  })

  it('T26-P3: 후보를 고르면 flow.selectCandidate 가 그 transitionId 로 호출된다', async () => {
    const selectCandidate = vi.fn()
    render(
      <AmbiguousTransitionPrompt flow={createFlowStub({ prompt: OPEN_PROMPT, selectCandidate })} />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '즉시 완료' }))
    expect(selectCandidate).toHaveBeenCalledOnce()
    expect(selectCandidate).toHaveBeenCalledWith(CANDIDATE_TWO_ID)
  })

  it('T26-P4: 취소하면 flow.cancelPrompt 만 호출된다 (전환 미실행)', async () => {
    const cancelPrompt = vi.fn()
    const selectCandidate = vi.fn()
    render(
      <AmbiguousTransitionPrompt
        flow={createFlowStub({ prompt: OPEN_PROMPT, cancelPrompt, selectCandidate })}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: ambiguousTransitionStrings.cancel }))
    expect(cancelPrompt).toHaveBeenCalledOnce()
    expect(selectCandidate).not.toHaveBeenCalled()
  })

  it('T26-P5: 재요청 진행 중이면 후보 버튼이 disabled 된다 (isPending 전달 확인)', () => {
    render(
      <AmbiguousTransitionPrompt flow={createFlowStub({ prompt: OPEN_PROMPT, isPending: true })} />,
    )
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeDisabled()
  })
})
