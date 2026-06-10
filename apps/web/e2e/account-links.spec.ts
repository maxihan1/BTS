// FR-AU-08/08b D7 E2E — 계정 연결 설정 페이지 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - msw-derived-behavior-shared-store-e2e: linkStore는 MSW 브라우저 인메모리 — 플래그로 분기
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 시 data-testid / exact:true / 컨테이너 한정
//   - e2e-fixture-whoami-userid-alignment: alice userId '00000000-...-001' — auth-fixtures.ts 정합
//   - ui-pr-defer-e2e-regression-latent: 기존 auth E2E 회귀 확인 필수 (별도 실행)
//   - msw-mutation-stateful-refetch: linkStore stateful — mutation 후 refetch 화면 갱신 검증
//   - worktree-stale-base-rebase-and-e2e-msw-traps: account-link-handlers는 CSRF 미검증 → XSRF 쿠키 불필요
//
// SCENARIO_KEY 상수는 src/mocks/account-link-fixtures.ts 와 동일.
// src→e2e 역방향 import 금지(tsconfig.app.json include:"src") — 여기서 별도 상수로 정의한다.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 플래그 키 상수 — src/mocks/account-link-fixtures.ts SCENARIO_KEY와 동일
// src→e2e 역방향 import 금지(tsconfig.app.json include:"src") — 복사 선언
// ─────────────────────────────────────────────────────────────────────────────

const SCENARIO_KEY = {
  STEP_UP_VALID: 'msw:account-link:step-up-valid',
  REAUTH_FAIL: 'msw:account-link:reauth-fail',
  CONFLICT: 'msw:account-link:conflict',
  PROVIDER_UNAVAILABLE: 'msw:account-link:provider-unavailable',
  LAST_METHOD: 'msw:account-link:last-method',
} as const


