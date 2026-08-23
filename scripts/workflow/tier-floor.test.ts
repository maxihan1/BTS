// 보안·마이그레이션 표면이 낮은 티어로 선언되는 것을 CI 에서 막는 판별식 (티어 하한)
//
// ## 왜 이 파일이 있나
//
// 티어 판정 자체는 **원리적으로 강제할 수 없다.** 착수 시점엔 diff 가 없어 판정할 근거가
// 없고, 선언을 적는 주체가 모델이기 때문이다. 그래서 「전부 강제」를 노리지 않고,
// **경로만 보면 반박이 불가능한 두 하한**만 기계에 맡긴다.
//
//   ⓐ 보안 표면을 건드렸으면 T2 미만일 수 없다.
//   ⓑ Flyway 마이그레이션을 건드렸으면 T3 여야 한다.
//
// 나머지(T0 인지 T1 인지, 승격이 필요한지)는 사람이 게이트 2 에서 본다. 자동 승격은 하지
// 않는다 — 구현이 끝난 뒤에 계획 승인을 받는 절차 역전이 되기 때문이다.
//
// ## 선언을 어디서 읽나
//
// **plan 파일의 부재/존재가 곧 선언이다.** T0/T1 은 `docs/plans/*.md` 를 만들지 않고,
// T2+ 는 만들면서 머리에 `티어:` 행을 적는다. 두 신호가 한 술어라 「plan 은 없는데 T2 라고
// 우기기」가 성립하지 않는다.
//
// ## 판정 로직을 여기 다시 적지 않는다
//
// 표면 글로브는 `surfaces.ts`, 경로→표면 판정은 `detect-tier.ts`, 변경 목록은
// `changed-paths.ts` 가 정본이다. 이 파일은 그 셋을 **import 해서 하한만 본다.**
// 판정을 두 벌 적으면 판별식과 실제 판정기가 서로 다른 답을 내면서 둘 다 초록이 된다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { SURFACES, SURFACE_PRECEDENCE, type SurfaceName } from './surfaces.ts';
import { detectTier, matchesGlob, surfaceOf, TIER_ORDER } from './detect-tier.ts';
import { changedPaths } from './changed-paths.ts';
import type { Tier } from './types.ts';
// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { gitFixtureEnv } from './git-fixture-env.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const DETECT_TIER_CLI = path.join(REPO_ROOT, 'scripts/workflow/detect-tier.ts');

/** plan 파일이 사는 곳. 파일명 규칙(`YYYY-MM-DD-slug.md`)은 doc-index 가 따로 본다 */
const PLAN_GLOB = 'docs/plans/*.md';

/** 착수 시점 선언 — plan 파일 머리에서 읽는다 */
interface Declaration {
  /** plan 파일 경로. 없으면 null = T0/T1 선언 */
  planFile: string | null;
  /** `티어:` 행의 값. plan 은 있는데 행이 없으면 null */
  tier: Tier | null;
  /** `타입:`(또는 `type:`) 행의 값 */
  type: string | null;
}

/**
 * plan 스텁 머리의 선언 행을 읽는다.
 *
 * 한글·영문 두 표기를 다 받는다. 정본 템플릿은 `티어:` 와 `type:` 을 섞어 쓰고 있고,
 * 여기서 한쪽만 읽으면 「표기가 달라서 못 읽었는데 조용히 통과」가 된다.
 */
export const parseDeclaration = (planBody: string): Pick<Declaration, 'tier' | 'type'> => {
  const tierMatch = planBody.match(/^>?\s*(?:티어|tier)\s*:\s*(T[0-3])\b/im);
  const typeMatch = planBody.match(/^>?\s*(?:타입|type)\s*:\s*([a-z-]+)/im);
  return {
    tier: (tierMatch?.[1] as Tier | undefined) ?? null,
    type: typeMatch?.[1] ?? null,
  };
};

