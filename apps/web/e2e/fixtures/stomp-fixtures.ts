// STOMP 1.2 프레임 빌더 헬퍼 — E2E routeWebSocket 핸드셰이크 모사용

/**
 * STOMP 1.2 CONNECTED 응답 프레임을 반환한다.
 *
 * @stomp/stompjs 가 CONNECT 프레임을 수신하면 이 프레임으로 응답해야
 * 클라이언트가 연결 성립을 인식하고 onConnect 콜백을 실행한다.
 *
 * - `version:1.2`  — 클라이언트의 `accept-version` 협상에서 1.2 선택
 * - `heart-beat:0,0` — 서버측 heartbeat 비활성(cx, cy 모두 0)
 * - `\x00` — STOMP 프레임 종료 null byte
 */
export function connectedFrame(): string {
  return 'CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\x00'
}

/**
 * STOMP 1.2 MESSAGE 프레임을 반환한다.
 *
 * @param destination  메시지 목적지 — 예: '/user/queue/notifications'
 * @param subscriptionId  SUBSCRIBE 프레임에서 캡처한 `id:` 헤더값.
 *   클라이언트가 어느 구독에서 메시지가 왔는지 식별하는 데 필수.
 * @param jsonBody  직렬화된 JSON 문자열 (InAppNotificationPayload)
 */
export function messageFrame(
  destination: string,
  subscriptionId: string,
  jsonBody: string,
): string {
  const messageId = `msg-${Date.now()}`
  return (
    'MESSAGE\n' +
    `destination:${destination}\n` +
    `subscription:${subscriptionId}\n` +
    `message-id:${messageId}\n` +
    'content-type:application/json\n' +
    '\n' +
    jsonBody +
    '\x00'
  )
}

/**
 * STOMP 프레임 raw 텍스트에서 command(첫 줄)를 추출한다.
 *
 * @param raw  routeWebSocket ws.onMessage 로 수신한 raw 프레임 문자열
 * @returns 'CONNECT' | 'SUBSCRIBE' | 기타 command 문자열
 */
export function parseCommand(raw: string): string {
  return raw.split('\n')[0] ?? ''
}

/**
 * STOMP SUBSCRIBE 프레임에서 `id:` 헤더값을 추출한다.
 *
 * @param raw  SUBSCRIBE 프레임 raw 텍스트
 * @returns subscription id 문자열, 없으면 빈 문자열
 */
export function parseSubscriptionId(raw: string): string {
  const match = raw.match(/^id:(.+)$/m)
  return match?.[1]?.trim() ?? ''
}
