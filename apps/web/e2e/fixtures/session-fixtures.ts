// FR-AU-09 E2E 세션 관리 공통 fixtures — 세션 mock 데이터 + 로그인 헬퍼
//
// MSW session-handlers.ts 가 이 파일의 fixture 객체를 import 한다.
// 따라서 이 파일은 순수 데이터 + Playwright 헬퍼만 포함한다. page.route 없음.
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import type { Session } from '../../src/api/sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Mock 세션 fixture 데이터
// alice 의 현재 세션 + 다른 기기(other-device) 세션을 모사.
// UUID는 고정값 — MSW session-handlers 와 E2E spec 이 동일 식별자를 참조한다.
// ─────────────────────────────────────────────────────────────────────────────

/** alice 의 현재 세션 fixture — current: true, 강제 종료 불가 (S-4) */
export const currentSessionFixture: Session = {
  sid: '11111111-1111-1111-8111-111111111111',
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/124',
  ipAddress: '127.0.0.1',
  lastSeenAt: '2026-05-29T10:00:00Z',
  createdAt: '2026-05-29T09:00:00Z',
  current: true,
}

/** alice 의 다른 기기 세션 fixture — current: false, 강제 종료 가능 (S-2) */
export const otherSessionFixture: Session = {
  sid: '22222222-2222-2222-8222-222222222222',
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
 * Zod uuid() 검증 통과 형식: M 자리 [1-8], N 자리 [89abAB].
 */
export const bobSessionSid = '33333333-3333-3333-8333-333333333333'

// ─────────────────────────────────────────────────────────────────────────────
// 로그인 헬퍼 — MSW auth-handlers 가 login/whoami 를 처리하므로 page.route 불필요
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice (Local provider) 로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * FR-AU-07 identifier-first 2단계 흐름 반영.
 * 1단계: example.com 이메일 입력 → "계속" → routeStore 미매칭 → 2단계 폼 진입.
 * 2단계: Local provider 선택 → username=alice + password 입력 → 로그인.
 *
 * MSW dev mock 환경 가정 — auth-handlers.ts 의 loginHandler/whoamiHandler 가 처리.
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

  // 1단계: 이메일 입력 + "계속" — example.com 미등록 도메인 → 2단계 진입
  await page.getByLabel('이메일').fill('alice@example.com')
  await page.getByRole('button', { name: '계속', exact: true }).click()

  // 2단계: provider 드롭다운 대기 후 Local 선택
  const providerSelect = page.getByRole('combobox', { name: '로그인 방식' })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: 'Local', exact: true }).click()

  // 2단계: username + password 입력 후 로그인
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL('**/dashboard')
}

/**
 * MSW session-handlers 의 stateful revokedSids 를 초기화한다.
 *
 * `GET /api/v1/auth/sessions` 에 X-MSW-Reset-Sessions: true 헤더를 포함해 호출하면
 * session-handlers 가 내부 Set 을 clear 한다.
 * 각 테스트의 beforeEach 에서 호출해 테스트 격리를 보장한다.
 *
 * @param page Playwright Page 객체
 */
export async function resetSessionHandlerState(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch('/api/v1/auth/sessions', {
      headers: { 'X-MSW-Reset-Sessions': 'true' },
    })
  })
}
