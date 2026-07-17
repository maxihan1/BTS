// FR-AT-07 PR-D D7 E2E — Git 웹훅 등록/URL 1회 노출/목록/삭제 + PR_MERGED targetBranch 라운드트립
//   (S1 등록+URL 노출 / S6 목록 표시 / S5 삭제 확인 / S12 targetBranch 라운드트립 — plan Task 10)
//
// 선례: automation-yaml-gitops.spec.ts(FR-AT-06 D6/D7)/automation-rules.spec.ts(FR-AT-01 D7) —
// 로그인/진입/셀렉터 패턴을 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: git-webhook-handlers.ts/
//     automation-rule-handlers.ts는 stateful 공유 store를 사용 — 각 테스트는 독립된 페이지 로드마다
//     git-webhook-fixtures.ts/automation-rule-fixtures.ts 모듈이 재평가되어 기본 시드로 리셋된다.
//   - zod-v4-uuid-fixture-strictness: 신규 PR_MERGED 시드는 RFC4122 v4 UUID
//     (automation-rule-fixtures.ts SEED_AUTOMATION_RULE_IDS.prMerged)를 쓴다. E2E는 mocks 소스를
//     import하지 않는 기존 관례 그대로 이 파일에도 리터럴로 고정한다.
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(항목명 포함) + exact:true 로
//     한정, 모달 내부 버튼은 role=dialog 컨테이너로 스코프
//   - spec-stated-count-becomes-blindfold: automation-rule-fixtures.ts의 PR_MERGED 시드는
//     DEFAULT_AUTOMATION_RULES 배열 맨 끝에 append됐다(위치 계약, 개수 아님) — 이 스펙은 그 계약을
//     전제하지 않고 이름 텍스트로만 룰을 찾는다.
//   - ui-pr-defer-e2e-regression-latent: 기존 automation E2E 6건(actions/conditions/
//     conflict-warning/execution-history/rules/yaml-gitops)과 함께 실행해 같은 페이지의 기존 흐름에
//     회귀가 없는지 확인한다(보고 시 별도 실행 결과 첨부).
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — automation-rule-fixtures.ts/git-webhook-fixtures.ts 시드와 동일 리터럴
// (직접 import 대신 리터럴 고정 — E2E는 프로덕션 화면 계약만 참조하고 mock 내부 상수에 최소
// 의존한다는 기존 관례, automation-yaml-gitops.spec.ts PROJECT_KEY/LS_* 동형)
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts/git-webhook-fixtures.ts DEFAULT_*_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/**
 * automation-rule-fixtures.ts DEFAULT_AUTOMATION_RULES 마지막 시드(append된 PR_MERGED 룰) 이름.
 * 이 스펙(task-10)이 그 fixture와 함께 추가한 값이다.
 */
const PR_MERGED_RULE_NAME = 'PR 병합 릴리즈 룰'
/** 위 시드 룰의 triggerConfig.targetBranch 값 — automation-rule-fixtures.ts와 동일 리터럴 */
const PR_MERGED_TARGET_BRANCH = 'release/1.2'

const labels = {
  ruleHeading: '자동화 룰',
  webhookHeading: 'Git 웹훅',
  addWebhookButton: '웹훅 등록',
  providerLabel: 'Provider',
  secretLabel: 'Secret',
  registerSubmit: '등록',
  registerTitle: 'Git 웹훅 등록',
  urlCopiedLabel: '복사됨',
  deleteButton: '삭제',
  /** GitWebhookSection.tsx labels.deleteConfirmTitle */
  deleteConfirmTitle: 'Git 웹훅을 삭제하시겠습니까?',
  /** GitWebhookSection.tsx labels.deleteConfirmButton */
  deleteConfirmButton: '삭제',
  ruleEditTitle: '자동화 룰 수정',
  ruleNameLabel: '이름',
  ruleSaveButton: '저장',
} as const

/** provider → 한국어(고유명사) 표시 라벨 — GitWebhookSection.tsx providerLabels 동형 리터럴 */
const providerLabels = { GITHUB: 'GitHub', GITLAB: 'GitLab' } as const

