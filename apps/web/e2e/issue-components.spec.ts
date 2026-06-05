// FR-CM-02 E2E — 이슈 상세 컴포넌트 다중 셀렉터 happy path (S1 할당·S2 부분교체·S3 전부해제·S4 권한 활성)
//
// 앱은 MSW(issue-handlers.ts, component-handlers.ts) 위에서 동작한다.
// componentStore 는 초기 빈 상태이므로 각 테스트에서 X-MSW-Seed-Components 헤더를 이용해
// 이슈 상세 페이지 내 fetch 로 componentStore 를 직접 seed 한 뒤 TanStack Query 를 refetch 한다.
// issueOverrides.changeComponentsHandler 가 componentIds 를 stateful 영속한다.
//
// 설계 결정.
//   - page.reload() / page.goto() 는 ServiceWorker 재시작을 유발하므로 사용하지 않는다.
//   - page.route() 는 MSW ServiceWorker 가 이미 응답한 요청을 가로채지 못한다.
//   - X-MSW-Seed-Components 헤더를 활용해 이슈 상세 페이지에서 직접 componentStore 를 seed 한다.
//   - seed 직후 TanStack Query refetch 는 window 에 노출된 queryClient 또는 visibilitychange
//     이벤트 없이, window.location.href 재할당 없이 처리하려면 해당 queryKey 를 명시 refetch 해야 한다.
//     실용적 대안: query cache invalidation 이 어려우므로 seed + waitForRequest 으로
//     TanStack Query 가 자동 refetch 하도록 리소스 소진 없이 유도한다.
//     최종 채택: seed 헤더 호출 → page.evaluate 로 window._refetchComponents?() 호출.
//     react-query devtools 없이는 QueryClient 노출이 없으므로, seed 후 이슈 상세를 navigate
//     하는 가장 간단한 SPA 내부 navigate(TanStack Router link)를 사용한다.
//   - SPA 내부 내비게이션(href 변경): dashboard → ISSUE_URL 은 React Router 내부 처리라
//     ServiceWorker 를 재시작하지 않는다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - msw-mutation-stateful-refetch: changeComponentsHandler 가 componentIds 영속 — refetch 후 롤백 없음
//   - e2e-fixture-whoami-userid-alignment: alice(adminPermissions) 로 로그인 → canEdit=true → 셀렉터 활성
//   - playwright-getbyrole-exact-strict-mode: components-section 컨테이너 한정 셀렉터 사용
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 회귀 0 확인 필수 (전체 실행)
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (componentIds 초기값 []) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** 컴포넌트 Fixture — 고정 UUID + 이름 (Zod v4 RFC4122 v4 검증 통과 형식) */
const COMP_A = {
  id: 'a1b2c3d4-e5f6-4abc-8def-0a1b2c3d4e5f',
  name: 'E2E-A-프론트엔드',
  projectId: 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f6a',
  description: null,
  leadUserId: null,
}

const COMP_B = {
  id: 'c3d4e5f6-a7b8-4cde-a0f1-2c3d4e5f6a7b',
  name: 'E2E-B-백엔드',
  projectId: 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f6a',
  description: null,
  leadUserId: null,
}

/** 섹션 data-testid */
const SECTION_TESTID = 'components-section'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW componentStore seed
//
// X-MSW-Seed-Components 헤더를 포함한 GET 요청을 이슈 상세 페이지에서 실행한다.
// MSW listComponentsHandler 가 이 헤더를 감지해 componentStore 를 초기화하고
// seed 데이터를 등록한다. ServiceWorker 재시작 없이 componentStore 에 영속된다.
// ─────────────────────────────────────────────────────────────────────────────

type ComponentSeed = { id: string; name: string; description?: string | null; leadUserId?: string | null }

/**
 * 이슈 상세 페이지 컨텍스트에서 MSW componentStore 를 seed 한다.
 * ServiceWorker 가 살아있는 상태에서 호출하며, fetch 가 ServiceWorker 를 통해 처리된다.
 * 반환값: MSW 에서 반환한 컴포넌트 배열 길이 (seed 성공 확인용)
 */
