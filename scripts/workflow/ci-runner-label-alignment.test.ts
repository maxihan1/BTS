// 모든 워크플로우 잡이 self-hosted 러너 라벨을 쓰는지, 그리고 그 판별식이 실제로 CI 에서 돌게
// 배선돼 있는지를 강제하는 판별식
//
// 왜 이 테스트가 있나. 2026-07-29 17:32 KST 부터 GitHub Actions **결제 차단**으로
// `ubuntu-latest` 잡이 `steps=0` 으로 배정조차 되지 않는다. 차단 이후 실행 12/12 전부 같은
// 어노테이션이었고(`The job was not started because recent account payments have failed...`),
// 그 사이 #322 · #323 이 **CI 0회로 머지**됐다. self-hosted 러너는 이 차단을 받지 않는다
// (확인 사격 run 30508738275 에서 `steps=4 success` 로 실증).
//
// 위험은 「전환했다」가 아니라 「일부가 조용히 되돌아간다」다. `runs-on` 하나가 `ubuntu-latest` 로
// 돌아가면 그 잡은 실패하는 게 아니라 **배정 자체가 안 된 채 실패로 표시**돼, 원인이
// 「테스트가 깨졌다」로 오독된다. 그래서 정합을 보는 눈이 필요하다.
//
// ## ★ 왜 워크플로우 파일 목록을 선언하지 않고 런타임에 훑나
//
// 리뷰가 지적한 결함 — 파일 목록을 상수로 적으면 그 목록과 실제 `.github/workflows/` 가
// **서로를 안 보는 두 목록**이 된다. 새 워크플로우를 추가하면서 목록에 안 넣으면 그 파일은
// `ubuntu-latest` 를 써도 아무도 안 잡는다. 이 저장소의 지배 결함 양식이다.
//
// 처방은 두 목록을 화해시키는 차집합 검사가 아니라 **목록을 하나로 만드는 것**이다.
// 그래서 파일 집합은 런타임 `readdirSync` 로 얻고, 상수로는 **디렉터리 한 곳**만 선언한다.
// 목록이 하나면 갈라질 수 없다. 대신 훑기가 0건을 반환하면 모든 단언이 공허해지므로
// 양성 대조군을 첫 단언으로 둔다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * 이 판별식이 의존하는 입력과, CI 트리거에서 그 입력을 덮는 경로 패턴.
 *
 * 읽기 경로와 트리거 요구를 **한 선언에서 파생**시킨다. 따로 두면 갈라진다 — #323 코드리뷰에서
 * 실제로 갈라졌고(입력 4개, 손으로 적은 트리거 요구 3개), 빠진 하나를 되돌리는 PR 이 CI 를
 * 안 띄워 그 단언을 0회 실행하는 상태가 됐다. 판별식을 무력화하는 PR 이 정확히 그 결함을
 * 만드는 PR 이라 가장 나쁜 사각이다.
 */
const INPUTS = {
  /** 훑기 대상 디렉터리. 파일 목록은 여기서 **런타임에** 얻는다 (위 주석 참조). */
  workflows: { file: '.github/workflows', coveredBy: '.github/workflows/**' },
  /** 판별식 자신. 런타임에 읽지는 않지만, 이 파일을 고치는 PR 에서도 CI 가 돌아야 한다. */
  self: {
    file: 'scripts/workflow/ci-runner-label-alignment.test.ts',
    coveredBy: 'scripts/workflow/**',
  },
} as const;

/** `on:` 아래에서 입력을 걸어야 하는 트리거. 한쪽만 걸면 봉인이 절반만 닫힌다. */
const CI_TRIGGERS = ['pull_request', 'push'] as const;

/** 트리거에 반드시 있어야 하는 경로 — [INPUTS] 에서 파생한다(중복 제거). */
const REQUIRED_CI_TRIGGER_PATHS = [
  ...new Set(Object.values(INPUTS).map((input) => input.coveredBy)),
];

/**
 * 판별식(`pnpm test:workflow`)을 실제로 돌리는 워크플로우.
 *
 * backend-ci 가 아니라 전용 워크플로우인 이유. `paths` 는 **워크플로우 레벨**이라 잡별 필터가
 * 없다. 판별식 잡을 backend-ci 에 두고 그 트리거에 `.github/workflows/**` 를 걸면,
 * 워크플로우 파일 한 줄 고치는 PR 이 backend 12잡을 전부 끌고 온다 — 러너 1대에서 50~60분이다.
 */
const DISCRIMINANT_WORKFLOW = '.github/workflows/workflow-scripts-ci.yml';

