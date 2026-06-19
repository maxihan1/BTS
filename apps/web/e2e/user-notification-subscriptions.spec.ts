// FR-NT-04 D7 E2E — 사용자 알림 구독 설정 매트릭스 토글 + MSW stateful 영속 검증
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 핸들러 관통이 정석
//   - msw-mutation-stateful-refetch: 각 시나리오 시작 시 X-MSW-Reset-User-Notification-Subscriptions
//     헤더로 store 리셋 (notification-policies.spec.ts resetPolicyStore 동형)
//   - playwright-getbyrole-exact-strict-mode: aria-label exact 매칭으로 strict mode violation 회피
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 로딩 대기 후 인터랙션 (isLoading 가드)

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import {
  eventTypeLabels,
  channelLabels,
} from '../src/i18n/notification-policy-labels'
import { notificationSubscriptionStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 + CSRF 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 알림 구독 설정 페이지 URL */
const PAGE_URL = '/settings/notifications'

/** MSW store 리셋 헤더명 — user-notification-subscription-handlers.ts 1:1 */
const RESET_HEADER = 'X-MSW-Reset-User-Notification-Subscriptions'

/** MSW GET 엔드포인트 — 리셋 요청 경로 */
const API_URL = '/api/v1/users/me/notifications'

// ─────────────────────────────────────────────────────────────────────────────
// CSRF 쿠키 수동 시드 헬퍼
//
// MSW patchSubscriptionsHandler가 X-XSRF-TOKEN 헤더 존재를 검증한다.
// API 클라이언트(user-notification-subscriptions.ts)는 readXsrfToken()으로
// XSRF-TOKEN 쿠키를 읽어 헤더에 담아 보낸다.
// E2E에서 Spring 백엔드가 없으므로 page.context().addCookies()로 수동 심는다.
// (field-permissions.spec.ts seedXsrfCookie 동형 패턴)
// ─────────────────────────────────────────────────────────────────────────────

async function seedXsrfCookie(page: import('@playwright/test').Page): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'e2e-test-csrf-token',
      domain: 'localhost',
      path: '/',
      httpOnly: false,
      secure: false,
      sameSite: 'Lax',
    },
  ])
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW store 리셋
//
// 각 시나리오 시작 시 호출해 20셀 enabled=true 시드 상태를 복원한다.
// 브라우저에서 GET 요청에 X-MSW-Reset-User-Notification-Subscriptions:true 헤더를 담으면
// getSubscriptionsHandler가 resetUserNotificationSubscriptionStore()를 실행한 뒤 응답한다.
// (notification-policies.spec.ts:57-63 resetPolicyStore 패턴 동형)
// ─────────────────────────────────────────────────────────────────────────────

async function resetSubscriptionStore(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async ([url, header]: [string, string]) => {
    await fetch(url, { headers: { [header]: 'true' } })
  }, [API_URL, RESET_HEADER])
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 매트릭스 테이블 로딩 완료 대기
//
// isLoading 중 <p>설정을 불러오는 중입니다.</p>가 표시되다가 table이 마운트된다.
// table role로 대기 후 인터랙션하면 타이밍 문제 없음.
// ─────────────────────────────────────────────────────────────────────────────

