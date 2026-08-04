// 이슈 상세 서브트리가 렌더하는 모달 전수 ↔ 단축키 게이트가 아는 것의 차집합을 소스 스캔으로 재는 회귀 가드 (FR-UX-10 F11 리뷰 봉합 C-1)
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { describe, it, expect } from 'vitest'

// ─────────────────────────────────────────────────────────────────────────────
// 왜 이 가드가 필요한가 — 그리고 왜 **소스 스캔**인가
//
// `issues.$key.tsx` 의 단축키 게이트는 원래 모달 4종을 **손으로 열거**했다. 그런데 이
// 페이지가 실제로 렌더하는 모달은 그것만이 아니었다 — 댓글 삭제 확인(`CommentSection`)과
// 첨부 미리보기(`AttachmentPreviewModal`)가 열거에서 빠져 있었고, 둘 다 입력 요소가 없어
// `shouldIgnoreEvent` 를 통과하는 탓에 **확인 다이얼로그가 떠 있는데 `i` 가 담당자 PATCH 를
// 실제로 발행**했다. 열거를 2건 늘리는 것으로 끝내면 다음 모달이 생길 때 똑같이 뚫린다 —
// 두 목록이 서로를 검사하지 않는 지배 결함 양식이다(`two-lists-never-check-each-other`).
//
// 그래서 게이트는 **한 신호**(`useOpenModalRegistry`)로 바뀌었고, 이 파일이 그 신호의
// 모집단을 지킨다. 렌더 테스트로는 못 닫는다 — 렌더 테스트는 그 모달을 **여는 경로를 아는
// 시나리오에서만** 돌아, 아무도 열어보지 않은 신규 모달을 통째로 놓친다. 라우트에서
// import 그래프를 따라 전수로 훑어야 "이 페이지가 렌더할 수 있는 모달 전부"가 모집단이 된다.
// ─────────────────────────────────────────────────────────────────────────────

/** apps/web/src 루트 — 이 파일 기준 ../.. */
const SRC_ROOT = resolve(import.meta.dirname, '../..')

/** 스캔 시작점 — 이슈 상세 라우트 */
const ENTRY = 'routes/issues.$key.tsx'

/** 모달 열림을 레지스트리에 보고하는 훅 호출 — 소스에 이 문자열이 있으면 자가 보고로 본다 */
const REPORT_CALL = 'useReportModalOpen('

/**
 * 모달을 **소유**하는 선언의 표지.
 *
 * `<AttachmentPreviewModal …>` 처럼 남의 모달을 **배치만** 하는 JSX 는 일부러 제외한다 —
 * 열림 상태를 실제로 들고 있는 파일만 보고 의무를 진다. 그 파일이 보고하면 어디에
 * 배치되든 신호가 선다.
 */
const MODAL_OWNER_MARKERS: readonly RegExp[] = [
  /<Dialog\b/,
  /<DialogContent\b/,
  /AlertDialog\.Root/,
  /<AlertDialogContent\b/,
  /<Sheet\b/,
  /<Drawer\b/,
]

/**
 * 부모가 열림 상태를 소유해 **라우트가 대신 보고하는** 모달 — 파일 → 라우트 쪽 상태 이름.
 *
 * 이 3종은 `open` 을 prop 으로 받기만 하므로 자기 파일에서 보고할 값이 없다. 대신
 * 라우트의 `useReportModalOpen(...)` 인자 식에 그 상태가 실제로 들어 있는지를 아래
 * 「라우트가 대신 보고하는 상태가 실재한다」가 되잰다 — 이름만 적어 두고 배선을 빠뜨리는
 * 부패한 예외 목록을 차단한다.
 */
const ROUTE_REPORTED_MODALS: ReadonlyMap<string, string> = new Map([
  ['components/issue/ResolutionModal.tsx', 'pendingDoneTransition'],
  ['components/issues/CloneIssueDialog.tsx', 'cloneDialogOpen'],
  ['components/issues/MoveIssueDialog.tsx', 'moveDialogOpen'],
])

