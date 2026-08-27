// FR-WF-06 D7 E2E — 워크플로우 전환 규칙(validator) CRUD · 편집 불가 행 · 비admin 게이팅
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import type { WorkflowView } from '../src/api/workflows'
import {
  validatorDeleteButtonLabel,
  validatorEditButtonLabel,
  validatorLabels,
} from '../src/i18n/validator-labels'
import { E2E_IS_SYSTEM_ADMIN_KEY } from '../src/mocks/auth-handlers'
import {
  E2E_DELETE_FAILS_KEY,
  SEEDED_VALIDATOR_TRANSITION_ID,
  SEEDED_VALIDATOR_WORKFLOW_KEY,
  VALIDATOR_TYPES,
} from '../src/mocks/validator-handlers'
import { simpleFixture, softwareDefaultFixture } from '../src/mocks/workflow-fixtures'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 값은 전부 픽스처·라벨 정본에서 끌어온다
//
// ★ 셀렉터 문자열을 spec 에 베끼지 않는다. `validator-labels.ts` 가 E2E 셀렉터의 정본이고,
//   워크플로우·전환 좌표의 정본은 `workflow-fixtures.ts` 다. 베껴 두면 정본이 바뀌는 날
//   이 spec 만 조용히 낡는다.
//
// ★★ validator 라벨끼리 **부분문자열 포함 관계**가 있다 —
//   `전환 규칙 삭제`(다이얼로그 제목) ⊃ `규칙 삭제`(확인 버튼),
//   `첫 규칙 추가`(빈 상태 버튼) ⊃ `규칙 추가`(섹션 버튼),
//   `RequiredField 규칙 삭제 1`(행 버튼) ⊃ `규칙 삭제`.
//   Playwright 의 이름·텍스트 매칭은 **기본이 부분 일치**라 그대로 집으면 strict mode 로 즉사한다.
//   그래서 이 파일은 `getByRole`/`getByText` 전부에 `exact: true` 를 붙이고, 다이얼로그 안의
//   컨트롤은 다이얼로그 locator 로 범위를 좁힌다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 규칙을 **새로 만들** 워크플로우.
 *
 * MSW 시드는 software-default 의 한 전환에만 들어 있으므로, 여기 전환은 규칙 0건에서 출발한다 —
 * 「빈 상태 → 추가 → 수정 → 삭제 → 빈 상태」 왕복이 그대로 재현된다.
 */
const BLANK_WORKFLOW = simpleFixture

/**
 * `RequiredField` 의 필수 config 키.
 *
 * 정본은 `api/validators.ts` 의 `VALIDATOR_CONFIG_FORM_SCHEMAS` 이고 그 선언을
 * `scripts/workflow/validator-type-catalog.test.ts` 가 SDD §7.3 표와 대조한다. 폼이 그리는
 * 입력 칸의 라벨이 이 키 문자열 그대로라 spec 은 소비자로서 이 값을 집는다 — 키가 바뀌면
 * 이 E2E 가 red 로 알린다.
 */
const REQUIRED_FIELD_CONFIG_KEY = 'field'

/**
 * 픽스처에서 첫 NORMAL 전환 id 를 꺼낸다.
 *
 * 화면 select 의 값은 전환 id(UUID) 다(제약 C4). id 를 spec 에 리터럴로 적으면 픽스처와 갈리는
 * 두 번째 목록이 되므로 도출하고, 도출에 실패하면 조용한 폴백 대신 크게 터뜨린다.
 *
 * @param workflow 워크플로우 픽스처.
 * @return 첫 NORMAL 전환의 id.
 */
function firstNormalTransitionId(workflow: WorkflowView): string {
  const found = workflow.transitions.find((row) => row.kind === 'NORMAL')
  if (found === undefined) {
    throw new Error(`${workflow.key} 픽스처에 NORMAL 전환이 없다 — 규칙을 걸 자리가 사라졌다.`)
  }
  return found.id
}