/** 변경 목록에서 plan 파일을 찾아 선언을 읽는다 */
/**
 * 바뀐 plan 후보를 **이 PR 의 선언일 가능성이 높은 순서**로 늘어놓는다.
 *
 * ★사전순 첫 번째를 쓰면 **문서 전수 편집 PR 에서 무너진다.** 2026-08-18 실측 —
 * Transition 용어 교체 PR 이 옛 계획서 89개를 함께 바꾸자 `2026-05-21-…` 이 먼저 잡혀
 * 이 PR 이 만든 `2026-08-18-…`(티어 T2)이 가려졌고, 보안 하한이 「T0/T1 선언」으로 오판했다.
 * 파일명이 `YYYY-MM-DD-slug.md` 라 **이름 내림차순 = 날짜 내림차순**이다.
 */
export const orderPlanCandidates = (paths: readonly string[]): string[] =>
  paths.filter((p) => matchesGlob(PLAN_GLOB, p)).sort().reverse();

const declarationOf = (paths: readonly string[]): Declaration => {
  const planFiles = orderPlanCandidates(paths);
  for (const planFile of planFiles) {
    const abs = path.join(REPO_ROOT, planFile);
    // 삭제된 plan 파일은 선언이 아니다. 디스크에 있는 것만 읽는다.
    if (!fs.existsSync(abs)) continue;
    const parsed = parseDeclaration(fs.readFileSync(abs, 'utf8'));
    return { planFile, ...parsed };
  }
  return { planFile: null, tier: null, type: null };
};

const rank = (tier: Tier): number => TIER_ORDER.indexOf(tier);

/**
 * ⓐ 보안 하한 — 보안 표면이 걸렸으면 선언이 T2 이상이어야 한다.
 *
 * @returns 위반 사유. 위반이 아니면 null
 */
export const securityFloorViolation = (
  paths: readonly string[],
  declaration: Declaration,
): string | null => {
  const verdict = detectTier(paths);
  if (!verdict.securityLens) return null;
  const secure = verdict.paths
    .filter((v) => v.matched.some((name) => SURFACES[name].lens === 'security'))
    .map((v) => v.path);
  if (declaration.tier === null) {
    return `보안 표면 ${secure.length}건을 건드렸는데 선언이 T0/T1 이다 (plan 파일 ${declaration.planFile ?? '부재'}). 해당 경로: ${secure.join(', ')}`;
  }
  if (rank(declaration.tier) < rank('T2')) {
    return `보안 표면 ${secure.length}건을 건드렸는데 선언 티어가 ${declaration.tier} 다. 해당 경로: ${secure.join(', ')}`;
  }
  return null;
};

/**
 * ⓑ 마이그레이션 하한 — Flyway 파일이 걸렸으면 선언이 T3 여야 한다.
 *
 * 여기만 「이상」이 아니라 「정확히 T3」인 이유. 마이그레이션 위에 더 무거운 티어가 없다.
 */
export const migrationFloorViolation = (
  paths: readonly string[],
  declaration: Declaration,
): string | null => {
  const migrations = paths.filter((p) => surfaceOf(p) === 'MIGRATION');
  if (migrations.length === 0) return null;
  if (declaration.tier === 'T3') return null;
  return `마이그레이션 ${migrations.length}건을 건드렸는데 선언이 ${declaration.tier ?? 'T0/T1(plan 부재)'} 다. 해당 경로: ${migrations.join(', ')}`;
};

/**
 * ⓓ TDD 선행 — `test:` 커밋이 첫 `feat:` 커밋보다 앞서야 한다.
 *
 * @param subjects 오래된 것부터 정렬된 커밋 제목
 * @returns 위반 사유. 위반이 아니면 null
 */
export const tddOrderViolation = (subjects: readonly string[]): string | null => {
  const isPrefix = (subject: string, kind: string): boolean =>
    new RegExp(`^${kind}(\\([^)]*\\))?!?:`).test(subject.trim());
  const firstFeat = subjects.findIndex((s) => isPrefix(s, 'feat'));
  if (firstFeat === -1) return null;
  const firstTest = subjects.findIndex((s) => isPrefix(s, 'test'));
  if (firstTest !== -1 && firstTest < firstFeat) return null;
  return firstTest === -1
    ? `feat: 커밋은 있는데 test: 커밋이 하나도 없다 (첫 feat = "${subjects[firstFeat]}")`
    : `test: 커밋이 첫 feat: 커밋보다 뒤에 있다 (feat = "${subjects[firstFeat]}" · test = "${subjects[firstTest]}")`;
};

