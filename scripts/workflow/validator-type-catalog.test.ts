// SDD §7.3·§7.4 의 Validator/Post-function 표 ↔ 두 팩토리의 `when (type)` 분기 ↔ 규칙 구현체가
// 서로 어긋나지 않는지 강제하는 판별식
//
// ## 왜 이 파일이 있나
//
// 2026-08-25 실측 — SDD §7.3·§7.4 가 적어 둔 타입 **9행 중 7행**이 코드와 달랐다.
// 표는 `Permission` · `NotStatusCategory` · `SetField` … 라고 적었는데 팩토리가 실제로 받는
// 문자열은 `permission-check` · `not-status-category` · `SET_FIELD` … 였다.
// 표대로 YAML/DTO 를 쓰면 `IllegalArgumentException("지원하지 않는 validator type")` 이 난다.
//
// 「문서가 낡았다」가 아니다. **표가 거짓을 말하는데 아무도 그 거짓을 검사하지 않는다**가 문제다.
// 이 저장소가 이름 붙인 지배 결함 양식 `two-lists-never-check-each-other` 이다.
// 이 판별식이 아래 축마다 두 목록의 차집합을 **양방향으로** 0 으로 강제한다.
//
// ## 무엇을 대조하나 — 축 7개
//
// | 축 | 목록 ① | 목록 ② | 갈리면 무슨 일이 나나 |
// |---|---|---|---|
// | 1 타입 | 표 `타입 식별자` 열 | 팩토리 `when (type)` 분기 | 표대로 쓰면 「지원하지 않는 type」 예외 |
// | 2 config 키 | 표 `필수 config 키` 열 | 팩토리 `create*` 의 config 읽기 | 화면이 키를 손으로 들고, 틀리면 400 |
// | 3 구현 클래스 | 표 `구현 클래스` 열 | `override val type` 을 선언한 `.kt` | 개명·삭제해도 표는 초록으로 남는다 |
// | 4 인스턴스 type | 팩토리 `when (type)` 분기 | 구현체 `override val type` | 아래 「인스턴스 type 축」 참조 |
// | 5 읽기 형태 봉인 | 축 2 가 아는 config 읽기 헬퍼 | 팩토리가 실제로 부른 callee | 축 2 가 못 본 키는 문서화를 요구받지 못한다 |
// | 6 폼 스키마 | 표 `필수 config 키` 열 | 편집 화면의 type 별 config 선언 | 갈린 사실이 저장 시점 400 으로만 드러난다 |
// | 7 선언 형태 봉인 | 축 6 이 아는 선언 형태 | 편집 화면이 실제로 쓴 형태 | 축 6 이 못 본 키가 조용히 샌다 |
//
// **config 키 축이 필요한 이유.** 요청·응답의 `config` 는 `Map<String, Any?>` 로 그대로 왕복해서
// 타입별 필수 키가 계약 어디에도 없었다. 화면은 폼을 그리려고 그 키를 손으로 드는데, 틀리면 400 이다.
//
// **구현 클래스 축이 필요한 이유.** 표에 `구현 클래스` 열을 넣고 그 열을 **읽지 않으면** 열이 조용히
// 썩는다. 이 저장소가 `partial-column-parser-lets-unread-column-rot` 로 이름 붙인 양식이고,
// 판별식이 자기가 잡으려는 결함을 옆 열에서 재생산하는 꼴이다.
//
// **인스턴스 type 축이 필요한 이유.** `WorkflowEngine.plan` 이 전환 실패를
// `WorkflowValidatorFailureException(validator.type, …)` 으로 던진다 — API 응답에 실리는 값이
// 팩토리 분기 문자열이 아니라 **인스턴스 쪽 `override val type`** 이다. 두 벌이 갈리면 엔진은
// 전환 실패에서 type X 를 보고하는데 그 X 를 validator CRUD API 에 되돌려 쓰면 400 이다.
//
// **폼 스키마 축(6·7)이 필요한 이유.** 화면은 폼을 그리려면 타입별 키를 알아야 해서 표와 갈리는
// **두 번째 목록**을 가질 수밖에 없다. SDD §7.3 이 D6 을 향해 「이 열이 편집 화면 입력 폼의
// 계약이다」라고 미리 적어 둔 자리다. 종전의 이 주석은 `PostActionConfigSection.tsx` 가
// `CALL_WEBHOOK` 의 `url`/`method` 를 손으로 든 사실을 **결함으로 적어만 두고 재지 않았다** —
// 판별식이 자기가 이름 붙인 결함을 옆 표면에서 재생산한 자리였고, 축 6·7 이 그 자리를 닫는다.
//
// 두 표면의 선언 형태가 달라 파서는 두 벌이지만 축은 하나다. 한 표면에 둘 다 돌린 합집합을 쓴다.
// - **validator** — `apps/web/src/api/validators.ts` 의 `VALIDATOR_CONFIG_FORM_SCHEMAS` 객체 리터럴.
//   키 = 타입 식별자, `z.object({…})` 의 프로퍼티 = config 키, `.optional()` = 표의 `(선택)`.
// - **post-action** — 선언적 스키마가 **없다**. 뮤테이션 호출부의 `type: '리터럴'` 과 같은 객체
//   리터럴에 실린 `config: { … }` 를 읽는다. 그것이 백엔드로 나가는 실제 계약이기 때문이다.
//   이 표면은 `(선택)` 을 표현할 자리가 없어 파서가 전부 필수로 읽는다 — 표가 그 표면의 키를
//   `(선택)` 으로 적는 날 축 6 이 red 를 내고, 처방은 표가 아니라 **화면을 선언적 스키마로**
//   바꾸는 것이다.
//
// **축 6 만 정본 방향이 반대다.** 표는 축 1·2 로 이미 팩토리와 같음이 강제돼 있으므로, 화면이
// 표를 따라야 한다. 그래서 축 6 의 실패 메시지만 「화면을 고쳐라」로 적는다.
//
// **표 전량 ↔ 폼 전량 양방향 차집합은 성립하지 않는다.** 표의 모든 타입에 폼이 있는 것이 아니다
// (`CustomExpression` 은 편집 불가라 폼이 없고, post-action 은 `CALL_WEBHOOK` 만 폼이 있다).
// 그래서 축 6 은 **폼이 있는 타입으로 도메인을 좁혀** 그 타입의 키 집합만 표와 양방향으로 잰다.
//
// ## 왜 목록을 상수로 적지 않나
//
// 「있어야 할 type 목록」을 여기 적으면 그것이 **또 하나의 목록**이 되고, 판별식이 자기가 잡으려는
// 결함 양식을 스스로 재생산한다. 모든 집합을 런타임에 파일에서 읽는다 —
// 표는 `docs/sdd/07-workflow-engine.md`, 분기와 config 키는 팩토리 `.kt` 원문,
// 구현 클래스와 인스턴스 type 은 `validator/`·`postaction/` 패키지 `.kt` 원문이고,
// 폼 스키마는 `apps/web/src` 편집 화면 원문이다.
// 사람이 손으로 유지하는 type·키·클래스 목록은 이 파일에 **하나도 없다**.
//
// ## 어느 쪽이 정본인가
//
// **코드다** (Maxi 결정, 2026-08-25). 팩토리 `when` 분기가 런타임 계약이고 표는 그 서술이다.
// 그래서 실패 메시지는 「표를 코드에 맞춰라」로 적는다. **축 6 만 예외**다 — 그 축의 목록 ① 인
// 표는 축 1·2 로 이미 팩토리와 같음이 강제돼 있으므로, 화면이 표를 따르는 것이 곧 코드를 따르는
// 것이다. 그 축의 메시지만 「화면을 고쳐라」로 적는다.
//
// ## 표의 어느 열을 읽나
//
// 표는 4열(`타입 식별자` · `구현 클래스` · `필수 config 키` · `용도`)이고 파서는 앞 3열을 읽는다.
// 열은 위치가 아니라 **제목 접두사**로 찾는다 — 판별식과 표가 같은 열에서 만난다는 계약을 기계가
// 들고 있게 하려는 것이다. 위치로 찾으면 열을 하나 끼워 넣는 순간 엉뚱한 열을 읽고, 제목 전체를
// 못박으면 제목을 다듬는 순간 추출이 0건이 되어 「표가 틀렸다」가 아니라 「표를 못 읽었다」는
// 덜 쓸모 있는 red 로 바뀐다. 제목에서 접두사가 사라지면 추출이 0건이 되고, 그건 비-공허 짝이 잡는다.
//
// ## 왜 주석을 지우고 파싱하나
//
// 팩토리 KDoc 이 `config["field"] 필수` 처럼 **본문과 같은 토큰**을 적는다. KDoc 을 그대로 읽으면
// 옆 함수의 KDoc 이 이 함수의 키로 새어 들어온다. 그래서 원문에서 주석을 먼저 지우고
// (`stripComments` — 길이를 보존한다) `when` 분기 본문과 `create*` 함수 본문만 본다.
// KDoc 은 사람 손으로 유지되는 목록이라 **파서가 읽는 대상이 되어서는 안 된다**.
// 축 6·7 이 읽는 TS/TSX 도 같은 이유로 주석을 먼저 지운다 — JSDoc 이 `config['url']` 같은 토큰을
// 서술로 적으면 그 서술이 화면의 키로 새어 들어온다.
//
// ## 비-공허 짝이 왜 별도 테스트인가
//
// 파서 하나라도 0건을 뱉으면 그 차집합은 공허하게 0 이 된다. 그때 위 단언들은 **아무것도 안
// 지키면서 초록**이다. 그래서 뽑아낸 집합이 하나도 비어 있지 않음을 따로 단언한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SDD_FILE = path.join(REPO_ROOT, 'docs/sdd/07-workflow-engine.md')
const WORKFLOW_MAIN = path.join(
  REPO_ROOT,
  'backend/modules/project-workflow/src/main/kotlin/com/bts/workflow',
)
const ENGINE_DIR = path.join(WORKFLOW_MAIN, 'engine')
const VALIDATOR_FACTORY = path.join(ENGINE_DIR, 'DefaultWorkflowValidatorFactory.kt')
const POST_ACTION_FACTORY = path.join(ENGINE_DIR, 'DefaultWorkflowPostActionFactory.kt')
const VALIDATOR_DIR = path.join(WORKFLOW_MAIN, 'validator')
const POST_ACTION_DIR = path.join(WORKFLOW_MAIN, 'postaction')

