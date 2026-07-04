// FR-UX-04 D7 E2E — 명령 팔레트(Cmd+K) 시나리오 (S1 열기/닫기 / S3 goto / S4 search / S5 issue / E5 비로그인 / NFR2 대체)
//
// 시나리오 개요.
//   S1. 팔레트 열기/닫기   — alice 로그인 후 `Cmd+K`(크로스플랫폼 `ControlOrMeta+KeyK`)로 열림,
//                          입력창 자동 포커스, `Esc`로 닫힘
//   S3. `/goto <이슈키>`   — 이슈 상세(`/issues/<키>`)로 이동
//   S4. `/search <질의>`   — `/search?q=<질의>`로 이동 + 검색 자동 실행(결과 목록 렌더) 확인
//   S5. `/issue <제목>`    — `/issues/new?summary=<제목>`로 이동 + 제목 필드 프리필 확인
//   E5. 비로그인           — `/login` 페이지에서 `Cmd+K` → 팔레트 미표시 (FR2)
//   NFR2(대체)             — axe-core 미도입(package.json/e2e 어디에도 의존성 없음, 2026-07-04 확인) →
//                          키보드 전용 조작(방향키↓ + Enter + Esc) 완결로 대체 검증
//
// 설계 결정.
//   - 명령 팔레트는 전부 클라이언트 라우팅(mutation 0) → 신규 MSW 핸들러 불필요, 기존 핸들러 전부 재사용
//     (issue-handlers/search-handlers 기본 시나리오, 플래그 미설정)
//   - S4 검증: search.tsx의 SearchPage는 마운트 시 q prop을 submittedQuery 초기값으로 사용해
//     useQuery가 즉시 enabled → 팔레트로 이동만 해도 검색이 자동 실행된다(버튼 클릭 불필요).
//     기본 MSW 핸들러는 쿼리 문자열과 무관하게 고정 3건(ATLAS-1/2/3)을 반환한다(search-handlers.ts).
//   - `/goto` 대상 이슈: ATLAS-1(issue-fixtures.ts 기존 fixture, summary 고정) — 신규 fixture 불필요.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - msw-mutation-stateful-refetch / worktree-stale-base-rebase-and-e2e-msw-traps:
//     팔레트 이동은 TanStack Router 클라이언트 라우팅(SPA 내부 이동)이라 자연히 ServiceWorker 재시작 없음.
//     page.reload() 사용하지 않는다.
//   - playwright-getbyrole-exact-strict-mode: 팔레트 dialog/combobox를 role+name(exact 기본)으로 한정,
//     다이얼로그 컨테이너(dialog)로 스코프해 다른 페이지 요소와 충돌 방지
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 공유 헬퍼 재사용 (userId 정합)
//   - ui-pr-defer-e2e-regression-latent: 이 파일만 신규 추가, 기존 E2E 수정 없음
//
// cmdk 접근성 계약 확인(node_modules 소스 직접 확인, 2026-07-04).
//   - CommandPrimitive.Dialog → Radix Dialog.Content, role="dialog" + aria-label=label prop
//   - CommandPrimitive.Input → role="combobox"
//   - 다이얼로그 오픈 시 Radix가 첫 포커스 가능 자손(Command.Input)에 자동 포커스
//   - 빈 입력 상태에서 방향키(ArrowDown/Up)는 cmdk 루트가 처리해 하이라이트를 이동시키고,
//     Enter는 현재 하이라이트된 항목의 onSelect를 트리거한다(CommandPalette.tsx의
//     handleInputKeyDown은 parsed.kind==='not-command'일 때 조기 반환 → cmdk 기본 동작에 위임)

import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueCreateStrings } from '../src/i18n/ko'
import { issueAtlas1Fixture } from '../src/mocks/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** CommandPalette.tsx L23 commandPaletteStrings.dialogLabel(비export) — 하드코딩 (search.spec.ts HEADER_SEARCH_ARIA_LABEL 선례) */
const PALETTE_DIALOG_LABEL = '명령 팔레트'

/** Header 검색 버튼 aria-label(Header.tsx) — RootLayout(Header+CommandPalette 훅) 마운트 완료 신호로 사용 */
const HEADER_SEARCH_ARIA_LABEL = '검색'