/** 이 브랜치의 커밋 제목 — 오래된 것부터 */
const branchSubjects = (): string[] | null => {
  const base = spawnSync('git', ['merge-base', 'origin/main', 'HEAD'], {
    cwd: REPO_ROOT,
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });
  if (base.status !== 0) return null;
  const log = spawnSync('git', ['log', '--reverse', '--format=%s', `${base.stdout.trim()}..HEAD`], {
    cwd: REPO_ROOT,
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });
  if (log.status !== 0) return null;
  return log.stdout.split('\n').filter((s) => s.trim().length > 0);
};

/** 저장소가 실제로 추적 중인 파일 전량 */
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

// ─────────────────────────────────────────────────────────
// ⓒ 표면 카탈로그 자체의 건강 — 죽은 글로브·빠진 표면을 먼저 잡는다
// ─────────────────────────────────────────────────────────

describe('표면 카탈로그', () => {
  const tracked = trackedFiles();

  test('추적 파일을 실제로 읽었다 (비-공허 짝)', () => {
    // 파일 목록이 비면 아래 「글로브가 파일을 문다」가 전부 공허하게 실패한다.
    // 반대로 목록이 있는데 0건 매칭이면 그것은 진짜 죽은 글로브다 — 둘을 구분해 둔다.
    assert.ok(tracked.length > 500, `추적 파일이 ${tracked.length}개뿐이다 — git ls-files 가 고장났다.`);
  });

  test('표면 이름 집합이 12개 이상이다 (하한)', () => {
    // 티어 4단계 각각에 최소 1개는 살아 있어야 판정이 성립한다. 15 − 3 = 12 를 하한으로 둔다.
    const names = Object.keys(SURFACES);
    assert.ok(names.length >= 12, `표면이 ${names.length}개뿐이다 — 파서나 카탈로그가 고장났다.`);
    for (const tier of TIER_ORDER) {
      const has = names.some((name) => SURFACES[name as SurfaceName].tier === tier);
      assert.ok(has, `${tier} 를 요구하는 표면이 하나도 없다 — 그 티어는 도달 불가가 된다.`);
    }
  });

  test('우선순위 배열과 표면 집합의 차집합이 0 이다 (양방향)', () => {
    const declared = Object.keys(SURFACES).sort();
    const ordered = [...SURFACE_PRECEDENCE].sort();
    assert.deepEqual(
      ordered,
      declared,
      '표면을 추가·삭제하면서 SURFACE_PRECEDENCE 를 안 고쳤다. ' +
        '빠진 표면은 어떤 경로도 세지 못하고 조용히 미분류로 떨어진다.',
    );
    assert.equal(new Set(SURFACE_PRECEDENCE).size, SURFACE_PRECEDENCE.length, '우선순위에 중복이 있다.');
  });

  test('모든 글로브가 실제 파일을 하나 이상 문다', () => {
    const dead: string[] = [];
    for (const [name, surface] of Object.entries(SURFACES)) {
      for (const glob of surface.globs) {
        if (!tracked.some((file) => matchesGlob(glob, file))) dead.push(`${name}: ${glob}`);
      }
    }
    assert.deepEqual(
      dead,
      [],
      `아무 파일도 안 걸리는 글로브가 있다:\n${dead.join('\n')}\n\n` +
        '죽은 글로브는 지켜주는 척만 한다. 경로가 바뀌었으면 고치고, 사라진 표면이면 지워라.',
    );
  });
});

// ─────────────────────────────────────────────────────────
// ⓔ 배포 게이트도 강제 장치다 — 판정하는 자리와 판정되는 자리의 티어를 맞춘다
// ─────────────────────────────────────────────────────────

