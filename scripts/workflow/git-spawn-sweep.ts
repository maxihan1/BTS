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

/** 러너 글롭의 정본이 들어 있는 `package.json` 스크립트 이름. */
const RUNNER_SCRIPT = 'test:workflow'

/**
 * 판별식 러너의 명령 한 줄. 글롭도 플래그도 여기서만 나온다.
 *
 * @returns `package.json` 이 적어 둔 명령. 그 스크립트가 없으면 빈 문자열
 */
function runnerCommand(): string {
  const packageJson = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'package.json'), 'utf-8')) as {
    scripts?: Record<string, string>
  }
  return packageJson.scripts?.[RUNNER_SCRIPT] ?? ''
}

/**
 * 판별식 러너가 실제로 실행하는 글롭 전량.
 *
 * 확장자를 손으로 적으면 「러너가 도는 것」과 「스윕이 훑는 것」이 두 벌이 되고, 둘은
 * 서로를 검사하지 않는다. 그 어긋남의 손실은 **대칭**이라 양방향 차집합이 원리적으로
 * 못 본다 — 러너만 도는 확장자에 사는 git 스포너는 spawners 와 importers 에서 **동시에**
 * 빠져 `빈집합 == 빈집합` 이 된다. 실제로 `.mjs` 를 빼면 전량이 초록이었다.
 *
 * @returns 따옴표를 턴 글롭 목록. 명령이 비면 빈 목록이고, 그러면 파생 집합이
 *   비어 판별식의 비-공허 짝이 red 가 된다
 */
export function runnerGlobs(): string[] {
  return [...runnerCommand().matchAll(/'([^']*\*[^']*)'/g)].map((hit) => hit[1] ?? '')
}

/**
 * 판별식 러너가 쓰는 실행 플래그 전량. 글롭과 같은 한 줄에서 뽑는다.
 *
 * 원본을 자식으로 돌려 재는 판정이 이것을 쓴다. 플래그를 따로 적으면 러너와 두 벌이 되고,
 * 러너 쪽만 바뀌면 자식이 원본을 아예 못 돈다 — 그 실패는 자식의 종료 코드로 드러난다.
 *
 * @returns `--` 로 시작하는 토큰 목록
 */
export function runnerFlags(): string[] {
  return runnerCommand()
    .split(/\s+/)
    .filter((token) => token.startsWith('--'))
}

/** 글롭 조각을 자리표로 바꿀 때 쓰는 문자. 소스에 나올 수 없는 것을 고른다. */
const GLOB_PLACEHOLDER = '\u0000'

/**
 * 글롭 하나를 경로 정규식으로 바꾼다. `**` 는 디렉터리 여러 겹, `*` 는 한 겹 안이다.
 *
 * @param glob 러너 글롭
 * @returns 저장소 상대 경로에 맞춰 볼 정규식
 */
function globToRegExp(glob: string): RegExp {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
  const source = escaped
    .replaceAll('**/', GLOB_PLACEHOLDER)
    .replaceAll('*', '[^/]*')
    .replaceAll(GLOB_PLACEHOLDER, '(?:[^/]+/)*')
  return new RegExp('^' + source + '$')
}

/**
 * 러너가 그 파일 하나만 직접 실행할 수 있는 경로인가.
 *
 * @param rel 저장소 상대 경로
 * @returns 러너 글롭에 걸리면 true
 */
export function matchesRunnerGlob(rel: string): boolean {
  return runnerGlobs().some((glob) => globToRegExp(glob).test(rel))
}

/** 파생 집합이 훑는 소스 확장자. 러너 글롭에서 뽑으므로 러너와 목록이 하나다. */
const SOURCE_EXTENSIONS = [...new Set(runnerGlobs().map((glob) => path.extname(glob)))]

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

/**
 * 정규식 안에 넣어도 제 뜻대로 읽히도록 특수문자를 막는다.
 *
 * @param text 원문
 * @returns 이스케이프된 텍스트
 */
