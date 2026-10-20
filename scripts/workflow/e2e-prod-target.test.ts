// 실서버 대상 E2E(`@prod`) 스펙이 실서버에서 실제로 돌 수 있는지 대조
//
// ## 실서버에서 돌 수 없는 스펙을 실서버에 돌리고 있었다 — 2026-09-11 적발
//
// 배포 후 E2E 가 스펙 전량을 **실서버**(`https://bts.maxihan.com`)에 돌리게 배선돼 있었다.
// 그런데 그 스펙들은 MSW 전용이다.
//
//     MSW    `apps/web/src/main.tsx` 의 `if (import.meta.env.DEV)` 안에서만 기동
//     배포본  `vite build` 라 DEV=false — MSW 가 **없다**
//     스펙    168개 중 146개가 `alice` 로 로그인 (dev 시드 계정)
//     실서버  그 계정이 없다 — `ProdDevSeedAbsenceBootTest` 가 부재를 단언한다
//
// 즉 **구조적으로 전량 red** 이고, 「배포 검증 실패」로 보이지만 실제로는
// 「이 대상에서 돌 수 없는 스펙」이다.
//
// 처방은 표시다 — 실서버에서도 도는 스펙의 제목에 `@prod` 를 붙이고, 실서버 대상일 때
// `--grep @prod` 로 그 집합만 돈다. 두 목록이 아니라 **스펙 자신이 표시**를 갖는다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const E2E = join(ROOT, 'apps/web/e2e');

/**
 * 제목에 `@prod` 가 붙은 스펙 파일.
 *
 * ★파일 전체에서 `@prod` 문자열을 찾으면 안 된다. 「왜 이 태그가 필요했나」를 적은
 *   **주석**이 그 문자열을 갖고 있어서, 태그를 지워도 파일이 계속 걸린다(실측 — 초안의
 *   뮤테이션 ①이 통과했다). Playwright 가 `--grep` 으로 보는 것은 **제목**이므로
 *   제목만 본다.
 */
function prodSpecs(): string[] {
  return readdirSync(E2E).filter((f) => f.endsWith('.spec.ts')).filter((f) =>
    readFileSync(join(E2E, f), 'utf-8')
      .split('\n')
      .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
      .some((l) => /^\s*test(?:\.\w+)?\(\s*['"`][^'"`]*@prod/.test(l)),
  );
}

describe('① 실서버 대상 스펙', () => {
  test('★★@prod 스펙이 최소 1건 있다 (비-공허 짝)', () => {
    assert.ok(
      prodSpecs().length > 0,
      '`@prod` 태그가 붙은 스펙이 하나도 없다.\n' +
        '★그러면 배포 후 실서버 검증이 **0건**이다. 아래 대조도 통째로 공허해진다.\n' +
        '  실서버에서 돌 수 있는 스펙(MSW·dev 시드에 기대지 않는 것)에 태그를 붙여라.',
    );
  });

  test('★★@prod 스펙은 MSW·dev 시드에 기대지 않는다 (차집합)', () => {
    const offenders = prodSpecs().filter((f) => {
      // ★주석은 뺀다. 「왜 이 태그가 필요했나」를 적으면서 `alice` 를 언급하는 것은
      //   설명이지 의존이 아니다 — 안 빼면 설명을 잘 쓸수록 red 가 된다.
      const code = readFileSync(join(E2E, f), 'utf-8')
        .split('\n')
        .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
        .join('\n');
      return /loginAsAlice|['"]alice['"]|src\/mocks/.test(code);
    });
    assert.deepEqual(
      offenders,
      [],
      `@prod 인데 MSW·dev 시드를 쓰는 스펙: ${offenders.join(', ')}\n` +
        '★실서버에는 MSW 도 `alice` 도 없다. 그 스펙은 반드시 red 가 되고,\n' +
        '  그 red 가 「배포 실패」로 오독된다.',
    );
  });
});
