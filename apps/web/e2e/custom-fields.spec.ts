// FR-IS-10 D7 E2E — 커스텀 필드 관리 + 이슈 값 입력 시나리오 S1~S6
//
// 앱은 MSW(custom-field-handlers.ts) 위에서 동작한다.
// customFieldStore 는 모듈 스코프 stateful — 시나리오마다 고유 key/name 접두사를 써서
// 저장소 오염 없이 격리한다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(<필드명> 수정/삭제)으로 한정
//   - msw-mutation-stateful-refetch: mutation 후 refetch로 화면 갱신 검증 (가짜 그린 방지)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 권한 토글
//   - msw-derived-behavior-shared-store-e2e: X-MSW-Seed-CustomFields 헤더로 브라우저 시드
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminProjectPermissions 정합
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 프로젝트 키 + 라우트
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/custom-fields`

/** custom-field-handlers.ts의 LS_KEY_CUSTOM_FIELD_403 과 동일 */
const LS_KEY_CUSTOM_FIELD_403 = 'msw-custom-field-403'

/** 관리 페이지 라벨 — custom-field-labels.ts의 정본 값과 일치시킨다 */
const labels = {
  pageHeading: '커스텀 필드 설정',
  addButton: '필드 추가',
  editButton: '수정',
  deleteButton: '삭제',
  saveButton: '저장',
  deleteConfirm: '정말 삭제하시겠습니까?',
  emptyMessage: '아직 커스텀 필드가 없습니다.',
  dialogCreateTitle: '커스텀 필드 추가',
  dialogEditTitle: '커스텀 필드 수정',
  keyLabel: '키',
  nameLabel: '이름',
  fieldTypeLabel: '필드 타입',
  requiredLabel: '필수',
  noPermission: '프로젝트 관리자만 수정할 수 있습니다.',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SHORT_TEXT 필드 생성 (다른 시나리오의 사전조건 세팅용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CustomFieldFormDialog를 통해 SHORT_TEXT 커스텀 필드를 생성하고
 * 다이얼로그가 닫힐 때까지 대기한다.
 * 생성 후 목록에 해당 이름이 보여야 반환 (stateful refetch 포함).
 */
async function createShortTextField(
  page: import('@playwright/test').Page,
  key: string,
  name: string,
): Promise<void> {
  await page.getByRole('button', { name: labels.addButton }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  // 키 입력
  await page.getByLabel(labels.keyLabel).fill(key)
  // 이름 입력
  await page.getByLabel(labels.nameLabel).fill(name)
  // fieldType 기본값이 SHORT_TEXT이므로 변경 불필요
  // 저장 클릭
  await page.getByRole('button', { name: labels.saveButton }).click()
  // 다이얼로그 닫힘
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch된 목록에 이름이 보여야 함
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 정의 생성 (SHORT_TEXT + SINGLE_SELECT)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 커스텀 필드 정의 생성 (FR-IS-10)', () => {
  // Given  alice 로그인 + 커스텀 필드 설정 페이지 진입
  // When   SHORT_TEXT 1개 생성 → 목록 확인
  //        SINGLE_SELECT(옵션 2개) 1개 생성 → 목록 확인
  // Then   두 필드가 목록에 표시됨

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S1-A SHORT_TEXT 필드 생성 → 목록 표시', async ({ page }) => {
    const fieldKey = 's1a_note'
    const fieldName = 'S1-메모'

    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogCreateTitle)).toBeVisible()

    await page.getByLabel(labels.keyLabel).fill(fieldKey)
    await page.getByLabel(labels.nameLabel).fill(fieldName)
    // fieldType 기본값 SHORT_TEXT 그대로 사용

    await page.getByRole('button', { name: labels.saveButton }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // 목록에 이름과 타입 칩이 표시됨
    await expect(page.getByText(fieldName, { exact: true })).toBeVisible()
    // fieldType 한국어 칩 '짧은 텍스트' 표시 확인
    await expect(page.getByText('짧은 텍스트')).toBeVisible()
  })

  test('S1-B SINGLE_SELECT 필드(옵션 2개) 생성 → 목록 표시', async ({ page }) => {
    const fieldKey = 's1b_priority'
    const fieldName = 'S1-우선순위'

    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    await page.getByLabel(labels.keyLabel).fill(fieldKey)
    await page.getByLabel(labels.nameLabel).fill(fieldName)

    // fieldType을 SINGLE_SELECT로 변경
    await page.getByLabel(labels.fieldTypeLabel).selectOption('SINGLE_SELECT')

    // 선택지 추가 버튼이 나타남
    await expect(page.getByRole('button', { name: '선택지 추가' })).toBeVisible()

    // 옵션 1 추가
    await page.getByRole('button', { name: '선택지 추가' }).click()
    await page.getByLabel('옵션 1 값').fill('high')
    await page.getByLabel('옵션 1 라벨').fill('높음')

    // 옵션 2 추가
    await page.getByRole('button', { name: '선택지 추가' }).click()
    await page.getByLabel('옵션 2 값').fill('low')
    await page.getByLabel('옵션 2 라벨').fill('낮음')

    await page.getByRole('button', { name: labels.saveButton }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // 목록에 이름 표시
    await expect(page.getByText(fieldName, { exact: true })).toBeVisible()
    // fieldType 한국어 칩 '단일 선택' 표시
    await expect(page.getByText('단일 선택')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 정의 수정: fieldType/key readonly + name 변경 저장
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 커스텀 필드 정의 수정 (FR-IS-10)', () => {
  // Given  "S2-설명필드" SHORT_TEXT 필드가 목록에 있음
  // When   행의 수정 버튼 → Dialog 열기
  // Then   fieldType select disabled, key input disabled
  // When   name 변경 → 저장
  // Then   다이얼로그 닫힘, 목록에 새 이름 표시 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S2 수정 Dialog — fieldType/key readonly + name 변경 저장 → 갱신 반영', async ({ page }) => {
    const fieldKey = 's2_desc'
    const originalName = 'S2-설명필드'
    const updatedName = 'S2-설명필드-수정됨'

    // 사전조건: 필드 생성
    await createShortTextField(page, fieldKey, originalName)

    // aria-label="S2-설명필드 수정" 패턴으로 행 한정 (strict mode violation 회피)
    await page.getByRole('button', { name: `${originalName} ${labels.editButton}` }).click()

    // 수정 다이얼로그 열림
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogEditTitle)).toBeVisible()

    // key input disabled 확인 (edit 모드에서 불변)
    const keyInput = page.getByLabel(labels.keyLabel)
    await expect(keyInput).toBeDisabled()

    // fieldType select disabled 확인 (edit 모드에서 불변)
    const fieldTypeSelect = page.getByLabel(labels.fieldTypeLabel)
    await expect(fieldTypeSelect).toBeDisabled()

    // 이름 prefill 확인
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
// S3 — 정의 삭제
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 커스텀 필드 정의 삭제 (FR-IS-10)', () => {
  // Given  "S3-삭제대상" SHORT_TEXT 필드가 목록에 있음
  // When   행의 삭제 버튼 → 인라인 확인 UI 표시 → 삭제 확인 클릭
  // Then   목록에서 "S3-삭제대상"이 사라짐 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S3 삭제 — 인라인 확인 후 삭제 → 목록에서 사라짐', async ({ page }) => {
    const fieldKey = 's3_target'
    const fieldName = 'S3-삭제대상'

    // 사전조건: 필드 생성
    await createShortTextField(page, fieldKey, fieldName)

    // 삭제 버튼 클릭 — aria-label="S3-삭제대상 삭제"
    await page.getByRole('button', { name: `${fieldName} ${labels.deleteButton}` }).click()

    // 인라인 확인 UI 표시
    await expect(page.getByText(labels.deleteConfirm)).toBeVisible()

    // 확인 "삭제" 버튼 클릭 — 인라인 확인 컨테이너 한정 (strict mode 회피)
    const confirmContainer = page.getByText(labels.deleteConfirm).locator('xpath=ancestor::div[1]')
    await confirmContainer.getByRole('button', { name: labels.deleteButton }).click()

    // 목록에서 제거됨
    await expect(page.getByText(fieldName, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 권한 disabled
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 커스텀 필드 관리 권한 게이팅 (FR-IS-10)', () => {
  // Given  alice 로그인 + addInitScript로 msw-custom-field-403='true' 설정
  //        → MSW createCustomFieldHandler가 403 반환
  //        → useProjectPermissions가 nonMemberProjectPermissions(MANAGE_CUSTOM_FIELDS:false) 반환
  // When   커스텀 필드 설정 페이지 진입 (플래그 적용 시점: goto 이후)
  // Then   "필드 추가" 버튼 disabled (fail-closed 게이팅)
  //        페이지 정상 렌더 (에러 없이 빈 상태 또는 목록 영역 표시)
  //
  // 참고: E2E_FORCE_CREATE_FALSE_KEY('__bts_e2e_force_create_false')를 설정하면
  //       project-permission-handlers가 nonMemberProjectPermissions를 반환하므로
  //       MANAGE_CUSTOM_FIELDS=false → 버튼 disabled로 됩니다.

  test('S4 권한 없는 사용자 — 필드 추가 버튼 disabled, 페이지 정상 렌더', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. goto 이전에 addInitScript 등록 — 다음 탐색부터 플래그 적용
    // E2E_FORCE_CREATE_FALSE_KEY → project-permission-handlers가 MANAGE_CUSTOM_FIELDS:false 반환
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_force_create_false', 'true')
    })

    // When. 커스텀 필드 설정 페이지 진입 (이 goto부터 플래그 적용)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()

    // Then. "필드 추가" 버튼 disabled (fail-closed 게이팅)
    const addButton = page.getByRole('button', { name: labels.addButton })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()

    // Then. 페이지 정상 렌더 — 에러 없이 빈 상태 또는 목록이 보임
    const emptyMsg = page.getByText(labels.emptyMessage)
    const fieldList = page.locator('ul.space-y-2')
    const isRenderedNormally = (await emptyMsg.isVisible()) || (await fieldList.count()) > 0
    expect(isRenderedNormally).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 이슈 생성 시 커스텀 필드 값 입력 + 이슈 상세 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 이슈 생성 시 커스텀 필드 값 입력 (FR-IS-10)', () => {
  // Given  alice 로그인
  //        브라우저 컨텍스트에서 MSW에 fetch를 직접 호출해 X-MSW-Seed-CustomFields 헤더로
  //        SHORT_TEXT 활성 필드 1개를 store에 시드한 뒤 이슈 생성 폼에 진입
  //        (msw-derived-behavior-shared-store-e2e 교훈 — 공유 store에서 브라우저 시드 가능)
  // When   /issues/new에서 projectKey=ATLAS 입력 → 커스텀 필드 섹션 렌더 확인
  //        커스텀 필드 위젯에 값 입력 + summary 입력 → 제출
  // Then   이슈 상세 페이지 진입, h1 summary 표시

  test('S5 이슈 생성 — 커스텀 필드 위젯 렌더 + 값 입력 후 제출 → 상세 표시', async ({ page }) => {
    // Given. alice 로그인 → /dashboard
    await loginAsAlice(page)

    // 커스텀 필드 시드 데이터 — SHORT_TEXT 활성 필드 1개
    const seedField = {
      id: '00000000-0000-4000-8000-000000000cf1',
      projectId: '00000000-0000-4000-8000-000000000001',
      key: 'sprint_note',
      name: '스프린트 메모',
      fieldType: 'SHORT_TEXT',
      required: false,
      displayOrder: 0,
      options: [],
    }
    const seedHeader = encodeURIComponent(JSON.stringify([seedField]))

    // /dashboard 상태에서 MSW SW가 이미 활성화됐으므로 시드 fetch를 호출한다.
    // MSW listCustomFieldsHandler가 X-MSW-Seed-CustomFields 헤더를 받아 store를 초기화한다.
    // SPA 내부 탐색(TanStack Router) 전에 시드해야 페이지 JS 컨텍스트가 유지된다.
    await page.evaluate(
      async ({ projectKey, header }: { projectKey: string; header: string }) => {
        await fetch(`/api/v1/projects/${projectKey}/custom-fields`, {
          headers: { 'X-MSW-Seed-CustomFields': header },
        })
      },
      { projectKey: 'ATLAS', header: seedHeader },
    )

    // SPA 내 navigate — TanStack Router history.pushState 트리거 (페이지 리로드 없음)
    // 페이지 리로드 없이 JS 컨텍스트(MSW store 포함)를 유지한 채 /issues/new로 이동한다.
    await page.evaluate(() => {
      window.history.pushState({}, '', '/issues/new')
      // TanStack Router가 popstate 이벤트를 감청하므로 CustomEvent 발행 불필요 — pushState만으로 충분
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    })

    // 이슈 생성 폼 렌더 대기
    await expect(page.getByLabel('프로젝트 키')).toBeVisible()

    // projectKey 입력 — useCustomFields(projectKey)로 커스텀 필드 정의 fetch 트리거
    // 시드 fetch로 store가 초기화됐으므로 이후 GET 요청에 sprint_note 필드가 반환됩니다.
    await page.getByLabel('프로젝트 키').fill('ATLAS')

    // 커스텀 필드 섹션이 렌더됨 (data-testid="custom-fields-section")
    await expect(page.getByTestId('custom-fields-section')).toBeVisible()

    // 커스텀 필드 위젯에 값 입력 — data-testid="custom-field-sprint_note"
    await page.getByTestId('custom-field-sprint_note').fill('E2E 테스트 스프린트')

    // summary 입력 — issueCreateStrings.summaryLabel = '제목'
    await page.getByLabel('제목').fill('S5 커스텀 필드 이슈')

    // 제출 — issueCreateStrings.submitButton = '이슈 생성'
    await page.getByRole('button', { name: '이슈 생성' }).click()

    // Then. 이슈 상세 페이지로 이동
    await page.waitForURL(/\/issues\/[A-Z]+-\d+$/)

    // 이슈 상세에 summary 표시
    await expect(page.getByRole('heading', { level: 1, name: 'S5 커스텀 필드 이슈' })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — required 필드 미입력 시 422 → 에러 표시
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// S6-A — SINGLE_SELECT 옵션 0개일 때 저장 버튼 disabled (클라이언트 검증)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6-A 커스텀 필드 클라이언트 검증 (FR-IS-10)', () => {
  // Given  관리 페이지에서 SINGLE_SELECT 필드 생성 시도
  // When   옵션 0개 상태에서 저장 버튼 상태 확인
  // Then   저장 버튼 disabled (isSaveDisabled 로직)
  // When   옵션 1개 추가
  // Then   저장 버튼 활성화

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
  })

  test('S6-A SINGLE_SELECT 옵션 0개일 때 저장 버튼 disabled → 옵션 추가 후 활성화', async ({ page }) => {
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    await page.getByLabel(labels.keyLabel).fill('s6a_select')
    await page.getByLabel(labels.nameLabel).fill('S6-선택형')
    await page.getByLabel(labels.fieldTypeLabel).selectOption('SINGLE_SELECT')

    // 선택지 편집기 노출
    await expect(page.getByRole('button', { name: '선택지 추가' })).toBeVisible()

    // 옵션 0개 상태에서 저장 버튼 disabled (isSaveDisabled 로직)
    const saveButton = page.getByRole('button', { name: labels.saveButton })
    await expect(saveButton).toBeDisabled()

    // 옵션 1개 추가 후 저장 버튼 활성화
    await page.getByRole('button', { name: '선택지 추가' }).click()
    await page.getByLabel('옵션 1 값').fill('opt1')
    await page.getByLabel('옵션 1 라벨').fill('옵션1')
    await expect(saveButton).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6-B — MSW 핸들러 에러 응답 → Dialog 유지 + submitError 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6-B 커스텀 필드 서버 에러 처리 (FR-IS-10)', () => {
  // Given  alice 로그인 + addInitScript로 msw-custom-field-403='true' 설정
  //        → custom-field-handlers의 createCustomFieldHandler가 403 반환
  // When   "필드 추가" → Dialog → 저장
  // Then   Dialog 유지 + role="alert" 에러 메시지 표시 (submitError 경로)

  test('S6-B 서버 403 에러 — Dialog 유지 + 에러 메시지 표시', async ({ page }) => {
    // Given. addInitScript 등록 — 다음 탐색부터 플래그 적용
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_CUSTOM_FIELD_403)

    // Given. alice 로그인 (addInitScript는 goto 전에 등록됐으므로 /login goto에 적용)
    await loginAsAlice(page)

    // When. 커스텀 필드 설정 페이지 진입
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()

    // "필드 추가" 클릭 → Dialog 열기
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // 키/이름 입력 (fieldType 기본 SHORT_TEXT)
    await page.getByLabel(labels.keyLabel).fill('s6b_denied')
    await page.getByLabel(labels.nameLabel).fill('S6-권한없음')

    // 저장 클릭 — MSW createCustomFieldHandler가 403 반환
    const saveButton = page.getByRole('button', { name: labels.saveButton })
    await expect(saveButton).not.toBeDisabled()
    await saveButton.click()

    // Then. Dialog가 열린 채로 에러 메시지가 표시됨
    // customFieldErrorMessage('CUSTOM_FIELD_ACCESS_DENIED') → '권한이 없습니다.'
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('권한이 없습니다')

    // Dialog는 닫히지 않음 (submitError 유지)
    await expect(page.getByRole('dialog')).toBeVisible()
  })
})
