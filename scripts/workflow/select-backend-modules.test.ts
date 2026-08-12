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
} from './select-backend-modules.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/** 실측 기준선. 줄어들면 도출이 고장난 것이다. */
const MIN_MODULES = 9

const WORKFLOW = '.github/workflows/backend-ci.yml'

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

  test('★★디스크의 모든 모듈이 매트릭스이거나 전용 잡을 갖는다 (조용한 누락 차단)', () => {
    // 새 BC 를 추가하면 자동으로 들어와야 한다. 손으로 적는 목록이 없다는 것의 실질이다.
    //
    // ★매트릭스에서 빼는 것 자체는 정당할 수 있다 — `app` 은 「조립 부팅」 전용 잡이 통째로
    //   맡는다. 위험한 것은 **빠졌는데 아무도 안 도는** 상태다. 그래서 제외 모듈은
    //   backend-ci 안에 자기 잡이 있는지까지 확인한다.
    const onDisk = fs
      .readdirSync(path.join(REPO_ROOT, 'backend/modules'), { withFileTypes: true })
      .filter((e) => e.isDirectory())
      .map((e) => e.name)
      .sort()

    const inMatrix = new Set(allModules())
    const excluded = onDisk.filter((m) => !inMatrix.has(m))

    // 합집합이 디스크와 같아야 한다 — 어느 쪽에도 없는 모듈이 있으면 그것이 조용한 누락이다.
    assert.deepEqual(
      [...inMatrix, ...excluded].sort(),
      onDisk,
      '디스크 모듈이 매트릭스에도 제외 목록에도 없다 — 아무도 안 도는 모듈이 생겼다.',
    )

    const workflow = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW), 'utf8')
    const orphan = excluded.filter((m) => !new RegExp(`:modules:${m}\\b`).test(workflow))
    assert.deepEqual(
      orphan,
      [],
      `매트릭스에서 빠졌는데 전용 잡도 없는 모듈이 있다: ${orphan.join(', ')}\n` +
        `그 모듈은 backend-ci 에서 **한 번도 검증되지 않는다.**`,
    )
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

  test('★★backend-ci 가 선별기를 실제로 부르고 매트릭스에 먹인다 (배선)', () => {
    // 선별기만 있고 아무도 안 부르면 12개 잡은 그대로 돈다 — 이 저장소가 여러 번 겪은
    // 「가드는 있는데 배선이 없다」 양식이다. 여기서는 반대로 **줄이는 도구**가 안 불리는 것이라
    // 조용히 아무 일도 안 일어난다(빨간불조차 없다).
    const workflow = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW), 'utf8')

    assert.match(
      workflow,
      /select-backend-modules\.ts/,
      `${WORKFLOW} 가 선별기를 부르지 않는다 — 스크립트가 있어도 실행되지 않는다.`,
    )
    assert.match(
      workflow,
      /matrix:\s*\n\s*module:\s*\$\{\{\s*fromJSON\(needs\.select\.outputs\.modules\)\s*\}\}/,
      `${WORKFLOW} 의 매트릭스가 선별 결과를 받지 않는다 — 골라 놓고 안 쓰는 상태다.`,
    )
  })

  test('★★매트릭스가 고정 목록으로 되돌아가지 않았다 (두 목록 차단)', () => {
    // 목록을 워크플로우에 다시 적으면 그 목록과 `backend/modules/` 실물이 서로를 안 보는
    // 두 목록이 된다 — 새 BC 를 추가하고 목록에 안 적으면 그 모듈은 **한 번도 안 돈다.**
    const workflow = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW), 'utf8')
    const matrixBlock = workflow.slice(
      workflow.indexOf('matrix:'),
      workflow.indexOf('steps:', workflow.indexOf('matrix:')),
    )
    assert.ok(matrixBlock.length > 0, '매트릭스 블록을 못 찾았다 — 아래 단언이 공허해진다.')

    const hardcoded = allModules().filter((m) =>
      new RegExp(`^\\s*-\\s*${m}\\s*$`, 'm').test(matrixBlock),
    )
    assert.deepEqual(
      hardcoded,
      [],
      `매트릭스에 모듈이 하드코딩돼 있다: ${hardcoded.join(', ')}\n` +
        `선별 결과와 이 목록 중 무엇이 실제로 도는지가 갈리고, 새 BC 는 조용히 빠진다.`,
    )
  })

  test('★★선별기 자신의 변경은 backend-ci 를 트리거하고 전 모듈을 고른다', () => {
    // 없으면 「선별기를 너무 좁게 고치는 PR」이 `scripts/**` 만 건드리므로 backend-ci 가
    // PR 에서도 머지 후에도 **0회** 돈다 — 무엇을 돌릴지 정하는 코드가 정작 백엔드 잡으로는
    // 한 번도 검증되지 않는다(독립 리뷰 적발).
    const SELF = 'scripts/workflow/select-backend-modules.ts'
    const workflow = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW), 'utf8')

    // 트리거 2벌(pull_request · push). 한쪽만 걸면 봉인이 절반이다.
    const occurrences = [...workflow.matchAll(new RegExp(`^\\s*-\\s*'${SELF}'$`, 'gm'))].length
    assert.equal(
      occurrences,
      2,
      `${WORKFLOW} 의 paths 에 선별기가 ${occurrences}곳 걸려 있다 (pull_request·push 2곳이어야 한다).`,
    )

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

  test('★★select 스텝의 셸이 이 머신에서 실제로 돈다 (문자열 매칭이 못 보는 층)', () => {
    // ## 왜 실행해 보는가
    //
    // 위 단언들은 선별기의 **판정**을 잰다. 그런데 그 판정을 부르는 것은 워크플로우 안의
    // **인라인 셸**이고, 거기서 죽으면 매트릭스가 통째로 스킵된다 — 판정이 아무리 옳아도 소용없다.
    //
    // 실제로 그렇게 죽었다. 초안이 `xargs -a` 를 썼는데 그것은 **GNU 전용**이고 러너는
    // macOS(BSD xargs)라 `invalid option -- a` 로 사망했다(2026-08-12 실측). 유닛 테스트는
    // 전부 초록이었다 — 그 층을 아무도 안 재고 있었다.
    //
    // 이 저장소는 같은 교훈을 이미 적어 두었다(`runner-health.yml` 의 자원 판정 블록을 뽑아
    // `bash -e` 로 돌리는 판별식). 「두 층의 차이는 문자열 매칭이 못 본다.」
    const workflow = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW), 'utf8')

    const stepIdx = workflow.indexOf('- name: 변경 파일 → 대상 모듈')
    assert.ok(stepIdx >= 0, 'select 스텝을 못 찾았다 — 추출이 고장났다.')
    const runIdx = workflow.indexOf('run: |', stepIdx)
    assert.ok(runIdx > stepIdx, 'select 스텝에 run 블록이 없다.')

    const INDENT = 10
    const lines: string[] = []
    for (const line of workflow.slice(runIdx).split('\n').slice(1)) {
      if (line.trim() === '') {
        lines.push('')
        continue
      }
      if (!line.startsWith(' '.repeat(INDENT))) break
      lines.push(line.slice(INDENT))
    }
    // ★비-공허 확인. 앵커가 어긋나 빈 스크립트가 나오면 `bash -e ""` 는 그냥 성공하고
    //   아래 단언이 **공허하게 통과**한다 — 가드가 있는 척하는 최악의 상태다.
    assert.ok(
      lines.filter((l) => l.trim() !== '').length >= 8,
      `추출된 스텝이 ${lines.length}줄뿐이다 — 빈 스크립트를 돌리면 단언이 공허하다.`,
    )

    // `${{ ... }}` 는 Actions 가 치환한다. 여기서는 실제 커밋으로 갈아끼워 돌린다.
    const script = lines.join('\n').replace(/\$\{\{\s*github\.sha\s*\}\}/g, 'HEAD')
    assert.match(script, /xargs/, '추출본에 실행부가 없다 — 엉뚱한 블록을 잘라냈다.')

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-select-step-'))
    const file = path.join(dir, 'step.sh')
    fs.writeFileSync(file, script)
    // ★결과는 stdout 이 아니라 `$GITHUB_OUTPUT` 으로 나간다 — 스텝이 매트릭스에 넘기는 통로가
    //   그것이기 때문이다. stdout 을 재면 「셸은 살았는데 아무것도 안 넘긴다」를 못 본다.
    const outFile = path.join(dir, 'github_output')
    fs.writeFileSync(outFile, '')

    const r = spawnSync('bash', ['-e', file], {
      cwd: REPO_ROOT,
      encoding: 'utf-8',
      env: {
        ...process.env,
        BASE_SHA: 'HEAD~1',
        GITHUB_OUTPUT: outFile,
        GITHUB_STEP_SUMMARY: path.join(dir, 'summary.md'),
      },
    })
    assert.equal(
      r.status,
      0,
      `select 스텝의 셸이 이 머신에서 죽는다 (exit ${r.status}).\n` +
        `${r.stdout ?? ''}${r.stderr ?? ''}\n` +
        `러너는 macOS 다 — GNU 전용 옵션(xargs -a 등)을 쓰면 여기서 잡힌다.`,
    )

    const emitted = fs.readFileSync(outFile, 'utf8')
    assert.match(
      emitted,
      /^modules=\[".+"\]$/m,
      `매트릭스로 넘길 모듈 JSON 이 안 나왔다 — 셸은 살았는데 아무것도 안 넘긴다.\n` +
        `GITHUB_OUTPUT: ${JSON.stringify(emitted)}\n${r.stdout ?? ''}${r.stderr ?? ''}`,
    )

    // ★★diff 가 실패하는 경우(얕은 클론 · base 부재)도 **같은 셸로** 확인한다.
    //   여기가 이 스텝의 fail-safe 다. 2026-08-12 CI 실측에서 정확히 이 경로가 무너졌다 —
    //   입력이 비면 `xargs` 가 명령을 **아예 실행하지 않아** 빈 문자열이 나왔고, 그것을
    //   그대로 넘기면 `fromJSON('')` 이 매트릭스를 깨뜨린다. 유닛 테스트로는 못 보는 층이다.
    const outFile2 = path.join(dir, 'github_output_2')
    fs.writeFileSync(outFile2, '')
    const r2 = spawnSync('bash', ['-e', file], {
      cwd: REPO_ROOT,
      encoding: 'utf-8',
      env: {
        ...process.env,
        // 존재하지 않는 ref — diff 가 실패해 목록이 비는 경로를 강제한다.
        BASE_SHA: '0000000000000000000000000000000000000000',
        GITHUB_OUTPUT: outFile2,
        GITHUB_STEP_SUMMARY: path.join(dir, 'summary2.md'),
      },
    })
    assert.equal(
      r2.status,
      0,
      `diff 실패 경로에서 스텝이 죽는다 (exit ${r2.status}) — fail-safe 가 성립하지 않는다.\n` +
        `${r2.stdout ?? ''}${r2.stderr ?? ''}`,
    )
    const emitted2 = fs.readFileSync(outFile2, 'utf8')
    assert.match(
      emitted2,
      /^modules=\[".+"\]$/m,
      `diff 실패 시 모듈 JSON 이 비었다 — 매트릭스가 조용히 스킵된다.\n` +
        `GITHUB_OUTPUT: ${JSON.stringify(emitted2)}\n${r2.stdout ?? ''}${r2.stderr ?? ''}`,
    )
    const picked2 = JSON.parse(emitted2.match(/^modules=(.+)$/m)?.[1] ?? '[]') as string[]
    assert.deepEqual(
      picked2.sort(),
      allModules().sort(),
      `diff 실패인데 전 모듈이 아니다 — 모르는 상태에서 좁혔다.\n${emitted2}`,
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