/** 모든 잡이 요구받는 러너 라벨. 이 저장소 전용 self-hosted 러너를 가리킨다. */
const REQUIRED_RUNNER_LABEL = 'bts-local';

/**
 * 판별식 **전용** 입력 경로 — 이 경로들을 트리거로 거는 것은 `DISCRIMINANT_WORKFLOW` 하나여야 한다.
 *
 * 왜 막나. 판별식은 `pnpm test:workflow` 로 workflow-scripts-ci 에서만 돈다(2026-07-30 이전
 * 에는 backend-ci 에 있었다). 그런데 backend-ci 의 `paths` 에 이 경로들이 **이동 후에도
 * 남아 있었다.** backend-ci 는 `./gradlew` 만 실행하므로 이 경로들은 잡 결과에 아무 영향이 없다.
 *
 * 비용은 실측됐다. PR #329(문서 인덱싱)는 backend 를 한 줄도 건드리지 않았는데
 * `scripts/workflow/` 에 판별식 파일 하나를 추가한 것만으로 backend 12잡을 전부 끌고 왔다.
 * 러너 1대 직렬이라 33분 스위트가 50~60분이 된다.
 *
 * 되돌리려면 이 상수를 함께 지워야 한다 — 그때 위 근거를 다시 검토하라.
 *
 * ## ★예외 1건 — 「`./gradlew` 만 실행한다」는 전제가 깨졌다 (2026-08-12 · 부채 매핑 31)
 *
 * backend-ci 가 이제 `scripts/workflow/select-backend-modules.ts` 를 **실행한다** — 어떤 모듈을
 * 돌릴지 고르는 코드다. 그래서 그 **한 파일만은** backend-ci 의 `paths` 에 있어야 한다.
 * 없으면 「선별기를 너무 좁게 고치는 PR」이 `scripts/**` 만 건드리므로 backend-ci 가 PR 에서도
 * 머지 후에도 **0회** 돌고, 어떤 백엔드 테스트를 돌릴지 정하는 코드가 정작 백엔드 잡으로는
 * 한 번도 검증되지 않는다(독립 리뷰 적발).
 *
 * ★글로브가 아니라 **그 파일 하나**다. `scripts/workflow/**` 를 통째로 넣으면 판별식 파일을
 *   추가하는 것만으로 backend 12잡을 끌고 오는 PR #329 의 비용이 그대로 돌아온다.
 */
const DISCRIMINANT_ONLY_PATHS = [
  'scripts/workflow/**',
  'apps/web/vite.config.ts',
  'apps/web/playwright.config.ts',
  'package.json',
] as const;

/** 판별식을 돌리지 않는 워크플로우 — 위 경로를 트리거로 걸면 안 된다. */
const NON_DISCRIMINANT_WORKFLOWS = ['.github/workflows/backend-ci.yml'] as const;

/** 파서가 고장났을 때 차집합이 공허하게 통과하는 것을 막는 하한. */
const MIN_WORKFLOW_FILES = 3;
const MIN_RUNS_ON_ENTRIES = 8;

interface RunsOnEntry {
  /** repo 루트 기준 워크플로우 파일 경로 */
  workflow: string;
  /** 1-based 줄 번호 */
  line: number;
  /** `runs-on:` 우변 원문 */
  raw: string;
}

/** `.github/workflows/` 의 워크플로우 파일 전수. 목록을 상수로 두지 않는 것이 핵심이다. */
function workflowFiles(): string[] {
  const dir = path.join(REPO_ROOT, INPUTS.workflows.file);
  if (!fs.existsSync(dir)) return [];

  return fs
    .readdirSync(dir)
    .filter((name) => name.endsWith('.yml') || name.endsWith('.yaml'))
    .sort()
    .map((name) => `${INPUTS.workflows.file}/${name}`);
}

/**
 * 모든 워크플로우 파일에서 `runs-on:` 선언을 전수 수집한다.
 *
 * 값을 이 파일에 복사해 두면 그것이 또 하나의 어긋날 수 있는 목록이 되므로 원본을 읽는다.
 */
function runsOnEntries(): RunsOnEntry[] {
  const entries: RunsOnEntry[] = [];

  for (const workflow of workflowFiles()) {
    const lines = fs.readFileSync(path.join(REPO_ROOT, workflow), 'utf8').split('\n');
    lines.forEach((line, index) => {
      const raw = /^\s*runs-on:\s*(.+?)\s*$/.exec(line)?.[1];
      if (raw !== undefined) entries.push({ workflow, line: index + 1, raw });
    });
  }

  return entries;
}

