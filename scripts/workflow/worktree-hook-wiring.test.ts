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
  /**
   * 훅이 **부르는** 설정. 훅 본체와 한 몸으로 봐야 한다.
   *
   * 훅 본체에서 pnpm 래퍼를 걷어내도 이 파일이 래퍼를 쓰면 커밋은 같은 자리에서 죽는다.
   * 실제로 2026-08-04 에 그 절반 봉합 상태가 났다 — 본체는 고쳐졌는데 이 파일이 그대로라
   * `apps/web/**` 를 건드리는 커밋만 골라서 죽는, 더 찾기 어려운 형태였다.
   *
   * ★이 파일은 **존재 자체가 배선**이다. 아래 `lintStagedWeb` 주석의 `hasMultipleConfigs`
   * 설명을 반드시 함께 읽을 것 — 지우면 프론트 lint 의 cwd 가 조용히 루트로 되돌아간다.
   */
  lintStaged: { file: '.lintstagedrc.json', coveredBy: '.lintstagedrc.json' },
  /**
   * 프론트 lint 를 **`apps/web` cwd 에서** 돌리는 설정.
   *
   * ## 왜 따로 있나
   *
   * `apps/web/eslint.config.js` 의 예외 목록(PR22 원시 `<button>` 19파일)은 `'src/routes/…'`
   * 같은 **상대 패턴**이다. ESLint flat config 는 상대 `files` 패턴을 **cwd 기준**으로 푼다.
   * 루트에서 돌리면 `apps/web/src/routes/…` 와 안 맞아 예외가 **한 건도 적용되지 않고**,
   * 그 19파일을 건드리는 커밋만 골라서 죽는다. CI(`eslint src`, cwd=`apps/web`)는 통과하므로
   * 훅과 CI 가 서로 다른 판정을 하는, 또 하나의 두-목록 결함이었다 (2026-08-04 실측).
   *
   * ## ★지우면 안 되는 이유 — `hasMultipleConfigs`
   *
   * lint-staged 는 설정이 **2개 이상일 때만** 각 그룹을 설정 파일의 디렉토리에서 실행한다.
   * 하나뿐이면 프로세스 cwd(=저장소 루트)를 그대로 쓴다 (`runAll.js` 의
   * `groupCwd = hasExplicitCwd || !hasMultipleConfigs ? cwd : path.dirname(configPath)`).
   * 즉 루트 설정을 지워 이 파일만 남기면 cwd 가 루트로 돌아가 **결함이 부활한다** —
   * 2026-08-04 샌드박스 실측으로 확인했다. 두 파일은 함께 있어야 의미가 있다.
   */
  lintStagedWeb: {
    file: 'apps/web/.lintstagedrc.json',
    coveredBy: 'apps/web/.lintstagedrc.json',
  },
  /** 층 1 — worktree 생성 시 훅을 연결하는 곳. */
  start: { file: '.claude/skills/bts-start/SKILL.md', coveredBy: '.claude/skills/**' },
  /** 층 2 — PR push 전 최종 점검이 판별식을 돌리는 곳. */
  impl: { file: '.claude/skills/bts-impl/SKILL.md', coveredBy: '.claude/skills/**' },
  /**
   * 판별식 명령의 **정본**. 스킬이 적는 worktree 대체 명령은 이 스크립트와 짝이어야 한다.
   *
   * 여기 글로브가 바뀌었는데 스킬이 안 따라오면 로컬이 CI 보다 **적게** 돌면서 초록이 된다 —
   * 이 저장소의 지배 결함 양식(두 목록이 서로를 확인하지 않는다)이다.
   */
  pkg: { file: 'package.json', coveredBy: 'package.json' },
  /** 판별식 자신. 이 파일을 고치는 PR 에서도 CI 가 돌아야 한다. */
  self: {
    file: 'scripts/workflow/worktree-hook-wiring.test.ts',
    coveredBy: 'scripts/workflow/**',
  },
} as const;

