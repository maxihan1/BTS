// 알림 정책 관리자 라우트 페이지 단위 테스트 — 조립 렌더·생성·토글·삭제·에러
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requireSystemAdmin } from '@/auth/routeGuard'
import {
  resetNotificationPolicyStore,
  notificationPolicyHandlers,
} from '@/mocks/notification-policy-handlers'
import { SEED_POLICY_IDS } from '@/mocks/notification-policy-fixtures'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { AdminNotificationPoliciesPage } from '@/routes/admin.notification-policies'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock — 실제 DOM 없이 호출 여부로 검증
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// Radix/shadcn Select → 네이티브 <select> mock
// Radix Select는 jsdom에서 hasPointerCapture 제약으로 클릭 인터랙션이 불가하므로
// 네이티브 select 엘리먼트로 대체해 userEvent.selectOptions를 활성화한다.
// (NotificationPolicyForm.test.tsx 동형 선례)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const { createElement, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
  }: {
    children: React.ReactNode
    onValueChange?: (v: string) => void
    value?: string
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<React.ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as React.ReactElement<{ 'aria-label'?: string; children?: React.ReactNode }>
        if (el.props['aria-label']) {
          triggerLabel.current = el.props['aria-label']
        }
        if (el.props.children) {
          contentOptions.current = el.props.children
        }
      }
    })

    return createElement(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      createElement('option', { value: '' }, '-- 선택 --'),
      contentOptions.current,
    )
  }

  function SelectTrigger({ children, 'aria-label': ariaLabel }: { children?: React.ReactNode; 'aria-label'?: string }) {
    return createElement('span', { 'aria-label': ariaLabel, 'data-testid': 'select-trigger' }, children)
  }

  function SelectValue() {
    return null
  }

  function SelectContent({ children }: { children: React.ReactNode }) {
    return createElement('span', { 'data-testid': 'select-content' }, children)
  }

  function SelectItem({ value, children }: { value: string; children: React.ReactNode }) {
    return createElement('option', { value }, children)
  }

  return { Select, SelectTrigger, SelectValue, SelectContent, SelectItem }
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 — 가드 테스트용 최소 beforeLoad 컨텍스트
// ─────────────────────────────────────────────────────────────────────────────

interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

interface RedirectResponse extends Response {
  options: {
    to: string
    search?: Record<string, string>
  }
}

