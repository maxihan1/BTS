// git 을 spawn 하는 소스를 훑어 배선 집합을 계산하는 스윕 한 벌 — 격리 판별식은 여기만 쓴다
//
// ## 왜 따로 있나
//
// 배선을 재는 판정은 한 자리에 머물지 않는다. 「git 을 부르는 파일이 헬퍼를 거치는가」 옆에
// 다른 층위의 판정이 붙고, 그 판정도 **같은 소스 집합**을 읽는다. 훑기와 어휘를 판정 파일
// 안에 두면 그 파일이 나뉠 때 **쪼개면서 복제된다** — 테스트 파일은 export 를 하지 않으므로
// 나뉜 쪽은 자기 사본을 만드는 수밖에 없다. 그 순간 「git 을 부르는 형태」가 두 벌이 되고,
// 둘은 서로를 검사하지 않는다. 이 저장소가 이미 이름 붙인 지배 결함 양식
// (`two-lists-never-check-each-other`)이다.
//
// 그래서 어휘와 훑기는 여기 한 벌만 둔다.
//
// ## 이 모듈은 판정하지 않는다
//
// 여기 있는 것은 **소스를 읽어 집합을 계산해 주는 것**뿐이다. 「무엇이 위반인가」는 판별식
// 파일에 남는다. 판정을 이리로 끌고 오면 실패 메시지가 대상에서 멀어지고, 무엇보다 이 모듈이
// 자기가 검사받아야 할 대상을 스스로 정의하게 된다.
//
// 판별식. scripts/workflow/git-fixture-isolation.test.ts

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/**
 * 스크럽 헬퍼 모듈 자신. 파생 집합 계산에서 뺀다.
 *
 * 정의부는 소비자가 아니다 — 자기를 임포트할 수 없고 git 도 안 부른다. 빼지 않으면
 * 「정의부가 제 규칙을 어겼다」는 오탐이 언제든 살아난다. 그리고 이것은 예외 목록이 아니다.
 * 여기 이름을 얹어 red 를 끌 수 있는 파일은 헬퍼 자신 하나뿐이고, git 호출을 헬퍼 안으로
 * 옮겨 숨기면 호출자 쪽에 임포트만 남아 **반대 방향 차집합**이 red 가 된다.
 *
 * 반대로 판별식 파일 자신은 **특별 취급하지 않는다.** victim 을 세우려고 실제로 git 을
 * 부르므로 파생 집합에 들고, 그래서 헬퍼도 실제로 임포트한다 — 규칙이 제 파일에 먼저 걸린다.
 * 비-공허 짝만 일부러 `GIT_DIR` 를 걸어 오염을 재현하는데, 그 바탕 env 는 스크럽된 것이다.
 * 경로만으로는 못 막기 때문이다 — `assertVictimPathSafe` 는 victim **경로**를 지키지만
 * `GIT_INDEX_FILE` 은 `GIT_DIR` 를 이겨 그 경로를 무력화한다. 상속된 GIT_* 하나면 충분하다.
 * 그 방향을 지키는 것은 이 주석이 아니라 판별식의 bystander 판정이다.
 */
const HELPER_MODULE = 'scripts/workflow/git-fixture-env.mjs'

/** 파생 집합이 훑는 소스 확장자. 판별식 러너가 실행하는 것과 같은 둘이다. */
const SOURCE_EXTENSIONS = ['.ts', '.mjs']

/**
 * 자식 프로세스를 띄우는 함수 이름. 긴 이름을 앞에 둬야 교대가 짧은 쪽으로 먼저 안 먹는다.
 *
 * 서브커맨드를 신호로 쓰지 않는다 — `init`·`clone` 을 세면 `worktree add` 나 `clone --bare`,
 * 변수에 담은 서브커맨드가 전부 빠져나간다. 「git 을 spawn 한다」만 본다.
 */
const SPAWN_CALLEES = ['spawnSync', 'spawn', 'execFileSync', 'execFile', 'execSync', 'exec']

/**
 * `git` 이라는 프로그램을 부르는 호출 형태의 **정규식 소스**.
 *
 * 파일 단위 술어와 호출부 스캐너가 여기서 갈라져 나온다. 층위마다 정규식을 따로 들면
 * 「git 을 부르는 형태」가 두 벌이 되고 둘은 서로를 검사하지 않는다.
 * 인자 배열 형태와 명령 문자열 형태를 함께 문다 — 1번 그룹이 호출 이름, 2번이 여는 따옴표다.
 */
