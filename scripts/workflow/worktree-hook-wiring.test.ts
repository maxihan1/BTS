// worktree 에서 pre-commit 훅이 조용히 죽는 것을 막는 판별식 — 훅 연결 배선 + 로컬/CI 검사 목록 정합
//
// ## 왜 이 파일이 있나
//
// #329 가 `.husky/pre-commit` 에 `build-doc-index.mjs --check` 를 넣어 인덱스 drift 를 커밋
// 시점에 막게 했다. 그런데 그 훅은 **BTS 의 모든 실작업 커밋에서 한 번도 실행되지 않았다.**
//
//   - husky 는 `core.hooksPath` 를 **상대 경로** `.husky/_` 로 설정한다.
//   - `.husky/_/` 는 husky 가 `pnpm install` 때 만드는 shim 이라 `.gitignore` 대상이다.
//   - worktree 는 커밋된 파일만 checkout 하므로 `_/` 가 **없다.**
//   - git 은 hooksPath 가 가리키는 곳에 훅이 없으면 **경고도 에러도 없이 건너뛴다.**
//
// `CLAUDE.md §핵심 패턴` 은 "`.worktrees/<slug>` 안에서만 Edit/Write" 를 강제한다. 즉 훅이
// 지켜야 할 커밋 전량이 훅이 꺼진 곳에서 만들어진다. 2026-08-03 실측으로 확인했다.
//
// 대가는 PR #331 · #333 에서 실측됐다 — 문서 3건(spec/plan/decision)을 만든 커밋부터
// 인덱스 재생성 커밋 전까지 workflow-scripts-ci 가 계속 red 였고, 그때마다 실패 메일이 갔다.
// #333 은 5커밋 구간이 red 였다.
//
// ## 처방이 두 층인 이유
//
// 훅 연결(층 1)만으로도 커밋은 막힌다. 하지만 훅은 `--check` 라 **막기만 하고 고쳐주지 않는다** —
// 막힌 사람이 무엇을 해야 하는지는 스킬이 알려줘야 한다. 그리고 `/bts-impl` 의 PR push 전
// 최종 점검(층 2)이 돌리는 명령 목록에 `pnpm test:workflow` 가 **없었다**. CI 가 돌리는 검사와
// 로컬이 돌리는 검사가 서로를 안 보는 두 목록이었다([[two-lists-never-check-each-other]]).
//
// 그래서 두 층을 함께 못박는다. 한쪽만 있으면 다른 쪽이 조용히 되돌아간다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * 이 판별식이 읽는 입력과, CI 트리거에서 그 입력을 덮는 경로 패턴.
 *
 * 읽기 경로와 트리거 요구를 **한 선언에서 파생**시킨다. 따로 두면 갈라지고, 빠진 쪽만
 * 바꾸는 PR 에서 이 판별식이 0회 실행된 채 통과한다 — #323 에서 실증된 양식이다.
 */
const INPUTS = {
  /** 훅 shim 을 무시하는 규칙. 이 무시가 곧 worktree 에 훅이 없는 이유다. */
  gitignore: { file: '.gitignore', coveredBy: '.gitignore' },
  /** 연결해봐야 훅 본체가 검사를 안 하면 무의미하다. */
  hook: { file: '.husky/pre-commit', coveredBy: '.husky/**' },
  /** 층 1 — worktree 생성 시 훅을 연결하는 곳. */
  start: { file: '.claude/skills/bts-start/SKILL.md', coveredBy: '.claude/skills/**' },
  /** 층 2 — PR push 전 최종 점검이 판별식을 돌리는 곳. */
  impl: { file: '.claude/skills/bts-impl/SKILL.md', coveredBy: '.claude/skills/**' },
  /** 판별식 자신. 이 파일을 고치는 PR 에서도 CI 가 돌아야 한다. */
  self: {
    file: 'scripts/workflow/worktree-hook-wiring.test.ts',
    coveredBy: 'scripts/workflow/**',
  },
} as const;

