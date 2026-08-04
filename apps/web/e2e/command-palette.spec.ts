// FR-UX-04 + FR-UX-12 F4 D7 E2E — 명령 팔레트(Cmd+K) 시나리오
// (S1 열기/닫기 / S3 goto / S4 search / S5 issue / E5 비로그인 / NFR2 대체 / S9~S14 실체 검색)
//
// 시나리오 개요.
//   S1. 팔레트 열기/닫기   — alice 로그인 후 `Cmd+K`(크로스플랫폼 `ControlOrMeta+KeyK`)로 열림,
//                          입력창 자동 포커스, `Esc`로 닫힘
//   S3. `/goto <이슈키>`   — 이슈 상세(`/issues/<키>`)로 이동
//   S4. `/search <질의>`   — `/search?q=text ~ "<질의>"`로 이동 + 검색 자동 실행(결과 목록 렌더) 확인
//   S5. `/issue <제목>`    — `/issues/new?summary=<제목>`로 이동 + 제목 필드 프리필 확인
//   E5. 비로그인           — `/login` 페이지에서 `Cmd+K` → 팔레트 미표시 (FR2)
//   NFR2(대체)             — axe-core 미도입(package.json/e2e 어디에도 의존성 없음, 2026-07-04 확인) →
//                          키보드 전용 조작(방향키↓ + Enter + Esc) 완결로 대체 검증
//   ── 아래는 FR-UX-12 F4(슬래시 없는 입력의 실체 검색) ──
//   S9.  이슈키 즉시매칭   — 소문자 `atlas-1` → 「이슈」 그룹 정확일치 + Enter로 이동
//   S10. 자유 텍스트 검색  — 디바운스 후 결과 목록 렌더
//   S11. 모든 결과 보기    — 팔레트가 만든 감싼 AQL 그대로 검색 페이지로 이동
//   S12. 키보드 전용 완결  — Home+방향키+Enter로 검색 결과 열기(마우스 클릭 0)
//   S13. 슬래시 무회귀     — `/goto ATLAS-1`이 이슈키 검색 경로로 새지 않음 (FR2 판별 순서)
//   S14. 결과 0건          — 안내 표시 + 팔레트 유지 (FR12·E11)
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
import { E2E_SEARCH_SCENARIO_KEY } from '../src/mocks/search-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** CommandPalette.tsx L23 commandPaletteStrings.dialogLabel(비export) — 하드코딩 (search.spec.ts HEADER_SEARCH_ARIA_LABEL 선례) */
const PALETTE_DIALOG_LABEL = '명령 팔레트'

/** Header 검색 버튼 aria-label(Header.tsx) — RootLayout(Header+CommandPalette 훅) 마운트 완료 신호로 사용 */
const HEADER_SEARCH_ARIA_LABEL = '검색'

/** search-fixtures.ts DEFAULT_SEARCH_PAGE 기대 결과(형태가 유효한 AQL 이면 고정 3건) */
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

/**
 * MSW AQL 검색 시나리오 플래그를 심는다 — **로그인/이동 전에** 호출해야 한다.
 *
 * `addInitScript`는 이후 모든 문서 로드 시점에 실행되므로 SPA 부팅(=MSW 워커 등록)보다
 * 항상 앞선다. 핸들러를 런타임에 갈아끼우는 방식은 페이지 리로드에 깨진다
 * (e2e-msw-scenario-toggle-localstorage-flag).
 *
 * 이 파일 안에 두는 이유. `search.spec.ts`도 같은 3줄을 인라인으로 갖고 있어 공용 헬퍼로
 * 뽑는 편이 낫지만, 이 작업의 허용 파일이 이 spec 하나뿐이라 추출은 후속으로 남긴다.
 *
 * @param page Playwright Page 객체
 * @param scenario search-handlers.ts가 해석하는 시나리오 값
 */
