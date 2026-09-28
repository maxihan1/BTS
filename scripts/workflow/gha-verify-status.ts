// GitHub Actions 검증 체크를 브랜치 기준으로 조회해 3분기로 판정한다 — 머지 전 게이트가 읽는 자리
//
// ## 왜 이 스크립트가 있나
//
// 검증이 젠킨스에서 GHA 로 옮겨 오면서(2026-09-11), 게이트 2 가 읽던
// `jenkins-build-status.ts` 는 **배포 상태 전용**이 된다. 검증 초록 여부를 읽는 자리가
// 비므로 같은 계약을 그대로 갖는 스크립트를 새로 세운다.
//
// ## ★「체크 0건」은 통과가 아니다 — 계약은 그대로다
//
// `jenkins-build-status.ts:13-19` 가 세운 성질을 글자 그대로 물려받는다.
//
//     폴링이 아직 안 돌았거나, 잡이 다른 브랜치를 보고 있거나, 젠킨스가 죽었을 때
//     이 명령은 아무것도 못 찾는다. 그것을 「빨간불이 아니니 통과」로 읽으면
//     **검증 없는 머지**가 된다. 그래서 종료 코드를 셋으로 가른다 —
//     0(초록) · 1(빨강) · 2(**판정 불가**). 2 를 0 으로 뭉개지 않는 것이 존재 이유다.
//
// GHA 에서도 같은 일이 일어난다. 워크플로우가 아직 큐에 있거나, 파일이 지워졌거나,
// 이벤트가 안 맞아 아예 안 떴을 때 체크는 0건이다.
//
// 사용. node --experimental-strip-types scripts/workflow/gha-verify-status.ts [브랜치]
// 판별식. scripts/workflow/gha-verify-status.test.ts
import { spawnSync } from 'node:child_process'
// ★git 을 spawn 할 때는 이 헬퍼를 거친다. 훅 컨텍스트에서 상속된 `GIT_DIR` 가 자식의 `cwd` 를
//   이기기 때문이다 — 걷어내지 않으면 여기서 부른 git 이 **실저장소**를 본다.
//   `git-spawn-sweep.ts` 가 저장소 전량에 대해 이 배선을 강제한다.
//   `jenkins-build-status.ts:28-30` 이 「내가 빠뜨려 red 를 봤다」고 적어 뒀는데
//   이 파일을 세우면서 **같은 실수를 반복했고 같은 판별식이 다시 잡았다**(2026-09-11).
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

/** 한 체크의 결론. GitHub 이 내는 값을 그대로 쓴다. */
export interface Check {
  name: string
  /** `success` `failure` `cancelled` `skipped` `neutral` 등. 아직 안 끝났으면 `null`. */
  conclusion: string | null
  /** `queued` `in_progress` `completed`. */
  status: string
}

/** 게이트가 읽는 판정. 종료 코드 0 · 1 · 2 에 그대로 대응한다. */
export type Verdict = 'green' | 'red' | 'unknown'

/**
 * 체크 목록을 게이트 판정으로 바꾼다.
 *
 * ★이 함수가 이 스크립트의 전부다. 나머지는 조회와 출력이다.
 */