function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 스크럽 헬퍼를 임포트하는 자리. 모듈 지정자로만 판정한다 — 이름을 바꿔 달아도 걸린다.
 *
 * 파일 이름은 `HELPER_MODULE` 한 자리에서 뽑는다. 두 곳에 적으면 헬퍼를 옮길 때 한쪽만
 * 따라가고, 그러면 임포트 집합이 통째로 비어 양방향 차집합이 `빈집합 == 빈집합` 이 된다.
 */
const HELPER_IMPORT = new RegExp(`\\bfrom\\s*(['"])[^'"]*${escapeForRegExp(path.basename(HELPER_MODULE))}\\1`)

/**
 * 어떤 텍스트가 파생 집합 술어를 하나라도 만족하는가.
 *
 * 살아남은 주석이 위험한 까닭은 그것이 **이 술어들을 그대로 만족시키기** 때문이다.
 * 주석에 적어 둔 임포트 한 줄이 배선 없이 임포트 집합에 들고, 호출 예시 한 줄이
 * spawn 집합을 부풀린다. 판별식이 술어를 제 손으로 다시 적으면 두 벌이 되고,
 * 스윕이 형태를 넓힐 때 판별식 쪽만 낡아 「막았다」가 거짓이 된다.
 *
 * @param text 재어 볼 텍스트
 * @returns 술어를 하나라도 만족하면 true
 */
export function satisfiesWiringPredicate(text: string): boolean {
  return GIT_SPAWN.test(text) || HELPER_IMPORT.test(text)
}

/** 직전 유의 토큰이 식을 끝냈는가를 가리는 마지막 문자. 이 뒤의 `/` 는 나눗셈이다. */
const EXPRESSION_END = /[A-Za-z0-9_$)\]]/

/** 식별자를 이룰 수 있는 글자. 낱말의 경계를 뒤로 훑을 때 쓴다. */
const WORD_CHAR = /[A-Za-z0-9_$]/

/**
 * 뒤에 오는 `/` 가 **정규식을 여는** 예약어.
 *
 * 마지막 글자만 보면 `return` 의 `n` 이 식별자 끝과 구별되지 않아 식이 끝난 것으로 읽히고,
 * 그 `/` 가 나눗셈이 된다. 그러면 리터럴 본문이 코드 자리에서 스캔돼 따옴표는 유령 문자열을,
 * 슬래시는 안 닫히는 블록 주석을 연다 — 앞은 주석 생존, 뒤는 파일이 파생 집합에서 통째로
 * 빠지는 대칭 실명이다. 게이트 2 라운드 3 의 적대적 렌즈가 둘 다 실측으로 심어 보였다.
 *
 * **값**인 예약어(`this` · `super` · `true` · `false` · `null`)는 여기 없다 — 그 뒤의 `/` 는
 * 나눗셈이다. 넣으면 진짜 나눗셈이 정규식으로 읽혀 반대 방향으로 뚫린다.
 *
 * 이것은 언어가 정하는 **닫힌 집합**이지 이 저장소가 유지하는 목록이 아니다. 그래도 원소가
 * 빠지면 green 쪽으로 뚫리므로, 판별식이 **원소마다** 실제로 행동을 바꾸는지 값으로 잰다.
 */
const KEYWORDS_BEFORE_REGEX = new Set([
  'await',
  'case',
  'delete',
  'do',
  'else',
  'in',
  'instanceof',
  'new',
  'of',
  'return',
  'throw',
  'typeof',
  'void',
  'yield',
])

/** 예약어 집합을 판별식이 값으로 훑을 수 있게 내보낸다. */
export function keywordsBeforeRegex(): string[] {
  return [...KEYWORDS_BEFORE_REGEX].sort()
}

/** 코드 자리. 템플릿 보간(`${…}`)은 코드 자리를 다시 열므로 제 상태를 따로 갖는다. */
interface CodeContext {
  kind: 'code'
  /** 이 자리에서 연 중괄호 깊이. 0 에서 만난 `}` 는 보간의 끝이다 */
  braces: number
  /** 직전 유의 토큰이 식을 끝냈는가 */
  expressionEnded: boolean
}