async function setSearchScenario(page: Page, scenario: 'empty'): Promise<void> {
  await page.addInitScript(
    ({ key, value }: { key: string; value: string }) => {
      window.localStorage.setItem(key, value)
    },
    { key: E2E_SEARCH_SCENARIO_KEY, value: scenario },
  )
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
  // Then   `/search?q=text ~ "로그인 버그"`로 이동 — 자유 텍스트를 **감싼 AQL**로 넘긴다.
  //        날것 그대로 넘기면 백엔드 AqlParser가 SEARCH_SYNTAX_ERROR를 낸다(선재 결함, ADR D-3).
  //        AQL 검색 페이지가 마운트 즉시 검색을 실행(버튼 클릭 없이) → 결과 목록 3건 표시
  //        팔레트는 닫힌다(FR6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 /search 로그인 버그 → 검색 페이지 이동 + 검색 자동 실행', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    const query = '로그인 버그'

    // When. /search 명령 실행
    await runSlashCommand(dialog, `/search ${query}`)

    // Then. /search?q=<감싼 AQL>로 이동 — URLSearchParams로 정확한 q 값 검증(인코딩 무관)
    // ★기대값은 리터럴로 적는다. buildTextQuery()로 계산하면 구현이 감싸기를 그만둬도
    //   기대값이 함께 바뀌어 테스트가 증인 노릇을 못 한다(동어반복 가드).
    await page.waitForURL('**/search**')
    const url = new URL(page.url())
    expect(url.searchParams.get('q')).toBe('text ~ "로그인 버그"')

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

  // ═══════════════════════════════════════════════════════════════════════════
  // FR-UX-12 F4 — 슬래시 없는 입력의 실체 검색 (S9~S14)
  //
  // 번호를 S9부터 시작하는 이유. S1~S5는 FR-UX-04 스펙의 번호라 재사용하면 두 정본이
  // 같은 이름을 두고 충돌한다.
  // 스펙 대응. S9=S1·S2 / S10=S4 / S11=S5 / S12=NFR2 / S13=S7·FR2 / S14=FR12·E11
  //
  // 공통 전제. 자유 텍스트 검색은 250ms 디바운스 뒤에 실행된다(use-palette-search).
  // 고정 sleep을 쓰지 않는다 — Playwright 자동 대기(toBeVisible/toHaveAttribute)가
  // 느린 CI에서도 무너지지 않는 유일한 방법이다.
  // ═══════════════════════════════════════════════════════════════════════════

  // ───────────────────────────────────────────────────────────────────────────
  // S9. 이슈키 즉시매칭 — 스펙 S1/S2
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   소문자 `atlas-1` 입력(슬래시 없음)
  // Then   「이슈」 그룹에 ATLAS-1이 뜨고 기본 하이라이트가 그 위에 있다(대문자 정규화)
  //        Enter로 이슈 상세로 이동하고 팔레트가 닫힌다
  // ───────────────────────────────────────────────────────────────────────────
  test('S9 atlas-1 입력 → 이슈 정확일치가 뜨고 Enter로 이동(대소문자 무관)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // When. 소문자 이슈키 — palette-input.ts가 대문자로 정규화한다
    await dialog.getByRole('combobox').fill('atlas-1')

    // Then. 정확일치 한 줄. 접근성 이름이 "ATLAS-1 <요약>"이라 부분 일치로 잡는다
    // (getByRole의 name은 exact 미지정 시 부분 일치다). 유사일치 목록에서 같은 키는
    // 걷어내므로 'ATLAS-1'을 포함하는 option은 하나뿐이다 — strict mode가 그것을 강제한다.
    const issueOption = dialog.getByRole('option', { name: 'ATLAS-1' })
    await expect(issueOption).toBeVisible()

    // Then. 기본 하이라이트가 정확일치 위에 있다 — 확정 전에 Enter를 누르면 아무 일도
    // 일어나지 않아 flaky가 된다. "무엇이 실행될지"를 먼저 못 박는다.
    await expect(issueOption).toHaveAttribute('aria-selected', 'true')

    // When2. Enter
    await page.keyboard.press('Enter')

    // Then2. 이슈 상세로 이동 + 팔레트 닫힘(FR6)
    await page.waitForURL('**/issues/ATLAS-1')
    await expect(dialog).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S10. 자유 텍스트 검색 — 스펙 S4
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   `로그인` 입력(이슈키 형식 아님 → 자유 텍스트)
  // Then   디바운스 뒤 검색 결과 3건이 팔레트 안에 렌더된다
  // ───────────────────────────────────────────────────────────────────────────
  test('S10 자유 텍스트 입력 → 디바운스 후 검색 결과 목록', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // When. 자유 텍스트
    await dialog.getByRole('combobox').fill('로그인')

    // Then. 결과 3건. ★`getByRole('option').first()`로 잡지 않는다 — 「모든 결과 보기」는
    // 결과가 0건이어도 늘 렌더되는 탈출구라 first()는 검색이 아무것도 못 찾아도 초록이 되는
    // 공허한 증인이다. 실제 결과 키를 이름으로 지목해야 증인이 된다.
    for (const key of DEFAULT_SEARCH_RESULT_KEYS) {
      await expect(dialog.getByRole('option', { name: key })).toBeVisible()
    }
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S11. 「모든 결과 보기」 — 스펙 S5
  //
  // Given  alice로 로그인, 팔레트 열림, 자유 텍스트 입력
  // When   「모든 결과 보기」 선택
  // Then   팔레트가 만든 것과 **같은** 감싼 AQL로 검색 페이지에 도착하고 결과가 렌더된다
  //        (여기서 감싸기가 빠지면 검색 페이지가 SEARCH_SYNTAX_ERROR로 죽는다 — ADR D-3)
  // ───────────────────────────────────────────────────────────────────────────
  test('S11 「모든 결과 보기」 → 감싼 AQL 그대로 검색 페이지로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    await dialog.getByRole('combobox').fill('로그인')

    // When. ★이름은 고정이 아니다 — 총계를 알면 「모든 결과 보기 (3건)」, 모르면(조회 전·실패·
    // 0건) 접미사가 없다. 총계를 모르는데 「(0건)」을 붙이면 거짓 정보이기 때문이다.
    // 그래서 전체 일치가 아니라 정규식으로 잡는다.
    await dialog.getByRole('option', { name: /모든 결과 보기/ }).click()

    // Then. 감싼 AQL이 q로 그대로 넘어간다(S4와 같은 리터럴 기대값 — 동어반복 가드)
    await page.waitForURL('**/search**')
    expect(new URL(page.url()).searchParams.get('q')).toBe('text ~ "로그인"')

    // Then. 검색 페이지가 그 질의로 실제 결과를 그린다 — 이동만 하고 깨지면 탈출구가 아니다
    await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S12. 키보드 전용 완결 — NFR2
  //
  // Given  alice로 로그인, 팔레트 열림, 검색 결과 렌더 완료
  // When   `Home`(첫 항목) → `ArrowDown` 1회 → `Enter`
  // Then   2번째 검색 결과의 이슈 상세로 이동한다(마우스 클릭 0)
  //
  // NFR2 대체 테스트가 빈 입력의 QUICK_LINKS를, 이 테스트가 **검색 결과**를 커버해
  // 둘이 합쳐 "팔레트는 키보드만으로 완결된다"를 검증한다.
  //
  // ★왜 `Home`으로 시작하는가 (2026-08-04 실측).
  // 자유 텍스트는 250ms 디바운스 뒤에 조회되는데 「모든 결과 보기」는 질의가 확정되는
  // 즉시(=결과 도착 **전**) 렌더된다. cmdk는 그 순간의 유일한 항목을 하이라이트하고,
  // 뒤늦게 결과가 붙어도 유효한 선택은 유지한다. 그래서 검색이 끝난 시점의 기본
  // 하이라이트는 목록 **맨 아래** 「모든 결과 보기」다. 출발점을 `Home`으로 못 박지 않으면
  // ↓ 1회의 도착지가 확정되지 않는다. 이 하이라이트 위치 자체는 별건 보고 대상이며,
  // 여기서 그 좌표를 단언하면 고쳐질 때 이 테스트가 함께 깨진다 — 그래서 단언하지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S12 키보드만으로 검색 결과를 연다(마우스 클릭 0)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    await dialog.getByRole('combobox').fill('로그인')

    // Given. 결과가 도착한 뒤에 키 조작을 시작한다(항목 마운트가 하이라이트를 흔들지 않도록)
    const firstResult = dialog.getByRole('option', { name: DEFAULT_SEARCH_RESULT_KEYS[0] })
    await expect(firstResult).toBeVisible()

    // When. Home = 첫 항목으로 — 출발점을 확정한다
    await page.keyboard.press('Home')
    await expect(firstResult).toHaveAttribute('aria-selected', 'true')

    // When2. ↓ 1회 — 하이라이트가 2번째 결과로 내려간다
    const secondResult = dialog.getByRole('option', { name: DEFAULT_SEARCH_RESULT_KEYS[1] })
    await page.keyboard.press('ArrowDown')
    await expect(secondResult).toHaveAttribute('aria-selected', 'true')

    // Then. Enter로 2번째 이슈를 연다
    await page.keyboard.press('Enter')
    await page.waitForURL(`**/issues/${DEFAULT_SEARCH_RESULT_KEYS[1]}`)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S13. 슬래시 경로 무회귀 — 스펙 S7 · FR2
  //
  // Given  alice로 로그인, 팔레트 열림
  // When   `/goto ATLAS-1` 입력 + Enter
  // Then   여전히 이슈 상세로 이동한다 — 이슈키 판별이 슬래시 명령을 가로채지 않는다
  //
  // 판별 **순서**(parseCommand 먼저 → resolveNonCommandInput 나중)는 단위 테스트가 계약으로
  // 못 박고, 이 테스트는 그 순서가 깨졌을 때 실사용이 무엇을 잃는지를 지키는 무회귀 증인이다.
  // 순서가 뒤집히면 `/goto ATLAS-1`이 자유 텍스트로 새어 Enter가 검색 결과를 열어버린다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S13 /goto ATLAS-1은 이슈키 검색 경로로 새지 않는다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // When. 슬래시 명령 — 인자가 이슈키 형식이라 2계층 판별과 겹치는 입력이다
    await runSlashCommand(dialog, '/goto ATLAS-1')

    // Then. 슬래시 경로 그대로 이슈 상세로 이동
    await page.waitForURL('**/issues/ATLAS-1')
    await expect(
      page.getByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S14. 결과 0건 — FR12 · E11
  //
  // Given  MSW 'empty' 시나리오, alice로 로그인, 팔레트 열림
  // When   결과가 없는 자유 텍스트 입력
  // Then   「결과가 없습니다.」 안내가 뜨고 팔레트는 **열린 채로** 남는다
  //        (닫아버리면 사용자가 질의를 고칠 자리를 잃는다)
  // ───────────────────────────────────────────────────────────────────────────
  test('S14 결과 0건 — 안내가 뜨고 팔레트는 열려 있다', async ({ page }) => {
    // Given. 0건 시나리오를 로그인 전에 심는다(addInitScript는 이후 모든 문서 로드에 적용)
    await setSearchScenario(page, 'empty')
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)

    // When. 아무것도 못 찾는 질의
    await dialog.getByRole('combobox').fill('없는것')

    // Then. 0건 안내 — 검색 중 표시를 지나 확정된 뒤에만 뜬다
    await expect(dialog.getByText('결과가 없습니다.')).toBeVisible()

    // Then. 팔레트 유지 — 질의를 고쳐 다시 시도할 수 있어야 한다(E11)
    await expect(dialog).toBeVisible()
  })
})
