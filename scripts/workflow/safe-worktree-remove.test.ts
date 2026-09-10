// safe-worktree-remove 가 실제로 막는지 + bts-merge 가 그것을 부르는지 대조하는 판별식
//
// ## 두 가지를 함께 본다 — 하나만으로는 부족하다
//
// ① **장치가 실제로 막는가** — 픽스처 저장소를 만들어 미커밋·미푸시를 넣고 종료 코드를 본다.
// ② **하네스가 그 장치를 부르는가** — `bts-merge/SKILL.md` 가 `--force` 를 직접 쓰지 않고
//    이 스크립트를 부르는지 대조한다.
//
// ①만 있으면 「잘 만든 장치가 아무 데도 연결되지 않은」 상태가 된다. 이 저장소가
// `select-test-scope` 에서 겪은 그대로다. ②만 있으면 문자열 계약이라 장치가 텅 비어도 초록이다.
// 룰 L 이 정확히 그 형태였다 — 「부르라」고 적힌 것만 검사하고 실물은 아무도 안 봤다.
//
// ## 픽스처를 쓰는 이유
//
// 실저장소에서 돌리면 판별식이 실제 worktree 를 지울 수 있다. 임시 저장소를 만들어
// 그 안에서만 논다. `gitFixtureEnv` 가 필수인 것도 이 때문이다 — 훅 안에서 돌 때
// `GIT_DIR` 가 `cwd` 를 이겨서 픽스처가 실저장소에 걸린다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import { gitFixtureEnv } from './git-fixture-env.mjs';
// ★`main` 을 임포트한다 — spawn 만으로는 부족하다.
//
// `git-fixture-isolation.test.ts` 는 「git 을 spawn 하는 파일」과 「어느 테스트의 자식으로
// 도는 파일」의 차집합이 0인지 본다. 그 판정에서 **자식 후보는 테스트 파일뿐**이라,
// 스크립트를 자식 프로세스로만 부르면 그 스크립트 자체는 감시망 밖에 남는다.
// 임포트로 이어야 「이 파일이 스크럽을 잃으면 red」가 성립한다.
import { main } from './safe-worktree-remove.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const SCRIPT = join(HERE, 'safe-worktree-remove.ts');

const git = (cwd: string, args: string[]) =>
  spawnSync('git', args, { cwd, encoding: 'utf-8', env: gitFixtureEnv() });

/** 원격까지 갖춘 픽스처를 만들고 worktree 하나를 붙여 돌려준다. */
function makeFixture() {
  const base = mkdtempSync(join(tmpdir(), 'swr-'));
  const repo = join(base, 'repo');
  const remote = join(base, 'remote.git');
  const wt = join(base, 'wt');
  git(base, ['init', '-q', 'repo']);
  git(repo, ['config', 'user.email', 't@t']);
  git(repo, ['config', 'user.name', 't']);
  writeFileSync(join(repo, 'a.txt'), 'hi\n');
  git(repo, ['add', '-A']);
  git(repo, ['commit', '-qm', 'init']);
  git(base, ['init', '-q', '--bare', 'remote.git']);
  git(repo, ['worktree', 'add', '-q', wt, '-b', 'feat']);
  git(wt, ['remote', 'add', 'origin', remote]);
  git(wt, ['push', '-q', '-u', 'origin', 'feat']);
  return { base, repo, wt };
}

const run = (target: string) =>
  spawnSync(process.execPath, ['--experimental-strip-types', SCRIPT, target], {
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });

test('깨끗한 worktree 는 지운다 (EXIT=0)', () => {
  const { base, wt } = makeFixture();
  try {
    const r = run(wt);
    assert.equal(r.status, 0, `깨끗한데 안 지웠다.\n${r.stdout}\n${r.stderr}`);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test('★untracked 파일이 있으면 지우지 않는다 (EXIT=2) — spec 문서 소실 사고의 형태', () => {
  const { base, wt } = makeFixture();
  try {
    writeFileSync(join(wt, 'docs-spec.md'), '# spec\n');
    const r = run(wt);
    assert.equal(r.status, 2, `untracked 파일이 있는데 지웠다 — 영구 소실이다.\n${r.stdout}`);
    assert.match(r.stderr, /docs-spec\.md/, '무엇이 사라질 뻔했는지 이름을 보여줘야 한다.');
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test('★미푸시 커밋이 있으면 지우지 않는다 (EXIT=2)', () => {
  const { base, wt } = makeFixture();
  try {
    writeFileSync(join(wt, 'b.txt'), 'x\n');
    git(wt, ['add', '-A']);
    git(wt, ['-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-qm', '미푸시']);
    const r = run(wt);
    assert.equal(r.status, 2, `미푸시 커밋이 있는데 지웠다.\n${r.stdout}`);
    assert.match(r.stderr, /미푸시/, '미푸시라는 사유를 보여줘야 한다.');
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test('없는 경로는 조용히 성공한다 (EXIT=0) — 재실행이 안전해야 한다', () => {
  const r = run(join(tmpdir(), 'swr-does-not-exist-xyz'));
  assert.equal(r.status, 0, '이미 없는 것을 지우라 했는데 실패했다.');
});

test('main 을 직접 불러도 같은 판정을 낸다 (임포트 경로)', () => {
  assert.equal(main([join(tmpdir(), 'swr-does-not-exist-xyz')]), 0);
  assert.equal(main([]), 1, '인자가 없으면 사용법을 내고 1 이어야 한다.');
});

test('★bts-merge Step 5 가 이 스크립트를 부른다 (배선 대조)', () => {
  const skill = readFileSync(join(ROOT, '.claude/skills/bts-merge/SKILL.md'), 'utf-8');
  assert.match(
    skill,
    /scripts\/workflow\/safe-worktree-remove\.ts/,
    'bts-merge 가 이 장치를 부르지 않는다. 잘 만든 장치가 아무 데도 연결되지 않은 상태다.',
  );
});

test('★bts-merge 가 worktree 를 --force 로 직접 지우지 않는다', () => {
  const skill = readFileSync(join(ROOT, '.claude/skills/bts-merge/SKILL.md'), 'utf-8');
  // 스크립트를 부르는 줄과 설명문은 통과시키고, 맨몸 `git worktree remove --force` 만 잡는다.
  const bare = skill
    .split('\n')
    .filter((l) => /^\s*git worktree remove\s+--force/.test(l));
  assert.deepEqual(
    bare,
    [],
    '맨몸 `git worktree remove --force` 가 남아 있다. 그 줄은 직전 검사 없이 파괴한다 —\n' +
      '종전 주석은 「Step 1에서 미커밋 0 확인했으므로 안전」이었고, 검사와 파괴가 100줄 떨어져 있었다.',
  );
});
