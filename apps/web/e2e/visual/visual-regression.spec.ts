// 이슈 목록·이슈 상세 2화면의 픽셀 회귀를 라이트/다크 양쪽에서 잡는 스냅샷 스펙 (§7.5 파일럿 1차)
//
// ─────────────────────────────────────────────────────────────────────────────
// 이 파일이 있는 이유
// ─────────────────────────────────────────────────────────────────────────────
// 시각 계약(`docs/design/jira-parity-contract.md` §6)의 "대상 화면 기본 상태 눈확인"은
// 사람이 매번 브라우저를 열어야만 지켜진다. 여기서 다루는 2화면·기본상태·라이트/다크에
// 한해서만 그 눈확인을 기계가 대신한다. **그 밖은 전부 눈확인이 그대로 남는다** —
// 특히 ① 여기 없는 화면 ② 빈/에러 상태 ③ 조작·인터랙션(픽셀이 같아도 기능은 죽을 수 있다).
//
// ─────────────────────────────────────────────────────────────────────────────
// 파일럿 범위 — 왜 2화면뿐인가
// ─────────────────────────────────────────────────────────────────────────────
// 이 저장소 최초의 Playwright CI 잡이라, 헛 빨간불(flaky) 빈도를 먼저 재고 확대한다.
// 2차 확대 대상(백로그 `/projects/$projectKey/backlog` · 이슈 생성 `/issues/new`)의
// `describe` 는 **일부러 작성하지 않았다**. skip 으로 끄지 않고 아예 안 쓴 것이며,
// 이는 "테스트 skip 금지" 위반이 아니다 — skip 금지는 **머지된 초록 테스트를 사후에
// 끄는 행위**를 막는 규칙이다 (`docs/rules/behavior-rules.md` #6b 정의 조항).
//
// 채택 판정. 코드 무변경·baseline 고정 상태에서 **연속 20회** 실행해
//   diff 0회  → 채택(4화면 확대 + CI 잡 비차단 해제)
//   diff 1회  → 보류. 마스킹·클록 고정을 보강하고 재측정 1회
//   diff 2회+ → 그 화면 미채택
// 측정 시 **실패 회수와 diff 픽셀 수를 함께** 기록한다. 회수만 세면 임계를 못 정한다.
//
// ─────────────────────────────────────────────────────────────────────────────
// baseline(기준 PNG) 생성 절차 — 이 저장소에서 유일하게 허용된 방법
// ─────────────────────────────────────────────────────────────────────────────
// 1. CI 러너와 **같은 머신·같은 계정**에서 실행한다. OS·아키텍처·브라우저 캐시
//    (`~/Library/Caches/ms-playwright`)가 같아야 픽셀이 같다. 작업 디렉터리는 달라도 된다.
// 2. `pnpm --filter @bts/web exec playwright test --project=visual --update-snapshots`
// 3. 생성된 `e2e/visual/__screenshots__/*.png` 를 커밋한다.
//    (`.gitignore` 의 `test-results/`·`e2e-results/` 는 이 경로를 덮지 않는다 — 커밋된다.)
// 4. **커밋 후 첫 CI 실행이 초록인 것까지 확인해야** baseline 이 유효하다.
//    빨간불이면 baseline 을 다시 만들지 말고 **환경 차이를 조사**한다.
// 5. baseline 이 붙은 뒤 `apps/web/src` 의 여백을 1px 만 일부러 바꿔 이 spec 이
//    **빨간불이 되는지 1회** 확인한다(비-공허 확인). 초록이면 이 파일은 아무것도 안 지킨다.
//
// baseline 갱신이 허용되는 유일한 조건.
//   ① `apps/web/src/**` 변경을 동반하고 ② 그 변경이 **의도된 시각 변경**일 때.
//   원인을 모르는 상태에서의 갱신은 금지다 — 그것이 이 저장소의 가짜초록 양식이다.
//   기계 짝은 `scripts/workflow/snapshot-baseline-guard.test.ts`(baseline PNG 만 바뀌고
//   `apps/web/src/**` 가 안 바뀐 PR 을 차단).
//
// ─────────────────────────────────────────────────────────────────────────────
// 로컬 실행
// ─────────────────────────────────────────────────────────────────────────────
//   pnpm --filter @bts/web exec playwright test --project=visual
// 기능 E2E(`pnpm test:e2e` 의 chromium 프로젝트)에서는 이 디렉토리가 제외돼 있다
// (`playwright.config.ts` 의 `testIgnore`). 두 프로젝트가 같은 baseline 파일 1개를
// 놓고 서로 다른 뷰포트로 비교하는 것을 막기 위해서다.
//
// ─────────────────────────────────────────────────────────────────────────────
// flaky 를 만났을 때 허용되는 처방 (순서 고정)
// ─────────────────────────────────────────────────────────────────────────────
//   ① MASK_LOCATORS 에 흔들리는 요소를 추가한다
//   ② 클록·애니메이션 고정을 보강한다
// **baseline 재생성과 임계 완화는 원인 규명 전 금지**다. 같은 화면이 3회 실패하면
// 처방을 더 시도하지 말고 중단·보고한다 — 화면을 빼는 결정은 사람이 한다.
// 판별 기준. "같은 테스트가 반복 실패하는가"가 아니라 **"실패 대상이 회차마다 바뀌는가"**가
// flaky 서명이다. 단독 실행에서도 실패하면 flaky 가 아니라 **진짜 회귀**다.

