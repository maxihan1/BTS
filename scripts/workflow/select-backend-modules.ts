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

/**
 * 모듈 트리의 루트. 기본은 저장소지만 `BTS_BACKEND_MODULES_ROOT` 로 갈아끼울 수 있다.
 *
 * ★왜 이음매가 필요한가. 「Gradle 참조를 못 읽으면 넓힌다」는 방어는 **현재 저장소에 비표준
 * 참조가 하나도 없어** 뮤테이션으로 잴 수가 없다 — 그 분기를 통째로 지워도 아무 테스트가 안
 * 깨진다(2026-08-12 실측). 실제 파일을 비표준 형태로 바꿔 놓고 검증할 수는 없으므로,
 * 가짜 모듈 트리를 가리키게 하는 통로를 둔다. `BTS_RUNNER_ROOT`·`BTS_GH_BIN` 과 같은 관례다.
 */
function modulesRoot(): string {
  const injected = process.env.BTS_BACKEND_MODULES_ROOT
  return injected !== undefined && injected !== '' ? injected : path.join(REPO_ROOT, MODULES_DIR)
}

/** 매트릭스 대상이 아닌 모듈. `app` 은 별도 「조립 부팅」 잡이 통째로 맡는다. */
const NOT_IN_MATRIX = new Set(['app'])

/** 이 경로가 바뀌면 영향 범위를 알 수 없다 — 전 모듈로 간다. */
const WIDEN_PREFIXES = [
  'backend/',
  '.github/workflows/backend-ci.yml',
  // ★선별기 자신이 바뀌면 전 모듈이다. 이 파일이 「무엇을 돌릴지」를 정하므로, 그 변경을
  //   일부 모듈로만 검증하면 **좁히는 실수를 그 PR 안에서 못 잡는다.**
  'scripts/workflow/select-backend-modules.ts',
]

/**
 * 마이그레이션 파일. **모듈 역산보다 앞서** 판정해 전 모듈로 넓힌다.
 *
 * ★왜 모듈 안에 있는데도 넓히나. 마이그레이션 번호 대역이 여러 컨텍스트에 걸쳐 있어
 * **모듈 의존 그래프로는 영향 범위를 원리적으로 계산할 수 없다**(ADR D2). 그래프가 답을 못 주는
 * 축이므로 넓히는 것이 유일한 정답이다.
 *
 * ★그래서 `WIDEN_PREFIXES` 가 아니라 별도 판정이다. 실제 경로는
 * `backend/modules/<bc>/src/main/resources/db/migration/<bc>/V500__x.sql` 로 **모듈 안**이고,
 * `moduleOf()` 가 먼저 돌면 그 모듈로 귀속돼 좁혀진다. 순서가 이 규칙의 전부다.
 *
 * ★디렉터리 이름으로 잡고 모듈 경로를 다시 적지 않는다 — 모듈이 개명돼도 살아남는다.
 */
const MIGRATION_MARKER = '/db/migration/'

/** 이 라벨이 PR 에 붙으면 무조건 전 모듈. 위험이 큰 작업은 **경로에 신호가 없는 유일한 조건**이다(ADR D2). */
const FULL_CI_LABEL = 'ci:full'

/** Gradle 의 모듈 참조. 이 형태만 잡고, 못 잡은 것은 `modulesWithUnparsedRefs()` 가 센다. */
const MODULE_REF = /project\("\:modules\:([\w-]+)"\)/g

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
  const dir = modulesRoot()
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !NOT_IN_MATRIX.has(e.name))
    .map((e) => e.name)
    .sort()
}

/** 디스크의 모듈 디렉터리 전수 — 매트릭스 밖(`app`)도 포함한다. 그래프의 정의역이다. */
function moduleDirs(): string[] {
  const dir = modulesRoot()
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .sort()
}

