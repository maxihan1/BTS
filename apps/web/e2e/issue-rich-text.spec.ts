// 리치 텍스트 서식이 실제로 **화면에 보이는지** 재는 E2E — CSS 부재 회귀 차단 (J8·J22·J23·J24)
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * ## 왜 이 spec 이 있나
 *
 * 유닛 테스트는 「버튼을 누르면 `<ol>` 이 만들어진다」까지만 잰다. 이 PR 이 고친 결함은
 * 그 다음 단계였다 — **`<ol>` 은 만들어지는데 화면에 번호가 안 보였다.**
 * `@tailwindcss/typography` 없이 `prose` 클래스를 써서 스타일이 통째로 없었고,
 * Tailwind preflight 가 `list-style: none` 으로 마커를 지웠기 때문이다.
 *
 * 그 결함은 **계산된 스타일(computed style)** 을 봐야만 잡힌다. DOM 구조만 보는 테스트는
 * 전부 초록이었다 — 실제로 몇 달간 그랬다.
 *
 * ## 무엇을 재나
 *
 * ① 목록에 실제 마커가 붙는가 (`list-style-type`)
 * ② 코드 블록이 자기 안에서 가로 스크롤하는가 (`overflow-x`)
 * ③ 제목이 본문보다 큰가 (preflight 가 `font-size: inherit` 로 지운 것)
 * ④ 툴팁이 **키보드 포커스**에서도 뜨는가 (ADS 계약)
 */

test.describe('리치 텍스트 서식이 화면에 보인다', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  test('번호 목록에 실제 번호 마커가 붙는다 (Maxi 지적 2번)', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')

    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    const editor = page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel })
    await expect(editor).toBeVisible()

    await editor.click()
    await page.getByRole('button', { name: '번호 목록' }).click()
    await editor.pressSequentially('첫째')
    await editor.press('Enter')
    await editor.pressSequentially('둘째')

    // ★DOM 에 `<ol>` 이 있는 것만으로는 부족하다 — 마커가 실제로 그려지는지 본다.
    const ol = editor.locator('ol').first()
    await expect(ol).toBeVisible()
    const listStyle = await ol.evaluate((el) => getComputedStyle(el).listStyleType)
    expect(listStyle).toBe('decimal')
  })

  test('글머리 목록에 불릿이 붙는다', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')
    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    const editor = page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel })
    await editor.click()
    await page.getByRole('button', { name: '글머리 목록' }).click()
    await editor.pressSequentially('항목')

    const ul = editor.locator('ul').first()
    const listStyle = await ul.evaluate((el) => getComputedStyle(el).listStyleType)
    expect(listStyle).toBe('disc')
  })

  test('코드 블록이 자기 안에서 가로 스크롤한다 — 긴 코드가 레이아웃을 밀지 않는다', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')
    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    const editor = page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel })
    await editor.click()
    await page.getByRole('button', { name: '코드 블록' }).click()
    await editor.pressSequentially('const veryLongLine = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"')

    const pre = editor.locator('pre').first()
    const overflowX = await pre.evaluate((el) => getComputedStyle(el).overflowX)
    expect(overflowX).toBe('auto')
  })

  test('제목이 본문보다 크다 — preflight 가 지운 크기를 되돌렸다', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')
    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    const editor = page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel })
    await editor.click()
    await editor.pressSequentially('제목입니다')
    await page.getByLabel('제목 수준').selectOption('1')

    const h1 = editor.locator('h1').first()
    const h1Size = await h1.evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
    const bodySize = await editor.evaluate((el) => parseFloat(getComputedStyle(el).fontSize))
    expect(h1Size).toBeGreaterThan(bodySize)
  })

  test('툴바 툴팁이 키보드 포커스에서 뜬다 (ADS 계약 J24)', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')
    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    await expect(page.getByRole('button', { name: '굵게' })).toBeVisible()

    // native `title` 은 포커스에서 뜨지 않는다. 이 단언이 그 차이를 지킨다.
    await page.getByRole('button', { name: '굵게' }).focus()
    await expect(page.getByRole('tooltip').first()).toBeVisible()
    await expect(page.getByRole('tooltip').first()).toContainText('⌘B')
  })

  test('인용 단축키가 Jira 와 같다 — ⌘⇧9 는 인용이지 체크박스가 아니다 (J22)', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')
    await page.getByRole('button', { name: i18nLabels.issueDetail.descriptionEditButton }).click()
    const editor = page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel })
    await editor.click()
    await editor.pressSequentially('인용문')
    await editor.press('ControlOrMeta+Shift+9')

    await expect(editor.locator('blockquote')).toHaveCount(1)
    // 종전에는 TipTap TaskList 가 이 키를 선점해 체크박스 목록이 나왔다.
    await expect(editor.locator('ul[data-type="taskList"]')).toHaveCount(0)
  })
})

test.describe('이슈 생성 폼의 리치 에디터 (J23)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  test('생성 폼 본문에 서식 툴바가 있다 — 종전에는 plain textarea 였다', async ({ page }) => {
    await page.goto('/issues/new')
    // 툴바가 보이면 리치 에디터다. Maxi 지적 1번의 직접 확인.
    await expect(page.getByRole('toolbar', { name: '서식 도구 모음' })).toBeVisible()
    await expect(page.getByRole('button', { name: '번호 목록' })).toBeVisible()
  })
})
