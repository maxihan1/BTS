// TODOS.md 의 항목이 「어느 절에 있는가」를 강제하는 구조 계약 판별식
//
// ## 이 파일이 지키는 것 (다섯 축)
//
// ① **절 이름 집합** == `CATEGORIES[].name` + 계약 대상 아닌 상태의 `heading` (양방향 차집합 0).
// ② **미해결·보류 항목의 절 배정** == `AREA_CATEGORIES[areaOfTitle(제목)]`.
// ③ **해소 항목**은 전부 해소 절에 있다.
// ④ **어느 카테고리 절에도 안 속한 항목 0** (파일 머리 H1 아래에 남은 항목 0).
// ⑤ **모든 항목이 영역 접두를 갖는다** (해소 포함). 매핑 존재까지는 묻지 않는다.
//
// ## 이 파일이 지키지 **않는** 것
//
// - 항목·줄이 **사라졌는지**. `todos-reorder-integrity.test.ts` 소관이다.
// - 두 줄(「쉬운 말」·「방치하면」) 존재·내용. `todos-plain-language-contract.test.ts` 소관.
// - `✅` 섹션 본문의 순수성. `todos-resolved-section-purity.test.ts` 소관.
// - 마스터 계획과의 매핑·PR 열. `debt-ledger-mapping.test.ts` 소관.
//
// ## ★절 이름을 문자열로 적지 않는다
//
// 이 파일 전체에 카테고리 이름도 「해소」도 **리터럴로 나오지 않는다.** 절 제목은 전부
// `CATEGORIES` 와 `TODO_STATUSES` 에서 파생한다. 하나라도 적는 순간 손으로 유지하는
// 두 번째 목록이 되고, 상수를 고쳐도 이 파일은 옛 이름을 계속 쓰면서 초록이 된다 —
// `[[two-lists-never-check-each-other]]` 그대로다.
//
// 해소 절은 「계약 대상이 아닌 상태」의 여집합으로 정의한다. 그래야 `'해소'` 를 적지 않고,
// 의미도 맞는다 — 카테고리로 나누지 않는 상태가 곧 뒤로 모이는 상태다(선행 NFR N2).
//
// ## ★파서를 새로 적지 않는다
//
// `parseTodos` 를 import 한다. 절 추적은 그 함수가 하고, 이 파일은 결과만 읽는다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import {
  parseTodos,
  areaOfTitle,
  AREA_CATEGORIES,
  CATEGORIES,
  CONTRACTED_STATUSES,
  TODO_STATUSES,
} from '../build-dashboard.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const LEDGER = path.join(REPO_ROOT, 'TODOS.md')

/**
 * 카테고리로 나누지 않는 상태들 = 계약 대상의 여집합.
 *
 * 실데이터에서는 해소 1종이다. 여집합으로 잡는 이유는 위 헤더의 「리터럴 금지」 그대로 —
 * `'해소'` 를 적지 않으려면 이 방향뿐이다.
 */
const UNCONTRACTED_STATUSES = TODO_STATUSES.filter(
  (s: { status: string }) => !CONTRACTED_STATUSES.includes(s.status),
)

/** 카테고리 절 제목 = `CATEGORIES[].name`. */
const CATEGORY_SECTIONS = Object.values(CATEGORIES).map((c: { name: string }) => c.name)

/** 뒤로 모이는 절 제목 = 계약 대상 아닌 상태의 `heading`. */
const TRAILING_SECTIONS = UNCONTRACTED_STATUSES.map((s: { heading: string }) => s.heading)

/** 파일에 있어야 할 절 제목 전량. */
const EXPECTED_SECTIONS = [...CATEGORY_SECTIONS, ...TRAILING_SECTIONS]

/** 항목이 있어야 할 절 제목. 계약 대상이면 영역이 정하고, 아니면 뒤로 모인다. */
function expectedSectionOf(item: { status: string; title: string }): string | null {
  if (!CONTRACTED_STATUSES.includes(item.status)) {
    const s = UNCONTRACTED_STATUSES.find((x: { status: string }) => x.status === item.status)
    return s ? s.heading : null
  }
  const area = areaOfTitle(item.title)
  const cat = area === null ? null : AREA_CATEGORIES[area]
  return cat ? cat.name : null
}

