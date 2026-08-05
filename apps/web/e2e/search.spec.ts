// FR-SR-02 D7 E2E — AQL 검색 페이지 시나리오 (S1 정상 / S2 문법오류 / S4 0건 / B3 한글 IME)
//
// 시나리오 개요.
//   S1. 정상 검색       — `/search` 진입, AQL 쿼리 입력 → 검색 → 결과 목록 + syntax highlight 실렌더
//   S2. 문법오류        — 잘못된 쿼리 → 입력창 하단 role=alert + 에러 메시지 + 결과 미갱신
//   S4. 0건             — empty 시나리오 쿼리 → 결과영역 "검색 결과가 없습니다." 안내 (alert 아님)
//   B3. 한글 IME        — `summary ~ "로그인"` fill → overlay에 한글 정상 표시 (jsdom 못 흉내)
//
// 설계 결정.
//   - 검색 진입: 상단바 전역 검색 입력창(role=searchbox, aria-label="전역 검색")에 자연어를
//     넣고 Enter 제출 → SPA 내부 이동 → /search 진입 (FR-UX-12 F13)
//     (reload 금지 — MSW 핸들러가 ServiceWorker 기반이라 reload 시 시나리오 플래그 리셋)
//   - MSW 시나리오 토글: addInitScript + localStorage.setItem 패턴 (goto 전 등록)
//     플래그 키: E2E_SEARCH_SCENARIO_KEY = '__bts_e2e_search_scenario' (search-handlers.ts)
//   - 검색 버튼: aria-label="검색" — 이 이름은 F13 이후 이 제출 버튼 **전용**이다
//     (Jira 패리티 계약 §2 이름 분리, 상단바는 "전역 검색"). 컨테이너 한정은 그대로 둔다 —
//     같은 화면에 동명 버튼이 다시 생겨도 이 셀렉터가 흔들리지 않게 하는 보험이다.
//   - 결과 목록 컨테이너: role=list, aria-label="검색 결과"
//   - syntax highlight 검증: pre[aria-hidden="true"] 내 span.text-syntax-keyword 존재
//     (jsdom 단위테스트는 실 CSS 적용/렌더 불가 → E2E 필수)
//   - 0건 안내: role=alert 없음 — 에러가 아닌 정상 빈 상태 (검색결과가 없습니다.)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 검색 버튼은 입력 영역 컨테이너로 한정 (Header 동명 버튼 충돌)
//   - msw-mutation-stateful-refetch: SPA 내부 이동 — page.reload() 금지 (MSW store 리셋)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript → goto 순서 필수
//   - e2e-loginasalice-fixture-fr-au-07-regression: loginAsAlice는 issue-fixtures 공유 2단계 헬퍼
//   - vitest-usertype-long-string-timeout: 한글 입력은 fill() 사용 (pressSequentially delay 불필요)
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 수정 없음 — 이 파일만 신규 추가
//
// MSW 핵심 사항.
//   - search-handlers.ts가 handlers.ts에 등록되어 SPA 전역 활성
//   - 기본(no flag): 3건 결과 반환 (ATLAS-1/2/3, search-fixtures.ts DEFAULT_SEARCH_PAGE)
//   - 'syntax-error': SEARCH_SYNTAX_ERROR 400 + position:7 반환
//   - 'empty': 0건 결과 반환 (EMPTY_SEARCH_PAGE)
//   - 각 테스트는 새 Playwright context → ServiceWorker 초기화 → 기본 3건 상태로 시작

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { navLabels } from '../src/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — search-handlers.ts E2E_SEARCH_SCENARIO_KEY 와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW 시나리오 토글 localStorage 키.
 * search-handlers.ts `E2E_SEARCH_SCENARIO_KEY` 와 동일해야 한다.
 */
const E2E_SEARCH_SCENARIO_KEY = '__bts_e2e_search_scenario'

/** 검색 페이지 URL */
const SEARCH_URL = '/search'

