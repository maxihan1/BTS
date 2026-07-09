// CalendarFeedCard 컴포넌트 테스트 — 미발급 발급버튼·1회노출+복사+경고·재발급확인·취소 (FR-CA-02 Task 9)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { CalendarFeedCard } from './CalendarFeedCard'

// ─────────────────────────────────────────────────────────────────────────────
// 상태 저장 MSW 핸들러 — 발급(rotate)/조회/취소 흐름을 파일 내부 store로 시뮬레이션
// (settings.pats.test.tsx 선례 — 재사용 가능한 mocks/calendar-feed-handlers.ts와는 별개로
//  이 테스트 파일이 독립적으로 검증 가능하도록 인라인 핸들러를 둔다)
// ─────────────────────────────────────────────────────────────────────────────

interface FeedFixture {
  token: string
  createdAt: string
}

let feedState: FeedFixture | null = null
let tokenSeq = 0

function resetFeedStore(): void {
  feedState = null
  tokenSeq = 0
}

function seedIssuedFeed(): void {
  feedState = { token: 'existing-token-0', createdAt: '2026-07-01T00:00:00Z' }
}

function calendarFeedTestHandlers() {
  return [
    http.get('/api/v1/users/me/calendar/feed', () => {
      if (feedState === null) return HttpResponse.json({ enabled: false })
      return HttpResponse.json({ enabled: true, createdAt: feedState.createdAt })
    }),
    http.post('/api/v1/users/me/calendar/feed', () => {
      tokenSeq += 1
      const token = `issued-token-${tokenSeq}`
      const createdAt = '2026-07-09T09:00:00Z'
      feedState = { token, createdAt }
      return HttpResponse.json(
        { feedUrl: `https://bts.local/ical/feed/${token}.ics`, token, createdAt },
        { status: 201 },
      )
    }),
    http.delete('/api/v1/users/me/calendar/feed', () => {
      feedState = null
      return new HttpResponse(null, { status: 204 })
    }),
  ]
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderCard(): { user: ReturnType<typeof userEvent.setup> } & ReturnType<typeof render> {
  const user = userEvent.setup({ delay: null })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <CalendarFeedCard />
    </QueryClientProvider>,
  )
  return { user, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증·MSW 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami(),
  })
  document.cookie = 'XSRF-TOKEN=test-calendar-feed-xsrf; path=/'
  resetFeedStore()
  server.use(...calendarFeedTestHandlers())
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  server.resetHandlers()
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 미발급 — 발급 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarFeedCard — 미발급 상태', () => {
  it('미발급 상태면 "구독 URL 발급" 버튼이 렌더된다', async () => {
    renderCard()
    expect(await screen.findByRole('button', { name: '구독 URL 발급' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 발급 — URL 1회 표시 + 복사 버튼 + 경고 문구
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarFeedCard — 발급 + 1회 노출', () => {
  it('발급 클릭 시 URL이 1회 표시되고 복사 버튼 + "다시 표시되지 않습니다" 경고가 함께 노출된다', async () => {
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '구독 URL 발급' }))

    expect(await screen.findByText(/다시 표시되지 않습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '복사' })).toBeInTheDocument()
    expect(screen.getByText(/ical\/feed\/issued-token-1\.ics/)).toBeInTheDocument()
  })

  it('발급된 URL 노출 화면에 webcal:// 구독 힌트가 함께 표시된다 (spec FR7)', async () => {
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '구독 URL 발급' }))

    await screen.findByText(/ical\/feed\/issued-token-1\.ics/)
    expect(screen.getByText(/webcal:\/\//)).toBeInTheDocument()
  })

  it('복사 버튼 클릭 시 clipboard.writeText가 발급 URL로 호출되고 라벨이 "복사됨"으로 바뀐다', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      writable: true,
      configurable: true,
    })
    const writeTextSpy = vi.spyOn(navigator.clipboard, 'writeText')

    const { user } = renderCard()
    await user.click(await screen.findByRole('button', { name: '구독 URL 발급' }))
    await user.click(await screen.findByRole('button', { name: '복사' }))

    expect(writeTextSpy).toHaveBeenCalledWith('https://bts.local/ical/feed/issued-token-1.ics')
    expect(await screen.findByRole('button', { name: '복사됨' })).toBeInTheDocument()
  })

  it('닫기 클릭 시 노출된 URL 텍스트가 화면에서 사라진다', async () => {
    const { user } = renderCard()
    await user.click(await screen.findByRole('button', { name: '구독 URL 발급' }))
    await screen.findByText(/ical\/feed\/issued-token-1\.ics/)

    await user.click(screen.getByRole('button', { name: '닫기' }))

    await waitFor(() => {
      expect(screen.queryByText(/ical\/feed\/issued-token-1\.ics/)).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 재발급 — 인라인 확인(기존 URL 무효 경고)
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarFeedCard — 재발급 확인', () => {
  it('이미 발급된 상태에서 "재발급" 클릭 시 기존 URL 무효 경고가 표시된다', async () => {
    seedIssuedFeed()
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '재발급' }))

    expect(await screen.findByText(/무효/)).toBeInTheDocument()
  })

  it('재발급 확인 클릭 시 새 URL이 1회 표시된다', async () => {
    seedIssuedFeed()
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '재발급' }))
    await user.click(await screen.findByRole('button', { name: '확인' }))

    expect(await screen.findByText(/ical\/feed\/issued-token-1\.ics/)).toBeInTheDocument()
  })

  it('재발급 확인 화면에서 "취소" 클릭 시 확인 단계가 닫히고 재발급 버튼으로 돌아간다', async () => {
    seedIssuedFeed()
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '재발급' }))
    await user.click(await screen.findByRole('button', { name: '취소' }))

    expect(await screen.findByRole('button', { name: '재발급' })).toBeInTheDocument()
    expect(screen.queryByText(/무효/)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 취소 — 구독 취소
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarFeedCard — 구독 취소', () => {
  it('구독 취소 확인 시 미발급 상태(발급 버튼)로 돌아간다', async () => {
    seedIssuedFeed()
    const { user } = renderCard()

    await user.click(await screen.findByRole('button', { name: '구독 취소' }))
    await user.click(await screen.findByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '구독 URL 발급' })).toBeInTheDocument()
    })
  })
})
