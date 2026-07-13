// FR-SL-06 D7 E2E — 프로젝트 설정 → Slack 채널 매핑 CRUD (happy path/이벤트 미선택 가드/빈 상태) — plan Task 7
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch:
//     slack-channel-mapping-handlers.ts의 mappingStore는 모듈 로드 시 빈 상태로 시작한다
//     (automation-rule-fixtures.ts 동형 — 각 테스트는 독립된 브라우저 컨텍스트를 받으므로
//     신규 페이지 로드마다 store가 빈 상태로 리셋된다). 이 특성 덕에 E3(빈 상태)는 별도
//     localStorage 토글 없이 그냥 goto 직후 상태를 그대로 검증할 수 있다.
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(채널 표시명 포함) +
//     exact:true 로 한정, 모달 내부 버튼은 role=dialog 컨테이너로 스코프
//   - data-testid 우선 사용 — slack-channel-mapping-add-button / -edit-{id} / -delete-{id} /
//     -delete-confirm-{id}
//   - ui-pr-defer-e2e-regression-latent: 기존 관련 E2E(automation-rules, slack-connect,
//     slack-user-connection)와 함께 실행해 프로젝트 설정 라우트/전역 셀렉터 회귀가 없는지
//     확인한다 (보고 시 별도 실행 결과 첨부)
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rules.spec.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 — 프로젝트 존재 여부를
 *  검증하지 않는 mock이므로 어떤 프로젝트 키를 써도 무방하나, 기존 spec과 관례를 맞춘다. */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/slack-channels`

const labels = {
  pageHeading: 'Slack 채널',
  listHeading: 'Slack 채널 매핑',
  addButton: '채널 추가',
  createTitle: '채널 매핑 추가',
  editTitle: '채널 매핑 수정',
  channelIdLabel: '채널 ID',
  saveButton: '저장',
  cancelButton: '취소',
  editButton: '수정',
  deleteButton: '삭제',
  deleteConfirmTitle: '채널 매핑을 삭제하시겠습니까?',
  deleteConfirmButton: '삭제',
  emptyMessage: '아직 등록된 채널 매핑이 없습니다.',
  eventIssueCreated: '이슈 생성',
  eventIssueAssigned: '담당자 지정',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 표시 텍스트(채널명 또는 채널 ID)로 목록의 행(li) 컨테이너를 찾는다 (automation-rules.spec.ts 동형). */
function mappingRow(page: Page, displayText: string): Locator {
  return page.getByText(displayText, { exact: true }).locator('xpath=ancestor::li[1]')
}

// ─────────────────────────────────────────────────────────────────────────────
// E1 — happy path: 생성 → 목록 반영 → 수정(이벤트 필터 변경) → 삭제
//
// Given   alice 로그인 + 설정 페이지 진입(빈 상태)
// When    "채널 추가" → 채널 ID 입력 + 이벤트 2개 선택 → 저장
// Then    목록에 새 매핑 표시(채널 ID + 선택 이벤트 라벨 2개)
// When    "수정" → 이벤트 1개 해제(필터 변경) → 저장
// Then    목록에 변경된 이벤트 필터가 반영된다
// When    "삭제" → 확인
// Then    목록에서 사라진다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E1 채널 매핑 생성 → 이벤트 필터 수정 → 삭제 (FR-SL-06)', () => {
  test('Given 설정 페이지 When 채널 추가 후 수정·삭제 Then 목록에 각 단계가 반영된다', async ({ page }) => {
    // Given. alice 로그인 + 설정 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: labels.listHeading, exact: true })).toBeVisible()

    // When. "채널 추가" → 채널 ID 입력 + 이벤트 2개 선택 → 저장
    const channelId = 'C0111111111'
    await page.getByTestId('slack-channel-mapping-add-button').click()

    const createDialog = page.getByRole('dialog')
    await expect(createDialog.getByRole('heading', { name: labels.createTitle, exact: true })).toBeVisible()
    await createDialog.getByLabel(labels.channelIdLabel, { exact: true }).fill(channelId)
    await createDialog.getByRole('checkbox', { name: labels.eventIssueCreated, exact: true }).check()
    await createDialog.getByRole('checkbox', { name: labels.eventIssueAssigned, exact: true }).check()
    await createDialog.getByRole('button', { name: labels.saveButton, exact: true }).click()

    // Then. 폼이 닫히고 목록에 새 매핑이 표시된다(채널명 미입력 → 표시명은 채널 ID) + 선택 이벤트 라벨 2개
    await expect(createDialog).not.toBeVisible()
    const createdRow = mappingRow(page, channelId)
    await expect(createdRow).toBeVisible()
    await expect(createdRow.getByText(labels.eventIssueCreated, { exact: true })).toBeVisible()
    await expect(createdRow.getByText(labels.eventIssueAssigned, { exact: true })).toBeVisible()

    // When. "수정" → 이벤트 필터 변경("담당자 지정" 해제, "이슈 생성"만 남김) → 저장
    await createdRow.getByRole('button', { name: `${channelId} ${labels.editButton}`, exact: true }).click()
    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByRole('heading', { name: labels.editTitle, exact: true })).toBeVisible()
    await expect(editDialog.getByRole('checkbox', { name: labels.eventIssueAssigned, exact: true })).toBeChecked()
    await editDialog.getByRole('checkbox', { name: labels.eventIssueAssigned, exact: true }).uncheck()
    await editDialog.getByRole('button', { name: labels.saveButton, exact: true }).click()

    // Then. 폼이 닫히고 목록에 변경된 이벤트 필터가 반영된다("담당자 지정"은 더 이상 이 행에 없음)
    await expect(editDialog).not.toBeVisible()
    const editedRow = mappingRow(page, channelId)
    await expect(editedRow.getByText(labels.eventIssueCreated, { exact: true })).toBeVisible()
    await expect(editedRow.getByText(labels.eventIssueAssigned, { exact: true })).toHaveCount(0)

    // When. "삭제" → 확인 모달 "삭제" 클릭
    await editedRow.getByRole('button', { name: `${channelId} ${labels.deleteButton}`, exact: true }).click()
    const confirmDialog = page.getByRole('dialog')
    await expect(confirmDialog.getByRole('heading', { name: labels.deleteConfirmTitle, exact: true })).toBeVisible()
    await confirmDialog.getByRole('button', { name: labels.deleteConfirmButton, exact: true }).click()

    // Then. 모달이 닫히고 목록에서 해당 행이 사라진다
    await expect(confirmDialog).not.toBeVisible()
    await expect(page.getByText(channelId, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E2 — 이벤트 미선택 가드: 채널 ID만 입력하고 이벤트 0개면 저장이 막힌다
//
// Given   alice 로그인 + 설정 페이지 진입 + "채널 추가" 폼을 연다
// When    채널 ID만 입력하고 이벤트를 하나도 선택하지 않는다
// Then    "저장" 버튼이 비활성화되어 제출할 수 없다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2 이벤트 미선택 가드 (FR-SL-06)', () => {
  test('Given 채널 ID만 입력 When 이벤트 0개 Then 저장 버튼이 비활성화된다', async ({ page }) => {
    // Given. alice 로그인 + 설정 페이지 진입 + "채널 추가" 폼을 연다
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await page.getByTestId('slack-channel-mapping-add-button').click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: labels.createTitle, exact: true })).toBeVisible()

    // When. 채널 ID만 입력하고 이벤트는 선택하지 않는다
    await dialog.getByLabel(labels.channelIdLabel, { exact: true }).fill('C0222222222')

    // Then. "저장" 버튼이 비활성화되어 제출이 막힌다(EC1)
    await expect(dialog.getByRole('button', { name: labels.saveButton, exact: true })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E3 — 빈 상태: 매핑 0개 프로젝트 진입 시 빈 상태 안내가 렌더된다
//
// Given   alice 로그인
// When    매핑이 없는 설정 페이지에 진입한다(신규 페이지 로드 — MSW store 기본값은 빈 상태)
// Then    "아직 등록된 채널 매핑이 없습니다." 빈 상태 안내가 표시된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E3 빈 상태 (FR-SL-06)', () => {
  test('Given 매핑 0개 When 설정 페이지 진입 Then 빈 상태 안내 문구가 표시된다', async ({ page }) => {
    // Given + When. alice 로그인 + 설정 페이지 진입(신규 브라우저 컨텍스트 → store 기본값 빈 상태)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.listHeading, exact: true })).toBeVisible()

    // Then. 빈 상태 안내 문구 표시
    await expect(page.getByText(labels.emptyMessage, { exact: true })).toBeVisible()
  })
})
