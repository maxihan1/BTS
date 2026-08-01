// FR-CM-04 E2E — 프로젝트 리드 지정/해제 + 폴백 자동배정 시나리오 S1~S5
//
// 이 E2E는 MSW(project-lead-handlers.ts) 위에서 동작한다.
// ground-truth는 백엔드 통합테스트(DefaultAssigneeResolverTest, PR #89).
//
// [설계 결정]
// - S1~S4: /projects/ATLAS/settings/project-lead 설정 페이지 시나리오
// - S5: createIssueHandler 폴백 분기(T1 MSW 변경) 위에서 이슈 생성 후 담당자 자동배정 검증
//
// [시드 패턴]
// MSW module-scope store는 page.goto() full reload 시 재초기화된다.
// S1~S4는 loginAsAlice → dashboard에서 seedProjectLead(fetch) → navigateInSpa(SETTINGS_URL)
//   패턴으로 store를 유지하면서 설정 페이지로 진입한다
//   (issue-component-default-assignee S3 동형 패턴, 4건 통과 확인).
// S5는 goto('/issues/new') 후 seed fetch → 이슈 생성 흐름이라 goto 이후 시드가 가능하다.
//
// [회귀 방지 교훈 반영]
// - playwright-getbyrole-exact-strict-mode: exact:true
// - msw-mutation-stateful-refetch: PATCH 후 refetch 롤백 없음 검증
// - e2e-fixture-whoami-userid-alignment: alice(ALICE_USER_ID)로 리드/담당자 UUID 통일
// - e2e-msw-serviceworker-block: navigateInSpa(history.pushState+popstate)로 SW store 유지

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

/** carol 사용자 UUID — userCarolFixture.id (user-fixtures.ts 단일 진실 원천) */
const CAROL_USER_ID = '00000000-0000-4000-8000-000000000003'

/** ATLAS 프로젝트 키 */
const PROJECT_KEY = 'ATLAS'

/**
 * ATLAS 프로젝트 UUID — projectLeadResponseSchema 의 projectId 는 z.string().uuid() 검증이므로
 * MSW 시드 시 반드시 유효한 RFC4122 v4 UUID를 사용해야 한다.
 * (zod-v4-uuid-fixture-strictness 교훈)
 */
const ATLAS_PROJECT_UUID = '00000000-0000-4000-8000-000000000010'

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
// 헬퍼 — MSW projectLeadStore seed
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/projects/:projectKey/lead 요청에 X-MSW-Seed-ProjectLead 헤더를 붙여
 * MSW projectLeadStore에 리드 정보를 시드한다.
 *
 * SW가 활성화된 SPA 컨텍스트에서 호출해야 하며, page.goto() 이후에 호출한다.
 * 시드 후 navigateInSpa로 이동하면 module-scope store가 유지된다.
 *
 * @param page Playwright Page 객체
 * @param projectKey 프로젝트 키 (URL 파라미터)
 * @param projectId store에 저장할 projectId 값
 * @param leadUserId 리드 사용자 UUID — null 허용
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
      await fetch(`/api/v1/projects/${pk}/lead`, {
        headers: { 'X-MSW-Seed-ProjectLead': encoded },
      })
    },
    { pk: projectKey, seed: { projectId, leadUserId } },
  )
}

/**
 * SPA 내부 네비게이션으로 지정 URL로 이동한다.
 * page.goto()는 full reload → MSW module-scope store 리셋.
 * history.pushState + popstate 이벤트는 SPA 라우터가 내부 네비게이션으로 처리 → store 유지.
 * (issue-component-default-assignee S3 동형 패턴 — 4건 통과 확인)
 */
