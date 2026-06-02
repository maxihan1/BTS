// FR-PM-02 이슈 CREATE 권한 게이트 E2E — "새 이슈" 버튼 활성/비활성 시나리오
//
// project-permissions 응답에 따라 "새 이슈" 버튼이 활성(link)인지 비활성(disabled button)인지 검증한다.
//
// S1 — CREATE 권한 있음 (alice, ADMIN).
//   Given  alice 로그인 + project-permissions CREATE:true (기본 MSW 핸들러)
//   When   /issues 목록 진입
//   Then   new-issue-button 이 활성(enabled, role=link, href=/issues/new), 클릭 시 /issues/new 이동
//
// S2 — CREATE 권한 없음 (비멤버 시뮬레이션).
//   Given  alice 로그인 + project-permissions CREATE:false (MSW worker.use 오버라이드)
//   When   /issues 목록 진입
//   Then   new-issue-button 이 비활성(disabled button), 클릭해도 /issues 유지
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: data-testid 셀렉터로 텍스트 중복 회피
//   - e2e-fixture-whoami-userid-alignment: alice userId 00000000-...-001 (MSW whoami 정합)
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - ui-pr-defer-e2e-regression-latent: 기존 spec 회귀 방지
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { nonMemberProjectPermissions } from '../src/mocks/project-permission-fixtures'

test.describe('이슈 CREATE 권한 게이트 (FR-PM-02 Task-6)', () => {
  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — CREATE 권한 있음: alice(ADMIN) → "새 이슈" 링크 활성
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 CREATE 권한 있음 — new-issue-button 이 link(활성), 클릭 시 /issues/new 이동', async ({ page }) => {
    // Given. alice 로그인 — MSW 기본 핸들러가 CREATE:true 응답
    await loginAsAlice(page)

    // When. 이슈 목록 진입 + 권한 로딩 대기
    await page.goto('/issues')
    const newIssueBtn = page.getByTestId('new-issue-button')
    await expect(newIssueBtn).toBeVisible()

    // Then. role=link (a 태그), href 속성 확인
    await expect(newIssueBtn).toHaveAttribute('href', '/issues/new')

    // Then. 클릭 시 /issues/new 로 이동
    await newIssueBtn.click()
    await page.waitForURL('**/issues/new')
    expect(new URL(page.url()).pathname).toBe('/issues/new')
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — CREATE 권한 없음: MSW worker.use 오버라이드(dev __msw 훅 경유)로 CREATE:false 주입 → disabled button
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 CREATE 권한 없음 — new-issue-button 이 disabled, 클릭해도 /issues 유지', async ({ page }) => {
    // Given. alice 로그인 — /dashboard 진입 완료 시점에 MSW worker.start + __msw 훅 노출 완료
    await loginAsAlice(page)

    // When. 이슈 목록 진입 — 먼저 /issues 로 이동해 앱 마운트 + __msw/__queryClient 훅 준비
    await page.goto('/issues')

    // Given. project-permissions 를 CREATE:false 로 오버라이드 (비멤버 시뮬레이션).
    //
    // MSW worker.use 로 핸들러를 일회성 오버라이드한다.
    // page.goto 가 JS 컨텍스트를 새로 시작하므로 goto 완료 후 오버라이드해야 한다.
    // __msw / __queryClient 훅은 main.tsx dev 게이트에서 worker.start 직후 window 에 노출된다
    // (production 미포함). /issues 앱 마운트 완료 시점을 waitForFunction 으로 확인한다.
    //
    // 순서: goto('/issues') → __msw 준비 확인 → worker.use 오버라이드 →
    //        __queryClient.invalidateQueries 로 강제 refetch → 버튼 비활성 확인.
    await page.waitForFunction(() => {
      const w = window as unknown as { __msw?: unknown; __queryClient?: unknown }
      return w.__msw !== undefined && w.__queryClient !== undefined
    })

    await page.evaluate(async (perms) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const w = window as unknown as { __msw: { worker: { use: (...h: unknown[]) => void }; http: any; HttpResponse: any }; __queryClient: { invalidateQueries: (opts: unknown) => Promise<void> } }
      const { worker, http, HttpResponse } = w.__msw
      // 1. project-permissions 핸들러 오버라이드 — CREATE:false 반환
      worker.use(
        http.get('/api/v1/users/me/project-permissions', ({ request }: { request: Request }) => {
          const url = new URL(request.url)
          return HttpResponse.json({ projectKey: url.searchParams.get('projectKey') ?? 'ATLAS', permissions: perms })
        }),
      )
      // 2. MSW SW-페이지 메시지 채널 워밍업 — worker.use 후 첫 fetch 전에 채널이 비활성일 수 있음.
      //    더미 fetch 한 번으로 오버라이드 핸들러를 SW에 동기화한다(응답은 무시).
      await fetch('/api/v1/users/me/project-permissions?projectKey=ATLAS', {
        headers: { Authorization: 'Bearer mock-access-token-alice' },
      })
      // 3. 캐시 무효화 → refetch 트리거 — 오버라이드 핸들러로 새 응답을 받게 함
      // queryKey 정본: ['project-permissions', projectKey] (use-project-permissions.ts)
      await w.__queryClient.invalidateQueries({ queryKey: ['project-permissions'] })
    }, nonMemberProjectPermissions)

    const newIssueBtn = page.getByTestId('new-issue-button')
    await expect(newIssueBtn).toBeVisible()

    // Then. disabled button (a 태그 아님)
    await expect(newIssueBtn).toBeDisabled()

    // Then. href 속성 없음 (button 태그이므로)
    await expect(newIssueBtn).not.toHaveAttribute('href')

    // Then. 클릭해도 /issues 유지 (disabled button 은 click 이벤트 차단).
    // force:true 로 클릭해도 URL 이 바뀌지 않는지 확인.
    // waitForURL 에 타임아웃을 짧게 주어 이동이 발생하지 않음을 검증한다.
    await newIssueBtn.click({ force: true })
    await expect(page).toHaveURL(/\/issues$/)
    expect(new URL(page.url()).pathname).toBe('/issues')
  })
})
