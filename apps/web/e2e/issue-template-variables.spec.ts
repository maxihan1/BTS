// FR-TM-02 D7 E2E — 이슈 템플릿 변수 삽입 UI 시나리오 E1·E2·E3
//
// 배경.
//   D6(FR-TM-02)이 IssueTemplateFormDialog에 TemplateContentField를 도입했다.
//   본문 라벨 아래 "+ 작성자" / "+ 일자" / "+ 프로젝트" 버튼을 클릭하면
//   textarea 커서 위치에 {{author}}/{{date}}/{{project}} 토큰이 삽입된다.
//   "사용 가능 변수 …" 도움말도 표시된다.
//
// MSW 핵심 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 그대로
//   - msw-mutation-stateful-refetch: POST → store 영속 → GET refetch → 목록 표시 확인
//   - playwright-getbyrole-exact-strict-mode: 버튼은 정확 aria-label로 한정
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminProjectPermissions 정합
//
// 시나리오 격리.
//   각 테스트는 page 단위로 새 MSW store 인스턴스를 받으므로 이름 접두사로 충분히 격리된다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 기존 issue-templates.spec.ts 패턴 그대로 미러
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/issue-templates`

/** 이슈 타입 ID — issue-type-fixtures.ts 정합 */
const ISSUE_TYPE_BUG_ID = 1   // name='버그'

/** 관리 페이지 라벨 상수 — issue-template-labels.ts 정본값과 일치 */
const labels = {
  pageHeading: '이슈 템플릿 설정',
  addButton: '템플릿 추가',
  saveButton: '저장',
  contentLabel: '본문 (Markdown)',
  issueTypeLabel: '이슈 타입',
  nameLabel: '이름',
  /** 변수 삽입 버튼 aria-label — variableInsertAria(label) = `${label} 변수 삽입` */
  authorInsertAria: '작성자 변수 삽입',
  dateInsertAria: '일자 변수 삽입',
  projectInsertAria: '프로젝트 변수 삽입',
  /** 도움말 문구 앞부분 — variableHelpPrefix */
  helpPrefix: '사용 가능 변수',
  /** 도움말에 노출되는 토큰 */
  authorToken: '{{author}}',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// E1 — 커서 위치 삽입
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E1 변수 삽입 — 커서 위치에 토큰 삽입 (FR-TM-02)', () => {
  // Given  alice 로그인 → 이슈 템플릿 설정 페이지
  // When   "템플릿 추가" 다이얼로그 → 본문에 '보고자: ' 입력
  //        → "작성자 변수 삽입" 버튼 클릭
  // Then   본문 textarea 값이 '보고자: {{author}}' 임

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('E1 본문에 보고자: 입력 후 작성자 변수 삽입 버튼 클릭 → {{author}} 토큰 삽입', async ({ page }) => {
    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // When. 본문에 '보고자: ' 입력 — fill은 커서를 끝에 위치시킨다
    await page.getByLabel(labels.contentLabel).fill('보고자: ')

    // When. 작성자 변수 삽입 버튼 클릭
    // playwright-getbyrole-exact-strict-mode: aria-label 정확히 일치
    await page.getByRole('button', { name: labels.authorInsertAria }).click()

    // Then. 본문 textarea 값에 {{author}} 토큰이 커서 위치(끝)에 삽입됨
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue('보고자: {{author}}')
  })

  test('E1-date 일자 변수 삽입 버튼 → {{date}} 토큰 삽입', async ({ page }) => {
    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // When. 본문에 '생성일: ' 입력
    await page.getByLabel(labels.contentLabel).fill('생성일: ')

    // When. 일자 변수 삽입
    await page.getByRole('button', { name: labels.dateInsertAria }).click()

    // Then. {{date}} 삽입됨
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue('생성일: {{date}}')
  })

  test('E1-project 프로젝트 변수 삽입 버튼 → {{project}} 토큰 삽입', async ({ page }) => {
    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // When. 본문에 '프로젝트: ' 입력
    await page.getByLabel(labels.contentLabel).fill('프로젝트: ')

    // When. 프로젝트 변수 삽입
    await page.getByRole('button', { name: labels.projectInsertAria }).click()

    // Then. {{project}} 삽입됨
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue('프로젝트: {{project}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E2 — 라운드트립 (토큰 전송·저장·프리필 입증)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2 변수 라운드트립 — create → store → refetch → edit 프리필 (FR-TM-02)', () => {
  // Given  alice 로그인 → 이슈 템플릿 설정 페이지
  // When   생성 다이얼로그: 이슈 타입(버그) + 이름 입력 + 본문에 {{author}} 토큰 삽입 → 저장
  // Then   다이얼로그 닫힘, 목록에 이름 표시 (msw stateful store + refetch)
  // When   같은 행 "수정" 클릭
  // Then   수정 다이얼로그 본문 프리필에 {{author}} 토큰이 잔존함
  //        (create 시 토큰이 실제로 전송·저장됐음을 end-to-end 입증)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('E2 생성 시 {{author}} 토큰 저장 → 수정 다이얼로그 프리필에 잔존', async ({ page }) => {
    const templateName = 'E2-라운드트립 변수 템플릿'
    const contentWithToken = '보고자: {{author}}'

    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // When. 이슈 타입 선택 (버그, id=1)
    await page.getByLabel(labels.issueTypeLabel).selectOption(String(ISSUE_TYPE_BUG_ID))

    // When. 이름 입력
    await page.getByLabel(labels.nameLabel).fill(templateName)

    // When. 본문에 '보고자: ' 입력 후 작성자 변수 삽입 버튼 클릭
    await page.getByLabel(labels.contentLabel).fill('보고자: ')
    await page.getByRole('button', { name: labels.authorInsertAria }).click()
    // 삽입 결과 확인 — 토큰 삽입이 정상 동작해야 저장 의미가 있다
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue(contentWithToken)

    // When. 저장
    await page.getByRole('button', { name: labels.saveButton }).click()

    // Then. 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 이름 표시 (msw-mutation-stateful-refetch 교훈)
    await expect(page.getByText(templateName, { exact: true })).toBeVisible()

    // When. 같은 행 "수정" 클릭 — aria-label="{이름} 수정"
    await page.getByRole('button', { name: `${templateName} 수정` }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // Then. 수정 다이얼로그 본문 프리필에 {{author}} 토큰 잔존
    // — 토큰이 실제로 MSW store에 저장됐다가 edit 시 반환됐음을 입증
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue(contentWithToken)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E4 — edit 프리필 + 본문 미포커스 → 끝에 append (실브라우저 selectionStart=0 버그 회귀 방지)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E4 edit 프리필 + 본문 미포커스 → 끝에 append (FR-TM-02 F2 회귀)', () => {
  // 재현 시나리오.
  //   실브라우저에서 포커스 안 한 textarea의 selectionStart = 0 (jsdom은 null).
  //   수정 전 코드는 selectionStart ?? length fallback이 null에만 동작해
  //   브라우저에서 0으로 읽히면 fallback 없이 맨 앞 prepend가 되는 버그가 있었다.
  //
  // Given  alice 로그인 → 이슈 템플릿 설정 페이지
  //        (MSW store에 본문 "## 재현 방법" 템플릿이 시드되어 있는 상황을 생성으로 흉내)
  // When   "템플릿 추가" 다이얼로그 → 본문을 fill("## 재현 방법")로 채움
  //        → 이름 input에 포커스를 옮겨 textarea 포커스를 해제
  //        → "일자 변수 삽입" 버튼 클릭
  // Then   본문이 "## 재현 방법{{date}}" (끝에 append) — "{{date}}## 재현 방법" 아님

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('E4 본문 미포커스 상태에서 일자 변수 삽입 버튼 클릭 → 끝에 append', async ({ page }) => {
    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // Given. 본문을 채운다
    await page.getByLabel(labels.contentLabel).fill('## 재현 방법')

    // Given. 실 브라우저의 "포커스 없이 selectionStart=0" 상태를 재현한다.
    //   page.evaluate로 selectionStart를 0으로 강제 설정한 뒤 이름 input에 포커스를 이동.
    //   — 이후 버튼 클릭 시: 수정 전(selectionStart ?? length)은 0을 그대로 사용 → prepend
    //                         수정 후(isFocused 분기)는 isFocused=false → length → append
    await page.getByLabel(labels.contentLabel).evaluate((el) => {
      ;(el as HTMLTextAreaElement).setSelectionRange(0, 0)
    })
    await page.getByLabel(labels.nameLabel).focus()

    // When. 본문을 클릭하지 않은 채 "일자 변수 삽입" 버튼 클릭
    await page.getByRole('button', { name: labels.dateInsertAria }).click()

    // Then. 끝에 append: "## 재현 방법{{date}}" (prepend "{{date}}## 재현 방법" 아님)
    await expect(page.getByLabel(labels.contentLabel)).toHaveValue('## 재현 방법{{date}}')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E3 — 도움말 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E3 변수 도움말 표시 (FR-TM-02)', () => {
  // Given  alice 로그인 → 이슈 템플릿 설정 페이지
  // When   "템플릿 추가" 다이얼로그 열기
  // Then   "사용 가능 변수" 문구가 보임
  //        {{author}} 토큰 텍스트가 도움말에 표시됨

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('E3 생성 다이얼로그 — 사용 가능 변수 도움말 + {{author}} 토큰 표시', async ({ page }) => {
    // Given. 생성 다이얼로그 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // Then. "사용 가능 변수" 도움말 문구가 보임 (variableHelpPrefix)
    // 도움말 <p> 안에 포함된 텍스트 — 다이얼로그 내로 한정해 strict mode violation 방지
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText(labels.helpPrefix, { exact: false })).toBeVisible()

    // Then. {{author}} 토큰이 도움말에 표시됨 — code 요소로 렌더링
    await expect(dialog.locator('code', { hasText: labels.authorToken })).toBeVisible()
  })
})
