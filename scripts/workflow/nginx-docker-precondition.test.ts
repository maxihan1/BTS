// nginx 봉인이 「Docker 데몬 부재」를 「설정 문법 오류」로 오진하지 않는지 검증하는 판별식
//
// ## 왜 이 판별식이 필요한가
//
// 2026-08-12 PR #367 에서 infra-ci 가 이렇게 말했다.
//
//   ❌ FAIL(5) nginx 문법 검증 실패 — 이 설정으로 배포하면 프론트 전체가 뜨지 않는다
//
// **설정은 멀쩡했고 Docker 데몬만 꺼져 있었다.** Docker 를 켜고 코드 무변경 상태로 재실행하니
// `EXIT=5 → 0` 이었다 — 변수는 데몬 하나뿐이었다.
//
// 원인은 한 줄이다. 스크립트가 `command -v docker` 부재만 `exit 7` 로 가르고, **데몬 부재는
// 문법 오류와 같은 `exit 5`** 로 뭉갠다. 그 파일이 「docker 부재를 SKIP 으로 넘기지 않는다 —
// 조용한 스킵은 vacuous 통과 경로다」라고 적어 둔 바로 그 자리에서, **CLI 존재와 데몬 가동을
// 구분하지 않아** 반대 방향 오진이 났다.
//
// ## 오진은 「빨간불이 났다」보다 나쁘다
//
// 엉뚱한 곳을 파게 만들기 때문이다. 그날 사람이 잡 12개의 로그를 하나씩 뒤졌다.
// 그래서 이 판별식이 재는 것은 **「실패하는가」가 아니라 「무엇이라고 말하는가」**다.
//
// ## 실제 Docker 를 끄지 않고 어떻게 재현하나
//
// `BTS_DOCKER_BIN` 이음매로 **가짜 docker** 를 주입한다 — `info` 만 실패하는 것(데몬 부재),
// 아예 없는 것(CLI 부재). 이 저장소의 `BTS_GH_BIN`·`BTS_RUNNER_ROOT` 관례와 같다.
// 실제 데몬을 끄는 검증은 운영 중 CI 를 끊으므로 하지 않는다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SCRIPT = path.join(REPO_ROOT, 'scripts/verify/nginx-log-masking.sh');

/** 데몬 부재 = 이 코드. 문법 오류(5) · CLI 부재(7) 와 **다른 값**이어야 한다. */
const EXIT_DAEMON_DOWN = 8;
const EXIT_SYNTAX = 5;

/** 오진 문구. 데몬이 꺼졌을 뿐인데 이 말이 나오면 사람이 설정을 파게 된다. */
const MISDIAGNOSIS = '이 설정으로 배포하면';

interface RunResult {
  code: number;
  output: string;
}

/**
 * 가짜 docker 를 주입해 스크립트를 돌린다.
 *
 * @param behavior `daemon-down` = CLI 는 있고 `info` 만 실패 / `missing` = 실행 파일 자체가 없음
 */
function runWithFakeDocker(behavior: 'daemon-down' | 'missing'): RunResult {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-nginx-precond-'));
  const bin = path.join(dir, 'fake-docker');
  fs.writeFileSync(
    bin,
    ['#!/usr/bin/env bash', 'if [ "$1" = "info" ]; then exit 1; fi', 'exit 0'].join('\n'),
    { mode: 0o755 },
  );

  let code = 0;
  let output = '';
  try {
    output = execFileSync('bash', [SCRIPT], {
      encoding: 'utf-8',
      // ★stderr 를 버리지 않는다. 이 스크립트는 진단을 stderr 로 낸다.
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        BTS_DOCKER_BIN: behavior === 'missing' ? path.join(dir, 'no-such-docker') : bin,
      },
    });
  } catch (err) {
    const e = err as { status?: number; stdout?: string; stderr?: string };
    code = e.status ?? 1;
    output = `${e.stdout ?? ''}${e.stderr ?? ''}`;
  }
  return { code, output };
}

describe('nginx 봉인의 Docker 전제조건 판정', () => {
  test('스크립트가 실재하고 실행 가능하다 (비-공허 짝)', () => {
    assert.ok(fs.existsSync(SCRIPT), `${SCRIPT} 가 없다 — 아래 단언이 전부 공허해진다.`);
  });

  test('★★데몬 부재를 문법 오류로 오진하지 않는다', () => {
    const r = runWithFakeDocker('daemon-down');

    assert.equal(
      r.code,
      EXIT_DAEMON_DOWN,
      `데몬 부재의 종료 코드가 ${r.code} 다 (기대 ${EXIT_DAEMON_DOWN}).\n` +
        `${EXIT_SYNTAX}(문법 오류)로 떨어지면 「설정이 잘못됐다」로 읽혀 엉뚱한 곳을 판다.\n${r.output}`,
    );
    assert.match(
      r.output,
      /데몬/,
      `출력이 데몬 부재를 말하지 않는다 — 오진 문구가 그대로다.\n${r.output}`,
    );
    assert.ok(
      !r.output.includes(MISDIAGNOSIS),
      `데몬이 꺼졌을 뿐인데 설정 탓을 한다 — 이 PR 이 없애려는 바로 그 문구다.\n${r.output}`,
    );
  });

  test('★CLI 부재와 데몬 부재를 다른 코드로 가른다', () => {
    // 둘 다 「전제조건 부재」지만 사람이 할 일이 다르다 — 설치냐 기동이냐.
    // 같은 코드로 뭉개면 로그만 보고 구분할 수 없다.
    const missing = runWithFakeDocker('missing');
    const down = runWithFakeDocker('daemon-down');

    assert.notEqual(
      missing.code,
      down.code,
      `CLI 부재와 데몬 부재가 같은 코드(${down.code})다 — 로그만 보고 구분할 수 없다.`,
    );
    assert.notEqual(down.code, 0, '데몬 부재를 조용히 통과시켰다 — vacuous 통과 경로다.');
    assert.notEqual(missing.code, 0, 'CLI 부재를 조용히 통과시켰다 — vacuous 통과 경로다.');
  });

  test('★가짜 docker 이음매가 실제로 먹는다 (양성 대조군)', () => {
    // 이 단언이 없으면 위의 「exit 8」이 **이음매가 안 먹어서** 우연히 난 값과 구분되지 않는다.
    // 이음매가 죽으면 실제 docker 가 불려 데몬이 켜진 이 머신에서는 0 이 나온다.
    const r = runWithFakeDocker('daemon-down');
    assert.notEqual(
      r.code,
      0,
      'BTS_DOCKER_BIN 을 주입했는데 통과했다 — 이음매가 안 먹어 실제 docker 가 불렸다.',
    );
  });
});
