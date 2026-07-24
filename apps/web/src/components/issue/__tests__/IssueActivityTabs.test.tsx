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
  it('(a) role="tablist"와 탭 3개(작업로그/연결/이력)를 렌더한다', () => {
    renderTabs()

    expect(screen.getByRole('tablist')).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityWorklogTabLabel)).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityLinksTabLabel)).toBeInTheDocument()
    expect(getTab(issueDetailStrings.activityHistoryTabLabel)).toBeInTheDocument()
  })

  it('(b) 기본 활성 탭은 이력이다 — IssueChangelog만 초기 렌더되고 작업로그/연결은 미마운트', () => {
    renderTabs()

    expect(screen.getByTestId('mock-changelog')).toBeInTheDocument()
    expect(screen.queryByTestId('mock-worklog')).not.toBeInTheDocument()
    expect(screen.queryByTestId('mock-links')).not.toBeInTheDocument()
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
