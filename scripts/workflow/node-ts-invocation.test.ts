// node 로 .ts 를 부르는 호출문이 타입 스트리핑 플래그를 갖는지 강제하는 판별식
//
// ## 왜 이 파일이 있나
//
// 2026-08-14 실측 — `pnpm test:workflow` 가 로컬에서 **62 pass / 20 fail** 이었다.
// 실패 20건은 테스트 실패가 아니라 전량 `ERR_UNKNOWN_FILE_EXTENSION` 이다.
// `.ts` 판별식이 **한 줄도 실행되지 않았다.** 플래그를 붙이면 296 pass / 0 fail 이 된다.
//
//   로컬 node        v22.14.0
//   러너 toolcache   22.23.2      ← 22.18+ 는 타입 스트리핑이 **기본 활성**
//   CI 선언          `node-version: 22` → 최신 22.x 로 부유
//   버전 핀 파일     없음
//   engines          `node >=22`  ← 22.14 도 22.18 도 만족. **이 경계를 못 잡는다**
//
// 그래서 **CI 초록 / 로컬 빨강이 구조적으로 고정**돼 있었다. 흔한 「로컬 초록 → CI 빨강」의
// 거울상이라 더 나쁘다 — 사람이 로컬 빨강을 **정상이라고 학습**하고, 그 순간 이 명령은
// 검증 장치가 아니라 소음이 된다. `TODOS.md` 매핑 `27` 이 그렇게 적었다.
//
// ## 이 결함이 왜 살아남았나 — 몰라서가 아니다
//
// 착수 전 조사에서 같은 사실이 **세 곳에 이미 기록돼 있었다.**
//
//   1. `scripts/doc-index/mutation-probe.sh:19` 의 ★주석
//   2. learnings `bts-node-strip-types` (confidence 9/10, 2026-08-09)
//   3. `TODOS.md` 매핑 `27` (2026-08-12 등재 · 처방 후보 3안까지 기재)
//
// 셋 다 「알고 있다」였고 **아무것도 강제하지 않았다.** 「`node` 로 `.ts` 를 부르는 호출문
// 목록」과 「플래그를 든 호출문 목록」이 서로를 검사하지 않는다 —
// 이 저장소의 지배 결함 양식(`two-lists-never-check-each-other`)이다.
//
// 선례. learnings 2026-07-15 (PR #274) — **검증 장치가 고장 나면 검증했다는 착각이
// 검증 부재보다 나쁘다.**

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/**
 * 스캔에서 통째로 빼는 디렉터리 — 생성물·의존성·다른 워크트리.
 *
 * `_archive` 는 보관된 옛 하네스다. 고치면 「그때 무엇이었나」가 사라진다.
 */
const EXCLUDE_DIRS = new Set([
  'node_modules',
  '.git',
  '.worktrees',
  'build',
  'dist',
  'target',
  '_archive',
  '.gradle',
  'coverage',
  'test-results',
  'playwright-report',
  // `/bts` 의 분류 캐시. `.gitignore` 대상이고 **사용자 입력 원문**을 담아서, 작업 제목에
  // 파일 경로가 들어가면 그것이 호출문으로 오인된다. 저장소 산출물이 아니므로 뺀다.
  '.bts-cache',
])

/**
 * ★과거 기록. **고치면 사실이 바뀐다.**
 *
 * `docs/plans` 한 곳에만 플래그 없는 호출문이 수십 건 있는데, 전부 그 시점에 실제로 친
 * 명령의 기록이다. 판별식이 이것을 red 로 만들면 사람은 기록을 고쳐서 초록을 만들게 되고,
 * 그러면 이 저장소가 가진 가장 값진 자산(무엇을 왜 했는지의 원본)이 훼손된다.
 *
 * ⚠️ **알려진 사각.** `docs/plans/2026-08-12-debt24-master.md` 는 과거 기록이 아니라
 * **운영 중인 부채 장부 정본**인데 이 규칙에 함께 걸린다. 그 파일의 호출문은 2026-08-14 에
 * 손으로 고쳤고, **판별식은 그것을 지키지 못한다.** 디렉터리 규칙에 파일 하나짜리 구멍을
 * 내지 않는 쪽을 택했다 — 구멍은 유지 대상이 되고 다음 사람이 그 예외의 이유를 모른다.
 * 근본 처방(장부를 `docs/plans/` 밖으로 옮기거나 frontmatter 로 살아있음을 표시)은 별건이다.
 */
const EXCLUDE_PREFIXES = [
  'docs/plans/',
  'docs/specs/',
  'docs/decisions/',
  'docs/_archive/',
] as const

/** 명령이 실릴 수 있는 파일 형식. `.mjs`/`.js` 는 대상이 아니지만 **주석에 명령을 적는다**. */
const SCAN_EXTENSIONS = new Set([
  '.md',
  '.json',
  '.yml',
  '.yaml',
  '.sh',
  '.ts',
  '.mts',
  '.cts',
  '.tsx',
  '.mjs',
  '.js',
])

/** 타입 스트리핑을 켜는 플래그. 이 문자열이 세그먼트 안에 있으면 봉인된 것으로 본다. */
const FLAG = '--experimental-strip-types'

/**
 * `node` 가 **명령 자리**에 오는 지점.
 *
 * ## ★따옴표와 백틱을 빼면 안 된다
 *
 * 1차 초안은 선행 클래스를 `[\s;|&(]` 로 적었고 **`package.json` 의 두 호출문을
 * 통째로 놓쳤다.** JSON 은 명령을 `"…"` 안에 담고 마크다운은 `` `…` `` 안에 담는다.
 * 하필 가장 중요한 호출문(`test:workflow`)이 정확히 그 구멍에 있었다 — 판별식은 초록인데
 * 결함은 살아 있는, 이 저장소가 반복해온 형태다.
 *
 * ## 왜 `\b` 가 아니라 문자 클래스인가
 *
 * `\bnode\b` 는 `node_modules/.bin/…` 같은 **정상 경로**까지 잡는다. 뒤에 공백을 요구하면
 * `node_modules` 는 `node` 다음이 `_` 라 걸리지 않는다.
 */
