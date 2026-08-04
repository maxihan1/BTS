// 화면의 aria-keyshortcuts JSX 리터럴이 CONTEXT_SHORTCUTS 와 어긋나지 않는지 소스 전수로 재는 회귀 가드 (FR-UX-10 F11 Task 7)
import { readFileSync, globSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'
import { CONTEXT_SHORTCUTS } from './context-shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// 왜 이 가드가 필요한가 — 그리고 왜 **렌더가 아니라 소스**를 재는가
//
// Task 4 가 5개 컨트롤에 붙인 `aria-keyshortcuts` 의 키 문자는 **JSX 리터럴**이다.
// 레지스트리(`CONTEXT_SHORTCUTS`)에서 키를 재배치하면 판별·도움말·예약 가드는 전부
// 따라 바뀌는데 이 리터럴만 제자리에 남아, 스크린리더가 **옛 키를 계속 안내**한다.
// 두 목록이 서로를 검사하지 않는 지배 결함 양식이다(`two-lists-never-check-each-other`).
//
// 런타임 렌더 테스트로는 못 닫는다 — 렌더 테스트는 그 컴포넌트가 **쓰이는 화면에서만**
// 돌아, 아무도 렌더하지 않는 신규/고아 선언을 통째로 놓친다. 소스를 전수로 훑어야
// "선언이 존재하는 곳 전부"가 검사 모집단이 된다.
// ─────────────────────────────────────────────────────────────────────────────

/** apps/web/src 루트 — 이 파일 기준 ../.. */
const SRC_ROOT = resolve(import.meta.dirname, '../..')

/** 스캔 대상 = 프로덕션 소스 전량. 테스트 파일은 리터럴을 흉내 내므로 제외한다. */
const SOURCE_FILES: readonly string[] = [
  ...globSync('**/*.ts', { cwd: SRC_ROOT }),
  ...globSync('**/*.tsx', { cwd: SRC_ROOT }),
].filter((f) => !f.endsWith('.test.ts') && !f.endsWith('.test.tsx'))

/**
 * `CONTEXT_SHORTCUTS` 소관이 **아닌** `aria-keyshortcuts` 선언 — 파일 경로 → 제외 사유.
 *
 * 컨텍스트 단축키 파이프라인을 타지 않는 화면 지역 키가 여기 온다. 목록에 넣는 것으로
 * 검사를 면제받지만, 아래 「부패 차단」 테스트가 **선언이 실제로 아직 있는지**를 되재므로
 * 기능이 사라진 뒤 남은 유령 항목은 red 가 된다.
 */
const OUT_OF_REGISTRY_FILES: ReadonlyMap<string, string> = new Map([
  [
    'components/timeline/TimelineZoomControl.tsx',
    '타임라인 줌 힌트 1/2/3 (FR-TL-03 D3). 그 화면이 자체 키 핸들러로 처리하는 지역 키라 컨텍스트 레지스트리에 등재되지 않는다.',
  ],
])

/**
 * `issue-detail` 레지스트리 키 중 `aria-keyshortcuts` 를 **붙이지 않기로** 한 것 — 키 → 사유.
 *
 * `aria-keyshortcuts` 는 그 키가 활성화하는 **컨트롤 하나**에 붙이는 속성이라,
 * 1키 ↔ 1컨트롤이 성립하지 않으면 붙일 자리가 없다. Task 4 의 5종(`a`/`m`/`l`/`s`/`w`)은
 * 전부 성립했고 아래 2종은 성립하지 않는다.
 *
 * 이 목록을 그냥 빼두면 다음 사람이 "왜 5개뿐이지"를 다시 조사해야 하므로 사유째 남기고,
 * 아래 「예외 목록 자체 검사」가 **양방향**으로 되잰다 — 레지스트리에서 사라진 키를
 * 계속 예외로 두는 것도, 전용 컨트롤이 생겨 속성을 붙였는데 예외에 남겨두는 것도 red 다.
 */
const KEYS_WITHOUT_ANNOUNCED_CONTROL: ReadonlyMap<string, string> = new Map([
  [
    'i',
    '나에게 할당 — 전용 컨트롤이 아예 없다(담당자 뮤테이션을 직접 호출한다). `a` 와 같은 검색 입력에 붙이면 한 컨트롤이 두 키를 광고해 오안내가 된다.',
  ],
  [
    'e',
    '제목 편집 — 진입로가 둘(제목 텍스트 버튼 · `✎ 제목 수정` 버튼)이라 어느 하나에만 붙이면 안내가 반쪽이 되고, 둘 다 붙이면 스크린리더가 같은 키를 두 번 읽는다.',
  ],
])

// ─────────────────────────────────────────────────────────────────────────────
// 소스 스캐너
// ─────────────────────────────────────────────────────────────────────────────

/** JSX 속성으로서의 선언만 고른다 — `=` 가 없는 산문 언급은 자연히 빠진다 */
const ATTRIBUTE = 'aria-keyshortcuts='

/** `aria-keyshortcuts` 선언 하나 */
interface KeyshortcutDeclaration {
  /** SRC_ROOT 기준 상대 경로 */
  readonly file: string
  /** 1-indexed — 실패 메시지에서 바로 찾아갈 수 있게 파일 행 번호 그대로 쓴다 */
  readonly line: number
  /** 값 표현식에서 뽑은 문자열 리터럴. 동적 표현식이면 빈 배열 */
  readonly keys: readonly string[]
}

/**
 * 주석 **내용만** 공백으로 덮고 줄 구조는 보존한다.
 *
 * 설명 주석에 `aria-keyshortcuts="a"` 같은 예시를 적는 것이 벌점이 되면 결국 문서화를
 * 지우게 만드는 잘못된 유인이 생긴다. `button-primitive-usage.test.ts` 가 주석 속
 * `<button>` 리터럴을 코드로 세는 실수를 실제로 저질렀고, 그 처방을 가져왔다.
 * 줄 수를 유지하는 것은 실패 메시지의 `파일:행` 을 원문과 맞추기 위함이다.
 */
function blankComments(source: string): string {
  return (
    source
      // 블록 주석 + JSX 주석(`{/* … */}`) — 여러 줄이라도 줄바꿈만 남기고 내용을 공백으로 덮는다
      .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, ' '))
      // 줄 앞 한 줄 주석
      .replace(/^[ \t]*\/\/.*$/gm, (match) => ' '.repeat(match.length))
  )
}

