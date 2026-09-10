// 탭 라벨 레지스트리 × navLabels 교차 판별식 — 값 겹침을 면제표로만 통과시킨다 (Jira 패리티 J5)
import { describe, it, expect } from 'vitest'
import { navLabels } from '@/i18n/nav-labels'
import { projectViewLabels } from '@/i18n/project-view-labels'
import { scrumEmptyStateLabels } from '@/components/board/ScrumSprintEmptyState'

/**
 * 왜 이 파일이 따로 있는가.
 *
 * `nav-labels.test.ts` FR15 는 `navLabels` **안에서만** 쌍을 훑는다. 탭 라벨을 그 파일에
 * 넣었으면 `이슈`·`대시보드`·`캘린더` 3건이 값 중복으로, `보드` ⊂ `대시보드` 가 substring 으로
 * 즉사했을 것이다. 레지스트리를 가른 대가로 **두 레지스트리 사이의 관계를 아무도 안 보는**
 * 사각이 생기는데(`two-lists-never-check-each-other` 지배 결함 양식), 이 파일이 그 차집합이다.
 *
 * 그러므로 이 파일의 존재 이유는 「면제를 적는 자리」이지 「겹침을 없애는 것」이 아니다.
 * 겹침은 의도된 것이고, 관리 수단은 **조회 스코프**(`projectViewLabels` JSDoc 조회 규약)다.
 */

/** 두 레지스트리를 한 이름 공간으로 합친 항목 — 교차 쌍을 훑는 재료 */
interface LabelEntry {
  /** `nav` 또는 `tab` — 어느 레지스트리 소속인가 */
  readonly registry: 'nav' | 'tab'
  /** 레지스트리 안의 키 */
  readonly key: string
  /** 화면에 노출되는 문자열 */
  readonly value: string
}

const ALL_ENTRIES: readonly LabelEntry[] = [
  ...Object.entries(navLabels).map(([key, value]) => ({ registry: 'nav' as const, key, value })),
  ...Object.entries(projectViewLabels).map(([key, value]) => ({
    registry: 'tab' as const,
    key,
    value,
  })),
]

/** `registry.key` 형태의 안정 식별자 — 면제표가 이 표기로 쌍을 적는다 */
const idOf = (entry: LabelEntry): string => `${entry.registry}.${entry.key}`

/**
 * substring 관계가 **허용된** 순서쌍 `[안쪽, 바깥쪽]`. 전부 실재하며 근거가 다르다.
 *
 * 1. `tab.board`('보드') ⊂ `tab.dashboards`('대시보드') — **같은 탭바 안에 공존한다.**
 *    이 레지스트리에서 가장 위험한 쌍이다. 관리 수단은 조회 규약 ② — Playwright 의 `getByRole`
 *    은 기본이 부분 일치라 `exact: true` 없이 `보드` 를 찾으면 `대시보드` 가 함께 잡혀
 *    strict mode 위반이 된다. Testing Library 는 `name` 이 기본 완전 일치라 유닛은 선재 안전.
 *
 * 2. `tab.board`('보드') ⊂ `nav.dashboards`('대시보드') — 위와 같은 낱말 관계인데 바깥쪽이
 *    사이드바 링크다. 두 nav 는 한 화면에 공존하므로 조회 규약 ①(nav 스코프)이 이것도 덮는다.
 *
 * 3. `tab.issues`·`tab.dashboards`·`tab.calendar` ⇄ `nav.*` 동명 3쌍 — **값이 완전히 같다.**
 *    같은 목적지 개념을 사이드바와 탭바가 각자 부르는 것이라 한쪽 이름을 비틀면 화면이
 *    거짓말을 한다. 완전 일치는 양방향 substring 이므로 순서쌍이 6개가 된다.
 *    관리 수단은 조회 규약 ① — 문서 전역 조회 금지, 반드시 nav 로 스코프한다.
 *
 * 4. `tab.issues`('이슈') ⊂ `nav.globalSearchPlaceholder`('이슈 검색') — 바깥쪽은
 *    **placeholder 라 접근성 이름이 아니다**(입력창에 `aria-label` 이 있다). `getByRole` 계열이
 *    이 값을 보지 못하므로 실제 충돌면이 없다. `nav-labels.test.ts` 가 같은 근거로 이미
 *    `nav.issues` ⊂ `nav.globalSearchPlaceholder` 를 면제하고 있다 — 그 판단을 승계한 것이다.
 *
 * ⚠️ 새 탭 라벨을 넣을 때 이 표에 줄이 늘어난다면, 늘리기 전에 **다른 낱말을 고를 수 있는지**
 *    부터 본다. 면제는 공짜가 아니라 조회 규약을 지켜야 하는 부채다.
 */