function readLedger(): string {
  return fs.readFileSync(LEDGER, 'utf-8')
}

describe('TODOS.md — 구조 계약', () => {
  test('항목을 실제로 수집한다 (비-공허 짝)', () => {
    const items = parseTodos(readLedger())
    assert.ok(items.length > 0, `TODOS.md 에서 항목을 하나도 수집하지 못했다. 경로: ${LEDGER}`)
    const contracted = items.filter((t: { status: string }) => CONTRACTED_STATUSES.includes(t.status))
    assert.ok(contracted.length > 0, '계약 대상(미착수·보류) 항목이 0건이다 — 아래 축들이 공허하게 통과한다')
    assert.ok(
      UNCONTRACTED_STATUSES.length > 0 && TRAILING_SECTIONS.length > 0,
      '뒤로 모이는 절이 0종이다 — 축①③ 이 공허해진다',
    )
  })

  test('축① 절 이름 집합이 상수와 양방향으로 같다', () => {
    const actual = new Set(
      parseTodos(readLedger())
        .map((t: { section: string | null }) => t.section)
        .filter((s: string | null): s is string => s !== null),
    )
    const expected = new Set(EXPECTED_SECTIONS)
    const missing = [...expected].filter((s) => !actual.has(s))
    const extra = [...actual].filter((s) => !expected.has(s))
    assert.deepEqual(
      { missing, extra },
      { missing: [], extra: [] },
      `절 이름이 상수와 갈라졌다.\n` +
        `  상수에 있는데 파일에 절이 없음: ${missing.join(' · ') || '(없음)'}\n` +
        `  파일에 있는데 상수에 없음: ${extra.join(' · ') || '(없음)'}\n` +
        `절 제목은 CATEGORIES[].name 과 TODO_STATUSES 의 heading 에서만 나온다.`,
    )
  })

  test('축② 미해결·보류 항목이 자기 영역의 카테고리 절에 있다', () => {
    const wrong = parseTodos(readLedger())
      .filter((t: { status: string }) => CONTRACTED_STATUSES.includes(t.status))
      .map((t: { status: string; title: string; section: string | null }) => ({
        t,
        want: expectedSectionOf(t),
      }))
      .filter((x: { t: { section: string | null }; want: string | null }) => x.t.section !== x.want)
      .map(
        (x: { t: { title: string; section: string | null }; want: string | null }) =>
          `  「${x.t.title.slice(0, 60)}」\n    있어야 할 절: ${x.want ?? '(영역 매핑 없음)'}\n    실제 있는 절: ${x.t.section ?? '(절 밖)'}`,
      )
    assert.deepEqual(
      wrong,
      [],
      `항목이 자기 영역의 카테고리 절에 있지 않다.\n` +
        `절은 제목의 영역 접두가 정한다 — 매핑 정본은 build-dashboard.mjs 의 AREA_CATEGORIES 다.\n\n` +
        wrong.join('\n'),
    )
  })

  test('축③ 해소 항목이 전부 뒤 절에 있다', () => {
    const wrong = parseTodos(readLedger())
      .filter((t: { status: string }) => !CONTRACTED_STATUSES.includes(t.status))
      .map((t: { title: string; section: string | null; status: string }) => ({ t, want: expectedSectionOf(t) }))
      .filter((x: { t: { section: string | null }; want: string | null }) => x.t.section !== x.want)
      .map(
        (x: { t: { title: string; section: string | null }; want: string | null }) =>
          `  「${x.t.title.slice(0, 60)}」 → 있어야 할 절 ${x.want} · 실제 ${x.t.section ?? '(절 밖)'}`,
      )
    assert.deepEqual(wrong, [], `해소 항목이 뒤 절 밖에 있다.\n\n${wrong.join('\n')}`)
  })

  test('축④ 어느 절에도 안 속한 항목이 0 이다', () => {
    const orphans = parseTodos(readLedger())
      .filter((t: { section: string | null }) => t.section === null || !EXPECTED_SECTIONS.includes(t.section))
      .map((t: { title: string; section: string | null }) => `  「${t.title.slice(0, 60)}」 (절: ${t.section ?? 'null'})`)
    assert.deepEqual(
      orphans,
      [],
      `어느 카테고리 절에도 안 속한 항목이 있다 — 파일 머리 H1 아래에 남았거나 절 밖이다.\n\n${orphans.join('\n')}`,
    )
  })

  test('축⑤ 모든 항목이 영역 접두를 갖는다 (해소 포함)', () => {
    const noArea = parseTodos(readLedger())
      .filter((t: { title: string }) => areaOfTitle(t.title) === null)
      .map((t: { status: string; title: string }) => `  ${t.status} 「${t.title.slice(0, 70)}」`)
    assert.deepEqual(
      noArea,
      [],
      `제목에 영역 접두(\`<영역> — \`)가 없는 항목이 있다.\n` +
        `매핑에 없는 영역이어도 괜찮다 — 접두 자체가 없는 것만 막는다.\n\n${noArea.join('\n')}`,
    )
  })
})

