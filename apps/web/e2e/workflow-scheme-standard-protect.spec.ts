// FR-WF-02 D7 E2E-3 — 표준 스킴 보호 (삭제 disabled + tooltip + name/description disabled + key readonly)
import { test, expect } from '@playwright/test'
import { loginAsAlice, navigateToSchemeDetail, i18nLabels } from './fixtures/workflow-scheme-fixtures'

const labels = i18nLabels.workflowScheme

/**
 * E2E-3 표준 스킴 보호 시나리오.
 *
 * Given. alice 로그인 + 표준 스킴(isStandard=true) 상세 진입.
 * When 1. 우 메타패널 영역 확인.
 * Then 1. 삭제 버튼 disabled + title tooltip "표준 스킴은 삭제 불가",
 *         저장 버튼 disabled, name/description input disabled, key 영역 readonly(div).
 * When 2. 표준 안내 카드 확인.
 * Then 2. role=note 안내 카드 가시.
 *
 * 구현 참조. SchemeMetaPanel.tsx — isStandard=true 시 saveButton/deleteButton/name/description 모두 disabled.
 * D11 결정. 컴포넌트가 표준 스킴의 name/description 편집도 disabled로 처리한다 (spec §E2E-3 Then 2와 구현 불일치,
 *            구현이 정본이므로 disabled 상태를 검증).
 */

// 표준 스킴 fixture schemeKey (scheme-fixtures.ts softwareDefaultSchemeFixture)
const STANDARD_SCHEME_KEY = 'software-default-scheme'

test.describe('E2E-3 표준 스킴 보호', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 + 표준 스킴 상세 진입
    await loginAsAlice(page)
    await navigateToSchemeDetail(page, STANDARD_SCHEME_KEY)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // When 1 / Then 1 — 삭제 버튼 disabled + tooltip
  // ─────────────────────────────────────────────────────────────────────────────

  test('삭제 버튼 disabled + title tooltip "표준 스킴은 삭제 불가"', async ({ page }) => {
    // When. 메타패널의 삭제 버튼 확인
    const deleteButton = page.getByRole('button', { name: labels.metaPanel.deleteButton })
    await expect(deleteButton).toBeVisible()

    // Then. disabled 상태
    await expect(deleteButton).toBeDisabled()

    // Then. title attribute 가 tooltip 텍스트와 일치 (SchemeMetaPanel line 176: title={scheme.isStandard ? STANDARD_DELETE_TOOLTIP : undefined})
    await expect(deleteButton).toHaveAttribute('title', labels.standardProtect.deleteTooltip)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // When 1 / Then 1 — 저장 버튼 disabled
  // ─────────────────────────────────────────────────────────────────────────────

  test('저장 버튼 disabled (표준 스킴 편집 불가)', async ({ page }) => {
    // When. 메타패널의 저장 버튼 확인
    const saveButton = page.getByRole('button', { name: labels.metaPanel.saveButton })
    await expect(saveButton).toBeVisible()

    // Then. disabled 상태 (SchemeMetaPanel line 165: disabled={scheme.isStandard || ...})
    await expect(saveButton).toBeDisabled()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // When 1 / Then 1 — name / description Input disabled
  // ─────────────────────────────────────────────────────────────────────────────

  test('name/description 입력 필드 disabled (표준 스킴 편집 불가)', async ({ page }) => {
    // When. 이름 라벨로 연결된 Input 확인
    const nameInput = page.getByLabel(labels.metaPanel.nameLabel)
    await expect(nameInput).toBeVisible()

    // Then. name Input disabled (SchemeMetaPanel line 126: disabled={scheme.isStandard})
    await expect(nameInput).toBeDisabled()

    // When. 설명 라벨로 연결된 textarea 확인
    const descTextarea = page.getByLabel(labels.metaPanel.descriptionLabel)
    await expect(descTextarea).toBeVisible()

    // Then. description textarea disabled (SchemeMetaPanel line 143: disabled={scheme.isStandard})
    await expect(descTextarea).toBeDisabled()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // When 1 / Then 1 — 스킴 키 영역 readonly (div, 변경 불가)
  // ─────────────────────────────────────────────────────────────────────────────

  test('스킴 키 영역 — 텍스트 표시만 하는 readonly div (input 없음)', async ({ page }) => {
    // When. 스킴 키 라벨 가시 확인
    const keyLabel = page.getByText(labels.metaPanel.schemeKeyLabel)
    await expect(keyLabel).toBeVisible()

    // Then. 스킴 키 값이 텍스트로 표시됨 (SchemeMetaPanel line 102: div.font-mono, input 아님)
    // MSW fixture schemeKey = 'software-default-scheme'
    await expect(page.locator('div.font-mono').filter({ hasText: STANDARD_SCHEME_KEY })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // When 2 / Then 2 — 표준 안내 카드 가시
  // ─────────────────────────────────────────────────────────────────────────────

  test('표준 스킴 안내 카드 (role=note) 가시', async ({ page }) => {
    // When. 표준 스킴 상세 진입 후 안내 카드 확인
    const noticeCard = page.getByRole('note')
    await expect(noticeCard).toBeVisible()

    // Then. 안내 텍스트 포함 (SchemeMetaPanel line 94: {STANDARD_SCHEME_NOTICE})
    await expect(noticeCard).toHaveText(labels.standardProtect.notice)
  })
})
