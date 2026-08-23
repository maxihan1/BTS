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
    test('★★의존성 판정이 `.bin` 셰임 존재로 서지 않는다', () => {
      const sh = read(DEPLOY)
      const shimTests = sh.match(/\[\s*-[a-z]+\s+[^\]]*node_modules\/\.bin\/[^\]]*\]/g) ?? []
      assert.deepEqual(
        shimTests,
        [],
        `${DEPLOY} 가 \`.bin\` 셰임 존재를 의존성 판정으로 쓴다 — ${shimTests.join(' · ')}\n` +
          '셰임은 모듈이 사라져도 남는다. 이 판정은 파괴된 node_modules 를 그대로 통과시키고, ' +
          '바로 다음 줄에서 MODULE_NOT_FOUND 로 죽는다.',
      )
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

    test('★부재 메시지가 복구 명령과 그 전제를 함께 준다', () => {
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

    test('★게이트가 vitest 를, 빌드 폴백이 vite 를 각각 확인하고 부른다', () => {
      const sh = read(DEPLOY)
      const gateBlock = sh.slice(sh.indexOf('BTS_SKIP_DEPLOY_TEST'), sh.indexOf('bootJar'))
      const afterBuild = sh.slice(sh.indexOf('bootJar'))
      assert.match(
        gateBlock,
        new RegExp(`${GUARD}\\s+vitest\\b`),
        `전량 검증 게이트가 vitest 모듈을 확인하지 않는다.`,
      )
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
