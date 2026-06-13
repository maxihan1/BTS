// FR-NT-02 D7 E2E — STOMP 인앱 알림 수신 → sonner 토스트 표시 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 시 컨테이너 한정 + exact:true
//   - ui-pr-defer-e2e-regression-latent: 신규 spec이라 기존 셀렉터 영향 없음
//   - zod-v4-uuid-fixture-strictness: payload.id 는 RFC4122 v4 UUID 형식 필수
//   - worktree-stale-base-rebase-and-e2e-msw-traps: routeWebSocket 등록은 네비게이션 전에
//
// STOMP 전략.
//   page.routeWebSocket('**/ws', ...) 으로 앱이 연결하는 WebSocket 을 가로챈다.
//   routeWebSocket 은 Promise<void> 를 반환하므로 await 필수 — 등록 전 goto 하면 놓침.
//   핸드셰이크: CONNECT 수신 → CONNECTED 응답 → SUBSCRIBE 수신 → subId 캡처.
//   알림 주입: ws.send(messageFrame('/user/queue/notifications', subId, payload)) 호출.
//   실 백엔드 불필요.

import { test, expect } from '@playwright/test'
import type { WebSocketRoute } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import {
  connectedFrame,
  messageFrame,
  parseCommand,
  parseSubscriptionId,
} from './fixtures/stomp-fixtures'
import type { InAppNotification } from '../src/api/notifications-stream'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — STOMP 핸드셰이크 설정 + subId 캡처
//
// routeWebSocket 은 Promise<void> 를 반환하므로 await 하고 반환한다.
// subIdPromise 는 SUBSCRIBE 프레임이 도착하면 resolve 된다.
// ─────────────────────────────────────────────────────────────────────────────

async function setupStompRoute(
  page: import('@playwright/test').Page,
): Promise<{ wsRef: { current: WebSocketRoute | null }; subIdPromise: Promise<string> }> {
  const wsRef: { current: WebSocketRoute | null } = { current: null }

  let resolveSubId!: (id: string) => void
  const subIdPromise = new Promise<string>((resolve) => {
    resolveSubId = resolve
  })

  // await 필수 — 등록 완료 후 네비게이션해야 연결을 가로챌 수 있다
  await page.routeWebSocket('**/ws', (ws) => {
    wsRef.current = ws

    ws.onMessage((raw: string) => {
      const command = parseCommand(raw)

      if (command === 'CONNECT') {
        // STOMP 핸드셰이크 — CONNECTED 프레임으로 응답
        ws.send(connectedFrame())
        return
      }

      if (command === 'SUBSCRIBE') {
        // subscription id 캡처 — 이후 MESSAGE 프레임의 subscription 헤더로 사용
        const subId = parseSubscriptionId(raw)
        resolveSubId(subId)
        return
      }

      // DISCONNECT 등 나머지 프레임은 무시 (push-only 채널)
    })
  })

  return { wsRef, subIdPromise }
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — title + body 있는 알림 → 토스트에 제목과 본문 모두 표시
//
// Given   alice 로그인 → STOMP 연결 성립 → /user/queue/notifications 구독
// When    알림 payload (title="이슈 담당자로 지정됨", body="ATLAS-42: 빌드 자동화") 주입
// Then    sonner 토스트에 title 과 body 가 모두 표시됨
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 title+body 알림 → 토스트 제목+본문 표시 (FR-NT-02)', () => {
  test(
    'Given STOMP 구독 성립 When title+body payload 주입 Then 토스트에 title·body 모두 표시',
    async ({ page }) => {
      // Given. routeWebSocket 먼저 등록 (await — 등록 완료 후 네비게이션)
      const { wsRef, subIdPromise } = await setupStompRoute(page)

      // Given. alice 로그인 — 인증 성공 후 useNotificationStream 이 STOMP 연결 시도
      await loginAsAlice(page)

      // Given. 구독 성립 대기 (CONNECT→CONNECTED→SUBSCRIBE 순서)
      const subId = await subIdPromise

      // When. 알림 payload 주입
      const payload: InAppNotification = {
        id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
        eventType: 'issue.assigned',
        issueKey: 'ATLAS-42',
        title: '이슈 담당자로 지정됨',
        body: 'ATLAS-42: 빌드 자동화',
        occurredAt: '2026-06-13T10:00:00Z',
      }

      wsRef.current!.send(
        messageFrame('/user/queue/notifications', subId, JSON.stringify(payload)),
      )

      // Then. sonner 토스트 컨테이너 내에서 title 과 body 확인
      // [data-sonner-toaster] 컨테이너로 한정 — strict mode violation 회피
      const toaster = page.locator('[data-sonner-toaster]')
      await expect(toaster.getByText('이슈 담당자로 지정됨', { exact: true })).toBeVisible()
      await expect(toaster.getByText('ATLAS-42: 빌드 자동화', { exact: true })).toBeVisible()
    },
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — body=null 알림 → 토스트에 title만 표시, 깨짐 없음
//
// Given   alice 로그인 → STOMP 연결 성립 → /user/queue/notifications 구독
// When    알림 payload (title="이슈 상태 변경됨", body=null) 주입
// Then    sonner 토스트에 title 만 표시되고 본문 없이 정상 렌더됨
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 body=null 알림 → title만 표시, 깨짐 없음 (FR-NT-02)', () => {
  test(
    'Given STOMP 구독 성립 When body=null payload 주입 Then 토스트에 title만 표시',
    async ({ page }) => {
      // Given. routeWebSocket 먼저 등록 (await — 등록 완료 후 네비게이션)
      const { wsRef, subIdPromise } = await setupStompRoute(page)

      // Given. alice 로그인
      await loginAsAlice(page)

      // Given. 구독 성립 대기
      const subId = await subIdPromise

      // When. body=null payload 주입
      const payload: InAppNotification = {
        id: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2f3e4d5c',
        eventType: 'issue.transitioned',
        issueKey: 'ATLAS-10',
        title: '이슈 상태 변경됨',
        body: null,
        occurredAt: '2026-06-13T10:01:00Z',
      }

      wsRef.current!.send(
        messageFrame('/user/queue/notifications', subId, JSON.stringify(payload)),
      )

      // Then. 토스트 컨테이너에서 title 표시 확인
      const toaster = page.locator('[data-sonner-toaster]')
      await expect(toaster.getByText('이슈 상태 변경됨', { exact: true })).toBeVisible()

      // Then. body 텍스트가 없으므로 토스트가 비정상 렌더되지 않음 — 페이지 오류 없음
      await expect(page.locator('body')).not.toContainText('undefined')
      await expect(page.locator('body')).not.toContainText('null')
    },
  )
})
