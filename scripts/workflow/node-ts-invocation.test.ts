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
 * 1차 프로토타입은 선행 클래스를 `[\s;|&(]` 로 적었고 **`package.json` 의 두 호출문을
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
      'runnerPatterns() 가 받는 **입력 형태의 예시**. 실행문이 아니라 파서 설명이라 플래그가 붙으면 설명이 입력과 어긋난다.',
  },
  {
    file: 'scripts/workflow/script-test-coverage.test.ts',
    tail: "--test scripts/workflow/*.test.ts'",
    reason:
      '양성 대조군 **픽스처**. 좁은 훑기를 일부러 넣어 매처가 누락을 잡는지 본다 — 고치면 그 테스트가 깨진다.',
  },
] as const

/** 허용목록 항목이 가리키는 실제 세그먼트. 저장은 `tail`, 비교는 복원해서 한다. */
function allowedCommand(entry: { tail: string }): string {
  return `node ${entry.tail}`
}

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
function scanTargets(dir: string = REPO_ROOT, acc: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (EXCLUDE_DIRS.has(entry.name)) continue
    const full = path.join(dir, entry.name)
    // worktree 는 node_modules 등을 심볼릭으로 갖는다. 따라가면 main 트리를 두 번 세거나 순환한다.
    if (entry.isSymbolicLink()) continue
    if (entry.isDirectory()) {
      scanTargets(full, acc)
      continue
    }
    if (!SCAN_EXTENSIONS.has(path.extname(entry.name))) continue
    const rel = path.relative(REPO_ROOT, full)
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

/** 저장소 전체에서 `node … <파일>.ts` 형태의 호출문을 전부 모은다. */
function collectInvocations(): Invocation[] {
  const rows: Invocation[] = []
  for (const file of scanTargets()) {
    const source = fs.readFileSync(path.join(REPO_ROOT, file), 'utf8')
    for (const { text, line } of logicalLines(source)) {
      for (const command of nodeSegments(text)) {
        if (!TS_REFERENCE.test(command)) continue
        rows.push({ file, line, command, flagged: command.includes(FLAG) })
      }
    }
  }
  return rows
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
      // ★1차 프로토타입이 정확히 이걸 놓쳤다. package.json 의 두 호출문이 여기 있었다.
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
        segments.some((s) => TS_REFERENCE.test(s) && !s.includes(FLAG)),
        `위반을 놓쳤다 (${label}): ${line}`,
      )
    }

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
