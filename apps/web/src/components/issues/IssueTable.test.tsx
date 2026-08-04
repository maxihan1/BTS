// IssueTable 컴포넌트 단위 테스트 — 시맨틱·e2e 셀렉터 보존·네비게이션·정렬 헤더·컬럼 표시 (FR-UX-06 Phase 5 PR18 Task 4)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { issueAtlas1Fixture, issueAtlas2Fixture, ISSUE_FILTER_BOB_ID } from '@/mocks/issue-fixtures'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueTable } from './IssueTable'
import type { IssueTableProps, IssueTableSelectionProps } from './IssueTable'
import type { IssueCellEditContext } from './issue-columns'

// ─────────────────────────────────────────────────────────────────────────────
// FR12 가드용 훅 스파이 (FR-UX-11 F9)
//
// ★조회 훅이 **popover 안에서만** 마운트되는지를 재는 유일한 증인이다. 네트워크 카운트가
// 아니라 훅 호출을 재는 이유 — 훅이 `PopoverContent` 밖으로 올라가면 그 순간 **렌더 중에
// 동기적으로** 호출되므로, 비동기 요청이 날아갈 때까지 기다릴 필요 없이 즉시 잡힌다.
// 원본 구현에 그대로 위임하므로 동작은 바뀌지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

const { transitionsSpy, permissionsSpy } = vi.hoisted(() => ({
  transitionsSpy: vi.fn(),
  permissionsSpy: vi.fn(),
}))

vi.mock('@/hooks/use-issue-transitions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-issue-transitions')>()
  return {
    ...actual,
    useIssueTransitions: (key: string) => {
      transitionsSpy(key)
      return actual.useIssueTransitions(key)
    },
  }
})

vi.mock('@/hooks/use-issue-permissions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-issue-permissions')>()
  return {
    ...actual,
    useIssuePermissions: (issueKey: string) => {
      permissionsSpy(issueKey)
      return actual.useIssuePermissions(issueKey)
    },
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 전체 컬럼 키 — issue-columns.ts ISSUE_COLUMNS와 동기화 */
const ALL_COLUMN_KEYS = ['key', 'summary', 'status', 'assignee', 'priority', 'updatedAt']

/** 셀 인라인 편집 컨텍스트 — 목록 queryKey는 issues.index.tsx의 useQuery 키와 같은 모양이다 */
const EDIT_CONTEXT: IssueCellEditContext = {
  listQueryKey: ['issues', 'ATLAS', 0, {}, null],
  enabled: true,
}

function makeSelection(overrides: Partial<IssueTableSelectionProps> = {}): IssueTableSelectionProps {
  return {
    isSelected: () => false,
    onToggle: vi.fn(),
    onSelectAllPage: vi.fn(),
    isAllPageSelected: false,
    ...overrides,
  }
}

function renderTable(overrides: Partial<IssueTableProps> = {}): IssueTableProps {
  const props: IssueTableProps = {
    issues: [issueAtlas1Fixture, issueAtlas2Fixture],
    visibleColumnKeys: ALL_COLUMN_KEYS,
    sort: null,
    onSort: vi.fn(),
    selection: makeSelection(),
    onNavigate: vi.fn(),
    assigneeNameMap: new Map([[ISSUE_FILTER_BOB_ID, 'bob']]),
    ...overrides,
  }
  // 편집 셀은 mutation 훅(useMutation)을 쓰므로 QueryClient가 필요하다.
  // retry: false — 인증 없는 단위 테스트에서 401 재시도로 요청 수가 부풀지 않게 한다.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <IssueTable {...props} />
    </QueryClientProvider>,
  )
  return props
}

/**
 * 편집 트리거 로케이터 — 이슈 키 접두 **앵커 정규식**.
 *
 * 행이 여러 개라 키 접두가 고유성의 근거다. 접근성 이름에는 현재 값도 함께 실리므로
 * (`ATLAS-1 담당자 변경, 현재 bob` — 리뷰 C4) 완전일치로 잡으면 값이 바뀔 때마다
 * 로케이터가 죽는다. 동작 부분만 앵커로 고정한다.
 */
