// 판별식 전량이 푸시 훅에서 **조건 없이** 돌도록 강제하는 판별식
//
// ## 왜 이 파일이 생겼나
//
// 2026-08-21 CI 자동 실행을 껐다(1인 개발 · 실사용자 0명 단계에서 값을 하지 않았다).
// 그 전까지 각 판별식은 자기 파일에 이런 단언을 하나씩 들고 있었다.
//
//   「내 입력 경로가 `workflow-scripts-ci.yml` 의 `on.<트리거>.paths` 에 들어 있는가.
//     없으면 나는 로컬 1회성 확인으로 끝나고 썩는다」
//
// 그 단언들이 지키려던 것은 **「판별식이 실제로 돈다」**이지 CI 그 자체가 아니었다.
// 전원이 CI 에서 푸시 훅으로 옮겨졌으므로 단언도 옮긴다.
//
// ## ★이 교체는 보장을 **강화**한다
//
// 종전은 「바뀐 경로 ↔ 판별식 입력」이라는 **두 목록**을 사람이 짝지어 유지하는 구조였다.
// 이 저장소가 이미 이름 붙인 지배 결함 양식이다 — 두 목록은 서로를 안 보면 조용히 갈라지고,
// 갈라진 뒤에도 **초록**이다. 실제로 `coveredBy` 선언이 4개 파일에 복제돼 있었다.
//
// 새 구조는 목록이 **0개**다. 훅이 `scripts/**` 전량을 무조건 돌리므로 짝맞춤이라는 개념 자체가
// 사라진다. 새 판별식을 추가할 때 어디에도 등재하지 않아도 그 순간부터 돈다.
//
// ## 무엇을 재나
//
// 「파일이 존재한다」가 아니라 **「조건 없이 전량을 부른다」**를 잰다. 존재만 보면
// `if [ 조건 ]` 안에 넣어도 통과하고, 그 순간 판별식은 특정 경로에서만 도는 옛 구조로 되돌아간다.
//
// 판별식이 실제로 red 를 낼 수 있는지(비-공허)는 아래 §양성 대조군이 합성 입력으로 확인한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  DEPLOY_GATE,
  GUARD_OPERATORS,
  HOOK,
  PNPM_WRAPPER,
  blockDepthAt,
  commandLines,
} from './hook-source.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 훅이 반드시 덮어야 하는 판별식 글롭. `package.json` 의 `test:workflow` 와 같은 범위다. */
const REQUIRED_GLOBS = ['scripts/**/*.test.ts', 'scripts/**/*.test.mjs']

function readHook(): string {
  const p = path.join(REPO_ROOT, HOOK)
  assert.ok(
    fs.existsSync(p),
    `${HOOK} 가 없다. CI 자동 실행을 끈 뒤 이것이 판별식의 **유일한 기계 강제 지점**이다 — ` +
      '없으면 판별식 25종이 전부 아무 때도 돌지 않는 장식이 된다.',
  )
  return fs.readFileSync(p, 'utf-8')
}

/**
 * 판별식 전량을 부르는 줄인가. 술어를 한 벌만 둔다 — 사본을 만들면 「호출을 찾는 규칙」이
 * 두 벌이 되고, 둘은 서로를 검사하지 않는다.
 */
function isDiscriminantCall(line: string): boolean {
  return line.includes('--test') && REQUIRED_GLOBS.every((g) => line.includes(g))
}

/** 훅 안에서 판별식 전량을 부르는 줄. 없으면 undefined. */
function discriminantInvocation(lines: string[]): string | undefined {
  return lines.find(isDiscriminantCall)
}

/**
 * 판별식 호출이 놓인 셸 블록 깊이. 0 이면 무조건 도달한다.
 *
 * 호출이 아예 없으면 `null` — 그 경우는 위 단언이 먼저 잡으므로 여기서 판정하지 않는다.
 */
function invocationBlockDepth(lines: string[]): number | null {
  return blockDepthAt(lines, isDiscriminantCall)
}

