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
import { spawnSync } from 'node:child_process';
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

/**
 * exit code 와 출력을 함께 돌려준다. 실패해도 진단을 읽어야 하므로 throw 를 삼킨다.
 *
 * `extraEnv` 는 자원 판정의 측정값 주입용이다 — `BTS_RUNNER_ROOT` 와 같은 성격의 이음매다.
 * 자원 상태는 실제 머신에 종속이라 주입 없이 단언하면 그날 부하에 따라 결과가 뒤집힌다(flaky).
 */
function run(root: string, extraEnv: Record<string, string> = {}): { code: number; out: string } {
  // ★spawnSync 를 쓰는 이유. execFileSync 는 **성공 시 stdout 만** 돌려준다 —
  //   그러면 stderr 로 나가는 진단(예: `awk: division by zero`)이 exit 0 뒤에 숨어
  //   「에러가 없다」는 단언이 조용히 공허해진다. 실제로 이 파일이 그 함정에 한 번 빠졌다.
  const r = spawnSync('bash', [SCRIPT], {
    env: { ...process.env, BTS_RUNNER_ROOT: root, ...extraEnv },
    encoding: 'utf8',
  });
  return { code: r.status ?? -1, out: `${r.stdout ?? ''}${r.stderr ?? ''}` };
}

/** 2026-08-07 실측 그대로. 고갈 = load 34.43/8코어 = 4.30배 · swap 15,014M/16GB = 91.6% */
const EXHAUSTED = {
  BTS_RUNNER_FAKE_LOAD: '34.43',
  BTS_RUNNER_FAKE_SWAP_MB: '15014',
  BTS_RUNNER_FAKE_NCPU: '8',
  BTS_RUNNER_FAKE_MEM_MB: '16384',
};
/** 같은 머신의 정리 후 상태. load 7.72/8 = 0.97배 · swap 3,556M/16GB = 21.7% */
const HEALTHY_RESOURCE = {
  BTS_RUNNER_FAKE_LOAD: '7.72',
  BTS_RUNNER_FAKE_SWAP_MB: '3556',
  BTS_RUNNER_FAKE_NCPU: '8',
  BTS_RUNNER_FAKE_MEM_MB: '16384',
};

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

// ── 리눅스 러너 레이아웃 ──────────────────────────────────────────────────────
//
// 왜 필요한가. 러너를 늘리면 두 번째 러너는 리눅스다(2026-08-20 · 네이버 클라우드 VM).
// 그런데 JDK 툴캐시의 실행 파일 경로가 OS 마다 다르다 — macOS 는 `<arch>/Contents/Home/bin/java`,
// 리눅스는 `<arch>/bin/java` 로 중간 두 단계가 없다. 한쪽을 하드코딩하면 다른 쪽에서
// **「표식은 있는데 바이너리가 없다」**로 읽혀 `report` 가 실패를 내고, 이 스크립트는
// 모든 잡의 선행(`needs: runner-health`)이므로 그 러너가 집은 잡이 전부 차단된다.
//
// ★그 실패는 setup-java 가 한 번 돌아 `.complete` 표식이 생긴 뒤에야 나타난다. 갓 설치한
//   러너는 툴캐시가 비어 「경고」로 지나가므로, 러너를 붙인 당일에는 초록이다가 며칠 뒤
//   빨간불이 된다 — 원인을 러너 증설과 잇기 가장 어려운 시점이다.
const JAVA_LINUX_REL = '_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/x64/bin/java';
const JAVA_LINUX_MARKER_REL = '_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/x64.complete';
const TOOLCACHE_NODE_LINUX_REL = '_work/_tool/node/22.23.2/x64/bin/node';
const NODE_LINUX_MARKER_REL = '_work/_tool/node/22.23.2/x64.complete';

/** [healthyRoot] 와 같은 「전부 정상」 상태를 **리눅스 러너의 경로 레이아웃**으로 만든 것. */
function healthyLinuxRoot(): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-runner-linux-'));
  const stub = (rel: string, body: string) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  };
  stub('externals/node20/bin/node', 'echo v20.20.2');
  stub('externals/node24/bin/node', 'echo v24.18.0');
  stub(TOOLCACHE_NODE_LINUX_REL, 'echo v22.23.2');
  stub(JAVA_LINUX_REL, 'echo "openjdk version \\"21.0.11\\"" 1>&2');
  fs.writeFileSync(path.join(root, NODE_LINUX_MARKER_REL), '');
  fs.writeFileSync(path.join(root, JAVA_LINUX_MARKER_REL), '');
  return root;
}

