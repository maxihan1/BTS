// FR-UX-08 PR-A D7 E2E — 프로젝트 스위처(F12) + 트리 펼침 영속 시나리오
//
//   S1. 검색 파라미터 없는 라우트에서 전환 — URL 유지, 활성값만 갱신
//   S2. 경로 파라미터 라우트에서 전환 — 같은 하위 경로로 치환 이동
//   ★E7(a). S2 직후 활성값이 되돌아가지 않는다 (useTrackActiveProject:50 되기록 차단)
//   ★E7(b). 검색 파라미터 라우트에서 전환 — ?projectKey= 치환 + 유지
//            (초안이 빠뜨린 분기. useResolvedActiveProject:85-91 되기록 차단)
//   ★E7-b.  검색 파라미터 치환이 다른 파라미터를 지우지 않는다 (...prev 미펼침 판별식)
//   S4. 펼친 트리가 페이지 왕복을 건너 유지된다 (덮어쓰기 폐지 + localStorage 영속)
//   S6. ?projectKey= 만으로도 트리가 활성 프로젝트를 표시한다 (선재 갭)
//
// 관례.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click으로만 페이지 전환.
//   - localStorage 는 `page.addInitScript` 로 로그인 **전에** 심는다(active-project.spec.ts:46,65).
//   - `playwright.config.ts` 에 storageState 가 없어 테스트마다 새 컨텍스트다 —
//     그래도 펼침 영속 테스트는 시작 상태를 명시 초기화한다(리스크 R5).
import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { projectListFixtures } from '../src/mocks/project-list-handlers'

/** `hooks/use-active-project.ts` 저장 키 — 값은 JSON 인코딩 문자열 */
const ACTIVE_PROJECT_KEY = 'bts.active-project'

/** `hooks/use-project-tree-expanded.ts` 저장 키 — 값은 JSON 인코딩 string[] */
const TREE_EXPANDED_KEY = 'bts.project-tree.expanded'

/** MSW 핸들러와 동일한 name 오름차순 정렬 재현 */
const sortedProjects = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))

const atlas = sortedProjects.find((p) => p.key === 'ATLAS')
const other = sortedProjects.find((p) => p.key !== 'ATLAS')
if (atlas === undefined || other === undefined) {
  throw new Error('projectListFixtures 에 ATLAS + 최소 1개 프로젝트가 더 필요하다')
}

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

/** 로그인 전에 localStorage 키를 심는다 (JSON 인코딩) */
async function seedStorage(page: Page, key: string, value: unknown): Promise<void> {
  await page.addInitScript(
    ([k, v]: [string, string]) => {
      window.localStorage.setItem(k, v)
    },
    [key, JSON.stringify(value)] as [string, string],
  )
}

/** 스위처를 열고 지정 프로젝트를 고른다 */
async function switchProject(page: Page, projectName: string): Promise<void> {
  await page.getByRole('button', { name: /프로젝트 선택/ }).click()
  const listbox = page.getByRole('listbox')
  await expect(listbox).toBeVisible()
  await listbox.getByRole('option', { name: projectName, exact: true }).click()
  await expect(listbox).toBeHidden()
}

/** 프로젝트 트리 nav — `exact: true` 필수('프로젝트 뷰 전환'의 substring) */
function projectNav(page: Page) {
  return page.getByRole('navigation', { name: '프로젝트', exact: true })
}

