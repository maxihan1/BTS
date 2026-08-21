// 푸시 훅의 백엔드 모듈 테스트 스크립트 계약 — 좁힘·탈출구·오진 방지·훅 배선
//
// ## 무엇을 지키나
//
// CI 자동 실행을 끈 뒤(2026-08-21) 이 스크립트가 **백엔드 코드에 대한 유일한 기계 검증**이다.
// 그래서 재는 것이 「돌아가는가」가 아니라 **「어떤 성질을 유지하는가」**다.
//
//   1. 마이그레이션 넓힘을 꺼서 좁게 돈다 — 안 그러면 55분이고 사람이 훅을 꺼 버린다.
//   2. 탈출구가 있다 — 없으면 `--no-verify` 를 쓰고 lint·판별식까지 함께 죽는다.
//   3. 「Docker 부재」와 「테스트 실패」를 구분한다 — 뭉개면 엉뚱한 곳을 판다(PR #367).
//   4. 훅이 실제로 이 스크립트를 부른다 — 안 부르면 위 셋이 전부 장식이다.
//
// ## ★소스 대조 테스트를 쓰는 이유
//
// 이 스크립트의 본체는 `execFileSync('./gradlew', …)` 라 단위 테스트로 실행할 수 없다
// (돌리면 진짜로 16분이 나간다). 그래서 **소스에 그 성질이 적혀 있는지**를 본다.
// 약한 형태의 검증임을 인정하고, 그 한계를 좁히기 위해 실행 가능한 부분(`dockerAlive`)은
// 이음매로 실제 실행한다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { SKIP_ENV, changedFiles, dockerAlive } from './push-backend-tests.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = 'scripts/workflow/push-backend-tests.ts'
const HOOK = '.husky/pre-push'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

