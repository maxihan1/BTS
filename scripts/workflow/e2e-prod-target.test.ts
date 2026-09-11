// 실서버 대상 E2E 와 도메인 매핑이 실제로 성립하는지 대조
//
// ## ① 실서버에서 돌 수 없는 스펙을 실서버에 돌리고 있었다 — 2026-09-11 적발
//
// 배포 후 E2E 가 스펙 전량을 **실서버**(`https://bts.maxihan.com`)에 돌리게 배선돼 있었다.
// 그런데 그 스펙들은 MSW 전용이다.
//
//     MSW    `apps/web/src/main.tsx` 의 `if (import.meta.env.DEV)` 안에서만 기동
//     배포본  `vite build` 라 DEV=false — MSW 가 **없다**
//     스펙    168개 중 146개가 `alice` 로 로그인 (dev 시드 계정)
//     실서버  그 계정이 없다 — `ProdDevSeedAbsenceBootTest` 가 부재를 단언한다
//
// 즉 **구조적으로 전량 red** 이고, 그 3시간이 executor 1개를 점유해 `bts-ci` 가 멈춘다.
// 젠킨스 화면에는 「배포 검증 실패」로 보이지만 실제로는 「이 대상에서 돌 수 없는 스펙」이다.
//
// 처방은 표시다 — 실서버에서도 도는 스펙의 제목에 `@prod` 를 붙이고, 실서버 대상일 때
// `--grep @prod` 로 그 집합만 돈다. 두 목록이 아니라 **스펙 자신이 표시**를 갖는다.
//
// ## ② 도메인 글로브가 하이픈 없는 이름을 놓쳤다
//
// `${d}-*.spec.ts` 하나로 골라서 `backlog.spec.ts` 같은 단독 이름이 안 걸렸다.
// 실측 — 컴포넌트 폴더 38개 중 매칭 0건이 **25개**였고, 서식을 둘 다 보면 **13개**로 준다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { allSpecNames, specsForDomain, specsForDomains, unmappedDomains } from './e2e-select.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const E2E = join(ROOT, 'apps/web/e2e');
const PIPELINE = join(ROOT, 'Jenkinsfile.e2e');

/**
 * 제목에 `@prod` 가 붙은 스펙 파일.
 *
 * ★파일 전체에서 `@prod` 문자열을 찾으면 안 된다. 「왜 이 태그가 필요했나」를 적은
 *   **주석**이 그 문자열을 갖고 있어서, 태그를 지워도 파일이 계속 걸린다(실측 — 초안의
 *   뮤테이션 ①이 통과했다). Playwright 가 `--grep` 으로 보는 것은 **제목**이므로
 *   제목만 본다.
 */
function prodSpecs(): string[] {
  return allSpecNames(E2E).filter((f) =>
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

  test('★★실서버 대상일 때 파이프라인이 @prod 로 좁힌다', () => {
    const src = readFileSync(PIPELINE, 'utf-8');
    assert.match(
      src,
      /--grep @prod/,
      'Jenkinsfile.e2e 가 실서버 대상에서 스펙을 안 좁힌다.\n' +
        '★MSW 전제 스펙 146개를 실서버에 돌리면 구조적으로 전부 red 이고,\n' +
        '  그 3시간이 executor 1개를 점유해 bts-ci 가 멈춘다.',
    );
    // 두 stage(변경 도메인 · 전량) 모두에 걸려야 한다 — 한쪽만이면 나머지가 그대로 돈다.
    const runs = [...src.matchAll(/npx playwright test[^\n]*/g)].map((m) => m[0]);
    assert.ok(runs.length >= 2, `playwright 실행을 ${runs.length}건밖에 못 찾았다 — 대조가 공허하다.`);
    const bare = runs.filter((r) => !r.includes('GREP_ARG'));
    assert.deepEqual(bare, [], `필터가 안 걸린 playwright 실행: ${bare.join(' | ')}`);
  });

  test('★로컬 대상에서는 좁히지 않는다 (비-공허 짝)', () => {
    // ★이 짝이 없으면 위 단언은 `GREP_ARG` 를 **항상** `--grep @prod` 로 둬도 통과한다.
    //   그러면 개발 워크플로우와 CI 2층에서도 스모크 1건만 돌아 검증이 사라진다.
    const src = readFileSync(PIPELINE, 'utf-8');
    assert.match(
      src,
      /startsWith\('http:\/\/localhost'\)\s*\?\s*''/,
      'GREP_ARG 가 대상에 따라 갈리지 않는다 — 로컬에서도 @prod 만 돌면 검증이 사라진다.',
    );
  });
});

describe('② 도메인 매핑', () => {
  test('★★하이픈 없는 이름도 걸린다', () => {
    const specs = allSpecNames(E2E);
    // 저장소에 실재하는 `<도메인>.spec.ts` 를 하나 뽑아 그 도메인으로 조회한다.
    const single = specs.find((s) => !s.slice(0, -'.spec.ts'.length).includes('-'));
    assert.ok(single, '`<도메인>.spec.ts` 서식 파일이 없다 — 대조가 공허해진다(비-공허 짝).');
    const domain = single.slice(0, -'.spec.ts'.length);
    assert.deepEqual(
      specsForDomain(domain, specs),
      [single],
      `'${domain}' 도메인이 '${single}' 을 못 문다.\n` +
        '★종전 글로브 `${d}-*.spec.ts` 하나는 이 서식을 통째로 놓쳤다 —\n' +
        '  실측으로 도메인 12개의 E2E 가 1단에서 0건이었다.',
    );
  });

  test('★★하이픈 서식도 그대로 걸린다 (비-공허 짝)', () => {
    const specs = allSpecNames(E2E);
    const dashed = specs.find((s) => s.includes('-'));
    assert.ok(dashed, '`<도메인>-<시나리오>.spec.ts` 가 없다 — 대조가 공허해진다.');
    const domain = dashed.slice(0, dashed.indexOf('-'));
    assert.ok(
      specsForDomain(domain, specs).includes(dashed),
      `'${domain}' 도메인이 '${dashed}' 을 못 문다 — 서식을 하나만 보고 있다.`,
    );
  });

  test('★매핑 없는 도메인을 숨기지 않는다', () => {
    const specs = allSpecNames(E2E);
    // `apps/web/src/components/` 의 실물 폴더 중 스펙이 하나도 없는 것을 그대로 돌려줘야 한다.
    const comps = readdirSync(join(ROOT, 'apps/web/src/components'), { withFileTypes: true })
      .filter((d) => d.isDirectory())
      .map((d) => d.name);
    const missing = unmappedDomains(comps, specs);
    assert.ok(
      missing.length > 0,
      '매핑 없는 도메인이 0건이다 — 이 저장소 실물과 안 맞는다. 판정기가 공허할 수 있다.',
    );
    // 반대로 전부 미매칭이면 판정기가 고장난 것이다.
    assert.ok(
      missing.length < comps.length,
      `${comps.length}개 폴더 전부가 미매칭이다 — 매칭 규칙이 고장났다.`,
    );
  });

  test('★선택 결과가 apps/web 상대경로다 (playwright 가 그 경로로 돈다)', () => {
    const picked = specsForDomains(['board'], allSpecNames(E2E));
    assert.ok(picked.length > 0, "'board' 도메인 스펙이 0건 — 대조가 공허해진다.");
    for (const p of picked) {
      assert.ok(p.startsWith('e2e/'), `경로가 apps/web 기준이 아니다: ${p}`);
    }
  });
});