test.describe('FR-UX-08 PR-A — 프로젝트 스위처 · 트리 펼침 영속', () => {
  test('S1 Given /dashboards When 스위처로 전환 Then URL 은 그대로고 활성값만 바뀐다', async ({
    page,
  }) => {
    await seedStorage(page, ACTIVE_PROJECT_KEY, atlas.key)
    await loginAsAlice(page)
    await page.goto('/dashboards')

    await switchProject(page, other.name)

    await expect(page).toHaveURL(/\/dashboards$/)
    await expect.poll(async () => readActiveProject(page)).toBe(other.key)
  })

  test('S2 + ★E7(a) Given /projects/ATLAS/board When 전환 Then 같은 하위 경로로 이동하고 되돌아가지 않는다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${atlas.key}/board`)
    await expect(page).toHaveURL(new RegExp(`/projects/${atlas.key}/board$`))

    await switchProject(page, other.name)

    await expect(page).toHaveURL(new RegExp(`/projects/${other.key}/board$`))
    // ★ 되기록 차단 실증 — useTrackActiveProject:50 이 경로 파라미터를 다시 기록하는데,
    //   경로가 함께 바뀌었으므로 기록되는 값도 새 프로젝트다. 활성값만 바꿨다면 여기서
    //   ATLAS 로 되돌아간다.
    await expect.poll(async () => readActiveProject(page)).toBe(other.key)
  })

  test('★E7(b) Given /issues?projectKey=ATLAS When 전환 Then 검색 파라미터가 바뀌고 유지된다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/issues?projectKey=${atlas.key}`)
    await expect(page).toHaveURL(new RegExp(`projectKey=${atlas.key}`))

    await switchProject(page, other.name)

    // ★ 초안이 빠뜨린 분기다. 활성값만 바꾸면 해소①(URL)이 이겨 화면이 안 바뀌고
    //   useResolvedActiveProject:85-91 이 ATLAS 를 되기록해 선택이 즉시 되돌려진다.
    await expect(page).toHaveURL(new RegExp(`projectKey=${other.key}`))
    await expect.poll(async () => readActiveProject(page)).toBe(other.key)
  })

  test('★E7-b Given ?projectKey=&status=&selected= When 전환 Then 다른 검색 파라미터가 살아남는다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/issues?projectKey=${atlas.key}&status=open&selected=${atlas.key}-1`)

    await switchProject(page, other.name)

    // FR-UX-07 FR4-b 가 겪은 함정 — TanStack `search` 는 객체형이면 병합이 아니라 치환이고
    // 전 필드가 optional 이라 타입 체크로도 안 잡힌다. `...prev` 를 안 펼치면 여기서 증발한다.
    await expect(page).toHaveURL(new RegExp(`projectKey=${other.key}`))
    await expect(page).toHaveURL(/status=open/)
    await expect(page).toHaveURL(new RegExp(`selected=${atlas.key}-1`))
  })

  test('S4 Given 두 프로젝트를 펼침 When 페이지 왕복 Then 둘 다 펼쳐진 채 유지된다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')
    // ★ 시작 상태 초기화는 `addInitScript` 로 하면 안 된다 — 그건 **이후 모든 goto 에
    //   재적용**되므로(active-project.spec.ts:61) 아래 왕복마다 펼침을 다시 지워
    //   "영속되지 않는다"는 거짓 실패를 만든다. 1회성 초기화는 `evaluate` 로 한다.
    await page.evaluate((key) => { window.localStorage.removeItem(key) }, TREE_EXPANDED_KEY)

    const nav = projectNav(page)
    const atlasToggle = nav.getByRole('button', { name: `${atlas.name} 하위 메뉴` })
    const otherToggle = nav.getByRole('button', { name: `${other.name} 하위 메뉴` })

    await atlasToggle.click()
    await otherToggle.click()
    await expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(otherToggle).toHaveAttribute('aria-expanded', 'true')

    // SPA 이동으로 왕복 — reload 금지(store 리셋 = 가짜그린)
    await page.goto('/calendar')
    await page.goto('/dashboards')

    // ★ 덮어쓰기가 남아 있으면 라우트 이동이 펼침 집합을 갈아엎어 둘 다 접힌다
    await expect(nav.getByRole('button', { name: `${atlas.name} 하위 메뉴` })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await expect(nav.getByRole('button', { name: `${other.name} 하위 메뉴` })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  test('S6 Given /issues?projectKey=ATLAS When 트리를 본다 Then ATLAS 가 자동 펼침된다 (선재 갭)', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/issues?projectKey=${atlas.key}`)

    const nav = projectNav(page)
    // ★ 기존 구현은 useParams(경로 파라미터)만 봐서 여기가 'false' 였다
    await expect(nav.getByRole('button', { name: `${atlas.name} 하위 메뉴` })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    // 검색 파라미터로 온 프로젝트에는 aria-current="page" 를 붙이지 않는다 — 사용자는
    // 그 프로젝트의 보드 페이지에 있지 않다
    await expect(nav.getByRole('link', { name: atlas.name, exact: true })).not.toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  test('NFR3 Given 스위처가 배선됨 When 상단바를 본다 Then navigation 랜드마크가 늘지 않는다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    // 스위처를 <nav> 로 만들면 '프로젝트'가 '프로젝트 뷰 전환'의 substring 이라
    // getByRole('navigation') 계약이 흔들린다 (ADR §D5)
    await expect(page.getByRole('button', { name: /프로젝트 선택/ })).toBeVisible()
    await expect(projectNav(page)).toHaveCount(1)
    await expect(page.getByRole('navigation', { name: '메인 메뉴', exact: true })).toHaveCount(1)
  })
})
