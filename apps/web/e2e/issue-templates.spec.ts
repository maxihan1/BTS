// FR-TM-01 D7 E2E — 이슈 템플릿 관리 시나리오 S1·S2·S3·S6·S7
//
// 앱은 MSW(issue-template-handlers.ts) 위에서 동작한다.
// issueTemplateStore 는 모듈 스코프 stateful — 시나리오마다 고유 이름 접두사를 써서
// 저장소 오염 없이 격리한다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(<이름> 수정/삭제)으로 한정
//   - msw-mutation-stateful-refetch: mutation 후 refetch로 화면 갱신 검증 (가짜 그린 방지)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 권한 토글
//   - msw-derived-behavior-shared-store-e2e: X-MSW-Seed-IssueTemplates 헤더로 브라우저 시드
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminProjectPermissions 정합
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 프로젝트 키 + 라우트
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/issue-templates`

/** 이슈 타입 ID — issue-type-fixtures.ts 와 정합 */
const ISSUE_TYPE_BUG_ID = 1   // name='버그'
const ISSUE_TYPE_STORY_ID = 2 // name='스토리'
const ISSUE_TYPE_TASK_ID = 3  // name='작업'

/** 관리 페이지 라벨 — issue-template-labels.ts 정본 값과 일치 */
const labels = {
  pageHeading: '이슈 템플릿 설정',
  addButton: '템플릿 추가',
  editButton: '수정',
  deleteButton: '삭제',
  saveButton: '저장',
  deleteConfirm: '정말 삭제하시겠습니까?',
  emptyMessage: '아직 이슈 템플릿이 없습니다.',
  dialogCreateTitle: '이슈 템플릿 추가',
  dialogEditTitle: '이슈 템플릿 수정',
  issueTypeLabel: '이슈 타입',
  nameLabel: '이름',
  contentLabel: '본문 (Markdown)',
  noPermission: '프로젝트 관리자만 수정할 수 있습니다.',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 템플릿 생성 (다른 시나리오의 사전조건 세팅용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IssueTemplateFormDialog를 통해 템플릿을 생성하고
 * 다이얼로그가 닫힐 때까지 대기한다.
 * 생성 후 목록에 해당 이름이 보여야 반환 (stateful refetch 포함).
 */
async function createTemplate(
  page: import('@playwright/test').Page,
  issueTypeId: number,
  name: string,
  content: string,
): Promise<void> {
  await page.getByRole('button', { name: labels.addButton }).click()
  await expect(page.getByRole('dialog')).toBeVisible()

  // 이슈 타입 select
  await page.getByLabel(labels.issueTypeLabel).selectOption(String(issueTypeId))
  // 이름 입력
  await page.getByLabel(labels.nameLabel).fill(name)
  // 본문 입력
  await page.getByLabel(labels.contentLabel).fill(content)
  // 저장
  await page.getByRole('button', { name: labels.saveButton }).click()
  // 다이얼로그 닫힘
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch된 목록에 이름이 보여야 함
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 시드된 템플릿 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 이슈 템플릿 목록 표시 (FR-TM-01)', () => {
  // Given  alice 로그인 + 설정 페이지 진입
  //        템플릿 2개 직접 생성 (버그 타입, 스토리 타입)
  // When   목록 페이지 확인
  // Then   생성된 템플릿 이름 + 이슈 타입명 칩이 목록에 표시됨

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S1-A 버그 타입 템플릿 생성 → 이름+타입명 칩 표시', async ({ page }) => {
    // Given. 버그 타입 템플릿 생성
    const templateName = 'S1A-버그 리포트 템플릿'
    await createTemplate(page, ISSUE_TYPE_BUG_ID, templateName, '## 재현 방법')

    // Then. 이름 표시
    await expect(page.getByText(templateName, { exact: true })).toBeVisible()

    // Then. 이슈 타입명 칩 표시 (useIssueTypes 해석 — id=1 → '버그')
    // 칩 CSS 클래스로 한정해 strict mode violation 방지
    await expect(
      page.locator('span.rounded-full', { hasText: '버그' }).first()
    ).toBeVisible()
  })

  test('S1-B 스토리 타입 템플릿 생성 → 이름+타입명 칩 표시', async ({ page }) => {
    // Given. 스토리 타입 템플릿 생성
    const templateName = 'S1B-스토리 기본 템플릿'
    await createTemplate(page, ISSUE_TYPE_STORY_ID, templateName, '## 사용자 스토리')

    // Then. 이름 표시
    await expect(page.getByText(templateName, { exact: true })).toBeVisible()

    // Then. 이슈 타입명 칩 표시 (useIssueTypes 해석 — id=2 → '스토리')
    await expect(
      page.locator('span.rounded-full', { hasText: '스토리' }).first()
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 템플릿 생성 → 목록에 추가 (refetch 검증)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 이슈 템플릿 생성 (FR-TM-01)', () => {
  // Given  alice 로그인 + 이슈 템플릿 설정 페이지 진입
  // When   "템플릿 추가" → 이슈 타입 선택(작업) + 이름 + 본문 입력 → 저장
  // Then   다이얼로그 닫힘, 목록에 새 템플릿 이름 표시 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S2 "템플릿 추가" → 이슈 타입+이름+본문 입력 → 저장 → 목록에 추가됨', async ({ page }) => {
    const templateName = 'S2-작업 기본 템플릿'
    const templateContent = '## 작업 설명\n\n작업 내용을 입력하세요.'

    // When. 추가 버튼 클릭
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogCreateTitle)).toBeVisible()

    // 이슈 타입 선택 (작업, id=3)
    await page.getByLabel(labels.issueTypeLabel).selectOption(String(ISSUE_TYPE_TASK_ID))

    // 이름 입력
    await page.getByLabel(labels.nameLabel).fill(templateName)

    // 본문 입력
    await page.getByLabel(labels.contentLabel).fill(templateContent)

    // 저장
    await page.getByRole('button', { name: labels.saveButton }).click()

    // Then. 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 이름 표시 (msw-mutation-stateful-refetch — invalidateQueries refetch 후 확인)
    await expect(page.getByText(templateName, { exact: true })).toBeVisible()

    // 이슈 타입명 칩도 표시됨 (useIssueTypes 해석 — id=3 → '작업')
    // playwright-getbyrole-exact-strict-mode 교훈 — 이름 텍스트와 겹치지 않도록 exact + 칩 CSS 클래스 한정
    await expect(
      page.locator('span.rounded-full', { hasText: '작업' }).first()
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 중복 (project, issueTypeId) 409 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 이슈 템플릿 중복 409 에러 (FR-TM-01)', () => {
  // Given  alice 로그인 + 설정 페이지 진입
  //        버그 타입 템플릿이 이미 생성됨
  // When   같은 이슈 타입(버그)으로 다시 템플릿 생성 시도
  // Then   다이얼로그가 열린 채로 중복 에러 메시지 표시
  //        ('이미 해당 이슈 타입에 템플릿이 있습니다.')

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S3 같은 이슈 타입으로 중복 생성 → 409 인라인 에러 표시', async ({ page }) => {
    // 사전조건: 버그 타입 템플릿 생성
    await createTemplate(
      page,
      ISSUE_TYPE_BUG_ID,
      'S3-버그 첫 번째 템플릿',
      '## 버그 설명',
    )

    // When. 같은 이슈 타입으로 두 번째 생성 시도
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    await page.getByLabel(labels.issueTypeLabel).selectOption(String(ISSUE_TYPE_BUG_ID))
    await page.getByLabel(labels.nameLabel).fill('S3-버그 중복 템플릿')
    await page.getByLabel(labels.contentLabel).fill('중복 본문')
    await page.getByRole('button', { name: labels.saveButton }).click()

    // Then. 다이얼로그가 열린 채로 에러 메시지 표시
    // issueTemplateErrorMessage('ISSUE_TEMPLATE_DUPLICATE') = '이미 해당 이슈 타입에 템플릿이 있습니다.'
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('이미 해당 이슈 타입에 템플릿이 있습니다')

    // 다이얼로그는 닫히지 않음
    await expect(page.getByRole('dialog')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 수정: 이름/본문 변경 저장 → 갱신 반영
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 이슈 템플릿 수정 (FR-TM-01)', () => {
  // Given  "S4-수정대상 템플릿"이 목록에 있음 (에픽 타입)
  // When   행의 수정 버튼 → Dialog 열기
  // Then   이슈 타입 select disabled (issueTypeId 불변)
  //        이름/본문 프리필 확인
  // When   이름 변경 → 저장
  // Then   다이얼로그 닫힘, 목록에 새 이름 표시 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S4 수정 Dialog — issueType disabled + 이름 변경 저장 → 갱신 반영', async ({ page }) => {
    // 에픽 타입(id=4)으로 구분 — 다른 시나리오와 타입 겹침 방지
    const epicIssueTypeId = 4
    const originalName = 'S4-수정대상 템플릿'
    const updatedName = 'S4-수정완료 템플릿'

    // 사전조건: 에픽 타입 템플릿 생성
    await createTemplate(page, epicIssueTypeId, originalName, '## 에픽 설명')

    // aria-label="S4-수정대상 템플릿 수정" 패턴으로 행 한정 (strict mode violation 회피)
    await page.getByRole('button', { name: `${originalName} ${labels.editButton}` }).click()

    // 수정 다이얼로그 열림
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogEditTitle)).toBeVisible()

    // 이슈 타입 select disabled 확인 (edit 모드에서 issueTypeId 불변)
    const issueTypeSelect = page.getByLabel(labels.issueTypeLabel)
    await expect(issueTypeSelect).toBeDisabled()

    // 이름 프리필 확인
    await expect(page.getByLabel(labels.nameLabel)).toHaveValue(originalName)

    // 이름 변경
    await page.getByLabel(labels.nameLabel).clear()
    await page.getByLabel(labels.nameLabel).fill(updatedName)

    // 저장
    await page.getByRole('button', { name: labels.saveButton }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // 갱신된 이름이 목록에 표시됨
    await expect(page.getByText(updatedName, { exact: true })).toBeVisible()
    // 원래 이름은 사라짐
    await expect(page.getByText(originalName, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 삭제: 인라인 확인 후 목록에서 사라짐
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6 이슈 템플릿 삭제 (FR-TM-01)', () => {
  // Given  "S6-삭제대상 템플릿"이 목록에 있음
  // When   행의 삭제 버튼 → 인라인 확인 UI 표시 → 삭제 확인 클릭
  // Then   목록에서 "S6-삭제대상 템플릿"이 사라짐 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S6 삭제 — 인라인 확인 후 목록에서 사라짐', async ({ page }) => {
    // 하위 작업 타입(id=5)으로 구분 — 다른 시나리오와 타입 겹침 방지
    const subtaskIssueTypeId = 5
    const templateName = 'S6-삭제대상 템플릿'

    // 사전조건: 하위 작업 타입 템플릿 생성
    await createTemplate(page, subtaskIssueTypeId, templateName, '## 하위 작업 설명')

    // 삭제 버튼 클릭 — aria-label="S6-삭제대상 템플릿 삭제"
    await page.getByRole('button', { name: `${templateName} ${labels.deleteButton}` }).click()

    // 인라인 확인 UI 표시
    await expect(page.getByText(labels.deleteConfirm)).toBeVisible()

    // 확인 "삭제" 버튼 클릭 — 인라인 확인 컨테이너 한정 (strict mode violation 회피)
    // IssueTemplateRow의 DeleteConfirm 컴포넌트 내 삭제 버튼
    const confirmContainer = page.getByText(labels.deleteConfirm).locator('xpath=ancestor::div[1]')
    await confirmContainer.getByRole('button', { name: labels.deleteButton }).click()

    // 목록에서 제거됨 (refetch 반영)
    await expect(page.getByText(templateName, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7 — 권한 게이팅: MANAGE_TEMPLATES 없으면 추가/수정/삭제 버튼 disabled
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S7 이슈 템플릿 관리 권한 게이팅 (FR-TM-01)', () => {
  // Given  alice 로그인 + addInitScript로 __bts_e2e_force_create_false='true' 설정
  //        → project-permission-handlers가 MANAGE_TEMPLATES:false 반환 (nonMemberProjectPermissions)
  // When   이슈 템플릿 설정 페이지 진입 (플래그 적용 시점: goto 이후)
  // Then   "템플릿 추가" 버튼 disabled (fail-closed 게이팅)
  //        페이지 정상 렌더 (에러 없이 빈 상태 또는 목록 영역 표시)
  //
  // 참고: E2E_FORCE_CREATE_FALSE_KEY('__bts_e2e_force_create_false')를 설정하면
  //       project-permission-handlers가 nonMemberProjectPermissions를 반환하므로
  //       MANAGE_TEMPLATES=false → 추가/수정/삭제 버튼 disabled.

  test('S7 권한 없는 사용자 — 템플릿 추가 버튼 disabled, 페이지 정상 렌더', async ({ page }) => {
    // Given. addInitScript 등록 — 다음 탐색부터 플래그 적용
    // e2e-msw-scenario-toggle-localstorage-flag 교훈 — 핸들러 임시교체 대신 localStorage 플래그
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_force_create_false', 'true')
    })

    // Given. alice 로그인 (addInitScript는 goto 전에 등록됐으므로 /login goto에 적용)
    await loginAsAlice(page)

    // When. 설정 페이지 진입 (이 goto부터 플래그 적용)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()

    // Then. "템플릿 추가" 버튼 disabled (fail-closed 게이팅)
    const addButton = page.getByRole('button', { name: labels.addButton })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()

    // Then. 페이지 정상 렌더 — 에러 없이 빈 상태 또는 목록이 보임
    const emptyMsg = page.getByText(labels.emptyMessage)
    const templateList = page.locator('ul.space-y-2')
    const isRenderedNormally = (await emptyMsg.isVisible()) || (await templateList.count()) > 0
    expect(isRenderedNormally).toBe(true)
  })

  // S7-B (행별 수정·삭제 버튼 disabled) 는 단위 테스트(IssueTemplateList.test.tsx)에서
  // canManage=false 시 버튼 disabled를 검증하므로 E2E에서 중복 추가하지 않는다.
  // useProjectPermissions의 staleTime(30초)으로 인해 SPA 내 권한 상태 토글이
  // 즉시 반영되지 않아 E2E에서 안정적으로 검증하기 어렵다.
  // 행별 게이팅은 IssueTemplateList + IssueTemplateRow 단위 테스트가 담당한다.
})
