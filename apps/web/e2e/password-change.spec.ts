// 비밀번호 변경 흐름 E2E — /settings/password
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

/**
 * MSW 브라우저 모드 사용 — serviceWorkers:'block' 절대 금지 (e2e-msw-serviceworker-block).
 * seed currentPassword = "CurrentPass123!" (password-handlers.ts 상수와 동일).
 * 비밀번호 입력은 fill() 사용 — type() 긴 문자열 timeout 함정 회피 (vitest-usertype-long-string-timeout).
 * getByRole exact:true 사용 — strict mode violation 회피 (playwright-getbyrole-exact-strict-mode).
 */

test.describe('FR-AU-05 비밀번호 변경 흐름 (/settings/password)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건 — loginAsAlice 헬퍼 재사용
    await loginAsAlice(page)
    // Given. /settings/password 진입
    await page.goto('/settings/password')
    await expect(page.getByRole('heading', { name: '비밀번호 변경', exact: true })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 Happy Path — 정상 비밀번호 변경
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   /settings/password 진입 (alice 로그인 완료)
   * When    현재="CurrentPass123!", 새="NewPass4567!@", 확인="NewPass4567!@" 입력 후 제출
   * Then    성공 메시지 "비밀번호가 변경되었습니다" 노출
   *         "다른 기기의 세션은 로그아웃되었습니다" 안내 노출
   *         3 입력 필드 초기화
   */
  test('S1 happy — 정상 변경 시 성공 메시지와 세션 로그아웃 안내 노출', async ({ page }) => {
    // When. 3 필드 입력
    await page.getByLabel('현재 비밀번호').fill('CurrentPass123!')
    await page.getByLabel('새 비밀번호').fill('NewPass4567!@')
    await page.getByLabel('새 비밀번호 확인').fill('NewPass4567!@')

    // When. 제출
    await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

    // Then. 성공 메시지 노출
    await expect(page.getByText('비밀번호가 변경되었습니다')).toBeVisible()
    await expect(page.getByText('다른 기기의 세션은 로그아웃되었습니다')).toBeVisible()

    // Then. 3 필드 초기화 (value 빈 문자열)
    await expect(page.getByLabel('현재 비밀번호')).toHaveValue('')
    await expect(page.getByLabel('새 비밀번호')).toHaveValue('')
    await expect(page.getByLabel('새 비밀번호 확인')).toHaveValue('')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 현재 비밀번호 불일치
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   /settings/password 진입
   * When    현재="WrongPass999!" (seed 불일치), 새="NewPass4567!@", 확인 동일 제출
   * Then    "현재 비밀번호가 일치하지 않습니다." 에러 메시지 노출
   *         폼이 유지됨 (성공 메시지 없음)
   */
  test('S2 현재 비밀번호 불일치 — 에러 메시지 노출, 폼 유지', async ({ page }) => {
    // When. 현재 비번을 틀리게 입력
    await page.getByLabel('현재 비밀번호').fill('WrongPass999!')
    await page.getByLabel('새 비밀번호').fill('NewPass4567!@')
    await page.getByLabel('새 비밀번호 확인').fill('NewPass4567!@')

    await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

    // Then. 에러 메시지 노출
    await expect(page.getByText('현재 비밀번호가 일치하지 않습니다.')).toBeVisible()

    // Then. 성공 메시지 없음 — 폼 유지 확인
    await expect(page.getByText('비밀번호가 변경되었습니다')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3/S4 정책 위반 — 12자 미만 새 비밀번호
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   /settings/password 진입
   * When    새 비밀번호로 "short" (12자 미만 + 복잡도 부족) 입력 후 제출
   * Then    정책 위반 메시지 노출 (MIN_LENGTH: "최소 12자 이상")
   */
  test('S3/S4 정책 위반 — 약한 새 비밀번호 시 POLICY_VIOLATION 메시지 노출', async ({ page }) => {
    // When. 정책 위반 비밀번호 입력 ("short" = 5자, 소문자만 → MIN_LENGTH + COMPLEXITY 위반)
    await page.getByLabel('현재 비밀번호').fill('CurrentPass123!')
    await page.getByLabel('새 비밀번호').fill('short')
    await page.getByLabel('새 비밀번호 확인').fill('short')

    await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

    // Then. MIN_LENGTH 위반 메시지 노출
    await expect(page.getByText('비밀번호는 최소 12자 이상이어야 합니다.')).toBeVisible()

    // Then. 성공 메시지 없음
    await expect(page.getByText('비밀번호가 변경되었습니다')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 확인 불일치 — 클라이언트 차단 (네트워크 요청 없음)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   /settings/password 진입
   * When    새="NewPass4567!@", 확인="Different123!" (불일치) 입력 후 제출
   * Then    "새 비밀번호가 일치하지 않습니다." 클라이언트 에러 메시지 노출
   *         네트워크 요청 0 — API 호출 없음 (클라이언트 단에서 차단)
   */
  test('S6 확인 불일치 — 클라이언트 차단, API 요청 없음', async ({ page }) => {
    // 네트워크 요청 감시 셋업 — POST /me/password 가 발생하면 실패
    let passwordApiCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/users/me/password') && req.method() === 'POST') {
        passwordApiCalled = true
      }
    })

    // When. 확인 비번을 다르게 입력
    await page.getByLabel('현재 비밀번호').fill('CurrentPass123!')
    await page.getByLabel('새 비밀번호').fill('NewPass4567!@')
    await page.getByLabel('새 비밀번호 확인').fill('Different123!')

    await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

    // Then. 클라이언트 에러 메시지 노출
    await expect(page.getByText('새 비밀번호가 일치하지 않습니다.')).toBeVisible()

    // Then. API 호출 없음 확인
    // 에러가 즉시 동기 렌더링되므로 짧은 대기 없이 바로 확인 가능
    expect(passwordApiCalled).toBe(false)

    // Then. 성공 메시지 없음
    await expect(page.getByText('비밀번호가 변경되었습니다')).not.toBeVisible()
  })
})