async function waitForMatrix(page: import('@playwright/test').Page): Promise<void> {
  await expect(page.getByRole('table')).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 셀 aria-label 생성
//
// SubscriptionCell이 렌더하는 aria-label 패턴:
//   `${resolvedEventLabel} ${channelLabel} 알림 ${enabled ? '켜짐' : '꺼짐'}`
// issue.mentioned는 ko.ts eventIssueMentioned 라벨 사용, 나머지는 eventTypeLabels.
// ─────────────────────────────────────────────────────────────────────────────

function cellAriaLabel(eventType: string, channel: string, enabled: boolean): string {
  const eventLabel =
    eventType === 'issue.mentioned'
      ? notificationSubscriptionStrings.eventIssueMentioned
      : (eventTypeLabels[eventType] ?? eventType)
  const channelLabel = channelLabels[channel] ?? channel
  return `${eventLabel} ${channelLabel} 알림 ${enabled ? '켜짐' : '꺼짐'}`
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 매트릭스 표시: 이벤트 행 + 채널 열(인앱/이메일) 렌더 확인
//
// Given   alice 로그인 → /settings/notifications 진입
// When    페이지 로딩 완료
// Then    페이지 제목 "알림 구독 설정" 표시
//         채널 헤더 "인앱 알림" + "이메일" 표시
//         이벤트 행 "이슈 댓글 작성" 표시
//         테이블 셀 체크박스(이슈 댓글 × 이메일) 기본값 켜짐 상태
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 매트릭스 표시 — 채널 2열 + 이벤트 행 확인 (FR-NT-04)', () => {
  test('Given alice 로그인 When 페이지 진입 Then 매트릭스 표시 + 채널 2열 확인', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await resetSubscriptionStore(page)
    await page.reload()

    // Then. 페이지 제목 표시
    await expect(
      page.getByRole('heading', {
        name: notificationSubscriptionStrings.pageTitle,
        exact: true,
      }),
    ).toBeVisible()

    // Then. 매트릭스 테이블 로딩 완료 대기
    await waitForMatrix(page)

    // Then. 채널 헤더 "인앱 알림" 표시 (채널 열 헤더 — th 내부)
    // playwright-getbyrole-exact-strict-mode: thead로 한정해 tbody 셀과 중복 회피
    const thead = page.locator('thead')
    await expect(thead.getByText(channelLabels['IN_APP']!, { exact: true })).toBeVisible()
    await expect(thead.getByText(channelLabels['EMAIL']!, { exact: true })).toBeVisible()

    // Then. 이벤트 행 "이슈 댓글 작성" 표시 (tbody — eventTypeLabels['issue.commented'])
    const tbody = page.locator('tbody')
    await expect(
      tbody.getByText(eventTypeLabels['issue.commented']!, { exact: true }),
    ).toBeVisible()

    // Then. 이슈 댓글 × 이메일 셀 기본값 켜짐(checked) 상태
    const commentEmailCell = page.getByRole('checkbox', {
      name: cellAriaLabel('issue.commented', 'EMAIL', true),
      exact: true,
    })
    await expect(commentEmailCell).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 토글(끄기) + SPA 재진입 후 영속
//
// Given   alice 로그인 → /settings/notifications 진입 (store 시드: 20셀 enabled=true)
//         "이슈 댓글 작성 × 이메일" 셀이 켜짐 상태
// When    해당 셀 체크박스 클릭 → enabled=false PATCH 발송
// Then    refetch 완료 후 해당 셀 꺼짐 상태 반영
//         (invalidate-only 패턴 — setQueryData 없이 refetch로 반영)
// When    SPA 내부 네비게이션 → /settings/sessions 이동 (pushState+popstate, MSW 워커 유지)
// Then    세션 설정 페이지 렌더
// When    SPA 내부 네비게이션 → /settings/notifications 복귀 (pushState+popstate)
// Then    매트릭스 테이블 재렌더 — "이슈 댓글 × 이메일" 셀 여전히 꺼짐 (MSW store 영속)
//         "이슈 댓글 × 인앱" 셀은 여전히 켜짐 (부분 변경 격리)
//         MSW store 직접 fetch 단언도 통과 (enabled=false 영속 확인)
//
// SPA 내부 전환 방식.
//   window.history.pushState + PopStateEvent('popstate') 조합.
//   TanStack Router는 popstate 이벤트를 감청해 URL에 맞는 컴포넌트를 마운트한다.
//   full navigation(page.goto)과 달리 브라우저 컨텍스트를 유지하므로
//   MSW ServiceWorker 모듈/store가 buildSeedStore()로 리셋되지 않는다.
//   (custom-fields.spec.ts:327-333 / issue-links.spec.ts:75-78 선례 동형)
// ─────────────────────────────────────────────────────────────────────────────

/** SPA 내부 전환 헬퍼 — pushState + popstate로 MSW store를 보존한 채 라우트 변경 */
async function spaNavigate(page: import('@playwright/test').Page, url: string): Promise<void> {
  await page.evaluate((targetUrl: string) => {
    window.history.pushState({}, '', targetUrl)
    window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
  }, url)
}

test.describe('S4 토글(끄기) + SPA 재진입 후 영속 (FR-NT-04)', () => {
  test('Given enabled=true 셀 When 체크박스 클릭 Then 꺼짐 + SPA 재진입 후에도 꺼짐 유지', async ({ page }) => {
    // Given. alice 로그인 → CSRF 쿠키 시드 → 페이지 진입 + store 시드 복원
    await loginAsAlice(page)
    await seedXsrfCookie(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await resetSubscriptionStore(page)
    await page.reload()

    // Given. 매트릭스 로딩 완료 대기
    await waitForMatrix(page)

    // Given. "이슈 댓글 작성 × 이메일" 셀 켜짐 상태 확인
    const targetCellOn = page.getByRole('checkbox', {
      name: cellAriaLabel('issue.commented', 'EMAIL', true),
      exact: true,
    })
    await expect(targetCellOn).toBeVisible()
    await expect(targetCellOn).toBeChecked()

    // When. 셀 체크박스 클릭 → enabled=false PATCH 발송
    await targetCellOn.click()

    // Then. invalidate-only 패턴 — refetch 완료 후 꺼짐 aria-label 체크박스 출현 대기
    // (mutation onSuccess → invalidateQueries → GET refetch → aria-label 갱신)
    const targetCellOff = page.getByRole('checkbox', {
      name: cellAriaLabel('issue.commented', 'EMAIL', false),
      exact: true,
    })
    await expect(targetCellOff).toBeVisible()
    await expect(targetCellOff).not.toBeChecked()

    // When. SPA 내부 네비게이션 → /settings/sessions 이동
    // pushState + popstate 조합으로 MSW 워커/store를 보존한 채 다른 settings 라우트로 전환
    await spaNavigate(page, '/settings/sessions')

    // Then. 세션 설정 페이지 렌더 확인 — /settings/sessions 라우트의 SessionList가 마운트됨
    // NotificationSubscriptionMatrix가 언마운트되어 컴포넌트 상태 초기화 검증 준비 완료
    await expect(page).toHaveURL(/settings\/sessions/)

    // When. SPA 내부 네비게이션 → /settings/notifications 복귀
    await spaNavigate(page, PAGE_URL)

    // Then. 알림 구독 설정 페이지 URL 확인
    await expect(page).toHaveURL(/settings\/notifications/)

    // Then. 매트릭스 재렌더 완료 대기
    await waitForMatrix(page)

    // Then. "이슈 댓글 × 이메일" 셀이 여전히 꺼짐 상태 (MSW store 영속 + 컴포넌트 재마운트 후)
    const targetCellOffAfterReentry = page.getByRole('checkbox', {
      name: cellAriaLabel('issue.commented', 'EMAIL', false),
      exact: true,
    })
    await expect(targetCellOffAfterReentry).toBeVisible()
    await expect(targetCellOffAfterReentry).not.toBeChecked()

    // Then. "이슈 댓글 × 인앱" 셀은 여전히 켜짐 (부분 변경 격리 — 다른 셀 영향 없음)
    const commentInAppCellOn = page.getByRole('checkbox', {
      name: cellAriaLabel('issue.commented', 'IN_APP', true),
      exact: true,
    })
    await expect(commentInAppCellOn).toBeVisible()
    await expect(commentInAppCellOn).toBeChecked()

    // Then. MSW store 직접 fetch 단언 — page.evaluate로 GET 재호출해 store 영속 이중 확인
    // (msw-mutation-stateful-refetch 교훈: store 영속이 가짜그린 원인이 될 수 있어 명시 검증)
    type SubscriptionEntry = { eventType: string; channel: string; enabled: boolean }
    const storeState = await page.evaluate(async (url: string): Promise<SubscriptionEntry[]> => {
      const res = await fetch(url)
      const json = (await res.json()) as { data: { subscriptions: SubscriptionEntry[] } }
      return json.data.subscriptions
    }, API_URL)

    const commentEmailEntry = storeState.find(
      (e) => e.eventType === 'issue.commented' && e.channel === 'EMAIL',
    )
    expect(commentEmailEntry).toBeDefined()
    expect(commentEmailEntry?.enabled).toBe(false)

    const commentInAppEntry = storeState.find(
      (e) => e.eventType === 'issue.commented' && e.channel === 'IN_APP',
    )
    expect(commentInAppEntry).toBeDefined()
    expect(commentInAppEntry?.enabled).toBe(true)
  })
})
