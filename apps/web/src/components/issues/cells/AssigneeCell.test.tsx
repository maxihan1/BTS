// 이슈 목록 담당자 셀 편집부 테스트 — 검색·선택·해제·로딩/빈 상태·권한·Enter (FR-UX-11 F9 FR5·FR10·E6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AssigneeCell, AssigneeCellEditor } from './AssigneeCell'
import type { UserSummary } from '@/api/users'
import type { IssueResponse } from '@/api/issues'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { issueDetailStrings } from '@/i18n/ko'

// 권한 조회를 UPDATE:true 로 고정한다 — 그래야 **필드 단위** 가부만 분별할 수 있다.
// 단위 테스트에는 인증 토큰이 없어 실제 조회는 401 이 되고, 그러면 이슈 단위 권한에
// 가려져 필드 권한 가드가 공허해진다.
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

// 사용자 검색을 고정한다. 단위 테스트에는 인증 토큰이 없어 MSW 실경로가 빈 결과를 주고,
// 그러면 "후보를 고른다" 자체가 성립하지 않는다. 검색 플러밍은 AssigneeCellEditor 테스트가
// 따로 덮으므로 여기서는 **표시 동작**만 잰다.
vi.mock('@/hooks/use-users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-users')>()
  return {
    ...actual,
    useUsers: () => ({
      data: [
        {
          id: '11111111-1111-1111-1111-111111111111',
          username: 'maxi',
          displayName: '맥시',
          email: null,
        },
      ],
      isLoading: false,
    }),
  }
})

/**
 * 후보 1명 픽스처.
 *
 * `as UserSummary` 캐스팅을 쓰지 않고 전 필드를 채운다 — 캐스팅은 스키마가 자라도 조용히
 * 통과해 mock drift 를 감춘다 (PR #46 교훈). 형제 `AssigneeUserList.test.tsx` 와 같은 관례다.
 */
const MAXI: UserSummary = {
  id: '11111111-1111-1111-1111-111111111111',
  username: 'maxi',
  displayName: '맥시',
  email: null,
}

const USERS: UserSummary[] = [MAXI]

describe('AssigneeCellEditor', () => {
  it('후보를 고르면 그 UUID 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '맥시' }))

    // ★표시 이름도 함께 넘긴다 — 고르는 순간 이름을 아는 곳은 여기뿐이라, 목록 이름 맵이
    // 따라올 때까지의 임시 표기에 쓴다 (리뷰 C3).
    expect(onChange).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111', '맥시')
  })

  it('담당자가 있으면 해제 버튼이 null 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value={MAXI.id} currentAssigneeName={MAXI.displayName} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '담당자 해제' }))

    expect(onChange).toHaveBeenCalledWith(null, null)
  })

  it('검색 결과가 없으면 빈 상태를 안내한다 (E6)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument()
  })

  it('검색 중에는 "결과 없음" 대신 진행 상태를 보인다 (디자인 리뷰 Pass 2)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={[]} isLoading canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 중…')).toBeInTheDocument()
    expect(screen.queryByText('검색 결과가 없습니다.')).not.toBeInTheDocument()
  })

  it('popover 맨 위에 현재 담당자를 보인다 (디자인 리뷰 Pass 1)', () => {
    render(
      <AssigneeCellEditor
        value={MAXI.id} currentAssigneeName={MAXI.displayName} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('cell-assignee-current')).toHaveTextContent('맥시')
  })

  it('권한이 없으면 검색창이 비활성이다 (FR10 fail-closed)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit={false} isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeDisabled()
  })

  it('Enter 로 폼이 제출되지 않도록 기본동작을 막는다 (FR-UX-09 F2 회귀 방지)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    const input = screen.getByRole('textbox', { name: '담당자 검색' })
    input.focus()
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C2 — 필드 단위 권한
//
// 이슈 단위 UPDATE 만 보면, 관리자가 `assigneeId` 를 편집 불가로 잠가도 목록에서는 그대로
// 고칠 수 있어 보인다(저장은 서버가 거절 → 사용자는 이유 모를 실패를 본다). 상세 화면
// `IssueMetaPanel.tsx:315` 가 이미 같은 판정을 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 열린 담당자 셀을 렌더한다 */
async function renderOpenedAssigneeCell(overrides: Partial<IssueResponse> = {}): Promise<void> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <AssigneeCell
        issue={{ ...issueAtlas1Fixture, ...overrides }}
        assigneeName={undefined}
        listQueryKey={['issues', 'ATLAS', 0, {}, null]}
      />
    </QueryClientProvider>,
  )
  await userEvent.click(
    screen.getByRole('button', { name: `${issueAtlas1Fixture.key} 담당자 변경` }),
  )
}

