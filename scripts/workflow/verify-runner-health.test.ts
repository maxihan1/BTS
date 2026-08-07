// 러너 엔진 헬스체크 스크립트의 계약 — 「파일 존재」가 아니라 「실행 성공」으로 판정하는지 강제
//
// 왜 이 테스트가 있나. 2026-08-04 홈 폴더 대용량 파일 정리로 self-hosted 러너의 실행 엔진 4개가
// 지워져 8/4~8/7 사흘간 CI 가 0회 실행됐고, 그 사이 #342·#343·#344 가 검증 없이 머지됐다.
//
// ## ★ 존재 확인으로는 못 잡는다 — 세 경우 모두 `test -f` 가 통과했다
//
// | 대상 | 파일 상태 | 실행 |
// |---|---|---|
// | `externals/node24/bin/` | `corepack`·`npm`·`npx` 심볼릭 존재 | `node` 자체가 없음 |
// | `_tool/node/<v>/arm64` | `arm64.complete` 표식 존재 → setup-node 가 캐시 히트로 오판 | 시스템 node 로 흘러내림 |
// | `_tool/Java_…/bin/` | `java` 포함 실행파일 30개 전부 존재 | `Failed setting boot class path` |
//
// 그래서 스크립트는 `node -v` / `java -version` 의 **exit 0** 을 보고, 이 테스트는 그 판정이
// 실제로 뒤집히는지를 **가짜 러너 트리**로 확인한다. 루트는 `BTS_RUNNER_ROOT` 로 주입한다 —
// 실제 러너를 건드리지 않고 결함 상태를 재현하기 위한 유일한 이음매다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SCRIPT = path.join(REPO_ROOT, 'scripts/verify-runner-health.sh');

/** JDK 툴캐시 스텁의 상대 경로. 여러 테스트가 같은 경로를 쓰므로 한 곳에서 파생시킨다. */
const JAVA_REL =
  '_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64/Contents/Home/bin/java';
const TOOLCACHE_NODE_REL = '_work/_tool/node/22.23.2/arm64/bin/node';

/** exit code 와 출력을 함께 돌려준다. 실패해도 진단을 읽어야 하므로 throw 를 삼킨다. */
function run(root: string): { code: number; out: string } {
  try {
    const out = execFileSync('bash', [SCRIPT], {
      env: { ...process.env, BTS_RUNNER_ROOT: root },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { code: 0, out };
  } catch (e) {
    const err = e as { status?: number; stdout?: string; stderr?: string };
    return { code: err.status ?? -1, out: `${err.stdout ?? ''}${err.stderr ?? ''}` };
  }
}

/** 툴캐시 완료 표식. 이 파일의 존재가 setup-* 의 「캐시 히트」 판정 근거다. */
const NODE_MARKER_REL = '_work/_tool/node/22.23.2/arm64.complete';
const JAVA_MARKER_REL = '_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64.complete';

/**
 * 실행 가능한 가짜 엔진 4종 + 툴캐시 완료 표식을 갖춘 러너 트리. 이 상태가 초록의 기준선이다.
 *
 * 표식까지 만드는 이유. 툴캐시 판정이 **표식 유무에 따라 갈리기** 때문이다 —
 * 표식이 있는데 실행이 안 되면 setup-* 가 캐시 히트로 오판하고 조용히 흘러내리므로 **실패**,
 * 표식이 없으면 어차피 캐시 미스로 자가 재설치되므로 **경고**다.
 */
function healthyRoot(): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-runner-'));
  const stub = (rel: string, body: string) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  };
  stub('externals/node20/bin/node', 'echo v20.20.2');
  stub('externals/node24/bin/node', 'echo v24.18.0');
  stub(TOOLCACHE_NODE_REL, 'echo v22.23.2');
  // java 는 -version 을 stderr 로 쓴다. 실제 거동을 흉내 내야 리다이렉션 실수를 잡는다.
  stub(JAVA_REL, 'echo "openjdk version \\"21.0.11\\"" 1>&2');
  fs.writeFileSync(path.join(root, NODE_MARKER_REL), '');
  fs.writeFileSync(path.join(root, JAVA_MARKER_REL), '');
  return root;
}

describe('러너 엔진 헬스체크 스크립트', () => {
  test('엔진 4종이 모두 실행되면 exit 0 (양성 대조군)', () => {
    const { code, out } = run(healthyRoot());
    assert.equal(code, 0, `초록이어야 하는데 실패했다\n${out}`);
  });

  test('★파일은 있는데 실행이 실패하면 red — 존재 확인으로는 못 잡는 결함', () => {
    const root = healthyRoot();
    // JDK 사고 재현. bin/java 는 그대로 있고 실행만 실패한다(lib/modules 부재와 동형).
    const java = path.join(root, JAVA_REL);
    fs.writeFileSync(java, '#!/bin/sh\necho "Failed setting boot class path" 1>&2\nexit 1\n', {
      mode: 0o755,
    });

    const { code, out } = run(root);
    assert.ok(fs.existsSync(java), '전제 확인 — 파일은 존재한다');
    assert.notEqual(code, 0, `java 실행 실패를 놓쳤다\n${out}`);
    assert.match(out, /러너 환경 결함 — 코드 문제 아님/);
  });

  test('node 바이너리가 사라지면 red + 복구 절차를 안내한다', () => {
    const root = healthyRoot();
    fs.rmSync(path.join(root, 'externals/node24/bin/node'));

    const { code, out } = run(root);
    assert.notEqual(code, 0, `node 부재를 놓쳤다\n${out}`);
    assert.match(out, /externals\/node24/);
    assert.match(out, /sha256/, '복구 절차가 안내되지 않았다');
  });

  test('툴캐시 표식만 남고 알맹이가 없으면 red — setup-* 가 캐시 히트로 오판하는 상태', () => {
    const root = healthyRoot();
    fs.rmSync(path.join(root, TOOLCACHE_NODE_REL));

    const { code, out } = run(root);
    assert.ok(fs.existsSync(path.join(root, NODE_MARKER_REL)), '전제 확인 — 표식은 남아 있다');
    assert.notEqual(code, 0, `툴캐시 알맹이 부재를 놓쳤다\n${out}`);
    assert.match(out, /arm64\.complete/, '표식 삭제 복구 절차가 안내되지 않았다');
  });

  test('★★표식이 이미 없으면 실패가 아니라 경고다 — 안 그러면 자가 치유를 자기가 막는다', () => {
    const root = healthyRoot();
    // 실제 2026-08-07 상황. JDK 는 깨졌지만 표식을 이미 지웠으므로 setup-java 가 캐시 미스로
    // 판정해 다음 실행에서 새로 받는다. 여기서 실패시키면 **재설치를 수행할 바로 그 잡**을
    // 막아 영원히 초록이 될 수 없다(실측 — PR #347 infra-ci run 31139123013 에서 교착 발생).
    fs.writeFileSync(path.join(root, JAVA_REL), '#!/bin/sh\nexit 1\n', { mode: 0o755 });
    fs.rmSync(path.join(root, JAVA_MARKER_REL));

    const { code, out } = run(root);
    assert.equal(code, 0, `표식이 없으면 자가 재설치되므로 통과해야 한다\n${out}`);
    assert.match(out, /⚠️/, '경고조차 안 나오면 조용히 묻힌다');
    assert.match(out, /자가 재설치/, '왜 통과시키는지가 로그에 없다');
  });
});