import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from '../fixtures/session-fixtures'
import { issueAtlas1Fixture, issuePageFixture } from '../../src/mocks/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 브라우저 Date 고정값.
 *
 * MSW 이슈 fixture 의 날짜는 2026-01 로 박혀 있어 화면에 절대시각으로 그려지지만,
 * `new Date()` 를 읽는 코드가 하나라도 끼면 baseline 은 **찍은 날에만 맞는 그림**이 된다.
 * fixture 보다 뒤이면서 고정된 시각으로 못박아 벽시계 의존을 끊는다.
 * (`page.clock.setFixedTime` 은 타이머는 그대로 돌리고 시각만 고정한다 — calendar.spec.ts 선례.)
 */
const FIXED_NOW = new Date('2026-07-01T00:00:00.000Z')

/**
 * 허용 diff 픽셀 상한 — **가설값이다**. 파일럿에서 실제 diff 픽셀 분포를 보고 확정한다.
 *
 * 비율 임계(`threshold` 0.2)만으로는 1280×800 의 0.2% = 2,048px 까지 통과한다.
 * 24×24 아이콘 하나가 통째로 자리를 옮겨도 약 1,152px 이라 **그 통과 구간 안에 들어간다** —
 * 그러면 이 spec 은 "아이콘이 어긋난 것을 못 보는 시각 회귀 테스트"가 된다.
 */
const MAX_DIFF_PIXELS = 200

/**
 * 마스킹 대상 — **여기 한 곳에만 적는다**.
 *
 * 화면별로 흩어 적으면 두 목록이 서로를 검사하지 못한 채 갈라진다. 마스킹된 영역은
 * 단색 박스로 덮이므로 **위치·크기 변화는 여전히 잡히고 내용 변화만 무시된다**.
 *
 * 넓게 잡으면 이 spec 이 지키려던 것까지 덮어 가짜초록이 된다. 그래서 아래 셀렉터는
 * 전부 좁게 한정돼 있다 — 특히 아바타는 `banner`(상단바) 안으로 가둔다. 본문의
 * `role="img"`(이슈 타입 아이콘·라벨 칩 등)까지 걸리면 아이콘 회귀를 못 본다.
 */
const MASK_LOCATORS: readonly ((page: Page) => Locator)[] = [
  // 시각 표기 — `<time>` 요소(변경 이력 등). 클록을 고정해도 남는 시간 파생 텍스트 방어선.
  (page) => page.locator('time[datetime]'),
  // 아바타 — 상단바 계정 메뉴의 이미지 또는 이니셜 폴백 1개.
  (page) => page.getByRole('banner').locator('img, [role="img"]'),
  // 이슈 키 — 목록의 키 셀 링크(`<a aria-label="ATLAS-1" href="/issues/ATLAS-1">`) 및
  // 상세 화면의 이슈 링크. 키 문자열은 fixture 라 결정적이지만, 실 데이터로 갈아탈 때
  // 가장 먼저 흔들리는 지점이라 미리 덮는다.
  (page) => page.locator('a[aria-label][href^="/issues/"]'),
  // 이슈 키 — 상세 화면 breadcrumb 의 마지막 조각(`ATLAS / ATLAS-1` 의 키 부분).
  (page) => page.locator('nav[aria-label="이동 경로"] span:last-child'),
]

/**
 * 스크린샷 옵션 — 4개 샷이 모두 같은 조건에서 찍히도록 한 곳에 둔다.
 *
 * - `fullPage: false` — 전체 페이지는 지연 로드·가상 스크롤이 섞여 flaky 원천이다. 뷰포트만 찍는다.
 * - `animations: 'disabled'` — 전환 중간 프레임이 찍히는 것을 막는다(3대 flaky 원천 중 하나).
 * - `caret: 'hide'` — 깜빡이는 커서 제거.
 * - `scale: 'css'` — 디스플레이 배율과 무관하게 CSS 픽셀로 고정.
 */
const SHOT_OPTIONS = {
  fullPage: false,
  animations: 'disabled',
  caret: 'hide',
  scale: 'css',
  threshold: 0.2,
  maxDiffPixels: MAX_DIFF_PIXELS,
} as const

/** 이슈 목록 라우트 */
const ISSUES_URL = '/issues'
/** 이슈 상세 라우트 — MSW 기본 fixture 4건 중 ATLAS-1 */
const ISSUE_DETAIL_URL = `/issues/${issueAtlas1Fixture.key}`

