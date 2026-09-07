// 이슈 목록 상태 셀 테스트 — role="status" 보존 · 전환 선택 · 사유 2종 · 권한 · 종료 전환 (FR-UX-11 F9 FR7·FR8·FR9·FR10·FR14)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { IssueTransition } from '@/api/issues'
import { transitionIssue } from '@/api/issues'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { issueDetailStrings } from '@/i18n/ko'
import { StatusCell, StatusCellDisplay, StatusCellEditor } from './StatusCell'

// ─────────────────────────────────────────────────────────────────────────────
// C6 — FR14 **성공 경로** 배선 가드용 mock
//
// 기존 테스트는 순수 표현부(`StatusCellEditor`)만 렌더했고 e2e 는 **취소 경로**만 덮었다.
// 그래서 `runTransition(pendingDone.toStateKey)` 로 `resolutionId` 인자를 빠뜨려도 전
// 테스트가 초록이었다 — 결의안을 고르고 확인했는데 서버에는 안 실려 가는 상태다.
//
// `ResolutionModal` 자체는 자체 테스트(`issue/__tests__/ResolutionModal.test.tsx`)가 덮으므로
// 여기서는 stub 으로 갈아끼우고 **StatusCell 의 이음매**만 잰다. Radix Select 를 jsdom 에서
// 조작하는 60줄짜리 mock 을 복제하지 않는 이유이기도 하다.
// ─────────────────────────────────────────────────────────────────────────────

const { RESOLUTION_ID, DONE_TRANSITION, PLAIN_TRANSITION, transitionState } = vi.hoisted(() => {
  const done = {
    key: 'finish',
    name: '완료',
    fromStateKey: 'open',
    toStateKey: 'DONE',
    toCategory: 'DONE',
  }
  return {
    RESOLUTION_ID: '99999999-9999-4999-8999-999999999999',
    DONE_TRANSITION: done,
    PLAIN_TRANSITION: {
      key: 'start',
      name: '진행 시작',
      fromStateKey: 'open',
      toStateKey: 'IN_PROGRESS',
      toCategory: 'IN_PROGRESS',
    },
    // 테스트마다 갈아끼우는 가용 전환 — 종료/비종료 두 경로를 같은 배선으로 재기 위함
    transitionState: { current: [done] as { toCategory?: string | null }[] },
  }
})

vi.mock('@/api/issues', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/issues')>()
  return { ...actual, transitionIssue: vi.fn() }
})

vi.mock('@/hooks/use-issue-transitions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-issue-transitions')>()
  return {
    ...actual,
    useIssueTransitions: () => ({
      data: transitionState.current,
      isLoading: false,
      isError: false,
      error: null,
    }),
  }
})

vi.mock('@/hooks/use-issue-permissions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-issue-permissions')>()
  return {
    ...actual,
    useIssuePermissions: () => ({
      data: {
        issueKey: 'ATLAS-1',
        permissions: { UPDATE: true, SOFT_DELETE: true, TRANSITION: true },
      },
      isLoading: false,
    }),
  }
})

vi.mock('@/components/issue/ResolutionModal', async () => {
  const { Button } = await vi.importActual<typeof import('@/components/ui/button')>(
    '@/components/ui/button',
  )
  return {
    ResolutionModal: ({
      onConfirm,
      onCancel,
    }: {
      onConfirm: (resolutionId: string) => void
      onCancel: () => void
    }) => (
      <>
        <Button type="button" onClick={() => onConfirm(RESOLUTION_ID)}>
          결의안 확인(stub)
        </Button>
        <Button type="button" onClick={onCancel}>
          결의안 취소(stub)
        </Button>
      </>
    ),
  }
})

/**
 * 비종료 전환 1건.
 *
 * `as IssueTransition` 캐스팅을 쓰지 않고 전 필드를 채운다 — 캐스팅은 스키마가 자라도
 * 조용히 통과해 mock drift 를 감춘다(형제 task 와 동일 판단).
 */
const TRANSITIONS: IssueTransition[] = [
  {
    key: 'start',
    name: '진행 시작',
    fromStateKey: 'TODO',
    toStateKey: 'IN_PROGRESS',
    toCategory: 'IN_PROGRESS',
  },
]