const makeCtx = (pathname: string): MinimalBeforeLoadContext => ({
  location: { href: pathname, pathname },
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(): { user: ReturnType<typeof userEvent.setup> } & ReturnType<typeof render> {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <AdminNotificationPoliciesPage />
    </QueryClientProvider>,
  )
  return { user, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 + store 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami({ isSystemAdmin: true }),
  })
  resetNotificationPolicyStore()
  vi.clearAllMocks()
  server.use(...notificationPolicyHandlers)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 렌더 + 목록 표시 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminNotificationPoliciesPage — 렌더', () => {
  it('페이지 제목 "알림 정책"이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /알림 정책/ })).toBeInTheDocument()
    })
  })

  it('시드 정책 목록이 테이블에 렌더된다', async () => {
    renderPage()
    // 시드에 있는 이벤트 유형 라벨이 테이블에 표시되어야 함
    // select option에도 동일 텍스트가 있으므로 getAllByText로 검증
    await waitFor(() => {
      expect(screen.getAllByText('이슈 생성').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('이슈 담당자 지정').length).toBeGreaterThanOrEqual(1)
    })
    // 테이블 행(td) 안에 해당 텍스트가 있어야 함
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1)
  })

  it('폼 컴포넌트(정책 추가 버튼)가 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '정책 추가' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 폼 제출 → 생성 → 목록 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminNotificationPoliciesPage — 정책 생성', () => {
  it('폼 제출 성공 시 목록에 새 항목이 추가된다', async () => {
    const { user } = renderPage()

    // 카탈로그 로드 완료 대기 — 이슈 기한 임박 option이 나타나야 함
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '이슈 기한 임박' })).toBeInTheDocument()
    })

    // 이벤트 유형 select — "이슈 기한 임박" (issue.due_soon) 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '이벤트 유형' }),
      'issue.due_soon',
    )

    // 수신자 역할 select — "보고자" (REPORTER) 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '수신자' }),
      'REPORTER',
    )

    // 채널 select — "이메일" (EMAIL) 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '채널' }),
      'EMAIL',
    )

    // 정책 추가 버튼 클릭
    await user.click(screen.getByRole('button', { name: '정책 추가' }))

    // 새 정책의 이벤트 라벨이 목록(테이블 행)에 나타나야 함
    await waitFor(() => {
      const rows = screen.getAllByRole('row')
      // 헤더 행 포함 5개(시드 4개 + 헤더 1개) → 새 항목 추가 후 6개 이상
      expect(rows.length).toBeGreaterThanOrEqual(6)
    })
  })

  it('409 중복 응답 시 폼에 에러 메시지가 표시된다', async () => {
    // issue.created + REPORTER + EMAIL 는 시드에 이미 존재 → 409 유도
    const { user } = renderPage()

    // 카탈로그 로드 완료 대기
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '이슈 생성' })).toBeInTheDocument()
    })

    // 이벤트 유형 — 이슈 생성 (이미 존재)
    await user.selectOptions(
      screen.getByRole('combobox', { name: '이벤트 유형' }),
      'issue.created',
    )

    // 수신자 역할 — 보고자
    await user.selectOptions(
      screen.getByRole('combobox', { name: '수신자' }),
      'REPORTER',
    )

    // 채널 — 이메일
    await user.selectOptions(
      screen.getByRole('combobox', { name: '채널' }),
      'EMAIL',
    )

    await user.click(screen.getByRole('button', { name: '정책 추가' }))

    // submitError role="alert" 메시지가 폼에 나타나야 함
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('동일한 이벤트·수신자·채널 조합의 정책이 이미 존재합니다.')
    })
  })

  it('기타 서버 에러 시 sonner toast.error가 호출된다', async () => {
    // 500 에러 강제
    server.use(
      http.post('/api/v1/notification-policies', () => {
        return HttpResponse.json({ error: 'internal' }, { status: 500 })
      }),
    )

    const { user } = renderPage()

    // 카탈로그 로드 완료 대기
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '이슈 생성' })).toBeInTheDocument()
    })

    // 이벤트 유형 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '이벤트 유형' }),
      'issue.created',
    )

    // 수신자 역할 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '수신자' }),
      'REPORTER',
    )

    // 채널 선택
    await user.selectOptions(
      screen.getByRole('combobox', { name: '채널' }),
      'EMAIL',
    )

    await user.click(screen.getByRole('button', { name: '정책 추가' }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 토글 → 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminNotificationPoliciesPage — 토글', () => {
  it('토글 버튼 클릭 시 PATCH가 호출되고 목록이 갱신된다', async () => {
    const { user } = renderPage()

    // 시드 p1(이슈 생성, enabled=true)의 토글 버튼이 나타날 때까지 대기
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이슈 생성 정책 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '이슈 생성 정책 비활성화' }))

    // 갱신 후 다시 "활성화" 버튼이 나타나야 함 (enabled → false)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이슈 생성 정책 활성화' })).toBeInTheDocument()
    })
  })

  it('토글 mutation 에러 시 sonner toast.error가 호출된다', async () => {
    server.use(
      http.patch(`/api/v1/notification-policies/${SEED_POLICY_IDS.p1}`, () => {
        return HttpResponse.json({ error: 'internal' }, { status: 500 })
      }),
    )

    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이슈 생성 정책 비활성화' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '이슈 생성 정책 비활성화' }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 → 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminNotificationPoliciesPage — 삭제', () => {
  it('삭제 확인 후 해당 행이 목록에서 제거된다', async () => {
    const { user } = renderPage()

    // 삭제 버튼이 렌더될 때까지 대기
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이슈 생성 정책 삭제' })).toBeInTheDocument()
    })

    // 삭제 버튼 클릭 → 인라인 확인 흐름
    await user.click(screen.getByRole('button', { name: '이슈 생성 정책 삭제' }))

    // 확인 버튼 클릭
    await user.click(screen.getByRole('button', { name: '이슈 생성 정책 삭제 확인' }))

    // 해당 행이 목록에서 사라져야 함 — select option은 여전히 존재하지만
    // 테이블 행 수가 감소해야 함
    await waitFor(() => {
      // 시드 4개 → 삭제 후 3개 + 헤더 1개 = 4행
      expect(screen.getAllByRole('row').length).toBeLessThanOrEqual(4)
    })
  })

  it('삭제 mutation 에러 시 sonner toast.error가 호출된다', async () => {
    server.use(
      http.delete(`/api/v1/notification-policies/${SEED_POLICY_IDS.p2}`, () => {
        return HttpResponse.json({ error: 'internal' }, { status: 500 })
      }),
    )

    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이슈 담당자 지정 정책 삭제' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '이슈 담당자 지정 정책 삭제' }))
    await user.click(screen.getByRole('button', { name: '이슈 담당자 지정 정책 삭제 확인' }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireSystemAdmin 가드 — /admin/notification-policies 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('composeGuards(requireAuth, requireSystemAdmin) 가드 — /admin/notification-policies', () => {
  const notifPoliciesGuard = composeGuards(requireAuth, requireSystemAdmin)

  it('미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      notifPoliciesGuard(makeCtx('/admin/notification-policies'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('인증됐지만 isSystemAdmin=false → /dashboard 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: false }),
    })

    let thrown: unknown
    try {
      notifPoliciesGuard(makeCtx('/admin/notification-policies'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  it('시스템 관리자 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: true }),
    })

    expect(() => notifPoliciesGuard(makeCtx('/admin/notification-policies'))).not.toThrow()
  })
})
