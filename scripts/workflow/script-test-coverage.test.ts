// scripts/ 아래 모든 테스트 파일이 실제로 러너에 잡히는지 강제하는 판별식
//
// ## 왜 이 파일이 있나
//
// 2026-08-10 실측 — `pnpm test:workflow` 가 `node --test scripts/workflow/*.test.ts` 였고,
// 그 밖의 테스트 파일 **5개**(`scripts/build-dashboard.test.mjs` ·
// `scripts/doc-index/*.test.mjs` 4개)는 **어떤 러너도 돌리지 않았다.**
// CI 도 아니고 husky 도 아니고 lint-staged 도 아니다. 실행 경로가 아예 없었다.
//
// 그 5파일은 **57건**의 테스트를 갖고 있었고 손으로 돌려 보니 전부 통과였다.
// 하지만 통과한다는 사실을 **아무도 확인하지 않는다** — 깨져도 CI 는 초록이다.
// 「테스트가 깨졌다」가 아니라 **「테스트가 없다」**와 같은 상태다.
//
// 이 저장소가 이름 붙인 지배 결함 양식 `two-lists-never-check-each-other` 이다.
// 목록 ① **디스크에 있는 테스트 파일** 과 목록 ② **러너가 훑는 glob** 이 서로를 안 본다.
// 이 판별식이 ①−② 차집합을 0 으로 강제한다.
//
// ## 왜 목록을 상수로 적지 않나
//
// 「돌려야 할 파일 목록」을 여기 적으면 그것이 **세 번째 목록**이 된다. 파일 집합은
// 런타임 `readdirSync` 재귀로 얻고, 러너 목록은 `package.json` 에서 읽는다.
// 사람이 손으로 유지하는 목록은 이 파일에 하나도 없다.
//
// ## 실행 여부와 트리거는 별개다
//
// glob 이 파일을 잡아도 **CI 가 그 변경에 발화하지 않으면** 여전히 0회 실행이다.
// 그래서 「러너가 훑는가」와 「그 경로 변경이 워크플로우를 깨우는가」를 **둘 다** 본다
// (룰 L 이 doc-index 에 대해 하는 것과 같은 형태).

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPTS_DIR = path.join(REPO_ROOT, 'scripts')
const PACKAGE_JSON = path.join(REPO_ROOT, 'package.json')
const CI_FILE = path.join(REPO_ROOT, '.github/workflows/workflow-scripts-ci.yml')

/**
 * 테스트 파일로 인정하는 파일명 형태.
 *
 * ★확장자를 열거하지 않는다. `.test.` 뒤에 오는 것을 `[cm]?[jt]s` 로 받아
 * `.test.js` · `.test.mjs` · `.test.cjs` · `.test.ts` · `.test.mts` · `.test.cts` 를 한 번에 덮는다.
 * 「지금 쓰는 두 확장자만」 적으면 새 확장자로 쓴 파일이 조용히 빠진다.
 */
const TEST_FILE_PATTERN = /\.test\.[cm]?[jt]s$/

/**
 * 훑기에서 제외할 디렉터리.
 *
 * `node_modules` 는 남의 코드고, `.bts-cache` 는 작업 산물이다.
 * 둘 다 우리가 돌릴 대상이 아니다.
 */
const SKIP_DIRS = new Set(['node_modules', '.bts-cache'])

/**
 * `scripts/` 아래 테스트 파일의 **저장소 상대 경로**를 전부 모은다.
 *
 * @returns 정렬된 경로 목록 (예: `scripts/build-dashboard.test.mjs`)
 */
function collectTestFiles(dir: string = SCRIPTS_DIR): string[] {
  const found: string[] = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue
      found.push(...collectTestFiles(path.join(dir, entry.name)))
      continue
    }
    if (TEST_FILE_PATTERN.test(entry.name)) {
      found.push(path.relative(REPO_ROOT, path.join(dir, entry.name)))
    }
  }
  return found.sort()
}