/**
 * `모듈 → 그 모듈이 의존하는 모듈들`.
 *
 * ★매트릭스 밖 모듈(`app`)도 **정의역에 넣는다.** 「매트릭스 모듈 A → 비매트릭스 N →
 * 매트릭스 B」 형태가 생기면, N 을 빼면 B 변경 시 역폐포가 N 을 통과하지 못해 A 가 누락된다 —
 * **좁아지는 방향**이라 치명적이다. 지금은 그런 경로가 없지만 구조로 막아 둔다.
 * (초안은 주석에 「간선은 남긴다」고 적어 놓고 실제로는 `app/build.gradle.kts` 를 한 번도 읽지
 * 않았다 — 독립 리뷰 적발. 주석이 막는다고 선언한 위험을 코드가 안 막고 있었다.)
 */
export function moduleGraph(): Record<string, string[]> {
  const graph: Record<string, string[]> = {}
  for (const m of moduleDirs()) {
    const buildFile = path.join(modulesRoot(), m, 'build.gradle.kts')
    if (!fs.existsSync(buildFile)) {
      graph[m] = []
      continue
    }
    const body = fs.readFileSync(buildFile, 'utf8')
    const deps = new Set<string>()
    for (const match of body.matchAll(MODULE_REF)) {
      if (match[1] !== m) deps.add(match[1])
    }
    graph[m] = [...deps].sort()
  }
  return graph
}

/**
 * 파서가 **못 읽은** `:modules:` 참조를 가진 모듈들.
 *
 * ★왜 필요한가. `MODULE_REF` 는 `project(":modules:X")` 형태만 잡는다. Gradle 은
 * `project(path = ":modules:X")` · `project(":modules:X", configuration = "…")` · 줄바꿈 형태도
 * 허용하는데, 그런 간선은 **조용히 사라진다** — 그러면 그 모듈이 바뀌어도 의존 모듈의 테스트가
 * 안 돌고 **깨진 채 초록으로 머지된다.** 좁아지는 방향이라 치명적이다.
 *
 * 정규식을 늘려 쫓아가는 대신 **못 읽었다는 사실 자체를 감지**한다 — 문법 변형은 앞으로도
 * 새로 생기고, 열거는 언제나 뒤처진다. 못 읽으면 전 모듈로 넓힌다.
 */
