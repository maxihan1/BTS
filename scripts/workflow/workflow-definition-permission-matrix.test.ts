// 워크플로우 정의 권한의 D6 표 ↔ 판정 코드 ↔ enum 세 목록을 서로 검사시키는 판별식 (FR-WF-08 스펙 §6-3)
//
// ─────────────────────────────────────────────────────────────────────────────
// 이 파일이 있는 이유
// ─────────────────────────────────────────────────────────────────────────────
// 「프로젝트 관리자에게 어디까지 여는가」가 **세 곳**에 적혀 있다.
//
//   ① 스펙 §4 D6 표          — 사람이 읽고 합의한 결정
//   ② isProjectDelegable()   — 판정기가 실제로 갈리는 자리
//   ③ WorkflowDefinitionPermission enum — 권한 목록 그 자체
//
// 셋이 서로를 검사하지 않으면 이 저장소의 지배 결함 양식이 그대로 재현된다.
// - 표만 고치고 코드가 안 따라오면, 문서는 「DELETE 도 허용」인데 운영은 계속 거부다.
// - 코드만 고치고 표가 안 따라오면, **권한이 조용히 넓어진 채** 아무도 그 결정을 승인하지 않았다.
// - enum 에 권한이 하나 늘면 ①②가 모르는 값이 생기고, 그 값은 어느 테스트도 타지 않는다.
//
// ★ ②의 `when` 에 else 가 없으므로 ③→② 누락은 컴파일이 막는다. 그러나 ①은 문서라 컴파일이
//   없다 — 그 자리를 이 판별식이 맡는다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/** D6 표의 정본 */
const SPEC = 'docs/specs/2026-09-08-project-owned-workflows.md'

/** 판정이 갈리는 자리 */
const RESOLVER =
  'backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/' +
  'IdentityAccessWorkflowDefinitionPermissionResolver.kt'

/** 권한 목록의 정본 */
const ENUM = 'backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/WorkflowDefinitionPermission.kt'

/** D6 표를 찾는 머리글 — 이 줄 다음의 표 본문만 읽는다 */
const TABLE_HEADING = '### D6. PROJECT_ADMIN 에게 여는 권한 범위'

/** 판정 분기 함수 이름 */
const BRANCH_FN = 'isProjectDelegable'

type Verdict = 'delegated' | 'systemAdminOnly'

/**
 * 스펙 §4 D6 표를 읽어 `권한 → 판정` 으로 옮긴다.
 *
 * 표의 두 번째 열이 「허용」이면 위임, 「불허」면 시스템 관리자 전용이다. 둘 다 아니거나 둘 다면
 * 표가 모호한 것이므로 그 자리에서 실패시킨다 — 모호한 표를 통과시키면 대조가 무의미해진다.
 */
