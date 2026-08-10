// TODOS.md 의 ✅ 섹션 본문에 미해결 마커가 숨는 것을 차단하는 판별식

import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { TODO_STATUSES, parseTodos } from '../build-dashboard.mjs'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const TODOS = resolve(REPO_ROOT, 'TODOS.md')

/**
 * 상태 마커 목록 — **파서와 같은 상수에서 파생**한다.
 *
 * ★여기 마커를 손으로 적지 않는다. 2026-08-10 이전에는 이 파일이 ✅·📌·⬜ 셋을 허용하는데
 * `build-dashboard.mjs` 의 파서는 둘만 인식했다. 두 목록이 서로를 안 봐서, 📌 섹션을
 * 만드는 순간 대시보드에서 통째로 사라지는 상태가 **잠복**해 있었다
 * (`two-lists-never-check-each-other`). 이제 목록은 하나다.
 */
const ALL_MARKERS: string[] = TODO_STATUSES.map((s: { marker: string }) => s.marker)

/**
 * 미해결을 뜻하는 마커. 해소(✅)·보류(📌)와 구분된다.
 *
 * ★기본값으로 물러나지 않는다. `?? '⬜'` 같은 폴백을 두면 상수에서 「미착수」가 사라져도
 * 이 파일만 조용히 옛 마커로 계속 돌아 **두 목록이 다시 갈라진다** — 방금 없앤 결함의 재발이다.
 */
const OPEN_MARKER: string = (() => {
  const found = TODO_STATUSES.find((s: { status: string }) => s.status === '미착수')
  if (found === undefined) {
    throw new Error(
      'build-dashboard.mjs 의 TODO_STATUSES 에 「미착수」 상태가 없다 — ' +
        '이 판별식이 무엇을 미해결로 볼지 정할 수 없다. 상수를 먼저 확인하라.',
    )
  }
  return found.marker
})()

interface Section {
  /** `## ` 다음의 제목 (마커 포함). */
  heading: string
  /** 1-indexed 시작 줄. */
  startLine: number
  /** 본문 줄들 — 제목 줄은 제외한다. */
  body: { line: number; text: string }[]
}

/**
 * `## ` 헤딩 단위로 문서를 자른다.
 *
 * 헤딩 앞의 서문(파일 상단 주석 · `# TODOS`)은 어느 섹션에도 속하지 않으므로 버린다.
 */
function parseSections(markdown: string): Section[] {
  const sections: Section[] = []
  let current: Section | null = null

  markdown.split('\n').forEach((text, index) => {
    const line = index + 1
    if (text.startsWith('## ')) {
      current = { heading: text.slice(3).trim(), startLine: line, body: [] }
      sections.push(current)
      return
    }
    if (current) current.body.push({ line, text })
  })

  return sections
}