/**
 * SDD 표에서 런타임 식별자가 실린 열을 찾는 제목 접두사. 판별식과 표가 이 문자열 하나로 만난다.
 *
 * 접두사인 이유는 위 「표의 어느 열을 읽나」 참조 — `타입` 도 `타입 식별자` 도 같은 열로 본다.
 * 나머지 열(`구현 클래스` · `필수 config 키` · `용도`)은 걸리지 않는다.
 */
const TYPE_COLUMN_PREFIX = '타입'

/** 구현 클래스명이 실린 열을 찾는 제목 접두사. */
const IMPL_COLUMN_PREFIX = '구현'

/** 타입별 config 키가 실린 열을 찾는 제목 접두사. */
const CONFIG_COLUMN_PREFIX = '필수 config'

/**
 * 한 칸에 키가 여럿일 때 쓰는 구분자. 저장소 문서 관례를 그대로 쓴다.
 * 예: `` `permission` · `scope`(선택) ``.
 */
const CONFIG_KEY_SEPARATOR = '·'

/**
 * 기본값이 있어 생략 가능한 키에 붙이는 표기.
 *
 * 표와 코드 양쪽을 이 표기로 정규화해서 비교한다 — `필수`/`선택` 구분까지 대조 대상이라
 * 필수 키를 선택으로 적어 두는 거짓말도 red 가 된다.
 */
const OPTIONAL_SUFFIX = '(선택)'

/** 표 칸에서 키 하나로 인정하는 형태. 식별자(+선택 표기) 가 아닌 토큰(`—` 등)은 버린다. */
const CONFIG_KEY_TOKEN = /^[A-Za-z_][A-Za-z0-9_]*(?:\(선택\))?$/

/** 팩토리에서 분기를 찾을 진입 표지. 두 팩토리 모두 `create(type, config)` 안에 이 형태로 있다. */
const WHEN_HEAD = 'when (type) {'

/** Kotlin 원문에서 문자열로 인정할 따옴표. 문자 리터럴(`'x'`)은 문자열이 아니므로 넣지 않는다. */
const KOTLIN_QUOTES: readonly string[] = ['"']

/**
 * 문자열 리터럴 **내부**를 같은 길이의 `_` 로 덮는다.
 *
 * 중괄호 짝을 셀 때 문자열 안의 `{` `}`(예: `"${'$'}{foo}"`)에 속지 않기 위한 것이다.
 * 길이를 보존하므로 여기서 얻은 인덱스를 **원문에 그대로** 쓸 수 있다.
 *
 * @param quotes 문자열로 볼 따옴표. 언어마다 다르므로 호출자가 준다.
 */
function maskStringLiterals(source: string, quotes: readonly string[] = KOTLIN_QUOTES): string {
  const out = source.split('')
  let open = ''
  for (let i = 0; i < out.length; i += 1) {
    const ch = out[i]
    if (open !== '' && ch === '\\') {
      // 이스케이프된 다음 글자도 함께 덮는다. 끝을 넘겨 쓰면 길이가 늘어 인덱스 보존이 깨지므로 막는다.
      if (i + 1 < out.length) out[i + 1] = '_'
      i += 1
      continue
    }
    if (open === '' && quotes.includes(ch)) {
      open = ch
      continue
    }
    if (open === ch) {
      open = ''
      continue
    }
    if (open !== '' && ch !== '\n') out[i] = '_'
  }
  return out.join('')
}

/**
 * Kotlin 원문에서 주석(`//` · `/* … *​/`)을 **공백으로** 지운다. 줄바꿈과 전체 길이는 보존한다.
 *
 * KDoc 이 본문과 같은 토큰(`config["field"]`)을 적기 때문에 파서가 주석을 읽으면 옆 함수의 문서가
 * 이 함수의 키로 새어 들어온다. 문자열 리터럴 안의 `//` 는 주석이 아니므로 문자열 상태를 함께 센다.
 *
 * @param quotes 문자열로 볼 따옴표. TS/TSX 는 작은따옴표와 백틱도 문자열이다.
 */
function stripComments(source: string, quotes: readonly string[] = KOTLIN_QUOTES): string {
  const out = source.split('')
  let state: 'code' | 'string' | 'line' | 'block' = 'code'
  let open = ''
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i]
    const next = source[i + 1]
    if (state === 'string') {
      if (ch === '\\') i += 1
      else if (ch === open) state = 'code'
      continue
    }
    if (state === 'line') {
      if (ch === '\n') state = 'code'
      else out[i] = ' '
      continue
    }
    if (state === 'block') {
      if (ch === '*' && next === '/') {
        out[i] = ' '
        out[i + 1] = ' '
        i += 1
        state = 'code'
      } else if (ch !== '\n') {
        out[i] = ' '
      }
      continue
    }
    if (quotes.includes(ch)) {
      state = 'string'
      open = ch
    } else if (ch === '/' && (next === '/' || next === '*')) {
      state = next === '/' ? 'line' : 'block'
      out[i] = ' '
      out[i + 1] = ' '
      i += 1
    }
  }
  return out.join('')
}

/** 팩토리·구현체 `.kt` 를 읽어 주석을 지운 원문을 돌려준다. 파서는 이 결과만 본다. */
function readKotlin(filePath: string): string {
  return stripComments(fs.readFileSync(filePath, 'utf-8'))
}

/**
 * [from] 이후 처음 나오는 `{` 부터 짝이 맞는 `}` 직전까지의 **본문 원문**을 잘라낸다.
 *
 * [from] 지점부터만 마스킹한다 — 앞쪽에 홀수 개의 따옴표가 있어도 영향을 안 받는다.
 * 짝이 맞는 닫는 중괄호를 못 찾으면 빈 문자열을 돌려주고, 비-공허 짝이 그것을 red 로 만든다.
 */
function balancedBlock(
  source: string,
  from: number,
  quotes: readonly string[] = KOTLIN_QUOTES,
): string {
  if (from < 0) return ''
  const tail = source.slice(from)
  const masked = maskStringLiterals(tail, quotes)
  const open = masked.indexOf('{')
  if (open < 0) return ''
  let depth = 0
  for (let i = open; i < masked.length; i += 1) {
    if (masked[i] === '{') depth += 1
    else if (masked[i] === '}') {
      depth -= 1
      if (depth === 0) return tail.slice(open + 1, i)
    }
  }
  return ''
}

/** `when (type) { … }` 블록의 본문 원문. 표지를 못 찾으면 빈 문자열. */
function whenTypeBlock(source: string): string {
  return balancedBlock(source, source.indexOf(WHEN_HEAD))
}

/**
 * 이름으로 지목한 함수의 **본문 원문**. 없으면 빈 문자열.
 *
 * 중괄호 짝으로 잘라내므로 뒤따르는 다른 함수의 선언부가 섞이지 않는다.
 */
function functionBody(source: string, name: string): string {
  const signature = new RegExp(`\\bfun\\s+${name}\\s*\\(`).exec(source)
  return signature === null ? '' : balancedBlock(source, signature.index)
}

/**
 * `when` 분기 한 줄에서 문자열 리터럴을 뽑는 형태. 예: `    "SET_FIELD" -> createSetField(config)`.
 *
 * 줄 처음에 붙여 두어 분기 머리에서만 잡는다. `else -> …` 는 따옴표가 없으니 자연히 빠진다.
 * (`"A", "B" ->` 같은 다중 리터럴 분기는 지금 코드에 없다. 생기면 여기서 누락되어 차집합이
 * red 를 내므로 조용히 새지 않는다.)
 */
