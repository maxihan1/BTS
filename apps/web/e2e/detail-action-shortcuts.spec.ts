// FR-UX-10 F11 E2E — 이슈 상세 액션 단축키 (a·i·m·e·s·w + `.`) 와 레이어 경계
//
// 시나리오 (plan Task 8 표 12행 그대로)
//   S1  a   — 담당자 검색 입력으로 포커스가 간다
//   S2  i   — 담당자가 나(alice)로 바뀐다
//   S3  i   — 재입력하면 해제된다(토글)
//   S4  m   — 댓글 탭으로 전환된 뒤 작성 입력에 포커스가 간다
//   S5  e   — 제목 편집 입력이 **현재 제목이 채워진 채로** 열린다
//   S6  s   — 즐겨찾기 aria-pressed 가 반전된다
//   S7  w   — 관심(watch) aria-pressed 가 반전된다
//   S8  .   — 명령 팔레트가 열린다
//   S9  ★와이드 동시 생존 — split view 에서 `j` 로 커서를 옮긴 뒤 `m` 으로 우측 댓글에 들어간다
//   S10 ★E1 입력 중 무발화 — 댓글에 `a.m` 을 쳐도 팔레트도 포커스 이동도 없다
//   S11 E9 좁은 폭 — 375px 전체화면 상세에서 `s`/`w`/`a` 가 그대로 동작한다(폭 무관)
//   S12 ★E5 전체화면에서 `j` — URL 이 안 바뀌고 **브라우저 기본 동작이 살아 있다**
//
// 설계 메모
//   - 상세 액션은 전부 기존 컨트롤을 미는 방식이라 신규 MSW 핸들러가 필요 없다. 담당자·
//     즐겨찾기·관심 mutation 은 각각 issue-handlers / favorite-handlers / issue-watcher-handlers
//     의 stateful 오버라이드에 영속하므로 refetch 후에도 값이 롤백되지 않는다.
//   - 상세 진입은 SPA 내부 이동(pushState+popstate)으로 한다 — `page.goto` 는 ServiceWorker 를
//     재기동시켜 favoriteStore/watcherStore 를 리셋한다(favorites.spec.ts · issue-watchers.spec.ts
//     선례). 목록(S9)만 `page.goto('/issues')` 를 쓴다(context-shortcuts.spec.ts 선례).
//   - 셀렉터는 i18n 정본(`src/i18n/ko`)과 fixture 정본(`src/mocks/*`)에서 끌어온다.
//     하드코딩은 팔레트 dialog label 하나뿐이고 그건 비-export 라 기존 선례를 따른다.
//
// 🛑 negative 단언에는 **settle barrier** 를 세운다.
//   `page.keyboard.press` 는 이벤트 디스패치까지만 기다린다. 회귀로 URL 이 바뀌거나 팔레트가
//   열리더라도 그 갱신은 단언보다 **늦게** 도착하므로, 동기 읽기는 갱신 전 상태를 보고
//   통과한다 — F10 이 실제로 이 함정에 빠져 대표 시나리오가 공허했다. 그래서 negative 앞에는
//   **효과가 관측되는 키를 눌러 한 왕복이 끝난 것을 확인**하고 그 시점에 단언한다.
//   배리어 위치는 S10 · S12 각 주석에 명시했다.
import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { commentStrings, issueDetailStrings } from '../src/i18n/ko'
import { issueAtlas1Fixture } from '../src/mocks/issue-fixtures'
import { userAliceFixture } from '../src/mocks/user-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 대상 이슈 — ATLAS-1 (assigneeId=null · labels=[] 인 기본 시드) */
const ISSUE_KEY = issueAtlas1Fixture.key
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** 이슈 목록 URL — S9(와이드 split view) 전용 */
const ISSUES_URL = '/issues'

/** Header 검색 버튼 aria-label — RootLayout(단축키 리스너 등록) 마운트 완료 신호 */
const HEADER_SEARCH_ARIA_LABEL = '검색'

/** CommandPalette.tsx commandPaletteStrings.dialogLabel(비export) — command-palette.spec.ts 와 같은 하드코딩 선례 */
const PALETTE_DIALOG_LABEL = '명령 팔레트'