describe('푸시 훅 백엔드 모듈 테스트', () => {
  test('★훅이 이 스크립트를 부른다', () => {
    const hook = read(HOOK)
      .split('\n')
      .filter((l) => l.trim() !== '' && !l.trim().startsWith('#'))
      .join('\n')

    assert.ok(
      hook.includes(SCRIPT),
      `${HOOK} 가 ${SCRIPT} 를 부르지 않는다.\n` +
        '이 스크립트가 안 불리면 백엔드 코드는 **아무 기계 검증도 받지 않는다** — ' +
        'CI 자동 실행을 껐으므로 다른 자리가 없다.',
    )
  })

  test('★마이그레이션 넓힘을 끄고 부른다 (그래야 훅이 살아남는다)', () => {
    const src = read(SCRIPT)

    assert.ok(
      /selectModules\([^)]*widenOnMigration:\s*false/s.test(src),
      `${SCRIPT} 가 widenOnMigration:false 로 부르지 않는다.\n` +
        '켜 두면 마이그레이션 한 줄에 9모듈(약 55분)이다. 사람이 --no-verify 를 쓰기 시작하고 ' +
        '그 순간 lint·판별식까지 함께 죽는다 — 훅의 실패는 언제나 이 경로로 온다.',
    )
  })

  test('★★그래프 넓힘까지 끄지는 않았다', () => {
    // 「좁게 돈다」가 「아무것도 안 본다」로 미끄러지는 것을 막는다.
    // 역의존 폐포는 그래프로 계산 가능한 **정당한** 넓힘이라 유지돼야 한다.
    const src = read(SCRIPT)
    const opts = src.match(/selectModules\((.*?)\)\s*$/ms)?.[1] ?? src

    for (const forbidden of ['allModules()', 'skipGraph', 'moduleOf(']) {
      assert.equal(
        opts.includes(forbidden),
        false,
        `${SCRIPT} 가 selectModules 를 우회해 모듈을 직접 계산한다 (${forbidden}).\n` +
          '그러면 역의존 폐포가 사라져 「검증 안 된 코드가 통과」하는 방향의 회귀가 된다.',
      )
    }
  })

  test('★탈출구가 있다', () => {
    const src = read(SCRIPT)
    assert.ok(
      src.includes(SKIP_ENV),
      `${SCRIPT} 에 탈출구(${SKIP_ENV})가 없다.\n` +
        '탈출구가 없으면 급한 사람이 --no-verify 를 쓴다. 그것은 이 스크립트만이 아니라 ' +
        '훅에 걸린 **모든** 검사를 함께 끈다 — 좁은 탈출구가 넓은 우회보다 안전하다.',
    )
    assert.equal(SKIP_ENV, 'BTS_SKIP_MODULE_TEST', '탈출구 이름이 바뀌었다 — 문서·메시지와 갈라진다.')
  })

  test('★「Docker 부재」와 「테스트 실패」를 다른 메시지로 말한다', () => {
    // PR #367 — 설정은 멀쩡하고 데몬만 꺼져 있었는데 「문법 오류」로 보고돼
    // 사람이 잡 12개 로그를 뒤졌다. 재는 것은 「실패하는가」가 아니라 「무엇이라고 말하는가」다.
    const src = read(SCRIPT)
    assert.ok(src.includes('Docker 데몬'), 'Docker 데몬 부재를 가리키는 문구가 없다.')
    assert.ok(
      src.includes('코드 문제가 아니다'),
      '데몬 부재 메시지가 「코드 문제가 아니다」를 말하지 않는다 — 그 한 줄이 오진을 막는다.',
    )
  })

  describe('dockerAlive — 이음매로 실제 실행한다', () => {
    const original = process.env.BTS_DOCKER_BIN

    test('데몬이 응답하면 true', () => {
      // `true` 는 무엇을 받든 0 으로 끝나는 실제 바이너리다. 임시 파일도 chmod 도 필요 없다.
      process.env.BTS_DOCKER_BIN = 'true'
      assert.equal(dockerAlive(), true)
      if (original === undefined) delete process.env.BTS_DOCKER_BIN
      else process.env.BTS_DOCKER_BIN = original
    })

    test('데몬이 죽었으면 false', () => {
      process.env.BTS_DOCKER_BIN = 'false'
      assert.equal(dockerAlive(), false)
      if (original === undefined) delete process.env.BTS_DOCKER_BIN
      else process.env.BTS_DOCKER_BIN = original
    })

    test('바이너리 자체가 없어도 던지지 않고 false', () => {
      // 던지면 훅이 스택트레이스로 죽고, 그 화면은 「테스트 실패」와 구분되지 않는다.
      process.env.BTS_DOCKER_BIN = 'bts-no-such-binary-9f2a'
      assert.equal(dockerAlive(), false)
      if (original === undefined) delete process.env.BTS_DOCKER_BIN
      else process.env.BTS_DOCKER_BIN = original
    })
  })

  test('★changedFiles 는 「모른다」와 「없다」를 구분한다', () => {
    // 이 저장소에서 부르면 배열이 나온다(비-공허 짝 — git 배선이 살아 있다는 증거).
    // 둘을 뭉개면 판정 실패가 조용한 통과가 된다.
    const files = changedFiles()
    assert.ok(Array.isArray(files), `이 저장소에서 null 이 나왔다 — git 배선이 죽었다. ${files}`)

    const src = read(SCRIPT)
    assert.ok(
      src.includes('files === null'),
      `${SCRIPT} 가 null 분기를 갖지 않는다 — 「모른다」를 「없다」로 읽는다.`,
    )
    assert.ok(
      src.includes('0회'),
      '판정 실패를 조용히 넘긴다. 무엇을 못 했는지 크게 말해야 한다.',
    )
  })

  test('★새 브랜치(upstream 없음)를 위한 폴백 기준이 있다', () => {
    // 새 브랜치의 첫 푸시가 가장 검증이 필요한 순간인데, `@{u}` 는 그때 실패한다.
    const src = read(SCRIPT)
    assert.ok(
      src.includes('origin/main'),
      `${SCRIPT} 에 upstream 폴백이 없다 — 새 브랜치 첫 푸시가 통째로 검증을 건너뛴다.`,
    )
  })
})