function editTriggerName(issueKey: string, field: '상태' | '우선순위' | '담당자'): RegExp {
  return new RegExp(`^${issueKey} ${field} 변경`)
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 + 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 렌더 + 접근성', () => {
  it('T-1: <table> 시맨틱으로 렌더되고 접근 가능한 이름을 가진다(W1)', () => {
    renderTable()
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★e2e 셀렉터 verbatim 보존 (G2)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — e2e 셀렉터 verbatim 보존', () => {
  it('T-2: 체크박스 — data-testid=select-{key} + aria-label="이슈 선택"', () => {
    renderTable()
    const checkbox = screen.getByTestId(`select-${issueAtlas1Fixture.key}`)
    expect(checkbox).toHaveAttribute('type', 'checkbox')
    expect(checkbox).toHaveAttribute('aria-label', '이슈 선택')
  })

  it('T-3: 요약 — data-testid=issue-summary-{key}', () => {
    renderTable()
    expect(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`)).toHaveTextContent(
      issueAtlas1Fixture.summary,
    )
  })

  it('T-4: 행 링크 — aria-label={key}, role=link, href=/issues/{key}', () => {
    renderTable()
    const link = screen.getByRole('link', { name: issueAtlas1Fixture.key })
    expect(link).toHaveAttribute('href', `/issues/${issueAtlas1Fixture.key}`)
  })

  it('T-5: 상태 배지 — role="status"', () => {
    renderTable()
    const statuses = screen.getAllByRole('status')
    const texts = statuses.map((el) => el.textContent)
    expect(texts).toContain(issueAtlas1Fixture.currentStateKey)
    expect(texts).toContain(issueAtlas2Fixture.currentStateKey)
  })

  it('T-6: 전체 선택 체크박스 — data-testid=select-all-page (기존 IssueListContent 계약 보존)', () => {
    renderTable()
    expect(screen.getByTestId('select-all-page')).toHaveAttribute('aria-label', '현재 페이지 전체 선택')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 네비게이션 — 행 클릭 vs 체크박스 클릭
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 네비게이션', () => {
  it('T-7: 요약 셀 클릭 → onNavigate(key) 호출', async () => {
    const onNavigate = vi.fn()
    renderTable({ onNavigate })
    const user = userEvent.setup()
    await user.click(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`))
    expect(onNavigate).toHaveBeenCalledWith(issueAtlas1Fixture.key)
  })

  it('T-8: 키 링크 클릭 → onNavigate(key)가 정확히 1회만 호출된다(행 클릭과 중복 방지)', async () => {
    const onNavigate = vi.fn()
    renderTable({ onNavigate })
    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: issueAtlas1Fixture.key }))
    expect(onNavigate).toHaveBeenCalledTimes(1)
    expect(onNavigate).toHaveBeenCalledWith(issueAtlas1Fixture.key)
  })

  it('T-9: 체크박스 클릭 → onToggle 호출 + onNavigate는 호출되지 않는다(전파 차단)', async () => {
    const onToggle = vi.fn()
    const onNavigate = vi.fn()
    renderTable({ onNavigate, selection: makeSelection({ onToggle }) })
    const user = userEvent.setup()
    await user.click(screen.getByTestId(`select-${issueAtlas1Fixture.key}`))
    expect(onToggle).toHaveBeenCalledWith(issueAtlas1Fixture.key)
    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('T-10: 전체 선택 체크박스 클릭 → onSelectAllPage 호출', async () => {
    const onSelectAllPage = vi.fn()
    renderTable({ selection: makeSelection({ onSelectAllPage }) })
    const user = userEvent.setup()
    await user.click(screen.getByTestId('select-all-page'))
    expect(onSelectAllPage).toHaveBeenCalledOnce()
  })

  it('T-11: isAllPageSelected=true이면 전체 선택 체크박스가 checked 상태로 렌더된다', () => {
    renderTable({ selection: makeSelection({ isAllPageSelected: true }) })
    expect(screen.getByTestId('select-all-page')).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 헤더 (GAP-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 정렬 헤더', () => {
  it('T-12: 정렬 가능 헤더("요약") 클릭 → onSort("summary") 호출', async () => {
    const onSort = vi.fn()
    renderTable({ onSort })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '요약' }))
    expect(onSort).toHaveBeenCalledWith('summary')
  })

  it('T-13: 활성 정렬(summary asc) → 해당 헤더에 aria-sort="ascending" + 방향 아이콘', () => {
    renderTable({ sort: { field: 'summary', dir: 'asc' } })
    const header = screen.getByRole('columnheader', { name: '요약' })
    expect(header).toHaveAttribute('aria-sort', 'ascending')
    expect(header.querySelector('svg')).not.toBeNull()
  })

  it('T-14: 활성 정렬(updatedAt desc) → 해당 헤더에 aria-sort="descending"', () => {
    renderTable({ sort: { field: 'updatedAt', dir: 'desc' } })
    const header = screen.getByRole('columnheader', { name: '수정일' })
    expect(header).toHaveAttribute('aria-sort', 'descending')
  })

  it('T-15: 비활성 정렬가능 컬럼("우선순위")은 aria-sort="none"', () => {
    renderTable({ sort: { field: 'summary', dir: 'asc' } })
    const header = screen.getByRole('columnheader', { name: '우선순위' })
    expect(header).toHaveAttribute('aria-sort', 'none')
  })

  it('T-16: 비정렬 컬럼("상태")은 aria-sort 속성 자체가 없다', () => {
    renderTable()
    const header = screen.getByRole('columnheader', { name: '상태' })
    expect(header).not.toHaveAttribute('aria-sort')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 표시 (visibleColumnKeys)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 컬럼 표시', () => {
  it('T-17: visibleColumnKeys에서 제외된 컬럼("담당자")은 렌더되지 않는다', () => {
    renderTable({ visibleColumnKeys: ['key', 'summary'] })
    expect(screen.queryByRole('columnheader', { name: '담당자' })).not.toBeInTheDocument()
  })

  it('T-18: 필수 컬럼(키·요약)은 visibleColumnKeys가 빈 배열이어도 항상 렌더된다', () => {
    renderTable({ visibleColumnKeys: [] })
    expect(screen.getByRole('columnheader', { name: '키' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '요약' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '상태' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 렌더 내용 — 담당자 해석·날짜 포맷
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 컬럼 렌더 내용', () => {
  it('T-19: 담당자 매핑 존재 시 이름, 미배정이면 "미배정"을 표시한다', () => {
    renderTable()
    expect(screen.getByText('미배정')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
  })

  it('T-20: 수정일 — null이면 "—", ISO 문자열이면 프리셋(iso) 형식으로 포맷한다', () => {
    renderTable()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText('2026-01-03')).toBeInTheDocument()
  })

  it('T-20b: 우선순위 — 한국어 라벨 정본을 쓴다(백엔드 영어 priorityName 미노출)', () => {
    // ★표기 정본은 issueDetailStrings.priorityNames 하나다 (Maxi 확정 2026-08-04).
    // fixture 2건 모두 priority=3 이라 '보통'이 2개다.
    renderTable()

    expect(screen.getAllByText(issueDetailStrings.priorityNames[3])).toHaveLength(2)
    expect(screen.queryByText(issueAtlas1Fixture.priorityName)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// split view 선택 강조 (selectedKey) — bulk selection과 독립 (FR-UX-06 Phase 5 PR20 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — selectedKey 강조(split view 현재 상세 행)', () => {
  it('T-21: selectedKey와 일치하는 행에 aria-current="true" + 선택 배경 강조가 붙고, 다른 행에는 붙지 않는다', () => {
    renderTable({ selectedKey: issueAtlas1Fixture.key })

    const currentRow = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(currentRow).not.toBeNull()
    expect(currentRow).toHaveAttribute('aria-current', 'true')
    expect(currentRow).toHaveAttribute('data-state', 'selected')

    const otherRow = screen.getByRole('link', { name: issueAtlas2Fixture.key }).closest('tr')
    expect(otherRow).not.toHaveAttribute('aria-current')
    expect(otherRow).not.toHaveAttribute('data-state', 'selected')
  })

  it('T-22: selectedKey 미지정 시 어떤 행에도 강조가 없고, 기존 e2e 셀렉터는 그대로 유지된다', () => {
    renderTable()

    const row1 = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    const row2 = screen.getByRole('link', { name: issueAtlas2Fixture.key }).closest('tr')
    expect(row1).not.toHaveAttribute('aria-current')
    expect(row2).not.toHaveAttribute('aria-current')

    expect(screen.getByTestId(`select-${issueAtlas1Fixture.key}`)).toHaveAttribute('aria-label', '이슈 선택')
    expect(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`)).toHaveTextContent(
      issueAtlas1Fixture.summary,
    )
    expect(screen.getByRole('link', { name: issueAtlas1Fixture.key })).toHaveAttribute(
      'href',
      `/issues/${issueAtlas1Fixture.key}`,
    )
  })

  it('T-23: selectedKey=null도 미지정과 동일하게 어떤 행에도 강조가 없다', () => {
    renderTable({ selectedKey: null })

    const row1 = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(row1).not.toHaveAttribute('aria-current')
    expect(row1).not.toHaveAttribute('data-state', 'selected')
  })

  it('T-24: selectedKey 강조는 bulk selection.isSelected와 독립 — 체크박스 미선택이어도 강조된다', () => {
    renderTable({
      selectedKey: issueAtlas1Fixture.key,
      selection: makeSelection({ isSelected: () => false }),
    })

    expect(screen.getByTestId(`select-${issueAtlas1Fixture.key}`)).not.toBeChecked()
    const row = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(row).toHaveAttribute('aria-current', 'true')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 셀 인라인 편집 배선 (FR-UX-11 F9) — 회귀 0 · FR9 즉사 계약 · FR12 지연 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 셀 인라인 편집 컨텍스트', () => {
  it('T-25: edit 컨텍스트가 없으면 기존 읽기 전용 셀을 렌더한다 (회귀 0)', () => {
    renderTable()

    // 상태 배지는 그대로 — role="status"는 §2 즉사 계약이다
    const statusTexts = screen.getAllByRole('status').map((el) => el.textContent)
    expect(statusTexts).toContain(issueAtlas1Fixture.currentStateKey)

    // 편집 트리거는 하나도 없다 — 3종 전부
    for (const field of ['상태', '우선순위', '담당자'] as const) {
      expect(
        screen.queryByRole('button', { name: editTriggerName(issueAtlas1Fixture.key, field) }),
      ).not.toBeInTheDocument()
    }
  })

  it('T-26: edit.enabled=false도 읽기 전용이다 (상위가 편집을 끌 수 있다)', () => {
    renderTable({ edit: { ...EDIT_CONTEXT, enabled: false } })

    expect(
      screen.queryByRole('button', { name: editTriggerName(issueAtlas1Fixture.key, '상태') }),
    ).not.toBeInTheDocument()
  })

  it('T-27: edit 컨텍스트가 있으면 3종 편집 트리거를 렌더하되 role="status"를 유지한다 (FR9)', () => {
    renderTable({ edit: EDIT_CONTEXT })

    // 트리거 접근성 이름은 이슈 키 접두 — 행이 여러 개라 고유해야 e2e strict mode를 통과한다
    for (const issue of [issueAtlas1Fixture, issueAtlas2Fixture]) {
      for (const field of ['상태', '우선순위', '담당자'] as const) {
        expect(
          screen.getByRole('button', { name: editTriggerName(issue.key, field) }),
        ).toBeInTheDocument()
      }
    }

    // ★편집 트리거로 감싸도 배지의 role="status"는 살아 있어야 한다 (§2 즉사 계약)
    const statusTexts = screen.getAllByRole('status').map((el) => el.textContent)
    expect(statusTexts).toContain(issueAtlas1Fixture.currentStateKey)
    expect(statusTexts).toContain(issueAtlas2Fixture.currentStateKey)

    // 표시값도 그대로 — 닫힌 셀은 읽기 전용 경로와 같은 텍스트를 낸다
    expect(screen.getByRole('button', { name: editTriggerName(issueAtlas2Fixture.key, '담당자') }))
      .toHaveTextContent('bob')
    expect(screen.getByRole('button', { name: editTriggerName(issueAtlas1Fixture.key, '담당자') }))
      .toHaveTextContent('미배정')
  })

  it('T-28: 편집 셀 클릭은 행 클릭(상세 이동)으로 전파되지 않는다 (FR3)', async () => {
    const onNavigate = vi.fn()
    renderTable({ edit: EDIT_CONTEXT, onNavigate })

    await userEvent.click(
      screen.getByRole('button', { name: editTriggerName(issueAtlas1Fixture.key, '우선순위') }),
    )

    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('T-29: popover가 닫혀 있는 동안 전이·권한 조회가 발생하지 않는다 (FR12·NFR1·D-3)', async () => {
    transitionsSpy.mockClear()
    permissionsSpy.mockClear()

    renderTable({ edit: EDIT_CONTEXT })

    // ★목록 초기 렌더에서 행 수만큼 조회가 터지는 N+1을 막는 유일한 증인이다.
    // 조회 훅을 EditableCell 밖으로 끌어올리면 여기서 즉시 빨강이 된다.
    expect(transitionsSpy).not.toHaveBeenCalled()
    expect(permissionsSpy).not.toHaveBeenCalled()

    // 비-공허 짝 — 실제로 열면 조회가 일어난다. 이 짝이 없으면 위 단언은
    // "조회 코드가 아예 없다"와 구분되지 않아 공허해진다.
    await userEvent.click(
      screen.getByRole('button', { name: editTriggerName(issueAtlas1Fixture.key, '상태') }),
    )

    await waitFor(() => {
      expect(transitionsSpy).toHaveBeenCalledWith(issueAtlas1Fixture.key)
      expect(permissionsSpy).toHaveBeenCalledWith(issueAtlas1Fixture.key)
    })
    // 열린 이슈 **하나만** 조회한다 — 다른 행 키로는 부르지 않는다
    expect(transitionsSpy).not.toHaveBeenCalledWith(issueAtlas2Fixture.key)
  })
})
