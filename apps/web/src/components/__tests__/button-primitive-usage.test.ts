// 배치1 파일의 원시 <button> 잔존을 사유 주석 기준으로 전수 통제하는 회귀 가드 (FR-UX-06 PR22 T6)
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/** apps/web/src 루트 — 이 파일 기준 ../.. */
const SRC_ROOT = resolve(import.meta.dirname, '../..')

/**
 * 배치 1 = 공용 `Button`을 **이미 소비하면서** 원시 `<button>`이 남아 있던 파일 전수.
 *
 * 판별식은 `grep -l "from '@/components/ui/button'"` 단 하나이며 세션 2 재열거로 확정했다.
 * 여기 적힌 21이라는 숫자를 신뢰 근거로 쓰지 않는다 — 아래 첫 테스트가 목록의 **원소 전부**를
 * 실재 여부와 import 보유 여부로 검증한다(개수 가드는 원소가 바뀌어도 숫자만 맞으면 통과한다).
 */
const BATCH1_FILES = [
  'components/admin/AddMemberDialog.tsx',
  'components/admin/AuditLogFilters.tsx',
  'components/admin/WorkflowSchemeSidebar.tsx',
  'components/automation/ActionConfigEditor.tsx',
  'components/automation/ActionListEditor.tsx',
  'components/automation/AutomationRuleFormDialog.tsx',
  'components/automation/ConditionBuilder.tsx',
  'components/automation/RuleExecutionTraceRow.tsx',
  'components/board/QuickFilterChips.tsx',
  'components/dashboard/DashboardForm.tsx',
  'components/filters/FilterBar.tsx',
  'components/global-permissions/GlobalPermissionFormDialog.tsx',
  'components/issue/EpicChildrenSection.tsx',
  'components/issue/IssueChangelog.tsx',
  'components/issue/IssueDescription.tsx',
  'components/issue/IssueLinksPanel.tsx',
  'components/issue/meta/IssueLabelsEdit.tsx',
  'components/ooo/OooModal.tsx',
  'components/search/SavedFilterMenu.tsx',
  'routes/issues.$key.tsx',
  'routes/issues.index.tsx',
] as const

/**
 * 남기기로 판정한 원시 `<button>`에 붙이는 사유 주석 마커.
 * T8의 ESLint `no-restricted-syntax` `overrides` 예외 목록과 1:1 대응시킨다.
 * 형식. `// PR22 OUT — P5 옵션 행: <사유>`
 */
const OUT_MARKER = 'PR22 OUT'

/** OUT 사유 패턴 코드 — P4 role=tab · P5 옵션 행 · P6 전체 클릭 영역 */
const OUT_PATTERN_RE = /PR22 OUT\s*—\s*(P[456])\b/

/**
 * 남아 있어야 하는 OUT 발생 전수 (`파일::패턴코드`).
 *
 * **행 번호로 키를 잡지 않는다** — GREEN이 줄 수를 바꾸면 행 기반 allowlist는 통째로 깨진다.
 * 대신 파일과 사유 패턴으로 키를 잡아 줄 이동에 불변이 되게 했다.
 * `IssueDescription`이 2건인 것은 `role="tab"` 버튼이 write/preview 두 개라서다.
 */
const EXPECTED_OUT = [
  'components/admin/AddMemberDialog.tsx::P5',
  'components/admin/AuditLogFilters.tsx::P5',
  'components/admin/WorkflowSchemeSidebar.tsx::P5',
  'components/automation/RuleExecutionTraceRow.tsx::P6',
  'components/dashboard/DashboardForm.tsx::P5',
  'components/filters/FilterBar.tsx::P5',
  'components/global-permissions/GlobalPermissionFormDialog.tsx::P5',
  'components/issue/IssueChangelog.tsx::P6',
  'components/issue/IssueDescription.tsx::P4',
  'components/issue/IssueDescription.tsx::P4',
  'components/ooo/OooModal.tsx::P5',
] as const

interface RawButton {
  readonly file: string
  /** 1-indexed — 실패 메시지에서 바로 찾아갈 수 있게 파일 행 번호 그대로 쓴다 */
  readonly line: number
  /** 직전 비어있지 않은 줄 — OUT 사유 주석 판정 대상 */
  readonly precedingLine: string
}

/**
 * 주석 **내용만** 공백으로 덮고 줄 구조는 보존한다.
 *
 * 선례인 `state-tokens.test.ts` · `category-color-tokens.test.ts`의 `stripComments`는 주석을
 * 통째로 삭제해 **행 번호가 밀린다**. 이 가드는 (a) 실패 메시지에 `파일:행`을 그대로 찍어야 하고
 * (b) OUT 사유 주석을 원문에서 읽어야 하므로, 줄 수를 유지하는 변형이 필요하다.
 *
 * 줄 단위 판정(`^\s*(\*|\/\/)`)으로는 부족하다 — **여러 줄 주석의 이어지는 줄**은 `*`로 시작하지
 * 않는 산문이라 코드로 오인된다. 실제로 T6 REFACTOR에서 `SavedFilterMenu`의 설명 주석 본문에
 * `<button>` 이라 적었다가 가드가 위반으로 잡은 전례가 있다. 주석 안에 리터럴을 쓰는 것이
 * 벌점이 되면 결국 문서화를 지우게 만드는 잘못된 유인이 생긴다.
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
 * 파일에서 원시 `<button` 여는 태그를 전수 추출한다.
 *
 * 주석은 코드가 아니므로 제외한다 — 세션 1이 `routes/issues.index.tsx`의 JSDoc 예시 리터럴
 * `<button type="button" disabled>` 을 코드로 세는 바람에 OUT 개수를 11이 아니라 12로 적은 전례가 있다.
 * 닫는 태그 `</button>`은 `<button` 과 접두사가 달라 자연히 걸리지 않는다.
 *
 * 탐지는 주석을 덮은 사본으로 하고, 사유 주석 판정은 **원문** 줄로 한다.
 */
