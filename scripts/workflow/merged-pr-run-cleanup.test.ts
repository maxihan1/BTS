// 머지된 PR 의 큐 잔존 run 정리 스크립트의 계약 판별식
//
// ## 왜 이 판별식이 필요한가
//
// 이 스크립트는 **정리 도구**다. 정리 도구가 잘못 동작하는 두 방향이 있고 둘 다 치명적이다.
//
// | 방향 | 결과 |
// |---|---|
// | 너무 적게 취소 | 좀비 run 이 러너를 계속 점유 — 고치려던 문제가 그대로 |
// | 너무 많이 취소 | **막 시작한 main 검증을 죽인다** — 고치려던 문제를 스스로 만든다 |
//
// 두 번째가 더 나쁘다. 2026-08-07 사고의 본질이 「현재 main 을 검증하는 run 이 0건」이었는데,
// 그것을 고치겠다고 만든 도구가 main 의 push CI 를 취소하면 사고를 재생산한다.
//
// ## 실제 GitHub 을 건드리지 않고 어떻게 검증하나
//
// 스크립트가 `BTS_GH_BIN` 이음매로 `gh` 를 받는다(`verify-runner-health.sh` 의
// `BTS_RUNNER_ROOT` 와 같은 관례). 여기서 **호출을 기록하는 가짜 gh** 를 주입해
// 「무엇을 호출했는가」를 그대로 읽는다. 문자열로 스크립트 본문을 매칭하지 않는다 —
// 이 저장소는 「두 층 errexit 차이를 문자열 매칭이 못 본다」를 이미 겪었다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = path.join(REPO_ROOT, 'scripts/cancel-merged-pr-runs.sh')
const MERGE_SKILL = path.join(REPO_ROOT, '.claude/skills/bts-merge/SKILL.md')

interface RunResult {
  /** 스크립트 종료 코드. */
  code: number
  /** 표준 출력 + 표준 오류를 합친 것. */
  output: string
  /** 가짜 gh 가 받은 인자 줄들. 한 줄이 한 호출이다. */
  calls: string[]
}

/**
 * 가짜 `gh` 를 만들어 스크립트를 돌린다.
 *
 * @param branch 스크립트에 넘길 브랜치 이름
 * @param behavior 가짜 gh 의 동작 — `ids` 는 `run list` 가 뱉을 id 들, `failMode` 는 실패 흉내
 */
function runScript(
  branch: string,
  behavior: { ids?: string[]; failMode?: 'list' | 'cancel' | 'all'; missing?: boolean } = {},
): RunResult {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-run-cleanup-'))
  const log = path.join(dir, 'calls.log')
  const ghPath = path.join(dir, 'fake-gh')

  const ids = behavior.ids ?? []
  const failMode = behavior.failMode ?? 'none'
  // 가짜 gh — 인자를 그대로 기록하고, 요청받은 동작을 흉내 낸다.
  fs.writeFileSync(
    ghPath,
    [
      '#!/usr/bin/env bash',
      `echo "$*" >> "${log}"`,
      `FAIL="${failMode}"`,
      'if [ "$1" = "run" ] && [ "$2" = "list" ]; then',
      '  if [ "$FAIL" = "list" ] || [ "$FAIL" = "all" ]; then exit 1; fi',
      ...ids.map((id) => `  echo "${id}"`),
      '  exit 0',
      'fi',
      'if [ "$1" = "run" ] && [ "$2" = "cancel" ]; then',
      '  if [ "$FAIL" = "cancel" ] || [ "$FAIL" = "all" ]; then exit 1; fi',
      '  exit 0',
      'fi',
      'exit 0',
    ].join('\n'),
    { mode: 0o755 },
  )

  let code = 0
  let output = ''
  try {
    output = execFileSync('bash', [SCRIPT, branch], {
      encoding: 'utf-8',
      // ★stderr 를 버리지 않는다. execFileSync 기본값은 실패 원인을 숨긴다.
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        // 존재하지 않는 경로를 주면 `command -v` 가 실패하는 경로를 탄다.
        BTS_GH_BIN: behavior.missing === true ? path.join(dir, 'no-such-gh') : ghPath,
      },
    })
  } catch (err) {
    const e = err as { status?: number; stdout?: string; stderr?: string }
    code = e.status ?? 1
    output = `${e.stdout ?? ''}${e.stderr ?? ''}`
  }

  const calls = fs.existsSync(log)
    ? fs.readFileSync(log, 'utf-8').split('\n').filter((l) => l.trim().length > 0)
    : []
  return { code, output, calls }
}

