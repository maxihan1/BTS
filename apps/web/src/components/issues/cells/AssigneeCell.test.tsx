// 이슈 목록 담당자 셀 편집부 테스트 — 검색·선택·해제·로딩/빈 상태·권한·Enter (FR-UX-11 F9 FR5·FR10·E6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AssigneeCell, AssigneeCellDisplay, AssigneeCellEditor } from './AssigneeCell'
import type { UserSummary } from '@/api/users'
import type { IssueResponse } from '@/api/issues'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { issueDetailStrings } from '@/i18n/ko'
import { ISSUE_COLUMNS } from '../issue-columns'

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
    screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
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
// 재리뷰 — 열람 숨김(restrictedFields)
//
// `isFieldDisabled` 만으로는 **못 막는다.** 백엔드 `buildNoneditableKeys` 가
// `.filter { key -> key !in restrictedSet }` 로 두 목록을 **배타적**으로 만들어, 열람 숨김
// 키는 `noneditableFields` 에 절대 안 들어온다 ⇒ `isFieldDisabled('assigneeId', true, [])`
// 는 false ⇒ 셀이 완전히 열린다. 그래서 `isFieldHidden` 을 **짝으로** 봐야 한다.
//
// 게다가 백엔드는 열람 불가 `assigneeId` 를 **null 로 마스킹**하므로, 그냥 그리면
// 담당자가 있는데 "미배정" 이라고 말하는 **거짓 표시**가 된다.
// ─────────────────────────────────────────────────────────────────────────────

/** 편집 컨텍스트를 가진 담당자 셀을 렌더한다(열지 않는다) */
function renderAssigneeCellWith(overrides: Partial<IssueResponse> = {}): void {
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
}

describe('AssigneeCell — 열람 숨김 (재리뷰)', () => {
  it('restrictedFields 에 assigneeId 가 있으면 편집 트리거를 아예 걸지 않는다', () => {
    renderAssigneeCellWith({ restrictedFields: ['assigneeId'] })

    // 클릭할 것 자체가 없어야 한다 — 눌러서야 못 한다는 걸 알게 되면 안 된다
    expect(
      screen.queryByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
    ).not.toBeInTheDocument()
  })

  it('restrictedFields 가 비어 있으면 정상 편집된다 (비-공허 짝)', () => {
    // 이 짝이 없으면 위 단언은 "항상 잠근다" 와 구분되지 않아 공허해진다
    renderAssigneeCellWith({ restrictedFields: [] })

    expect(
      screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
    ).toBeInTheDocument()
  })

  it('열람 숨김이면 "미배정" 이라는 거짓 표시를 하지 않는다', () => {
    // ★핵심. 백엔드가 assigneeId 를 null 로 마스킹하므로 그대로 그리면
    // 담당자가 **있는데 없다고** 말하게 된다.
    renderAssigneeCellWith({ restrictedFields: ['assigneeId'], assigneeId: null })

    expect(screen.queryByText('미배정')).not.toBeInTheDocument()
    expect(screen.getByTestId('cell-assignee-restricted')).toBeInTheDocument()
  })

  it('사유는 i18n 정본을 title 로 단다 — 표 열 폭을 넘기지 않으면서 이유를 남긴다', () => {
    renderAssigneeCellWith({ restrictedFields: ['assigneeId'] })

    expect(screen.getByTestId('cell-assignee-restricted')).toHaveAttribute(
      'title',
      issueDetailStrings.descriptionRestricted,
    )
  })

  it('다른 필드가 숨겨져 있어도 담당자는 영향받지 않는다 (필드 키 정확도)', () => {
    renderAssigneeCellWith({ restrictedFields: ['description', 'environment'] })

    expect(
      screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
    ).toBeInTheDocument()
  })
})