const GIT_SPAWN_SOURCE = '\\b(' + SPAWN_CALLEES.join('|') + ')\\s*\\(\\s*([\'"`])git(\\2|\\s)'

/** 파일 하나가 git 을 부르는지 보는 술어. `lastIndex` 를 안 남기도록 전역 플래그를 뺀다. */
const GIT_SPAWN = new RegExp(GIT_SPAWN_SOURCE)

/** 스크럽 헬퍼를 임포트하는 자리. 모듈 지정자로만 판정한다 — 이름을 바꿔 달아도 걸린다. */
const HELPER_IMPORT = /\bfrom\s*(['"])[^'"]*git-fixture-env\.mjs\1/

/**
 * 주석을 걷어낸 소스. 재는 것은 「무엇이 적혀 있나」가 아니라 「무엇이 실행되나」다.
 *
 * 주석을 남기면 양쪽으로 뚫린다 — 설명문에 적어 둔 호출 예시가 파생 집합을 부풀리고,
 * 주석 처리된 임포트 한 줄이 배선 없이 대조를 만족시킨다.
 * 문자열 리터럴 안의 `//` 를 주석으로 오인하지 않도록 따옴표 상태를 따라간다.
 * 정규식 리터럴은 안 따라간다 — 그 손상은 호출이나 임포트를 지워 **red 쪽으로** 기운다.
 *
 * @param src 원본 소스
 * @returns 주석이 빠진 소스
 */
function stripComments(src: string): string {
  let out = ''
  let quote: string | null = null
  for (let i = 0; i < src.length; i += 1) {
    const c = src[i] ?? ''
    if (quote !== null) {
      if (c === '\\') out += c + (src[(i += 1)] ?? '')
      else if (c === quote) {
        quote = null
        out += c
      } else out += c
      continue
    }
    if (c === "'" || c === '"' || c === '`') {
      quote = c
      out += c
      continue
    }
    if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i += 1
      out += '\n'
      continue
    }
    if (c === '/' && src[i + 1] === '*') {
      const closing = src.indexOf('*/', i + 2)
      const stop = closing === -1 ? src.length : closing + 2
      // 줄바꿈만 남긴다 — 호출부 파생 집합이 **원본 줄번호**를 실패 메시지에 실어야 한다.
      // 통째로 지우면 JSDoc 뒤의 호출이 전부 위로 밀려 file:line 이 엉뚱한 자리를 가리킨다.
      out += src.slice(i, stop).replace(/[^\n]/g, '')
      i = stop - 1
      continue
    }
    out += c
  }
  return out
}

/**
 * `scripts` 아래 소스 전량의 저장소 상대 경로.
 *
 * `git ls-files` 를 안 쓴다 — 아직 추적되지 않은 새 파일도 이 규칙의 대상이다.
 * 「추적되면 검사한다」로 두면 새 픽스처 테스트가 첫 커밋 전까지 규칙 밖에 산다.
 *
 * @returns 정렬된 저장소 상대 경로 목록
 */
function scriptSources(): string[] {
  const found: string[] = []
  const walk = (dir: string): void => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (SOURCE_EXTENSIONS.includes(path.extname(entry.name))) found.push(path.relative(REPO_ROOT, full))
    }
  }
  walk(path.join(REPO_ROOT, 'scripts'))
  return found.sort()
}

/** 소스에서 재계산한 두 집합. 사람이 유지하는 목록은 어느 쪽에도 없다. */
export interface WiringSets {
  /** git 을 spawn 하는 파일 */
  spawners: string[]
  /** 스크럽 헬퍼를 임포트하는 파일 */
  importers: string[]
}

/**
 * 두 집합을 한 번의 순회로 계산한다. 같은 소스를 한 번만 읽어 두 술어를 적용한다.
 *
 * @returns 정렬된 두 집합
 */