const ALLOWED_SUBSTRING_PAIRS: ReadonlyArray<readonly [string, string]> = [
  ['tab.board', 'tab.dashboards'],
  ['tab.board', 'nav.dashboards'],
  ['tab.issues', 'nav.issues'],
  ['nav.issues', 'tab.issues'],
  ['tab.dashboards', 'nav.dashboards'],
  ['nav.dashboards', 'tab.dashboards'],
  ['tab.calendar', 'nav.calendar'],
  ['nav.calendar', 'tab.calendar'],
  ['tab.issues', 'nav.globalSearchPlaceholder'],
]

/**
 * 두 레지스트리에 **값이 완전히 같은** 키 쌍 — 조회 규약 ① 없이는 못 쓰는 항목 전수.
 *
 * 이 목록이 늘어난다는 것은 「사이드바와 탭바가 같은 이름을 하나 더 쓴다」는 뜻이고,
 * 그때마다 e2e 조회 스코프를 함께 손봐야 한다. 그래서 개수가 아니라 **쌍 자체**를 얼린다.
 */
const EXPECTED_EXACT_DUPLICATES: ReadonlyArray<readonly [tabKey: string, navKey: string]> = [
  ['issues', 'issues'],
  ['dashboards', 'dashboards'],
  ['calendar', 'calendar'],
]

/**
 * 교차 순서쌍 전량 — 같은 레지스트리 안쪽 쌍도 포함한다.
 *
 * `nav × nav` 는 `nav-labels.test.ts` 가 이미 덮으므로 여기서 다시 세지 않는다. 남는 것은
 * `tab × tab` 과 `tab ⇄ nav` 다.
 */
const crossPairs = ALL_ENTRIES.flatMap((a) =>
  ALL_ENTRIES.filter((b) => idOf(b) !== idOf(a))
    .filter((b) => a.registry === 'tab' || b.registry === 'tab')
    .map((b) => ({ a, b })),
)

const isAllowed = (innerId: string, outerId: string): boolean =>
  ALLOWED_SUBSTRING_PAIRS.some(([inner, outer]) => inner === innerId && outer === outerId)

