// 「무엇이 바뀌었나」의 비교 기준이 main 위에서 자기 자신이 되지 않는지 대조
//
// ## 무엇을 막는가 — 2026-09-10 실측 사고
//
// 젠킨스 빌드 #31(main · SUCCESS · 3.0분)이 **백엔드·프론트 테스트를 한 건도 안 돌렸다.**
//
//     # 백엔드 — 생략 (변경 없음)
//     # 프론트 — 생략 (프론트 변경 없음)
//     # E2E  — 생략 (시나리오 변경 없음)
//
// 88 파일이 바뀐 머지 직후였는데 계산기는 「변경 없음」이라고 했다. 인과는 한 줄이다.
//
//     base   = @{u} 실패(detached) → 'origin/main'
//     merged = merge-base(origin/main, HEAD) = HEAD     ← main 위에서는 자기 자신
//     diff   = HEAD..HEAD = 빈 목록 → []
//
// ★`[]`(변경 없음)과 `null`(기준을 모른다)을 구분해 둔 설계가 여기서 무너졌다.
//   main 에서 「자기 자신과의 차이」는 **없음이 아니라 고장**인데 전자로 접혔다.
//
// ## 왜 하필 지금 터졌나
//
// 종전에는 main 이 무조건 전량이라 이 구멍이 덮여 있었다. `RUN_FULL`/`RUN_DEEP` 를 나눠
// main 을 계산기 범위로 바꾼 순간(2026-09-10) 드러났다. **가드를 좁힌 변경이 가드가
// 기대던 다른 가드를 무너뜨린 것**이라, 좁히는 변경마다 이 짝을 확인해야 한다.
//
// 게다가 젠킨스 잡이 `*/main` 전용이라 **이것이 유일한 CI 다** — 작업 브랜치는 빌드되지
// 않는다. 즉 저장소 전체에서 기계 검증이 0 이었다.
//
// ## 왜 사본을 금지하나
//
// 같은 기준 계산이 **5벌**이었다(TS 4 + Jenkinsfile 1). 다섯이 동시에 같은 방식으로
// 틀렸고 서로를 검사하지 않았다 — 이 저장소가 이름 붙인 「두 목록」의 5중판이다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { resolveDiffBase, changedFiles } from './diff-base.ts';
// ★git 을 spawn 할 때는 이 헬퍼를 거친다. 훅 컨텍스트에서 상속된 `GIT_DIR` 가 자식의 `cwd`
//   를 이기기 때문이다 — 안 거치면 임시 픽스처가 **실저장소**를 건드린다.
//   `git-fixture-isolation.test.ts` 가 저장소 전량에 대해 이 배선을 강제한다(내가 빠뜨려 red).
import { gitFixtureEnv } from './git-fixture-env.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** 임시 저장소에서 git 을 돌린다. 실패하면 즉시 죽는다 — 조용한 픽스처가 가장 나쁘다. */
function git(cwd: string, args: string[]): string {
  const r = spawnSync('git', args, { cwd, encoding: 'utf-8', env: gitFixtureEnv() });
  assert.equal(r.status, 0, `git ${args.join(' ')} 실패\n${r.stderr}`);
  return r.stdout.trim();
}

function commit(cwd: string, file: string, body: string): string {
  fs.mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
  fs.writeFileSync(path.join(cwd, file), body);
  git(cwd, ['add', '-A']);
  git(cwd, ['commit', '-m', `add ${file}`]);
  return git(cwd, ['rev-parse', 'HEAD']);
}

/**
 * 커밋 3개짜리 임시 저장소를 만든다.
 *
 * @returns 저장소 경로와 각 커밋 SHA
 */
function fixture(): { dir: string; a: string; b: string } {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-diff-base-'));
  git(dir, ['init', '-q', '-b', 'main']);
  git(dir, ['config', 'user.email', 't@t']);
  git(dir, ['config', 'user.name', 't']);
  const a = commit(dir, 'a.txt', 'a');
  const b = commit(dir, 'b.txt', 'b');
  return { dir, a, b };
}

const cleanup = (dir: string) => fs.rmSync(dir, { recursive: true, force: true });

