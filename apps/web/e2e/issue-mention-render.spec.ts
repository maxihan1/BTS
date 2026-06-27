// FR-MN-01 D7 E2E — 본문 @멘션 강조 표시 + 멘션→Inbox 도착 시나리오
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: .mention 셀렉터는 description-preview-content 컨테이너로 한정
//   - msw-mutation-stateful-refetch: MSW inboxStore stateful — 멘션 저장 후 SPA 이동으로 검증
//   - msw-derived-behavior-shared-store-e2e: PATCH 시 멘션 파생 알림은 공유 inboxStore 경유
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - e2e-fixture-whoami-userid-alignment: bob userId=00000000-0000-4000-8000-000000000002 (auth-fixtures.bobUser.userId)
//   - worktree-stale-base-rebase-and-e2e-msw-traps: MSW 공유 inboxStore는 SPA 내부이동으로 검증
//     (page.reload() 금지 — MSW store 리셋)
//   - msw-derived-behavior-shared-store-e2e: CONCERN-E2 — descriptionHtml 형식은 백엔드 마크업 미러
//     (<span class="mention">@alice</span>)
//
// MSW 핵심 사항.
//   - GET /api/v1/issues/ATLAS-MENTION → renderDescriptionHtml 이 description에서 .mention 스팬 생성 (GREEN에서 활성)
//   - PATCH /api/v1/issues/ATLAS-1 (description 포함) → 멘션 추출 → inboxStore[bob.userId] 에 ISSUE_MENTIONED 추가 (GREEN에서 활성)
//   - 각 테스트는 Playwright 기본 새 컨텍스트(새 ServiceWorker) → MSW 인메모리 store가 초기화 상태로 시작
//   - bob의 인박스는 기본 시드 없음(alice만 자동 시드) — S4 분별 시드 검증 근거
//
// S3/S4 사용자 전환 아키텍처 노트.
//   MSW v2 핸들러는 page 스레드(메인 스레드)에서 실행되어 inboxStore가 page 레벨 메모리에 있다.
//   page.goto()가 전체 페이지 로드를 일으키면 page JS 컨텍스트 전체가 파괴·재초기화되어
//   inboxStore가 리셋된다(alice 기본 시드만 남음). 따라서 alice 세션을 유지한 채
//   bob의 inbox API를 page.evaluate로 직접 호출해 파생을 검증한다.
//   bob 토큰('mock-access-token-bob')을 Authorization 헤더에 직접 전달하면
//   MSW inbox-handlers.ts가 bob의 userId를 도출해 bob의 inbox를 반환한다.
//
// 분담 (ground-truth).
//   - 백엔드 멘션→알림→Inbox 파이프라인의 진실은 NotificationDeliveryEndToEndIntegrationTest 등이 담당.
//   - 이 E2E는 프론트 관점 UI만 검증 — MSW가 백엔드 파이프라인을 시뮬.

import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** FR-MN-01 D7 E2E 전용 멘션 강조 검증 이슈 키 (ATLAS-MENTION fixture) */
const MENTION_ISSUE_KEY = 'ATLAS-MENTION'

/** 멘션 PATCH 대상 이슈 키 — S3/S4 본문 저장 */
const PATCH_ISSUE_KEY = 'ATLAS-1'

/**
 * bob의 MSW mock access token.
 * auth-fixtures.ts mockAccessToken('bob') = 'mock-access-token-bob'.
 * inbox-handlers.ts resolveUserIdFromRequest이 이 토큰에서 bobUserId를 도출한다.
 */
const BOB_MOCK_TOKEN = 'mock-access-token-bob'

/**
 * NotificationWorker.kt buildTitleBody — ISSUE_MENTIONED 알림 제목 형식 미러.
 * 백엔드: `"$issueRef 에서 멘션되었습니다"` (NotificationWorker.kt:357)
 */
const MENTION_INBOX_TITLE = `${PATCH_ISSUE_KEY} 에서 멘션되었습니다`

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세의 본문 편집 모드(Write 탭)로 진입해 텍스트를 입력하고 저장한다.
 * issue-body-meta.spec.ts E1 진입 패턴 미러 (strict mode 주석 포함).
 *
 * @param page Playwright Page 객체
 * @param text 입력할 본문 텍스트
 */
