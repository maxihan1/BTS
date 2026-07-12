// FR-AT-02 D7 E2E — 자동화 룰 액션 빌더(4종 액션 편집·순서변경·실행주체) UI 검증 — plan Task 8
//
// 선례: automation-rules.spec.ts(FR-AT-01 D7) — MSW 시나리오/셀렉터 패턴을 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-rule-handlers.ts는
//     stateful 공유 store(ruleStore)를 사용한다. 각 테스트는 독립된 브라우저 컨텍스트(신규 페이지 로드마다
//     automation-rule-fixtures.ts 모듈이 재평가되어 기본 시드 2건으로 리셋)를 받으므로 데이터 격리가 보장된다.
//   - playwright-getbyrole-exact-strict-mode: 트리거 배지("생성")와 액션 배지("담당자"/"댓글"/"웹훅")가
//     텍스트를 공유할 수 있어(예: ADD_COMMENT="댓글" vs ISSUE_COMMENTED 트리거="댓글") 모든 시나리오는
//     트리거 타입을 기본값(ISSUE_CREATED="생성")으로 유지해 충돌을 피하고, 행/목록 행 컨테이너로 스코프한다.
//   - 액션 목록의 각 행(<li>)은 SET_FIELD "labels" 위젯을 쓰면 태그칩도 <li>(nested listitem)로 렌더되어
//     `dialog.getByRole('listitem')` 인덱싱이 흐트러진다 — 이 스펙은 SET_FIELD에 문자열 필드(요약/설명)만
//     사용해 회피한다(라벨 태그 위젯은 컴포넌트 단위 테스트 ActionConfigEditor.test.tsx가 커버).
//   - data-testid 우선 + getByLabel(aria-label) 조합 — automation-rule-add-button/-save-button 등은
//     기존 관례(FR-AT-01) 그대로, 액션 행 내부 위젯은 ActionConfigEditor/ProjectMemberSelect의
//     aria-label로 스코프한다.
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/**
 * project-member-fixtures.ts atlasInitialMembers 앨리스 userId 리터럴 고정
 * (automation-rule-fixtures.ts DEFAULT_AUTOMATION_ACTOR_ID와 동일 값).
 * E2E는 mock 내부 상수를 직접 import하는 대신 프로덕션 화면 계약(고정 시드 값)만 리터럴로 참조한다
 * (automation-rules.spec.ts LS_EMPTY_LIST 리터럴 고정 관례 동형).
 */
const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

