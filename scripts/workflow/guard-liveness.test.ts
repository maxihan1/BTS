// 판별식이 「존재하는데 안 도는」 상태가 아닌지 대조
//
// ## ① 주석에 삼켜진 테스트 — 2026-09-11 적발
//
// `node-ts-invocation.test.ts:796` 의 `/**` 가 닫히지 않아 그 아래 `test(...)` 하나가
// **통째로 주석이 됐다.** 실행 목록에서 사라졌고, 판별식 총 건수는 9 → 8 이 아니라
// 그냥 9 로 보였다(다른 파일 수가 흔들려 티가 안 났다).
//
//     원인 커밋 7f6c7441d — `setup-node` 단언 3종을 지우면서 여는 `/**` 만 남겼다.
//
// 꺼진 것은 「`.nvmrc` 가 타입 스트리핑 하한(22.6)을 넘는가」였다. 그 하한 아래로 내리면
// `.ts` 실행이 전부 `ERR_UNKNOWN_FILE_EXTENSION` 으로 죽는데, 남은 단언들은 초록이다.
//
// ★이 결함은 **빨간불을 안 낸다.** 테스트가 줄어드는 것은 초록이다. 「실패가 아니라 침묵」이라
//   전량 실행 로그를 봐도 안 보인다 — 건수를 세는 쪽이 없으면 아무도 모른다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const WF = join(ROOT, 'scripts/workflow');

describe('① 판별식이 주석에 삼켜지지 않는다', () => {
  /**
   * 블록 주석 **안에** 들어가 버린 `test(`/`describe(` 선언 줄을 찾는다.
   *
   * ★줄 단위로 본다. TS 를 제대로 렉싱하려면 정규식 리터럴까지 가려야 하는데
   *   (`/\*\//` 같은 것들), 그 비용을 치르지 않고도 **실제 고장 모양**은 잡힌다 —
   *   여는 주석 줄 다음에 들여쓰기만 있고 바로 `test(` 가 오는 형태다.
   *
   * ★JSDoc 안의 예시는 ` * test(...)` 처럼 `*` 로 시작하므로 걸리지 않는다.
   *   저장소 전량 실측(2026-09-11) 오탐 0건.
   */
  function swallowedDeclarations(src: string): string[] {
    const hits: string[] = [];
    let inBlock = false;
    src.split('\n').forEach((raw, idx) => {
      const t = raw.trim();
      if (!inBlock) {
        if (t.startsWith('/*')) inBlock = !t.includes('*/');
        return;
      }
      if (t.startsWith('test(') || t.startsWith('describe(')) {
        hits.push(`${idx + 1}: ${t.slice(0, 60)}`);
      }
      if (t.includes('*/')) inBlock = false;
    });
    return hits;
  }

  test('★★블록 주석에 삼켜진 test 선언이 없다', () => {
    const offenders: string[] = [];
    for (const f of readdirSync(WF).filter((n) => n.includes('.test.'))) {
      const hits = swallowedDeclarations(readFileSync(join(WF, f), 'utf-8'));
      if (hits.length > 0) offenders.push(`${f} → ${hits.join(' · ')}`);
    }
    assert.deepEqual(
      offenders,
      [],
      `블록 주석 안에 들어간 test 선언: ${offenders.join(' | ')}\n` +
        '★그 테스트는 **조용히 안 돈다.** 테스트가 줄어드는 것은 초록이라\n' +
        '  전량 실행 로그를 봐도 안 보인다 — 「실패가 아니라 침묵」이다.\n' +
        '  2026-09-11 에 `node-ts-invocation.test.ts` 가 정확히 그 상태였고,\n' +
        '  「.nvmrc 가 타입 스트리핑 하한을 넘는가」가 안 돌고 있었다.',
    );
  });

  test('★비-공허 확인 — 삼켜진 선언을 실제로 잡아낸다 (합성 뮤테이션)', () => {
    // ★위 단언은 판정기가 「항상 빈 배열」이어도 통과한다. 실제로 구분하는지 확인한다.
    const broken = "/**\n// 설명이 이어진다\ntest('a', () => {})\n";
    assert.equal(
      swallowedDeclarations(broken).length,
      1,
      '안 닫힌 주석이 삼킨 선언을 판정기가 못 잡는다 — 위 단언이 통째로 공허하다.',
    );
    const healthy = "/** doc */\ntest('a', () => {})\n";
    assert.deepEqual(swallowedDeclarations(healthy), [], '멀쩡한 파일을 잡는다(오탐).');
    const jsdocExample = "/**\n * 예시. test('x', () => {})\n */\ntest('a', () => {})\n";
    assert.deepEqual(swallowedDeclarations(jsdocExample), [], 'JSDoc 예시를 고장으로 읽는다.');
  });
});