describe('비교 기준 — 행동을 잰다', () => {
  test('★★main 위(origin/main == HEAD)에서 「변경 없음」이 아니라 직전 커밋과 비교한다', () => {
    const { dir, a, b } = fixture();
    try {
      // 젠킨스가 main 을 체크아웃한 상태를 그대로 만든다 — detached HEAD + origin/main == HEAD
      git(dir, ['update-ref', 'refs/remotes/origin/main', b]);
      git(dir, ['checkout', '-q', '--detach', b]);

      assert.equal(
        resolveDiffBase(dir),
        a,
        'main 위에서 기준이 직전 커밋이 아니다.\n' +
          '★기준이 HEAD 로 잡히면 차집합이 항상 공집합이라 **아무 테스트도 안 돈다.**\n' +
          '  빌드 #31 이 그래서 3분 만에 초록이었다 — 88 파일이 바뀐 머지 직후에.',
      );
      assert.deepEqual(
        changedFiles(dir),
        ['b.txt'],
        'main 위에서 변경 파일이 직전 커밋 대비로 안 나온다.',
      );
    } finally {
      cleanup(dir);
    }
  });

  test('★작업 브랜치에서는 종전대로 origin/main 과의 분기점이 기준이다 (비-공허 짝)', () => {
    const { dir, a } = fixture();
    try {
      // origin/main 을 a 에 두고 그 위에 브랜치를 판다 — 흔한 작업 상태
      git(dir, ['update-ref', 'refs/remotes/origin/main', a]);
      git(dir, ['checkout', '-q', '-b', 'feat', a]);
      commit(dir, 'c.txt', 'c');

      assert.equal(resolveDiffBase(dir), a, '작업 브랜치 기준이 분기점이 아니다.');
      assert.deepEqual(
        changedFiles(dir),
        ['c.txt'],
        '작업 브랜치에서 변경 파일이 안 나온다 — 이 짝이 없으면 위 테스트가\n' +
          '「항상 HEAD~1」로도 통과해 기준 계산 자체가 공허해진다.',
      );
    } finally {
      cleanup(dir);
    }
  });

  test('★BTS_DIFF_BASE 가 있으면 그것이 이긴다 (CI 가 직전 성공 커밋을 안다)', () => {
    const { dir, a, b } = fixture();
    const saved = process.env['BTS_DIFF_BASE'];
    try {
      git(dir, ['update-ref', 'refs/remotes/origin/main', b]);
      git(dir, ['checkout', '-q', '--detach', b]);
      process.env['BTS_DIFF_BASE'] = a;

      assert.equal(resolveDiffBase(dir), a, 'BTS_DIFF_BASE 를 안 본다.');
    } finally {
      if (saved === undefined) delete process.env['BTS_DIFF_BASE'];
      else process.env['BTS_DIFF_BASE'] = saved;
      cleanup(dir);
    }
  });

  test('★기준을 못 정하면 [] 가 아니라 null 이다 (넓히는 쪽으로 실패한다)', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-diff-base-empty-'));
    try {
      git(dir, ['init', '-q', '-b', 'main']);
      git(dir, ['config', 'user.email', 't@t']);
      git(dir, ['config', 'user.name', 't']);
      commit(dir, 'only.txt', 'only'); // 커밋 1개 — HEAD~1 이 없다
      git(dir, ['update-ref', 'refs/remotes/origin/main', 'HEAD']);
      git(dir, ['checkout', '-q', '--detach', 'HEAD']);

      assert.equal(
        resolveDiffBase(dir),
        null,
        '기준이 없는데 null 이 아니다 — 「모른다」가 「없다」로 접히면 조용한 통과다.',
      );
      assert.equal(changedFiles(dir), null, '기준이 null 인데 파일 목록이 null 이 아니다.');
    } finally {
      cleanup(dir);
    }
  });
});

