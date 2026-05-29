// FR-AU-09 E2E 세션 관리 공통 fixtures — 로그인 헬퍼 + 세션 mock 데이터 + page.route 헬퍼
//
// 세션 관리 E2E 는 test.use({ serviceWorkers: 'block' }) 과 함께 사용한다.
// MSW Service Worker 를 차단하고 page.route 로 모든 API 를 직접 인터셉트한다.
// 이 방식이 필요한 이유:
//   - MSW Service Worker 는 브라우저 fetch 레벨에서 인터셉트하므로
//     Playwright page.route (CDP 레벨) 보다 먼저 실행된다.
//   - GET /api/v1/auth/sessions 는 MSW 에 핸들러가 없으므로 백엔드로 bypass.
//   - serviceWorkers: 'block' 으로 MSW 를 비활성화 후 page.route 단독 처리.
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import type { Session } from '../../src/api/sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Mock 세션 fixture 데이터
// alice 의 현재 세션 + 다른 기기(other-device) 세션을 모사.
// UUID는 고정값 — 각 테스트가 동일한 식별자를 예상한다.
// ─────────────────────────────────────────────────────────────────────────────

/** alice 의 현재 세션 fixture — current: true, 강제 종료 불가 (S-4) */
export const currentSessionFixture: Session = {
  sid: '11111111-1111-1111-1111-111111111111',
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/124',
  ipAddress: '127.0.0.1',
  lastSeenAt: '2026-05-29T10:00:00Z',
  createdAt: '2026-05-29T09:00:00Z',
  current: true,
}

/** alice 의 다른 기기 세션 fixture — current: false, 강제 종료 가능 (S-2) */
export const otherSessionFixture: Session = {
  sid: '22222222-2222-2222-2222-222222222222',
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/123',
  ipAddress: '192.168.1.100',
  lastSeenAt: '2026-05-28T18:00:00Z',
  createdAt: '2026-05-28T17:00:00Z',
  current: false,
}

/**
 * bob 이 소유한 세션 sid — alice 가 이 sid 로 DELETE 시도하면 404 반환 (IDOR, S-3).
 * 실제 세션 fixture 객체는 불필요 — sid 값만 사용.
 */
export const bobSessionSid = '33333333-3333-3333-3333-333333333333'

// ─────────────────────────────────────────────────────────────────────────────
// 인증 API mock — serviceWorkers: 'block' 환경에서 login/whoami 를 대체
// MSW 가 비활성화되므로 세션 spec 이 직접 등록해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 인증 관련 API 를 page.route 로 mock 한다.
 *
 * serviceWorkers: 'block' 환경에서는 MSW 가 비활성화되므로
 * login / whoami / logout 을 직접 인터셉트해야 로그인 헬퍼가 동작한다.
 *
 * @param page Playwright Page 객체
 */
export async function mockAuthRoutes(page: Page): Promise<void> {
  // POST /api/v1/auth/login — alice/password → 200 access token
  await page.route('**/api/v1/auth/login', (route) => {
    if (route.request().method() !== 'POST') {
      void route.fallback()
      return
    }
    void route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        access_token: 'mock-access-token-alice',
        token_type: 'Bearer',
        expires_in: 900,
      }),
    })
  })

  // GET /api/v1/users/me/whoami — Bearer mock-access-token-alice → alice 정보
  await page.route('**/api/v1/users/me/whoami', (route) => {
    const authHeader = route.request().headers()['authorization'] ?? ''
    if (!authHeader.startsWith('Bearer mock-access-token-')) {
      void route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'unauthorized' }),
      })
      return
    }
    void route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'jwt',
        userId: '00000000-0000-0000-0000-000000000001',
      }),
    })
  })

  // POST /api/v1/auth/logout — 204 No Content
  await page.route('**/api/v1/auth/logout', (route) => {
    void route.fulfill({ status: 204 })
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 로그인 헬퍼 — loginAsAlice 패턴 재사용 (issue-fixtures.ts 와 동일 방식)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice (Local provider) 로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * mockAuthRoutes 를 먼저 등록한 뒤 호출해야 한다 (serviceWorkers: 'block' 환경).
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')
  await page.getByRole('button', { name: '로그인' }).click()
  await page.waitForURL('**/dashboard')
}

// ─────────────────────────────────────────────────────────────────────────────
// 세션 API mock — GET /sessions + DELETE /sessions/:sid
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/auth/sessions 를 인터셉트해 고정 fixture 응답을 반환한다.
 *
 * 초기에 currentSession + otherSession 두 개를 반환하고,
 * DELETE 로 otherSession 이 삭제된 후에는 목록에서 제외된 응답을 반환하도록
 * stateful 변수를 관리한다.
 *
 * @param page Playwright Page 객체
 * @returns revokedSids — 삭제된 sid 를 외부에서 추적하는 Set 참조
 */
export async function mockSessionsRoute(page: Page): Promise<Set<string>> {
  const revokedSids = new Set<string>()

  await page.route('**/api/v1/auth/sessions', (route) => {
    const method = route.request().method()

    if (method === 'GET') {
      const activeSessions = [currentSessionFixture, otherSessionFixture].filter(
        (s) => !revokedSids.has(s.sid),
      )
      void route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ sessions: activeSessions }),
      })
    } else {
      void route.fulfill({ status: 405 })
    }
  })

  return revokedSids
}

/**
 * DELETE /api/v1/auth/sessions/:sid 를 인터셉트한다.
 *
 * IDOR 방어 (S-3): alice 가 bob 의 sid 를 삭제 시도 → 404.
 * 현재 세션 삭제 시도 (S-4): 409 Conflict.
 * 본인 다른 세션 삭제 (S-2): 204 + revokedSids 에 sid 추가.
 *
 * @param page Playwright Page 객체
 * @param revokedSids mockSessionsRoute 에서 반환된 동일 Set 참조
 * @param knownOtherSids alice 소유 다른 세션 sid 목록 (이 외 sid → IDOR 404)
 */
export async function mockRevokeSessionRoute(
  page: Page,
  revokedSids: Set<string>,
  knownOtherSids: string[] = [otherSessionFixture.sid],
): Promise<void> {
  await page.route('**/api/v1/auth/sessions/**', (route) => {
    const url = route.request().url()
    const method = route.request().method()

    if (method !== 'DELETE') {
      void route.fallback()
      return
    }

    const sid = url.split('/').pop() ?? ''

    // 현재 세션 종료 시도 → 409
    if (sid === currentSessionFixture.sid) {
      void route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'cannot_revoke_current_session' }),
      })
      return
    }

    // alice 소유 다른 세션 → 204 + revokedSids 에 등록
    if (knownOtherSids.includes(sid)) {
      revokedSids.add(sid)
      void route.fulfill({ status: 204 })
      return
    }

    // 그 외 (타인 sid, 미존재 sid) → 404 IDOR 방어
    void route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'not_found' }),
    })
  })
}
