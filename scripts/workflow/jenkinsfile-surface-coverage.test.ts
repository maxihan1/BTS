// 저장소에 있는 파이프라인 정의 파일 전부가 GUARD_CI 표면에 걸리는지 대조 (차집합)
//
// ## 무엇을 막는가 — 2026-09-11 적발
//
// `surfaces.ts` 의 `GUARD_CI` 글로브가 정확히 `'Jenkinsfile'` 이었다. 그래서
// **`Jenkinsfile.e2e` 가 어느 표면에도 안 걸렸다.**
//
//     detect-tier.ts Jenkinsfile      → TIER: T2 · SURFACES: GUARD_CI
//     detect-tier.ts Jenkinsfile.e2e  → TIER: T1 · UNMAPPED
//
// `Jenkinsfile.e2e` 는 **프로덕션 실서버를 상대로 무엇을 검증할지** 정하는 파일이다.
// 한 줄만 고쳐도 배포 후 E2E 전체를 끌 수 있는데, T1 이면 계획 0 · 리뷰 1 이다.
// CI 정의(T2)보다 낮은 강도로 프로덕션 검증을 끌 수 있는 자리였다.
//
// ## 왜 이름을 열거하지 않는가
//
// 「파이프라인 파일 목록」과 「글로브 목록」이라는 **두 목록**이 생기면, 파이프라인을
// 하나 더 만들 때 뒤쪽이 조용히 낡는다. 그 순간 새 파이프라인은 UNMAPPED 로 T1 에 떨어지고,
// **아무도 red 를 보지 않는다.** 그래서 이 판별식은 디스크의 실물을 훑어 차집합을 본다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { SURFACES } from './surfaces.ts';
import { detectTier } from './detect-tier.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** 저장소 루트의 파이프라인 정의 파일 실물. */
function pipelineFiles(): string[] {
  return readdirSync(ROOT)
    .filter((f) => f === 'Jenkinsfile' || f.startsWith('Jenkinsfile.'))
    .sort();
}

describe('파이프라인 정의 파일 ⟺ GUARD_CI 표면', () => {
  test('★비-공허 확인 — 파이프라인 파일이 실제로 있다', () => {
    const files = pipelineFiles();
    assert.ok(
      files.length >= 2,
      `루트에서 파이프라인 파일을 ${files.length}개밖에 못 찾았다: ${files.join(', ')}\n` +
        '★아래 대조가 통째로 공허해진다 — 훑을 대상이 없으면 차집합은 언제나 빈 집합이다.\n' +
        '  `Jenkinsfile`(bts-ci)과 `Jenkinsfile.e2e`(bts-e2e) 둘 다 있어야 한다.',
    );
  });

  test('★★파이프라인 정의 파일 전부가 GUARD_CI(T2)로 판정된다', () => {
    const offenders = pipelineFiles()
      .map((f) => ({ file: f, tier: detectTier([f]) }))
      .filter((r) => !r.tier.surfaces.includes('GUARD_CI'));

    assert.deepEqual(
      offenders.map((o) => `${o.file} → ${o.tier.tier} ${o.tier.surfaces.join(',') || 'UNMAPPED'}`),
      [],
      '파이프라인 정의 파일인데 GUARD_CI 에 안 걸리는 것이 있다.\n' +
        '★그 파일 변경은 UNMAPPED 로 **기본 T1** 에 떨어진다 — 계획 0 · 리뷰 1 이다.\n' +
        '  파이프라인은 「무엇을 돌릴지」를 정하는 자리라 1행 변경이 검증 전체를 끌 수 있다.\n' +
        '  처방. surfaces.ts 의 GUARD_CI 글로브를 `Jenkinsfile*` 로 둔다 — 이름을 열거하지 마라.',
    );
  });

  test('★GUARD_CI 글로브가 이름을 열거하지 않는다 (두 목록 방지)', () => {
    const globs = SURFACES.GUARD_CI.globs.filter((g: string) => g.startsWith('Jenkinsfile'));
    assert.deepEqual(
      globs,
      ['Jenkinsfile*'],
      `GUARD_CI 의 Jenkinsfile 글로브가 ${JSON.stringify(globs)} 다.\n` +
        '★이름을 하나씩 적으면 「파이프라인 파일 목록」과 「글로브 목록」이라는 두 목록이 된다.\n' +
        '  파이프라인을 하나 더 만들 때 뒤쪽이 조용히 낡고, 그 파일은 UNMAPPED 로 T1 에 떨어진다.',
    );
  });
});