const CMD_START = /(?:^|[\s;|&("'`])node(?=\s)/g

/**
 * 명령 세그먼트의 끝.
 *
 * 백틱을 포함하는 이유 — 주석·문서의 명령은 `` `…` `` 로 감싸이므로, 백틱에서 끊어야
 * 세그먼트가 뒤따르는 산문을 삼키지 않는다. 삼키면 허용목록 키가 산문 변경에 깨진다.
 *
 * ⚠️ 따옴표는 **넣지 않는다.** `node --experimental-strip-types --test 'scripts/**\/*.test.ts'`
 * 처럼 경로가 따옴표로 감싸인 정상 호출문이 첫 따옴표에서 잘려 `.ts` 를 잃고
 * **탐지에서 빠진다.**
 */
const SEGMENT_END = /[|;)`]|&&/

/**
 * `.ts` / `.mts` / `.cts` **경로** 참조. `.tsx` 는 대상이 아니다.
 *
 * ## ★확장자 앞에 이름을 요구하는 이유 — 한국어 산문 오탐
 *
 * 처음엔 `/\.[cm]?ts(?![a-zA-Z0-9])/` 로 적었고, 자기 파일 머리말을 위반으로 읽었다.
 *
 *   // node 로 .ts 를 부르는 호출문이 …
 *          ~~~~     ~~~
 *          명령 자리로 인식      맨 `.ts`
 *
 * 한국어는 `node` 뒤에 조사가 붙어 「node 로」·「node 가」가 자연스럽게 나오고, 그 뒤 어딘가에
 * 맨 `.ts` 가 있으면 **문장 전체가 명령으로 오인**된다. 실행문은 확장자 앞에 반드시 파일명이나
 * 글롭이 온다(`x.ts` · `*.test.ts` · `scripts/x.ts`) — 그것을 요구해 산문과 가른다.
 *
 * 뒤에 영숫자가 오면 다른 확장자(`.tsx`)이거나 파일명의 일부다.
 */
const TS_REFERENCE = /[\w*][\w*@./-]*\.[cm]?ts(?![a-zA-Z0-9])/

/**
 * 파서가 고장났을 때 아래 단언들이 공허하게 통과하는 것을 막는 하한.
 *
 * 이 저장소는 "0 이 나오면 판별식을 의심하라" 를 여러 번 겪었다.
 */
const MIN_SCANNED_FILES = 100

/** 처방 — 실패 메시지에 그대로 실어 막힌 사람이 바로 고치게 한다. */
const REMEDY =
  `처방. 호출문에 '${FLAG}' 를 붙인다.\n` +
  // 예시는 플래그를 **문자열 그대로** 적는다. `${FLAG}` 로 보간하면 소스에 플래그가 없어서
  // 이 판별식이 자기 처방문을 위반으로 읽는다(2026-08-14 첫 실행에서 실제로 그랬다).
  `      예) node --experimental-strip-types scripts/workflow/classify-task.ts --title "…"\n` +
  `      Node 22.18+ 는 타입 스트리핑이 기본 활성이라 이 플래그가 무동작이지만,\n` +
  `      22.6~22.17 에서도 같은 명령이 돌게 만드는 이식성 장치다. 지우지 말 것.`

/**
 * 「고치면 안 되는 위반」 — 명령이 아니라 **명령에 대한 서술**인 자리.
 *
 * 라인 번호로 잡지 않는다(줄이 밀리면 조용히 어긋난다). 아래 `tail` 을 `node ` 뒤에 이어붙인
 * 것이 스캐너가 실제로 뽑는 세그먼트와 **정확히** 같아야 하며, 그 줄이 바뀌면 매칭이 끊겨
 * `허용목록에 죽은 항목이 없다` 가 red 가 된다 — 사람이 다시 판단하게 만드는 장치다.
 *
 * ## ★왜 `node ` 를 떼어 저장하나
 *
 * 선행 `node ` 를 소스에 그대로 적으면 **이 판별식이 자기 허용목록을 위반으로 읽는다.**
 * 2026-08-14 첫 실행에서 실제로 그랬다. 붙여 놓으면 자기 자신을 위한 허용목록 항목이
 * 또 필요해지는 재귀가 생긴다 — 소스에 그 형태가 나타나지 않게 하는 쪽이 끊는 방법이다.
 */
const ALLOWED_MENTIONS = [
  {
    file: 'scripts/workflow/script-test-coverage.test.ts',
    tail: '--test scripts/workflow/*.test.ts',
    reason:
      '봉합 **전**의 명령을 과거형으로 서술한 주석("…였고"). 고치면 그 판별식이 왜 생겼는지가 사라진다.',
  },
  {
    file: 'scripts/workflow/script-test-coverage.test.ts',
    tail: "--test 'scripts/**\\/*.test.ts' 'scripts/**\\/*.test.mjs'",
    reason:
      'runnerPatterns() 의 **입력 형태를 예시하는 JSDoc**. 실행문이 아니다. (2026-08-14 리뷰 C4 정정 — 종전 사유는 「플래그가 붙으면 설명이 어긋난다」였으나, 이 PR 이 package.json 을 바꾼 지금 실제 입력에는 플래그가 있으므로 오히려 예시 쪽이 낡았다. 예시 갱신은 그 파일 소관이라 별건.)',
  },
  {
    file: 'scripts/workflow/script-test-coverage.test.ts',
    tail: "--test scripts/workflow/*.test.ts'",
    reason:
      '양성 대조군 **픽스처** — 봉합 전 test:workflow 의 정확한 형태를 재현해 매처가 누락을 잡는지 본다. (2026-08-14 리뷰 C4 정정 — 종전 사유 「고치면 그 테스트가 깨진다」는 **거짓**이었다. runnerPatterns() 가 `-` 로 시작하는 토큰을 버려 플래그를 붙여도 결과가 같음이 실측됐다. 손대지 않는 진짜 이유는 이것이 **당시 형태의 재현**이고, 고치면 그 대조군이 무엇을 재현하는지가 사라지기 때문이다.)',
  },
] as const

/** 허용목록 항목이 가리키는 실제 세그먼트. 저장은 `tail`, 비교는 복원해서 한다. */
function allowedCommand(entry: { tail: string }): string {
  return `node ${entry.tail}`
}

/** node 버전 정본. 로컬(nvm/mise)과 CI(`setup-node`)가 **같은 파일**을 읽게 만드는 자리. */
const VERSION_FILE = '.nvmrc'

/**
 * 타입 스트리핑이 **기본 활성**이 되는 하한.
 *
 * Node 22.18 부터 `.ts` 를 플래그 없이 실행한다. `.nvmrc` 를 이 아래로 내리면 축 B 의
 * 다른 단언(파일 존재 · 워크플로우가 그것을 읽음)이 전부 초록인 채 **전부 깨진다** —
 * 값 자체를 재지 않으면 봉인이 형식만 남는다.
 */
const STRIP_TYPES_DEFAULT_FLOOR = { major: 22, minor: 18 } as const

/**
 * `--experimental-strip-types` 플래그가 **도입된** 하한 (Node 22.6.0).
 *
 * `REMEDY` 는 「22.6~22.17 에서도 같은 명령이 돌게 만드는 이식성 장치」라고 약속한다.
 * 그런데 `package.json` 의 `engines.node` 가 그보다 낮으면 그 약속이 거짓이다 —
 * 22.0~22.5 에서는 **플래그 자체가 unknown option** 이라 붙여도 죽는다.
 * 2026-08-14 독립 리뷰 C6 — 「정본은 `.nvmrc` 하나」를 내건 PR 안에 두 번째 버전 선언이
 * 남아 있었고 **아무도 둘을 대조하지 않았다**. `two-lists-never-check-each-other` 가
 * 정본 단일화 PR 안에 남은 형태다.
 */
const FLAG_INTRODUCED_FLOOR = { major: 22, minor: 6 } as const

/** `major.minor[.patch]` 를 숫자 쌍으로. 못 읽으면 `null`. */
function parseVersion(raw: string): { major: number; minor: number } | null {
  const m = raw.trim().replace(/^v/, '').match(/^(\d+)\.(\d+)/)
  if (m === null) return null
  return { major: Number(m[1]), minor: Number(m[2]) }
}

/** a >= b 인가. */
function atLeast(a: { major: number; minor: number }, b: { major: number; minor: number }): boolean {
  return a.major > b.major || (a.major === b.major && a.minor >= b.minor)
}

/** CI 가 node 를 까는 액션. 이 스텝이 버전 정본을 안 읽으면 러너의 시스템 node 로 떨어진다. */
const SETUP_NODE_ACTION = 'actions/setup-node'

/** `setup-node` 스텝이 반드시 갖는 키. **부재를 금지하는 게 아니라 존재를 요구한다.** */
const VERSION_FILE_KEY = 'node-version-file'

/**
 * 이 판별식이 읽는 입력과, CI 트리거에서 그 입력을 덮는 경로 패턴.
 *
 * 읽기 경로와 트리거 요구를 **한 선언에서 파생**시킨다. 따로 두면 갈라지고, 빠진 쪽만
 * 바꾸는 PR 에서 이 판별식이 0회 실행된 채 통과한다.
 */
const INPUTS = {
  /** 버전 정본. **신규 입력이라 트리거에 없었다** — 이 판별식이 그것을 잡는다. */
  version: { file: VERSION_FILE, coveredBy: '.nvmrc' },
  /** 판별식 실행 명령의 정본. */
  pkg: { file: 'package.json', coveredBy: 'package.json' },
  /** 축 B 의 검사 대상. */
  ci: { file: '.github/workflows/workflow-scripts-ci.yml', coveredBy: '.github/workflows/**' },
  /** 축 A 가 지키는 호출문이 사는 곳. */
  start: { file: '.claude/skills/bts-start/SKILL.md', coveredBy: '.claude/skills/**' },
  /** 판별식 자신. 이 파일을 고치는 PR 에서도 CI 가 돌아야 한다. */
  self: { file: 'scripts/workflow/node-ts-invocation.test.ts', coveredBy: 'scripts/workflow/**' },
} as const

/** `on:` 아래에서 입력을 걸어야 하는 트리거. 한쪽만 걸면 봉인이 절반만 닫힌다. */
const CI_TRIGGERS = ['pull_request', 'push'] as const

const DISCRIMINANT_WORKFLOW = '.github/workflows/workflow-scripts-ci.yml'

/** 한 건의 호출문과 그 출처. 실패 메시지가 어느 파일 어디인지 바로 가리키게 한다. */
interface Invocation {
  file: string
  line: number
  command: string
  flagged: boolean
}

/**
 * 스캔 대상 파일 전부. **열거형 화이트리스트로 두지 않는다.**
 *
 * 이 결함의 재발 경로가 「새 파일이 새 호출문을 들고 들어오는 것」이라 열거는 원리적으로
 * 못 잡는다. 그래서 훑고 빼는 방식이다.
 *
 * 훑기를 공유 헬퍼로 뽑지 않은 것은 의도다(2026-08-14 Maxi 결정) — 형제 판별식이 전부
 * 자기 훑기를 갖고 있고, **강제 장치끼리 결합하면 공유 모듈 버그 1개가 전장을 눈멀게 한다.**
 */
/**
 * [dir] 이 링크된 git worktree 의 루트인가.
 *
 * worktree 는 `.git` 을 **파일**로 갖는다(본체 저장소는 디렉터리). 그 성질로 거른다.
 *
 * ★이름 목록([EXCLUDE_DIRS])에 경로를 더 적지 않는 이유. 이 저장소에 worktree 위치 규약이
 * **둘**이다 — `.worktrees/`(CLAUDE.md §핵심 패턴)과 `EnterWorktree` 의 `.claude/worktrees/`.
 * 이름을 세면 규약이 하나 늘 때마다 목록이 뒤처지고, 그 뒤처짐은 「main 에서 push 가 막힌다」로만
 * 드러난다(2026-09-02 실측 · 위반 78건 전부 남의 worktree 안, main 트리 0건).
 * 성질로 거르면 규약이 몇 개가 되든 함께 걷힌다.
 *
 * @param dir 검사할 디렉터리 절대 경로.
 * @return `.git` 이 파일로 존재하면 true.
 */
function isLinkedWorktree(dir: string): boolean {
  const dotGit = path.join(dir, '.git')
  return fs.existsSync(dotGit) && fs.statSync(dotGit).isFile()
}

function scanTargets(dir: string = REPO_ROOT, acc: string[] = [], root: string = REPO_ROOT): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (EXCLUDE_DIRS.has(entry.name)) continue
    const full = path.join(dir, entry.name)
    // worktree 는 node_modules 등을 심볼릭으로 갖는다. 따라가면 main 트리를 두 번 세거나 순환한다.
    if (entry.isSymbolicLink()) continue
    if (entry.isDirectory()) {
      // 남의 worktree 는 이 저장소의 사본이다. 훑으면 같은 파일을 두 번 세고,
      // 그 안의 docs/plans 옛 기록이 EXCLUDE_PREFIXES 면제를 못 받아 위반으로 잡힌다.
      if (isLinkedWorktree(full)) continue
      scanTargets(full, acc, root)
      continue
    }
    if (!SCAN_EXTENSIONS.has(path.extname(entry.name))) continue
    const rel = path.relative(root, full)
    if (EXCLUDE_PREFIXES.some((p) => rel.startsWith(p))) continue
    acc.push(rel)
  }
  return acc
}

/**
 * ★**논리 줄**로 되돌린다 — 줄끝 `\` 로 이어진 명령을 한 줄로 합친다.
 *
 * 물리 줄 단위로 보면 다음이 **양쪽 다 빠져나간다.**
 *
 *   node \            ← `.ts` 가 없다
 *     scripts/x.ts    ← `node` 가 없다
 *
 * 저장소에 이어지는 호출문이 이미 둘 있다(`bts-start/SKILL.md` 의 classify 호출,
 * `backend-ci.yml` 의 select-backend-modules 호출). 지금은 `node` 와 `.ts` 가 우연히 같은
 * 줄이라 잡혔을 뿐이고, 줄바꿈 위치가 한 칸만 달랐어도 통째로 놓쳤다.
 * YAML 블록 스칼라(`run: |`) 안의 여러 줄 명령도 이걸로 함께 덮인다.
 *
 * @returns `{ text, line }` — line 은 논리 줄이 **시작된** 물리 줄 번호(1-based)
 */
function logicalLines(source: string): { text: string; line: number }[] {
  const out: { text: string; line: number }[] = []
  const physical = source.split('\n')
  let buffer: string | null = null
  let startLine = 0

  physical.forEach((raw, i) => {
    const continues = /\\\s*$/.test(raw)
    const body = raw.replace(/\\\s*$/, ' ')
    if (buffer === null) {
      buffer = body
      startLine = i + 1
    } else {
      buffer += body
    }
    if (!continues) {
      out.push({ text: buffer, line: startLine })
      buffer = null
    }
  })

  if (buffer !== null) out.push({ text: buffer, line: startLine })
  return out
}

/** 한 논리 줄에서 `node …` 명령 세그먼트를 전부 뽑는다. */
function nodeSegments(text: string): string[] {
  const found: string[] = []
  CMD_START.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = CMD_START.exec(text)) !== null) {
    const start = match.index + match[0].indexOf('node')
    const rest = text.slice(start)
    // 'node' 4글자 뒤부터 종료 문자를 찾는다 — 명령 자신의 첫 글자에서 끊기지 않게.
    const tail = rest.slice(4)
    const end = SEGMENT_END.exec(tail)
    found.push((end ? rest.slice(0, 4 + end.index) : rest).trim())
  }
  return found
}

/**
 * ★플래그가 **스크립트 경로 앞**에 있는지 본다 — 뒤에 있으면 봉인이 아니다.
 *
 * node 는 실행 파일 경로 **이전**의 인자만 자기 옵션으로 읽고, 그 뒤는 스크립트의 argv 로
 * 넘긴다. 따라서 플래그가 경로 뒤에 오면 22.18 미만에서 **그대로 죽는다.**
 *
 *   ‹실행› ‹경로›.ts ‹플래그› --title "x"
 *          └ 여기서 이미 .ts 로드 시도 → ERR_UNKNOWN_FILE_EXTENSION
 *
 * 세그먼트 전체에 `includes(FLAG)` 를 걸면 이 명령이 **통과한다** — 이 판별식이 막겠다고
 * 선언한 바로 그 죽음이다(2026-08-14 독립 리뷰 C1 실측). 덤으로 두 갈래가 함께 닫힌다.
 *
 *   ‹실행› ‹경로›.ts   # ‹플래그› 미부착      ← 줄끝 주석의 언급
 *   run: ‹실행› ‹경로›.ts  # TODO ‹플래그›    ← 할 일 메모
 *
 * 셋 다 플래그가 `.ts` **뒤**에 있으므로 판정 구간에 들지 않는다. 주석 제거 휴리스틱을
 * 따로 두지 않고 **CLI 의 실제 의미와 같은 규칙 하나**로 한 번에 막는다.
 *
 * ★위 예시에 실제 명령 문자열을 쓰지 않고 `‹›` 로 가린 것은 의도다 — 위반 형태를 문서에
 *   그대로 적으면 이 판별식이 자기 설명문을 위반으로 읽는다. 실물 형태는 아래 양성
 *   대조군(`flagAfterPath`)이 조립해서 갖고 있고, **거기가 판정되는 자리**다.
 */
function isSealed(command: string): boolean {
  const pathAt = command.search(TS_REFERENCE)
  // 호출자가 TS_REFERENCE 매칭을 이미 확인하므로 -1 이 올 수 없다. 방어적으로 미봉인 처리.
  if (pathAt < 0) return false
  return command.slice(0, pathAt).includes(FLAG)
}

/** 저장소 전체에서 `node … <파일>.ts` 형태의 호출문을 전부 모은다. */
function collectInvocations(): Invocation[] {
  const rows: Invocation[] = []
  for (const file of scanTargets()) {
    const source = fs.readFileSync(path.join(REPO_ROOT, file), 'utf8')
    for (const { text, line } of logicalLines(source)) {
      for (const command of nodeSegments(text)) {
        if (!TS_REFERENCE.test(command)) continue
        rows.push({ file, line, command, flagged: isSealed(command) })
      }
    }
  }
  return rows
}

/** 워크플로우 안의 한 `setup-node` 스텝. */
interface SetupNodeStep {
  file: string
  line: number
  /** 이 스텝의 본문 — `uses:` 부터 다음 스텝 직전까지. */
  body: string
}

/**
 * `setup-node` 를 쓰는 스텝을 **실행 줄에 앵커를 걸어** 뽑는다.
 *
 * ## ★부분 문자열 매칭은 배선을 못 잰다
 *
 * 파일 전체에서 `node-version-file` 을 찾는 방식으로 적으면 **머리말 주석 한 줄이 대신
 * 만족**시켜, `setup-node` 스텝을 통째로 지워도 초록이 된다. 이 저장소는 정확히 그 형태를
 * 겪었다(2026-08-13 — `:modules:app` 을 파일 전체에서 찾아 조립 부팅 잡을 지워도 통과).
 *
 * 그래서 `uses: actions/setup-node` 가 실린 줄을 시작점으로 잡고, **그 스텝의 본문 안에서만**
 * 키를 본다. 스텝의 끝은 같은 들여쓰기의 다음 `- ` 항목이다.
 */
function setupNodeSteps(file: string): SetupNodeStep[] {
  const lines = fs.readFileSync(path.join(REPO_ROOT, file), 'utf8').split('\n')
  const steps: SetupNodeStep[] = []

  lines.forEach((line, i) => {
    if (!new RegExp(`uses:\\s*${SETUP_NODE_ACTION}`).test(line)) return

    // 이 스텝이 시작된 `- ` 의 들여쓰기를 위로 거슬러 찾는다.
    let start = i
    while (start > 0 && !/^\s*-\s/.test(lines[start])) start -= 1
    const indent = (lines[start].match(/^\s*/) ?? [''])[0].length

    // 다음 스텝(같은 들여쓰기의 `- `) 직전까지가 이 스텝의 본문이다.
    let end = start + 1
    while (end < lines.length) {
      const l = lines[end]
      const isNextStep = /^\s*-\s/.test(l) && (l.match(/^\s*/) ?? [''])[0].length <= indent
      // 들여쓰기가 스텝보다 얕은 비-공백 줄이면 잡 자체가 끝난 것이다.
      const leftBlock =
        l.trim().length > 0 && (l.match(/^\s*/) ?? [''])[0].length < indent
      if (isNextStep || leftBlock) break
      end += 1
    }

    steps.push({ file, line: start + 1, body: lines.slice(start, end).join('\n') })
  })

  return steps
}

/** 워크플로우 파일 경로 전부. */
function workflowFiles(): string[] {
  const dir = path.join(REPO_ROOT, '.github/workflows')
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))
    .map((f) => path.join('.github/workflows', f))
}

/** 저장소의 모든 워크플로우에서 `setup-node` 스텝을 모은다. */
function allSetupNodeSteps(): SetupNodeStep[] {
  return workflowFiles().flatMap((f) => setupNodeSteps(f))
}

/**
 * ★YAML 주석을 걷어낸 본문 — **키가 실제로 배선됐는지**만 남긴다.
 *
 * 걷지 않으면 스텝 본문 안 주석 한 줄이 요구를 대신 만족시킨다. 실측(2026-08-14 독립 리뷰 C2).
 *
 *   with:
 *     # TODO: node-version-file 로 바꿔야 한다     ← 이 줄이 요구를 만족시켰다
 *     node-version: 22                             ← 부유가 그대로 살아 있는데 초록
 *
 * 이 파일은 `setupNodeSteps()` 주석에서 「부분 문자열 매칭은 배선을 못 잰다」를 ★로 적고
 * **파일 전체** 검색을 스텝 본문 앵커로 좁혀 해결했다고 서술했다. 그 앵커는 파일 전체
 * 갈래만 닫았고 **본문 안** 갈래는 열려 있었다 — `invariant-satisfied-by-helptext-not-logic`
 * 과 같은 양식이며, 「해결했다」고 적힌 채 남는 것이 가장 나쁘다(learnings 2026-07-15).
 */
function executableBody(step: SetupNodeStep): string {
  return step.body
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n')
}

/**
 * `setup-node` 를 **가져야 하는** 워크플로우와, 갖지 **않아야 하는** 워크플로우.
 *
 * ## 왜 추론이 아니라 명시 집합인가
 *
 * 종전 비-공허 짝은 `steps.length > 0` 뿐이라 **스텝을 통째로 지워도** 통과했다(C3 실측).
 * 그 잡은 러너의 시스템 node 로 떨어지고 그 버전은 아무도 재지 않는다.
 *
 * 「`run:` 에서 node 를 부르는 잡은 setup-node 를 갖는다」는 추론 규칙을 먼저 시도했고
 * **오탐이 났다** — `- name: 러너 엔진 점검 (node · java)` 같은 **산문**이 실행으로 읽혔다.
 * 한국어 산문 오탐(`TS_REFERENCE` 주석 참조)과 같은 자리다. 실행 여부를 문자열로 추론하는
 * 것은 이 저장소에서 반복해 실패했다.
 *
 * 더 나쁜 것은 그 규칙이 `runner-health.yml` 에 setup-node 를 **요구**했다는 점이다.
 * 그 워크플로우는 의도적으로 순수 shell 이다 — JavaScript 액션은 node 로 돌아가므로,
 * node 가 죽었을 때 setup-node 를 쓰면 **점검 자신이 먼저 죽어** 결함을 못 본다.
 * 2026-08-04 사흘간 CI 0회 실행 사고가 정확히 그 사각에서 났다. 가드가 다른 가드의
 * 존재 이유를 거스르게 만들 뻔했다.
 *
 * 그래서 **집합을 선언하고 실측과 대조**한다(이 저장소의 `INPUTS`/`coveredBy` 양식).
 * 스텝을 지우면 집합이 어긋나 red · 새 워크플로우가 node 를 쓰면 여기 등재해야 red 가 풀린다.
 * 하드코딩 **숫자**는 두지 않는다 — 숫자는 세 번째 목록이 되어 또 갈린다.
 */
const NODE_WORKFLOWS: Record<string, string> = {
  'workflow-scripts-ci.yml': '판별식 전량을 node 로 돌린다',
  'frontend-ci.yml': 'lint · typecheck · test 3잡이 각각 node 를 쓴다',
  'backend-ci.yml': '변경 파일 → 대상 모듈 선별을 node 로 한다',
}

/**
 * setup-node 를 **갖지 않아야** 하는 워크플로우와 그 이유.
 *
 * 빈 값이 아니라 사유를 요구한다 — 「왜 없는가」가 사라지면 다음 사람이 「빠뜨렸다」로 읽고 넣는다.
 *
 * ★2026-08-21 — `runner-health.yml` 항목을 지웠다. 그 워크플로우가 삭제됐기 때문이다
 *   (CI 자동 실행 중단 + 자체호스팅 러너 관리 장치 제거). 위 산문의 「가드가 다른 가드의
 *   존재 이유를 거스를 뻔했다」는 교훈 자체는 유효하므로 문서로 남긴다.
 */
const NODE_FREE_WORKFLOWS: Record<string, string> = {
  'infra-ci.yml': 'node 를 쓰지 않는다.',
}

/** `coveredBy` 글롭이 실제로 그 입력 경로를 덮는지. 짝을 잘못 적은 선언을 잡는다. */
function globCovers(glob: string, file: string): boolean {
  if (glob === file) return true
  if (!glob.endsWith('/**')) return false
  const base = glob.slice(0, -3)
  return file === base || file.startsWith(`${base}/`)
}

/** 워크플로우의 `on.<trigger>.paths` 블록 원문. 트리거별로 갈라야 절반 봉인을 잡는다. */
function triggerBlock(ci: string, trigger: string): string {
  const bounds: Record<string, [string, string]> = {
    pull_request: ['pull_request:', 'push:'],
    push: ['push:', 'concurrency:'],
  }
  const [from, to] = bounds[trigger]
  return ci.slice(ci.indexOf(from), ci.indexOf(to))
}

/** 허용목록에 걸리는가. 파일과 **명령 문자열이 둘 다** 맞아야 한다. */
function isAllowed(row: Invocation): boolean {
  return ALLOWED_MENTIONS.some((a) => a.file === row.file && allowedCommand(a) === row.command)
}

describe('node 로 .ts 를 부르는 호출문 — 타입 스트리핑 플래그 봉인 (축 A)', () => {
  /**
   * 비-공허 짝.
   *
   * 훑기가 0건을 뽑거나 정규식이 아무것도 못 잡으면 아래 단언이 전부 공허하게 통과한다.
   * 대조군(플래그를 이미 가진 호출문)까지 따로 센다 — 그것이 0 이면 `FLAG` 문자열이
   * 바뀌었거나 세그먼트 추출이 짧게 끊긴 것이다.
   */
  test('입력을 실제로 읽었다 (비-공허 짝)', () => {
    const files = scanTargets()
    assert.ok(
      files.length >= MIN_SCANNED_FILES,
      `스캔 대상이 ${files.length}건뿐이다 — 훑기가 고장났거나 제외 규칙이 과하다.\n` +
        `0 에 가까우면 아래 '플래그 없는 호출문이 없다' 가 검사할 것 없이 통과한다.`,
    )

    const rows = collectInvocations()
    assert.ok(
      rows.length > 0,
      `'node … .ts' 호출문을 0건 뽑았다 — CMD_START 나 TS_REFERENCE 가 고장났다.`,
    )

    const flagged = rows.filter((r) => r.flagged)
    assert.ok(
      flagged.length > 0,
      `이미 봉인된 호출문(대조군)이 0건이다 — FLAG 문자열이 바뀌었거나 세그먼트가 짧게 끊긴다.\n` +
        `대조군이 0 이면 '플래그 있음' 판정이 한 번도 참이 된 적 없다는 뜻이다.`,
    )
  })

  /**
   * ★링크된 worktree 안은 훑지 않는다 — 위치 규약과 무관하게.
   *
   * 2026-09-02 실측. main 체크아웃에서 `git push` 가 막혔다. 위반 78건이 전부
   * `.claude/worktrees/<name>/docs/plans/` 안의 **옛 기록**이었고 main 트리에는 0건이었다.
   * [EXCLUDE_DIRS] 는 `.worktrees` 라는 **이름**만 알고, `EnterWorktree` 가 쓰는
   * `.claude/worktrees/` 는 모른다. [EXCLUDE_PREFIXES] 의 `docs/plans/` 면제도 worktree
   * 경로가 앞에 붙어 안 걸린다. 규약이 둘인데 목록이 하나라 생긴
   * two-lists-never-check-each-other 다.
   *
   * 그래서 **이름을 세지 않는다.** 링크된 worktree 는 `.git` 을 디렉터리가 아니라
   * **파일**로 갖는다 — 그 성질로 거르면 규약이 몇 개가 되든 함께 걷힌다.
   *
   * 비-공허 짝 — 같은 트리의 일반 파일이 결과에 실제로 담기는지 함께 센다. 훑기가 통째로
   * 0건이면 「worktree 가 없다」도 공허하게 참이 된다.
   */
  test('★링크된 worktree 안은 훑지 않는다 (이름이 아니라 .git 파일로 판별)', () => {
    // ★픽스처 본문에 호출문을 적지 않는다. 이 가드는 `.ts` 도 훑으므로 여기 리터럴을 적으면
    // **가드가 자기 픽스처를 위반으로 잡는다**(실제로 한 번 밟았다). 이 판정이 보는 것은
    // 훑기의 대상 목록이지 파일 내용이 아니라 본문은 아무래도 좋다.
    const FIXTURE_BODY = '(fixture)\n'
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-scan-'))
    try {
      // 규약 둘을 모두 재현한다 — 이름을 세는 처방이면 둘 중 하나는 반드시 샌다.
      for (const wt of ['.worktrees/old-style', '.claude/worktrees/new-style']) {
        fs.mkdirSync(path.join(tmp, wt, 'docs/plans'), { recursive: true })
        // worktree 의 서명 — `.git` 이 파일이다.
        fs.writeFileSync(path.join(tmp, wt, '.git'), 'gitdir: /somewhere/.git/worktrees/x\n')
        fs.writeFileSync(path.join(tmp, wt, 'docs/plans/old-record.md'), FIXTURE_BODY)
      }
      // 대조군 — 일반 파일. 이것이 안 담기면 위 단언이 공허하다.
      fs.mkdirSync(path.join(tmp, 'scripts'), { recursive: true })
      fs.writeFileSync(path.join(tmp, 'scripts/live.md'), FIXTURE_BODY)

      const scanned = scanTargets(tmp, [], tmp)

      assert.ok(
        scanned.includes(path.join('scripts', 'live.md')),
        `대조군을 못 담았다 — 훑기가 고장났다. scanned=${JSON.stringify(scanned)}`,
      )
      const leaked = scanned.filter((f) => f.includes('worktrees'))
      assert.deepEqual(
        leaked,
        [],
        `worktree 안의 파일이 훑기에 실렸다. 그 안의 옛 기록이 위반으로 잡혀 main 에서 push 가 막힌다.\n` +
          leaked.join('\n'),
      )
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  /**
   * ★ 이 파일의 존재 이유.
   *
   * 새 호출문이 플래그 없이 들어오면 여기서 죽는다. 실패 메시지에 파일·줄·명령 원문을
   * 그대로 실어 막힌 사람이 검색 없이 고치게 한다.
   */
  test('★플래그 없이 .ts 를 부르는 호출문이 없다', () => {
    const offending = collectInvocations()
      .filter((r) => !r.flagged && !isAllowed(r))
      .map((r) => `${r.file}:${r.line}\n    ${r.command}`)

    assert.deepEqual(
      offending,
      [],
      `타입 스트리핑 플래그가 없는 호출문이 있다.\n${offending.join('\n')}\n\n` +
        `이 호출문은 Node 22.18 **미만**에서 ERR_UNKNOWN_FILE_EXTENSION 으로 즉사한다.\n` +
        `CI 는 최신 22.x 라 초록이므로 로컬에서만 죽고, 그 빨강을 사람이 정상으로 학습한다.\n` +
        REMEDY,
    )
  })

  /**
   * 죽은 예외를 남기지 않는다.
   *
   * 허용목록은 「고치면 안 되는 위반」을 담는데, 그 줄이 바뀌면 매칭이 끊긴다. 그때 항목을
   * 조용히 남겨두면 **다음에 같은 문자열이 진짜 위반으로 나타나도 통과**한다.
   * 끊긴 순간 red 로 만들어 사람이 다시 판단하게 한다.
   */
  test('허용목록에 죽은 항목이 없다', () => {
    const rows = collectInvocations()
    const dead = ALLOWED_MENTIONS.filter(
      (a) => !rows.some((r) => r.file === a.file && r.command === allowedCommand(a)),
    ).map((a) => `${a.file}\n    ${allowedCommand(a)}\n    사유. ${a.reason}`)

    assert.deepEqual(
      dead,
      [],
      `허용목록 항목이 더 이상 실재하지 않는다.\n${dead.join('\n')}\n\n` +
        `해당 줄이 바뀌었거나 지워졌다. 항목을 지우거나, 바뀐 문자열로 갱신하되 **사유가 여전히\n` +
        `유효한지 다시 판단할 것.** 죽은 예외를 남기면 같은 문자열의 진짜 위반이 통과한다.`,
    )
  })

  /**
   * 양성 대조군.
   *
   * 위 단언이 초록인 이유가 "호출문이 봉인돼서" 인지 "탐지 로직이 죽어서" 인지 구분한다.
   * 아래 `잡아야 함` 목록은 **전부 실제로 뚫렸거나 뚫릴 뻔한 형태**다.
   */
  test('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    // ★픽스처를 조립해서 만든다 — 소스에 `node <경로>.ts` 형태를 통째로 적으면
    //   **이 판별식이 자기 대조군을 진짜 위반으로 읽는다.** 2026-08-14 첫 실행에서 그랬다.
    //   `ALLOWED_MENTIONS` 가 선행 `node ` 를 떼어 저장하는 것과 같은 처방이다.
    const N = 'node'

    const mustCatch: [string, string][] = [
      ['줄머리', `${N} scripts/x.ts`],
      // ★1차 초안이 정확히 이걸 놓쳤다. package.json 의 두 호출문이 여기 있었다.
      ['JSON 값(따옴표)', `    "classify": "${N} scripts/workflow/classify-task.ts",`],
      ['마크다운 인라인(백틱)', `실행은 \`${N} scripts/x.ts\` 로 한다`],
      ['따옴표 글롭', `"test:workflow": "${N} --test 'scripts/**/*.test.ts'"`],
      ['파이프', `git diff --name-only | ${N} scripts/workflow/detect-tier.ts`],
      ['명령 치환', `classify=$(${N} scripts/workflow/classify-task.ts --cache)`],
      ['.mts', `${N} scripts/x.mts`],
    ]

    for (const [label, line] of mustCatch) {
      const segments = nodeSegments(logicalLines(line)[0].text)
      assert.ok(
        segments.some((s) => TS_REFERENCE.test(s) && !isSealed(s)),
        `위반을 놓쳤다 (${label}): ${line}`,
      )
    }

    // ★★플래그가 **경로 뒤**에 오는 형태. node 는 경로 이후를 argv 로 넘기므로 이 명령은
    //   플래그를 달고도 22.18 미만에서 죽는다. 세그먼트 전체 includes 로 판정하면 통과한다.
    //   2026-08-14 독립 리뷰 C1 — 이 판별식이 막겠다고 선언한 죽음을 스스로 통과시켰다.
    const flagAfterPath: [string, string][] = [
      ['플래그가 경로 뒤', `${N} scripts/workflow/classify-task.ts ${FLAG} --title "x"`],
      ['줄끝 주석의 언급', `${N} scripts/x.ts   # ${FLAG} 미부착`],
      ['할 일 메모', `run: ${N} scripts/workflow/detect-tier.ts  # TODO ${FLAG} 붙일 것`],
    ]
    for (const [label, line] of flagAfterPath) {
      const segments = nodeSegments(logicalLines(line)[0].text)
      assert.ok(
        segments.some((s) => TS_REFERENCE.test(s) && !isSealed(s)),
        `플래그가 경로 뒤인데 봉인으로 읽었다 (${label}): ${line}\n` +
          `node 옵션은 스크립트 경로 **앞**에서만 유효하다. 뒤는 argv 로 넘어가 무효다.`,
      )
    }

    // 반대 방향 — 경로 앞이면 봉인이 맞다.
    assert.equal(
      isSealed(`${N} ${FLAG} scripts/x.ts --title "x"`),
      true,
      '경로 앞의 플래그를 봉인으로 못 읽었다 — 정상 호출문이 전부 red 가 된다.',
    )

    // ★★줄바꿈으로 이어진 호출문. 물리 줄 단위면 1행엔 .ts 가 없고 2행엔 node 가 없어
    //   **양쪽 다 빠져나간다.** logicalLines() 가 이 구멍을 막는다.
    const continued = [`${N} \\`, '  scripts/workflow/classify-task.ts --cache'].join('\n')
    const joined = logicalLines(continued)
    assert.equal(joined.length, 1, `줄 이어붙이기가 안 됐다 — ${joined.length}줄로 쪼개졌다.`)
    assert.ok(
      nodeSegments(joined[0].text).some((s) => TS_REFERENCE.test(s) && !s.includes(FLAG)),
      '줄바꿈으로 이어진 위반을 놓쳤다 — 이것이 R3-a 가 막는 구멍이다.',
    )

    // 오탐 대조. 정상 명령을 위반으로 읽으면 사람이 판별식을 끄게 된다.
    const mustNotCatch: [string, string][] = [
      ['이미 봉인됨', `${N} ${FLAG} scripts/x.ts`],
      ['.mjs 는 대상 아님', `${N} scripts/build-doc-index.mjs --check`],
      ['node_modules 경로', 'node_modules/.bin/lint-staged'],
      ['.tsx 는 대상 아님', `${N} apps/web/src/routes/__root.tsx`],
      ['산문 언급', 'classify-task.ts 는 입력을 타입으로 분류한다'],
      ['확장자 아님', `${N} scripts/x.tsx-helper`],
      // 한국어 조사가 붙는 자리. TS_REFERENCE 가 확장자 앞 이름을 요구하지 않으면 여기서 샌다.
      ['한국어 산문', `${N} 로 .ts 를 부르는 호출문을 전부 봉인한다`],
    ]

    for (const [label, line] of mustNotCatch) {
      const segments = nodeSegments(logicalLines(line)[0].text)
      assert.equal(
        segments.some((s) => TS_REFERENCE.test(s) && !s.includes(FLAG)),
        false,
        `정상 명령을 위반으로 읽었다 (${label}): ${line}`,
      )
    }
  })
})

