// FR-CM-03 E2E — 이슈 생성 폼에서 컴포넌트 선택 시 이슈 상세 담당자 자동 표시 (MSW 미러)
//
// 이 E2E는 MSW 미러 위에서 동작한다.
// 자동배정 ground-truth는 백엔드 통합테스트(IssueCreateAutoAssignIntegrationTest / ChangeComponentsAutoAssignIntegrationTest).
//
// [설계 결정]
// createIssueHandler(issue-handlers.ts)는 X-MSW-Seed-Components 헤더로 componentStore에 시드된
// 컴포넌트의 리드 정보를 읽어 default-assignee를 resolve한다(getStoredComponentsByIds 경유).
// 시드 → 이슈 생성 → ATLAS-42 상세 진입 → assigneeId=리드UUID → useUsersByIds → 이름 렌더 흐름이 완전 검증 가능.
// 따라서 이 spec은 아래 시나리오를 검증한다.
//   S1: 이슈 생성 폼에서 리드 있는 컴포넌트(alice=김앨리스)를 선택하고 생성
//       → 이슈 상세 assignee-section에 '김앨리스' 이름 표시 (자동배정 담당자 이름 검증)
//   S2: 생성 폼 컴포넌트 선택 UI — 리드 유무 무관 복수 선택 가능
//   S3: 기존 미할당 이슈(ATLAS-1) 상세에서 컴포넌트 지정 → components-section 칩 표시
//   S4: 리드 보유 컴포넌트 2개 선택 → 사전순 첫 번째 리드(alice=김앨리스)가 자동배정
//       (name 오름차순 정렬: CM03-Auth < CM03-Backend → Auth 리드가 배정)
//
// [회귀 방지 교훈 반영]
// - playwright-getbyrole-exact-strict-mode: 컨테이너 한정 셀렉터 사용
// - e2e-msw-serviceworker-block: serviceWorkers:'block' 미사용 (playwright.config.ts 그대로)
// - e2e-fixture-whoami-userid-alignment: alice(userAliceFixture.id='c3d4e5f6-...')로 컴포넌트 리드 UUID 설정
// - msw-mutation-stateful-refetch: changeComponentsHandler stateful 영속 → refetch 후 롤백 없음

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueCreateStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — Zod v4 RFC4122 v4 UUID 형식 (3번째 그룹 첫 글자 '4', 4번째 그룹 첫 글자 '8'~'b')
// ─────────────────────────────────────────────────────────────────────────────

/** alice 사용자 UUID — userAliceFixture.id (user-fixtures.ts 단일 진실 원천) */
const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

/** bob 사용자 UUID — userBobFixture.id (user-fixtures.ts 단일 진실 원천) */
const BOB_USER_ID = '00000000-0000-4000-8000-000000000002'

/** 리드(alice)가 지정된 컴포넌트 — 사전순 첫 번째 (자동배정 우선 대상) */
const COMP_AUTH = {
  id: 'a1b2c3d4-e5f6-4abc-8def-0a1b2c3d4e50',
  name: 'CM03-Auth-컴포넌트',
  leadUserId: ALICE_USER_ID,
}

/** 리드 없는 컴포넌트 — 자동배정 대상 아님 */
const COMP_NO_LEAD = {
  id: 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f60',
  name: 'CM03-NoLead-컴포넌트',
  leadUserId: null,
}

/**
 * 리드(bob)가 지정된 두 번째 컴포넌트 — 사전순으로 COMP_AUTH 다음.
 * S4 tiebreak 시나리오: 두 컴포넌트 리드가 각각 alice/bob으로 다르며,
 * name 사전순 정렬 시 CM03-Auth < CM03-Backend이므로 alice(CM03-Auth 리드)가 배정됨.
 */
const COMP_BACKEND = {
  id: 'c3d4e5f6-a7b8-4cde-a0f1-2c3d4e5f6a70',
  name: 'CM03-Backend-컴포넌트',
  leadUserId: BOB_USER_ID,
}

/** ATLAS-1 이슈 URL — 이슈 상세 페이지 */
const ATLAS_1_URL = '/issues/ATLAS-1'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW componentStore seed (X-MSW-Seed-Components 헤더)
// ─────────────────────────────────────────────────────────────────────────────

type ComponentSeed = {
  id: string
  name: string
  projectId?: string
  description?: string | null
  leadUserId?: string | null
}

/**
 * 이슈 생성 폼(/issues/new) 컨텍스트에서 MSW componentStore를 seed한다.
 * GET /api/v1/projects/:projectKey/components 의 X-MSW-Seed-Components 헤더를 활용한다.
 * ServiceWorker가 살아있는 상태에서 호출하며, fetch가 ServiceWorker를 통해 처리된다.
 *
 * @returns 시드된 컴포넌트 수
 */
