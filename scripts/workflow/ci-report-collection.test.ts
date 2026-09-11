// CI 가 테스트 결과를 젠킨스에 남기는지 · 배포 우회가 코드로 막히는지 대조
//
// ## ① bts-ci 가 E2E 결과를 안 남기고 지웠다 — 2026-09-11 적발
//
// `Jenkinsfile` 의 `post { always }` 가 백엔드 XML 하나만 수집하고 `cleanWs` 로
// 워크스페이스를 지웠다. 빠른 게이트·전량이 돌린 Playwright 의 결과가 **아무 데도**
// 안 남았다.
//
// 정책 §1 의 마지막 줄(「E2E 결과를 젠킨스에서 볼 수 있어야 한다」)이 `bts-e2e` 에서만
// 지켜지고 `bts-ci` 에서는 안 지켜지고 있었다.
//
// ★`playwright.config.ts` 는 증거를 남기도록 이미 설정돼 있다 —
//   `trace: 'retain-on-failure'` · `screenshot` · `video` · junit · html 리포터.
//   **수집하는 쪽이 없어서** 그 설정 전체가 공허했다. 두 목록이 서로를 안 봤다.
//
// ## ② 배포 우회가 주석으로만 막혀 있었다
//
// `BTS_SKIP_DEPLOY_TEST=1` 이면 `bts-deploy.sh` 가 전량 테스트를 건너뛰고 배포한다.
// 그 배포는 **전수 검증 0회**인데 파이프라인은 초록이다. 젠킨스 쪽 방어는
// 「넘기지 않는다」는 **주석 한 줄**뿐이었다 — 전역 환경변수로 서면 그대로 통과한다.
//
// 같은 저장소가 「주석으로는 못 막는다」를 잡 등록 함정에서 이미 실증했다
// (`bootstrap.sh` — 함정을 XML 주석에 적어 두고 같은 날 또 밟았다).
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const CI = join(ROOT, 'Jenkinsfile');
const PW_CONFIG = join(ROOT, 'apps/web/playwright.config.ts');

describe('① 테스트 결과 수집', () => {
  test('★★playwright 가 쓰는 경로를 bts-ci 가 실제로 수집한다 (차집합)', () => {
    const cfg = readFileSync(PW_CONFIG, 'utf-8');
    const jf = readFileSync(CI, 'utf-8');

    // 설정이 내보내는 산출물 경로를 **설정에서 읽는다** — 여기 다시 적으면 세 번째 목록이 된다.
    const junitOut = /outputFile:\s*'([^']+)'/.exec(cfg)?.[1];
    const htmlOut = /outputFolder:\s*'([^']+)'/.exec(cfg)?.[1];
    assert.ok(junitOut, 'playwright.config.ts 에서 junit 경로를 못 읽었다 — 대조가 공허해진다.');
    assert.ok(htmlOut, 'playwright.config.ts 에서 html 경로를 못 읽었다 — 대조가 공허해진다.');

    for (const [what, p] of [
      ['junit', junitOut],
      ['html 리포트', htmlOut],
    ] as const) {
      assert.ok(
        jf.includes(p),
        `Jenkinsfile 이 playwright 의 ${what} 산출물(${p})을 수집하지 않는다.\n` +
          '★설정은 증거를 남기는데 수집하는 쪽이 없으면 그 설정이 통째로 공허하다.\n' +
          '  그리고 `cleanWs` 가 그것을 지운다 — 실패 원인을 볼 방법이 없어진다.',
      );
    }
  });

  test('★★수집이 cleanWs 보다 앞이다', () => {
    // ★주석이 아니라 **코드 줄 번호**로 본다. 「cleanWs 보다 앞이어야 한다」고 적은
    //   주석이 코드보다 위에 있어서, 문자열 위치로 재면 주석이 먼저 잡힌다(초안에서 실측).
    const lines = readFileSync(CI, 'utf-8').split('\n');
    const codeLine = (needle: string): number =>
      lines.findIndex((l) => !/^\s*(\/\/|\*|\/\*|#)/.test(l) && l.includes(needle));
    const lastArchive = lines.reduce(
      (acc, l, i) => (!/^\s*(\/\/|\*|\/\*|#)/.test(l) && l.includes('archiveArtifacts') ? i : acc),
      -1,
    );
    const clean = codeLine('cleanWs');
    assert.ok(lastArchive >= 0, 'archiveArtifacts 가 없다 — 증거를 안 남긴다.');
    assert.ok(clean >= 0, 'cleanWs 가 없다 — 잔재성 거짓 초록이 생긴다.');
    assert.ok(
      lastArchive < clean,
      `수집(줄 ${lastArchive + 1})이 cleanWs(줄 ${clean + 1}) 뒤에 있다 —\n` +
        '  지워진 것을 수집한다. 항상 빈 아티팩트가 된다.',
    );
  });
});

describe('② 배포 우회 차단', () => {
  test('★★BTS_SKIP_DEPLOY_TEST 를 코드로 막는다 (주석이 아니라)', () => {
    const jf = readFileSync(CI, 'utf-8');
    const at = jf.indexOf("stage('배포')");
    assert.ok(at >= 0, "stage('배포') 를 못 찾았다.");
    const body = jf.slice(at);
    const code = body
      .split('\n')
      .filter((l) => !/^\s*(#|\/\/|\*|\/\*)/.test(l))
      .join('\n');
    /*
     * ★**조건식**을 요구한다. 변수 이름이 나오는 것만으로는 부족하다 —
     *   `if false; then echo "BTS_SKIP_DEPLOY_TEST ..."; exit 1; fi` 로 바꿔도
     *   「이름이 있고 exit 1 이 있다」는 만족된다(초안 뮤테이션 ③이 그렇게 통과했다).
     *   실제로 그 변수를 **읽어서 판단**하는 줄이 있어야 한다.
     */
    const cond = /(\[\s*-n\s*"?\$\{BTS_SKIP_DEPLOY_TEST|\[\s*"?\$\{BTS_SKIP_DEPLOY_TEST[^\]]*\]|BTS_SKIP_DEPLOY_TEST:-\}"?\s*\])/;
    assert.match(
      code,
      cond,
      '배포 stage 의 **코드**가 BTS_SKIP_DEPLOY_TEST 를 읽어서 판단하지 않는다.\n' +
        '★주석으로 적어 둔 금지는 환경변수를 막지 못한다. 그 값이 서면\n' +
        '  `bts-deploy.sh` 가 전량 테스트를 건너뛰고 배포하는데 파이프라인은 초록이다.',
    );
    assert.match(
      code,
      /BTS_SKIP_DEPLOY_TEST[\s\S]{0,300}exit 1/,
      '차단이 감지만 하고 안 죽는다 — 경고는 읽히지 않는다.',
    );
  });

  test('★배포 스크립트에 그 우회가 실재한다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 단언은 「막을 것이 애초에 없는데 막는 코드만 있는」 상태에서도
    //   통과한다. 우회가 실재하는지 배포 스크립트에서 확인한다.
    const sh = readFileSync(join(ROOT, 'infra/deploy/bts-deploy.sh'), 'utf-8');
    assert.match(
      sh,
      /if \[ "\$\{BTS_SKIP_DEPLOY_TEST:-\}" = "1" \]/,
      'bts-deploy.sh 에 우회 분기가 없다 — 위 차단이 무엇을 막는지 불명이다.',
    );
  });
});
