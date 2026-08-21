// 구 표기 「전이」가 살아남아야 하는 자리를 동결해 양방향으로 지키는 판별식
//
// ## 왜 있나
//
// 2026-08-18. Transition 의 한국어 정본을 「전이」 → 「전환」 으로 바꾸면서 저장소 3,253곳을
// 치환했다. 그런데 **전부 바꾸면 안 됐다** — 다음 넷은 Transition 이 아니라서 그대로 둬야 한다.
//
// ① 다른 낱말의 부분문자열 — 「버전이 갈린다」·「이전이 아니라」·「탈락 기전이 없다」
// ② 수학적 이행성(transitive) — 「전이적으로 더한다」·「전이 폐포」·「전이 의존」·「전이 탐색」
// ③ 「~하기 전(前)이다」 — 「병목은 시작 전이다」·「측정 전이면 null」
// ④ 파급(carry-over) — 「선례가 전이되지 않는다」·「지연으로 전이(noisy neighbor)되는 것」
//    그리고 Flyway 로 동결된 마이그레이션(체크섬 검증 때문에 고칠 수 없다).
//
// **그 예외 목록과 저장소 본문이 서로를 검사하지 않으면** 다음 사람이 잔존 「전이」를 보고
// 전수 치환을 한 번 더 돌린다 — 이번에 어렵게 살려 둔 자리가 전부 날아간다.
// 이 저장소가 이름 붙인 `two-lists-never-check-each-other` 양식 그대로다.
//
// ## 무엇을 강제하나 — 양방향 차집합
//
// - **늘면 red.** 동결에 없는 자리에 「전이」가 생겼다 = 구 표기로 회귀했다.
// - **줄면 red.** 동결에 있던 자리가 사라졌다 = 예외를 쓸어버렸다(또는 그 문서를 지웠다).
//
// 정당한 변경이면 동결 파일을 **의도적으로** 갱신한다. 조용히 통과시키지 않는 것이 목적이다.
//
// ## ★기준은 `git ls-files` 다
//
// 파일시스템 워크로 세면 `build/**` 와 `src/generated/jooq/**` 를 함께 세어 **Gradle 이 도는
// 동안 수치가 움직인다**. jOOQ 는 마이그레이션의 `COMMENT ON` 문구를 KDoc 으로 옮기므로
// 생성 코드에 「전이」가 계속 되살아난다 — 그것을 세면 이 판별식은 영원히 빨간불이다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const BASELINE = path.join(REPO_ROOT, 'scripts/workflow/transition-term-baseline.json')
const TERM = '전이'

/** 동결 파일이 비면 모든 단언이 공허해진다. 그 상태를 실패로 만드는 하한. */
const MIN_FROZEN = 250

interface Baseline {
  총건수: number
  파일별: Record<string, number>
}

function readBaseline(): Baseline {
  return JSON.parse(fs.readFileSync(BASELINE, 'utf-8'))
}

/** `git ls-files` 기준 현재 잔존 분포. 빌드 산출물·생성 코드는 애초에 목록에 없다. */
function currentCounts(): Record<string, number> {
  const listed = execFileSync('git', ['ls-files'], {
    cwd: REPO_ROOT,
    encoding: 'utf-8',
    maxBuffer: 32 * 1024 * 1024,
    env: gitFixtureEnv(),
  })
    .split('\n')
    .filter(Boolean)
  const out: Record<string, number> = {}
  for (const rel of listed) {
    let text: string
    try {
      text = fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
    } catch {
      continue // 심볼릭·바이너리·삭제 중인 파일
    }
    const n = text.split(TERM).length - 1
    if (n > 0) out[rel] = n
  }
  return out
}

/** 양방향 차집합. 호출자가 넘긴 두 분포만 본다 — 대상 파일에서 기대값을 파생시키지 않는다. */
export function diffCounts(frozen: Record<string, number>, current: Record<string, number>) {
  const added: string[] = []
  const removed: string[] = []
  for (const [f, n] of Object.entries(current)) {
    const was = frozen[f] ?? 0
    if (n > was) added.push(`${f}  ${was} → ${n}`)
  }
  for (const [f, n] of Object.entries(frozen)) {
    const now = current[f] ?? 0
    if (now < n) removed.push(`${f}  ${n} → ${now}`)
  }
  return { added, removed }
}

describe('구 표기 「전이」 잔존 동결 (양방향)', () => {
  test('동결 목록이 비어 있지 않다 (판별식 비-공허 확인)', () => {
    const b = readBaseline()
    assert.ok(
      b.총건수 >= MIN_FROZEN,
      `동결이 ${b.총건수}건뿐이다 (하한 ${MIN_FROZEN}). 목록이 비면 아래 차집합 단언이 전부 공허하게 통과한다.`,
    )
    assert.ok(Object.keys(b.파일별).length > 0, '파일별 분포가 비었다')
  })

  test('구 표기가 새로 생기지 않았다 (회귀 차단)', () => {
    const { added } = diffCounts(readBaseline().파일별, currentCounts())
    assert.deepEqual(
      added,
      [],
      '동결에 없던 자리에 「전이」가 생겼다 — 구 표기로 회귀했거나 새 문서가 옛 표기를 썼다.\n' +
        '정본은 「전환」이다. 정당한 예외(이행성·前·파급·마이그레이션)라면 동결 파일을 갱신하라.\n\n' +
        added.map((s) => `  + ${s}`).join('\n'),
    )
  })

  test('살려 둔 예외가 쓸려나가지 않았다 (전수 치환 재실행 차단)', () => {
    const { removed } = diffCounts(readBaseline().파일별, currentCounts())
    assert.deepEqual(
      removed,
      [],
      '동결돼 있던 「전이」가 사라졌다 — 전수 치환을 한 번 더 돌렸거나 그 문서를 지웠다.\n' +
        '이 자리들은 Transition 이 아니라서 일부러 남긴 것이다(이행성·前·파급·Flyway 동결).\n\n' +
        removed.map((s) => `  - ${s}`).join('\n'),
    )
  })

  test('차집합 함수가 양방향을 실제로 잡는다 (합성 뮤테이션)', () => {
    // 판정을 실파일로만 확인하면 「지금 우연히 같다」와 「함수가 늘 빈 배열을 준다」가
    // 구분되지 않는다. 합성 입력으로 두 방향을 각각 밟아 본다.
    const frozen = { 'a.md': 2, 'b.kt': 1 }
    assert.deepEqual(diffCounts(frozen, { 'a.md': 2, 'b.kt': 1 }), { added: [], removed: [] })
    assert.equal(diffCounts(frozen, { 'a.md': 3, 'b.kt': 1 }).added.length, 1, '증가를 못 잡았다')
    assert.equal(diffCounts(frozen, { 'a.md': 2 }).removed.length, 1, '소실을 못 잡았다')
    assert.equal(diffCounts(frozen, { 'a.md': 2, 'b.kt': 1, 'c.ts': 1 }).added.length, 1, '새 파일을 못 잡았다')
  })
})