describe('TODOS.md — 구조 계약 판별식의 양성 대조군', () => {
  /** 합성 장부를 만든다. 절 제목은 상수에서 뽑아 쓴다. */
  function synth(opts: { sectionName?: string; itemSection?: string; area?: string; noArea?: boolean } = {}) {
    const cat = CATEGORY_SECTIONS[0]
    const trailing = TRAILING_SECTIONS[0]
    const area = opts.area ?? Object.keys(AREA_CATEGORIES).find((a) => AREA_CATEGORIES[a].name === cat)!
    const title = opts.noArea ? '접두 없는 항목' : `${area} — 합성 항목`
    return [
      '# TODOS',
      '',
      `# ${opts.sectionName ?? opts.itemSection ?? cat}`,
      '',
      `## ⬜ ${title}`,
      '',
      '**무엇.** 본문.',
      '',
      `# ${trailing}`,
      '',
      '## ✅ 인프라 — 해소된 합성 항목',
      '',
      '**무엇.** 본문.',
      '',
    ].join('\n')
  }

  test('축① — 상수에 없는 절 이름을 쓰면 잡는다', () => {
    const items = parseTodos(synth({ sectionName: '없는 절 이름' }))
    const actual = new Set(items.map((t: { section: string | null }) => t.section))
    assert.ok(actual.has('없는 절 이름'), '합성 입력이 의도한 절을 만들지 못했다')
    assert.ok(
      [...actual].some((s) => s !== null && !EXPECTED_SECTIONS.includes(s)),
      '상수 밖 절 이름을 탐지하지 못한다 — 축① 이 죽어 있다',
    )
  })

  test('축② — 항목을 엉뚱한 카테고리 절에 두면 잡는다', () => {
    const wrongCat = CATEGORY_SECTIONS[1]
    const items = parseTodos(synth({ itemSection: wrongCat }))
    const contracted = items.filter((t: { status: string }) => CONTRACTED_STATUSES.includes(t.status))
    assert.equal(contracted.length, 1, '합성 입력에서 계약 항목 1건을 뽑아야 한다')
    assert.notEqual(
      contracted[0].section,
      expectedSectionOf(contracted[0]),
      '엉뚱한 절에 둔 항목을 탐지하지 못한다 — 축② 가 죽어 있다',
    )
  })

  test('축④ — 파일 머리 H1 아래 항목을 잡는다', () => {
    const md = ['# TODOS', '', '## ⬜ apps/web — 머리에 남은 항목', '', '**무엇.** 본문.', ''].join('\n')
    const items = parseTodos(md)
    assert.equal(items.length, 1)
    assert.ok(
      !EXPECTED_SECTIONS.includes(items[0].section as string),
      '머리 H1 아래 항목을 절 밖으로 판정하지 못한다 — 축④ 가 죽어 있다',
    )
  })

  test('축⑤ — 영역 접두 없는 항목을 잡는다', () => {
    const items = parseTodos(synth({ noArea: true }))
    assert.ok(
      items.some((t: { title: string }) => areaOfTitle(t.title) === null),
      '접두 없는 항목을 탐지하지 못한다 — 축⑤ 가 죽어 있다',
    )
  })
})
