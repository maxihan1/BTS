// worktree 를 지우기 직전에 소실될 것이 있는지 스스로 확인하고, 있으면 지우지 않는 제거기
//
// ## 무엇을 막는가
//
// `git worktree remove --force` 는 미커밋 변경을 **경고 없이 버린다.** 이 저장소는 그 사고를
// 이미 겪었고(메모리 `bts-spec-file-uncommitted-loss`), 소실 1순위는 spec/plan 문서다 —
// 구현물과 달리 다시 만들 수 없고, 없어져도 빌드가 깨지지 않아 한참 뒤에 발견된다.
//
// ## 종전 방어가 공허했던 이유
//
// `bts-merge/SKILL.md` 는 Step 1 에서 `git status --porcelain` 이 비었는지 보고,
// Step 5 에서 `--force` 로 지웠다. 그 `--force` 줄 위 주석이 이랬다.
//
//     # worktree 제거 (Step 1에서 미커밋 0 확인했으므로 안전)
//
// **검사와 파괴가 100줄 떨어져 있고 서로를 검사하지 않는다.** 그 사이에 Step 2~4 가
// 커밋·푸시·리뷰를 하고, 그 과정에서 파일이 새로 생길 수 있다. ktlintFormat 이 만드는
// 포맷 변경이 대표적이다 — Step 1 시점에는 없던 것이 Step 5 시점에는 있다.
// 「아까 봤다」는 지금의 근거가 아니다.
//
// ## 이 스크립트가 대신 하는 것
//
// 지우기 **직전에** 두 가지를 본다. 둘 중 하나라도 걸리면 지우지 않고 종료 코드 2 로 죽는다.
//   ① 미커밋 — `git status --porcelain` (untracked 포함)
//   ② 미푸시 — 업스트림이 있으면 `@{u}..HEAD`, 없으면 「업스트림 없음」자체를 위험으로 본다
//
// ★②를 넣는 이유. 커밋만 하고 푸시를 안 한 상태에서 worktree 를 지우면 브랜치는 남지만
//   그 브랜치가 어느 worktree 것이었는지 추적이 끊긴다. 커밋은 살아 있으니 「소실」은
//   아니지만, 되찾으려면 reflog 를 뒤져야 한다.
//
// ## 종료 코드
//   0  지웠다 (또는 애초에 없었다)
//   2  소실될 것이 있어 **지우지 않았다**
//   1  git 호출 자체가 실패했다 — 판정 불가이므로 지우지 않는다
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { gitFixtureEnv } from './git-fixture-env.mjs';

type Run = { ok: boolean; stdout: string; stderr: string };

const git = (cwd: string, args: string[]): Run => {
  const run = spawnSync('git', ['-c', 'core.quotePath=false', ...args], {
    cwd,
    encoding: 'utf-8',
    env: gitFixtureEnv(),
  });
  return {
    ok: run.status === 0,
    stdout: run.stdout ?? '',
    stderr: `${run.stderr ?? ''}${run.error ? ` ${run.error.message}` : ''}`,
  };
};

export function main(argv: string[] = process.argv.slice(2)): number {
  const target = argv[0];
  if (!target) {
    console.error('사용법: safe-worktree-remove.ts <worktree 경로>');
    return 1;
  }
  const path = resolve(target);

  if (!existsSync(path)) {
    console.log(`✓ ${target} — 이미 없다. 할 일 없음.`);
    return 0;
  }

  // ① 미커밋. untracked 를 포함하는 것이 요점이다 — 새로 만든 spec 문서가 정확히 그 상태다.
  const status = git(path, ['status', '--porcelain', '--untracked-files=all']);
  if (!status.ok) {
    console.error(`❌ git status 실패 — 판정 불가라 지우지 않는다.\n${status.stderr}`);
    return 1;
  }
  const dirty = status.stdout.split('\n').filter((l) => l.trim().length > 0);

  // ② 미푸시. 업스트림 부재도 위험으로 본다.
  const upstream = git(path, ['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}']);
  let unpushed: string[] = [];
  let noUpstream = false;
  if (upstream.ok) {
    const ahead = git(path, ['log', '--oneline', '@{u}..HEAD']);
    if (!ahead.ok) {
      console.error(`❌ git log 실패 — 판정 불가라 지우지 않는다.\n${ahead.stderr}`);
      return 1;
    }
    unpushed = ahead.stdout.split('\n').filter((l) => l.trim().length > 0);
  } else {
    noUpstream = true;
  }

  if (dirty.length > 0 || unpushed.length > 0 || noUpstream) {
    console.error(`🛑 ${target} 을 지우지 않았다 — 지웠으면 아래가 사라진다.\n`);
    if (dirty.length > 0) {
      console.error(`미커밋 ${dirty.length}건 (untracked 포함)`);
      for (const l of dirty) console.error(`  ${l}`);
      console.error('');
    }
    if (unpushed.length > 0) {
      console.error(`미푸시 커밋 ${unpushed.length}건`);
      for (const l of unpushed) console.error(`  ${l}`);
      console.error('');
    }
    if (noUpstream) {
      console.error('업스트림 브랜치가 없다 — 푸시된 적이 없는 브랜치다.\n');
    }
    console.error('커밋·푸시를 끝낸 뒤 다시 부르라. spec/plan 문서가 소실 1순위다.');
    return 2;
  }

  const removed = git(path, ['worktree', 'remove', '--force', path]);
  if (!removed.ok) {
    // worktree 안에서 자기 자신을 지울 수 없는 경우가 있어 상위에서 한 번 더 시도한다.
    const parent = git(process.cwd(), ['worktree', 'remove', '--force', path]);
    if (!parent.ok) {
      console.error(`❌ worktree 제거 실패.\n${removed.stderr}\n${parent.stderr}`);
      return 1;
    }
  }
  console.log(`✓ ${target} 제거 — 미커밋 0 · 미푸시 0 을 직전에 확인했다.`);
  return 0;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? '');
if (isMain) process.exit(main());
