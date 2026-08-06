// 이슈 생성 진입점 이름 전수 판별식 — 같은 화면 버튼 이름끼리 substring 충돌 0 (FR-UX-09 F3 FR-14)

import { describe, expect, it } from 'vitest'
import { navLabels } from '../nav-labels'
import { backlogLabels } from '../backlog-labels'
import { boardLabels } from '../board-labels'
import { burndownLabels } from '../burndown-labels'
import { filterBarLabels } from '../filter-bar-labels'
import { issueCreateStrings } from '../ko'
// ★i18n 밖의 정본을 끌어오는 유일한 import. `필터 초기화` 는 `i18n/` 이 아니라
//   `BacklogBoard.tsx` 가 문자열 상수로 소유한다(F16 이 세운 관례 — 컴포넌트 모듈은
//   `react-refresh/only-export-components` 때문에 라벨 **객체**를 못 내보낸다).
//   여기서 리터럴을 다시 적으면 두 목록이 서로를 검사하지 않는 상태가 되고, 이름이
//   바뀌는 날 판별식만 조용히 낡는다 (`two-lists-never-check-each-other`).
import { BACKLOG_FILTER_RESET_LABEL } from '@/components/backlog/BacklogBoard'
import { EPIC_PANEL_TITLE } from '@/components/backlog/BacklogEpicPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 왜 이 판별식이 있나
//
// Playwright `getByRole(role, { name })` 과 Testing Library 의 정규식 매칭은
// **둘 다 기본이 부분 일치**다. 그래서 새 버튼 이름이 기존 버튼 이름을 부분 문자열로
// 포함하면, 멀쩡한 구현인데도 기존 e2e·단위 테스트가 strict mode 로 깨진다.
//
// 실측된 지뢰 3종 (2026-08-03).
//   - `navLabels.create`('만들기')            — 상단바. **모든 페이지에 있다**
//   - `issueCreateStrings.submitButton`('이슈 생성') — 생성 모달 제출 버튼
//   - `backlogLabels.createSprint`('스프린트 생성')  — 백로그 화면
// e2e `issue-create-dialog.spec.ts:18-19` 가 앞의 둘을 **비-exact** 로 조회한다.
//
// 손으로 3~5개만 나열하면 놓친다 — FR-UX-08 PR-B 의 `admin`('관리') ⊂ `adminNav`('관리 메뉴')가
// 정확히 손나열이 놓친 결함이었고, 전수 판별식이 첫 실행에서 잡았다.
//
// 범위. **같은 화면에서 `button` role 의 접근 가능한 이름으로 조회될 수 있는 값**.
//
// 제외 3종과 그 사유.
//   1. 제목·본문 텍스트 — role 이 달라 조회 공간이 겹치지 않는다.
//      (`dialogTitle`='새 이슈 만들기' 는 이미 `navLabels.create`('만들기')를 포함하고 있어,
//       넣으면 판별식이 구조적으로 성립 불가가 된다.)
//   2. **같은 버튼의 다른 상태** — 공존하지 않는다.
//      `submitButtonPending`('이슈 생성 중…')은 `submitButton`('이슈 생성')을 포함하지만,
//      한 버튼이 둘 중 하나만 보이므로 같은 순간에 두 이름이 화면에 있을 수 없다.
//      집합의 단위는 **버튼 상태가 아니라 버튼 정체**다.
//   3. **`navLabels.search`('검색') — 상단바에 그 이름의 `button` 이 없다** (FR-UX-12 F13).
//      F13 이 상단바 검색을 버튼에서 `role="searchbox"` + `aria-label="전역 검색"` 입력창으로
//      바꿨다. 같은 PR 의 `ShellLayout.test.tsx`·`navigation-contract.test.tsx` 가
//      「상단바에 `검색` 버튼 0개」를 단언한다. 여기 §범위는 **button role** 이므로
//      `searchbox` 는 애초에 대상이 아니다. 남겨두면 화면에 없는 이름을 지키는 stale 입력이
//      돼, 훗날 `검색 결과` 버튼이 추가되면 존재하지 않는 상단바 버튼에 대한 유령 위반을
//      띄운다 (2차 코드리뷰 CONCERNS-2, `two-lists-never-check-each-other` 양식).
//      ⚠️ `'전역 검색'` 과 `'검색'` 의 substring 충돌 방어는 이 파일이 아니라
//      `nav-labels.test.ts` 의 「FR15 라벨 쌍 substring 전수 판별식」이 맡는다
//      (`ALLOWED_SUBSTRING_PAIRS` 의 `['search', 'globalSearch']` 면제 + 그 근거 주석).
//      `topBarCreate`('만들기')는 여전히 button 이라 **그대로 둔다**.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 화면에서 버튼 접근 이름으로 동시에 존재할 수 있는 값.
 *
 * 상단바·생성 모달은 **어느 화면에서나** 같이 뜨므로 항상 포함한다.
 * 토스트 액션(`createdToastAction`='보기')도 sonner 가 실제 `button` 으로 렌더한다.
 */