/**
 * `infra/deploy/bts-deploy.sh` 는 **프로덕션 직전의 마지막 검사대**다.
 *
 * 2026-08-23 실측 — 이 파일이 `surfaces.ts` 의 어느 글로브에도 안 걸려
 * `TIER: T1` · `SURFACES: (없음)` · `UNMAPPED: infra/deploy/bts-deploy.sh` 가 나왔다.
 * 그 상태에서는 게이트가 조용히 약해져도 절차 강도가 안 올라간다 — 계획 0 · 리뷰 1종으로
 * 프로덕션 직전 검사대를 고칠 수 있다.
 *
 * ★왜 훅과 짝으로 재나. 두 파일은 `GIT_*` 스크럽 **같은 한 줄**을 공유하고,
 * `discriminant-hook-wiring.test.ts` 가 「배포 줄을 바꾸려면 훅 줄을 **같은 커밋에서 같은
 * 형태로** 바꿔라」를 강제한다. 티어가 갈리면 그 한 커밋 안에서 한쪽은 T2 절차, 다른 쪽은
 * T1 절차가 된다 — 같이 움직이라고 묶어 놓고 절차만 갈라 놓는 셈이다.
 */
describe('ⓔ 배포 게이트의 표면 등록', () => {
  const DEPLOY = 'infra/deploy/bts-deploy.sh';
  const HOOK = '.husky/pre-push';

  test('★배포 게이트가 미분류로 떨어지지 않는다', () => {
    const verdict = detectTier([DEPLOY]);
    assert.deepEqual(
      verdict.unmapped,
      [],
      `${DEPLOY} 가 어느 표면에도 안 걸린다.\n` +
        '프로덕션 직전의 마지막 검사대가 기본 T1 로 떨어진다 — 계획 0 · 리뷰 1종이다.',
    );
    assert.equal(
      surfaceOf(DEPLOY),
      'GUARD_CI',
      `${DEPLOY} 가 GUARD_CI 가 아니다. 배포 게이트는 강제 장치이지 배포 산출물이 아니다.`,
    );
  });

  test('★★훅과 배포 게이트의 티어가 같다', () => {
    const hookTier = detectTier([HOOK]).tier;
    const deployTier = detectTier([DEPLOY]).tier;
    assert.equal(
      deployTier,
      hookTier,
      `${HOOK} 는 ${hookTier} 인데 ${DEPLOY} 는 ${deployTier} 다.\n` +
        '두 파일은 GIT_* 스크럽 같은 한 줄을 공유하고, 그 동일성을 판별식이 강제한다. ' +
        '한 커밋에서 함께 바뀌어야 하는 두 자리의 절차 강도가 갈렸다.',
    );
  });
});

// ─────────────────────────────────────────────────────────
// 판정 5조 단위 케이스 — 판정기가 살아 있음을 먼저 증명한다
// ─────────────────────────────────────────────────────────

describe('티어 판정 5조', () => {
  test('① 혼합이면 최고 티어', () => {
    const verdict = detectTier([
      'docs/rules/behavior-rules.md',
      'apps/web/src/features/issue/IssueCard.tsx',
      'backend/modules/issue-tracking/src/main/resources/db/migration/V99__x.sql',
    ]);
    assert.equal(verdict.tier, 'T3');
    // 나열 순서는 우선순위 배열 순서다 — 「무엇에 걸렸나」를 항상 같은 차례로 읽게 한다.
    assert.deepEqual(verdict.surfaces, ['MIGRATION', 'DOC', 'FE_SRC']);
  });

  test('② 신호가 없으면 T1', () => {
    assert.equal(detectTier([]).tier, 'T1');
    assert.equal(detectTier(['docs/INDEX-fr.md', 'MEMORY.md']).tier, 'T1');
  });

  test('③ 미분류는 T1 + UNMAPPED 로 드러난다', () => {
    const verdict = detectTier(['apps/web/vite.config.ts']);
    assert.equal(verdict.tier, 'T1');
    assert.deepEqual(verdict.unmapped, ['apps/web/vite.config.ts']);
  });

  test('④ 테스트 전용 변경은 티어를 올리지 않는다', () => {
    const verdict = detectTier([
      'backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/AuthTest.kt',
      'apps/web/src/auth/authStore.test.ts',
      'scripts/workflow/detect-tier.test.ts',
    ]);
    assert.equal(verdict.tier, 'T1');
    assert.equal(verdict.securityLens, false, '보안 모듈의 테스트가 보안 렌즈를 켜면 규칙 ④ 가 깨진다.');
    // 같은 파일의 소스 짝이 함께 바뀌면 그때는 올라간다 — 규칙 ④ 가 구멍이 아님을 보이는 대조군.
    const withSource = detectTier([
      'backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/AuthTest.kt',
      'backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/Auth.kt',
    ]);
    assert.equal(withSource.tier, 'T2');
    assert.equal(withSource.securityLens, true);
  });

  test('⑤ 실측이 선언보다 높아도 자동 승격하지 않는다 (종료 코드 0)', () => {
    const run = spawnSync(
      process.execPath,
      [
        '--experimental-strip-types',
        DETECT_TIER_CLI,
        '--declared',
        'T0',
        'backend/modules/issue-tracking/src/main/resources/db/migration/V99__x.sql',
        'apps/web/vite.config.ts',
      ],
      { cwd: REPO_ROOT, encoding: 'utf-8' },
    );
    assert.equal(run.status, 0, `자동 승격/차단이 생겼다. stderr=${run.stderr}`);
    assert.match(run.stdout, /^TIER: T3$/m);
    assert.match(run.stdout, /^UNMAPPED: apps\/web\/vite\.config\.ts$/m, '미분류 경로가 출력에 없다.');
    assert.match(run.stdout, /PROMOTION_NEEDED/, '선언<실측 인데 사람이 볼 줄이 없다.');
  });

  test('보안 렌즈는 shared-kernel 의 권한 코드에서도 켜진다', () => {
    // 티어는 SHARED_KERNEL(T3)로 세어지지만 렌즈까지 사라지면 보안 리뷰가 조용히 빠진다.
    const verdict = detectTier(['backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/Scope.kt']);
    assert.equal(verdict.tier, 'T3');
    assert.equal(verdict.securityLens, true);
  });
});