/** `on:` 아래에서 입력을 걸어야 하는 트리거. 한쪽만 걸면 봉인이 절반만 닫힌다. */
const CI_TRIGGERS = ['pull_request', 'push'] as const;

const DISCRIMINANT_WORKFLOW = '.github/workflows/workflow-scripts-ci.yml';

/**
 * 훅 연결 명령이 반드시 담아야 하는 요소.
 *
 * 문구 하나를 통째로 요구하면 표현만 바꿔도 깨지고, 한 단어만 요구하면 주석에 스쳐도 통과한다.
 * **한 코드블록 안에 세 요소가 모두** 있을 것을 요구해 둘 다 피한다.
 */
const HOOK_LINK_TOKENS = ['ln -s', '.husky/_', '.worktrees/'] as const;

/** `/bts-impl` 의 PR push 전 점검이 반드시 돌려야 하는 명령 — CI 와 같은 판별식이다. */
const IMPL_REQUIRED_COMMAND = 'pnpm test:workflow';

/** 훅 본체가 반드시 수행해야 하는 검사. */
const HOOK_REQUIRED_CHECK = 'build-doc-index.mjs --check';

/** 파서가 고장났을 때 아래 단언들이 공허하게 통과하는 것을 막는 하한. */
const MIN_FENCED_BLOCKS = 3;

function read(input: { file: string }): string {
  return fs.readFileSync(path.join(REPO_ROOT, input.file), 'utf8');
}

/**
 * 마크다운의 ``` 펜스 코드블록 본문만 뽑는다.
 *
 * 산문·주석에 명령어가 스쳐 지나가는 것을 배선으로 오인하지 않기 위함이다.
 */
function fencedBlocks(markdown: string): string[] {
  const blocks: string[] = [];
  let current: string[] | null = null;

  for (const line of markdown.split('\n')) {
    if (line.trimStart().startsWith('```')) {
      if (current === null) current = [];
      else {
        blocks.push(current.join('\n'));
        current = null;
      }
      continue;
    }
    current?.push(line);
  }

  return blocks;
}

/** 한 코드블록 안에 요소 전부가 있는 블록을 찾는다. */
function blockContainingAll(markdown: string, tokens: readonly string[]): string | undefined {
  return fencedBlocks(markdown).find((block) => tokens.every((t) => block.includes(t)));
}

/** `coveredBy` 글롭이 실제로 그 입력 경로를 덮는지. 짝을 잘못 적은 선언을 잡는다. */
function globCovers(glob: string, file: string): boolean {
  if (glob === file) return true;
  if (!glob.endsWith('/**')) return false;

  const base = glob.slice(0, -3);
  return file === base || file.startsWith(`${base}/`);
}

/** 워크플로우의 `on.<trigger>.paths` 블록 원문. 트리거별로 갈라야 절반 봉인을 잡는다. */
function triggerBlock(ci: string, trigger: string): string {
  const bounds: Record<string, [string, string]> = {
    pull_request: ['pull_request:', 'push:'],
    push: ['push:', 'concurrency:'],
  };
  const [from, to] = bounds[trigger];
  return ci.slice(ci.indexOf(from), ci.indexOf(to));
}

