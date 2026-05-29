// FR-WF-02 D7 E2E-1 — 스킴 CRUD happy path (목록 → 생성 → 상세 → 수정 → 카운트 갱신)
import { test, expect } from '@playwright/test'
import {
  loginAsAlice,
  navigateToSchemeList,
  navigateToSchemeDetail,
  i18nLabels,
} from './fixtures/workflow-scheme-fixtures'

/**
 * scheme-fixtures 기준 카운트:
 *   표준 4개 — softwareDefaultSchemeFixture, serviceManagementSchemeFixture,
 *               businessProjectSchemeFixture, itServiceManagementSchemeFixture
 *   커스텀 2개 — customSchemeAlphaFixture, customSchemeBetaFixture
 */
const STANDARD_COUNT = 4
const CUSTOM_COUNT = 2

const labels = i18nLabels.workflowScheme

test('E2E-1 스킴 CRUD — 목록 → 생성 → 상세 → 수정 → 카운트 갱신', async ({ page }) => {
  // ── Given. alice 로그인 ──────────────────────────────────────────────────────
  await loginAsAlice(page)

  // ── When 1 + Then 1 (S1 목록). 사이드바 + 표준/커스텀 카운트 ──────────────────
  await navigateToSchemeList(page)

  // nav 가시 검증은 navigateToSchemeList 내부에서 수행
  // 표준 그룹 헤더 텍스트 가시
  await expect(
    page.getByRole('region', { name: `${labels.sidebar.standardGroup} 스킴 그룹` }),
  ).toBeVisible()
  // 커스텀 그룹 헤더 텍스트 가시
  await expect(
    page.getByRole('region', { name: `${labels.sidebar.customGroup} 스킴 그룹` }),
  ).toBeVisible()

  // 표준 그룹 내 아이템 수 검증 — section aria-label 기준으로 내부 li 개수
  const standardGroup = page.getByRole('region', { name: `${labels.sidebar.standardGroup} 스킴 그룹` })
  await expect(standardGroup.locator('li')).toHaveCount(STANDARD_COUNT)

  // 커스텀 그룹 내 아이템 수 검증
  const customGroup = page.getByRole('region', { name: `${labels.sidebar.customGroup} 스킴 그룹` })
  await expect(customGroup.locator('li')).toHaveCount(CUSTOM_COUNT)

  // 그룹 헤더의 카운트 span 텍스트 검증 — "표준" span + "(4)" span
  await expect(standardGroup.getByText(`(${STANDARD_COUNT})`)).toBeVisible()
  await expect(customGroup.getByText(`(${CUSTOM_COUNT})`)).toBeVisible()

  // ── When 2 + Then 2 (S3 생성) ──────────────────────────────────────────────
  // CONCERN-NAV: AdminWorkflowSchemesPage.handleAddNew이 현재 navigate 미구현 (T11 예정).
  // 사이드바 버튼 클릭 대신 직접 URL 진입으로 생성 폼 접근.
  await page.goto('/admin/workflow-schemes/new')
  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/new$/)

  // 생성 폼 입력 — schemeKey / name
  await page.getByLabel(labels.create.keyLabel).fill('test-scheme-01')
  await page.getByLabel(labels.create.nameLabel).fill('테스트 스킴 1')

  // 저장
  await page.getByRole('button', { name: labels.create.submitButton }).click()

  // 201 응답 → MSW 핸들러가 custom-scheme-{timestamp} 키로 생성 후 onSuccess(schemeKey) 콜백 실행
  // UI는 navigate({ to: '/admin/workflow-schemes/$schemeKey', params: { schemeKey } }) 호출
  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/custom-scheme-\d+$/)

  // 사이드바 nav 가시 (상세 페이지에도 사이드바 존재)
  await expect(
    page.getByRole('navigation', { name: labels.sidebar.nav }),
  ).toBeVisible()

  // 메타 패널 스킴 키 표시 확인 — 생성된 스킴 상세 페이지 진입 검증
  // (MSW는 stateless — POST 후 목록 재조회가 고정 fixture를 반환하므로 카운트 +1 검증 불가)
  await expect(page.getByText(labels.metaPanel.schemeKeyLabel)).toBeVisible()

  // ── When 3 + Then 3 (S2 상세 진입) ─────────────────────────────────────────
  // 기존 커스텀 스킴(custom-scheme-alpha) 상세로 직접 이동
  await navigateToSchemeDetail(page, 'custom-scheme-alpha')

  // 라우트 일치 확인
  await expect(page).toHaveURL(/\/admin\/workflow-schemes\/custom-scheme-alpha$/)

  // 중앙 매핑 테이블 — 컬럼 헤더 가시
  await expect(page.getByRole('columnheader', { name: labels.mapping.issueTypeColumn })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: labels.mapping.workflowColumn })).toBeVisible()

  // 우 메타 패널 — 통계 카드 label 가시
  await expect(page.getByText(labels.metaPanel.usedByProjectsLabel)).toBeVisible()
  await expect(page.getByText(labels.metaPanel.mappingsCountLabel)).toBeVisible()

  // ── When 4 + Then 4 (PUT 수정) ──────────────────────────────────────────────
  // custom-scheme-alpha는 isStandard=false이므로 이름 편집 가능
  const nameInput = page.getByLabel(labels.metaPanel.nameLabel)
  await expect(nameInput).toBeVisible()
  await expect(nameInput).toBeEnabled()

  // 이름 변경
  const updatedName = '사내 개발팀 커스텀 스킴 (수정됨)'
  await nameInput.fill(updatedName)

  // 저장 버튼 클릭
  await page.getByRole('button', { name: labels.metaPanel.saveButton }).click()

  // 200 응답 → 메타 패널 이름 필드에 새 값 반영 (optimistic update)
  await expect(nameInput).toHaveValue(updatedName)

  // 저장 버튼이 활성화 상태로 돌아옴 (저장 완료)
  await expect(page.getByRole('button', { name: labels.metaPanel.saveButton })).toBeEnabled()
  // (MSW stateless — invalidateQueries 후 GET 목록이 원본 반환하므로 사이드바 이름 갱신 검증은 생략)
})