/** search-fixtures.ts DEFAULT_SEARCH_PAGE 기대 결과(쿼리 문자열 무관 고정 3건) */
const DEFAULT_SEARCH_RESULT_KEYS = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice로 로그인하고 RootLayout(Header + useCommandPalette 훅) 마운트 완료까지 대기한다.
 *
 * Header 검색 버튼 렌더를 신호로 사용 — isAuthenticated 분기 렌더 완료 및
 * useCommandPalette의 document keydown 리스너 등록(useEffect)이 끝났음을 보장한다.
 */
async function loginAndWaitForRootReady(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('button', { name: HEADER_SEARCH_ARIA_LABEL, exact: true }),
  ).toBeVisible()
}

/**
 * `ControlOrMeta+KeyK`(mac=Cmd, CI linux=Ctrl 모두 커버)로 팔레트를 열고 dialog 로케이터를 반환한다.
 */
async function openCommandPalette(page: Page): Promise<Locator> {
  await page.keyboard.press('ControlOrMeta+KeyK')
  const dialog = page.getByRole('dialog', { name: PALETTE_DIALOG_LABEL })
  await expect(dialog).toBeVisible()
  return dialog
}

/**
 * 팔레트 입력창(combobox)에 슬래시 명령을 입력하고 Enter로 실행한다.
 *
 * @param dialog openCommandPalette가 반환한 dialog 로케이터
 * @param command 입력할 슬래시 명령 문자열 (예: '/goto ATLAS-1')
 */