/**
 * Dialog 프리미티브가 **아닌데도** 단축키를 막아야 하는 라우트 지역 상태 — 상태 이름 → 사유.
 *
 * 모달 스캔은 Dialog 계열 표지로 모집단을 만들므로 이런 손수 만든 차단 UI 를 못 본다.
 * 그래서 열거가 필요하고, 그 열거가 조용히 사라지지 않도록 여기서 되잰다.
 */
const NON_DIALOG_BLOCKING_STATES: ReadonlyMap<string, string> = new Map([
  [
    'confirmDelete',
    '이슈 삭제 확인은 Dialog 가 아니라 메타패널 자리를 대체하는 <aside> 다. role=dialog 도 Dialog 프리미티브도 아니라 스캔 모집단에 잡히지 않는다.',
  ],
])

// ─────────────────────────────────────────────────────────────────────────────
// import 그래프 스캐너
// ─────────────────────────────────────────────────────────────────────────────

/** 확장자 없는 모듈 경로를 실제 파일로 해석한다 (tsconfig `@/*` = `src/*`) */
function resolveModule(spec: string, fromFile: string): string | null {
  let base: string
  if (spec.startsWith('@/')) base = resolve(SRC_ROOT, spec.slice(2))
  else if (spec.startsWith('.')) base = resolve(SRC_ROOT, dirname(fromFile), spec)
  else return null // 외부 패키지 — 스캔 대상 아님

  for (const candidate of [`${base}.tsx`, `${base}.ts`, `${base}/index.tsx`, `${base}/index.ts`]) {
    if (existsSync(candidate)) return candidate.slice(SRC_ROOT.length + 1)
  }
  return null
}

/**
 * 진입 파일에서 로컬 import 를 따라 도달 가능한 소스 파일을 전수 수집한다.
 *
 * `from '…'` 만 본다 — `import type` 도 같은 형태라 함께 들어오지만, 타입 전용 모듈은
 * 모달 표지를 갖지 않으므로 모집단만 넓히고 판정은 바꾸지 않는다.
 *
 * @param entry SRC_ROOT 기준 상대 경로
 * @returns 도달 가능한 파일 경로 집합 (진입 파일 포함)
 */
function collectReachableFiles(entry: string): ReadonlySet<string> {
  const seen = new Set<string>()
  const queue: string[] = [entry]

  while (queue.length > 0) {
    const file = queue.shift()
    if (file === undefined || seen.has(file)) continue
    seen.add(file)

    const source = readFileSync(resolve(SRC_ROOT, file), 'utf-8')
    const pattern = /from\s+['"]([^'"]+)['"]/g
    let match = pattern.exec(source)
    while (match !== null) {
      const spec = match[1]
      const resolved = spec === undefined ? null : resolveModule(spec, file)
      if (resolved !== null && !seen.has(resolved)) queue.push(resolved)
      match = pattern.exec(source)
    }
  }
  return seen
}

const REACHABLE_FILES = collectReachableFiles(ENTRY)

/** 도달 가능한 파일 중 모달을 **소유**하는 것 전수 */
const MODAL_OWNER_FILES: readonly string[] = [...REACHABLE_FILES]
  .filter((file) => {
    const source = readFileSync(resolve(SRC_ROOT, file), 'utf-8')
    return MODAL_OWNER_MARKERS.some((marker) => marker.test(source))
  })
  .sort()

/** 자기 파일에서 열림을 보고하는 모달 */
const SELF_REPORTING_FILES = new Set(
  MODAL_OWNER_FILES.filter((file) =>
    readFileSync(resolve(SRC_ROOT, file), 'utf-8').includes(REPORT_CALL),
  ),
)

/** 라우트 소스 — 보고 식 추출용 */
const ROUTE_SOURCE = readFileSync(resolve(SRC_ROOT, ENTRY), 'utf-8')

