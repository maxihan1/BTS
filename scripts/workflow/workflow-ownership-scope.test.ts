// 워크플로우·스킴 소유 스코프가 조용히 전역으로 되돌아가지 않게 막는 판별식 (FR-WF-08)
//
// ─────────────────────────────────────────────────────────────────────────────
// 이 파일이 있는 이유
// ─────────────────────────────────────────────────────────────────────────────
// FR-WF-08 이전에 `WorkflowSchemeScope.Global` 은 스킴 CRUD 13곳에 **하드코딩**돼 있었다.
// 그래서 프로젝트 관리자는 자기 프로젝트 스킴조차 만들 수 없었고, 그 사실은 어디에도
// red 로 나타나지 않았다 — 모든 테스트가 SYSTEM_ADMIN 픽스처였기 때문이다.
//
// ★이 결함은 「한 곳의 버그」가 아니라 **양식**이다. 스코프를 정하는 자리가 열세 군데면
//  다음에 생기는 열네 번째도 `Global` 로 시작하고, 아무도 그 자리를 다시 보지 않는다.
//  저장소가 이름 붙인 「두 목록이 서로를 검사하지 않는다」와 같은 모양이다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 규칙 A — 전역 스코프는 **의도를 적어야** 쓴다
// ─────────────────────────────────────────────────────────────────────────────
// `project-workflow` 의 main 소스에서 `WorkflowSchemeScope.Global` 을 쓰려면 바로 위
// 여섯 줄 안에 `SCOPE-GLOBAL:` 로 시작하는 주석이 있어야 한다. 전역이 **틀렸다는** 규칙이
// 아니다 — 전역 관리자 목록처럼 전역이 정답인 자리가 실제로 있다. 규칙은 「전역을 고른 것이
// 판단이었는지 관성이었는지 구분할 수 있어야 한다」다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 규칙 B — 소유 파라미터에 기본값을 두지 않는다
// ─────────────────────────────────────────────────────────────────────────────
// `projectId: UUID? = null` 한 글자면 호출부가 소유를 빠뜨려도 컴파일이 통과하고, 만들어진
// 워크플로우·스킴은 **조용히 전역**이 된다. 전역이 된 것은 그 프로젝트 관리자가 영영 못 고친다.
// 그래서 소유를 실제로 저장하는 세 자리는 기본값 없이 남는다 — 빠뜨리면 컴파일이 막는다.
//
// 반대로 **요청 DTO** 의 `projectKey: String? = null` 은 정당하다. 거긴 「지목하지 않았다」를
// 표현해야 하고, 그 null 은 전역 생성이라는 **명시적 의미**다. 그래서 규칙 B 의 범위는
// 저장 경로 세 파일로 좁혀 둔다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'

/** 판별 대상 — 스코프를 결정하는 BC 의 main 소스 */
const PW_MAIN = 'backend/modules/project-workflow/src/main/kotlin'

/** 전역 스코프 사용 지점 */
const GLOBAL_USE = 'WorkflowSchemeScope.Global'

/** 전역을 의도적으로 고를 때 붙이는 표식 */
const GLOBAL_MARKER = 'SCOPE-GLOBAL:'

/** 표식을 찾을 범위 — 사용 지점 바로 위 몇 줄까지 인정하는가 */
const MARKER_LOOKBACK_LINES = 6

/**
 * 규칙 A 면제 파일 — 스코프 **결정 지점 자신**.
 *
 * 이 파일은 「소유가 없으면 전역」이라는 규칙 자체를 구현한다. 여기에 표식을 요구하면
 * 규칙이 자기 자신을 설명하라는 말이 된다. 대신 이 파일의 판정은
 * `WorkflowOwnershipScopeResolverTest` 가 전수로 잰다 — 전역·프로젝트·fail-closed 세 갈래를
 * 모두 요구하므로, 여기서 전역으로 굳어지면 그쪽이 red 다.
 *
 * ★면제는 **파일 하나**다. 목록이 늘기 시작하면 이 판별식은 장식이 된다.
 */
const SCOPE_DECISION_FILE = 'WorkflowOwnershipScopeResolver.kt'

/**
 * 규칙 B 판별 대상 — 소유를 실제로 저장하는 자리.
 *
 * 요청 DTO 는 넣지 않는다. 그쪽 `= null` 은 「지목하지 않았다」라는 뜻이라 정당하다.
 */