const WHEN_BRANCH = /^\s*"([^"]+)"\s*->/

/**
 * `when` 분기가 지목한 `create*` 함수 이름까지 함께 뽑는 형태.
 *
 * [WHEN_BRANCH] 와 나눠 둔 이유. 분기 본문이 `-> throw …` 처럼 함수 호출이 아닌 형태여도 type 추출
 * 자체는 살아 있어야 한다. 여기서 못 잡은 type 은 config 키가 0건이 되고, 표에 적힌 키와 어긋나
 * **조용히 새지 않고 red** 가 된다.
 */
const WHEN_BRANCH_TARGET = /^\s*"([^"]+)"\s*->\s*(\w+)\s*\(\s*config\s*\)/

/** 팩토리 `.kt` 원문에서 `when (type)` 분기의 type 문자열을 전부 뽑는다. */
function factoryTypes(source: string): string[] {
  return whenTypeBlock(source)
    .split('\n')
    .map((line) => WHEN_BRANCH.exec(line)?.[1])
    .filter((type): type is string => type !== undefined)
    .sort()
}

/** 필수 키를 읽는 호출. 키가 없으면 `IllegalArgumentException` 이 난다. */
const REQUIRED_CONFIG_CALL = /requireConfigString\(\s*config\s*,\s*"([^"]+)"\s*[,)]/g

/** 기본값이 있어 생략 가능한 키를 읽는 호출. */
const OPTIONAL_CONFIG_CALL = /requireConfigEnumOrDefault\(\s*config\s*,\s*"([^"]+)"\s*,/g

/**
 * 검증 없이 그대로 꺼내 쓰는 키. 예: `val value = config["value"]`.
 *
 * 헬퍼가 쓰는 `config[key]` 는 리터럴이 아니라 변수라서 여기 걸리지 않는다.
 * 주석을 먼저 지우므로 KDoc 의 `config["field"]` 서술도 걸리지 않는다.
 */
const OPTIONAL_CONFIG_INDEX = /config\[\s*"([^"]+)"\s*\]/g

/** 정규식 전역 매치의 첫 캡처를 전부 모은다. */
function captureAll(source: string, pattern: RegExp): string[] {
  return [...source.matchAll(pattern)].map((match) => match[1])
}

/**
 * 팩토리가 타입별로 실제로 읽는 config 키를 정규화된 문자열 목록으로 돌려준다.
 *
 * 필수 키는 그대로, 생략 가능한 키는 뒤에 [OPTIONAL_SUFFIX] 를 붙인다 — 표도 같은 표기를 쓰므로
 * 「필수를 선택으로 적어 둔」 거짓말까지 차집합에 걸린다.
 */
function factoryConfigKeys(source: string): Record<string, string[]> {
  const keys: Record<string, string[]> = {}
  for (const line of whenTypeBlock(source).split('\n')) {
    const branch = WHEN_BRANCH_TARGET.exec(line)
    if (branch === null) continue
    const [, type, functionName] = branch
    const body = functionBody(source, functionName)
    const required = captureAll(body, REQUIRED_CONFIG_CALL)
    const optional = [
      ...captureAll(body, OPTIONAL_CONFIG_CALL),
      ...captureAll(body, OPTIONAL_CONFIG_INDEX),
    ]
    keys[type] = [...required, ...optional.map((key) => `${key}${OPTIONAL_SUFFIX}`)].sort()
  }
  // 분기는 있는데 `create*(config)` 형태가 아니어서 위에서 빠진 type 도 빈 목록으로 남긴다 —
  // 표에 키가 적혀 있으면 차집합이 red 를 낸다.
  for (const type of factoryTypes(source)) keys[type] ??= []
  return keys
}

/**
 * `create*` 본문에서 `config` 를 **인자로 넘기는** 호출의 callee 이름.
 *
 * [REQUIRED_CONFIG_CALL] · [OPTIONAL_CONFIG_CALL] 이 헬퍼 이름을 박아 두고 있어, 그 둘 밖의 형태로
 * 키를 읽으면 [factoryConfigKeys] 가 **못 본다**. 못 본 키는 표에 문서화를 요구받지 않는다.
 */
const CONFIG_CONSUMER_CALL = /(\w+)\s*\(\s*config\s*[,)]/g

/** 축 2 가 키를 읽어낼 수 있는 호출 형태. 이 집합 밖은 키가 무음으로 사라진다. */
const KNOWN_CONFIG_READERS: readonly string[] = [
  'requireConfigString',
  'requireConfigEnumOrDefault',
]

/**
 * 팩토리 `create*` 본문에서 **축 2 가 해석하지 못하는** config 읽기 형태를 type 별로 모은다.
 *
 * 두 가지를 한꺼번에 잡는다.
 * 1. **네 번째 헬퍼** — `requireConfigInt(config, "maxLength")` 처럼 새 형태로 읽는 필수 키는
 *    축 2 의 정규식 3종에 안 걸려 표에 요구되지 않고 샌다.
 * 2. **한 단계 위임** — `requireConfigString` 을 같은 파일 private helper 로 빼고
 *    `fieldOf(config)` 로 부르면 축 2 는 키를 0건으로 보고, 그때 뜨는 실패 메시지
 *    「코드가 정본이니 표를 고쳐라」를 그대로 따르면 **아직 필요한 키를 문서에서 지우고** 초록이 된다.
 *
 * 둘 다 callee 이름이 허용 집합 밖이라는 하나의 사실로 드러난다.
 */
function unreadableConfigReaders(source: string): Record<string, string[]> {
  const unknown: Record<string, string[]> = {}
  for (const line of whenTypeBlock(source).split('\n')) {
    const branch = WHEN_BRANCH_TARGET.exec(line)
    if (branch === null) continue
    const [, type, functionName] = branch
    const callees = [...new Set(captureAll(functionBody(source, functionName), CONFIG_CONSUMER_CALL))]
      .filter((callee) => !KNOWN_CONFIG_READERS.includes(callee))
      .sort()
    if (callees.length > 0) unknown[type] = callees
  }
  return unknown
}

/** `override val type: String = "…"` 한 줄. 구현체가 인스턴스 쪽 type 을 선언하는 자리다. */
const OVERRIDE_TYPE = /^\s*override\s+val\s+type\s*:\s*String\s*=\s*"([^"]+)"/

/** 클래스 선언 한 줄. 앞에 붙는 제어자(`data` · `enum` · `open` …)는 건너뛴다. */
const CLASS_DECLARATION =
  /^\s*(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|enum|inner|final)\s+)*class\s+(\w+)/

/** 규칙 구현체 한 벌 — 클래스명과 인스턴스가 들고 다니는 type. */
interface RuleImplementation {
  className: string
  type: string
  file: string
}

/**
 * 패키지 디렉터리 아래 `.kt` 원문에서 `override val type` 을 선언한 구현체를 전부 뽑는다.
 *
 * 「구현체」의 정의를 **파일 목록이 아니라 `override val type` 선언 유무**로 잡는다 —
 * 같은 패키지의 서비스·리포지토리·DTO 가 섞여 들어오지 않고, 구현체가 하위 패키지로 옮겨가도
 * 따라간다. `override val type` 을 지우면 두 축(구현 클래스·인스턴스 type)이 동시에 red 를 낸다.
 */
function ruleImplementations(dir: string): RuleImplementation[] {
  const found: RuleImplementation[] = []
  for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.kt')) continue
    const file = path.join(entry.parentPath, entry.name)
    let className = ''
    for (const line of readKotlin(file).split('\n')) {
      className = CLASS_DECLARATION.exec(line)?.[1] ?? className
      const type = OVERRIDE_TYPE.exec(line)?.[1]
      if (type !== undefined && className !== '') found.push({ className, type, file })
    }
  }
  return found
}

/** `## 7.3 …` 부터 다음 `## ` 직전까지의 절 본문을 잘라낸다. 못 찾으면 빈 문자열. */
function sddSection(markdown: string, heading: string): string {
  const lines = markdown.split('\n')
  const start = lines.findIndex((line) => line.startsWith(`## ${heading}`))
  if (start < 0) return ''
  const rest = lines.slice(start + 1)
  const end = rest.findIndex((line) => line.startsWith('## '))
  return (end < 0 ? rest : rest.slice(0, end)).join('\n')
}

/** 마크다운 표 한 행을 셀 배열로 쪼갠다. 양끝 파이프가 만드는 빈 칸은 버린다. */
function tableCells(row: string): string[] {
  return row.trim().split('|').slice(1, -1).map((cell) => cell.trim())
}

/** 절 본문의 표 — 제목 행과 데이터 행. 표가 없으면 둘 다 빈 배열. */
interface SddTable {
  header: string[]
  rows: string[][]
}