/**
 * worktree 에서 실행 불가능한 것 — pnpm 래퍼 호출 **전체**.
 *
 * worktree 의 `node_modules` 는 main 을 가리키는 심볼릭 링크다. pnpm 11 의 실행 전
 * 의존성 검사가 경로 불일치를 감지해 `pnpm install` 을 자동 트리거하고,
 * TTY 가 없어 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 중단된다 (2026-08-04 실측).
 *
 * ## 왜 하위 명령 열거가 아니라 낱말 금지인가
 *
 * 처음엔 `['pnpm exec', 'pnpm run', 'pnpm install']` 로 적었다. 그런데 `.lintstagedrc.json`
 * 의 실제 명령은 `pnpm --filter @bts/web exec eslint` 였고, 사이에 낀 플래그 때문에
 * **셋 다 안 걸렸다.** 판별식은 초록인데 결함은 살아 있는, 이 저장소가 반복해온 형태다.
 * 하위 명령 열거는 플래그가 하나만 끼어도 뚫린다 — 그래서 `pnpm` 이라는 낱말 자체를 막는다.
 *
 * 경계를 `\b` 가 아니라 문자 클래스로 잡는 이유. `\bpnpm\b` 는 `node_modules/.pnpm/` 같은
 * **정상 경로**까지 잡아 오탐이 난다. 명령어가 올 수 있는 자리(줄머리 · 공백 · 파이프 ·
 * 경로 구분자 뒤)만 본다.
 */
const PNPM_WRAPPER = /(?:^|[\s;|&(]|\/)pnpm(?:[\s;|&)]|$)/;

/** pnpm 래퍼를 대신하는 처방 — 실패 메시지에 그대로 실어 막힌 사람이 바로 고치게 한다. */
const PNPM_WRAPPER_REMEDY =
  `처방. 바이너리를 직접 부른다 — 'node_modules/.bin/<도구>' 또는\n` +
  `      'apps/web/node_modules/.bin/<도구>' (루트에 없는 도구는 후자에만 있다).\n` +
  `      워크스페이스 필터(--filter)로 cwd 를 옮기던 명령은 '--config <경로>' 로 대체한다.`;

/** worktree 가 심볼릭 링크로 갖는 경로 — 끝 슬래시를 붙이면 링크를 놓친다. */
const SYMLINKED_IGNORE_PATHS = ['node_modules', 'apps/web/node_modules', '.husky/_'] as const;

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

/**
 * 최종 점검이 반드시 담아야 하는 **종료 코드 규율**의 요소 (2026-08-12 · TODOS `1707`).
 *
 * ## 왜 이것이 층 2 에 속하나
 *
 * `1707`(전 스위트에서 `pnpm test` 가 간헐적으로 exit≠0)의 실제 구멍은 **코드가 아니라
 * 읽는 쪽**이다. 장부가 그렇게 특정했다.
 *
 * > vitest 는 `cli-api…:13897-13899` 에서 이미 `process.exitCode=1` 을 세우고
 * > `frontend-ci.yml:106-107` 이 그 종료 코드를 그대로 잡 성패로 쓴다.
 * > **실제 구멍은 CI 배선이 아니라 「Tests N passed 만 읽고 초록으로 보고하는」 사람/에이전트 쪽**이다.
 *
 * 실제 사고가 기록돼 있다 — FR-UX-09 F2 세션 체크포인트가 그렇게 잘못 적혔다가 게이트 2
 * 재검증에서 교정됐다. 「Tests 8378 passed」와 「EXIT=1」이 **같은 실행에서 동시에 참**이라
 * 통과 건수만 보면 초록으로 읽힌다.
 *
 * ## ★파이프가 종료 코드를 삼킨다
 *
 * `pnpm test 2>&1 | tail -20` 처럼 파이프에 태우면 셸이 보고하는 종료 코드는 **파이프
 * 마지막 명령(tail)의 것**이다. 원래 재려던 값이 사라진다. 에이전트가 출력을 줄여 읽으려
 * 할 때 정확히 이 형태를 쓰기 때문에 위험이 크다.
 *
 * ## 왜 낱말 3개를 함께 요구하나
 *
 * 문구 하나를 통째로 요구하면 표현만 바꿔도 깨지고, 한 낱말만 요구하면 무관한 산문에
 * 스쳐도 통과한다. `HOOK_LINK_TOKENS` 와 같은 처방이다.
 */
const EXIT_CODE_DISCIPLINE_TOKENS = ['종료 코드', '파이프', 'passed'] as const;

/** 훅 본체가 반드시 수행해야 하는 검사. */
const HOOK_REQUIRED_CHECK = 'build-doc-index.mjs --check';