/**
 * 「그 줄이 조건 없이 실행되는가」를 재는 축 한 벌. 훅 쪽과 배포 쪽이 **같은 판정**을 쓰므로
 * 복붙 대신 여기로 모은다 — 사본을 두면 한쪽에만 축이 붙고, 두 벌은 서로를 검사하지 않는다.
 *
 * 판정은 판별식 파일인 여기 남는다(`hook-source.ts` 는 어휘와 파서만 갖는다).
 * 실패 메시지가 대상에서 멀어지지 않도록 `where`·`subject` 를 호출자가 준다.
 *
 * @param target.where 대상 파일의 저장소 상대 경로. 실패 메시지에 그대로 싣는다
 * @param target.subject 무엇의 무조건성을 재는지 (실패 메시지용)
 * @param target.lines 주석을 걷어낸 실행 줄 (`commandLines` 산출물)
 * @param target.matches 대상 줄을 고르는 술어
 * @param target.why 왜 무조건이어야 하는지. 실패 메시지 꼬리에 붙는다
 */
function assertUnconditional(target: {
  where: string
  subject: string
  lines: string[]
  matches: (line: string) => boolean
  why: string
}): void {
  const { where, subject, lines, matches, why } = target
  const line = lines.find(matches)
  const depth = blockDepthAt(lines, matches)

  assert.equal(
    depth,
    0,
    `${where} 의 ${subject}이 셸 블록 깊이 ${depth} 에 있다 — 조건부로 실행된다.\n\n${why}`,
  )

  const guarded = GUARD_OPERATORS.filter((op) => (line ?? '').includes(op))
  assert.deepEqual(
    guarded,
    [],
    `${where} 의 ${subject}이 ${guarded.join(' · ')} 로 앞 명령에 매달려 있다.\n` +
      `앞이 실패하면 그 줄은 **한 줄도 안 돌고** 파일은 그 사실을 말하지 않는다.\n\n${why}`,
  )
}