describe('projectViewLabels × navLabels 교차 판별식', () => {
  it('비-공허: 검사 대상 쌍이 0건이 아니고 두 레지스트리를 모두 훑는다', () => {
    // 훑기가 고장 나 0쌍이 되면 아래 단언이 전부 조용히 통과한다.
    const navCount = Object.keys(navLabels).length
    const tabCount = Object.keys(projectViewLabels).length
    expect(navCount).toBeGreaterThan(1)
    expect(tabCount).toBeGreaterThan(1)

    // tab×tab 순서쌍 + tab⇄nav 양방향 순서쌍
    const expected = tabCount * (tabCount - 1) + 2 * tabCount * navCount
    expect(crossPairs).toHaveLength(expected)

    // 한쪽 레지스트리만 훑고 있으면 즉시 red — 교차 판별식이 아니게 된다.
    expect(crossPairs.some(({ a, b }) => a.registry === 'tab' && b.registry === 'nav')).toBe(true)
    expect(crossPairs.some(({ a, b }) => a.registry === 'nav' && b.registry === 'tab')).toBe(true)
    expect(crossPairs.some(({ a, b }) => a.registry === 'tab' && b.registry === 'tab')).toBe(true)
  })

  it('화이트리스트 짝 검사: 허용 쌍은 실제로 substring 관계이고 존재하는 키다', () => {
    // 짝을 잘못 적으면 파생이 틀린 쌍을 면제하며 통과한다(#323 M9 동형 결함).
    const byId = new Map(ALL_ENTRIES.map((entry) => [idOf(entry), entry.value]))

    for (const [innerId, outerId] of ALLOWED_SUBSTRING_PAIRS) {
      const inner = byId.get(innerId)
      const outer = byId.get(outerId)
      // 🛑 옵셔널 체이닝 + 폴백으로 넘기지 마라 — 키를 잘못 적으면 폴백이 판정을 삼켜
      //    「없는 쌍을 면제했다」가 조용히 통과한다. 여기서 던져 타입도 함께 좁힌다.
      if (inner === undefined || outer === undefined) {
        throw new Error(`면제표가 없는 키를 가리킨다: ${innerId} / ${outerId}`)
      }
      expect(innerId).not.toBe(outerId)
      expect(outer.includes(inner), `${innerId} ⊄ ${outerId}`).toBe(true)
    }
  })

  it('화이트리스트에 없는 어떤 교차 쌍도 substring 관계가 아니다', () => {
    const violations = crossPairs
      .filter(({ a, b }) => b.value.includes(a.value) && !isAllowed(idOf(a), idOf(b)))
      .map(({ a, b }) => `${idOf(a)}('${a.value}') ⊂ ${idOf(b)}('${b.value}')`)

    expect(violations).toEqual([])
  })

  it('값이 완전히 같은 교차 쌍은 면제표에 적힌 3건뿐이다 (조회 규약 ① 대상)', () => {
    // 개수가 아니라 쌍 자체를 얼린다 — 「N건」은 눈가리개다.
    const actual = Object.entries(projectViewLabels)
      .flatMap(([tabKey, tabValue]) =>
        Object.entries(navLabels)
          .filter(([, navValue]) => navValue === tabValue)
          .map(([navKey]) => `${tabKey}=${navKey}`),
      )
      .sort()

    const expected = EXPECTED_EXACT_DUPLICATES.map(([tabKey, navKey]) => `${tabKey}=${navKey}`).sort()

    expect(actual).toEqual(expected)
  })

  it('탭 라벨은 navLabels 에 키로 들어가 있지 않다 (레지스트리 분리 봉인)', () => {
    // 편의로 한 벌 더 넣으면 두 정본이 갈려 한쪽만 고쳐진다.
    const navKeys = new Set(Object.keys(navLabels))
    const leaked = Object.keys(projectViewLabels).filter((key) => navKeys.has(key))

    // `issues`·`dashboards`·`calendar` 는 **양쪽에 같은 키가 실재한다** — 값 겹침과 같은 근거로
    // 의도된 것이고 위 단언이 그 3건을 이미 얼린다. 그 밖의 키가 새로 겹치면 red.
    expect(leaked.sort()).toEqual(['calendar', 'dashboards', 'issues'])
  })

  it('모든 탭 라벨 값은 비어 있지 않다 (빈 문자열은 모든 문자열의 substring)', () => {
    for (const [key, value] of Object.entries(projectViewLabels)) {
      expect(value, `${key} 라벨이 비어 있다`).not.toBe('')
    }
  })

  it('탭 라벨 안에 값 중복이 없다 (같은 값 두 키는 한 탭바에서 서로를 가린다)', () => {
    const values = Object.values(projectViewLabels)
    expect(new Set(values).size).toBe(values.length)
  })
})

/**
 * 탭바와 **한 화면에 공존하는** 다른 문구와의 관계.
 *
 * `navLabels` 는 사이드바라 조회 규약 ①(nav 스코프)로 갈라지지만, 본문 안 문구는 nav 로
 * 못 가른다. 탭바가 생기면서 새로 만들어진 쌍이 하나 있고, 이 블록이 그것을 얼려 둔다.
 */
