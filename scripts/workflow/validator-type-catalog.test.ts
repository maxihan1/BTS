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
// ## 무엇을 대조하나 — 축 4개
//
// | 축 | 목록 ① | 목록 ② | 갈리면 무슨 일이 나나 |
// |---|---|---|---|
// | 타입 | 표 `타입 식별자` 열 | 팩토리 `when (type)` 분기 | 표대로 쓰면 「지원하지 않는 type」 예외 |
// | config 키 | 표 `필수 config 키` 열 | 팩토리 `create*` 의 config 읽기 | 화면이 키를 손으로 들고, 틀리면 400 |
// | 구현 클래스 | 표 `구현 클래스` 열 | `override val type` 을 선언한 `.kt` | 개명·삭제해도 표는 초록으로 남는다 |
// | 인스턴스 type | 팩토리 `when (type)` 분기 | 구현체 `override val type` | 아래 「인스턴스 type 축」 참조 |
//
// **config 키 축이 필요한 이유.** 요청·응답의 `config` 는 `Map<String, Any?>` 로 그대로 왕복해서
// 타입별 필수 키가 계약 어디에도 없었다. 실제로 `PostActionFormDialog.tsx` 가 `CALL_WEBHOOK` 의
// `url`/`method` 를 프론트에서 손으로 들고 있다 — 팩토리가 키를 하나 바꾸면 화면만 조용히 틀려진다.
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
// ## 왜 목록을 상수로 적지 않나
//
// 「있어야 할 type 목록」을 여기 적으면 그것이 **또 하나의 목록**이 되고, 판별식이 자기가 잡으려는
// 결함 양식을 스스로 재생산한다. 모든 집합을 런타임에 파일에서 읽는다 —
// 표는 `docs/sdd/07-workflow-engine.md`, 분기와 config 키는 팩토리 `.kt` 원문,
// 구현 클래스와 인스턴스 type 은 `validator/`·`postaction/` 패키지 `.kt` 원문이다.
// 사람이 손으로 유지하는 type·키·클래스 목록은 이 파일에 **하나도 없다**.
//
// ## 어느 쪽이 정본인가
//
// **코드다** (Maxi 결정, 2026-08-25). 팩토리 `when` 분기가 런타임 계약이고 표는 그 서술이다.
// 그래서 실패 메시지는 항상 「표를 코드에 맞춰라」로 적는다.
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

/**
 * 문자열 리터럴 **내부**를 같은 길이의 `_` 로 덮는다.
 *
 * 중괄호 짝을 셀 때 문자열 안의 `{` `}`(예: `"${'$'}{foo}"`)에 속지 않기 위한 것이다.
 * 길이를 보존하므로 여기서 얻은 인덱스를 **원문에 그대로** 쓸 수 있다.
 */
function maskStringLiterals(source: string): string {
  const out = source.split('')
  let inString = false
  for (let i = 0; i < out.length; i += 1) {
    const ch = out[i]
    if (inString && ch === '\\') {
      // 이스케이프된 다음 글자도 함께 덮는다. 끝을 넘겨 쓰면 길이가 늘어 인덱스 보존이 깨지므로 막는다.
      if (i + 1 < out.length) out[i + 1] = '_'
      i += 1
      continue
    }
    if (ch === '"') {
      inString = !inString
      continue
    }
    if (inString && ch !== '\n') out[i] = '_'
  }
  return out.join('')
}

/**
 * Kotlin 원문에서 주석(`//` · `/* … *​/`)을 **공백으로** 지운다. 줄바꿈과 전체 길이는 보존한다.
 *
 * KDoc 이 본문과 같은 토큰(`config["field"]`)을 적기 때문에 파서가 주석을 읽으면 옆 함수의 문서가
 * 이 함수의 키로 새어 들어온다. 문자열 리터럴 안의 `//` 는 주석이 아니므로 문자열 상태를 함께 센다.
 */