/** alice 표시 이름 — IssueAssigneeSelect 의 displayName 우선 폴백과 같은 규칙으로 유도 */
const ALICE_DISPLAY_NAME = userAliceFixture.displayName ?? userAliceFixture.username

/** split 분기 기준(min-width: 1024px)을 넘는 와이드 뷰포트 — issue-split-view.spec.ts 미러 */
const WIDE_VIEWPORT = { width: 1280, height: 900 }

/** E9 좁은 폭 — 모바일 기준선 375px */
const NARROW_VIEWPORT = { width: 375, height: 812 }

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 진입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로 로그인하고 RootLayout(전역 keydown 리스너) 마운트 완료까지 기다린다.
 *
 * context-shortcuts.spec.ts · command-palette.spec.ts 의 같은 이름 헬퍼 미러 —
 * Header 검색 버튼 렌더가 인증 분기 렌더 + 리스너 등록 완료 신호다.
 */
async function loginAndWaitForRootReady(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('button', { name: HEADER_SEARCH_ARIA_LABEL, exact: true }),
  ).toBeVisible()
}

/**
 * 이슈 상세로 SPA 내부 이동한다(ServiceWorker 재기동 없음).
 *
 * 메타패널 담당자 섹션이 보이는 시점 = 이슈 로딩 완료 = `detailShortcutsEnabled` 가 켜져
 * `issue-detail` 레이어가 등록된 시점이다.
 */
async function gotoIssueDetail(page: Page): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, ISSUE_URL)
  await expect(page.getByTestId('assignee-section')).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 로케이터
// ─────────────────────────────────────────────────────────────────────────────

/** 담당자 검색 입력 — 접근성 이름은 `사용자 검색`(실측 정정). assignee-section 으로 한정 */
function assigneeSearchInput(page: Page): Locator {
  return page
    .getByTestId('assignee-section')
    .getByRole('textbox', { name: issueDetailStrings.assigneeSearchPlaceholder, exact: true })
}

/** 현재 담당자 표시 이름 — 미할당이면 `미지정` */
function assigneeCurrentName(page: Page): Locator {
  return page.getByTestId('assignee-section').getByTestId('assignee-current-name')
}

/** 댓글 작성 textarea — 댓글 탭이 활성일 때만 마운트된다(Radix Tabs 가 비활성 탭을 언마운트) */
function commentInput(page: Page): Locator {
  return page.getByRole('textbox', { name: commentStrings.commentBodyLabel, exact: true })
}

/** 즐겨찾기 토글 버튼 (`aria-pressed` 보유, 비활성은 `aria-disabled`) */
function favoriteToggle(page: Page): Locator {
  return page.getByTestId('favorite-section').getByTestId('favorite-button')
}

/** 관심(watch) 토글 버튼 (`aria-pressed` 보유, 비활성은 `aria-disabled`) */
function watchToggle(page: Page): Locator {
  return page.getByTestId('watch-toggle-button')
}

/**
 * 관심 토글이 **실제로 눌릴 수 있는 상태**가 될 때까지 기다린다.
 *
 * ★버튼이 보인다 ≠ 누를 수 있다. 감시자 GET 이 아직 도는 동안 이 버튼은
 * `aria-disabled="true"` 이고, `handleToggle` 의 `canToggle` 가드가 클릭을 삼킨다(E8 중복
 * 발행 차단). 네이티브 `disabled` 가 아니라 `aria-disabled` 라(Task 5b — 포커스를 잃지
 * 않으려고 일부러 그렇게 뒀다) Playwright 의 actionability 검사에도 걸리지 않는다.
 * 이 대기를 빼면 로딩이 조금만 늦는 회차에서 `w` 가 조용히 무동작이 된다(실측 실패).
 */
async function waitForWatchToggleReady(page: Page): Promise<void> {
  await expect(watchToggle(page)).toHaveAttribute('aria-disabled', 'false')
}

