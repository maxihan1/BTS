// FR-NT-05 D7 E2E — 워크플로우 전이 post-action CRUD + 비admin 게이팅
import { test, expect } from '@playwright/test'
import { postActionLabels } from '../src/i18n/post-action-labels'
import { E2E_IS_SYSTEM_ADMIN_KEY } from '../src/mocks/auth-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트에 사용할 워크플로우 키 — workflow-fixtures.ts의 simple 워크플로우 (3전이로 select 선택 단순) */
const WORKFLOW_KEY = 'simple'

/**
 * simple 워크플로우의 첫 번째 전이 display name — 전이 선택 드롭다운에서 이 텍스트 선택.
 * workflow-fixtures.ts: { key: 'todo__doing', name: 'Start', ... }
 */
const TRANSITION_NAME = 'Start'

const labels = postActionLabels

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — FR-AU-07 identifier-first 2단계 로그인
// session-fixtures.ts의 loginAsAlice 패턴과 동일 (2단계 흐름)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice(Local provider)로 2단계 로그인 후 /dashboard 도달.
 * auth-fixtures.ts: alice.isSystemAdmin=false (기본값).
 * isSystemAdmin 토글이 필요한 경우 addInitScript로 localStorage 플래그 선설정 후 호출.
 */
async function loginAsAlice(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

  // 1단계: 이메일 입력 + "계속" — example.com 미등록 도메인 → 2단계 진입
  await page.getByLabel('이메일').fill('alice@example.com')
  await page.getByRole('button', { name: '계속', exact: true }).click()

  // 2단계: provider 드롭다운 대기 후 Local 선택
  const providerSelect = page.getByRole('combobox', { name: '로그인 방식' })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: 'Local', exact: true }).click()

  // 2단계: username + password 입력 후 로그인
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL('**/dashboard')
}

/**
 * MSW post-action store를 완전 초기화한다.
 * 전용 E2E reset 라우트(DELETE /api/v1/__e2e__/post-actions/reset)를 호출한다.
 * 각 테스트 beforeEach에서 호출해 테스트 간 store 누수를 차단한다.
 * (D8 — 전용 라우트가 GET 헤더 방식보다 누락 위험이 낮음)
 */
