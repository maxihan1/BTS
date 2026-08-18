// TODOS.md 대량 이동이 내용을 잃지 않았는지 기계로 확인하는 판별식
//
// ## 이 파일이 지키는 것
//
// ① `compareTodoIntegrity` — 두 버전의 항목 `(상태, 제목, 본문)` **멀티셋** 양방향 차집합.
//    사라진 항목·생긴 항목을 **제목과 함께** 열거한다.
// ② `compareAllLines` — 두 버전의 **모든 줄** 멀티셋 차집합. 항목 안팎을 가리지 않으므로
//    파일 머리 산문이나 절 사이 설명이 사라져도 잡는다.
// ③ **F2b 실파일 축** — merge-base 대비 부채 항목이 사라졌는지를 매 PR 이 검사한다.
//
// ## 이 파일이 지키지 **않는** 것
//
// - 항목이 **어느 절에 있는가**. 그건 `todos-structure-contract.test.ts` 소관이다.
// - 두 줄(「쉬운 말」·「방치하면」) 존재. `todos-plain-language-contract.test.ts` 소관.
// - `✅` 섹션 본문의 순수성. `todos-resolved-section-purity.test.ts` 소관.
// - 마스터 계획과의 매핑. `debt-ledger-mapping.test.ts` 소관.
//
// ## ★파서를 새로 적지 않는다
//
// 항목 파싱은 `build-dashboard.mjs` 의 `parseTodos` 를 **import** 한다. `# `·`## ` 를 읽는
// 코드를 새로 적을 때마다 코드펜스 판정을 잊는 것이 이 저장소의 반복 사고다(#389 5회).
// 줄 멀티셋 축(②)은 파싱이 아니라 `split('\n')` 이라 스캐너가 아니다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import {
  compareTodoIntegrity,
  compareAllLines,
  readBaseTodos,
} from './todos-reorder-integrity.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const LEDGER = path.join(REPO_ROOT, 'TODOS.md')

/** 합성 장부. 항목 3건 + 머리 산문. */
const BASE = [
  '# TODOS',
  '',
  '이 줄은 어느 항목에도 속하지 않는 머리 산문이다.',
  '',
  '## ⬜ apps/web — 첫 번째',
  '',
  '**무엇.** 본문 A 첫 줄.',
  '**또.** 본문 A 둘째 줄.',
  '',
  '## ⬜ 도구 — 두 번째',
  '',
  '**무엇.** 본문 B.',
  '',
  '## ✅ 인프라 — 세 번째 (해소)',
  '',
  '**무엇.** 본문 C.',
  '',
].join('\n')