const BACKLOG_SCREEN_BUTTON_NAMES = {
  // 상단바 (모든 페이지) — `navLabels.search`('검색')는 여기 없다. 제외 3종 §3 참조
  topBarCreate: navLabels.create,
  // 생성 모달 (열린 동안 공존 — 스펙 E-10)
  dialogSubmit: issueCreateStrings.submitButton,
  dialogCancel: issueCreateStrings.cancelButton,
  toastAction: issueCreateStrings.createdToastAction,
  // 백로그 화면 고유
  createSprint: backlogLabels.createSprint,
  startSprint: backlogLabels.startSprint,
  completeSprint: backlogLabels.completeSprint,
  burndown: burndownLabels.toggle.burndown,
  // ★이 PR 이 추가하는 진입점 2종
  createIssueInBacklog: backlogLabels.createIssueInBacklog,
  createIssueInSprint: backlogLabels.createIssueInSprint('2026-W31'),
  // ★FR-UX-13 F5 가 추가하는 진입점 1종 — 등록을 빠뜨리면 판별식이 조용히 공허해진다.
  // `retrying`('다시 시도 중…')은 **넣지 않는다** — 같은 버튼의 다른 상태(§제외 3종 ②).
  retry: backlogLabels.retry,
  // ★FR-UX-13 F15 가 추가하는 섹션 접기 토글 1종.
  // 상태(접힘/펼침)로 이름이 갈리지 않는다 — 섹션 A 는 접힘, B 는 펼침이 **동시에** 가능해
  // §제외 3종 ②「같은 버튼의 다른 상태」가 성립하지 않기 때문이다 (스펙 §리뷰 반영 C-9).
  // `스프린트 시작 중…`·`스프린트 완료 중…`은 반대로 진짜 「같은 버튼의 다른 상태」라 **넣지 않는다**.
  collapseSection: backlogLabels.collapseSection('2026-W31'),
  // ★FR-UX-13 F16 이 추가하는 3종.
  //
  // ① 에픽 패널 접기 토글 — `collapseSection` 생성기를 재사용하지만 **다른 버튼**이다.
  //    섹션 토글과 **동시에** 화면에 있으므로 §제외 3종 ②가 성립하지 않는다.
  // ② 필터바 초기화(`초기화`) · ③ 필터 0건 빈 상태의 초기화(`필터 초기화`).
  //    이 둘은 **실제로 공존한다** — 필터 결과가 0건이면 필터바(위)와 빈 상태(아래)가
  //    같은 화면에 함께 뜬다. 2026-08-06 실측(브라우저): `getByRole('button', {name:'초기화'})`
  //    가 **2개**를 잡고, `exact: true` 를 붙이면 1개다. 그래서 substring 관계를 **면제**로
  //    명시하고(§ALLOWED_SUBSTRING_PAIRS) e2e 는 `exact: true` 로 조회한다.
  epicPanelCollapse: backlogLabels.collapseSection(EPIC_PANEL_TITLE),
  filterReset: filterBarLabels.filter.reset,
  filteredEmptyReset: BACKLOG_FILTER_RESET_LABEL,
} as const

/** 보드 화면에서 버튼 접근 이름으로 동시에 존재할 수 있는 값. */
const BOARD_SCREEN_BUTTON_NAMES = {
  // 상단바 — 제외 3종 §3 (`navLabels.search` 는 F13 이후 상단바 button 이 아니다)
  topBarCreate: navLabels.create,
  dialogSubmit: issueCreateStrings.submitButton,
  dialogCancel: issueCreateStrings.cancelButton,
  toastAction: issueCreateStrings.createdToastAction,
  // ★이 PR 이 추가하는 진입점 1종
  createIssue: boardLabels.page.createIssue,
} as const

/** 화면 이름 → 그 화면의 버튼 이름 집합 */
const SCREENS = {
  백로그: BACKLOG_SCREEN_BUTTON_NAMES,
  보드: BOARD_SCREEN_BUTTON_NAMES,
} as const

// ─────────────────────────────────────────────────────────────────────────────
// substring 면제 — 「고칠 수 없어서」가 아니라 「고치지 않기로 결정해서」 여는 구멍
// ─────────────────────────────────────────────────────────────────────────────

