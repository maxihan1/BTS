// 이 PR 이 바꾼 파일 목록을 한 곳에서 구한다 — 티어 하한·스냅샷 판별식의 공통 입력
//
// ## 왜 판별식마다 각자 구하지 않나
//
// 「무엇이 바뀌었나」를 판별식이 각자 계산하면 한쪽만 base 를 잘못 잡아도 **초록인 채로**
// 검사 대상이 0건이 된다. 지금까지 이 저장소에서 반복된 실패는 「테스트가 깨졌다」가 아니라
// 「테스트가 한 번도 안 돌았다」쪽이었다. 그래서 입력 계산은 한 벌만 둔다.
//
// ## 우선순위
//
// ① `BTS_CHANGED_PATHS` — 줄바꿈으로 나눈 경로 목록. CI 가 diff 스텝 산출물을 그대로 넘길 때.
// ② `BASE_SHA` + `GITHUB_SHA` — 워크플로우가 이미 계산해 둔 두 커밋.
// ③ 로컬 — `origin/main`(없으면 `main`)과 HEAD 의 공통 조상부터 HEAD 까지.
//
// 셋 다 실패하면 **빈 목록을 돌려주지 않고 throw** 한다. 부재를 0건으로 바꾸면 그 순간
// 모든 하한 검사가 공허하게 통과한다.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

export interface ChangedPaths {
  /** 저장소 상대 경로. 삭제된 파일도 포함된다 */
  paths: string[];
  /** 어디서 구했는지 — 실패 메시지에 그대로 싣는다 */
  source: string;
}

/**
 * git 을 한 번 돌린다.
 *
 * ★stderr 를 버리지 않는다. 기본값은 실패 원인을 숨기고, 그러면 「왜 0건인지」를 알 수 없다.
 */
const git = (args: string[]): { ok: boolean; stdout: string; stderr: string } => {
  const run = spawnSync('git', ['-c', 'core.quotePath=false', ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf-8',
  });
  return {
    ok: run.status === 0,
    stdout: run.stdout ?? '',
    stderr: `${run.stderr ?? ''}${run.error ? ` ${run.error.message}` : ''}`,
  };
};

/** NUL 로 나뉜 `--name-only -z` 출력을 경로 배열로 */
const splitNul = (out: string): string[] => out.split('\0').filter((p) => p.length > 0);

/** 존재하는 첫 ref. 하나도 없으면 null */
const firstExistingRef = (refs: string[]): string | null =>
  refs.find((ref) => git(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`]).ok) ?? null;

/**
 * 이 PR 이 바꾼 파일 목록.
 *
 * @throws 세 경로 모두 실패했을 때. 호출자는 이 예외를 삼키면 안 된다.
 */
export const changedPaths = (): ChangedPaths => {
  const injected = process.env.BTS_CHANGED_PATHS;
  if (injected !== undefined && injected.trim().length > 0) {
    return {
      paths: injected.split('\n').map((p) => p.trim()).filter((p) => p.length > 0),
      source: 'BTS_CHANGED_PATHS',
    };
  }

  const base = process.env.BASE_SHA;
  const head = process.env.GITHUB_SHA;
  if (base && head) {
    const diff = git(['diff', '--name-only', '-z', base, head]);
    if (!diff.ok) {
      throw new Error(
        `BASE_SHA..GITHUB_SHA diff 실패 (${base}..${head}): ${diff.stderr.trim()}\n` +
          'fetch-depth: 0 인지, 두 커밋이 러너에 다 있는지 확인하라.',
      );
    }
    return { paths: splitNul(diff.stdout), source: `${base}..${head}` };
  }

  const mainRef = firstExistingRef(['origin/main', 'main']);
  if (mainRef === null) {
    throw new Error(
      'origin/main 도 main 도 없어서 비교 기준을 못 잡았다. ' +
        'CI 라면 BASE_SHA·GITHUB_SHA 를, 로컬이라면 BTS_CHANGED_PATHS 를 넘겨라.',
    );
  }

  const mergeBase = git(['merge-base', mainRef, 'HEAD']);
  if (!mergeBase.ok) {
    throw new Error(`merge-base ${mainRef} HEAD 실패: ${mergeBase.stderr.trim()}`);
  }
  const from = mergeBase.stdout.trim();
  const diff = git(['diff', '--name-only', '-z', from, 'HEAD']);
  if (!diff.ok) {
    throw new Error(`diff ${from}..HEAD 실패: ${diff.stderr.trim()}`);
  }
  return { paths: splitNul(diff.stdout), source: `${mainRef}...HEAD` };
};