async function navigateInSpa(
  page: import('@playwright/test').Page,
  url: string,
): Promise<void> {
  await page.evaluate((targetUrl: string) => {
    window.history.pushState({}, '', targetUrl)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, url)
}

/**
 * 이슈 생성 폼용 componentStore seed (component-handlers.ts X-MSW-Seed-Components 헤더).
 * S5 폴백 시나리오에서 리드 없는 컴포넌트를 시드할 때 사용한다.
 * page.goto 후 폼 로드 대기 후에 호출해야 SW가 활성 상태다.
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
  // Given   alice 로그인 → dashboard 컨텍스트에서 리드=null(미지정) seed
  //         navigateInSpa로 settings/project-lead 진입 (store 유지)
  // When    "리드 검색" input에 "캐럴" 입력 → 드롭다운 "캐럴" 버튼 클릭
  // Then    lead-current-name이 "캐럴" 표시 (PATCH /lead 후 stateful refetch)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 리드 지정 — 캐럴 검색 후 선택하면 lead-current-name이 캐럴로 표시', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // dashboard 컨텍스트(SW 활성)에서 seed → navigateInSpa로 store 유지
    await seedProjectLead(page, PROJECT_KEY, ATLAS_PROJECT_UUID, null)
    await navigateInSpa(page, SETTINGS_URL)
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
  // Given   alice 로그인 → dashboard에서 carol이 리드로 지정된 상태 seed
  //         navigateInSpa로 settings/project-lead 진입
  // When    "미지정" 해제 버튼 클릭
  // Then    lead-current-name이 "미지정"으로 변경
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 리드 해제 — 미지정 버튼 클릭하면 lead-current-name이 미지정으로 변경', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // carol이 리드로 지정된 상태로 seed
    await seedProjectLead(page, PROJECT_KEY, ATLAS_PROJECT_UUID, CAROL_USER_ID)
    await navigateInSpa(page, SETTINGS_URL)
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
  // Given   bob 로그인 → dashboard에서 리드=null seed
  //         navigateInSpa로 settings/project-lead 진입
  // When    페이지 렌더 완료
  // Then    "리드 검색" input이 disabled
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 권한 제한 — bob은 리드 검색 input이 disabled 상태', async ({ page }) => {
    await loginAsBob(page)

    // 리드 미지정 상태로 seed
    await seedProjectLead(page, PROJECT_KEY, ATLAS_PROJECT_UUID, null)
    await navigateInSpa(page, SETTINGS_URL)
    await expect(page.getByRole('heading', { name: HEADING_PAGE })).toBeVisible()

    // Then. "리드 검색" input이 disabled (bob은 MANAGE_COMPONENTS=false)
    await expect(page.getByLabel(ARIA_LEAD_SEARCH)).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 삭제된 리드 표시
  //
  // Given   alice 로그인 → dashboard에서 존재하지 않는 UUID로 리드 seed
  //         navigateInSpa로 settings/project-lead 진입
  // When    페이지 렌더 완료
  // Then    lead-current-name이 "알 수 없는 사용자 (앞8자)" 형식으로 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 삭제된 리드 — 없는 UUID 시드 시 알 수 없는 사용자 앞8자 표시', async ({ page }) => {
    await loginAsAlice(page)

    // 존재하지 않는 UUID로 리드 seed
    await seedProjectLead(page, PROJECT_KEY, ATLAS_PROJECT_UUID, GHOST_USER_ID)
    await navigateInSpa(page, SETTINGS_URL)
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
  //         goto('/issues/new') 후 ATLAS 프로젝트 리드=alice seed
  //         리드 없는 컴포넌트 1개(COMP_NO_LEAD) seed
  // When    이슈 생성 폼에서 COMP_NO_LEAD 선택 후 이슈 생성
  // Then    이슈 상세 assignee-section의 assignee-current-name이 "김앨리스"
  //         (컴포넌트 리드 없음 → 프로젝트 리드 폴백 → alice 자동배정)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 폴백 자동배정 — 리드 없는 컴포넌트 선택 시 프로젝트 리드(김앨리스)가 담당자로 자동배정', async ({
    page,
  }) => {
    await loginAsAlice(page)

    // 이슈 생성 폼 진입 — goto 후 SW 활성 상태에서 seed
    // 시드는 **컴포넌트 목록을 건드리지 않는 화면**에서 한다. /issues 에서 시드하면
    // 그 화면이 이미 컴포넌트 쿼리를 캐시해, staleTime 때문에 이후 SPA 이동에서
    // 재조회가 걸리지 않아 시드가 반영되지 않는다.
    await page.goto('/dashboard')
    // 시드 전에 앱(MSW ServiceWorker)이 뜰 때까지 기다린다 — 상단바 만들기 버튼이 그 신호다.
    // 기다리지 않고 시드하면 핸들러가 아직 없어 500 이 돌아온다.
    await expect(page.getByRole('button', { name: '만들기' })).toBeVisible()

    // ATLAS 프로젝트 리드=alice seed (goto 후 SW 활성 상태)
    await seedProjectLead(page, PROJECT_KEY, ATLAS_PROJECT_UUID, ALICE_USER_ID)

    // 리드 없는 컴포넌트 seed
    const COMP_NO_LEAD = {
      id: 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f60',
      name: 'CM04-NoLead-컴포넌트',
      leadUserId: null,
    }
    await seedComponentStore(page, PROJECT_KEY, [COMP_NO_LEAD])

    // 프로젝트 키 입력 — ComponentMultiSelect 활성화

    // FR-UX-09 F2 — 라우트가 생성 모달을 열고, 프로젝트는 자유 텍스트가 아니라 셀렉터다.
    // 프로젝트가 **자동 선택**되면서 useComponents 가 마운트 직후 발사되므로,
    // 시드를 먼저 하고 **페이지 리로드 없이** SPA 이동으로 들어간다
    // (custom-fields.spec.ts:328 검증된 패턴 — page.goto 는 SW 를 재시작해 store 를 날린다).
    await page.evaluate(() => {
      window.history.pushState({}, '', '/issues/new')
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    })

    // 상단바 ProjectSwitcher 와 이름이 겹치므로 모달 안으로 범위를 한정한다.
    const projectKeyInput = page
      .getByRole('dialog', { name: '새 이슈 만들기' })
      .getByLabel(issueCreateStrings.projectKeyLabel)
    await expect(projectKeyInput).toBeVisible()
    await projectKeyInput.selectOption(PROJECT_KEY)

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
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()
    await expect(assigneeSection.getByTestId('assignee-current-name')).toHaveText('김앨리스')
  })
})