async function editAndSaveDescription(
  page: import('@playwright/test').Page,
  text: string,
): Promise<void> {
  // 본문 편집 버튼 클릭 — description=null이든 설정된 상태든 항상 노출
  const editButton = page.getByRole('button', {
    name: i18nLabels.issueDetail.descriptionEditButton,
  })
  await expect(editButton).toBeVisible()
  await editButton.click()

  // Write 탭 활성화
  const writeTab = page.getByRole('tab', {
    name: i18nLabels.issueDetail.descriptionWriteTab,
  })
  await writeTab.click()

  // textarea 입력 — aria-label '본문 편집'으로 strict mode 구별
  const textarea = page.getByRole('textbox', {
    name: i18nLabels.issueDetail.descriptionEditButton,
  })
  await textarea.fill(text)

  // 저장 — tablist 직접 자식 div 컨테이너로 저장 버튼 한정 (issue-body-meta E1 패턴)
  const descriptionEditor = page.locator('div:has(> [role="tablist"])')
  const saveBtn = descriptionEditor.getByRole('button', {
    name: i18nLabels.issueDetail.descriptionSaveButton,
  })
  await saveBtn.click()
  // PATCH 완료 대기 — 저장 버튼이 다시 활성화되면 mutation 완료
  await expect(saveBtn).toBeEnabled()
}

/**
 * alice 세션에서 bob의 inbox 항목을 API 직접 호출로 조회한다.
 *
 * 목적.
 *   MSW v2 핸들러는 page 스레드에서 실행되어 page.goto()가 inboxStore를 리셋한다.
 *   사용자 전환 없이 bob 토큰을 Authorization 헤더에 직접 전달해 bob의 inbox를 조회한다.
 *   inbox-handlers.ts의 resolveUserIdFromRequest가 'mock-access-token-bob'에서
 *   bob userId=00000000-0000-4000-8000-000000000002 를 도출해 bob의 항목을 반환한다.
 *
 * @param page Playwright Page 객체 (alice 세션 유지 중)
 * @returns bob의 inbox 항목 배열 (ALL 탭 기준)
 */
