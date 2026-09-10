// RUN_FULL(전량 테스트) 과 RUN_DEEP(배포 경로) 이 갈려 있고, app 모듈이 빠지지 않는지 대조
//
// ## 무엇을 막는가
//
// 2026-09-10 에 `RUN_FULL` 하나가 두 판단을 겸하고 있었다.
//   ① 테스트를 **전량**으로 돌 것인가
//   ② 조립 부팅·인프라 봉인·배포 **stage 를 켤 것인가**
//
// 그래서 `main` 머지가 무조건 37분이었다. ①을 계산기 범위로 좁히려면 둘을 나눠야 한다 —
// 배포하려면 조립 부팅과 인프라 봉인이 필요하기 때문이다.
//
// ## ★그 분리가 만드는 구멍
//
// `app` 모듈은 `select-backend-modules.ts` 의 `NOT_IN_MATRIX` 라 **계산기가 아예 안 고른다**
// (「app 변경은 조립 부팅 잡이 맡는다」는 그 파일의 설계). 종전에는 전량의 `./gradlew test` 가
// `:modules:app:test` 를 포함했는데, main 에서 전량이 안 돌게 되면 **아무도 안 돈다.**
//
// 조용한 구멍이다 — 테스트가 사라져도 빨간불이 안 뜬다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINSFILE = join(ROOT, 'Jenkinsfile');

/** `stage('<이름>')` 의 `when { ... }` 한 줄 또는 블록. */
function whenOf(src: string, stageName: string): string {
  const at = src.indexOf(`stage('${stageName}')`);
  assert.ok(at >= 0, `stage('${stageName}') 를 못 찾았다.`);
  const stepsAt = src.indexOf('steps', at);
  return src.slice(at, stepsAt);
}

test('★조립 부팅이 :modules:app:test 를 돈다 (아무도 안 도는 구멍 차단)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const at = jf.indexOf("stage('조립 부팅')");
  const body = jf.slice(at, jf.indexOf("stage('", at + 10));
  assert.match(
    body,
    /gradlew\s+:modules:app:test/,
    '조립 부팅이 `:modules:app:test` 를 안 돈다.\n' +
      '★`app` 은 select-backend-modules.ts 의 NOT_IN_MATRIX 라 **계산기가 안 고른다.**\n' +
      '  전량이 main 에서 안 도는 지금 구조에서는 여기서 안 돌리면 **아무도 안 돈다** —\n' +
      '  테스트가 사라져도 빨간불이 안 뜨는 조용한 구멍이다.',
  );
});

test('★조립 부팅·인프라 봉인은 RUN_DEEP 으로 열린다 (전량과 별개)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  for (const stage of ['DB 마련', '조립 부팅', '인프라 봉인']) {
    assert.match(
      whenOf(jf, stage),
      /RUN_DEEP.*'true'/s,
      `stage('${stage}') 가 RUN_DEEP 이 아니라 RUN_FULL 을 본다.\n` +
        'main 머지에서 전량을 끄면 이 stage 들도 함께 꺼져 배포 경로가 막힌다.',
    );
  }
});

test('★전량 stage 는 RUN_FULL 로만 열린다', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  assert.match(
    whenOf(jf, '전량'),
    /RUN_FULL.*'true'/s,
    '전량 stage 가 RUN_FULL 을 안 본다 — 두 판단을 나눈 의미가 사라진다.',
  );
});

test('★main 이라는 이유만으로 전량이 되지 않는다', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const line = jf.split('\n').find((l) => /env\.RUN_FULL\s*=/.test(l));
  assert.ok(line, 'env.RUN_FULL 대입을 못 찾았다.');
  assert.doesNotMatch(
    line,
    /branch\s*==\s*'main'/,
    'RUN_FULL 판정에 `branch == main` 이 들어 있다 — main 머지가 무조건 37분이 된다.\n' +
      '전량의 목적은 「여러 PR 이 main 에서 만나는 조합」을 보는 것인데, 개발자 1명이라\n' +
      '그 전제가 성립하지 않는다. 남는 조합 위험은 야간 크론이 받는다.',
  );
});

test('★야간 전량 크론이 살아 있다 (조합 위험을 받는 자리)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  // ★`pollSCM('H/5 * * * *')` 도 「cron」 문자열을 품는다. 그것과 구별하려면
  //   **줄 시작이 `cron(`** 인 것을 봐야 한다 — 안 그러면 크론을 지워도 초록이다(공허).
  assert.match(
    jf,
    /^\s*cron\('[^']*'\)/m,
    '야간 크론이 없다.\n' +
      'main 을 전량에서 뺀 근거가 「조합 위험은 야간이 받는다」인데, 그 자리가 사라지면\n' +
      '모듈 그래프로 계산할 수 없는 축(마이그레이션 번호 대역 등)을 아무도 안 본다.',
  );
});
