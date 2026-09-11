// 스냅샷 baseline(기준 이미지)만 조용히 갱신하는 커밋을 막는 판별식
//
// ## 왜 이 파일이 있나
//
// 시각 회귀 테스트는 「지금 화면」을 저장해 둔 기준 이미지와 비교한다. 그래서 실패했을 때
// **가장 쉬운 해결책이 기준 이미지를 새로 찍는 것**이다. 그러면 테스트는 초록이 되고,
// 무엇이 어떻게 달라졌는지는 아무도 안 본 채로 남는다. 회귀 안전망이 회귀를 승인해 준다.
//
// 처방은 단순하다 — **기준 이미지가 바뀌면 화면 소스도 같이 바뀌어야 한다.**
// 소스는 그대로인데 기준만 바뀌었다면, 그것은 「고쳤다」가 아니라 「기준을 낮췄다」이다.
//
// 반대 방향(소스가 바뀌면 기준도 바꿔라)은 강제하지 않는다. 화면에 안 보이는 변경이
// 대부분이고, 그쪽은 `visual` 잡이 실패로 알려준다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { matchesGlob } from './detect-tier.ts';
import { changedPaths } from './changed-paths.ts';
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * 기준 이미지가 사는 곳.
 *
 * Playwright 기본값(스펙 파일 옆 `*-snapshots/`)을 쓰지 않고 한 곳으로 모은다 —
 * 기본값은 파일명에 OS 접미(`-darwin`)를 붙여, 러너를 바꾸면 **조용히 새 파일이 생긴다.**
 */
const BASELINE_GLOB = 'apps/web/e2e/visual/__screenshots__/**';

/** 기준 이미지를 만든 화면 소스 */
const SOURCE_GLOB = 'apps/web/src/**';

/**
 * 기준 이미지만 바뀌었는지 판정한다.
 *
 * @returns 위반 사유. 위반이 아니면 null
 */
export const baselineOnlyViolation = (
  paths: readonly string[],
  /**
   * 「이 경로가 **이미 있었나**」를 판정하는 함수. 테스트에서 주입한다.
   *
   * 기본값은 디스크를 본다 — 작업 트리에 없으면 이번에 새로 만든 것이다.
   */
  existedBefore: (p: string) => boolean = defaultExistedBefore,
): string | null => {
  const baselines = paths.filter((p) => matchesGlob(BASELINE_GLOB, p));
  if (baselines.length === 0) return null;
  if (paths.some((p) => matchesGlob(SOURCE_GLOB, p))) return null;

  /*
   * ★★「최초 생성」과 「무단 갱신」을 구분한다 (2026-09-11).
   *
   * 이 판별식이 막으려는 것은 **회귀를 승인하는 재생성**이다 — 시각 테스트가 실패했을 때
   * 가장 쉬운 해결책이 기준 이미지를 새로 찍는 것이고, 그러면 무엇이 달라졌는지 아무도
   * 안 본 채 초록이 된다.
   *
   * 그런데 **아직 없던 기준을 처음 만드는 것**은 그 고장이 원리적으로 불가능하다 —
   * 덮어쓸 기준이 없으므로 승인할 회귀도 없다. 종전 규칙은 둘을 구분하지 않아,
   * P3(시각 회귀 파일럿)의 최초 기준선 커밋이 **영원히 막혀** 있었다.
   * 그래서 계획 문서가 「커밋 해시로 일회성 예외를 열고 같은 PR 에서 닫는다」는
   * 위험한 우회를 적어 두고 있었다 — 열어 둔 채 머지하면 그날부터 이 판별식이 공허해진다.
   *
   * 우회 대신 규칙을 정확하게 만든다. 삭제도 「바뀜」이라 여전히 걸리므로,
   * 「지우고 다시 만들기」로 빠져나갈 수도 없다(그 커밋의 삭제가 먼저 잡힌다).
   */
  const updated = baselines.filter((p) => existedBefore(p));
  if (updated.length === 0) return null;

  return `기준 이미지 ${updated.length}건이 **갱신**됐는데 ${SOURCE_GLOB} 변경이 하나도 없다: ${updated.join(', ')}`;
};

