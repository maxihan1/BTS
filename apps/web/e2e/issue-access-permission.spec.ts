// FR-PM-05 이슈 접근 권한 E2E — 권한 없는 사용자가 이슈 직접 진입 시 not-found 화면 검증
//
// backend 계약: 권한 없는 이슈 접근은 403 대신 404(미존재와 동일 UX)로 처리한다.
// MSW 핸들러(issue-handlers.ts)는 LS_KEY_PERMISSION_DENIED_KEYS localStorage 키에
// 쉼표 구분 이슈 키 목록이 있으면 GET 단건에서 404를 반환한다.
// Playwright addInitScript 로 goto 전에 localStorage를 설정해 리로드 생존을 보장한다.
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그 패턴
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: data-testid / exact:true 셀렉터
//   - ui-pr-defer-e2e-regression-latent: 기존 issue E2E 회귀 0 동반 실행

import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'
import { LS_KEY_PERMISSION_DENIED_KEYS } from '../src/mocks/issue-handlers'

test.describe('이슈 접근 권한 게이트 (FR-PM-05)', () => {
  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — 권한 없는 이슈 직접 진입 → not-found 화면 (에러 토스트·스택 노출 0)
  //
  // Given  alice 로그인
  //        addInitScript 로 ATLAS-1을 권한거부 키로 설정 (리로드 후에도 생존)
  // When   /issues/ATLAS-1 직접 진입
  // Then   role=alert 영역에 notFound 메시지 노출
  //        heading level=1(이슈 summary) 렌더 안 됨
  //        에러 토스트·스택 트레이스 노출 없음
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 권한 없는 이슈 진입 — not-found 화면, 에러 토스트·스택 노출 없음', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 다음 페이지 로드 전에 localStorage 플래그를 심는다.
    // MSW getIssueHandler 가 이 키 목록을 읽어 ATLAS-1 요청에 404를 반환한다.
    // addInitScript 는 goto 포함 모든 페이지 로드/리로드 직전에 실행되므로 리로드 생존 보장.
    await page.addInitScript((lsKey) => {
      window.localStorage.setItem(lsKey, 'ATLAS-1')
    }, LS_KEY_PERMISSION_DENIED_KEYS)

    // When. 권한 없는 이슈 키로 직접 진입
    await page.goto('/issues/ATLAS-1')

    // Then. role=alert 영역 노출 + notFound 메시지 정합
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(i18nLabels.issueDetail.notFound)

    // Then. 이슈 summary heading(level=1) 은 렌더되지 않아야 함
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(0)

    // Then. 에러 토스트(role=status 또는 data-testid=toast-error)·스택 트레이스 미노출
    // 권한 거부를 404와 동일하게 처리하므로 운영 에러 UI 가 노출되어선 안 된다.
    await expect(page.getByTestId('toast-error')).toHaveCount(0)
    await expect(page.locator('pre')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — 권한 있는 이슈는 정상 렌더 (회귀 가드)
  //
  // Given  alice 로그인 + 권한거부 플래그 없음 (기본 MSW 핸들러)
  // When   /issues/ATLAS-1 직접 진입
  // Then   이슈 상세 화면 정상 렌더 (heading level=1 노출)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 권한 있는 이슈 진입 — 이슈 상세 정상 렌더 (회귀 가드)', async ({ page }) => {
    // Given. alice 로그인 — localStorage 플래그 없음 → MSW 기본 응답(200)
    await loginAsAlice(page)

    // When. 정상 이슈 키로 직접 진입
    await page.goto('/issues/ATLAS-1')

    // Then. heading level=1 (이슈 summary) 렌더 확인
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    // Then. alert 영역(not-found) 미노출
    await expect(page.getByRole('alert')).toHaveCount(0)
  })
})