/**
 * 행 버튼의 접근 가능한 이름을 **순번만 자유롭게** 둔 정규식으로 바꾼다.
 *
 * 행 버튼 이름에는 1-based 순번이 들어간다(같은 type 을 여러 번 걸 수 있으므로 type 만으로는
 * 유일하지 않다). 시드 안 행의 순번을 spec 에 못박으면 시드 순서가 바뀌는 날 「무엇이 틀렸는지」를
 * 말하지 못하는 red 가 난다. 이름 **형식**의 정본은 `validator-labels.ts` 의 라벨 헬퍼이므로
 * 문자열을 다시 조립하지 않고 1번 순번으로 부른 결과에서 접미 숫자만 벗긴다.
 *
 * @param labelForRowOne 라벨 헬퍼를 순번 1 로 부른 결과.
 * @return 순번 자리만 `\d+` 인 완전 일치 정규식.
 */
function anyRowNumber(labelForRowOne: string): RegExp {
  return new RegExp(`^${labelForRowOne.replace(/1$/, '')}\\d+$`)
}

/** 전환 규칙 섹션 제목(h3) — 섹션 노출 여부의 단일 판정점. */
function sectionHeading(page: Page): Locator {
  return page.getByRole('heading', { name: validatorLabels.section.title, exact: true })
}

/** 규칙 대상 전환 select — 형제 post-action 의 `전환 선택` 과 문자열이 겹치지 않는다. */
function transitionSelect(page: Page): Locator {
  return page.getByLabel(validatorLabels.section.transitionSelectLabel, { exact: true })
}

/** 섹션 헤더의 「규칙 추가」 버튼. 빈 상태의 「첫 규칙 추가」와 부분문자열로 겹치므로 exact 다. */
function addRuleButton(page: Page): Locator {
  return page.getByRole('button', { name: validatorLabels.section.addButton, exact: true })
}

/** 규칙 추가 다이얼로그. */
function createDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: validatorLabels.dialog.createTitle, exact: true })
}

/** 규칙 수정 다이얼로그. */
function editDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: validatorLabels.dialog.editTitle, exact: true })
}

/**
 * MSW validator store 를 시드 상태로 되돌린다.
 *
 * 전용 E2E reset 라우트(`DELETE /api/v1/__e2e__/validators/reset`)를 부른다 — 형제
 * post-action spec 이 세운 관례다. 각 테스트가 자기 데이터만 보게 해 테스트 간 누수를 막는다.
 *
 * @param page Playwright Page.
 */