/** git-webhook-fixtures.ts VALID_SECRET(MIN_SECRET_LENGTH=16) 만족하는 유효 secret */
const VALID_SECRET = 'a'.repeat(32)

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** alice 로그인 후 자동화 설정 페이지로 이동하고 "Git 웹훅" 섹션 헤딩이 보일 때까지 대기한다. */
async function loginAndNavigate(page: Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto(SETTINGS_URL)
  await expect(page.getByRole('heading', { name: labels.webhookHeading, exact: true })).toBeVisible()
}

/** provider 표시 라벨로 목록의 웹훅 행(li) 컨테이너를 찾는다 (automation-rules.spec.ts ruleRow 동형). */
function webhookRow(page: Page, providerLabel: string): Locator {
  return page.getByText(providerLabel, { exact: true }).locator('xpath=ancestor::li[1]')
}

/** 룰 이름으로 목록의 룰 행(li) 컨테이너를 찾는다 (automation-rules.spec.ts ruleRow 동형). */
function ruleRow(page: Page, name: string): Locator {
  return page.getByText(name, { exact: true }).locator('xpath=ancestor::li[1]')
}

/**
 * "웹훅 등록" 버튼으로 등록 폼을 열고, provider를 선택(기본값 GITHUB가 아니면 콤보박스로 변경)한
 * 뒤 secret을 입력해 제출한다. 제출 직후 URL 1회 노출 모달이 뜰 때까지는 기다리지 않는다(호출부
 * 책임).
 */
