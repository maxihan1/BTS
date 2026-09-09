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

/**
 * **자기 run 을 만들지 않는** 유일한 트리거.
 *
 * ★허용목록이 아니라 **차단목록**이다. 게이트2 리뷰가 실측으로 적발한 결함 —
 * 「자기 run 을 만드는 트리거」를 4종(`pull_request`·`push`·`schedule`·`workflow_dispatch`)
 * 으로 열거했더니 `release` · `workflow_run` · `pull_request_target` · `merge_group` 로 도는
 * 새 워크플로우가 concurrency 없이 **판별식 초록인 채** 통과했다(샌드박스 실측 3종 확인).
 * 이 저장소가 이름 붙인 `two-lists-never-check-each-other` 양식이다.
 *
 * 목록을 `workflow_call` 하나로 줄이면 GitHub 이 새 이벤트를 추가해도 갈라지지 않는다.
 */
const NON_RUN_CREATING_TRIGGERS = new Set(['workflow_call'])

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
 * 워크플로우의 `on:` 선언에서 트리거 이름을 전부 뽑는다.
 *
 * ★서식에 견고해야 한다. 게이트2 리뷰가 실측으로 적발한 결함 — `on:` 을 `/^on:\s*$/m`
 * 으로만 찾았더니 GitHub 이 똑같이 허용하는 아래 표기가 전부 「트리거 없음」으로 분류돼
 * **조용히 검사에서 빠졌다.** `infra-ci.yml` 의 `on:` 줄에 행끝 주석 하나만 달면서
 * concurrency 를 통째로 지우는 변경이 4/4 초록으로 통과했다.
 *
 * 지원해야 하는 표기.
 * - `on:` + 들여쓴 매핑 (임의 들여쓰기)
 * - `on: [push, pull_request]` (flow sequence)
 * - `on: push` (scalar)
 * - `"on":` · `'on':` (따옴표 키 — YAML 1.1 이 `on` 을 boolean 으로 읽는 것을 피하려는 관례)
 * - 행끝 주석 (`on:  # 트리거`)
 *
 * @returns 소문자 트리거 이름 집합. `on:` 자체가 없으면 빈 집합.
 */
function parseTriggers(source: string): Set<string> {
  const lines = source.split('\n')
  const onLineIndex = lines.findIndex((l) => /^\s*(on|"on"|'on')\s*:/.test(l))
  if (onLineIndex === -1) return new Set()

  const onLine = lines[onLineIndex] ?? ''
  const onIndent = indentOf(onLine)

  // ① 인라인 형태 — `on: push` · `on: [push, pull_request]`. 행끝 주석은 떼고 본다.
  const inline = stripComment(onLine.slice(onLine.indexOf(':') + 1)).trim()
  if (inline.length > 0) {
    return new Set(
      inline
        .replace(/[[\]]/g, ' ')
        .split(/[\s,]+/)
        .filter((t) => /^[a-z_]+$/.test(t)),
    )
  }

  // ② 블록 매핑/시퀀스 — `on:` 보다 깊은 줄들이 자식이다. 들여쓰기 폭은 가정하지 않고
  //    **첫 자식 줄의 깊이**를 기준으로 삼아 그 깊이의 키만 트리거로 본다.
  //    (그보다 깊은 `types:`/`branches:` 는 트리거가 아니다.)
  const triggers = new Set<string>()
  let childIndent: number | null = null
  for (const line of lines.slice(onLineIndex + 1)) {
    if (line.trim() === '' || line.trim().startsWith('#')) continue
    const indent = indentOf(line)
    if (indent <= onIndent) break // `on:` 블록 끝 — 다음 최상위 키
    if (childIndent === null) childIndent = indent
    if (indent !== childIndent) continue

    // `  push:` (매핑) 또는 `  - push` (시퀀스) 둘 다 받는다.
    const body = line.trim().replace(/^-\s*/, '')
    const key = body.match(/^([a-z_]+)\s*:?\s*$/)?.[1] ?? body.match(/^([a-z_]+)\s*:/)?.[1]
    if (key !== undefined) triggers.add(key)
  }
  return triggers
}

/** 줄 앞 공백 수. */
function indentOf(line: string): number {
  return (line.match(/^\s*/)?.[0] ?? '').length
}