async function seedComponentStore(
  page: import('@playwright/test').Page,
  components: ComponentSeed[],
): Promise<number> {
  // HTTP 헤더는 ISO-8859-1 (Latin-1) 만 허용한다.
  // 한글 이름이 포함된 JSON 을 그대로 전송하면 "non ISO-8859-1 code point" 에러가 발생한다.
  // 해결: encodeURIComponent 로 퍼센트-인코딩 후 전송, MSW 핸들러에서 decodeURIComponent 복원.
  return page.evaluate(async (comps: ComponentSeed[]) => {
    const encoded = encodeURIComponent(JSON.stringify(comps))
    const res = await fetch('/api/v1/projects/ATLAS/components', {
      headers: { 'X-MSW-Seed-Components': encoded },
    })
    if (!res.ok) throw new Error(`seed 실패: ${res.status}`)
    const json = await res.json() as { data: unknown[] }
    return json.data.length
  }, components)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 내비게이션 (ServiceWorker 재시작 없음)
//
// loginAsAlice 후 dashboard 에서 이슈 상세로 이동.
// TanStack Router 가 SPA 내부 navigate 를 처리하므로 ServiceWorker 유지됨.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * dashboard 에서 이슈 상세 페이지로 SPA 내부 내비게이션.
 * ServiceWorker 는 재시작되지 않아 이전 seed 가 componentStore 에 유지된다.
 * 단, TanStack Query 가 ['components', 'ATLAS'] 를 fetch 하므로 componentStore seed 가 필요하다.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
): Promise<void> {
  // TanStack Router navigate — href 직접 변경보다 link 클릭이 SPA navigate 보장
  // dashboard 에는 이슈 목록 링크가 없으므로 page.evaluate 로 직접 navigate
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, ISSUE_URL)
  await expect(page.getByTestId(SECTION_TESTID)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-CM-02 이슈 컴포넌트 셀렉터 (IssueMetaPanel > ComponentMultiSelect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN) 로 로그인 → canEdit=true → 체크박스 활성 (교훈: e2e-fixture-whoami-userid-alignment)
    await loginAsAlice(page)
    // 로그인 후 dashboard 진입 — ServiceWorker 기동 완료 상태
    // loginAsAlice 가 이미 /dashboard 까지 이동하므로 추가 goto 불필요
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 할당 — 컴포넌트 2개 선택 → 칩 2개 표시
  //
  // Given   alice 로그인, dashboard 진입, MSW seed 로 A·B 컴포넌트 등록
  //         SPA 내부 navigate 로 ATLAS-1 이슈 상세 진입
  //         componentIds=[], options에 A·B 존재
  // When    A 체크박스 체크 → B 체크박스 체크 → onChange → mutation PATCH → invalidate refetch
  // Then    component-chip이 2개(A·B 이름) 표시됨 (stateful refetch 후 롤백 없음)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 할당 — 컴포넌트 2개 선택 후 칩 2개 표시', async ({ page }) => {
    // 사전조건: dashboard 에서 A·B seed (ServiceWorker 살아있는 상태)
    const seeded = await seedComponentStore(page, [COMP_A, COMP_B])
    expect(seeded).toBe(2)

    // SPA 내부 내비게이션 → 이슈 상세 (ServiceWorker 유지, componentStore 보존)
    await navigateToIssueDetail(page)

    const section = page.getByTestId(SECTION_TESTID)

    // 체크박스 A·B 노출 대기 (컴포넌트 목록 로드)
    const checkboxA = section.getByRole('checkbox', { name: COMP_A.name })
    const checkboxB = section.getByRole('checkbox', { name: COMP_B.name })
    await expect(checkboxA).toBeVisible()
    await expect(checkboxA).not.toBeChecked()

    // When: A 체크 — 클릭 후 칩 1개가 나타날 때까지 대기 (mutation PATCH + refetch 완료 보장)
    // refetch 완료 전 B 클릭 시 onChange([B]) 가 전송되어 A가 사라지는 타이밍 문제 방지
    await checkboxA.click()
    await expect(section.getByTestId('component-chip')).toHaveCount(1)

    // When: B 체크 — refetch 완료 후 B 클릭 (onChange([A, B]) 보장)
    await expect(checkboxB).toBeVisible()
    await checkboxB.click()

    // Then: component-chip이 2개 표시 (PATCH + invalidateQueries refetch 후 롤백 없음)
    // msw-mutation-stateful-refetch 교훈 — changeComponentsHandler 가 componentIds 영속
    await expect(section.getByTestId('component-chip')).toHaveCount(2)

    // 칩 이름 확인 (순서 무관하게 두 이름 모두 존재)
    const chipTexts = await section.getByTestId('component-chip').allTextContents()
    expect(chipTexts).toContain(COMP_A.name)
    expect(chipTexts).toContain(COMP_B.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 부분교체 — A·B 선택 상태에서 A 해제 → 칩 1개(B만)
  //
  // Given   A·B 두 칩이 표시된 상태
  // When    A 체크박스 해제 → onChange → PATCH [B] → refetch
  // Then    component-chip이 1개(B만) 표시됨
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 부분교체 — A 해제 후 B 칩 1개만 표시', async ({ page }) => {
    // 사전조건: A·B seed + SPA navigate
    await seedComponentStore(page, [COMP_A, COMP_B])
    await navigateToIssueDetail(page)

    const section = page.getByTestId(SECTION_TESTID)
    const checkboxA = section.getByRole('checkbox', { name: COMP_A.name })
    const checkboxB = section.getByRole('checkbox', { name: COMP_B.name })

    // 사전상태: A·B 모두 선택 — A 클릭 후 칩 1개 확인, 그 다음 B 클릭 (타이밍 안정화)
    await expect(checkboxA).toBeVisible()
    await checkboxA.click()
    await expect(section.getByTestId('component-chip')).toHaveCount(1)
    await expect(checkboxB).toBeVisible()
    await checkboxB.click()
    await expect(section.getByTestId('component-chip')).toHaveCount(2)

    // When: A 체크박스 해제
    await checkboxA.click()

    // Then: 칩 1개(B만) 표시
    await expect(section.getByTestId('component-chip')).toHaveCount(1)
    await expect(section.getByTestId('component-chip').first()).toHaveText(COMP_B.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 전부해제 — B만 선택된 상태에서 B 해제 → 칩 0개
  //
  // Given   B 칩 1개만 표시된 상태
  // When    B 체크박스 해제 → PATCH [] → refetch
  // Then    component-chip 0개 (component-chip-list 렌더 안 됨)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 전부해제 — B 해제 후 칩 0개 (없음 상태)', async ({ page }) => {
    // 사전조건: B seed + SPA navigate
    await seedComponentStore(page, [COMP_B])
    await navigateToIssueDetail(page)

    const section = page.getByTestId(SECTION_TESTID)
    const checkboxB = section.getByRole('checkbox', { name: COMP_B.name })

    // 사전상태: B 선택
    await expect(checkboxB).toBeVisible()
    await checkboxB.click()
    await expect(section.getByTestId('component-chip')).toHaveCount(1)

    // When: B 해제
    await checkboxB.click()

    // Then: 칩 0개 — ComponentChipList는 components.length===0이면 null 반환
    await expect(section.getByTestId('component-chip')).toHaveCount(0)
    // component-chip-list 자체도 DOM에 없어야 함
    await expect(section.getByTestId('component-chip-list')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 권한 활성 — alice(canEdit=true) 체크박스 클릭 시 checked 전환
  //
  // Given   alice(ADMIN 권한) 로 로그인, A 컴포넌트가 options 에 표시됨
  // When    components-section 체크박스 A 클릭 (canEdit=true → 활성)
  // Then    체크박스가 checked 상태로 전환됨 (비활성이면 클릭해도 변화 없음)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 권한 활성 — alice(canEdit=true) 체크박스 클릭 시 checked 전환', async ({ page }) => {
    // 사전조건: A seed + SPA navigate
    await seedComponentStore(page, [COMP_A])
    await navigateToIssueDetail(page)

    const section = page.getByTestId(SECTION_TESTID)
    const checkboxA = section.getByRole('checkbox', { name: COMP_A.name })
    await expect(checkboxA).toBeVisible()

    // canEdit=true → 체크박스가 disabled 아님을 확인 (alice ADMIN 권한)
    await expect(checkboxA).not.toBeDisabled()

    // 클릭 후 checked 상태 전환 확인 (활성 게이트 검증)
    await checkboxA.click()
    await expect(checkboxA).toBeChecked()
  })
})