/** 기본 판정 — 작업 트리에 그 파일이 있으면 「이미 있던 것」이다. */
const defaultExistedBefore = (p: string): boolean =>
  fs.existsSync(path.join(REPO_ROOT, p));

/** 저장소가 추적 중인 파일 전량 */
const trackedFiles = (): string[] => {
  const run = spawnSync('git', ['ls-files', '-z'], {
    cwd: REPO_ROOT,
    encoding: 'utf-8',
    maxBuffer: 64 * 1024 * 1024,
    env: gitFixtureEnv(),
  });
  assert.equal(run.status, 0, `git ls-files 실패: ${run.stderr}`);
  return run.stdout.split('\0').filter((p) => p.length > 0);
};

describe('스냅샷 baseline 무단 갱신 차단', () => {
  test('기준만 바뀌면 위반 · 소스가 함께 바뀌면 통과 (오탐 대조)', () => {
    const baseline = 'apps/web/e2e/visual/__screenshots__/issue-list-light.png';
    // ★합성 경로라 디스크에 없다. 「이미 있던 것」을 명시해 **갱신** 시나리오를 만든다 —
    //   기본 판정기(디스크 존재)를 그대로 쓰면 이 케이스가 「최초 생성」으로 읽힌다.
    const existed = () => true;

    const violation = baselineOnlyViolation([baseline], existed);
    assert.ok(violation, '기준 이미지만 바꾼 변경을 통과시켰다 — 이 판별식의 존재 이유가 사라진다.');
    assert.match(violation, /apps\/web\/src/);

    assert.equal(
      baselineOnlyViolation([baseline, 'apps/web/src/features/issue/IssueList.tsx'], existed),
      null,
      '소스를 함께 고친 정상 갱신을 위반으로 읽으면 아무도 이 판별식을 안 믿는다.',
    );

    // ★★최초 생성은 막지 않는다. 덮어쓸 기준이 없으므로 승인할 회귀도 없다.
    //   종전 규칙은 둘을 구분하지 않아 P3 의 최초 기준선 커밋이 영원히 막혀 있었고,
    //   계획 문서가 「커밋 해시로 일회성 예외를 연다」는 위험한 우회를 적어 두고 있었다.
    assert.equal(
      baselineOnlyViolation([baseline], () => false),
      null,
      '아직 없던 기준을 **처음 만드는** 것을 막는다 — 그러면 시각 회귀를 영영 도입할 수 없다.',
    );
    assert.equal(
      baselineOnlyViolation(['apps/web/e2e/visual/visual-regression.spec.ts']),
      null,
      '기준 이미지가 없는 변경은 애초에 대상이 아니다.',
    );
    assert.equal(baselineOnlyViolation([]), null);
  });

  test('기준 이미지가 정해진 경로 밖에 생기지 않았다', () => {
    // 경로가 어긋나면 위 판정이 **영원히 0건**을 보면서 초록이 된다.
    // 「지키는 척」을 막는 유일한 방법은 실물 파일 위치를 직접 세는 것이다.
    const stray = trackedFiles().filter(
      (f) => matchesGlob('apps/web/e2e/**/*.png', f) && !matchesGlob(BASELINE_GLOB, f),
    );
    assert.deepEqual(
      stray,
      [],
      `기준 이미지가 감시 밖에 있다: ${stray.join(', ')}\n\n` +
        `playwright 설정의 snapshotPathTemplate 이 '${BASELINE_GLOB}' 아래를 가리키는지 확인하라.`,
    );
  });

  test('이 PR 이 기준 이미지를 무단 갱신하지 않았다', () => {
    const { paths, source } = changedPaths();
    const violation = baselineOnlyViolation(paths);
    assert.equal(
      violation,
      null,
      `${violation}\n\n변경 목록 출처: ${source}\n` +
        '화면이 안 바뀌었는데 기준만 바뀌었다면, 무엇이 달라졌는지를 먼저 설명해야 한다. ' +
        '의도한 변경이면 그 화면 소스 커밋과 같은 PR 에 담아라.',
    );
  });
});
