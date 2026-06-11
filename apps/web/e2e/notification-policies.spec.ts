// FR-NT-01 D7 E2E — 관리자 알림 정책 페이지 (SYSTEM_ADMIN 전용)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 버튼은 aria-label/컨테이너 한정
//   - msw-mutation-stateful-refetch: 각 시나리오 시작 시 X-MSW-Reset-Notification-Policies 헤더로 store 리셋
//   - msw-derived-behavior-shared-store: MSW store는 브라우저 내 공유 store에서 읽기
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - worktree-stale-base-rebase-and-e2e-msw-traps: Select 드롭다운 로딩 대기 후 옵션 클릭

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import {
  eventTypeLabels,
  recipientRoleLabels,
  channelLabels,
  notificationPolicyLabels,
} from '../src/i18n/notification-policy-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY — audit-logs.spec.ts 동형 */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

/** 알림 정책 페이지 URL */
const PAGE_URL = '/admin/notification-policies'

/** MSW store 리셋 헤더명 — notification-policy-handlers.ts 1:1 */
const RESET_HEADER = 'X-MSW-Reset-Notification-Policies'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice 로그인
//
// addInitScript 로 LS_IS_SYSTEM_ADMIN='true' 를 먼저 심은 뒤 로그인한다.
// 로그인 중 whoami 응답이 isSystemAdmin:true 로 반환되어 requireSystemAdmin 가드 통과.
// (audit-logs.spec.ts:27-32 loginAsSystemAdmin 동형)
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW store 리셋
//
// 각 시나리오 시작 시 호출해 시드 상태(4건)를 복원한다.
// 브라우저에서 GET 요청에 X-MSW-Reset-Notification-Policies:true 헤더를 담으면
// listPoliciesHandler 가 resetNotificationPolicyStore() 를 실행한 뒤 응답한다.
// (version-release-notes.spec.ts:77-83 resetVersionStore 패턴 동형)
// ─────────────────────────────────────────────────────────────────────────────

async function resetPolicyStore(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async (header: string) => {
    await fetch('/api/v1/notification-policies', {
      headers: { [header]: 'true' },
    })
  }, RESET_HEADER)
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — admin 메뉴 노출 + 알림 정책 페이지 진입 + 시드 목록 표시
//
// Given   SYSTEM_ADMIN alice 로 로그인
// When    Header의 "알림 정책" 링크 클릭
// Then    URL /admin/notification-policies 진입
//         페이지 제목 "알림 정책" 표시
//         시드 정책 4건 행 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 admin 메뉴 노출 + 알림 정책 페이지 진입 (FR-NT-01)', () => {
  test('Given SYSTEM_ADMIN 로그인 When 알림 정책 링크 클릭 Then 시드 목록 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)
    await resetPolicyStore(page)

    // Then. Header "관리 메뉴" nav 노출 확인
    const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })
    await expect(adminNav).toBeVisible()

    // Then. nav 내부에 "알림 정책" 링크 노출
    // playwright-getbyrole-exact-strict-mode: nav 컨테이너 내부로 한정해 strict mode violation 회피
    const notifPolicyLink = adminNav.getByRole('link', { name: notificationPolicyLabels.page.heading, exact: true })
    await expect(notifPolicyLink).toBeVisible()

    // When. "알림 정책" 링크 클릭 → SPA 내부 이동
    await notifPolicyLink.click()
    await page.waitForURL(`**${PAGE_URL}`)
    expect(new URL(page.url()).pathname).toBe(PAGE_URL)

    // Then. 페이지 제목 표시
    await expect(
      page.getByRole('heading', { name: notificationPolicyLabels.page.heading, exact: true }),
    ).toBeVisible()

    // Then. 시드 정책 4건 행 표시 — 이벤트 유형 라벨 확인으로 검증
    const tbody = page.locator('tbody')
    const rows = tbody.locator('tr')
    await expect(rows.first()).toBeVisible()
    const rowCount = await rows.count()
    expect(rowCount).toBe(4)

    // 시드 행들의 이벤트 유형 라벨이 렌더됐는지 확인
    await expect(page.getByText(eventTypeLabels['issue.created']!, { exact: true }).first()).toBeVisible()
    await expect(page.getByText(eventTypeLabels['issue.assigned']!, { exact: true }).first()).toBeVisible()
    await expect(page.getByText(eventTypeLabels['issue.transitioned']!, { exact: true }).first()).toBeVisible()
    await expect(page.getByText(eventTypeLabels['sprint.started']!, { exact: true }).first()).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 새 정책 생성 → 목록에 새 행 추가
