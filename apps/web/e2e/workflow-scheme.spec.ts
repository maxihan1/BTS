// 워크플로우 스킴 계약 정렬 회귀 E2E — 어휘가 바뀐 필드가 실제로 화면에 그려지는지 값으로 단정한다
import { test, expect } from '@playwright/test'
import {
  loginAsSystemAdmin,
  navigateToSchemeList,
  navigateToSchemeDetail,
  navigateToProjectAssignment,
  i18nLabels,
} from './fixtures/workflow-scheme-fixtures'

/**
 * 이 스펙이 잡는 회귀.
 *
 * 계약 정렬로 응답 어휘가 바뀌었다(`schemeKey`→`key`, `isStandard` 는 백엔드가 직접 제공,
 * `mappings[].isDefault` 신설). 화면이 옛 필드를 읽으면 **에러 없이 빈 값·잘못된 분류**로 렌더된다 —
 * 타입 검사와 단위 테스트는 통과하는데 사용자만 깨진 화면을 보는 조합이다.
 *
 * 기존 16건은 그룹 개수·버튼 존재 같은 **구조**를 보므로 이 실패를 통과시킨다.
 * 여기서는 필드에서 온 **실제 값**을 단정해 그 구멍을 막는다.
 *
 * ⚠️ 범위 한정 — 이 스펙은 MSW 목을 통해 돈다. **백엔드 계약 정합의 증거가 아니다**(그건
 * `WorkflowSchemeContractSnapshotTest` + `workflow-schemes.contract.test.ts` 가 담당한다).
 * 여기서 잡는 것은 "프론트가 계약 필드를 제대로 읽어 그리는가"뿐이다.
 */

const labels = i18nLabels.workflowScheme

test('계약 필드가 실제 값으로 렌더된다 — 목록의 스킴 키·이름', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  // key 를 못 읽으면 항목이 빈 라벨로 남는다 — 이름 텍스트가 실제 값인지 본다.
  await expect(page.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /사내 개발팀 커스텀 스킴/ })).toBeVisible()
})

test('isStandard 로 표준·커스텀 그룹이 갈린다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeList(page)

  // isStandard 를 못 읽으면 전부 한 그룹으로 몰리거나 반대로 갈린다.
  const standardGroup = page.getByRole('region', { name: `${labels.sidebar.standardGroup} 스킴 그룹` })
  const customGroup = page.getByRole('region', { name: `${labels.sidebar.customGroup} 스킴 그룹` })

  await expect(standardGroup.locator('li')).toHaveCount(4)
  await expect(customGroup.locator('li')).toHaveCount(2)
  await expect(standardGroup.getByText('소프트웨어 개발 기본 스킴')).toBeVisible()
  await expect(customGroup.getByText('사내 개발팀 커스텀 스킴')).toBeVisible()
})

test('상세의 스킴 키가 key 필드 값으로 표시된다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, 'custom-scheme-alpha')

  // 메타 패널의 「스킴 키」 값이 비면 schemeKey→key 정렬이 화면까지 이어지지 않은 것이다.
  await expect(page.getByText(labels.metaPanel.schemeKeyLabel)).toBeVisible()
  await expect(page.getByText('custom-scheme-alpha', { exact: true })).toBeVisible()
})

test('mappings[].isDefault 로 기본 매핑 행이 표시된다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, 'custom-scheme-alpha')

  // 백엔드가 명시적으로 실어 보내는 isDefault 를 못 읽으면 ★ 표시가 사라진다.
  // (issueTypeKey === null 로 유도하던 옛 방식이면 cross-BC 조회 실패도 ★ 로 잘못 표시된다.)
  await expect(page.getByText(labels.mapping.defaultRowPrefix)).toBeVisible()
})

test('배정 화면이 key 필드로 현재 스킴을 표시한다', async ({ page }) => {
  await loginAsSystemAdmin(page)
  await navigateToProjectAssignment(page, 'ATLAS')

  // 배정 조회 응답은 스킴 객체 하나다(projectKey/schemeName 은 본문에 없다).
  // 드롭다운이 비어 있으면 assignment.key 를 못 읽은 것이다.
  await expect(
    page.getByRole('combobox', { name: labels.assignment.schemeSelectAriaLabel }),
  ).toContainText('사내 개발팀 커스텀 스킴')
})