/**
 * substring 관계를 **의도적으로 허용**하는 `[안쪽, 바깥쪽]` 키 쌍 — 화면별.
 *
 * 면제는 「이 이름 쌍은 공존해도 된다」가 아니라 **「공존하므로 조회는 반드시
 * `exact: true`(또는 컨테이너 한정)로 한다」는 계약**이다. 아래 두 짝 테스트가 그 면제를
 * 지킨다 — 실제로 substring 관계여야 하고(짝이 틀리면 엉뚱한 쌍이 면제된다), 키가 실재해야
 * 한다(오타 난 키는 아무것도 면제하지 않으면서 목록만 늘린다).
 *
 * `nav-labels.test.ts` 의 `ALLOWED_SUBSTRING_PAIRS` 와 같은 형태다.
 */
const ALLOWED_SUBSTRING_PAIRS: {
  readonly [S in keyof typeof SCREENS]: ReadonlyArray<
    readonly [keyof (typeof SCREENS)[S], keyof (typeof SCREENS)[S]]
  >
} = {
  /**
   * `초기화` ⊂ `필터 초기화` (FR-UX-13 F16).
   *
   * 필터 결과가 0건이면 필터바의 `초기화` 와 빈 상태의 `필터 초기화` 가 **같은 화면에
   * 함께** 있다(2026-08-06 브라우저 실측 — 비-exact 조회가 2개를 잡는다).
   * 이름을 가르는 쪽은 이미 `routes/issues.index.tsx` 가 같은 두 문구로 운영 중이고,
   * `IssueFilterBar` 와 공존해 온 선례다. 그래서 이름을 또 바꾸지 않고 면제로 명시한다.
   * 대신 `e2e/backlog.spec.ts` 는 두 버튼을 **`exact: true`** 로만 조회한다.
   */
  백로그: [['filterReset', 'filteredEmptyReset']],
  보드: [],
}

/** 한 집합의 모든 (a, b) 순서쌍 — 손으로 나열하지 않는다 */
function orderedPairs(names: Readonly<Record<string, string>>) {
  return Object.entries(names).flatMap(([keyA, valueA]) =>
    Object.entries(names)
      .filter(([keyB]) => keyB !== keyA)
      .map(([keyB, valueB]) => ({ keyA, valueA, keyB, valueB })),
  )
}

/**
 * 화면 하나의 면제 목록을 꺼낸다.
 *
 * 타입이 모든 화면 키를 강제하므로 `undefined` 는 도달 불가지만, `Object.entries` 를
 * 지나면 타입이 사라진다. **조용한 빈 배열 폴백을 두지 않는 것**이 요점이다 — 화면이
 * 늘었는데 면제 목록에 키를 안 넣으면 여기서 터져야 한다(아래 「키 집합 일치」 테스트가
 * 그 짝이다).
 */
function allowedPairsOf(screen: string): ReadonlyArray<readonly [string, string]> {
  const found = (ALLOWED_SUBSTRING_PAIRS as Record<string, ReadonlyArray<readonly [string, string]>>)[
    screen
  ]
  if (found === undefined) {
    throw new Error(`ALLOWED_SUBSTRING_PAIRS 에 '${screen}' 화면이 없습니다.`)
  }
  return found
}