/** 스캐너가 서 있는 자리. 중첩 템플릿이 실재하므로 스택으로 쌓는다. */
type ScanContext = CodeContext | { kind: 'template' } | { kind: 'quote'; mark: string }

/** 한 걸음 — 내보낼 텍스트와 이번 걸음이 소비한 마지막 위치. */
interface Step {
  text: string
  next: number
}

/**
 * 정규식 리터럴이 닫히는 자리. 문자 클래스 안의 `/` 는 종결자가 아니다.
 *
 * 줄 안에서 안 닫히면 -1 을 돌려준다 — 정규식 리터럴은 줄을 넘지 못하므로 그 `/` 는
 * 나눗셈이었다는 뜻이다. 되돌릴 길을 남겨야 오판이 소스를 먹지 않는다.
 *
 * @param src 소스
 * @param start 여는 `/` 위치
 * @returns 닫는 `/` 위치. 줄 안에서 안 닫히면 -1
 */
function endOfRegex(src: string, start: number): number {
  let inClass = false
  for (let i = start + 1; i < src.length; i += 1) {
    const c = src[i]
    if (c === '\n') return -1
    if (c === '\\') {
      i += 1
      continue
    }
    if (c === '[') inClass = true
    else if (c === ']') inClass = false
    else if (c === '/' && !inClass) return i
  }
  return -1
}

/**
 * 리터럴이 닫혔음을 바깥 코드 자리에 알린다. 그 뒤의 `/` 는 나눗셈이다.
 *
 * @param stack 스캐너 상태 스택
 */
function markExpressionEnded(stack: ScanContext[]): void {
  const outer = stack[stack.length - 1]
  if (outer !== undefined && outer.kind === 'code') outer.expressionEnded = true
}

/**
 * 따옴표 문자열 안에서 한 글자를 옮긴다.
 *
 * @param src 소스
 * @param i 현재 위치
 * @param stack 스캐너 상태 스택
 * @param mark 열려 있는 따옴표
 * @returns 한 걸음
 */
function stepInsideQuote(src: string, i: number, stack: ScanContext[], mark: string): Step {
  const c = src[i] ?? ''
  if (c === '\\') return { text: c + (src[i + 1] ?? ''), next: i + 1 }
  if (c === mark) {
    stack.pop()
    markExpressionEnded(stack)
  }
  return { text: c, next: i }
}

/**
 * 템플릿 리터럴 안에서 한 글자를 옮긴다. `${` 는 코드 자리를 다시 연다.
 *
 * 보간 안을 문자열로 흘려보내면 그 안의 닫는 백틱이 짝을 어긋내고, 그 뒤 파일 전체가
 * 유령 문자열이 된다 — 실측에서 `${x.map(...)}` 하나가 파일 나머지의 주석을 다 살렸다.
 *
 * @param src 소스
 * @param i 현재 위치
 * @param stack 스캐너 상태 스택
 * @returns 한 걸음
 */
function stepInsideTemplate(src: string, i: number, stack: ScanContext[]): Step {
  const c = src[i] ?? ''
  if (c === '\\') return { text: c + (src[i + 1] ?? ''), next: i + 1 }
  if (c === '`') {
    stack.pop()
    markExpressionEnded(stack)
    return { text: c, next: i }
  }
  if (c === '$' && src[i + 1] === '{') {
    stack.push({ kind: 'code', braces: 0, expressionEnded: false })
    return { text: '${', next: i + 1 }
  }
  return { text: c, next: i }
}

/**
 * 코드 자리에서 문자열·템플릿 리터럴을 연다.
 *
 * @param c 현재 글자
 * @param i 현재 위치
 * @param stack 스캐너 상태 스택
 * @returns 리터럴을 열었으면 한 걸음. 아니면 null
 */
function stepOpenLiteral(c: string, i: number, stack: ScanContext[]): Step | null {
  if (c === "'" || c === '"') {
    stack.push({ kind: 'quote', mark: c })
    return { text: c, next: i }
  }
  if (c === '`') {
    stack.push({ kind: 'template' })
    return { text: c, next: i }
  }
  return null
}