describe('TODOS.md — 대량 이동 무손실 대조', () => {
  test('합성 장부에서 항목을 실제로 수집한다 (비-공허 짝)', () => {
    const r = compareTodoIntegrity(BASE, BASE)
    assert.equal(r.beforeCount, 3, '합성 장부에서 항목 3건을 못 뽑았다 — 파서가 깨졌거나 서식이 바뀌었다')
    assert.deepEqual(r.lost, [])
    assert.deepEqual(r.gained, [])
  })

  test('순수 이동은 차집합 0 이다 (음성 대조군)', () => {
    // 항목 순서만 뒤집는다. 줄 내용은 한 글자도 안 바꾼다.
    const moved = [
      '# TODOS',
      '',
      '이 줄은 어느 항목에도 속하지 않는 머리 산문이다.',
      '',
      '## ✅ 인프라 — 세 번째 (해소)',
      '',
      '**무엇.** 본문 C.',
      '',
      '## ⬜ 도구 — 두 번째',
      '',
      '**무엇.** 본문 B.',
      '',
      '## ⬜ apps/web — 첫 번째',
      '',
      '**무엇.** 본문 A 첫 줄.',
      '**또.** 본문 A 둘째 줄.',
      '',
    ].join('\n')
    const r = compareTodoIntegrity(BASE, moved)
    assert.deepEqual(r.lost, [], '순수 이동인데 항목이 사라졌다고 보고했다')
    assert.deepEqual(r.gained, [], '순수 이동인데 항목이 생겼다고 보고했다')
    const lines = compareAllLines(BASE, moved)
    assert.deepEqual(lines.lost, [], '순수 이동인데 줄이 사라졌다고 보고했다')
    assert.deepEqual(lines.gained, [], '순수 이동인데 줄이 생겼다고 보고했다')
  })

  test('양성 ① — 항목 1건을 통째로 지우면 잡는다', () => {
    const after = BASE.replace('## ⬜ 도구 — 두 번째\n\n**무엇.** 본문 B.\n\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '항목이 통째로 사라졌는데 못 잡았다')
    assert.match(r.lost[0], /두 번째/, '사라진 항목을 제목으로 지목하지 못한다')
    assert.deepEqual(r.gained, [])
  })

  test('양성 ② — 본문 1줄만 지워도 잡는다', () => {
    const after = BASE.replace('**또.** 본문 A 둘째 줄.\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.equal(r.lost.length, 1, '본문 1줄 소실을 못 잡았다 — fingerprint 에서 본문이 빠졌다')
    assert.equal(r.gained.length, 1, '본문이 바뀐 항목은 사라진 것 + 생긴 것 양쪽에 나와야 한다')
  })

  test('양성 ③ — 제목 1글자만 바꾸면 개명으로 잡는다 (소실 아님)', () => {
    // 제목만 바뀌고 본문이 같으면 그것은 소실이 아니라 개명이다. 소실로 세면
    // 정당한 제목 수정이 전부 red 가 되어 판별식이 마찰 장치로 전락한다.
    const after = BASE.replace('첫 번째', '첫 번쨰')
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.lost, [], '제목만 바뀐 것을 소실로 셌다')
    assert.deepEqual(r.gained, [], '제목만 바뀐 것을 신규로 셌다')
    assert.equal(r.renamed.length, 1, '제목 변경을 개명으로 잡지 못했다')
    assert.match(r.renamed[0], /첫 번째.*→.*첫 번쨰/, '개명 보고가 이전 → 이후를 보여주지 않는다')
  })

  test('양성 ⑥ — 본문이 다르면 개명으로 접지 않는다 (관대함 차단)', () => {
    // 개명 인식이 본문까지 안 보면 「제목이 바뀌고 본문도 통째로 갈린」 항목이
    // 조용히 개명으로 접힌다. 그러면 소실 검사가 무력해진다.
    const after = BASE.replace('첫 번째', '첫 번쨰').replace('**또.** 본문 A 둘째 줄.\n', '')
    const r = compareTodoIntegrity(BASE, after)
    assert.deepEqual(r.renamed, [], '본문이 다른데 개명으로 접었다 — 소실이 숨는다')
    assert.equal(r.lost.length, 1, '본문까지 바뀐 항목을 소실로 잡지 못했다')
    assert.equal(r.gained.length, 1)
  })

  test('양성 ④ — 같은 항목 2건 중 1건만 지워도 잡는다 (멀티셋 축)', () => {
    // 집합으로 세면 이 소실이 조용히 통과한다. 멀티셋이라야 잡힌다.
    const dup = BASE + ['## ⬜ 도구 — 두 번째', '', '**무엇.** 본문 B.', ''].join('\n')
    const r = compareTodoIntegrity(dup, BASE)
    assert.equal(r.lost.length, 1, '중복 항목 1건 제거를 못 잡았다 — 집합으로 세고 있다')
  })

  test('양성 ⑤ — 항목 밖 머리 산문이 사라지면 줄 축이 잡는다', () => {
    // `parseTodos` 는 항목 밖 줄을 버리므로 ① 축은 이것을 못 본다.
    // 그래서 줄 멀티셋 축(②)이 따로 있다 — 사람 판정에 맡기지 않는다.
    const after = BASE.replace('이 줄은 어느 항목에도 속하지 않는 머리 산문이다.\n', '')
    const items = compareTodoIntegrity(BASE, after)
    assert.deepEqual(items.lost, [], '전제 확인 — 항목 축은 머리 산문 소실을 보지 못한다')
    const lines = compareAllLines(BASE, after)
    assert.equal(lines.lost.length, 1, '머리 산문 소실을 줄 축도 못 잡았다')
    assert.match(lines.lost[0], /머리 산문/)
  })

  test('줄 축이 H1 추가와 실제 소실을 구분한다', () => {
    // 재배열은 H1 을 새로 만든다. 그 차이는 정상이고, 그 밖의 줄 차이는 소실이다.
    const after = BASE.replace('## ⬜ apps/web — 첫 번째', '# 화면에서 보이는 것\n\n## ⬜ apps/web — 첫 번째')
    const lines = compareAllLines(BASE, after)
    assert.deepEqual(lines.lost, [], 'H1 만 추가했는데 소실을 보고했다')
    assert.deepEqual(
      lines.gained.filter((l: string) => !l.startsWith('# ') && l.trim() !== ''),
      [],
      'H1 과 빈 줄 말고 다른 줄이 생겼다 — 이동이 아니라 편집이다',
    )
  })
})

describe('TODOS.md — merge-base 대비 항목 소실 (F2b)', () => {
  const base = readBaseTodos(REPO_ROOT)

  test('base 획득 결과를 반드시 보고한다 (조용한 통과 차단)', () => {
    assert.ok(
      typeof base.reason === 'string' && base.reason.length > 0,
      'base 획득 결과에 이유 문자열이 없다 — 못 얻었는지 얻었는지 구분할 수 없다',
    )
    // 못 얻은 경우에도 **왜** 못 얻었는지가 출력에 남아야 한다.
    if (base.content === null) {
      console.log(`[F2b] base 미획득 — ${base.reason}. 이 실행에서 소실 검사는 돌지 않았다.`)
    } else {
      console.log(`[F2b] base 획득 — ${base.reason}`)
    }
  })

  test('merge-base 에 있던 부채 항목이 이 브랜치에서 사라지지 않았다', (t) => {
    if (base.content === null) {
      t.skip(`base 미획득 (${base.reason}) — 이 축은 이 실행에서 돌지 않는다`)
      return
    }
    const current = fs.readFileSync(LEDGER, 'utf-8')
    const r = compareTodoIntegrity(base.content, current)
    if (r.renamed.length > 0) {
      // 개명은 정당한 편집이라 통과시키되, **무엇이 바뀌었는지는 반드시 보인다.**
      // 조용히 접으면 제목 변경이 기록 없이 지나가고, 제목은 장부의 조인 키다.
      for (const s of r.renamed) console.log(`[F2b] 개명 — ${s}`)
    }
    assert.deepEqual(
      r.lost,
      [],
      `merge-base 에 있던 부채 항목이 사라졌다 — 이 저장소는 항목을 지우지 않고 ✅ 로 바꾼다.\n` +
        `제목만 바뀐 것은 개명으로 접히므로 여기 나오지 않는다. 여기 나온 것은 본문까지 사라진 것이다.\n\n` +
        r.lost.map((s: string) => `  - ${s}`).join('\n'),
    )
  })

  test('자기 자신과의 비교는 공허하므로 그 사실을 보고한다', () => {
    if (base.content === null) return
    const current = fs.readFileSync(LEDGER, 'utf-8')
    const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: REPO_ROOT, encoding: 'utf-8' }).trim()
    if (base.sha === head) {
      console.log('[F2b] merge-base == HEAD — 비교가 공허하다. 이 축은 PR 브랜치에서만 유효하다.')
    }
    // 공허하든 아니든 파서는 실제로 항목을 봐야 한다 (비-공허 짝).
    const r = compareTodoIntegrity(base.content, current)
    assert.ok(
      r.beforeCount > 10,
      `merge-base 장부에서 항목을 ${r.beforeCount}건밖에 못 찾았다 — 파서가 깨졌거나 base 가 엉뚱한 파일이다.`,
    )
  })
})