describe('러너 엔진 헬스체크 스크립트', () => {
  test('엔진 4종이 모두 실행되면 exit 0 (양성 대조군)', () => {
    const { code, out } = run(healthyRoot());
    assert.equal(code, 0, `초록이어야 하는데 실패했다\n${out}`);
  });

  test('★리눅스 러너의 JDK 레이아웃(Contents/Home 없음)도 초록 — 러너가 두 OS 에 걸친다', () => {
    const { code, out } = run(healthyLinuxRoot());
    assert.equal(
      code,
      0,
      `리눅스 레이아웃에서 실패했다. macOS 경로를 하드코딩하면 리눅스 러너가 집은 잡이 전부 차단된다\n${out}`,
    );
    assert.match(out, /✅ toolcache java/, `java 검사가 아예 안 돌았다(공허한 통과)\n${out}`);
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
    // arch 이름(arm64/x64)을 고정하지 않는다. 러너가 두 OS 에 걸치면서 복구 안내가
    // arch 중립 문구(`<arch>.complete`)로 바뀌었다 — 리눅스 러너에게 `arm64.complete` 를
    // 지우라고 안내하는 것은 **틀린 복구 절차**다. 검증 대상은 arch 이름이 아니라
    // 「표식을 지우라고 안내하는가」이므로 그 부분만 단언한다.
    assert.match(out, /\.complete 표식 삭제/, '표식 삭제 복구 절차가 안내되지 않았다');
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

// ── 자원 고갈 판정 ────────────────────────────────────────────────────────────
//
// 왜 필요한가. 2026-08-07 조사에서 **엔진이 전부 정상인데도 CI 가 2.4배 느려지는** 실패 양식을
// A/B 로 확증했다. 동일 커밋 run 31139616352 를 `gh run rerun --job` 으로 재실행한 결과다.
//
//   issue-tracking    1,380s (swap 15,014M · load 34.43) → 554s (swap 3,556M · load 7.72)
//   identity-access     739s                             → 384s
//   테스트 케이스 수는 3,247 → 3,247 로 증감 0
//
// 위 엔진 점검은 이 상태를 **전부 통과시킨다** — 바이너리가 멀쩡히 실행되기 때문이다.
// 그래서 감속이 초록불인 채 일어나고 아무도 못 본다. 그 사각을 이 판정이 덮는다.
//
// ## ★ 왜 경고이지 실패가 아닌가
//
// 자원 고갈은 **사람이 자원을 회복시켜야** 풀린다. 여기서 실패시키면 회복 전까지 모든 작업이
// 멈춘다 — 위 「표식이 이미 없으면 경고」와 정확히 같은 구조의 교착이고, 그 실측이
// run 31139123013 이다. 판정은 **`exit` 코드를 건드리지 않는다.**
describe('러너 자원 고갈 판정', () => {
  test('자원이 넉넉하면 경고하지 않는다 (음성 대조군)', () => {
    const { code, out } = run(healthyRoot(), HEALTHY_RESOURCE);

    assert.equal(code, 0, `엔진·자원 모두 정상인데 실패했다\n${out}`);
    assert.doesNotMatch(
      out,
      /러너 자원 고갈/,
      `정상 상태에 경고가 뜨면 경고가 상시화되어 진짜 고갈이 묻힌다\n${out}`,
    );
  });

  test('★고갈이면 경고하되 exit 0 을 유지한다 (차단하면 자원 회복을 자기가 막는다)', () => {
    const { code, out } = run(healthyRoot(), EXHAUSTED);

    assert.equal(
      code,
      0,
      `자원 고갈로 차단하면 회복 작업까지 막힌다 — run 31139123013 교착과 동형이다\n${out}`,
    );
    assert.match(out, /러너 자원 고갈/, '경고가 없으면 이 판정이 통째로 공허하다');
    assert.match(
      out,
      /시간을 믿지 마라/,
      '왜 문제인지가 없으면 「그냥 느린 날」로 오독된다 — 그것이 이 판정을 만든 이유다',
    );
  });

  test('load 와 swap 이 각각 독립으로 판정을 켠다', () => {
    // 한쪽만 고갈인 두 경우. AND 로 잘못 짜면 둘 다 통과해버린다.
    const loadOnly = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_LOAD: '34.43',
    });
    const swapOnly = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_SWAP_MB: '15014',
    });

    assert.match(loadOnly.out, /러너 자원 고갈/, `load 단독 고갈을 놓쳤다\n${loadOnly.out}`);
    assert.match(swapOnly.out, /러너 자원 고갈/, `swap 단독 고갈을 놓쳤다\n${swapOnly.out}`);
  });

  test('판정은 코어 수 대비 비율이다 — 코어가 늘면 같은 load 가 정상이 된다', () => {
    // 절대 load 로 짜면 러너를 더 큰 머신으로 바꾸는 순간 조용히 의미가 바뀐다.
    // load 34.43 은 8코어에서 4.30배(고갈)지만 64코어에서는 0.54배(정상)다.
    const bigMachine = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_LOAD: '34.43',
      BTS_RUNNER_FAKE_NCPU: '64',
    });

    assert.doesNotMatch(
      bigMachine.out,
      /러너 자원 고갈/,
      `절대 load 로 판정하고 있다 — 러너 교체 시 판정이 조용히 어긋난다\n${bigMachine.out}`,
    );
  });

  test('★스왑도 물리 메모리 대비 비율이다 — 큰 머신에서 같은 MB 가 정상이 된다', () => {
    // load 를 비율로 짜 놓고 swap 만 절대 MB 로 두면 같은 논리를 절반만 적용한 것이 된다.
    // 8,000MB 는 16GB 머신에서 48.8%(정상 경계)지만 8GB 머신에서는 97.7%(고갈)다.
    // 실물 검증에서 드러난 결함이다 — 이 러너의 4,348MB 가 16GB 대비 26.5% 인데도 경고가 떴다.
    const bigMachine = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_SWAP_MB: '8000',
      BTS_RUNNER_FAKE_MEM_MB: '65536', // 64GB
    });
    const smallMachine = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_SWAP_MB: '8000',
      BTS_RUNNER_FAKE_MEM_MB: '8192', // 8GB — 같은 8,000MB 가 97.7%
    });

    assert.doesNotMatch(
      bigMachine.out,
      /러너 자원 고갈/,
      `절대 MB 로 판정하고 있다 — 큰 머신으로 옮기면 경고가 상시화되어 무시된다\n${bigMachine.out}`,
    );
    assert.match(
      smallMachine.out,
      /러너 자원 고갈/,
      `같은 8,000MB 라도 8GB 머신에서는 고갈이다 — 비율 판정이 안 되고 있다\n${smallMachine.out}`,
    );
  });

  test('★★측정값이 숫자가 아니어도 죽지 않는다 — awk 의 문자열 비교 함정', () => {
    // awk 에 `-v n="xyz"` 로 넘긴 값은 **문자열**이다. `n > 0` 은 숫자 비교가 아니라
    // 문자열 비교("xyz" > "0" → 참)가 되어, 0 나눗셈을 막으려던 삼항 가드를 **통과**한다.
    // 결과는 `awk: division by zero` 이고 exit 2 다.
    //
    // ★이것이 왜 치명적인가. 로컬 스크립트는 errexit 가 없어 조용히 넘어가지만,
    //   CI 워크플로우는 GitHub Actions 기본이 `bash -e` 라 **스텝이 죽는다.**
    //   runner-health 는 모든 워크플로우의 `needs:` 선행 잡이므로 CI 전체가 멈춘다 —
    //   자원 부족을 알리려던 장치가 CI 를 세우는, 이 PR 이 막으려던 것의 더 나쁜 판본이다.
    const garbage = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_RUNNER_FAKE_LOAD: 'abc',
      BTS_RUNNER_FAKE_NCPU: 'xyz',
      BTS_RUNNER_FAKE_SWAP_MB: 'zzz',
      BTS_RUNNER_FAKE_MEM_MB: 'qqq',
    });

    assert.equal(
      garbage.code,
      0,
      `측정값이 숫자가 아닐 때 죽었다 — CI 층에서는 이것이 러너 전체 정지다\n${garbage.out}`,
    );
    assert.doesNotMatch(
      garbage.out,
      /division by zero|awk:/,
      `awk 가 에러를 뱉었다 — 숫자 강제 변환(+0)이 빠졌다\n${garbage.out}`,
    );
  });

  test('★엔진 결함이 있으면 자원 경고가 그 exit 1 을 덮지 않는다', () => {
    // 자원 판정을 나중에 끼워 넣으면서 FAILED 를 리셋하는 실수가 가장 흔하다.
    const root = healthyRoot();
    fs.rmSync(path.join(root, 'externals/node24/bin/node'));

    const { code, out } = run(root, EXHAUSTED);

    assert.notEqual(code, 0, `엔진 결함이 자원 경고에 묻혔다 — 진짜 차단 사유를 잃었다\n${out}`);
    assert.match(out, /러너 환경 결함 — 코드 문제 아님/);
  });
});