/** 파서가 고장났을 때 아래 단언들이 공허하게 통과하는 것을 막는 하한. */
const MIN_FENCED_BLOCKS = 3;

function read(input: { file: string }): string {
  return fs.readFileSync(path.join(REPO_ROOT, input.file), 'utf8');
}

/** 한 줄의 실행 명령과 그 출처. 실패 메시지가 어느 파일 어디인지 바로 가리키게 한다. */
interface ExecutionLine {
  where: string;
  command: string;
}

/** 훅 본체에서 실제로 실행되는 줄만. 주석(`#`)과 빈 줄은 실행되지 않으므로 뺀다. */
function hookBodyLines(): ExecutionLine[] {
  return read(INPUTS.hook)
    .split('\n')
    .map((line, i) => ({ where: `${INPUTS.hook.file}:${i + 1}`, command: line.trim() }))
    .filter(({ command }) => command.length > 0 && !command.startsWith('#'));
}

/**
 * 훅이 부르는 lint-staged 설정 **전부**. 루트 하나만 보면 절반 봉합이 통과한다.
 *
 * 설정이 여러 벌인 이유는 `INPUTS.lintStagedWeb` 주석 참조.
 */
const LINT_STAGED_INPUTS = [INPUTS.lintStaged, INPUTS.lintStagedWeb] as const;

/** 설정이 2개 미만이면 lint-staged 가 설정 디렉토리 cwd 를 쓰지 않는다 (`hasMultipleConfigs`). */
const MIN_LINT_STAGED_CONFIGS = 2;

/**
 * lint-staged 설정이 커밋마다 실행하는 명령. 키는 glob 이고 값이 명령이다.
 *
 * JSON 은 주석을 못 다니, 이 파일이 왜 pnpm 을 못 쓰는지는 여기와 아래 실패 메시지에만 남는다.
 */
function lintStagedCommands(input: { file: string }): ExecutionLine[] {
  const parsed: unknown = JSON.parse(read(input));
  if (typeof parsed !== 'object' || parsed === null) return [];

  return Object.entries(parsed as Record<string, unknown>).flatMap(([glob, value]) => {
    const commands = typeof value === 'string' ? [value] : Array.isArray(value) ? value : [];
    return commands
      .filter((c): c is string => typeof c === 'string')
      .map((command) => ({ where: `${input.file}  "${glob}"`, command }));
  });
}

/**
 * 훅이 커밋마다 실행하는 명령 **전부** — 본체 + 본체가 부르는 설정.
 *
 * 두 파일을 한 목록으로 합치는 것이 핵심이다. 따로 검사하면 한쪽만 고친 절반 봉합이
 * 통과한다 — 훅이 살아나도 훅이 부르는 설정이 래퍼를 쓰면 결국 같은 자리에서 죽는다.
 * 두 목록이 서로를 검사하지 않는 것이 이 저장소의 지배적 결함 양식이다.
 */
