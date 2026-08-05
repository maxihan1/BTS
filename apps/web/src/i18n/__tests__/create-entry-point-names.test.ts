// 이슈 생성 진입점 이름 전수 판별식 — 같은 화면 버튼 이름끼리 substring 충돌 0 (FR-UX-09 F3 FR-14)

import { describe, expect, it } from 'vitest'
import { navLabels } from '../nav-labels'
import { backlogLabels } from '../backlog-labels'
import { boardLabels } from '../board-labels'
import { burndownLabels } from '../burndown-labels'
import { issueCreateStrings } from '../ko'

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

/** 한 집합의 모든 (a, b) 순서쌍 — 손으로 나열하지 않는다 */
function orderedPairs(names: Readonly<Record<string, string>>) {
  return Object.entries(names).flatMap(([keyA, valueA]) =>
    Object.entries(names)
      .filter(([keyB]) => keyB !== keyA)
      .map(([keyB, valueB]) => ({ keyA, valueA, keyB, valueB })),
  )
}

describe('FR-14 — 진입점 버튼 이름 substring 전수 판별식', () => {
  for (const [screen, names] of Object.entries(SCREENS)) {
    describe(`${screen} 화면`, () => {
      it('비-공허: 검사 대상 쌍이 N*(N-1) 건이다', () => {
        // 집합이 비거나 훑기가 고장 나면 아래 단언들이 조용히 통과한다.
        const keyCount = Object.keys(names).length
        expect(keyCount).toBeGreaterThan(1)
        expect(orderedPairs(names)).toHaveLength(keyCount * (keyCount - 1))
      })

      it('어떤 쌍도 substring 관계가 아니다', () => {
        const violations = orderedPairs(names)
          .filter(({ valueA, valueB }) => valueB.includes(valueA))
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
    expect(Object.values(BOARD_SCREEN_BUTTON_NAMES)).toContain(boardLabels.page.createIssue)
  })

  it('스프린트 진입점 이름은 스프린트마다 달라진다 (FR-10 — 같은 화면 N개 공존)', () => {
    expect(backlogLabels.createIssueInSprint('A')).not.toBe(
      backlogLabels.createIssueInSprint('B'),
    )
  })
})