describe('★사본 금지 — 여섯이 동시에 틀렸다', () => {
  /**
   * 「바뀐 파일 목록」을 스스로 만드는 표식.
   *
   * 기준 계산 자체(`merge-base`)가 아니라 **파일 목록 생산**을 본다. 커밋 제목이나
   * 문서 기준선을 구하려고 `merge-base` 를 쓰는 곳은 이 규칙의 대상이 아니다 —
   * 넓히면 이유가 다른 예외가 쌓이고, 그 예외 목록이 다시 「두 목록」이 된다.
   */
  const SMELL = /--name-only/;

  /**
   * 파일 목록을 만들어도 되는 곳.
   *
   * · `diff-base.ts`    CI 범위 계산기들의 공통 입력
   * · `changed-paths.ts` 티어 하한·스냅샷 판별식의 공통 입력 (기준은 diff-base 에서 받는다)
   */
  const PRODUCERS = new Set(['diff-base.ts', 'changed-paths.ts']);

  test('바뀐 파일 목록을 만드는 비-테스트 파일은 두 곳뿐이다', () => {
    const dir = path.join(ROOT, 'scripts/workflow');
    const offenders = fs
      .readdirSync(dir)
      .filter((f) => (f.endsWith('.ts') || f.endsWith('.mjs')) && !f.includes('.test.'))
      .filter((f) => !PRODUCERS.has(f))
      .filter((f) => {
        const src = fs.readFileSync(path.join(dir, f), 'utf-8');
        // 주석·사고 기록·usage 문자열에는 이름이 나올 수 있다. **코드 줄**만 본다.
        return src
          .split('\n')
          .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l) && !l.includes("'       경로를"))
          .some((l) => SMELL.test(l));
      });

    assert.deepEqual(
      offenders,
      [],
      `바뀐 파일 목록을 스스로 만드는 파일이 있다: ${offenders.join(', ')}\n` +
        '★2026-09-10 에 이 계산이 6벌이었고 **여섯이 동시에 같은 방식으로 틀렸다.**\n' +
        '  main 위에서 전부 기준을 HEAD 로 잡아 CI 가 아무 테스트도 안 돌렸다.\n' +
        '  처방은 한 벌로 모으는 것이다 — `diff-base.ts` 의 changedFiles 를 import 해라.',
    );
  });

  test('★★범위 계산기를 실제로 실행한다 (글자가 아니라 동작)', () => {
    /*
     * ★왜 실행하나. `export { x } from './y.ts'` 는 **재수출**이라 그 파일 안에서 이름을
     *   안 잡는다. 본문이 `changedFiles()` 를 부르면 런타임에 ReferenceError 로 죽는데,
     *   **모듈 밖에서는 `m.changedFiles` 가 멀쩡히 보인다.** 그래서
     *     · 소스 글자를 보는 단언   → 통과
     *     · export 를 보는 단언     → 통과
     *     · 실제로 돌리는 단언      → red    ← 이것만 잡는다
     *   2026-09-10 에 판별식 690건이 초록인 채로 계산기 2벌이 죽어 있었다.
     *
     * ★`push-backend-tests.ts` 는 여기서 안 돌린다 — main 이 gradle 을 부른다.
     */
    const CALCULATORS = [
      'select-test-scope.ts',
      'requires-full-build.ts',
      'changed-e2e-specs.ts',
      'changed-files.ts',
    ];
    for (const script of CALCULATORS) {
      const r = spawnSync(
        process.execPath,
        ['--experimental-strip-types', `scripts/workflow/${script}`],
        { cwd: ROOT, encoding: 'utf-8', env: gitFixtureEnv() },
      );
      // `changed-files.ts` 는 「기준을 모른다」를 3 으로 알린다. 그 외는 0 이어야 한다.
      assert.ok(
        r.status === 0 || (script === 'changed-files.ts' && r.status === 3),
        `${script} 가 실행에서 죽는다 (exit ${r.status}).\n${r.stderr}\n` +
          '★소스를 읽는 단언은 이 고장을 못 본다 — 한 번은 돌려야 한다.',
      );
    }
  });

  test('★changed-paths.ts 도 기준은 diff-base.ts 에서 받는다', () => {
    const src = fs.readFileSync(path.join(ROOT, 'scripts/workflow/changed-paths.ts'), 'utf-8');
    assert.match(
      src,
      /import\s*\{[^}]*resolveDiffBase[^}]*\}\s*from\s*'\.\/diff-base\.ts'/,
      'changed-paths.ts 가 기준을 스스로 잡는다.\n' +
        '★그 파일 머리말이 「입력 계산은 한 벌만 둔다」고 적어 놓고 자기가 두 번째 벌이었다.\n' +
        '  main 위에서 merge-base(origin/main, HEAD) = HEAD 라 검사 대상이 0건이 된다 —\n' +
        '  그 머리말이 경고한 바로 그 고장이다.',
    );
  });

  test('Jenkinsfile 이 자체적으로 diff 기준을 계산하지 않는다', () => {
    const jf = fs.readFileSync(path.join(ROOT, 'Jenkinsfile'), 'utf-8');
    const bad = jf
      .split('\n')
      .filter((l) => !/^\s*(\/\/|\*|#)/.test(l))
      .filter((l) => /git\s+diff\s+--name-only/.test(l));

    assert.deepEqual(
      bad,
      [],
      'Jenkinsfile 이 `git diff --name-only` 로 직접 기준을 잡는다.\n' +
        '★그 줄이 2026-09-10 사고의 다섯 번째 사본이었다(E2E 도메인 계산).\n' +
        '  계산기 스크립트를 부르고 결과만 읽어라.',
    );
  });
});
