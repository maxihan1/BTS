// 기술부채 장부(TODOS.md) ↔ 마스터 계획 매핑표의 양방향 차집합 판별식
//
// ## 왜 이 파일이 있나
//
// `docs/plans/2026-08-12-debt24-master.md` 는 **「24건이 어느 PR 에 속하는가」의 정본**이다.
// 그 문서 스스로가 존재 이유를 이렇게 적었다.
//
// > PR 묶음표가 **건수만 적고 어느 항목인지는 안 적으면** 두 목록이 서로를 검사하지 못한다.
// > 이 저장소의 지배적 결함 양식(`two-lists-never-check-each-other`)이 바로 그것이고,
// > 처방은 **차집합 판별식**이다.
//
// 그런데 그 처방이 **문서 안의 bash 스니펫으로만** 존재했다. 아무도 돌리지 않았고, 그래서
// 다음이 조용히 성립했다 — **작성 시점부터 키가 어긋나 있었다.**
//
// | 항목 | 마스터 계획이 적은 줄 | #365 머지 직후 실제 | 현재 실제 |
// |---|---|---|---|
// | OpenAPI required | `2023` | **2033** | **2098** |
// | setupServer 59개 | `2285` | **2295** | **2360** |
// | placeholder/aria-label | `3243` | **3429** | **3531** |
//
// 22건 중 **18건**이 어긋났다. 「자기 결함을 재생산한 봉인」 양식이다.
//
// ## ★왜 줄번호를 키로 쓰지 않는가
//
// 줄번호는 **휘발성**이다. 장부를 한 줄만 고쳐도 그 아래가 전부 밀린다 — PR #366 하나가
// 110줄을 추가했다. 앞으로도 부채를 닫을 때마다 밀린다. 키가 매 PR 마다 깨지는 구조라면
// 그 키는 키가 아니다.
//
// 그래서 **제목**을 키로 쓴다. 제목도 바뀔 수 있지만, 바뀌면 이 판별식이 **즉시 red** 다.
// 핵심은 완벽한 키가 아니라 **조용히 깨지지 않는 것**이다.
//
// ## 정규화 규칙
//
// `## ⬜ <제목> (<상태>)` 에서 **문자열 끝의 괄호 그룹 하나만** 떼어 낸다.
// 상태 표기(`미착수` · `착수` · `선재` · `신규`)는 진행에 따라 바뀌지만 제목 본문은 안 바뀐다.
//
// ★「괄호 앞까지 자르기」로 하면 안 된다 — `apps/web(테스트 인프라) — …` 는 괄호가 **맨 앞**에
// 있어 제목이 통째로 날아가고, `로딩 프레임 계약 — 임포트 페이지는 고정됨(2026-08-10), …` 은
// 괄호가 **중간**에 있다. 끝에 붙은 것만 떼는 이유가 그것이다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const LEDGER = path.join(REPO_ROOT, 'TODOS.md')
const MASTER = path.join(REPO_ROOT, 'docs/plans/2026-08-12-debt24-master.md')

/** 장부에서 미해소를 뜻하는 마커. */
const OPEN_MARKER = '⬜'

/**
 * 제목 끝의 상태 괄호 하나를 떼어 키로 만든다.
 *
 * @param heading `## ⬜ ` 를 제거한 제목 줄
 * @returns 비교에 쓸 키
 */
function normalizeKey(heading: string): string {
  return heading.replace(/\s*\([^()]*\)\s*$/, '').trim()
}

/** TODOS.md 의 미해소 항목 제목 키 목록. */
function ledgerOpenKeys(): string[] {
  const lines = fs.readFileSync(LEDGER, 'utf-8').split('\n')
  return lines
    .filter((l) => l.startsWith(`## ${OPEN_MARKER} `))
    .map((l) => normalizeKey(l.slice(`## ${OPEN_MARKER} `.length)))
}

/**
 * 마스터 계획 「전수 매핑」 표에서 미해소 행의 항목 키를 뽑는다.
 *
 * 표 형식. `| # | 상태 | 항목 | PR | 영역 |`
 * 상태 열이 `⬜` 인 행만 장부의 `## ⬜` 와 대응한다.
 */
function masterOpenKeys(): string[] {
  const lines = fs.readFileSync(MASTER, 'utf-8').split('\n')
  const keys: string[] = []
  for (const line of lines) {
    if (!line.startsWith('|')) continue
    const cells = line.split('|').map((c) => c.trim())
    // ['', '#', '상태', '항목', 'PR', '영역', '']
    if (cells.length < 6) continue
    if (cells[2] !== OPEN_MARKER) continue
    const item = cells[3]
    if (item === undefined || item.length === 0) continue
    keys.push(normalizeKey(item))
  }
  return keys
}

describe('기술부채 장부 ↔ 마스터 계획 매핑', () => {
  test('두 파일이 실재한다 (비-공허 짝)', () => {
    // 경로가 어긋나면 아래 차집합이 전부 「빈 집합 == 빈 집합」으로 공허 통과한다.
    assert.ok(fs.existsSync(LEDGER), `${LEDGER} 가 없다.`)
    assert.ok(fs.existsSync(MASTER), `${MASTER} 가 없다.`)
  })

  test('★파서가 실제로 항목을 찾는다 (비-공허 짝)', () => {
    // 정규식이나 표 형식이 깨지면 0건을 훑고도 차집합 0 이라 통과한다 — 이 저장소가
    // 여러 번 겪은 「훑기 0건이면 단언이 공허」 양식.
    assert.ok(
      ledgerOpenKeys().length >= 10,
      `장부에서 미해소 항목을 ${ledgerOpenKeys().length}건밖에 못 찾았다 — 파서가 깨졌다.`,
    )
    assert.ok(
      masterOpenKeys().length >= 10,
      `마스터 계획에서 미해소 행을 ${masterOpenKeys().length}건밖에 못 찾았다 — 표 형식이 바뀌었다.`,
    )
  })

  test('★★장부에만 있고 마스터 계획에 없는 항목이 0 이다', () => {
    // 이 방향이 새면 **어느 PR 도 담당하지 않는 부채**가 생긴다 — 조용히 영원히 안 닫힌다.
    const master = new Set(masterOpenKeys())
    const orphans = ledgerOpenKeys().filter((k) => !master.has(k))
    assert.deepEqual(
      orphans,
      [],
      `장부의 아래 항목을 마스터 계획이 담당하지 않는다 — 어느 PR 도 이것을 닫지 않는다.\n` +
        orphans.map((o) => `  - ${o}`).join('\n'),
    )
  })

  test('★★마스터 계획에만 있고 장부에 없는 항목이 0 이다', () => {
    // 이 방향이 새면 **이미 닫힌 것을 다시 열거나** 제목이 바뀐 것을 못 따라간 상태다.
    const ledger = new Set(ledgerOpenKeys())
    const stale = masterOpenKeys().filter((k) => !ledger.has(k))
    assert.deepEqual(
      stale,
      [],
      `마스터 계획의 아래 항목이 장부에 미해소로 없다 — 이미 닫혔거나 제목이 바뀌었다.\n` +
        stale.map((s) => `  - ${s}`).join('\n'),
    )
  })

  test('마스터 계획의 항목 키에 중복이 없다', () => {
    // 같은 항목을 두 PR 이 담당하면 반쪽 봉합이 난다.
    const keys = masterOpenKeys()
    const dups = keys.filter((k, i) => keys.indexOf(k) !== i)
    assert.deepEqual(dups, [], `마스터 계획에 중복된 항목이 있다.\n${dups.join('\n')}`)
  })
})
