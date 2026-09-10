// 푸시 훅의 프론트 테스트 스크립트 계약 — 배선 · 탈출구 · 오진 방지 · 판정 위임
//
// ## 무엇을 지키나
//
// 2026-09-04 진단 — 프론트 테스트 9,755개가 커밋·푸시 어느 훅에서도 안 돌고 있었다.
// CI 는 꺼져 있으므로, 사람이 최종 점검을 건너뛰면 프론트 검증 0회로 머지됐다.
//
//   1. 훅이 실제로 이 스크립트를 부른다 — 안 부르면 나머지가 전부 장식이다.
//   2. 탈출구가 있다 — 없으면 `--no-verify` 를 쓰고 판별식·lint 까지 함께 죽는다.
//   3. 「도구 부재」와 「테스트 실패」를 구분한다 — worktree 심볼릭 누락이 흔하다.
//   4. **판정을 스스로 하지 않는다.** 좁힘 규칙은 `select-test-scope.ts` 한 곳에만 있다.
//
// ## ★4번이 이 파일에만 있는 계약이다
//
// 백엔드 훅 스크립트는 자기 안에서 `selectModules` 옵션을 정한다. 프론트는 그러면 안 된다 —
// `bts-impl` Step 4 와 이 훅이 각자 「전량으로 넓힐 조건」을 들고 있으면 두 목록이 갈린다.
// 그래서 이 스크립트는 `frontendScope` 를 그대로 부르기만 한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { SKIP_ENV, VITEST_BIN } from './push-frontend-tests.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = 'scripts/workflow/push-frontend-tests.ts'
const HOOK = '.husky/pre-push'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

describe('푸시 훅 프론트 테스트', () => {
  // ★2026-09-09 재조준. 백엔드 쪽과 같은 이유다 — 종전 단언의 근거였던
  //   「훅 말고 다른 자리가 없다」가 젠킨스 도입으로 거짓이 됐다.
  //   지키려던 것은 호출 자리가 아니라 **「프론트가 푸시마다 자동으로 검증된다」**이다.
  test('★프론트 검증이 푸시마다 자동으로 돈다 (젠킨스가 그 자리다)', () => {
    const jf = read('Jenkinsfile')

    assert.match(jf, /pollSCM\(/, 'Jenkinsfile 에 pollSCM 이 없다 — 푸시를 감지하는 자리가 없다')
    assert.match(
      jf,
      /select-test-scope\.ts/,
      'Jenkinsfile 이 범위 계산기를 부르지 않는다 — 프론트 검증 구멍이 다시 열렸다',
    )
  })

  test('★젠킨스의 그 호출에 조건이 걸려 있지 않다', () => {
    // 종전에 훅에 대해 걸던 단언과 **같은 것**을 새 자리에 건다.
    // `if` 안에 넣으면 「어떤 경우엔 안 돈다」가 되고, 그 조건이 조용히 썩는다.
    // 범위 좁힘은 계산기 안에서 한다 — 부르는 자리는 무조건이다.
    const line = read('Jenkinsfile')
      .split('\n')
      .find((l) => l.includes('select-test-scope.ts') && !l.trim().startsWith('//'))
    assert.ok(line !== undefined, 'Jenkinsfile 에서 실행 줄을 못 찾았다(주석만 있다)')
    assert.doesNotMatch(line!, /^\s*(if|case|\[|test)\b/, `조건부 실행이다:\n    ${line}`)
  })

  test('★수동 폴백이 남아 있다 (젠킨스가 죽었을 때의 자리)', () => {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, SCRIPT)),
      `${SCRIPT} 가 사라졌다 — 젠킨스가 죽으면 프론트를 검증할 방법이 없다.`,
    )
    assert.ok(
      read(HOOK).includes(SCRIPT),
      `${HOOK} 가 ${SCRIPT} 를 언급하지 않는다 — 이전 사실과 폴백 명령의 기록이 사라졌다.`,
    )
  })

  test('탈출구가 있다', () => {
    const src = read(SCRIPT)
    assert.equal(SKIP_ENV, 'BTS_SKIP_FRONTEND_TEST')
    assert.ok(src.includes(SKIP_ENV), '스킵 환경변수를 스크립트가 참조하지 않는다')
    assert.ok(
      /--no-verify/.test(src),
      '탈출구를 왜 두는지(= --no-verify 로 전부 죽는 것을 막으려고)가 안 적혀 있다',
    )
  })

  test('★도구 부재와 테스트 실패를 구분한다', () => {
    const src = read(SCRIPT)
    assert.ok(src.includes('existsSync'), 'vitest 바이너리 존재 확인이 없다')
    assert.ok(
      src.includes('이것은 코드 문제가 아니다'),
      '도구 부재를 테스트 실패와 구분해 알려 주는 문구가 없다',
    )
  })

  test('★판정을 위임한다 — 좁힘 규칙을 여기서 다시 정하지 않는다', () => {
    const src = read(SCRIPT)
    assert.ok(src.includes("from './select-test-scope.ts'"), 'frontendScope 를 import 하지 않는다')
    // 전량 넓힘 조건을 이 파일이 따로 들고 있으면 두 목록이 갈린다.
    assert.doesNotMatch(
      src,
      /pnpm-lock|vite\.config|FE_WIDEN\s*=/,
      '넓힘 조건이 이 파일에 복제돼 있다 — 정본은 select-test-scope.ts 한 곳이다',
    )
  })

  test('worktree 에서 도는 바이너리 경로를 쓴다', () => {
    assert.equal(VITEST_BIN, 'apps/web/node_modules/.bin/vitest')
    assert.doesNotMatch(read(SCRIPT), /execFileSync\('pnpm'/, 'worktree 에서 pnpm 래퍼는 죽는다')
  })
})
