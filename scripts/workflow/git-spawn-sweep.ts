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
 * 비-공허 짝만 일부러 스크럽 없이 부르는데, 그 대상은 `assertVictimPathSafe` 를 통과한
 * mkdtemp 아래 victim 이라 저장소에 닿지 않는다.
 */
const HELPER_MODULE = 'scripts/workflow/git-fixture-env.mjs'

/** 파생 집합이 훑는 소스 확장자. 판별식 러너가 실행하는 것과 같은 둘이다. */
const SOURCE_EXTENSIONS = ['.ts', '.mjs']

/**
 * `git` 이라는 프로그램을 부르는 호출 형태.
 *
 * 서브커맨드를 신호로 쓰지 않는다 — `init`·`clone` 을 세면 `worktree add` 나 `clone --bare`,
 * 변수에 담은 서브커맨드가 전부 빠져나간다. 「git 을 spawn 한다」만 본다.
 * 인자 배열 형태와 명령 문자열 형태를 함께 문다.
 */
const GIT_SPAWN = /\b(?:spawnSync|spawn|execFileSync|execFile|execSync|exec)\s*\(\s*(['"`])git(\1|\s)/

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
      for (i += 2; i < src.length && !(src[i] === '*' && src[i + 1] === '/'); i += 1);
      i += 1
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