/** 절 본문에서 표를 읽는다. `|---|---|` 구분선은 버린다. */
function sddTable(section: string): SddTable {
  const lines = section.split('\n').filter((line) => line.trimStart().startsWith('|'))
  if (lines.length === 0) return { header: [], rows: [] }
  return {
    header: tableCells(lines[0]),
    rows: lines
      .slice(1)
      .filter((row) => !/^[\s|:-]+$/.test(row))
      .map((row) => tableCells(row)),
  }
}

/** 제목 접두사로 열 하나를 뽑는다. 백틱은 벗긴다(표는 `` `SET_FIELD` `` 로 적는다). */
function sddColumn(table: SddTable, prefix: string): string[] {
  const column = table.header.findIndex((cell) => cell.startsWith(prefix))
  if (column < 0) return []
  return table.rows
    .map((row) => (row[column] ?? '').replaceAll('`', '').trim())
    .filter((cell) => cell.length > 0)
    .sort()
}

/**
 * 표의 타입 열과 config 키 열을 **같은 행에서** 짝지어 읽는다.
 *
 * 집합이 아니라 행 단위로 대조해야 「키를 옆 행에 적어 둔」 어긋남까지 잡힌다.
 */
function sddConfigKeys(table: SddTable): Record<string, string[]> {
  const typeColumn = table.header.findIndex((cell) => cell.startsWith(TYPE_COLUMN_PREFIX))
  const configColumn = table.header.findIndex((cell) => cell.startsWith(CONFIG_COLUMN_PREFIX))
  if (typeColumn < 0 || configColumn < 0) return {}
  const keys: Record<string, string[]> = {}
  for (const row of table.rows) {
    const type = (row[typeColumn] ?? '').replaceAll('`', '').trim()
    if (type.length === 0) continue
    keys[type] = (row[configColumn] ?? '')
      .replaceAll('`', '')
      .split(CONFIG_KEY_SEPARATOR)
      .map((token) => token.trim())
      .filter((token) => CONFIG_KEY_TOKEN.test(token))
      .sort()
  }
  return keys
}

/**
 * 표의 타입 열과 **구현 클래스 열**을 같은 행에서 짝지어 읽는다.
 *
 * 집합이 아니라 행 단위로 대조해야 「클래스를 옆 행에 적어 둔」 어긋남까지 잡힌다 —
 * [sddConfigKeys] 와 같은 이유다. 집합만 보면 두 행의 클래스를 **맞바꿔도** 양쪽 집합이
 * 그대로라 초록이고, 그때 표는 각 type 을 엉뚱한 클래스에 매핑한 채 남는다.
 */
function sddClassByType(table: SddTable): Record<string, string> {
  const typeColumn = table.header.findIndex((cell) => cell.startsWith(TYPE_COLUMN_PREFIX))
  const implColumn = table.header.findIndex((cell) => cell.startsWith(IMPL_COLUMN_PREFIX))
  if (typeColumn < 0 || implColumn < 0) return {}
  const byType: Record<string, string> = {}
  for (const row of table.rows) {
    const type = (row[typeColumn] ?? '').replaceAll('`', '').trim()
    if (type.length === 0) continue
    byType[type] = (row[implColumn] ?? '').replaceAll('`', '').trim()
  }
  return byType
}


// ─────────────────────────────────────────────────────────────────────────────
// 축 6·7 — 편집 화면의 type 별 config 폼 선언
// ─────────────────────────────────────────────────────────────────────────────

/** 프론트 소스 루트. 아래 표면 목록은 **파일 경로**일 뿐 type·키 목록이 아니다. */
const WEB_SRC = path.join(REPO_ROOT, 'apps/web/src')

/**
 * validator 편집 표면의 파일들. 선언이 이 밖으로 옮겨 가면 파서가 0건을 뽑고 비-공허 짝이 잡는다.
 */
const VALIDATOR_FORM_FILES: readonly string[] = [
  path.join(WEB_SRC, 'api/validators.ts'),
  path.join(WEB_SRC, 'components/workflow/ValidatorFormDialog.tsx'),
  path.join(WEB_SRC, 'components/workflow/ValidatorConfigSection.tsx'),
]

/** post-action 편집 표면의 파일들. */
const POST_ACTION_FORM_FILES: readonly string[] = [
  path.join(WEB_SRC, 'api/post-actions.ts'),
  path.join(WEB_SRC, 'components/workflow/PostActionFormDialog.tsx'),
  path.join(WEB_SRC, 'components/workflow/PostActionConfigSection.tsx'),
]

/**
 * TS/TSX 에서 문자열로 인정할 따옴표.
 *
 * 백틱을 넣어야 템플릿 리터럴 본문이 통째로 덮이고, 그 안의 `${…}` 중괄호에 짝 세기가 안 속는다.
 */
const TS_QUOTES: readonly string[] = ['"', "'", '`']

/** TS/TSX 원문을 읽어 주석을 지운 결과. 파서는 이것만 본다 — Kotlin 쪽과 같은 이유다. */
function readTypeScript(filePath: string): string {
  return stripComments(fs.readFileSync(filePath, 'utf-8'), TS_QUOTES)
}

/** 객체 리터럴 한 요소의 머리(`키:`). 키는 따옴표가 있어도 없어도 같은 키로 본다. */
const ENTRY_HEAD = /^(?:['"]([^'"]*)['"]|([A-Za-z_$][\w$]*))\s*:\s*/

/** 축약 프로퍼티(`{ url, method }`). 값이 없으므로 선택성 표기는 읽히지 않는다. */
const SHORTHAND_ENTRY = /^[A-Za-z_$][\w$]*$/

/** 실패 메시지에 실을 조각을 한 줄로 줄인다. */
function collapse(fragment: string): string {
  const line = fragment.replace(/\s+/g, ' ').trim()
  return line.length > 60 ? `${line.slice(0, 60)}…` : line
}

/** 객체 리터럴 본문을 요소로 쪼갠 결과. 못 읽은 조각은 버리지 않고 따로 들고 나온다. */
interface ObjectLiteral {
  entries: { key: string; value: string }[]
  unreadable: string[]
}

/**
 * 객체 리터럴 **본문 원문**을 `키 → 값 원문` 으로 쪼갠다.
 *
 * 깊이 0 의 쉼표로만 자르므로 중첩 객체·호출 인자의 쉼표에 속지 않는다. 전개(`...base`)나
 * 계산된 키(`[k]:`)처럼 키를 이름으로 못 읽는 요소는 [ObjectLiteral.unreadable] 로 나가고,
 * 축 7 이 그것을 red 로 만든다 — 조용히 버리면 키가 무음으로 사라진다.
 */
function objectLiteralEntries(body: string): ObjectLiteral {
  const masked = maskStringLiterals(body, TS_QUOTES)
  const segments: string[] = []
  let depth = 0
  let start = 0
  for (let i = 0; i < masked.length; i += 1) {
    const ch = masked[i]
    if (ch === '{' || ch === '[' || ch === '(') depth += 1
    else if (ch === '}' || ch === ']' || ch === ')') depth -= 1
    else if (ch === ',' && depth === 0) {
      segments.push(body.slice(start, i))
      start = i + 1
    }
  }
  segments.push(body.slice(start))
  return splitEntries(segments)
}

/** 쪼갠 조각을 `키: 값` · 축약 · 못 읽음으로 가른다. */
function splitEntries(segments: readonly string[]): ObjectLiteral {
  const entries: { key: string; value: string }[] = []
  const unreadable: string[] = []
  for (const raw of segments) {
    const segment = raw.trim()
    if (segment.length === 0) continue
    const head = ENTRY_HEAD.exec(segment)
    if (head !== null) {
      entries.push({ key: head[1] ?? head[2], value: segment.slice(head[0].length).trim() })
    } else if (SHORTHAND_ENTRY.test(segment)) {
      entries.push({ key: segment, value: '' })
    } else {
      unreadable.push(collapse(segment))
    }
  }
  return { entries, unreadable }
}

/** 화면이 한 자리에서 선언한 「이 type 은 이 키들을 쓴다」 한 벌. */
interface FrontDeclaration {
  type: string
  /** 정규화된 키. 선택 키는 뒤에 [OPTIONAL_SUFFIX] 가 붙는다 — 표와 같은 표기다. */
  keys: string[]
  /** 그 선언에서 파서가 이름으로 못 읽은 조각. */
  unreadable: string[]
}

/** 한 파일에서 뽑은 선언들과, 어느 선언에도 귀속되지 않는 못 읽은 조각. */
interface FrontParseResult {
  declarations: FrontDeclaration[]
  notes: string[]
}

/** 선언적 폼 스키마 객체를 찾는 표지. 이름이 아니라 **접미사**로 찾는다. */
const FORM_SCHEMA_DECLARATION = /\bconst\s+\w*CONFIG_FORM_SCHEMAS\b[^=\n]*=/g

