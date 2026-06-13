// 인증 상태에 연동해 STOMP 인앱 알림을 토스트로 띄우는 hook
import { useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { createNotificationStream } from '@/api/notifications-stream'
import type { NotificationStream, InAppNotification } from '@/api/notifications-stream'
import { useIsAuthenticated, useAuthStore } from '@/auth/authStore'

/**
 * 인증 상태에 연동해 STOMP 인앱 알림 스트림을 관리하는 hook.
 *
 * - 인증 상태가 true이면 스트림을 생성하고 activate한다.
 * - 수신 알림은 sonner toast로 표시한다. body=null이면 description을 생략한다.
 * - 언마운트(cleanup) 시 반드시 deactivate해 좀비 STOMP 연결을 방지한다.
 * - useRef 가드로 StrictMode 이중 호출 시 중복 activate를 차단한다 (C2).
 */
export function useNotificationStream(): void {
  const isAuthenticated = useIsAuthenticated()
  const streamRef = useRef<NotificationStream | null>(null)

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
      onMessage: buildToastHandler(),
    })

    streamRef.current = stream
    stream.activate()

    return () => {
      stream.deactivate()
      streamRef.current = null
    }
  }, [isAuthenticated])
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 페이로드를 sonner toast로 변환하는 핸들러를 반환한다.
 * body=null이면 description을 undefined로 전달해 토스트 본문을 생략한다.
 */
function buildToastHandler(): (notification: InAppNotification) => void {
  return (notification: InAppNotification) => {
    toast(notification.title, {
      description: notification.body ?? undefined,
    })
  }
}