// ─────────────────────────────────────────────────────────
// ⓐⓑⓓ 하한 판정 — 먼저 합성 입력으로 살아 있음을 보이고, 그다음 이 PR 에 적용한다
// ─────────────────────────────────────────────────────────

const SECURITY_PATH = 'backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/auth/Jwt.kt';
const MIGRATION_PATH = 'backend/modules/issue-tracking/src/main/resources/db/migration/V99__x.sql';
const PLAN_PATH = 'docs/plans/2026-08-13-x.md';

describe('plan 선언 고르기 — 여럿이 바뀌어도 이 PR 의 것을 읽는다', () => {
  test('최신 날짜 plan 이 먼저 온다 (문서 전수 편집 PR 회귀 방지)', () => {
    const ordered = orderPlanCandidates([
      'docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md',
      'backend/modules/identity-access/src/main/kotlin/X.kt',
      'docs/plans/2026-08-18-workflow-editor-adr-fr.md',
      'docs/plans/2026-07-16-something.md',
    ]);
    assert.equal(
      ordered[0],
      'docs/plans/2026-08-18-workflow-editor-adr-fr.md',
      '사전순 첫 번째를 읽으면 옛 계획서가 이 PR 의 선언을 가린다',
    );
    assert.equal(ordered.length, 3, 'plan 이 아닌 경로가 후보에 섞였다');
  });

  test('plan 이 하나뿐이면 그대로 고른다 (오탐 대조)', () => {
    assert.deepEqual(orderPlanCandidates(['docs/plans/2026-08-18-a.md', 'apps/web/src/X.tsx']), [
      'docs/plans/2026-08-18-a.md',
    ]);
  });
});

