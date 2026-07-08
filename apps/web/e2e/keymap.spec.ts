// FR-PF-03 Task 10 E2E — 단축키 커스터마이즈(/settings/keymap) 재배치→발화 / 충돌 거부 / 기본 복원
//
// 시나리오 개요.
//   S1. 재배치→발화       — create-issue 키를 `n`으로 재배치·저장 → SPA 내부 이동 후 `n` 입력 시
//                          `/issues/new`로 이동한다(useKeyboardShortcuts가 invalidate 후 재조회한
//                          병합 키맵을 즉시 반영, 하드 리로드 불필요 — Task 8).
//   S2. 완전중복 거부     — search를 create-issue 기본값(`c`)과 겹치게 재배치 → 두 행 모두 충돌
//                          배지 표시 + 저장 버튼 비활성화(로컬 검증이 서버 요청 이전에 차단).
//   S3. dead-leader 거부  — goto-my-issues를 `g g`(리더 키 자기 자신)로 재배치 → 충돌 배지 표시 +
//                          저장 버튼 비활성화.
//   S4. 기본값 복원       — create-issue를 `n`으로 재배치·저장(override 영속) → "기본값 복원" 클릭 후
//                          재저장 → 하드 리로드로 GET 재조회 시 기본값(`c`)으로 되돌아간다(override
//                          실제 삭제, 로컬 draft 되돌림만으로는 검증 불가).
//   S5. 서버발 409 배너   — 강제 409 시나리오 토글(localStorage) 적용 후, 로컬 검증을 통과하는
//                          무해한 재배치를 저장 → 서버가 409로 거부하면 서버 충돌 배너가 표시된다
//                          (mocks/keymap-handlers.ts에 Task 10에서 추가한 시나리오 토글).
//
// 설계 결정.
//   - GET/PATCH는 mocks/keymap-handlers.ts(Task 7, userId-keyed stateful override Map)를 그대로
//     사용한다. Playwright 기본 설정(storageState 미사용, 테스트별 새 페이지)이라 각 테스트가 새
//     브라우저 컨텍스트에서 시작 → 모듈이 재평가되어 override가 케이스 간 leak되지 않는다.
//   - S1은 저장 직후 `/settings/keymap`에 남아서 곧바로 새 키를 누르지 않는다. 캡처 input이
//     포커스를 쥐고 있으면 `shouldIgnoreEvent`(shortcuts.ts의 입력 가드, isEditableTarget)가 전역
//     단축키 리스너를 막는다. 저장 후 Header의 SPA `<Link to="/dashboards">`를 클릭해 포커스를
//     이탈시키고 하드 리로드 없이(keymapStore 메모리 보존, msw-mutation-stateful-refetch 교훈)
//     이동한 뒤에 키를 누른다.
//   - S4는 하드 리로드(page.goto) 왕복으로 GET 재조회(override 실제 삭제)까지 검증한다 — 로컬 draft
//     되돌림만으로는 서버 상태가 실제로 지워졌는지 확인할 수 없다.
//   - ★ 리더 접두(leaderPrefix, single "g" + "g X" 공존) 충돌은 이 UI로는 재현 불가능하다 —
//     KeymapForm.handleKeyCapture는 `e.key === LEADER_KEY`(= "g")를 누르면 무조건 leader 대기
//     상태로 진입시키므로(다음 키를 기다림), 어떤 행에서도 bare 단일 "g"를 캡처할 방법이 없다
//     (Escape는 대기만 취소할 뿐 draft를 "g"로 만들지 않는다). 빈값(blank) 위반도 마찬가지로
//     캡처 흐름상 도달 불가(모든 keydown이 최소 1글자 값을 만든다, 지우기 버튼 없음). plan에 명시된
//     "leader접두 또는 빈값 1개 이상"의 실질 대체로 dead-leader(S3, 실제로 UI에서 재현 가능한
//     6종 규칙 중 하나)를 사용한다 — 로컬/서버 검증 로직상 duplicate와는 다른 코드 경로(설명 문구도
//     다름)를 타므로 "완전중복 외 최소 1종" 요건을 실질적으로 충족한다. 구현 코드는 손대지 않음
//     (qa 범위 밖 — Maxi/구현 담당자 보고 대상).
//   - ★ S2/S3는 KeymapForm의 로컬 검증(서버 6종 규칙을 1:1 복제)이 저장 버튼을 비활성화시켜
//     네트워크 요청 자체를 막는 경로를 검증한다 — 서버로 실제 PATCH가 전송되는 순간에는 이미
//     로컬 검증을 통과한 상태이므로, 실제 사용자 흐름으로는 서버 409 응답 렌더 경로에 도달할
//     방법이 없다. S5는 mocks/keymap-handlers.ts에 추가한 강제 409 시나리오 토글
//     (`LS_KEY_KEYMAP_FORCE_CONFLICT`)로 이 경로를 별도 검증한다 — Task 7 핸들러 위에 신규 추가한
//     시드/시나리오.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 그대로(MSW 기본 allow)
//   - msw-mutation-stateful-refetch: keymap-handlers.ts PATCH가 override를 메모리에 보존 → 재조회 반영 검증(S1/S4)
//   - playwright-getbyrole-exact-strict-mode: 버튼/링크 모두 name+exact:true로 한정
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 공유 헬퍼 재사용(AUTH_USERS 기반 userId 정합,
//     keymap-handlers.ts의 resolveUserIdFromRequest도 같은 AUTH_USERS를 참조)
//   - ui-pr-defer-e2e-regression-latent: 이 파일만 신규 추가, 기존 E2E 수정 없음(회귀 확인은 별도 실행)
//   - 셀렉터는 keymapSettingsStrings(i18n 정본) 참조 — hardcoded 한국어 리터럴 금지

import { test, expect, type Page, type Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { keymapSettingsStrings, issueCreateStrings } from '../src/i18n/ko'
import type { KeymapActionId } from '../src/api/keymap'
import { LS_KEY_KEYMAP_FORCE_CONFLICT } from '../src/mocks/keymap-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const KEYMAP_PAGE_HEADING = keymapSettingsStrings.pageTitle
const SAVE_BUTTON_NAME = keymapSettingsStrings.saveButtonAriaLabel

/** Header.tsx L92-97 메인 nav "대시보드" Link(`to="/dashboards"`) — SPA 내부 이동(하드 리로드 없음) */
const DASHBOARDS_LINK_NAME = '대시보드'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** action id → 키 캡처 input Locator (keymapSettingsStrings.captureInputAriaLabel 정본 사용) */
function captureInputFor(page: Page, action: KeymapActionId): Locator {
  const label = keymapSettingsStrings.actionLabels[action]
  return page.getByLabel(keymapSettingsStrings.captureInputAriaLabel(label), { exact: true })
}

/** action id → 기본값 복원 버튼 Locator (keymapSettingsStrings.resetButtonAriaLabel 정본 사용) */
function resetButtonFor(page: Page, action: KeymapActionId): Locator {
  const label = keymapSettingsStrings.actionLabels[action]
  return page.getByRole('button', { name: keymapSettingsStrings.resetButtonAriaLabel(label), exact: true })
}

/** alice로 로그인 후 /settings/keymap으로 이동해 페이지 제목이 뜰 때까지 대기한다. */
async function loginAndGotoKeymap(page: Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto('/settings/keymap')
  await expect(page.getByRole('heading', { name: KEYMAP_PAGE_HEADING, exact: true })).toBeVisible()
}

/**
 * 저장 버튼을 클릭하고 PATCH 응답(200)을 기다린다 — mutation.isPending 해제만으로는 성공/실패를
 * 구분할 수 없으므로 응답 자체를 관찰한다.
 */
async function saveAndWaitForPatch(page: Page): Promise<void> {
  const patchResponse = page.waitForResponse(
    (res) => res.url().includes('/api/v1/users/me/keymap') && res.request().method() === 'PATCH',
  )
  await page.getByRole('button', { name: SAVE_BUTTON_NAME, exact: true }).click()
  const res = await patchResponse
  expect(res.status()).toBe(200)
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-PF-03 단축키 커스터마이즈 (/settings/keymap)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 재배치 → 발화
  //
  // Given  /settings/keymap 진입, create-issue 기본 키 `c`
  // When   create-issue 키를 `n`으로 재배치·저장 → SPA 내부 이동으로 포커스 이탈 → `n` 입력
  // Then   `/issues/new`로 이동한다
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 create-issue 키를 n으로 재배치·저장 후 n 입력 시 /issues/new로 이동한다', async ({ page }) => {
    await loginAndGotoKeymap(page)

    // When. create-issue 캡처 input에 포커스 후 `n` 입력
    const createIssueInput = captureInputFor(page, 'create-issue')
    await createIssueInput.click()
    await page.keyboard.press('n')
    await expect(createIssueInput).toHaveValue('n')

    // When. 저장(PATCH 200 대기)
    await saveAndWaitForPatch(page)

    // When. SPA 내부 이동(하드 리로드 없음 — keymapStore override 메모리 보존)으로 포커스 이탈
    await page.getByRole('link', { name: DASHBOARDS_LINK_NAME, exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/dashboards')

    // When. 새로 배정한 키 `n` 입력
    await page.keyboard.press('n')

    // Then. /issues/new로 이동
    await page.waitForURL((url) => url.pathname === '/issues/new')
    await expect(page.getByLabel(issueCreateStrings.summaryLabel)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 완전중복 거부
  //
  // Given  /settings/keymap 진입
  // When   search 키를 create-issue 기본값(`c`)과 동일하게 재배치
  // Then   두 행 모두 완전중복 충돌 배지가 표시되고 저장 버튼이 비활성화된다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 완전중복(search=c) 시 충돌 배지 표시 + 저장 버튼 비활성화', async ({ page }) => {
    await loginAndGotoKeymap(page)

    // When. search 캡처 input에 create-issue 기본값과 같은 `c` 입력
    const searchInput = captureInputFor(page, 'search')
    await searchInput.click()
    await page.keyboard.press('c')
    await expect(searchInput).toHaveValue('c')

    // Then. 완전중복 충돌 배지(두 action 이름 포함) 표시
    const duplicateActionNames = [
      keymapSettingsStrings.actionLabels['create-issue'],
      keymapSettingsStrings.actionLabels['search'],
    ].join(', ')
    await expect(
      page.getByText(keymapSettingsStrings.conflictDuplicate('c', duplicateActionNames), { exact: true }),
    ).toHaveCount(2)

    // Then. 저장 버튼 비활성화
    await expect(page.getByRole('button', { name: SAVE_BUTTON_NAME, exact: true })).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. dead-leader 거부
  //
  // Given  /settings/keymap 진입
  // When   goto-my-issues 키를 `g` `g`(리더 키 자기 자신)로 재배치
  // Then   dead-leader 충돌 배지가 표시되고 저장 버튼이 비활성화된다
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 dead-leader(g g) 시 충돌 배지 표시 + 저장 버튼 비활성화', async ({ page }) => {
    await loginAndGotoKeymap(page)

    // When. goto-my-issues 캡처 input에 `g` `g` 입력(리더 대기 후 자기 자신으로 continuation)
    const myIssuesInput = captureInputFor(page, 'goto-my-issues')
    await myIssuesInput.click()
    await page.keyboard.press('g')
    await page.keyboard.press('g')
    await expect(myIssuesInput).toHaveValue('g g')

    // Then. dead-leader 충돌 배지 표시
    const deadLeaderActionNames = keymapSettingsStrings.actionLabels['goto-my-issues']
    await expect(
      page.getByText(keymapSettingsStrings.conflictDeadLeader(deadLeaderActionNames), { exact: true }),
    ).toBeVisible()

    // Then. 저장 버튼 비활성화
    await expect(page.getByRole('button', { name: SAVE_BUTTON_NAME, exact: true })).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. 기본값 복원
  //
  // Given  create-issue 키를 `n`으로 재배치·저장(override 영속)
  // When   "기본값 복원" 클릭 후 재저장 → 하드 리로드로 재진입
  // Then   GET 재조회 시 create-issue 키가 기본값(`c`)으로 되돌아간다
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 기본값 복원 후 재저장 시 GET 재조회에서 기본값으로 되돌아간다', async ({ page }) => {
    await loginAndGotoKeymap(page)

    // Given. create-issue 키를 `n`으로 재배치·저장
    const createIssueInput = captureInputFor(page, 'create-issue')
    await createIssueInput.click()
    await page.keyboard.press('n')
    await saveAndWaitForPatch(page)
    await expect(createIssueInput).toHaveValue('n')

    // When. "기본값 복원" 클릭 후 재저장
    await resetButtonFor(page, 'create-issue').click()
    await expect(createIssueInput).toHaveValue('c')
    await saveAndWaitForPatch(page)

    // When. 하드 리로드로 재진입(GET 재조회 — override가 실제로 삭제됐는지 서버 상태 확인)
    await page.goto('/settings/keymap')
    await expect(page.getByRole('heading', { name: KEYMAP_PAGE_HEADING, exact: true })).toBeVisible()

    // Then. create-issue 키가 기본값 `c`로 되돌아가 있다
    await expect(captureInputFor(page, 'create-issue')).toHaveValue('c')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. 서버발 409 배너 (강제 시나리오 토글)
  //
  // Given  강제 409 시나리오 토글(localStorage, goto 이전 addInitScript로 심음) 적용
  // When   로컬 검증을 통과하는 무해한 재배치(search → `x`, 다른 action과 겹치지 않음)를 저장
  // Then   서버가 409로 거부하고, 서버 충돌 배너(conflictHeading)가 표시된다
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 강제 409 시나리오 — 로컬 검증 통과 배정도 서버가 거부하면 서버 충돌 배너가 표시된다', async ({
    page,
  }) => {
    // Given. goto 이전에 addInitScript 등록 — 첫 페이지 로드부터 플래그 적용
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_KEY_KEYMAP_FORCE_CONFLICT)

    await loginAndGotoKeymap(page)

    // When. 로컬 검증을 통과하는 무해한 변경(고유 단일 키) 후 저장 시도
    const searchInput = captureInputFor(page, 'search')
    await searchInput.click()
    await page.keyboard.press('x')
    await expect(searchInput).toHaveValue('x')
    await expect(page.getByRole('button', { name: SAVE_BUTTON_NAME, exact: true })).toBeEnabled()

    const patchResponse = page.waitForResponse(
      (res) => res.url().includes('/api/v1/users/me/keymap') && res.request().method() === 'PATCH',
    )
    await page.getByRole('button', { name: SAVE_BUTTON_NAME, exact: true }).click()
    const res = await patchResponse

    // Then. 서버가 409로 거부 + 서버 충돌 배너 표시
    expect(res.status()).toBe(409)
    await expect(page.getByText(keymapSettingsStrings.conflictHeading, { exact: true })).toBeVisible()
  })
})