//
// Given   SYSTEM_ADMIN alice → /admin/notification-policies 진입 (시드 4건)
// When    폼에서 이벤트(이슈 기한 임박) / 수신자(담당자) / 채널(인앱 알림) 선택 후 정책 추가
// Then    목록에 5번째 행 추가 (이슈 기한 임박 + 담당자 + 인앱 알림)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 새 정책 생성 → 목록 행 추가 (FR-NT-01)', () => {
  test('Given 정책 페이지 When 폼 선택 후 정책 추가 Then 목록에 새 행 추가', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 페이지 직접 진입 + 시드 복원
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await resetPolicyStore(page)
    await page.reload()

    // Given. 페이지 로딩 완료 + 시드 4건 확인
    await expect(
      page.getByRole('heading', { name: notificationPolicyLabels.page.heading, exact: true }),
    ).toBeVisible()
    const tbody = page.locator('tbody')
    await expect(tbody.locator('tr').first()).toBeVisible()
    expect(await tbody.locator('tr').count()).toBe(4)

    // When. 이벤트 유형 Select 열기 — aria-label로 한정
    const eventTrigger = page.getByRole('combobox', {
      name: notificationPolicyLabels.form.eventType,
      exact: true,
    })
    await expect(eventTrigger).toBeVisible()
    await eventTrigger.click()

    // When. "이슈 기한 임박" 선택
    const eventOption = page.getByRole('option', {
      name: eventTypeLabels['issue.due_soon']!,
      exact: true,
    })
    await expect(eventOption).toBeVisible()
    await eventOption.click()

    // When. 수신자 역할 Select 열기
    const recipientTrigger = page.getByRole('combobox', {
      name: notificationPolicyLabels.form.recipientRole,
      exact: true,
    })
    await expect(recipientTrigger).toBeVisible()
    await recipientTrigger.click()

    // When. "담당자" 선택
    const recipientOption = page.getByRole('option', {
      name: recipientRoleLabels['ASSIGNEE']!,
      exact: true,
    })
    await expect(recipientOption).toBeVisible()
    await recipientOption.click()

    // When. 채널 Select 열기
    const channelTrigger = page.getByRole('combobox', {
      name: notificationPolicyLabels.form.channel,
      exact: true,
    })
    await expect(channelTrigger).toBeVisible()
    await channelTrigger.click()

    // When. "인앱 알림" 선택
    const channelOption = page.getByRole('option', {
      name: channelLabels['IN_APP']!,
      exact: true,
    })
    await expect(channelOption).toBeVisible()
    await channelOption.click()

    // When. "정책 추가" 버튼 클릭
    await page.getByRole('button', {
      name: notificationPolicyLabels.form.addButton,
      exact: true,
    }).click()

    // Then. 목록 행 5건으로 증가
    await expect(tbody.locator('tr').nth(4)).toBeVisible()
    expect(await tbody.locator('tr').count()).toBe(5)

    // Then. 새 행 이벤트 유형 라벨이 tbody에 표시 확인
    // tbody로 한정해 Select value와 중복 매칭(strict mode violation) 회피
    await expect(
      tbody.getByText(eventTypeLabels['issue.due_soon']!, { exact: true }),
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 행 활성 토글 → 상태 변경 반영
//
// Given   SYSTEM_ADMIN alice → /admin/notification-policies 진입
//         시드 p1 (이슈 생성/보고자/이메일, enabled=true)
// When    p1 행의 "이슈 생성 정책 비활성화" 버튼 클릭
// Then    해당 행 "비활성" 상태로 변경
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 활성 토글 → 상태 변경 (FR-NT-01)', () => {
  test('Given enabled=true 행 When 비활성화 버튼 클릭 Then 비활성 상태로 변경', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 페이지 진입 + 시드 복원
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await resetPolicyStore(page)
    await page.reload()

    // Given. 페이지 로딩 완료 + 목록 표시
    await expect(
      page.getByRole('heading', { name: notificationPolicyLabels.page.heading, exact: true }),
    ).toBeVisible()
    const tbody = page.locator('tbody')
    await expect(tbody.locator('tr').first()).toBeVisible()

    // p1 행 — 이슈 생성/보고자/이메일 (enabled=true) → aria-label로 한정
    // NotificationPolicyRow: toggleAriaLabel = `${eventLabel} 정책 비활성화` (enabled=true일 때)
    const p1EventLabel = eventTypeLabels['issue.created']!
    const disableButton = page.getByRole('button', {
      name: `${p1EventLabel} 정책 비활성화`,
      exact: true,
    })
    await expect(disableButton).toBeVisible()

    // When. 비활성화 버튼 클릭
    await disableButton.click()

    // Then. 해당 행에서 "비활성" 텍스트 표시
    // row aria-label=p1EventLabel로 행을 한정 후 확인
    const p1Row = page.getByRole('row', { name: p1EventLabel, exact: true })
    await expect(p1Row.getByText('비활성', { exact: true })).toBeVisible()

    // 비활성화됐으므로 토글 버튼이 이제 "활성화"로 변경
    await expect(
      page.getByRole('button', { name: `${p1EventLabel} 정책 활성화`, exact: true }),
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 행 삭제(인라인 확인) → 행 제거
//
// Given   SYSTEM_ADMIN alice → /admin/notification-policies 진입 (시드 4건)
//         시드 p4 (스프린트 시작/프로젝트 관리자/Slack, enabled=true)
// When    p4 행의 "삭제" 버튼 → 인라인 확인 "확인" 클릭
// Then    행 제거 → 3건으로 감소
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 행 삭제 + 인라인 확인 → 행 제거 (FR-NT-01)', () => {
  test('Given 정책 행 When 삭제 확인 Then 행 제거', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 페이지 진입 + 시드 복원
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await resetPolicyStore(page)
    await page.reload()

    // Given. 페이지 로딩 완료 + 4건 확인
    await expect(
      page.getByRole('heading', { name: notificationPolicyLabels.page.heading, exact: true }),
    ).toBeVisible()
    const tbody = page.locator('tbody')
    await expect(tbody.locator('tr').first()).toBeVisible()
    expect(await tbody.locator('tr').count()).toBe(4)

    // p4 행 — 스프린트 시작 정책 삭제
    // NotificationPolicyRow: 삭제 버튼 aria-label = `${eventLabel} 정책 삭제`
    const p4EventLabel = eventTypeLabels['sprint.started']!
    const deleteButton = page.getByRole('button', {
      name: `${p4EventLabel} 정책 삭제`,
      exact: true,
    })
    await expect(deleteButton).toBeVisible()

    // When. 삭제 버튼 클릭 → 인라인 확인 상태로 전환
    await deleteButton.click()

    // 인라인 확인 버튼: aria-label = `${eventLabel} 정책 삭제 확인`
    const confirmButton = page.getByRole('button', {
      name: `${p4EventLabel} 정책 삭제 확인`,
      exact: true,
    })
    await expect(confirmButton).toBeVisible()

    // 취소 버튼도 표시 확인
    const cancelButton = page.getByRole('button', {
      name: `${p4EventLabel} 정책 삭제 취소`,
      exact: true,
    })
    await expect(cancelButton).toBeVisible()

    // When. 확인 클릭 → onDelete 호출 → 목록 refetch
    await confirmButton.click()

    // Then. 행 3건으로 감소
    await expect(tbody.locator('tr').nth(2)).toBeVisible()
    // 4번째 행 없어야 함
    await expect(tbody.locator('tr').nth(3)).not.toBeVisible()
    expect(await tbody.locator('tr').count()).toBe(3)

    // Then. 삭제된 p4 이벤트 라벨 더 이상 테이블에 없음
    await expect(
      tbody.getByText(p4EventLabel, { exact: true }),
    ).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 비관리자: 관리 메뉴 미노출 + /admin/notification-policies 진입 차단
//
// Given   일반 사용자 alice 로 로그인 (isSystemAdmin:false, 기본 fixture)
// When 1  Header 확인
// Then 1  "관리 메뉴" nav 미노출 + "알림 정책" 링크 미노출
// When 2  /admin/notification-policies 직접 goto
// Then 2  requireSystemAdmin 가드 → /dashboard redirect
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 비관리자 미노출 + 차단 (FR-NT-01)', () => {
  test('Given 일반 사용자 로그인 When 관리 메뉴 확인 Then nav 미노출 + 직접 접근 시 redirect', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false (기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // Then (When 1). Header "관리 메뉴" nav 미노출
    await expect(page.getByRole('navigation', { name: '관리 메뉴' })).not.toBeVisible()

    // Then (When 1). "알림 정책" 링크 미노출 (nav 없으므로 DOM에도 없음)
    await expect(
      page.getByRole('link', { name: notificationPolicyLabels.page.heading, exact: true }),
    ).not.toBeVisible()

    // When 2. /admin/notification-policies 직접 goto
    await page.goto(PAGE_URL)

    // Then 2. requireSystemAdmin 가드 → /dashboard redirect
    await page.waitForURL('**/dashboard')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})