/**
 * `aria-keyshortcuts=` 뒤의 **속성 값 표현식만** 잘라낸다(따옴표 포함).
 *
 * 줄 끝까지 읽는 방식을 쓰지 않는 이유는, 같은 줄에 다른 속성이 오면
 * (`aria-keyshortcuts="a" placeholder="검색"`) 그 값까지 키로 삼켜 가드가 거짓 red 를
 * 내기 때문이다. `{}` 는 깊이를 세어 중첩 표현식도 정확히 끊는다.
 */
function attributeValueAt(source: string, start: number): string {
  const head = source[start]
  if (head === '"' || head === "'") {
    const end = source.indexOf(head, start + 1)
    return end === -1 ? '' : source.slice(start, end + 1)
  }
  if (head !== '{') return ''

  let depth = 0
  for (let i = start; i < source.length; i += 1) {
    const ch = source[i]
    if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth === 0) return source.slice(start, i + 1)
    }
  }
  return ''
}

/** 표현식 안의 문자열 리터럴 전량 — 템플릿 리터럴(백틱)은 의도적으로 제외한다(동적 취급) */
function stringLiteralsIn(expression: string): string[] {
  const literals: string[] = []
  const pattern = /'([^']*)'|"([^"]*)"/g
  let match = pattern.exec(expression)
  while (match !== null) {
    const value = match[1] ?? match[2]
    if (value !== undefined) literals.push(value)
    match = pattern.exec(expression)
  }
  return literals
}

/** 소스 문자열에서 선언을 전수 추출한다(파일 I/O 없이 재사용 가능한 순수 함수) */
function declarationsInSource(source: string, file: string): KeyshortcutDeclaration[] {
  if (!source.includes(ATTRIBUTE)) return []
  // 주석 블랭킹은 매칭을 **줄이기만** 하므로 위 사전 필터를 원문에 걸어도 새지 않는다.
  const code = blankComments(source)
  const found: KeyshortcutDeclaration[] = []

  let cursor = code.indexOf(ATTRIBUTE)
  while (cursor !== -1) {
    const valueStart = cursor + ATTRIBUTE.length
    found.push({
      file,
      line: code.slice(0, cursor).split('\n').length,
      keys: stringLiteralsIn(attributeValueAt(code, valueStart)),
    })
    cursor = code.indexOf(ATTRIBUTE, valueStart)
  }
  return found
}