describe('머지된 PR 의 큐 잔존 run 정리', () => {
  test('스크립트가 실재하고 실행 가능하다 (비-공허 짝)', () => {
    assert.ok(fs.existsSync(SCRIPT), `${SCRIPT} 가 없다 — 아래 계약 단언이 전부 공허해진다.`)
  })

  test('★보호 브랜치는 gh 를 한 번도 호출하지 않는다', () => {
    // 머지 직후에는 main 의 push CI 가 막 시작된다. 그걸 취소하면 이 도구가 고치려던 문제를
    // 스스로 만든다 — 「현재 main 을 검증하는 run 이 0건」.
    for (const protectedBranch of ['main', 'master', 'HEAD']) {
      const r = runScript(protectedBranch, { ids: ['111', '222'] })
      assert.equal(r.code, 0, `${protectedBranch}: 종료 코드가 0 이 아니다.\n${r.output}`)
      assert.deepEqual(
        r.calls,
        [],
        `${protectedBranch} 에 대해 gh 를 호출했다 — 보호 브랜치의 검증을 죽일 수 있다.\n` +
          r.calls.join('\n'),
      )
    }
  })

  test('빈 브랜치 이름이면 아무것도 하지 않는다', () => {
    // 호출부가 브랜치를 못 구한 경우다. 여기서 인자 없이 gh 를 부르면 **현재 체크아웃된
    // 브랜치**(대개 main)를 대상으로 삼는 사고가 난다.
    const r = runScript('', { ids: ['111'] })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    assert.deepEqual(r.calls, [], '브랜치가 비었는데 gh 를 호출했다.')
  })

  test('★queued 와 in_progress 를 둘 다 조회하고 각 run 을 취소한다', () => {
    const r = runScript('fix/some-branch', { ids: ['101', '102'] })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)

    const lists = r.calls.filter((c) => c.startsWith('run list'))
    // queued 만 보면 **이미 러너를 잡은** 좀비를 놓친다. 러너가 1대라 그게 가장 아픈 경우다.
    assert.ok(
      lists.some((c) => c.includes('--status queued')),
      `queued 를 조회하지 않았다.\n${r.calls.join('\n')}`,
    )
    assert.ok(
      lists.some((c) => c.includes('--status in_progress')),
      `in_progress 를 조회하지 않았다 — 러너를 이미 점유한 run 이 그대로 남는다.\n${r.calls.join('\n')}`,
    )
    // 대상 브랜치로 한정하지 않으면 남의 PR 검증까지 죽인다.
    assert.ok(
      lists.every((c) => c.includes('--branch fix/some-branch')),
      `브랜치로 한정하지 않은 조회가 있다.\n${lists.join('\n')}`,
    )

    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels.sort(),
      ['run cancel 101', 'run cancel 101', 'run cancel 102', 'run cancel 102'],
      `조회된 run 을 전부 취소하지 않았다 (상태 2종 × id 2건 = 4회).\n${cancels.join('\n')}`,
    )
  })

  test('★gh 가 실패해도 종료 코드는 0 이다 (fail-open)', () => {
    // 정리 도구가 머지 절차를 막으면 안 된다.
    for (const failMode of ['list', 'cancel', 'all'] as const) {
      const r = runScript('fix/some-branch', { ids: ['101'], failMode })
      assert.equal(
        r.code,
        0,
        `failMode=${failMode} 에서 종료 코드가 ${r.code} 다 — 머지 절차를 막는다.\n${r.output}`,
      )
    }
  })

  test('gh 가 아예 없어도 종료 코드는 0 이다 (fail-open)', () => {
    const r = runScript('fix/some-branch', { missing: true })
    assert.equal(r.code, 0, `gh 부재에서 종료 코드가 ${r.code} 다.\n${r.output}`)
    assert.deepEqual(r.calls, [], 'gh 가 없는데 호출을 기록했다 — 이음매가 안 먹었다.')
  })

  test('숫자가 아닌 출력은 취소 대상으로 삼지 않는다 (음성 대조군)', () => {
    // gh 가 경고문·에러 텍스트를 뱉는 경우. 그걸 그대로 `run cancel` 에 먹이면 안 된다.
    const r = runScript('fix/some-branch', { ids: ['not-an-id', '303'] })
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.ok(
      cancels.every((c) => /^run cancel \d+$/.test(c)),
      `숫자가 아닌 값을 취소 대상으로 넘겼다.\n${cancels.join('\n')}`,
    )
    assert.ok(cancels.length > 0, '유효한 id 마저 취소하지 않았다 — 필터가 과하다.')
  })

  test('가짜 gh 가 실제로 호출을 기록한다 (양성 대조군)', () => {
    // 이 단언이 없으면 위의 「호출 0건」 단언들이 「기록 장치가 죽어서 0건」과 구분되지 않는다.
    const r = runScript('fix/some-branch', { ids: ['1'] })
    assert.ok(r.calls.length > 0, '가짜 gh 가 호출을 하나도 기록하지 못했다 — 이음매가 죽었다.')
  })

  test('★/bts-merge 가 이 스크립트를 실제로 부른다 (배선)', () => {
    // 스크립트만 있고 아무도 안 부르면 러너는 계속 막힌다 — 이 저장소가 여러 번 겪은
    // 「가드는 있는데 CI 배선이 없다」 양식.
    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    assert.ok(
      skill.includes('cancel-merged-pr-runs.sh'),
      '.claude/skills/bts-merge/SKILL.md 가 cancel-merged-pr-runs.sh 를 부르지 않는다 — ' +
        '스크립트가 있어도 실행되지 않는다.',
    )
  })
})
