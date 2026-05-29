// FR-AU-09 D7 E2E — 세션 관리 시나리오 (목록 조회 + 강제 종료 + IDOR 방어 + 현재 세션 보호)
//
// MSW session-handlers.ts 가 GET /api/v1/auth/sessions + DELETE /api/v1/auth/sessions/:sid 를
// 처리한다. serviceWorkers:'block' 없이 MSW 위에서 동작한다 (기존 E2E 패턴과 일치).
import { test, expect } from '@playwright/test'
import {
  loginAsAlice,
  resetSessionHandlerState,
  bobSessionSid,
} from './fixtures/session-fixtures'

test.beforeEach(async ({ page }) => {
  // MSW session-handlers 의 stateful revokedSids 를 초기화해 테스트 격리 보장.
  // 로그인 전에는 /api/v1/auth/sessions 에 인증 없이 접근하므로
  // 페이지 로드 후 reset 을 실행한다.
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  await resetSessionHandlerState(page)
})

// ─────────────────────────────────────────────────────────────────────────────
// Happy Path — S-1 + S-2
//
// Given  alice 가 로그인, 현재 세션 1개 + 다른 기기 세션 1개 존재
// When   /settings/sessions 진입
// Then   두 세션 카드 표시, 현재 세션 배지 표시
// When   다른 기기 세션의 "세션 종료" 버튼 클릭
// Then   해당 세션 카드가 목록에서 사라짐 (TanStack Query 재조회 반영)
// ─────────────────────────────────────────────────────────────────────────────
test('S-1/S-2 happy path — 세션 목록 조회 → 다른 세션 강제 종료 → 목록에서 사라짐', async ({ page }) => {
  // ── Given. alice 로그인 ────────────────────────────────────────────────────
  await loginAsAlice(page)

  // ── When. /settings/sessions 진입 ─────────────────────────────────────────
  await page.goto('/settings/sessions')

  // ── Then. 페이지 헤딩 확인 ────────────────────────────────────────────────
  await expect(page.getByRole('heading', { name: '활성 세션 관리' })).toBeVisible()

  // ── Then. 두 세션 카드 표시 확인 ─────────────────────────────────────────
  // SessionCard 는 userAgent 를 CardTitle 에 렌더한다.
  await expect(page.getByText('Macintosh', { exact: false })).toBeVisible()
  await expect(page.getByText('Windows NT', { exact: false })).toBeVisible()

  // ── Then. EC-29 5초 지연 안내 문구 표시 확인 (FR-8) ─────────────────────
  await expect(page.getByText('세션 종료는 최대 5초 내 완전히 적용됩니다.')).toBeVisible()

  // ── When. 다른 기기 세션의 "세션 종료" 버튼 클릭 ─────────────────────────
  // SessionCard 의 강제 종료 버튼은 aria-label="세션 종료" 이다.
  // 현재 세션 버튼은 disabled=true → enabled 버튼이 다른 세션의 것.
  const enabledRevokeButton = page.getByRole('button', { name: '세션 종료', disabled: false })
  await expect(enabledRevokeButton).toHaveCount(1)
  await enabledRevokeButton.click()

  // ── Then. 강제 종료된 세션 카드가 목록에서 사라짐 ────────────────────────
  // TanStack Query 가 invalidate 후 재조회(GET /sessions) → otherSession 제외된 응답.
  await expect(page.getByText('Windows NT', { exact: false })).toHaveCount(0)

  // 현재 세션 카드는 여전히 표시
  await expect(page.getByText('Macintosh', { exact: false })).toBeVisible()
})