describe('worktree 훅 배선 정합', () => {
  /**
   * 비-공허 짝.
   *
   * 입력을 못 읽거나 코드블록을 0개 뽑으면 아래 단언이 전부 공허하게 통과한다.
   * 이 저장소는 "0 이 나오면 판별식을 의심하라" 를 여러 번 겪었다.
   */
  test('입력을 실제로 읽었다 (비-공허 짝)', () => {
    const missing = Object.entries(INPUTS)
      .filter(([, input]) => !fs.existsSync(path.join(REPO_ROOT, input.file)))
      .map(([key, input]) => `${key}. ${input.file}`);

    assert.deepEqual(missing, [], `입력 파일이 없다.\n${missing.join('\n')}`);

    for (const key of ['start', 'impl'] as const) {
      const blocks = fencedBlocks(read(INPUTS[key]));
      assert.ok(
        blocks.length >= MIN_FENCED_BLOCKS,
        `${INPUTS[key].file} 에서 코드블록을 ${blocks.length}개만 뽑았다 — 펜스 파서가 고장났다. ` +
          `0 이면 아래 배선 단언이 전부 공허하게 통과한다.`,
      );
    }
  });

  /**
   * 전제 확인.
   *
   * 아래 훅 연결 처방이 필요한 **이유**가 여전히 살아 있는지 본다. 만약 훅 shim 을 저장소에
   * 커밋하는 방식으로 바꿨다면 worktree 에도 자동으로 따라오므로 연결이 불필요해진다 —
   * 그때는 이 판별식을 통째로 재검토하라는 신호다.
   *
   * 끝 슬래시(`.husky/_/`)는 **디렉토리만** 매칭한다. worktree 가 갖는 것은 심볼릭 링크라
   * 슬래시가 붙어 있으면 매칭되지 않아 매 작업이 untracked 를 달고 다닌다.
   */
  test('.gitignore 가 훅 shim 을 링크까지 무시한다 (끝 슬래시 없음)', () => {
    const lines = read(INPUTS.gitignore)
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.startsWith('.husky/_'));

    assert.ok(
      lines.length > 0,
      `.gitignore 에 '.husky/_' 규칙이 없다.\n\n` +
        `이 규칙이 사라졌다면 훅 shim 이 저장소에 커밋되는 방식으로 바뀐 것이다 — ` +
        `그 경우 worktree 에도 자동으로 따라오므로 이 파일의 훅 연결 처방을 통째로 재검토하라.`,
    );

    assert.deepEqual(
      lines.filter((l) => l.endsWith('/')),
      [],
      `'.husky/_/' 처럼 끝 슬래시가 붙어 있다 — 디렉토리만 매칭하고 심볼릭 링크는 놓친다.\n` +
        `bts-start 가 만드는 훅 링크가 매 worktree 에서 untracked 로 남는다.`,
    );
  });

  test('pre-commit 훅이 문서 인덱스를 실제로 검사한다', () => {
    assert.match(
      read(INPUTS.hook),
      new RegExp(HOOK_REQUIRED_CHECK.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
      `${INPUTS.hook.file} 이 '${HOOK_REQUIRED_CHECK}' 를 돌리지 않는다.\n\n` +
        `훅을 worktree 에 연결해도 본체가 검사를 안 하면 아무것도 막지 못한다 — ` +
        `연결 배선만 초록인 채 drift 가 그대로 커밋된다.`,
    );
  });

  /**
   * ★ 층 1. 이것이 이 파일의 존재 이유다.
   *
   * worktree 생성 직후 훅을 연결하지 않으면 그 worktree 의 **모든 커밋**이 무방비다.
   */
  test('bts-start 가 worktree 에 훅을 연결한다', () => {
    const block = blockContainingAll(read(INPUTS.start), HOOK_LINK_TOKENS);

    assert.ok(
      block !== undefined,
      `${INPUTS.start.file} 에 훅 연결 명령이 없다.\n` +
        `한 코드블록 안에 다음이 모두 있어야 한다: ${HOOK_LINK_TOKENS.join(' · ')}\n\n` +
        `husky 의 core.hooksPath 는 상대 경로 '.husky/_' 이고 그 디렉토리는 .gitignore 대상이라\n` +
        `worktree 에는 존재하지 않는다. git 은 훅을 못 찾으면 **경고 없이 건너뛴다** —\n` +
        `실패가 아니라 침묵이라 아무도 눈치채지 못한다 (2026-08-03 실측).\n` +
        `BTS 의 모든 실작업은 worktree 안에서 이뤄지므로, 연결이 없으면 훅은 장식이다.`,
    );
  });

  /**
   * ★ 층 2. 로컬이 돌리는 검사와 CI 가 돌리는 검사를 같게 만든다.
   *
   * 로컬 목록에 판별식이 없으면 "로컬 초록 → push → CI 빨강" 이 구조적으로 반복된다.
   */
  test('bts-impl 의 PR push 전 점검이 판별식을 돌린다', () => {
    const found = fencedBlocks(read(INPUTS.impl)).some((b) => b.includes(IMPL_REQUIRED_COMMAND));

    assert.ok(
      found,
      `${INPUTS.impl.file} 의 코드블록에 '${IMPL_REQUIRED_COMMAND}' 가 없다.\n\n` +
        `CI(workflow-scripts-ci)는 이 명령으로 판별식 전량을 돌린다. 로컬 최종 점검이 같은 명령을\n` +
        `돌리지 않으면 두 목록이 서로를 안 보게 되고, 로컬 초록이 CI 빨강을 예측하지 못한다.`,
    );
  });

  /**
   * 양성 대조군.
   *
   * 위 단언들이 초록인 이유가 "배선이 있어서" 인지 "탐지 로직이 죽어서" 인지 구분한다.
   */
  test('판별식이 합성 위반을 실제로 잡아낸다 (양성 대조군)', () => {
    const wired = ['설명 문장.', '', '```bash', 'ln -s /repo/.husky/_ .worktrees/x/.husky/_', '```', ''].join('\n');
    const proseOnly = ['산문에서 ln -s 로 .husky/_ 를 .worktrees/ 에 건다고 말만 한다.', '', '```bash', 'echo hi', '```', ''].join('\n');

    assert.ok(
      blockContainingAll(wired, HOOK_LINK_TOKENS) !== undefined,
      '배선된 합성 입력을 못 잡았다 — 탐지 로직이 죽어 있다.',
    );
    assert.equal(
      blockContainingAll(proseOnly, HOOK_LINK_TOKENS),
      undefined,
      '산문에만 있는 언급을 배선으로 오인했다 — 코드블록 한정이 풀렸다.',
    );
    assert.equal(fencedBlocks(wired).length, 1, '펜스 파서가 블록 수를 틀리게 셌다.');
  });

  for (const trigger of CI_TRIGGERS) {
    test(`workflow-scripts-ci 의 ${trigger} 트리거가 이 판별식 입력을 전부 건다`, () => {
      const block = triggerBlock(read({ file: DISCRIMINANT_WORKFLOW }), trigger);

      assert.ok(block.length > 0, `${DISCRIMINANT_WORKFLOW} 에서 ${trigger} 블록을 못 잘랐다.`);

      const required = [...new Set(Object.values(INPUTS).map((i) => i.coveredBy))];
      const missing = required.filter((p) => !block.includes(`'${p}'`));

      assert.deepEqual(
        missing,
        [],
        `${trigger} 트리거에 다음 경로가 없다: ${missing.join(', ')}\n\n` +
          `이 목록은 손으로 유지하지 않는다 — INPUTS 의 coveredBy 에서 파생된다.\n` +
          `빠진 경로만 바꾸는 PR 은 이 판별식을 0회 실행하고 통과한다.`,
      );
    });
  }

  test('선언한 coveredBy 패턴이 실제로 그 입력을 덮는다', () => {
    const mismatched = Object.entries(INPUTS)
      .filter(([, input]) => !globCovers(input.coveredBy, input.file))
      .map(([key, input]) => `${key}. '${input.coveredBy}' 가 '${input.file}' 를 덮지 않는다`);

    assert.deepEqual(
      mismatched,
      [],
      `INPUTS 의 짝 선언이 틀렸다.\n${mismatched.join('\n')}\n\n` +
        `짝을 잘못 적으면 엉뚱한 경로를 요구하면서 통과한다 — 트리거는 초록인데 정작 ` +
        `입력을 바꾸는 PR 에서 판별식이 안 돈다.`,
    );
  });
});
