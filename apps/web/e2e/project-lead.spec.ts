// FR-CM-04 E2E — 프로젝트 리드 지정/해제 + 폴백 자동배정 시나리오 S1~S5
//
// 이 E2E는 MSW(project-lead-handlers.ts) 위에서 동작한다.
// ground-truth는 백엔드 통합테스트(DefaultAssigneeResolverTest, PR #89).
//
// [설계 결정]
// - S1~S4: /projects/ATLAS/settings/project-lead 설정 페이지 시나리오
// - S5: createIssueHandler 폴백 분기(T1 MSW 변경) 위에서 이슈 생성 후 담당자 자동배정 검증
//
// [회귀 방지 교훈 반영]
// - playwright-getbyrole-exact-strict-mode: 컨테이너 한정 셀렉터, exact:true
// - msw-mutation-stateful-refetch: PATCH 후 refetch 롤백 없음 검증
// - e2e-fixture-whoami-userid-alignment: alice(ALICE_USER_ID)로 리드/담당자 UUID 통일
// - e2e-msw-scenario-toggle-localstorage-flag: 시드는 X-MSW-Seed-ProjectLead 헤더 활용

import { test, expect } from '@playwright/test'
import { loginAsAlice, loginAsBob } from './fixtures/issue-fixtures'
import { issueCreateStrings } from '../src/i18n/ko'
import { userAliceFixture } from '../src/mocks/user-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — UUID (user-fixtures.ts 단일 진실 원천과 정합)
// ─────────────────────────────────────────────────────────────────────────────

/** alice 사용자 UUID — userAliceFixture.id */
const ALICE_USER_ID = userAliceFixture.id

/** 존재하지 않는 사용자 UUID — S4 삭제리드 시나리오용 (Zod v4 RFC4122 v4 형식) */
const GHOST_USER_ID = 'f0e1d2c3-b4a5-4f6e-8d7c-9b0a1c2d3e4f'

/** ATLAS 프로젝트 키 */
const PROJECT_KEY = 'ATLAS'