/** 행끝 `#` 주석을 떼어낸다. */
function stripComment(text: string): string {
  const at = text.indexOf('#')
  return at === -1 ? text : text.slice(0, at)
}

/**
 * 이 워크플로우가 **자기 run 을 만드는가**.
 *
 * ★미분류를 통과로 두지 않는다(fail-closed). `on:` 을 못 읽었거나 트리거가 하나도
 * 안 잡히면 「대상 아님」이 아니라 **판정 실패**로 보고한다 — 아래 「모든 워크플로우가
 * 둘 중 하나로 분류된다」 단언이 그것을 red 로 만든다.
 */
function classify(source: string): 'creates-own-run' | 'reusable-only' | 'unclassified' {
  const triggers = parseTriggers(source)
  if (triggers.size === 0) return 'unclassified'
  const runCreating = [...triggers].filter((t) => !NON_RUN_CREATING_TRIGGERS.has(t))
  return runCreating.length > 0 ? 'creates-own-run' : 'reusable-only'
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

  /**
   * 자기 run 을 만드는 워크플로우의 **실측 하한**.
   *
   * ★`> 0` 으로 두면 파싱이 「절반만」 죽어도(4 → 1) 첫 단언이 통과하고 나머지 3개에 대한
   * 검사가 공허해진다. 게이트2 리뷰가 실측으로 지적한 결함이다 — 실제로 인식이 4→3 으로
   * 줄었는데 두 단언 모두 초록이었다. 현재 값은 backend-ci · frontend-ci · infra-ci ·
   * workflow-scripts-ci 4건. 워크플로우를 늘리면 이 값도 함께 올린다.
   */
  const MIN_RUN_CREATING_WORKFLOWS = 4

  test('워크플로우를 실제로 수집하고 트리거를 파싱한다 (비-공허 짝)', () => {
    assert.ok(
      workflows.length > 0,
      `${WORKFLOW_DIR} 에서 워크플로우를 하나도 못 읽었다 — 경로가 바뀌었거나 훑기가 죽었다.`,
    )
    const runCreating = workflows.filter((w) => classify(w.source) === 'creates-own-run')
    assert.ok(
      runCreating.length >= MIN_RUN_CREATING_WORKFLOWS,
      `자기 run 을 만드는 워크플로우가 ${runCreating.length}건뿐이다 (하한 ${MIN_RUN_CREATING_WORKFLOWS}). ` +
        `on: 파싱이 일부 서식을 놓치고 있다. 인식: ${runCreating.map((w) => w.name).join(', ')}`,
    )
  })

  test('★모든 워크플로우가 둘 중 하나로 분류된다 (미분류 = 실패)', () => {
    const unclassified = workflows
      .filter((w) => classify(w.source) === 'unclassified')
      .map((w) => `  .github/workflows/${w.name}`)

    assert.deepEqual(
      unclassified,
      [],
      '아래 파일의 `on:` 선언에서 트리거를 하나도 못 뽑았다.\n' +
        '「대상 아님」으로 조용히 통과시키지 않는다 — 그게 이 판별식이 뚫렸던 방식이다.\n' +
        '파서를 고치거나 파일의 on: 표기를 바로잡아라.\n\n' +
        unclassified.join('\n'),
    )
  })

  // ── 젠킨스 포팅 (2026-09-09 · P4) ────────────────────────────────────────
  //
  // ★이 판별식이 지키려던 것은 **「낡은 실행이 현재 커밋의 검증을 막지 않는다」**이지
  //   `concurrency:` 라는 문자열이 아니다. CI 정본이 젠킨스로 옮겨졌으므로 그 보장도 옮긴다.
  //
  // ★글자 그대로 옮기면 안 되는 자리다. Actions 의 `cancel-in-progress: true` 는
  //   새 run 이 오면 **이전 것을 취소**한다. 젠킨스의 `disableConcurrentBuilds()` 는
  //   **큐에서 기다린다** — 동시 실행은 막지만 낡은 것이 먼저 끝나야 새 것이 돈다.
  //   executor 가 1개인 이 머신에서는 그것이 정확히 막으려던 상태다.
  //   짝은 `disableConcurrentBuilds(abortPrevious: true)` 다.
  //   ★포팅하면서 실제로 `abortPrevious` 없이 써 놓았다가 이 단언을 쓰며 잡았다.
  test('★젠킨스 파이프라인이 낡은 빌드를 중단한다 (abortPrevious)', () => {
    const jf = fs.readFileSync(path.join(REPO_ROOT, 'Jenkinsfile'), 'utf8')

    // 양성 대조군 — options 블록을 실제로 뽑았다. 못 뽑으면 아래가 공허하게 통과한다.
    const options = jf.match(/options\s*\{[\s\S]*?\n\s*\}/)?.[0] ?? ''
    assert.ok(options.length > 0, 'Jenkinsfile 에서 options 블록을 못 뽑았다 — 추출기가 깨졌다')

    assert.match(
      options,
      /disableConcurrentBuilds\s*\(\s*abortPrevious\s*:\s*true\s*\)/,
      '낡은 빌드를 중단하지 않는다.\n' +
        '  executor 가 1개라 낡은 빌드가 도는 동안 현재 커밋의 검증이 시작조차 못 한다.\n' +
        '  2026-08-07 실측 — 큐 8건 중 3건이 이미 머지되고 브랜치까지 삭제된 PR 의 검증이었고,\n' +
        '  그때 현재 main 을 검증하는 run 은 0건이었다.\n' +
        '  처방. options { disableConcurrentBuilds(abortPrevious: true) }',
    )
  })

  test('★판정기가 abortPrevious 없는 형태를 실제로 거른다 (합성 뮤테이션)', () => {
    // 위 단언이 정규식만 스쳐도 통과하는 형태가 아닌지 본다.
    const bare = 'options {\n  disableConcurrentBuilds()\n  timestamps()\n}'
    assert.doesNotMatch(
      bare,
      /disableConcurrentBuilds\s*\(\s*abortPrevious\s*:\s*true\s*\)/,
      '인자 없는 형태를 통과시킨다 — 절반만 고친 상태가 초록이 된다',
    )
  })

  test('★자기 run 을 만드는 워크플로우는 전부 concurrency 취소를 선언한다', () => {
    const violations = workflows
      .filter((w) => classify(w.source) === 'creates-own-run')
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
    const reusableOnly = ['on:', '  workflow_call:', '', 'jobs:', '  check:'].join('\n')
    assert.equal(classify(reusableOnly), 'reusable-only', 'workflow_call 전용을 대상으로 잘못 판정했다.')
  })

  test('★서식 6종을 전부 인식한다 (게이트2 리뷰가 뚫었던 우회로)', () => {
    // 리뷰가 실제로 통과시켰던 형태들. 하나라도 unclassified 면 그 표기로 쓴 새 워크플로우가
    // concurrency 없이 조용히 살아남는다.
    const variants: Array<[string, string]> = [
      ['블록 매핑', 'on:\n  push:\n    branches: [main]\n'],
      ['행끝 주석', 'on:  # 트리거 — 인프라 경로만\n  push:\n    branches: [main]\n'],
      ['인라인 시퀀스', 'on: [push, pull_request]\n'],
      ['스칼라', 'on: push\n'],
      ['따옴표 키', '"on":\n  push:\n    branches: [main]\n'],
      ['4칸 들여쓰기', 'on:\n    release:\n        types: [published]\n'],
    ]
    for (const [label, source] of variants) {
      assert.equal(
        classify(source),
        'creates-own-run',
        `${label} 표기를 자기 run 생성으로 인식하지 못했다 — 이 표기로 쓴 워크플로우가 검사에서 빠진다.`,
      )
    }
  })

  test('열거하지 않은 트리거도 대상이다 (허용목록이 아니라 차단목록)', () => {
    // GitHub 이 이벤트를 추가해도 갈라지지 않아야 한다.
    for (const trigger of ['release', 'workflow_run', 'pull_request_target', 'merge_group', 'issue_comment']) {
      assert.equal(
        classify(`on:\n  ${trigger}:\n    types: [x]\n`),
        'creates-own-run',
        `${trigger} 를 대상에서 빠뜨렸다 — 허용목록 방식으로 되돌아갔는지 확인하라.`,
      )
    }
  })

  test('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    const missing = ['on:', '  push:', '    branches: [main]', '', 'jobs:', '  a:'].join('\n')
    assert.equal(classify(missing), 'creates-own-run')
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
