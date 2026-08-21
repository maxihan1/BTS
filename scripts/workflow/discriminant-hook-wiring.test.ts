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
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const HOOK = '.husky/pre-push'

/** 훅이 반드시 덮어야 하는 판별식 글롭. `package.json` 의 `test:workflow` 와 같은 범위다. */
const REQUIRED_GLOBS = ['scripts/**/*.test.ts', 'scripts/**/*.test.mjs']

/**
 * 조건 분기 토큰. 하나라도 있으면 「무조건」이 깨진다.
 *
 * ★`&&`·`||` 도 넣는다. `changed=$(...) && node --experimental-strip-types --test ...` 형태는 겉보기에 조건문이 아니지만
 *   앞 명령이 실패하면 판별식이 통째로 스킵되고 훅은 초록이다 — 조용한 부재다.
 */
const BRANCH_TOKENS = ['if ', 'elif ', 'case ', '&&', '||', 'for ', 'while ']

/** pnpm 래퍼. 워크트리에서 모듈 재설치를 유발해 무-TTY 로 죽는다. */
const PNPM_WRAPPER = /(^|[;&|(\s])(npx\s+)?pnpm(\s|$)/

/** 주석과 빈 줄을 제거한 실행 줄만. 판정 대상은 「무엇이 적혀 있나」가 아니라 「무엇이 실행되나」다. */
function commandLines(sh: string): string[] {
  return sh
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'))
}

function readHook(): string {
  const p = path.join(REPO_ROOT, HOOK)
  assert.ok(
    fs.existsSync(p),
    `${HOOK} 가 없다. CI 자동 실행을 끈 뒤 이것이 판별식의 **유일한 기계 강제 지점**이다 — ` +
      '없으면 판별식 25종이 전부 아무 때도 돌지 않는 장식이 된다.',
  )
  return fs.readFileSync(p, 'utf-8')
}

/** 훅 안에서 판별식 전량을 부르는 줄. 없으면 undefined. */
function discriminantInvocation(lines: string[]): string | undefined {
  return lines.find((l) => l.includes('--test') && REQUIRED_GLOBS.every((g) => l.includes(g)))
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

  test('★조건 없이 부른다 (경로별 선별로 되돌아가지 않는다)', () => {
    const lines = commandLines(readHook())
    const found = BRANCH_TOKENS.filter((t) => lines.some((l) => l.includes(t)))

    assert.deepEqual(
      found,
      [],
      `${HOOK} 에 조건 분기가 들어왔다: ${found.join(' · ')}\n\n` +
        `조건을 걸면 「바뀐 경로 ↔ 판별식 입력」이라는 두 목록이 되살아난다. 그 둘은 서로를 ` +
        `안 보므로 갈라진 뒤에도 초록이다 — 이 저장소가 이미 여러 번 물린 양식이고, ` +
        `이 훅이 무조건인 유일한 이유다.\n` +
        `느려서 줄이고 싶다면 조건이 아니라 **판별식 자체를 줄여라.**`,
    )
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

    assert.ok(
      BRANCH_TOKENS.some((t) => conditional.some((l) => l.includes(t))),
      '조건문 안의 호출을 무조건으로 읽었다.',
    )
    assert.ok(
      BRANCH_TOKENS.some((t) => andGuarded.some((l) => l.includes(t))),
      '`&&` 로 앞 명령에 매달린 호출을 무조건으로 읽었다 — 앞이 실패하면 조용히 스킵된다.',
    )
    assert.ok(viaPnpm.some((l) => PNPM_WRAPPER.test(l)), 'pnpm 래퍼를 놓쳤다.')

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
    assert.deepEqual(
      BRANCH_TOKENS.filter((t) => lines.some((l) => l.includes(t))),
      [],
      '주석의 if 언급을 조건 분기로 읽었다.',
    )
  })
})