describe('StatusCellDisplay', () => {
  it('상태 배지의 role="status" 를 유지한다 (FR9 — 즉사 계약)', () => {
    render(<StatusCellDisplay currentStateKey="TODO" />)

    expect(screen.getByRole('status')).toHaveTextContent('TODO')
  })

  it('상태 이름이 해석되면 키 대신 이름을 보인다 — 화면에 원시 키를 내지 않는다', () => {
    render(<StatusCellDisplay currentStateKey="in_progress" statusName="진행 중" />)

    expect(screen.getByRole('status')).toHaveTextContent('진행 중')
    expect(screen.getByRole('status')).not.toHaveTextContent('in_progress')
  })

  it('이름 해석에 실패하면 원시 키로 폴백한다 — 값을 숨기지 않는다', () => {
    render(<StatusCellDisplay currentStateKey="in_progress" />)

    expect(screen.getByRole('status')).toHaveTextContent('in_progress')
  })

  it.each([
    ['TODO', 'neutral'],
    ['IN_PROGRESS', 'blue'],
    ['DONE', 'green'],
  ] as const)('카테고리 %s → 배지 색 %s (Jira: 회색·파랑·초록)', (category, variant) => {
    render(<StatusCellDisplay currentStateKey="s" statusName="상태" category={category} />)

    expect(screen.getByRole('status')).toHaveAttribute('data-variant', variant)
  })

  it('카테고리를 모르면 중립색이다 — 모르는 것에 색을 지어내지 않는다', () => {
    render(<StatusCellDisplay currentStateKey="s" />)

    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'neutral')
  })
})

describe('StatusCellEditor', () => {
  it('가용 전환만 버튼으로 노출하고 고르면 toStateKey 로 onTransition 을 부른다 (FR7)', async () => {
    const onTransition = vi.fn()
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={onTransition}
        onDoneTransition={vi.fn()}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '진행 시작' }))

    expect(onTransition).toHaveBeenCalledWith('IN_PROGRESS')
  })

  it('워크플로우 미설정이면 그 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason="no-workflow"
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(
      screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError),
    ).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  it('종료 상태면 다른 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason="terminal"
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    expect(
      screen.queryByText(issueDetailStrings.transitionWorkflowNotConfiguredError),
    ).not.toBeInTheDocument()
  })

  it('전환 권한이 없으면 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition={false}
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '진행 시작' })).toBeDisabled()
  })

  it('저장 중이면 선택지가 비활성이다 (NFR3 중복 제출 차단)', () => {
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition
        isSaving
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '진행 시작' })).toBeDisabled()
  })

  it('조회 중이면 "결과 없음" 대신 로딩을 알린다 (전환 0건과 구분)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  it('종료 전환(toCategory=DONE)는 즉시 전환하지 않고 결의안 요청을 올린다 (FR14)', async () => {
    const onTransition = vi.fn()
    const onDoneTransition = vi.fn()
    const doneTransition: IssueTransition = {
      key: 'finish',
      name: '완료',
      fromStateKey: 'IN_PROGRESS',
      toStateKey: 'DONE',
      toCategory: 'DONE',
    }

    render(
      <StatusCellEditor
        transitions={[doneTransition]}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={onTransition}
        onDoneTransition={onDoneTransition}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '완료' }))

    expect(onDoneTransition).toHaveBeenCalledWith(doneTransition)
    // ★결의안 없이 전환하지 않는다 — 해결 결과는 종료 전환의 필수 입력이다
    expect(onTransition).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C6 — FR14 성공 경로 (결의안 확인 → resolutionId 가 실려 나간다)
// ─────────────────────────────────────────────────────────────────────────────