/**
 * 코드 자리에서 주석을 걷는다. 줄 주석은 줄 끝까지, 블록 주석은 닫는 자리까지.
 *
 * @param src 소스
 * @param i 현재 위치
 * @returns 주석이면 한 걸음. 아니면 null
 */
function stepStripComment(src: string, i: number): Step | null {
  if (src[i] !== '/') return null
  if (src[i + 1] === '/') {
    const newline = src.indexOf('\n', i)
    return { text: '\n', next: newline === -1 ? src.length : newline }
  }
  if (src[i + 1] === '*') {
    const closing = src.indexOf('*/', i + 2)
    const stop = closing === -1 ? src.length : closing + 2
    // 줄바꿈만 남긴다 — 호출부 파생 집합이 **원본 줄번호**를 실패 메시지에 실어야 한다.
    // 통째로 지우면 JSDoc 뒤의 호출이 전부 위로 밀려 file:line 이 엉뚱한 자리를 가리킨다.
    return { text: src.slice(i, stop).replace(/[^\n]/g, ''), next: stop - 1 }
  }
  return null
}

/**
 * 이 글자가 식을 끝냈는가. 끝냈으면 바로 뒤의 `/` 는 나눗셈이다.
 *
 * 마지막 **한 글자**만 보면 후위 증감(`i++`)이 `+` 로 읽혀 식이 안 끝난 것이 된다.
 * 그러면 그 뒤의 `/` 가 정규식을 열고, 같은 줄의 주석 여는 자리를 종결자로 먹는다 —
 * 주석이 통째로 살아남아 임포트·호출 예시가 파생 집합에 든다. 실제로 그렇게 뚫렸다.
 * 앞 글자까지 보는 형태는 후위 증감뿐이라 두 글자만 본다.
 *
 * @param src 소스
 * @param i 현재 위치
 * @param c 현재 글자
 * @returns 식을 끝냈으면 true
 */
function endsExpression(src: string, i: number, c: string): boolean {
  if ((c === '+' || c === '-') && src[i - 1] === c) return true
  if (!EXPRESSION_END.test(c)) return false
  if (!WORD_CHAR.test(c)) return true
  return !KEYWORDS_BEFORE_REGEX.has(wordEndingAt(src, i))
}

/**
 * 이 위치에서 끝나는 낱말. 예약어인지 보려면 마지막 글자가 아니라 낱말 전체가 필요하다.
 *
 * @param src 소스
 * @param i 낱말의 마지막 글자 위치
 * @returns 낱말
 */
function wordEndingAt(src: string, i: number): string {
  let start = i
  while (start > 0 && WORD_CHAR.test(src[start - 1] ?? '')) start -= 1
  return src.slice(start, i + 1)
}

/**
 * 코드 자리에서 한 글자를 옮긴다. 주석은 여기서만 걷힌다.
 *
 * @param src 소스
 * @param i 현재 위치
 * @param stack 스캐너 상태 스택
 * @param top 지금 서 있는 코드 자리
 * @returns 한 걸음
 */
function stepInsideCode(src: string, i: number, stack: ScanContext[], top: CodeContext): Step {
  const c = src[i] ?? ''
  const literal = stepOpenLiteral(c, i, stack)
  if (literal !== null) return literal
  const comment = stepStripComment(src, i)
  if (comment !== null) return comment
  if (c === '/' && !top.expressionEnded) {
    const end = endOfRegex(src, i)
    if (end !== -1) {
      top.expressionEnded = true
      return { text: src.slice(i, end + 1), next: end }
    }
  }
  if (c === '}' && top.braces === 0 && stack.length > 1) {
    stack.pop()
    return { text: c, next: i }
  }
  if (c === '{') top.braces += 1
  else if (c === '}') top.braces -= 1
  if (c.trim() !== '') top.expressionEnded = endsExpression(src, i, c)
  return { text: c, next: i }
}