function rawButtons(file: string): RawButton[] {
  const original = readFileSync(resolve(SRC_ROOT, file), 'utf-8')
  const originalLines = original.split('\n')
  const codeLines = blankComments(original).split('\n')
  const found: RawButton[] = []

  codeLines.forEach((codeLine, index) => {
    if (!codeLine.includes('<button')) return

    // 직전 "비어있지 않은" 줄을 찾는다. 빈 줄이 끼어도 사유 주석을 인정한다.
    let cursor = index - 1
    while (cursor >= 0 && (originalLines[cursor] ?? '').trim() === '') cursor -= 1

    found.push({
      file,
      line: index + 1,
      precedingLine: originalLines[cursor] ?? '',
    })
  })

  return found
}

const ALL_RAW_BUTTONS = BATCH1_FILES.flatMap((f) => rawButtons(f))

describe('FR-UX-06 PR22 T6 — 배치1의 원시 <button>은 Button 프리미티브로 흡수하거나 사유를 남긴다', () => {
  it('스캔 대상 21파일이 전부 실재하고 공용 Button을 소비한다 (glob/경로 오타로 인한 공허 통과 차단)', () => {
    const missing = BATCH1_FILES.filter((f) => !existsSync(resolve(SRC_ROOT, f)))
    expect(missing).toEqual([])

    // 배치1의 정의 자체가 "Button import 보유"다. 이게 깨지면 목록이 stale이라는 뜻.
    const withoutImport = BATCH1_FILES.filter(
      (f) => !readFileSync(resolve(SRC_ROOT, f), 'utf-8').includes("from '@/components/ui/button'"),
    )
    expect(withoutImport).toEqual([])
  })

  it('한 줄 주석(JSDoc) 속 <button> 리터럴은 코드로 세지 않는다 — issues.index.tsx', () => {
    const file = 'routes/issues.index.tsx'
    const original = readFileSync(resolve(SRC_ROOT, file), 'utf-8')

    // 주석 리터럴이 실제로 파일에 남아 있어야 이 테스트가 의미를 갖는다(공허 통과 차단).
    expect(original).toContain('`<button type="button" disabled>`')

    // 순진한 grep과 주석 제외 후의 차이가 딱 그 1건이어야 한다.
    // (RED에서는 코드 1건이 남고 GREEN에서는 0건이 되므로, 절대 개수 대신 **차이**로 고정한다.)
    const naive = original.split('\n').filter((line) => line.includes('<button')).length
    const code = blankComments(original).split('\n').filter((line) => line.includes('<button')).length
    expect(naive - code).toBe(1)
    expect(rawButtons(file)).toHaveLength(code)
  })

  it('여러 줄 JSX 주석 속 <button> 리터럴도 코드로 세지 않는다 — SavedFilterMenu 회귀', () => {
    const file = 'components/search/SavedFilterMenu.tsx'
    const original = readFileSync(resolve(SRC_ROOT, file), 'utf-8')

    // 이 파일의 PR22 설명 주석은 본문에 `<button>` 이라고 적는다. 그 줄은 `*`/`//` 로 시작하지 않는
    // 이어지는 산문이라 줄 단위 판정으로는 코드로 오인된다 — 실제로 T6에서 한 번 오인했다.
    expect(original).toContain('...props 를 <button> 으로 전개하기 때문에')

    // 이 파일의 트리거는 IN으로 교체됐으므로 코드상 원시 button은 0이어야 한다.
    expect(rawButtons(file)).toEqual([])
  })

  it('남아 있는 모든 원시 <button>은 직전 줄에 PR22 OUT 사유 주석을 갖는다', () => {
    const unjustified = ALL_RAW_BUTTONS.filter(
      (b) => !b.precedingLine.includes(OUT_MARKER),
    ).map((b) => `${b.file}:${b.line}`)

    // 개수 상한이 아니라 **목록 전수 비교** — 개수 가드는 새 위반이 늘어도 숫자만 올리면 통과한다.
    expect(unjustified).toEqual([])
  })

  it('사유 주석이 붙은 OUT 발생이 기대 목록과 정확히 일치한다 (누락도 초과도 차단)', () => {
    const actual = ALL_RAW_BUTTONS.map((b) => {
      const matched = OUT_PATTERN_RE.exec(b.precedingLine)
      return `${b.file}::${matched?.[1] ?? 'NO_PATTERN_CODE'}`
    }).sort()

    expect(actual).toEqual([...EXPECTED_OUT].sort())
  })
})
