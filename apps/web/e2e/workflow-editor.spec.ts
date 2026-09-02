// FR-WF-07 D6 E2E — /admin/workflows 초안 편집기 (편집 ≠ 배포 · 초안 생존 · 사이드바 계약)
//
// 관련 함정 메모리.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
//   - msw-mutation-stateful-refetch: workflow-draft-handlers 는 인메모리 stateful —
//     저장 후 재진입으로 화면이 실제로 갱신되는지까지 본다
//   - ★ 라벨 substring: '워크플로우' 는 '워크플로우 스킴' 의 substring 이라 부분일치가
//     둘을 함께 잡는다. 사이드바 링크는 반드시 '워크플로우 관리' 로 집는다.
//
// ★ FR-WF-04 D7 에서 넘어오며 **의미가 바뀐 시나리오들이다.** 종전 E2E-1·2·3 은 「고치면
//   서버가 바뀐다」를 쟀는데, 초안 전환 뒤에는 그것이 **거짓**이다 — 발행해야 바뀐다.
//   그 전환 자체가 FR-WF-07 의 내용이므로 판정도 함께 뒤집었다.
import { test, expect } from '@playwright/test'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'
import { gotoAdminPage, openAdminLinks } from './fixtures/admin-hub'

/** 목 픽스처 기준 — software-default 는 5 상태 + 7 전환(INITIAL 1 + NORMAL 6) */
const TARGET_KEY = 'software-default'
const TARGET_NAME = '소프트웨어 개발 기본 워크플로우'

/** 관리 진입점을 통해 목록으로 간다 — 진입점이 실제로 걸려 있는지까지 함께 본다 */
async function navigateToWorkflowList(page: import('@playwright/test').Page): Promise<void> {
  // exact 로 집는다 — '워크플로우 관리' 는 '워크플로우 스킴' 과 접두를 공유한다(헬퍼가 보장)
  await gotoAdminPage(page, '워크플로우 관리')
  await expect(page.getByRole('heading', { level: 1, name: '워크플로우 관리' })).toBeVisible()
}

/** 목록에서 편집기로 들어간다. */
async function openEditor(page: import('@playwright/test').Page): Promise<void> {
  await page
    .getByRole('table', { name: '워크플로우 목록' })
    .getByRole('row')
    .filter({ hasText: TARGET_NAME })
    .getByRole('button', { name: `편집 ${TARGET_NAME}` })
    .click()
  await expect(page.getByRole('list', { name: '편성된 상태 목록' })).toBeVisible()
}

test('E2E-1 이름을 고쳐도 발행 전에는 목록이 옛 이름을 보여준다 (D7 · 편집 ≠ 배포)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToWorkflowList(page)
  await openEditor(page)

  const nameInput = page.getByRole('textbox', { name: '워크플로우 이름' })
  await nameInput.fill('이름을 고친 워크플로우')

  // 자동저장이 끝난 뒤에도 정규 정의는 그대로다.
  await expect(page.getByText('저장됨')).toBeVisible()
  await page.getByRole('button', { name: '목록으로' }).click()
  const table = page.getByRole('table', { name: '워크플로우 목록' })
  await expect(table).toContainText(TARGET_NAME)
  await expect(table).not.toContainText('이름을 고친 워크플로우')
})

test('E2E-2 상태를 추가하면 초안이 살아 있고, 발행해야 목록에 반영된다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToWorkflowList(page)
  await openEditor(page)

  const statusList = page.getByRole('list', { name: '편성된 상태 목록' })
  const before = await statusList.getByRole('listitem').count()

  // ── 상태 추가 ──
  await page.getByRole('button', { name: '상태 추가' }).click()
  const picker = page.getByRole('dialog', { name: '워크플로우에 추가할 상태 선택' })
  await expect(picker).toBeVisible()
  await picker.getByRole('combobox', { name: '워크플로우에 추가할 상태 선택' }).click()
  await page.getByRole('option', { name: 'Blocked', exact: true }).click()
  await picker.getByRole('button', { name: '상태 추가' }).click()

  await expect(statusList.getByRole('listitem')).toHaveCount(before + 1)
  await expect(page.getByText('저장됨')).toBeVisible()

  // ── 목록으로 나갔다 **클라이언트 사이드로** 다시 들어와도 초안이 살아 있다 ──
  //
  // ★ `page.goto()` 로 돌아오면 안 된다. full navigation 이라 MSW 핸들러 모듈이 재평가되고
  //   목 저장소가 리셋된다. 처음에 그렇게 썼다가 red 였다 — 목이 브라우저 메모리에 사는 이상
  //   「재기동 생존」은 프론트 E2E 로 검증할 수 있는 계약이 아니다.
  await page.getByRole('button', { name: '목록으로' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '워크플로우 관리' })).toBeVisible()
  await openEditor(page)
  await expect(page.getByRole('list', { name: '편성된 상태 목록' })).toContainText('Blocked')
})

test('E2E-3 전환 이름을 고치면 초안 목록이 새 이름을 보여준다 (D7)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  // 여기서는 `goto` 가 무해하다 — 진입 직후 픽스처 상태에서 시작하고, 그 뒤로 재진입하지 않는다.
  await page.goto(`/admin/workflows/${TARGET_KEY}`)
  await page.getByRole('tab', { name: '전환' }).click()

  const list = page.getByRole('list', { name: '전환 목록' })
  await expect(list).toBeVisible()

  // 픽스처의 INITIAL 전환에는 종류 배지가 붙는다 — 출발 상태가 없다는 것을 사용자가 안다
  await expect(list).toContainText('이슈 생성 시')

  await page.getByRole('button', { name: '전환 편집 Start Work' }).click()
  const dialog = page.getByRole('dialog', { name: '전환 수정' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '전환 이름' }).fill('작업 시작하기')
  await dialog.getByRole('button', { name: '저장' }).click()

  await expect(list).toContainText('작업 시작하기')
  await expect(list).not.toContainText('Start Work')
})

test('E2E-4 관리 진입점의 워크플로우 링크 이름이 스킴 링크와 겹치지 않는다 (§2 즉사 계약)', async ({ page }) => {
  await loginAsSystemAdmin(page)
  const adminLinks = await openAdminLinks(page)

  // 부분일치(기본)로 '워크플로우' 를 집으면 둘이 잡힌다 — 그 사실 자체를 못박는다.
  // 이 단언이 1 로 바뀌면 누군가 라벨을 줄인 것이고, 그때 exact 없는 기존 셀렉터가 죽는다.
  await expect(adminLinks.getByRole('link', { name: '워크플로우' })).toHaveCount(2)
  await expect(adminLinks.getByRole('link', { name: '워크플로우 관리', exact: true })).toHaveCount(1)
  await expect(adminLinks.getByRole('link', { name: '워크플로우 스킴', exact: true })).toHaveCount(1)
})
