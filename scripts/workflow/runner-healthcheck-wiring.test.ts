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
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
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

/** 주석 줄을 걷어낸다 — 「docker 를 쓴다」와 「docker 를 언급한다」는 다르다. */
function withoutComments(body: string): string {
  return body
    .split('\n')
    .filter((l) => !l.trim().startsWith('#'))
    .join('\n');
}

/**
 * 워크플로우가 **실제로** Docker 를 쓰는가 — 자신의 명령 + 자신이 부르는 스크립트까지 본다.
 *
 * ★목록을 손으로 적지 않는 이유는 `callerFiles()` 와 같다. 「docker 를 쓰는 워크플로우」를
 * 상수로 적으면 그 목록과 실제 워크플로우가 **서로를 안 보는 두 목록**이 되고, 새 워크플로우가
 * docker 를 쓰면서 플래그를 빠뜨려도 아무도 안 잡는다.
 *
 * ★스크립트를 따라가지 않으면 `infra-ci` 를 놓친다. 그 파일의 docker 언급은 **주석 한 줄뿐**이고
 * 실제 사용은 `scripts/verify/nginx-log-masking.sh` 안에 있다.
 *
 * ★★단 「실행하는」 스크립트만 따라간다 — 인터프리터가 앞에 붙은 것. 경로가 적혔다는 이유만으로
 * 따라가면 `paths:` **필터 목록**까지 호출로 오인한다. 실제로 `workflow-scripts-ci` 는 판별식
 * 입력으로 `scripts/verify-runner-health.sh` 를 `paths` 에 적어 두는데, 그 스크립트가 docker 를
 * 쓰게 되자 이 워크플로우까지 「docker 를 쓴다」로 잡혔다(2026-08-12 실측). 그러면 docker 가
 * 필요 없는 판별식 PR 이 데몬 부재로 막힌다 — 프리플라이트가 새 차단면을 만드는 것이다.
 */
function usesDocker(name: string): boolean {
  const body = withoutComments(readWorkflow(name));
  if (/\bdocker\b/.test(body)) return true;

  for (const m of body.matchAll(/(?:bash|sh|node|\.\/)\s+(scripts\/[\w./-]+\.(?:sh|mjs|ts))/g)) {
    const p = path.join(REPO_ROOT, m[1]);
    if (fs.existsSync(p) && /\bdocker\b/.test(withoutComments(fs.readFileSync(p, 'utf8')))) {
      return true;
    }
  }
  return false;
}

/** 자원 점검 스텝의 이름. 추출 앵커이므로 워크플로우와 한 글자도 달라선 안 된다. */
const RESOURCE_STEP_NAME = '- name: 러너 자원 점검';
/** `run: |` 블록의 들여쓰기(스텝 6칸 + run 8칸 → 본문 10칸). */
const RUN_BLOCK_INDENT = 10;
/** 추출이 이보다 짧으면 앵커가 어긋난 것이다 — 0줄을 돌려주면 아래 단언이 전부 공허해진다. */
const MIN_STEP_LINES = 20;

/**
 * CI 인라인 자원 판정 블록을 **실행 가능한 셸 스크립트로** 뽑아낸다.
 *
 * 왜 필요한가. `runner-health.yml` 의 판정은 인라인 shell 이라 보통은 실행해볼 수단이 없고,
 * 그래서 「문자열이 있는지」만 검사하게 된다. 그 검사는 **셸 옵션 차이로 생기는 거동 차이를
 * 원리적으로 못 본다** — 실제로 awk 문자열 비교 함정이 그 사각으로 빠져나갔다.
 */