describe('Docker 데몬 판정 (로컬 층)', () => {
  /**
   * 데몬 부재를 흉내내는 가짜 docker — `info` 만 실패한다.
   *
   * ★실제 데몬을 끄는 검증은 하지 않는다(개발 머신의 다른 작업을 끊는다).
   * `BTS_DOCKER_BIN` 이 그것을 대신하는 유일한 통로이고, 이 이음매가 없으면 아래 세 케이스가
   * **원리적으로 검증 불가능**해진다 — 문자열 존재 단언만 남는데, 이 파일 스스로가
   * 「그 방식은 거동 차이를 못 본다」고 적고 있다.
   */
  function fakeDockerDaemonDown(): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-docker-'));
    const bin = path.join(dir, 'docker');
    fs.writeFileSync(bin, '#!/bin/sh\nif [ "$1" = "info" ]; then exit 1; fi\nexit 0\n', {
      mode: 0o755,
    });
    return bin;
  }

  test('★데몬이 꺼져도 기본값은 경고다 — 차단하지 않는다', () => {
    // 프론트·판별식 작업은 데몬이 필요 없다. 여기서 막으면 프리플라이트가 고치려던 것보다
    // 큰 차단면을 새로 만든다.
    const { code, out } = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_DOCKER_BIN: fakeDockerDaemonDown(),
    });
    assert.equal(code, 0, `데몬 부재가 기본값에서 차단됐다 — 경고여야 한다\n${out}`);
    assert.match(out, /Docker 데몬이 꺼져 있다/, `데몬 부재를 말하지 않는다\n${out}`);
    assert.match(out, /차단하지 않는다/, `차단하지 않는다는 사실을 말하지 않는다\n${out}`);
  });

  test('★★BTS_REQUIRE_DOCKER=true 면 차단한다', () => {
    const { code, out } = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_DOCKER_BIN: fakeDockerDaemonDown(),
      BTS_REQUIRE_DOCKER: 'true',
    });
    assert.notEqual(code, 0, `데몬을 요구했는데 통과했다 — 차단 분기가 죽어 있다\n${out}`);
    assert.match(out, /러너 환경 결함 — 코드 문제 아님/, `배너가 없다\n${out}`);
  });

  test('★★차단 배너가 Docker 절(§7)을 가리킨다 — 엔진 절(§4)이 아니다', () => {
    // 데몬만 꺼졌는데 배너가 §4(엔진)를 안내하면 사람이 node·java 를 판다.
    // 이 파일이 없애려는 오진을 가드 자신이 재생산하는 경로다(독립 리뷰 적발).
    const { out } = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_DOCKER_BIN: fakeDockerDaemonDown(),
      BTS_REQUIRE_DOCKER: 'true',
    });
    const banner = out.split('\n').find((l) => l.includes('self-hosted-runner.md')) ?? '';
    assert.match(banner, /§7/, `배너가 Docker 절을 가리키지 않는다: ${banner}\n${out}`);
    assert.ok(
      !/§4/.test(banner),
      `데몬만 꺼졌는데 배너가 엔진 절(§4)을 가리킨다 — 엉뚱한 곳을 파게 된다: ${banner}`,
    );
  });

  test('★데몬이 응답하지 않으면 타임아웃으로 끊는다 (잡 전체를 물고 늘어지지 않는다)', () => {
    // 「데몬 기동 중」이면 소켓은 있고 응답만 없어 `docker info` 가 붙잡힌다. 그 상태를
    // 그대로 두면 잡 타임아웃에 걸려 **데몬을 쓰지 않는** 워크플로우까지 스킵된다.
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-docker-hang-'));
    const bin = path.join(dir, 'docker');
    fs.writeFileSync(bin, '#!/bin/sh\nif [ "$1" = "info" ]; then sleep 60; fi\nexit 0\n', {
      mode: 0o755,
    });

    const started = process.hrtime.bigint();
    const { code, out } = run(healthyRoot(), {
      ...HEALTHY_RESOURCE,
      BTS_DOCKER_BIN: bin,
      BTS_DOCKER_PROBE_TIMEOUT: '2',
    });
    const elapsedSec = Number(process.hrtime.bigint() - started) / 1e9;

    assert.ok(elapsedSec < 30, `타임아웃이 안 먹었다 — ${elapsedSec.toFixed(1)}초 걸렸다\n${out}`);
    assert.equal(code, 0, `응답 없음이 기본값에서 차단됐다 — 경고여야 한다\n${out}`);
    assert.match(out, /초 안에 없다/, `타임아웃 사실을 말하지 않는다\n${out}`);
  });
});