/**
 * 주석을 걷어낸 소스. 재는 것은 「무엇이 적혀 있나」가 아니라 「무엇이 실행되나」다.
 *
 * 주석을 남기면 양쪽으로 뚫린다 — 설명문에 적어 둔 호출 예시가 파생 집합을 부풀리고,
 * 주석 처리된 임포트 한 줄이 배선 없이 대조를 만족시킨다.
 *
 * 그래서 문자열·템플릿·**정규식 리터럴**을 함께 따라간다. 정규식을 안 따라가면
 * `/['"]/` 의 따옴표가 유령 문자열을 열어 그 뒤가 주석까지 통째로 살아남고(green 쪽),
 * 슬래시가 든 리터럴은 닫히지 않은 블록 주석을 열어 파일 나머지를 지운다 — 그 파일은
 * 파생 집합 양쪽에서 동시에 빠져 차집합이 `빈집합 == 빈집합` 이 된다(대칭 실명).
 *
 * 「무엇이 실행되나」로 재고 있는지는 판별식의 전량 생존 판정이 잰다. 이 주석이 아니다.
 *
 * @param src 원본 소스
 * @returns 주석이 빠진 소스
 */
export function stripComments(src: string): string {
  const stack: ScanContext[] = [{ kind: 'code', braces: 0, expressionEnded: false }]
  let out = ''
  for (let i = 0; i < src.length; i += 1) {
    const top = stack[stack.length - 1]
    if (top === undefined) break
    const step =
      top.kind === 'quote'
        ? stepInsideQuote(src, i, stack, top.mark)
        : top.kind === 'template'
          ? stepInsideTemplate(src, i, stack)
          : stepInsideCode(src, i, stack, top)
    out += step.text
    i = step.next
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

/** 주석을 걷은 소스 한 벌. 파일 경로와 그 텍스트. */
export interface StrippedSource {
  /** 저장소 상대 경로 */
  file: string
  /** 주석이 빠진 소스 */
  code: string
}

/**
 * `scripts` 아래 소스 전량을 읽어 주석을 걷는다.
 *
 * 아래 파생 집합들이 전부 여기를 지난다 — 판별식이 「주석이 실제로 걷혔는가」를 잴 때
 * **판정이 소비하는 바로 그 텍스트**를 보게 하려는 것이다. 따로 읽어 따로 걷으면
 * 재는 텍스트와 판정하는 텍스트가 두 벌이 되고, 둘은 서로를 검사하지 않는다.
 *
 * @returns 정렬된 경로 순서의 소스 목록
 */
export function strippedSources(): StrippedSource[] {
  return scriptSources().map((file) => ({
    file,
    code: stripComments(fs.readFileSync(path.join(REPO_ROOT, file), 'utf-8')),
  }))
}

/**
 * git 을 부르는 파일과 **그것에 닿는 파일** 전량. 임포트를 따라 이어서 넓힌다.
 *
 * 자식으로 태울 대상이 여기서 나온다. spawn 하는 파일만 태우면 그것을 임포트해서 부르는
 * 파일이 아무 자식에도 안 실려 사각지대가 된다 — 실측에서 `push-backend-tests.ts` 가
 * 정확히 그 자리였다. 사람이 목록을 적으면 파일이 하나 늘 때마다 조용히 낡는다.
 *
 * 임포트는 모듈 지정자의 파일 이름으로 본다. 경로를 통째로 맞추면 상대 경로 표기가
 * 갈릴 때마다 사각지대가 다시 열린다.
 *
 * @returns 정렬된 저장소 상대 경로 전량
 */
export function deriveGitTouchingFiles(): string[] {
  const sources = strippedSources()
  const touching = new Set(deriveWiringSets().spawners)
  let grew = true
  while (grew) {
    grew = false
    for (const { file, code } of sources) {
      if (touching.has(file) || !importsAnyOf(file, code, touching)) continue
      touching.add(file)
      grew = true
    }
  }
  return [...touching].sort()
}

/**
 * 이 소스가 주어진 것 중 하나라도 임포트하는가.
 *
 * @param importer 이 소스의 저장소 상대 경로. 상대 지정자를 푸는 기준이다
 * @param code 주석이 걷힌 소스
 * @param targets 저장소 상대 경로들
 * @returns 하나라도 임포트하면 true
 */
export function importsAnyOf(importer: string, code: string, targets: Iterable<string>): boolean {
  for (const target of targets) if (code.includes(`./${path.basename(target)}`)) return true
  return false
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
  for (const { file, code } of strippedSources()) {
    if (file === HELPER_MODULE) continue
    if (GIT_SPAWN.test(code)) spawners.push(file)
    if (HELPER_IMPORT.test(code)) importers.push(file)
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
 * 호출 인자에서 **첫 최상위 객체 리터럴**을 잘라낸다 — 그것이 옵션 객체다.
 *
 * 「마지막」으로 잡으면 옵션 뒤에 객체가 하나 더 붙은 호출에서 그 뒤엣것이 옵션 행세를
 * 하고, 거기 적힌 `env` 가 판정을 대신 만족시킨다. spawn 계열의 인자는 명령·인자배열·
 * 옵션·콜백이라 객체 리터럴을 하나만 받는다 — 첫 번째가 옵션이고 뒤엣것은 옵션이 아니다.
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
  for (let i = openParen; i < code.length; i += 1) {
    const c = code[i] ?? ''
    if (c === "'" || c === '"' || c === '`') i = endOfString(code, i)
    else if (c === '(' || c === '[' || c === '{') {
      depth += 1
      if (c === '{' && depth === ARGUMENT_DEPTH + 1 && objectStart < 0) objectStart = i
    } else if (c === ')' || c === ']' || c === '}') {
      if (c === '}' && depth === ARGUMENT_DEPTH + 1 && objectStart >= 0) return code.slice(objectStart, i + 1)
      depth -= 1
      if (depth === 0) return null
    }
  }
  return null
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

/**
 * `env` 를 **제 값과 함께** 명시한 조각. `env: X` 와 축약형 `{ …, env }` 를 함께 인정한다.
 *
 * 값이 `process.env` 인 자리와 `undefined` 인 자리는 뺀다 — 앞은 GIT_* 를 통째로 물려주고,
 * 뒤는 Node 에서 `env` 를 아예 안 준 것과 같다. 둘 다 스크럽의 반대인데 키만 보면 통과한다.
 * 이름 둘을 빼는 것은 예외 목록이 아니다 — 여기 이름을 얹어 red 를 끌 수 있는 자리가 없다.
 */
const ENV_ENTRY = /^['"]?env['"]?\s*(?::\s*(?!process\.env\b|undefined\b)\S|$)/

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
  return strippedSources().flatMap(({ file, code }) => scanGitSpawnCallSites(file, code))
}

/**
 * 주석이 걷힌 소스 **하나**에서 git 을 spawn 하는 호출부를 모은다.
 *
 * 판별식이 합성 소스로 이 층위를 직접 재려고 갈라 둔 자리다. 소스를 못 넣으면 「어떤
 * 형태가 통과하는가」를 저장소에 실제로 그 형태를 심어야만 잴 수 있고, 그 순간 판정이
 * 제 미끼를 위반으로 읽는다.
 *
 * @param file 실패 메시지에 실을 이름
 * @param code 주석이 걷힌 소스
 * @returns 줄 순서대로 쌓인 호출부 목록
 */
export function scanGitSpawnCallSites(file: string, code: string): GitSpawnCallSite[] {
  const sites: GitSpawnCallSite[] = []
  const scanner = new RegExp(GIT_SPAWN_SOURCE, 'g')
  let hit: RegExpExecArray | null
  while ((hit = scanner.exec(code)) !== null) {
    const options = optionsObject(code, code.indexOf('(', hit.index))
    sites.push({
      file,
      line: lineNumberAt(code, hit.index),
      callee: hit[1] ?? '',
      hasEnv: options !== null && hasEnvKey(options),
      calleeSpansLines: hit[0].includes('\n'),
      optionsSpanLines: options !== null && options.includes('\n'),
    })
  }
  return sites
}
