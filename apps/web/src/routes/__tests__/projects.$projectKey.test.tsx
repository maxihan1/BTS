// 프로젝트 요약 라우트 페이지 단위 테스트 — 3상태 · 위젯 국소 에러 격리 (Jira 패리티 J4)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import type { ProjectActivity, ProjectSummary } from '@/api/project-summary'
import { ProjectSummaryPage } from '@/routes/projects.$projectKey'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router Link (라우터 없이 페이지만 검증한다)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    to,
    params,
  }: {
    children: React.ReactNode
    to: string
    params?: Record<string, string>
  }) => {
    const href = Object.entries(params ?? {}).reduce(
      (acc, [k, v]) => acc.replace(`$${k}`, v),
      to,
    )
    return <a href={href}>{children}</a>
  },
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const projectKey = 'ATLAS'

const summaryFixture: ProjectSummary = {
  projectKey,
  recent: {
    windowDays: 7,
    completed: { current: 12, previous: 8 },
    updated: { current: 34, previous: 34 },
    created: { current: 18, previous: 24 },
  },
  upcoming: { windowDays: 7, due: 5, overdue: 2 },
  statusOverview: [
    { statusKey: 'IN_PROGRESS', statusName: '진행 중', category: 'IN_PROGRESS', count: 9 },
    { statusKey: 'CUSTOM_ONLY_KEY', category: 'TODO', count: 3 },
  ],
  priorityBreakdown: [{ priority: 3, priorityName: '보통', count: 12 }],
  typesOfWork: [{ typeKey: 'story', typeName: '스토리', count: 22 }],
  teamWorkload: [
    { assigneeId: 'u-1', assigneeName: '성민제', count: 7 },
    { count: 4 },
  ],
}

