// 낡은 실행이 현재 커밋의 검증을 막지 않는지 강제하는 판별식
//
// ## 왜 이 테스트가 있나
//
// 실행기가 **1개**라 빌드가 전부 한 줄로 선다. 여기서 낡은 실행이 큐에 남아 있으면 그것이
// 실행기를 점유하는 동안 **현재 커밋의 검증이 시작조차 못 한다**. 2026-08-07 실측에서
// 큐 8건 중 3건이 이미 머지되고 브랜치까지 삭제된 PR #346 의 검증이었고, 나머지도 낡은
// main 커밋이라 **현재 main 을 검증하는 run 이 0건**이었다. PR 하나의 벽시계가 1~2시간이
// 되면서 최근 30건 중 11건이 cancelled 였다 — 검증이 실질적으로 무의미해지는 구간이다.
//
// ## ★2026-09-09 — GitHub Actions 에서 젠킨스로 옮겼다 (P4)
//
// 종전에는 `.github/workflows/` 를 전부 훑어 `concurrency.cancel-in-progress: true` 를
// 요구했다. CI 정본이 젠킨스로 옮겨지고 워크플로우 4종을 철거했으므로 그 기계는 통째로
// 지웠다 — 훑을 파일이 없는 파서와 그것을 검사하던 합성 테스트는 죽은 무게다.
//
// **글자 그대로 옮기면 안 되는 자리였다.**
//
//   Actions  `cancel-in-progress: true`                     새 run 이 오면 **이전 것을 취소**
//   젠킨스   `disableConcurrentBuilds()`                     **큐에서 기다림** (동시 실행만 차단)
//   짝       `disableConcurrentBuilds(abortPrevious: true)`  이전 빌드를 **중단**
//
// 실행기가 1개인 이 머신에서 「큐에서 기다림」은 정확히 막으려던 상태다. P2 에서 실제로
// `abortPrevious` 없이 써 놓았고 이 판별식을 포팅하며 잡혔다 — **절반만 고친 상태가
// 초록이던 것**이라, 판정기가 인자 없는 형태를 실제로 거르는지도 합성으로 함께 단언한다.
//
// 실행. node --experimental-strip-types --test scripts/workflow/ci-concurrency-coverage.test.ts

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const PIPELINE = 'Jenkinsfile'

/** 낡은 빌드를 **중단**하는 선언. 대기가 아니라 중단이어야 한다. */
const ABORT_PREVIOUS = /disableConcurrentBuilds\s*\(\s*abortPrevious\s*:\s*true\s*\)/

describe('CI — 낡은 실행이 현재 커밋의 검증을 막지 않는다', () => {
  const jf = fs.readFileSync(path.join(REPO_ROOT, PIPELINE), 'utf-8')

  test('options 블록을 실제로 뽑았다 (비-공허 짝)', () => {
    // 못 뽑으면 아래 단언이 검사할 것 없이 통과한다.
    const options = jf.match(/options\s*\{[\s\S]*?\n\s*\}/)?.[0] ?? ''
    assert.ok(
      options.length > 0,
      `${PIPELINE} 에서 options 블록을 못 뽑았다 — 추출기가 깨졌거나 선언 형태가 바뀌었다.`,
    )
    assert.match(options, /timestamps\(\)/, 'options 블록을 잘못 잘랐다 — 알려진 항목이 안 들어 있다')
  })

  test('★젠킨스 파이프라인이 낡은 빌드를 중단한다 (abortPrevious)', () => {
    const options = jf.match(/options\s*\{[\s\S]*?\n\s*\}/)?.[0] ?? ''
    assert.match(
      options,
      ABORT_PREVIOUS,
      '낡은 빌드를 중단하지 않는다.\n' +
        '  실행기가 1개라 낡은 빌드가 도는 동안 현재 커밋의 검증이 시작조차 못 한다.\n' +
        '  2026-08-07 실측 — 큐 8건 중 3건이 이미 머지되고 브랜치까지 삭제된 PR 의 검증이었고,\n' +
        '  그때 현재 main 을 검증하는 run 은 0건이었다.\n' +
        '  처방. options { disableConcurrentBuilds(abortPrevious: true) }',
    )
  })

  test('★판정기가 abortPrevious 없는 형태를 실제로 거른다 (합성 뮤테이션)', () => {
    // 정규식이 문자열을 스치기만 해도 통과하는 형태가 아닌지 본다.
    const bare = 'options {\n  disableConcurrentBuilds()\n  timestamps()\n}'
    assert.doesNotMatch(
      bare,
      ABORT_PREVIOUS,
      '인자 없는 형태를 통과시킨다 — 「동시 실행은 막았는데 낡은 것이 먼저 돈다」가 초록이 된다',
    )

    const wrong = 'options {\n  disableConcurrentBuilds(abortPrevious: false)\n}'
    assert.doesNotMatch(wrong, ABORT_PREVIOUS, 'false 를 통과시킨다')
  })

  test('★동시 실행 자체도 막혀 있다 (2코어 · 실행기 1개)', () => {
    // `abortPrevious` 는 `disableConcurrentBuilds` 의 인자이므로 위 단언이 이미 함께 보지만,
    // 인자만 보고 본체를 놓치는 회귀를 막으려고 이름을 따로 확인한다.
    assert.match(
      jf,
      /disableConcurrentBuilds/,
      '동시 빌드 차단 선언이 없다 — Testcontainers 컨테이너까지 겹쳐 스왑으로 밀린다',
    )
  })
})
