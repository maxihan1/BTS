// FR-UX-06 PR12 D6 E2E — C3 랜드마크 계약(banner/main/complementary) 인증 화면·로그인 화면 각 1개
// FR-UX-06 PR13 Task 9 확장 — PL-7 landmark 강등(자체 main→section/div) 3파일 중 2개를 단일 main으로 실증
//
// 시나리오 개요.
//   L1. 인증 화면(alice 로그인 후 /dashboards) — banner 1 + main 1 + complementary 1
//   L2. 로그인 화면(미인증 /login)             — main 1
//   L3. 이슈 상세(issues.$key, /issues/ATLAS-1)     — main 1 (PR13 Task 7: 자체 main → section 강등)
//   L4. admin.workflow-schemes(SYSTEM_ADMIN)         — main 1 (PR13 Task 7: 자체 main → div 강등)
//
// 설계 결정.
//   - L1은 /dashboards로 진입한다 — 이 라우트는 자체 <main>/<header>/<aside>를 렌더하지
//     않는 "깨끗한" 페이지다(issues.$key 상세, admin.workflow-schemes.* 등은 자체 <main>을
//     보유해 landmark 중복 위험이 있으므로 회피, ShellLayout.tsx 랜드마크 소유 맵 참고).
//   - 페이지 자체 <header>(issues.index.tsx 등)는 ShellLayout의 <main> 자손이라 HTML-AAM 규칙상
//     암묵적 banner role을 잃는다(header가 main/article/aside/nav/section의 자손이면 role 없음).
//     그래도 회귀 안전을 위해 자체 header가 전혀 없는 /dashboards를 택했다.
//   - L3·L4는 PR12 당시 회피했던 "자체 main 보유" 페이지다. PR13 Task 7이 issues.$key.tsx의
//     <main>을 <section aria-label="이슈 상세">로, admin.workflow-schemes.tsx의 <main>을 강등해
//     문서당 main 1개(WCAG 1.3.1)를 회복했다 — 이 두 시나리오가 그 강등을 실브라우저에서 실증한다.
//   - admin.workflow-schemes는 PR13 Task 8이 SYSTEM_ADMIN 가드를 추가했으므로 L4는
//     loginAsSystemAdmin(비-admin 접근 시 /dashboard 리다이렉트되어 main 자체를 볼 수 없음)을 사용한다.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto로만 페이지 전환.
//   - serviceWorkers:'block' 금지(e2e-msw-serviceworker-block) — 기본 설정 그대로 사용.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { loginAsSystemAdmin, navigateToSchemeList } from './fixtures/workflow-scheme-fixtures'

test.describe('FR-UX-06 PR12 C3 랜드마크(banner/main/complementary)', () => {
  test('L1 Given alice 로그인 When /dashboards 진입 Then banner 1 + main 1 + complementary 1', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    await expect(page.getByRole('banner')).toHaveCount(1)
    await expect(page.getByRole('main')).toHaveCount(1)
    await expect(page.getByRole('complementary')).toHaveCount(1)
  })

  // L2 는 2026-09-03 로그인 모달 전환으로 단언이 바뀌었다.
  // 전환 전. `/login` 은 전체 페이지였고 접근성 트리에 main 이 1개 있었다.
  // 전환 후. 로그인이 Radix Dialog 로 뜨고, 열린 모달은 **배경 형제를 aria-hidden 으로 덮는다**.
  //   그래서 `getByRole('main')` 은 0 이 된다 — 이건 회귀가 아니라 모달의 올바른 접근성 동작이다
  //   (모달이 떠 있는 동안 배경은 보조기술에서 감춰져야 한다).
  // 🛑 그러므로 여기서 main 1 을 되살리려 하지 마라. 되살리는 방법은 Dialog 의 modal 을 끄는 것뿐이고,
  //    그러면 포커스 트랩이 사라져 「닫기 3경로 봉인」(ADR D3)의 근거가 무너진다.
  // 대신 두 가지를 함께 잰다 — ①모달이 접근성 트리를 점유한다 ②DOM 의 main 은 여전히 정확히 1개다
  //   (즉 랜드마크가 사라진 게 아니라 가려진 것뿐이라는 사실).
  test('L2 Given 미인증 When /login 진입 Then 로그인 모달이 트리를 점유하고 DOM main 은 1', async ({
    page,
  }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    await expect(page.getByRole('dialog', { name: 'BTS 로그인' })).toHaveCount(1)
    // 접근성 트리에서는 배경이 감춰진다
    await expect(page.getByRole('main')).toHaveCount(0)
    // 그러나 문서에는 main 이 정확히 하나 살아 있다 (WCAG 1.3.1 문서당 main 1개)
    await expect(page.locator('main')).toHaveCount(1)
  })

  test('L3 Given alice 로그인 When 이슈 상세(issues.$key) 진입 Then main 1(자체 main→section 강등 실증)', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/issues/ATLAS-1')

    // 페이지 정체성 확인 — Task 7 강등 대상인 <section aria-label="이슈 상세">가 실제 렌더됨을 확인
    await expect(page.getByRole('region', { name: '이슈 상세' })).toBeVisible()

    await expect(page.getByRole('main')).toHaveCount(1)
  })

  test('L4 Given SYSTEM_ADMIN 로그인 When admin.workflow-schemes 진입 Then main 1(자체 main→div 강등 실증)', async ({ page }) => {
    // SYSTEM_ADMIN 필요 — PR13 Task 8이 /admin/workflow-schemes에 requireSystemAdmin 가드 추가
    await loginAsSystemAdmin(page)
    // navigateToSchemeList가 goto + 사이드바 nav 가시 검증까지 완료(페이지 정체성 확인 겸용)
    await navigateToSchemeList(page)

    await expect(page.getByRole('main')).toHaveCount(1)
  })
})