async function fetchBobInboxDirect(
  page: import('@playwright/test').Page,
): Promise<Array<{ eventType: string; issueKey: string | null; title: string }>> {
  return page.evaluate(async (token: string) => {
    const res = await fetch('/api/v1/users/me/inbox', {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return []
    const data = (await res.json()) as {
      content: Array<{ eventType: string; issueKey: string | null; title: string }>
    }
    return data.content
  }, BOB_MOCK_TOKEN)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-MN-01 본문 @멘션 강조 + Inbox 도착 (D7)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1: 멘션 강조 표시
  //
  // Given  ATLAS-MENTION 이슈 (description: '@alice 확인 부탁드립니다. ...')
  //        MSW GET handler → renderDescriptionHtml → <span class="mention">@alice</span>
  // When   이슈 상세 페이지를 연다
  // Then   description-preview-content 내 .mention 요소가 표시되고 텍스트는 '@alice'
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 멘션 강조 — @alice 텍스트가 .mention 클래스로 표시됨', async ({ page }) => {
    await loginAsAlice(page)

    // When. ATLAS-MENTION 이슈 상세 진입
    await page.goto(`/issues/${MENTION_ISSUE_KEY}`)

    // Given. description 컨테이너 확인 (non-null description → ReadMode 즉시 노출)
    const descPreview = page.getByTestId('description-preview-content')
    await expect(descPreview).toBeVisible()

    // Then. .mention 요소가 보이고 텍스트 '@alice' 확인
    // (description-preview-content 한정 — 다른 영역 .mention과 strict mode 충돌 방지)
    const mention = descPreview.locator('.mention')
    await expect(mention).toBeVisible()
    await expect(mention).toHaveText('@alice')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2: 코드스팬·이메일 제외
  //
  // Given  ATLAS-MENTION 이슈 (description에 `@code` 코드스팬 + user@example.com 이메일 포함)
  //        MSW renderDescriptionHtml → 코드스팬·이메일 @ 는 <span class="mention"> 미적용
  // When   이슈 상세 페이지를 연다
  // Then   description-preview-content 내 .mention 개수 정확히 1 (코드·이메일 제외 검증)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 코드스팬·이메일 제외 — .mention 개수 정확히 1 (@alice만)', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto(`/issues/${MENTION_ISSUE_KEY}`)

    const descPreview = page.getByTestId('description-preview-content')
    await expect(descPreview).toBeVisible()

    // Then. .mention 개수 = 정확히 1 (@alice만 강조, `@code`·user@example.com 제외)
    // 코드스팬·이메일이 잘못 강조되면 2+ 개 → fail (분별 검증)
    const mentions = descPreview.locator('.mention')
    await expect(mentions).toHaveCount(1)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3: 멘션→Inbox 파생 검증 (page.evaluate API 직접 호출)
  //
  // Given  alice로 로그인, ATLAS-1 본문에 '@bob 확인해주세요' 저장
  //        MSW PATCH handler → '@bob' 추출 → inboxStore[bob.userId]에 ISSUE_MENTIONED 추가
  // When   alice 세션 유지한 채 bob 토큰으로 GET /users/me/inbox 직접 호출
  // Then   eventType='ISSUE_MENTIONED', issueKey='ATLAS-1', title='ATLAS-1 에서 멘션되었습니다' 항목 포함
  //
  // 사용자 전환 불가 이유 (MSW 아키텍처).
  //   page.goto('/login')이 전체 페이지 로드 → page 스레드 재초기화 → inboxStore 리셋.
  //   대신 alice 세션에서 bob 토큰으로 API를 직접 호출한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 멘션→Inbox 파생 — @bob 저장 후 bob inbox API 응답에 ISSUE_MENTIONED 포함', async ({ page }) => {
    // Given. alice로 로그인
    await loginAsAlice(page)

    // Given. ATLAS-1 이슈 본문에 @bob 멘션 저장
    await page.goto(`/issues/${PATCH_ISSUE_KEY}`)
    await editAndSaveDescription(page, '@bob 확인해주세요')

    // When. alice 세션 유지 + bob 토큰으로 GET /users/me/inbox 직접 호출
    // (page.goto로 bob 전환 시 page JS 컨텍스트 재초기화 → inboxStore 리셋되므로 불가)
    const bobItems = await fetchBobInboxDirect(page)

    // Then. bob의 inbox에 ISSUE_MENTIONED 항목 확인
    const mentioned = bobItems.find(
      (item) => item.eventType === 'ISSUE_MENTIONED' && item.issueKey === PATCH_ISSUE_KEY,
    )
    expect(mentioned).toBeDefined()
    expect(mentioned?.title).toBe(MENTION_INBOX_TITLE)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4: 분별 시드 — 멘션 없는 저장은 Inbox 무변화
  //
  // Given  alice로 로그인, ATLAS-1 본문에 '@' 없는 일반 텍스트 저장
  //        MSW PATCH handler → 멘션 미감지 → inboxStore[bob.userId] 변경 없음
  // When   alice 세션 유지한 채 bob 토큰으로 GET /users/me/inbox 직접 호출
  // Then   bob의 inbox 비어있음 (기본 시드 없음 + 멘션 알림 미생성)
  //
  // 분별 시드 목적.
  //   S3가 GREEN을 보장하는 것과 달리, S4는 GREEN 구현이 @ 없을 때 알림을 추가하지 않음을 검증
  //   (가짜그린 방지 — MSW가 항상 알림 추가 시 이 테스트가 실패).
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 분별 시드 — 멘션 없는 저장 시 bob inbox API 응답 비어있음', async ({ page }) => {
    // Given. alice로 로그인
    await loginAsAlice(page)

    // Given. ATLAS-1 이슈 본문에 @ 없는 일반 텍스트 저장
    await page.goto(`/issues/${PATCH_ISSUE_KEY}`)
    await editAndSaveDescription(page, '일반 본문 텍스트입니다. 멘션 없음.')

    // When. alice 세션 유지 + bob 토큰으로 GET /users/me/inbox 직접 호출
    const bobItems = await fetchBobInboxDirect(page)

    // Then. bob의 inbox 비어있음 (기본 시드 없음 + 멘션 알림 미생성)
    // bob은 기본 시드가 없고 PATCH에서 멘션이 없으므로 항목이 추가되지 않음
    expect(bobItems).toHaveLength(0)
    // 추가 검증: ISSUE_MENTIONED 이벤트 완전 부재
    const mentioned = bobItems.find((item) => item.eventType === 'ISSUE_MENTIONED')
    expect(mentioned).toBeUndefined()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5: 후행 마침표 제외 — @bob. 에서 'bob' 만 추출
  //
  // Given  alice로 로그인, ATLAS-1 본문에 '@bob.' 저장
  //        새 regex: [A-Za-z0-9] 종료 조건 → 마침표 앞 'bob' 만 캡처
  //        (구 regex [a-zA-Z0-9_.-]+ 는 'bob.' 전체 캡처 → AUTH_USERS['bob.'] 없음 → 알림 누락)
  // When   alice 세션 유지한 채 bob 토큰으로 GET /users/me/inbox 직접 호출
  // Then   bob inbox에 ISSUE_MENTIONED 항목 존재 — 마침표를 제거하고 bob 이 올바르게 멘션됨
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 후행 마침표 제외 — @bob. 에서 bob 추출, bob inbox에 알림 도착', async ({ page }) => {
    // Given. alice로 로그인
    await loginAsAlice(page)

    // Given. ATLAS-1 이슈 본문에 '@bob.' (후행 마침표 포함) 저장
    await page.goto(`/issues/${PATCH_ISSUE_KEY}`)
    await editAndSaveDescription(page, '@bob.')

    // When. alice 세션 유지 + bob 토큰으로 GET /users/me/inbox 직접 호출
    const bobItems = await fetchBobInboxDirect(page)

    // Then. bob의 inbox에 ISSUE_MENTIONED 항목 — 'bob' 이 올바르게 추출됨
    const mentioned = bobItems.find(
      (item) => item.eventType === 'ISSUE_MENTIONED' && item.issueKey === PATCH_ISSUE_KEY,
    )
    expect(mentioned).toBeDefined()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6: 선행 구두점 차단 — foo.@bob / foo-@bob 은 멘션 아님
  //
  // Given  alice로 로그인, ATLAS-1 본문에 'foo.@bob foo-@bob' 저장
  //        새 regex lookbehind: (?<![A-Za-z0-9._@-]) — . 과 - 가 앞에 있으면 비매칭
  //        (구 regex (?<![\w@]) 는 . 과 - 를 lookbehind에서 누락 → 'bob' 잘못 추출 → 오알림)
  // When   alice 세션 유지한 채 bob 토큰으로 GET /users/me/inbox 직접 호출
  // Then   bob inbox 비어있음 — .@ / -@ 패턴은 백엔드 MentionParser와 동일하게 비매칭
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 선행 구두점 차단 — foo.@bob / foo-@bob 은 멘션 아님, bob inbox 비어있음', async ({ page }) => {
    // Given. alice로 로그인
    await loginAsAlice(page)

    // Given. ATLAS-1 본문에 선행 구두점이 있는 패턴 저장 (.@ 와 -@ 두 케이스 동시 검증)
    await page.goto(`/issues/${PATCH_ISSUE_KEY}`)
    await editAndSaveDescription(page, 'foo.@bob foo-@bob')

    // When. alice 세션 유지 + bob 토큰으로 GET /users/me/inbox 직접 호출
    const bobItems = await fetchBobInboxDirect(page)

    // Then. bob의 inbox 비어있음 — .@ / -@ 는 멘션 아님
    expect(bobItems).toHaveLength(0)
    const mentioned = bobItems.find((item) => item.eventType === 'ISSUE_MENTIONED')
    expect(mentioned).toBeUndefined()
  })
})
