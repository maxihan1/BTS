// Gradle 성능 스위치와 그 부작용 차단을 한 계약으로 묶는 판별식
//
// ## 왜 이 파일이 생겼나
//
// 2026-08-25 실측. `backend/gradle.properties` 는 6줄이었고 그중 3줄이 성능을 껐다 —
// `org.gradle.daemon=false` · `org.gradle.parallel=false` · `org.gradle.caching` 미설정.
// 아무것도 안 바뀐 no-op 빌드가 12초였다(3 tasks up-to-date). 전부 JVM 콜드 스타트 비용이다.
//
// ## ★스위치 하나가 다른 곳에 구멍을 낸다
//
// `org.gradle.caching=true` 를 켜는 순간 ktlint/detekt 가 캐시로 UP-TO-DATE 통과할 수 있다.
// `backend-ci.yml` 은 이미 그 사실을 알고 `--rerun-tasks` 로 우회한다. **로컬 검증 명령에는
// 그 우회가 없었다.** 즉 캐시를 켠 채 로컬만 고치지 않으면 「린트 초록」이 거짓이 된다.
//
// 이 저장소가 이름 붙인 지배 결함 양식이 「두 목록이 서로를 검사하지 않는다」다.
// 여기서 두 목록은 ①gradle.properties 의 성능 스위치 ②로컬 검증 명령의 `--rerun-tasks` 다.
// 둘을 **한 파일 안에서 서로 보게** 만들어 갈라짐이 red 로 드러나게 한다.
//
// ## 무엇을 재나
//
// 「파일에 문자열이 있다」가 아니라 **「값이 실제로 켜져 있다」**를 잰다. 주석 처리된 줄이
// 매칭되면 끈 설정도 통과하므로 파서가 주석을 먼저 걷어낸다.
// 판별식이 실제로 red 를 낼 수 있는지는 §양성 대조군이 합성 입력으로 확인한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { computeScope, renderCommands } from './select-test-scope.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

/**
 * 켜져 있어야 하는 Gradle 성능 스위치. 값까지 대조한다.
 *
 * ★`org.gradle.parallel` 은 여기 없다. **일부러 꺼 둔다** — 아래 DELIBERATELY_OFF 참조.
 */
const REQUIRED_SWITCHES: ReadonlyArray<readonly [string, string]> = [
  ['org.gradle.daemon', 'true'],
  ['org.gradle.caching', 'true'],
  ['org.gradle.configuration-cache', 'true'],
]

/**
 * **일부러 꺼 둔 스위치.** 켜는 것이 이득처럼 보이지만 실측이 아니라고 말한 것들이다.
 * 값이 바뀌면 red 가 나고, 바꾸려는 사람이 아래 근거를 마주친다.
 */
const DELIBERATELY_OFF: ReadonlyArray<readonly [string, string, string]> = [
  [
    'org.gradle.parallel',
    'false',
    '2026-08-25 실측. 전량 test 를 --rerun-tasks 로 돌린 A/B —\n' +
      '  parallel=true  762초에 :modules:notification:test 실패로 중단(52/59). 다시 돌리면\n' +
      '                 실패 대상이 바뀐다(SUB-2 → SUB-1) = 흔들림이지 결함이 아니다.\n' +
      '  parallel=false 928초 · 59/59 전량 실행 · BUILD SUCCESSFUL.\n' +
      '  이득은 ~18% 뿐이다 — 프로파일상 parallel=true 는 경합으로 각 태스크를 2배 느리게\n' +
      '  만들고(태스크 합계 28.5분) 병렬로 그걸 겨우 되돌린다.\n' +
      '  깨지는 쪽은 NotificationWorkerSubscriptionFilterTest 처럼 Awaitility +\n' +
      '  poll-interval 50ms 로 시간에 민감한 워커 테스트다. CPU 를 뺏기면 진다.\n' +
      '  ⇒ 되살리려면 먼저 그 테스트들의 시간 민감성을 없애라. 순서가 반대면 가짜 초록이 된다.',
  ],
]

/**
 * `caching=true` 의 부작용을 막는 곳. 이 파일들의 gradle 린트 호출은 `--rerun-tasks` 를
 * 반드시 달아야 한다. 캐시가 위반을 삼키는 것을 막는 유일한 지점이다.
 *
 * ★2026-09-04 — 로컬 린트 호출이 `bts-impl/SKILL.md` 본문에서
 * `select-test-scope.ts` 의 렌더 출력으로 옮겨갔다(전량 명령을 스킬에 두면 사람이 그쪽을
 * 복사해 쓰기 때문). 텍스트 목록에서 빼는 대신 **렌더된 실제 명령을 검사**한다 — 아래
 * 「렌더 출력」 describe. 문자열 매칭보다 강한 형태이고, 계약은 그대로 살아 있다.
 */
const LINT_BYPASS_SITES: readonly string[] = []

/** `.properties` 본문에서 주석과 빈 줄을 걷어낸 key=value 맵을 만든다. */
export function parseProperties(source: string): Map<string, string> {
  const out = new Map<string, string>()
  for (const raw of source.split('\n')) {
    const line = raw.trim()
    if (line === '' || line.startsWith('#') || line.startsWith('!')) continue
    const eq = line.indexOf('=')
    if (eq === -1) continue
    out.set(line.slice(0, eq).trim(), line.slice(eq + 1).trim())
  }
  return out
}

/**
 * 마크다운에서 gradle 린트를 부르는 줄만 뽑는다.
 *
 * ★`ktlintCheck` 또는 `detekt` 를 부르는 `./gradlew` 줄만 본다. `test` 만 부르는 줄에
 *   `--rerun-tasks` 를 요구하면 캐시 이득이 통째로 사라지므로 대상에서 뺀다.
 */