async function runSlashCommand(dialog: Locator, command: string): Promise<void> {
  const input = dialog.getByRole('combobox')
  await input.fill(command)
  await input.press('Enter')
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-04 명령 팔레트 (Cmd+K)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 팔레트 열기/닫기
  //
  // Given  alice로 로그인, /dashboard 진입(RootLayout 마운트 완료)
  // When   `ControlOrMeta+KeyK` 입력
  // Then   dialog(role="dialog", name="명령 팔레트")가 열리고 입력창(combobox)에 포커스가 간다
  // When2  `Esc` 입력
  // Then2  dialog가 닫힌다(DOM에서 제거)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 Cmd+K로 팔레트 열림, Esc로 닫힘', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. Cmd+K/Ctrl+K
    const dialog = await openCommandPalette(page)

    // Then. 입력창 자동 포커스
    const input = dialog.getByRole('combobox')
    await expect(input).toBeFocused()

    // When2. Esc
    await page.keyboard.press('Escape')

    // Then2. 닫힘
    await expect(dialog).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. `/goto <이슈키>` — 이슈 상세로 이동
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   `/goto ATLAS-1` 입력 + Enter
  // Then   `/issues/ATLAS-1`로 이동(SPA 내부 이동), 이슈 상세 heading(summary) 표시
  //        팔레트는 닫힌다(FR6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 /goto ATLAS-1 → 이슈 상세로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // When. /goto 명령 실행
    await runSlashCommand(dialog, '/goto ATLAS-1')

    // Then. 이슈 상세 URL로 이동
    await page.waitForURL('**/issues/ATLAS-1')
    await expect(
      page.getByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
    ).toBeVisible()

    // Then. 팔레트 닫힘(FR6)
    await expect(dialog).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. `/search <질의>` — 검색 결과로 이동 + 검색 자동 실행
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   `/search 로그인 버그` 입력 + Enter
  // Then   `/search?q=로그인 버그`로 이동
  //        AQL 검색 페이지가 마운트 즉시 검색을 실행(버튼 클릭 없이) → 결과 목록 3건 표시
  //        (기본 MSW 핸들러는 쿼리 문자열 무관 고정 3건 반환 — search-handlers.ts)
  //        팔레트는 닫힌다(FR6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 /search 로그인 버그 → 검색 페이지 이동 + 검색 자동 실행', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    const query = '로그인 버그'

    // When. /search 명령 실행
    await runSlashCommand(dialog, `/search ${query}`)

    // Then. /search?q=<질의>로 이동 — URLSearchParams로 정확한 q 값 검증(인코딩 무관)
    await page.waitForURL('**/search**')
    const url = new URL(page.url())
    expect(url.searchParams.get('q')).toBe(query)

    // Then. AQL 검색 페이지 렌더
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()

    // Then. 검색 자동 실행 확인 — 결과 목록(aria-label="검색 결과") 3건 표시(버튼 클릭 없이)
    const resultList = page.getByRole('list', { name: '검색 결과' })
    await expect(resultList).toBeVisible()
    for (const key of DEFAULT_SEARCH_RESULT_KEYS) {
      await expect(resultList.getByRole('link', { name: key, exact: true })).toBeVisible()
    }

    // Then. 팔레트 닫힘(FR6)
    await expect(dialog).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. `/issue <제목>` — 새 이슈 폼(제목 프리필)으로 이동
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   `/issue 결제 실패 조사` 입력 + Enter
  // Then   `/issues/new?summary=결제 실패 조사`로 이동
  //        새 이슈 폼의 제목 필드 기본값이 "결제 실패 조사"로 프리필됨(FR7)
  //        팔레트는 닫힌다(FR6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 /issue 결제 실패 조사 → 새 이슈 폼 제목 프리필', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    const title = '결제 실패 조사'

    // When. /issue 명령 실행
    await runSlashCommand(dialog, `/issue ${title}`)

    // Then. /issues/new?summary=<제목>로 이동
    await page.waitForURL('**/issues/new**')
    const url = new URL(page.url())
    expect(url.searchParams.get('summary')).toBe(title)

    // Then. 제목 필드 프리필 확인
    await expect(page.getByLabel(issueCreateStrings.summaryLabel)).toHaveValue(title)

    // Then. 팔레트 닫힘(FR6)
    await expect(dialog).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E5. 비로그인 — Cmd+K 무반응
  //
  // Given  비로그인 상태로 /login 페이지에 있음(RootLayout이 Header/CommandPalette 미마운트, FR2)
  // When   `ControlOrMeta+KeyK` 입력
  // Then   팔레트 dialog가 표시되지 않는다
  // ───────────────────────────────────────────────────────────────────────────
  test('E5 비로그인 상태 — Cmd+K 눌러도 팔레트 미표시', async ({ page }) => {
    // Given. 로그인 없이 /login 페이지
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    // When. Cmd+K/Ctrl+K
    await page.keyboard.press('ControlOrMeta+KeyK')

    // Then. 팔레트 미표시
    await expect(page.getByRole('dialog', { name: PALETTE_DIALOG_LABEL })).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // NFR2(대체) — axe-core 미도입(2026-07-04 확인: apps/web/package.json 및 e2e/ 어디에도
  // axe 관련 의존성/사용처 없음) → 접근성 완전 자동측정 대신, 방향키+Enter+Esc만으로
  // 팔레트를 완결 조작할 수 있는지(마우스 클릭 0) 실렌더로 검증한다.
  // S1이 Esc 닫힘을, 이 테스트가 ArrowDown 하이라이트 이동 + Enter 실행을 커버해
  // 두 테스트가 합쳐 "키보드 전용 조작 완결"을 검증한다.
  //
  // Given  alice로 로그인, 팔레트 열림(빈 입력 → QUICK_LINKS 4개 노출, 기본 하이라이트=1번째 "내 이슈")
  // When   `ArrowDown` 1회(하이라이트를 2번째 "검색"으로 이동) + `Enter`(마우스 클릭 없이 선택 실행)
  // Then   `/search`로 이동(빈 질의) + AQL 검색 페이지 렌더 확인
  //        팔레트는 닫힌다
  // ───────────────────────────────────────────────────────────────────────────
  test('NFR2 대체 — 방향키+Enter만으로 바로가기 선택(키보드 전용 조작 완결)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // Given. QUICK_LINKS 4개 노출(빈 입력) — commands.ts 순서: 내 이슈(0)/검색(1)/대시보드(2)/받은 편지함(3)
    // exact:true 필수 — 명령 힌트 그룹에 "/search 검색 결과로 이동" 등 텍스트 일부 중복 항목 존재
    // (playwright-getbyrole-exact-strict-mode 학습)
    await expect(dialog.getByRole('option', { name: '내 이슈', exact: true })).toBeVisible()
    await expect(dialog.getByRole('option', { name: '검색', exact: true })).toBeVisible()

    // When. 방향키로 하이라이트 이동(마우스 클릭 없음) + Enter로 실행
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')

    // Then. /search로 이동(빈 질의 — QUICK_LINKS '검색' 항목의 to='/search')
    await page.waitForURL('**/search**')
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()

    // Then. 팔레트 닫힘
    await expect(dialog).not.toBeVisible()
  })
})
