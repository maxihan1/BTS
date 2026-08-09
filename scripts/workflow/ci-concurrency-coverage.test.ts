// 트리거를 직접 가진 모든 워크플로우가 concurrency 취소를 선언하는지 강제하는 판별식
//
// 왜 이 테스트가 있나. 러너가 **1대**(`maxi-mac-bts`)라 잡이 전부 한 줄로 선다.
// 여기서 낡은 run 이 큐에 남아 있으면 그 run 이 러너를 점유하는 동안 **현재 커밋의 검증이
// 시작조차 못 한다**. 2026-08-07 실측에서 큐 8건 중 3건이 이미 머지되고 브랜치까지 삭제된
// PR #346 의 검증이었고, 나머지도 낡은 main 커밋이라 **현재 main 을 검증하는 run 이 0건**이었다.
// PR 하나의 벽시계가 1~2시간이 되면서 최근 backend-ci 30건 중 11건이 cancelled 다 —
// 검증이 실질적으로 무의미해지는 구간이다.
//
// `concurrency: {group, cancel-in-progress: true}` 는 같은 ref 의 이전 run 을 자동으로 취소한다.
// backend-ci · frontend-ci · workflow-scripts-ci 는 이미 갖고 있었고 **infra-ci 만 빠져 있었다**
// (2026-08-09 실측 · TODOS 「CI 벽시계가 실제 실행의 10배다」 후속 1).
//
// ## ★ 왜 파일 목록을 상수로 적지 않나
//
// 목록을 적으면 그 목록과 실제 `.github/workflows/` 가 **서로를 안 보는 두 목록**이 된다.
// 새 워크플로우를 추가하면서 목록에 안 넣으면 그 파일은 concurrency 없이 살아남는다.
// 이 저장소의 지배 결함 양식(`two-lists-never-check-each-other`)이라 목록을 **하나로** 둔다 —
// 파일 집합은 런타임 `readdirSync` 로 얻고 상수로는 디렉터리 한 곳만 선언한다.
// 훑기가 0건이면 모든 단언이 공허해지므로 양성 대조군을 첫 단언으로 둔다.
//
// ## 재사용 워크플로우는 대상이 아니다
//
// `on: workflow_call` 만 가진 파일(`runner-health.yml`)은 자기 run 을 만들지 않고 호출자의
// run 안에서 돈다. 거기에 concurrency 를 걸면 **호출자를 취소**하게 되므로 제외한다.
// 이 제외를 「이름으로」 하지 않고 **트리거 선언에서 파생**시키는 것이 중요하다 —
// 이름 목록을 두면 그것이 또 하나의 갈라지는 목록이 된다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const WORKFLOW_DIR = path.join(REPO_ROOT, '.github/workflows')

/** 자기 run 을 만드는 트리거. 이 중 하나라도 있으면 concurrency 대상이다. */
const RUN_CREATING_TRIGGERS = ['pull_request', 'push', 'schedule', 'workflow_dispatch'] as const

interface Workflow {
  name: string
  source: string
}

/** `.github/workflows` 의 yml/yaml 을 전부 읽는다. */
function readWorkflows(): Workflow[] {
  return fs
    .readdirSync(WORKFLOW_DIR)
    .filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))
    .map((name) => ({ name, source: fs.readFileSync(path.join(WORKFLOW_DIR, name), 'utf-8') }))
}

/**
 * `on:` 블록 안에 자기 run 을 만드는 트리거가 선언돼 있는가.
 *
 * `on:` 부터 다음 최상위 키(`jobs:`/`concurrency:`/`env:` 등)까지를 잘라 그 안만 본다.
 * 파일 상단 주석에 'push' 같은 단어가 있어도 오탐하지 않게 하기 위해서다.
 */
function createsOwnRun(source: string): boolean {
  const onIndex = source.search(/^on:\s*$/m)
  if (onIndex === -1) return false
  const rest = source.slice(onIndex)
  const nextTopLevel = rest.slice(3).search(/^[a-zA-Z_]+:/m)
  const onBlock = nextTopLevel === -1 ? rest : rest.slice(0, nextTopLevel + 3)
  return RUN_CREATING_TRIGGERS.some((t) => new RegExp(`^\\s{2}${t}:`, 'm').test(onBlock))
}

