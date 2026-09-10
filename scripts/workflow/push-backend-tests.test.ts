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
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

import { SKIP_ENV, changedFiles, dockerAlive } from './push-backend-tests.ts'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT = 'scripts/workflow/push-backend-tests.ts'
const HOOK = '.husky/pre-push'

function read(rel: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, rel), 'utf-8')
}

describe('푸시 훅 백엔드 모듈 테스트', () => {
  // ★2026-09-09 재조준. 종전 단언은 「훅이 이 스크립트를 부른다」였고, 근거는
  //   「CI 자동 실행을 껐으므로 다른 자리가 없다」였다. **그 전제가 거짓이 됐다** —
  //   젠킨스가 `pollSCM` 으로 푸시를 감지해 같은 검증을 돈다(실측 80초).
  //
  //   그래서 지키려던 것을 그대로 지키되 자리를 옮긴다. 지키려던 것은 스크립트의 호출 자리가
  //   아니라 **「백엔드 코드가 푸시마다 자동으로 검증된다」**이다.
  //   단언을 지우면 그 보장이 사라지므로, 삭제가 아니라 재조준이다.
  test('★백엔드 검증이 푸시마다 자동으로 돈다 (젠킨스가 그 자리다)', () => {
    const jf = read('Jenkinsfile')

    assert.match(
      jf,
      /pollSCM\(/,
      'Jenkinsfile 에 pollSCM 이 없다 — 푸시를 감지하는 자리가 사라졌다.\n' +
        '훅에서 백엔드 테스트를 걷어낸 상태에서 이것까지 없으면 ' +
        '**로컬도 젠킨스도 안 도는 검증 0회 구간**이 된다.',
    )
    assert.match(
      jf,
      /select-test-scope\.ts/,
      'Jenkinsfile 이 범위 계산기를 부르지 않는다 — 무엇을 돌릴지 정하는 정본이 끊겼다.',
    )
  })

  test('★수동 폴백이 남아 있다 (젠킨스가 죽었을 때의 자리)', () => {
    // 젠킨스는 단일 장애점이다. 그 스크립트를 지우면 손으로 돌릴 방법이 0 이 된다.
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, SCRIPT)),
      `${SCRIPT} 가 사라졌다 — 젠킨스가 죽으면 백엔드를 검증할 방법이 없다.`,
    )
    // 훅 주석이 그 폴백 명령을 적어 둔다. 사람이 찾을 자리가 저장소 안에 있어야 한다.
    assert.ok(
      read(HOOK).includes(SCRIPT),
      `${HOOK} 가 ${SCRIPT} 를 언급하지 않는다 — 이전 사실과 폴백 명령의 기록이 사라졌다.`,
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

  /**
   * ★보상 통제 — 훅이 좁게 도는 대가를 되찾는 자리가 실재하는가.
   *
   * 이 스크립트는 `widenOnMigration:false` 로 부르므로, 마이그레이션이 **다른 BC 의 테이블**을
   * 건드리는 영향을 원리적으로 못 본다. 그 거래를 소스 주석과 커밋 메시지가
   * 「되찾는 자리는 배포 전 전량 실행이다」라고 약속한다.
   *
   * **약속한 자리가 없으면 그 주석은 거짓말이다.** 이 저장소가 반복해 물린 양식이 정확히
   * 그것이다 — 문서가 존재하지 않는 장치를 가리키고, 아무도 대조하지 않아 조용히 썩는다.
   * 그래서 두 목록(주석의 약속 ↔ 배포 스크립트의 실물)을 여기서 짝지어 둔다.
   */
  describe('배포 전 전량 게이트 (훅이 좁게 도는 대가의 보상)', () => {
    const DEPLOY = 'infra/deploy/bts-deploy.sh'

    /**
     * 배포 스크립트와 이 판별식이 공유하는 **유일한 문자열**. 셸 함수 이름이다.
     * 두 자리가 각자 경로를 적으면 그 둘은 서로를 검사하지 않는 두 목록이 된다.
     */
    const GUARD = 'require_web_module'

    /** 헬퍼 정의 본문만 잘라 낸다. 못 자르면 실패한다 — 조용히 빈 문자열을 판정하면 공허해진다 */
    const helperBody = (sh: string): string => {
      const at = sh.indexOf(`${GUARD}() {`)
      assert.notEqual(
        at,
        -1,
        `${DEPLOY} 에 ${GUARD} 정의가 없다. 의존성 판정이 어디로 갔는지 확인할 것.`,
      )
      const end = sh.indexOf('\n}\n', at)
      assert.notEqual(end, -1, `${GUARD} 의 닫는 괄호를 못 찾았다 — 이 파서가 낡았다.`)
      return sh.slice(at, end)
    }

    test('★배포 스크립트가 백엔드 전량 테스트를 돌린다', () => {
      const sh = read(DEPLOY)
      assert.ok(
        /\.\/gradlew test\b/.test(sh),
        `${DEPLOY} 에 백엔드 전량 테스트(\`./gradlew test\`)가 없다.\n` +
          '푸시 훅은 바뀐 모듈만 돈다. 이 게이트가 없으면 마이그레이션의 cross-BC 영향은 ' +
          '**어디서도 검증되지 않은 채** 프로덕션에 올라간다.',
      )
    })

    test('★게이트가 빌드·업로드보다 앞에 있다', () => {
      const sh = read(DEPLOY)
      const gate = sh.search(/\.\/gradlew test\b/)
      const build = sh.indexOf('bootJar')
      assert.ok(gate >= 0 && build >= 0, '게이트 또는 빌드 단계를 못 찾았다 — 파서가 낡았다.')
      assert.ok(
        gate < build,
        `${DEPLOY} 의 전량 테스트가 빌드(${build}) 뒤(${gate})에 있다.\n` +
          '뒤에 두면 깨진 산출물을 이미 만든 뒤에 멈춘다 — 게이트의 의미가 절반이다.',
      )
    })

    test('★★게이트가 pnpm 을 거치지 않는다', () => {
      // 워크트리가 붙어 있으면 pnpm 이 모듈 재설치를 시도하다 무-TTY 로 죽는다.
      // 배포가 그 지점에서 죽은 실측이 있다(2026-08-21, 커밋 78734fa04).
      const sh = read(DEPLOY)
      const gateBlock = sh.slice(sh.indexOf('BTS_SKIP_DEPLOY_TEST'), sh.indexOf('bootJar'))
      assert.equal(
        /(^|[;&|(\s])(npx\s+)?pnpm(\s|$)/m.test(gateBlock),
        false,
        `${DEPLOY} 의 게이트가 pnpm 을 거친다 — 워크트리가 붙어 있으면 배포가 여기서 죽는다.`,
      )
    })

    test('★게이트도 「Docker 부재」를 구분해 말한다', () => {
      const sh = read(DEPLOY)
      assert.ok(
        sh.includes('코드 문제가 아니다'),
        `${DEPLOY} 의 게이트가 Docker 부재를 테스트 실패와 구분하지 않는다 — ` +
          '배포 직전에 그 오진이 나면 가장 비싸다.',
      )
    })

    /**
     * ★★셰임 존재는 의존성의 증거가 아니다.
     *
     * pnpm 의 `node_modules/.bin/<도구>` 는 모듈을 부르는 **독립 셸 스크립트**다. 모듈이
     * 사라져도 그 파일은 남으므로 `-x` 가 통과하고, 바로 다음 줄이 `MODULE_NOT_FOUND` 로 죽는다.
     * 그러면 「의존성 복구가 선행돼야 한다」는 안내 대신 스택이 쏟아지고 `set -euo pipefail` 이
     * 스크립트를 끝낸다 — 원인이 배포가 아니라 **테스트 실패로 오독된다.**
     *
     * 실측 2026-08-23 — `apps/web/node_modules` 의 선언 의존성 50개가 전부 부재였는데
     * `.bin/vitest` 만 남아 `-x` 를 통과했다. #395 배포가 백엔드 게이트(10m55s · 10,401 초록)를
     * 지난 직후 여기서 26초 만에 끝났다. **그 상태가 이틀 동안 안 보였다** — 앞의 백엔드
     * 게이트가 매번 먼저 죽어 여기까지 온 적이 없었기 때문이다.
     */
    test('★★헬퍼 밖에서는 `.bin` 을 **부를 때만** 쓴다', () => {
      // ★형태를 열거하지 않는다. 종전 판정은 `[ -x … ]` 모양만 봐서
      //   `test -x …` · `command -v …` · `[ ! -x … ]` 로 전부 빠져나갔다(2026-08-23 리뷰 실측).
      //   특히 `[ ! -x … ]` 는 `[ -x … ] || { exit 1; }` 의 **가장 자연스러운 재작성**이다.
      //   그래서 형태가 아니라 **자리**를 잰다 — 헬퍼 밖에서 `.bin` 이 나오는 줄은 실행 두 줄뿐이다.
      //   헬퍼 안은 예외다. 거기서는 모듈 실체를 먼저 본 **뒤** 2차로 셰임을 확인한다.
      const sh = read(DEPLOY)
      const outside = sh.replace(helperBody(sh), '')
      const probes = outside
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => !l.startsWith('#') && l.includes('node_modules/.bin/'))
      // ★줄 **모양**을 재고 개수를 단언한다. 문자열을 통째로 고정하면 `--reporter=dot` 을
      //   붙이는 것 같은 **정당한 편집**까지 red 가 되고, 그때 읽히는 사유가
      //   「의존성 판정에 셰임을 썼다」라 원인을 가린다. 지켜야 할 불변식은
      //   「`.bin` 이 나오는 줄은 **부르는 줄**이다」 하나다.
      const INVOKE = /^\(cd apps\/web && node_modules\/\.bin\/[a-z]+ /
      const notInvocations = probes.filter((l) => !INVOKE.test(l))
      assert.deepEqual(
        notInvocations,
        [],
        `${DEPLOY} 의 헬퍼 밖에서 \`.bin\` 이 실행 이외의 자리에 쓰였다 — ${notInvocations.join(' · ')}\n` +
          '셰임은 모듈이 사라져도 남는다. 그것으로 의존성을 판정하면 파괴된 node_modules 를 ' +
          '그대로 통과시키고 바로 다음 줄에서 MODULE_NOT_FOUND 로 죽는다.',
      )
      assert.equal(
        probes.length,
        2,
        `${DEPLOY} 의 \`.bin\` 실행 줄이 2개가 아니다 (${probes.length}) — ` +
          '전량 검증 게이트와 빌드 폴백 둘이어야 한다. 하나가 사라졌으면 그 자리의 확인도 함께 사라졌다.',
      )
    })

    /**
     * ★★여기서 **실행한다.** 위 판정들은 전부 소스 문자열 대조라 「형태만」 지킨다.
     *
     * 2026-08-23 리뷰 실측 — 헬퍼의 `package.json` 줄을 그대로 두고 `|| test -x …` 폴백만
     * 얹어 게이트를 의미상 셰임 수용으로 되돌렸더니 **판별식 405종이 전부 초록**이었다.
     * 부채 95 가 기술한 고장이 그대로 돌아왔는데 아무도 안 물었다. 보장이 동작이 아니라
     * 형태로 서 있었던 것이다.
     *
     * 그래서 헬퍼를 실제로 뽑아 임시 트리에서 돌린다. 형태가 무엇이든 **행동**을 잰다.
     */
    describe('★★모듈 확인 헬퍼를 실제로 실행한다', () => {
      /** 헬퍼만 뽑아 임시 루트에서 돌린다. @returns 종료 코드와 출력 */
      const runGuard = (tool: string, build: (root: string) => void) => {
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-web-module-'))
        build(root)
        const body = helperBody(read(DEPLOY))
        assert.ok(
          body.includes('package.json'),
          '헬퍼를 못 뽑았다 — 아래 실행이 통째로 공허해진다 (비-공허 짝).',
        )
        const script = ['set -euo pipefail', body, '}', `${GUARD} ${tool}`].join('\n')
        const r = spawnSync('bash', ['-c', script], { cwd: root, encoding: 'utf-8' })
        fs.rmSync(root, { recursive: true, force: true })
        return { status: r.status, out: `${r.stdout}${r.stderr}` }
      }

      const shim = (root: string, tool: string) => {
        const dir = path.join(root, 'apps/web/node_modules/.bin')
        fs.mkdirSync(dir, { recursive: true })
        fs.writeFileSync(path.join(dir, tool), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
      }
      const mod = (root: string, tool: string) => {
        const dir = path.join(root, 'apps/web/node_modules', tool)
        fs.mkdirSync(dir, { recursive: true })
        fs.writeFileSync(path.join(dir, 'package.json'), '{}')
      }

      test('셰임만 남고 모듈이 파괴된 트리를 막는다 (부채 95 의 고장)', () => {
        const r = runGuard('vitest', (root) => {
          shim(root, 'vitest')
          // 실측 파괴 양식 — 남은 항목이 전부 빈 스코프 디렉터리였다
          fs.mkdirSync(path.join(root, 'apps/web/node_modules/@radix-ui'), { recursive: true })
        })
        assert.notEqual(r.status, 0, `셰임만 있는 트리를 통과시켰다.\n${r.out}`)
        assert.match(r.out, /의존성 복구/, '막긴 했는데 이유를 안 말한다 — 오진의 원인이 그것이었다.')
      })

      test('모듈은 있는데 셰임이 없는 트리도 막는다 (`.bin` 부재부터 의심)', () => {
        // CLAUDE.md §함정 —「worktree node_modules 는 심볼릭. `.bin` 부재부터 의심」.
        // 이 저장소가 반복 고장으로 이름 붙인 자리다. 종전 `-x` 판정이 유일하게 잡던 것을
        // 부채 95 를 닫으면서 떼어 냈다 — 반대쪽에 낸 사각이라 여기서 되돌린다.
        const r = runGuard('vitest', (root) => mod(root, 'vitest'))
        assert.notEqual(r.status, 0, `셰임이 없는 트리를 통과시켰다.\n${r.out}`)
        assert.match(r.out, /\.bin/, '무엇이 없는지를 안 말한다.')
      })

      test('음성 대조군 — 모듈과 셰임이 다 있으면 통과한다', () => {
        const r = runGuard('vitest', (root) => {
          mod(root, 'vitest')
          shim(root, 'vitest')
        })
        assert.equal(r.status, 0, `정상 트리를 막았다 — 배포가 통째로 못 나간다.\n${r.out}`)
      })

      test('음성 대조군 — 끊어진 심볼릭은 모듈 부재로 센다', () => {
        const r = runGuard('vitest', (root) => {
          shim(root, 'vitest')
          fs.symlinkSync(
            path.join(root, 'apps/web/node_modules/.store/vitest'),
            path.join(root, 'apps/web/node_modules/vitest'),
          )
        })
        assert.notEqual(r.status, 0, `끊어진 심볼릭을 통과시켰다.\n${r.out}`)
      })
    })

    test('★모듈 확인 헬퍼가 셰임이 아니라 `package.json` 실체를 본다', () => {
      const body = helperBody(read(DEPLOY))
      assert.ok(
        body.includes('node_modules/$1/package.json'),
        `${GUARD} 가 모듈 실체를 안 본다.\n` +
          '빈 스코프 디렉터리만 남는 파괴 양식이 실측이었다(2026-08-23 — 41개 항목이 전부 빈 ' +
          '디렉터리). 디렉터리 존재로는 못 가른다 — `package.json` 을 봐야 한다.',
      )
    })

    test('★부재 메시지가 복구 명령과 그 전제를 준다', () => {
      const body = helperBody(read(DEPLOY))
      assert.ok(
        body.includes('pnpm install --frozen-lockfile'),
        `${GUARD} 가 복구 명령을 안 준다 — 「복구가 선행돼야 한다」만 말하고 방법을 안 말한다.`,
      )
      assert.ok(
        body.includes('.worktrees'),
        `${GUARD} 가 복구 명령의 **전제**를 안 준다.\n` +
          `${DEPLOY} 는 같은 파일 안에서 \`CI=true\` purge 를 ★★로 금지한다. 그 금지의 근거는 ` +
          '「워크트리의 심볼릭이 지워지는 실체를 가리킨다」이고, 워크트리가 0개면 성립하지 않는다. ' +
          '조건을 안 적으면 메시지와 금지 주석이 서로를 반박한다.',
      )
    })

    /**
     * ★★위 판정과 이 판정을 가르는 이유. 뮤테이션 실측 —
     * 「복구 명령 줄」 하나에 명령과 전제(`.worktrees/*` 가 0개인지)가 같이 있어서,
     * **대가를 적은 줄만 지우면 위 판정이 그대로 초록**이었다. 판정이 그 줄에만 있는
     * 문자열을 안 보면 그 줄은 아무도 안 지키는 주석이 된다.
     */
    test('★부재 메시지가 전제를 어겼을 때의 대가를 말한다', () => {
      const body = helperBody(read(DEPLOY))
      assert.ok(
        body.includes('워크트리가 붙어 있으면'),
        `${GUARD} 가 「워크트리가 붙어 있으면 무슨 일이 나는가」를 안 말한다.\n` +
          '전제만 적고 대가를 안 적으면 급한 사람은 전제를 건너뛴다 — 그 거래의 값이 ' +
          '「옆 세션의 모듈 실체가 함께 지워진다」임을 그 자리에서 읽혀야 한다.',
      )
    })

    test('★헬퍼 정의가 게이트 블록 **밖**에 있다', () => {
      // 정의를 게이트 안으로 옮기면 메시지의 `pnpm install` 문자열이
      // 위 「★★게이트가 pnpm 을 거치지 않는다」를 엉뚱한 이유로 red 로 만든다.
      // 그때 읽히는 실패 사유가 「워크트리가 붙어 있으면 배포가 죽는다」라 원인을 가린다.
      const sh = read(DEPLOY)
      const def = sh.indexOf(`${GUARD}() {`)
      const gate = sh.indexOf('BTS_SKIP_DEPLOY_TEST')
      assert.ok(def >= 0 && gate >= 0, '헬퍼 정의 또는 게이트를 못 찾았다 — 파서가 낡았다.')
      assert.ok(
        def < gate,
        `${GUARD} 정의(${def})가 게이트(${gate}) 안으로 들어갔다. 게이트 밖으로 뺄 것.`,
      )
    })

    /**
     * ★두 자리를 **두 판정으로** 가른다. 한 판정에 두 단언을 넣었더니 뮤테이션 M1(게이트만
     * 되돌림)과 M2(폴백만 되돌림)가 같은 이름으로 red 를 냈다 — 실패 이름만 보고는
     * 어느 자리가 깨졌는지 못 가른다.
     */
    test('★전량 검증 게이트가 vitest 모듈을 확인하고 부른다', () => {
      const sh = read(DEPLOY)
      const gateBlock = sh.slice(sh.indexOf('BTS_SKIP_DEPLOY_TEST'), sh.indexOf('bootJar'))
      assert.match(
        gateBlock,
        new RegExp(`${GUARD}\\s+vitest\\b`),
        '전량 검증 게이트가 vitest 모듈을 확인하지 않는다 — 파괴된 node_modules 로 vitest 를 부른다.',
      )
    })

    test('★빌드 폴백이 vite 모듈을 확인하고 부른다', () => {
      const sh = read(DEPLOY)
      const afterBuild = sh.slice(sh.indexOf('bootJar'))
      assert.match(
        afterBuild,
        new RegExp(`${GUARD}\\s+vite\\b`),
        'pnpm 빌드 폴백이 vite 모듈을 확인하지 않는다 — 폴백은 `.bin/vite` 를 직접 부르는 자리다.',
      )
    })
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