/**
 * `coveredBy` 패턴이 실제로 그 입력을 덮는지. 잘못 선언한 짝을 잡는다.
 *
 * 입력이 **디렉터리**인 경우(`workflows`)도 덮은 것으로 본다 — `X/**` 는 GitHub Actions
 * 경로 필터에서 `X` 아래 파일 변경을 걸므로, 그 디렉터리를 훑는 입력의 짝으로 정확하다.
 */
function globCovers(glob: string, file: string): boolean {
  if (glob === file) return true;
  if (!glob.endsWith('/**')) return false;

  const base = glob.slice(0, -3);
  return file === base || file.startsWith(`${base}/`);
}

/**
 * 워크플로우의 `on.<trigger>.paths` 를 **트리거별로** 수집한다.
 *
 * 한 덩어리로 합치면 `pull_request` 에만 배선돼도 통과한다 — 봉인이 절반만 닫히는 양식이다.
 * YAML 은 들여쓰기가 곧 구조라 줄 단위 상태 기계가 정규식 블록 절단보다 주석·빈 줄에 견고하다.
 */
function triggerPaths(workflow: string): Map<string, Set<string>> {
  const collected = new Map<string, Set<string>>();
  const abs = path.join(REPO_ROOT, workflow);
  if (!fs.existsSync(abs)) return collected;

  let trigger: string | undefined;
  let inPaths = false;

  for (const line of fs.readFileSync(abs, 'utf8').split('\n')) {
    const triggerKey = /^ {2}([a-z_]+):/.exec(line)?.[1];
    if (triggerKey !== undefined) {
      trigger = triggerKey;
      inPaths = false;
      continue;
    }
    if (/^ {4}paths:/.test(line)) {
      inPaths = true;
      continue;
    }
    if (!inPaths || trigger === undefined) continue;

    const entry = /^ {6}- '([^']+)'/.exec(line)?.[1];
    if (entry !== undefined) {
      if (!collected.has(trigger)) collected.set(trigger, new Set());
      collected.get(trigger)?.add(entry);
      continue;
    }
    // 들여쓰기가 얕아지면 paths 블록이 끝난 것. 주석·빈 줄은 블록 안으로 본다.
    if (line.trim().length > 0 && !line.startsWith('      ')) inPaths = false;
  }

  return collected;
}

