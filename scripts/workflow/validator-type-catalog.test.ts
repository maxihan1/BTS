// SDD §7.3·§7.4 의 Validator/Post-function 표가 두 팩토리의 실제 `when (type)` 분기와 일치하는지 강제하는 판별식
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
// 목록 ① **SDD 표의 타입 식별자 열** 과 목록 ② **팩토리 `when (type)` 분기의 문자열 리터럴** 이
// 서로를 안 본다. 이 판별식이 ①−② 와 ②−① 차집합을 **양방향으로** 0 으로 강제한다.
//
// ## 왜 목록을 상수로 적지 않나
//
// 「있어야 할 type 목록」을 여기 적으면 그것이 **세 번째 목록**이 되고, 판별식이 자기가 잡으려는
// 결함 양식을 스스로 재생산한다. 두 집합 모두 런타임에 파일에서 읽는다 —
// 표는 `docs/sdd/07-workflow-engine.md`, 분기는 팩토리 `.kt` 원문이다.
// 사람이 손으로 유지하는 type 목록은 이 파일에 **하나도 없다**.
//
// ## 어느 쪽이 정본인가
//
// **코드다** (Maxi 결정, 2026-08-25). 팩토리 `when` 분기가 런타임 계약이고 표는 그 서술이다.
// 그래서 실패 메시지는 항상 「표를 코드에 맞춰라」로 적는다.
//
// ## 표의 어느 열을 읽나
//
// 표는 3열(`타입 식별자` · `구현 클래스` · `용도`)이고 파서는 **런타임 식별자 열만** 읽는다.
// 그 열은 위치(0번째)가 아니라 **제목이 `타입` 으로 시작하는 열**로 찾는다 — 판별식과 표가
// 같은 열에서 만난다는 계약을 기계가 들고 있게 하려는 것이다. 위치로 찾으면 열을 하나 끼워 넣는
// 순간 엉뚱한 열을 읽고, 제목 전체를 못박으면 제목을 다듬는 순간 추출이 0건이 되어
// 「표가 틀렸다」가 아니라 「표를 못 읽었다」는 덜 쓸모 있는 red 로 바뀐다.
// 제목에서 `타입` 이 사라지면 추출이 0건이 되고, 그건 아래 비-공허 짝이 잡는다.
//
// ## 비-공허 짝이 왜 별도 테스트인가
//
// 두 파서 중 하나라도 0건을 뱉으면 차집합 중 하나는 공허하게 0 이 된다. 그때 위 두 테스트는
// **아무것도 안 지키면서 초록**이다. 그래서 네 집합이 전부 비어 있지 않음을 따로 단언한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SDD_FILE = path.join(REPO_ROOT, 'docs/sdd/07-workflow-engine.md')
const ENGINE_DIR = path.join(
  REPO_ROOT,
  'backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine',
)
const VALIDATOR_FACTORY = path.join(ENGINE_DIR, 'DefaultWorkflowValidatorFactory.kt')
const POST_ACTION_FACTORY = path.join(ENGINE_DIR, 'DefaultWorkflowPostActionFactory.kt')

/**
 * SDD 표에서 런타임 식별자가 실린 열을 찾는 제목 접두사. 판별식과 표가 이 문자열 하나로 만난다.
 *
 * 접두사인 이유는 위 「표의 어느 열을 읽나」 참조 — `타입` 도 `타입 식별자` 도 같은 열로 본다.
 * 나머지 열(`구현 클래스` · `용도`)은 걸리지 않는다.
 */
const TYPE_COLUMN_PREFIX = '타입'

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
      out[i + 1] = '_'
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
 * `when (type) { … }` 블록의 **본문 원문**을 잘라낸다.
 *
 * 표지를 찾은 지점부터만 마스킹한다 — 앞쪽 KDoc 에 홀수 개의 따옴표가 있어도 영향을 안 받는다.
 * 표지나 짝이 맞는 닫는 중괄호를 못 찾으면 빈 문자열을 돌려주고, 비-공허 짝이 그것을 red 로 만든다.
 */
function whenTypeBlock(source: string): string {
  const head = source.indexOf(WHEN_HEAD)
  if (head < 0) return ''
  const tail = source.slice(head)
  const masked = maskStringLiterals(tail)
  const open = masked.indexOf('{')
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

/**
 * `when` 분기 한 줄에서 문자열 리터럴을 뽑는 형태. 예: `    "SET_FIELD" -> createSetField(config)`.
 *
 * 줄 처음에 붙여 두어 분기 머리에서만 잡는다. `else -> …` 는 따옴표가 없으니 자연히 빠진다.
 * (`"A", "B" ->` 같은 다중 리터럴 분기는 지금 코드에 없다. 생기면 여기서 누락되어 차집합이
 * red 를 내므로 조용히 새지 않는다.)
 */
const WHEN_BRANCH = /^\s*"([^"]+)"\s*->/