function resourceStepScript(): string {
  const body = readWorkflow(HEALTH_WORKFLOW);

  const stepIdx = body.indexOf(RESOURCE_STEP_NAME);
  assert.ok(stepIdx >= 0, `자원 점검 스텝(${RESOURCE_STEP_NAME})을 못 찾았다 — 추출이 고장났다.`);

  const runIdx = body.indexOf('run: |', stepIdx);
  assert.ok(runIdx > stepIdx, '자원 점검 스텝에 run 블록이 없다.');

  const lines: string[] = [];
  for (const line of body.slice(runIdx).split('\n').slice(1)) {
    if (line.trim() === '') {
      lines.push('');
      continue;
    }
    // 블록 들여쓰기보다 얕아지면 다음 스텝/키다. 거기서 끊는다.
    if (!line.startsWith(' '.repeat(RUN_BLOCK_INDENT))) break;
    lines.push(line.slice(RUN_BLOCK_INDENT));
  }

  const script = lines.join('\n');
  // ★비-공허 확인. 앵커가 어긋나 빈 스크립트가 나오면 `bash -e ""` 는 그냥 성공하고
  //   아래 5개 케이스가 **전부 초록으로 통과**한다 — 가드가 있는 척하는 최악의 상태다.
  assert.ok(
    lines.filter((l) => l.trim() !== '').length >= MIN_STEP_LINES,
    `추출된 스텝이 ${lines.length}줄뿐이다 — 빈 스크립트를 돌리면 모든 단언이 공허하게 통과한다.`,
  );
  assert.match(script, /OVER/, '추출본에 판정 로직이 없다 — 엉뚱한 블록을 잘라냈다.');

  return script;
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

  test('★두 복사본의 판정 규칙이 갈라지지 않는다', () => {
    // 왜 복사본이 둘인가. CI 층은 checkout **앞**에서 돌아야 해서 저장소의 스크립트를 부를 수
    // 없다(그 시점엔 저장소가 없다). 그래서 판정 규칙이 셸 스크립트와 워크플로우 인라인,
    // 두 곳에 존재한다 — 이 저장소가 반복해서 당한 「서로를 안 보는 두 목록」 양식이다.
    // 완전한 dedup 은 구조적으로 불가능하므로, **하중을 받는 술어**만이라도 양쪽에 있는지 본다.
    const script = fs.readFileSync(path.join(REPO_ROOT, HEALTH_SCRIPT), 'utf8');
    const workflow = readWorkflow(HEALTH_WORKFLOW);

    /** 하나라도 한쪽에서 사라지면 두 층의 판정이 달라진다. */
    const INVARIANTS = [
      // 실행으로 판정한다 (존재 확인이 아니다)
      '! "$bin" "$flag" >/dev/null 2>&1',
      // 표식 게이팅 — 표식이 없으면 자가 재설치되므로 차단하지 않는다
      'marker="${5:-}"',
      '[ -n "$marker" ] && [ ! -e "$marker" ]',
      // 글로브는 버전 디렉터리에 건다 (바이너리에 걸면 지워지는 순간 0건이 된다)
      '/_work/_tool/node/*/*/',
      '/_work/_tool/Java_*/*/*/',
      '"${dir%/}.complete"',
      // 진단 문구 — 이게 없으면 「테스트가 깨졌다」로 오독된다
      '러너 환경 결함 — 코드 문제 아님',

      // ── 자원 고갈 판정 (2026-08-07 추가) ───────────────────────────────
      // 엔진이 전부 정상인데도 CI 가 2.4배 느려지는 상태. 위 점검은 전부 통과시킨다.
      // 한쪽 층에만 있으면 「로컬은 경고하는데 CI 는 조용하다」(또는 그 반대)가 된다.
      'hw.ncpu', // load 를 코어 수 대비 비율로 — 절대값이면 러너 교체 시 의미가 바뀐다
      'hw.memsize', // swap 을 물리 메모리 대비 비율로 — 같은 이유
      'vm.swapusage',
      'BTS_RUNNER_FAKE_LOAD', // 측정값 주입 이음매. 없으면 그 층은 검증 불가능해진다
      'BTS_RUNNER_FAKE_SWAP_MB',
      '러너 자원 고갈', // 경고 문구 자체
      '시간을 믿지 마라', // ★왜 문제인지. 없으면 「그냥 느린 날」로 오독된다

      // ── Docker 데몬 판정 (2026-08-12 추가 · 부채 매핑 30) ───────────────
      // 엔진도 자원도 정상인데 **데몬만 꺼진** 상태. 위 점검들은 전부 통과시킨다.
      // 2026-08-12 PR #367 실측 상관 100% — 데몬 준비 이전 시작 잡 5건 전부 실패,
      // 이후 시작한 잡 전부 통과. 코드 무변경 재실행으로 EXIT=5 → 0.
      'docker info', // ★CLI 존재가 아니라 **데몬 가동**으로 판정한다. 그 구분이 결함의 핵심이다
      'BTS_DOCKER_BIN', // 데몬 부재 주입 이음매. 없으면 그 층은 검증 불가능해진다
      'Docker 데몬이 꺼져 있다', // 진단 문구 — 없으면 「테스트가 깨졌다」로 오독된다
      '코드 문제 아님', // 오진 차단. 이 문구가 이 판정의 존재 이유다
    ];

    const missing = INVARIANTS.flatMap((needle) => [
      ...(script.includes(needle) ? [] : [`script 에 없음. ${needle}`]),
      ...(workflow.includes(needle) ? [] : [`${HEALTH_WORKFLOW} 에 없음. ${needle}`]),
    ]);

    assert.deepEqual(
      missing,
      [],
      `두 층의 판정 규칙이 갈라졌다 —\n${missing.join('\n')}\n` +
        `한쪽만 고치면 로컬은 잡고 CI 는 놓치는(또는 그 반대) 상태가 된다.`,
    );
  });

  test('★★Docker 를 쓰는 워크플로우는 require_docker 를 넘긴다 (목록을 손으로 적지 않는다)', () => {
    // 데몬이 꺼지면 이 워크플로우들의 잡이 **코드와 무관하게** 줄줄이 오진 실패한다.
    // 프리플라이트가 있어도 호출부가 안 넘기면 아무것도 안 막는다 —
    // 이 저장소가 여러 번 겪은 「가드는 있는데 배선이 없다」 양식이다.
    const needing = callerFiles().filter(usesDocker);

    // ★비-공허 짝. 훑기가 고장나 0건이 되면 아래 단언이 조용히 통과한다.
    assert.ok(
      needing.length >= 2,
      `docker 를 쓰는 워크플로우를 ${needing.length}개만 찾았다 (실측 기준선 2 — backend·infra).\n` +
        `훑기가 고장나면 아래 단언이 공허하게 통과한다.`,
    );

    const missing = needing.filter((name) => !/require_docker:\s*true/.test(readWorkflow(name)));

    assert.deepEqual(
      missing,
      [],
      `Docker 를 쓰면서 require_docker 를 안 넘기는 워크플로우가 있다: ${missing.join(', ')}\n\n` +
        `데몬이 꺼지면 이 워크플로우의 잡이 코드와 무관하게 전부 빨간불이 되고, 로그는\n` +
        `「테스트가 깨졌다」로 읽힌다. 2026-08-12 PR #367 에서 실제로 그 비용을 치렀다 —\n` +
        `12개 잡이 줄줄이 실패하고 사람이 하나씩 로그를 팠다.`,
    );
  });

  test('★Docker 를 안 쓰는 워크플로우는 require_docker 를 넘기지 않는다 (음성 대조군)', () => {
    // 반대 방향. 전부 true 로 두면 데몬이 꺼진 동안 프론트·판별식 PR 까지 부당하게 막힌다.
    // 위 단언만 있으면 「전부 true」가 통과하므로, 이 짝이 없으면 계약이 절반이다 —
    // 프리플라이트가 고치려던 것보다 큰 차단면을 새로 만드는 경로다.
    const overreach = callerFiles()
      .filter((name) => !usesDocker(name))
      .filter((name) => /require_docker:\s*true/.test(readWorkflow(name)));

    assert.deepEqual(
      overreach,
      [],
      `Docker 를 안 쓰는데 요구하는 워크플로우가 있다: ${overreach.join(', ')}\n` +
        `데몬이 꺼진 동안 이 PR 들까지 막힌다 — 프리플라이트가 새 차단면을 만든다.`,
    );
  });

  test('★★인용한 런북 섹션이 실재한다 (죽은 참조 차단)', () => {
    // 두 층은 실패할 때 「docs/runbooks/self-hosted-runner.md §N」을 안내한다. 그 섹션이 없거나
    // 다른 내용이면 **막힌 사람이 엉뚱한 절을 읽는다** — 진단을 주는 척하고 안 주는 상태다.
    //
    // ★같은 양식을 2026-08-12 에 이미 밟았다. `/bts-merge` 가 26일간 실재하지 않는 경로를
    //   `git add` 하라고 지시하고 있었다(PR #377). 사람은 두 목록을 못 맞춘다 — 기계가 맞춘다.
    const runbookPath = path.join(REPO_ROOT, 'docs/runbooks/self-hosted-runner.md');
    assert.ok(fs.existsSync(runbookPath), `${runbookPath} 가 없다 — 아래 단언이 공허해진다.`);
    const runbook = fs.readFileSync(runbookPath, 'utf8');

    // 런북이 실제로 가진 섹션 번호. `## 4. …` 형태에서 뽑는다.
    const present = new Set(
      [...runbook.matchAll(/^##\s+(\d+)\./gm)].map((m) => m[1]),
    );
    assert.ok(present.size >= 3, `런북 섹션을 ${present.size}개만 찾았다 — 파서가 고장났다.`);

    // 두 층이 인용하는 섹션 번호 전수.
    const citing = [readWorkflow(HEALTH_WORKFLOW), fs.readFileSync(path.join(REPO_ROOT, HEALTH_SCRIPT), 'utf8')];
    const cited = [...new Set(citing.flatMap((body) => [...body.matchAll(/self-hosted-runner\.md\s+§(\d+)/g)].map((m) => m[1])))];
    assert.ok(cited.length > 0, '두 층이 런북을 한 번도 인용하지 않는다 — 진단 경로가 없다.');

    const dangling = cited.filter((n) => !present.has(n));
    assert.deepEqual(
      dangling,
      [],
      `실재하지 않는 런북 섹션을 인용한다: ${dangling.map((n) => `§${n}`).join(', ')}\n` +
        `런북이 가진 섹션. ${[...present].map((n) => `§${n}`).join(' · ')}\n` +
        `막힌 사람이 엉뚱한 절을 읽게 된다 — 진단을 주는 척하고 안 주는 상태다.`,
    );

    // ★번호가 실재하는지만 보면 **부족하다.** 번호는 있는데 **다른 내용**인 경우를 못 잡는다.
    //   실제로 이 PR 초안이 §5 를 인용했는데 그 절은 「GitHub 호스팅 러너로 되돌리는 절차」였다.
    //   그래서 번호를 손으로 적지 않고 **런북에서 Docker 절을 찾아** 그 번호를 요구한다.
    const dockerSection = [...runbook.matchAll(/^##\s+(\d+)\.\s*(.+)$/gm)].find((m) =>
      /docker/i.test(m[2]),
    );
    assert.ok(
      dockerSection !== undefined,
      '런북에 Docker 절이 없다 — 데몬 부재로 막힌 사람이 읽을 곳이 없다.\n' +
        `런북 섹션. ${[...runbook.matchAll(/^##\s+(\d+\..+)$/gm)].map((m) => m[1]).join(' / ')}`,
    );
    const dockerN = dockerSection[1];
    assert.ok(
      cited.includes(dockerN),
      `두 층이 Docker 절(§${dockerN} ${dockerSection[2]})을 인용하지 않는다 — 인용한 것. ` +
        `${cited.map((n) => `§${n}`).join(' · ')}\n` +
        `번호만 맞는 엉뚱한 절을 가리키면 막힌 사람이 그 절을 읽고 더 헤맨다.`,
    );
  });

  test('★자원 경고는 run 요약과 어노테이션으로 표출된다 — 스텝 로그 안이면 아무도 안 본다', () => {
    // 2026-08-07 사고의 본질은 「판정이 없었다」가 아니라 **「초록불이라 아무도 안 봤다」**이다.
    // 판정이 맞아도 표출이 스텝 로그뿐이면 잡을 펼쳐야 보이고, 그러면 아무도 안 본다 —
    // 표출 실패는 판정 부재와 같은 결과를 낳는다.
    const workflow = readWorkflow(HEALTH_WORKFLOW);

    assert.match(
      workflow,
      /\$GITHUB_STEP_SUMMARY/,
      `자원 판정 결과가 run 요약에 안 남는다 — 잡을 펼쳐야만 보이면 사실상 없는 것이다.`,
    );
    assert.match(
      workflow,
      /::warning/,
      `경고 어노테이션이 없다 — PR 화면 상단에 뜨지 않으면 다음 사람도 오늘의 나처럼 3시간을 쓴다.`,
    );

    // ★어노테이션은 **경고일 때만** 나와야 한다. 매 run 마다 뜨면 노이즈가 되어 무시된다 —
    //   음성 대조군 테스트(verify-runner-health.test.ts)가 지키려는 성질과 같다.
    const overIdx = workflow.indexOf('"$OVER" -eq 1');
    const warnIdx = workflow.indexOf('::warning');
    assert.ok(
      overIdx > 0 && warnIdx > overIdx,
      `::warning 이 고갈 분기(${overIdx}) 밖(${warnIdx})에 있다 — 정상 run 에도 경고가 떠 상시화된다.`,
    );
  });

  test('★★CI 인라인 자원 판정은 errexit 하에서도 절대 죽지 않는다', () => {
    // ## 왜 문자열 매칭으로는 부족한가
    //
    // 위 INVARIANTS 는 두 층에 **같은 술어가 있는지**만 본다. 그런데 두 층은 셸 옵션이 다르다 —
    //   로컬 scripts/verify-runner-health.sh : `set -uo pipefail`  (errexit **없음**)
    //   CI   runner-health.yml `run:`         : GitHub Actions 기본 `bash -e` (errexit **있음**)
    // 같은 코드가 로컬에서는 조용히 넘어가고 CI 에서는 스텝을 죽인다. 실제로 awk 문자열 비교
    // 함정(`-v n="xyz"` 에서 `n > 0` 이 문자열 비교가 되어 0 나눗셈 가드를 통과)이
    // **CI 에서만** exit 2 를 냈고, INVARIANTS 는 그것을 통과시켰다.
    //
    // ★runner-health 는 모든 워크플로우의 `needs:` 선행 잡이다. 이 스텝이 죽으면
    //   backend·frontend·infra·workflow-scripts 의 모든 잡이 안 돈다 — 자원 부족을 알리려던
    //   장치가 CI 전체를 세운다. 그래서 「어떤 입력에도 exit 0」이 이 스텝의 핵심 계약이다.
    const step = resourceStepScript();
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'bts-step-')), 'step.sh');
    fs.writeFileSync(file, step);

    /** 실제 CI 와 동일하게 `bash -e` 로 돌린다. 여기가 로컬 스크립트와 갈리는 지점이다. */
    const runStep = (env: Record<string, string>) =>
      spawnSync('bash', ['-e', file], {
        env: { ...process.env, ...env },
        encoding: 'utf8',
      });

    const CASES: Array<[string, Record<string, string>]> = [
      ['정상', { LOAD: '7.72', NCPU: '8', SWAP_MB: '3556', MEM_MB: '16384' }],
      ['고갈', { LOAD: '34.43', NCPU: '8', SWAP_MB: '15014', MEM_MB: '16384' }],
      // ★숫자가 아닌 값. awk 문자열 비교 함정이 여기서 드러난다.
      ['비숫자', { LOAD: 'abc', NCPU: 'xyz', SWAP_MB: 'zzz', MEM_MB: 'qqq' }],
      // 0 나눗셈 경로.
      ['0코어', { LOAD: '34.43', NCPU: '0', SWAP_MB: '3556', MEM_MB: '0' }],
      // 음수 — sysctl 이 이상해지는 이론적 경로.
      ['음수', { LOAD: '-1', NCPU: '-8', SWAP_MB: '-1', MEM_MB: '-1' }],
    ];

    const failures = CASES.flatMap(([label, vals]) => {
      const env = Object.fromEntries(
        Object.entries(vals).map(([k, v]) => [`BTS_RUNNER_FAKE_${k}`, v]),
      );
      const r = runStep(env);
      const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
      if (r.status !== 0) return [`${label} → exit ${r.status}\n${out}`];
      if (/division by zero|awk:|syntax error/.test(out)) return [`${label} → 셸/awk 에러\n${out}`];
      return [];
    });

    assert.deepEqual(
      failures,
      [],
      `CI 인라인 자원 판정이 죽었다 — runner-health 가 죽으면 모든 워크플로우가 멈춘다.\n` +
        failures.join('\n---\n'),
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