export function judge(checks: Check[]): Verdict {
  // ★① 체크 0건은 통과가 아니다. 이 한 줄이 이 파일의 존재 이유다.
  //   워크플로우가 아직 큐에 있거나, 파일이 지워졌거나, 이벤트가 안 맞아 아예 안 떴을 때
  //   조회는 빈 배열을 낸다. 「빨간불이 아니다」를 「초록이다」로 읽으면 검증 없는 머지다.
  if (checks.length === 0) return 'unknown'

  // ② 아직 안 끝난 것이 있으면 판정을 미룬다. 게이트는 지금 답을 내야 하고,
  //   「도는 중」을 초록으로 세면 결과를 안 보고 머지하는 것과 같다.
  if (checks.some((c) => c.status !== 'completed')) return 'unknown'

  // ③ 실패 계열. `cancelled` 도 여기 넣는다 — 사람이 취소했든 `cancel-in-progress` 가
  //   죽였든, 그 체크는 **결과를 내지 않았다**. 오늘 실제로 겪었다(run 34602622965).
  const RED = new Set(['failure', 'timed_out', 'action_required', 'cancelled', 'stale'])
  if (checks.some((c) => c.conclusion !== null && RED.has(c.conclusion))) return 'red'

  // ④ 초록으로 인정하는 값. **`skipped` 를 포함한다** — 이 저장소에서는 정상 경로다.
  //   `verify.yml` 은 route 판정에 따라 잡을 **의도적으로** 건너뛴다. 문서만 고친 PR 이면
  //   backend·frontend·e2e 가 전부 skipped 이고, 그것을 빨강으로 보면 모든 문서 PR 이 막힌다.
  const GREEN = new Set(['success', 'skipped', 'neutral'])
  // 모르는 결론값은 초록으로 뭉개지 않는다. GitHub 이 값을 추가해도 조용히 통과하지 않는다.
  if (!checks.every((c) => c.conclusion !== null && GREEN.has(c.conclusion))) return 'unknown'

  // ★⑤ 그런데 **전부 skipped** 면 다르다. 아무것도 검증되지 않았다는 뜻이고,
  //   그 상태는 「워크플로우가 깨져 잡이 전부 건너뛰어졌다」와 구분되지 않는다.
  //   `verify.yml` 의 `discriminants` 는 조건 없이 항상 도므로 정상이면 최소 1건이 success 다.
  //   전부 skipped 라는 것은 그 무조건성이 깨졌다는 신호다 — 사람이 봐야 한다.
  if (checks.every((c) => c.conclusion === 'skipped')) return 'unknown'

  return 'green'
}

/** 현재 브랜치 이름. */
function currentBranch(): string {
  const r = spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], {
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  })
  return r.status === 0 ? r.stdout.trim() : ''
}

/** GitHub 에서 해당 브랜치 최신 커밋의 체크를 읽는다. 조회 자체가 실패하면 `null`. */
export function fetchChecks(branch: string): Check[] | null {
  const r = spawnSync(
    'gh',
    ['api', `/repos/{owner}/{repo}/commits/${branch}/check-runs`, '--jq', '.check_runs'],
    { encoding: 'utf-8' },
  )
  if (r.status !== 0) return null
  try {
    const raw = JSON.parse(r.stdout) as Array<{ name: string; conclusion: string | null; status: string }>
    return raw.map((c) => ({ name: c.name, conclusion: c.conclusion, status: c.status }))
  } catch {
    return null
  }
}

function main(argv: string[]): number {
  const branch = argv[0] ?? currentBranch()
  if (branch === '') {
    process.stderr.write('브랜치를 못 구했다.\n')
    return 2
  }

  const checks = fetchChecks(branch)
  if (checks === null) {
    // ★조회 실패도 「판정 불가」다. 「못 물어봤으니 문제없다」가 아니다.
    process.stdout.write(`판정 불가 — 체크를 조회하지 못했다 (${branch})\n`)
    return 2
  }

  const verdict = judge(checks)
  const summary = checks.map((c) => `  ${c.conclusion ?? c.status}\t${c.name}`).join('\n')
  process.stdout.write(`브랜치 ${branch} · 체크 ${checks.length}건\n${summary}\n`)

  if (verdict === 'green') {
    process.stdout.write('초록 — 검증 통과\n')
    return 0
  }
  if (verdict === 'red') {
    process.stdout.write('빨강 — 실패한 검증이 있다\n')
    return 1
  }
  process.stdout.write('★판정 불가 — 통과로 읽지 마라. 검증 없는 머지가 된다.\n')
  return 2
}

if (process.argv[1] !== undefined && process.argv[1].endsWith('gha-verify-status.ts')) {
  process.exit(main(process.argv.slice(2)))
}