/** 명령 팔레트 dialog */
function commandPalette(page: Page): Locator {
  return page.getByRole('dialog', { name: PALETTE_DIALOG_LABEL })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — keydown 관측기 (S12 전용)
//
// `defaultPrevented` 는 DOM 이벤트에만 남는 값이라 화면으로는 볼 수 없다. 앱 리스너
// (RootLayout `useKeyboardShortcuts`, document bubble)보다 **나중에** 같은 대상·같은 단계에
// 리스너를 하나 더 달면 앱이 `preventDefault` 를 했는지 그대로 읽힌다.
// ─────────────────────────────────────────────────────────────────────────────

/** 관측기가 남기는 한 건 — 어떤 키가 눌렸고 그때 기본 동작이 막혔는가 */
interface KeydownRecord {
  readonly key: string
  readonly defaultPrevented: boolean
}

// ★`page.evaluate` 콜백은 브라우저로 직렬화돼 넘어가므로 **바깥 타입을 참조할 수 없다.**
// 그래서 전역 슬롯 타입을 콜백 안에 각각 인라인으로 적는다(공유 alias 를 두면 런타임에
// 사라진 이름을 참조하게 된다).

/** keydown 관측기를 현재 문서에 심는다 (SPA 내부 이동으로는 사라지지 않는다) */
async function installKeydownProbe(page: Page): Promise<void> {
  await page.evaluate(() => {
    const probeWindow = window as Window & {
      __btsKeydownProbe?: { key: string; defaultPrevented: boolean }[]
    }
    const records: { key: string; defaultPrevented: boolean }[] = []
    probeWindow.__btsKeydownProbe = records
    document.addEventListener('keydown', (event) => {
      records.push({ key: event.key, defaultPrevented: event.defaultPrevented })
    })
  })
}

/**
 * 관측기가 모은 기록에서 특정 키만 골라 읽는다.
 *
 * @param key `e.key` 값 (예: `j`)
 */
async function readKeydownRecords(page: Page, key: string): Promise<readonly KeydownRecord[]> {
  return page.evaluate((target: string) => {
    const probeWindow = window as Window & {
      __btsKeydownProbe?: { key: string; defaultPrevented: boolean }[]
    }
    return (probeWindow.__btsKeydownProbe ?? []).filter((record) => record.key === target)
  }, key)
}

/** 현재 커서(=split 선택) 이슈 키를 URL 에서 읽는다 — context-shortcuts.spec.ts 미러 */
function selectedFromUrl(page: Page): string | null {
  return new URL(page.url()).searchParams.get('selected')
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite — 전체화면 상세 (기본 뷰포트)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-10 F11 이슈 상세 액션 단축키', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueDetail(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1. a — 담당자 검색 포커스
  //
  // Given  ATLAS-1 상세에 있고 담당자 검색 입력에 포커스가 없다
  // When   `a` 를 누르면
  // Then   담당자 검색 입력으로 포커스가 옮겨간다
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 a → 담당자 검색 입력으로 포커스가 간다', async ({ page }) => {
    const search = assigneeSearchInput(page)
    await expect(search).toBeVisible()
    await expect(search).not.toBeFocused()

    await page.keyboard.press('a')

    await expect(search).toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. i — 나에게 할당
  //
  // Given  ATLAS-1 은 미할당(fixture assigneeId=null)이다
  // When   `i` 를 누르면
  // Then   담당자 표시가 로그인 사용자(김앨리스)로 바뀐다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 i → 담당자가 나로 바뀐다', async ({ page }) => {
    const currentName = assigneeCurrentName(page)
    await expect(currentName).toHaveText(issueDetailStrings.assigneeUnassigned)

    await page.keyboard.press('i')

    // PATCH → invalidate → 단건 refetch 까지 auto-retry 로 기다린다
    await expect(currentName).toHaveText(ALICE_DISPLAY_NAME)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. i 재입력 — 해제(토글)
  //
  // Given  `i` 로 나에게 할당된 상태다
  // When   `i` 를 한 번 더 누르면
  // Then   담당자가 해제되어 `미지정` 으로 돌아온다 (Jira `Toggle` 문구 대응)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 i 재입력 → 담당자가 해제된다 (토글)', async ({ page }) => {
    const currentName = assigneeCurrentName(page)

    // Given. 먼저 나에게 할당하고, **refetch 완료를 확인한 뒤** 두 번째 입력을 보낸다.
    // 이 대기가 없으면 두 번째 `i` 가 옛 `assigneeId` 로 판정해 같은 값을 다시 쓴다.
    await page.keyboard.press('i')
    await expect(currentName).toHaveText(ALICE_DISPLAY_NAME)

    // When. 재입력
    await page.keyboard.press('i')

    // Then. 해제
    await expect(currentName).toHaveText(issueDetailStrings.assigneeUnassigned)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. m — 댓글 탭 전환 + 입력 포커스
  //
  // Given  활동 영역 기본 탭은 「이력」이라 댓글 작성 입력이 아직 마운트돼 있지 않다
  // When   `m` 을 누르면
  // Then   댓글 탭으로 전환되고 작성 입력에 포커스가 간다
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 m → 댓글 탭으로 전환한 뒤 작성 입력에 포커스가 간다', async ({ page }) => {
    // Given. 댓글 탭은 아직 비활성 — 입력창은 DOM 에 없다
    const commentTab = page.getByRole('tab', {
      name: issueDetailStrings.activityCommentTabLabel,
      exact: true,
    })
    await expect(commentTab).toHaveAttribute('aria-selected', 'false')
    await expect(commentInput(page)).toHaveCount(0)

    // When. m
    await page.keyboard.press('m')

    // Then. 탭 전환 + 포커스
    await expect(commentTab).toHaveAttribute('aria-selected', 'true')
    await expect(commentInput(page)).toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. e — 제목 편집 진입
  //
  // Given  제목이 읽기 모드(h1)로 보인다
  // When   `e` 를 누르면
  // Then   제목 편집 입력이 **현재 제목이 채워진 채로** 열린다
  //        (빈 값으로 열리면 Enter 한 번에 제목이 지워진다 — 그 회귀를 여기서 막는다)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 e → 제목 편집 입력이 현재 제목으로 채워진 채 열린다', async ({ page }) => {
    await expect(
      page.getByRole('heading', { level: 1, name: issueAtlas1Fixture.summary, exact: true }),
    ).toBeVisible()

    await page.keyboard.press('e')

    const titleInput = page.getByRole('textbox', {
      name: issueDetailStrings.titleEditLabel,
      exact: true,
    })
    await expect(titleInput).toBeVisible()
    await expect(titleInput).toHaveValue(issueAtlas1Fixture.summary)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6. s — 즐겨찾기 토글
  //
  // Given  새 브라우저 컨텍스트라 favoriteStore 가 비어 있다(aria-pressed=false)
  // When   `s` 를 누르면
  // Then   aria-pressed 가 true 로 반전되고, 그 버튼에 포커스가 남는다
  //        (포커스가 남아야 스크린리더가 상태 변화를 읽는다 — plan F-2 접근성 계약)
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 s → 즐겨찾기 aria-pressed 가 반전된다', async ({ page }) => {
    const favorite = favoriteToggle(page)
    await expect(favorite).toHaveAttribute('aria-pressed', 'false')

    await page.keyboard.press('s')

    await expect(favorite).toHaveAttribute('aria-pressed', 'true')
    await expect(favorite).toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7. w — 관심(watch) 토글
  //
  // Given  새 브라우저 컨텍스트라 watcherStore 가 비어 있다(aria-pressed=false)
  // When   `w` 를 누르면
  // Then   aria-pressed 가 true 로 반전되고, 그 버튼에 포커스가 남는다
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 w → 관심 aria-pressed 가 반전된다', async ({ page }) => {
    const watch = watchToggle(page)
    await expect(watch).toHaveAttribute('aria-pressed', 'false')
    await waitForWatchToggleReady(page)

    await page.keyboard.press('w')

    await expect(watch).toHaveAttribute('aria-pressed', 'true')
    await expect(watch).toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S8. . — 명령 팔레트 열기
  //
  // Given  팔레트가 닫혀 있다
  // When   `.` 을 누르면
  // Then   팔레트 dialog 가 열린다 (`.` 은 여는 것만 한다 — 닫기는 Esc)
  // ───────────────────────────────────────────────────────────────────────────
  test('S8 . → 명령 팔레트가 열린다', async ({ page }) => {
    await expect(commandPalette(page)).toHaveCount(0)

    await page.keyboard.press('.')

    await expect(commandPalette(page)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S10. ★E1 — 입력 중에는 아무 단축키도 발화하지 않는다
  //
  // Given  `m` 으로 댓글 작성 입력에 들어와 있다
  // When   `a` `.` `m` 을 그대로 타이핑하면
  // Then   세 글자가 그대로 입력되고, 팔레트도 열리지 않고 포커스도 옮겨가지 않는다
  //
  // ★settle barrier. 댓글 입력 안에서는 어떤 키도 단축키 효과가 없으므로 "효과가 관측되는
  //   키"란 곧 **입력된 글자 자체**다. textarea 는 controlled 라 `toHaveValue('a.m')` 가
  //   마지막 키까지 React 커밋이 끝났음을 증명한다. `.` 가 팔레트를 열었다면 그 setState 는
  //   같은 keydown 안에서 발생하므로 이 커밋보다 **앞서** 반영된다 — 즉 이 배리어 뒤의
  //   negative 는 갱신 전 상태를 보고 통과할 수 없다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S10 댓글 입력 중 a.m 은 단축키로 발화하지 않는다 (E1)', async ({ page }) => {
    // Given. 댓글 입력 진입
    await page.keyboard.press('m')
    const input = commentInput(page)
    await expect(input).toBeFocused()

    // When. 단축키와 같은 글자들을 그대로 타이핑
    await page.keyboard.type('a.m')

    // ★settle barrier — 마지막 글자까지 왕복이 끝난 것을 확인한다
    await expect(input).toHaveValue('a.m')

    // Then. `.` 이 팔레트를 열지 않았다
    await expect(commandPalette(page)).toHaveCount(0)
    // Then. `a`·`m` 이 포커스를 옮기지 않았다
    await expect(input).toBeFocused()
    await expect(assigneeSearchInput(page)).not.toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S12. ★E5 — 전체화면 상세에서 `j` 는 죽되 **브라우저 기본 동작은 살아 있다**
  //
  // Given  전체화면 상세다 — `issue-list` 레이어는 등록돼 있지 않다
  // When   `j` 를 누르면
  // Then   URL 이 바뀌지 않고, 그 keydown 의 `defaultPrevented` 가 false 다
  //
  // ★이 시나리오가 ADR D-5-a 의 존재 이유다. 정적 폴백표만 보면 판별이 성공해
  //   `preventDefault` 까지 한 뒤 핸들러가 없어 아무 일도 안 일어난다 — 사용자에게는
  //   브라우저 기본 동작(Firefox quick-find 등)만 사라진 상태로 보인다. "URL 이 안 바뀐다"
  //   만 재면 그 상태도 통과하므로 `defaultPrevented` 를 함께 잰다.
  //
  // ★settle barrier. `j` 다음에 **효과가 관측되는 키** `.` 을 눌러 팔레트가 뜨는 것으로
  //   한 왕복이 끝난 것을 확인하고, 그 시점에 URL 불변을 단언한다. 회귀로 `j` 가 라우팅을
  //   일으켰다면 그 히스토리 갱신은 이 배리어 안에 반드시 도착한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S12 전체화면 상세에서 j 는 URL 도 기본 동작도 건드리지 않는다 (E5)', async ({ page }) => {
    await installKeydownProbe(page)
    const urlBefore = page.url()

    // When. j — 이 화면에는 목록 레이어가 없다
    await page.keyboard.press('j')

    // ★settle barrier — 효과가 관측되는 키로 한 왕복 완료를 확인한다
    await page.keyboard.press('.')
    await expect(commandPalette(page)).toBeVisible()

    // Then. 왕복이 끝난 지금도 URL 이 그대로다
    expect(page.url()).toBe(urlBefore)

    // Then. `j` 의 기본 동작이 살아 있다 — 판별만 성공하고 삼키는 상태를 금지한다
    const jRecords = await readKeydownRecords(page, 'j')
    expect(jRecords).toHaveLength(1)
    expect(jRecords[0]?.defaultPrevented).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Suite — 와이드 split view (S9)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-10 F11 와이드 split view 동시 생존', () => {
  test.use({ viewport: WIDE_VIEWPORT })

  // ───────────────────────────────────────────────────────────────────────────
  // S9. ★와이드 동시 생존 — 상세 레이어가 목록 항법을 죽이지 않는다
  //
  // Given  와이드 `/issues` 에서 `j` 로 첫 행을 잡아 우측 상세 페인을 연다
  //        (이 순간 `issue-detail` 과 `issue-list` 두 레이어가 **동시에** 등록된다)
  // When   `j` 를 한 번 더 눌러 커서를 옮기고, 이어서 `m` 을 누르면
  // Then   커서는 다음 행으로 이동하고(목록 항법 생존), `m` 은 우측 상세의 댓글 입력을
  //        연다(상세 액션 생존)
  //
  // ★이 FR 에서 가장 깨지기 쉬운 지점이다. 상세 레이어를 단독 활성으로 두면 F10 이 만든
  //   `j`/`k` 가 **이 화면에서만** 죽는다(CONTEXT_LAYERS['issue-detail'] 폴백이 그 방어다).
  // ───────────────────────────────────────────────────────────────────────────
  test('S9 split view 에서 j 로 커서를 옮긴 뒤 m 으로 우측 댓글에 들어간다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await page.goto(ISSUES_URL)
    await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()

    // Given. j — 첫 행을 잡고 우측 상세 페인이 열린다(= issue-detail 레이어 등록)
    await page.keyboard.press('j')
    await expect(page).toHaveURL(/selected=/)
    const firstKey = selectedFromUrl(page)
    expect(firstKey).not.toBeNull()
    await expect(page.getByTestId('assignee-section')).toBeVisible()

    // When 1. 상세 레이어가 활성인 지금도 목록 항법이 살아 있어야 한다
    await page.keyboard.press('j')
    await expect.poll(() => selectedFromUrl(page)).not.toBe(firstKey)
    const secondKey = selectedFromUrl(page)
    expect(secondKey).not.toBeNull()

    // 페인이 두 번째 이슈로 다시 마운트되기를 기다린다 — 재마운트 중에는 상세 레이어 등록이
    // 잠깐 비므로, 그 창에 `m` 을 쏘면 조용히 무동작이 된다. 목록 행에서 요약을 읽어
    // 페인 제목(h2)이 그 이슈로 바뀐 것을 확인한다(하드코딩 대신 화면에서 유도).
    const secondSummary = (
      await page.getByTestId(`issue-summary-${secondKey ?? ''}`).innerText()
    ).trim()
    await expect(
      page.getByRole('heading', { level: 2, name: secondSummary, exact: true }),
    ).toBeVisible()

    // When 2. 같은 화면에서 상세 액션도 살아 있다
    await page.keyboard.press('m')

    // Then. 우측 페인의 댓글 작성 입력에 포커스가 간다
    await expect(commentInput(page)).toBeFocused()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Suite — 좁은 폭 (S11)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-10 F11 좁은 폭 상세 액션', () => {
  test.use({ viewport: NARROW_VIEWPORT })

  // ───────────────────────────────────────────────────────────────────────────
  // S11. E9 — 375px 전체화면 상세에서도 상세 액션은 그대로 동작한다
  //
  // Given  375px 뷰포트의 전체화면 상세다
  // When   `s` → `w` → `a` 를 차례로 누르면
  // Then   셋 다 그대로 동작한다 — 상세 액션은 커서 4종(F10)과 달리 **폭 무관**이다
  //
  // ★키 순서가 곧 설계다. `a` 를 먼저 누르면 포커스가 검색 입력으로 들어가 뒤따르는
  //   `s`/`w` 가 입력 문자로 삼켜진다(shouldIgnoreEvent). 버튼 → 버튼 → 입력 순으로 민다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S11 375px 전체화면 상세에서 s·w·a 가 그대로 동작한다 (E9)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueDetail(page)

    // When 1. s — 즐겨찾기
    const favorite = favoriteToggle(page)
    await expect(favorite).toHaveAttribute('aria-pressed', 'false')
    await page.keyboard.press('s')
    await expect(favorite).toHaveAttribute('aria-pressed', 'true')

    // When 2. w — 관심 (포커스는 버튼 위라 입력 가드에 걸리지 않는다)
    const watch = watchToggle(page)
    await expect(watch).toHaveAttribute('aria-pressed', 'false')
    await waitForWatchToggleReady(page)
    await page.keyboard.press('w')
    await expect(watch).toHaveAttribute('aria-pressed', 'true')

    // When 3. a — 담당자 검색 포커스 (마지막에 민다)
    await page.keyboard.press('a')
    await expect(assigneeSearchInput(page)).toBeFocused()
  })
})
