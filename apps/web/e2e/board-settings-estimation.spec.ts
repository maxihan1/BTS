// 보드 설정 — 추정 · 작업일 탭 E2E (부채 177 Task 23·33 · J36·J37·J38·J39·J40)
//
// ## 이 spec 이 지는 판정 — 각 축이 무엇과 무엇을 가르나 (뮤테이션 **실측** 2026-09-06)
//
// 「죽는 테스트」는 예상이 아니라 **실제로 돌려서 받은 결과**다.
//
// ★**어느 명령으로 잰 결과인가 — 두 열은 스코프가 다르다.**
//   **스코프를 안 밝힌 「죽는 테스트」가 이 표를 한 번 썩혔다**(부채 177 Task 34 가 정정).
//   - **단위 열** — 2026-09-06 실측.
//     `(cd apps/web && node_modules/.bin/vitest related --watch=false \`
//     `   src/components/board/settings/EstimationPanel.tsx src/components/board/settings/WorkingDaysPanel.tsx \`
//     `   src/mocks/board-handlers.ts src/mocks/burndown-handlers.ts)`
//     → **21파일 460건** · 무변경 baseline 종료 코드 0.
//     `related` 는 나열한 파일을 **모듈 그래프로 import 하는 테스트 전부**를 고른다 —
//     그래프 밖 테스트는 이 파일들을 읽지 않으니 죽을 수 없다. 그래서 「0건」은 사실상
//     「단위 전체에서 0건」이다. 그 21파일에는 `src/routes/__tests__/sprint-burndown.test.tsx` 도 있다.
//     ★종료 코드는 **파일로 리다이렉트하고 따로** 잡아라. `| tail` 을 붙이면 `$?` 가 tail 의 것이 된다.
//   - **E2E 열** — `(cd apps/web && node_modules/.bin/playwright test e2e/board-settings-estimation.spec.ts)`.
//     ★**Task 34 에서는 재확인하지 못했다** — 개발 서버 포트가 다른 작업의 직렬 락 아래였다.
//     Task 23·33 당시 실측을 그대로 둔 값이고, 새로 세운 ⑬·⑭ 의 E2E 열은 **확인 못 함**이다.
//   - **⑥·⑦(백엔드 축)은 어느 쪽으로도 안 쟀다** — Kotlin 스위트는 이 task 의 스코프 밖이다.
//
// | 뮤테이션 | 단위에서 함께 죽는 것 (실측) | E2E (★미재확인) | 가르는 것 |
// |---|---|---|---|
// | ① `EstimationPanel.locked` 에서 `!isScrum` 을 뺀다 | `EstimationPanel.test.tsx` **T-ES-2** (1건) | **S1 1건만** | 「칸반에서도 열린다」 ↔ 스크럼 전용 (J37) |
// | ② `EstimationPanel.locked` 를 `true` 로 고정 (항상 잠김) | `EstimationPanel.test.tsx` **T-ES-1 · T-ES-8 · T-ES-9 · T-ES-10 · T-ES-11 · T-ES-13 · T-ES-14** (7건) | **S2~S4 1건만** | 「항상 잠긴 구현」 ↔ 스크럼에서 열린다 — ①의 **대조군** |
// | ③ `board-handlers.toSettingsPayload` 가 `workingDays` 를 항상 미설정으로 낸다 (**배선 절단**) | **0건** — 460 전부 초록 | **S2~S4 1건만** | 저장이 서버에 닿았나 ↔ 화면 state 만 바뀌었나 |
// | ④ `WorkingDaysPanel` 저장 요청에서 `timezone` 을 뺀다 (`timezone: stored?.timezone ?? null`) | `WorkingDaysPanel.test.tsx` **T-WD-12** (1건) | **S2~S4 1건만** | 「타임존만 바꿨는데 저장이 안 된다」 ↔ 3축 동시 전송 |
// | ⑤ `WorkingDaysPanel` 초기값을 미설정 대신 월~금으로 채운다 (**항상 좁힌다**) | `WorkingDaysPanel.test.tsx` **T-WD-2 · T-WD-4 · T-WD-18 · T-WD-20** (4건) | **6건 전부** | 미설정을 「근무일 0개/월~금」으로 뭉갬 ↔ 두 상태를 가름 (R6) |
// | ⑥ **백엔드** 서비스가 근무일을 `BurndownCalculator` 에 넘기지 않는다 (**배선 절단**) | — (백엔드 축 · 프론트 vitest 스코프 밖이라 **안 쟀다**) | — **여전히 못 잡는다** (아래 ★★) | — |
// | ⑦ **백엔드** 번다운이 타임존을 무시하고 UTC 로 고정한다 | — (같음 · **안 쟀다**) | — **여전히 못 잡는다** (아래 ★★) | — |
// | ⑧ **목**이 보드 작업일을 안 읽는다 — `withBoardWorkingDays` 가 시드를 그대로 낸다 (**배선 절단**) | **0건** — 460 전부 초록 | **S5·S6 2건** | 목이 공유 store 에서 파생하나 ↔ 정적 store (Task 33 RED 상태 그 자체) |
// | ⑨ **목**이 `nonWorkingDates` 를 무시한다 (`new Set<string>()`) | **0건** | **S5 1건만** | 특정 날짜 제외가 x축에서 빠지나 (J39) |
// | ⑩ **목**이 타임존을 무시하고 UTC 로 고정한다 (`boardLocalDate(…, null)`) | **0건** | **S6 1건만** | worklog 일 귀속이 보드 타임존을 따르나 (J40 · R10) |
// | ⑪ **목**이 미설정(`null`)을 월~금으로 채운다 (**항상 좁힌다**) | **0건** | — **못 잡는다** (아래 ★★2) | — |
// | ⑫ **목**이 `[]`(근무일 0개)를 `null` 처럼 다룬다 | **0건** | — **못 잡는다** (아래 ★★2) | — |
// | ⑬ `WorkingDaysPanel` 저장 버튼이 **타임존만** 보낸다 — `submit({ standardDays: null, nonWorkingDates: [], timezone })` | `WorkingDaysPanel.test.tsx` **T-WD-7 · T-WD-8 · T-WD-9 · T-WD-10 · T-WD-11 · T-WD-20** (6건) | **확인 못 함** (S4 의 「타임존만 바꿔도 다른 두 축이 산다」 자리) | PUT 은 **3축 교체**라 한 축만 보내면 나머지가 지워진다 ↔ 3축 동시 전송 (④의 **반대 방향**) |
// | ⑭ `board-handlers.settingsFor` 가 보드가 아니라 **프로젝트**를 키로 쓴다 — 저장이 옆 보드로 샌다 | `board-handlers.test.ts` **T-MSW-CL-2 · T-MSW-CL-3** · `IssueMetaPanel.test.tsx` **T21-1 · T21-2 · T21-4 · T21-5** (6건) | **확인 못 함** (S4 대조군 「옆 칸반 보드는 여전히 미설정」 자리) | 보드 단위 저장 ↔ **프로젝트 단위로 샌다** (편차 X7) |
//
// ★**번호는 append-only 다.** ⑬·⑭ 는 뒤에 붙였다 — 중간에 끼우거나 재번호하면 본문 인라인
//   참조가 조용히 썩는다. 이 파일이 실제로 겪었다(`19dcdc0e7` 가 ①~⑪ → ①~⑦ 로 재번호하며
//   머리말 두 줄만 고쳐 본문 참조 6군데가 옛 번호를 가리켰다 · 부채 177 Task 34 가 정정).
// ★**⑬·⑭ 는 「표가 코드보다 *적게* 주장하던」 자리를 되살린 것이다.** 본문 S4 와 S4 대조군이
//   이미 그 두 축을 단언으로 지고 있는데 표에는 행이 없었다(⑭ 는 재번호 때 사라진 옛 ⑧).
//   ★두 행의 E2E 열은 **확인 못 함**이다 — Task 34 는 Playwright 를 못 돌렸다(아래 스코프 참고).
//   단위 열만 실측이고, 「S4 에서 죽는다」는 **단언 위치로 읽은 구조적 추정**이지 실측이 아니다.
// ★**③·⑧·⑨·⑩·⑪·⑫ 의 「0건」이 이 spec 의 존재 이유다.** MSW 목의 파생과 GET 봉투는
//   프론트 단위가 한 건도 안 잡는다 — 그 스코프에 `src/routes/__tests__/sprint-burndown.test.tsx`
//   가 들어 있는데도 그렇다. 반대로 ①②④⑤⑬⑭ 는 단위가 **먼저** 죽는다. 「이 spec 만 잡는다」는
//   함의를 표 전체에 붙이면 안 된다.
//
// ★**⑤ 만 좁게 특정되지 않는다** — 6건 전부가 죽는다. 「미설정」이라는 상태 자체를 지우는
//   변경이라 작업일을 만지는 모든 시나리오의 출발점(`근무일 고르기` 버튼)이 사라지기 때문이다.
//   좁히려고 단언을 빼지 않았다 — 넓은 것이 사실이고, 그 사실이 곧 「미설정은 이 화면의
//   뼈대」라는 뜻이다.
// ★**①②는 짝으로만 산다.** 잠금만 재면 「항상 잠긴 구현」이 통과한다.
// ★**S5 와 S8 도 짝으로만 산다.** S5 만 두면 「무조건 좁히는 구현」이, S8 만 두면
//   「아무것도 안 좁히는 구현」이 통과한다.
//
// ★★**⑥·⑦ 은 이 spec 이 여전히 잡지 못한다 — 프론트 E2E 는 백엔드를 밟지 않는다.**
//   이 spec 은 MSW 목 위에서 돈다. 백엔드 서비스가 근무일을 계산기에 안 넘기든(⑥) 타임존을
//   UTC 로 고정하든(⑦) 이 화면은 한 픽셀도 안 바뀐다. 그 판정자는 백엔드다 —
//   `BurndownWorkingDaysTest`(Task 11) · `BurndownTimezoneTest` ·
//   `SprintBurndownIntegrationTest`(Task 12).
//   ★**바뀐 것은 목이다**(Task 33 · 2026-09-06). `src/mocks/burndown-handlers.ts` 가 이제
//   `board-handlers.ts` 의 `boardSettingsStore` 를 **유일한 공개 창구인 `GET /api/v1/boards/{id}`
//   로 경유해** 읽어 `points` 를 파생한다. 그래서 **목 층의 같은 결함**(⑧·⑨·⑩)은 이 spec 이 잡고,
//   Task 23 이 「구조적으로 red」라 적었던 자리가 닫혔다.
//   목의 파생 규칙이 백엔드 `BurndownCalculator`·`SprintBurndownService` 와 **어디까지 같고
//   어디부터 다른지**는 `burndown-handlers.ts` 의 「목이 백엔드와 같은 것 / 다른 것」 두 표가
//   정본이다 — 여기 복사하지 않는다(복사본은 두 번째 진실이 된다).
//   ★그리고 **S7 은 초록으로 남긴다.** S5·S6 이 밟는 SPA 경로와 recharts x축 셀렉터를 따로
//   지키는 자리라, 두 건이 초록이 된 뒤에도 「왜 red 인가」의 후보를 좁혀 준다.
//
// ★★2 **⑪·⑫ 는 이 픽스처에서 관측되지 않는다 — 공허를 감추지 않으려고 적어 둔다.**
//   - **⑪** 미설정을 월~금으로 채워도 이 스프린트의 x축은 그대로다. `DEFAULT_BURNDOWN` 의 5일
//     (2026-06-01 월 ~ 06-05 금)이 **전부 평일**이라 월~금 필터가 한 날도 빼지 못한다. 시드가
//     들고 있는 미래 null 두 칸은 축이 아니라 **값**이라 x축 단언에 걸리지 않는다.
//     주말을 낀 픽스처로 바꾸면 잡히지만, 그러면 `burndownAxisTicks` 가 「양 끝 tick 은 좁혀도
//     남는다」는 전제를 잃는다 — 부분 렌더 가짜통과를 막는 장치다(커밋 `19dcdc0e7`).
//     이 축은 백엔드가 짝으로 이미 잰다 — `BurndownWorkingDaysTest` 의
//     `control pair - unset keeps all 14 calendar days while MON-FRI narrows to 10 working days`.
//   - **⑫** `standardDays: []` 는 **API 로 도달할 수 없다.** `PUT /boards/{id}/working-days` 가
//     0개를 400 으로 막는다(스펙 E1). 화면에서 만들 수 없는 상태를 e2e 가 지키면 그것이 곧
//     가짜 그린이다. 그래도 목은 `??` 로 뭉개지 않는다 — 뭉개는 순간 미설정과 같은 코드가 된다.
//   ★**⑧ 을 넣고 `vitest run src/mocks src/routes/__tests__/sprint-burndown.test.tsx
//     src/router.test.tsx src/components/burndown src/api/burndown.test.ts` 를 돌리면 1029건이
//     전부 통과한다**(2026-09-06 실측). 목의 파생을 재는 프론트 판정자는 이 spec 하나뿐이다.
//   ★**이 주장을 Task 34 가 다른 스코프로 재확인했다**(2026-09-06). 위 「단위 열」 스코프
//     (`vitest related` · 21파일 460건)에서 ⑧뿐 아니라 ⑨·⑩·⑪·⑫ 도 **전부 0건**이다.
//     두 스코프가 겹치지 않는데 결론이 같다 — 목의 파생에는 프론트 단위 판정자가 **없다**.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: `serviceWorkers:'block'` 금지 — playwright.config.ts 기본값 사용.
//   - **`page.route()` 를 쓰지 않는다.** MSW Service Worker 가 먼저 응답해 가로채기가 무효임이
//     이 저장소에서 실측돼 있다(`backlog.spec.ts:125` · `import.spec.ts` S3 히스토리).
//     그래서 「응답을 spec 에서 덮어쓴다」는 선택지가 이 파일에는 존재하지 않는다.
//   - **reload 금지** — MSW store 는 모듈 스코프라 전체 로드마다 픽스처로 되돌아간다.
//     `goto` 는 각 test 처음 1회뿐이고 이후 이동은 전부 SPA 링크·탭이다(가짜그린 방지).
//   - playwright-getbyrole-exact-strict-mode: `추정`·`작업일` 같은 짧은 라벨은 `exact: true` 로,
//     `⋯`·스위처는 `board-helpers.ts` 의 **컨테이너 스코프 헬퍼**로 좁힌다(캠페인 R10).
//   - e2e-fixture-whoami-userid-alignment: `loginAsAlice` 를 쓴다.
//   - board-fixtures.ts 를 직접 import 하지 않는다 — 모듈 로드 시 `import.meta.env` 를 참조해
//     Playwright(Node) 런타임에서 깨진다. 상수는 인라인 동기화한다(board-kanban.spec.ts 관례).
//
// 시나리오 개요.
//   S1. 칸반 잠금 (J37)      — 칸반 보드의 추정 탭이 잠기고 **사유가 글자로 보인다**.
//                              같은 보드의 작업일 탭은 「미설정」이다(⑤ 대조군의 앞짝).
//   S2. 스크럼 대조군 (J37)  — 스크럼 보드에서는 열리고, 고른 값이 **탭을 떠났다 돌아와도** 남는다.
//   S3. 작업일 저장 (J38·J39) — 근무일·비근무일을 저장하면 **서버에서 다시 읽힌 값**이 화면에 선다.
//   S4. ★타임존만 바꾼다 (J40) — 근무일·비근무일은 손대지 않고 서울→뉴욕. 타임존이 바뀌고
//                              **다른 두 축은 살아남으며**, 옆 보드(칸반)는 여전히 미설정이다.
//   S5. 근무일 배선 (J39)    — 비근무일을 저장하면 번다운 x축에서 그 날이 빠진다.
//   S6. 타임존 배선 (J40)    — 타임존만 바꾸면 **x축은 그대로**고 잔여 라인이 달라진다.
//   S7. 경로 계약        — S5·S6 이 밟는 SPA 경로와 recharts x축 셀렉터가 살아 있는지 **초록으로** 잰다.
//   S8. ★대조군         — 미설정 보드의 번다운 x축은 **달력일 전부**다. S5 의 짝이다.
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import {
  boardActionsTrigger,
  boardHeader,
  boardSwitcherTrigger,
  goToBoardNameStep,
  selectBoardType,
} from './fixtures/board-helpers'
import { clickProjectViewTab } from './fixtures/project-view-tabs'
import { boardLabels } from '../src/i18n/board-labels'
import { burndownLabels } from '../src/i18n/burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — src 정본의 **미러**다. 값만 동기화하고 출처를 주석으로 남긴다.
// ─────────────────────────────────────────────────────────────────────────────

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.boardId 미러 — 종류는 **KANBAN** 이다 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.projectKey 미러 */
const PROJECT_KEY = 'ATLAS'

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.name 미러 */
const DEFAULT_BOARD_NAME = 'ATLAS 보드'