/**
 * 상단바 전역 검색으로 `/search` 에 진입할 때 넣는 씨앗 질의(FR-UX-12 F13).
 *
 * ★이 파일의 어떤 시나리오가 쓰는 질의와도 **겹치는 토큰이 없어야** 한다.
 * 상단바 제출은 `/search?q=text ~ "…"` 로 착지시키고 검색 페이지는 마운트 시 그 q 로
 * 한 번 조회하므로, 씨앗이 시나리오 질의와 글자를 공유하면 「fill 이 안 먹었는데도
 * overlay 에 그 글자가 보인다」는 가짜 초록이 생긴다(B3 의 `로그인` 이 대표 위험).
 */
const GLOBAL_SEARCH_SEED_TEXT = '진입용씨앗'

// ─────────────────────────────────────────────────────────────────────────────
// search-fixtures.ts DEFAULT_SEARCH_PAGE 기대 결과 (3건, 동기화 유지)
//
//   ATLAS-1: '로그인 버튼이 동작하지 않음'  (status=open, priority=High)
//   ATLAS-2: '대시보드 로딩이 느림'         (status=in_progress, priority=Medium)
//   ATLAS-3: '회원가입 이메일 인증 구현'     (status=done, priority=High)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 시나리오에서 기대되는 이슈 키 목록 */
const DEFAULT_RESULT_KEYS = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 후 SPA 내부 이동으로 /search 진입한다.
 *
 * reload 금지 — SPA 내부 이동(상단바 전역 검색 Enter 제출)으로 ServiceWorker가 재시작되지 않는다.
 * [[fr-nt-03-d6-d7-done]] MSW store 리셋 방지.
 *
 * 셀렉터 이름은 `navLabels` 정본에서 읽는다(하드코딩 금지). `exact: true` 필수 —
 * `검색`(이 페이지 제출 버튼 전용 이름)이 `전역 검색` 의 substring 이다.
 *
 * ⚠️ 착지 URL 에 `?q=text ~ "…"` 가 실린다 — 검색 페이지가 마운트 시 그 q 로 한 번 조회하므로
 * 결과 목록이 각 시나리오의 명시 검색 **이전에** 이미 떠 있다. 씨앗 토큰이 시나리오 질의와
 * 겹치지 않게 고른 이유({@link GLOBAL_SEARCH_SEED_TEXT})가 그것이다.
 *
 * @param page Playwright Page 객체
 */
async function navigateToSearch(page: import('@playwright/test').Page): Promise<void> {
  await loginAsAlice(page)
  // 상단바 전역 검색 입력창에 자연어 제출 → SPA pushState → /search?q=text ~ "…"
  const globalSearch = page.getByRole('searchbox', {
    name: navLabels.globalSearch,
    exact: true,
  })
  await globalSearch.fill(GLOBAL_SEARCH_SEED_TEXT)
  await globalSearch.press('Enter')
  await page.waitForURL('**/search**')
  // 검색 버튼(입력 영역) 렌더 대기 — AQL 검색 페이지 헤딩 확인
  await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()
}

/**
 * AQL 쿼리를 입력하고 검색을 실행한다.
 *
 * 검색 버튼: aria-label="검색" — Header 버튼과 동명이므로 입력 컨테이너 내로 한정.
 * SearchPage 렌더 구조: div.space-y-4 > div.space-y-2 > div.flex > button[aria-label="검색"]
 *
 * @param page Playwright Page 객체
 * @param query AQL 쿼리 문자열
 */
async function fillAndSearch(
  page: import('@playwright/test').Page,
  query: string,
): Promise<void> {
  // AqlHighlighter textarea — placeholder로 식별
  const textarea = page.locator('textarea[placeholder*="AQL 쿼리를 입력하세요"]')
  await textarea.fill(query)

  // 검색 버튼 — aria-label="검색". Header 버튼과 충돌하므로 검색 폼 영역(Cmd/Ctrl+Enter 힌트 형제)로 한정
  // [[playwright-getbyrole-exact-strict-mode]] 패턴: 컨테이너 한정으로 strict mode 회피
  const searchButtonContainer = page.locator('div.flex.items-center.gap-3').filter({
    has: page.getByRole('button', { name: '검색', exact: true }),
  })
  await searchButtonContainer.getByRole('button', { name: '검색', exact: true }).click()
}

