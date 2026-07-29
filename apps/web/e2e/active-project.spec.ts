// 활성 프로젝트 컨텍스트 E2E — FR-UX-07 Task 10 (S1~S8 + NFR5 + B2 + E2 + E8)
//
// 관련 학습.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript 로 저장값 선주입
//   - frontend-nav-aria-label-e2e-contract: nav aria-label 4종은 계약이다 (신규 라벨 도입 금지)
//   - e2e-playwright-filter-arg-drop: 이 spec 단독 실행은 바이너리 직접 호출로
//
// ★ 판별자 선택 — "어느 프로젝트를 보고 있나"를 MSW 응답 내용이 아니라 **URL 과 localStorage**
//   로 판정한다. mock 이 projectKey 별로 다른 이슈를 주는지에 의존하지 않아야 이 spec 이
//   활성 프로젝트 해소 자체만 검증한다(mock 동작이 바뀌어도 의미가 유지된다).
//
// ★ S5·E2 는 `mocks/project-handlers.ts` 의 신규 localStorage 토글(LS_KEY_PROJECT_LIST_EMPTY·
//   LS_KEY_PROJECT_LIST_ERROR)에, S7 은 `mocks/issue-handlers.ts` 의 신규 sentinel 프로젝트 키
//   ('NOPERM' → 403)에 의존한다. 이 spec 을 작성하며 함께 추가했다 — GET /api/v1/issues 가
//   projectKey 로 필터링하지 않는 기존 mock 설계상, 이 두 시나리오는 opt-in 토글 없이는
//   관측 불가능했다(기본값 off, 기존 시나리오 무회귀).
import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { navLabels } from '../src/i18n/nav-labels'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { ALICE_USER_ID } from '../src/mocks/auth-fixtures'
import { LS_KEY_PROJECT_LIST_EMPTY, LS_KEY_PROJECT_LIST_ERROR } from '../src/mocks/project-handlers'

/** `hooks/use-active-project.ts` 의 저장 키 — 값은 JSON 인코딩된 문자열이다 */
const ACTIVE_PROJECT_KEY = 'bts.active-project'

/** MSW 활성 시드는 name 오름차순 ATLAS·MIDDLE·ZETA — 첫 원소는 ATLAS */
const FIRST_PROJECT = 'ATLAS'

/** 저장된 활성 프로젝트 키를 읽는다 (JSON 디코딩) */
async function readActiveProject(page: Page): Promise<string | null> {
  return page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    if (raw === null) return null
    try {
      const parsed: unknown = JSON.parse(raw)
      return typeof parsed === 'string' ? parsed : null
    } catch {
      return null
    }
  }, ACTIVE_PROJECT_KEY)
}

/** 로그인 전에 활성 프로젝트 저장값을 심는다 */
async function seedActiveProject(page: Page, projectKey: string): Promise<void> {
  await page.addInitScript(
    ([key, value]: [string, string]) => {
      window.localStorage.setItem(key, JSON.stringify(value))
    },
    [ACTIVE_PROJECT_KEY, projectKey] as [string, string],
  )
}

/** 이슈 목록 표가 뜰 때까지 기다린다 — 해소가 끝났다는 신호 */
async function waitForIssueList(page: Page): Promise<void> {
  await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()
}

/**
 * 로그인 전에 지정한 localStorage 플래그를 'true' 로 심는다 (콜드 시나리오 재현용).
 * `page.addInitScript` 는 이후 모든 goto 에 재적용되므로 `loginAsAlice` 이전에 걸어두면
 * whoami/Sidebar 의 최초 조회부터 적용된다(msw-derived-behavior-shared-store-e2e 관례).
 */
async function seedScenarioFlag(page: Page, key: string): Promise<void> {
  await page.addInitScript((k: string) => {
    window.localStorage.setItem(k, 'true')
  }, key)
}

/**
 * alice 로 로그인 폼을 제출한다(identifier-first 2단계 흐름) — `fixtures/auth-fixtures.ts` 의
 * `loginAsAlice` 와 동일한 스텝이지만, 로그인 성공 후 도착 URL 을 기다리지 않는다.
 *
 * S8 은 시작 페이지 설정(my_issues)에 따라 `/dashboards` 가 아닌 `/issues`로 도착해야 하므로
 * `loginAsAlice`(항상 dashboard URL 을 기다림)를 재사용할 수 없다 — 호출자가 원하는 목적지를
 * 직접 단언한다(`start-page.spec.ts` `submitAliceLogin` 선례와 동일 패턴, 파일 간 import 불가라
 * 로컬로 복제).
 */
