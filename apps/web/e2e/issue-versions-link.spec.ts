// FR-VR-03 E2E — 이슈 상세 화면 Affects/Fix 버전 연결 시나리오 (VersionMultiSelect happy path)
//
// ─────────────────────────────────────────────────────────────────────────────
// 선결 조건 / 현재 상태 (2026-06-10 기준)
// ─────────────────────────────────────────────────────────────────────────────
//
// [A] loginAsAlice 회귀 — session-fixtures.ts 사용 (FR-AU-07 2단계 흐름 반영).
//     issue-fixtures.ts 의 구식 1단계 loginAsAlice 는 FR-AU-07 이후 깨진 상태이며
//     별도 PR 대상이다. 이 파일은 session-fixtures.ts 를 사용한다.
//
// [B] 라우트 배선 완료 — issues.$key.tsx 에 useVersions/useChangeAffectsVersions/
//     useChangeFixVersions 가 배선됐다. S0~S5 전체 시나리오 실행 가능.
//
// [C] issue-components.spec.ts 로그인 회귀 — 별도 PR 대상 (loginAsAlice 회귀).
//     session-fixtures.ts 의 loginAsAlice 는 정상.
//
// 교훈 반영.
//   - e2e-loginasalice-fixture-fr-au-07-regression: session-fixtures.ts 의 loginAsAlice 사용
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 그대로
//   - msw-mutation-stateful-refetch: PATCH 핸들러가 affectsVersionIds/fixVersionIds 영속 확인
//   - playwright-getbyrole-exact-strict-mode: 섹션 컨테이너 한정 셀렉터
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 회귀 0 확인 필수
//   - worktree-stale-base-rebase-and-e2e-msw-traps: MSW store seed 패턴
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (affectsVersionIds/fixVersionIds 초기값 []) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`
const PROJECT_KEY = 'ATLAS'

/** 섹션 data-testid (IssueMetaPanel 구현 기준) */
const AFFECTS_SECTION_TESTID = 'affects-versions-section'
const FIX_SECTION_TESTID = 'fix-versions-section'

/** i18n 라벨 (ko.ts 정본 기준 — import 없이 문자열 상수로 관리) */
const labels = {
  affectsSearchPlaceholder: '영향 버전 검색',
  fixSearchPlaceholder: '수정 버전 검색',
}

// ─────────────────────────────────────────────────────────────────────────────
// 버전 Fixture — Zod v4 RFC4122 v4 검증 통과 형식 고정 UUID
// ─────────────────────────────────────────────────────────────────────────────

/** 영향 버전 테스트용 — UNRELEASED (드롭다운 표시) */
const VER_A = {
  name: 'VR03-E2E-1.0.0',
  description: null,
}

/** 수정 버전 테스트용 — UNRELEASED (드롭다운 표시) */
const VER_B = {
  name: 'VR03-E2E-2.0.0',
  description: null,
}

/** 보관된 버전 — ARCHIVED (드롭다운 미표시 검증용) */
const VER_ARCHIVED = {
  name: 'VR03-E2E-ARCHIVED-0.9.0',
  description: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW versionStore 초기화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW versionStore 를 초기화한다.
 * X-MSW-Reset-Versions: true 헤더를 포함해 GET 목록을 호출하면
 * listVersionsHandler 가 resetVersionStore() 를 실행한다.
 */
async function resetVersionStore(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async (projectKey: string) => {
    await fetch(`/api/v1/projects/${projectKey}/versions`, {
      headers: { 'X-MSW-Reset-Versions': 'true' },
    })
  }, PROJECT_KEY)
}

/**
 * MSW versionStore 에 버전을 생성한다.
 * POST /api/v1/projects/{key}/versions 로 저장소에 영속한다.
 * 반환값: 생성된 버전 id
 */
async function createVersionInStore(
  page: import('@playwright/test').Page,
  versionData: { name: string; description: string | null },
): Promise<string> {
  return page.evaluate(
    async ([projectKey, data]: [string, { name: string; description: string | null }]) => {
      const res = await fetch(`/api/v1/projects/${projectKey}/versions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: data.name }),
      })
      if (!res.ok) throw new Error(`버전 생성 실패: ${res.status}`)
      const json = await res.json() as { data: { id: string } }
      return json.data.id
    },
    [PROJECT_KEY, versionData] as [string, { name: string; description: string | null }],
  )
}

/**
 * ARCHIVED 전환 — versionId 를 ARCHIVED 상태로 전환한다.
 * PATCH /api/v1/projects/{key}/versions/{id}/status body { status: 'ARCHIVED' }
 */