describe('projectViewLabels × 본문 문구 — 한 화면 공존 쌍', () => {
  /**
   * `보드` 화면 한정. 스크럼 보드에 활성 스프린트가 없으면 빈 상태가 뜨고, 그 안내 링크가
   * `백로그로 이동` 이다. 탭바의 `백로그` 는 그 **부분 문자열**이다.
   *
   * Playwright 의 `getByRole` 은 기본이 부분 일치라 `exact: true` 없이 `백로그` 를 찾으면
   * 둘이 함께 잡혀 strict mode 위반이 된다. Testing Library 는 기본이 완전 일치라 안전하다.
   * 실측 — `e2e/scrum-board.spec.ts` 의 빈 상태 클릭은 이미 `exact: true` 다.
   */
  it('탭 `백로그` 는 빈 상태 `백로그로 이동` 의 substring 이다 (exact 조회 필수 근거)', () => {
    // 관계가 사라지면(둘 중 하나를 개명) 이 단언이 red 가 되어 규약 ③ 을 다시 보게 한다.
    expect(scrumEmptyStateLabels.backlogLink).toContain(projectViewLabels.backlog)
    expect(scrumEmptyStateLabels.backlogLink).not.toBe(projectViewLabels.backlog)
  })

  /**
   * 리포트 화면 한정. 탭 `리포트` 와 같은 낱말이 **탭바 밖에** 둘 더 있고 둘 다 한 화면에 있다.
   *
   * | 공존 문구 | 관계 | 소유자 | role |
   * |---|---|---|---|
   * | `리포트 전환` | `리포트` ⊂ 값 | `ProjectReportsNav` 의 `aria-label` | `navigation` |
   * | `리포트` | 값이 같다 | 리포트 착지 화면 제목 | `heading` |
   *
   * 🛑 두 문자열은 각자의 소스 파일이 `const` 로 소유하고 **export 하지 않는다**. 여기에
   *    리터럴로 얼려 두는 것은 그래서다 — `ProjectReportsNav.test.tsx` 가 같은 근거로
   *    `REPORTS_NAV_NAME` 을 리터럴로 갖고 있다. 탭 라벨 쪽을 개명하면 이 단언이 red 가 되어
   *    「셋이 한 화면에 있다」는 사실을 다시 보게 만든다.
   */
  it('탭 `리포트` 는 role 이 다른 두 문구와 한 화면에 공존한다 (조회 규약 ④ 근거)', () => {
    /** 🔒 `ProjectReportsNav.tsx` 의 `REPORTS_NAV_LABEL` */
    const reportsNavLabel = '리포트 전환'
    /** 🔒 `routes/projects.$projectKey.reports.index.tsx` 의 `REPORTS_INDEX_TITLE` */
    const reportsIndexTitle = '리포트'

    expect(reportsNavLabel).toContain(projectViewLabels.reports)
    expect(reportsNavLabel).not.toBe(projectViewLabels.reports)
    expect(reportsIndexTitle).toBe(projectViewLabels.reports)

    // 관리 수단은 **role 지정**이다. 셋의 role 이 link/navigation/heading 으로 갈려 있어
    // `getByRole('link', { name: '리포트', exact: true })` 는 탭 하나만 잡는다.
    // 🛑 `getByText('리포트')` 처럼 role 을 안 쓰면 착지 화면에서 2건이 잡혀 strict mode 위반이다.
  })

  it('그 밖의 탭 라벨은 빈 상태의 **조작 가능한** 이름과 substring 관계가 아니다', () => {
    /**
     * 대조 범위를 링크·버튼 이름으로 좁힌다.
     *
     * 🛑 문구 전량과 대조하면 안 된다 — `'이 스프린트에 이슈가 없습니다'`(제목)와
     *    `'백로그에서 이 스프린트로 이슈를 옮기세요.'`(설명)가 `이슈` 를 품지만 **제목·문단은
     *    `getByRole` 의 이름 표면이 아니다.** 그것까지 금지하면 멀쩡한 한국어 안내문을 못 쓰게
     *    되고, 판별식이 「지키라는 것」과 「실제 위험」이 어긋난다.
     *    `nav-labels.test.ts` 가 placeholder 를 같은 근거로 면제한 것과 같은 판단이다.
     */
    const interactiveNames: readonly string[] = [scrumEmptyStateLabels.backlogLink]

    // 비-공허 짝 — 좁히기가 「전부 버리기」가 되지 않았는지 본다. 평탄화하면 문구가 더 많고,
    // 그중 조작 가능한 것만 골라 냈다는 사실을 수치로 남긴다.
    const allStrings = Object.values(scrumEmptyStateLabels).flatMap((value) =>
      typeof value === 'string' ? [value] : Object.values(value),
    )
    expect(allStrings.length).toBeGreaterThan(interactiveNames.length)
    expect(interactiveNames).toHaveLength(1)

    const violations = Object.entries(projectViewLabels)
      .filter(([key]) => key !== 'backlog')
      .flatMap(([key, value]) =>
        interactiveNames
          .filter((outer) => outer.includes(value))
          .map((outer) => `${key}('${value}') ⊂ '${outer}'`),
      )

    expect(violations).toEqual([])
  })
})