describe('FR-14 — 진입점 버튼 이름 substring 전수 판별식', () => {
  it('면제 목록의 화면 키가 SCREENS 와 정확히 같다', () => {
    // 두 목록이 서로를 검사하지 않으면 새 화면이 면제 없이(또는 유령 면제로) 지나간다.
    expect(Object.keys(ALLOWED_SUBSTRING_PAIRS).sort()).toEqual(Object.keys(SCREENS).sort())
  })

  for (const [screen, names] of Object.entries(SCREENS)) {
    describe(`${screen} 화면`, () => {
      it('비-공허: 검사 대상 쌍이 N*(N-1) 건이다', () => {
        // 집합이 비거나 훑기가 고장 나면 아래 단언들이 조용히 통과한다.
        const keyCount = Object.keys(names).length
        expect(keyCount).toBeGreaterThan(1)
        expect(orderedPairs(names)).toHaveLength(keyCount * (keyCount - 1))
      })

      it('면제 짝 검사: 허용 쌍은 실재하는 키이며 실제로 substring 관계다', () => {
        // 짝을 잘못 적으면 파생이 틀린 쌍을 면제하며 통과한다(#323 M9 동형 결함).
        const table = names as Readonly<Record<string, string>>
        for (const [inner, outer] of allowedPairsOf(screen)) {
          expect(inner, `면제 안쪽 키 '${inner}' 가 ${screen} 집합에 없다`).toBeOneOf(
            Object.keys(table),
          )
          expect(outer, `면제 바깥쪽 키 '${outer}' 가 ${screen} 집합에 없다`).toBeOneOf(
            Object.keys(table),
          )
          expect(inner).not.toBe(outer)
          expect(String(table[outer]).includes(String(table[inner]))).toBe(true)
        }
      })

      it('면제되지 않은 어떤 쌍도 substring 관계가 아니다', () => {
        const allowed = allowedPairsOf(screen)
        const isAllowed = (keyA: string, keyB: string): boolean =>
          allowed.some(([inner, outer]) => inner === keyA && outer === keyB)

        const violations = orderedPairs(names)
          .filter(({ keyA, valueA, keyB, valueB }) => valueB.includes(valueA) && !isAllowed(keyA, keyB))
          .map(({ keyA, valueA, keyB, valueB }) => `${keyA}('${valueA}') ⊂ ${keyB}('${valueB}')`)

        expect(violations).toEqual([])
      })

      it('모든 이름이 비어 있지 않다 (빈 문자열은 모든 문자열의 substring)', () => {
        for (const [key, value] of Object.entries(names)) {
          expect(value, `${key} 이름이 비어 있다`).not.toBe('')
        }
      })

      it('이름 중복이 없다 (같은 값 두 키는 서로의 substring)', () => {
        const values = Object.values(names)
        expect(new Set(values).size).toBe(values.length)
      })
    })
  }

  // ───────────────────────────────────────────────────────────────────────────
  // 집합에서 신규 진입점을 조용히 빼면 판별식이 공허해진다 — 그것 자체를 막는다.
  // ───────────────────────────────────────────────────────────────────────────
  // ★제목에 개수를 쓰지 않는다. FR-UX-09 F3 이 「3종」으로 적어둔 뒤 FR-UX-13 F5 가 `retry` 를
  //   더하면서 제목만 낡아 실제 단언 수(4)와 어긋났다. 개수 리터럴은 stale 해지는 순간 거짓이 된다.
  it('이 화면 집합에 등록돼야 할 진입점이 실제로 들어 있다', () => {
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(
      backlogLabels.createIssueInBacklog,
    )
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(
      backlogLabels.createIssueInSprint('2026-W31'),
    )
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(backlogLabels.retry)
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(
      backlogLabels.collapseSection('2026-W31'),
    )
    // ★FR-UX-13 F16 이 백로그 화면에 들여온 버튼 3종.
    //   `초기화`·`필터 초기화` 를 빼먹으면 「같은 화면에 substring 쌍 없음」이 거짓인 채로
    //   판별식이 초록이 되고, e2e 가 머지 시점에 strict mode 로 터진다(T6 이 실측 적발).
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(
      backlogLabels.collapseSection(EPIC_PANEL_TITLE),
    )
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(filterBarLabels.filter.reset)
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(BACKLOG_FILTER_RESET_LABEL)
    expect(Object.values(BOARD_SCREEN_BUTTON_NAMES)).toContain(boardLabels.page.createIssue)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 면제가 「없어도 되는 것」이 아님을 그 자리에서 증명한다 (FR-UX-13 F16)
  // ───────────────────────────────────────────────────────────────────────────
  it('비-공허: 면제를 빼면 백로그 화면이 실제로 위반을 낸다', () => {
    // 면제 목록을 **비운 채** 같은 판정을 돌려 위반이 나오는지 본다. 위반이 0건이면
    // 면제는 아무것도 면제하지 않는 장식이고, 그 장식은 훗날 진짜 충돌을 가려 준다.
    const violationsWithoutExemption = orderedPairs(BACKLOG_SCREEN_BUTTON_NAMES)
      .filter(({ valueA, valueB }) => valueB.includes(valueA))
      .map(({ keyA, keyB }) => `${keyA} ⊂ ${keyB}`)

    expect(violationsWithoutExemption).toEqual(['filterReset ⊂ filteredEmptyReset'])
  })

  it('면제된 두 이름은 Playwright 기본 조회(부분 일치)로는 갈리지 않는다', () => {
    // 「그래서 왜 exact 가 필요한가」를 코드로 남긴다 — 주석은 실행되지 않는다.
    const partialMatches = Object.values(BACKLOG_SCREEN_BUTTON_NAMES).filter((name) =>
      name.includes(filterBarLabels.filter.reset),
    )
    expect(partialMatches).toHaveLength(2)

    // exact 로는 정확히 하나다.
    const exactMatches = Object.values(BACKLOG_SCREEN_BUTTON_NAMES).filter(
      (name) => name === filterBarLabels.filter.reset,
    )
    expect(exactMatches).toHaveLength(1)
  })

  it('섹션 접기 토글 이름은 섹션마다 달라진다 (FR-2 — 같은 화면 N개 공존)', () => {
    expect(backlogLabels.collapseSection('백로그')).not.toBe(
      backlogLabels.collapseSection('스프린트 1'),
    )
  })

  it('스프린트 진입점 이름은 스프린트마다 달라진다 (FR-10 — 같은 화면 N개 공존)', () => {
    expect(backlogLabels.createIssueInSprint('A')).not.toBe(
      backlogLabels.createIssueInSprint('B'),
    )
  })
})