export function modulesWithUnparsedRefs(): string[] {
  const stale: string[] = []
  for (const m of moduleDirs()) {
    const buildFile = path.join(modulesRoot(), m, 'build.gradle.kts')
    if (!fs.existsSync(buildFile)) continue
    const body = fs.readFileSync(buildFile, 'utf8')
    const total = [...body.matchAll(/:modules:/g)].length
    const parsed = [...body.matchAll(MODULE_REF)].length
    if (total !== parsed) stale.push(m)
  }
  return stale
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
export function selectModules(changedFiles: string[], labels: string[] = []): Selection {
  const universe = allModules()
  const wide = (reason: string): Selection => ({ modules: universe, all: true, reason })

  // ★라벨을 **가장 먼저** 본다. 사람이 「전부 돌려라」라고 말한 것이므로 어떤 자동 판정보다 세다.
  //   정확 일치만 넓힌다 — 부분 일치로 짜면 `ci:fullish` 같은 것이 조용히 전 모듈을 끌고 온다.
  if (labels.some((l) => l.trim() === FULL_CI_LABEL)) {
    return wide(`PR 라벨 \`${FULL_CI_LABEL}\` — 사람이 전 모듈을 지시했다`)
  }

  const files = changedFiles.filter((f) => f.trim() !== '')
  if (files.length === 0) {
    return wide('변경 파일 목록이 비었다 — 판정 불가라 전 모듈을 돈다')
  }

  // ★★마이그레이션은 **모듈 역산보다 앞서** 판정한다(ADR D2). 실제 경로가 모듈 안이라
  //   `moduleOf()` 가 먼저 돌면 그 모듈로 귀속돼 **좁혀진다** — 스키마 영향은 그래프로 계산할 수
  //   없는 축이므로 그 좁힘은 「검증 안 된 코드가 초록으로 머지된다」 방향이다.
  const migration = files.find((f) => f.includes(MIGRATION_MARKER))
  if (migration !== undefined) {
    return wide(`마이그레이션 변경이 있다 (${migration}) — 스키마 영향은 모듈 그래프로 계산할 수 없다`)
  }

  // ★그래프를 다 못 읽었으면 좁히지 않는다. 사라진 간선은 「의존이 없다」와 구분되지 않고,
  //   그 오해는 **의존 모듈의 테스트를 안 돌리는** 쪽으로 작동한다.
  const stale = modulesWithUnparsedRefs()
  if (stale.length > 0) {
    return wide(
      `Gradle 의존 참조를 다 읽지 못했다 (${stale.join(' · ')}) — 간선 누락 가능이라 전 모듈을 돈다`,
    )
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
      // `app` 변경 자체는 「조립 부팅」 잡이 맡으므로 씨앗에 넣지 않는다.
      //
      // ⚠️ 다만 **app 만 바뀐 경우**는 씨앗이 비어 아래 fallback 이 전 모듈로 넓힌다.
      //    의도한 최적점은 아니지만(그 경우 매트릭스 0개가 이상적이다) **빈 매트릭스는 만들 수
      //    없다** — GitHub 이 거부하고 거부된 잡은 스킵되는데 그것은 빨간불이 아니라 부재다
      //    (장부 매핑 31 이 착수 조건으로 못박은 위험). 좁히다 사고 내는 것보다 넓게 도는 편이
      //    낫다는 이 파일의 원칙을 그대로 따른다. app 단독 변경은 흔하므로 이 비용은 실재한다.
      if (NOT_IN_MATRIX.has(m)) continue
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
    // 도달 경로 2종. ①이 워크플로우 파일만 바뀐 경우(위에서 이미 넓혔으므로 실제로는 안 온다)
    // ②`app` 만 바뀐 경우 — **흔하다**(cross-BC 배선 · application.yml · prod 조립 설정).
    // ②에서 전 모듈을 도는 것은 최적이 아니지만 빈 매트릭스를 만들 수 없어 택한 값이다.
    return wide('매트릭스 대상 모듈을 하나도 못 찾았다 (app 단독 변경 등) — 전 모듈을 돈다')
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

/**
 * 환경변수에서 PR 라벨을 읽는다. `BTS_CI_PR_LABELS` 는 GitHub 이 준 **JSON 배열 문자열**이다.
 *
 * ★못 읽어도 넓히지 않는다. 라벨 부재는 **정상 상태**이고(대부분의 PR 에 `ci:full` 이 없다),
 * 여기서 넓히면 모든 PR 이 전 모듈을 돌아 이 선별기 자체가 무의미해진다. 변경 파일 획득 실패와
 * 성격이 다르다 — 그쪽은 「알아야 할 것을 못 얻은 것」이고 이쪽은 「없는 것이 기본」이다.
 */
function labelsFromEnv(): string[] {
  const raw = process.env.BTS_CI_PR_LABELS
  if (raw === undefined || raw.trim() === '') return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((l): l is string => typeof l === 'string') : []
  } catch {
    // 형태가 깨졌으면 라벨이 없는 것으로 본다(위 주석). 다만 조용히 넘기지는 않는다.
    process.stderr.write(`선별. ⚠️ BTS_CI_PR_LABELS 를 JSON 배열로 읽지 못했다 — 라벨 없음으로 진행한다.\n`)
    return []
  }
}

// CLI — 인자로 받은 변경 파일에서 매트릭스용 JSON 배열을 낸다.
if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  const picked = selectModules(process.argv.slice(2), labelsFromEnv())
  process.stderr.write(`선별. ${picked.modules.length}/${allModules().length} 모듈 — ${picked.reason}\n`)
  process.stdout.write(JSON.stringify(picked.modules))
}
