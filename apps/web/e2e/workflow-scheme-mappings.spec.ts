// FR-WF-02 D7 E2E-2 — 매핑 편집 (추가 + 삭제 + default mapping)
// FR-UX-06 PR13 Task 9 회귀 수정 — /admin/workflow-schemes에 SYSTEM_ADMIN 가드 추가(Task 8)로
// 비-admin alice는 /dashboard로 redirect된다. loginAsSystemAdmin으로 갱신.
import { test, expect } from '@playwright/test'
import { loginAsSystemAdmin, navigateToSchemeDetail, i18nLabels } from './fixtures/workflow-scheme-fixtures'

/**
 * 테스트 대상 스킴: custom-scheme-alpha
 * - bug 매핑 (id 50, issueTypeKey: 'bug', issueTypeName: '버그')
 * - default 매핑 (id 51, issueTypeKey: null, isDefault: true)
 * - story, task, epic, subtask 이슈 타입은 미매핑 → S4 추가 가능
 *
 * MSW 핸들러는 stateless (메모리 변경 없음).
 * S4: POST 201 응답 body 를 assert (낙관적 업데이트 + 서버 응답 검증).
 * S5: DELETE 204 응답 후 낙관적 삭제 row 제거 검증.
 * S6: hasDefaultMapping=true 시 sentinel 옵션 숨김 검증.
 * S6 보완: default 행 강조 (★ prefix + badge) 검증.
 */

const labels = i18nLabels.workflowScheme
const SCHEME_KEY = 'custom-scheme-alpha'

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 매핑 추가 (POST 201 응답 body 검증)
// ─────────────────────────────────────────────────────────────────────────────

test('S4 — 매핑 추가: story 이슈 타입 + 워크플로우 선택 → 추가 클릭 → POST 201 응답 수신', async ({ page }) => {
  /*
   * Given. alice 로그인 + custom-scheme-alpha 상세 페이지 진입
   * When.  "이슈 타입 선택" select → "스토리" 선택
   *        "워크플로우 선택" select → 첫 번째 옵션 선택
   *        "추가" 버튼 클릭
   * Then.  POST /api/v1/workflow-schemes/.../mappings 요청 발생
   *        응답 status 201 + body.data.issueTypeKey === 'story'
   */

  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, SCHEME_KEY)

  // 추가 행 이슈 타입 select 노출 확인
  const issueTypeSelect = page.getByRole('combobox', {
    name: labels.mapping.issueTypeSelectAriaLabel,
  })
  await expect(issueTypeSelect).toBeVisible()

  // story 이슈 타입 선택
  await issueTypeSelect.selectOption({ label: '스토리' })

  // 워크플로우 select — 첫 번째 실제 옵션 선택 (placeholder 제외, index 1)
  const workflowSelect = page.getByRole('combobox', {
    name: labels.mapping.workflowSelectAriaLabel,
  })
  await expect(workflowSelect).toBeVisible()
  await workflowSelect.selectOption({ index: 1 })

  // POST 응답 캡처 준비
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/v1/workflow-schemes/${SCHEME_KEY}/mappings`) &&
      response.request().method() === 'POST',
  )

  // 추가 버튼 클릭
  const addButton = page.getByRole('button', {
    name: labels.mapping.addMappingAriaLabel,
  })
  await expect(addButton).toBeEnabled()
  await addButton.click()

  // Then — POST 201 응답 수신 + body.data.issueTypeKey === 'story'
  const response = await responsePromise
  expect(response.status()).toBe(201)
  const body = await response.json() as { data: { issueTypeKey: string } }
  expect(body.data.issueTypeKey).toBe('story')
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 매핑 삭제 (DELETE 204 응답 검증)
// ─────────────────────────────────────────────────────────────────────────────

test('S5 — 매핑 삭제: bug 매핑 행 "삭제" → 확인 모달 → 확인 → DELETE 204 응답 수신', async ({ page }) => {
  /*
   * Given. alice 로그인 + custom-scheme-alpha 상세 진입 (bug 매핑 id 50 존재)
   * When.  bug 매핑 행의 "삭제" 버튼 클릭
   *        확인 dialog (aria-label="매핑 삭제 확인") 의 "확인" 버튼 클릭
   * Then.  DELETE /api/v1/workflow-schemes/.../mappings/50 요청 → 204 응답
   *
   * Note.  MSW stateless 이므로 낙관적 삭제 후 onSettled invalidate 시 bug 행 복원됨.
   *        E2E 는 서버 응답(204)과 DELETE 요청 발생 자체를 assert.
   */

  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, SCHEME_KEY)

  // bug 매핑 삭제 버튼 — aria-label "매핑 삭제 (★ 기본값...)" 또는 "매핑 삭제 (버그)" 형태
  // MappingRow: `매핑 삭제 (${issueTypeDisplay})` — bug 행은 issueTypeName '버그'
  const deleteButton = page.getByRole('button', {
    name: /매핑 삭제 \(버그\)/i,
  })
  await expect(deleteButton).toBeVisible()

  // DELETE 응답 캡처 준비
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/v1/workflow-schemes/${SCHEME_KEY}/mappings/`) &&
      response.request().method() === 'DELETE',
  )

  await deleteButton.click()

  // 확인 dialog 가시
  const confirmDialog = page.getByRole('dialog', {
    name: labels.mapping.confirmDeleteDialogAriaLabel,
  })
  await expect(confirmDialog).toBeVisible()

  // 확인 버튼 클릭
  await confirmDialog
    .getByRole('button', { name: labels.mapping.confirmDeleteButton })
    .click()

  // Then — DELETE 204 응답 수신
  const response = await responsePromise
  expect(response.status()).toBe(204)
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — default mapping: hasDefaultMapping=true 시 sentinel 옵션 숨김
// ─────────────────────────────────────────────────────────────────────────────