/** 파일 하나의 선언 전수 */
function declarationsIn(file: string): KeyshortcutDeclaration[] {
  return declarationsInSource(readFileSync(resolve(SRC_ROOT, file), 'utf-8'), file)
}

const DECLARATIONS: readonly KeyshortcutDeclaration[] = SOURCE_FILES.flatMap(declarationsIn)

/** 레지스트리가 책임지는 선언(= 제외 목록에 없는 것) */
const IN_REGISTRY_DECLARATIONS = DECLARATIONS.filter((d) => !OUT_OF_REGISTRY_FILES.has(d.file))

/** 화면이 실제로 스크린리더에 안내하는 키 전량 */
const DECLARED_KEYS = new Set(IN_REGISTRY_DECLARATIONS.flatMap((d) => d.keys))

/**
 * 레지스트리 키 전량 — **레이어를 가리지 않는다.**
 *
 * ★비교 범위를 `issue-detail` 로 좁히면 **옳은 변경이 가드에 막힌다.** 예컨대 사이드바
 * 토글 버튼에 `aria-keyshortcuts="["` 를 붙이는 것은 레지스트리에 실재하는 키
 * (`app-shell` 레이어)를 정확히 안내하는 정당한 선언인데, 상세 레이어 목록에만 대면
 * "레지스트리와 불일치" red 가 난다. 그러면 다음 사람은 그 선언을 지우거나 **가드를
 * 약화시키는 쪽**으로 손을 대고, 이 파일이 지키려던 것이 통째로 사라진다.
 * 오늘 두 집합이 우연히 같은 것은 마침 선언이 상세 컨트롤 5곳뿐이기 때문이지 설계가 아니다.
 *
 * 그래서 "선언 → 레지스트리" 방향은 **전량**으로 넓히고, 예외 목록이 지키는
 * "레지스트리 → 선언" 방향만 `issue-detail` 로 남긴다(아래 두 가드).
 */
const REGISTRY_KEYS = new Set(CONTEXT_SHORTCUTS.map((s) => s.key))

/** `issue-detail` 레이어의 레지스트리 키 전량 */
const DETAIL_REGISTRY_KEYS = new Set(
  CONTEXT_SHORTCUTS.filter((s) => s.context === 'issue-detail').map((s) => s.key),
)

/**
 * 레지스트리 **어디에도** 없는 키를 안내하는 선언 지점 — `파일:행`.
 *
 * 실물 스캔과 픽스처가 **같은 술어**를 쓰도록 함수로 뽑는다. 픽스처가 다른 코드 경로를
 * 재면 "범위를 넓힌 뒤에도 가드가 살아 있다"는 증명이 성립하지 않는다.
 */
function orphanDeclarations(declarations: readonly KeyshortcutDeclaration[]): string[] {
  return declarations
    .filter((d) => d.keys.some((key) => !REGISTRY_KEYS.has(key)))
    .map((d) => `${d.file}:${d.line}`)
}