describe('AssigneeCellDisplay — 열람 숨김 (읽기 전용 경로)', () => {
  it('isRestricted 면 이름 대신 사유를 보인다', () => {
    render(<AssigneeCellDisplay assigneeName="bob" isRestricted />)

    expect(screen.queryByText('bob')).not.toBeInTheDocument()
    expect(screen.getByTestId('cell-assignee-restricted')).toBeInTheDocument()
  })

  it('isRestricted 가 아니면 기존대로 이름을 보인다 (비-공허 짝)', () => {
    render(<AssigneeCellDisplay assigneeName="bob" />)

    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.queryByTestId('cell-assignee-restricted')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 읽기 전용 경로(`issue-columns.ts` renderAssigneeCell)의 열람 숨김
//
// ★이 블록이 왜 있나 — 뮤테이션이 잡았다. `AssigneeCell` 만 잠그는 가드로는
// `issue-columns.ts` 의 `isRestricted` 전달을 지워도 **전부 초록**이었다. 편집이 꺼진
// 경로(`ctx.edit` 부재)는 `AssigneeCell` 을 거치지 않고 `AssigneeCellDisplay` 를 직접
// 그리므로 별도 증인이 필요하다. 컬럼 render 는 순수 함수라 여기서 직접 부를 수 있다.
// ─────────────────────────────────────────────────────────────────────────────

describe('renderAssigneeCell — 읽기 전용 경로 열람 숨김', () => {
  /** 담당자 컬럼의 render 를 편집 컨텍스트 **없이** 호출한 결과를 그린다 */
  function renderReadOnlyAssigneeColumn(overrides: Partial<IssueResponse> = {}): void {
    const column = ISSUE_COLUMNS.find((c) => c.key === 'assignee')
    if (column === undefined) throw new Error('assignee 컬럼이 없다 — 정의가 바뀌었다')

    render(
      <>
        {column.render(
          { ...issueAtlas1Fixture, ...overrides },
          { assigneeName: 'bob', formatDate: () => '', onNavigate: () => undefined },
        )}
      </>,
    )
  }

  it('열람 숨김이면 읽기 전용 경로도 이름을 그리지 않는다', () => {
    renderReadOnlyAssigneeColumn({ restrictedFields: ['assigneeId'] })

    expect(screen.queryByText('bob')).not.toBeInTheDocument()
    expect(screen.queryByText('미배정')).not.toBeInTheDocument()
    expect(screen.getByTestId('cell-assignee-restricted')).toBeInTheDocument()
  })

  it('열람 숨김이 아니면 기존대로 이름을 그린다 (비-공허 짝)', () => {
    renderReadOnlyAssigneeColumn({ restrictedFields: [] })

    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.queryByTestId('cell-assignee-restricted')).not.toBeInTheDocument()
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
      screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
    )
    // 후보는 검색 뒤에만 나온다(위 「후보는 검색 뒤에만 나온다」 절 참조).
    // 이 단계가 없으면 고를 후보가 없어 아래 클릭이 실패한다 — 계약이 바뀐 결과다.
    await userEvent.type(screen.getByRole('textbox', { name: '담당자 검색' }), '맥')
    await userEvent.click(await screen.findByRole('button', { name: '맥시' }, { timeout: 2000 }))

    const trigger = screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) })
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
      screen.getByRole('button', { name: new RegExp(`^${issueAtlas1Fixture.key} 담당자 변경`) }),
    ).toHaveTextContent('맵이 해석한 이름')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 후보는 검색해야 나온다
//
// `useUsers` 는 **빈 검색어에 전체 사용자 목록**을 돌려준다(훅 KDoc — 필터 계열 소비처가 그
// 동작에 의존하므로 훅에 전역 `enabled` 를 달 수 없다). 그대로 넘기면 목록에서 담당자 셀을
// 여는 순간 사내 1,000명이 펼쳐진다.
//
// 상세 화면은 2026-08-09(PR #352)에 같은 처방으로 닫혔다(`routes/issues.$key.tsx` 의
// `assigneeCandidates`). 이 셀은 FR-UX-11 F9(PR #338)로 **새로 생긴 표면**이라 그때 범위 밖이었다.
// ★같은 컨트롤이 화면마다 다르게 동작하면 사용자는 이유를 알 수 없다 — 판정 표현을 정본과
// **문자 그대로 동일**하게 둬서 두 곳이 같은 규칙임을 grep 으로 확인할 수 있게 한다.
//
// ★게이트는 **컨테이너**(`AssigneeCellPopoverBody`)에 둔다. `AssigneeCellEditor` 는 `users` 를
// props 로 받는 표시 전용이라 위 편집기 테스트들은 이 변경에 영향받지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('AssigneeCell — 후보는 검색 뒤에만 나온다', () => {
  it('검색어가 비어 있으면 후보를 하나도 보이지 않는다', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: [] })

    // `useUsers` 목은 검색어와 무관하게 후보 1건을 돌려준다 — 실제 훅과 같은 동작이다.
    // 그 후보가 화면에 나오면 게이트가 없는 것이다.
    expect(screen.queryByRole('button', { name: '맥시' })).not.toBeInTheDocument()
    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument()
  })

  it('검색어를 넣으면 후보가 나온다 (비-공허 짝 — 게이트가 항상 닫혀 있지 않다)', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: [] })

    await userEvent.type(screen.getByRole('textbox', { name: '담당자 검색' }), '맥')

    // 디바운스 250ms 를 넘겨 판정이 뒤집히기를 기다린다.
    await screen.findByRole('button', { name: '맥시' }, { timeout: 2000 })
  })

  it('공백만 입력하면 후보를 보이지 않는다 (trim 판정)', async () => {
    await renderOpenedAssigneeCell({ noneditableFields: [] })

    await userEvent.type(screen.getByRole('textbox', { name: '담당자 검색' }), '   ')

    await new Promise((r) => setTimeout(r, 400))
    expect(screen.queryByRole('button', { name: '맥시' })).not.toBeInTheDocument()
  })
})
