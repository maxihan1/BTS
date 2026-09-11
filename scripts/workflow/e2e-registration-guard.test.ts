// 파라미터 미등록 실행이 실서버 전량 E2E 를 시작하지 못하는지 대조
//
// ## 무엇을 막는가 — 2026-09-11 적발
//
// `bts-e2e` 잡의 `config.xml` 이 `<properties/>` 였다. 파라미터가 하나도 등록돼 있지 않다.
// 젠킨스는 **Jenkinsfile 을 파싱해야** `parameters` 를 등록하는데, 그 잡의 등록 빌드가
// `Jenkinsfile.e2e not found` 로 죽어 파싱이 한 번도 성공하지 않았기 때문이다.
//
// 그 상태에서 `bts-ci` 의 배포 stage 가 이렇게 부른다.
//
//     build(job: 'bts-e2e', parameters: [DEPLOYED_SHA, CHANGED_DOMAINS, BASE_URL])
//
// ★그리고 **아무 에러도 안 난다.** 젠킨스는 SECURITY-170 이후 정의되지 않은 파라미터를
//   조용히 버린다. 그래서 `bts-e2e` 는 이렇게 돈다.
//
//     DEPLOYED_SHA 없음     → 커밋 대조 생략 (무엇을 검증하는지 모른다)
//     CHANGED_DOMAINS 없음  → 1단 건너뛰고 **2단 전량**
//     BASE_URL 없음         → 기본값이 **실서버**
//
// 「무엇을 검증하는지 모르는 실서버 전량 3시간」이다. executor 가 1개라 그동안
// `bts-ci` 가 통째로 멈춘다 — 푸시 검증이 3시간 정지한다.
//
// 「실패가 아니라 침묵」의 교과서적 사례다. 빨간불이 아니라 **엉뚱한 초록 + 3시간 정지**로 나타난다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const E2E = join(ROOT, 'Jenkinsfile.e2e');
const CI = join(ROOT, 'Jenkinsfile');

/** `stage('<이름>')` 부터 그 안의 `steps` 직전까지 — `when` 블록이 있는 구간. */
function whenOf(src: string, stageName: string): string {
  const at = src.indexOf(`stage('${stageName}')`);
  assert.ok(at >= 0, `stage('${stageName}') 를 못 찾았다.`);
  const stepsAt = src.indexOf('steps', at);
  assert.ok(stepsAt > at, `stage('${stageName}') 의 steps 를 못 찾았다.`);
  return src.slice(at, stepsAt);
}

describe('bts-e2e — 파라미터 미등록 실행 차단', () => {
  test('★★등록 여부를 기존 파라미터의 null 로 판별한다', () => {
    const src = readFileSync(E2E, 'utf-8');
    assert.match(
      src,
      /params\.BASE_URL\s*==\s*null/,
      '파라미터 등록 여부를 판별하지 않는다.\n' +
        '★새 파라미터를 만들어 판별하면 안 된다 — 그것도 똑같이 미등록이라 null 이다.\n' +
        '  **기존 파라미터가 null 인지**가 곧 「아직 등록 전」이라는 신호다.',
    );
    assert.match(
      src,
      /env\.REGISTRATION_ONLY\s*=\s*'yes'/,
      '등록 실행을 표시하는 플래그가 없다 — 뒤 stage 들이 그것을 보고 꺼져야 한다.',
    );
  });

  test('★★E2E 전량이 등록 실행에서 꺼진다 (실서버 3시간 차단)', () => {
    const src = readFileSync(E2E, 'utf-8');
    assert.match(
      whenOf(src, 'E2E — 전량'),
      /REGISTRATION_ONLY/,
      '전량 stage 가 등록 실행에서도 돈다.\n' +
        '★그 실행은 대상 커밋을 모른 채 **실서버**를 3시간 훑는다(BASE_URL 기본값이 실서버다).\n' +
        '  executor 가 1개라 그동안 bts-ci 가 통째로 멈춘다.',
    );
  });

  test('★무거운 stage 전부가 등록 실행에서 꺼진다', () => {
    const src = readFileSync(E2E, 'utf-8');
    for (const stage of ['배포 반영 대기', '의존성', 'E2E — 변경 도메인', 'E2E — 전량', '판정 유효성']) {
      assert.match(
        whenOf(src, stage),
        /REGISTRATION_ONLY/,
        `stage('${stage}') 가 등록 실행에서도 돈다.\n` +
          '  한 곳이라도 열려 있으면 「파싱만 하고 끝낸다」가 성립하지 않는다.',
      );
    }
  });

  test('★bts-ci 는 여전히 파라미터를 넘긴다 (차단이 호출부를 지우지 않았다)', () => {
    // ★비-공허 짝. 위 단언들은 「전량이 안 돈다」를 요구하는데, 호출부가 통째로 사라져도
    //   그 요구는 만족된다 — 그러면 CD 이후 E2E 층이 없어진 것이고 훨씬 나쁘다.
    const src = readFileSync(CI, 'utf-8');
    assert.match(src, /job:\s*'bts-e2e'/, 'bts-ci 가 bts-e2e 를 안 부른다 — 3층이 사라졌다.');
    for (const p of ['DEPLOYED_SHA', 'CHANGED_DOMAINS', 'BASE_URL']) {
      assert.match(
        src,
        new RegExp(`name:\\s*'${p}'`),
        `bts-ci 가 ${p} 를 안 넘긴다 — 그러면 bts-e2e 가 무엇을 검증하는지 모른다.`,
      );
    }
  });

  test('★Jenkinsfile.e2e 의 parameters 와 bts-ci 가 넘기는 이름이 서로 덮는다 (차집합)', () => {
    // ★두 목록이다. 한쪽에만 있는 이름은 조용히 폐기되거나 조용히 기본값이 된다.
    const e2e = readFileSync(E2E, 'utf-8');
    const ci = readFileSync(CI, 'utf-8');

    const declared = new Set(
      [...e2e.matchAll(/^\s*(?:string|booleanParam)\(\s*\n\s*name:\s*'([^']+)'/gm)].map((m) => m[1]),
    );
    assert.ok(declared.size > 0, 'Jenkinsfile.e2e 에서 parameters 를 못 읽었다 — 아래 대조가 공허해진다.');

    const ciBlock = ci.slice(ci.indexOf("job: 'bts-e2e'"));
    const passed = new Set(
      [...ciBlock.slice(0, 900).matchAll(/name:\s*'([^']+)'/g)].map((m) => m[1]),
    );
    assert.ok(passed.size > 0, 'bts-ci 의 호출부에서 파라미터 이름을 못 읽었다 — 대조가 공허해진다.');

    const unknown = [...passed].filter((p) => !declared.has(p));
    assert.deepEqual(
      unknown,
      [],
      `bts-ci 가 Jenkinsfile.e2e 에 없는 파라미터를 넘긴다: ${unknown.join(', ')}\n` +
        '★젠킨스는 정의되지 않은 파라미터를 **조용히 버린다**(SECURITY-170). 에러가 안 난다.\n' +
        '  받는 쪽은 기본값으로 돌고, 그 기본값은 실서버 전량이다.',
    );
  });
});