async function archiveVersion(
  page: import('@playwright/test').Page,
  versionId: string,
): Promise<void> {
  await page.evaluate(
    async ([projectKey, id]: [string, string]) => {
      const res = await fetch(`/api/v1/projects/${projectKey}/versions/${id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'ARCHIVED' }),
      })
      if (!res.ok) throw new Error(`ARCHIVED 전환 실패: ${res.status}`)
    },
    [PROJECT_KEY, versionId] as [string, string],
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 내비게이션 (ServiceWorker 재시작 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * dashboard 에서 이슈 상세 페이지로 SPA 내부 내비게이션.
 * ServiceWorker 는 재시작되지 않아 이전 versionStore seed 가 유지된다.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, ISSUE_URL)
  // affects-versions-section 이 렌더될 때까지 대기
  await expect(page.getByTestId(AFFECTS_SECTION_TESTID)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-VR-03 이슈 버전 연결 (IssueMetaPanel > VersionMultiSelect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN) 로 로그인 → canEdit=true → 체크박스/검색 input 활성
    // session-fixtures.ts 의 loginAsAlice 사용 (FR-AU-07 2단계 흐름, issue-fixtures.ts 의 구식 1단계 아님)
    await loginAsAlice(page)
    // loginAsAlice 가 이미 /dashboard 까지 이동 — ServiceWorker 기동 완료 상태
    // versionStore 격리 — 각 테스트 시작 시 초기화
    await resetVersionStore(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S0 — 이슈 상세 진입 시 Affects/Fix 버전 섹션 렌더 확인
  //
  // Given   alice 로 로그인, dashboard 진입, versionStore 초기화됨
  // When    ATLAS-1 이슈 상세로 SPA 내부 내비게이션
  // Then    affects-versions-section, fix-versions-section 이 DOM 에 렌더됨
  //         각 섹션에 검색 input 이 비활성 아님 (canEdit=true, alice ADMIN 권한)
  // ───────────────────────────────────────────────────────────────────────────
  test('S0 섹션 렌더 — 이슈 상세 진입 시 버전 섹션 2종이 표시됨', async ({ page }) => {
    await navigateToIssueDetail(page)

    const affectsSection = page.getByTestId(AFFECTS_SECTION_TESTID)
    const fixSection = page.getByTestId(FIX_SECTION_TESTID)

    // 섹션 렌더 확인
    await expect(affectsSection).toBeVisible()
    await expect(fixSection).toBeVisible()

    // 검색 input 렌더 확인 (VersionMultiSelect 가 기본으로 검색 input 을 렌더함)
    const affectsSearchInput = affectsSection.getByRole('textbox', {
      name: labels.affectsSearchPlaceholder,
    })
    const fixSearchInput = fixSection.getByRole('textbox', {
      name: labels.fixSearchPlaceholder,
    })
    await expect(affectsSearchInput).toBeVisible()
    await expect(fixSearchInput).toBeVisible()

    // canEdit=true (alice ADMIN) — 검색 input 이 disabled 아님
    await expect(affectsSearchInput).not.toBeDisabled()
    await expect(fixSearchInput).not.toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 Fix 버전 할당 — 버전 선택 후 칩 표시
  //
  // Given   alice 로 로그인, VER_B 버전이 versionStore 에 존재
  //         이슈 상세 진입 — fix-versions-section 렌더됨
  //
  // ⚠️ SKIP 사유: issues.$key.tsx 라우트에 useVersions/useChangeFixVersions 미배선.
  //    versions=[] 기본값이 IssueMetaPanel 에 전달되어 체크박스 목록이 비어있다.
  //    라우트 배선 완료 후 아래 시나리오가 통과된다.
  //
  // When    Fix 버전 섹션에서 VER_B 체크박스 체크 → PATCH /fix-versions → invalidate refetch
  // Then    fix-versions-section 에 version-chip 이 1개(VER_B 이름) 표시됨
  //
  // NOTE: 이 테스트는 라우트 배선 완료 후 unskip 필요
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 Fix 버전 할당 — 버전 선택 후 칩 1개 표시', async ({ page }) => {
    // 사전조건: VER_B seed
    await createVersionInStore(page, VER_B)

    await navigateToIssueDetail(page)

    const fixSection = page.getByTestId(FIX_SECTION_TESTID)

    // VER_B 체크박스 대기 — 라우트 배선 완료 후 동작
    const checkboxB = fixSection.getByRole('checkbox', { name: VER_B.name })
    await expect(checkboxB).toBeVisible()
    await expect(checkboxB).not.toBeChecked()

    // When: VER_B 체크 → mutation PATCH + invalidate refetch
    await checkboxB.click()

    // Then: version-chip 이 1개 (VER_B 이름)
    await expect(fixSection.getByTestId('version-chip')).toHaveCount(1)
    await expect(fixSection.getByTestId('version-chip').first()).toHaveText(VER_B.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 Affects 버전 할당 — 버전 선택 후 칩 표시
  //
  // ⚠️ SKIP 사유: 라우트 미배선 (S1 동일)
  //
  // Given   VER_A 버전이 versionStore 에 존재, 이슈 상세 진입
  // When    Affects 버전 섹션에서 VER_A 체크박스 체크
  // Then    affects-versions-section 에 version-chip 이 1개(VER_A 이름) 표시됨
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 Affects 버전 할당 — 버전 선택 후 칩 1개 표시', async ({ page }) => {
    await createVersionInStore(page, VER_A)
    await navigateToIssueDetail(page)

    const affectsSection = page.getByTestId(AFFECTS_SECTION_TESTID)
    const checkboxA = affectsSection.getByRole('checkbox', { name: VER_A.name })
    await expect(checkboxA).toBeVisible()

    await checkboxA.click()

    await expect(affectsSection.getByTestId('version-chip')).toHaveCount(1)
    await expect(affectsSection.getByTestId('version-chip').first()).toHaveText(VER_A.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 버전 교체 — 이미 선택된 버전 해제 후 다른 버전 선택
  //
  // ⚠️ SKIP 사유: 라우트 미배선 (S1 동일)
  //
  // Given   VER_A, VER_B 가 versionStore 에 있고 Fix 버전에 VER_B 가 선택된 상태
  // When    VER_B 해제 → VER_A 선택
  // Then    fix-versions-section 에 version-chip 이 1개(VER_A 이름)만 표시됨
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 버전 교체 — VER_B 해제 후 VER_A 선택 시 칩 교체', async ({ page }) => {
    await createVersionInStore(page, VER_A)
    await createVersionInStore(page, VER_B)
    await navigateToIssueDetail(page)

    const fixSection = page.getByTestId(FIX_SECTION_TESTID)
    const checkboxA = fixSection.getByRole('checkbox', { name: VER_A.name })
    const checkboxB = fixSection.getByRole('checkbox', { name: VER_B.name })

    // 사전 상태: VER_B 선택
    await checkboxB.click()
    await expect(fixSection.getByTestId('version-chip')).toHaveCount(1)

    // When: VER_B 해제 → VER_A 선택
    await checkboxB.click()
    await expect(fixSection.getByTestId('version-chip')).toHaveCount(0)
    await checkboxA.click()

    // Then: VER_A 칩 1개만 표시
    await expect(fixSection.getByTestId('version-chip')).toHaveCount(1)
    await expect(fixSection.getByTestId('version-chip').first()).toHaveText(VER_A.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 버전 해제 — 선택된 버전 해제 후 칩 0개
  //
  // ⚠️ SKIP 사유: 라우트 미배선 (S1 동일)
  //
  // Given   Affects 버전에 VER_A 가 선택된 상태
  // When    VER_A 체크박스 해제
  // Then    affects-versions-section 에 version-chip 0개 (version-chip-list 미렌더)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 버전 해제 — VER_A 해제 후 칩 0개', async ({ page }) => {
    await createVersionInStore(page, VER_A)
    await navigateToIssueDetail(page)

    const affectsSection = page.getByTestId(AFFECTS_SECTION_TESTID)
    const checkboxA = affectsSection.getByRole('checkbox', { name: VER_A.name })

    // 사전 상태: VER_A 선택
    await checkboxA.click()
    await expect(affectsSection.getByTestId('version-chip')).toHaveCount(1)

    // When: VER_A 해제
    await checkboxA.click()

    // Then: 칩 0개 — VersionChipList versions.length===0 이면 null 반환
    await expect(affectsSection.getByTestId('version-chip')).toHaveCount(0)
    await expect(affectsSection.getByTestId('version-chip-list')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 ARCHIVED 버전 미표시 — 드롭다운에서 ARCHIVED 버전이 안 보임
  //
  // Given   VER_A(UNRELEASED), VER_ARCHIVED(ARCHIVED) 버전이 versionStore 에 존재
  //         이슈 상세 진입 — Fix 버전 섹션 렌더됨
  //
  // ⚠️ SKIP 사유: 라우트 미배선 — versions=[] 이므로 체크박스 렌더 자체가 없음.
  //    배선 완료 후 VersionMultiSelect 의 ARCHIVED 필터링 규칙을 E2E 레벨에서 검증.
  //
  // When    Fix 버전 섹션 체크박스 목록 확인 (검색어 없음 → 전체 목록)
  // Then    VER_A 체크박스는 보임, VER_ARCHIVED 체크박스는 미표시
  //         (단, 이미 연결된 ARCHIVED 는 표시 — Jira 정석, 이 테스트는 미연결 케이스)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 ARCHIVED 버전 미표시 — 드롭다운에서 ARCHIVED 버전이 안 보임', async ({ page }) => {
    // 사전조건: VER_A seed + VER_ARCHIVED seed → ARCHIVED 전환
    await createVersionInStore(page, VER_A)
    const archivedId = await createVersionInStore(page, VER_ARCHIVED)
    await archiveVersion(page, archivedId)

    await navigateToIssueDetail(page)

    const fixSection = page.getByTestId(FIX_SECTION_TESTID)

    // VER_A(UNRELEASED): 체크박스 표시 (ARCHIVED 아님)
    await expect(fixSection.getByRole('checkbox', { name: VER_A.name })).toBeVisible()

    // VER_ARCHIVED(ARCHIVED): 체크박스 미표시 — VersionMultiSelect 필터링 규칙
    // visibleOptions: status==='ARCHIVED' && !value.includes(id) → 제외
    await expect(
      fixSection.getByRole('checkbox', { name: VER_ARCHIVED.name }),
    ).toHaveCount(0)
  })
})