describe('node 버전 정본 단일화 — 로컬과 CI 가 같은 node 를 쓴다 (축 B)', () => {
  /**
   * 비-공허 짝.
   *
   * `setup-node` 스텝을 0건 뽑으면 아래 배선 단언이 전부 공허하게 통과한다.
   * 파서가 YAML 구조 변화에 깨지는 경로가 실재하므로 하한을 못박는다.
   */
  test('setup-node 스텝을 실제로 찾았다 (비-공허 짝)', () => {
    const steps = allSetupNodeSteps()
    assert.ok(
      steps.length > 0,
      `.github/workflows 에서 '${SETUP_NODE_ACTION}' 스텝을 0건 뽑았다.\n` +
        `파서가 고장났거나 워크플로우 형식이 바뀌었다. 0 이면 아래 단언이 검사할 것 없이 통과한다.`,
    )

    // 본문을 못 자르면(빈 문자열) 키 검사가 통째로 무의미해진다.
    const empty = steps.filter((s) => s.body.trim().length === 0).map((s) => `${s.file}:${s.line}`)
    assert.deepEqual(empty, [], `본문을 못 자른 스텝이 있다: ${empty.join(', ')}`)

    // ★파서가 **일부만** 잡는 것을 막는다. `> 0` 만 보면 5곳 중 1곳만 잡혀도 통과하고,
    //   나머지 4곳이 정본을 안 읽어도 초록이 된다. 원시 문자열 수를 **다른 로직으로** 세어
    //   대조한다 — 한쪽이 깨지면 어긋난다.
    const rawCount = workflowFiles().reduce((n, f) => {
      const hits = fs
        .readFileSync(path.join(REPO_ROOT, f), 'utf8')
        .split('\n')
        .filter((l) => !/^\s*#/.test(l))
        .filter((l) => new RegExp(`uses:\\s*${SETUP_NODE_ACTION}`).test(l))
      return n + hits.length
    }, 0)

    assert.equal(
      steps.length,
      rawCount,
      `스텝 파서가 ${steps.length}건을 뽑았는데 원시 매칭은 ${rawCount}건이다.\n\n` +
        `두 수가 어긋나면 파서가 일부를 놓쳤거나(→ 놓친 스텝은 검사되지 않는다) ` +
        `주석을 스텝으로 오인한 것이다. 둘 다 봉인이 조용히 좁아지는 경로다.`,
    )
  })

  /**
   * ★★C3 — 스텝을 통째로 지우면 위 단언들은 검사할 것이 없어 전부 통과한다.
   *
   * 그 잡은 러너 PATH 의 시스템 node 로 떨어지고, 그 버전은 아무도 재지 않는다 —
   * 이 판별식이 닫으려던 결함 그 자체다. 개수 하한(`>= 5`)으로도 막히지만 그 숫자가
   * **세 번째 목록**이 되어 또 갈린다. 「node 를 쓰면 setup-node 를 갖는다」로 숫자를 없앤다.
   */
  test('★★setup-node 보유 집합이 선언과 일치한다 (스텝 삭제 봉인)', () => {
    const declared = Object.keys(NODE_WORKFLOWS).sort()
    const declaredFree = Object.keys(NODE_FREE_WORKFLOWS).sort()

    // ① 저장소의 워크플로우가 두 선언 중 정확히 한쪽에 있다 — 미분류를 만들지 않는다.
    const actual = workflowFiles().map((f) => path.basename(f)).sort()
    const unclassified = actual.filter(
      (f) => !(f in NODE_WORKFLOWS) && !(f in NODE_FREE_WORKFLOWS),
    )
    assert.deepEqual(
      unclassified,
      [],
      `어느 집합에도 없는 워크플로우가 있다: ${unclassified.join(', ')}\n\n` +
        `node 를 쓰면 NODE_WORKFLOWS 에, 안 쓰면 사유와 함께 NODE_FREE_WORKFLOWS 에 등재하라.\n` +
        `미분류를 허용하면 새 워크플로우가 버전 정본을 무시해도 아무도 안 본다.`,
    )

    const stale = [...declared, ...declaredFree].filter((f) => !actual.includes(f))
    assert.deepEqual(stale, [], `선언에만 있고 실재하지 않는 워크플로우: ${stale.join(', ')}`)

    // ② 가져야 하는 곳이 실제로 갖고 있다 — 스텝을 통째로 지우면 여기서 잡힌다.
    const withStep = new Set(allSetupNodeSteps().map((s) => path.basename(s.file)))
    const missing = declared.filter((f) => !withStep.has(f))
    assert.deepEqual(
      missing,
      [],
      `setup-node 를 가져야 하는데 없는 워크플로우: ${missing.join(', ')}\n` +
        missing.map((f) => `  ${f} — ${NODE_WORKFLOWS[f]}`).join('\n') +
        `\n\n스텝이 사라지면 그 잡은 러너 PATH 의 시스템 node 로 떨어진다. 그 버전은\n` +
        `'${VERSION_FILE}' 와 무관하게 움직이고 아무도 재지 않는다 — 이 판별식이 닫으려던 결함이다.`,
    )

    // ③ 갖지 않아야 하는 곳에 들어오지 않았다. runner-health 는 들어오는 순간 무력화된다.
    const intruded = declaredFree.filter((f) => withStep.has(f))
    assert.deepEqual(
      intruded,
      [],
      `setup-node 가 없어야 하는 워크플로우에 들어왔다: ${intruded.join(', ')}\n` +
        intruded.map((f) => `  ${f} — ${NODE_FREE_WORKFLOWS[f]}`).join('\n'),
    )
  })

  /**
   * ★★ 축 B 의 본체 — **존재 기준**.
   *
   * 「`node-version:` 하드코딩 금지」로 적으면 버전 키를 **아예 안 적은** 스텝이 통과한다.
   * 그 스텝은 러너의 시스템 node 를 쓰고, 그 버전은 아무도 재지 않으므로
   * **이번 결함(로컬↔CI 불일치)이 그대로 재발한다.** 없는 것을 금지하지 말고 있어야 할 것을
   * 요구한다 — 2026-08-14 eng review 교정(R4-a).
   */
  test('★★모든 setup-node 스텝이 버전 정본 파일을 읽는다', () => {
    const offending = allSetupNodeSteps()
      .filter((s) => {
        const body = executableBody(s)
        // 주석을 걷은 본문에서만 본다. 그리고 하드코딩 키가 **함께 있으면** 그것도 위반이다 —
        // setup-node 는 두 키가 공존하면 `node-version` 을 우선해 정본을 무시한다.
        const readsFile = new RegExp(`${VERSION_FILE_KEY}\\s*:`).test(body)
        const pinsInline = /^\s*node-version\s*:/m.test(body)
        return !readsFile || pinsInline
      })
      .map((s) => {
        const pinned = executableBody(s).match(/^\s*node-version\s*:\s*\S+/m)
        return `${s.file}:${s.line}${pinned ? `  (지금. ${pinned[0].trim()})` : '  (버전 키 자체가 없다)'}`
      })

    assert.deepEqual(
      offending,
      [],
      `버전 정본을 읽지 않는 setup-node 스텝이 있다.\n${offending.join('\n')}\n\n` +
        `'node-version: 22' 는 최신 22.x 로 **부유**한다. 로컬이 22.14 인데 CI 가 22.23 이면\n` +
        `타입 스트리핑 기본 활성 여부가 갈려 같은 명령이 두 환경에서 다른 것을 실행한다 —\n` +
        `2026-08-14 실측으로 로컬 62/20 · CI 초록이 구조적으로 고정돼 있었다(부채 매핑 27).\n` +
        `처방. 'node-version:' 을 지우고 '${VERSION_FILE_KEY}: ${VERSION_FILE}' 를 쓴다.`,
    )
  })

  /**
   * ★ 값 자체를 잰다.
   *
   * 파일이 있고 워크플로우가 그것을 읽어도, 값이 22.18 미만이면 전부 깨진다.
   * 형식만 봉인하고 값을 안 재면 그 봉인은 장식이다.
   */
  test('★버전 정본이 실재하고 타입 스트리핑 하한을 넘는다', () => {
    const full = path.join(REPO_ROOT, VERSION_FILE)
    assert.ok(
      fs.existsSync(full),
      `${VERSION_FILE} 가 없다.\n\n` +
        `이 파일이 로컬(nvm/mise)과 CI(setup-node)가 공유하는 유일한 버전 정본이다.\n` +
        `없으면 두 환경이 각자 다른 node 를 고르고, 그 차이는 아무도 재지 않는다.`,
    )

    const raw = fs.readFileSync(full, 'utf8').trim().replace(/^v/, '')
    const parts = raw.split('.').map((n) => Number(n))
    assert.ok(
      parts.length >= 2 && parts.every((n) => Number.isInteger(n) && n >= 0),
      `${VERSION_FILE} 의 값을 못 읽었다: '${raw}'\n` +
        `구체 버전이어야 한다(예: 22.23.2). 'lts/*' 같은 별칭은 두 환경에서 다른 값으로 풀린다.`,
    )

    const [major, minor] = parts
    const ok =
      major > STRIP_TYPES_DEFAULT_FLOOR.major ||
      (major === STRIP_TYPES_DEFAULT_FLOOR.major && minor >= STRIP_TYPES_DEFAULT_FLOOR.minor)

    assert.ok(
      ok,
      `${VERSION_FILE} 가 ${raw} 인데 타입 스트리핑 기본 활성 하한은 ` +
        `${STRIP_TYPES_DEFAULT_FLOOR.major}.${STRIP_TYPES_DEFAULT_FLOOR.minor} 이다.\n\n` +
        `이 아래로 내리면 위 두 단언(파일 존재 · 워크플로우가 읽음)은 초록인 채\n` +
        `'.ts' 실행이 전부 ERR_UNKNOWN_FILE_EXTENSION 으로 죽는다 — 봉인이 형식만 남는다.`,
    )
  })

  /**
   * ★두 버전 선언이 서로를 본다 (2026-08-14 독립 리뷰 C6).
   *
   * `.nvmrc` 를 정본으로 세워도 `package.json` 의 `engines.node` 가 그대로 남아 **두 번째
   * 선언**이 된다. 둘을 대조하지 않으면 정본 단일화 PR 안에 `two-lists-never-check-each-other`
   * 가 남는 셈이다. 구체적으로 두 가지가 어긋날 수 있다.
   *
   *   1. `engines` 하한이 플래그 도입(22.6)보다 낮으면, 그 범위에서는 이 판별식이 붙이라고
   *      강제하는 플래그가 **unknown option 으로 죽는다** — REMEDY 의 약속이 거짓이 된다.
   *   2. `.nvmrc` 가 `engines` 를 만족하지 않으면 두 선언이 정면으로 모순이다.
   */
  test('★engines 하한과 버전 정본이 서로 모순되지 않는다', () => {
    const pkg = JSON.parse(
      fs.readFileSync(path.join(REPO_ROOT, INPUTS.pkg.file), 'utf8'),
    ) as { engines?: { node?: string } }
    const range = pkg.engines?.node

    assert.ok(
      typeof range === 'string' && range.length > 0,
      `${INPUTS.pkg.file} 에 engines.node 가 없다 — 아래 대조가 공허해진다.`,
    )

    const floor = parseVersion(range.replace(/^[^\d]*/, ''))
    assert.ok(floor !== null, `engines.node 하한을 못 읽었다: '${range}'`)

    assert.ok(
      atLeast(floor, FLAG_INTRODUCED_FLOOR),
      `engines.node 가 '${range}' 인데 '${FLAG}' 는 ` +
        `${FLAG_INTRODUCED_FLOOR.major}.${FLAG_INTRODUCED_FLOOR.minor} 에서 도입됐다.\n\n` +
        `그 아래 버전에서는 이 판별식이 붙이라고 강제하는 플래그 자체가 unknown option 으로\n` +
        `죽는다 — 봉인이 약속하는 이식 범위를 engines 가 보장하지 않는 상태다.`,
    )

    const pinned = parseVersion(fs.readFileSync(path.join(REPO_ROOT, VERSION_FILE), 'utf8'))
    assert.ok(pinned !== null, `${VERSION_FILE} 값을 못 읽었다.`)
    assert.ok(
      atLeast(pinned, floor),
      `${VERSION_FILE}(${pinned.major}.${pinned.minor})가 engines.node('${range}')를 만족하지 않는다.\n` +
        `두 버전 선언이 정면으로 모순이다 — 어느 쪽을 믿어야 하는지 알 수 없다.`,
    )
  })

  // ── 축 B 젠킨스 포팅 (2026-09-09 · P4) ──────────────────────────────────
  //
  // ★지키려던 것은 `setup-node` 스텝이 아니라 **「node 버전 리터럴을 두 곳에 두지 않는다」**다.
  //   정본은 `.nvmrc` 하나이고, 그것이 갈리면 타입 스트리핑 기본 활성 여부가 달라져
  //   판별식이 조용히 0줄 실행된다(로컬 22.14 · 러너 22.23 — 이 파일 머리말의 그 사고).
  //
  // 젠킨스에서 그 보장을 지키는 자리는 셋이다. 하나라도 빠지면 리터럴이 두 벌이 된다.
  //   ① `bootstrap.sh`  `.nvmrc` 를 읽어 이미지 빌드 ARG 로 주입한다
  //   ② `Dockerfile`    버전 리터럴이 없다 (ARG 로만 받는다)
  //   ③ `Jenkinsfile`   이미지의 node 와 `.nvmrc` 가 어긋나면 빌드를 죽인다
  //
  // ★③ 이 필요한 이유. 이미지는 한번 구우면 굳는다. `.nvmrc` 가 나중에 바뀌어도 이미지는
  //   따라오지 않으므로, ①②만으로는 **빌드 시점의 정합**만 보장된다. 실행 시점 drift 는
  //   ③ 이 잡는다 — Dockerfile 이 「여기서 막을 수 없다」고 적어 둔 그 몫이다.
  test('★★젠킨스도 node 버전을 정본에서만 읽는다 (리터럴 두 벌 금지)', () => {
    const read = (rel: string) => fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
    const boot = read('infra/jenkins/bootstrap.sh')
    const dockerfile = read('infra/jenkins/Dockerfile')
    const jenkinsfile = read('Jenkinsfile')

    // ① 정본을 읽어 주입한다
    assert.match(
      boot,
      new RegExp(`${VERSION_FILE.replace('.', '\\.')}`),
      `bootstrap.sh 가 ${VERSION_FILE} 를 읽지 않는다 — 버전을 손으로 넘기게 되고 그것이 두 번째 목록이다.`,
    )
    assert.match(boot, /NODE_VERSION/, 'bootstrap.sh 가 NODE_VERSION 을 주입하지 않는다')

    // ② 이미지에 리터럴이 없다. `22.23.2` 같은 숫자가 박히면 정본이 둘이 된다.
    assert.match(dockerfile, /ARG NODE_VERSION/, 'Dockerfile 이 NODE_VERSION 을 ARG 로 받지 않는다')
    const literal = dockerfile.match(/^\s*(?:ENV|ARG)\s+NODE_VERSION\s*=\s*["']?\d+\.\d+/m)
    assert.equal(
      literal,
      null,
      `Dockerfile 에 node 버전 리터럴이 박혀 있다: ${literal?.[0]?.trim()}\n` +
        `  정본은 ${VERSION_FILE} 하나다. bootstrap.sh 가 읽어 넘긴다.`,
    )

    // ③ 실행 시점 drift 를 죽인다
    assert.match(
      jenkinsfile,
      new RegExp(`${VERSION_FILE.replace('.', '\\.')}`),
      `Jenkinsfile 이 ${VERSION_FILE} 를 읽지 않는다 — 이미지가 굳어도 아무도 모른다.`,
    )
    assert.match(
      jenkinsfile,
      /node -v/,
      'Jenkinsfile 이 실제 node 버전을 재지 않는다 — 정본과 대조할 값이 없다.',
    )
  })

  test('★판정기가 리터럴 박힌 Dockerfile 을 실제로 잡는다 (합성 뮤테이션)', () => {
    // 위 단언이 스쳐도 통과하는 형태가 아닌지 본다.
    const bad = 'FROM jenkins/jenkins:lts-jdk21\nARG NODE_VERSION=22.23.2\nRUN echo hi\n'
    assert.match(
      bad,
      /^\s*(?:ENV|ARG)\s+NODE_VERSION\s*=\s*["']?\d+\.\d+/m,
      '리터럴 기본값이 박힌 형태를 못 잡는다 — 정본이 둘인 상태가 초록이 된다',
    )
  })

  test('선언한 coveredBy 패턴이 실제로 그 입력을 덮는다', () => {
    const mismatched = Object.entries(INPUTS)
      .filter(([, input]) => !globCovers(input.coveredBy, input.file))
      .map(([key, i]) => `${key}. '${i.coveredBy}' 가 '${i.file}' 를 덮지 않는다`)

    assert.deepEqual(
      mismatched,
      [],
      `INPUTS 의 짝 선언이 틀렸다.\n${mismatched.join('\n')}\n\n` +
        `짝을 잘못 적으면 엉뚱한 경로를 요구하면서 통과한다 — 트리거는 초록인데 정작 ` +
        `입력을 바꾸는 PR 에서 판별식이 안 돈다.`,
    )
  })
  // ★2026-08-21 — 「내 입력이 CI 트리거 paths 에 있는가」 단언을 여기서 지웠다.
  //   CI 자동 실행을 껐고, 판별식은 이제 `.husky/pre-push` 가 **조건 없이 전량** 돌린다.
  //   그 무조건성은 `scripts/workflow/discriminant-hook-wiring.test.ts` 가 강제한다.
  //   경로 짝맞춤 목록이 필요 없어졌으므로 보장은 유지되고 유지비만 사라진다.
})
