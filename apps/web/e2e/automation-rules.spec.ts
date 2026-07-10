// FR-AT-01 D7 E2E — 프로젝트 자동화 룰 UI (목록/생성/토글/삭제/OCC 409) — plan Task 9
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - e2e-msw-scenario-toggle-localstorage-flag: 빈 목록 시나리오는 addInitScript + localStorage 플래그
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-rule-handlers.ts는
//     stateful 공유 store(ruleStore)를 사용 — 각 테스트는 독립된 브라우저 컨텍스트(신규 페이지 로드마다
//     automation-rule-fixtures.ts 모듈이 재평가되어 기본 시드 2건으로 리셋)를 받으므로 데이터 격리가 보장된다.
//     이 특성 때문에 page.reload()/goto 재진입은 store를 초기화한다(가짜 그린 위험) — T7에서는 reload를
//     쓰지 않고 같은 페이지 컨텍스트 안에서 raw fetch로 자연스러운 OCC 버전 불일치를 유도한다.
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(룰 이름 포함) + exact:true 로 한정,
//     모달 내부 버튼은 role=dialog 컨테이너로 스코프
//   - data-testid 우선 사용 — automation-rule-add-button / -trigger-select / -cron-input /
//     -save-button / -cancel-button / -toggle-{id} / -edit-{id} / -delete-{id} / -delete-confirm-{id}
//   - ui-pr-defer-e2e-regression-latent: 기존 관련 E2E(예: version-management, webhook)와 함께 실행해
//     프로젝트 설정 라우트 회귀가 없는지 확인 (보고 시 별도 실행 결과 첨부)
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/** automation-rule-fixtures.ts SCENARIO_KEY.EMPTY_LIST 와 동일 문자열 리터럴(직접 import 대신 리터럴 고정 —
 *  E2E는 프로덕션 화면 계약만 참조하고 mock 내부 상수에 최소 의존한다는 기존 관례, webhook.spec.ts 동형) */
const LS_EMPTY_LIST = 'msw:automation-rule:empty-list'

