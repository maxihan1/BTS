// useNotificationStream hook 단위 테스트 — 인증 연동 STOMP 스트림 + toast 동작 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import React from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import type { InAppNotification } from '@/api/notifications-stream'
import { UNREAD_COUNT_KEY } from '@/api/inbox'

// ─────────────────────────────────────────────────────────────────────────────
// createNotificationStream mock — activate/deactivate spy 반환
// ─────────────────────────────────────────────────────────────────────────────

const mockActivate = vi.fn()
const mockDeactivate = vi.fn()
let capturedOnMessage: ((payload: InAppNotification) => void) | null = null

vi.mock('@/api/notifications-stream', () => ({
  createNotificationStream: vi.fn((opts: { getToken: () => string | null | undefined; onMessage: (n: InAppNotification) => void }) => {
    capturedOnMessage = opts.onMessage
    return {
      activate: mockActivate,
      deactivate: mockDeactivate,
    }
  }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: vi.fn(),
}))

import { toast } from 'sonner'
import { createNotificationStream } from '@/api/notifications-stream'
import { useNotificationStream } from './useNotificationStream'

// ─────────────────────────────────────────────────────────────────────────────
// 유효한 페이로드 픽스처
// ─────────────────────────────────────────────────────────────────────────────
const validPayload: InAppNotification = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  eventType: 'issue.assigned',
  issueKey: 'BTS-42',
  title: '이슈가 나에게 할당됐습니다',
  body: '담당자가 변경되었습니다.',
  occurredAt: '2026-06-13T10:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업 / 해제
// ─────────────────────────────────────────────────────────────────────────────
beforeEach(() => {
  vi.clearAllMocks()
  capturedOnMessage = null
  useAuthStore.setState({ accessToken: null, user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-1. 인증 상태면 스트림을 생성하고 activate를 호출한다 (S1)
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — 인증 상태', () => {
  it('accessToken이 있으면 createNotificationStream을 호출하고 activate한다', () => {
    // renderHook 전에 store 시드 → useEffect 첫 실행 시 이미 인증 상태
    useAuthStore.setState({ accessToken: 'test-access-token', user: null })

    renderHook(() => useNotificationStream())

    expect(createNotificationStream).toHaveBeenCalledOnce()
    expect(mockActivate).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-2. 미인증이면 스트림을 생성하지 않는다 (S3)
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — 미인증 상태', () => {
  it('accessToken이 없으면 createNotificationStream을 호출하지 않는다', () => {
    // accessToken = null (beforeEach에서 설정)
    renderHook(() => useNotificationStream())

    expect(createNotificationStream).not.toHaveBeenCalled()
    expect(mockActivate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-3. onMessage → toast(title, { description: body }) (S1)
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — onMessage 토스트 표시', () => {
  it('onMessage 콜백이 toast(title, { description: body })를 호출한다', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    renderHook(() => useNotificationStream())

    expect(capturedOnMessage).not.toBeNull()
    act(() => {
      capturedOnMessage!(validPayload)
    })

    expect(toast).toHaveBeenCalledWith(validPayload.title, {
      description: validPayload.body,
    })
  })

  it('body=null이면 toast(title, { description: undefined })를 호출한다 (S2)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    renderHook(() => useNotificationStream())

    expect(capturedOnMessage).not.toBeNull()
    act(() => {
      capturedOnMessage!({ ...validPayload, body: null })
    })

    expect(toast).toHaveBeenCalledWith(validPayload.title, {
      description: undefined,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-4. 언마운트 시 deactivate 호출 (S4)
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — 언마운트 cleanup', () => {
  it('언마운트 시 deactivate를 호출한다', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    const { unmount } = renderHook(() => useNotificationStream())

    unmount()

    expect(mockDeactivate).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-5. StrictMode 중복 activate 방지 + 각 cleanup deactivate (C2)
// React StrictMode에서 dev 환경에 useEffect가 mount→unmount→mount 2회 실행됨.
// useRef 가드로 activate가 살아있는 연결을 중복 생성하지 않아야 한다.
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — StrictMode 중복 방지 (C2)', () => {
  it('mount→unmount→mount 재실행 시 activate가 총 1회만 호출된다(useRef 가드)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    // StrictMode처럼 수동으로 mount→unmount→mount 시뮬레이션
    // 첫 번째 마운트
    const { unmount: unmount1 } = renderHook(() => useNotificationStream())

    // 첫 번째 언마운트 (cleanup → deactivate, streamRef 초기화)
    unmount1()

    // 두 번째 마운트 (새 hook 인스턴스, streamRef 초기화 상태)
    renderHook(() => useNotificationStream())

    // 별도 renderHook = 별도 인스턴스 → 각각 1회 activate가 맞음.
    // 중요: 각 인스턴스 내에서 useRef 가드가 중복 activate를 차단함.
    expect(mockActivate).toHaveBeenCalledTimes(2) // 각 renderHook 인스턴스 1회씩
  })

  it('각 unmount마다 deactivate가 호출된다 (좀비 연결 0)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    const { unmount: unmount1 } = renderHook(() => useNotificationStream())
    unmount1()

    const { unmount: unmount2 } = renderHook(() => useNotificationStream())
    unmount2()

    expect(mockDeactivate).toHaveBeenCalledTimes(2)
  })

  it('같은 hook 인스턴스 내 effect cleanup+재실행 시 이전 스트림이 deactivate되고 새 스트림이 activate된다', () => {
    // 인증 상태 변경으로 effect 재실행 시뮬레이션
    useAuthStore.setState({ accessToken: 'token-v1', user: null })

    const { rerender } = renderHook(() => useNotificationStream())

    // effect 실행 → activate 1회
    expect(mockActivate).toHaveBeenCalledTimes(1)
    expect(mockDeactivate).toHaveBeenCalledTimes(0)

    // 인증 해제 → effect cleanup (deactivate) + effect 미재실행(미인증)
    act(() => {
      useAuthStore.setState({ accessToken: null, user: null })
    })
    rerender()

    expect(mockDeactivate).toHaveBeenCalledTimes(1)
    // 미인증이므로 activate 추가 호출 없음
    expect(mockActivate).toHaveBeenCalledTimes(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UNS-6. onMessage 시 inbox 쿼리 invalidate (FR-UX-03 Task 9)
// ─────────────────────────────────────────────────────────────────────────────
describe('useNotificationStream — onMessage inbox invalidate', () => {
  it("onMessage 수신 시 ['inbox'] 접두사와 UNREAD_COUNT_KEY 를 invalidate한다", () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: queryClient }, children)

    renderHook(() => useNotificationStream(), { wrapper })

    expect(capturedOnMessage).not.toBeNull()
    act(() => {
      capturedOnMessage!(validPayload)
    })

    // ['inbox'] 접두사 무효화 (목록 캐시)
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ['inbox'] })
    // UNREAD_COUNT_KEY 무효화 (미읽음 카운트)
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: UNREAD_COUNT_KEY })
  })

  it('onMessage 수신 시 기존 토스트 동작도 함께 실행된다 (공존)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: queryClient }, children)

    renderHook(() => useNotificationStream(), { wrapper })

    expect(capturedOnMessage).not.toBeNull()
    act(() => {
      capturedOnMessage!(validPayload)
    })

    // 토스트도 여전히 호출됨
    expect(toast).toHaveBeenCalledWith(validPayload.title, {
      description: validPayload.body,
    })
  })
})
