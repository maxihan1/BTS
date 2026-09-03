// IssueActivityTabs 컴포넌트 단위 테스트 — 활동 3탭(작업로그/연결/이력) Radix Tabs 배선 검증 (FR-UX-06 PR19 Task 1)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ChangelogRefs } from '@/lib/changelog-labels'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 하위 섹션 컴포넌트 스텁 — 이 테스트는 "어느 탭에 어느 섹션이 배선되는지"만 검증한다.
// 각 섹션 자체 동작(데이터 페칭·CRUD)은 WorklogSection.test.tsx 등 자체 테스트파일이 담당(G2).
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/issue/WorklogSection', () => ({
  WorklogSection: ({ issueKey, canUpdate }: { issueKey: string; canUpdate: boolean }) => (
    <div data-testid="mock-worklog">{`worklog:${issueKey}:${String(canUpdate)}`}</div>
  ),
}))

vi.mock('@/components/issue/CommentSection', () => ({
  CommentSection: ({ issueKey, canUpdate }: { issueKey: string; canUpdate: boolean }) => (
    <div data-testid="mock-comment">{`comment:${issueKey}:${String(canUpdate)}`}</div>
  ),
}))

vi.mock('@/components/issue/IssueLinksPanel', () => ({
  IssueLinksPanel: (props: {
    issueKey: string
    parent: { key: string; summary: string } | null
    epic: { key: string; summary: string } | null
    showEpicSection: boolean
    disabled: boolean
  }) => (
    <div data-testid="mock-links">
      {`links:${props.issueKey}:${String(props.showEpicSection)}:${String(props.disabled)}`}
    </div>
  ),
}))

vi.mock('@/components/issue/EpicChildrenSection', () => ({
  EpicChildrenSection: ({ epicKey, disabled }: { epicKey: string; disabled: boolean }) => (
    <div data-testid="mock-epic-children">{`epic:${epicKey}:${String(disabled)}`}</div>
  ),
}))

vi.mock('@/components/issue/LinkGraph', () => ({
  LinkGraph: ({ issueKey }: { issueKey: string }) => (
    <div data-testid="mock-link-graph">{`graph:${issueKey}`}</div>
  ),
}))

vi.mock('@/components/issue/IssueChangelog', () => ({
  IssueChangelog: ({ issueKey }: { issueKey: string }) => (
    <div data-testid="mock-changelog">{`changelog:${issueKey}`}</div>
  ),
}))

import { IssueActivityTabs } from '@/components/issue/IssueActivityTabs'

/** IssueChangelog refs 인자 — 스텁 렌더만 검증하므로 빈 참조로 충분 */
const emptyChangelogRefs: ChangelogRefs = {
  types: [],
  components: [],
  versions: [],
  priorityMap: {},
  impactMap: {},
  customFieldDefinitions: [],
}

function renderTabs(issueOverrides: Partial<typeof issueAtlas1Fixture> = {}) {
  return render(
    <IssueActivityTabs
      issueKey="ATLAS-1"
      canUpdate={true}
      issue={{ ...issueAtlas1Fixture, ...issueOverrides }}
      changelogRefs={emptyChangelogRefs}
    />,
  )
}

/** 탭 트리거를 접근성 이름(한글 라벨)으로 조회한다 — 반복되는 getByRole 호출 축약 */
function getTab(name: string) {
  return screen.getByRole('tab', { name })
}

describe('IssueActivityTabs — 활동 3탭 Radix Tabs 배선 (Task 1)', () => {
  it('(a) role="tablist"와 탭 4개(작업로그/연결/이력/댓글)를 렌더한다', () => {
    renderTabs()

    expect(screen.getByRole('tablist')).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityWorklogTabLabel)).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityLinksTabLabel)).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityHistoryTabLabel)).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityCommentTabLabel)).toBeInTheDocument()

    // ★개수를 단정한다 — 탭이 조용히 늘거나 줄면 여기서 걸린다 (열거 눈가리개 방지)
    expect(screen.getAllByRole('tab')).toHaveLength(4)
  })

  it('(b) 기본 활성 탭은 댓글이다 — CommentSection만 초기 렌더되고 이력/작업로그/연결은 미마운트', () => {
    // ★2026-09-04 Jira 패리티로 「이력」에서 번복했다 — "By default the activity feed shows
    //   comments"(J3). 근거와 경위는 IssueActivityTabs KDoc 에 남아 있다.
    renderTabs()

    expect(screen.getByTestId('mock-comment')).toBeInTheDocument()
    expect(screen.queryByTestId('mock-changelog')).not.toBeInTheDocument()
    expect(screen.queryByTestId('mock-worklog')).not.toBeInTheDocument()
    // ★FR-CO-01 리뷰 G6 — 탭이 늘 때 이 열거를 함께 늘리지 않으면 테스트가 초록인 채로
    // 새 섹션의 초기 마운트 여부를 보지 못한다. 목록이 곧 눈가리개다.
    expect(screen.queryByTestId('mock-links')).not.toBeInTheDocument()
  })

  it('(b-2) 댓글 탭 클릭 시 CommentSection이 보이고 canUpdate가 전달된다', async () => {
    const user = userEvent.setup()
    renderTabs()

    await user.click(getTab(issueDetailStrings.activityCommentTabLabel))

    expect(screen.getByTestId('mock-comment')).toHaveTextContent('comment:ATLAS-1:true')
    expect(screen.queryByTestId('mock-changelog')).not.toBeInTheDocument()
  })

  it('(c) 작업로그 탭 클릭 시 WorklogSection이 보이고 이력은 숨는다', async () => {
    const user = userEvent.setup()
    renderTabs()

    await user.click(getTab(issueDetailStrings.activityWorklogTabLabel))

    expect(screen.getByTestId('mock-worklog')).toBeInTheDocument()
    expect(screen.queryByTestId('mock-changelog')).not.toBeInTheDocument()
  })

  it('(d) 연결 탭 클릭 시 IssueLinksPanel과 LinkGraph가 보인다', async () => {
    const user = userEvent.setup()
    renderTabs()

    await user.click(getTab(issueDetailStrings.activityLinksTabLabel))

    expect(screen.getByTestId('mock-links')).toBeInTheDocument()
    expect(screen.getByTestId('mock-link-graph')).toBeInTheDocument()
  })

  it('(e) 에픽 타입이 아니면 연결 탭에 EpicChildrenSection이 렌더되지 않는다', async () => {
    const user = userEvent.setup()
    renderTabs({ typeKey: 'story' })

    await user.click(getTab(issueDetailStrings.activityLinksTabLabel))

    expect(screen.queryByTestId('mock-epic-children')).not.toBeInTheDocument()
  })

  it('(e) 에픽 타입이면 연결 탭에 EpicChildrenSection이 렌더된다', async () => {
    const user = userEvent.setup()
    renderTabs({ typeKey: 'epic' })

    await user.click(getTab(issueDetailStrings.activityLinksTabLabel))

    expect(screen.getByTestId('mock-epic-children')).toBeInTheDocument()
  })
})