describe('판별식 훅 배선 정합', () => {
  test('푸시 훅이 판별식 전량을 부른다', () => {
    const lines = commandLines(readHook())
    const call = discriminantInvocation(lines)

    assert.ok(
      call !== undefined,
      `${HOOK} 에 판별식 전량 실행이 없다.\n` +
        `필요한 글롭 전부를 한 줄에서 덮어야 한다: ${REQUIRED_GLOBS.join(' · ')}\n\n` +
        `실행 줄(주석 제외):\n${lines.map((l) => `  ${l}`).join('\n') || '  (없음)'}\n\n` +
        `한쪽 글롭만 걸면 .ts 는 돌고 .mjs 는 안 도는 **절반 봉인**이 된다 — ` +
        `그 상태는 초록이라 아무도 눈치채지 못한다.`,
    )
  })

  test('★판별식 호출이 조건에 매달리지 않는다 (경로별 선별로 되돌아가지 않는다)', () => {
    assertUnconditional({
      where: HOOK,
      subject: '판별식 호출',
      lines: commandLines(readHook()),
      matches: isDiscriminantCall,
      why:
        `조건을 걸면 「바뀐 경로 ↔ 판별식 입력」이라는 두 목록이 되살아난다. 그 둘은 서로를 ` +
        `안 보므로 갈라진 뒤에도 초록이다 — 이 저장소가 이미 여러 번 물린 양식이고, ` +
        `이 호출이 무조건인 유일한 이유다.\n` +
        `느려서 줄이고 싶다면 조건이 아니라 **판별식 자체를 줄여라.**`,
    })
  })

  test('★pnpm 을 거치지 않는다 (워크트리 모듈 삭제 사고)', () => {
    const lines = commandLines(readHook())
    const wrapped = lines.filter((l) => PNPM_WRAPPER.test(l))

    assert.deepEqual(
      wrapped,
      [],
      `${HOOK} 가 pnpm 을 거친다: ${wrapped.join(' · ')}\n\n` +
        `워크트리가 붙어 있으면 pnpm 이 모듈 디렉터리를 지우고 다시 깔아야 한다고 판단하고, ` +
        `무-TTY 라 확인을 못 받아 \`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY\` 로 죽는다.\n` +
        `\`CI=true\` 로 승인해 뚫으면 **더 나쁘다** — 지워지는 실체를 워크트리의 심볼릭이 ` +
        `가리키고 있어 옆 세션의 작업이 함께 깨진다.\n` +
        `처방은 \`node\` 또는 \`node_modules/.bin/\` 직접 호출이다(\`.husky/pre-commit\` 과 같은 관례).`,
    )
  })

  test('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    // 위 단언들이 초록인 이유가 「배선이 옳아서」인지 「탐지 로직이 죽어서」인지 가른다.
    const ok = ["node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'"]
    const halfSealed = ["node --experimental-strip-types --test 'scripts/**/*.test.ts'"]
    const conditional = ['if git diff --quiet scripts/; then', "  node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'", 'fi']
    const viaPnpm = ['pnpm test:workflow']
    const andGuarded = ["changed=$(git diff --name-only) && node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'"]

    assert.ok(discriminantInvocation(ok) !== undefined, '정상 배선을 못 잡았다 — 탐지 로직이 죽어 있다.')
    assert.equal(discriminantInvocation(halfSealed), undefined, '절반 봉인(.mjs 누락)을 정상으로 읽었다.')

    assert.equal(invocationBlockDepth(ok), 0, '최상위 호출을 블록 안으로 읽었다.')
    assert.equal(invocationBlockDepth(conditional), 1, '`if` 블록 안의 호출을 무조건으로 읽었다.')
    assert.ok(
      GUARD_OPERATORS.some((op) => andGuarded[0].includes(op)),
      '`&&` 로 앞 명령에 매달린 호출을 무조건으로 읽었다 — 앞이 실패하면 조용히 스킵된다.',
    )
    assert.ok(viaPnpm.some((l) => PNPM_WRAPPER.test(l)), 'pnpm 래퍼를 놓쳤다.')

    // ★오탐 대조 — 훅의 **다른** 명령이 조건을 가져도 판별식 호출은 여전히 깊이 0 이다.
    //   이 대조가 없으면 종전의 과한 규칙(「훅 어디에도 조건문 금지」)으로 되돌아간다.
    const otherCmdBranches = [
      "node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'",
      'if [ -n "$SOMETHING" ]; then',
      '  ./gradlew :modules:x:test',
      'fi',
    ]
    assert.equal(
      invocationBlockDepth(otherCmdBranches),
      0,
      '판별식 뒤의 조건문을 판별식 호출의 조건으로 읽었다 — 정당한 다른 명령을 막는다.',
    )

    // 오탐 대조. 정상 처방을 위반으로 읽으면 훅을 고칠 방법이 없어진다.
    for (const allowed of [
      'node --experimental-strip-types --test',
      'node_modules/.bin/lint-staged',
      'node scripts/build-doc-index.mjs --check',
    ]) {
      assert.equal(PNPM_WRAPPER.test(allowed), false, `정상 명령을 pnpm 위반으로 읽었다: ${allowed}`)
    }
  })

  test('주석이 아니라 실행 줄을 본다 (산문 오탐 방지)', () => {
    // 이 저장소는 한국어 산문을 실행으로 오인한 전례가 여럿이다. 훅 주석에 `pnpm` 이나
    // `if` 가 설명 목적으로 등장해도 위반이 아니어야 한다.
    const prose = [
      '# ★`pnpm` 을 쓰지 않는다. if 조건을 걸어도 안 된다.',
      '',
      "node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'",
    ].join('\n')

    const lines = commandLines(prose)
    assert.deepEqual(lines.length, 1, '주석·빈 줄을 실행 줄로 셌다.')
    assert.equal(PNPM_WRAPPER.test(lines[0]), false, '주석의 pnpm 언급을 위반으로 읽었다.')
    assert.equal(invocationBlockDepth(lines), 0, '주석의 if 언급을 블록 시작으로 읽었다.')
  })
})

/**
 * `GIT_*` 네임스페이스를 지우는 실행 줄인가.
 *
 * ★무엇을 지우는지 **열거해서 맞추지 않는다.** 열거하면 「git 이 훅에 넣는 목록」과
 *   「우리가 재는 목록」이라는 두 목록이 생기고, 둘은 서로를 검사하지 않는다.
 *   여기서는 접두를 건드리는 `unset` 줄을 **찾기만** 하고, 그 줄이 정말 지우는지는
 *   아래 실효 실측이 그 줄을 실행해서 판정한다.
 */
