// STOMP 인앱 알림 스트림 클라이언트 — 백엔드 /ws 엔드포인트 연결 + Zod 파싱
import { Client } from '@stomp/stompjs'
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const WS_PATH = '/ws'
const DESTINATION = '/user/queue/notifications'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 InAppNotificationPayload.kt 1:1 정합
// 필드 추가 시 백엔드 DTO를 먼저 확인할 것 (frontend-zod-backend-dto-contract-gap 교훈)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 인앱 알림 페이로드 Zod 스키마.
 *
 * 백엔드 `InAppNotificationPayload.kt` 직렬화 형식을 그대로 미러.
 * - `issueKey`, `body`는 nullable — `@JsonInclude(NON_NULL)` 적용이지만
 *   null로 명시 전달되는 경우도 허용하기 위해 `.nullable()` 사용.
 * - `occurredAt`는 Jackson ISO 8601 문자열로 직렬화됨.
 */
export const inAppNotificationSchema = z.object({
  /** 알림 식별자 (UUID) */
  id: z.string().uuid(),
  /** 이벤트 타입 wireValue (예: "issue.assigned") */
  eventType: z.string(),
  /** 관련 이슈 키 — 이슈와 무관한 이벤트는 null */
  issueKey: z.string().nullable(),
  /** 토스트 제목 */
  title: z.string(),
  /** 토스트 본문 — null이면 제목만 표시 */
  body: z.string().nullable(),
  /** 발생 시각 (ISO 8601 문자열) */
  occurredAt: z.string(),
})

/** 인앱 알림 페이로드 타입 — z.infer 자동 추론 */
export type InAppNotification = z.infer<typeof inAppNotificationSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** createNotificationStream 생성 옵션 */
export interface NotificationStreamOptions {
  /** 최신 accessToken을 반환하는 함수. beforeConnect마다 재평가됨 (C1) */
  getToken: () => string | null | undefined
  /** 유효 메시지 수신 시 호출되는 콜백 */
  onMessage: (notification: InAppNotification) => void
}

/** createNotificationStream 반환 타입 */
export interface NotificationStream {
  /** STOMP 연결을 시작한다 */
  activate: () => void
  /** STOMP 연결을 종료한다 */
  deactivate: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 구현
// ─────────────────────────────────────────────────────────────────────────────

/**
 * STOMP 인앱 알림 스트림을 생성한다.
 *
 * - brokerURL은 현재 페이지의 host를 기반으로 자동 구성 (dev=vite proxy, prod=동일 호스트).
 * - `beforeConnect`에서 매 연결 시 최신 토큰을 재평가해 만료 토큰 재사용을 방지한다 (C1).
 * - 수신 메시지는 Zod로 검증하며, 스키마 불일치 시 `console.warn` 후 `onMessage` 미호출 (앱 크래시 금지).
 *
 * @param options.getToken 최신 accessToken 반환 함수
 * @param options.onMessage 유효 메시지 수신 콜백
 * @returns activate/deactivate를 가진 스트림 객체
 */
export function createNotificationStream(options: NotificationStreamOptions): NotificationStream {
  const { getToken, onMessage } = options

  const brokerURL = buildBrokerURL()
  const initialToken = getToken()

  const client = new Client({
    brokerURL,
    connectHeaders: {
      Authorization: `Bearer ${initialToken ?? ''}`,
    },
    reconnectDelay: 5_000,
    onConnect: () => {
      client.subscribe(DESTINATION, (frame) => {
        handleFrame(frame.body, onMessage)
      })
    },
    beforeConnect: () => {
      client.connectHeaders = {
        Authorization: `Bearer ${getToken() ?? ''}`,
      }
    },
  })

  return {
    activate: () => { client.activate() },
    deactivate: () => { client.deactivate() },
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 페이지 프로토콜에 따라 brokerURL을 구성한다.
 * - https → wss://
 * - http  → ws://
 */
function buildBrokerURL(): string {
  const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${location.host}${WS_PATH}`
}

/**
 * STOMP 프레임 body를 파싱해 유효하면 onMessage를 호출한다.
 * 파싱 실패(비JSON / 스키마 불일치) 시 console.warn 1회 + onMessage 미호출.
 */
function handleFrame(body: string, onMessage: (n: InAppNotification) => void): void {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    console.warn('[notifications-stream] 수신 프레임 JSON 파싱 실패:', body.slice(0, 100))
    return
  }

  const result = inAppNotificationSchema.safeParse(parsed)
  if (!result.success) {
    console.warn('[notifications-stream] 수신 메시지 스키마 불일치:', result.error.issues)
    return
  }

  onMessage(result.data)
}