describe('TODOS.md — ✅ 섹션의 순수성', () => {
  const markdown = readFileSync(TODOS, 'utf-8')
  const sections = parseSections(markdown)

  /**
   * 비-공허 짝.
   *
   * 파싱이 0건이면 아래 단언들이 전부 **공허하게 통과**한다. 이 저장소는
   * "0 이 나오면 판별식을 의심하라" 를 여러 번 겪었으므로 하한을 먼저 못박는다.
   */
  it('섹션을 실제로 수집한다 (비-공허 짝)', () => {
    assert.ok(
      sections.length > 0,
      `TODOS.md 에서 '## ' 섹션을 하나도 수집하지 못했다 — 헤딩 서식이 바뀌었거나 경로가 틀렸다. 경로: ${TODOS}`,
    )
    const withBody = sections.filter((s) => s.body.some((b) => b.text.trim().length > 0))
    assert.ok(
      withBody.length > 0,
      '본문이 있는 섹션이 0건이다 — 파서가 본문을 못 담고 있다.',
    )
  })

  /**
   * ★이것이 이 파일의 존재 이유다.
   *
   * 2026-07-27 에 "TODOS 28항목 전부 ✅, 열림 0건" 이라고 보고했는데 **틀렸다.**
   * 측정에 `grep "^## "` 를 써서 **헤딩만** 셌기 때문이다. 실제로는
   * `## ✅ project-workflow — 계약 파손 (해소 #317)` 섹션 **본문 안에** ⬜ 2건이
   * 살아 있었다.
   *
   * 요약이 헤딩에만 붙고 실물은 본문에 있으면, 요약을 믿는 다음 세션이
   * "검증된 것" 으로 착각한다. 이 저장소가 이미 겪은 실패 양식이다
   * (체크포인트 Remaining Work ⊄ TODOS).
   *
   * **규칙** — 섹션을 ✅ 로 닫으려면 본문의 미해결 항목을 먼저 처리하거나,
   * 자기 섹션으로 **승격**시켜야 한다. 숨겨두는 것은 안 된다.
   */
  it('✅ 로 표시한 섹션 본문에 미해결 마커가 없다', () => {
    const violations = sections
      .filter((s) => s.heading.startsWith('✅'))
      .flatMap((s) =>
        s.body
          .filter((b) => b.text.includes(OPEN_MARKER))
          .map((b) => `  TODOS.md:${b.line}  ${b.text.trim().slice(0, 90)}\n    └ 소속 섹션 (L${s.startLine}): ${s.heading.slice(0, 70)}`),
      )

    assert.deepEqual(
      violations,
      [],
      `✅ 섹션 본문에 미해결(${OPEN_MARKER}) 항목이 숨어 있다 — 헤딩만 세면 "열림 0건" 으로 잘못 보고된다.\n` +
        `해소했으면 마커를 지우고, 아직이면 그 항목을 자기 '## ${OPEN_MARKER} ...' 섹션으로 승격하라.\n\n` +
        violations.join('\n'),
    )
  })

  /**
   * 판별식 자체의 양성 대조군.
   *
   * 위 단언이 항상 초록인 이유가 "위반이 없어서" 인지 "탐지 로직이 죽어서" 인지
   * 구분하려면, 합성 입력에서 실제로 잡히는지 봐야 한다
   * ([[archunit-vacuous-rule-silent-pass]] 와 같은 처방).
   */
  it('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    const synthetic = ['## ✅ 해소된 무언가', '', '본문 설명.', `- ${OPEN_MARKER} 사실은 안 끝난 항목`, ''].join('\n')

    const parsed = parseSections(synthetic)
    assert.equal(parsed.length, 1, '합성 입력에서 섹션 1개를 뽑아야 한다.')

    const caught = parsed
      .filter((s) => s.heading.startsWith('✅'))
      .flatMap((s) => s.body.filter((b) => b.text.includes(OPEN_MARKER)))

    assert.equal(caught.length, 1, '합성 위반을 못 잡았다 — 탐지 로직이 죽어 있다.')
  })

  /**
   * 헤딩 마커 자체의 정합.
   *
   * 마커가 없는 섹션은 해소인지 미해결인지 알 수 없어 집계에서 조용히 빠진다.
   * 「미분류 0건」을 강제한다.
   */
  it('모든 섹션 헤딩이 상태 마커를 갖는다', () => {
    const unmarked = sections
      .filter((s) => !ALL_MARKERS.some((m) => s.heading.startsWith(m)))
      .map((s) => `  TODOS.md:${s.startLine}  ${s.heading.slice(0, 80)}`)

    assert.deepEqual(
      unmarked,
      [],
      `상태 마커(${TODO_STATUSES.map((s: { marker: string; status: string }) => `${s.marker} ${s.status}`).join(' / ')})가 ` +
        '없는 섹션이 있다 — 집계에서 조용히 누락된다.\n\n' +
        unmarked.join('\n'),
    )
  })

  /**
   * ★파서와 판별식이 **같은 마커 집합**을 쓰는지.
   *
   * 이 파일이 허용하는 마커를 파서가 모르면, 그 마커로 만든 섹션은 판별식은 통과하는데
   * 대시보드에서는 앞 섹션에 흡수돼 사라진다. 2026-08-10 이전의 📌 가 정확히 그 상태였다.
   * 지금은 목록이 하나라 원리적으로 갈라질 수 없지만, 누군가 여기에 마커를 **다시 손으로**
   * 적어 넣는 순간 갈라진다. 그 재발을 이 단언이 막는다.
   */
  it('판별식이 허용하는 마커를 파서도 전부 인식한다 (단일 출처 확인)', () => {
    const unknown = ALL_MARKERS.filter((marker) => {
      const parsed = parseTodos(`# TODOS\n\n## ${marker} 제목\n\n본문.\n`)
      return parsed.length !== 1
    })

    assert.deepEqual(
      unknown,
      [],
      `아래 마커를 build-dashboard.mjs 의 parseTodos 가 섹션으로 인식하지 못한다 — ` +
        '그 마커로 만든 섹션은 대시보드에서 앞 섹션에 흡수돼 사라진다.\n\n  ' +
        unknown.join(' '),
    )
    // 비-공허 짝 — 훑을 마커가 0건이면 위 단언이 아무것도 안 지킨다.
    assert.ok(ALL_MARKERS.length >= 3, `상태 마커가 ${ALL_MARKERS.length}종뿐이다 — 상수를 확인하라.`)
  })
})
