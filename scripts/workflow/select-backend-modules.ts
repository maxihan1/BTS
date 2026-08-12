// 바뀐 파일에서 backend-ci 가 돌려야 할 Gradle 모듈을 고른다 — 의존 그래프는 Gradle 에서 도출
//
// ## 왜 있나
//
// `backend-ci` 는 `matrix.module` 이 9개 BC **고정 목록**이라 백엔드 파일이 하나만 바뀌어도
// 전부 돈다. 2026-08-12 PR #367 실측 — `IssueMoveController.kt` 의 **KDoc 한 덩어리
// (실행 코드 0줄)** 가 잡 12개를 깨웠고, 러너가 1대라 직렬로 **약 37분**이 나갔다.
//
// 설계 주석은 「매트릭스로 병렬화하면 벽시계가 최장 모듈로 수렴」이라 적었는데, 그 수렴은
// **러너가 여러 대일 때** 성립한다. 1대에서는 병렬이 아니라 직렬이라 **전 모듈 합계**가 된다.
//
// ## ★두 방향의 비용이 대칭이 아니다
//
// | 방향 | 결과 |
// |---|---|
// | 너무 넓게 고름 | 시간만 든다 — 고치려던 문제가 그대로 |
// | **너무 좁게 고름** | **검증 안 된 코드가 초록으로 머지된다** |
//
// 그래서 판정을 못 하는 **모든** 경우의 기본값은 전 모듈이다. 이 파일에서 「모르겠으면」은
// 항상 넓은 쪽이다.
//
// ## ★그래프를 손으로 적지 않는다
//
// `backend/modules/*/build.gradle.kts` 의 `project(":modules:X")` 참조에서 런타임에 도출한다.
// 목록을 상수로 두면 그 목록과 실제 Gradle 설정이 **서로를 안 보는 두 목록**이 되고, 의존을
// 추가한 PR 이 그 사실을 목록에 안 적으면 영향 모듈이 조용히 안 돈다.
//
// ★`implementation` · `testImplementation` · `testRuntimeOnly` 를 **구분하지 않는다.**
//   테스트 의존도 「그 모듈이 바뀌면 이 모듈의 테스트가 영향받는다」는 뜻이므로 폐포에 넣어야
//   한다. 실제로 `project-workflow` 는 `issue-tracking` 을 `testRuntimeOnly` 로만 의존한다.
//
// 사용. node --experimental-strip-types scripts/workflow/select-backend-modules.ts <변경파일...>
//       변경 파일을 인자로 못 주면(획득 실패) 인자 0개로 부르면 되고, 그러면 전 모듈이 나온다.
// 판별식. scripts/workflow/select-backend-modules.test.ts

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const MODULES_DIR = 'backend/modules'

/** 매트릭스 대상이 아닌 모듈. `app` 은 별도 「조립 부팅」 잡이 통째로 맡는다. */
const NOT_IN_MATRIX = new Set(['app'])

/** 이 경로가 바뀌면 영향 범위를 알 수 없다 — 전 모듈로 간다. */
const WIDEN_PREFIXES = ['backend/', '.github/workflows/backend-ci.yml']

export interface Selection {
  /** 돌릴 모듈. **절대 비지 않는다.** */
  modules: string[]
  /** 전 모듈로 넓혔는가. 로그가 「의도」와 「판정 실패」를 구분하게 한다. */
  all: boolean
  /** 사람이 읽을 사유. */
  reason: string
}

/** 매트릭스 대상 모듈 전수. 디스크의 디렉터리가 정본이다. */
export function allModules(): string[] {
  const dir = path.join(REPO_ROOT, MODULES_DIR)
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !NOT_IN_MATRIX.has(e.name))
    .map((e) => e.name)
    .sort()
}

/**
 * `모듈 → 그 모듈이 의존하는 모듈들`.
 *
 * 매트릭스 밖 모듈(`app`)은 키에서 빼되, **간선은 남긴다** — `app` 을 경유한 역의존은
 * 매트릭스에 영향이 없으므로 실질적으로 무해하고, 넣어 두면 폐포가 넓어질 뿐이다.
 */
export function moduleGraph(): Record<string, string[]> {
  const graph: Record<string, string[]> = {}
  for (const m of allModules()) {
    const buildFile = path.join(REPO_ROOT, MODULES_DIR, m, 'build.gradle.kts')
    if (!fs.existsSync(buildFile)) {
      graph[m] = []
      continue
    }
    const body = fs.readFileSync(buildFile, 'utf8')
    const deps = new Set<string>()
    for (const match of body.matchAll(/project\("\:modules\:([\w-]+)"\)/g)) {
      if (match[1] !== m) deps.add(match[1])
    }
    graph[m] = [...deps].sort()
  }
  return graph
}