describe('StatusCell — 종료 전환 성공 경로 (리뷰 C6)', () => {
  beforeEach(() => {
    // ★mock 호출 이력을 반드시 비운다 — 안 비우면 앞 테스트의 호출이 남아
    // "요청이 나가지 않았다" 단언이 남의 호출을 보고 실패한다 (2026-08-04 실측)
    vi.clearAllMocks()
    transitionState.current = [DONE_TRANSITION]
    vi.mocked(transitionIssue).mockResolvedValue({ ...issueAtlas1Fixture, currentStateKey: 'DONE' })
  })

  /** 상태 셀을 열고 첫 전환을 고른다 */
  async function openAndPickTransition(name: string): Promise<void> {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <StatusCell issue={issueAtlas1Fixture} listQueryKey={['issues', 'ATLAS', 0, {}, null]} />
      </QueryClientProvider>,
    )
    await userEvent.click(
      screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 상태 변경`) }),
    )
    await userEvent.click(await screen.findByRole('button', { name }))
  }

  /** 종료 전환을 골라 결의안 모달까지 간다 */
  async function openAndPickDoneTransition(): Promise<void> {
    await openAndPickTransition(DONE_TRANSITION.name)
  }

  it('결의안을 확인하면 그 resolutionId 를 실어 전환한다 (FR14)', async () => {
    await openAndPickDoneTransition()

    // 이 지점까지는 전환 요청이 나가면 안 된다 — 결의안은 종료 전환의 **필수** 입력이다
    expect(vi.mocked(transitionIssue)).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '결의안 확인(stub)' }))

    await waitFor(() => expect(vi.mocked(transitionIssue)).toHaveBeenCalledTimes(1))
    expect(vi.mocked(transitionIssue)).toHaveBeenCalledWith(
      issueAtlas1Fixture.key,
      expect.objectContaining({
        toStatusKey: DONE_TRANSITION.toStateKey,
        expectedVersion: issueAtlas1Fixture.version,
        // ★인자를 빠뜨리면 여기서 죽는다 — 종료 전환이 결의안 없이 나가던 구멍
        resolutionId: RESOLUTION_ID,
      }),
    )
  })

  it('결의안을 취소하면 전환 요청이 나가지 않는다 (E14 — 상태 불변)', async () => {
    await openAndPickDoneTransition()

    await userEvent.click(screen.getByRole('button', { name: '결의안 취소(stub)' }))

    expect(vi.mocked(transitionIssue)).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '결의안 확인(stub)' })).not.toBeInTheDocument()
  })

  it('비종료 전환은 결의안 모달 없이 즉시 전환하고 resolutionId 를 싣지 않는다', async () => {
    // 같은 배선이 비종료 전환에까지 결의안을 실어 보내면 백엔드 계약 위반이다.
    // 이 짝이 없으면 위 단언은 "항상 resolutionId 를 싣는다" 와 구분되지 않아 공허해진다.
    transitionState.current = [PLAIN_TRANSITION]

    await openAndPickTransition(PLAIN_TRANSITION.name)

    expect(screen.queryByRole('button', { name: '결의안 확인(stub)' })).not.toBeInTheDocument()
    await waitFor(() => expect(vi.mocked(transitionIssue)).toHaveBeenCalledTimes(1))
    expect(vi.mocked(transitionIssue).mock.calls[0]?.[1]).toMatchObject({
      toStatusKey: PLAIN_TRANSITION.toStateKey,
    })
    expect(vi.mocked(transitionIssue).mock.calls[0]?.[1]?.resolutionId).toBeUndefined()
  })
})

describe('StatusCellEditor — 같은 상태쌍 전환 둘 (장부 「목록 인라인 상태 편집이 React key 중복과 409 미처리를 함께 갖는다」)', () => {
  /**
   * 백엔드 `WorkflowTransition.key` 게터는 NORMAL 을 `from__to` 로 합성한다. 같은
   * (from, to) 쌍에 전환이 둘이면 **서로 다른 전환인데 key 문자열이 같다** — FR-WF-05 가
   * 전환 ID 식별자를 연 이유가 그것이고, 서버가 409 `AMBIGUOUS_TRANSITION` 을 내는 상황과
   * 같은 조합이다.
   *
   * 픽스처에 이 조합이 없어서 지금까지 초록이었다.
   */
  const SAME_PAIR: IssueTransition[] = [
    {
      key: 'open__done',
      name: '승인 완료',
      fromStateKey: 'open',
      toStateKey: 'done',
      toCategory: 'DONE',
    },
    {
      key: 'open__done',
      name: '강제 완료',
      fromStateKey: 'open',
      toStateKey: 'done',
      toCategory: 'DONE',
    },
  ]

  it('같은 key 를 갖는 전환 둘을 모두 버튼으로 노출한다', () => {
    render(
      <StatusCellEditor
        transitions={SAME_PAIR}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '승인 완료' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '강제 완료' })).toBeInTheDocument()
  })
})
