// FR-HS-02 D7 E2E — 이슈 변경 이력 타임라인 happy path (변경 이력 섹션 조회 + 더 보기 페이징)
//
// ─────────────────────────────────────────────────────────────────────────────
// 교훈 반영
// ─────────────────────────────────────────────────────────────────────────────
//
// - e2e-loginasalice-fixture-fr-au-07-regression: session-fixtures.ts 의 loginAsAlice 사용
// - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 그대로
// - msw-derived-behavior-shared-store-e2e: 시드는 브라우저 page.evaluate fetch → MSW 핸들러 적재
// - playwright-getbyrole-exact-strict-mode: 섹션 컨테이너 한정 + exact:true
// - ui-pr-defer-e2e-regression-latent: 기존 이슈 E2E 회귀 0 확인 필수
// - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동, 드롭다운 로딩 대기
//
// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 커버리지
// ─────────────────────────────────────────────────────────────────────────────
//
// S1  이슈 상세 하단에 "변경 이력" 섹션이 표시되고 기본 펼침 상태임
// S2  그룹별 타임라인 — actorName · 시각 헤더 + 필드별 from → to 행 표시
//     2a. assignee 박제 label (from: Alice → to: Bob)
//     2b. securityLevel 박제 label (from: Confidential → to: Public)
//     2c. priority label 없는 숫자 변경 (1 → 3)
// S3  lifecycle "이슈를 생성했습니다" + actorName=null → "시스템" 표시
// S4  "더 보기" 클릭 시 다음 페이지 그룹 누적 로드 확인
//     (ATLAS-2 에 21개 그룹 사전 등록 — fixtureStore 기본값, 런타임 시드 없이 결정적 검증)
// ─────────────────────────────────────────────────────────────────────────────

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 키 — ATLAS-1 (atlasOneChangelogFixture 사전 등록) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** i18n 정본 — ko.ts 직접 import 대신 상수로 고정 (단순화, 변경 시 E2E 명시적 실패) */
const labels = {
  changelogSectionTitle: '변경 이력',
  changelogLoadMore: '더 보기',
  changelogSystemActor: '시스템',
  changelogLifecycleCreated: '이슈를 생성했습니다',
  fieldAssignee: '담당자',
  fieldSecurityLevel: '보안등급',
  fieldPriority: '우선순위',
  fieldLifecycle: '생명주기',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-HS-02 이슈 변경 이력 타임라인 (IssueChangelog)', () => {
  /**
   * Given   alice 로그인 완료 + ATLAS-1 이슈 상세 페이지 진입
   */
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(ISSUE_URL)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 — "변경 이력" 섹션 존재 및 기본 펼침 상태
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입
   * When    페이지 하단 확인
   * Then    aria-label="변경 이력" region이 표시되고
   *         id="changelog-content" 컨테이너가 보임 (기본 isOpen=true)
   */
  test('S1 — 변경 이력 섹션이 기본 펼침 상태로 표시됨', async ({ page }) => {
    // Then. 섹션 region 존재 확인
    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    // Then. 기본 펼침 상태 — changelog-content 컨테이너 표시
    const changelogContent = changelogSection.locator('#changelog-content')
    await expect(changelogContent).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 — 그룹별 타임라인: actorName·시각 헤더 + 필드 from→to 표시
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 상세 + 변경 이력 섹션 펼침
   * When    그룹 목록 확인 (atlasOneChangelogFixture — Alice/Bob 그룹 포함)
   * Then    2a. assignee 그룹에서 "담당자: Alice → Bob" 표시
   *         2b. securityLevel 그룹에서 "보안등급: Confidential → Public" 표시
   *         2c. priority 그룹에서 "우선순위" 필드 from→to 표시
   */
  test('S2a — assignee 박제 label (Alice → Bob) 표시 확인', async ({ page }) => {
    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    // assignee 변경 그룹 — fixture: actorName='Bob', createdAt='2026-06-11T08:30:00Z'
    // ChangeGroupRow aria-label: "Bob · 2026년 6월 11일 오후 05:30" (KST UTC+9)
    // ChangeItemRow: "담당자: Alice → Bob"
    //
    // Bob 그룹이 2개(components/assignee) 있어 aria-label 시각으로 구분한다.
    // 섹션 전체에서 담당자·Alice·Bob이 함께 나타나는지 확인한다.
    const changelogContent = changelogSection.locator('#changelog-content')
    await expect(changelogContent).toBeVisible()

    // "담당자" ul 항목이 존재하는지 확인 (섹션 컨테이너 한정 — strict mode 방지)
    // Bob의 변경 항목 그룹이 2개 존재 (components, assignee) — 담당자가 포함된 ul 을 filter 로 한정
    const assigneeItems = changelogContent
      .getByRole('list', { name: 'Bob의 변경 항목' })
      .filter({ hasText: labels.fieldAssignee })
    await expect(assigneeItems).toBeVisible()
    await expect(assigneeItems).toContainText('Alice')
    await expect(assigneeItems).toContainText('Bob')
  })

  test('S2b — securityLevel 박제 label (Confidential → Public) 표시 확인', async ({ page }) => {
    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    // securityLevel 변경 그룹 — fixture: actorName='Alice', items=[{field:'securityLevel',fromLabel:'Confidential',toLabel:'Public'}]
    // "보안등급: Confidential → Public"이 포함된 그룹 확인
    const changelogContent = changelogSection.locator('#changelog-content')
    await expect(changelogContent).toContainText(labels.fieldSecurityLevel)
    await expect(changelogContent).toContainText('Confidential')
    await expect(changelogContent).toContainText('Public')
  })

  test('S2c — priority 변경 그룹에서 "우선순위" 필드 표시 확인', async ({ page }) => {
    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    const changelogContent = changelogSection.locator('#changelog-content')
    await expect(changelogContent).toContainText(labels.fieldPriority)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3 — lifecycle 항목 + actorName=null → "시스템" 표시
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 상세 + 변경 이력 섹션 펼침
   *         atlasOneChangelogFixture 마지막 항목: lifecycle created, actorId=null
   * When    변경 이력 목록 확인
   * Then    "생명주기" 필드 행이 "이슈를 생성했습니다" 텍스트로 표시됨
   *         actorName=null 그룹 헤더가 "시스템"으로 표시됨
   */
  test('S3 — lifecycle "이슈를 생성했습니다" + actorName=null → "시스템" 표시', async ({ page }) => {
    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    // actorName=null 그룹 — aria-label: "시스템 · <시각>"
    const systemGroup = changelogSection.getByRole('group', {
      name: new RegExp(`^${labels.changelogSystemActor}`),
    })
    await expect(systemGroup).toBeVisible()

    // lifecycle 항목 — "생명주기: 이슈를 생성했습니다"
    await expect(systemGroup).toContainText(labels.fieldLifecycle)
    await expect(systemGroup).toContainText(labels.changelogLifecycleCreated)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4 — "더 보기" 클릭 → 다음 페이지 누적 로드
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 changelog에 21개 그룹 시드 (size=20 → page=0 last=false)
   *         페이지 재진입 후 변경 이력 섹션 확인
   * When    "더 보기" 버튼 클릭
   * Then    page=1 그룹이 누적 로드됨 (21번째 그룹 표시)
   *         "더 보기" 버튼 사라짐 (page=1 last=true)
   */
  test('S4 — "더 보기" 클릭 시 다음 페이지 그룹 누적 로드', async ({ page }) => {
    // Given. ATLAS-2 에 21개 그룹이 사전 등록됨(paginationChangelogFixture) → page=0 size=20 → last=false
    // 런타임 시드 대신 fixtureStore 기본 등록을 사용해 결정적으로 검증한다(MSW SW 컨텍스트 시드 취약성 회피).
    await page.goto('/issues/ATLAS-2')

    const changelogSection = page.getByRole('region', {
      name: labels.changelogSectionTitle,
      exact: true,
    })
    await expect(changelogSection).toBeVisible()

    // page=0 로드 완료 대기 — 첫 그룹 Alice 표시 확인
    const changelogContent = changelogSection.locator('#changelog-content')
    await expect(changelogContent).toContainText('Alice')

    // When. "더 보기" 버튼 클릭 (changelogSection 컨테이너 한정 — strict mode 방지)
    const loadMoreButton = changelogSection.getByRole('button', {
      name: labels.changelogLoadMore,
      exact: true,
    })
    await expect(loadMoreButton).toBeVisible()
    await loadMoreButton.click()

    // Then. page=1 누적 로드 — "더 보기" 버튼 사라짐 (page=1 last=true, 총 21개 로드 완료)
    await expect(loadMoreButton).not.toBeVisible()

    // Then. 그룹이 21개 전부 화면에 렌더됨 — role="group" 개수 확인
    const allGroups = changelogContent.getByRole('group')
    await expect(allGroups).toHaveCount(21)
  })
})