/** 진입 URL — 보드를 명시 지목한다(설정 화면은 기본 보드를 스스로 고르지 않는다) */
const BOARD_URL = `/projects/${PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** `src/mocks/backlog-fixtures.ts` DEFAULT_BACKLOG.sprints[0].sprint.name 미러 */
const SPRINT_NAME = '스프린트 1'

/**
 * `src/mocks/burndown-handlers.ts` DEFAULT_BURNDOWN.points 의 날짜 5건 미러 —
 * 2026-06-01(월) ~ 2026-06-05(금). **다섯 날이 전부 평일**이라 「주말이라서 빠졌다」와
 * 「비근무일로 등록해서 빠졌다」가 섞이지 않는다.
 */
const BURNDOWN_DATES = ['2026-06-01', '2026-06-02', '2026-06-03', '2026-06-04', '2026-06-05'] as const

/** S5 가 비근무일로 등록하는 날 — 위 5건의 한가운데라 x축이 좁아지면 반드시 사라진다 */
const HOLIDAY = '2026-06-03'

/** `BurndownChart.formatDateTick` 미러 — `YYYY-MM-DD` → `MM/DD` */
function toTick(isoDate: string): string {
  return isoDate.slice(5).replace('-', '/')
}

/**
 * 번다운 뷰의 라인 수 미러 — `BurndownChart` 의 `renderCommonElements()` 가 **범위** 1개를,
 * `renderPrimaryLines('burndown')` 이 **이상선 → 잔여** 2개를 그 뒤에 붙인다. DOM 순서가 곧
 * 아래 두 index 다.
 */
const BURNDOWN_CURVE_COUNT = 3

/** 범위(scope) 라인의 자리 — 전 구간 평탄해서 **타임존이 바뀌어도 같아야 한다**(공허 방지 대조군) */
const SCOPE_CURVE_INDEX = 0

/** 잔여(remaining) 라인의 자리 — worklog 일 귀속이 바뀌면 **여기가 달라진다** */
const REMAINING_CURVE_INDEX = 2

/**
 * `CreateBoardForm` 2단계 제출 버튼 문구.
 *
 * 🛑 `boardLabels` 에 키가 없다(폼이 직접 들고 있다). `board-manage.spec.ts` 가 같은 문자열을
 *    인라인으로 들고 있으므로 여기서도 같은 방식으로 미러한다 — 바뀌면 두 spec 이 함께 죽는다.
 */
const CREATE_BOARD_SUBMIT = '보드 만들기'

/** `CreateBoardForm` 2단계 이름 입력 라벨 — 위와 같은 이유로 인라인 미러 */
const BOARD_NAME_LABEL = '보드 이름'

/** S2~S4 가 자기 데이터로 만드는 스크럼 보드 이름 (테스트가 자기 보드를 만든다 — 공유 픽스처 무변경) */
const SCRUM_BOARD_NAME = '추정 테스트 보드'

const tabs = boardLabels.settings.tabs
const estimationLabels = boardLabels.settings.estimation
const workingDaysLabels = boardLabels.settings.workingDays

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 화면 진입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 화면의 `⋯` → 「보드 설정」으로 들어간다.
 *
 * `⋯` 는 `board-helpers.ts` 의 **헤더 컨테이너 스코프** 헬퍼로 잡는다. 사이드바 보드 `⋯` 와
 * 접근성 이름이 바이트 단위로 같아 전역 조회는 strict mode 로 즉사한다(캠페인 R10 · PR #35).
 */
async function openBoardSettings(page: Page): Promise<void> {
  await expect(boardHeader(page)).toBeVisible()
  await boardActionsTrigger(page).click()
  await page.getByRole('menuitem', { name: boardLabels.actions.settingsItem }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: boardLabels.settings.pageHeading }),
  ).toBeVisible()
}

/** 설정 탭 트리거 — 라벨 5종이 서로 substring 이 아니지만 계약대로 `exact` 를 명시한다 */
function settingsTab(page: Page, name: string): Locator {
  return page.getByRole('tab', { name, exact: true })
}

/**
 * 탭을 떠났다 돌아와 본문을 **재마운트**시킨다.
 *
 * `SettingsTabs` 는 `forceMount` 없는 Radix `TabsContent` 라 비활성 탭 본문이 언마운트된다 —
 * 돌아오면 초기값을 **보드 조회 응답에서 다시 읽는다.** 그래서 이 왕복이 곧 「저장이 서버에
 * 닿았는가」의 관측점이다. 패널이 자기 state 만 들고 있으면(=배선 절단) 여기서 죽는다.
 *
 * 🛑 `expect(...).toPass()` 로 감싼다. 저장의 `onSettled` 가 `invalidateQueries` 를 걸어 두는데
 *    그 refetch 가 정착하기 전에 재마운트하면 **저장 전 캐시**가 초기값이 된다 — sleep 없이
 *    이 경합을 없애는 유일한 수단이 재시도다.
 */
async function remountTab(page: Page, tabName: string, assertion: () => Promise<void>): Promise<void> {
  await expect(async () => {
    await settingsTab(page, tabs.columns).click()
    await settingsTab(page, tabName).click()
    await assertion()
  }).toPass({ timeout: 15_000 })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 추정 탭
// ─────────────────────────────────────────────────────────────────────────────

/** 시간 추적 라디오 그룹 */
function timeTrackingGroup(page: Page): Locator {
  return page.getByRole('radiogroup', { name: estimationLabels.groupLabel })
}

/** 시간 추적 라디오 한 개 */
function timeTrackingOption(page: Page, label: string): Locator {
  return timeTrackingGroup(page).getByRole('radio', { name: label, exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 작업일 탭
// ─────────────────────────────────────────────────────────────────────────────

/** 요일 체크박스 — `<ul aria-label="표준 근무일 요일">` 안으로 좁힌다 */
function dayCheckbox(page: Page, dayLabel: string): Locator {
  return page
    .getByRole('list', { name: workingDaysLabels.daysGroupLabel })
    .getByRole('checkbox', { name: dayLabel, exact: true })
}

/** 비근무일 목록 — `<h3>비근무일</h3>` 과 이름이 같으므로 role=list 로 가른다 */
function nonWorkingList(page: Page): Locator {
  return page.getByRole('list', { name: workingDaysLabels.nonWorkingHeading })
}

/** 저장 버튼 */
function saveWorkingDays(page: Page): Locator {
  return page.getByRole('button', { name: workingDaysLabels.save, exact: true })
}

/**
 * 지역 → 타임존 순으로 고른다 (J40 — *"select a Region, then Timezone from the dropdowns"*).
 *
 * 🛑 트리거와 팝오버 검색 입력이 **둘 다 role=combobox** 다. 트리거만 `aria-label` 을 갖고
 *    cmdk 검색 입력은 **placeholder 뿐**이라(2026-09-06 실측 — `role=combobox aria-label=null
 *    placeholder=지역 검색`) `getByRole(name)` 으로는 검색 입력이 잡히지 않는다.
 *    트리거는 `exact: true` 로, 검색 입력은 `getByPlaceholder` 로 가른다.
 *
 * @param page Playwright 페이지
 * @param region 지역 — `Asia` 처럼 IANA 앞머리
 * @param zoneLabel 타임존 표시 라벨 — `Asia/Seoul` → `Seoul`, `America/New_York` → `New York`
 */
async function pickTimezone(page: Page, region: string, zoneLabel: string): Promise<void> {
  const regionTrigger = page.getByRole('combobox', { name: workingDaysLabels.regionLabel, exact: true })
  await regionTrigger.click()
  await expect(page.getByPlaceholder(workingDaysLabels.regionSearch)).toBeVisible()
  await page.getByRole('option', { name: region, exact: true }).click()
  await expect(regionTrigger).toContainText(region)

  const zoneTrigger = page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true })
  await zoneTrigger.click()
  // 지역 하나에 타임존이 수십~수백 개다 — 검색으로 좁힌 뒤 고른다.
  const zoneSearch = page.getByPlaceholder(workingDaysLabels.timezoneSearch)
  await expect(zoneSearch).toBeVisible()
  await zoneSearch.fill(zoneLabel)
  await page.getByRole('option', { name: zoneLabel, exact: true }).click()
  await expect(zoneTrigger).toContainText(zoneLabel)
}

/** 「저장」을 누르고 저장 완료 문구를 확인한다 */
async function submitWorkingDays(page: Page): Promise<void> {
  await saveWorkingDays(page).click()
  await expect(page.getByText(workingDaysLabels.saved, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 번다운 차트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 설정 화면 → 백로그 → 스프린트 칸의 「번다운 보기」로 **SPA 이동**한다.
 *
 * 🛑 `page.goto` 로 대체하지 않는다. 전체 로드는 MSW store 를 픽스처로 되돌려 **방금 저장한
 *    작업일이 사라진 채** 차트를 재게 된다 — 원인이 전혀 보이지 않는 가짜 red 가 된다.
 */
async function goToBurndownViaSpa(page: Page): Promise<void> {
  await clickProjectViewTab(page, '백로그')
  const sprintColumn = page.getByRole('region').filter({ hasText: new RegExp(`^${SPRINT_NAME}`) }).first()
  await expect(sprintColumn).toBeVisible()
  await sprintColumn
    .getByRole('link', { name: `${SPRINT_NAME} ${burndownLabels.toggle.burndown} 보기`, exact: true })
    .click()
  await expect(page.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeVisible()
}

/**
 * 번다운 x축 tick 문자열을 읽는다.
 *
 * ★recharts 내부 클래스에 기대는 **이 저장소의 유일한 자리**다. 형제 spec
 * (`sprint-burndown.spec.ts`)은 컨테이너 가시성까지만 재는데, 「x축이 **좁아진다**」는
 * 축 자체를 읽지 않고는 잴 수 없다. 셀렉터가 썩으면 S7 이 초록에서 죽는다.
 *
 * 🛑 `.recharts-xAxis` 의 **자손이 아니다.** recharts 3.8.1 은 tick 글자를 축과 전혀 다른
 *    z-index 레이어(`g.recharts-zIndex-layer_2000`)의 `.recharts-xAxis-tick-labels` 아래에
 *    그린다 — 2026-09-06 실측 조상 사슬은
 *    `text.recharts-cartesian-axis-tick-value < g.recharts-cartesian-axis-tick-label
 *     < g.recharts-xAxis-tick-labels < g.recharts-zIndex-layer_2000 < svg.recharts-surface` 다.
 *    `.recharts-xAxis`·`.recharts-xAxis-ticks` 아래로 찾으면 **0건**이다(둘 다 실측 red).
 *    y축은 `.recharts-yAxis-tick-labels` 라 이 접두가 두 축을 가른다(안 가르면 `0m`·`2h 30m`
 *    같은 시간 tick 이 섞인다). 한 페이지에 recharts surface 가 여럿이므로 차트
 *    컨테이너(`role="img"`)로 먼저 좁힌다.
 */
async function burndownAxisTicks(page: Page): Promise<string[]> {
  const ticks = page
    .getByRole('img', { name: burndownLabels.chart.ariaLabel })
    .locator('.recharts-xAxis-tick-labels .recharts-cartesian-axis-tick-value')

  // 🛑 **첫 tick 가시성만으로 읽으면 안 된다.** recharts 는 축을 점진적으로 그려서, 그
  //    중간에 읽으면 배열이 잘린 채 돌아온다 — 2026-09-06 실측으로 S5 가 「06/03 이 없다」로
  //    **가짜 통과**했다(뮤테이션 M-C 실행 중 포착). 잘린 배열은 부재 단언을 공짜로 만족시킨다.
  //    양 끝이 제자리에 설 때까지 기다려야 그 사이가 다 그려졌다고 말할 수 있다.
  // ★양 끝을 기대값으로 쓸 수 있는 근거 — 스프린트 시작·종료일(06/01·06/05)이 **둘 다 평일**이라
  //   근무일로 좁혀도 남고, `interval="preserveStartEnd"` 가 양 끝 tick 을 항상 그린다.
  await expect(ticks.first()).toHaveText(toTick(BURNDOWN_DATES[0]))
  await expect(ticks.last()).toHaveText(toTick(BURNDOWN_DATES[BURNDOWN_DATES.length - 1]))

  return (await ticks.allTextContents()).map((text) => text.trim())
}

/**
 * 번다운 뷰가 그리는 라인 3개의 SVG path `d` 를 DOM 순서대로 읽는다.
 *
 * ★**왜 축이 아니라 라인인가.** 보드 타임존은 x축을 바꾸지 않는다 — 백엔드 `BurndownCalculator`
 * 의 축은 `LocalDate` 만 보고(`buildAxis`), 타임존이 바꾸는 것은 worklog 가 **어느 날짜 칸에
 * 붙는가**뿐이다(`SprintBurndownService.aggregateByBoardDate` · 스펙 R10 · 완료 기준 11).
 * 그 결과는 point 의 `remainingSeconds` 값이고, 이 화면에서 값이 나타나는 곳은 선의 모양뿐이다
 * (페이지에 수치를 글자로 내는 자리가 없다 — `SprintBurndownPage` 는 차트 하나만 그린다).
 *
 * 🛑 `.recharts-xAxis-tick-labels` 와 함께 recharts 내부 클래스에 기대는 두 번째 자리다.
 *    `<Curve className="recharts-line-curve">` 는 recharts 3.8.1 의 Line 구현이다.
 * 🛑 **개수를 먼저 못 박는다.** 부분 렌더 중에 읽으면 배열이 짧게 돌아오고, 짧은 배열은
 *    「달라졌다」를 공짜로 만족시킨다 — `burndownAxisTicks` 가 양 끝 tick 을 기다리는 것과
 *    같은 함정이다(2026-09-06 실측 · 커밋 `19dcdc0e7`).
 */
async function burndownCurvePaths(page: Page): Promise<string[]> {
  const curves = page
    .getByRole('img', { name: burndownLabels.chart.ariaLabel })
    .locator('.recharts-line-curve')

  await expect(curves).toHaveCount(BURNDOWN_CURVE_COUNT)
  const paths = await curves.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('d') ?? ''))
  expect(paths.filter((d) => d.length > 0)).toHaveLength(BURNDOWN_CURVE_COUNT)
  return paths
}

/**
 * 「작업일을 한 번도 설정하지 않은 보드」임을 단언한다 (스펙 R6).
 *
 * ★두 줄이 **짝으로만 산다.** 안내 문구만 보면 「요일 7개가 다 꺼진 화면」과 구분되지 않고,
 * 체크박스 부재만 보면 조회 실패와 구분되지 않는다. 미설정과 「근무일 0개」는 뜻이 정반대다.
 */
async function expectWorkingDaysUnset(page: Page): Promise<void> {
  await expect(page.getByText(workingDaysLabels.unsetNotice)).toBeVisible()
  await expect(page.getByRole('list', { name: workingDaysLabels.daysGroupLabel })).toHaveCount(0)
}

/** 「미설정 → 근무일 고르기(월~금) → 비근무일 등록 → 저장」 한 묶음 */
async function configureWorkingDaysWithHoliday(page: Page): Promise<void> {
  await settingsTab(page, tabs.workingDays).click()
  await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()
  await page.getByLabel(workingDaysLabels.dateInputLabel).fill(HOLIDAY)
  await page.getByRole('button', { name: workingDaysLabels.addDate, exact: true }).click()
  await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
  await submitWorkingDays(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('보드 설정 — 추정 · 작업일 (부채 177 Task 23)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 칸반 잠금 + 미설정 (뮤테이션 ①·⑤ 의 앞짝)
  //
  // Given  alice 로 로그인해 **칸반** 보드(ATLAS 보드)의 설정 화면에 있다
  // When   「추정」 탭을 연다
  // Then   라디오 2종이 전부 비활성이고 **잠긴 사유가 글자로** 보인다 (J37)
  //        이어서 「작업일」 탭은 요일 체크박스 대신 **미설정 안내**를 그린다 (R6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 칸반 보드 — 추정 탭이 사유와 함께 잠기고, 작업일은 미설정이다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)

    // When. 추정 탭
    await settingsTab(page, tabs.estimation).click()

    // Then. 사유가 글자로 보인다 — 비활성만 하면 사용자는 「고장」으로 읽는다.
    await expect(page.getByText(estimationLabels.kanbanLocked)).toBeVisible()
    await expect(timeTrackingOption(page, estimationLabels.optionNone)).toBeDisabled()
    await expect(timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)).toBeDisabled()

    // Then. 작업일 탭은 미설정 — 「요일 7개가 다 꺼진 화면」이 아니다(미설정 ≠ 근무일 0개).
    await settingsTab(page, tabs.workingDays).click()
    await expectWorkingDaysUnset(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2~S4 — 스크럼 보드 한 바퀴 (뮤테이션 ②·③·④·⑤·⑬·⑭)
  //
  // 한 test 로 잇는 이유. MSW store 는 전체 로드마다 픽스처로 되돌아가므로, test 를 쪼개면
  // 앞 단계가 만든 보드와 저장이 전부 사라진다(`board-manage.spec.ts` 가 세운 관례).
  //
  // Given  alice 가 **스스로 만든 스크럼 보드**의 설정 화면에 있다 (공유 픽스처를 건드리지 않는다)
  // When   ① 추정을 「잔여 추정 + 소요 시간」으로 바꾸고 ② 근무일에서 금요일을 빼고 비근무일을 넣고
  //        ③ **타임존만** 서울 → 뉴욕으로 바꾼다
  // Then   각 단계가 탭을 떠났다 돌아와도 남아 있고, 타임존만 바꾼 저장이 근무일·비근무일을
  //        지우지 않으며, 옆 보드(칸반)는 여전히 미설정이다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2~S4 스크럼 보드 — 추정 열림 → 작업일 저장 → 타임존만 변경, 옆 보드는 그대로', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await expect(boardHeader(page)).toBeVisible()

    // ── Given. 이 테스트가 쓸 스크럼 보드를 **자기가 만든다** ────────────────────
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.switcher.createItem }).click()
    const createDialog = page.getByRole('dialog', { name: boardLabels.switcher.createDialogTitle })
    await expect(createDialog).toBeVisible()
    await selectBoardType(createDialog, 'SCRUM')
    await goToBoardNameStep(createDialog)
    await createDialog.getByLabel(BOARD_NAME_LABEL).fill(SCRUM_BOARD_NAME)
    await createDialog.getByRole('button', { name: CREATE_BOARD_SUBMIT, exact: true }).click()
    await expect(boardSwitcherTrigger(page)).toContainText(SCRUM_BOARD_NAME)

    await openBoardSettings(page)

    // ── S2. 추정 탭이 열린다 — ①의 대조군. 「항상 잠긴 구현」이 여기서 죽는다 ──────
    await settingsTab(page, tabs.estimation).click()
    await expect(page.getByText(estimationLabels.kanbanLocked)).toHaveCount(0)
    const remaining = timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)
    await expect(remaining).toBeEnabled()
    await remaining.click()
    await expect(remaining).toBeChecked()

    // Then. 탭을 떠났다 돌아와도 남는다 — 화면 state 가 아니라 **서버 값**이라는 뜻이다.
    await remountTab(page, tabs.estimation, async () => {
      await expect(timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)).toBeChecked()
    })

    // ── S3. 작업일 저장 — 근무일에서 금요일을 빼고 비근무일 하루를 넣는다 ──────────
    await settingsTab(page, tabs.workingDays).click()
    await expectWorkingDaysUnset(page)
    await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()

    const friday = dayCheckbox(page, workingDaysLabels.dayLabels.FRI)
    await expect(friday).toBeChecked() // 「근무일 고르기」의 출발점은 월~금이다
    await friday.click()
    await expect(friday).not.toBeChecked()

    await page.getByLabel(workingDaysLabels.dateInputLabel).fill(HOLIDAY)
    await page.getByRole('button', { name: workingDaysLabels.addDate, exact: true }).click()
    await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()

    await submitWorkingDays(page)

    // Then. 재마운트 후에도 서버 값이 선다 — ③(배선 절단)이 여기서 죽는다.
    await remountTab(page, tabs.workingDays, async () => {
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.FRI)).not.toBeChecked()
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
      await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
    })

    // ── S4. ★타임존만 바꾼다 — 근무일·비근무일에는 손대지 않는다 ─────────────────
    await pickTimezone(page, 'Asia', 'Seoul')
    await submitWorkingDays(page)
    await remountTab(page, tabs.workingDays, async () => {
      await expect(
        page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true }),
      ).toContainText('Seoul')
    })

    await pickTimezone(page, 'America', 'New York')
    await submitWorkingDays(page)

    // Then. 타임존이 바뀌었고 **다른 두 축은 살아 있다** — PUT 3축 교체가 다른 축을 지우면
    //       (⑬) 여기서 죽는다. 이 짝이 없으면 「타임존만 바꿨는데 근무일이 날아가는」 침묵
    //       실패를 아무도 못 본다.
    //       ★단, 이 축은 단위 `T-WD-7`(요일만 바꿔도 나머지 두 축이 함께 실린다)도 진다 —
    //         여기가 유일한 판정자가 아니다(⑬ 실측).
    await remountTab(page, tabs.workingDays, async () => {
      await expect(
        page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true }),
      ).toContainText('New York')
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.FRI)).not.toBeChecked()
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
      await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
    })

    // ── S4 대조군. 옆 보드(칸반 ATLAS 보드)는 **여전히 미설정**이다 ───────────────
    // ⑤(항상 좁힌다) 와 ⑭(프로젝트 단위로 샌다) 가 여기서 죽는다. 이것이 없으면
    // 「모든 보드를 월~금으로 만드는」 구현도 위 단언을 전부 통과한다.
    await clickProjectViewTab(page, '보드')
    await expect(boardHeader(page)).toBeVisible()
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitemradio', { name: DEFAULT_BOARD_NAME }).click()
    await expect(boardSwitcherTrigger(page)).toContainText(DEFAULT_BOARD_NAME)

    await openBoardSettings(page)
    await settingsTab(page, tabs.workingDays).click()
    await expectWorkingDaysUnset(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7 — 경로 계약 (S5·S6 의 셀렉터가 살아 있는지 초록으로 잰다)
  //
  // Given  칸반 보드에 근무일·비근무일을 저장했다
  // When   설정 → 백로그 → 스프린트 칸 「번다운 보기」로 **SPA 이동**한다
  // Then   차트가 그려지고 x축 tick 을 읽을 수 있으며 스프린트 **시작일**이 그 안에 있다
  //
  // ★기대값을 「5개」로 굳히지 않는다 — 배선이 들어오면 4개가 되고, 그때 이 초록이
  //   엉뚱하게 죽으면 안 된다. 시작일은 좁혀도 남으므로 두 세계 모두에서 참이다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 근무일 저장 후 SPA 로 번다운까지 도달하고 x축을 읽을 수 있다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)
    await configureWorkingDaysWithHoliday(page)

    await goToBurndownViaSpa(page)

    const ticks = await burndownAxisTicks(page)
    expect(ticks).toContain(toTick(BURNDOWN_DATES[0]))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — 근무일 배선 (뮤테이션 ⑧·⑨)
  //
  // Given  칸반 보드에 표준 근무일(월~금)과 비근무일 2026-06-03 을 저장했다
  // When   같은 프로젝트의 스프린트 번다운을 연다
  // Then   x축에서 2026-06-03(=`06/03`)이 빠져 있다 (J39)
  //
  // ★2026-09-06(Task 33) 이전에는 「예상 실패」 마커로 깨진 채 박혀 있었다. 목이 작업일 설정에서
  //   아무것도 파생하지 않아 x축이 `["06/01","06/02","06/03","06/04","06/05"]` 로 고정이었다.
  //   목이 공유 store 를 경유해 파생하게 되면서 초록이 됐다(파일 머리말 ★★).
  // ★**S8 과 짝으로만 산다** — 이 건만 두면 「무조건 좁히는 구현」이 통과한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 근무일을 저장하면 번다운 x축에서 비근무일이 빠진다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)
    await configureWorkingDaysWithHoliday(page)

    await goToBurndownViaSpa(page)

    expect(await burndownAxisTicks(page)).not.toContain(toTick(HOLIDAY))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 — 타임존 배선 (뮤테이션 ⑧·⑩)
  //
  // Given  칸반 보드의 표준 근무일은 월~금으로 **고정**이고 타임존만 서울이다
  // When   근무일 요일에는 손대지 않고 **타임존만** 뉴욕으로 바꾼다
  // Then   x축은 **그대로**이고 잔여 라인만 달라진다 (J40 · 스펙 R10 · 완료 기준 11)
  //
  // ★설정은 저장되는데 차트가 안 바뀌면 아무도 모른다 — 이 단언이 그 침묵을 깬다.
  // ★★**두 Then 이 짝으로만 산다.**
  //   - 축만 재면(옛 판정) 「타임존이 축을 좁히는」 **틀린 구현**을 요구하게 된다. 타임존은
  //     축을 바꾸지 않는다 — 백엔드 `BurndownCalculator.buildAxis` 는 `LocalDate` 와 근무일만
  //     본다. 그래서 첫 Then 은 **같음**을 못 박는다.
  //   - 라인만 재면 컨테이너 폭이 달라져도 전 라인이 함께 달라져 공짜로 통과한다. 그래서 두
  //     번째 Then 은 **범위 라인은 같고 잔여 라인만 다름**을 함께 재 레이아웃 차이를 배제한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 타임존만 서울→뉴욕으로 바꾸면 x축은 그대로고 잔여 라인이 달라진다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)

    // Given. 근무일 월~금 + 타임존 서울
    await settingsTab(page, tabs.workingDays).click()
    await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()
    await pickTimezone(page, 'Asia', 'Seoul')
    await submitWorkingDays(page)

    await goToBurndownViaSpa(page)
    const seoulTicks = await burndownAxisTicks(page)
    const seoulCurves = await burndownCurvePaths(page)

    // When. 근무일은 그대로 두고 **타임존만** 뉴욕으로 바꾼다
    await clickProjectViewTab(page, '보드')
    await openBoardSettings(page)
    await settingsTab(page, tabs.workingDays).click()
    await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
    await pickTimezone(page, 'America', 'New York')
    await submitWorkingDays(page)

    await goToBurndownViaSpa(page)

    // Then ①. 근무일이 같으면 축도 같다 — 타임존은 x축을 만지지 않는다(스펙 E7 의 앞짝).
    expect(await burndownAxisTicks(page)).toEqual(seoulTicks)

    // Then ②. 같은 worklog 가 다른 날짜 칸에 붙어 **잔여 라인만** 달라진다.
    //
    // 🛑 `toPass` 로 감싼다. 돌아온 차트는 React Query 캐시(서울 응답)를 먼저 그리고 refetch 로
    //    뉴욕 응답을 받는다 — 그 정착을 sleep 없이 기다리는 유일한 수단이 재시도다.
    //    단정이 「달라짐」이라 첫 읽기가 캐시여도 조용히 통과하지 않는다(같으면 재시도한다).
    await expect(async () => {
      const newYorkCurves = await burndownCurvePaths(page)
      expect(newYorkCurves[SCOPE_CURVE_INDEX]).toBe(seoulCurves[SCOPE_CURVE_INDEX])
      expect(newYorkCurves[REMAINING_CURVE_INDEX]).not.toBe(seoulCurves[REMAINING_CURVE_INDEX])
    }).toPass({ timeout: 15_000 })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S8 — ★대조군. 미설정 보드의 번다운은 **현행 그대로**다 (스펙 R6 · 뮤테이션 ⑤)
  //
  // Given  작업일을 **한 번도 설정하지 않은** 보드다(설정 화면에서 그 사실을 먼저 확인한다)
  // When   그 프로젝트의 스프린트 번다운을 연다
  // Then   x축이 달력일 5일 **전부**다 — 아무것도 빠지지 않는다
  //
  // ★★**S5 와 짝으로만 산다.** S5 만 두면 「무조건 좁히는 구현」이 통과한다 — 설정을 만지지도
  //   않은 보드의 차트까지 조용히 바뀌는 것이 R6 가 금지한 바로 그것이다(기존 스프린트의
  //   차트가 배포 순간 달라진다). 반대로 이 건만 두면 「아무것도 안 좁히는 구현」이 통과한다.
  //   ★2026-09-06(Task 33) 부터 **둘 다 green** 이다.
  //   🛑 단 이 건이 잡는 「무조건 좁히는 구현」은 **목이 축을 비우는 쪽**뿐이다. 목이 미설정을
  //      월~금으로 채우는 쪽(뮤테이션 ⑪)은 이 픽스처의 5일이 전부 평일이라 관측되지 않는다 —
  //      머리말 ★★2 에 이유와 백엔드 판정자를 적어 뒀다.
  //
  // ★기대값을 5건 전량으로 굳힌다. `toContain` 이 아니라 `toEqual` 인 것이 요점이다 —
  //   부분 일치는 「일부만 남기는」 구현을 놓친다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S8 대조군 — 작업일 미설정 보드의 번다운 x축은 달력일 전부다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)

    // Given. 이 보드는 작업일을 한 번도 설정하지 않았다 — 대조군의 전제를 화면에서 확인한다.
    await settingsTab(page, tabs.workingDays).click()
    await expectWorkingDaysUnset(page)

    // When. 아무것도 저장하지 않고 번다운으로 간다.
    await goToBurndownViaSpa(page)

    // Then. 달력일 5일이 전부 그대로다.
    expect(await burndownAxisTicks(page)).toEqual(BURNDOWN_DATES.map(toTick))
  })
})