// ─────────────────────────────────────────────────────────────────────────────
// 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('FR-UX-10 F11 — aria-keyshortcuts 리터럴 ↔ CONTEXT_SHORTCUTS 차집합 0', () => {
  it('소스 스캔이 실제로 돌았다 (glob·파서 파손으로 인한 공허 통과 차단)', () => {
    expect(SOURCE_FILES.length).toBeGreaterThan(100)
    expect(DECLARATIONS.length).toBeGreaterThan(0)
    expect(IN_REGISTRY_DECLARATIONS.length).toBeGreaterThan(0)
  })

  it('파서가 주석 속 예시를 세지 않고, 같은 줄의 옆 속성도 삼키지 않는다', () => {
    // 저장소에 아직 그런 주석이 없다는 사실은 파서의 정확성을 보증하지 않는다 —
    // 그래서 실물이 아니라 픽스처로 파서 자체를 잰다.
    const fixture = [
      '/** 예시. aria-keyshortcuts="블록주석" 처럼 쓴다 */',
      '  // 한 줄 주석에서도 aria-keyshortcuts="줄주석"',
      '<input aria-keyshortcuts="a" placeholder="옆속성" />',
      '<b aria-keyshortcuts={focusRef !== undefined ? \'m\' : undefined} />',
      '<i aria-keyshortcuts={ZOOM[level]} />',
    ].join('\n')

    expect(declarationsInSource(fixture, 'fixture.tsx')).toEqual([
      { file: 'fixture.tsx', line: 3, keys: ['a'] },
      { file: 'fixture.tsx', line: 4, keys: ['m'] },
      { file: 'fixture.tsx', line: 5, keys: [] },
    ])
  })

  it('레지스트리 밖으로 빼둔 선언이 아직 실재한다 (부패한 제외 목록 차단)', () => {
    const declaredFiles = new Set(DECLARATIONS.map((d) => d.file))
    const stale = [...OUT_OF_REGISTRY_FILES.keys()].filter((f) => !declaredFiles.has(f))

    // 개수 상한이 아니라 **목록 전수 비교** — 어느 항목이 유령인지 실패 메시지에 그대로 뜬다.
    expect(stale).toEqual([])
  })

  it('★화면이 안내하는 키가 전부 레지스트리에 실재한다 (레이어 무관)', () => {
    // 레지스트리에서 키를 재배치·삭제해도 이 리터럴만 제자리에 남는 지배 결함을 여기서 막는다.
    // 소스 스캔으로는 "이 컨트롤이 어느 레이어 소속인가"를 알 수 없으므로(파일 경로는
    // 레이어를 뜻하지 않는다) 레이어 대조까지는 하지 않는다 — 그건 이 가드의 사거리 밖이고,
    // 억지로 흉내 내면 위 주석의 거짓 red 를 그대로 되살린다.
    expect(orphanDeclarations(IN_REGISTRY_DECLARATIONS)).toEqual([])
  })

  it('넓힌 범위가 다른 레이어의 정당한 선언을 통과시키되, 레지스트리 밖 글자는 그대로 막는다', () => {
    // 좁은 범위였다면 `[`(app-shell)는 red 였다. 넓힌 뒤에도 가드가 공허하지 않다는 증거를
    // 픽스처로 영구 고정한다 — 실물 소스가 언젠가 `[` 를 선언해도 이 짝은 계속 유효하다.
    const otherLayer = declarationsInSource('<button aria-keyshortcuts="[" />', 'shell.tsx')
    expect(orphanDeclarations(otherLayer)).toEqual([])

    const notInRegistry = declarationsInSource('<button aria-keyshortcuts="z" />', 'shell.tsx')
    expect(orphanDeclarations(notInRegistry)).toEqual(['shell.tsx:1'])
  })

  it('★issue-detail 레지스트리 키는 예외를 뺀 전부가 안내된다', () => {
    const unannounced = [...DETAIL_REGISTRY_KEYS].filter(
      (key) => !KEYS_WITHOUT_ANNOUNCED_CONTROL.has(key) && !DECLARED_KEYS.has(key),
    )

    // 개수가 아니라 **어느 키가 빠졌는지**를 실패 메시지에 그대로 띄운다.
    expect(unannounced).toEqual([])
  })

  it('예외 목록이 낡지 않았다 — 레지스트리에 실재하고, 소스에는 선언되지 않았다', () => {
    const exceptions = [...KEYS_WITHOUT_ANNOUNCED_CONTROL.keys()]

    // ← 방향. 레지스트리에서 사라진 키를 계속 예외로 두면 예외 목록이 조용히 검사망을 넓힌다.
    expect(exceptions.filter((key) => !DETAIL_REGISTRY_KEYS.has(key))).toEqual([])

    // → 방향. 전용 컨트롤이 생겨 속성을 붙였다면 예외에서 빼야 한다(그래야 위 안내 검사가 그 키를 맡는다).
    expect(exceptions.filter((key) => DECLARED_KEYS.has(key))).toEqual([])
  })

  it('레지스트리 소관 선언은 전부 문자열 리터럴을 갖는다 (동적 표현식은 검사망을 빠져나간다)', () => {
    const dynamic = IN_REGISTRY_DECLARATIONS.filter((d) => d.keys.length === 0).map(
      (d) => `${d.file}:${d.line}`,
    )

    expect(dynamic).toEqual([])
  })
})
