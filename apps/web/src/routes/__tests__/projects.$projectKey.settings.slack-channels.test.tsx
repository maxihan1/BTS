// Slack 채널 매핑 설정 페이지 라우트 단위 테스트 — RouteAdapter projectKey 전달·조립(List+FormDialog) (FR-SL-06 D6 Task 5)
import { server } from '@/test/server'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  slackChannelMappingHandlers,
  resetSlackChannelMappingStore,
  seedSlackChannelMappings,
} from '@/mocks/slack-channel-mapping-handlers'
import type { ChannelMapping } from '@/api/slack'
import {
  ProjectSlackChannelSettingsPage,
  ProjectSlackChannelSettingsRouteAdapter,
} from '@/routes/projects.$projectKey.settings.slack-channels'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router useParams mock — RouteAdapter 단위 테스트용 (automation.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

const mockUseParams = vi.fn<() => { projectKey?: string }>(() => ({ projectKey: PROJECT_KEY }))

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockUseParams(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — 이 라우트 테스트는 List+FormDialog 조립 통합 검증이므로
// (컴포넌트 단위 테스트와 달리 vi.mock('@/api/slack') 비의존) 실제 api/slack.ts 함수 +
// MSW 핸들러를 함께 사용한다. 전역 handlers.ts 등록과 별도로 로컬 서버로 격리한다
// (automation.test.tsx 동형).
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...slackChannelMappingHandlers)
})
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
  mockUseParams.mockReturnValue({ projectKey: PROJECT_KEY })
})
afterEach(() => {
  resetSlackChannelMappingStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

const MAPPING: ChannelMapping = {
  id: '11111111-1111-4111-8111-111111111111',
  projectKey: PROJECT_KEY,
  channelId: 'C0123456',
  channelName: 'general',
  eventTypes: ['issue.created'],
  createdAt: '2026-07-10T00:00:00Z',
  updatedAt: '2026-07-10T00:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(projectKey = PROJECT_KEY) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const user = userEvent.setup({ delay: null })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ProjectSlackChannelSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
  return { user, ...utils }
}

function renderAdapter() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectSlackChannelSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter — projectKey 전달
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSlackChannelSettingsRouteAdapter', () => {
  it('useParams의 $projectKey를 Page에 전달해 페이지 헤더가 렌더된다', async () => {
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Slack 채널' })).toBeInTheDocument()
    })
  })

  it('projectKey가 없으면 ProjectNotFoundScreen이 렌더된다', async () => {
    mockUseParams.mockReturnValue({ projectKey: undefined })
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument()
    })
    expect(screen.queryByRole('heading', { name: 'Slack 채널' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — projectKey 없음
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSlackChannelSettingsPage — projectKey 없음', () => {
  it('projectKey가 빈 문자열이면 ProjectNotFoundScreen이 렌더된다', () => {
    renderPage('')

    expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Slack 채널' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 조립 렌더 (List)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSlackChannelSettingsPage — 조립 렌더', () => {
  it('페이지 헤더와 SlackChannelMappingList가 렌더된다', async () => {
    seedSlackChannelMappings([MAPPING])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('general')).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'Slack 채널' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 채널 추가/수정 다이얼로그 조립
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSlackChannelSettingsPage — 채널 추가/수정 다이얼로그 조립', () => {
  it('"채널 추가" 클릭 시 생성 모드 FormDialog가 열린다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Slack 채널' })

    await user.click(screen.getByTestId('slack-channel-mapping-add-button'))

    expect(await screen.findByRole('heading', { name: '채널 매핑 추가' })).toBeInTheDocument()
  })

  it('행 "수정" 클릭 시 수정 모드 FormDialog가 열린다', async () => {
    seedSlackChannelMappings([MAPPING])
    const { user } = renderPage()
    await screen.findByText('general')

    await user.click(screen.getByTestId(`slack-channel-mapping-edit-${MAPPING.id}`))

    expect(await screen.findByRole('heading', { name: '채널 매핑 수정' })).toBeInTheDocument()
  })

  it('"취소" 클릭 시 FormDialog가 닫힌다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: 'Slack 채널' })

    await user.click(screen.getByTestId('slack-channel-mapping-add-button'))
    await screen.findByRole('heading', { name: '채널 매핑 추가' })

    await user.click(screen.getByRole('button', { name: '취소' }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: '채널 매핑 추가' })).not.toBeInTheDocument()
    })
  })
})