const labels = {
  heading: '자동화 룰',
  addButton: '룰 추가',
  emptyMessage: '아직 자동화 룰이 없습니다.',
  createTitle: '자동화 룰 추가',
  editTitle: '자동화 룰 수정',
  nameLabel: '이름',
  triggerLabel: '트리거',
  saveButton: '저장',
  cancelButton: '취소',
  enableButton: '활성화',
  disableButton: '비활성화',
  enabledBadge: '활성',
  disabledBadge: '비활성',
  editButton: '수정',
  deleteButton: '삭제',
  deleteConfirmTitle: '자동화 룰을 삭제하시겠습니까?',
  deleteConfirmButton: '삭제',
  nextFireAtLabel: '다음 실행',
  webhookTitle: '웹훅 토큰이 발급되었습니다',
  webhookCopyButton: '복사',
  webhookCopiedLabel: '복사됨',
  webhookCloseButton: '닫기',
  conflictMessage: '다른 곳에서 먼저 변경되었습니다. 최신 정보를 다시 불러온 뒤 시도해주세요.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 룰 이름으로 목록의 행(li) 컨테이너를 찾는다 (version-management.spec.ts 동형 ancestor 스코프). */
function ruleRow(page: Page, name: string): Locator {
  return page.getByText(name, { exact: true }).locator('xpath=ancestor::li[1]')
}

/**
 * "룰 추가" 폼으로 ISSUE_CREATED 또는 SCHEDULED 룰 1건을 생성한다(Given 전제 데이터 생성 — 데이터 격리).
 * WEBHOOK 생성(토큰 모달 별도 처리 필요)은 T4에서 인라인으로 직접 다룬다.
 */
async function createRuleViaUi(
  page: Page,
  options: { name: string; scheduled?: { cron: string } },
): Promise<void> {
  await page.getByTestId('automation-rule-add-button').click()

  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()

  await dialog.getByLabel(labels.nameLabel, { exact: true }).fill(options.name)

  if (options.scheduled !== undefined) {
    await dialog.getByTestId('automation-rule-trigger-select').selectOption('SCHEDULED')
    await dialog.getByTestId('automation-rule-cron-input').fill(options.scheduled.cron)
  }

  await dialog.getByTestId('automation-rule-save-button').click()
  await expect(dialog).not.toBeVisible()
}

/** 행의 "수정" 버튼 data-testid(automation-rule-edit-{id})에서 룰 UUID를 추출한다. */
async function getRuleId(page: Page, name: string): Promise<string> {
  const editButton = ruleRow(page, name).getByRole('button', { name: `${name} ${labels.editButton}`, exact: true })
  const testId = await editButton.getAttribute('data-testid')
  if (testId === null) {
    throw new Error(`룰 "${name}"의 data-testid를 찾지 못했습니다`)
  }
  return testId.replace('automation-rule-edit-', '')
}

// ─────────────────────────────────────────────────────────────────────────────
// T1 — 목록 로드 + 빈 상태 CTA
//
// Given   alice 로그인 + EMPTY_LIST 시나리오 플래그(빈 목록 강제)
// When    자동화 설정 페이지 진입
// Then    빈 상태 문구 표시 + "룰 추가" 버튼으로 생성 폼이 열림(dead path 아님)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T1 목록 로드 + 빈 상태 CTA (FR-AT-01)', () => {
  test('Given 빈 목록 When 페이지 진입 Then 빈 상태 문구 + 룰 추가 CTA가 생성 폼을 연다', async ({ page }) => {
    // Given. EMPTY_LIST 플래그를 addInitScript로 심는다 — 핸들러 임시 교체 대신 localStorage 토글
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_EMPTY_LIST)
    await loginAsAlice(page)

    // When. 자동화 설정 페이지 진입
    await page.goto(SETTINGS_URL)

    // Then. 헤딩 + 빈 상태 문구
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()
    await expect(page.getByText(labels.emptyMessage, { exact: true })).toBeVisible()

    // Then. "룰 추가" CTA 클릭 → 실제 생성 폼이 열린다(dead path 금지 회귀 가드)
    await page.getByTestId('automation-rule-add-button').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()
    await expect(dialog.getByLabel(labels.nameLabel, { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2 — ISSUE_CREATED 룰 생성 → 목록 반영
//
// Given   alice 로그인 + 자동화 설정 페이지 진입
// When    "룰 추가" → 이름 입력(트리거 기본값 ISSUE_CREATED 유지) → 저장
// Then    목록에 새 행 반영 (이름 + "생성" 트리거 배지 + "활성" 배지)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T2 ISSUE_CREATED 룰 생성 → 목록 반영 (FR-AT-01)', () => {
  test('Given 자동화 설정 페이지 When 기본 트리거로 룰 생성 Then 목록에 새 행 반영', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    // When. 이름만 입력하고 저장(트리거 기본값 ISSUE_CREATED)
    const name = 'T2 이슈생성 룰'
    await createRuleViaUi(page, { name })

    // Then. 목록에 새 행 반영 — 이름 + 트리거 배지("생성") + enabled 배지("활성")
    const row = ruleRow(page, name)
    await expect(row).toBeVisible()
    await expect(row.getByText('생성', { exact: true })).toBeVisible()
    await expect(row.getByText(labels.enabledBadge, { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3 — SCHEDULED 룰 생성(cron 입력) → 목록에 스케줄 배지 + 다음 실행 표시
//
// Given   alice 로그인 + 자동화 설정 페이지 진입
// When    "룰 추가" → 트리거를 SCHEDULED로 선택 → cron 입력 → 저장
// Then    목록에 "스케줄" 트리거 배지 + "다음 실행" 텍스트가 표시된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T3 SCHEDULED 룰 생성(cron) → 스케줄 배지 + 다음 실행 표시 (FR-AT-01)', () => {
  test('Given 자동화 설정 페이지 When SCHEDULED 트리거+cron으로 생성 Then 스케줄 배지+다음 실행 표시', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    // When. SCHEDULED 트리거 + cron 입력 후 저장
    const name = 'T3 스케줄 룰'
    await createRuleViaUi(page, { name, scheduled: { cron: '0 0 9 * * *' } })

    // Then. 트리거 배지("스케줄") + "다음 실행" 텍스트(nextFireAt 계산값) 표시
    const row = ruleRow(page, name)
    await expect(row).toBeVisible()
    await expect(row.getByText('스케줄', { exact: true })).toBeVisible()
    await expect(row.getByText(new RegExp(labels.nextFireAtLabel))).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4 — WEBHOOK 룰 생성 → 토큰 모달 노출 + 복사 → 닫기 후 토큰 미표시
//
// Given   alice 로그인 + 클립보드 권한 부여 + 자동화 설정 페이지 진입
// When    "룰 추가" → 트리거를 WEBHOOK으로 선택 → 저장
// Then    토큰 모달이 원문 토큰과 함께 노출되고, "복사" 클릭 시 클립보드에 반영되며,
//         "닫기" 후에는 토큰 문자열이 화면 어디에도 남지 않는다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T4 WEBHOOK 룰 생성 → 토큰 모달 1회 노출 + 복사 + 닫기 후 미표시 (FR-AT-01)', () => {
  test('Given WEBHOOK 트리거 생성 When 토큰 모달에서 복사 후 닫기 Then 토큰이 더 이상 표시되지 않는다', async ({ page, context }) => {
    // Given. 클립보드 권한 부여 (dashboard-share.spec.ts / version-release-notes.spec.ts 선례)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    // When. WEBHOOK 트리거로 생성
    const name = 'T4 웹훅 룰'
    await page.getByTestId('automation-rule-add-button').click()
    const formDialog = page.getByRole('dialog')
    await expect(formDialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()
    await formDialog.getByLabel(labels.nameLabel, { exact: true }).fill(name)
    await formDialog.getByTestId('automation-rule-trigger-select').selectOption('WEBHOOK')
    await formDialog.getByTestId('automation-rule-save-button').click()

    // Then. 폼 다이얼로그는 닫히고 토큰 모달이 이어서 노출된다.
    // page.getByRole('dialog')는 특정 DOM 노드가 아니라 쿼리이므로, 폼이 닫히자마자 토큰 모달이 열리면
    // 같은 role=dialog 셀렉터가 새 모달로 즉시 재매칭되어 "닫힘" 판정이 거짓이 될 수 있다 — 폼 고유
    // 제목이 사라졌는지로 폼 닫힘을 판정한다.
    await expect(page.getByRole('heading', { name: labels.createTitle })).not.toBeVisible()
    const tokenDialog = page.getByRole('dialog')
    await expect(tokenDialog.getByRole('heading', { name: labels.webhookTitle })).toBeVisible()

    const tokenCode = tokenDialog.locator('code')
    const tokenText = (await tokenCode.textContent())?.trim() ?? ''
    expect(tokenText.startsWith('whk_')).toBe(true)

    // When. "복사" 클릭
    const copyButton = tokenDialog.getByTestId('webhook-token-copy-button')
    await copyButton.click()

    // Then. 라벨이 "복사됨"으로 바뀌고 클립보드 내용이 토큰과 일치
    await expect(copyButton).toHaveText(labels.webhookCopiedLabel)
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText())
    expect(clipboardText).toBe(tokenText)

    // When. "닫기" 클릭
    await tokenDialog.getByTestId('webhook-token-close-button').click()

    // Then. 모달이 사라지고, 토큰 원문 문자열은 화면 어디에도 남지 않는다(§1.18 — state 소거 회귀 가드)
    await expect(tokenDialog).not.toBeVisible()
    await expect(page.getByText(tokenText, { exact: true })).toHaveCount(0)

    // Then. 목록에는 "웹훅" 트리거 배지를 가진 행이 반영되어 있다
    await expect(ruleRow(page, name).getByText('웹훅', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5 — 활성 토글(PATCH enabled) → 배지 변화
//
// Given   alice 로그인 + 자동화 설정 페이지 진입 + 룰 1건 생성(Given 전제, 기본 활성)
// When    행의 "비활성화" 버튼 클릭
// Then    배지가 "비활성"으로 바뀌고 버튼 라벨도 "활성화"로 전환된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T5 활성 토글(PATCH enabled) → 배지 변화 (FR-AT-01)', () => {
  test('Given 활성 룰 When 비활성화 토글 Then 배지+버튼 라벨이 전환된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + 룰 1건 생성(기본 enabled=true)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const name = 'T5 토글 대상 룰'
    await createRuleViaUi(page, { name })

    const row = ruleRow(page, name)
    await expect(row.getByText(labels.enabledBadge, { exact: true })).toBeVisible()

    // When. "비활성화" 토글 버튼 클릭
    await row.getByRole('button', { name: `${name} ${labels.disableButton}`, exact: true }).click()

    // Then. 배지가 "비활성"으로 전환되고, 토글 버튼 라벨도 "활성화"로 바뀐다(PATCH 성공 → invalidate → refetch)
    await expect(row.getByText(labels.disabledBadge, { exact: true })).toBeVisible()
    await expect(row.getByRole('button', { name: `${name} ${labels.enableButton}`, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6 — 삭제 확인 → 목록 제거
//
// Given   alice 로그인 + 자동화 설정 페이지 진입 + 룰 1건 생성(Given 전제)
// When    행 "삭제" → 확인 모달 "삭제" 클릭
// Then    목록에서 해당 행이 제거된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T6 삭제 확인 → 목록 제거 (FR-AT-01)', () => {
  test('Given 생성된 룰 When 삭제 확인 클릭 Then 목록에서 제거된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + 룰 1건 생성
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const name = 'T6 삭제 대상 룰'
    await createRuleViaUi(page, { name })
    await expect(ruleRow(page, name)).toBeVisible()

    // When. 행 "삭제" 버튼 클릭 → 확인 모달 노출
    await ruleRow(page, name).getByRole('button', { name: `${name} ${labels.deleteButton}`, exact: true }).click()

    const confirmDialog = page.getByRole('dialog')
    await expect(confirmDialog.getByRole('heading', { name: labels.deleteConfirmTitle })).toBeVisible()
    await expect(confirmDialog.getByText(name)).toBeVisible()

    // When. 확인 모달 "삭제" 클릭(role=dialog 컨테이너로 한정 — 행의 "삭제" 버튼과 텍스트 중복 회피)
    await confirmDialog.getByRole('button', { name: labels.deleteConfirmButton, exact: true }).click()

    // Then. 모달이 닫히고 목록에서 해당 행이 제거된다
    await expect(confirmDialog).not.toBeVisible()
    await expect(page.getByText(name, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T7 — 409 OCC 버전 충돌 → 에러 표시(재조회 안내 문구 포함)
//
// Given   alice 로그인 + 자동화 설정 페이지 진입 + 룰 1건 생성(Given 전제)
//         페이지 컨텍스트 안에서 raw fetch로 같은 룰을 먼저 PATCH해 서버 version을 앞서 올린다
//         (다른 사용자가 먼저 변경한 상황을 자연스럽게 재현 — MSW 핸들러 수정 없이 실제 OCC 시맨틱만 사용)
// When    화면에는 여전히 구버전이 캐시된 채로 "수정" → 이름 변경 → 저장
// Then    409 버전 충돌 에러 문구(재조회 안내 포함)가 표시되고, 폼은 닫히지 않으며,
//         취소 후 목록은 오염되지 않은 채로 남는다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T7 409 OCC 버전 충돌 → 에러 표시 (FR-AT-01)', () => {
  test('Given 캐시가 뒤처진 룰 When 수정 저장 Then 버전 충돌 에러가 표시되고 폼이 유지된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + 룰 1건 생성(서버/캐시 모두 version=1)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const name = 'T7 OCC 대상 룰'
    await createRuleViaUi(page, { name })
    const ruleId = await getRuleId(page, name)

    // Given. 같은 페이지 컨텍스트에서 raw fetch로 "다른 사용자"가 먼저 변경한 상황을 만든다.
    // 화면(TanStack Query 캐시)은 이 변경을 모르는 채로 version=1을 그대로 들고 있다.
    const externalPatchStatus = await page.evaluate(
      async ({ projectKey, id }) => {
        const res = await fetch(`/api/v1/projects/${projectKey}/automation/rules/${id}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ version: 1, name: '외부에서 먼저 변경됨' }),
        })
        return res.status
      },
      { projectKey: PROJECT_KEY, id: ruleId },
    )
    expect(externalPatchStatus).toBe(200)

    // When. 화면은 여전히 구버전 캐시(이름="T7 OCC 대상 룰", version=1)를 보여주므로 그 행의 "수정"을 연다
    await ruleRow(page, name).getByRole('button', { name: `${name} ${labels.editButton}`, exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: labels.editTitle })).toBeVisible()

    // When. 이름을 바꾸고 저장 — 폼이 들고 있던 stale version=1로 PATCH 전송 → 서버 현재 version=2와 불일치
    await dialog.getByLabel(labels.nameLabel, { exact: true }).fill('충돌 시도 이름')
    await dialog.getByTestId('automation-rule-save-button').click()

    // Then. 409 버전 충돌 에러 문구(재조회 안내 포함) 표시 + 폼은 닫히지 않는다(onOpenChange(false)는 성공시에만 호출)
    await expect(dialog.getByRole('alert').filter({ hasText: labels.conflictMessage })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: labels.editTitle })).toBeVisible()

    // When. 취소로 폼을 닫는다(저장 실패로 캐시 invalidate가 일어나지 않았으므로 목록은 그대로)
    await dialog.getByTestId('automation-rule-cancel-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 목록은 실패한 PATCH로 오염되지 않았다 — "충돌 시도 이름"으로 반영되지 않는다
    await expect(page.getByText('충돌 시도 이름', { exact: true })).toHaveCount(0)
  })
})