/** `모듈 → 그 모듈을 의존하는 모듈들` (그래프를 뒤집은 것). */
function reverseGraph(graph: Record<string, string[]>): Record<string, string[]> {
  const reverse: Record<string, string[]> = {}
  for (const m of Object.keys(graph)) reverse[m] = []
  for (const [dependent, deps] of Object.entries(graph)) {
    for (const dep of deps) {
      if (reverse[dep] === undefined) reverse[dep] = []
      reverse[dep].push(dependent)
    }
  }
  return reverse
}

/** 변경 파일 하나가 어느 모듈에 속하는지. 모듈 밖이면 null. */
function moduleOf(file: string): string | null {
  const prefix = `${MODULES_DIR}/`
  if (!file.startsWith(prefix)) return null
  const name = file.slice(prefix.length).split('/')[0]
  // 빈 문자열(`backend/modules/`)이나 실재하지 않는 이름은 귀속 실패다.
  return name !== undefined && name !== '' ? name : null
}

/**
 * 변경 파일 목록에서 돌릴 모듈을 고른다.
 *
 * ★반환값은 **절대 비지 않는다.** 빈 매트릭스는 GitHub 이 거부하고, 거부된 잡은 **스킵**된다 —
 * 빨간불이 아니라 부재라 아무도 눈치채지 못한 채 초록으로 머지된다. 장부(매핑 31)가 착수
 * 조건으로 못박은 위험이 정확히 그것이다.
 */
export function selectModules(changedFiles: string[]): Selection {
  const universe = allModules()
  const wide = (reason: string): Selection => ({ modules: universe, all: true, reason })

  const files = changedFiles.filter((f) => f.trim() !== '')
  if (files.length === 0) {
    return wide('변경 파일 목록이 비었다 — 판정 불가라 전 모듈을 돈다')
  }

  const graph = moduleGraph()
  const known = new Set(universe)
  const seeds = new Set<string>()

  for (const file of files) {
    const m = moduleOf(file)
    if (m !== null) {
      if (known.has(m)) {
        seeds.add(m)
        continue
      }
      if (NOT_IN_MATRIX.has(m)) continue // `app` 변경은 조립 부팅 잡이 맡는다
      // 실재하지 않는 모듈 이름 — 모듈이 새로 생겼거나 지워졌다. 좁히지 않는다.
      return wide(`알 수 없는 모듈 경로가 있다 (${file}) — 판정 불가라 전 모듈을 돈다`)
    }
    // 모듈 밖인데 백엔드/워크플로우에 속하면 영향 범위를 알 수 없다.
    if (WIDEN_PREFIXES.some((p) => file === p || file.startsWith(p))) {
      return wide(`모듈에 귀속되지 않는 변경이 있다 (${file}) — 영향 범위 불명이라 전 모듈을 돈다`)
    }
    // 그 밖(프론트·문서 등)은 이 워크플로우와 무관하므로 무시한다.
  }

  if (seeds.size === 0) {
    // backend-ci 는 `backend/**` 에만 트리거되므로 여기 오는 것은 예상 밖이다.
    return wide('백엔드 변경을 하나도 못 찾았다 — 판정 불가라 전 모듈을 돈다')
  }

  // 역의존 폐포. 방문 집합으로 순환에 안전하다 —
  // `issue-tracking ↔ project-workflow` 는 한쪽이 testRuntimeOnly 라 실제 순환은 아니지만,
  // 그래프가 그렇게 보이므로 계산은 순환을 견뎌야 한다.
  const reverse = reverseGraph(graph)
  const selected = new Set<string>()
  const queue = [...seeds]
  while (queue.length > 0) {
    const cur = queue.shift() as string
    if (selected.has(cur)) continue
    selected.add(cur)
    for (const dependent of reverse[cur] ?? []) {
      if (!selected.has(dependent)) queue.push(dependent)
    }
  }

  const modules = [...selected].filter((m) => known.has(m)).sort()
  if (modules.length === 0) {
    return wide('폐포 결과가 비었다 — 그래프 도출이 고장났다. 전 모듈을 돈다')
  }
  return {
    modules,
    all: modules.length === universe.length,
    reason: `변경 모듈 ${[...seeds].sort().join(' · ')} 의 역의존 폐포`,
  }
}

// CLI — 인자로 받은 변경 파일에서 매트릭스용 JSON 배열을 낸다.
if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  const picked = selectModules(process.argv.slice(2))
  process.stderr.write(`선별. ${picked.modules.length}/${allModules().length} 모듈 — ${picked.reason}\n`)
  process.stdout.write(JSON.stringify(picked.modules))
}
