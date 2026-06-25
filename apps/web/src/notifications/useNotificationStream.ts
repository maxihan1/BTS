// 인증 상태에 연동해 STOMP 인앱 알림을 토스트로 띄우는 hook
import { useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { createNotificationStream } from '@/api/notifications-stream'
import type { NotificationStream, InAppNotification } from '@/api/notifications-stream'
import { useIsAuthenticated, useAuthStore } from '@/auth/authStore'
import { UNREAD_COUNT_KEY } from '@/api/inbox'

/** Inbox 쿼리 캐시 무효화 접두사 — 목록 전체를 한 번에 무효화한다 */
const INBOX_QUERY_PREFIX = ['inbox'] as const

/**
 * 인증 상태에 연동해 STOMP 인앱 알림 스트림을 관리하는 hook.
 *
 * - 인증 상태가 true이면 스트림을 생성하고 activate한다.
 * - 수신 알림은 sonner toast로 표시한다. body=null이면 description을 생략한다.
 * - 수신 알림 도착 시 inbox 목록(INBOX_QUERY_PREFIX) + 미읽음 카운트(UNREAD_COUNT_KEY)를 invalidate한다.
 * - 언마운트(cleanup) 시 반드시 deactivate해 좀비 STOMP 연결을 방지한다.
 * - useRef 가드로 StrictMode 이중 호출 시 중복 activate를 차단한다 (C2).
 */
export function useNotificationStream(): void {
  const isAuthenticated = useIsAuthenticated()
  const streamRef = useRef<NotificationStream | null>(null)
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }

    // useRef 가드 — 이미 스트림이 있으면 중복 생성 차단 (StrictMode C2)
    if (streamRef.current !== null) {
      return
    }

    const stream = createNotificationStream({
      getToken: () => useAuthStore.getState().accessToken,
      onMessage: buildOnMessageHandler(queryClient),
    })

    streamRef.current = stream
    stream.activate()

    return () => {
      stream.deactivate()
      streamRef.current = null
    }
  }, [isAuthenticated, queryClient])
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 페이로드 수신 시 실행되는 핸들러를 반환한다.
 *
 * 동작 순서.
 * 1. sonner toast 표시 (body=null이면 description 생략).
 * 2. inbox 목록 쿼리 캐시 무효화 (INBOX_QUERY_PREFIX 접두사 일치).
 * 3. 미읽음 카운트 쿼리 캐시 무효화 (UNREAD_COUNT_KEY).
 *
 * @param queryClient TanStack QueryClient 인스턴스
 */
function buildOnMessageHandler(
  queryClient: ReturnType<typeof useQueryClient>,
): (notification: InAppNotification) => void {
  return (notification: InAppNotification) => {
    toast(notification.title, {
      description: notification.body ?? undefined,
    })

    void queryClient.invalidateQueries({ queryKey: INBOX_QUERY_PREFIX })
    void queryClient.invalidateQueries({ queryKey: UNREAD_COUNT_KEY })
  }
}