async function resetValidatorStore(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch('/api/v1/__e2e__/validators/reset', { method: 'DELETE' })
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 묶음 1 — SYSTEM_ADMIN
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-WF-06 전환 규칙 설정 (SYSTEM_ADMIN)', () => {
  test.beforeEach(async ({ page }) => {
    // isSystemAdmin 플래그를 goto 전에 심어 whoami 핸들러가 true 를 돌려주게 한다
    // (형제 관례 · 메모리 `e2e-msw-scenario-toggle-localstorage-flag`).
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_IS_SYSTEM_ADMIN_KEY)

    await loginAsAlice(page)
    await resetValidatorStore(page)
  })

  test.afterEach(async ({ page }) => {
    // 플래그 정리 — 다른 테스트로 새지 않게 한다.
    await page.evaluate((key) => {
      localStorage.removeItem(key)
    }, E2E_IS_SYSTEM_ADMIN_KEY)
  })

  /**
   * T1 (형제 T1 승계)
   *
   * Given SYSTEM_ADMIN 으로 로그인했다
   * When  워크플로우 상세로 들어간다
   * Then  전환 규칙 섹션 · 규칙 대상 전환 select · 규칙 추가 버튼이 보인다
   */
  test('T1 전환 규칙 섹션이 SYSTEM_ADMIN 에게 노출된다', async ({ page }) => {
    // When. 워크플로우 상세 진입
    await page.goto(`/workflows/${BLANK_WORKFLOW.key}`)

    // Then. 워크플로우 이름 h1 이 뜬 뒤(= 상세 로드 완료) 섹션이 보인다
    await expect(page.getByRole('heading', { level: 1 })).toContainText(BLANK_WORKFLOW.name)
    await expect(sectionHeading(page)).toBeVisible()
    await expect(transitionSelect(page)).toBeVisible()
    await expect(addRuleButton(page)).toBeVisible()
  })

  /**
   * T2 (스펙 S1 · S2 · S3)
   *
   * Given SYSTEM_ADMIN 이 규칙 0건인 전환을 고르고 있다
   * When  `RequiredField` 규칙을 걸고 → 값을 고치고 → 하드 삭제 확인을 거쳐 지운다
   * Then  목록이 각 단계의 결과를 보여주고, 마지막엔 빈 상태로 돌아온다
   */
  test('T2 규칙 추가 → 목록 표시 → 수정 → 삭제 전체 CRUD', async ({ page }) => {
    // Given. 워크플로우 상세 진입 + 전환 선택
    await page.goto(`/workflows/${BLANK_WORKFLOW.key}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(BLANK_WORKFLOW.name)
    await expect(sectionHeading(page)).toBeVisible()

    await transitionSelect(page).selectOption(firstNormalTransitionId(BLANK_WORKFLOW))

    // Given. 아직 규칙이 없다
    await expect(page.getByText(validatorLabels.list.emptyTitle, { exact: true })).toBeVisible()

    // ── When 1. 규칙 추가 (S1) ────────────────────────────────────────────
    await addRuleButton(page).click()
    const create = createDialog(page)
    await expect(create).toBeVisible()

    await create
      .getByLabel(validatorLabels.form.typeLabel, { exact: true })
      .selectOption(VALIDATOR_TYPES.requiredField)
    await create.getByLabel(REQUIRED_FIELD_CONFIG_KEY, { exact: true }).fill('resolution')
    await create
      .getByRole('button', { name: validatorLabels.dialog.createButton, exact: true })
      .click()

    // Then 1. 다이얼로그가 닫히고 목록에 행이 뜬다.
    //   평가 시점 배지는 **응답의 `phase`** 가 정본이다 — `RequiredField` 만 EXECUTION 이므로
    //   `실행 시 차단` 이어야 한다(화면이 type→phase 표를 자기 코드에 들면 안 된다).
    await expect(create).toBeHidden()
    await expect(page.getByText(VALIDATOR_TYPES.requiredField, { exact: true })).toBeVisible()
    await expect(page.getByText(`${REQUIRED_FIELD_CONFIG_KEY}=resolution`, { exact: true })).toBeVisible()
    await expect(
      page.getByText(validatorLabels.list.phase.EXECUTION, { exact: true }),
    ).toBeVisible()

    // ── When 2. 그 행을 수정한다 (S2) ─────────────────────────────────────
    await page
      .getByRole('button', {
        name: validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 1),
        exact: true,
      })
      .click()

    const edit = editDialog(page)
    await expect(edit).toBeVisible()

    // Then 2-1. 기존 값이 프리필돼 있다
    const editedField = edit.getByLabel(REQUIRED_FIELD_CONFIG_KEY, { exact: true })
    await expect(editedField).toHaveValue('resolution')

    await editedField.fill('assignee')
    await edit.getByRole('button', { name: validatorLabels.dialog.saveButton, exact: true }).click()

    // Then 2-2. mutation 뒤 refetch 로 화면이 실제로 바뀐다 (옛 값은 사라진다)
    await expect(edit).toBeHidden()
    await expect(page.getByText(`${REQUIRED_FIELD_CONFIG_KEY}=assignee`, { exact: true })).toBeVisible()
    await expect(
      page.getByText(`${REQUIRED_FIELD_CONFIG_KEY}=resolution`, { exact: true }),
    ).toBeHidden()

    // ── When 3. 규칙을 푼다 (S3) ──────────────────────────────────────────
    await page
      .getByRole('button', {
        name: validatorDeleteButtonLabel(VALIDATOR_TYPES.requiredField, 1),
        exact: true,
      })
      .click()

    // Then 3-1. 되돌릴 수 없다는 확인을 거친다 (ADR 2026-08-25 hard-delete)
    const confirm = page.getByRole('dialog', {
      name: validatorLabels.dialog.deleteTitle,
      exact: true,
    })
    await expect(confirm).toBeVisible()
    await expect(
      confirm.getByText(validatorLabels.dialog.deleteDescription, { exact: true }),
    ).toBeVisible()

    await confirm
      .getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton, exact: true })
      .click()

    // Then 3-2. 행이 사라지고 빈 상태 문구가 돌아온다
    await expect(confirm).toBeHidden()
    await expect(page.getByText(VALIDATOR_TYPES.requiredField, { exact: true })).toBeHidden()
    await expect(page.getByText(validatorLabels.list.emptyTitle, { exact: true })).toBeVisible()
  })

  /**
   * T4 (형제 T4 승계 — 형제가 **실제로 잡은 회귀**다)
   *
   * Given 같은 전환에 같은 type 의 규칙 2행이 서로 다른 값으로 걸려 있다
   * When  B 행의 편집을 열어 닫은 **뒤** A 행의 편집을 연다
   * Then  A 의 값이 프리필되고 직전에 열었던 B 의 값이 새지 않는다
   *
   * ★ **여는 순서가 이 시나리오의 전부다.** 다이얼로그는 열 때마다 재마운트돼야 하는데,
   *   재마운트하지 않는 결함 구현이라도 **첫 개방**의 초기값은 그 행에서 온다 — A 를 먼저
   *   열면 결함 구현도 그냥 통과한다. 누수는 **서로 다른 행을 연속으로 열 때만** 드러나므로
   *   B → 취소 → A 순서여야 「매번 재마운트」와 「직전 행 값 잔류」가 구분된다.
   *   형제 `workflow-post-action.spec.ts` T4 와 Task 6 유닛 T6-12 가 같은 순서다.
   */
  test('T4 B 를 연 뒤 A 를 편집하면 A 값이 프리필되고 B 값이 새지 않는다', async ({ page }) => {
    // Given. 전환 선택
    await page.goto(`/workflows/${BLANK_WORKFLOW.key}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(BLANK_WORKFLOW.name)
    await expect(sectionHeading(page)).toBeVisible()
    await transitionSelect(page).selectOption(firstNormalTransitionId(BLANK_WORKFLOW))
    await expect(page.getByText(validatorLabels.list.emptyTitle, { exact: true })).toBeVisible()

    // Given. 같은 type 두 행을 서로 다른 값으로 만든다.
    //   type 을 갈라 두면 프리필이 type 분기만으로도 맞아 버려 이 시나리오가 아무것도 증명하지
    //   못한다 — 그래서 **같은 type · 다른 값**이어야 한다.
    const valueA = 'resolution'
    const valueB = 'assignee'
    for (const value of [valueA, valueB]) {
      await addRuleButton(page).click()
      const create = createDialog(page)
      await expect(create).toBeVisible()
      await create
        .getByLabel(validatorLabels.form.typeLabel, { exact: true })
        .selectOption(VALIDATOR_TYPES.requiredField)
      await create.getByLabel(REQUIRED_FIELD_CONFIG_KEY, { exact: true }).fill(value)
      await create
        .getByRole('button', { name: validatorLabels.dialog.createButton, exact: true })
        .click()
      await expect(create).toBeHidden()
      await expect(
        page.getByText(`${REQUIRED_FIELD_CONFIG_KEY}=${value}`, { exact: true }),
      ).toBeVisible()
    }

    const edit = editDialog(page)

    // ── When 1. **B(2행)** 의 편집을 먼저 연다 — 이 개방이 A 개방의 「직전 상태」를 만든다.
    //   행 버튼 이름의 순번이 A 와 B 를 가른다.
    await page
      .getByRole('button', {
        name: validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 2),
        exact: true,
      })
      .click()
    await expect(edit).toBeVisible()
    await expect(edit.getByLabel(REQUIRED_FIELD_CONFIG_KEY, { exact: true })).toHaveValue(valueB)

    // ── When 2. 저장하지 않고 닫는다 — 데이터는 그대로 두고 「직전에 연 행」만 남긴다
    await edit.getByRole('button', { name: validatorLabels.dialog.cancelButton, exact: true }).click()
    await expect(edit).toBeHidden()

    // ── When 3. 이어서 **A(1행)** 의 편집을 연다
    await page
      .getByRole('button', {
        name: validatorEditButtonLabel(VALIDATOR_TYPES.requiredField, 1),
        exact: true,
      })
      .click()

    // Then. A 의 값이 프리필된다 — 직전에 연 B 의 값이 남아 있으면 여기서 red 다
    await expect(edit).toBeVisible()
    await expect(edit.getByLabel(REQUIRED_FIELD_CONFIG_KEY, { exact: true })).toHaveValue(valueA)

    // 정리 — 취소로 닫는다. 취소는 저장이 아니므로 B 행은 목록에 그대로 살아 있어야 한다
    await edit.getByRole('button', { name: validatorLabels.dialog.cancelButton, exact: true }).click()
    await expect(edit).toBeHidden()
    await expect(
      page.getByText(`${REQUIRED_FIELD_CONFIG_KEY}=${valueB}`, { exact: true }),
    ).toBeVisible()
  })

  /**
   * T5 (스펙 S4 — `editable` 소비)
   *
   * Given 시드에 `CustomExpression` 행이 들어 있다
   * When  그 전환의 규칙 목록을 연다
   * Then  그 행은 **숨겨지지 않고 보이되** 편집이 비활성이고 이유가 표시된다. 삭제는 가능하다
   * And   새 규칙 추가의 종류 선택지에 `CustomExpression` 이 없다
   *
   * ★ 「보이되 편집 불가」가 이 시나리오의 핵심이다 — 숨기면 반쪽 목록이 관리자를 속인다.
   */
  test('T5 CustomExpression 행은 목록에 보이되 편집이 비활성이다', async ({ page }) => {
    // Given. 시드가 들어 있는 워크플로우·전환
    await page.goto(`/workflows/${SEEDED_VALIDATOR_WORKFLOW_KEY}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(softwareDefaultFixture.name)
    await expect(sectionHeading(page)).toBeVisible()
    await transitionSelect(page).selectOption(SEEDED_VALIDATOR_TRANSITION_ID)

    // Then 1. 행이 목록에 **보인다**
    await expect(page.getByText(VALIDATOR_TYPES.customExpression, { exact: true })).toBeVisible()

    // Then 2. 편집은 비활성이고 이유가 행에 표시된다 (툴팁 단독이 아니다)
    await expect(
      page.getByRole('button', {
        name: anyRowNumber(validatorEditButtonLabel(VALIDATOR_TYPES.customExpression, 1)),
      }),
    ).toBeDisabled()
    await expect(
      page.getByText(validatorLabels.list.notEditableTypeReason, { exact: true }),
    ).toBeVisible()

    // Then 3. 삭제는 가능하다 — backend 가 막지 않으므로 화면도 막지 않는다
    await expect(
      page.getByRole('button', {
        name: anyRowNumber(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, 1)),
      }),
    ).toBeEnabled()

    // Then 4. 추가 폼의 종류 선택지에는 없다.
    //   ★ 「없다」만 재면 select 가 통째로 안 그려져도 초록이다. 편집 가능한 종류가 **있다**는
    //     짝을 같이 재서 이 단언이 공허해지지 않게 한다.
    await addRuleButton(page).click()
    const typeSelect = createDialog(page).getByLabel(validatorLabels.form.typeLabel, { exact: true })
    await expect(
      typeSelect.getByRole('option', { name: VALIDATOR_TYPES.requiredField, exact: true }),
    ).toHaveCount(1)
    await expect(
      typeSelect.getByRole('option', { name: VALIDATOR_TYPES.customExpression, exact: true }),
    ).toHaveCount(0)
  })

  /**
   * T6 (부채 139) — 삭제가 실패하면 확인 창이 **열린 채** 남고 사유가 **그 창 안**에 뜬다.
   *
   * Given 삭제가 봉투 없는 500 을 돌려준다 (`E2E_DELETE_FAILS_KEY`)
   * When  확인 창에서 규칙 삭제를 누른다
   * Then  창이 닫히지 않고, 삭제 전용 fallback 문구가 그 창 안의 `alert` 로 뜬다
   * And   처리 중에는 확인·취소가 **둘 다** 잠긴다 — 그 사이 닫으면 실패가 갈 곳이 없다
   *
   * ★ 유닛(T6-16·T6-17)이 같은 계약을 jsdom + MSW 로 재지만, 이 화면의 모달은 Radix portal 이라
   *   실제 브라우저에서만 드러나는 자리가 있다(오버레이가 배경을 가리는 것 · 포커스 이동).
   *   `jira-parity-contract` §5 가 UI PR 에 e2e 동반 실행을 요구하는 이유다.
   *
   * ★★ 토글이 필요한 이유. Playwright `page.route` 로는 이 실패를 만들 수 없다 — MSW worker 가
   *   요청을 페이지 컨텍스트에서 처리해 네트워크로 나가지 않는다(실측). worker 도 전역에 노출돼
   *   있지 않아 `worker.use()` 를 부를 수 없다.
   */
  test('T6 삭제 실패는 확인 창을 닫지 않고 그 안에 사유를 남긴다', async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_DELETE_FAILS_KEY)

    await page.goto(`/workflows/${SEEDED_VALIDATOR_WORKFLOW_KEY}`)
    await expect(sectionHeading(page)).toBeVisible()
    await transitionSelect(page).selectOption(SEEDED_VALIDATOR_TRANSITION_ID)
    await expect(page.getByText(VALIDATOR_TYPES.customExpression, { exact: true })).toBeVisible()

    await page
      .getByRole('button', {
        name: anyRowNumber(validatorDeleteButtonLabel(VALIDATOR_TYPES.customExpression, 1)),
      })
      .click()

    const confirm = page.getByRole('dialog', {
      name: validatorLabels.dialog.deleteTitle,
      exact: true,
    })
    await expect(confirm).toBeVisible()
    await confirm
      .getByRole('button', { name: validatorLabels.dialog.deleteConfirmButton, exact: true })
      .click()

    // Then. 창이 남고 사유가 그 안에 있다.
    await expect(confirm).toBeVisible()
    await expect(confirm.getByRole('alert')).toContainText(validatorLabels.error.removeFailed)

    // And. 행도 목록에 남는다 — 실패했으므로 지워지지 않았다.
    await confirm
      .getByRole('button', { name: validatorLabels.dialog.deleteCancelButton, exact: true })
      .click()
    await expect(confirm).toBeHidden()
    await expect(page.getByText(VALIDATOR_TYPES.customExpression, { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 묶음 2 — 비admin 미노출 (형제 T3 승계)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-WF-06 전환 규칙 섹션 비admin 미노출', () => {
  test.beforeEach(async ({ page }) => {
    // isSystemAdmin 플래그를 심지 않는다 — alice 의 기본값은 false 다
    await loginAsAlice(page)
    await resetValidatorStore(page)
  })

  /**
   * T3
   *
   * Given isSystemAdmin=false 인 사용자로 로그인했다
   * When  워크플로우 상세로 들어간다
   * Then  전환 규칙 섹션 · 전환 select · 규칙 추가 버튼이 모두 보이지 않는다
   */
  test('T3 비admin 사용자에게는 전환 규칙 섹션이 노출되지 않는다', async ({ page }) => {
    // When. 워크플로우 상세 진입 — 읽기 전용 상세는 누구나 본다
    await page.goto(`/workflows/${BLANK_WORKFLOW.key}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(BLANK_WORKFLOW.name)

    // ★ whoami 해결을 먼저 확정한다. 헤더의 「계정 메뉴」는 whoami 결과로 그려지므로 이것이
    //   보이면 「아직 안 불러와서 섹션이 없는」 상태가 아님이 확정된다 — 그 확정 없이 미노출을
    //   재면 로딩 지연이 곧 초록인 가짜 그린이 된다(형제 spec 이 같은 처방을 쓴다).
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()

    // Then. 섹션 전체가 없다
    await expect(sectionHeading(page)).toBeHidden()
    await expect(transitionSelect(page)).toBeHidden()
    await expect(addRuleButton(page)).toBeHidden()
  })
})