async function resetPostActionStore(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch('/api/v1/__e2e__/post-actions/reset', { method: 'DELETE' })
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 1 — SYSTEM_ADMIN CRUD 전체 경로
//
// Given: SYSTEM_ADMIN 사용자(alice + E2E_IS_SYSTEM_ADMIN 플래그)로 로그인
// When: /workflows/simple 진입 → post-action 섹션 노출 확인
//       → 전이 선택 → Webhook 추가 → url/method 저장
//       → 목록에 행 표시 → 행 수정(url 변경) → 반영 확인 → 삭제 → 목록에서 사라짐
// Then: 각 단계 assertion 통과
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-NT-05 post-action CRUD (SYSTEM_ADMIN)', () => {
  test.beforeEach(async ({ page }) => {
    // isSystemAdmin 플래그를 goto 전에 심어 whoami 핸들러가 true 반환
    // (e2e-msw-scenario-toggle-localstorage-flag, msw-derived-behavior-shared-store-e2e)
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_IS_SYSTEM_ADMIN_KEY)

    await loginAsAlice(page)

    // store 초기화 — 테스트 격리
    await resetPostActionStore(page)
  })

  test.afterEach(async ({ page }) => {
    // isSystemAdmin 플래그 정리 — 다른 테스트에 누수되지 않도록
    await page.evaluate((key) => {
      localStorage.removeItem(key)
    }, E2E_IS_SYSTEM_ADMIN_KEY)
  })

  test('T1 post-action 섹션이 SYSTEM_ADMIN에게 노출된다', async ({ page }) => {
    // Given: SYSTEM_ADMIN으로 로그인 완료 (beforeEach)
    // When: /workflows/simple 진입
    await page.goto(`/workflows/${WORKFLOW_KEY}`)

    // 워크플로우 다이어그램 렌더 대기
    await expect(page.getByRole('heading', { level: 1 })).toContainText('단순 워크플로우')

    // Then: post-action 섹션 제목 노출
    await expect(page.getByText(labels.section.title)).toBeVisible()

    // Then: 전이 선택 드롭다운 노출
    await expect(page.getByLabel(labels.section.transitionSelectLabel)).toBeVisible()

    // Then: "Webhook 추가" 버튼 노출
    await expect(
      page.getByRole('button', { name: labels.section.addWebhookButton, exact: true }),
    ).toBeVisible()
  })

  test('T2 Webhook 추가 → 목록 표시 → 수정 → 삭제 전체 CRUD', async ({ page }) => {
    // Given: SYSTEM_ADMIN으로 로그인, /workflows/simple 진입
    await page.goto(`/workflows/${WORKFLOW_KEY}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('단순 워크플로우')
    await expect(page.getByText(labels.section.title)).toBeVisible()

    // ── When 1. 전이 선택 ─────────────────────────────────────────────────
    const transitionSelect = page.getByLabel(labels.section.transitionSelectLabel)
    await transitionSelect.selectOption({ label: TRANSITION_NAME })

    // Then 1. 빈 상태 안내 노출 (아직 post-action 없음)
    await expect(page.getByText(labels.list.emptyState)).toBeVisible()

    // ── When 2. "Webhook 추가" 클릭 → 다이얼로그 오픈 ────────────────────
    await page.getByRole('button', { name: labels.section.addWebhookButton, exact: true }).click()

    // Then 2. create 다이얼로그 제목 노출
    await expect(page.getByText(labels.dialog.createTitle)).toBeVisible()

    // ── When 3. url + method 입력 후 저장 ───────────────────────────────
    const webhookUrl = 'https://hooks.example.com/test-webhook'
    await page.getByLabel(labels.form.urlLabel).fill(webhookUrl)

    // method select — POST 선택
    const methodSelect = page.getByLabel(labels.form.methodLabel)
    await methodSelect.selectOption('POST')

    await page.getByRole('button', { name: labels.dialog.createButton, exact: true }).click()

    // Then 3. 다이얼로그 닫힘 + 목록에 새 행 표시
    await expect(page.getByText(labels.dialog.createTitle)).not.toBeVisible()

    // 목록 테이블 헤더 노출 (행이 생긴 것)
    await expect(page.getByText(labels.list.typeColumn)).toBeVisible()

    // CALL_WEBHOOK 행 노출
    await expect(page.getByText('CALL_WEBHOOK')).toBeVisible()

    // url이 config 컬럼에 요약 표시
    await expect(page.getByText(webhookUrl, { exact: false })).toBeVisible()

    // ── When 4. 수정 버튼 클릭 → edit 다이얼로그 오픈 ──────────────────
    // "수정" 버튼이 여럿일 수 있으나 현재는 1개 — aria-label 활용
    await page.getByRole('button', { name: /post-action 수정/, exact: false }).click()

    // Then 4. edit 다이얼로그 제목 + url 프리필 확인
    await expect(page.getByText(labels.dialog.editTitle)).toBeVisible()
    const urlInput = page.getByLabel(labels.form.urlLabel)
    await expect(urlInput).toHaveValue(webhookUrl)

    // ── When 5. url 변경 후 저장 ─────────────────────────────────────────
    const updatedUrl = 'https://hooks.example.com/updated-webhook'
    await urlInput.fill(updatedUrl)
    await page.getByRole('button', { name: labels.dialog.saveButton, exact: true }).click()

    // Then 5. 다이얼로그 닫힘 + 목록에 변경된 url 반영
    await expect(page.getByText(labels.dialog.editTitle)).not.toBeVisible()
    await expect(page.getByText(updatedUrl, { exact: false })).toBeVisible()
    await expect(page.getByText(webhookUrl, { exact: false })).not.toBeVisible()

    // ── When 6. 삭제 버튼 클릭 ──────────────────────────────────────────
    await page.getByRole('button', { name: /post-action 삭제/, exact: false }).click()

    // Then 6. 목록에서 사라지고 빈 상태 안내 재노출
    await expect(page.getByText('CALL_WEBHOOK')).not.toBeVisible()
    await expect(page.getByText(labels.list.emptyState)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // T4 — B1(다중 행 stale 프리필) 검증
  //
  // Given: SYSTEM_ADMIN, /workflows/simple 진입, 전이 선택
  // When: url_a로 Webhook A 생성 → url_b로 Webhook B 생성
  //       → A 행의 "수정" 버튼 클릭
  // Then: 다이얼로그에 url_a가 프리필됨 (B의 url_b 새지 않음)
  //       Fix-A key 재마운트가 올바르면 통과
  // ─────────────────────────────────────────────────────────────────────────────
  test('T4 2행 시나리오 — A 수정 다이얼로그에 A url 프리필됨 (B url 새지 않음)', async ({ page }) => {
    // Given: /workflows/simple 진입 + 전이 선택
    await page.goto(`/workflows/${WORKFLOW_KEY}`)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('단순 워크플로우')
    await expect(page.getByText(labels.section.title)).toBeVisible()

    const transitionSelect = page.getByLabel(labels.section.transitionSelectLabel)
    await transitionSelect.selectOption({ label: TRANSITION_NAME })
    await expect(page.getByText(labels.list.emptyState)).toBeVisible()

    // ── When 1. Webhook A(url_a) 추가 ───────────────────────────────────
    const urlA = 'https://hooks.example.com/webhook-a'
    await page.getByRole('button', { name: labels.section.addWebhookButton, exact: true }).click()
    await expect(page.getByText(labels.dialog.createTitle)).toBeVisible()
    await page.getByLabel(labels.form.urlLabel).fill(urlA)
    await page.getByLabel(labels.form.methodLabel).selectOption('POST')
    await page.getByRole('button', { name: labels.dialog.createButton, exact: true }).click()
    await expect(page.getByText(labels.dialog.createTitle)).not.toBeVisible()

    // Then 1. url_a가 목록에 표시됨
    await expect(page.getByText(urlA, { exact: false })).toBeVisible()

    // ── When 2. Webhook B(url_b) 추가 ───────────────────────────────────
    const urlB = 'https://hooks.example.com/webhook-b'
    await page.getByRole('button', { name: labels.section.addWebhookButton, exact: true }).click()
    await expect(page.getByText(labels.dialog.createTitle)).toBeVisible()
    await page.getByLabel(labels.form.urlLabel).fill(urlB)
    await page.getByLabel(labels.form.methodLabel).selectOption('POST')
    await page.getByRole('button', { name: labels.dialog.createButton, exact: true }).click()
    await expect(page.getByText(labels.dialog.createTitle)).not.toBeVisible()

    // Then 2. url_a, url_b 둘 다 목록에 표시됨
    await expect(page.getByText(urlA, { exact: false })).toBeVisible()
    await expect(page.getByText(urlB, { exact: false })).toBeVisible()

    // ── When 3. A 행 "수정" 버튼 클릭 (첫 번째 행 — url_a 행) ──────────
    // aria-label 패턴: "post-action 수정 <id>" — 첫 번째 행을 first()로 한정
    const editButtons = page.getByRole('button', { name: /post-action 수정/, exact: false })
    await editButtons.first().click()

    // Then 3. 다이얼로그에 url_a가 프리필됨 — url_b가 새지 않음
    await expect(page.getByText(labels.dialog.editTitle)).toBeVisible()
    await expect(page.getByLabel(labels.form.urlLabel)).toHaveValue(urlA)

    // 취소 후 정리
    await page.getByRole('button', { name: labels.dialog.cancelButton, exact: true }).click()
    await expect(page.getByText(labels.dialog.editTitle)).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 2 — 비admin(isSystemAdmin=false) 미노출
//
// Given: alice(isSystemAdmin=false 기본값)로 로그인
// When: /workflows/simple 진입
// Then: post-action 섹션 미노출, 다이어그램은 정상 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-NT-05 post-action 섹션 비admin 미노출', () => {
  test.beforeEach(async ({ page }) => {
    // D8 — 비admin 시나리오도 store 초기화해 다른 테스트의 잔류 데이터 차단
    await loginAsAlice(page)
    await resetPostActionStore(page)
  })

  test('T3 비admin 사용자에게는 post-action 섹션이 노출되지 않는다', async ({ page }) => {
    // Given: isSystemAdmin 플래그 없는 기본 alice(isSystemAdmin=false) — beforeEach에서 로그인 완료

    // When: /workflows/simple 진입
    await page.goto(`/workflows/${WORKFLOW_KEY}`)

    // 다이어그램은 정상 노출 (read-only는 누구나 볼 수 있음)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('단순 워크플로우')
    await page.waitForSelector('[aria-label*="다이어그램"] svg', {
      state: 'visible',
      timeout: 10_000,
    })

    // D3 — whoami 해결 보장.
    // Header.tsx가 whoami 결과(user.username)로 "alice 계정 메뉴" 버튼을 렌더한다.
    // 다이어그램은 별도 fetchWorkflow 쿼리라 whoami 해결을 보장하지 않는다.
    // 이 버튼이 보이면 whoami가 해결됐음(비admin)이 확정되므로 그 다음 섹션 미노출 단언이 진짜 비admin 게이팅을 검증한다.
    await expect(
      page.getByRole('button', { name: /alice 계정 메뉴/, exact: false }),
    ).toBeVisible()

    // Then: post-action 섹션 제목 미노출
    await expect(page.getByText(labels.section.title)).not.toBeVisible()

    // Then: "Webhook 추가" 버튼 미노출
    await expect(
      page.getByRole('button', { name: labels.section.addWebhookButton, exact: true }),
    ).not.toBeVisible()

    // Then: 전이 선택 드롭다운 미노출
    await expect(page.getByLabel(labels.section.transitionSelectLabel)).not.toBeVisible()
  })
})