const labels = {
  heading: '자동화 룰',
  addButton: '룰 추가',
  createTitle: '자동화 룰 추가',
  editTitle: '자동화 룰 수정',
  nameLabel: '이름',
  saveButton: '저장',
  editButton: '수정',
  actorLabel: '실행 주체',
  addActionButton: '액션 추가',
  moveUpLabel: '위로',
  actionTypeLabel: '액션 유형',
  fieldLabel: '필드',
  valueLabel: '값',
  assigneeLabel: '담당자',
  commentBodyLabel: '댓글 본문',
  commentTemplateHint: '{{ issue.key }} 등 템플릿 변수를 본문에 사용할 수 있습니다.',
  webhookUrlLabel: 'URL',
  webhookMethodLabel: '메서드',
  webhookAddHeaderButton: '헤더 추가',
  webhookHeaderKeyLabel: '헤더 이름',
  webhookHeaderValueLabel: '헤더 값',
  webhookBodyLabel: '본문',
  emptyActionsMessage: '아직 액션이 없습니다 — 트리거만으로도 유효하지만, 액션을 추가하면 자동 실행됩니다.',
  noActionsBadge: '액션 없음',
  aliceMemberLabel: '앨리스',
  bobMemberLabel: '밥',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 룰 이름으로 목록의 행(li) 컨테이너를 찾는다 (automation-rules.spec.ts ruleRow 동형). */
function ruleRow(page: Page, name: string): Locator {
  return page.getByText(name, { exact: true }).locator('xpath=ancestor::li[1]')
}

/** 룰 생성 다이얼로그를 열고 이름을 입력한 뒤 다이얼로그 Locator를 반환한다(공통 Given 전제). */
async function openCreateDialogWithName(page: Page, name: string): Promise<Locator> {
  await page.getByTestId('automation-rule-add-button').click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()
  await dialog.getByLabel(labels.nameLabel, { exact: true }).fill(name)
  return dialog
}

// ─────────────────────────────────────────────────────────────────────────────
// T1 — SET_FIELD(priority=3) 액션 + 실행 주체 선택 → 저장 → 목록 배지 확인 + 재조회 시 값 보존(S1·S6·S7)
//
// Given   alice 로그인 + 자동화 룰 생성 다이얼로그
// When    "액션 추가" → SET_FIELD 선택(기본값) → 필드 priority → 값 3 → 실행 주체(앨리스) 선택 → 저장
// Then    목록에 액션 배지("액션: 필드 변경")가 반영되고, 편집을 다시 열면 필드/값/실행 주체가
//         그대로 로드된다(config 비대칭 read/write round-trip)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T1 SET_FIELD 액션 + 실행 주체 선택 → 목록 배지 반영 + 재조회 보존 (FR-AT-02)', () => {
  test('Given 룰 생성 다이얼로그 When SET_FIELD(priority=3)+실행 주체 선택 후 저장 Then 목록 배지 반영 + 재조회 시 값 보존', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 생성 다이얼로그
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    const name = 'T1 SET_FIELD 액션 룰'
    const dialog = await openCreateDialogWithName(page, name)

    // When. "액션 추가" → 기본 SET_FIELD 행에서 필드=priority, 값=3(숫자 select) 선택
    await dialog.getByRole('button', { name: labels.addActionButton, exact: true }).click()
    const actionRow = dialog.getByRole('listitem').nth(0)
    await actionRow.getByLabel(labels.fieldLabel, { exact: true }).selectOption('priority')
    await actionRow.getByLabel(labels.valueLabel, { exact: true }).selectOption('3')

    // When. 실행 주체를 앨리스로 명시 선택
    await dialog.getByLabel(labels.actorLabel, { exact: true }).selectOption({ label: labels.aliceMemberLabel })

    // When. 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 목록에 액션 배지 그룹("액션: 필드 변경")이 반영된다(FR11, role=group aria-label)
    const row = ruleRow(page, name)
    await expect(row.getByRole('group', { name: '액션: 필드 변경', exact: true })).toBeVisible()

    // Then. 편집을 다시 열면 SET_FIELD(priority=3) + 실행 주체(앨리스)가 그대로 로드된다(S7 round-trip)
    await row.getByRole('button', { name: `${name} ${labels.editButton}`, exact: true }).click()
    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByRole('heading', { name: labels.editTitle })).toBeVisible()

    const loadedRow = editDialog.getByRole('listitem').nth(0)
    await expect(loadedRow.getByLabel(labels.fieldLabel, { exact: true })).toHaveValue('priority')
    await expect(loadedRow.getByLabel(labels.valueLabel, { exact: true })).toHaveValue('3')
    await expect(editDialog.getByLabel(labels.actorLabel, { exact: true })).toHaveValue(ALICE_USER_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2 — 기존 룰 편집 → ASSIGN 액션 추가 + 순서변경(위로) → 저장 → 재조회 시 순서 반영(S2·S5)
//
// Given   alice 로그인 + SET_FIELD 액션 1개를 가진 기존 룰(Given 전제, UI로 미리 생성)
// When    편집 다이얼로그에서 ASSIGN 액션을 추가하고 담당자(밥) 선택 → 위로 이동해 SET_FIELD보다 앞에 오게 함
// Then    저장 후 목록 배지 그룹 순서가 "담당자, 필드 변경"으로 반영된다(position = 배열 순서)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T2 편집 시 ASSIGN 액션 추가 + 순서변경(위로) → 목록 순서 반영 (FR-AT-02)', () => {
  test('Given 액션 1개인 기존 룰 When ASSIGN 추가+위로 이동 후 저장 Then 목록 배지 순서가 반영된다', async ({ page }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    // Given. SET_FIELD 액션 1개를 가진 룰을 UI로 미리 생성한다("기존 룰" 전제)
    const name = 'T2 액션 순서변경 대상 룰'
    const createDialog = await openCreateDialogWithName(page, name)
    await createDialog.getByRole('button', { name: labels.addActionButton, exact: true }).click()
    const initialRow = createDialog.getByRole('listitem').nth(0)
    await initialRow.getByLabel(labels.valueLabel, { exact: true }).fill('초기 값')
    await createDialog.getByTestId('automation-rule-save-button').click()
    await expect(createDialog).not.toBeVisible()

    const row = ruleRow(page, name)
    await expect(row.getByRole('group', { name: '액션: 필드 변경', exact: true })).toBeVisible()

    // When. 편집을 열어 ASSIGN 액션을 추가하고 담당자(밥)를 지정
    await row.getByRole('button', { name: `${name} ${labels.editButton}`, exact: true }).click()
    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByRole('heading', { name: labels.editTitle })).toBeVisible()

    await editDialog.getByRole('button', { name: labels.addActionButton, exact: true }).click()
    const newActionRow = editDialog.getByRole('listitem').nth(1)
    await newActionRow.getByLabel(labels.actionTypeLabel, { exact: true }).selectOption('ASSIGN')
    await newActionRow.getByLabel(labels.assigneeLabel, { exact: true }).selectOption({ label: labels.bobMemberLabel })

    // When. 새로 추가한 ASSIGN 행을 위로 이동해 기존 SET_FIELD보다 앞에 오게 한다
    await newActionRow.getByRole('button', { name: labels.moveUpLabel, exact: true }).click()

    // When. 저장
    await editDialog.getByTestId('automation-rule-save-button').click()
    await expect(editDialog).not.toBeVisible()

    // Then. 목록이 재조회되어 순서가 반영된 액션 배지 그룹(ASSIGN → SET_FIELD)이 보인다(position = 배열 순서)
    await expect(row.getByRole('group', { name: '액션: 담당자, 필드 변경', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3 — 4종 액션(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK) 각 추가 happy path(S1~S4)
//
// Given   alice 로그인 + 자동화 룰 생성 다이얼로그
// When    "액션 추가" 4회 → 각 행의 타입을 SET_FIELD(기본)/ASSIGN/ADD_COMMENT/CALL_WEBHOOK으로 구성
// Then    저장 성공 후 목록 배지 그룹이 4종 모두 추가한 순서대로 반영된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T3 4종 액션(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK) 각 추가 happy path (FR-AT-02)', () => {
  test('Given 룰 생성 다이얼로그 When 4종 액션을 각각 추가 Then 저장 성공 + 목록 배지에 4종 순서대로 반영된다', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 생성 다이얼로그
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const name = 'T3 4종 액션 룰'
    const dialog = await openCreateDialogWithName(page, name)

    // When. "액션 추가" 4회로 빈 SET_FIELD 행 4개를 먼저 만든다(행 개수 확정 후 개별 구성 — nth() 인덱스 안정성)
    const addActionButton = dialog.getByRole('button', { name: labels.addActionButton, exact: true })
    await addActionButton.click()
    await addActionButton.click()
    await addActionButton.click()
    await addActionButton.click()

    const setFieldRow = dialog.getByRole('listitem').nth(0)
    const assignRow = dialog.getByRole('listitem').nth(1)
    const commentRow = dialog.getByRole('listitem').nth(2)
    const webhookRow = dialog.getByRole('listitem').nth(3)

    // When. 1행 — SET_FIELD(기본값 유지, 문자열 필드 위젯) 값 입력
    await setFieldRow.getByLabel(labels.valueLabel, { exact: true }).fill('자동 요약 처리')

    // When. 2행 — ASSIGN(밥 지정)
    await assignRow.getByLabel(labels.actionTypeLabel, { exact: true }).selectOption('ASSIGN')
    await assignRow.getByLabel(labels.assigneeLabel, { exact: true }).selectOption({ label: labels.bobMemberLabel })

    // When. 3행 — ADD_COMMENT(템플릿 힌트 노출 확인 + 본문 입력, S3)
    await commentRow.getByLabel(labels.actionTypeLabel, { exact: true }).selectOption('ADD_COMMENT')
    await expect(commentRow.getByText(labels.commentTemplateHint, { exact: true })).toBeVisible()
    await commentRow.getByLabel(labels.commentBodyLabel, { exact: true }).fill('{{ issue.key }} 자동 처리됨')

    // When. 4행 — CALL_WEBHOOK(url·method·헤더·본문, S4)
    await webhookRow.getByLabel(labels.actionTypeLabel, { exact: true }).selectOption('CALL_WEBHOOK')
    await webhookRow.getByLabel(labels.webhookUrlLabel, { exact: true }).fill('https://hooks.example.com/x')
    await webhookRow.getByLabel(labels.webhookMethodLabel, { exact: true }).selectOption('POST')
    await webhookRow.getByRole('button', { name: labels.webhookAddHeaderButton, exact: true }).click()
    await webhookRow.getByLabel(labels.webhookHeaderKeyLabel, { exact: true }).fill('X-Token')
    await webhookRow.getByLabel(labels.webhookHeaderValueLabel, { exact: true }).fill('abc')
    await webhookRow.getByLabel(labels.webhookBodyLabel, { exact: true }).fill('{"issueKey":"{{ issue.key }}"}')

    // When. 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 목록 배지 그룹에 4종이 추가한 순서(SET_FIELD·ASSIGN·ADD_COMMENT·CALL_WEBHOOK)대로 반영된다
    const row = ruleRow(page, name)
    await expect(
      row.getByRole('group', { name: '액션: 필드 변경, 담당자, 댓글, 웹훅', exact: true }),
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4 — 빈 액션 상태(0개) 안내 + CTA 표시(선택, EC5·design-review#2)
//
// Given   alice 로그인 + 자동화 룰 생성 다이얼로그(액션 미추가 상태)
// When    액션을 추가하지 않고 저장
// Then    다이얼로그에 빈 상태 안내 문구 + "액션 추가" CTA가 보이고, 저장 후 목록에는 "액션 없음"이 표시된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T4 빈 액션 상태(0개) 안내 + CTA 표시 (FR-AT-02, 선택)', () => {
  test('Given 액션 미추가 룰 생성 When 저장 Then 다이얼로그 빈 상태 안내 + 목록에 "액션 없음" 표시', async ({ page }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 생성 다이얼로그
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const name = 'T4 액션 없는 룰'
    const dialog = await openCreateDialogWithName(page, name)

    // Then(중간 확인). 액션 0개 상태에서 안내 문구 + "액션 추가" CTA가 보인다(EC5·design-review#2, dead path 아님)
    await expect(dialog.getByText(labels.emptyActionsMessage, { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: labels.addActionButton, exact: true })).toBeVisible()

    // When. 액션을 추가하지 않고 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 목록에는 액션 배지 대신 "액션 없음" 문구가 표시된다(actions:[] 저장 허용, FR11)
    const row = ruleRow(page, name)
    await expect(row.getByText(labels.noActionsBadge, { exact: true })).toBeVisible()
  })
})
