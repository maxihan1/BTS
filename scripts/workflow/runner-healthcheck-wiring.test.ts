// 러너 엔진 헬스체크가 **실제로 불리도록** 배선됐는지를 강제하는 판별식
//
// 왜 이 판별식이 있나. 헬스체크를 만들어 두고 아무도 안 부르면 그것이 가장 나쁜 공허 가드다 —
// 「우리는 러너를 점검한다」는 믿음만 남고 실제 감지는 0이 된다. 2026-08-04 사고는 러너 엔진
// 4개가 지워져 8/4~8/7 사흘간 CI 가 0회 실행된 사건이고, 그 재발 감지가 이 배선에 달려 있다.
//
// ## ★ 왜 워크플로우 목록을 선언하지 않고 런타임에 훑나
//
// 목록을 상수로 적으면 그 목록과 실제 `.github/workflows/` 가 **서로를 안 보는 두 목록**이 된다.
// 새 워크플로우를 추가하면서 목록에 안 넣으면 그 파일은 헬스체크 없이도 아무도 안 잡는다.
// 이 저장소의 지배 결함 양식이고, 처방은 **목록을 하나로 만드는 것**이다
// (선례. `ci-runner-label-alignment.test.ts`). 대신 훑기가 0건을 반환하면 모든 단언이
// 공허해지므로 **양성 대조군을 첫 단언**으로 둔다.
//
// ## 두 층을 각각 본다
//
// - **A (CI 층)** — 재사용 워크플로우 `runner-health.yml` 호출 + 실행 잡의 `needs:` 배선
// - **B (로컬 층)** — `/bts-start` 가 `scripts/verify-runner-health.sh` 를 부르는가
//
// A 의 전제(순수 shell `run:` 스텝이 node 부재 시에도 실행되는가)는 **미검증**이라
// B 가 전제 무관 백스톱이다. 둘 중 하나가 빠지면 감지가 절반만 남으므로 양쪽을 다 단언한다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const WORKFLOW_DIR = '.github/workflows';
/** 재사용 워크플로우 자신은 호출 대상이 아니다. */
const HEALTH_WORKFLOW = 'runner-health.yml';
/** 실측 기준선 (backend · frontend · infra · workflow-scripts). 줄어들면 훑기가 고장난 것이다. */
const MIN_CALLERS = 4;

const HEALTH_SCRIPT = 'scripts/verify-runner-health.sh';
const BTS_START_SKILL = '.claude/skills/bts-start/SKILL.md';

/** `.github/workflows/` 의 워크플로우 파일 전수. 목록을 상수로 두지 않는 것이 핵심이다. */
function workflowFiles(): string[] {
  const dir = path.join(REPO_ROOT, WORKFLOW_DIR);
  if (!fs.existsSync(dir)) return [];

  return fs
    .readdirSync(dir)
    .filter((name) => name.endsWith('.yml') || name.endsWith('.yaml'))
    .sort();
}

/** 호출자여야 하는 워크플로우 = 재사용 워크플로우 자신을 뺀 전부. 손으로 안 적는다. */
function callerFiles(): string[] {
  return workflowFiles().filter((name) => name !== HEALTH_WORKFLOW);
}

function readWorkflow(name: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, WORKFLOW_DIR, name), 'utf8');
}

describe('러너 헬스체크 배선', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const callers = callerFiles();

    assert.ok(
      callers.length >= MIN_CALLERS,
      `호출자 후보를 ${callers.length}개만 찾았다 — 훑기가 고장났다. ` +
        `이 값이 0 이면 아래 단언이 전부 공허하게 통과한다.`,
    );
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, WORKFLOW_DIR, HEALTH_WORKFLOW)),
      `재사용 워크플로우 ${HEALTH_WORKFLOW} 가 없다 — 호출 단언이 전부 무의미해진다.`,
    );
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, HEALTH_SCRIPT)),
      `${HEALTH_SCRIPT} 가 없다 — 로컬 층이 통째로 빠졌다.`,
    );
  });

  test('A. 모든 워크플로우가 러너 헬스체크를 호출한다', () => {
    const offenders = callerFiles().filter(
      (name) => !readWorkflow(name).includes(`uses: ./${WORKFLOW_DIR}/${HEALTH_WORKFLOW}`),
    );

    assert.deepEqual(
      offenders,
      [],
      `헬스체크를 안 부르는 워크플로우가 있다 — ${offenders.join(', ')}\n` +
        `그 워크플로우는 러너 엔진이 없어도 「원인 불명 실패」로만 뜬다.`,
    );
  });

  test('A. 실행 잡은 전부 헬스체크 잡을 needs 로 매단다', () => {
    const offenders: string[] = [];

    for (const name of callerFiles()) {
      const body = readWorkflow(name);
      // `runs-on:` 을 가진 잡 = 실제 실행 잡. 그 수만큼 `needs` 에 runner-health 가 있어야 한다.
      // 재사용 워크플로우 호출 잡(`uses:`)은 runs-on 이 없으므로 자연히 빠진다.
      const runsOn = (body.match(/^ {4}runs-on:/gm) ?? []).length;
      const needs = (body.match(/^ {4}needs:.*runner-health/gm) ?? []).length;
      if (runsOn !== needs) offenders.push(`${name} (runs-on ${runsOn} ≠ needs ${needs})`);
    }

    assert.deepEqual(
      offenders,
      [],
      `헬스체크에 안 매달린 잡이 있다 — ${offenders.join(', ')}\n` +
        `매달지 않으면 엔진이 죽어도 그 잡은 그냥 원인 불명으로 실패한다.`,
    );
  });

  test('B. bts-start 가 로컬 헬스체크를 호출한다 (전제 무관 백스톱)', () => {
    const skill = fs.readFileSync(path.join(REPO_ROOT, BTS_START_SKILL), 'utf8');

    assert.match(
      skill,
      new RegExp(`bash ${HEALTH_SCRIPT.replace(/[.\\/]/g, '\\$&')}`),
      `${BTS_START_SKILL} 가 헬스체크를 안 부른다 — 스크립트가 있어도 아무도 안 돌리면 공허 가드다. ` +
        `A(CI 층)의 전제가 미검증이라 이 백스톱이 빠지면 감지가 통째로 사라질 수 있다.`,
    );
  });
});
