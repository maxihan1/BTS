// backend-ci 가 돌릴 모듈을 고르는 선별기의 계약 판별식
//
// ## 왜 이 판별식이 필요한가
//
// 이 선별기는 **일감을 줄이는 도구**다. 줄이는 도구가 잘못 동작하는 두 방향이 있고 비용이
// **대칭이 아니다.**
//
// | 방향 | 결과 |
// |---|---|
// | 너무 넓게 고름 | 시간만 든다 — 고치려던 문제가 그대로 |
// | **너무 좁게 고름** | **검증 안 된 코드가 초록으로 머지된다** |
//
// 두 번째가 압도적으로 나쁘다. 그래서 판정을 못 하는 모든 경우의 fail-safe 는 **전 모듈**이고,
// 이 파일의 단언 절반은 「좁아지지 않는가」를 잰다.
//
// ## ★장부가 명시한 위험 — 조용한 0개
//
// > 필터가 조용히 0개를 골라 **아무 잡도 안 돌면서 초록**이 되는 경로가 생긴다.
//
// GitHub Actions 는 빈 매트릭스를 거부하고, 거부된 잡은 **스킵**된다 — 빨간불이 아니라 부재다.
// 그래서 「어떤 입력에도 0개를 돌려주지 않는다」를 독립 단언으로 둔다.
//
// ## 그래프를 손으로 적지 않는다
//
// 의존 관계는 `backend/modules/*/build.gradle.kts` 에서 **런타임에 도출**한다. 목록을 상수로
// 두면 그 목록과 실제 Gradle 설정이 서로를 안 보는 두 목록이 된다. 대신 도출이 0건을 내면
// 모든 단언이 공허해지므로 **양성 대조군을 첫 단언**으로 둔다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import {
  moduleGraph,
  allModules,
  selectModules,
  modulesWithUnparsedRefs,
  requiresFullBuild,
  WIDEN_PREFIXES,
} from './select-backend-modules.ts'
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 실측 기준선. 줄어들면 도출이 고장난 것이다. */
const MIN_MODULES = 9

/**
 * CI 정의 정본. 2026-09-09 이전까지는 `.github/workflows/backend-ci.yml` 이었다.
 *
 * ★그때 이 파일이 갖던 단언 6종을 지웠다(P4b) — 「매트릭스에 먹인다」·「select 스텝의 셸이
 *   실제로 돈다」·「라벨을 env 로 넘긴다」 같은 것들은 **GitHub Actions 의 구조 자체**에
 *   묶여 있어 젠킨스에 옮길 자리가 없다(매트릭스도 PR 라벨도 셸 스텝도 없다).
 *   그중 살릴 수 있는 보장은 먼저 옮겼다 — 「모듈 목록 하드코딩 금지」와
 *   「diff 에 `--no-renames`」가 그것이고 아래에 젠킨스판으로 있다.
 */
const WORKFLOW = 'Jenkinsfile'