/** 팩토리 `.kt` 원문에서 `when (type)` 분기의 type 문자열을 전부 뽑는다. */
function factoryTypes(filePath: string): string[] {
  return whenTypeBlock(fs.readFileSync(filePath, 'utf-8'))
    .split('\n')
    .map((line) => WHEN_BRANCH.exec(line)?.[1])
    .filter((type): type is string => type !== undefined)
    .sort()
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

/**
 * 절 본문의 표에서 런타임 식별자 열만 뽑는다.
 *
 * 열을 제목으로 찾는다 — 열 순서가 바뀌어도 같은 열을 읽고, 제목에서 `타입` 이 사라지면 0건이 되어
 * 비-공허 짝이 red 를 낸다. 백틱은 벗긴다(표는 `` `SET_FIELD` `` 로 적는다).
 */
function sddTypes(section: string): string[] {
  const rows = section.split('\n').filter((line) => line.trimStart().startsWith('|'))
  if (rows.length === 0) return []
  const column = tableCells(rows[0]).findIndex((cell) => cell.startsWith(TYPE_COLUMN_PREFIX))
  if (column < 0) return []
  return rows
    .slice(1)
    .filter((row) => !/^[\s|:-]+$/.test(row)) // `|---|---|` 구분선
    .map((row) => (tableCells(row)[column] ?? '').replaceAll('`', '').trim())
    .filter((cell) => cell.length > 0)
    .sort()
}

/** `left` 에만 있고 `right` 에는 없는 값. */
function onlyIn(left: string[], right: string[]): string[] {
  const other = new Set(right)
  return left.filter((value) => !other.has(value))
}

describe('SDD §7.3·§7.4 표의 type 집합 = 워크플로우 팩토리의 when 분기 집합', () => {
  const sdd = fs.readFileSync(SDD_FILE, 'utf-8')
  const catalogs = {
    validator: {
      label: '§7.3 Validator',
      documented: sddTypes(sddSection(sdd, '7.3')),
      implemented: factoryTypes(VALIDATOR_FACTORY),
      factory: path.basename(VALIDATOR_FACTORY),
    },
    postAction: {
      label: '§7.4 Post-function',
      documented: sddTypes(sddSection(sdd, '7.4')),
      implemented: factoryTypes(POST_ACTION_FACTORY),
      factory: path.basename(POST_ACTION_FACTORY),
    },
  } as const

  /** 어느 쪽이 무엇을 빠뜨렸는지 이름으로 말하는 양방향 차집합 단언. */
  function assertSameCatalog(catalog: (typeof catalogs)[keyof typeof catalogs]): void {
    const { label, documented, implemented, factory } = catalog
    const docOnly = onlyIn(documented, implemented)
    const codeOnly = onlyIn(implemented, documented)
    assert.deepEqual(
      { 'SDD 표에만 있는 type': docOnly, '팩토리에만 있는 type': codeOnly },
      { 'SDD 표에만 있는 type': [], '팩토리에만 있는 type': [] },
      `${label} 표가 ${factory} 의 when 분기와 어긋난다 — **코드가 정본**이니 표를 고쳐라.\n` +
        `  SDD 표에만 있는 type. ${docOnly.join(', ') || '(없음)'}\n` +
        `  팩토리에만 있는 type. ${codeOnly.join(', ') || '(없음)'}\n` +
        `  표의 런타임 식별자 열 전체. ${documented.join(', ')}\n` +
        `  팩토리 when 분기 전체. ${implemented.join(', ')}\n` +
        `  (표에 적은 값이 런타임에 그대로 들어온다. 어긋나면 "지원하지 않는 type" 예외가 난다.)`,
    )
  }

  test('SDD §7.3 표의 type 집합 = DefaultWorkflowValidatorFactory when 분기 집합', () => {
    assertSameCatalog(catalogs.validator)
  })

  test('SDD §7.4 표의 type 집합 = DefaultWorkflowPostActionFactory when 분기 집합', () => {
    assertSameCatalog(catalogs.postAction)
  })

  test('두 집합이 비어 있지 않다 (비-공허 짝)', () => {
    // 파서가 0건을 뱉으면 위 두 차집합이 공허하게 0 이 되어 판별식이 아무것도 안 지키면서 초록이 된다.
    const probes = [
      [`${catalogs.validator.label} 표`, catalogs.validator.documented, SDD_FILE],
      [`${catalogs.postAction.label} 표`, catalogs.postAction.documented, SDD_FILE],
      ['DefaultWorkflowValidatorFactory when 분기', catalogs.validator.implemented, VALIDATOR_FACTORY],
      ['DefaultWorkflowPostActionFactory when 분기', catalogs.postAction.implemented, POST_ACTION_FACTORY],
    ] as const

    for (const [label, values, source] of probes) {
      assert.ok(
        values.length > 0,
        `${label} 에서 type 을 **0건** 뽑았다 — 파서가 죽었고 위 차집합 단언은 공허하다.\n` +
          `  읽은 파일. ${path.relative(REPO_ROOT, source)}\n` +
          `  표는 '${TYPE_COLUMN_PREFIX}' 으로 시작하는 제목의 열이, 팩토리는 '${WHEN_HEAD}' 블록이 있어야 한다.`,
      )
    }
  })
})
