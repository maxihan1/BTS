// worktree install 차단 가드가 실제로 막는지 + package.json 이 그것을 부르는지 대조하는 판별식
//
// ## 왜 두 개를 함께 보는가
//
// ① 장치가 판정을 제대로 하는가 — 픽스처로 worktree/일반/비저장소 세 경우를 만들어 본다.
// ② `package.json` 의 `preinstall` 이 그 장치를 부르는가 — 안 부르면 장치는 장식이다.
//
// 이 저장소는 ②만 있는 계약(문구만 대조)이 4개월간 없는 가드를 광고한 전례가 있다(룰 L).
// 반대로 ①만 있으면 「잘 만든 장치가 아무 데도 연결되지 않은」 상태가 된다.
//
// ## 판정 기준을 경로 접두로 삼지 않는 이유도 함께 지킨다
//
// worktree 판정은 `.git` 이 파일인지로 한다 — git 의 규약이라 우리 목록이 아니다.
// `.worktrees/` 같은 경로 접두로 판정하면 위치를 옮기는 순간 조용히 무력해지므로,
// 그 회귀를 막는 단언을 아래에 둔다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isWorktree, main } from './block-worktree-install.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const GUARD = 'scripts/workflow/block-worktree-install.mjs';

/** `.git` 이 파일인 디렉터리 = worktree 흉내 */
function makeWorktreeLike() {
  const d = mkdtempSync(join(tmpdir(), 'bwi-wt-'));
  writeFileSync(join(d, '.git'), 'gitdir: /somewhere/.git/worktrees/x\n');
  return d;
}

/** `.git` 이 디렉터리인 곳 = 일반 체크아웃 흉내 */
function makeNormalCheckout() {
  const d = mkdtempSync(join(tmpdir(), 'bwi-nm-'));
  mkdirSync(join(d, '.git'));
  return d;
}

test('★worktree(.git 이 파일)에서는 install 을 막는다 (EXIT=1)', () => {
  const d = makeWorktreeLike();
  try {
    assert.equal(isWorktree(d), true, '.git 이 파일인데 worktree 로 판정하지 않았다.');
    assert.equal(main(d, {}), 1, 'worktree 인데 install 을 통과시켰다 — main 이 깨진다.');
  } finally {
    rmSync(d, { recursive: true, force: true });
  }
});

test('일반 체크아웃(.git 이 디렉터리)에서는 통과시킨다 (EXIT=0)', () => {
  const d = makeNormalCheckout();
  try {
    assert.equal(isWorktree(d), false);
    assert.equal(main(d, {}), 0, '일반 체크아웃인데 막았다 — 아무도 install 을 못 한다.');
  } finally {
    rmSync(d, { recursive: true, force: true });
  }
});

test('.git 이 없으면 막지 않는다 — 저장소 밖에서 오작동하지 않는다', () => {
  const d = mkdtempSync(join(tmpdir(), 'bwi-no-'));
  try {
    assert.equal(main(d, {}), 0);
  } finally {
    rmSync(d, { recursive: true, force: true });
  }
});

test('탈출구 BTS_ALLOW_WORKTREE_INSTALL 이 있으면 통과시킨다', () => {
  const d = makeWorktreeLike();
  try {
    assert.equal(main(d, { BTS_ALLOW_WORKTREE_INSTALL: '1' }), 0);
  } finally {
    rmSync(d, { recursive: true, force: true });
  }
});

test('★package.json 의 preinstall 이 이 가드를 부른다 (배선 대조)', () => {
  const pkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf-8')) as {
    scripts?: Record<string, string>;
  };
  const pre = pkg.scripts?.preinstall;
  assert.ok(
    typeof pre === 'string' && pre.includes(GUARD),
    `package.json 의 preinstall 이 ${GUARD} 를 부르지 않는다.\n` +
      `현재값: ${pre ?? '(없음)'}\n` +
      `가드가 아무 데도 연결되지 않으면 worktree install 이 그대로 main 을 덮어쓴다.`,
  );
});

test('★판정을 경로 접두로 하지 않는다 (.worktrees 같은 관례에 의존 금지)', () => {
  const src = readFileSync(join(ROOT, GUARD), 'utf-8');
  const body = src
    .split('\n')
    .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*'))
    .join('\n');
  assert.doesNotMatch(
    body,
    /\.worktrees|\.claude\/worktrees/,
    '경로 접두로 worktree 를 판정하고 있다. 그건 우리가 정한 관례라서 위치를 옮기면\n' +
      '조용히 무력해진다 — `.git` 이 파일인지로만 판정해야 한다.',
  );
});