// ─────────────────────────────────────────────────────────────────────────────
// 시드 전략 설명
//
// linkStore는 MSW 브라우저 워커 인메모리이므로:
//   1. page.evaluate fetch(POST /api/v1/auth/account/links)는 Vite 프록시(8080)와 충돌한다.
//      → S5/S6에서는 UI 흐름으로 연결을 먼저 생성한다 (S2 패턴 재사용).
//   2. 각 test는 새 browser context → linkStore 자동 초기화(격리 보장).
//   3. STEP_UP_VALID 플래그는 reauth 성공 시 MSW reauthHandler가 자동 세팅하므로
//      addInitScript 주입 없이 재인증 흐름을 거치면 된다.
//   4. LAST_METHOD 플래그는 연결 완료 후 page.evaluate로 localStorage.setItem 호출.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 빈 상태 목록 표시
//
// Given   alice 로그인 → /settings/account-links 진입
// When    linkStore가 비어 있음 (초기 상태)
// Then    빈 상태 메시지 + "외부 계정 연결하기" CTA 버튼 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 빈 상태 목록 표시 (FR-AU-08)', () => {
  test('Given 로그인 상태 When /settings/account-links 진입 Then 빈 상태 안내 + CTA 표시', async ({ page }) => {
    // Given. alice 로그인 — MSW auth-handlers.ts 처리 (loginAsAlice 패턴)
    await loginAsAlice(page)

    // When. 계정 연결 설정 페이지 진입
    await page.goto('/settings/account-links')

    // Then. 페이지 헤딩 확인
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Then. 빈 상태 메시지 확인 (AccountLinkList 빈 상태 렌더)
    await expect(page.getByText('연결된 외부 계정이 없습니다.')).toBeVisible()

    // Then. CTA 버튼 확인 — exact:true로 "외부 계정 연결하기"와 "계정 추가" 텍스트 중복 방지
    await expect(page.getByRole('button', { name: '외부 계정 연결하기', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — LDAP 계정 연결 (step-up 재인증 → 연결 성공)
//
// Given   alice 로그인, step-up 미보유
// When    "외부 계정 연결하기" → LDAP 선택 → "연결" 클릭
// Then    ReauthDialog(재인증 모달) 표시 → 비밀번호 입력 → 확인
//         → 자동으로 step-up 유효 → 연결 성공 토스트 + 카드 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 LDAP 계정 연결 — step-up 재인증 후 연결 (FR-AU-08)', () => {
  test('Given step-up 미보유 When LDAP 연결 시도 Then 재인증 모달 → 성공 → 카드 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 계정 연결 설정 페이지 진입
    await page.goto('/settings/account-links')
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // When. "외부 계정 연결하기" CTA 클릭 → AddAccountDialog 열기
    await page.getByRole('button', { name: '외부 계정 연결하기', exact: true }).click()

    // Then. AddAccountDialog 표시 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByText('연결할 인증 방식을 선택하세요.')).toBeVisible()

    // When. BTS LDAP 선택 — aria-label로 한정 (getByRole strict mode 회피)
    await page.getByRole('radio', { name: 'BTS LDAP (LDAP)', exact: true }).click()

    // Then. LDAP 인라인 폼 표시 확인
    await expect(page.getByLabel('사용자명')).toBeVisible()
    await expect(page.getByLabel('비밀번호')).toBeVisible()

    // When. LDAP 자격증명 입력
    await page.getByLabel('사용자명').fill('alice')
    await page.getByLabel('비밀번호').fill('Test1234!')

    // When. "연결" 버튼 클릭 → step-up 미보유 → 403 → ReauthDialog 자동 오픈
    await page.getByRole('button', { name: '연결', exact: true }).click()

    // Then. ReauthDialog(재인증 모달) 표시 — 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('heading', { name: '재인증 필요', exact: true })).toBeVisible()

    // Then. LOCAL 재인증 폼 표시 (alice는 LOCAL password 보유 — hasLocalPassword:true)
    // reauth-password id로 비밀번호 필드 한정
    const reauthPasswordInput = page.locator('#reauth-password')
    await expect(reauthPasswordInput).toBeVisible()

    // When. 재인증 비밀번호 입력 → "확인" 클릭
    // 재인증 성공 시 MSW reauthHandler가 STEP_UP_VALID 플래그를 자동 세팅
    await reauthPasswordInput.fill('password')
    // 재인증 다이얼로그의 "확인" 버튼 — form="reauth-form" submit 버튼
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 재인증 완료 후 LDAP 연결 자동 재개 → 성공 토스트
    await expect(page.getByText('외부 계정이 연결되었습니다.')).toBeVisible()

    // Then. AddAccountDialog 닫힘 확인 (onSuccess에서 setAddDialogOpen(false))
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 연결된 LDAP 카드 표시 (stateful linkStore 반영 — GET 재조회)
    await expect(page.getByText('BTS LDAP')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4a — SSO 연결 시작 (재인증 → authorizeUrl 네비게이션 인터셉트)
//
// Given   alice 로그인
// When    "외부 계정 연결하기" → Corp SAML 선택 → "연결" → 재인증 모달 → 재인증 성공
// Then    POST /api/v1/auth/account/links/sso/start → authorizeUrl 반환
//         → window.location.assign → /saml2/authenticate/saml-corp 네비게이션 인터셉트
//
// 설계 설명.
//   - 클라이언트 stepUpExpiresAt 상태는 초기 null이므로 "연결" 클릭 시 ReauthDialog가 열린다.
//   - reauth 성공 → MSW reauthHandler가 STEP_UP_VALID 플래그 자동 세팅 + stepUpExpiresAt 반환.
//   - 재인증 완료 후 pendingAction(ssoStart) 자동 재개 → ssoLinkStart → window.location.assign.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4a SSO 연결 시작 — 재인증 후 authorizeUrl 네비게이션 시도 검증 (FR-AU-08b)', () => {
  test('Given 재인증 완료 When SAML 선택 + 연결 Then /saml2/authenticate/* 로 네비게이션 시도', async ({ page }) => {
    // Given. /saml2/authenticate/** 네비게이션을 page.route로 인터셉트
    // window.location.assign이 풀 네비게이션을 일으키므로 page.route로 잡는다
    let capturedUrl: string | null = null
    await page.route('**/saml2/authenticate/**', (route) => {
      capturedUrl = route.request().url()
      void route.fulfill({ status: 200, body: '' })
    })

    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 계정 연결 설정 페이지 진입
    await page.goto('/settings/account-links')
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // When. "외부 계정 연결하기" → Corp SAML 선택
    await page.getByRole('button', { name: '외부 계정 연결하기', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('radio', { name: 'Corp SAML (SAML)', exact: true }).click()

    // Then. SSO 안내 문구 표시 확인
    await expect(page.getByText('외부 로그인으로 이동합니다.')).toBeVisible()

    // When. "연결" 버튼 클릭 → step-up 미보유 → 403 → ReauthDialog 자동 오픈
    await page.getByRole('button', { name: '연결', exact: true }).click()

    // Then. 재인증 모달 표시
    await expect(page.getByRole('heading', { name: '재인증 필요', exact: true })).toBeVisible()

    // When. LOCAL 재인증 비밀번호 입력 → 확인
    // reauth 성공 → STEP_UP_VALID 플래그 자동 세팅 → pendingAction(ssoStart) 재개
    await page.locator('#reauth-password').fill('password')
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. /saml2/authenticate/saml-corp 로 네비게이션 시도 확인
    await expect(async () => {
      expect(capturedUrl).not.toBeNull()
      expect(capturedUrl).toMatch(/\/saml2\/authenticate\/saml-corp$/)
    }).toPass({ timeout: 8_000 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4b — SSO 연결 콜백 (?link=success 직접 진입)
//
// Given   alice 로그인
// When    /settings/account-links?link=success 직접 진입
// Then    "외부 계정 연결이 완료되었습니다." 성공 토스트 표시
//         URL에서 ?link= 쿼리 파라미터 제거(EC7)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4b SSO 연결 콜백 — ?link=success 진입 토스트 + 쿼리 제거 (FR-AU-08b)', () => {
  test('Given ?link=success 진입 Then 성공 토스트 표시 + URL에서 ?link 제거', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /settings/account-links?link=success 직접 진입 (SSO IdP 콜백 시뮬레이션)
    await page.goto('/settings/account-links?link=success')

    // Then. 페이지 헤딩 확인
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Then. 성공 토스트 표시 (accountLinkLabels.callback.link.success)
    // strict mode violation 방지 — sonner가 동일 메시지를 복수 렌더할 수 있으므로 .first() 한정
    await expect(page.getByText('외부 계정 연결이 완료되었습니다.').first()).toBeVisible()

    // Then. URL에서 ?link= 쿼리 파라미터 제거 (EC7 — 새로고침 재표시 방지)
    // onClearCallbackSearch → navigate({ search: {}, replace: true }) → URL 정리
    await expect(async () => {
      const url = new URL(page.url())
      expect(url.searchParams.has('link')).toBe(false)
    }).toPass({ timeout: 5_000 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 계정 연결 해제 (연결 → 해제 흐름 통합)
//
// Given   alice 로그인, step-up 미보유
//         재인증 후 LDAP 연결 → 카드 표시 상태 (S2 흐름)
// When    "해제" 버튼 클릭 → 확인 다이얼로그 → "해제" 확인
//         (step-up이 이미 유효하므로 재인증 불필요)
// Then    204 → 카드가 목록에서 사라짐 (refetch 반영)
//
// 설계 설명.
//   - page.evaluate fetch 시드는 Vite 프록시 설정(8080)과 충돌하므로 UI 흐름으로 연결을 생성한다.
//   - S2 흐름으로 연결 후 stepUpExpiresAt이 유효하므로 해제 시 재인증 불필요.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 계정 연결 해제 (FR-AU-08)', () => {
  test('Given LDAP 연결 후 step-up 유효 When 해제 확인 Then 204 + 카드 사라짐', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 계정 연결 설정 페이지 진입
    await page.goto('/settings/account-links')
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Given. LDAP 연결 (S2 흐름) — 재인증 후 연결
    await page.getByRole('button', { name: '외부 계정 연결하기', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('radio', { name: 'BTS LDAP (LDAP)', exact: true }).click()
    await page.getByLabel('사용자명').fill('alice')
    await page.getByLabel('비밀번호').fill('Test1234!')
    await page.getByRole('button', { name: '연결', exact: true }).click()
    // 재인증 모달 → 비밀번호 입력
    await expect(page.getByRole('heading', { name: '재인증 필요', exact: true })).toBeVisible()
    await page.locator('#reauth-password').fill('password')
    await page.getByRole('button', { name: '확인', exact: true }).click()
    // 연결 성공 토스트 대기
    await expect(page.getByText('외부 계정이 연결되었습니다.').first()).toBeVisible()
    // AddAccountDialog 닫힘 대기
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. LDAP 카드 표시 확인
    await expect(page.getByText('BTS LDAP')).toBeVisible()

    // When. LDAP 카드 "해제" 버튼 클릭
    // 연결된 카드가 1개이므로 첫 번째 해제 버튼 클릭 (strict mode 회피)
    const unlinkButton = page.getByRole('button', { name: '해제', exact: true })
    await expect(unlinkButton).toHaveCount(1)
    await unlinkButton.click()

    // Then. 확인 다이얼로그 표시
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await expect(page.getByText('이 계정 연결을 해제하시겠습니까?')).toBeVisible()

    // When. "해제" 확인 버튼 클릭 (alertdialog 컨테이너 한정 — "취소"와 구분)
    const alertDialog = page.getByRole('alertdialog')
    await alertDialog.getByRole('button', { name: '해제', exact: true }).click()

    // Then. 성공 토스트 표시 (accountLinkLabels.toast.unlinkSuccess)
    await expect(page.getByText('계정 연결이 해제되었습니다.').first()).toBeVisible()

    // Then. 카드가 목록에서 사라짐 (TanStack Query invalidate → refetch)
    await expect(page.getByText('BTS LDAP')).toHaveCount(0)

    // Then. 빈 상태 메시지 다시 표시
    await expect(page.getByText('연결된 외부 계정이 없습니다.')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 마지막 인증수단 409 (해제 불가)
//
// Given   alice 로그인
//         재인증 후 LDAP 연결 (연결 완료 → stepUpExpiresAt 유효)
//         이후 LAST_METHOD 플래그를 page.evaluate로 localStorage에 추가
// When    "해제" → 확인 → DELETE → 409 last_login_method
// Then    "마지막 로그인 수단은 해제할 수 없습니다." 토스트 표시
//         목록 불변 (카드 여전히 표시)
//
// 설계 설명.
//   - LAST_METHOD 플래그는 연결 후에 세팅해야 한다.
//     연결 시 POST /links는 STEP_UP_VALID로 게이팅되며 LAST_METHOD와 무관하다.
//   - 연결 완료 후 page.evaluate로 localStorage.setItem(LAST_METHOD, 'true')를 호출한다.
//   - stepUpExpiresAt이 유효하므로 해제 시도 시 클라이언트는 직접 DELETE를 시도하고 409를 받는다.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6 마지막 인증수단 409 — 해제 불가 안내 (FR-AU-08)', () => {
  test('Given LDAP 연결 후 LAST_METHOD 플래그 When 해제 시도 Then 409 + 마지막수단 안내 + 목록 불변', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 계정 연결 설정 페이지 진입
    await page.goto('/settings/account-links')
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Given. LDAP 연결 (S2 흐름) — 재인증 후 연결
    await page.getByRole('button', { name: '외부 계정 연결하기', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('radio', { name: 'BTS LDAP (LDAP)', exact: true }).click()
    await page.getByLabel('사용자명').fill('alice')
    await page.getByLabel('비밀번호').fill('Test1234!')
    await page.getByRole('button', { name: '연결', exact: true }).click()
    // 재인증 모달 → 비밀번호 입력 (재인증 성공 시 STEP_UP_VALID 플래그 자동 세팅)
    await expect(page.getByRole('heading', { name: '재인증 필요', exact: true })).toBeVisible()
    await page.locator('#reauth-password').fill('password')
    await page.getByRole('button', { name: '확인', exact: true }).click()
    // 연결 성공 토스트 대기
    await expect(page.getByText('외부 계정이 연결되었습니다.').first()).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Given. 카드 표시 확인
    await expect(page.getByText('BTS LDAP')).toBeVisible()

    // Given. 연결 완료 후 LAST_METHOD 플래그 세팅 (page.evaluate → localStorage 직접 접근)
    await page.evaluate((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, SCENARIO_KEY.LAST_METHOD)

    // When. 카드 "해제" 버튼 클릭
    const unlinkButton = page.getByRole('button', { name: '해제', exact: true })
    await expect(unlinkButton).toHaveCount(1)
    await unlinkButton.click()

    // Then. 확인 다이얼로그 표시
    await expect(page.getByRole('alertdialog')).toBeVisible()

    // When. "해제" 확인 (alertdialog 컨테이너 한정)
    const alertDialog = page.getByRole('alertdialog')
    await alertDialog.getByRole('button', { name: '해제', exact: true }).click()

    // Then. "마지막 로그인 수단" 안내 토스트 (accountLinkErrorMessage('last_login_method'))
    await expect(page.getByText('마지막 로그인 수단은 해제할 수 없습니다.').first()).toBeVisible()

    // Then. 목록 불변 — 카드 여전히 표시 (DELETE가 409로 실패했으므로 store 변경 없음)
    await expect(page.getByText('BTS LDAP')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 콜백 — ?reauth=success 직접 진입
//
// Given   alice 로그인
// When    /settings/account-links?reauth=success 직접 진입
// Then    "재인증이 완료되었습니다." 토스트 표시
//         URL에서 ?reauth= 쿼리 파라미터 제거(EC7)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('콜백 — ?reauth=success 진입 토스트 + 쿼리 제거 (FR-AU-08b)', () => {
  test('Given ?reauth=success 진입 Then 재인증 성공 토스트 + URL에서 ?reauth 제거', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. ?reauth=success 직접 진입 (SSO step-up reauth 콜백 시뮬레이션)
    await page.goto('/settings/account-links?reauth=success')

    // Then. 페이지 헤딩 확인
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Then. 재인증 성공 토스트 (accountLinkLabels.callback.reauth.success)
    // strict mode violation 방지 — .first() 한정
    await expect(page.getByText('재인증이 완료되었습니다.').first()).toBeVisible()

    // Then. URL에서 ?reauth= 쿼리 파라미터 제거 (EC7)
    await expect(async () => {
      const url = new URL(page.url())
      expect(url.searchParams.has('reauth')).toBe(false)
    }).toPass({ timeout: 5_000 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 콜백 — ?link=conflict 직접 진입
//
// Given   alice 로그인
// When    /settings/account-links?link=conflict 직접 진입
// Then    "이 신원은 다른 계정에 이미 연결되어 있습니다." 에러 토스트 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('콜백 — ?link=conflict 진입 에러 토스트 (FR-AU-08b)', () => {
  test('Given ?link=conflict 진입 Then 충돌 에러 토스트 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. ?link=conflict 직접 진입
    await page.goto('/settings/account-links?link=conflict')

    // Then. 페이지 헤딩 확인
    await expect(page.getByRole('heading', { name: '계정 연결', exact: true })).toBeVisible()

    // Then. 충돌 에러 토스트 (accountLinkLabels.callback.link.conflict)
    // strict mode violation 방지 — .first() 한정
    await expect(page.getByText('이 신원은 다른 계정에 이미 연결되어 있습니다.').first()).toBeVisible()
  })
})
