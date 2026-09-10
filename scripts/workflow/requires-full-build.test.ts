// requires-full-build 가 WIDEN_PREFIXES 를 정본으로 쓰는지 + 젠킨스가 그것을 부르는지 대조
//
// ## 두 가지를 함께 본다
//
// ① **판정이 정본 하나를 보는가** — `WIDEN_PREFIXES` 원소 전부가 전량으로 걸리는지.
// ② **파이프라인이 그것을 부르는가** — 안 부르면 젠킨스는 여전히 브랜치만 보고,
//    CI 설정을 고친 푸시가 「빠른 게이트」로 가서 전량 stage 를 건너뛴다.
//
// ②가 특히 중요하다. 빌드 #21 에서 실제로 그 상태였다 — 이름은 빠른 게이트인데 32.7분이
// 걸렸고, 「조립 부팅」·「인프라 봉인」은 아예 안 돌았다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';
// ★`main` 을 임포트한다 — `git-fixture-isolation.test.ts` 의 자식 판정에 실리려면
//   spawn 만으로는 부족하고 임포트가 필요하다.
import { main } from './requires-full-build.ts';
import { requiresFullBuild, WIDEN_PREFIXES } from './select-backend-modules.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINSFILE = join(ROOT, 'Jenkinsfile');
const SCRIPT = 'scripts/workflow/requires-full-build.ts';

test('★WIDEN_PREFIXES 원소가 전부 전량으로 걸린다 (판정 정본 하나)', () => {
  assert.ok(WIDEN_PREFIXES.length > 0, 'WIDEN_PREFIXES 가 비었다 — 아래 루프가 공허해진다.');
  for (const p of WIDEN_PREFIXES) {
    const sample = p.endsWith('/') ? `${p}x/y.kt` : p;
    assert.equal(requiresFullBuild([sample]), true, `'${p}' 가 전량 판정에 안 걸린다.`);
  }
});

test('평범한 변경은 전량이 아니다', () => {
  assert.equal(requiresFullBuild(['apps/web/src/components/board/Board.tsx']), false);
  assert.equal(requiresFullBuild(['docs/rules/traps.md']), false);
});

test('★변경 목록을 못 구하면 전량이다 (모르면 넓게)', () => {
  assert.equal(requiresFullBuild(null), true);
});

test('main 은 종료 코드 0 이다 (판정만 하고 빌드를 막지 않는다)', () => {
  assert.equal(main(), 0);
});

test('★Jenkinsfile 의 전량 판정이 이 스크립트를 부른다 (배선 대조)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  assert.match(
    jf,
    new RegExp(SCRIPT.replace(/[/.]/g, '\\$&')),
    'Jenkinsfile 이 requires-full-build 를 부르지 않는다.\n' +
      '그러면 CI 설정을 고친 푸시가 「빠른 게이트」로 가고, 그 안에서 계산기가 스스로\n' +
      '전량으로 넓혀 32분이 걸리면서도 「조립 부팅」·「인프라 봉인」은 건너뛴다(빌드 #21).',
  );
});

test('★Jenkinsfile 이 판정 목록을 Groovy 로 다시 적지 않는다 (두 목록 차단)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const code = jf
    .split('\n')
    .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*'))
    .join('\n');
  // `WIDEN_PREFIXES` 라는 이름이 파이프라인 코드에 나오면 목록을 옮겨 적었다는 뜻이다.
  assert.doesNotMatch(
    code,
    /WIDEN_PREFIXES/,
    'Jenkinsfile 이 WIDEN_PREFIXES 를 직접 참조한다 — 목록이 두 벌이 된다.\n' +
      '판정은 requires-full-build.ts 를 통해서만 한다.',
  );
});