// ─────────────────────────────────────────────────────────────────────────────
// Edge 1 — S-4: 현재 세션 항목의 강제 종료 버튼 disabled + "현재 세션" 배지 표시
//
// Given  alice 가 로그인, /settings/sessions 진입
// When   페이지 렌더 완료
// Then   current=true 세션 카드에 "현재 세션" 텍스트 표시
//        해당 카드의 "세션 종료" 버튼이 disabled
// ─────────────────────────────────────────────────────────────────────────────
test('S-4 edge — 현재 세션 카드의 강제 종료 버튼이 disabled + "현재 세션" 배지 표시', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/settings/sessions')

  await expect(page.getByRole('heading', { name: '활성 세션 관리' })).toBeVisible()

  // ── Then. "현재 세션" 배지 텍스트가 표시됨 (S-4 / FR-7) ──────────────────
  // SessionList.tsx 의 CurrentSessionBadge 컴포넌트 — "현재 세션" 텍스트 렌더
  await expect(page.getByText('현재 세션')).toBeVisible()

  // ── Then. 현재 세션 카드의 "세션 종료" 버튼이 disabled ───────────────────
  // SessionCard: disabled={session.current || isRevoking} (SessionList.tsx:102)
  const allRevokeButtons = page.getByRole('button', { name: '세션 종료' })
  await expect(allRevokeButtons).toHaveCount(2)

  // disabled 버튼이 정확히 1개 (현재 세션 카드)
  const disabledButton = page.locator('button[aria-label="세션 종료"][disabled]')
  await expect(disabledButton).toHaveCount(1)
})

// ─────────────────────────────────────────────────────────────────────────────
// Edge 2 — S-3: IDOR 방어 — 타인 sid 로 직접 DELETE API 호출 시 404
//
// Given  alice 가 로그인된 상태
// When   bob 의 sid 로 직접 DELETE /api/v1/auth/sessions/{bob-sid} 호출
// Then   404 Not Found 응답 (세션 존재 여부 노출 없음)
//
// API 레벨 검증 — UI 의 "세션 종료" 버튼은 본인 세션만 노출하므로
// IDOR 는 직접 HTTP 호출로만 재현 가능.
// ─────────────────────────────────────────────────────────────────────────────
test('S-3 edge — 타인 sid 직접 DELETE API 호출 시 404 (IDOR 방어)', async ({ page }) => {
  await loginAsAlice(page)

  // ── When. 타인(bob) sid 로 직접 DELETE API 호출 ───────────────────────────
  // page.evaluate 로 브라우저 컨텍스트에서 fetch 실행 → MSW 인터셉트 적용됨.
  const response = await page.evaluate(async (sid: string) => {
    const res = await fetch(`/api/v1/auth/sessions/${sid}`, {
      method: 'DELETE',
      headers: { 'X-XSRF-TOKEN': '' },
    })
    return { status: res.status }
  }, bobSessionSid)

  // ── Then. 404 Not Found (S-3 / FR-4 / NFR-1) ─────────────────────────────
  expect(response.status).toBe(404)
})

// ─────────────────────────────────────────────────────────────────────────────
// EC-4 (5초 캐시 지연) — 검증 한계 명시
//
// 강제 종료 직후 그 세션의 access token 이 최대 5초간 유효할 수 있다.
// (SidRevokeJwtConverter EC-29 Caffeine 5s TTL 캐시 — spec §EC-4)
//
// E2E 에서 이 5초 윈도우를 실시간 대기로 검증하는 것은 비현실적이다.
//   - page.waitForTimeout(5000) 은 BTS E2E 절대 금지 패턴 (qa-engineer.md §절대 금지).
//   - 5초 sleep 은 CI 시간을 크게 증가시키고 flaky 요인이 된다.
//
// 대신 검증 전략.
//   - revoke API 성공(204) → TanStack Query invalidate → 목록 재조회 → 카드 제거를
//     S-2 happy path 에서 확인한다 (세션 데이터 레벨 즉시 반영).
//   - access token 의 실제 차단은 refresh chain 즉시 무효화
//     (revokeChainFromSession — spec §FR-3) 로 보장된다.
//     새 토큰 발급 시도가 즉시 차단되므로 5초 캐시는 짧은 grace period 에 불과하다.
//   - 해당 캐시 TTL 동작의 통합 테스트는 backend AuthControllerTest (Testcontainers)
//     로 위임하는 것이 적합하다.
// ─────────────────────────────────────────────────────────────────────────────
