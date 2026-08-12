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

/** 가짜 `gh` 에게 시킬 동작. */
interface FakeGhBehavior {
  /** `run list` 가 뱉을 id 들 — sha 없는 출력 (일반 브랜치 경로). */
  ids?: string[]
  /**
   * `run list` 가 뱉을 「id sha」 쌍들.
   *
   * ★필터링을 가짜 gh 의 `--jq` 에 맡기지 않는 이유. 가짜는 jq 를 실제로 돌리지 않으므로
   * 거기서 걸러 버리면 **스크립트가 거르는지 아닌지를 영영 못 잰다.** 판정 대상 로직은
   * 스크립트 안에 있어야 한다.
   */
  runs?: { id: string; sha: string }[]
  /**
   * `gh api` 가 돌려줄 커밋 목록 — HEAD 가 맨 앞이다.
   *
   * ★`message` 는 **본문까지 포함한 전체**다. GitHub 의 CI 건너뛰기 판정이 제목이 아니라
   * 메시지 전체를 보기 때문이다(2026-08-07 PR #345 실측 — squash 본문 3행의 `[skip ci]` 가
   * main push CI 를 0회로 만들었다).
   *
   * ★스크립트가 받는 형태는 실물 `--jq '... | @json'` 과 같다 — 개행이 `\n` 으로 이스케이프된
   * 한 줄짜리 JSON 문자열이다. **공백으로 눕히지 않는다**(아래 `apiLines` 주석 참조).
   */
  commits?: { sha: string; message: string }[]
  /**
   * `gh api` 가 돌려줄 현재 main HEAD sha.
   *
   * `commits` 를 주지 않은 케이스의 후방호환 통로다 — 한 줄만 뱉으므로 스크립트는
   * 「sha 1개 + 빈 메시지」로 읽고, 빈 메시지는 skip-ci 가 아니므로 곧 검증 대상 커밋이 된다.
   */
  headSha?: string
  /** `gh api` 자체가 실패하는 경우 (인증 만료 · 네트워크 · 저장소 판별 실패). */
  headShaFail?: boolean
  /** `run list` / `run cancel` 실패 흉내. */
  failMode?: 'list' | 'cancel' | 'all'
  /** `gh` 실행 파일 자체가 없는 경우. */
  missing?: boolean
}

/**
 * 가짜 `gh` 를 만들어 스크립트를 돌린다.
 *
 * @param branch 스크립트에 넘길 브랜치 이름
 * @param behavior 가짜 gh 의 동작
 */