const OWNER_PARAM_FILES = [
  `${PW_MAIN}/com/bts/workflow/repository/WorkflowWriteRepository.kt`,
  `${PW_MAIN}/com/bts/workflow/scheme/domain/WorkflowScheme.kt`,
  `${PW_MAIN}/com/bts/workflow/scheme/application/WorkflowSchemeApplicationService.kt`,
]

/** `projectId: UUID?` 파라미터 선언 — 기본값(`=`)이 붙었는지까지 본다 */
const OWNER_PARAM = /projectId\s*:\s*UUID\?\s*(=)?/g

/** 디렉터리를 훑어 `.kt` 파일 경로를 모은다 */
function collectKotlinFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) {
      out.push(...collectKotlinFiles(full))
    } else if (entry.endsWith('.kt')) {
      out.push(full)
    }
  }
  return out
}

/** `WorkflowSchemeScope.Global` 이 나오는 (파일, 줄번호, 표식 유무) 목록 */
function findGlobalUses(): { file: string; line: number; marked: boolean }[] {
  const uses: { file: string; line: number; marked: boolean }[] = []
  for (const file of collectKotlinFiles(PW_MAIN)) {
    if (path.basename(file) === SCOPE_DECISION_FILE) continue
    const lines = readFileSync(file, 'utf8').split('\n')
    lines.forEach((text, index) => {
      const trimmed = text.trimStart()
      // import 와 주석은 사용이 아니다 — KDoc 이 스코프를 설명하는 것까지 잡으면 판별이 시끄러워진다
      if (trimmed.startsWith('import ')) return
      if (trimmed.startsWith('*') || trimmed.startsWith('//') || trimmed.startsWith('/*')) return
      if (!text.includes(GLOBAL_USE)) return
      const from = Math.max(0, index - MARKER_LOOKBACK_LINES)
      const marked = lines.slice(from, index).some((l) => l.includes(GLOBAL_MARKER))
      uses.push({ file, line: index + 1, marked })
    })
  }
  return uses
}

describe('워크플로우 소유 스코프', () => {
  test('규칙 A — 전역 스코프는 SCOPE-GLOBAL 표식과 함께 쓴다', () => {
    const uses = findGlobalUses()

    // ★범위가 조용히 비지 않았는지 먼저 확인한다. 파일이 옮겨 가거나 이름이 바뀌면
    //  `uses` 가 0건이 되고 아래 단언은 **줄어든 범위 그대로 초록**이 된다.
    assert.ok(
      uses.length > 0,
      `${PW_MAIN} 에서 ${GLOBAL_USE} 사용을 하나도 찾지 못했다 — 판별 범위가 줄었는지 확인할 것`,
    )

    const unmarked = uses.filter((u) => !u.marked)
    assert.deepEqual(
      unmarked.map((u) => `${u.file}:${u.line}`),
      [],
      `전역 스코프를 표식 없이 썼다. 전역이 정답이면 바로 위에 ` +
        `\`// ${GLOBAL_MARKER} <왜 전역인가>\` 를 적고, 아니면 ` +
        `WorkflowOwnershipScopeResolver 로 소유를 물어야 한다 (FR-WF-08)`,
    )
  })

  test('규칙 B — 소유 파라미터에 기본값을 두지 않는다', () => {
    const offenders: string[] = []
    let seen = 0

    for (const file of OWNER_PARAM_FILES) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(OWNER_PARAM)) {
        seen += 1
        if (match[1] === undefined) continue
        const line = source.slice(0, match.index).split('\n').length
        offenders.push(`${file}:${line}`)
      }
    }

    // 범위 확인 — 파일에서 파라미터가 사라지면(이름 변경·이동) 이 판별은 무음 통과한다.
    assert.ok(
      seen >= OWNER_PARAM_FILES.length,
      `소유 파라미터 선언을 ${seen}건밖에 못 찾았다 (대상 파일 ${OWNER_PARAM_FILES.length}개) — ` +
        '이름이 바뀌었는지 확인할 것',
    )

    assert.deepEqual(
      offenders,
      [],
      '소유 파라미터에 기본값이 붙었다. 기본값이 있으면 호출부가 소유를 빠뜨려도 컴파일이 통과하고 ' +
        '그 워크플로우·스킴은 조용히 전역이 되어 프로젝트 관리자가 영영 못 고친다 (FR-WF-08)',
    )
  })
})