/** 프로젝트 리드 설정 페이지 URL */
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/project-lead`

// ─────────────────────────────────────────────────────────────────────────────
// 셀렉터 상수 — data-testid / aria-label (ProjectLeadSelect.tsx 실재 확인됨)
// ─────────────────────────────────────────────────────────────────────────────

const TESTID_LEAD_CURRENT_NAME = 'lead-current-name'
const ARIA_LEAD_SEARCH = '리드 검색'
const ARIA_UNASSIGN = '미지정'
const HEADING_PAGE = '프로젝트 리드'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW projectLeadStore seed (X-MSW-Seed-ProjectLead 헤더)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/projects/:projectKey/lead 요청에 X-MSW-Seed-ProjectLead 헤더를 붙여
 * MSW projectLeadStore에 리드 정보를 시드한다.
 * ServiceWorker가 살아있는 상태에서 page.evaluate로 호출해야 한다.
 */
async function seedProjectLead(
  page: import('@playwright/test').Page,
  projectKey: string,
  projectId: string,
  leadUserId: string | null,
): Promise<void> {
  await page.evaluate(
    async ({
      pk,
      seed,
    }: {
      pk: string
      seed: { projectId: string; leadUserId: string | null }
    }) => {
      const encoded = encodeURIComponent(JSON.stringify(seed))
      const res = await fetch(`/api/v1/projects/${pk}/lead`, {
        headers: { 'X-MSW-Seed-ProjectLead': encoded },
      })
      if (!res.ok && res.status !== 200) {
        throw new Error(`seed 실패: ${res.status}`)
      }
    },
    { pk: projectKey, seed: { projectId, leadUserId } },
  )
}

/**
 * 이슈 생성 폼용 componentStore seed (component-handlers.ts X-MSW-Seed-Components 헤더).
 * S5 폴백 시나리오에서 리드 없는 컴포넌트를 시드할 때 사용한다.
 */
async function seedComponentStore(
  page: import('@playwright/test').Page,
  projectKey: string,
  components: Array<{ id: string; name: string; leadUserId: string | null }>,
): Promise<void> {
  await page.evaluate(
    async ({
      pk,
      comps,
    }: {
      pk: string
      comps: Array<{ id: string; name: string; leadUserId: string | null }>
    }) => {
      const encoded = encodeURIComponent(JSON.stringify(comps))
      const res = await fetch(`/api/v1/projects/${pk}/components`, {
        headers: { 'X-MSW-Seed-Components': encoded },
      })
      if (!res.ok) throw new Error(`component seed 실패: ${res.status}`)
    },
    { pk: projectKey, comps: components },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-CM-04 프로젝트 리드 지정/해제 + 폴백 자동배정 (MSW 미러)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 리드 지정
  //
  // Given   alice 로그인 → settings/project-lead 진입
  //         X-MSW-Seed-ProjectLead로 ATLAS 프로젝트 리드=null(미지정) 초기화
  // When    "리드 검색" input에 "캐럴" 입력 → 드롭다운 "캐럴" 버튼 클릭
  // Then    lead-current-name이 "캐럴" 표시 (PATCH /lead 후 stateful refetch)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 리드 지정 — 캐럴 검색 후 선택하면 lead-current-name이 캐럴로 표시', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // projectLeadStore 시드 — ATLAS 프로젝트에 리드 미지정 상태로 초기화
    await seedProjectLead(page, PROJECT_KEY, PROJECT_KEY, null)

    // 설정 페이지 진입
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: HEADING_PAGE })).toBeVisible()

    // 초기 상태 확인 — 리드 미지정
    await expect(page.getByTestId(TESTID_LEAD_CURRENT_NAME)).toHaveText(ARIA_UNASSIGN)

    // "리드 검색" input에 "캐럴" 입력 (300ms debounce → 버튼 노출 대기)
    await page.getByLabel(ARIA_LEAD_SEARCH).fill('캐럴')

    // 드롭다운에 "캐럴" 버튼 노출 대기 → 클릭
    await expect(page.getByRole('button', { name: '캐럴', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '캐럴', exact: true }).click()

    // Then. lead-current-name이 "캐럴"로 갱신
    await expect(page.getByTestId(TESTID_LEAD_CURRENT_NAME)).toHaveText('캐럴')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 리드 해제
  //
  // Given   alice 로그인 → settings/project-lead 진입
  //         X-MSW-Seed-ProjectLead로 ATLAS 프로젝트 리드=캐럴로 초기화
  // When    "미지정" 해제 버튼 클릭
  // Then    lead-current-name이 "미지정"으로 변경
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 리드 해제 — 미지정 버튼 클릭하면 lead-current-name이 미지정으로 변경', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // carol UUID — userCarolFixture.id (user-fixtures.ts 단일 진실 원천)
    const CAROL_USER_ID = '961fb10c-6317-47c8-b377-d8fc5594db82'

    // projectLeadStore 시드 — ATLAS 프로젝트에 carol이 리드로 지정된 상태
    await seedProjectLead(page, PROJECT_KEY, PROJECT_KEY, CAROL_USER_ID)

    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: HEADING_PAGE })).toBeVisible()

    // 초기 상태 확인 — 캐럴이 리드
    await expect(page.getByTestId(TESTID_LEAD_CURRENT_NAME)).toHaveText('캐럴')

    // "미지정" 해제 버튼 클릭
    await page.getByRole('button', { name: ARIA_UNASSIGN, exact: true }).click()

    // Then. lead-current-name이 "미지정"으로 복원
    await expect(page.getByTestId(TESTID_LEAD_CURRENT_NAME)).toHaveText(ARIA_UNASSIGN)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 권한 제한 (bob은 MANAGE_COMPONENTS 없음)
  //
  // Given   bob 로그인 → settings/project-lead 진입
  //         X-MSW-Seed-ProjectLead로 ATLAS 프로젝트 리드=null 초기화
  // When    페이지 렌더 완료
  // Then    "리드 검색" input이 disabled
  //         "미지정" 해제 버튼이 없거나 disabled
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 권한 제한 — bob은 리드 검색 input이 disabled 상태', async ({ page }) => {
    await loginAsBob(page)

    // projectLeadStore 시드 — ATLAS 프로젝트에 리드 미지정 상태로 초기화
    await seedProjectLead(page, PROJECT_KEY, PROJECT_KEY, null)

    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: HEADING_PAGE })).toBeVisible()

    // Then. "리드 검색" input이 disabled (bob은 MANAGE_COMPONENTS=false)
    await expect(page.getByLabel(ARIA_LEAD_SEARCH)).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 삭제된 리드 표시
  //
  // Given   alice 로그인 → settings/project-lead 진입
  //         X-MSW-Seed-ProjectLead로 ATLAS 프로젝트 리드=존재하지 않는 UUID 시드
  // When    페이지 렌더 완료
  // Then    lead-current-name이 "알 수 없는 사용자 (앞8자)" 형식으로 표시
  //         (UserSummary를 찾지 못할 때 앞 8자 fallback 텍스트 — ProjectLeadSelect 구현)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 삭제된 리드 — 없는 UUID 시드 시 알 수 없는 사용자 앞8자 표시', async ({ page }) => {
    await loginAsAlice(page)

    // 존재하지 않는 UUID로 리드 시드
    await seedProjectLead(page, PROJECT_KEY, PROJECT_KEY, GHOST_USER_ID)

    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: HEADING_PAGE })).toBeVisible()

    // GHOST_USER_ID 앞 8자
    const leadIdPrefix = GHOST_USER_ID.slice(0, 8)

    // Then. "알 수 없는 사용자 (앞8자)" 텍스트 포함 확인
    await expect(page.getByTestId(TESTID_LEAD_CURRENT_NAME)).toContainText(
      `알 수 없는 사용자 (${leadIdPrefix})`,
    )
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 폴백 자동배정 (T1 MSW 변경 의존)
  //
  // Given   alice 로그인
  //         X-MSW-Seed-ProjectLead로 ATLAS 프로젝트 리드=김앨리스(alice) 시드
  //         리드 없는 컴포넌트 1개(COMP_NO_LEAD) 시드
  // When    이슈 생성 폼에서 COMP_NO_LEAD 선택 후 이슈 생성
  // Then    이슈 상세 assignee-section의 assignee-current-name이 "김앨리스"
  //         (컴포넌트 리드 없음 → 프로젝트 리드 폴백 → alice 자동배정)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 폴백 자동배정 — 리드 없는 컴포넌트 선택 시 프로젝트 리드(김앨리스)가 담당자로 자동배정', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // 프로젝트 리드 시드 — ATLAS 프로젝트 리드=alice
    await seedProjectLead(page, PROJECT_KEY, PROJECT_KEY, ALICE_USER_ID)

    // 이슈 생성 폼 진입
    await page.goto('/issues/new')
    const projectKeyInput = page.getByLabel(issueCreateStrings.projectKeyLabel)
    await expect(projectKeyInput).toBeVisible()

    // 리드 없는 컴포넌트 시드 (ServiceWorker 활성 상태)
    const COMP_NO_LEAD = {
      id: 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f60',
      name: 'CM04-NoLead-컴포넌트',
      leadUserId: null,
    }
    await seedComponentStore(page, PROJECT_KEY, [COMP_NO_LEAD])

    // 프로젝트 키 입력 — ComponentMultiSelect 활성화
    await projectKeyInput.fill(PROJECT_KEY)

    // 이슈 제목 입력
    const summaryInput = page.getByLabel(issueCreateStrings.summaryLabel)
    await expect(summaryInput).toBeVisible()
    await summaryInput.fill('FR-CM-04 S5 폴백 자동배정 E2E 테스트')

    // 리드 없는 컴포넌트 체크박스 선택
    const compNoLeadCheckbox = page.getByRole('checkbox', { name: COMP_NO_LEAD.name })
    await expect(compNoLeadCheckbox).toBeVisible()
    await compNoLeadCheckbox.click()
    await expect(compNoLeadCheckbox).toBeChecked()

    // 이슈 생성 버튼 클릭
    await page.getByRole('button', { name: issueCreateStrings.submitButton, exact: true }).click()

    // 이슈 상세 페이지로 이동 대기
    await page.waitForURL(/\/issues\/ATLAS-42$/)

    // assignee-section — 프로젝트 리드 폴백으로 alice(김앨리스)가 자동배정
    // T1 MSW 변경(getStoredProjectLead + 폴백 분기) 없으면 null → 미할당 → 이 단언 실패(RED)
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()
    await expect(assigneeSection.getByTestId('assignee-current-name')).toHaveText('김앨리스')
  })
})