function runScript(branch: string, behavior: FakeGhBehavior = {}): RunResult {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-run-cleanup-'))
  const log = path.join(dir, 'calls.log')
  const ghPath = path.join(dir, 'fake-gh')

  // `ids` 는 sha 없는 줄, `runs` 는 「id sha」 줄. 둘을 합친 것이 run list 의 출력이다.
  const listLines = [
    ...(behavior.ids ?? []),
    ...(behavior.runs ?? []).map((r) => `${r.id} ${r.sha}`),
  ]
  // `gh api` 가 뱉을 커밋 줄들. 실물 스크립트의 `--jq '... | @json'` 과 **같은 모양**이어야
  // 한다 — 메시지는 개행이 `\n` 으로 이스케이프된 한 줄짜리 JSON 문자열이다.
  //
  // ★공백으로 눕히지 않는다. 그렇게 하면 「메시지 전체를 보는가 제목만 보는가」의 판정이
  //   가짜 gh 쪽으로 넘어가고, 스크립트를 「제목만」으로 훼손해도 red 가 나지 않는다
  //   (2026-08-12 실측 — 그 형태에서 뮤테이션 M3 가 살아남았다).
  //
  // 가짜 gh 는 bash 스크립트라 `echo "..."` 안에 그대로 박힌다. JSON 문자열에는 따옴표와
  // 역슬래시가 들어 있으므로 큰따옴표 문맥용으로 이스케이프해야 한다 — 안 하면 생성된
  // 스크립트의 인용이 깨져 **가짜 gh 가 조용히 엉뚱한 것을 뱉는다.**
  const shellDq = (s: string): string => s.replace(/(["\\$`])/g, '\\$1')
  const apiLines = (behavior.commits ?? []).map(
    (c) => shellDq(`${c.sha} ${JSON.stringify(c.message)}`),
  )
  const failMode = behavior.failMode ?? 'none'
  const headSha = behavior.headSha ?? ''
  const apiFail = behavior.headShaFail === true ? '1' : '0'
  // 가짜 gh — 인자를 그대로 기록하고, 요청받은 동작을 흉내 낸다.
  fs.writeFileSync(
    ghPath,
    [
      '#!/usr/bin/env bash',
      `echo "$*" >> "${log}"`,
      `FAIL="${failMode}"`,
      `API_FAIL="${apiFail}"`,
      `HEAD_SHA="${headSha}"`,
      'if [ "$1" = "api" ]; then',
      '  if [ "$API_FAIL" = "1" ]; then exit 1; fi',
      // `commits` 를 준 케이스는 그 목록을, 안 준 케이스는 종전대로 sha 한 줄을 뱉는다.
      ...(apiLines.length > 0
        ? apiLines.map((line) => `  echo "${line}"`)
        : ['  echo "$HEAD_SHA"']),
      '  exit 0',
      'fi',
      'if [ "$1" = "run" ] && [ "$2" = "list" ]; then',
      '  if [ "$FAIL" = "list" ] || [ "$FAIL" = "all" ]; then exit 1; fi',
      ...listLines.map((line) => `  echo "${line}"`),
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
    // 좁힌 것은 `main` 하나뿐이다. `master`·`HEAD` 는 통째 무접촉 계약을 그대로 유지한다 —
    // 「좁히기」가 의도한 범위를 넘어 번지지 않았음을 이 단언이 고정한다.
    for (const protectedBranch of ['master', 'HEAD']) {
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

  test('★★main 의 run 중 현재 HEAD 것은 절대 취소하지 않는다', () => {
    // 이 도구가 자기 발등을 찍는 경로다. 머지 직후 main push CI 는 **새 HEAD** 로 돈다.
    // 그것을 죽이면 고치려던 문제(「현재 main 을 검증하는 run 이 0건」)를 스스로 만든다.
    const r = runScript('main', {
      headSha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      runs: [{ id: '901', sha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `현재 HEAD 를 검증 중인 run 을 취소했다 — 이 도구가 고치려던 문제를 스스로 만든다.\n` +
        r.calls.join('\n'),
    )
  })

  test('★★main 의 낡은 커밋 run 은 취소한다 (HEAD 것과 섞여 있어도)', () => {
    // paths 필터 사각. 뒤 머지들이 전부 backend/** 를 안 건드리면 낡은 backend-ci 는
    // 새 run 이 안 생겨 concurrency 가 발화하지 못하고 러너를 계속 점유한다.
    // 2026-08-10 실측 — 커밋 236ff3532 의 backend-ci 가 1시간 43분 점유했다.
    const HEAD = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    const OLD = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    const r = runScript('main', {
      headSha: HEAD,
      runs: [
        { id: '901', sha: HEAD },
        { id: '902', sha: OLD },
      ],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)

    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    // 상태 2종(queued · in_progress) × 낡은 run 1건 = 2회. HEAD 것(901)은 한 번도 없어야 한다.
    assert.deepEqual(
      cancels.sort(),
      ['run cancel 902', 'run cancel 902'],
      `낡은 run 만 정확히 취소해야 한다 (HEAD=901 보호, 낡음=902 취소).\n${r.calls.join('\n')}`,
    )
  })

  test('★★HEAD 가 [skip ci] 면 그 부모(= 실제 검증 중인 커밋)의 run 을 취소하지 않는다', () => {
    // 이 PR 의 존재 이유. post-merge 훅이 머지 직후 `[chore] dashboard regen [skip ci]` 를
    // push 해 HEAD 를 한 칸 민다. 그 커밋은 run 이 0건이고, 머지 내용을 검증 중인 run 은
    // **부모**에 붙어 있다. HEAD 만 보호하면 그 run 이 정확히 취소 대상이 된다.
    // 실측 2026-08-12 PR #376 — HEAD af3978648(run 0건) / in_progress ade4dd826.
    //
    // ★`[skip ci]` 를 **본문**에 둔다. 제목만 보는 구현은 여기서 red 가 나야 한다.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen\n\n[skip ci]' },
        { sha: MERGE, message: 'docs: 부채 등재 (#376)' },
      ],
      runs: [{ id: '901', sha: MERGE }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      '머지 내용을 검증 중인 run 을 취소했다 — 가드가 스스로 「현재 main 검증 0건」을 만든다.\n' +
        r.calls.join('\n'),
    )
  })

  test('★★[skip ci] 구간보다 낡은 커밋의 run 은 여전히 취소한다 (가드가 과하게 넓지 않다)', () => {
    // 반대 방향 사고. 보호를 넓히다가 「전부 보호」가 되면 낡은 run 이 러너를 계속 점유해
    // PR #366 이 닫은 부채가 되살아난다. 2026-08-10 실측 — 낡은 backend-ci 가 1시간 43분 점유.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const OLD = 'c'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen [skip ci]' },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
        { sha: OLD, message: 'feat: 더 낡은 것 (#375)' },
      ],
      runs: [
        { id: '901', sha: MERGE },
        { id: '902', sha: OLD },
      ],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    // 상태 2종 × 낡은 run 1건 = 2회. 검증 중인 901 은 한 번도 없어야 한다.
    assert.deepEqual(
      cancels.sort(),
      ['run cancel 902', 'run cancel 902'],
      `낡은 run 만 정확히 취소해야 한다 (보호=901, 취소=902).\n${r.calls.join('\n')}`,
    )
  })

  test('★훑은 구간이 전부 [skip ci] 면 아무것도 취소하지 않는다 (fail-open)', () => {
    // 검증 대상 커밋을 특정하지 못한 상태다. 여기서 「전부 취소」로 새면 그것이 곧
    // 자기 발등 찍기다 — 모르면 손대지 않는다. 기존 HEAD 미확인 케이스와 같은 방향.
    const r = runScript('main', {
      commits: [
        { sha: 'a'.repeat(40), message: '[chore] dashboard regen [skip ci]' },
        { sha: 'b'.repeat(40), message: '[chore] dashboard regen [skip ci]' },
      ],
      runs: [{ id: '901', sha: 'c'.repeat(40) }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `검증 대상 커밋을 모르는 상태에서 취소했다.\n${r.calls.join('\n')}`,
    )
  })

  test('★[skip ci] 커밋 자신에 run 이 붙어 있으면 그것도 보호한다', () => {
    // `[skip ci]` 는 push·PR 트리거만 막는다. 수동 dispatch 등으로 그 sha 에 run 이 생길 수
    // 있고, 그 run 도 **지금 main 에 있는 내용**을 검증 중이다. 보호 집합을 검증 대상 커밋
    // 하나로 좁히면 여기서 red 가 나야 한다.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: 'chore: dashboard regen [skip ci]' },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
      ],
      runs: [{ id: '901', sha: REGEN }],
    })
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `HEAD 자신의 run 을 취소했다 — 그것도 현재 main 내용을 검증 중이다.\n${r.calls.join('\n')}`,
    )
  })

  test('★★내용 있는 머지 커밋이 [skip ci] 를 물고 와도 되감지 않는다 (낡은 run 은 예정대로 취소)', () => {
    // 반대 방향 회귀. squash 본문은 브랜치 커밋 메시지를 그대로 싣기 때문에 **내용 있는 머지**가
    // `[skip ci]` 를 물고 오는 일이 실제로 있다(2026-08-07 PR #345 가 그 사고였다).
    // 토큰만으로 되감으면 그 머지를 지나쳐 훨씬 낡은 커밋을 기준으로 잡고, 거기 붙은 낡은 run 을
    // 보호한다 — PR #366 이 닫은 「낡은 main run 이 러너 1시간 43분 점유」 부채가 되살아난다.
    //
    // 그 머지 커밋은 GitHub 도 CI 를 건너뛰어 run 이 0건이므로, 되감지 않고 기준으로 삼아도
    // 보호할 것이 없다. 손해 없이 낡은 run 만 정확히 치운다.
    const REGEN = 'a'.repeat(40)
    const MERGE_WITH_TOKEN = 'b'.repeat(40)
    const OLD = 'c'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: '[chore] dashboard regen [skip ci]' },
        {
          sha: MERGE_WITH_TOKEN,
          message: 'feat: 뭔가 (#380)\n\n* chore: doc index regen — 메모리 1건 등재 [skip ci]',
        },
        { sha: OLD, message: 'feat: 훨씬 낡은 것 (#370)' },
      ],
      runs: [{ id: '902', sha: OLD }],
    })
    assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels.sort(),
      ['run cancel 902', 'run cancel 902'],
      '내용 있는 머지 커밋을 지나쳐 낡은 run 을 보호했다 — PR #366 이 닫은 부채가 되살아난다.\n' +
        r.calls.join('\n'),
    )
  })

  test('★[SKIP CI] 처럼 대문자로 써도 건너뛰기로 인식한다', () => {
    // GitHub 의 건너뛰기 키워드 판정은 대소문자를 가리지 않는다. 가드가 소문자만 보면
    // GitHub 은 CI 를 건너뛰었는데(그 커밋 run 0건) 가드는 그것을 검증 대상으로 잡아
    // 부모의 진짜 검증 run 을 취소한다 — 이 PR 이 고치려는 결함의 정확한 재현이다.
    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: '[chore] Dashboard Regen [SKIP CI]' },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
      ],
      runs: [{ id: '901', sha: MERGE }],
    })
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      '대문자 토큰을 못 알아봐서 부모의 검증 run 을 취소했다.\n' + r.calls.join('\n'),
    )
  })

  test('★★post-merge 훅이 실제로 만드는 커밋이 되감기 대상으로 분류된다 (두 목록 짝맞춤)', () => {
    // ★이 저장소의 지배적 결함 양식 차단 — 「두 목록이 서로를 확인하지 않는다」.
    //   되감기 패턴(`REWIND_MESSAGE_PATTERNS`)과 훅이 쓰는 커밋 메시지는 **짝**이다.
    //   한쪽만 바뀌면 가드가 조용히 되감기를 멈추고, 이 PR 이 고친 결함이 소리 없이 되돌아온다.
    //   그래서 문자열을 여기 베끼지 않고 **훅 파일에서 실제로 읽어** 먹인다.
    const hook = fs.readFileSync(path.join(REPO_ROOT, '.husky/post-merge'), 'utf-8')
    const m = hook.match(/git commit -m "([^"]+)"/)
    assert.ok(
      m !== null,
      '.husky/post-merge 에서 커밋 메시지를 못 찾았다 — 훅이 바뀌었으면 이 짝을 다시 맞춰야 한다.\n' +
        hook,
    )
    const hookMessage = m[1]
    assert.match(
      hookMessage,
      /\[skip ci\]/i,
      `훅 커밋 메시지에 건너뛰기 토큰이 없다 (${hookMessage}) — 되감기 전제가 무너진다.`,
    )

    const REGEN = 'a'.repeat(40)
    const MERGE = 'b'.repeat(40)
    const r = runScript('main', {
      commits: [
        { sha: REGEN, message: hookMessage },
        { sha: MERGE, message: 'feat: 뭔가 (#377)' },
      ],
      runs: [{ id: '901', sha: MERGE }],
    })
    const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
    assert.deepEqual(
      cancels,
      [],
      `훅이 만드는 커밋(${hookMessage})을 되감지 않아 부모의 검증 run 을 취소했다 — ` +
        'REWIND_MESSAGE_PATTERNS 와 .husky/post-merge 가 어긋났다.\n' +
        r.calls.join('\n'),
    )
  })

  test('★main 인데 현재 HEAD 를 확인하지 못하면 아무것도 취소하지 않는다 (fail-open)', () => {
    // 조회 실패에서 「전부 취소」로 새면 그것이 곧 자기 발등 찍기다. 모르면 손대지 않는다.
    for (const behavior of [{ headShaFail: true }, { headSha: '' }]) {
      const r = runScript('main', {
        ...behavior,
        runs: [{ id: '901', sha: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' }],
      })
      assert.equal(r.code, 0, `종료 코드가 0 이 아니다.\n${r.output}`)
      const cancels = r.calls.filter((c) => c.startsWith('run cancel'))
      assert.deepEqual(
        cancels,
        [],
        `HEAD 미확인 상태에서 취소했다 — 현재 main 검증을 죽일 수 있다.\n${r.calls.join('\n')}`,
      )
    }
  })

  test('★★사람이 읽는 건수는 run 개수다 — 호출 횟수가 아니다', () => {
    // 이 스크립트는 상태 2종(queued · in_progress)을 각각 조회하므로 **같은 run 이 두 번**
    // 나온다. 그것을 그대로 세면 run 1개가 「2건」이 된다. 로그는 사람이 읽고 판단하는
    // 유일한 창인데, 그 숫자가 2배로 부풀면 「생각보다 많이 죽었나」로 오독한다.
    const HEAD = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    const OLD = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    const r = runScript('main', {
      headSha: HEAD,
      runs: [
        { id: '901', sha: HEAD },
        { id: '902', sha: OLD },
      ],
    })
    // 취소된 run 은 902 하나, 보호된 run 은 901 하나다.
    //
    // ★단언을 문장 어순에 묶지 않는다. 재는 것은 **건수**이지 표현이 아니다 —
    // 어순에 결합하면 메시지를 다듬는 것만으로 red 가 되어, 무엇이 깨졌는지가 흐려진다.
    const lines = r.output.split('\n')
    const cancelLine = lines.find((l) => l.includes('취소했다'))
    const protectedLine = lines.find((l) => l.includes('건드리지 않았다'))

    assert.ok(cancelLine !== undefined, `취소 결과 줄이 없다.\n${r.output}`)
    assert.match(
      cancelLine,
      /run 1건/,
      `취소 건수가 run 개수가 아니다 (호출 횟수를 세고 있다).\n${cancelLine}`,
    )
    assert.ok(protectedLine !== undefined, `보호 결과 줄이 없다.\n${r.output}`)
    assert.match(
      protectedLine,
      /run 1건/,
      `보호 건수가 run 개수가 아니다.\n${protectedLine}`,
    )
  })

  test('★★「취소했다」와 「건드리지 않았다」를 같은 집합처럼 쓰지 않는다', () => {
    // 「잔존 run 2건을 취소했다 / **그중** 2건은 건드리지 않았다」는 자기모순이다.
    // 두 수는 서로소인 집합의 크기다 — 한쪽이 다른 쪽의 부분집합인 것처럼 쓰면 로그가
    // 거짓말한다. 계산값은 맞는데 문장이 틀린 경우라 숫자 단언만으로는 안 잡힌다.
    const r = runScript('main', {
      headSha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      runs: [
        { id: '901', sha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' },
        { id: '902', sha: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' },
      ],
    })
    assert.ok(
      !/그중/.test(r.output),
      `취소분과 보호분을 「그중」으로 이었다 — 서로소인 두 집합이다.\n${r.output}`,
    )
  })

  test('★main 조회는 headSha 를 함께 요청한다 (비-공허 짝)', () => {
    // 위 두 단언은 「스크립트가 sha 를 실제로 받아 비교한다」를 전제한다. 요청 자체가
    // 빠지면 가짜 gh 가 무엇을 뱉든 비교가 성립하지 않아 그 단언들이 공허해진다.
    const HEAD = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    const r = runScript('main', { headSha: HEAD, runs: [{ id: '902', sha: 'cccc' }] })
    const lists = r.calls.filter((c) => c.startsWith('run list'))
    assert.ok(lists.length > 0, `main 에 대해 run list 를 아예 호출하지 않았다.\n${r.calls.join('\n')}`)
    assert.ok(
      lists.every((c) => c.includes('headSha')),
      `run list 가 headSha 를 요청하지 않았다 — sha 비교가 성립할 수 없다.\n${lists.join('\n')}`,
    )
    assert.ok(
      r.calls.some((c) => c.startsWith('api ')),
      `현재 HEAD 를 원격에 묻지 않았다.\n${r.calls.join('\n')}`,
    )
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

  test('★★/bts-merge 가 main 도 인자로 넘긴다 (배선 — 낡은 main run 정리)', () => {
    // 스크립트가 main 의 낡은 run 을 가려낼 수 있어도 **아무도 main 을 넘기지 않으면**
    // paths 필터 사각은 그대로다. 위 케이스들이 전부 초록인 채 부채가 안 닫히는 경로다.
    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    assert.ok(
      /cancel-merged-pr-runs\.sh\s+main\b/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 가 `cancel-merged-pr-runs.sh main` 을 부르지 않는다 — ' +
        'PR 브랜치만 치우면 낡은 main run 이 러너를 계속 점유한다.',
    )
  })

  test('★계약이 좁아진 사실이 호출부 주석에 반영돼 있다 (문서 drift)', () => {
    // 「보호 브랜치(main/master/HEAD)를 넘기면 gh 를 한 번도 호출하지 않는다」는 이제 거짓이다.
    // 거짓 주석은 다음 사람이 계약을 되돌리게 만든다.
    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    assert.ok(
      !/보호 브랜치\(main\/master\/HEAD\)/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 주석이 아직 main 을 통째 무접촉으로 설명한다 — ' +
        '계약이 좁아졌으므로 같은 커밋에서 정정해야 한다.',
    )
  })

  test('★★대시보드 수동 폴백 경로가 생성기의 실제 출력과 같다 (두 목록 짝맞춤)', () => {
    // `/bts-merge` Step 3 은 훅이 안 돌았을 때의 수동 폴백으로 `git add <progress.html>` 을
    // 지시한다. 그 경로가 생성기의 실제 출력과 다르면 **실행하는 순간 pathspec 오류**로 죽는다.
    // 2026-07-17 에 이미 적발됐는데(메모리 `dashboard-regen-after-fr-marking`) 문서만 남아
    // 26일간 그대로였다 — 사람 기억은 짝을 유지하지 못한다는 증거라 판별식으로 못박는다.
    const generator = fs.readFileSync(path.join(REPO_ROOT, 'scripts/build-dashboard.mjs'), 'utf-8')
    const m = generator.match(/OUTPUT_PATH\s*=\s*path\.join\([^,]+,\s*'([^']+)'\)/)
    assert.ok(
      m !== null,
      'build-dashboard.mjs 에서 OUTPUT_PATH 를 못 읽었다 — 아래 대조가 공허해진다.',
    )
    const actual = m[1]

    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    // 스킬이 적은 progress.html 경로를 전부 모은다. 경로 없이 파일명만 쓴 산문은 제외한다.
    const mentioned = [...new Set([...skill.matchAll(/[\w./-]*progress\.html/g)].map((x) => x[0]))]
    const wrong = mentioned.filter((p) => p.includes('/') && p !== actual)

    assert.deepEqual(
      wrong,
      [],
      `.claude/skills/bts-merge/SKILL.md 가 실재하지 않는 경로를 지시한다: ${wrong.join(' · ')}\n` +
        `생성기(build-dashboard.mjs)의 실제 출력은 '${actual}' 이다.\n` +
        `그대로 실행하면 git 이 pathspec 오류로 죽어 폴백 절차 자체가 성립하지 않는다.`,
    )
    assert.ok(
      skill.includes(actual),
      `.claude/skills/bts-merge/SKILL.md 가 실제 출력 경로 '${actual}' 를 한 번도 적지 않는다 — ` +
        '폴백 절차가 무엇을 커밋해야 하는지 알 수 없다.',
    )
  })

  test('★★호출부 주석이 「현재 HEAD 무접촉」이라고 말하지 않는다 (문서 drift)', () => {
    // 계약이 「현재 HEAD」에서 「현재 main 내용(= [skip ci] 를 되감은 구간)」으로 넓어졌다.
    // 옛 문구가 남으면 다음 사람이 이 PR 을 되돌린다 — 이 저장소가 여러 번 겪은 양식.
    const skill = fs.readFileSync(MERGE_SKILL, 'utf-8')
    assert.ok(
      !/현재 HEAD 무접촉/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 가 아직 계약을 「현재 HEAD 무접촉」으로 설명한다 — ' +
        '넓어진 계약을 같은 커밋에서 반영해야 한다.',
    )
    // ★「skip ci」로 재지 않는다. 그 문자열은 Step 3 의 수동 폴백 커맨드에 **이미** 있어서
    //   무엇을 고치든 통과하는 공허한 단언이 된다. 되감기를 실제로 설명했는지를 재려면
    //   이 PR 이 새로 들여오는 낱말로 재야 한다.
    assert.ok(
      /되감/.test(skill),
      '.claude/skills/bts-merge/SKILL.md 가 `[skip ci]` 되감기를 설명하지 않는다 — ' +
        '호출자가 이 가드의 실제 판정 기준을 알 수 없다.',
    )
  })
})