/**
 * `package.json` 의 `test:workflow` 명령에서 **경로 인자**만 뽑는다.
 *
 * `node --test 'scripts/**\/*.test.ts' 'scripts/**\/*.test.mjs'` 같은 형태에서
 * 실행 파일(`node`)과 플래그(`--test` 등)를 버리고 나머지를 돌려준다.
 * 따옴표는 벗긴다 — 셸이 확장하든(따옴표 없음) node 가 확장하든(따옴표 있음)
 * **패턴 문자열이 가리키는 파일 집합은 같다**.
 */
function runnerPatterns(command: string): string[] {
  return command
    .split(/\s+/)
    .filter((token) => token.length > 0)
    .filter((token) => token !== 'node' && token !== 'pnpm' && token !== 'npx')
    .filter((token) => !token.startsWith('-'))
    .map((token) => token.replace(/^['"]|['"]$/g, ''))
}

/**
 * glob 패턴을 정규식으로 바꿔 경로 하나가 걸리는지 본다.
 *
 * 지원하는 것은 `**`(디렉터리 경계를 넘는 임의 문자열)와 `*`(경계를 안 넘음) 둘뿐이다.
 * node 의 glob 구현 전체를 흉내 내지 않는다 — 이 판별식이 쓰는 표현은 그 둘이고,
 * 아래 「양성 대조군」이 매처가 살아 있음을 매 실행마다 확인한다.
 *
 * 패턴이 디렉터리 이름으로 끝나면(`scripts/`) 그 아래 전부를 덮는 것으로 본다.
 */
function globMatches(pattern: string, filePath: string): boolean {
  if (pattern.endsWith('/')) return filePath.startsWith(pattern)
  // 디렉터리를 그대로 준 경우 (`scripts`) — 그 아래 전부.
  if (!pattern.includes('*') && !pattern.includes('.')) {
    return filePath === pattern || filePath.startsWith(`${pattern}/`)
  }
  const source = pattern
    .split('**')
    .map((chunk) =>
      chunk
        .replace(/[.+^${}()|[\]\\]/g, '\\$&')
        .replace(/\*/g, '[^/]*'),
    )
    .join('.*')
  return new RegExp(`^${source}$`).test(filePath)
}

/** 워크플로우 파일에서 `pull_request` · `push` 각 블록의 원문을 잘라낸다. */
function triggerBlocks(ci: string): Array<readonly [string, string]> {
  return [
    ['pull_request', ci.slice(ci.indexOf('pull_request:'), ci.indexOf('push:'))],
    ['push', ci.slice(ci.indexOf('push:'), ci.indexOf('concurrency:'))],
  ] as const
}

describe('scripts/ 의 테스트 파일은 전부 러너에 잡힌다', () => {
  const testFiles = collectTestFiles()
  const pkg = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf-8')) as {
    scripts: Record<string, string>
  }
  const command = pkg.scripts['test:workflow'] ?? ''
  const patterns = runnerPatterns(command)

  /**
   * 실측 하한.
   *
   * ★`> 0` 으로 두면 훑기가 **절반만** 죽어도(16 → 1) 첫 단언이 통과하고 나머지 15개에
   * 대한 검사가 공허해진다. 이 저장소가 `ci-concurrency-coverage` 에서 실제로 겪은
   * 양식이라(리뷰가 4→3 감소를 초록인 채 통과시켰다) 하한을 실측값으로 못박는다.
   * 테스트 파일을 늘리면 이 값도 함께 올린다.
   */
  const MIN_TEST_FILES = 16

  test('테스트 파일을 실제로 수집한다 (비-공허 짝)', () => {
    assert.ok(
      testFiles.length >= MIN_TEST_FILES,
      `scripts/ 에서 테스트 파일을 ${testFiles.length}건만 찾았다 (하한 ${MIN_TEST_FILES}). ` +
        `훑기가 죽었거나 파일명 규약이 바뀌었다. 찾은 것: ${testFiles.join(', ')}`,
    )
    assert.ok(
      patterns.length > 0,
      `package.json 의 test:workflow 에서 경로 인자를 못 뽑았다 — 명령 형태가 바뀌었다.\n  명령: ${command}`,
    )
  })

  test('★모든 테스트 파일이 test:workflow 의 훑기 범위 안에 있다', () => {
    const orphans = testFiles
      .filter((file) => !patterns.some((pattern) => globMatches(pattern, file)))
      .map((file) => `  ${file}`)

    assert.deepEqual(
      orphans,
      [],
      '아래 테스트 파일은 **어떤 러너도 돌리지 않는다** — 깨져도 CI 가 초록이다.\n' +
        `현재 훑기 범위: ${patterns.join(' ')}\n` +
        'package.json 의 test:workflow 가 이 파일들까지 덮게 고쳐라.\n' +
        '(개별 경로를 늘리지 말고 범위를 넓힐 것 — 목록을 늘리면 다음 파일에서 같은 일이 반복된다.)\n\n' +
        orphans.join('\n'),
    )
  })

  test('★테스트 파일이 있는 디렉터리는 CI 트리거 paths 에 있다 (pull_request·push 양쪽)', () => {
    // 훑기에 잡혀도 워크플로우가 발화하지 않으면 여전히 0회 실행이다.
    const ci = fs.readFileSync(CI_FILE, 'utf-8')
    const dirs = [...new Set(testFiles.map((f) => path.dirname(f)))].sort()
    const missing: string[] = []

    for (const dir of dirs) {
      for (const [name, block] of triggerBlocks(ci)) {
        // 자신 또는 **상위 접두** 와일드카드 중 하나라도 있으면 커버된 것으로 본다.
        // 룰 L 과 같은 판정 — `'docs/**'` 을 무조건 OR 로 붙이면 무관한 경로까지
        // "커버됨" 으로 읽히므로 접두를 실제로 갖는지 확인한다.
        const segments = dir.split('/')
        const covered = segments.some((_, i) =>
          block.includes(`'${segments.slice(0, i + 1).join('/')}/**'`),
        )
        if (!covered) missing.push(`${dir} (${name})`)
      }
    }

    assert.deepEqual(
      missing,
      [],
      'CI 트리거 paths 에 없는 테스트 디렉터리가 있다 — 그 경로만 바꾸는 PR 에서 판별식이 0회 실행된다.\n\n' +
        missing.map((m) => `  ${m}`).join('\n'),
    )
  })

  test('판별식이 합성 누락을 실제로 잡아낸다 (양성 대조군)', () => {
    // 좁은 훑기 — 봉합 전 `test:workflow` 가 정확히 이 형태였다.
    const narrow = runnerPatterns('node --test scripts/workflow/*.test.ts')
    const missed = testFiles.filter((f) => !narrow.some((p) => globMatches(p, f)))
    assert.ok(
      missed.length > 0,
      '좁은 훑기에서도 누락이 0건으로 나온다 — 매처가 아무것도 안 하고 있다.',
    )
    assert.ok(
      missed.includes('scripts/build-dashboard.test.mjs'),
      `봉합 전 실제로 빠져 있던 파일을 못 잡는다. 잡은 것: ${missed.join(', ')}`,
    )
  })

  test('glob 매처가 경계를 지킨다 (음성 대조군)', () => {
    // `*` 는 디렉터리 경계를 넘지 않는다 — 넘으면 좁은 훑기도 "전부 커버" 로 오판된다.
    assert.equal(globMatches('scripts/*.test.mjs', 'scripts/build-dashboard.test.mjs'), true)
    assert.equal(globMatches('scripts/*.test.mjs', 'scripts/doc-index/render.test.mjs'), false)
    // `**` 는 넘는다.
    assert.equal(globMatches('scripts/**/*.test.mjs', 'scripts/doc-index/render.test.mjs'), true)
    // 확장자가 다르면 안 걸린다.
    assert.equal(globMatches('scripts/**/*.test.ts', 'scripts/doc-index/render.test.mjs'), false)
    // 무관한 경로는 안 걸린다.
    assert.equal(globMatches('scripts/**/*.test.ts', 'apps/web/src/foo.test.ts'), false)
  })
})