test('S6 — default mapping: hasDefaultMapping=true → "기본값" 옵션 select 에서 숨김', async ({ page }) => {
  /*
   * Given. alice 로그인 + custom-scheme-alpha 상세 진입
   *        (fixture: default 매핑 id 51 — isDefault: true 이미 존재)
   * Then.  이슈 타입 select 에 data-testid="option-default" 옵션 없음
   *        (MappingTable AddMappingRow: !hasDefaultMapping 조건으로 조건부 렌더)
   */

  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, SCHEME_KEY)

  // 이슈 타입 select 가시 확인
  const issueTypeSelect = page.getByRole('combobox', {
    name: labels.mapping.issueTypeSelectAriaLabel,
  })
  await expect(issueTypeSelect).toBeVisible()

  // custom-scheme-alpha 에 default 매핑이 이미 있으므로 "기본값" 옵션 숨겨져야 함
  const defaultOption = page.locator('[data-testid="option-default"]')
  await expect(defaultOption).toHaveCount(0)
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 보완 — default 매핑 행 강조(highlight) 노출 검증
// ─────────────────────────────────────────────────────────────────────────────

test('S6 보완 — default 매핑 행 강조: ★ prefix + "기본" badge 노출', async ({ page }) => {
  /*
   * Given. alice 로그인 + custom-scheme-alpha 상세 진입
   *        (fixture: default 매핑 id 51 — isDefault: true)
   * Then.  default 매핑 행에 "★ 기본값 (모든 이슈 타입)" 텍스트 노출
   *        "기본" badge 노출
   */

  await loginAsSystemAdmin(page)
  await navigateToSchemeDetail(page, SCHEME_KEY)

  // default 매핑 행 — ★ prefix 포함 텍스트
  await expect(
    page.getByText(labels.mapping.defaultRowPrefix),
  ).toBeVisible()

  // "기본" badge — exact 매칭 + 여러 요소 허용 (first 사용)
  // '기본' 텍스트는 페이지 내 다수 노출 (스킴 이름, 컬럼 헤더 등) — badge span 클래스 기반 locator
  await expect(
    page.locator('span').filter({ hasText: /^기본$/ }).first(),
  ).toBeVisible()
})
