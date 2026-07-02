// FR-API-04 Task 9 E2E — PAT(Personal Access Token) 셀프서비스 설정 페이지 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지(전역 playwright.config 그대로 사용)
//   - msw-derived-behavior-shared-store-e2e / EC-7: pat-handlers.ts의 patStore는 시드/리셋 헤더가 없는
//     stateful Map이라 page.reload()/goto 재진입은 store를 초기화할 수 있어(가짜 그린) — 로그인 후
//     /settings/pats 로 최초 진입(goto)은 1회만 하고, 이후 데이터 생성/조회/폐기는 전부 SPA 내부
//     클릭(폼 submit, 모달 닫기, 인라인 확인)으로만 수행한다(webhook.spec.ts 동형 패턴).
//   - playwright-getbyrole-exact-strict-mode: PAT 행/버튼 aria-label에 name을 포함해 exact:true로 한정
//   - e2e-fixture-whoami-userid-alignment: alice 기본 fixture는 mfaEnrollmentRequired:false —
//     /settings/pats는 requireAuth+requirePasswordChanged+requireMfaEnrolled 체인이지만 별도 MFA
//     시나리오 토글 없이 loginAsAlice(page)만으로 가드를 통과한다(session-management.spec.ts /
//     account-links.spec.ts와 동일 — 두 라우트 모두 같은 requireAuthAndPasswordChanged 가드 사용).

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 URL + 라벨 상수 — PatCreateForm.tsx / PatList.tsx / PatTokenModal.tsx 고정 한국어 문구 미러
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_URL = '/settings/pats'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 발급 폼에 name 입력 + scope 1개 체크 + "발급" 클릭 (모달이 뜨는 지점까지)
// ─────────────────────────────────────────────────────────────────────────────