/**
 * 검색 결과 목록의 이슈 링크 로케이터를 반환한다.
 *
 * SearchResultCard: `<a aria-label={issueKey} ...>`
 * 결과 목록 컨테이너: `<ul aria-label="검색 결과">`
 *
 * @param page Playwright Page 객체
 * @param issueKey 이슈 키 (ATLAS-N 형태)
 */
function getResultCard(
  page: import('@playwright/test').Page,
  issueKey: string,
) {
  return page
    .getByRole('list', { name: '검색 결과' })
    .getByRole('link', { name: issueKey, exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-SR-02 AQL 검색 페이지 (S1 정상 / S2 문법오류 / S4 0건 / B3 한글 IME)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. 정상 검색 — 결과 목록 + syntax highlight 실렌더
  //
  // Given  alice 로그인 + /search 진입 (Header 검색 아이콘 SPA 이동)
  // When   `status = open AND priority IN (1, 2)` 입력 + 검색 버튼 클릭
  // Then   결과 목록(aria-label="검색 결과") 표시 — ATLAS-1/2/3 3건
  //        overlay(pre[aria-hidden="true"]) 내 span.text-syntax-keyword 존재 (syntax highlight 실렌더)
  //        span.text-syntax-field 존재 (필드 색상)
  //
  // 검증 핵심: jsdom 단위테스트는 실 CSS 적용 불가 → E2E에서 span 클래스 존재 확인 필수
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 정상 검색 — 결과 3건 표시 + syntax highlight span 클래스 실렌더', async ({ page }) => {
    // Given. alice 로그인 + /search SPA 진입
    await navigateToSearch(page)

    // Given. 입력창 렌더 대기 — AqlHighlighter textarea
    const textarea = page.locator('textarea[placeholder*="AQL 쿼리를 입력하세요"]')
    await expect(textarea).toBeVisible()

    // When. AQL 쿼리 입력
    await textarea.fill('status = open AND priority IN (1, 2)')

    // Then-1. syntax highlight span 클래스 실렌더 확인 (입력 즉시 overlay에 반영)
    // AqlHighlighter overlay: pre[aria-hidden="true"] 내 span.text-syntax-keyword
    // 'AND'는 KEYWORD — text-syntax-keyword 클래스 부여
    const overlay = page.locator('pre[aria-hidden="true"]')
    await expect(overlay).toBeVisible()
    // 'AND' 토큰 → KEYWORD → text-syntax-keyword 클래스 span
    await expect(overlay.locator('span.text-syntax-keyword').first()).toBeVisible()
    // 'status' 토큰 → FIELD → text-syntax-field 클래스 span
    await expect(overlay.locator('span.text-syntax-field').first()).toBeVisible()

    // When. 검색 실행
    await fillAndSearch(page, 'status = open AND priority IN (1, 2)')

    // Then-2. 결과 목록 3건 표시 (DEFAULT_SEARCH_PAGE — 시나리오 플래그 없음)
    for (const key of DEFAULT_RESULT_KEYS) {
      await expect(getResultCard(page, key)).toBeVisible()
    }

    // Then-3. 결과 목록 aria-label="검색 결과" 존재
    await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()

    // Then-4. 입력 에러 alert 없음 (정상 케이스)
    await expect(page.getByRole('alert')).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. 문법오류 — 입력창 하단 role=alert + 에러 메시지 표시, 결과 미갱신
  //
  // Given  alice 로그인 + /search 진입
  //        addInitScript로 '__bts_e2e_search_scenario'='syntax-error' 심기 (goto 전)
  // When   아무 쿼리 입력 + 검색 실행
  // Then   role=alert 입력창 하단에 표시 (에러 메시지 포함)
  //        결과 목록(aria-label="검색 결과") 미표시
  //
  // MSW syntax-error 시나리오: SEARCH_SYNTAX_ERROR 400 + detail + position:7
  // search.tsx resolveErrorMessage: position 7 → "(8번째 글자 근처)" 메시지 포함
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 문법오류 — 입력창 하단 role=alert 표시, 결과 미갱신', async ({ page }) => {
    // Given. addInitScript로 시나리오 플래그 설정 — goto 전 등록 필수 (e2e-msw-scenario-toggle-localstorage-flag)
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'syntax-error')
    }, E2E_SEARCH_SCENARIO_KEY)

    // Given. alice 로그인 + /search 진입 (addInitScript가 이미 등록된 상태)
    await loginAsAlice(page)
    await page.goto(SEARCH_URL)
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()

    // When. 잘못된 쿼리 입력 + 검색 실행
    await fillAndSearch(page, 'status = AND broken ===')

    // Then. role=alert 표시 (입력창 하단 문법오류 메시지)
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    // MSW 반환 detail: `Unexpected token in query: "status = AND broken ==="` (position:7 → 8번째 글자 근처)
    await expect(alert).toContainText('번째 글자 근처')

    // Then. 결과 목록 미표시 — 에러 시 결과 목록 렌더 안 함
    await expect(page.getByRole('list', { name: '검색 결과' })).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 0건 — 결과영역 빈 상태 안내 표시 (role=alert 아님)
  //
  // Given  alice 로그인 + /search 진입
  //        addInitScript로 '__bts_e2e_search_scenario'='empty' 심기
  // When   쿼리 입력 + 검색 실행
  // Then   결과영역 "검색 결과가 없습니다." 메시지 표시
  //        role=alert 없음 (에러가 아닌 정상 빈 상태)
  //        결과 목록(aria-label="검색 결과") 없음
  //
  // search.tsx: data.empty === true → div.text-center "검색 결과가 없습니다." (role=alert 없음)
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 0건 — "검색 결과가 없습니다." 안내 표시, role=alert 없음', async ({ page }) => {
    // Given. addInitScript로 empty 시나리오 플래그 설정
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'empty')
    }, E2E_SEARCH_SCENARIO_KEY)

    // Given. alice 로그인 + /search 진입
    await loginAsAlice(page)
    await page.goto(SEARCH_URL)
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()

    // When. 쿼리 입력 + 검색 실행
    await fillAndSearch(page, 'summary ~ "존재하지않는이슈"')

    // Then. "검색 결과가 없습니다." 메시지 표시
    await expect(page.getByText('검색 결과가 없습니다.')).toBeVisible()

    // Then. role=alert 없음 — 0건은 에러가 아님
    await expect(page.getByRole('alert')).not.toBeVisible()

    // Then. 결과 목록 없음 (data.empty → ul 렌더 안 함)
    await expect(page.getByRole('list', { name: '검색 결과' })).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // B4. text FTS — `text ~ "로그인"` 입력 시 text 가상 FTS 필드가
  //              text-syntax-field로 강조되고 검색 실행 후 결과 목록 표시
  //
  // Given  alice 로그인 + /search 진입 (기본 시나리오 — 플래그 없음)
  // When   textarea.fill('text ~ "로그인"') — FR-SR-04 text 가상 FTS 필드 쿼리
  // Then   overlay에 span.text-syntax-field 존재 ('text' 필드 색상 클래스)
  //        overlay에 span.text-syntax-string 존재 ('"로그인"' 문자열 색상 클래스)
  //        overlay 텍스트에 '로그인' 포함
  //        검색 실행 후 결과 목록 표시 — ATLAS-1 포함 (summary에 '로그인' 포함)
  //        role=alert 없음 (정상 케이스)
  //
  // 검증 목적.
  //   - 프론트 토크나이저에 'text' FIELD 등록 여부 (aql-tokenizer.ts AQL_FIELDS)
  //   - UI가 text ~ 연산자를 포함한 쿼리를 에러 없이 처리하는지 실렌더로 검증
  //   - MSW 기본 시나리오가 text 쿼리에 이슈를 반환하는지 확인
  //
  // MSW 처리 확인.
  //   search-handlers.ts 기본 경로(시나리오 플래그 없음)가 DEFAULT_SEARCH_PAGE(3건)를
  //   반환하므로 text 쿼리도 빈 배열을 반환하지 않는다. 가짜그린 없음.
  // ─────────────────────────────────────────────────────────────────────────
  test('B4 text FTS — text ~ "로그인" syntax highlight + 검색 실행 결과 표시', async ({ page }) => {
    // Given. alice 로그인 + /search SPA 진입 (기본 시나리오, localStorage 플래그 없음)
    await navigateToSearch(page)

    // Given. 입력창 렌더 대기
    const textarea = page.locator('textarea[placeholder*="AQL 쿼리를 입력하세요"]')
    await expect(textarea).toBeVisible()

    // When. text FTS 필드 쿼리 입력
    await textarea.fill('text ~ "로그인"')

    // Then. overlay에 'text' 토큰 → FIELD → span.text-syntax-field 실렌더
    const overlay = page.locator('pre[aria-hidden="true"]')
    await expect(overlay).toBeVisible()
    await expect(overlay.locator('span.text-syntax-field').first()).toBeVisible()

    // Then. '"로그인"' → STRING → span.text-syntax-string 실렌더
    await expect(overlay.locator('span.text-syntax-string').first()).toBeVisible()

    // Then. overlay 텍스트에 '로그인' 포함 확인 (한글 FTS 값 정상 표시)
    await expect(overlay).toContainText('로그인')

    // When. 검색 실행 (fillAndSearch: textarea.fill + 검색 버튼 클릭)
    await fillAndSearch(page, 'text ~ "로그인"')

    // Then. 결과 목록에 ATLAS-1 표시 (summary '로그인 버튼이 동작하지 않음' — FTS 매칭)
    await expect(getResultCard(page, 'ATLAS-1')).toBeVisible()

    // Then. 결과 목록 컨테이너 존재
    await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()

    // Then. role=alert 없음 (정상 케이스 — 문법오류 없이 검색 완료)
    await expect(page.getByRole('alert')).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // B3. 한글 IME — `summary ~ "로그인"` 입력 시 overlay에 한글 정상 표시
  //
  // Given  alice 로그인 + /search 진입 (기본 시나리오 — 플래그 없음)
  // When   textarea.fill('summary ~ "로그인"') — Playwright fill은 직접 value 설정
  // Then   overlay(pre[aria-hidden="true"]) 텍스트가 '로그인'을 포함
  //        span.text-syntax-field 존재 ('summary' 필드 색상 클래스)
  //        span.text-syntax-string 존재 ('"로그인"' 문자열 색상 클래스)
  //
  // jsdom 한계: composition 이벤트 시뮬레이션 불가 → 실 Chromium 브라우저 E2E 필수
  // ─────────────────────────────────────────────────────────────────────────
  test('B3 한글 IME — overlay에 한글 정상 포함 + syntax 색상 span 실렌더', async ({ page }) => {
    // Given. alice 로그인 + /search SPA 진입
    await navigateToSearch(page)

    // Given. 입력창 렌더 대기
    const textarea = page.locator('textarea[placeholder*="AQL 쿼리를 입력하세요"]')
    await expect(textarea).toBeVisible()

    // When. 한글 포함 AQL 쿼리 입력 — Playwright fill은 실 브라우저 value 직접 설정
    await textarea.fill('summary ~ "로그인"')

    // Then. overlay 텍스트에 한글 포함 확인 (IME 조합 후 정상 표시)
    const overlay = page.locator('pre[aria-hidden="true"]')
    await expect(overlay).toBeVisible()
    await expect(overlay).toContainText('로그인')

    // Then. 'summary' → FIELD → span.text-syntax-field 실렌더
    await expect(overlay.locator('span.text-syntax-field').first()).toBeVisible()

    // Then. '"로그인"' → STRING → span.text-syntax-string 실렌더
    await expect(overlay.locator('span.text-syntax-string').first()).toBeVisible()
  })
})
