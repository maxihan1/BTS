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
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { moduleGraph, allModules, selectModules } from './select-backend-modules.ts'

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

  test('★넓은 판정과 좁은 판정을 구분해 보고한다', () => {
    // 로그를 읽는 사람이 「전부 돈다」가 **의도**인지 **판정 실패**인지 알아야 한다.
    const narrow = selectModules(['backend/modules/automation/X.kt'])
    const wide = selectModules(['backend/build.gradle.kts'])
    assert.equal(narrow.all, false)
    assert.equal(wide.all, true)
    assert.ok(wide.reason.length > 0, '전 모듈로 넓힌 사유가 비어 있다 — 로그가 설명하지 못한다.')
  })
})