describe('AssigneeCell — 필드 단위 권한 (리뷰 C2)', () => {
  it('noneditableFields 에 assigneeId 가 있으면 UPDATE 권한이 있어도 검색창이 비활성이다', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: ['assigneeId'] })

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeDisabled()
    expect(screen.getByText('편집 권한이 없습니다.')).toBeInTheDocument()
  })

  it('noneditableFields 가 비어 있으면 검색창이 활성이다 (비-공허 짝)', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: [] })

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeEnabled()
  })

  it('다른 필드가 잠겨 있어도 assigneeId 는 영향받지 않는다 (필드 키 정확도)', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: ['priority', 'labels'] })

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeEnabled()
  })

  it('담당자 해제 버튼도 필드 권한을 따른다', async () => {
    await renderOpenedAssigneeCell({
      assigneeId: '11111111-1111-1111-1111-111111111111',
      noneditableFields: ['assigneeId'],
    })

    expect(
      screen.getByRole('button', { name: issueDetailStrings.assigneeUnassignButton }),
    ).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C3 — 낙관 갱신 중간 상태
//
// 목록 이름 맵(`useUsersByIds`)은 조회 결과라 새 담당자 이름이 **한 왕복 뒤**에 온다.
// 그 사이 방금 고친 셀이 '미배정' 으로 보이면 낙관적 반영(FR11·S1)이 깨져 보인다.
// e2e 는 auto-retry 때문에 이 창을 못 잰다 — **중간 상태를 직접 재는** 유닛이 유일한 증인이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('AssigneeCell — 낙관 갱신 중간 표기 (리뷰 C3)', () => {
  /**
   * 이름 맵이 아직 새 담당자를 모르는 상태(`assigneeName=undefined`)를 재현한다.
   * 부모가 낙관적 patch 로 `issue.assigneeId` 만 먼저 바꾼 그 순간이다.
   */
  function renderAfterOptimisticPatch(): void {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <AssigneeCell
          issue={{ ...issueAtlas1Fixture, assigneeId: MAXI.id }}
          // ★맵은 아직 모른다 — 조회가 끝나기 전이다
          assigneeName={undefined}
          listQueryKey={['issues', 'ATLAS', 0, {}, null]}
        />
      </QueryClientProvider>,
    )
  }

  it('맵이 아직 이름을 모르면 방금 고른 이름을 보인다 — 미배정으로 깜빡이지 않는다', async () => {
    renderAfterOptimisticPatch()

    await userEvent.click(
      screen.getByRole('button', { name: `${issueAtlas1Fixture.key} 담당자 변경` }),
    )
    await userEvent.click(await screen.findByRole('button', { name: '맥시' }))

    const trigger = screen.getByRole('button', { name: `${issueAtlas1Fixture.key} 담당자 변경` })
    expect(trigger).toHaveTextContent('맥시')
    expect(trigger).not.toHaveTextContent('미배정')
  })

  it('맵이 이름을 알면 맵이 정본이다 — 임시 표기가 이기지 않는다', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <AssigneeCell
          issue={{ ...issueAtlas1Fixture, assigneeId: MAXI.id }}
          assigneeName="맵이 해석한 이름"
          listQueryKey={['issues', 'ATLAS', 0, {}, null]}
        />
      </QueryClientProvider>,
    )

    expect(
      screen.getByRole('button', { name: `${issueAtlas1Fixture.key} 담당자 변경` }),
    ).toHaveTextContent('맵이 해석한 이름')
  })
})