describe('CI 러너 라벨 정합', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const files = workflowFiles();
    const entries = runsOnEntries();

    assert.ok(
      files.length >= MIN_WORKFLOW_FILES,
      `워크플로우 파일을 ${files.length}개만 찾았다 — 훑기가 고장났다. ` +
        `이 값이 0 이면 아래 모든 단언이 공허하게 통과한다.`,
    );
    assert.ok(
      entries.length >= MIN_RUNS_ON_ENTRIES,
      `runs-on 선언을 ${entries.length}개만 찾았다 — 파서가 고장났다.`,
    );
  });

  test(`모든 잡이 '${REQUIRED_RUNNER_LABEL}' 러너 라벨을 쓴다`, () => {
    const offenders = runsOnEntries()
      .filter((entry) => !entry.raw.includes(REQUIRED_RUNNER_LABEL))
      .map((entry) => `${entry.workflow}:${entry.line}  runs-on: ${entry.raw}`);

    assert.deepEqual(
      offenders,
      [],
      `self-hosted 러너를 쓰지 않는 잡이 있다.\n${offenders.join('\n')}\n\n` +
        `GitHub 결제가 차단된 동안 'ubuntu-latest' 잡은 실패하는 것이 아니라 ` +
        `배정 자체가 안 된 채(steps=0) 실패로 표시된다 — 원인이 "테스트가 깨졌다"로 오독된다.\n` +
        `결제 복구 후 되돌린다면 이 단언도 같은 PR 에서 함께 되돌린다 ` +
        `(docs/runbooks/self-hosted-runner.md).`,
    );
  });

  for (const trigger of CI_TRIGGERS) {
    test(`${DISCRIMINANT_WORKFLOW} 의 ${trigger} 트리거가 판별식 입력을 전부 건다`, () => {
      const paths = triggerPaths(DISCRIMINANT_WORKFLOW).get(trigger);

      assert.ok(
        paths !== undefined && paths.size > 0,
        `${DISCRIMINANT_WORKFLOW} 에 on.${trigger}.paths 가 없다. ` +
          `판별식을 만들어도 CI 가 안 돌리면 로컬 1회성 확인으로 끝나고 썩는다.`,
      );

      const missing = REQUIRED_CI_TRIGGER_PATHS.filter((required) => !paths.has(required));

      assert.deepEqual(
        missing,
        [],
        `${trigger} 트리거에 다음 경로가 없다: ${missing.join(', ')}\n\n` +
          `이 목록은 손으로 유지하지 않는다 — INPUTS 의 coveredBy 에서 파생돼 자동으로 요구된다. ` +
          `빠진 경로만 바꾸는 PR 은 이 판별식을 0회 실행하고 통과한다.`,
      );
    });
  }

  for (const workflow of NON_DISCRIMINANT_WORKFLOWS) {
    for (const trigger of CI_TRIGGERS) {
      test(`${workflow} 의 ${trigger} 트리거에 판별식 전용 입력이 없다`, () => {
        const paths = triggerPaths(workflow).get(trigger);
        assert.ok(
          paths !== undefined && paths.size > 0,
          `${workflow} 에 on.${trigger}.paths 가 없다 — 파서가 고장났거나 트리거가 사라졌다.`,
        );

        const leaked = DISCRIMINANT_ONLY_PATHS.filter((p) => paths.has(p));

        assert.deepEqual(
          leaked,
          [],
          `${workflow} 의 ${trigger} 트리거에 판별식 전용 경로가 있다: ${leaked.join(', ')}\n\n` +
            `이 워크플로우는 ./gradlew 만 실행한다 — 위 경로는 잡 결과에 영향이 없다.\n` +
            `판별식은 ${DISCRIMINANT_WORKFLOW} 의 discriminants 잡에서만 돈다.\n` +
            `경로 하나 때문에 backend 12잡(러너 1대 직렬 50~60분)이 통째로 끌려온다 — PR #329 실측.`,
        );
      });
    }
  }

  test('선언한 coveredBy 패턴이 실제로 그 입력을 덮는다', () => {
    const mismatched = Object.entries(INPUTS)
      .filter(([, input]) => !globCovers(input.coveredBy, input.file))
      .map(([key, input]) => `${key}. '${input.coveredBy}' 가 '${input.file}' 를 덮지 않는다`);

    assert.deepEqual(
      mismatched,
      [],
      `INPUTS 의 coveredBy 선언이 실제 입력 경로를 덮지 않는다.\n${mismatched.join('\n')}\n\n` +
        `짝을 잘못 적으면 파생이 엉뚱한 경로를 요구하면서 통과한다 — 트리거는 초록인데 ` +
        `정작 입력을 바꾸는 PR 에서 판별식이 안 돈다.`,
    );
  });

  test('self-hosted(macOS) 러너를 쓰는 워크플로우에 services: 블록이 없다', () => {
    const offenders: string[] = [];

    for (const workflow of workflowFiles()) {
      const lines = fs.readFileSync(path.join(REPO_ROOT, workflow), 'utf8').split('\n');
      lines.forEach((line, index) => {
        if (/^\s{4}services:\s*$/.test(line)) offenders.push(`${workflow}:${index + 1}`);
      });
    }

    assert.deepEqual(
      offenders,
      [],
      `services: 블록이 있다.\n${offenders.join('\n')}\n\n` +
        `GitHub Actions 의 서비스 컨테이너는 **Linux 러너에서만 지원된다.** macOS 러너에서는\n` +
        `잡이 'Initialize containers' 에서 다음 에러로 죽는다 (2026-07-30 실측, run 30514310932).\n` +
        `  ##[error]Container operations are only supported on Linux runners\n\n` +
        `이미지 아키텍처 문제가 아니다 — 기능 자체가 없다. 스텝 안에서 'docker run' 으로 직접\n` +
        `띄우는 것은 정상 동작한다(infra-ci 의 nginx 봉인 잡이 그렇게 돌고 있다).\n` +
        `대안은 docs/runbooks/self-hosted-runner.md §4 참조.`,
    );
  });

  test(`${DISCRIMINANT_WORKFLOW} 가 실제로 판별식을 실행한다`, () => {
    const abs = path.join(REPO_ROOT, DISCRIMINANT_WORKFLOW);

    assert.ok(fs.existsSync(abs), `${DISCRIMINANT_WORKFLOW} 가 없다.`);
    assert.match(
      fs.readFileSync(abs, 'utf8'),
      /pnpm test:workflow/,
      `${DISCRIMINANT_WORKFLOW} 가 'pnpm test:workflow' 를 돌리지 않는다.\n\n` +
        `트리거만 맞고 실행 스텝이 없으면 위 트리거 단언이 공허해진다 — ` +
        `워크플로우는 뜨는데 판별식은 0회 실행된다.`,
    );
  });
});