async function seedComponentStore(
  page: import('@playwright/test').Page,
  projectKey: string,
  components: ComponentSeed[],
): Promise<number> {
  return page.evaluate(
    async ({ pk, comps }: { pk: string; comps: ComponentSeed[] }) => {
      const encoded = encodeURIComponent(JSON.stringify(comps))
      const res = await fetch(`/api/v1/projects/${pk}/components`, {
        headers: { 'X-MSW-Seed-Components': encoded },
      })
      if (!res.ok) throw new Error(`seed 실패: ${res.status}`)
      const json = (await res.json()) as { data: unknown[] }
      return json.data.length
    },
    { pk: projectKey, comps: components },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-CM-03 이슈 컴포넌트 기본 담당자 자동배정 (MSW 미러)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 — canEdit=true → 컴포넌트 선택 활성 (교훈: e2e-fixture-whoami-userid-alignment)
    await loginAsAlice(page)
    // loginAsAlice 완료 후 /dashboard 진입 상태 — ServiceWorker 기동 완료
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 이슈 생성 폼 — 리드 있는 컴포넌트 선택 후 생성, 자동배정된 담당자 이름 검증
  //
  // Given   alice 로그인, /issues/new 진입
  //         ATLAS 프로젝트 컴포넌트 목록에 COMP_AUTH(리드=alice) seed
  // When    projectKey='ATLAS', summary='자동배정 E2E 테스트' 입력
  //         COMP_AUTH 체크박스 선택
  //         '이슈 생성' 버튼 클릭
  // Then    이슈 상세 페이지(ATLAS-42)로 이동
  //         components-section에 COMP_AUTH 칩 표시 (componentIds 에코 확인)
  //         assignee-section의 assignee-current-name이 '김앨리스' (자동배정 담당자 이름 검증)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 생성 폼 — 리드 있는 컴포넌트 선택 후 생성 시 자동배정 담당자 이름(김앨리스) 표시', async ({ page }) => {
    // 이슈 생성 폼 진입 (page.goto로 ServiceWorker 재시작 전 시드하면 리셋됨)
    // — 폼 진입 후 시드하는 순서가 중요하다
    // 시드는 **컴포넌트 목록을 건드리지 않는 화면**에서 한다. /issues 에서 시드하면
    // 그 화면이 이미 컴포넌트 쿼리를 캐시해, staleTime 때문에 이후 SPA 이동에서
    // 재조회가 걸리지 않아 시드가 반영되지 않는다.
    await page.goto('/dashboard')
    // 시드 전에 앱(MSW ServiceWorker)이 뜰 때까지 기다린다 — 상단바 만들기 버튼이 그 신호다.
    // 기다리지 않고 시드하면 핸들러가 아직 없어 500 이 돌아온다.
    await expect(page.getByRole('button', { name: '만들기' })).toBeVisible()

    // 폼 로드 대기 후 컴포넌트 시드 (ServiceWorker 활성 상태에서 시드)

    // 시드 — /issues/new 컨텍스트에서 ServiceWorker 활성 상태
    const seeded = await seedComponentStore(page, 'ATLAS', [COMP_AUTH, COMP_NO_LEAD])
    expect(seeded).toBe(2)

    // 프로젝트 키 입력 — ComponentMultiSelect 활성화 + useComponents fetch 트리거

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
    await projectKeyInput.selectOption('ATLAS')

    // 이슈 제목 입력
    const summaryInput = page.getByLabel(issueCreateStrings.summaryLabel)
    await expect(summaryInput).toBeVisible()
    await summaryInput.fill('자동배정 E2E 테스트 — FR-CM-03 S1')

    // 컴포넌트 목록 로드 대기 — COMP_AUTH 체크박스 노출 확인
    // ComponentMultiSelect는 projectKey 입력 후 useComponents로 fetch하므로 비동기 로드
    const compAuthCheckbox = page.getByRole('checkbox', { name: COMP_AUTH.name })
    await expect(compAuthCheckbox).toBeVisible()
    await expect(compAuthCheckbox).not.toBeDisabled()

    // COMP_AUTH 선택 (리드=alice — 자동배정 대상)
    await compAuthCheckbox.click()
    await expect(compAuthCheckbox).toBeChecked()

    // 생성 버튼 클릭
    await page.getByRole('button', { name: issueCreateStrings.submitButton, exact: true }).click()

    // 이슈 상세 페이지로 이동 대기 (MSW createIssueHandler → 201 → navigate)
    await page.waitForURL(/\/issues\/ATLAS-42$/)

    // components-section에 COMP_AUTH 칩 표시 (componentIds 에코 확인)
    // 이슈 상세 로드 대기
    const componentsSection = page.getByTestId('components-section')
    await expect(componentsSection).toBeVisible()

    // componentIds 에코 — 선택한 컴포넌트 칩이 표시됨
    const chip = componentsSection.getByTestId('component-chip')
    await expect(chip).toHaveCount(1)
    await expect(chip.first()).toHaveText(COMP_AUTH.name)

    // assignee-section — 자동배정된 담당자 이름 검증
    // createIssueHandler가 componentStore에서 COMP_AUTH.leadUserId(=alice)를 읽어 assigneeId에 반영.
    // issues.$key.tsx의 useUsersByIds(assigneeId) → userAliceFixture.displayName='김앨리스' 렌더.
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()
    const assigneeName = assigneeSection.getByTestId('assignee-current-name')
    await expect(assigneeName).toBeVisible()
    // 자동배정 담당자 이름 — alice의 displayName (user-fixtures.ts: userAliceFixture.displayName='김앨리스')
    await expect(assigneeName).toHaveText('김앨리스')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 생성 폼 컴포넌트 선택 UI — 리드 여부와 무관하게 체크박스 선택 가능
  //
  // Given   alice 로그인, /issues/new 진입
  //         ATLAS 프로젝트에 COMP_AUTH(리드=alice), COMP_NO_LEAD(리드 없음) seed
  //         projectKey='ATLAS' 입력 → ComponentMultiSelect 활성
  // When    COMP_AUTH 체크박스 선택
  //         COMP_NO_LEAD 체크박스 선택
  // Then    두 컴포넌트 모두 checked 상태
  //         컴포넌트 칩 2개 표시 (선택된 컴포넌트 시각적 피드백)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 생성 폼 — 리드 유무 무관 컴포넌트 복수 선택 가능', async ({ page }) => {
    // 시드는 **컴포넌트 목록을 건드리지 않는 화면**에서 한다. /issues 에서 시드하면
    // 그 화면이 이미 컴포넌트 쿼리를 캐시해, staleTime 때문에 이후 SPA 이동에서
    // 재조회가 걸리지 않아 시드가 반영되지 않는다.
    await page.goto('/dashboard')
    // 시드 전에 앱(MSW ServiceWorker)이 뜰 때까지 기다린다 — 상단바 만들기 버튼이 그 신호다.
    // 기다리지 않고 시드하면 핸들러가 아직 없어 500 이 돌아온다.
    await expect(page.getByRole('button', { name: '만들기' })).toBeVisible()


    // 폼 로드 후 시드 (ServiceWorker 활성 상태)
    await seedComponentStore(page, 'ATLAS', [COMP_AUTH, COMP_NO_LEAD])


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
    await projectKeyInput.selectOption('ATLAS')

    // 두 체크박스 모두 노출 대기
    const checkboxAuth = page.getByRole('checkbox', { name: COMP_AUTH.name })
    const checkboxNoLead = page.getByRole('checkbox', { name: COMP_NO_LEAD.name })
    await expect(checkboxAuth).toBeVisible()
    await expect(checkboxNoLead).toBeVisible()

    // COMP_AUTH 선택
    await checkboxAuth.click()
    await expect(checkboxAuth).toBeChecked()

    // 칩 1개 표시 확인 후 COMP_NO_LEAD 선택 (타이밍 안정화)
    await expect(page.getByTestId('component-chip')).toHaveCount(1)

    await checkboxNoLead.click()
    await expect(checkboxNoLead).toBeChecked()

    // Then. 칩 2개 표시
    await expect(page.getByTestId('component-chip')).toHaveCount(2)
    const chipTexts = await page.getByTestId('component-chip').allTextContents()
    expect(chipTexts).toContain(COMP_AUTH.name)
    expect(chipTexts).toContain(COMP_NO_LEAD.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 이슈 상세 컴포넌트 지정 — 기존 미할당 이슈에서 컴포넌트 지정 → 칩 표시
  //
  // Given   alice 로그인, ATLAS-1(assigneeId=null, componentIds=[]) 상세 진입
  //         componentStore에 COMP_AUTH seed
  // When    components-section에서 COMP_AUTH 체크박스 선택 → PATCH → invalidate refetch
  // Then    components-section에 COMP_AUTH 칩 1개 표시
  //         (PATCH 후 assignee 자동배정은 백엔드 통합테스트에서 검증)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 이슈 상세 — 컴포넌트 지정 후 칩 표시', async ({ page }) => {
    // dashboard 에서 componentStore seed (ServiceWorker 살아있는 상태)
    const seeded = await seedComponentStore(page, 'ATLAS', [COMP_AUTH, COMP_BACKEND])
    expect(seeded).toBe(2)

    // SPA 내부 내비게이션 — ServiceWorker 유지 (교훈: e2e-msw-serviceworker-block)
    await page.evaluate((url: string) => {
      window.history.pushState({}, '', url)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, ATLAS_1_URL)

    const componentsSection = page.getByTestId('components-section')
    await expect(componentsSection).toBeVisible()

    // COMP_AUTH 체크박스 선택
    const checkboxAuth = componentsSection.getByRole('checkbox', { name: COMP_AUTH.name })
    await expect(checkboxAuth).toBeVisible()
    await expect(checkboxAuth).not.toBeChecked()
    await checkboxAuth.click()

    // Then. 칩 1개 표시 (stateful PATCH + invalidateQueries refetch 후 롤백 없음)
    // msw-mutation-stateful-refetch 교훈 — changeComponentsHandler가 componentIds 영속
    await expect(componentsSection.getByTestId('component-chip')).toHaveCount(1)
    await expect(componentsSection.getByTestId('component-chip').first()).toHaveText(COMP_AUTH.name)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 이슈 생성 폼 — 리드 다른 두 컴포넌트 선택 시 사전순 첫 번째 리드가 자동배정
  //
  // Given   alice 로그인, /issues/new 진입
  //         COMP_BACKEND(리드=bob, name='CM03-Backend-...')와 COMP_AUTH(리드=alice, name='CM03-Auth-...') seed
  //         name 사전순: CM03-Auth < CM03-Backend → COMP_AUTH 리드(alice=김앨리스)가 자동배정 대상
  // When    summary='C2-tiebreak E2E 테스트' 입력
  //         COMP_BACKEND 먼저 선택, 그 다음 COMP_AUTH 선택 (선택 순서와 무관)
  //         '이슈 생성' 버튼 클릭
  // Then    이슈 상세 페이지(ATLAS-42)로 이동
  //         components-section에 칩 2개 표시
  //         assignee-section의 assignee-current-name이 '김앨리스'
  //         (createIssueHandler name 오름차순 정렬 → Auth 리드=alice 우선 배정 검증)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 생성 폼 — 리드 다른 컴포넌트 2개 선택 시 사전순 첫 번째 리드(김앨리스) 자동배정', async ({ page }) => {
    // 시드는 **컴포넌트 목록을 건드리지 않는 화면**에서 한다. /issues 에서 시드하면
    // 그 화면이 이미 컴포넌트 쿼리를 캐시해, staleTime 때문에 이후 SPA 이동에서
    // 재조회가 걸리지 않아 시드가 반영되지 않는다.
    await page.goto('/dashboard')
    // 시드 전에 앱(MSW ServiceWorker)이 뜰 때까지 기다린다 — 상단바 만들기 버튼이 그 신호다.
    // 기다리지 않고 시드하면 핸들러가 아직 없어 500 이 돌아온다.
    await expect(page.getByRole('button', { name: '만들기' })).toBeVisible()


    // seed — COMP_AUTH(alice 리드, 사전순 앞), COMP_BACKEND(bob 리드, 사전순 뒤)
    const seeded = await seedComponentStore(page, 'ATLAS', [COMP_AUTH, COMP_BACKEND])
    expect(seeded).toBe(2)


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
    await projectKeyInput.selectOption('ATLAS')

    const summaryInput = page.getByLabel(issueCreateStrings.summaryLabel)
    await expect(summaryInput).toBeVisible()
    await summaryInput.fill('C2-tiebreak E2E 테스트 — FR-CM-03 S4')

    // 두 체크박스 노출 대기
    const checkboxBackend = page.getByRole('checkbox', { name: COMP_BACKEND.name })
    const checkboxAuth = page.getByRole('checkbox', { name: COMP_AUTH.name })
    await expect(checkboxBackend).toBeVisible()
    await expect(checkboxAuth).toBeVisible()

    // COMP_BACKEND 먼저 선택 (선택 순서와 무관하게 사전순 첫 번째 리드가 배정됨을 검증)
    await checkboxBackend.click()
    await expect(checkboxBackend).toBeChecked()
    await expect(page.getByTestId('component-chip')).toHaveCount(1)

    await checkboxAuth.click()
    await expect(checkboxAuth).toBeChecked()
    await expect(page.getByTestId('component-chip')).toHaveCount(2)

    // 생성
    await page.getByRole('button', { name: issueCreateStrings.submitButton, exact: true }).click()
    await page.waitForURL(/\/issues\/ATLAS-42$/)

    // components-section — 칩 2개
    const componentsSection = page.getByTestId('components-section')
    await expect(componentsSection).toBeVisible()
    await expect(componentsSection.getByTestId('component-chip')).toHaveCount(2)

    // assignee-section — 사전순 첫 번째 리드(COMP_AUTH.leadUserId=alice)가 배정
    // createIssueHandler: name 오름차순 정렬 → CM03-Auth < CM03-Backend → alice(김앨리스) 배정
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()
    await expect(assigneeSection.getByTestId('assignee-current-name')).toHaveText('김앨리스')
  })
})