function stripComments(source: string): string {
  const out = source.split('')
  let state: 'code' | 'string' | 'line' | 'block' = 'code'
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i]
    const next = source[i + 1]
    if (state === 'string') {
      if (ch === '\\') i += 1
      else if (ch === '"') state = 'code'
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
    if (ch === '"') {
      state = 'string'
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
function balancedBlock(source: string, from: number): string {
  if (from < 0) return ''
  const tail = source.slice(from)
  const masked = maskStringLiterals(tail)
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
      documentedClasses: sddColumn(table, IMPL_COLUMN_PREFIX),
      implementedClasses: implementations.map((impl) => impl.className).sort(),
      documentedConfigKeys: sddConfigKeys(table),
      implementedConfigKeys: factoryConfigKeys(factory),
      instanceTypes: implementations.map((impl) => impl.type).sort(),
    }
  }

  const catalogs = {
    validator: catalogOf('7.3', '§7.3 Validator', VALIDATOR_FACTORY, VALIDATOR_DIR),
    postAction: catalogOf('7.4', '§7.4 Post-function', POST_ACTION_FACTORY, POST_ACTION_DIR),
  } as const

  type Catalog = (typeof catalogs)[keyof typeof catalogs]

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

  test('SDD §7.3 표의 구현 클래스 열 = validator 패키지 구현체 클래스 집합', () => {
    assertClassCatalog(catalogs.validator)
  })

  test('SDD §7.4 표의 구현 클래스 열 = postaction 패키지 구현체 클래스 집합', () => {
    assertClassCatalog(catalogs.postAction)
  })

  /** 축 3 — 표의 구현 클래스 열 ↔ `override val type` 을 선언한 실제 클래스. */
  function assertClassCatalog(catalog: Catalog): void {
    assertSameSet(
      { label: `${catalog.label} 표의 구현 클래스 열`, values: catalog.documentedClasses },
      {
        label: `${path.relative(REPO_ROOT, catalog.implDir)} 의 구현체 클래스`,
        values: catalog.implementedClasses,
      },
      '구현체는 `override val type` 을 선언한 클래스로 센다. 개명·삭제했다면 표도 함께 고쳐라.',
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

  test('뽑아낸 집합이 하나도 비어 있지 않다 (비-공허 짝)', () => {
    // 파서가 0건을 뱉으면 위 차집합 단언들이 공허하게 0 이 되어 아무것도 안 지키면서 초록이 된다.
    const probes: readonly (readonly [string, readonly unknown[], string])[] = [
      [`${catalogs.validator.label} 표의 타입 식별자 열`, catalogs.validator.documented, SDD_FILE],
      [`${catalogs.postAction.label} 표의 타입 식별자 열`, catalogs.postAction.documented, SDD_FILE],
      [
        `${catalogs.validator.label} 표의 구현 클래스 열`,
        catalogs.validator.documentedClasses,
        SDD_FILE,
      ],
      [
        `${catalogs.postAction.label} 표의 구현 클래스 열`,
        catalogs.postAction.documentedClasses,
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
      ['validator 패키지 구현체 클래스', catalogs.validator.implementedClasses, VALIDATOR_DIR],
      ['postaction 패키지 구현체 클래스', catalogs.postAction.implementedClasses, POST_ACTION_DIR],
      ['validator 구현체의 override val type', catalogs.validator.instanceTypes, VALIDATOR_DIR],
      ['postaction 구현체의 override val type', catalogs.postAction.instanceTypes, POST_ACTION_DIR],
    ]

    for (const [label, values, source] of probes) {
      assert.ok(
        values.length > 0,
        `${label}에서 **0건**을 뽑았다 — 파서가 죽었고 위 차집합 단언은 공허하다.\n` +
          `  읽은 파일. ${path.relative(REPO_ROOT, source)}\n` +
          `  표는 '${TYPE_COLUMN_PREFIX}' · '${IMPL_COLUMN_PREFIX}' · '${CONFIG_COLUMN_PREFIX}' 로 ` +
          `시작하는 제목의 열이, 팩토리는 '${WHEN_HEAD}' 블록이, 구현체는 ` +
          `'override val type' 선언이 있어야 한다.`,
      )
    }
  })
})
