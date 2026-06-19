// 사용자 알림 구독 설정 매트릭스 컴포넌트 단위 테스트 — vitest + Testing Library + MSW
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import {
  userNotificationSubscriptionHandlers,
  resetUserNotificationSubscriptionStore,
} from '@/mocks/user-notification-subscription-handlers'
import { NotificationSubscriptionMatrix } from './NotificationSubscriptionMatrix'

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient 래퍼 — 재시도 없이 에러를 즉시 노출
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

function renderMatrix() {
  const Wrapper = createWrapper()
  render(<NotificationSubscriptionMatrix />, { wrapper: Wrapper })
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 등록 및 정리
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetUserNotificationSubscriptionStore()
  server.use(...userNotificationSubscriptionHandlers)
  // XSRF-TOKEN 쿠키 설정 — apiFetch가 X-XSRF-TOKEN 헤더로 재전송하는 double submit cookie 패턴
  // MSW CSRF 검사가 통과하지 않으면 mutation이 403으로 실패함 (CreateUserForm.test.tsx 선례)
  document.cookie = 'XSRF-TOKEN=test-xsrf; path=/'
})

afterEach(() => {
  server.resetHandlers()
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 매트릭스 렌더: 이벤트 행 × 채널 열
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationSubscriptionMatrix — S1 매트릭스 렌더', () => {
  it('응답 20셀에서 채널(IN_APP/EMAIL) 열 헤더가 표시된다', async () => {
    renderMatrix()

    await waitFor(() => {
      // 채널 라벨은 i18n channelLabels에서 옴 — 원문 fallback 아닌 한국어 라벨
      expect(screen.getByText('인앱 알림')).toBeInTheDocument()
      expect(screen.getByText('이메일')).toBeInTheDocument()
    })
  })

  it('응답 이벤트 행 라벨이 표시된다', async () => {
    renderMatrix()

    await waitFor(() => {
      expect(screen.getByText('이슈 생성')).toBeInTheDocument()
      expect(screen.getByText('이슈 담당자 지정')).toBeInTheDocument()
      expect(screen.getByText('이슈 상태 전이')).toBeInTheDocument()
    })
  })

  it('각 셀에 토글 컨트롤이 렌더된다 — 20개 이상', async () => {
    renderMatrix()

    await waitFor(() => {
      // aria-label에 이벤트×채널 컨텍스트 포함 — getByRole('checkbox') or button
      // 토글 수가 20개(10 eventType × 2 channel)
      const toggles = screen.getAllByRole('checkbox')
      expect(toggles.length).toBeGreaterThanOrEqual(20)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — data-driven 열 구성: 응답 채널 집합으로 열 결정
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationSubscriptionMatrix — S2 data-driven 채널 열', () => {
  it('응답에 IN_APP/EMAIL 2채널 존재 → 2개 채널 열 헤더가 렌더된다', async () => {
    renderMatrix()

    await waitFor(() => {
      // 채널 헤더 2개 확인
      const inAppHeader = screen.getByText('인앱 알림')
      const emailHeader = screen.getByText('이메일')
      expect(inAppHeader).toBeInTheDocument()
      expect(emailHeader).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 셀 토글 → mutation 호출 + 상태 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationSubscriptionMatrix — S3 셀 토글 mutation', () => {
  it('이슈 생성 × 인앱 알림 셀 토글 시 enabled 상태가 반전된다', async () => {
    renderMatrix()

    // 초기 로딩 대기
    await waitFor(() => {
      expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    })

    // aria-label에 이벤트+채널 컨텍스트 포함된 체크박스 찾기
    const toggle = screen.getByRole('checkbox', {
      name: /이슈 생성.*인앱 알림|인앱 알림.*이슈 생성/,
    })

    // 초기 상태 checked=true (시드 기본값 enabled=true)
    expect(toggle).toBeChecked()

    const user = userEvent.setup()
    await user.click(toggle)

    // 토글 후 unchecked — MSW PATCH → invalidate → re-fetch → store 갱신값 반영
    // re-query로 최신 DOM 참조 (React 리렌더 후 다른 노드일 수 있음)
    await waitFor(() => {
      const updated = screen.getByRole('checkbox', {
        name: /이슈 생성.*인앱 알림|인앱 알림.*이슈 생성/,
      })
      expect(updated).not.toBeChecked()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — i18n 라벨 경유 (하드코딩 한국어 직접 비교)
// 이 테스트는 라벨이 notification-policy-labels.ts의 eventTypeLabels/channelLabels에서
// 옴을 간접 검증한다. 실제 이슈 멘션(issue.mentioned) 라벨이 i18n에 있어야 렌더된다.
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationSubscriptionMatrix — S4 i18n 라벨 경유', () => {
  it('이슈 댓글 작성 라벨이 표시된다', async () => {
    renderMatrix()

    await waitFor(() => {
      expect(screen.getByText('이슈 댓글 작성')).toBeInTheDocument()
    })
  })

  it('채널 라벨이 인앱 알림/이메일로 표시된다', async () => {
    renderMatrix()

    await waitFor(() => {
      // 열 헤더에 라벨이 있는지 확인 (thead 내)
      const table = screen.getByRole('table')
      const header = within(table).getAllByRole('columnheader')
      const headerTexts = header.map((h) => h.textContent ?? '')
      expect(headerTexts.some((t) => t.includes('인앱 알림'))).toBe(true)
      expect(headerTexts.some((t) => t.includes('이메일'))).toBe(true)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — a11y: 토글 aria-label에 이벤트×채널 컨텍스트
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationSubscriptionMatrix — S5 접근성', () => {
  it('토글에 이벤트+채널 컨텍스트가 포함된 aria-label이 있다', async () => {
    renderMatrix()

    await waitFor(() => {
      // aria-label에 "이슈 생성"과 "인앱 알림" 모두 포함된 토글이 있어야 한다
      const toggle = screen.getByRole('checkbox', {
        name: /이슈 생성.*인앱 알림|인앱 알림.*이슈 생성/,
      })
      expect(toggle).toBeInTheDocument()
    })
  })
})