function readSpecTable(): Map<string, Verdict> {
  const lines = readFileSync(SPEC, 'utf8').split('\n')
  const start = lines.findIndex((l) => l.trim() === TABLE_HEADING)
  assert.ok(start >= 0, `${SPEC} 에서 「${TABLE_HEADING}」 를 찾지 못했다 — 표가 옮겨 갔는지 확인할 것`)

  const table = new Map<string, Verdict>()
  for (const line of lines.slice(start + 1)) {
    const trimmed = line.trim()
    if (trimmed.startsWith('#')) break // 다음 절로 넘어갔다
    if (!trimmed.startsWith('|')) continue
    const cells = trimmed.split('|').map((c) => c.trim())
    // cells[0] 은 앞 파이프 앞의 빈 문자열
    const name = cells[1]?.replace(/`/g, '')
    const verdictCell = cells[2] ?? ''
    if (!name || !/^[A-Z_]+$/.test(name)) continue // 머리글·구분선

    const allows = verdictCell.includes('허용')
    const denies = verdictCell.includes('불허')
    assert.ok(
      allows !== denies,
      `D6 표의 ${name} 행이 「허용」인지 「불허」인지 읽을 수 없다: ${JSON.stringify(verdictCell)}`,
    )
    table.set(name, allows ? 'delegated' : 'systemAdminOnly')
  }
  return table
}

/**
 * 판정기의 `isProjectDelegable()` when 식을 읽어 `권한 → 판정` 으로 옮긴다.
 *
 * `-> true` 는 위임, `-> false` 는 시스템 관리자 전용이다. 화살표 앞에 여러 값이 쌓여 있는
 * 다중 분기(`A,\n B,\n -> true`)를 그대로 다룬다.
 */
function readResolverBranches(): Map<string, Verdict> {
  const source = readFileSync(RESOLVER, 'utf8')
  const at = source.indexOf(`fun WorkflowDefinitionPermission.${BRANCH_FN}(`)
  assert.ok(at >= 0, `${RESOLVER} 에서 ${BRANCH_FN}() 를 찾지 못했다 — 이름이 바뀌었는지 확인할 것`)

  const branches = new Map<string, Verdict>()
  let pending: string[] = []
  for (const raw of source.slice(at).split('\n')) {
    const line = raw.trim()
    if (line.startsWith('//') || line.startsWith('*')) continue
    if (line === '}') break // when 블록 끝

    for (const m of line.matchAll(/WorkflowDefinitionPermission\.([A-Z_]+)/g)) {
      pending.push(m[1]!)
    }
    if (line.includes('-> true') || line.includes('-> false')) {
      const verdict: Verdict = line.includes('-> true') ? 'delegated' : 'systemAdminOnly'
      for (const name of pending) branches.set(name, verdict)
      pending = []
    }
  }
  return branches
}

/** `WorkflowDefinitionPermission` enum 의 값 목록 */
function readEnumEntries(): string[] {
  const source = readFileSync(ENUM, 'utf8')
  const at = source.indexOf('enum class WorkflowDefinitionPermission {')
  assert.ok(at >= 0, `${ENUM} 에서 enum 선언을 찾지 못했다`)
  return [...source.slice(at).matchAll(/^ {4}([A-Z_]+),$/gm)].map((m) => m[1]!)
}

describe('워크플로우 정의 권한 매트릭스', () => {
  test('세 목록 모두 비어 있지 않다', () => {
    // ★가장 먼저 잰다. 표가 옮겨 가거나 함수 이름이 바뀌면 아래 대조는 **빈 집합끼리** 같다고
    //  판정해 조용히 초록이 된다. 판별 범위가 사라진 것을 통과로 읽으면 안 된다.
    assert.ok(readSpecTable().size > 0, 'D6 표에서 권한 행을 하나도 읽지 못했다')
    assert.ok(readResolverBranches().size > 0, `${BRANCH_FN}() 에서 분기를 하나도 읽지 못했다`)
    assert.ok(readEnumEntries().length > 0, 'enum 값을 하나도 읽지 못했다')
  })

  test('enum 의 모든 권한이 D6 표에 적혀 있다', () => {
    const table = readSpecTable()
    const missing = readEnumEntries().filter((name) => !table.has(name))
    assert.deepEqual(
      missing,
      [],
      `enum 에는 있는데 D6 표에 없는 권한이다. 새 권한을 만들었으면 「프로젝트 관리자에게 열 것인가」를 ` +
        `${SPEC} 의 D6 표에 적어야 한다 — 안 적으면 그 결정을 아무도 승인하지 않은 채 코드만 정한다`,
    )
  })

  test('D6 표에 enum 에 없는 권한이 적혀 있지 않다', () => {
    const entries = new Set(readEnumEntries())
    const ghosts = [...readSpecTable().keys()].filter((name) => !entries.has(name))
    assert.deepEqual(ghosts, [], 'D6 표에는 있는데 enum 에 없는 권한이다 — 지워진 권한이 표에 남았다')
  })

  test('D6 표의 판정과 코드의 판정이 값마다 일치한다', () => {
    const table = readSpecTable()
    const branches = readResolverBranches()
    const drift = [...table.entries()]
      .filter(([name, verdict]) => branches.get(name) !== verdict)
      .map(([name, verdict]) => `${name}: 표=${verdict} 코드=${branches.get(name) ?? '없음'}`)

    assert.deepEqual(
      drift,
      [],
      `D6 표와 ${BRANCH_FN}() 의 판정이 갈렸다. 표만 고치면 운영 동작이 안 따라오고, ` +
        `코드만 고치면 권한이 조용히 넓어진 채 아무도 그 결정을 승인하지 않는다 (FR-WF-08 §6-3)`,
    )
  })
})