/**
 * 라우트의 `useReportModalOpen(` 인자 식을 통째로 잘라낸다.
 *
 * 괄호 깊이를 세어 끊는다 — 인자가 `a !== null || b` 처럼 여러 줄에 걸치고 안쪽에
 * 괄호가 들어와도 정확히 닫힌다.
 *
 * @returns 인자 식 문자열. 호출이 없으면 빈 문자열
 */
function routeReportExpression(): string {
  const start = ROUTE_SOURCE.indexOf(REPORT_CALL)
  if (start === -1) return ''

  const open = start + REPORT_CALL.length - 1
  let depth = 0
  for (let i = open; i < ROUTE_SOURCE.length; i += 1) {
    const ch = ROUTE_SOURCE[i]
    if (ch === '(') depth += 1
    else if (ch === ')') {
      depth -= 1
      if (depth === 0) return ROUTE_SOURCE.slice(open + 1, i)
    }
  }
  return ''
}

const ROUTE_REPORT_EXPRESSION = routeReportExpression()

// ─────────────────────────────────────────────────────────────────────────────
// 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('FR-UX-10 F11 — 상세 서브트리 모달 전수 ↔ 단축키 게이트 차집합 0', () => {
  it('import 그래프 스캔이 실제로 돌았다 (해석 실패로 인한 공허 통과 차단)', () => {
    // 라우트 하나에서 100개 넘는 모듈이 걸려 나온다. 30개 밑으로 떨어졌다면
    // `@/` 해석이나 정규식이 깨져 모집단이 조용히 비어 버린 것이다.
    expect(REACHABLE_FILES.size).toBeGreaterThan(30)
    expect(REACHABLE_FILES.has(ENTRY)).toBe(true)
  })

  it('모달 스캐너가 실물 모달을 찾아낸다 (표지 정규식 파손 차단)', () => {
    // 리뷰가 실측으로 지목한 2건이 모집단에 들어오는지 이름으로 못 박는다.
    // 이 두 줄이 red 면 스캐너가 아니라 모집단이 무너진 것이다.
    expect(MODAL_OWNER_FILES).toContain('components/issue/CommentSection.tsx')
    expect(MODAL_OWNER_FILES).toContain('components/issue/AttachmentPreviewModal.tsx')
    expect(MODAL_OWNER_FILES.length).toBeGreaterThanOrEqual(5)
  })

  it('★모달을 소유한 파일은 전부 게이트에 보고한다 — 차집합 0', () => {
    const unreported = MODAL_OWNER_FILES.filter(
      (file) => !SELF_REPORTING_FILES.has(file) && !ROUTE_REPORTED_MODALS.has(file),
    )

    // 개수 상한이 아니라 **목록 전수 비교** — 어느 모달이 새는지 실패 메시지에 그대로 뜬다.
    expect(unreported).toEqual([])
  })

  it('라우트가 대신 보고하는 모달은 그 상태가 실제로 보고 식에 들어 있다', () => {
    const missing = [...ROUTE_REPORTED_MODALS.entries()]
      .filter(([, stateName]) => !ROUTE_REPORT_EXPRESSION.includes(stateName))
      .map(([file, stateName]) => `${file} → ${stateName}`)

    expect(ROUTE_REPORT_EXPRESSION).not.toBe('')
    expect(missing).toEqual([])
  })

  it('Dialog 가 아닌 차단 상태도 보고 식에 남아 있다', () => {
    const missing = [...NON_DIALOG_BLOCKING_STATES.keys()].filter(
      (stateName) => !ROUTE_REPORT_EXPRESSION.includes(stateName),
    )

    expect(missing).toEqual([])
  })

  it('라우트 대신 보고 목록이 낡지 않았다 — 전부 실재하는 모달 파일이다', () => {
    // 파일이 사라지거나 자가 보고로 옮겨갔는데 예외로 남겨두면, 그 목록이 조용히
    // 검사망을 넓힌다(예외가 스스로를 정당화하는 상태).
    const stale = [...ROUTE_REPORTED_MODALS.keys()].filter(
      (file) => !MODAL_OWNER_FILES.includes(file) || SELF_REPORTING_FILES.has(file),
    )

    expect(stale).toEqual([])
  })
})
