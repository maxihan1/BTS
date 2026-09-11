// FR-WF-02 D7 E2E-5 — 프로젝트 스킴 할당 (S9 첫 할당 / S10 자동 할당 안내)
import { test, expect } from '@playwright/test'
import {
  loginAsAlice,
  navigateToProjectAssignment,
  i18nLabels,
} from './fixtures/workflow-scheme-fixtures'

const labels = i18nLabels.workflowScheme.assignment

// ─────────────────────────────────────────────────────────────────────────────
// S9 — 할당 있는 프로젝트에서 스킴 변경 (UPSERT)
//
// Given. alice 로그인 + ATLAS 프로젝트 할당 설정 페이지 진입
//        (ATLAS 는 scheme-fixtures.ts 기준 custom-scheme-alpha 할당됨)
// When.  변경 select 에서 'software-default-scheme' 선택 → 적용 버튼 클릭
// Then.  PUT /api/v1/projects/ATLAS/workflow-scheme 응답 →
//        현재 적용 카드가 '소프트웨어 개발 기본 스킴' 으로 갱신 표시
// ─────────────────────────────────────────────────────────────────────────────

test('E2E-5 S9 — ATLAS 스킴 변경 PUT UPSERT → 현재 적용 카드 갱신', async ({ page }) => {
  await loginAsAlice(page)
  await navigateToProjectAssignment(page, 'ATLAS')

  // 초기 상태 — 현재 할당 스킴 카드 표시 확인
  // CardTitle 은 <div data-slot="card-title"> 이므로 getByText 사용
  await expect(page.getByText(labels.currentSchemeTitle)).toBeVisible()

  // 초기 할당 스킴명 ('사내 개발팀 커스텀 스킴') 이 노출됨을 확인
  // 현재 할당 카드 + select value 두 곳에 동일 텍스트가 있으므로 .first() 로 카드 우선 선택
  await expect(page.getByText('사내 개발팀 커스텀 스킴').first()).toBeVisible()

  // 변경 섹션 제목 ('스킴 변경') 확인
  await expect(page.getByText(labels.changeTitle)).toBeVisible()

  // select 에서 '소프트웨어 개발 기본 스킴' 선택
  const schemeSelect = page.getByRole('combobox', { name: labels.schemeSelectAriaLabel })
  await schemeSelect.click()
  await page.getByRole('option', { name: '소프트웨어 개발 기본 스킴' }).click()

  // 적용 버튼 클릭
  await page.getByRole('button', { name: labels.applyButton }).click()

  /*
   * PUT 응답 후 현재 적용 카드가 새 스킴명으로 갱신되어야 함.
   *
   * ★`.first()` 가 필요하다 (2026-09-11 수정). #483 이 프로젝트 설정에 스킴 목록을
   *   추가하면서 같은 이름이 **세 곳**(현재 적용 카드 · select 값 · 목록)에 뜬다.
   *   한정 없이 `getByText` 를 쓰면 Playwright strict mode 위반으로 죽는다 —
   *   앞선 단언들(:30 · :39)이 이미 `.first()` 를 쓰는 것과 같은 이유다.
   *
   * ★그리고 이 단언은 목이 **stateful 해야** 의미가 있다. 낙관적 갱신만으로도 잠깐
   *   통과하므로, `scheme-handlers.ts` 의 PUT 이 배정을 실제로 바꾸는 것과 짝이다.
   */
  await expect(page.getByText('소프트웨어 개발 기본 스킴').first()).toBeVisible()
  await expect(page.getByText(labels.currentSchemeBadge)).toBeVisible()
})

// ─────────────────────────────────────────────────────────────────────────────
// S10 — 존재하지 않는 프로젝트 진입 시 「프로젝트를 찾을 수 없습니다」
//
// ★ 이 케이스는 원래 「미할당 프로젝트 → 스킴 미할당 안내 카드」였다. 그 시나리오는 성립하지
//   않는다 — 백엔드 `WorkflowSchemeApplicationService.findAssignedScheme` 은 배정이 없으면
//   software-scheme 을 **자동 배정하고 200** 을 돌려주므로(EC-1 D10), 「존재하지만 미배정」은
//   이 엔드포인트로 관측될 수 없는 상태다. 404 의 유일한 의미는 「프로젝트 없음」이고,
//   프론트가 그것을 「미할당」으로 읽는 바람에 오타 난 URL 로 들어온 사용자가
//   "곧 자동 할당됩니다" 라는 거짓 안내를 봤다.
//
// Given. alice 로그인 + 존재하지 않는 프로젝트 키로 진입 (MSW GET 핸들러가 404 PROJECT_NOT_FOUND)
// When.  /projects/NO-SUCH-PROJECT/settings/workflow-scheme 페이지 진입
// Then.  '프로젝트를 찾을 수 없습니다' 안내 표시 + 배정 수단(select/적용) 미노출
// ─────────────────────────────────────────────────────────────────────────────

// 존재하지 않는 프로젝트 키 — scheme-fixtures.ts 의 assignmentFixtures 에 없는 임의 키
const MISSING_PROJECT_KEY = 'NO-SUCH-PROJECT'

test('E2E-5 S10 — 존재하지 않는 프로젝트 → 「프로젝트를 찾을 수 없습니다」 + 배정 수단 미노출', async ({ page }) => {
  await loginAsAlice(page)
  await navigateToProjectAssignment(page, MISSING_PROJECT_KEY)

  // CardTitle 은 <div data-slot="card-title"> 이므로 getByText 사용
  await expect(page.getByText(labels.projectNotFoundTitle)).toBeVisible()

  // 없는 프로젝트에 스킴을 배정할 수단을 열어두지 않는다
  await expect(page.getByRole('combobox', { name: labels.schemeSelectAriaLabel })).toHaveCount(0)
  await expect(page.getByRole('button', { name: labels.applyButton })).toHaveCount(0)
})