async function submitCreateForm(page: Page, name: string): Promise<void> {
  await page.getByLabel('이름', { exact: true }).fill(name)
  await page.getByRole('checkbox', { name: 'read:issues', exact: true }).check()
  await page.getByRole('button', { name: '발급', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
}

/** 발급 → 토큰 모달까지 띄운 뒤 "닫기"로 닫아 목록에 반영시키는 헬퍼(S2/S3의 Given 전제 데이터 생성용). */
async function issuePatAndCloseModal(page: Page, name: string): Promise<void> {
  await submitCreateForm(page, name)
  await page.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — PAT 발급 → raw token 1회 노출 모달 표시
//
// Given   alice 로그인 → /settings/pats 진입
// When    이름 입력 + scope 1개 선택 + 만료 기간(기본 30일) 유지 + "발급" 클릭
// Then    토큰 모달 표시 — 제목/재확인 불가 경고 문구/토큰 값/복사 버튼 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 PAT 발급 — raw token 1회 노출 모달 표시 (FR-API-04)', () => {
  test('Given name/scope/만료 입력 When 발급 클릭 Then 토큰 1회 모달 표시', async ({ page }) => {
    // Given. alice 로그인 → 설정 페이지 진입
    await loginAsAlice(page)
    await page.goto(PAGE_URL)
    await expect(page.getByRole('heading', { name: 'Personal Access Token', exact: true })).toBeVisible()

    // When. name 입력 + scope 1개 선택 + 발급
    await submitCreateForm(page, 'CI deploy token')

    // Then. 모달 제목 + 재확인 불가 경고 문구 표시
    await expect(page.getByRole('heading', { name: '토큰이 발급되었습니다', exact: true })).toBeVisible()
    await expect(
      page.getByText('이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없으니 안전한 곳에 보관하세요.'),
    ).toBeVisible()

    // Then. raw token 값이 code 블록에 노출됨(빈 문자열 아님)
    const tokenCode = page.locator('[role="dialog"] code')
    await expect(tokenCode).toBeVisible()
    const tokenText = await tokenCode.textContent()
    expect(tokenText).not.toBeNull()
    expect(tokenText?.trim().length ?? 0).toBeGreaterThan(0)

    // Then. "복사" 버튼 노출(복사 가능)
    await expect(page.getByRole('button', { name: '복사', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 모달 닫은 뒤 목록 반영 (token 값은 목록에 없음)
//
// Given   alice 로그인 → /settings/pats 진입 → PAT 1건 발급(모달 표시 상태)
// When    모달 "닫기" 클릭
// Then    목록에 새 PAT 이름이 나타남 + 발급 시 보였던 raw token 문자열은 화면 어디에도 없음
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 모달 닫은 뒤 목록 반영 — token 값 목록 미노출 (FR-API-04)', () => {
  test('Given 발급 완료 모달 표시 When 모달 닫기 Then 목록에 반영 + token 미노출', async ({ page }) => {
    // Given. alice 로그인 → 설정 페이지 진입 → PAT 발급(모달 표시까지)
    await loginAsAlice(page)
    await page.goto(PAGE_URL)
    const name = '목록 반영 확인용 token'
    await submitCreateForm(page, name)

    const tokenCode = page.locator('[role="dialog"] code')
    const tokenText = await tokenCode.textContent()
    expect(tokenText).not.toBeNull()
    const rawToken = tokenText as string

    // When. 모달 "닫기" 클릭 (SPA 내부 상태 변경 — 페이지 이동/리로드 없음)
    await page.getByRole('button', { name: '닫기', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 새 PAT 이름이 나타남
    const row = page.getByRole('listitem', { name, exact: true })
    await expect(row).toBeVisible()
    await expect(row.getByText('read:issues')).toBeVisible()

    // Then. 발급 시 보였던 raw token 문자열은 목록/화면 어디에도 더 이상 없음
    await expect(page.getByText(rawToken)).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — PAT 취소(폐기) → 목록에서 제거
//
// Given   alice 로그인 → /settings/pats 진입 → PAT 1건 발급 후 목록 반영(Given 전제)
// When    해당 행 "폐기" 클릭 → 인라인 확인 "확인" 클릭
// Then    목록에서 제거(빈 상태 문구 재노출)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 PAT 취소(폐기) → 목록에서 제거 (FR-API-04)', () => {
  test('Given 발급된 PAT 1건 When 폐기 확인 Then 목록에서 제거', async ({ page }) => {
    // Given. alice 로그인 → 설정 페이지 진입 → PAT 1건 발급 후 목록 반영
    await loginAsAlice(page)
    await page.goto(PAGE_URL)
    const name = '폐기 대상 token'
    await issuePatAndCloseModal(page, name)
    await expect(page.getByRole('listitem', { name, exact: true })).toBeVisible()

    // When. 행 "폐기" 버튼 클릭 → 인라인 확인 상태로 전환(aria-label = `${name} 폐기`, PatList.tsx)
    await page.getByRole('button', { name: `${name} 폐기`, exact: true }).click()
    await expect(page.getByText('이 PAT를 폐기하면 되돌릴 수 없습니다. 계속하시겠습니까?')).toBeVisible()

    // When. 인라인 "확인" 클릭(aria-label = `${name} 폐기 확인`)
    await page.getByRole('button', { name: `${name} 폐기 확인`, exact: true }).click()

    // Then. 목록에서 제거 — 행 사라짐 + 빈 상태 문구 재노출
    await expect(page.getByRole('listitem', { name, exact: true })).toHaveCount(0)
    await expect(page.getByText('발급된 PAT가 없습니다.', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — scope 미강제 경고 배너 노출
//
// Given   alice 로그인
// When    /settings/pats 진입
// Then    "scope는 아직 강제되지 않으며..." 경고 배너가 상시 노출됨(발급 이력과 무관)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 scope 미강제 경고 배너 노출 (FR-API-04)', () => {
  test('Given /settings/pats 진입 Then scope 미강제 경고 배너 표시', async ({ page }) => {
    // Given/When. alice 로그인 → 설정 페이지 진입
    await loginAsAlice(page)
    await page.goto(PAGE_URL)
    await expect(page.getByRole('heading', { name: 'Personal Access Token', exact: true })).toBeVisible()

    // Then. scope 미강제 경고 배너 노출(PatCreateForm.tsx 상시 렌더)
    await expect(
      page.getByText(
        'scope는 아직 강제되지 않으며, 이 토큰은 계정 전체 권한을 가집니다. 발급 후 안전하게 보관하세요.',
      ),
    ).toBeVisible()
  })
})