describe('backend-ci 모듈 선별', () => {
  test('모듈과 그래프를 실제로 도출한다 (양성 대조군)', () => {
    const modules = allModules()
    assert.ok(
      modules.length >= MIN_MODULES,
      `모듈을 ${modules.length}개만 찾았다 (기준선 ${MIN_MODULES}) — 도출이 고장나면 아래가 전부 공허하다.`,
    )
    const graph = moduleGraph()
    const edges = Object.values(graph).reduce((n, deps) => n + deps.length, 0)
    assert.ok(edges > 0, '의존 간선이 0건이다 — 파싱이 고장났다. 폐포가 아무 일도 안 한다.')
  })

  test('★★한 모듈만 바뀌면 그 모듈과 그것을 의존하는 것만 고른다', () => {
    const picked = selectModules(['backend/modules/notification/src/main/kotlin/X.kt'])
    // notification 을 의존하는 BC 는 없다(app 은 매트릭스 밖 별도 잡이다).
    assert.deepEqual(
      picked.modules,
      ['notification'],
      `변경과 무관한 모듈까지 골랐다 — 줄이려던 일감이 그대로다.\n${JSON.stringify(picked)}`,
    )
  })

  test('★★shared-kernel 이 바뀌면 전 모듈을 고른다 (역의존 폐포)', () => {
    // 전 모듈이 shared-kernel 을 의존한다. 여기서 좁히면 **깨진 채 머지된다.**
    const picked = selectModules(['backend/modules/shared-kernel/src/main/kotlin/X.kt'])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `shared-kernel 변경이 전 모듈로 번지지 않았다 — 의존 모듈이 검증 없이 통과한다.`,
    )
  })

  test('★역의존은 **전이적**이다 — 테스트 의존 간선도 센다', () => {
    // project-workflow 는 issue-tracking 을 testRuntimeOnly 로 의존한다. 컴파일 의존이
    // 아니라고 빼면, issue-tracking 이 바뀌었을 때 project-workflow 의 테스트가 안 돈다.
    const picked = selectModules(['backend/modules/issue-tracking/src/main/kotlin/X.kt'])
    assert.ok(
      picked.modules.includes('project-workflow'),
      `테스트 의존 간선을 빠뜨렸다 — 영향받는 테스트가 안 돈다.\n${JSON.stringify(picked)}`,
    )
  })

  test('★★모듈 밖 백엔드 변경은 전 모듈이다 (빌드 설정 · 마이그레이션)', () => {
    // 어느 모듈에도 귀속되지 않는 변경은 영향 범위를 알 수 없다. 모르면 넓게 간다.
    //
    // ★아래 4개 중 `backend/db/migration/…` 과 `backend/gradle/libs.versions.toml` 은 **이 저장소에
    //   실재하지 않는 가공 경로**다(2026-08-14 전수 확인 — 모듈 밖 백엔드 파일은
    //   `build.gradle.kts` · `gradle.properties` · `gradle/wrapper/*` · `gradlew(.bat)` ·
    //   `settings.gradle.kts` 뿐). 여기서 재는 것은 파일의 실재가 아니라 **`backend/` 접두 판정**
    //   이므로 공허하지 않고, 실재하는 앞 두 경로가 같은 루프에 있어 짝이 성립한다.
    //   ★단 「마이그레이션이 넓어진다」를 이 케이스로 착각하지 말 것 — 실제 마이그레이션은
    //   모듈 **안**이라 여기 안 걸린다. 그 축은 아래 `MIGRATION_MARKER` 판정 3건이 맡는다.
    for (const f of [
      'backend/build.gradle.kts',
      'backend/settings.gradle.kts',
      'backend/db/migration/V999__x.sql',
      'backend/gradle/libs.versions.toml',
    ]) {
      const picked = selectModules([f])
      assert.deepEqual(
        picked.modules.sort(),
        allModules().sort(),
        `${f} 변경이 전 모듈로 번지지 않았다 — 영향 범위를 모르는데 좁혔다.`,
      )
    }
  })

  test('★★모듈 변경과 빌드 설정 변경이 **섞여 있으면** 전 모듈이다', () => {
    // ★단일 파일 입력만 재면 이 분기를 못 잡는다. 빌드 설정만 바뀐 경우는 씨앗이 비어
    //   「백엔드 변경을 못 찾았다」 fallback 이 대신 넓혀 주기 때문이다 — 넓히는 분기를
    //   통째로 지워도 그 테스트는 통과한다(2026-08-12 뮤테이션 M2 가 실제로 살아남았다).
    //   씨앗이 **비지 않은** 상태에서 넓혀야 하는지를 재는 것이 이 케이스다.
    const picked = selectModules([
      'backend/modules/notification/src/main/kotlin/X.kt',
      'backend/build.gradle.kts',
    ])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `빌드 설정이 함께 바뀌었는데 한 모듈로 좁혔다 — 나머지가 검증 없이 통과한다.\n` +
        JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  test('★★알 수 없는 모듈 경로가 **섞여 있으면** 전 모듈이다', () => {
    // 모듈이 새로 생겼거나 개명·삭제된 상태다. 그래프가 낡았을 수 있으므로 좁히지 않는다.
    // 이것도 섞인 입력으로 재야 한다 — 단독으로는 fallback 이 가려 준다(뮤테이션 M6 생존).
    const picked = selectModules([
      'backend/modules/notification/src/main/kotlin/X.kt',
      'backend/modules/no-such-module/Y.kt',
    ])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `알 수 없는 모듈이 섞였는데 좁혔다 — 그래프가 낡았을 가능성을 무시했다.\n` +
        JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  /**
   * 로컬 푸시 훅용 옵션 — 마이그레이션 넓힘만 끈다.
   *
   * ## 왜 필요한가
   *
   * 2026-08-21 CI 자동 실행을 껐다. 이제 백엔드 테스트를 실제로 돌리는 유일한 자리는
   * `.husky/pre-push` 다. 그런데 마이그레이션이 하나만 있어도 전 모듈로 넓히면 로컬 푸시가
   * 9모듈 = 약 55분 걸린다 — 사람이 `--no-verify` 를 쓰기 시작하고 그 순간 훅 전체가 죽는다.
   *
   * ## ★기본값은 넓히는 쪽이다
   *
   * 「모르겠으면 넓힌다」는 이 파일의 규율이다. 옵션을 안 주면 종전과 **완전히 같게** 동작해야
   * 하고, 좁히는 것은 부르는 쪽이 명시적으로 요구할 때만이다.
   *
   * ## ★그래프 넓힘은 끄지 않는다
   *
   * `shared-kernel` 처럼 역의존 폐포가 전 모듈이 되는 경우는 **정당한 넓힘**이다. 스키마와
   * 달리 그래프로 계산 가능하고, 끄면 검증 안 된 코드가 통과한다. 이 옵션은 그것을 건드리지 않는다.
   */
  describe('마이그레이션 넓힘 스위치 (로컬 훅용)', () => {
    const MIGRATION =
      'backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V500__x.sql'

    test('옵션을 안 주면 종전대로 전 모듈이다 (안전한 기본값)', () => {
      const picked = selectModules([MIGRATION])
      assert.deepEqual(
        picked.modules.sort(),
        allModules().sort(),
        '기본값이 좁아졌다 — 옵션 도입이 기존 호출자의 동작을 바꿨다.',
      )
      assert.equal(picked.all, true)
    })

    test('★widenOnMigration:false 면 그 모듈의 폐포만 고른다', () => {
      const picked = selectModules([MIGRATION], [], { widenOnMigration: false })
      assert.notDeepEqual(
        picked.modules.sort(),
        allModules().sort(),
        '스위치를 껐는데 여전히 전 모듈이다 — 옵션이 배선되지 않았다.',
      )
      assert.ok(
        picked.modules.includes('project-workflow'),
        `마이그레이션이 속한 모듈이 빠졌다: ${JSON.stringify(picked.modules)}`,
      )
    })

    test('★★스위치를 꺼도 그래프 넓힘은 살아 있다 (shared-kernel)', () => {
      // 이것이 깨지면 「스키마 넓힘만 끈다」가 「전부 끈다」가 된 것이다.
      const picked = selectModules(
        ['backend/modules/shared-kernel/src/main/kotlin/X.kt'],
        [],
        { widenOnMigration: false },
      )
      assert.deepEqual(
        picked.modules.sort(),
        allModules().sort(),
        'shared-kernel 역의존 폐포가 사라졌다 — 검증 안 된 코드가 통과하는 방향의 회귀다.',
      )
    })

    test('★스위치를 꺼도 ci:full 라벨은 여전히 전 모듈이다', () => {
      // 사람의 명시 지시가 어떤 자동 판정보다 세다는 규율이 옵션 뒤에서도 유지되는지.
      const picked = selectModules([MIGRATION], ['ci:full'], { widenOnMigration: false })
      assert.deepEqual(picked.modules.sort(), allModules().sort())
    })
  })

  test('★워크플로우 자신이 바뀌면 전 모듈이다', () => {
    // 선별 로직·잡 정의가 바뀐 PR 에서 일부만 돌면 그 변경 자체를 검증하지 못한다.
    const picked = selectModules(['.github/workflows/backend-ci.yml'])
    assert.deepEqual(picked.modules.sort(), allModules().sort())
  })

  test('★★어떤 입력에도 0개를 돌려주지 않는다 (조용한 스킵 차단)', () => {
    // 장부가 명시한 위험. 빈 매트릭스는 GitHub 이 거부하고, 거부된 잡은 **스킵**된다 —
    // 빨간불이 아니라 부재라 아무도 눈치채지 못한 채 초록으로 머지된다.
    const inputs: string[][] = [
      [],
      [''],
      ['README.md'],
      ['apps/web/src/main.tsx'],
      ['backend/modules/no-such-module/X.kt'],
      ['backend/'],
      ['backend/modules/'],
    ]
    for (const input of inputs) {
      const picked = selectModules(input)
      assert.ok(
        picked.modules.length > 0,
        `입력 ${JSON.stringify(input)} 에서 0개를 골랐다 — 아무 잡도 안 돌면서 초록이 된다.`,
      )
    }
  })

  test('★고른 목록은 항상 실재하는 모듈의 부분집합이다', () => {
    // 없는 모듈 이름이 매트릭스에 들어가면 그 잡은 gradle 에서 죽는다 — 선별기가 CI 를 깬다.
    const universe = new Set(allModules())
    for (const input of [
      ['backend/modules/automation/X.kt'],
      ['backend/modules/shared-kernel/X.kt'],
      ['backend/db/migration/V1__x.sql'],
    ]) {
      for (const m of selectModules(input).modules) {
        assert.ok(universe.has(m), `실재하지 않는 모듈을 골랐다: ${m}`)
      }
    }
  })

  test('★★Jenkinsfile 에 모듈 목록이 하드코딩되지 않았다 (두 목록 차단)', () => {
    const jf = fs.readFileSync(path.join(REPO_ROOT, 'Jenkinsfile'), 'utf8')
    const code = jf
      .split('\n')
      .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('#'))
      .join('\n')

    const hardcoded = allModules().filter((m) => m !== 'app' && new RegExp(`\\b${m}\\b`).test(code))
    assert.deepEqual(
      hardcoded,
      [],
      `Jenkinsfile 이 BC 모듈 이름을 직접 적고 있다: ${hardcoded.join(', ')}\n` +
        '  무엇을 돌릴지는 select-test-scope.ts 가 정한다. 여기 목록을 두면\n' +
        '  settings.gradle.kts 와 서로를 검사하지 않는 두 목록이 되고, 새 BC 가 조용히 빠진다.',
    )
  })

  test('★★선별기 자신의 변경은 전 모듈을 고른다', () => {
    // ★2026-08-21 — 앞쪽 절반(「backend-ci 트리거 paths 에 선별기가 2곳 걸려 있다」)을 지웠다.
    //   CI 자동 실행을 껐으므로 트리거 paths 자체가 없다. 뒤쪽 절반은 그대로 살아 있다 —
    //   선별기를 좁히는 실수를 그 PR 안에서 잡는 것이 이 단언의 본체다.
    const SELF = 'scripts/workflow/select-backend-modules.ts'

    // 그리고 그 변경은 전 모듈이어야 한다 — 일부로 검증하면 좁히는 실수를 그 PR 에서 못 잡는다.
    //
    // ★**섞인 입력**으로 잰다. 선별기 파일만 넣으면 씨앗이 비어 바깥 fallback 이 대신 넓혀
    //   주므로, WIDEN_PREFIXES 에서 이 파일을 빼도 통과한다(2026-08-12 뮤테이션 M8 생존).
    const picked = selectModules([SELF, 'backend/modules/notification/src/main/kotlin/X.kt'])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `선별기가 함께 바뀌었는데 한 모듈로 좁혔다 — 좁히는 실수를 그 PR 에서 못 잡는다.\n` +
        JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  test('★★type-safe project accessor 가 켜져 있지 않다 (미검출 감지의 사각 봉인)', () => {
    // `modulesWithUnparsedRefs()` 는 「`:modules:` 리터럴은 있는데 파서가 못 읽은 것」을 센다.
    // 그런데 Gradle 의 type-safe accessor(`implementation(projects.modules.sharedKernel)`)는
    // **`:modules:` 문자열을 아예 포함하지 않아** total 도 parsed 도 0 이 된다 — 간선이 조용히
    // 사라지고 그것은 **좁아지는 방향**이다(2026-08-14 독립 리뷰 지적).
    //
    // 지금은 도달 불가다(`TYPESAFE_PROJECT_ACCESSORS` 미활성). 도달 가능해지는 순간을 여기서
    // 잡는다 — 켜는 PR 이 이 판정을 밟고 「감지 신호를 함께 고쳐라」를 읽게 된다.
    const settings = fs.readFileSync(path.join(REPO_ROOT, 'backend/settings.gradle.kts'), 'utf8')
    assert.doesNotMatch(
      settings,
      /TYPESAFE_PROJECT_ACCESSORS/,
      `type-safe project accessor 가 켜졌다. \`modulesWithUnparsedRefs()\` 의 감지 신호가\n` +
        `\`:modules:\` 리터럴이라 \`projects.modules.x\` 형태를 **미검출로도 못 센다** —\n` +
        `간선이 조용히 사라져 의존 모듈의 테스트가 안 돈다. 감지 신호를 먼저 바꿔라.`,
    )
  })

  test('★★Gradle 의존 참조를 다 읽지 못하면 좁히지 않는다', () => {
    // `MODULE_REF` 는 `project(":modules:X")` 형태만 잡는다. Gradle 은
    // `project(path = ":modules:X")` 같은 변형도 허용하고, 그런 간선은 **조용히 사라진다** —
    // 그러면 의존 모듈의 테스트가 안 돌고 깨진 채 초록으로 머지된다(좁아지는 방향).
    //
    // 지금은 전부 표준형이라 목록이 비어 있어야 한다. 그 사실 자체가 이 단언의 전제다.
    assert.deepEqual(
      modulesWithUnparsedRefs(),
      [],
      `읽지 못한 :modules: 참조가 있다 — 간선이 사라져 좁아진다.\n` +
        `정규식을 늘리기 전에 「못 읽으면 넓힌다」가 실제로 도는지 먼저 확인할 것.`,
    )
  })

  test('★★비표준 Gradle 참조가 있으면 감지하고 전 모듈로 넓힌다 (이음매로 주입)', () => {
    // ★위 단언만으로는 부족하다. 현재 저장소에 비표준 참조가 **하나도 없어서**, 감지·확장
    //   분기를 통째로 지워도 아무것도 안 깨진다(2026-08-12 뮤테이션 M7 생존).
    //   그래서 가짜 모듈 트리를 주입해 그 분기를 **실제로 밟는다.**
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-modules-'))
    const mk = (name: string, body: string) => {
      fs.mkdirSync(path.join(root, name), { recursive: true })
      fs.writeFileSync(path.join(root, name, 'build.gradle.kts'), body)
    }
    mk('shared-kernel', 'dependencies {\n}\n')
    mk('alpha', 'dependencies {\n  implementation(project(":modules:shared-kernel"))\n}\n')
    // ★이 형태를 `MODULE_REF` 는 못 읽는다 — 간선이 조용히 사라지는 경로다.
    mk('beta', 'dependencies {\n  implementation(project(path = ":modules:alpha"))\n}\n')

    const prev = process.env.BTS_BACKEND_MODULES_ROOT
    process.env.BTS_BACKEND_MODULES_ROOT = root
    try {
      assert.deepEqual(
        modulesWithUnparsedRefs(),
        ['beta'],
        '비표준 참조를 감지하지 못했다 — 간선이 사라져도 좁힌다.',
      )
      // alpha 변경 → beta 가 alpha 를 의존하지만 그 간선을 못 읽었다. 좁히면 beta 테스트가
      // 안 돈다. 감지가 살아 있으면 전 모듈로 넓혀야 한다.
      const picked = selectModules(['backend/modules/alpha/X.kt'])
      assert.deepEqual(
        picked.modules.sort(),
        ['alpha', 'beta', 'shared-kernel'],
        `읽지 못한 간선이 있는데 좁혔다 — beta 가 검증 없이 통과한다.\n${JSON.stringify(picked)}`,
      )
      assert.equal(picked.all, true)
      assert.match(picked.reason, /다 읽지 못했다/, '넓힌 사유가 파싱 실패를 말하지 않는다.')
    } finally {
      if (prev === undefined) delete process.env.BTS_BACKEND_MODULES_ROOT
      else process.env.BTS_BACKEND_MODULES_ROOT = prev
    }
  })

  test('★★실재하는 마이그레이션 경로 형태가 이 저장소에 있다 (비-공허 짝)', () => {
    // 아래 두 판정이 쓰는 경로가 가공이면 또 도달 불가를 지키게 된다. 실물로 고정한다.
    const real = spawnSync(
      'git',
      ['ls-files', 'backend/modules/*/src/main/resources/db/migration/**'],
      { cwd: REPO_ROOT, encoding: 'utf8', env: gitFixtureEnv() },
    )
    const files = real.stdout.split('\n').filter((f) => f.trim() !== '')
    assert.ok(
      files.length >= 50,
      `모듈 **안** 마이그레이션을 ${files.length}개밖에 못 찾았다 — 경로 형태가 바뀌었다면 아래 판정도 함께 고쳐야 한다.`,
    )
    // 그리고 그것이 실제로 모듈에 귀속되는 경로인지(=좁힐 위험이 있는지) 확인한다.
    assert.ok(
      files[0].startsWith('backend/modules/'),
      `마이그레이션이 모듈 밖에 있다면 이 판정 자체가 불필요하다 — ${files[0]}`,
    )

    // ★★하한만으로는 **부분 이동**을 못 잡는다 (2026-08-14 독립 리뷰 지적).
    //   마이그레이션이 마커 밖 새 위치로 조금씩 옮겨가고 구 위치에 하한 이상만 남으면 이 짝은
    //   계속 초록인데, 새 위치의 것은 `MIGRATION_MARKER` 를 안 타 **조용히 좁혀진다** —
    //   이 PR 이 방금 걷어낸 양식이 다른 자리에서 그대로 재발한다.
    //   그래서 「존재」가 아니라 **차집합**을 잰다. 이 저장소의 표준 처방이다
    //   (`[[two-lists-never-check-each-other]]`).
    const allSql = spawnSync('git', ['ls-files'], { cwd: REPO_ROOT, encoding: 'utf8', env: gitFixtureEnv() })
      .stdout.split('\n')
      .filter((f) => /\/V\d+__.*\.sql$/.test(f))
    assert.ok(
      allSql.length >= 50,
      `추적되는 \`V<숫자>__*.sql\` 을 ${allSql.length}개밖에 못 찾았다 — 이 차집합이 공허해진다.`,
    )
    const outside = allSql.filter((f) => !f.includes('/db/migration/'))
    assert.deepEqual(
      outside,
      [],
      `마이그레이션 파일이 \`/db/migration/\` 밖에 있다 — 선별기의 마커가 안 닿아 **조용히 좁혀진다**.\n` +
        `마커를 넓히거나 파일을 옮겨야 한다.\n` +
        outside.map((f) => `  - ${f}`).join('\n'),
    )
  })

  test('★★모듈 **안** 마이그레이션이 바뀌면 전 모듈이다 (모듈 역산보다 앞서 판정)', () => {
    // ADR D2. 마이그레이션 번호 대역은 여러 컨텍스트에 걸쳐 있어 **모듈 의존 그래프로는 영향
    // 범위를 원리적으로 계산할 수 없다.** 그래프가 답을 못 주는 축이라 넓히는 것이 유일한 정답이다.
    const picked = selectModules([
      'backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V599__x.sql',
    ])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `마이그레이션 변경이 한 모듈로 좁혀졌다 — 다른 컨텍스트의 스키마 영향이 검증 없이 통과한다.\n` +
        JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  test('★★마이그레이션이 다른 모듈 변경과 **섞여 있어도** 전 모듈이다', () => {
    // ★단일 입력만 재면 이 분기를 못 잡는다 — 이 저장소가 뮤테이션 M2·M6·M8 로 세 번 겪은 양식.
    //   씨앗이 **비지 않은** 상태에서 넓혀야 하는지를 재는 것이 이 케이스다.
    const picked = selectModules([
      'backend/modules/notification/src/main/kotlin/X.kt',
      'backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V299__y.sql',
    ])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `마이그레이션이 섞였는데 좁혔다 — 스키마 변경이 일부 모듈에서만 검증된다.\n` + JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  test('★★PR 라벨 `ci:full` 이 붙으면 전 모듈이다', () => {
    // ADR D2. 위험이 큰 작업은 **파일 경로에 신호가 없는 유일한 조건**이다 — 사람이 라벨로 말한다.
    const picked = selectModules(['backend/modules/notification/src/main/kotlin/X.kt'], ['ci:full'])
    assert.deepEqual(
      picked.modules.sort(),
      allModules().sort(),
      `ci:full 라벨을 무시하고 좁혔다 — 사람이 「전부 돌려라」라고 말한 유일한 통로가 막힌다.\n` +
        JSON.stringify(picked),
    )
    assert.equal(picked.all, true)
  })

  test('★★라벨이 없거나 다른 라벨이면 좁힘이 유지된다 (라벨 규칙이 전면 확대가 아님)', () => {
    // 반대 방향. 이 판정이 없으면 「라벨 분기를 항상 참으로」 만드는 뮤테이션이 살아남는다 —
    // 그러면 이 PR 이 줄이려던 것을 통째로 되돌리면서 위 판정은 초록이다.
    const narrow = selectModules(['backend/modules/notification/src/main/kotlin/X.kt'])
    assert.notDeepEqual(
      narrow.modules.sort(),
      allModules().sort(),
      '라벨 없이도 전 모듈이 나왔다 — 좁히기가 작동하지 않는다.',
    )
    for (const labels of [[], ['bc:notification'], ['type:fix'], ['ci:fulll'], ['CI:FULL']]) {
      const picked = selectModules(['backend/modules/notification/src/main/kotlin/X.kt'], labels)
      assert.deepEqual(
        picked.modules.sort(),
        narrow.modules.sort(),
        `라벨 ${JSON.stringify(labels)} 가 전 모듈로 넓혔다 — ci:full 정확 일치만 넓혀야 한다.`,
      )
    }
  })

  test('★★git 이 rename 을 접는다 — `--no-renames` 가 실제로 필요하다 (비-공허 짝)', () => {
    // ★배선 문자열만 재면 「그 플래그가 왜 필요한지」가 사라지고, git 기본값이 바뀌면 판정이
    //   조용히 무의미해진다. 그래서 **git 의 실제 동작**을 임시 저장소로 잰다.
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-rename-'))
    try {
      const git = (...args: string[]) =>
        spawnSync('git', args, { cwd: tmp, encoding: 'utf8', env: gitFixtureEnv({ ...process.env, HOME: tmp }) })
      git('init', '-q')
      git('config', 'user.email', 'x@example.com')
      git('config', 'user.name', 'x')
      fs.mkdirSync(path.join(tmp, 'a'), { recursive: true })
      // rename 유사도 판정을 확실히 넘기려면 내용이 충분히 있어야 한다.
      fs.writeFileSync(path.join(tmp, 'a/F.kt'), Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n'))
      git('add', '-A')
      git('commit', '-qm', 'base')
      fs.mkdirSync(path.join(tmp, 'b'), { recursive: true })
      fs.renameSync(path.join(tmp, 'a/F.kt'), path.join(tmp, 'b/F.kt'))
      git('add', '-A')
      git('commit', '-qm', 'move')

      const paths = (extra: string[]) =>
        git('-c', 'core.quotePath=false', 'diff', ...extra, '--name-only', '-z', 'HEAD~1', 'HEAD')
          .stdout.split('\0')
          .filter((p) => p !== '')

      const collapsed = paths([])
      const full = paths(['--no-renames'])

      // 이 판정이 공허해지는 유일한 길은 git 이 rename 을 접지 않게 되는 것이다. 그때는
      // 아래 단언이 RED 가 되고, 사람이 「플래그가 이제 불필요한가」를 판단하게 된다.
      assert.equal(
        collapsed.length,
        1,
        `git 이 rename 을 접지 않았다 — 이 짝의 전제가 바뀌었다. 실제 출력. ${JSON.stringify(collapsed)}`,
      )
      assert.deepEqual(
        full.sort(),
        ['a/F.kt', 'b/F.kt'],
        `--no-renames 가 출발지를 살리지 못했다. 실제 출력. ${JSON.stringify(full)}`,
      )
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true })
    }
  })

  // ★2026-09-09 P4 포팅. 이 배선이 **젠킨스 경로에서 통째로 사라져 있었다.**
  //
  //   Actions 시절에는 워크플로우 YAML 의 select 잡이 직접 `git diff` 를 돌렸고 거기에
  //   `--no-renames` 가 있었다. 젠킨스는 YAML 대신 `select-test-scope.ts` 가 **내부에서**
  //   diff 하는데, 옮기면서 그 플래그가 따라오지 않았다.
  //
  //   ★「YAML 이 하던 일을 스크립트가 물려받았다」는 이전에서 가장 놓치기 쉬운 자리다 —
  //   옮긴 쪽에는 그 줄이 **애초에 없어서** 지운 흔적조차 남지 않는다. 이 단언을 포팅하며
  //   비로소 드러났다.
  test('★★계산기의 diff 명령에 `--no-renames` 가 있다 (젠킨스 배선)', () => {
    const src = fs.readFileSync(path.join(REPO_ROOT, 'scripts/workflow/select-test-scope.ts'), 'utf8')
    assert.match(
      src,
      /git\(\[\s*'diff',\s*'--name-only',\s*'--no-renames'/,
      'select-test-scope.ts 의 변경 파일 수집에 `--no-renames` 가 없다.\n' +
        '  모듈 간 파일 이동에서 **출발 모듈의 테스트가 통째로 건너뛰어진다** — 좁아지는 방향이고,\n' +
        '  좁게 고르는 실수만이 치명적이다(검증 안 된 코드가 초록으로 머지된다).\n' +
        '  젠킨스는 이 계산기가 유일한 diff 자리다 — 워크플로우 YAML 에는 이제 아무것도 없다.',
    )
  })

  test('★모듈 간 이동에서 두 경로가 다 오면 두 모듈이 다 선별된다 (처방 확인)', () => {
    // `--no-renames` 가 준 입력을 선별기가 제대로 소화하는지. 위 두 판정의 짝이다.
    const picked = selectModules([
      'backend/modules/identity-access/src/main/kotlin/F.kt',
      'backend/modules/notification/src/main/kotlin/F.kt',
    ])
    assert.ok(
      picked.modules.includes('identity-access'),
      `출발 모듈이 빠졌다 — ${JSON.stringify(picked)}`,
    )
    assert.ok(
      picked.modules.includes('notification'),
      `목적 모듈이 빠졌다 — ${JSON.stringify(picked)}`,
    )
  })

  test('★넓은 판정과 좁은 판정을 구분해 보고한다', () => {
    // 로그를 읽는 사람이 「전부 돈다」가 **의도**인지 **판정 실패**인지 알아야 한다.
    const narrow = selectModules(['backend/modules/automation/X.kt'])
    const wide = selectModules(['backend/build.gradle.kts'])
    assert.equal(narrow.all, false)
    assert.equal(wide.all, true)
    assert.ok(wide.reason.length > 0, '전 모듈로 넓힌 사유가 비어 있다 — 로그가 설명하지 못한다.')
  })
})

/**
 * ★CI 설정 변경은 **전량 빌드**로 다룬다 (2026-09-10).
 *
 * ## 왜 필요했나 — 두 판단이 서로를 모르고 있었다
 *
 * 젠킨스 `전량 판정` stage 는 브랜치·파라미터·야간만 보고 `RUN_FULL` 을 정했고, 그것이
 * false 면 「빠른 게이트」로 갔다. 그런데 그 안에서 이 계산기가 [WIDEN_PREFIXES] 를 보고
 * **전량으로 넓혔다.** 이름은 빠른 게이트인데 32.7분이 걸렸다(빌드 #21 실측).
 *
 * 더 나쁜 것은 **전량 stage 에만 있는 검사를 건너뛴다**는 점이다 —
 * 「조립 부팅」(`:modules:app` 조립)과 「인프라 봉인」(nginx 로그 마스킹 · springdoc 비노출).
 * 「영향 범위를 모르니 전부 본다」면서 정작 그 둘을 안 보는 반쪽 확대였다.
 *
 * ## 목록을 두 번 적지 않는다
 *
 * 판정 입력은 [WIDEN_PREFIXES] **하나**다. 젠킨스가 같은 목록을 따로 적으면 그 순간
 * 두 목록이 되고, 새 CI 파일이 생길 때 한쪽만 고쳐진다.
 */
describe('CI 설정 변경 → 전량 빌드', () => {
  test('★Jenkinsfile 변경은 전량이다', () => {
    assert.equal(requiresFullBuild(['Jenkinsfile']), true)
  })

  test('★Jenkinsfile.e2e 변경도 전량이다 (접두 일치)', () => {
    assert.equal(requiresFullBuild(['Jenkinsfile.e2e']), true)
  })

  test('★선별기 자신이 바뀌어도 전량이다', () => {
    assert.equal(requiresFullBuild(['scripts/workflow/select-backend-modules.ts']), true)
  })

  test('평범한 프론트 변경은 전량이 아니다', () => {
    assert.equal(requiresFullBuild(['apps/web/src/components/board/Board.tsx']), false)
  })

  test('문서 변경은 전량이 아니다', () => {
    assert.equal(requiresFullBuild(['docs/rules/traps.md']), false)
  })

  test('★변경 목록을 못 구하면(null) 전량이다 — 모르면 넓게', () => {
    assert.equal(requiresFullBuild(null), true)
  })

  test('★판정 입력이 WIDEN_PREFIXES 하나다 (두 목록 차단)', () => {
    // 목록의 원소 **전부**가 전량으로 판정돼야 한다. 하나라도 빠지면 그 경로를 고친 PR 이
    // 일부만 검증되고, 그 사실이 조용하다.
    for (const p of WIDEN_PREFIXES) {
      const sample = p.endsWith('/') ? `${p}x/y.kt` : p
      assert.equal(
        requiresFullBuild([sample]),
        true,
        `WIDEN_PREFIXES 의 '${p}' 가 전량 판정에 안 걸린다 — 두 판단이 갈렸다.`,
      )
    }
    assert.ok(WIDEN_PREFIXES.length > 0, 'WIDEN_PREFIXES 가 비었다 — 위 루프가 공허해진다.')
  })
})