function hookExecutionLines(): ExecutionLine[] {
  return [...hookBodyLines(), ...LINT_STAGED_INPUTS.flatMap(lintStagedCommands)];
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

    // 실행선 추출이 0건이면 pnpm 래퍼 단언이 통째로 공허해진다. 출처별로 따로 센다 —
    // 합계만 보면 한쪽이 0 이어도 다른 쪽 개수에 가려진다. lint-staged 설정이 여러 벌이므로
    // **설정 파일마다** 따로 센다. 한 벌이 비면 그 벌의 명령은 검사 대상에서 통째로 빠진다.
    const executionSources: (readonly [string, ExecutionLine[]])[] = [
      [INPUTS.hook.file, hookBodyLines()],
      ...LINT_STAGED_INPUTS.map((input) => [input.file, lintStagedCommands(input)] as const),
    ];

    for (const [source, lines] of executionSources) {
      assert.ok(
        lines.length > 0,
        `${source} 에서 실행 명령을 0건 뽑았다 — 추출기가 고장났거나 파일 형식이 바뀌었다.\n` +
          `0 이면 'worktree 에서 실행 가능한 명령만 쓴다' 단언이 검사할 것 없이 통과한다.`,
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

  test('.gitignore 가 worktree 심볼릭 링크를 전부 무시한다 (끝 슬래시 없음)', () => {
    const lines = read(INPUTS.gitignore)
      .split('\n')
      .map((l) => l.trim());

    const bad = SYMLINKED_IGNORE_PATHS.filter((p) => lines.includes(`${p}/`));

    assert.deepEqual(
      bad,
      [],
      `다음 규칙이 끝 슬래시로 적혀 있다: ${bad.join(', ')}\n\n` +
        `끝 슬래시는 **디렉토리만** 매칭한다. worktree 가 갖는 것은 심볼릭 링크라 매칭되지 않아\n` +
        `매 작업이 untracked 를 달고 다닌다. #329 가 '.husky/_' 에 대해 같은 결함을 고쳤다 —\n` +
        `node_modules 갈래도 같은 규칙을 따라야 한다.`,
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
   * ★ 훅 본체와 lint-staged 설정을 **한 목록으로** 검사한다.
   *
   * 훅 본체만 보면 절반 봉합이 통과한다. 2026-08-04 에 실제로 그랬다 — `.husky/pre-commit`
   * 의 `pnpm exec` 는 걷어냈는데 그 훅이 부르는 `.lintstagedrc.json` 이 여전히
   * `pnpm --filter @bts/web exec eslint` 였다. `apps/web/**` 파일이 staged 인 커밋에서만
   * 죽으므로, 그 경로를 밟지 않는 커밋만 하는 동안에는 결함이 보이지도 않았다.
   */
  test('pre-commit 훅이 worktree 에서 실행 가능한 명령만 쓴다', () => {
    const offending = hookExecutionLines()
      .filter(({ command }) => PNPM_WRAPPER.test(command))
      .map(({ where, command }) => `${where}\n    ${command}`);

    assert.deepEqual(
      offending,
      [],
      `훅이 worktree 에서 실행 불가능한 명령을 쓴다.\n${offending.join('\n')}\n\n` +
        `BTS 의 모든 실작업은 worktree 안에서 이뤄진다. 훅을 연결해도(층 1) 실행선이 pnpm\n` +
        `래퍼를 부르면 매 커밋이 ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY 로 죽는다 —\n` +
        `결국 --no-verify 로 우회하게 되고 훅은 다시 장식이 된다.\n` +
        `훅 본체와 lint-staged 설정을 함께 보는 이유. 한쪽만 고치면 나머지 한쪽이 같은 자리에서\n` +
        `죽인다. 설정 쪽은 'apps/web/**' 가 staged 인 커밋에서만 터져 더 늦게 발견된다.\n` +
        PNPM_WRAPPER_REMEDY,
    );
  });

  /**
   * ★ 프론트 lint 의 cwd 를 지키는 유일한 조건.
   *
   * lint-staged 는 **설정이 2개 이상일 때만** 각 그룹을 그 설정 파일의 디렉토리에서 돌린다.
   * 하나로 줄면 프로세스 cwd(= 저장소 루트)로 되돌아가고, `apps/web/eslint.config.js` 의
   * 상대 예외 패턴이 전부 어긋나 PR22 예외 19파일을 건드리는 커밋만 골라서 죽는다.
   *
   * 되돌아감이 **조용하다**는 것이 핵심이다 — 에러가 아니라 "예외가 안 걸리는" 형태라
   * 다른 파일만 만지는 동안에는 아무도 눈치채지 못한다. 그래서 개수를 못박는다.
   */
  test('lint-staged 설정이 2벌 이상이다 (설정 디렉토리 cwd 의 성립 조건)', () => {
    const present = LINT_STAGED_INPUTS.filter((input) =>
      fs.existsSync(path.join(REPO_ROOT, input.file)),
    ).map((input) => input.file);

    assert.ok(
      present.length >= MIN_LINT_STAGED_CONFIGS,
      `lint-staged 설정이 ${present.length}벌뿐이다: ${present.join(', ') || '(없음)'}\n\n` +
        `lint-staged 는 설정이 2벌 이상일 때만 각 그룹을 설정 파일의 디렉토리에서 실행한다\n` +
        `(runAll.js: groupCwd = hasExplicitCwd || !hasMultipleConfigs ? cwd : dirname(configPath)).\n` +
        `한 벌로 줄면 cwd 가 저장소 루트로 돌아가고, apps/web/eslint.config.js 의 상대 예외\n` +
        `패턴('src/routes/…')이 어긋나 PR22 예외 파일을 건드리는 커밋이 전부 막힌다.\n` +
        `루트 설정을 지우고 apps/web 것만 남기는 것이 정확히 이 함정이다 (2026-08-04 실측).`,
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
   * ★★ 층 1 확장. 훅을 연결해도 **실행선이 없으면** 첫 커밋에서 죽는다 (2026-08-12 실측).
   *
   * `.gitignore` 는 worktree 가 `node_modules` · `apps/web/node_modules` · `.husky/_` **세 개**를
   * 심볼릭으로 갖는다고 이미 선언한다(`SYMLINKED_IGNORE_PATHS`). 그런데 `/bts-start` 는
   * `.husky/_` **하나만** 걸었다. 그 상태에서 첫 커밋을 하면 훅 본체
   * `node_modules/.bin/lint-staged` 가 **없어서** `No such file or directory` 로 죽는다 —
   * 훅은 연결됐는데 훅이 부르는 것이 없는, 층 1 과 층 2 사이의 구멍이다.
   *
   * 두 목록을 한 상수로 묶어 검사한다. 선언(.gitignore)과 생성(bts-start)이 어긋나면 red.
   */
  test('★bts-start 가 worktree 에 필요한 심볼릭을 전부 건다 (선언과 생성의 짝맞춤)', () => {
    const start = read(INPUTS.start);
    const linkLines = start
      .split('\n')
      .filter((l) => l.includes('ln -s'))
      .join('\n');

    const missing = SYMLINKED_IGNORE_PATHS.filter((target) => !linkLines.includes(target));

    assert.deepEqual(
      missing,
      [],
      `${INPUTS.start.file} 가 worktree 에 걸지 않는 심볼릭이 있다: ${missing.join(', ')}\n\n` +
        `.gitignore 는 이 경로들이 worktree 에서 심볼릭이라고 선언한다(SYMLINKED_IGNORE_PATHS).\n` +
        `선언만 있고 생성이 없으면 그 worktree 의 첫 커밋이 훅 본체를 못 찾아 죽는다 —\n` +
        `'node_modules/.bin/lint-staged: No such file or directory' (2026-08-12 실측).\n` +
        `훅 연결(층 1)이 통과해도 이 구멍은 별도로 열려 있으므로 따로 못박는다.`,
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
   * ★★ 층 2 확장. 그 명령이 **worktree 에서 실제로 돌아가야** 층 2 가 성립한다 (2026-08-12).
   *
   * 위 단언은 `pnpm test:workflow` 가 문서에 있는지만 본다. 그런데 BTS 의 모든 실작업은
   * worktree 안에서 이뤄지고, 거기서는 그 명령이 **실행 자체가 안 된다** — pnpm 이 심볼릭
   * `node_modules` 를 보고 의존성 검사를 돌려 `pnpm install` 을 트리거하고
   * `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다.
   *
   * ★이 저장소는 이미 같은 결론에 두 번 도달해 있다 — `.husky/pre-commit` 은 주석에
   *   「`pnpm exec` 를 쓰지 않는다」고 적고 실제로 바이너리를 직접 부르며, 위
   *   `PNPM_WRAPPER` 단언이 그것을 강제한다. **훅만 고쳐졌고 스킬 문서는 그 사정거리 밖**이라
   *   최종 점검 절차가 여전히 돌지 않는 명령을 지시하고 있었다.
   *
   * 그래서 대체 명령을 함께 적게 하되, 그 명령이 **정본과 같은 파일 목록**을 돌아야 한다.
   * 글로브를 `package.json` 에서 직접 읽어 대조하므로 한쪽만 바뀌면 red 다.
   */
  test('★★bts-impl 이 worktree 에서 실제로 돌아가는 대체 명령을 함께 적는다 (글로브 짝맞춤)', () => {
    const pkg = JSON.parse(read(INPUTS.pkg)) as { scripts?: Record<string, string> };
    const script = pkg.scripts?.['test:workflow'];

    assert.ok(
      typeof script === 'string' && script.length > 0,
      `${INPUTS.pkg.file} 에 'test:workflow' 스크립트가 없다 — 아래 대조가 공허해진다.`,
    );

    // 정본 스크립트가 도는 파일 목록(따옴표로 감싼 글로브). 없으면 대조가 성립하지 않는다.
    const globs = [...script.matchAll(/'([^']+)'/g)].map((m) => m[1]);
    assert.ok(
      globs.length > 0,
      `'test:workflow' 에서 글로브를 뽑지 못했다 (${script}) — 파서가 고장나면 아래가 공허하다.`,
    );

    const impl = read(INPUTS.impl);
    const missing = globs.filter((g) => !impl.includes(g));

    assert.deepEqual(
      missing,
      [],
      `${INPUTS.impl.file} 의 대체 명령이 정본과 같은 파일 목록을 돌지 않는다.\n` +
        `누락된 글로브: ${missing.join(' · ')}\n` +
        `정본(package.json test:workflow): ${script}\n\n` +
        `worktree 에서는 'pnpm test:workflow' 가 실행되지 않는다 — pnpm 이 심볼릭 node_modules 를\n` +
        `보고 install 을 트리거해 ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY 로 죽는다.\n` +
        `그래서 node 를 직접 부르는 대체 명령을 함께 적어야 하고, 그 명령은 정본과 **같은 목록**을\n` +
        `돌아야 한다. 한쪽만 바뀌면 로컬이 CI 보다 적게 돌면서 초록이 된다.\n` +
        PNPM_WRAPPER_REMEDY,
    );
  });

  /**
   * ★ 층 2 확장. 「통과 건수 ≠ 종료 코드」를 읽는 쪽에서 막는다 (TODOS `1707`).
   *
   * 이 항목의 누출 경로는 코드에 없다 — 2026-08-12 전수 조사에서 `.catch` 없는
   * `mutateAsync` **0건**, mutation 콜백의 `throw` **0건**이었다. 남은 구멍은 사람/에이전트가
   * 「Tests N passed」만 읽고 초록으로 보고하는 쪽이고, 그건 스킬 문서가 지켜야 한다.
   */
  test('★bts-impl 이 종료 코드 규율을 명시한다 (통과 건수로 판정 금지)', () => {
    const impl = read(INPUTS.impl);
    const missing = EXIT_CODE_DISCIPLINE_TOKENS.filter((t) => !impl.includes(t));

    assert.deepEqual(
      missing,
      [],
      `${INPUTS.impl.file} 에 종료 코드 규율이 없다. 빠진 요소. ${missing.join(' · ')}\n\n` +
        `「Tests N passed」와 「EXIT=1」은 **같은 실행에서 동시에 참**일 수 있다(TODOS 1707).\n` +
        `그리고 'pnpm test | tail' 처럼 파이프에 태우면 셸이 보는 종료 코드는 파이프 마지막\n` +
        `명령의 것이라 원래 재려던 값이 사라진다. 최종 점검 절차가 그 둘을 명시해야 한다.`,
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

    // pnpm 탐지. 아래 첫 줄이 실제로 놓쳤던 문자열이다 — 하위 명령을 열거하던 시절의
    // 'pnpm exec' 는 사이에 낀 --filter 때문에 이걸 못 잡았고, 그 갭이 결함을 살려뒀다.
    for (const caught of [
      'pnpm --filter @bts/web exec eslint --max-warnings 0 --cache',
      'pnpm exec lint-staged',
      'npx pnpm install',
    ]) {
      assert.ok(PNPM_WRAPPER.test(caught), `pnpm 래퍼를 놓쳤다: ${caught}`);
    }

    // 오탐 대조. 정상 처방과 pnpm 가상 스토어 경로를 위반으로 읽으면 훅을 고칠 방법이 없어진다.
    for (const allowed of [
      'apps/web/node_modules/.bin/eslint --config apps/web/eslint.config.js --cache',
      'node_modules/.bin/lint-staged',
      'node scripts/build-doc-index.mjs --check',
      'node_modules/.pnpm/foo/bar',
    ]) {
      assert.equal(PNPM_WRAPPER.test(allowed), false, `정상 명령을 위반으로 읽었다: ${allowed}`);
    }
  });
  // ★2026-08-21 — 「내 입력이 CI 트리거 paths 에 있는가」 단언을 여기서 지웠다.
  //   CI 자동 실행을 껐고, 판별식은 이제 `.husky/pre-push` 가 **조건 없이 전량** 돌린다.
  //   그 무조건성은 `scripts/workflow/discriminant-hook-wiring.test.ts` 가 강제한다.
  //   경로 짝맞춤 목록이 필요 없어졌으므로 보장은 유지되고 유지비만 사라진다.

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