/** 최상위 `concurrency:` 블록에 `cancel-in-progress: true` 가 있는가. */
function cancelsSupersededRuns(source: string): boolean {
  const idx = source.search(/^concurrency:\s*$/m)
  if (idx === -1) return false
  const rest = source.slice(idx)
  const nextTopLevel = rest.slice(12).search(/^[a-zA-Z_]+:/m)
  const block = nextTopLevel === -1 ? rest : rest.slice(0, nextTopLevel + 12)
  return /^\s+group:\s*\S/m.test(block) && /^\s+cancel-in-progress:\s*true\s*$/m.test(block)
}

describe('CI — 트리거를 가진 워크플로우는 낡은 run 을 취소한다', () => {
  const workflows = readWorkflows()

  test('워크플로우를 실제로 수집한다 (비-공허 짝)', () => {
    assert.ok(
      workflows.length > 0,
      `${WORKFLOW_DIR} 에서 워크플로우를 하나도 못 읽었다 — 경로가 바뀌었거나 훑기가 죽었다.`,
    )
    // 훑기가 살아 있어도 파싱이 죽으면 아래 단언이 전부 공허해진다.
    // 「트리거를 가진 파일이 최소 하나는 있다」를 함께 못박는다.
    const withTriggers = workflows.filter((w) => createsOwnRun(w.source))
    assert.ok(
      withTriggers.length > 0,
      `트리거를 가진 워크플로우가 0건이다 — on: 블록 파싱이 죽어 있다. 수집: ${workflows.map((w) => w.name).join(', ')}`,
    )
  })

  test('★자기 run 을 만드는 워크플로우는 전부 concurrency 취소를 선언한다', () => {
    const violations = workflows
      .filter((w) => createsOwnRun(w.source))
      .filter((w) => !cancelsSupersededRuns(w.source))
      .map((w) => `  .github/workflows/${w.name}`)

    assert.deepEqual(
      violations,
      [],
      '러너가 1대라 낡은 run 이 큐에 남으면 현재 커밋의 검증이 시작조차 못 한다.\n' +
        '아래 파일에 다음을 추가하라.\n\n' +
        'concurrency:\n' +
        '  group: ${{ github.workflow }}-${{ github.ref }}\n' +
        '  cancel-in-progress: true\n\n' +
        violations.join('\n'),
    )
  })

  test('재사용 전용 워크플로우는 대상에서 빠진다 (오탐 방지 대조군)', () => {
    // `on: workflow_call` 만 가진 파일은 호출자의 run 안에서 돈다.
    // 여기에 concurrency 를 걸면 호출자를 취소하게 되므로 대상이 아니어야 한다.
    const reusableOnly = ['on:', '  workflow_call:', '', 'jobs:', '  check:'].join('\n')
    assert.equal(createsOwnRun(reusableOnly), false, 'workflow_call 전용을 대상으로 잘못 판정했다.')
  })

  test('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    const missing = ['on:', '  push:', '    branches: [main]', '', 'jobs:', '  a:'].join('\n')
    assert.equal(createsOwnRun(missing), true, '합성 입력의 트리거를 못 봤다.')
    assert.equal(cancelsSupersededRuns(missing), false, '없는 concurrency 를 있다고 판정했다.')

    const present = [
      'on:',
      '  push:',
      '    branches: [main]',
      '',
      'concurrency:',
      '  group: ${{ github.workflow }}-${{ github.ref }}',
      '  cancel-in-progress: true',
      '',
      'jobs:',
      '  a:',
    ].join('\n')
    assert.equal(cancelsSupersededRuns(present), true, '있는 concurrency 를 못 봤다.')

    // cancel-in-progress: false 는 「선언은 했으나 취소는 안 한다」 — 통과하면 안 된다.
    const declaredButNotCancelling = present.replace('cancel-in-progress: true', 'cancel-in-progress: false')
    assert.equal(
      cancelsSupersededRuns(declaredButNotCancelling),
      false,
      'cancel-in-progress: false 를 취소 선언으로 잘못 인정했다 — 이러면 봉인이 공허하다.',
    )
  })
})