function isGitScrub(line: string): boolean {
  return /(^|[;&|(\s])unset\b/.test(line) && line.includes('GIT_')
}

/**
 * 스크럽 줄을 `sh -e` 로 실제로 실행하고, 그 셸에 남은 `GIT_*` 개수를 돌려준다.
 *
 * ★왜 실행하나. 배선 판정은 「그 줄이 있는가」만 본다. 그러면 줄이 문법적으로 존재하는데
 *   실제로는 아무것도 안 지우는 경우(BSD/GNU `sed` 방언 차이 등)를 아무도 못 잡는다 —
 *   이 저장소가 `invariant-satisfied-by-helptext-not-logic` 로 이름 붙인 양식이다.
 *
 * ★DEVELOPMENT.md §1.1⑥(검증되지 않은 사용자 입력으로 외부 명령 실행 금지)과의 관계.
 *   실행하는 문자열은 **저장소가 소유한 `.husky/pre-push`** 를 `readHook()` 이 읽은 것이지
 *   사용자 입력이 아니다. 훅 파일 밖에서 온 문자열은 이 함수에 들어오지 않는다.
 *   그리고 손으로 적은 사본을 실행하면 배선과 실효가 **다른 대상**을 가리켜 판정이 공허해진다.
 *
 * @param scrub 훅 파일에서 읽어낸 스크럽 줄. 빈 문자열이면 「스크럽을 안 돌린」 대조군이다
 * @param dirty `GIT_*` 가 실제로 걸린 환경
 */
function remainingGitVars(scrub: string, dirty: NodeJS.ProcessEnv): number {
  const out = execFileSync('sh', ['-e', '-c', `${scrub}\nenv | grep -c '^GIT_' || true`], {
    env: dirty,
    encoding: 'utf-8',
  })
  return Number(out.trim())
}

/**
 * 훅이 상속받는 상황을 재현한 환경. 값에 공백이 든 것을 섞어 **이름만** 뽑히는지도 함께 잰다.
 * 경로는 전부 실재하지 않는 미끼다 — 이 판정은 git 을 부르지 않지만, 그래도 진짜 저장소를
 * 가리키는 값을 자식 프로세스에 넘기지 않는다.
 */
const DIRTY_ENV: NodeJS.ProcessEnv = {
  ...process.env,
  GIT_DIR: '/nonexistent-decoy/.git',
  GIT_WORK_TREE: '/nonexistent-decoy',
  GIT_INDEX_FILE: '/nonexistent-decoy/.git/index',
  GIT_SSH_COMMAND: 'ssh -o StrictHostKeyChecking=yes',
}

const WHY_SCRUB =
  'git 은 훅 프로세스에 GIT_DIR 를 export 한다. 판별식의 git 픽스처가 그것을 상속하면 ' +
  'tmp 에서 부른 init·add·commit 이 **진짜 저장소**로 간다 — 2026-08-21 에 공유 config 의 ' +
  'core.bare 와 브랜치 ref·인덱스가 실제로 그렇게 깨졌다.'

describe('푸시 훅의 GIT_* 스크럽', () => {
  test('★판별식을 부르기 전에 GIT_* 를 지운다', () => {
    const lines = commandLines(readHook())
    const scrubAt = lines.findIndex(isGitScrub)
    const callAt = lines.findIndex(isDiscriminantCall)

    assert.notEqual(
      scrubAt,
      -1,
      `${HOOK} 에 GIT_* 스크럽이 없다.\n\n${WHY_SCRUB}\n\n` +
        `실행 줄(주석 제외):\n${lines.map((l) => `  ${l}`).join('\n') || '  (없음)'}`,
    )
    assert.notEqual(callAt, -1, `${HOOK} 에 판별식 호출이 없다 — 순서를 잴 대상이 없다.`)
    assert.ok(
      scrubAt < callAt,
      `${HOOK} 의 GIT_* 스크럽이 판별식 호출보다 뒤에 있다.\n` +
        `판별식이 먼저 돌면 그 안의 git 픽스처는 이미 오염된 환경을 상속한 뒤다 — ` +
        `뒤에서 지워도 늦다.\n\n${WHY_SCRUB}`,
    )
  })

  test('★푸시 훅의 스크럽이 조건에 안 매달린다', () => {
    assertUnconditional({
      where: HOOK,
      subject: 'GIT_* 스크럽',
      lines: commandLines(readHook()),
      matches: isGitScrub,
      why:
        `조건이 붙으면 그 조건이 거짓인 실행에서 스크럽이 통째로 사라지고, 훅은 그 사실을 ` +
        `말하지 않는다 — 조용한 부재다. 그리고 오염은 조용한 부재가 가장 비싼 자리다.\n\n${WHY_SCRUB}`,
    })
  })

  test('★★훅에서 읽어낸 그 줄을 실행하면 GIT_* 가 0개 남는다 (실효 실측)', () => {
    const scrub = commandLines(readHook()).find(isGitScrub)
    if (scrub === undefined) {
      assert.fail(`${HOOK} 에 GIT_* 스크럽이 없어 실효를 잴 대상이 없다.\n\n${WHY_SCRUB}`)
    }

    // ★비-공허 짝을 **먼저** 잰다. 같은 환경에서 스크럽을 안 돌렸는데도 0 이면
    //   「원래 GIT_* 가 없어서 0」이라 아래 판정은 아무것도 증명하지 못한다.
    assert.ok(
      remainingGitVars('', DIRTY_ENV) > 0,
      '스크럽을 안 돌린 대조군에서도 GIT_* 가 0개다 — 오염 환경 재현이 실패했다. ' +
        '이 상태에서는 아래 실효 판정이 공허하게 통과한다.',
    )

    assert.equal(
      remainingGitVars(scrub, DIRTY_ENV),
      0,
      `${HOOK} 의 스크럽 줄이 **문법적으로는 있는데 실제로는 GIT_* 를 안 지운다.**\n` +
        `실행한 줄: ${scrub}\n\n` +
        `배선만 재는 판정은 이 상태를 초록으로 읽는다 — ` +
        `\`sed\` 방언 차이 하나로 0개를 지워도 줄은 그대로 거기 있기 때문이다.\n\n${WHY_SCRUB}`,
    )
  })

  test('스크럽 판정이 주석이 아니라 실행 줄을 본다 (산문 오탐 방지 · 양성 대조군)', () => {
    const real = "unset $(env | sed -n 's/^\\(GIT_[A-Za-z0-9_]*\\)=.*/\\1/p')"
    const call = "node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'"

    const prose = ['# ★GIT_DIR 를 unset 한다고 여기 적어 두기만 하면 아무것도 안 지워진다.', '', call].join('\n')
    assert.equal(commandLines(prose).find(isGitScrub), undefined, '주석의 unset 언급을 스크럽으로 읽었다.')

    // 탐지 로직이 살아 있는가. 죽어 있으면 위 판정들은 「없어서」가 아니라 「못 봐서」 초록이다.
    assert.ok(isGitScrub(real), '실제 스크럽 줄을 못 잡았다 — 탐지 로직이 죽어 있다.')

    const afterCall = [call, real]
    assert.ok(
      afterCall.findIndex(isGitScrub) > afterCall.findIndex(isDiscriminantCall),
      '판별식 호출 뒤에 놓인 스크럽을 앞선 것으로 읽었다.',
    )
    assert.equal(
      blockDepthAt(['if [ -n "$GIT_DIR" ]; then', `  ${real}`, 'fi', call], isGitScrub),
      1,
      '`if` 블록 안의 스크럽을 무조건 실행으로 읽었다.',
    )
    assert.ok(
      GUARD_OPERATORS.some((op) => `changed=$(git diff --name-only) && ${real}`.includes(op)),
      '`&&` 로 앞 명령에 매달린 스크럽을 무조건 실행으로 읽었다.',
    )

    // 오탐 대조. GIT_ 를 언급만 하거나 unset 만 쓰는 정상 명령을 스크럽으로 읽으면
    // 훅을 고칠 방법이 없어진다.
    for (const notScrub of ['echo "$GIT_DIR"', 'unset BTS_SKIP_MODULE_TEST', call]) {
      assert.equal(isGitScrub(notScrub), false, `정상 명령을 GIT_* 스크럽으로 읽었다: ${notScrub}`)
    }
  })
})

/**
 * 배포 게이트 스크립트 본문. 없으면 실패한다 — 파일이 사라지면 「훅과 같은 한 줄」이라는
 * 약속의 상대가 사라지고, 아래 판정 전부가 검사할 대상 없이 조용히 통과한다.
 */
function readDeployGate(): string {
  const p = path.join(REPO_ROOT, DEPLOY_GATE)
  assert.ok(
    fs.existsSync(p),
    `${DEPLOY_GATE} 가 없다. 이 스크립트는 프로덕션 직전의 유일한 전수 게이트이고, ` +
      `그 앞의 GIT_* 스크럽이 여기서 재는 대상이다.`,
  )
  return fs.readFileSync(p, 'utf-8')
}

const WHY_SAME_LINE =
  `${HOOK} 와 ${DEPLOY_GATE} 는 **같은 스크럽 한 줄**을 공유한다. 그 동일성이 지켜지는 ` +
  `동안에만 배포 쪽 줄이 훅 쪽 판정(존재·순서·비가드·실효 실측)에 **무임승차**한다.\n` +
  `두 줄이 갈라지는 순간 배포 쪽은 아무도 실행해 보지 않는 사본이 되고, 갈라진 뒤에도 초록이다 — ` +
  `이 저장소가 이름 붙인 지배 결함 양식 그대로다. 실제로 그 자리 주석은 이 위험을 ` +
  `문장으로 적어 두기까지 했는데, 그 동일성을 재는 기계가 없었다.\n` +
  `배포 줄을 바꾸고 싶으면 훅 줄을 **같은 커밋에서 같은 형태로** 바꿔라.`

describe('배포 게이트의 GIT_* 스크럽 (훅 판정에 무임승차한다)', () => {
  test('★★배포 게이트의 스크럽 줄이 훅의 그 줄과 문자열로 같다', () => {
    // ★왜 실행해 보지 않고 문자열만 대조하나. 배포 줄의 **실효**는 훅 줄의 실효 실측이
    //   이미 판정한다(위 §푸시 훅의 GIT_* 스크럽). 두 줄이 같다면 실효도 같다 — 사본을
    //   두 벌 실행하는 것은 같은 것을 두 번 재면서 `sh -c` 호출만 하나 늘리는 거래다.
    //   동일성이 깨지는 순간 이 판정이 red 이므로 무임승차가 소리 없이 끊기지도 않는다.
    const hookSrc = readHook()
    const deploySrc = readDeployGate()

    // 비-공허 짝 ①. 서로 **다른 두 파일**을 읽었는가. 경로 배선이 미끄러져 같은 파일을
    // 두 번 읽으면 아래 동일성은 무엇을 하든 참이다.
    assert.notEqual(
      hookSrc,
      deploySrc,
      `${HOOK} 와 ${DEPLOY_GATE} 의 내용이 통째로 같게 읽혔다 — 경로 배선이 죽어 같은 파일을 두 번 읽는다.`,
    )

    const hookScrub = commandLines(hookSrc).find(isGitScrub)
    const deployScrub = commandLines(deploySrc).find(isGitScrub)

    // 비-공허 짝 ②. 양쪽에서 정말 **찾았는가**. 둘 다 못 찾은 채 `undefined === undefined` 로
    // 통과하면 이 판정은 아무것도 증명하지 않는다.
    assert.ok(
      hookScrub !== undefined,
      `${HOOK} 에서 GIT_* 스크럽을 못 찾았다 — 비교할 원본이 없다.\n\n${WHY_SCRUB}`,
    )
    assert.ok(
      deployScrub !== undefined,
      `${DEPLOY_GATE} 에서 GIT_* 스크럽을 못 찾았다.\n\n${WHY_SAME_LINE}\n\n${WHY_SCRUB}`,
    )

    assert.equal(
      deployScrub,
      hookScrub,
      `${DEPLOY_GATE} 의 스크럽 줄이 ${HOOK} 의 그 줄과 다르다.\n` +
        `  ${HOOK}: ${hookScrub}\n` +
        `  ${DEPLOY_GATE}: ${deployScrub}\n\n${WHY_SAME_LINE}`,
    )
  })

  test('★배포 스크럽이 전량 검증 게이트보다 앞에 있다', () => {
    // 동일성만으로는 「배포 스크립트 **어디에** 있는가」가 안 잡힌다. 게이트 뒤로 밀리면
    // 판별식은 이미 오염된 환경을 상속한 뒤이고, 뒤에서 지워도 늦다.
    const lines = commandLines(readDeployGate())
    const scrubAt = lines.findIndex(isGitScrub)
    const gateAt = lines.findIndex(isDiscriminantCall)

    assert.notEqual(scrubAt, -1, `${DEPLOY_GATE} 에 GIT_* 스크럽이 없다.\n\n${WHY_SAME_LINE}`)
    assert.notEqual(
      gateAt,
      -1,
      `${DEPLOY_GATE} 에 판별식 전량 호출이 없다 — 순서를 잴 상대가 없다. 게이트 자체가 사라졌는지 본다.`,
    )
    assert.ok(
      scrubAt < gateAt,
      `${DEPLOY_GATE} 의 GIT_* 스크럽이 전량 검증 게이트보다 뒤에 있다 (스크럽 ${scrubAt} · 게이트 ${gateAt}).\n` +
        `배포는 훅·\`git bisect run\`·\`git rebase --exec\` 아래에서도 불릴 수 있고, ` +
        `그때 판별식이 먼저 돌면 픽스처가 진짜 저장소를 건드린 뒤다.\n\n${WHY_SCRUB}`,
    )
  })

  test('★배포 게이트의 스크럽이 조건에 안 매달린다', () => {
    assertUnconditional({
      where: DEPLOY_GATE,
      subject: 'GIT_* 스크럽',
      lines: commandLines(readDeployGate()),
      matches: isGitScrub,
      why:
        `조건이 붙으면 그 조건이 거짓인 배포에서 스크럽이 통째로 사라진다 — 조용한 부재다.\n` +
        `특히 \`BTS_SKIP_DEPLOY_TEST\` 분기 안으로 들어가면 「테스트를 건너뛴 배포」가 ` +
        `동시에 「스크럽도 건너뛴 배포」가 된다.\n\n${WHY_SCRUB}`,
    })
  })

  test('동일성 판정이 변형을 실제로 잡아낸다 (양성 대조군)', () => {
    // 위 판정이 초록인 이유가 「두 줄이 같아서」인지 「비교가 죽어서」인지 가른다.
    const real = "unset $(env | sed -n 's/^\\(GIT_[A-Za-z0-9_]*\\)=.*/\\1/p')"
    const narrowed = 'unset GIT_DIR'
    const call = "node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'"

    const asHook = ['# 훅 쪽 주석은 여기서 이렇게 길다', real, call].join('\n')
    const asDeploy = ['#!/bin/bash', '# 배포 쪽 주석은 다른 문장이다', real, call].join('\n')
    const mutated = ['#!/bin/bash', '# 배포 쪽 주석은 다른 문장이다', narrowed, call].join('\n')

    const pick = (sh: string): string | undefined => commandLines(sh).find(isGitScrub)

    // ★주석이 판정에 안 샌다. 두 파일의 산문이 전혀 달라도 뽑히는 것은 실행 줄뿐이므로
    //   동일성은 「같은 명령을 쓰는가」만 묻는다 — 주석을 맞추라고 요구하지 않는다.
    assert.equal(pick(asHook), pick(asDeploy), '주석이 다르다는 이유로 같은 실행 줄을 다르게 읽었다.')
    assert.equal(pick(asDeploy), real, '실행 줄을 원형 그대로 뽑지 못했다 — 어딘가에서 문자열이 변형된다.')

    // ★MC2 의 구조. 좁혀진 변형은 존재·비가드 판정을 **통과**하고 동일성만 red 다.
    //   그 자리가 이 판정이 존재하는 이유다 — 다른 판정은 아무도 그 변형을 못 잡는다.
    assert.ok(isGitScrub(narrowed), '좁혀진 변형을 스크럽으로 못 읽었다 — 그러면 존재 판정이 대신 red 가 되어 동일성의 몫이 흐려진다.')
    assert.equal(blockDepthAt(commandLines(mutated), isGitScrub), 0, '좁혀진 변형을 조건부로 읽었다.')
    assert.notEqual(pick(mutated), pick(asHook), '좁혀진 변형(`unset GIT_DIR`)을 원형과 같은 줄로 읽었다 — 동일성 비교가 죽어 있다.')

    // 부재를 「같음」으로 읽지 않는가. 스크럽이 통째로 빠진 파일에서는 undefined 가 나와야 하고,
    // 그것을 원형과 같다고 읽으면 MC1(줄 삭제)이 초록으로 통과한다.
    assert.equal(pick([call].join('\n')), undefined, '스크럽이 없는 소스에서 무언가를 뽑았다.')
    assert.notEqual(pick([call].join('\n')), pick(asHook), '스크럽 부재를 원형과 같다고 읽었다.')
  })
})