export function gradleLintLines(source: string): string[] {
  return source
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.includes('./gradlew') && (l.includes('ktlintCheck') || l.includes('detekt')))
}

describe('gradle 성능 스위치', () => {
  const propsPath = path.join(REPO_ROOT, 'backend/gradle.properties')
  const props = parseProperties(fs.readFileSync(propsPath, 'utf8'))

  for (const [key, expected] of REQUIRED_SWITCHES) {
    test(`${key} 가 ${expected} 다`, () => {
      assert.equal(
        props.get(key),
        expected,
        `backend/gradle.properties 의 ${key} 가 ${expected} 가 아니다 (현재: ${props.get(key) ?? '미설정'}).\n` +
          '  이 스위치들이 꺼져 있으면 no-op 빌드마다 JVM 콜드 스타트와 전체 configuration 비용을\n' +
          '  다시 낸다 — 2026-08-25 실측으로 12초 대 0.83초다.',
      )
    })
  }

  for (const [key, expected, reason] of DELIBERATELY_OFF) {
    test(`${key} 는 ${expected} 로 남는다 (일부러)`, () => {
      assert.equal(
        props.get(key),
        expected,
        `backend/gradle.properties 의 ${key} 가 ${expected} 가 아니다 (현재: ${props.get(key) ?? '미설정'}).\n` +
          `  이건 성능을 놓친 게 아니라 **일부러 꺼 둔 것**이다.\n${reason}`,
      )
    })
  }
})

describe('caching 의 부작용 차단 — 렌더 출력의 린트 명령', () => {
  // 로컬 검증 명령의 정본은 이제 `select-test-scope.ts` 의 렌더다.
  // 텍스트가 아니라 **실제로 사람에게 출력되는 명령**을 본다.
  const rendered = renderCommands(
    computeScope(['backend/modules/notification/src/main/kotlin/A.kt'], null),
  )
  const lines = gradleLintLines(rendered)

  test('★렌더 출력에 gradle 린트 호출이 존재한다', () => {
    // 부재는 통과가 아니다. 호출이 사라지면 계약이 공허해지므로 그것부터 red 다.
    assert.ok(
      lines.length > 0,
      'select-test-scope.ts 의 렌더 출력에 gradle 린트 호출이 없다.\n' +
        '  백엔드가 바뀐 브랜치인데 ktlint/detekt 를 아무도 안 부른다는 뜻이다.',
    )
  })

  test('★렌더된 린트 호출이 --rerun-tasks 를 단다', () => {
    for (const line of lines) {
      assert.ok(
        line.includes('--rerun-tasks'),
        `렌더된 린트 호출에 --rerun-tasks 가 없다:\n    ${line}\n` +
          '  org.gradle.caching=true 인 상태에서 ktlint/detekt 가 UP-TO-DATE 로 통과해\n' +
          '  위반이 있어도 「린트 초록」이 된다. backend-ci.yml 이 같은 이유로 이미 우회 중이다.',
      )
    }
  })

  test('린트 대상이 바뀐 모듈로 좁혀져 있다', () => {
    assert.match(lines.join('\n'), /:modules:notification:ktlintCheck/)
    assert.doesNotMatch(
      lines.join('\n'),
      /:modules:issue-tracking:/,
      '바뀌지 않은 모듈까지 린트하고 있다 — 좁힘이 깨졌다',
    )
  })

  for (const site of LINT_BYPASS_SITES) {
    test(`${site} 의 gradle 린트 호출이 --rerun-tasks 를 단다`, () => {
      const source = fs.readFileSync(path.join(REPO_ROOT, site), 'utf8')
      const lines = gradleLintLines(source)

      // 부재는 통과가 아니다. 호출이 사라지면 계약이 공허해지므로 그것부터 red 다.
      assert.ok(
        lines.length > 0,
        `${site} 에 gradle 린트 호출이 하나도 없다. 계약이 지킬 대상을 잃었다 — 목록을 갱신할 것.`,
      )

      for (const line of lines) {
        assert.ok(
          line.includes('--rerun-tasks'),
          `${site} 의 린트 호출에 --rerun-tasks 가 없다:\n    ${line}\n` +
            '  org.gradle.caching=true 인 상태에서 ktlint/detekt 가 UP-TO-DATE 로 통과해\n' +
            '  위반이 있어도 「린트 초록」이 된다. backend-ci.yml 이 같은 이유로 이미 우회 중이다.',
        )
      }
    })
  }
})

describe('양성 대조군 — 판별식이 비어 있지 않다', () => {
  test('주석 처리된 스위치는 켜진 것으로 읽지 않는다', () => {
    const parsed = parseProperties('# org.gradle.caching=true\norg.gradle.daemon=false\n')
    assert.equal(parsed.get('org.gradle.caching'), undefined)
    assert.equal(parsed.get('org.gradle.daemon'), 'false')
  })

  test('스위치가 false 면 대조가 실패한다', () => {
    const parsed = parseProperties('org.gradle.parallel=false\n')
    assert.notEqual(parsed.get('org.gradle.parallel'), 'true')
  })

  test('--rerun-tasks 없는 린트 호출을 실제로 골라낸다', () => {
    const lines = gradleLintLines('./gradlew ktlintCheck detekt --console=plain\n./gradlew test\n')
    assert.equal(lines.length, 1)
    assert.equal(lines[0].includes('--rerun-tasks'), false)
  })

  test('test 만 부르는 줄은 대상이 아니다', () => {
    assert.deepEqual(gradleLintLines('./gradlew test --console=plain\n'), [])
  })
})