const activityFixture: ProjectActivity = {
  entries: [
    {
      issueKey: 'ATLAS-401',
      actorId: 'u-1',
      actorName: '성민제',
      createdAt: '2026-09-03T10:00:00Z',
      items: [
        { field: 'status', fromValue: 'TODO', toValue: 'DONE', fromLabel: null, toLabel: '완료' },
      ],
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectSummaryPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

/** 요약·활동 두 엔드포인트를 각각의 상태로 세운다 */
function useHandlers(options: {
  summary?: { status: number } | ProjectSummary
  activity?: { status: number } | ProjectActivity
}): void {
  const { summary = summaryFixture, activity = activityFixture } = options
  server.use(
    http.get(`/api/v1/projects/${projectKey}`, () =>
      HttpResponse.json({
        // id 는 projectSchema 가 z.string().uuid() 로 강제한다 — 임의 문자열이면 파싱이 죽고
        // 화면은 조용히 projectKey 폴백을 보여준다
        data: {
          id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
          key: projectKey,
          name: 'Atlas 프로젝트',
          archived: false,
        },
      }),
    ),
    http.get(`/api/v1/projects/${projectKey}/summary`, () =>
      'status' in summary
        ? HttpResponse.json({ message: 'nope' }, { status: summary.status })
        : HttpResponse.json({ data: summary }),
    ),
    http.get(`/api/v1/projects/${projectKey}/activity`, () =>
      'status' in activity
        ? HttpResponse.json({ message: 'nope' }, { status: activity.status })
        : HttpResponse.json({ data: activity }),
    ),
  )
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'token', user: makeWhoami() })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 성공 경로
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSummaryPage — 성공', () => {
  it('h1 이 정확히 하나이고 프로젝트 이름을 싣는다', async () => {
    useHandlers({})
    renderPage()

    // 이름이 도착할 때까지 기다린다 — 도착 전에는 projectKey 폴백이 h1 에 들어 있다
    expect(await screen.findByText('Atlas 프로젝트')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('카드 4종이 값과 델타 문구를 함께 보여준다', async () => {
    useHandlers({})
    renderPage()

    const completed = await screen.findByLabelText(labels.cards.completed)
    expect(within(completed).getByText('12')).toBeInTheDocument()
    expect(within(completed).getByText(/\+4/)).toBeInTheDocument()

    const updated = screen.getByLabelText(labels.cards.updated)
    expect(within(updated).getByText(labels.delta.flat)).toBeInTheDocument()

    const created = screen.getByLabelText(labels.cards.created)
    expect(within(created).getByText(/-6/)).toBeInTheDocument()

    const due = screen.getByLabelText(labels.cards.due)
    expect(within(due).getByText(`2${labels.delta.overdueSuffix}`)).toBeInTheDocument()
  })

  it('분포 위젯 4종이 모두 렌더된다', async () => {
    useHandlers({})
    renderPage()

    expect(await screen.findByLabelText(labels.distribution.statusOverview)).toBeInTheDocument()
    expect(screen.getByLabelText(labels.distribution.priority)).toBeInTheDocument()
    expect(screen.getByLabelText(labels.distribution.typesOfWork)).toBeInTheDocument()
    expect(screen.getByLabelText(labels.distribution.teamWorkload)).toBeInTheDocument()
  })

  it('상태 개요가 DONE 2주 특례를 각주로 밝힌다', async () => {
    useHandlers({})
    renderPage()

    const widget = await screen.findByLabelText(labels.distribution.statusOverview)
    expect(
      within(widget).getByText(labels.distribution.statusOverviewNote),
    ).toBeInTheDocument()
  })

  it('표시명이 없는 상태는 상태 키로 폴백한다 — 빈칸을 보여주지 않는다', async () => {
    useHandlers({})
    renderPage()

    const widget = await screen.findByLabelText(labels.distribution.statusOverview)
    expect(within(widget).getByText('CUSTOM_ONLY_KEY')).toBeInTheDocument()
  })

  it('담당자 미할당은 「미할당」으로 묶어 표시한다', async () => {
    useHandlers({})
    renderPage()

    const widget = await screen.findByLabelText(labels.distribution.teamWorkload)
    expect(within(widget).getByText(labels.distribution.unassigned)).toBeInTheDocument()
  })

  it('활동 피드가 이슈 키와 행위자를 보여준다', async () => {
    useHandlers({})
    renderPage()

    const feed = await screen.findByLabelText(labels.activity.title)
    expect(await within(feed).findByRole('link', { name: 'ATLAS-401' })).toBeInTheDocument()
    expect(within(feed).getByText(/성민제/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 위젯 국소 에러 격리 — 이 PR 의 핵심 봉인
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSummaryPage — 위젯 국소 에러 격리', () => {
  it('활동이 500 이어도 카드와 분포는 그대로 보인다', async () => {
    useHandlers({ activity: { status: 500 } })
    renderPage()

    expect(await screen.findByLabelText(labels.cards.completed)).toBeInTheDocument()
    expect(screen.getByLabelText(labels.distribution.statusOverview)).toBeInTheDocument()

    const feed = await screen.findByLabelText(labels.activity.title)
    expect(await within(feed).findByText(labels.status.activityFailed)).toBeInTheDocument()
  })

  it('요약이 500 이어도 활동 피드는 그대로 보인다', async () => {
    useHandlers({ summary: { status: 500 } })
    renderPage()

    const feed = await screen.findByLabelText(labels.activity.title)
    expect(await within(feed).findByRole('link', { name: 'ATLAS-401' })).toBeInTheDocument()
    expect(screen.getByText(labels.status.loadFailed)).toBeInTheDocument()
  })

  it('요약 403 은 권한 문구를, 500 은 일반 실패 문구를 낸다 — 둘을 섞지 않는다', async () => {
    useHandlers({ summary: { status: 403 } })
    renderPage()

    expect(await screen.findByText(labels.status.forbidden)).toBeInTheDocument()
    expect(screen.queryByText(labels.status.loadFailed)).not.toBeInTheDocument()
  })

  it('요약이 실패해도 헤더의 이동 링크는 남는다 — 착지 화면이 막다른 길이 되지 않는다', async () => {
    useHandlers({ summary: { status: 500 } })
    renderPage()

    expect(await screen.findByRole('link', { name: labels.page.goToBoard })).toHaveAttribute(
      'href',
      `/projects/${projectKey}/board`,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSummaryPage — 빈 상태', () => {
  it('분포가 전부 비면 안내를 내되 카드는 0 으로 남긴다 — 0 은 정보다', async () => {
    useHandlers({
      summary: {
        projectKey,
        recent: {
          windowDays: 7,
          completed: { current: 0, previous: 0 },
          updated: { current: 0, previous: 0 },
          created: { current: 0, previous: 0 },
        },
        upcoming: { windowDays: 7, due: 0, overdue: 0 },
        statusOverview: [],
        priorityBreakdown: [],
        typesOfWork: [],
        teamWorkload: [],
      },
      activity: { entries: [] },
    })
    renderPage()

    const completed = await screen.findByLabelText(labels.cards.completed)
    expect(within(completed).getByText('0')).toBeInTheDocument()
    expect(screen.getByText(labels.status.empty)).toBeInTheDocument()
    expect(screen.queryByLabelText(labels.distribution.priority)).not.toBeInTheDocument()
  })

  it('활동 항목이 없으면 빈 안내를 낸다', async () => {
    useHandlers({ activity: { entries: [] } })
    renderPage()

    const feed = await screen.findByLabelText(labels.activity.title)
    expect(await within(feed).findByText(labels.activity.empty)).toBeInTheDocument()
  })
})