async function registerWebhook(
  page: Page,
  provider: keyof typeof providerLabels,
  secret: string = VALID_SECRET,
): Promise<void> {
  await page.getByTestId('git-webhook-add-button').click()
  const dialog = page.getByTestId('git-webhook-register-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('heading', { name: labels.registerTitle })).toBeVisible()

  if (provider !== 'GITHUB') {
    await dialog.getByRole('combobox', { name: labels.providerLabel, exact: true }).click()
    await page.getByRole('option', { name: providerLabels[provider], exact: true }).click()
  }

  await dialog.getByLabel(labels.secretLabel, { exact: true }).fill(secret)
  await dialog.getByTestId('git-webhook-register-submit').click()
}

/** URL 1회 노출 모달을 "닫기" → 2단계 확인 "확정" 순으로 닫는다. */
async function closeUrlModal(page: Page): Promise<void> {
  const urlDialog = page.getByTestId('git-webhook-url-dialog')
  await urlDialog.getByTestId('git-webhook-url-close-button').click()
  await urlDialog.getByTestId('git-webhook-url-close-confirm').click()
  await expect(urlDialog).not.toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 등록하면 origin이 붙은 완전 URL이 1회 노출되고 복사할 수 있다
//
// Given   alice 로그인 + 클립보드 권한 부여 + 자동화 설정 페이지 진입
// When    "웹훅 등록" → GitLab 선택 → secret 입력 → 등록
// Then    등록 폼은 닫히고 URL 1회 노출 모달이 열리며, 표시된 URL이 origin으로 시작하고
//         "복사" 클릭 시 클립보드에 동일한 값이 담긴다. 닫으면(2단계 확인) 원문이 화면 어디에도
//         남지 않는다(1회 노출 재확인)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 등록 + URL 1회 노출 + 복사 (FR-AT-07 PR-D)', () => {
  test('등록하면 origin 이 붙은 완전 URL 이 1회 노출되고 복사할 수 있다', async ({ page, context }) => {
    // Given. 클립보드 권한 부여(automation-rules.spec.ts T4 선례) + alice 로그인 + 페이지 진입
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await loginAndNavigate(page)
    const origin = await page.evaluate(() => window.location.origin)

    // When. GitLab provider로 등록(기존 시드가 GitHub라 GitLab을 선택해 행 식별을 명확히 한다)
    await registerWebhook(page, 'GITLAB')

    // Then. 등록 폼은 닫히고 URL 1회 노출 모달이 열린다(FR2 순차 가드)
    const urlDialog = page.getByTestId('git-webhook-url-dialog')
    await expect(urlDialog).toBeVisible()
    await expect(page.getByTestId('git-webhook-register-dialog')).not.toBeVisible()

    // Then. 표시된 URL이 origin으로 시작하고 인바운드 경로 형태를 갖는다(FR9)
    const urlText = (await urlDialog.locator('code').textContent())?.trim() ?? ''
    expect(urlText.startsWith(origin)).toBe(true)
    expect(urlText).toMatch(/\/api\/v1\/webhooks\/git\/whk_[0-9a-f]+$/)

    // When. "복사" 클릭
    const copyButton = urlDialog.getByTestId('git-webhook-url-copy-button')
    await copyButton.click()

    // Then. 라벨이 "복사됨"으로 바뀌고 클립보드 내용이 화면 표시 URL과 일치한다
    await expect(copyButton).toHaveText(labels.urlCopiedLabel)
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText())
    expect(clipboardText).toBe(urlText)

    // When. 닫기 → 2단계 확인
    await closeUrlModal(page)

    // Then. 원문 URL은 더 이상 화면 어디에도 남지 않는다(1회 노출 재확인)
    await expect(page.getByText(urlText, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 등록 후 목록에 provider·등록일시로 표시된다
//
// Given   alice 로그인 + 자동화 설정 페이지 진입
// When    "웹훅 등록" → GitLab 선택 → secret 입력 → 등록 → URL 모달 닫기
// Then    목록에 새 행이 provider(GitLab)·등록일시(alice 기본 프리셋 iso, "YYYY-MM-DD HH:mm")로
//         표시된다. createdBy(UUID)는 화면에 노출되지 않는다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6 등록 후 목록 표시 (FR-AT-07 PR-D)', () => {
  test('등록 후 목록에 provider·등록일시로 표시된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입
    await loginAndNavigate(page)

    // When. GitLab provider로 등록 후 URL 모달 닫기
    await registerWebhook(page, 'GITLAB')
    await closeUrlModal(page)

    // Then. 새 행이 provider·등록일시(alice 기본 dateFormat='iso' → "YYYY-MM-DD HH:mm")로 표시된다
    const row = webhookRow(page, providerLabels.GITLAB)
    await expect(row).toBeVisible()
    await expect(row.getByText(providerLabels.GITLAB, { exact: true })).toBeVisible()
    await expect(row.getByText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 삭제 확인 모달이 복구 불가를 명시하고, 확인하면 목록에서 사라진다
//
// Given   alice 로그인 + 자동화 설정 페이지 진입(기본 시드 GitHub 웹훅 1건 존재)
// When    시드 행의 "삭제" 클릭 → 확인 모달 노출 → "삭제" 클릭
// Then    확인 모달은 복구 불가 문구와 provider 설정 URL 교체 안내를 함께 표시하고,
//         확인 후 모달이 닫히며 목록에서 해당 행이 사라진다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 삭제 확인 → 목록 제거 (FR-AT-07 PR-D)', () => {
  test('삭제 확인 모달이 복구 불가를 명시하고, 확인하면 목록에서 사라진다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 — 기본 시드 GitHub 웹훅 1건이 이미 목록에 있다
    // (git-webhook-fixtures.ts DEFAULT_GIT_WEBHOOKS, dev/E2E 모드 자동 시드)
    await loginAndNavigate(page)
    await expect(webhookRow(page, providerLabels.GITHUB)).toBeVisible()

    // When. 행 "삭제" 버튼 클릭 → 확인 모달 노출
    await page
      .getByRole('button', { name: `${providerLabels.GITHUB} ${labels.deleteButton}`, exact: true })
      .click()

    // Then. 확인 모달이 복구 불가 + provider 설정 URL 교체 안내를 함께 명시한다(FR14)
    const confirmDialog = page.getByRole('dialog')
    await expect(confirmDialog.getByRole('heading', { name: labels.deleteConfirmTitle })).toBeVisible()
    await expect(confirmDialog.getByText(/연동이 끊기고 복구할 수 없습니다/)).toBeVisible()
    await expect(confirmDialog.getByText(/provider 설정의 URL 도 함께 교체/)).toBeVisible()

    // When. 확인 모달 "삭제" 클릭(role=dialog 컨테이너로 한정 — 행의 "삭제" 버튼과 텍스트 중복 회피)
    await confirmDialog.getByRole('button', { name: labels.deleteConfirmButton, exact: true }).click()

    // Then. 모달이 닫히고 목록에서 해당 행이 제거된다
    await expect(confirmDialog).not.toBeVisible()
    await expect(page.getByText(providerLabels.GITHUB, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S12 — PR_MERGED 룰을 편집해 이름만 고쳐 저장해도 targetBranch가 유지된다(핵심 경로)
//
// EC16 — automation-rule-fixtures.ts에 PR_MERGED 시드가 0건이면 이 경로를 화면으로 밟을 수 없어
// omitManagedKeys 누락 같은 버그가 D7 눈검사를 통과한다(AutomationRuleFormDialog.test.tsx A4가
// 이미 컴포넌트 단위로는 이 가드를 커버하지만, 여기서는 실제 Dialog 로드→저장 왕복을 재현한다).
//
// Given   alice 로그인 + 자동화 설정 페이지 진입(PR_MERGED 시드 룰 존재, targetBranch='release/1.2')
// When    시드 룰의 "수정" → targetBranch 입력값 확인(로드 검증) → 이름만 변경 → 저장
// Then    PATCH 요청 본문의 triggerConfig에 targetBranch가 그대로 보존되고, 저장된 이름이 목록에
//         반영되며, 같은 룰을 다시 열어도 targetBranch 입력값이 그대로 남아있다(라운드트립 확인)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S12 PR_MERGED targetBranch 라운드트립 (FR-AT-07 PR-D, 핵심 경로)', () => {
  test('PR_MERGED 룰을 편집해 이름만 고쳐 저장해도 targetBranch 가 유지된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 — PR_MERGED 시드 룰이 이미 목록에 있다
    await loginAndNavigate(page)
    await expect(ruleRow(page, PR_MERGED_RULE_NAME)).toBeVisible()

    // When. 시드 룰의 "수정" 클릭 → 편집 Dialog가 targetBranch를 로드한다(A1 동형)
    await ruleRow(page, PR_MERGED_RULE_NAME)
      .getByRole('button', { name: `${PR_MERGED_RULE_NAME} 수정`, exact: true })
      .click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: labels.ruleEditTitle })).toBeVisible()
    await expect(dialog.getByTestId('automation-rule-target-branch-input')).toHaveValue(
      PR_MERGED_TARGET_BRANCH,
    )

    // When. PATCH 요청 본문을 가로챌 리스너를 저장 클릭 전에 등록한다(preferences.spec.ts S3 선례)
    let patchBody: unknown
    page.on('request', (request) => {
      if (request.method() === 'PATCH' && request.url().includes('/automation/rules/')) {
        patchBody = request.postDataJSON()
      }
    })

    // When. 이름만 변경(targetBranch 입력은 건드리지 않는다) → 저장
    const updatedName = `${PR_MERGED_RULE_NAME} (수정됨)`
    await dialog.getByLabel(labels.ruleNameLabel, { exact: true }).fill(updatedName)
    await dialog.getByTestId('automation-rule-save-button').click()

    // Then. 저장 성공 시 폼이 닫히고 목록에 새 이름이 반영된다
    await expect(dialog).not.toBeVisible()
    await expect(page.getByText(updatedName, { exact: true })).toBeVisible()

    // Then. PATCH 본문의 triggerConfig에 targetBranch가 유실 없이 그대로 보존된다(핵심 회귀 가드,
    // AutomationRuleFormDialog.test.tsx A2 동형 — omitManagedKeys 호출 자체가 지워져도 값 있는
    // 경로만 밟는 단위테스트는 못 잡는 누락을 여기서 화면으로 재현한다)
    if (patchBody === null || typeof patchBody !== 'object') {
      throw new Error('PATCH 요청 본문을 캡처하지 못함')
    }
    const body = patchBody as Record<string, unknown>
    if (typeof body['triggerConfig'] !== 'string') {
      throw new Error('PATCH 본문에 triggerConfig 문자열이 없음')
    }
    expect(JSON.parse(body['triggerConfig'])).toEqual({ targetBranch: PR_MERGED_TARGET_BRANCH })

    // Then. 같은 룰을 다시 열어도 targetBranch 입력값이 그대로 남아있다(서버 라운드트립 확인)
    await ruleRow(page, updatedName)
      .getByRole('button', { name: `${updatedName} 수정`, exact: true })
      .click()
    const reopenedDialog = page.getByRole('dialog')
    await expect(reopenedDialog.getByRole('heading', { name: labels.ruleEditTitle })).toBeVisible()
    await expect(
      reopenedDialog.getByTestId('automation-rule-target-branch-input'),
    ).toHaveValue(PR_MERGED_TARGET_BRANCH)
  })
})