export function deriveWiringSets(): WiringSets {
  const spawners: string[] = []
  const importers: string[] = []
  for (const rel of scriptSources()) {
    if (rel === HELPER_MODULE) continue
    const code = stripComments(fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8'))
    if (GIT_SPAWN.test(code)) spawners.push(rel)
    if (HELPER_IMPORT.test(code)) importers.push(rel)
  }
  return { spawners, importers }
}

/** 양방향 차집합 — 어느 쪽으로 어긋났는지 이름으로 남긴다. */
export interface WiringMismatch {
  /** git 을 부르는데 헬퍼를 안 거치는 파일 */
  unscrubbed: string[]
  /** 헬퍼를 임포트하는데 git 을 안 부르는 파일 */
  stray: string[]
}

/**
 * 두 집합의 차집합을 양방향으로 낸다.
 *
 * 뒤쪽 방향을 함께 재는 진짜 이유는 죽은 배선이 아니라 **정규식 부패의 조기 경보**다 —
 * 호출 형태를 놓쳐 파생 집합에서 파일이 빠져도 임포트는 남으므로 그쪽이 red 가 된다.
 *
 * @param sets 소스에서 재계산한 두 집합
 * @returns 양방향 차집합
 */
export function wiringMismatch(sets: WiringSets): WiringMismatch {
  return {
    unscrubbed: sets.spawners.filter((f) => !sets.importers.includes(f)),
    stray: sets.importers.filter((f) => !sets.spawners.includes(f)),
  }
}

// ─────────────────────────────────────────────────────────
// 호출부 단위 — 파일 단위가 못 보는 층위
// ─────────────────────────────────────────────────────────
//
// 파일 단위 집합은 「헬퍼를 아예 안 쓰는 파일」을 잡는다. 이미 배선된 파일에 스크럽 없는
// 호출을 **하나 더** 붙이면 그 대조는 초록이다. 실제로 그렇게 한 자리가 빠져나갔고,
// 잡아낸 것은 `GIT_DIR` 를 건 모사 실행이었다. 그래서 호출 하나하나를 원소로 갖는
// 집합을 따로 계산한다. 두 집합 모두 소스에서 나오므로 사람이 적는 목록은 여전히 없다.

/** git 을 spawn 하는 호출 하나. 파생 집합의 원소이고, 사람이 유지하는 목록이 아니다. */
export interface GitSpawnCallSite {
  /** 저장소 상대 경로 */
  file: string
  /** 호출 이름이 놓인 줄 (1-기반) */
  line: number
  /** 호출한 함수 이름 */
  callee: string
  /** 옵션 객체가 `env` 키를 명시했는가 */
  hasEnv: boolean
  /** 호출 이름과 `'git'` 리터럴이 서로 다른 줄에 있는가 */
  calleeSpansLines: boolean
  /** 옵션 객체가 여러 줄에 걸쳐 있는가 */
  optionsSpanLines: boolean
}

/**
 * 문자열 리터럴이 닫히는 자리.
 *
 * 이스케이프는 건너뛴다. 템플릿의 `${…}` 안은 문자열로 함께 지나간다 — 그 안에서 백틱을
 * 다시 여는 형태는 안 본다. 그 손상은 객체를 못 찾는 쪽, 즉 **red 쪽으로** 기운다.
 *
 * @param code 소스
 * @param start 여는 따옴표 위치
 * @returns 닫는 따옴표 위치. 안 닫히면 소스 끝
 */
function endOfString(code: string, start: number): number {
  const quote = code[start]
  for (let i = start + 1; i < code.length; i += 1) {
    if (code[i] === '\\') {
      i += 1
      continue
    }
    if (code[i] === quote) return i
  }
  return code.length
}

/**
 * 호출 인자에서 **마지막 최상위 객체 리터럴**을 잘라낸다 — 그것이 옵션 객체다.
 *
 * 줄 단위로 안 본다. 여러 줄에 걸쳐 쓴 호출이 바로 이 스윕이 놓쳤던 형태이므로
 * 괄호 균형을 따라가고, 문자열 리터럴 안은 건너뛴다.
 *
 * @param code 주석이 걷힌 소스
 * @param openParen 호출의 여는 괄호 위치
 * @returns 옵션 객체 본문. 인자에 객체 리터럴이 없으면 null
 */
function optionsObject(code: string, openParen: number): string | null {
  const ARGUMENT_DEPTH = 1
  let depth = 0
  let objectStart = -1
  let last: string | null = null
  for (let i = openParen; i < code.length; i += 1) {
    const c = code[i] ?? ''
    if (c === "'" || c === '"' || c === '`') i = endOfString(code, i)
    else if (c === '(' || c === '[' || c === '{') {
      depth += 1
      if (c === '{' && depth === ARGUMENT_DEPTH + 1) objectStart = i
    } else if (c === ')' || c === ']' || c === '}') {
      if (c === '}' && depth === ARGUMENT_DEPTH + 1 && objectStart >= 0) last = code.slice(objectStart, i + 1)
      depth -= 1
      if (depth === 0) return last
    }
  }
  return last
}

/**
 * 객체 리터럴 본문을 **최상위 쉼표**로 나눈다. 중첩 객체·배열·호출 안의 쉼표는 안 센다.
 *
 * @param objectText 여는 중괄호부터 닫는 중괄호까지
 * @returns 공백을 턴 조각 목록
 */
function topLevelEntries(objectText: string): string[] {
  const body = objectText.slice(1, -1)
  const entries: string[] = []
  let depth = 0
  let start = 0
  for (let i = 0; i < body.length; i += 1) {
    const c = body[i] ?? ''
    if (c === "'" || c === '"' || c === '`') i = endOfString(body, i)
    else if (c === '(' || c === '[' || c === '{') depth += 1
    else if (c === ')' || c === ']' || c === '}') depth -= 1
    else if (c === ',' && depth === 0) {
      entries.push(body.slice(start, i))
      start = i + 1
    }
  }
  entries.push(body.slice(start))
  return entries.map((e) => e.trim()).filter((e) => e !== '')
}

/** `env` 키를 명시한 조각. `env: X` 와 축약형 `{ …, env }` 를 함께 인정한다. */
const ENV_ENTRY = /^['"]?env['"]?\s*(?::|$)/

/**
 * 옵션 객체가 `env` 를 **명시**했는가.
 *
 * 「스크럽 헬퍼를 부르는가」로 재지 않는다. 격리 판별식의 비-공허 짝은 **일부러** 오염된
 * env 를 넘기는데, 그것도 이 호출부가 env 를 스스로 정한 자리다. 헬퍼 이름으로 재면
 * 그 한 자리를 살리려고 사람이 적는 예외 목록이 되살아나고, 그 목록이 red 를 끄는 가장 싼
 * 방법이 된다. 「명시했는가」로 재면 예외가 0개다 — 스크럽이 실제로 듣는지는 이 층위가
 * 아니라 victim 저장소를 세워 재는 실측이 맡는다.
 *
 * @param objectText 옵션 객체 본문
 * @returns 명시했으면 true
 */
function hasEnvKey(objectText: string): boolean {
  return topLevelEntries(objectText).some((entry) => ENV_ENTRY.test(entry))
}

/**
 * 어떤 위치가 몇 번째 줄인지. 주석 제거가 줄 수를 보존하므로 원본 줄번호와 같다.
 *
 * @param code 소스
 * @param index 위치
 * @returns 1-기반 줄번호
 */
function lineNumberAt(code: string, index: number): number {
  const FIRST_LINE = 1
  let line = FIRST_LINE
  for (let i = 0; i < index; i += 1) if (code[i] === '\n') line += 1
  return line
}

/**
 * `scripts` 아래 소스 전량에서 git 을 spawn 하는 **호출부**를 모은다.
 *
 * 파일 단위 집합과 달리 여기서는 **아무 파일도 빼지 않는다.** 스크럽 헬퍼 정의부라도
 * git 을 부른다면 env 를 명시해야 하므로 오탐이 될 수 없고, 그래서 예외가 0개다.
 *
 * @returns 파일·줄 순서대로 쌓인 호출부 목록
 */
export function deriveGitSpawnCallSites(): GitSpawnCallSite[] {
  const sites: GitSpawnCallSite[] = []
  for (const rel of scriptSources()) {
    const code = stripComments(fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8'))
    const scanner = new RegExp(GIT_SPAWN_SOURCE, 'g')
    let hit: RegExpExecArray | null
    while ((hit = scanner.exec(code)) !== null) {
      const options = optionsObject(code, code.indexOf('(', hit.index))
      sites.push({
        file: rel,
        line: lineNumberAt(code, hit.index),
        callee: hit[1] ?? '',
        hasEnv: options !== null && hasEnvKey(options),
        calleeSpansLines: hit[0].includes('\n'),
        optionsSpanLines: options !== null && options.includes('\n'),
      })
    }
  }
  return sites
}