/**
 * 목록 화면 기본 건수의 키 — 로딩 스켈레톤이 아니라 데이터가 다 그려진 뒤에 찍기 위한 대기 앵커.
 *
 * 키를 여기 베껴 적지 않고 MSW 핸들러가 실제로 돌려주는 fixture 에서 뽑는다.
 * 베껴 적으면 fixture 가 늘어난 날 **덜 그려진 화면을 찍고도 통과**한다.
 */
const DEFAULT_ISSUE_KEYS = issuePageFixture.content.map((issue) => issue.key)

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** {@link MASK_LOCATORS} 를 이 페이지에 바인딩한다. */
function maskFor(page: Page): Locator[] {
  return MASK_LOCATORS.map((toLocator) => toLocator(page))
}

/**
 * 지정한 테마·라우트로 진입한다.
 *
 * 테마는 `prefers-color-scheme` 에뮬레이션으로 건다. 앱의 기본 theme 설정이 `system`
 * 이라 이 한 줄이 **실제 프로덕션 경로**(PreferencesProvider → applyTheme → `<html>.dark`)를
 * 그대로 태운다. `<html>` 에 클래스를 직접 꽂는 방식과 달리 React 재렌더와 경합하지 않고,
 * 진입 전에 걸어 두면 `index.html` 의 FOUC 방지 스크립트도 같은 판정을 하므로
 * **첫 페인트부터** 목표 테마다.
 */
async function openScreen(page: Page, colorScheme: 'light' | 'dark', url: string): Promise<void> {
  await page.clock.setFixedTime(FIXED_NOW)
  await page.emulateMedia({ colorScheme })
  await loginAsAlice(page)
  await page.goto(url)
  // ★마우스를 상단바 구석으로 치운다. 로그인 폼 클릭 위치가 그대로 남아 있으면 이동한
  // 화면에서 그 좌표의 요소가 hover 상태로 찍힌다 — 목록 행에는 hover 배경색이 있어
  // "왜 이 행만 색이 다른가"로 나타난다. (0,0) 은 header 여백이라 hover 규칙이 없다.
  await page.mouse.move(0, 0)
}

/**
 * 목표 테마가 실제로 적용됐는지 확인한다.
 *
 * 이 단언이 없으면 테마 전환이 고장난 날 **라이트 화면이 다크 baseline 으로 굳는다** —
 * 이후로는 영원히 초록인 채 아무것도 안 지킨다.
 */
async function expectTheme(page: Page, colorScheme: 'light' | 'dark'): Promise<void> {
  const html = page.locator('html')
  if (colorScheme === 'dark') {
    await expect(html).toHaveClass(/(^|\s)dark(\s|$)/)
  } else {
    await expect(html).not.toHaveClass(/(^|\s)dark(\s|$)/)
  }
}

/** 목록 화면이 데이터까지 다 그려졌는지 기다린다. */
async function waitForIssueList(page: Page): Promise<void> {
  await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()
  for (const key of DEFAULT_ISSUE_KEYS) {
    await expect(page.getByTestId(`issue-summary-${key}`)).toBeVisible()
  }
}

/** 상세 화면이 데이터까지 다 그려졌는지 기다린다. */
async function waitForIssueDetail(page: Page): Promise<void> {
  await expect(
    page.getByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
  ).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. 이슈 목록 (/issues)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('시각 회귀 — 이슈 목록', () => {
  test('라이트 테마 기본 상태', async ({ page }) => {
    await openScreen(page, 'light', ISSUES_URL)
    await expectTheme(page, 'light')
    await waitForIssueList(page)

    await expect(page).toHaveScreenshot('issue-list-light.png', {
      ...SHOT_OPTIONS,
      mask: maskFor(page),
    })
  })

  test('다크 테마 기본 상태', async ({ page }) => {
    await openScreen(page, 'dark', ISSUES_URL)
    await expectTheme(page, 'dark')
    await waitForIssueList(page)

    await expect(page).toHaveScreenshot('issue-list-dark.png', {
      ...SHOT_OPTIONS,
      mask: maskFor(page),
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. 이슈 상세 (/issues/ATLAS-1)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('시각 회귀 — 이슈 상세', () => {
  test('라이트 테마 기본 상태', async ({ page }) => {
    await openScreen(page, 'light', ISSUE_DETAIL_URL)
    await expectTheme(page, 'light')
    await waitForIssueDetail(page)

    await expect(page).toHaveScreenshot('issue-detail-light.png', {
      ...SHOT_OPTIONS,
      mask: maskFor(page),
    })
  })

  test('다크 테마 기본 상태', async ({ page }) => {
    await openScreen(page, 'dark', ISSUE_DETAIL_URL)
    await expectTheme(page, 'dark')
    await waitForIssueDetail(page)

    await expect(page).toHaveScreenshot('issue-detail-dark.png', {
      ...SHOT_OPTIONS,
      mask: maskFor(page),
    })
  })
})