async function submitAliceLoginForm(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
}

/**
 * Header 계정 드롭다운에서 로그아웃해 로그인 폼까지 소프트 네비게이션으로 도달한다.
 * `start-page.spec.ts` 의 `logout` 과 동일한 우회(로그아웃 버튼의 기존 회귀로 `/login` 으로
 * 자동 이동하지 않아 popstate 를 직접 재발행한다, 하드 리로드 아님 — AUTH_USERS/활성 프로젝트
 * 스토어 모듈 상태를 보존해야 S8 재로그인이 의미가 있다).
 */
async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: /계정 메뉴$/ }).click()
  await page.getByRole('menuitem', { name: '로그아웃', exact: true }).click()
  await page.waitForFunction(() => sessionStorage.getItem('bts.auth') === null)

  await page.evaluate(() => {
    history.pushState({}, '', '/login')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
}

test.describe('활성 프로젝트 컨텍스트 (FR-UX-07)', () => {
  test('S1: ?projectKey=ZETA 로 진입하면 그 프로젝트가 활성이 되고 저장된다', async ({ page }) => {
    await seedActiveProject(page, 'MIDDLE')
    await loginAsAlice(page)

    await page.goto('/issues?projectKey=ZETA')
    await waitForIssueList(page)

    // 명시 지정이 저장값(MIDDLE)을 이긴다
    expect(await readActiveProject(page)).toBe('ZETA')
    expect(page.url()).toContain('projectKey=ZETA')
  })

  test('S2: URL 에 projectKey 가 없으면 저장값이 유지된다 (첫 프로젝트로 덮이지 않는다)', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/issues')
    await waitForIssueList(page)

    expect(await readActiveProject(page)).toBe('ZETA')
  })

  test('S3: 저장값도 URL 도 없으면 목록의 첫 프로젝트가 선택되고 저장된다', async ({ page }) => {
    await loginAsAlice(page)
    // 로그인 과정에서 값이 심어졌을 수 있으므로 명시적으로 비운다
    await page.evaluate((key) => { window.localStorage.removeItem(key) }, ACTIVE_PROJECT_KEY)

    await page.goto('/issues')
    await waitForIssueList(page)

    expect(await readActiveProject(page)).toBe(FIRST_PROJECT)
  })

  /**
   * ★ S4 — plan 독립 리뷰 BLOCKER B1 이 없었으면 이 시나리오는 구현되지 않았다.
   * `/projects/$projectKey/*` 를 볼 때 그 키를 기록하는 지점이 어디에도 없었다.
   */
  test('S4: 프로젝트 보드를 보다 사이드바 "이슈"를 누르면 그 프로젝트의 이슈가 열린다', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ATLAS')
    await loginAsAlice(page)

    await page.goto('/projects/ZETA/board')
    // 경로 파라미터가 저장값에 기록될 때까지 기다린다
    await expect.poll(() => readActiveProject(page)).toBe('ZETA')

    const mainNav = page.getByRole('navigation', { name: navLabels.mainNav })
    await mainNav.getByRole('link', { name: navLabels.issues, exact: true }).click()

    await page.waitForURL('**/issues*')
    await waitForIssueList(page)
    expect(await readActiveProject(page)).toBe('ZETA')
  })

  /**
   * 스펙 §2 S5 — "Given 사용자가 접근 가능한 프로젝트가 하나도 없다. When `/issues` 로 진입한다.
   * Then 이슈를 조회하지 않고 빈 상태 안내 + `/projects` 링크가 보인다."
   * FR8과 §9 완료 기준 "프로젝트 0개 → EmptyState + fetchIssues 0회 + /projects 링크"를 그대로 검증한다.
   */
  test('S5: 접근 가능한 프로젝트가 0개면 빈 상태 안내와 /projects 링크가 보이고, 이슈를 조회하지 않는다', async ({
    page,
  }) => {
    let issuesRequested = false
    page.on('request', (request) => {
      if (request.method() === 'GET' && /\/api\/v1\/issues\?/.test(request.url())) {
        issuesRequested = true
      }
    })

    await seedScenarioFlag(page, LS_KEY_PROJECT_LIST_EMPTY)
    await loginAsAlice(page)

    await page.goto('/issues')

    await expect(page.getByText('접근 가능한 프로젝트가 없습니다.')).toBeVisible()
    await expect(
      page.getByRole('link', { name: '프로젝트 목록으로 이동' }),
    ).toHaveAttribute('href', '/projects')
    expect(issuesRequested).toBe(false)
  })

  /**
   * 스펙 §2 S6 — "Given 저장값이 `OLDPROJ` 인데 그 프로젝트가 삭제됐거나 권한이 회수됐다.
   * When `/issues` 로 진입한다. Then `OLDPROJ` 로 조회하지 않고 폴백 ③(첫 프로젝트)으로
   * 내려가며, 저장값이 교정된다."
   * `OLDPROJ` 는 시드(ATLAS·MIDDLE·ZETA·NOVA) 어디에도 없는 키 — 대조 실패를 그대로 재현한다.
   */
  test('S6: 저장값이 낡으면(목록에 없음) 첫 프로젝트로 폴백하고 저장값이 교정된다', async ({
    page,
  }) => {
    await seedActiveProject(page, 'OLDPROJ')
    await loginAsAlice(page)

    const [issuesRequest] = await Promise.all([
      page.waitForRequest(
        (request) => request.method() === 'GET' && /\/api\/v1\/issues\?/.test(request.url()),
      ),
      page.goto('/issues'),
    ])
    await waitForIssueList(page)

    // OLDPROJ 로 조회하지 않았다 — 실제 발사된 요청의 projectKey 가 폴백 ③(첫 프로젝트)이다
    expect(new URL(issuesRequest.url()).searchParams.get('projectKey')).toBe(FIRST_PROJECT)
    // 저장값이 교정됐다
    expect(await readActiveProject(page)).toBe(FIRST_PROJECT)
  })

  /**
   * 스펙 §2 S7 — "Given 사용자가 `NOPERM` 에 접근 권한이 없다. When `/issues?projectKey=NOPERM`
   * 로 진입한다. Then 조용히 다른 프로젝트로 바꾸지 않고 **에러를 표시**한다. 저장값도
   * 갱신하지 않는다."
   * `NOPERM` 은 `mocks/issue-handlers.ts` 의 sentinel 프로젝트 키(GET /api/v1/issues → 403).
   * `resolveActiveProjectKey` 는 URL 키의 접근 가능 여부를 검사하지 않고 그대로 통과시키므로
   * (조용한 대체 금지, `lib/active-project.ts`), 에러는 이 요청 실패로 IssueListPage 자체의
   * `role="alert"` 분기에서 나온다 — ActiveProjectGate 가 아니다(프로젝트 목록 자체는 정상).
   */
  test('S7: 접근 불가한 프로젝트를 명시하면 조용히 대체하지 않고 에러를 표시하며 저장값도 갱신하지 않는다', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ATLAS')
    await loginAsAlice(page)

    await page.goto('/issues?projectKey=NOPERM')

    // 조용한 대체 금지 — URL 의 명시값이 그대로 유지된다
    expect(page.url()).toContain('projectKey=NOPERM')
    // 에러 표시
    await expect(page.getByText('이슈 목록을 불러올 수 없습니다.')).toBeVisible()
    // 저장값 미갱신 — 명시 지정이 실패했으므로 기존 저장값(ATLAS)이 그대로 남는다
    expect(await readActiveProject(page)).toBe('ATLAS')
  })

  /**
   * ★ B2 — `handleFilterChange` 만 `...prev` 를 펼치지 않아 필터 한 번에 projectKey 가
   * URL 에서 증발하던 회귀. 타입 체크로도 안 잡히는 조용한 결함이었다.
   */
  test('B2: 필터를 바꿔도 URL 의 projectKey 가 유지된다', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto('/issues?projectKey=ZETA')
    await waitForIssueList(page)

    // 필터 바의 "초기화" 가 handleFilterChange 를 태우는 가장 짧은 경로다
    await page.getByRole('button', { name: /초기화/ }).click()

    await expect.poll(() => page.url()).toContain('projectKey=ZETA')
    expect(await readActiveProject(page)).toBe('ZETA')
  })

  /**
   * NFR5 — 마운트 직후 URL 을 정규화하지 않는다. 이 저장소에는 transient URL 관측 race 로
   * e2e 가 90초 hang 한 선례가 있어(`saved-filters` SF-1/SF-3) 그 패턴을 새로 들이지 않는다.
   */
  test('NFR5: 진입 후 URL 이 저절로 바뀌지 않는다 (정규화 없음)', async ({ page }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/issues')
    await waitForIssueList(page)
    const afterLoad = page.url()

    // 해소가 끝난 뒤에도 URL 에 projectKey 가 주입되지 않아야 한다
    await page.waitForTimeout(500)
    expect(page.url()).toBe(afterLoad)
    expect(page.url()).not.toContain('projectKey=')
  })

  /**
   * 스펙 §2 S8 — "Given 사용자의 시작 페이지가 `my_issues` 이고 ATLAS 에는 담당 이슈가 없다.
   * When 로그인한다. Then 활성 프로젝트 기준 담당 이슈가 보인다 (기존에는 항상 ATLAS 라 빈 화면)."
   *
   * ★ mock 한계 — `GET /api/v1/issues` 는 애초에 projectKey 로 이슈를 필터링하지 않으므로
   * (issue-fixtures 가 프로젝트 스코핑을 흉내내지 않는다), "화면 내용이 비었다가 채워진다"는
   * 문자 그대로 재현할 수 없다. 대신 이 결함의 정확한 원인 — `routes/issues.index.tsx` 의
   * `DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩 — 이 사라졌다는 **유일한 관측 가능 증거**를 잡는다.
   * 즉 재로그인 후 실제로 발사되는 `GET /api/v1/issues` 요청의 `projectKey` 쿼리 파라미터가
   * (`resolveStartPageNav`의 `my_issues` 분기가 projectKey 를 아예 싣지 않으므로) 하드코딩
   * ATLAS 가 아니라 활성 프로젝트(MIDDLE)로 나가는지를 네트워크 레벨에서 가로채 확인한다.
   */
  test.describe('S8: 시작 페이지 my_issues 파생 결함 해소', () => {
    test('Given 시작 페이지=내 이슈 + 활성 프로젝트=MIDDLE When 재로그인 Then /issues 조회가 활성 프로젝트(MIDDLE) 기준으로 나간다', async ({
      page,
    }) => {
      // Given. alice 로 최초 로그인(기본 시작 페이지 dashboards) 후, 실제 앱 경로(S4 와 동일한
      // 경로 파라미터 기록기)로 활성 프로젝트를 ATLAS 가 아닌 MIDDLE 로 세팅한다.
      // localStorage 를 직접 쓰면 이미 하이드레이션된 zustand 스토어의 메모리 상태가 갱신되지
      // 않으므로, 반드시 실제 네비게이션 경로를 통해야 한다.
      await loginAsAlice(page)
      await page.goto('/projects/MIDDLE/board')
      await expect.poll(() => readActiveProject(page)).toBe('MIDDLE')

      // 시작 페이지를 "내 이슈"(my_issues)로 변경 — PATCH 완료까지 대기
      await page.goto('/settings/preferences')
      await expect(page.getByRole('heading', { name: '환경 설정', exact: true })).toBeVisible()
      const startPageCombobox = page.getByRole('combobox', { name: '시작 페이지', exact: true })
      await startPageCombobox.click()
      await page.getByRole('option', { name: '내 이슈', exact: true }).click()
      await expect(startPageCombobox).toBeEnabled()

      // When. 로그아웃 후 재로그인 — 소프트 네비게이션(AUTH_USERS·활성 프로젝트 스토어 모듈
      // 상태를 보존해야 방금 저장한 startPage/활성 프로젝트가 "다음 로그인"에 반영된다).
      await logout(page)

      const [issuesRequest] = await Promise.all([
        page.waitForRequest(
          (request) => request.method() === 'GET' && /\/api\/v1\/issues\?/.test(request.url()),
        ),
        submitAliceLoginForm(page),
      ])

      // Then. my_issues 는 담당자 필터만 싣고 projectKey 는 싣지 않는다(lib/start-page.ts
      // resolveStartPageNav) — 그런데도 실제 발사된 요청의 projectKey 가 활성 프로젝트다.
      // 이전(하드코딩 ATLAS)이었다면 이 값은 항상 'ATLAS'로 고정됐을 것이다.
      const issuesUrl = new URL(issuesRequest.url())
      expect(issuesUrl.searchParams.get('projectKey')).toBe('MIDDLE')
      expect(issuesUrl.searchParams.get('assignee')).toBe(ALICE_USER_ID)
      await expect(page).toHaveURL(/\/issues\?/)
    })
  })

  /**
   * ★ 라벨 정정 — 이 테스트는 스펙의 "S8"이 아니다. S8(§2)은 "시작 페이지 my_issues 파생 결함
   * 해소"를 가리키는 완전히 다른 시나리오다(바로 위 S8 테스트 참고). 이 테스트가 실제로
   * 검증하는 것은 스펙 §7 엣지 케이스 표의 **E8**이다.
   *
   * > E8 | `/search`는 이미 `?projectKey=`를 읽는다 | 우선순위 구조 유지, 폴백 상수만 해소
   * > 결과로 교체 |
   *
   * URL 에 명시적 projectKey 가 없을 때 `/search` 가 (구현 전 하드코딩이던 `DEFAULT_PROJECT_KEY`
   * 대신) 활성 프로젝트 해소 결과를 폴백으로 쓰는지를 검증한다 — S1~S8 어디에도 없는, FR6/E8
   * 전용 파생 검증이다.
   */
  test('E8: 검색 화면도 같은 활성 프로젝트를 따른다 (스펙 §7 E8 — 폴백 상수 교체)', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/search')
    await expect(page.getByRole('heading', { name: 'AQL 검색' })).toBeVisible()

    // 검색 화면이 렌더됐다는 것은 활성 프로젝트가 해소됐다는 뜻이다
    // (0개/로딩/에러면 ActiveProjectGate 가 대신 렌더된다)
    expect(await readActiveProject(page)).toBe('ZETA')
  })

  /**
   * 스펙 §7 E2 — "프로젝트 목록 조회 실패 → 에러 + 재시도. 저장값으로 추측 진행 금지."
   * 코드리뷰가 적발한 결함(번호 미상 A) — 소비처 2곳(`/issues`·`/search`) 중 한쪽만 재시도
   * 버튼이 배선됐던 반쪽 봉합 — 이므로 **두 화면 모두** 검증한다.
   *
   * ★ 콜드 에러 재현 — `useResolvedActiveProject` 는 캐시가 있으면 재조회 실패에도 그것을
   * 쓴다. 플래그를 `addInitScript` 로 최초 진입(로그인) 전부터 심어, Sidebar 의 `ProjectTree`
   * 를 포함한 **모든** `GET /api/v1/projects` 조회가 처음부터 실패하게 만든다(같은 queryKey
   * `['projects', false]` 공유 — msw-derived-behavior-shared-store-e2e).
   */
  test('E2 (/issues): 프로젝트 목록 조회 실패 시 에러와 다시 시도 버튼이 보이고, 재시도로 복구된다', async ({
    page,
  }) => {
    await seedScenarioFlag(page, LS_KEY_PROJECT_LIST_ERROR)
    await loginAsAlice(page)

    await page.goto('/issues')

    await expect(page.getByText('프로젝트 목록을 불러올 수 없습니다.')).toBeVisible()
    const retryButton = page.getByRole('button', { name: '다시 시도' })
    await expect(retryButton).toBeVisible()

    // 재시도가 성공하도록 플래그를 내린 뒤 클릭 — 재조회가 실제로 복구되는지 확인한다
    await page.evaluate(
      (key: string) => window.localStorage.removeItem(key),
      LS_KEY_PROJECT_LIST_ERROR,
    )
    await retryButton.click()

    await waitForIssueList(page)
  })

  test('E2 (/search): 프로젝트 목록 조회 실패 시 에러와 다시 시도 버튼이 보이고, 재시도로 복구된다', async ({
    page,
  }) => {
    await seedScenarioFlag(page, LS_KEY_PROJECT_LIST_ERROR)
    await loginAsAlice(page)

    await page.goto('/search')

    await expect(page.getByText('프로젝트 목록을 불러올 수 없습니다.')).toBeVisible()
    const retryButton = page.getByRole('button', { name: '다시 시도' })
    await expect(retryButton).toBeVisible()

    await page.evaluate(
      (key: string) => window.localStorage.removeItem(key),
      LS_KEY_PROJECT_LIST_ERROR,
    )
    await retryButton.click()

    await expect(page.getByRole('heading', { name: 'AQL 검색' })).toBeVisible()
  })
})