/** 폼 스키마 한 벌로 인정하는 형태. 이 밖은 축 6 이 키를 못 읽으므로 축 7 이 잡는다. */
const ZOD_OBJECT_HEAD = /^z\s*\.\s*object\s*\(\s*\{/

/** 파서가 아는 선택성 표기. 표의 `(선택)` 과 짝이다. */
const OPTIONAL_MARKER = '.optional()'

/**
 * zod 에서 `isOptional()` 을 참으로 만들지만 **파서는 모르는** 표기.
 *
 * 화면은 `field.isOptional()` 로 필수/선택을 정하는데 파서는 [OPTIONAL_MARKER] 만 읽는다.
 * 이 표기를 쓰면 화면은 선택으로 그리고 파서는 필수로 읽어 축 6 이 **틀린 초록**을 낼 수 있다.
 * 그래서 등재 없이 쓰면 축 7 이 먼저 red 를 낸다 — 축 5 가 새 헬퍼를 막는 것과 같은 자리다.
 */
const HIDDEN_OPTIONALITY_MARKERS: readonly string[] = ['.default(', '.nullish(', '.catch(']

/**
 * 선언적 폼 스키마 객체(`*CONFIG_FORM_SCHEMAS`)에서 type 별 config 키를 뽑는다.
 *
 * 키 = 타입 식별자, `z.object({…})` 의 프로퍼티 = config 키, `.optional()` = 표의 `(선택)`.
 */
function declarativeFormSchemas(source: string): FrontParseResult {
  const declarations: FrontDeclaration[] = []
  const notes: string[] = []
  for (const declaration of source.matchAll(FORM_SCHEMA_DECLARATION)) {
    const outer = objectLiteralEntries(balancedBlock(source, declaration.index, TS_QUOTES))
    notes.push(...outer.unreadable.map((fragment) => `스키마 객체 최상위. ${fragment}`))
    declarations.push(...outer.entries.map((entry) => zodObjectShape(entry.key, entry.value)))
  }
  return { declarations, notes }
}

/** `z.object({ … })` 한 벌을 [FrontDeclaration] 으로 읽는다. */
function zodObjectShape(type: string, value: string): FrontDeclaration {
  if (!ZOD_OBJECT_HEAD.test(value)) {
    return { type, keys: [], unreadable: [`z.object({…}) 리터럴이 아니다. ${collapse(value)}`] }
  }
  const shape = objectLiteralEntries(balancedBlock(value, 0, TS_QUOTES))
  const hidden = shape.entries.flatMap((field) =>
    HIDDEN_OPTIONALITY_MARKERS.filter((marker) => field.value.includes(marker)).map(
      (marker) => `${field.key} — 파서가 모르는 선택성 표기 ${marker}`,
    ),
  )
  const keys = shape.entries.map((field) =>
    field.value.includes(OPTIONAL_MARKER) ? `${field.key}${OPTIONAL_SUFFIX}` : field.key,
  )
  return { type, keys: keys.sort(), unreadable: [...shape.unreadable, ...hidden] }
}

/** 요청 바디의 `type: '리터럴'`. 이 자리가 백엔드로 나가는 실제 계약이다. */
const TYPE_LITERAL = /\btype\s*:\s*['"]([^'"]*)['"]/g

/** 같은 객체 리터럴 안의 `config:` 프로퍼티. */
const CONFIG_PROPERTY = /^config\s*:\s*/

/**
 * `at` 을 직접 감싸는 객체 리터럴의 여는 `{` 위치. 객체가 아니면(배열·인자 목록) `null`.
 *
 * 뒤로 훑으며 닫는 괄호를 만나면 깊이를 올리고, 깊이 0 에서 여는 괄호를 만나면 그것이 경계다.
 */
function enclosingObjectStart(masked: string, at: number): number | null {
  let depth = 0
  for (let i = at - 1; i >= 0; i -= 1) {
    const ch = masked[i]
    if (ch === '}' || ch === ']' || ch === ')') depth += 1
    else if (ch === '{' || ch === '[' || ch === '(') {
      if (depth === 0) return ch === '{' ? i : null
      depth -= 1
    }
  }
  return null
}

/**
 * `type: '리터럴'` 과 **같은 객체 리터럴**에 있는 `config:` 값의 시작 위치.
 *
 * `type:` 의 **감싸는 객체 경계를 먼저 찾고** 그 안을 처음부터 훑는다 — 그래서 `config` 가
 * `type` 앞에 오든 뒤에 오든 같은 선언을 읽는다. 깊이 0 을 유지하므로 중첩된 객체나 옆 객체의
 * `config` 는 이 type 의 것으로 붙지 않는다. 못 찾으면 `null` 이고 그 `type:` 은 건너뛴다
 * (요청 바디가 아니라 그냥 `type` 이라는 이름의 프로퍼티였다는 뜻이다).
 *
 * ★ 종전에는 `type:` **뒤로만** 훑어서 `{ config: {…}, type: 'X' }` 가 조용히 사라졌다.
 * 「전량 소실」은 비-공허 짝이 잡지만 「부분 소실」은 아무것도 잡지 못했다 — 그 자리를
 * 위 「선언의 키 순서에 무관하다」 describe 가 잠근다.
 */
function siblingConfigValue(masked: string, typeAt: number): number | null {
  const start = enclosingObjectStart(masked, typeAt)
  if (start === null) return null
  let depth = 0
  for (let i = start + 1; i < masked.length; i += 1) {
    const ch = masked[i]
    if (ch === '{' || ch === '[' || ch === '(') depth += 1
    else if (ch === '}' || ch === ']' || ch === ')') {
      if (depth === 0) return null
      depth -= 1
    } else if (depth === 0 && masked.startsWith('config', i) && !/[\w$.]/.test(masked[i - 1] ?? '')) {
      const head = CONFIG_PROPERTY.exec(masked.slice(i))
      if (head !== null) return i + head[0].length
    }
  }
  return null
}

/**
 * 뮤테이션 호출부의 `type: '리터럴'` + `config: { … }` 짝에서 type 별 config 키를 뽑는다.
 *
 * post-action 표면에는 선언적 스키마가 없어 이 형태가 화면이 가진 유일한 기계 판독 계약이다.
 * **이 표면은 `(선택)` 을 표현할 자리가 없다** — 객체 리터럴에 무조건 실리는 키뿐이라 파서는
 * 전부 필수로 읽는다. 조건부로 실리는 키는 요소를 이름으로 못 읽어 축 7 이 red 를 낸다.
 */
function literalConfigPayloads(source: string): FrontParseResult {
  const masked = maskStringLiterals(source, TS_QUOTES)
  const declarations: FrontDeclaration[] = []
  for (const match of source.matchAll(TYPE_LITERAL)) {
    if (masked[match.index] === '_') continue
    const value = siblingConfigValue(masked, match.index)
    if (value === null) continue
    if (masked[value] !== '{') {
      const fragment = collapse(source.slice(value, value + 80))
      declarations.push({
        type: match[1],
        keys: [],
        unreadable: [`config 값이 객체 리터럴이 아니다. ${fragment}`],
      })
      continue
    }
    const literal = objectLiteralEntries(balancedBlock(source, value, TS_QUOTES))
    const keys = literal.entries.map((entry) => entry.key).sort()
    declarations.push({ type: match[1], keys, unreadable: literal.unreadable })
  }
  return { declarations, notes: [] }
}

/** 화면이 `config['키']` 로 **직접 이름을 대고** 읽는 자리. 변수 인덱싱은 걸리지 않는다. */
const CONFIG_LITERAL_READ = /\bconfig\s*\[\s*['"]([^'"]*)['"]\s*\]/g

/** 선언 집합 밖의 `config['키']` 읽기를 모아 두는 축 7 의 자리표. */
const STRAY_READ_LABEL = "config['키'] 직접 읽기"

/** 한 편집 표면이 들고 있는 type 별 config 폼 선언 전부. */
interface FrontFormCatalog {
  label: string
  files: readonly string[]
  /** 폼이 있는 type → 정규화된 config 키. 폼이 없는 type 은 아예 없다. */
  configKeys: Record<string, string[]>
  /** 축 7 이 red 로 만드는, 파서가 이름으로 못 읽은 것들. */
  unreadable: Record<string, string[]>
}

/**
 * 한 편집 표면의 파일들에 **두 파서를 모두** 돌려 합친 목록.
 *
 * 표면마다 선언 형태가 달라 파서가 두 벌이지만 축은 하나다. post-action 이 나중에 validator 처럼
 * 선언적 스키마를 갖게 되면 이 함수도 축도 그대로 두고 파일 목록만 남는다.
 */
function frontFormCatalog(label: string, files: readonly string[]): FrontFormCatalog {
  const declarations: FrontDeclaration[] = []
  const unreadable: Record<string, string[]> = {}
  const notes: string[] = []
  const reads: string[] = []
  for (const file of files) {
    const source = readTypeScript(file)
    for (const parsed of [declarativeFormSchemas(source), literalConfigPayloads(source)]) {
      declarations.push(...parsed.declarations)
      notes.push(...parsed.notes)
    }
    reads.push(...captureAll(source, CONFIG_LITERAL_READ))
  }
  const configKeys = mergeDeclarations(declarations, unreadable)
  if (notes.length > 0) unreadable['선언 자체를 못 읽음'] = notes
  const stray = strayReads(reads, configKeys)
  if (stray.length > 0) unreadable[STRAY_READ_LABEL] = stray
  return { label, files, configKeys, unreadable }
}

/**
 * 같은 type 의 선언을 합친다. 여러 자리가 **서로 다른 키**를 들면 그것 자체가 어긋남이다.
 *
 * 추가 경로와 수정 경로가 갈리는 자리가 여기다 — 한쪽만 키를 더하면 추가는 되는데 수정은
 * 그 키를 지운다. 합집합으로 축 6 에 넘기고, 갈렸다는 사실은 축 7 이 이름으로 지목한다.
 */
function mergeDeclarations(
  declarations: readonly FrontDeclaration[],
  unreadable: Record<string, string[]>,
): Record<string, string[]> {
  const configKeys: Record<string, string[]> = {}
  for (const type of [...new Set(declarations.map((declaration) => declaration.type))].sort()) {
    const mine = declarations.filter((declaration) => declaration.type === type)
    configKeys[type] = [...new Set(mine.flatMap((declaration) => declaration.keys))].sort()
    const variants = [...new Set(mine.map((declaration) => declaration.keys.join(' · ')))]
    const found = mine.flatMap((declaration) => declaration.unreadable)
    if (variants.length > 1) {
      found.push(`선언 ${mine.length} 곳이 서로 다른 키를 든다. ${variants.join(' / ')}`)
    }
    if (found.length > 0) unreadable[type] = found
  }
  return configKeys
}

/** 어느 선언에도 없는 키를 `config['키']` 로 읽는 자리. 그 키는 표에 문서화를 요구받지 못한다. */
function strayReads(reads: readonly string[], configKeys: Record<string, string[]>): string[] {
  const declared = new Set(
    Object.values(configKeys)
      .flat()
      .map((key) => key.replace(OPTIONAL_SUFFIX, '')),
  )
  return [...new Set(reads)].filter((key) => !declared.has(key)).sort()
}

/** `left` 에만 있고 `right` 에는 없는 값. */
function onlyIn(left: string[], right: string[]): string[] {
  const other = new Set(right)
  return left.filter((value) => !other.has(value))
}

describe('SDD §7.3·§7.4 표 ↔ 팩토리 when 분기 ↔ 규칙 구현체 정합', () => {
  const sdd = fs.readFileSync(SDD_FILE, 'utf-8')

  /** 한 규칙 종류(validator · post-action)에 대해 네 축이 읽어 온 값 전부. */
  function catalogOf(heading: string, label: string, factoryFile: string, implDir: string) {
    const table = sddTable(sddSection(sdd, heading))
    const factory = readKotlin(factoryFile)
    const implementations = ruleImplementations(implDir)
    return {
      label,
      factoryFile,
      factoryName: path.basename(factoryFile),
      implDir,
      documented: sddColumn(table, TYPE_COLUMN_PREFIX),
      implemented: factoryTypes(factory),
      documentedClassByType: sddClassByType(table),
      implementedClassByType: Object.fromEntries(
        implementations.map((impl) => [impl.type, impl.className]),
      ),
      documentedConfigKeys: sddConfigKeys(table),
      implementedConfigKeys: factoryConfigKeys(factory),
      unreadableReaders: unreadableConfigReaders(factory),
      instanceTypes: implementations.map((impl) => impl.type).sort(),
    }
  }

  const catalogs = {
    validator: catalogOf('7.3', '§7.3 Validator', VALIDATOR_FACTORY, VALIDATOR_DIR),
    postAction: catalogOf('7.4', '§7.4 Post-function', POST_ACTION_FACTORY, POST_ACTION_DIR),
  } as const

  type Catalog = (typeof catalogs)[keyof typeof catalogs]

  /** 축 6·7 이 보는 편집 화면 쪽 목록. 파서가 두 벌이라 팩토리 카탈로그와 따로 둔다. */
  const fronts = {
    validator: frontFormCatalog('validator 편집 화면', VALIDATOR_FORM_FILES),
    postAction: frontFormCatalog('post-action 편집 화면', POST_ACTION_FORM_FILES),
  } as const

  /** 어느 쪽이 무엇을 빠뜨렸는지 이름으로 말하는 양방향 차집합 단언. */
  function assertSameSet(
    left: { label: string; values: string[] },
    right: { label: string; values: string[] },
    hint: string,
  ): void {
    const leftOnly = onlyIn(left.values, right.values)
    const rightOnly = onlyIn(right.values, left.values)
    assert.deepEqual(
      { [`${left.label}에만 있음`]: leftOnly, [`${right.label}에만 있음`]: rightOnly },
      { [`${left.label}에만 있음`]: [], [`${right.label}에만 있음`]: [] },
      `${left.label} 과(와) ${right.label} 이(가) 어긋난다 — **코드가 정본**이다.\n` +
        `  ${left.label}에만 있음. ${leftOnly.join(', ') || '(없음)'}\n` +
        `  ${right.label}에만 있음. ${rightOnly.join(', ') || '(없음)'}\n` +
        `  ${left.label} 전체. ${left.values.join(', ')}\n` +
        `  ${right.label} 전체. ${right.values.join(', ')}\n` +
        `  ${hint}`,
    )
  }

  test('SDD §7.3 표의 type 집합 = DefaultWorkflowValidatorFactory when 분기 집합', () => {
    assertTypeCatalog(catalogs.validator)
  })

  test('SDD §7.4 표의 type 집합 = DefaultWorkflowPostActionFactory when 분기 집합', () => {
    assertTypeCatalog(catalogs.postAction)
  })

  /** 축 1 — 표의 타입 식별자 열 ↔ 팩토리 `when` 분기. */
  function assertTypeCatalog(catalog: Catalog): void {
    assertSameSet(
      { label: `${catalog.label} 표의 런타임 식별자 열`, values: catalog.documented },
      { label: `${catalog.factoryName} 의 when 분기`, values: catalog.implemented },
      '표에 적은 값이 런타임에 그대로 들어온다. 어긋나면 「지원하지 않는 type」 예외가 난다.',
    )
  }

  test('SDD §7.3 표의 구현 클래스 열 = validator 구현체 — type 별 짝', () => {
    assertClassCatalog(catalogs.validator)
  })

  test('SDD §7.4 표의 구현 클래스 열 = postaction 구현체 — type 별 짝', () => {
    assertClassCatalog(catalogs.postAction)
  })

  /**
   * 축 3 — 표의 구현 클래스 열 ↔ `override val type` 을 선언한 실제 클래스. **행 단위로 대조한다.**
   *
   * 집합 대조였을 때 두 행의 클래스를 맞바꾸면 양쪽 집합이 그대로라 초록이었다(2026-08-26 게이트 2
   * 라운드 3 실측 — `RequiredFieldValidator` ↔ `PermissionValidator` 스왑에 9/9 통과). 그러면 표는
   * 각 type 을 엉뚱한 클래스에 매핑한 채 남고, 표를 읽어 구현을 찾는 사람이 잘못된 파일을 연다.
   * 축 2 가 같은 이유로 이미 행 단위였는데 이 축만 집합이었다 — 파일 자신이 아는 규율을 한 축에만
   * 적용한 자리다.
   */
  function assertClassCatalog(catalog: Catalog): void {
    const { label, implDir, documentedClassByType, implementedClassByType } = catalog
    const types = [
      ...new Set([
        ...Object.keys(documentedClassByType),
        ...Object.keys(implementedClassByType),
      ]),
    ].sort()
    const show = (name: string | undefined): string =>
      name === undefined ? '(그 type 의 행/구현체가 없음)' : name || '(칸이 비었음)'
    const diverged = types.filter(
      (type) => documentedClassByType[type] !== implementedClassByType[type],
    )
    assert.deepEqual(
      documentedClassByType,
      implementedClassByType,
      `${label} 표의 '${IMPL_COLUMN_PREFIX} …' 열이 ` +
        `${path.relative(REPO_ROOT, implDir)} 의 구현체와 어긋난다 — **코드가 정본**이니 표를 고쳐라.\n` +
        diverged
          .map(
            (type) =>
              `  ${type} — 표. ${show(documentedClassByType[type])}` +
              ` / 코드. ${show(implementedClassByType[type])}`,
          )
          .join('\n') +
        '\n  (구현체는 `override val type` 을 선언한 클래스로 센다. 같은 행의 type 과 클래스가 ' +
        '짝이어야 하므로, 클래스를 옆 행에 적으면 집합이 같아도 red 다.)',
    )
  }

  test('DefaultWorkflowValidatorFactory when 분기 = validator 구현체의 override val type', () => {
    assertInstanceTypes(catalogs.validator)
  })

  test('DefaultWorkflowPostActionFactory when 분기 = postaction 구현체의 override val type', () => {
    assertInstanceTypes(catalogs.postAction)
  })

  /** 축 4 — 팩토리 분기 문자열 ↔ 인스턴스가 들고 다니는 type. */
  function assertInstanceTypes(catalog: Catalog): void {
    assertSameSet(
      { label: `${catalog.factoryName} 의 when 분기`, values: catalog.implemented },
      {
        label: `${path.relative(REPO_ROOT, catalog.implDir)} 의 override val type`,
        values: catalog.instanceTypes,
      },
      'WorkflowEngine 은 전환 실패를 인스턴스 쪽 type 으로 보고한다. ' +
        '두 벌이 갈리면 엔진이 알려 준 type 을 CRUD API 에 되돌려 썼을 때 400 이 난다.',
    )
  }

  test('SDD §7.3 표의 필수 config 키 열 = DefaultWorkflowValidatorFactory 가 읽는 키', () => {
    assertConfigKeys(catalogs.validator)
  })

  test('SDD §7.4 표의 필수 config 키 열 = DefaultWorkflowPostActionFactory 가 읽는 키', () => {
    assertConfigKeys(catalogs.postAction)
  })

  /** 축 2 — 표의 config 키 열 ↔ 팩토리 `create*` 가 실제로 읽는 키. 행 단위로 대조한다. */
  function assertConfigKeys(catalog: Catalog): void {
    const { label, factoryName, documentedConfigKeys, implementedConfigKeys } = catalog
    const types = [
      ...new Set([...Object.keys(documentedConfigKeys), ...Object.keys(implementedConfigKeys)]),
    ].sort()
    const show = (keys: string[] | undefined): string =>
      keys === undefined ? '(표에 행이 없음)' : keys.join(' · ') || '(없음)'
    const diverged = types.filter(
      (type) =>
        JSON.stringify(documentedConfigKeys[type]) !== JSON.stringify(implementedConfigKeys[type]),
    )
    assert.deepEqual(
      documentedConfigKeys,
      implementedConfigKeys,
      `${label} 표의 '${CONFIG_COLUMN_PREFIX} …' 열이 ${factoryName} 가 읽는 키와 어긋난다 — ` +
        '**코드가 정본**이니 표를 고쳐라.\n' +
        diverged
          .map(
            (type) =>
              `  ${type} — 표. ${show(documentedConfigKeys[type])}` +
              ` / 코드. ${show(implementedConfigKeys[type])}`,
          )
          .join('\n') +
        `\n  (표기 규칙. 필수 키는 그대로 적고, 기본값이 있어 생략 가능한 키는 뒤에 ` +
        `${OPTIONAL_SUFFIX} 를 붙인다. 여러 개면 '${CONFIG_KEY_SEPARATOR}' 로 잇는다.)\n` +
        '  (코드 쪽 근거. `requireConfigString` = 필수 · `requireConfigEnumOrDefault` 와 ' +
        '`config["키"]` 직접 읽기 = 선택.)',
    )
  }

  test('DefaultWorkflowValidatorFactory 가 축 2 로 안 읽히는 형태로 config 를 읽지 않는다', () => {
    assertNoUnreadableReaders(catalogs.validator)
  })

  test('DefaultWorkflowPostActionFactory 가 축 2 로 안 읽히는 형태로 config 를 읽지 않는다', () => {
    assertNoUnreadableReaders(catalogs.postAction)
  })

  /**
   * 축 5 — `create*` 가 축 2 의 해석 범위 **안에서만** config 를 읽는지.
   *
   * 축 2 는 차집합이라 「표에 있는데 코드에 없다」와 「코드에 있는데 표에 없다」를 잡는다. 그런데
   * 코드가 **축 2 가 모르는 형태**로 키를 읽으면 그 키는 애초에 코드 쪽 집합에 안 들어가서
   * 차집합이 0 이 되고, 문서화되지 않은 필수 키가 초록인 채로 남는다. 차집합이 못 보는 자리를
   * 이 축이 지킨다.
   */
  function assertNoUnreadableReaders(catalog: Catalog): void {
    const { label, factoryName, unreadableReaders } = catalog
    assert.deepEqual(
      unreadableReaders,
      {},
      `${factoryName} 의 create* 가 축 2 로 해석되지 않는 형태로 config 를 읽는다 — ` +
        `그 키는 ${label} 표에 문서화를 요구받지 못하고 조용히 샌다.\n` +
        Object.entries(unreadableReaders)
          .map(([type, callees]) => `  ${type} — ${callees.join(' · ')}`)
          .join('\n') +
        `\n  (축 2 가 읽는 형태는 ${KNOWN_CONFIG_READERS.join(' · ')} 와 ` +
        'config["키"] 직접 인덱싱뿐이다.)\n' +
        '  **표를 고쳐서 지우지 마라.** 새 헬퍼를 쓰려면 그 헬퍼의 패턴을 이 파일의 ' +
        'REQUIRED_CONFIG_CALL / OPTIONAL_CONFIG_CALL 옆에 함께 등재하고 ' +
        'KNOWN_CONFIG_READERS 에 이름을 넣어라. 등재 없이 쓰면 표가 키를 빠뜨린 채 초록이 된다.',
    )
  }

  test('SDD §7.3 표의 필수 config 키 열 = validator 편집 화면의 폼 스키마', () => {
    assertFrontFormSchema(catalogs.validator, fronts.validator)
  })

  test('SDD §7.4 표의 필수 config 키 열 = post-action 편집 화면의 폼 선언', () => {
    assertFrontFormSchema(catalogs.postAction, fronts.postAction)
  })

  /**
   * 축 6 — 표의 config 키 열 ↔ 편집 화면이 그 type 에 그리는 폼의 키. **행 단위로 대조한다.**
   *
   * 도메인은 **폼이 있는 type** 으로 좁힌다. 표의 모든 타입에 폼이 있는 것이 아니라
   * (`CustomExpression` 은 편집 불가라 폼이 없고 post-action 은 `CALL_WEBHOOK` 만 폼이 있다)
   * 「표 전량 ↔ 폼 전량」 양방향 차집합은 성립하지 않는다. 대신 폼이 표에 없는 타입을 그리면
   * 표 쪽이 `(표에 행이 없음)` 으로 잡히므로 「폼 ⊆ 표」는 이 한 단언이 함께 지킨다.
   */
  function assertFrontFormSchema(catalog: Catalog, front: FrontFormCatalog): void {
    const types = Object.keys(front.configKeys).sort()
    const documented = Object.fromEntries(types.map((type) => [type, catalog.documentedConfigKeys[type]]))
    const drawn = Object.fromEntries(types.map((type) => [type, front.configKeys[type]]))
    const show = (keys: string[] | undefined): string =>
      keys === undefined ? '(표에 행이 없음)' : keys.join(' · ') || '(없음)'
    const diverged = types.filter(
      (type) => JSON.stringify(documented[type]) !== JSON.stringify(drawn[type]),
    )
    assert.deepEqual(
      drawn,
      documented,
      `${front.label}의 폼 선언이 ${catalog.label} 표의 '${CONFIG_COLUMN_PREFIX} …' 열과 어긋난다 — ` +
        '이 축만 **표가 정본**이다(표는 축 1·2 로 이미 팩토리와 같음이 강제돼 있다). 화면을 고쳐라.\n' +
        diverged
          .map((type) => `  ${type} — 표. ${show(documented[type])} / 화면. ${show(drawn[type])}`)
          .join('\n') +
        '\n  (도메인은 **폼이 있는 type** 뿐이다. 표에 행이 있어도 폼이 없으면 재지 않는다 — ' +
        'CustomExpression 처럼 편집 불가인 것이 정상이다.)\n' +
        `  (표기 규칙은 축 2 와 같다. 선택 키는 뒤에 ${OPTIONAL_SUFFIX} 를 붙인다.)\n` +
        `  (읽은 파일. ${front.files.map((file) => path.relative(REPO_ROOT, file)).join(' · ')})`,
    )
  }

  test('validator 편집 화면이 축 6 이 못 읽는 형태로 config 키를 들지 않는다', () => {
    assertFrontDeclarationsReadable(fronts.validator)
  })

  test('post-action 편집 화면이 축 6 이 못 읽는 형태로 config 키를 들지 않는다', () => {
    assertFrontDeclarationsReadable(fronts.postAction)
  })

  /**
   * 축 7 — 편집 화면이 축 6 의 해석 범위 **안에서만** config 키를 선언하는지.
   *
   * 축 6 은 차집합이라 「표에 있는데 폼에 없다」와 「폼에 있는데 표에 없다」를 잡는다. 그런데
   * 화면이 **축 6 이 모르는 형태**로 키를 들면 그 키는 애초에 화면 쪽 집합에 안 들어가서 차집합이
   * 0 이 되고, 문서화되지 않은 키가 초록인 채로 남는다. 축 5 가 팩토리 쪽에서 하는 일과 같다.
   */
  function assertFrontDeclarationsReadable(front: FrontFormCatalog): void {
    assert.deepEqual(
      front.unreadable,
      {},
      `${front.label}이 축 6 으로 해석되지 않는 형태로 config 키를 든다 — ` +
        '그 키는 SDD 표에 문서화를 요구받지 못하고 조용히 샌다.\n' +
        Object.entries(front.unreadable)
          .map(([where, found]) => `  ${where} — ${found.join(' · ')}`)
          .join('\n') +
        '\n  (축 6 이 읽는 형태는 둘뿐이다. ① *CONFIG_FORM_SCHEMAS 객체의 ' +
        '「타입: z.object({ 키: … })」 ② 요청 바디의 「type: 리터럴」 과 같은 객체에 있는 ' +
        '「config: { 키: … }」.)\n' +
        '  **표를 고쳐서 지우지 마라.** 새 형태로 키를 들려면 그 형태를 읽는 파서를 이 파일에 ' +
        '함께 등재하라. 등재 없이 쓰면 표가 키를 빠뜨린 채 초록이 된다.',
    )
  }

  test('뽑아낸 집합이 하나도 비어 있지 않다 (비-공허 짝)', () => {
    // 파서가 0건을 뱉으면 위 차집합 단언들이 공허하게 0 이 되어 아무것도 안 지키면서 초록이 된다.
    const probes: readonly (readonly [string, readonly unknown[], string | readonly string[]])[] = [
      [`${catalogs.validator.label} 표의 타입 식별자 열`, catalogs.validator.documented, SDD_FILE],
      [`${catalogs.postAction.label} 표의 타입 식별자 열`, catalogs.postAction.documented, SDD_FILE],
      [
        `${catalogs.validator.label} 표의 type→구현 클래스 짝`,
        Object.keys(catalogs.validator.documentedClassByType),
        SDD_FILE,
      ],
      [
        `${catalogs.postAction.label} 표의 type→구현 클래스 짝`,
        Object.keys(catalogs.postAction.documentedClassByType),
        SDD_FILE,
      ],
      [
        `${catalogs.validator.label} 표의 config 키 열`,
        Object.values(catalogs.validator.documentedConfigKeys).flat(),
        SDD_FILE,
      ],
      [
        `${catalogs.postAction.label} 표의 config 키 열`,
        Object.values(catalogs.postAction.documentedConfigKeys).flat(),
        SDD_FILE,
      ],
      [
        'DefaultWorkflowValidatorFactory when 분기',
        catalogs.validator.implemented,
        VALIDATOR_FACTORY,
      ],
      [
        'DefaultWorkflowPostActionFactory when 분기',
        catalogs.postAction.implemented,
        POST_ACTION_FACTORY,
      ],
      [
        'DefaultWorkflowValidatorFactory 가 읽는 config 키',
        Object.values(catalogs.validator.implementedConfigKeys).flat(),
        VALIDATOR_FACTORY,
      ],
      [
        'DefaultWorkflowPostActionFactory 가 읽는 config 키',
        Object.values(catalogs.postAction.implementedConfigKeys).flat(),
        POST_ACTION_FACTORY,
      ],
      [
        'validator 구현체의 type→클래스 짝',
        Object.keys(catalogs.validator.implementedClassByType),
        VALIDATOR_DIR,
      ],
      [
        'postaction 구현체의 type→클래스 짝',
        Object.keys(catalogs.postAction.implementedClassByType),
        POST_ACTION_DIR,
      ],
      ['validator 구현체의 override val type', catalogs.validator.instanceTypes, VALIDATOR_DIR],
      ['postaction 구현체의 override val type', catalogs.postAction.instanceTypes, POST_ACTION_DIR],
      [
        `${fronts.validator.label}의 폼 type 목록`,
        Object.keys(fronts.validator.configKeys),
        VALIDATOR_FORM_FILES,
      ],
      [
        `${fronts.validator.label}의 폼 config 키`,
        Object.values(fronts.validator.configKeys).flat(),
        VALIDATOR_FORM_FILES,
      ],
      [
        `${fronts.postAction.label}의 폼 type 목록`,
        Object.keys(fronts.postAction.configKeys),
        POST_ACTION_FORM_FILES,
      ],
      [
        `${fronts.postAction.label}의 폼 config 키`,
        Object.values(fronts.postAction.configKeys).flat(),
        POST_ACTION_FORM_FILES,
      ],
    ]

    for (const [label, values, source] of probes) {
      const files = typeof source === 'string' ? [source] : source
      assert.ok(
        values.length > 0,
        `${label}에서 **0건**을 뽑았다 — 파서가 죽었고 위 차집합 단언은 공허하다.\n` +
          `  읽은 파일. ${files.map((file) => path.relative(REPO_ROOT, file)).join(' · ')}\n` +
          `  표는 '${TYPE_COLUMN_PREFIX}' · '${IMPL_COLUMN_PREFIX}' · '${CONFIG_COLUMN_PREFIX}' 로 ` +
          `시작하는 제목의 열이, 팩토리는 '${WHEN_HEAD}' 블록이, 구현체는 ` +
          `'override val type' 선언이, 편집 화면은 *CONFIG_FORM_SCHEMAS 객체나 ` +
          `type/config 리터럴 짝이 있어야 한다.`,
      )
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 축 6 의 post-action 파서 계약 — 선언의 키 순서에 의존하지 않는다
// ─────────────────────────────────────────────────────────────────────────────
//
// 축 6·7 은 **파일 전체가 읽히지 않을 때**만 red 를 낸다(비-공허 짝이 「집합이 통째로 비었는가」를
// 본다). 파서가 선언 **하나**를 놓치면 그 type 만 대조에서 조용히 빠지고, 갈린 사실은 사용자가
// 저장을 눌러 400 을 받을 때 드러난다. 그래서 파서 자신의 계약을 여기서 직접 잰다.
//
// 픽스처는 `PostActionConfigSection.tsx` 의 실제 뮤테이션 호출부 모양을 그대로 쓴다 — 실물과
// 다른 모양을 재면 「엉뚱한 걸 잠갔다」가 된다.

describe('축 6 의 post-action 파서 — 선언의 키 순서에 무관하다', () => {
  const TYPE_FIRST = `
    addMutation.mutate({
      type: 'CALL_WEBHOOK',
      config: { url: values.url, method: values.method },
      displayOrder: nextDisplayOrder,
    })
  `

  /** 같은 선언에서 `config` 만 `type` 앞으로 옮긴 것. 화면이 언제든 이렇게 쓸 수 있다. */
  const CONFIG_FIRST = `
    addMutation.mutate({
      config: { url: values.url, method: values.method },
      type: 'CALL_WEBHOOK',
      displayOrder: nextDisplayOrder,
    })
  `

  test('정방향 선언을 실제로 읽는다 (비-공허 짝)', () => {
    // 이 짝이 없으면 아래 순서 무관 단언이 「양쪽 다 0건」으로 공허하게 통과한다.
    assert.deepEqual(literalConfigPayloads(TYPE_FIRST).declarations, [
      { type: 'CALL_WEBHOOK', keys: ['method', 'url'], unreadable: [] },
    ])
  })

  test('type 이 config 보다 뒤에 와도 같은 선언을 읽는다', () => {
    assert.deepEqual(
      literalConfigPayloads(CONFIG_FIRST).declarations,
      literalConfigPayloads(TYPE_FIRST).declarations,
      '같은 객체 리터럴인데 키 순서만 다른 두 선언을 파서가 다르게 읽는다 — ' +
        '순서가 뒤집힌 쪽이 조용히 사라지면 그 type 은 축 6 의 대조에서 빠진 채 초록이 된다.',
    )
  })

  test('config 가 없는 `type` 프로퍼티는 여전히 건너뛴다 — 요청 바디가 아니다', () => {
    // 순서 무관으로 넓히면서 「그냥 type 이라는 이름의 프로퍼티」까지 주워 담으면 안 된다.
    assert.deepEqual(
      literalConfigPayloads(`const column = { type: 'text', label: postActionLabels.name }`)
        .declarations,
      [],
    )
  })

  test('옆 객체의 config 를 이 type 의 것으로 붙이지 않는다', () => {
    // 경계를 넓힌 뒤에도 감싸는 객체 밖은 보지 않아야 한다.
    const SIBLING = `
      const payloads = [
        { type: 'CALL_WEBHOOK' },
        { config: { url: values.url } },
      ]
    `
    assert.deepEqual(literalConfigPayloads(SIBLING).declarations, [])
  })
})
