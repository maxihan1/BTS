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

  // PUT 응답 후 현재 적용 카드가 새 스킴명으로 갱신되어야 함
  await expect(page.getByText('소프트웨어 개발 기본 스킴')).toBeVisible()
  await expect(page.getByText(labels.currentSchemeBadge)).toBeVisible()
})

// ─────────────────────────────────────────────────────────────────────────────
// S10 — 미할당 프로젝트 진입 시 자동 할당 안내 카드 표시
//
// Given. alice 로그인 + assignmentFixtures 에 없는 프로젝트 키로 진입
//        (MSW GET 핸들러가 404 ASSIGNMENT_NOT_FOUND 반환 → assignment === null)
// When.  /projects/UNASSIGNED-PROJ/settings/workflow-scheme 페이지 진입
// Then.  '스킴 미할당' 안내 카드 표시 + 변경 select 정상 사용 가능
//
// fixture 현황:
//   assignmentFixtures = [ATLAS, BTS, PILOT] — 미할당 프로젝트 키 별도 fixture 없음.
//   MSW GET 핸들러는 fixture 에 없는 키에 대해 404 를 반환하므로,
//   'UNASSIGNED-PROJ' 키를 사용해 미할당 상태를 자연스럽게 재현한다.
// ─────────────────────────────────────────────────────────────────────────────

// 미할당 시나리오용 프로젝트 키 — scheme-fixtures.ts 의 assignmentFixtures 에 없는 임의 키
const UNASSIGNED_PROJECT_KEY = 'UNASSIGNED-PROJ'

test('E2E-5 S10 — 미할당 프로젝트 → 스킴 미할당 안내 카드 + 변경 select 정상', async ({ page }) => {
  await loginAsAlice(page)
  await navigateToProjectAssignment(page, UNASSIGNED_PROJECT_KEY)

  // '스킴 미할당' 안내 카드 제목 표시 확인
  // CardTitle 은 <div data-slot="card-title"> 이므로 getByText 사용
  await expect(page.getByText(labels.unassignedTitle)).toBeVisible()

  // 스킴 지정 섹션 (assignTitle) 표시 확인 — 할당 없을 때 changeTitle 대신 assignTitle
  await expect(page.getByText(labels.assignTitle)).toBeVisible()

  // 스킴 select 가 사용 가능 상태(disabled 아님) 인지 확인
  const schemeSelect = page.getByRole('combobox', { name: labels.schemeSelectAriaLabel })
  await expect(schemeSelect).toBeVisible()
  await expect(schemeSelect).not.toBeDisabled()

  // select 를 열면 스킴 옵션이 1개 이상 존재하는지 확인
  await schemeSelect.click()
  const options = page.getByRole('option')
  await expect(options.first()).toBeVisible()
})