describe('ⓐ 보안 표면 하한', () => {
  test('plan 부재 = T0/T1 선언이면 위반', () => {
    const v = securityFloorViolation([SECURITY_PATH], { planFile: null, tier: null, type: null });
    assert.ok(v, '보안 파일을 plan 없이 올렸는데 통과했다.');
    assert.match(v, /T0\/T1/);
  });

  test('plan 은 있는데 티어가 T1 이면 위반', () => {
    const v = securityFloorViolation([SECURITY_PATH], { planFile: PLAN_PATH, tier: 'T1', type: 'auth' });
    assert.ok(v, 'plan 에 T1 이라고 적으면 하한이 뚫린다.');
  });

  test('T2·T3 선언이면 통과하고, 보안 표면이 없으면 아예 대상이 아니다 (오탐 대조)', () => {
    assert.equal(securityFloorViolation([SECURITY_PATH], { planFile: PLAN_PATH, tier: 'T2', type: 'auth' }), null);
    assert.equal(securityFloorViolation([SECURITY_PATH], { planFile: PLAN_PATH, tier: 'T3', type: 'auth' }), null);
    assert.equal(
      securityFloorViolation(['apps/web/src/features/issue/IssueCard.tsx'], { planFile: null, tier: null, type: null }),
      null,
      '보안과 무관한 변경을 위반으로 읽으면 아무도 이 판별식을 안 믿는다.',
    );
  });

  test('이 PR 이 하한을 지킨다', () => {
    const { paths, source } = changedPaths();
    const violation = securityFloorViolation(paths, declarationOf(paths));
    assert.equal(
      violation,
      null,
      `${violation}\n\n변경 목록 출처: ${source}\n` +
        '보안 표면은 계획+승인(게이트 1)을 건너뛸 수 없다. plan 파일을 만들고 머리에 `티어: T2` 를 적어라.',
    );
  });
});

describe('ⓑ Flyway T3 하한', () => {
  test('T3 가 아니면 전부 위반', () => {
    for (const tier of ['T0', 'T1', 'T2'] as const) {
      assert.ok(
        migrationFloorViolation([MIGRATION_PATH], { planFile: PLAN_PATH, tier, type: 'migration' }),
        `${tier} 선언인데 마이그레이션이 통과했다.`,
      );
    }
    assert.ok(migrationFloorViolation([MIGRATION_PATH], { planFile: null, tier: null, type: null }));
  });

  test('T3 면 통과하고, 마이그레이션이 없으면 대상이 아니다 (오탐 대조)', () => {
    assert.equal(migrationFloorViolation([MIGRATION_PATH], { planFile: PLAN_PATH, tier: 'T3', type: 'migration' }), null);
    assert.equal(
      migrationFloorViolation(['backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/Issue.kt'], {
        planFile: null,
        tier: null,
        type: null,
      }),
      null,
    );
  });

  test('이 PR 이 하한을 지킨다', () => {
    const { paths, source } = changedPaths();
    const violation = migrationFloorViolation(paths, declarationOf(paths));
    assert.equal(
      violation,
      null,
      `${violation}\n\n변경 목록 출처: ${source}\n` +
        '마이그레이션은 되돌릴 수 없다 — 설계 문서와 승인 없이 올리지 않는다.',
    );
  });
});

describe('ⓓ TDD 선행 대조', () => {
  test('test: 가 첫 feat: 보다 앞서야 한다', () => {
    assert.equal(tddOrderViolation(['test: 실패하는 케이스', 'feat: 구현', 'refactor: 정리']), null);
    assert.equal(tddOrderViolation(['test(auth): red', 'feat(auth): green']), null);
    assert.equal(tddOrderViolation(['chore: 정리', 'docs: 설명']), null, 'feat 이 없으면 대조할 것이 없다.');
    assert.ok(tddOrderViolation(['feat: 구현', 'test: 뒤늦게']), 'green 뒤에 붙인 테스트를 통과시켰다.');
    assert.ok(tddOrderViolation(['feat: 구현만']), 'test 커밋이 아예 없는데 통과했다.');
  });

  test('plan 이 있고 타입이 ui 가 아니면 이 PR 도 선행을 지킨다', () => {
    const { paths } = changedPaths();
    const declaration = declarationOf(paths);
    if (declaration.planFile === null || declaration.type === 'ui') {
      // plan 부재 = T0/T1 이고, ui 는 시각 검증 트랙이라 red-first 가 면제된다.
      // 면제는 「검사 대상이 아니다」이지 「검사를 껐다」가 아니므로 여기서 끝낸다.
      return;
    }
    const subjects = branchSubjects();
    assert.ok(subjects, 'git log 를 못 읽었다 — 대조가 공허해진다.');
    const violation = tddOrderViolation(subjects);
    assert.equal(
      violation,
      null,
      `${violation}\n\nplan ${declaration.planFile} (타입 ${declaration.type ?? '미기재'}) 는 red-first 대상이다.`,
    );
  });
});
