# self-hosted 러너 엔진 헬스체크 + 런북 실패 모드 문서화

> slug: runner-engine-healthcheck
> type: chore (classify 오분류 `feature` 정정)
> agent: qa-engineer (classify 오분류 `backend-engineer` 정정)
> primary_bc: null (BC 무관)
> 생성: 2026-08-07

## Brief

2026-08-04 18:18 에 홈 폴더 대용량 파일 정리로 self-hosted 러너의 **실행 엔진 4개가 삭제**되어
**8/4~8/7 사흘간 모든 CI 가 0회 실행**됐다. 빨간불이 뜨긴 했으나 「테스트 실패」와 구분되지
않아 아무도 러너를 의심하지 않았고, 그 사이 #342·#343·#344 가 검증 없이 머지됐다.

같은 일이 또 나도 **사흘이 아니라 첫 실행에서** 잡히도록 조기 발견 장치를 넣고, 재발 원인
(사람의 용량 정리)을 런북에 명시한다.

배경 정본 — 메모리 `self-hosted-runner-node-binaries-swept`.

## 착수 전 실측 (2026-08-07)

### 무엇이 지워졌나 — 기준은 「이름」이 아니라 「홈 폴더 안 + 큰 파일」

| 파일 | 크기 | 위치 | 결과 |
|---|---|---|---|
| `externals/node24/bin/node` | 115M | 홈 안 | ❌ |
| `_tool/node/22.23.2/arm64/bin/node` | 112M | 홈 안 | ❌ |
| `externals/node20/bin/node` | 86M | 홈 안 | ❌ |
| `_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64/…/lib/modules` | ~130M | 홈 안 | ❌ |
| `_tool/Java_…/lib/ct.sym` | 10M | 홈 안 | ✅ 생존 |
| `/usr/local/bin/node` | **210M** | 홈 **밖** | ✅ 생존 |

210M 이 살고 86M 이 죽었으므로 **크기 단독 기준이 아니라 「홈 폴더 스코프 + 큰 파일」**이다.
4곳의 `bin/`·`lib/` 디렉터리 mtime 이 **전부 `Aug 4 18:18`** 로 동일 = 단일 시점 작업.
같은 10분 창에 `~/.cursor`·`~/.docker`·`~/.gradle` 등 홈 최상위 수십 개의 mtime 이 바뀌고
`.DS_Store` 가 생성됐다 = **GUI 도구가 홈 전체를 훑은 서명**. `/Applications/Disk Inventory.app`
설치돼 있고 데이터 볼륨 **83% 사용** 중이었다.

배제 — 러너 self-update(`_diag` 흔적 0건, 게다가 update 는 `_tool/` 을 안 건드린다) ·
저장소 스크립트(`actions-runner` 참조 0건) · Claude 세션(8/4 17~20시 세션 전수 확인, 파괴적
명령은 worktree 임시파일과 `.bts-cache` 뿐).

### ★ 이 결함은 「파일 존재 확인」으로 못 잡는다

| 대상 | 파일 상태 | 실행 |
|---|---|---|
| `externals/node24/bin/` | `corepack`·`npm`·`npx` 심볼릭 **존재** | `node` 자체가 없음 |
| `_tool/node/22.23.2/arm64` | `arm64.complete` 표식 **존재** → setup-node 가 **캐시 히트로 오판** | 시스템 node v22.14.0 으로 조용히 흘러내림 |
| `_tool/Java_…/bin/` | `java` 포함 30개 실행파일 **전부 존재** | `Failed setting boot class path` (`lib/modules` 부재) |

**세 경우 모두 `test -f` 는 통과한다.** 점검은 반드시 `node -v` / `java -version` 의
**실행 성공(exit 0)** 을 봐야 한다.

### ★★ 설계 핵심 전제 — 아직 실측 미완

「`actions/checkout` 앞의 순수 shell `run:` 스텝은 node 부재 상황에서도 실행된다」

**현재 근거는 정황이고 직접 실측이 아니다.**
- 사고 기간 실패 run 전수에서 **첫 스텝이 `Checkout`(JS 액션)이라** `run:` 스텝의 거동을 관측한
  사례가 **0건**이다. 어느 워크플로우도 checkout 앞에 `run:` 을 두지 않는다.
- 간접 근거 — 실패 run 에서 **`Set up job` 단계는 완료됐고** 에러가 `Checkout` 스텝에 귀속됐다.
  즉 node 해석이 **job 초기화 시점이 아니라 스텝 실행 시점**에 일어난다.
  `_diag/Worker_20260806-234940-utc.log` 에 `NodeScriptActionHandler` 가 **2회**만 등장한다
  (Checkout + Post Checkout) — `run:` 스텝용 핸들러는 별개다.
- **plan 단계에서 이 전제를 실측할 방법을 정한다.** 전제가 깨지면 CI 스텝 안은 이 결함을
  구조적으로 못 잡으므로 **설계 자체가 바뀐다**(아래 대안 B 로 이동).

### 현재 러너 상태 (복구 진행분)

| 대상 | 상태 |
|---|---|
| `externals/node20` | ✅ v20.20.2 (2026-08-07 복원, sha256 대조) |
| `externals/node24` | ✅ v24.18.0 (〃) |
| `_tool/node/22.23.2` | ✅ v22.23.2 (표식 삭제 → setup-node 자가 재설치) |
| `_tool/Java_…21` | ❌ **`Failed setting boot class path` — 미복구.** 표식 `arm64.complete` 잔존 |

Java 는 backend-ci 3개 잡이 `actions/setup-java@v4` 로 쓰므로 **다음 백엔드 PR 이 첫 희생자**다.

## 설계 후보 — plan 단계에서 확정

| | 안 | 장점 | 약점 |
|---|---|---|---|
| **A** | 각 워크플로우 checkout **앞**에 shell `run:` 헬스체크 | CI 가 스스로 잡음. 첫 실행에서 발견 | **전제 미검증**. 워크플로우 4개 전부 배선해야 하고 누락 시 공허 |
| **B** | `scripts/verify-runner-health.sh` + `/bts-start` 에서 호출 | 전제 불필요(Maxi 머신에서 실행). 작업 시작 시점에 발견 | Maxi 가 작업을 시작해야만 돈다. CI 단독 실행은 못 잡음 |
| **C** | A + B 병행 | 두 지점 모두 커버 | 비용 최대. 과한지 판단 필요 |

**판별식(모든 워크플로우에 배선됐는지 강제)을 추가할지도 plan 에서 비용 대비 효과로 판단한다** —
`two-lists-never-check-each-other` 양식이라 배선 누락이 곧 공허 가드가 된다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan

> **For agentic workers:** REQUIRED SUB-SKILL — `superpowers:subagent-driven-development`.
> 각 step 은 체크박스로 추적한다.

**Goal.** self-hosted 러너의 실행 엔진(node·java) 소실을 **사흘이 아니라 첫 기회에** 잡는다.

**Architecture.** 두 지점에서 본다. **B**(`scripts/verify-runner-health.sh` + `/bts-start` 배선)는
Maxi 머신에서 도는 **전제 무관 백스톱**이고, **A**(재사용 워크플로우 `runner-health.yml` +
4개 워크플로우가 `needs:` 로 연결)는 CI 가 스스로 잡는 층이다. 두 층 모두 **파일 존재가 아니라
`node -v` / `java -version` 의 exit 0** 을 본다. **C**(판별식)가 A 의 배선 누락을 막는다.

**Tech Stack.** POSIX shell · `node:test`(Node 22 타입 스트리핑) · GitHub Actions `workflow_call`.

### ★ plan 단계에서 새로 드러난 제약 — 인라인 복사본 문제

헬스체크는 `actions/checkout` **앞**에 있어야 하므로 그 시점에 **저장소가 아직 없다.**
따라서 `scripts/verify-runner-health.sh` 를 CI 에서 부를 수 없고, 순진하게 가면 **인라인 셸이
11개 잡(backend 5 · frontend 3 · infra 2 · workflow-scripts 1)에 복사**된다 —
PR #345 에서 없앤 「복사본 N벌 + 동기화 강제는 주석」 양식 그대로다.

**처방.** `.github/workflows/runner-health.yml` 을 `on: workflow_call` 재사용 워크플로우로 두고
각 워크플로우가 `uses: ./.github/workflows/runner-health.yml` 로 **호출**한다. 인라인 셸은
**저장소 전체에 1벌**이고, 나머지 잡은 `needs: runner-health` 로 매단다. 실패 시 의존 잡은
`skipped` 가 되고 run 전체가 `failure` 로 뜬다.

### ★ 미검증 전제 2건 — B 가 백스톱인 이유

| # | 전제 | 근거 | 깨지면 |
|---|---|---|---|
| P1 | 순수 shell `run:` 스텝은 node 부재 시에도 실행된다 | `_diag/Worker_20260806-234940-utc.log` 에 `NodeScriptActionHandler` 가 **2회**(Checkout + Post)만 등장 → node 해석은 **스텝 실행 시점**이고 `run:` 은 별개 핸들러. 사고 기간 `Set up job` 은 전부 완료 | A 무력 |
| P2 | `workflow_call` 해석은 node 를 안 쓴다 | 재사용 워크플로우는 GitHub 백엔드가 커밋에서 해석한다(러너가 내려받는 액션과 다른 경로) | A 무력 |

**완전 실측은 러너를 일부러 고장내야 하므로 범위 밖으로 둔다**(운영 중 CI 를 끊고, 동시 진행
중인 PR #346 과 맞부딪친다). 대신 **B 를 전제 무관 백스톱**으로 함께 넣어 A 가 무력이어도
감지가 비지 않게 한다. P1·P2 는 이 PR 머지 후 **다음 실제 사고 때 검증**되며, 그때까지
「A 는 미검증」이라고 런북에 명시한다.

### 파일 구조

| 파일 | 책임 |
|---|---|
| `scripts/verify-runner-health.sh` (신규) | 점검 로직 **단일 정본**. `BTS_RUNNER_ROOT` 로 루트 주입 가능(테스트·뮤테이션용) |
| `scripts/workflow/verify-runner-health.test.ts` (신규) | 위 스크립트의 exit code · 메시지 계약 |
| `.github/workflows/runner-health.yml` (신규) | `workflow_call` 재사용 워크플로우. 인라인 셸 1벌 |
| `.github/workflows/{backend,frontend,infra,workflow-scripts}-ci.yml` (수정) | `runner-health` 잡 호출 + 기존 잡에 `needs:` |
| `scripts/workflow/runner-healthcheck-wiring.test.ts` (신규) | 배선 판별식. `readdirSync` 런타임 훑기 |
| `.claude/skills/bts-start/SKILL.md` (수정) | Step 0 으로 B 호출 배선 |
| `docs/runbooks/self-hosted-runner.md` (수정) | §4 에 실패 모드 + 홈 정리 제외 경고 |

---

### Task 1. 헬스체크 스크립트 — 계약 테스트 먼저

**메타**.
- agent: `qa-engineer`
- files: [`scripts/workflow/verify-runner-health.test.ts`, `scripts/verify-runner-health.sh`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/verify-runner-health.test.ts`
- 스크립트를 `BTS_RUNNER_ROOT` 로 **가짜 러너 트리**에 겨눠 돌린다. 실행 성공을 보는지
  (파일 존재가 아니라) 확인하는 것이 이 테스트의 존재 이유다.

```ts
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SCRIPT = path.join(REPO_ROOT, 'scripts/verify-runner-health.sh');

/** exit code 와 stdout 을 함께 돌려준다. 스크립트는 실패해도 진단을 stdout 에 쓴다. */
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

/** 실행 가능한 가짜 엔진 4종을 갖춘 러너 트리를 만든다. */
function healthyRoot(): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bts-runner-'));
  const stub = (rel: string, body: string) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  };
  stub('externals/node20/bin/node', 'echo v20.20.2');
  stub('externals/node24/bin/node', 'echo v24.18.0');
  stub('_work/_tool/node/22.23.2/arm64/bin/node', 'echo v22.23.2');
  stub('_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64/Contents/Home/bin/java',
       'echo openjdk 21 1>&2');
  return root;
}

describe('러너 엔진 헬스체크 스크립트', () => {
  test('엔진 4종이 모두 실행되면 exit 0', () => {
    const { code } = run(healthyRoot());
    assert.equal(code, 0);
  });

  test('★파일은 있는데 실행이 실패하면 red — 존재 확인으로는 못 잡는 결함', () => {
    const root = healthyRoot();
    // JDK 사고 재현. bin/java 는 그대로 있고 실행만 실패한다(lib/modules 부재와 동형).
    const java = path.join(
      root, '_work/_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64/Contents/Home/bin/java');
    fs.writeFileSync(java, '#!/bin/sh\necho "Failed setting boot class path" 1>&2\nexit 1\n',
                     { mode: 0o755 });

    const { code, out } = run(root);
    assert.notEqual(code, 0, 'java 실행 실패를 놓쳤다');
    assert.ok(fs.existsSync(java), '전제 확인 — 파일은 존재한다');
    assert.match(out, /러너 환경 결함 — 코드 문제 아님/);
  });

  test('node 바이너리가 사라지면 red + 복구 절차를 안내한다', () => {
    const root = healthyRoot();
    fs.rmSync(path.join(root, 'externals/node24/bin/node'));

    const { code, out } = run(root);
    assert.notEqual(code, 0);
    assert.match(out, /externals\/node24/);
    assert.match(out, /sha256/, '복구 절차가 안내되지 않았다');
  });

  test('툴캐시 표식만 남고 알맹이가 없으면 red — setup-* 가 캐시 히트로 오판하는 상태', () => {
    const root = healthyRoot();
    fs.rmSync(path.join(root, '_work/_tool/node/22.23.2/arm64/bin/node'));
    fs.writeFileSync(path.join(root, '_work/_tool/node/22.23.2/arm64.complete'), '');

    const { code, out } = run(root);
    assert.notEqual(code, 0);
    assert.match(out, /arm64\.complete/, '표식 삭제 복구 절차가 안내되지 않았다');
  });
});
```

- [ ] **Step 1.1** 위 테스트 파일을 만든다.
- [ ] **Step 1.2** 실패 확인.
  `node --test scripts/workflow/verify-runner-health.test.ts`
  기대 — 4개 전부 FAIL (`scripts/verify-runner-health.sh` 부재).
  > 로컬 node 가 22.14 면 `--experimental-strip-types --no-warnings` 를 붙인다.
  > CI 는 22.23.2 라 불필요하다.

**GREEN**.
- 파일. `scripts/verify-runner-health.sh`

```sh
#!/usr/bin/env bash
# self-hosted 러너의 실행 엔진(node·java)이 살아 있는지를 실행으로 확인한다
#
# 왜 존재 확인이 아니라 실행인가. 2026-08-04 사고에서 세 경우 모두 `test -f` 는 통과했다 —
# externals 는 corepack·npm·npx 심볼릭이 남고 node 만 없었고, 툴캐시는 arm64.complete 표식만
# 남아 setup-* 가 캐시 히트로 오판했으며, JDK 는 bin 의 실행파일 30개가 전부 있는데
# lib/modules 만 없어 java -version 만 실패했다.
set -uo pipefail

ROOT="${BTS_RUNNER_ROOT:-$HOME/actions-runner-bts}"
FAILED=0

# 글로브가 안 맞으면 패턴 문자열이 그대로 남는 zsh/bash 차이를 없앤다.
shopt -s nullglob

report() {
  echo "❌ $1"
  echo "   복구. $2"
  FAILED=1
}

check_exec() {
  local label="$1" bin="$2" ; shift 2
  if [ ! -e "$bin" ]; then
    report "$label — 바이너리 부재 ($bin)" "$3"
    return
  fi
  if ! "$bin" "$@" >/dev/null 2>&1; then
    report "$label — 파일은 있으나 실행 실패 ($bin)" "$3"
    return
  fi
  echo "✅ $label"
}

# 1) 러너 내장 엔진 — 러너가 JavaScript 액션(actions/checkout 등)을 돌리는 데 쓴다.
for v in node20 node24; do
  check_exec "externals/$v" "$ROOT/externals/$v/bin/node" -v \
    "nodejs.org 공식 배포본을 SHASUMS256.txt 로 sha256 대조 후 bin/node 만 복원"
done

# 2) 툴 캐시 — setup-node / setup-java 가 심는다. 표식만 남으면 조용히 시스템 엔진으로 흘러내린다.
for bin in "$ROOT"/_work/_tool/node/*/*/bin/node; do
  check_exec "toolcache node" "$bin" -v \
    "해당 버전 디렉터리의 arm64.complete 표식을 지우면 setup-node 가 자가 재설치한다"
done

for bin in "$ROOT"/_work/_tool/Java_*/*/*/Contents/Home/bin/java; do
  check_exec "toolcache java" "$bin" -version \
    "해당 버전 디렉터리의 arm64.complete 표식을 지우면 setup-java 가 자가 재설치한다"
done

if [ "$FAILED" -ne 0 ]; then
  cat <<'MSG'

──────────────────────────────────────────────
러너 환경 결함 — 코드 문제 아님
이 PR 의 테스트는 한 줄도 실행되지 않았다.
자세한 내용. docs/runbooks/self-hosted-runner.md §4
──────────────────────────────────────────────
MSG
  exit 1
fi

echo "러너 엔진 정상."
```

- [ ] **Step 1.3** 스크립트를 만들고 `chmod +x scripts/verify-runner-health.sh`.
- [ ] **Step 1.4** 통과 확인. `node --test scripts/workflow/verify-runner-health.test.ts` → 4 pass.
- [ ] **Step 1.5** 실제 러너에도 겨눠 본다. `bash scripts/verify-runner-health.sh; echo "EXIT=$?"`
      기대 — 엔진 4종 ✅ 이고 `EXIT=0`. (Java 가 아직 재설치 전이면 red 가 정상이고,
      그 red 자체가 이 스크립트의 첫 실전 적중이다.)
- [ ] **Step 1.6** 커밋. `[skip ci]` 금지.

```bash
git add scripts/verify-runner-health.sh scripts/workflow/verify-runner-health.test.ts
git commit -m "test: 러너 엔진 헬스체크 계약 — 존재 아닌 실행으로 판정 (RED→GREEN)"
```

**검증**. `node --test scripts/workflow/verify-runner-health.test.ts` 4/4 pass.

---

### Task 2. 비-공허 확인 — 스크립트가 실제로 무는가

**메타**.
- agent: `qa-engineer`
- files: [`scripts/workflow/verify-runner-health.test.ts`]
- depends-on: [1]

Task 1 의 테스트가 **가짜 트리**를 쓰므로, 그 트리 조작이 실제로 판정을 뒤집는지 확인한다.
뒤집히지 않으면 단언이 공허하다.

- [ ] **Step 2.1** 뮤테이션 M1 — `check_exec` 의 실행 검사를 존재 검사로 되돌린다.
      `if ! "$bin" "$@" >/dev/null 2>&1; then` → `if [ ! -e "$bin" ]; then`
      기대 — 「파일은 있는데 실행 실패」 테스트가 **red**. 원복.
- [ ] **Step 2.2** 뮤테이션 M2 — 툴캐시 node 글로브를 지운다(루프 전체 제거).
      기대 — 「툴캐시 표식만 남고 알맹이 없음」 테스트가 **red**. 원복.
- [ ] **Step 2.3** 뮤테이션 M3 — 실패 메시지에서 `러너 환경 결함 — 코드 문제 아님` 문구를 지운다.
      기대 — 해당 단언이 **red**. 원복.
- [ ] **Step 2.4** 세 뮤테이션 결과를 plan 파일 `## 실측 결과` 절에 표로 기록한다.
      **red 가 안 나온 뮤테이션이 하나라도 있으면 그 단언은 공허하므로 테스트를 고친다.**

> ★ 원복은 반드시 **역방향 Edit** 으로 한다. `git checkout --` 은 **동시 진행 중인
> worktree(PR #346)의 미커밋 작업을 날린다** — [[parallel-wave-mutation-revert-destroys-peers]] ·
> [[mutation-test-requires-committed-baseline]].

**검증**. 뮤테이션 3종 전부 red 실측 + 원복 후 4/4 pass.

---

### Task 3. `/bts-start` 배선 — B 를 실제로 돌게 한다

**메타**.
- agent: `qa-engineer`
- files: [`.claude/skills/bts-start/SKILL.md`, `scripts/workflow/runner-healthcheck-wiring.test.ts`]
- depends-on: [1]

스크립트를 만들어 두고 **아무도 안 부르면** 그것이 가장 나쁜 공허 가드다.

- [ ] **Step 3.1** `.claude/skills/bts-start/SKILL.md` 의 `### Step 1. 작업 분류` **앞**에
      Step 0 을 넣는다.

````markdown
### Step 0. 러너 엔진 헬스체크 (필수)

작업을 시작하기 전에 self-hosted 러너의 실행 엔진이 살아 있는지 본다.
**여기서 red 면 이 작업의 CI 는 전부 무의미하다** — 먼저 러너를 고친다.

```bash
bash scripts/verify-runner-health.sh
```

exit 1 이면 출력의 복구 절차를 그대로 따르고, 초록이 될 때까지 Step 1 로 넘어가지 않는다.
배경. `docs/runbooks/self-hosted-runner.md` §4
````

- [ ] **Step 3.2** 배선 판별식을 만든다(파일은 Task 4 에서 A 배선까지 함께 채운다).
      지금은 **B 배선 단언 1건만** 넣는다.

```ts
test('bts-start 스킬이 러너 헬스체크를 호출한다', () => {
  const skill = fs.readFileSync(
    path.join(REPO_ROOT, '.claude/skills/bts-start/SKILL.md'), 'utf8');
  assert.match(
    skill, /bash scripts\/verify-runner-health\.sh/,
    'bts-start 가 헬스체크를 안 부른다 — 스크립트가 있어도 아무도 안 돌리면 공허 가드다.',
  );
});
```

- [ ] **Step 3.3** 실패 확인 → Step 3.1 적용 후 통과 확인.
- [ ] **Step 3.4** 커밋.

```bash
git add .claude/skills/bts-start/SKILL.md scripts/workflow/runner-healthcheck-wiring.test.ts
git commit -m "test: bts-start 의 러너 헬스체크 호출을 판별식으로 강제"
```

**검증**. `node --test scripts/workflow/runner-healthcheck-wiring.test.ts` pass.

---

### Task 4. A — 재사용 워크플로우 + 4개 배선 + 배선 판별식

**메타**.
- agent: `qa-engineer`
- files: [`.github/workflows/runner-health.yml`, `.github/workflows/backend-ci.yml`,
  `.github/workflows/frontend-ci.yml`, `.github/workflows/infra-ci.yml`,
  `.github/workflows/workflow-scripts-ci.yml`, `scripts/workflow/runner-healthcheck-wiring.test.ts`]
- depends-on: [3]

**RED** — 배선 판별식을 먼저 늘린다. 선례(`ci-runner-label-alignment.test.ts`)와 같이
**파일 목록을 상수로 두지 않고 `readdirSync` 로 훑고**, 훑기가 0건이면 모든 단언이 공허해지므로
**양성 대조군을 첫 단언**으로 둔다.

```ts
const WORKFLOW_DIR = '.github/workflows';
const HEALTH_WORKFLOW = 'runner-health.yml';
/** 재사용 워크플로우 자신은 호출 대상이 아니다. */
const MIN_CALLERS = 4;

function workflowFiles(): string[] {
  const dir = path.join(REPO_ROOT, WORKFLOW_DIR);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter((n) => n.endsWith('.yml') || n.endsWith('.yaml'))
    .sort();
}

/** 호출자여야 하는 워크플로우 = 재사용 워크플로우 자신을 뺀 전부. 목록을 손으로 안 적는다. */
function callerFiles(): string[] {
  return workflowFiles().filter((n) => n !== HEALTH_WORKFLOW);
}

test('판별식이 비어 있지 않다 (양성 대조군)', () => {
  assert.ok(
    callerFiles().length >= MIN_CALLERS,
    `호출자 후보를 ${callerFiles().length}개만 찾았다 — 훑기가 고장났다. ` +
      `0 이면 아래 단언이 전부 공허하게 통과한다.`,
  );
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, WORKFLOW_DIR, HEALTH_WORKFLOW)),
    '재사용 워크플로우가 없다.',
  );
});

test('모든 워크플로우가 러너 헬스체크를 호출한다', () => {
  const offenders = callerFiles().filter((name) => {
    const body = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW_DIR, name), 'utf8');
    return !body.includes(`uses: ./${WORKFLOW_DIR}/${HEALTH_WORKFLOW}`);
  });
  assert.deepEqual(
    offenders, [],
    `헬스체크를 안 부르는 워크플로우가 있다 — ${offenders.join(', ')}\n` +
      `그 워크플로우는 러너 엔진이 없어도 원인 불명 실패로만 뜬다.`,
  );
});

test('실행 잡은 헬스체크 잡을 needs 로 매단다', () => {
  const offenders: string[] = [];
  for (const name of callerFiles()) {
    const body = fs.readFileSync(path.join(REPO_ROOT, WORKFLOW_DIR, name), 'utf8');
    // `runs-on:` 을 가진 잡 = 실제 실행 잡. 그 수만큼 `needs: runner-health` 가 있어야 한다.
    const runsOn = (body.match(/^\s{4}runs-on:/gm) ?? []).length;
    const needs = (body.match(/^\s{4}needs:.*runner-health/gm) ?? []).length;
    if (runsOn !== needs) offenders.push(`${name} (runs-on ${runsOn} ≠ needs ${needs})`);
  }
  assert.deepEqual(
    offenders, [],
    `헬스체크에 안 매달린 잡이 있다 — ${offenders.join(', ')}`,
  );
});
```

- [ ] **Step 4.1** 위 3개 단언을 판별식에 추가하고 실패를 확인한다.
      기대 — 양성 대조군 FAIL(`runner-health.yml` 부재).
- [ ] **Step 4.2** `.github/workflows/runner-health.yml` 을 만든다. **인라인 셸은 저장소 전체에
      이 1벌뿐이다.**

```yaml
# 러너 실행 엔진(node·java) 생존을 checkout 앞에서 확인하는 재사용 워크플로우
#
# 왜 재사용 워크플로우인가. 헬스체크는 actions/checkout **앞**에 있어야 하는데 그 시점엔
# 저장소가 없어 scripts/verify-runner-health.sh 를 부를 수 없다. 각 잡에 인라인으로 넣으면
# 11개 복사본이 되고 동기화 강제가 사라진다(PR #345 에서 없앤 양식). 호출로 1벌만 둔다.
#
# ⚠️ 이 워크플로우의 전제 2건은 **미검증**이다 — ① 순수 shell run: 스텝이 node 부재 시에도
# 실행되는가 ② workflow_call 해석이 node 를 안 쓰는가. 완전 실측은 러너를 일부러 고장내야
# 하므로 하지 않았다. 그래서 scripts/verify-runner-health.sh + /bts-start 배선이
# **전제 무관 백스톱**으로 함께 있다. docs/runbooks/self-hosted-runner.md §4
name: runner-health

on:
  workflow_call:

jobs:
  check:
    name: 러너 엔진 점검
    runs-on: [self-hosted, bts-local]
    timeout-minutes: 3
    steps:
      # ★ 반드시 순수 shell 이다. uses: 를 쓰면 그 액션을 돌리는 데 node 가 필요해
      #   정확히 이 결함 상황에서 이 잡이 먼저 죽는다.
      - name: 러너 엔진 점검 (node · java)
        run: |
          ROOT="$HOME/actions-runner-bts"
          FAILED=0
          shopt -s nullglob

          report() { echo "❌ $1"; echo "   복구. $2"; FAILED=1; }

          check_exec() {
            local label="$1" bin="$2" flag="$3" fix="$4"
            if [ ! -e "$bin" ]; then report "$label — 바이너리 부재 ($bin)" "$fix"; return; fi
            if ! "$bin" "$flag" >/dev/null 2>&1; then
              report "$label — 파일은 있으나 실행 실패 ($bin)" "$fix"; return
            fi
            echo "✅ $label"
          }

          for v in node20 node24; do
            check_exec "externals/$v" "$ROOT/externals/$v/bin/node" -v \
              "nodejs.org 공식 배포본을 SHASUMS256.txt 로 sha256 대조 후 bin/node 만 복원"
          done
          for bin in "$ROOT"/_work/_tool/node/*/*/bin/node; do
            check_exec "toolcache node" "$bin" -v \
              "그 버전 디렉터리의 arm64.complete 표식 삭제 → setup-node 자가 재설치"
          done
          for bin in "$ROOT"/_work/_tool/Java_*/*/*/Contents/Home/bin/java; do
            check_exec "toolcache java" "$bin" -version \
              "그 버전 디렉터리의 arm64.complete 표식 삭제 → setup-java 자가 재설치"
          done

          if [ "$FAILED" -ne 0 ]; then
            echo ""
            echo "─────────────────────────────────────────"
            echo "러너 환경 결함 — 코드 문제 아님"
            echo "이 run 의 테스트는 한 줄도 실행되지 않는다."
            echo "docs/runbooks/self-hosted-runner.md §4"
            echo "─────────────────────────────────────────"
            exit 1
          fi
          echo "러너 엔진 정상."
```

- [ ] **Step 4.3** 4개 워크플로우 각각에 호출 잡을 추가하고, `runs-on:` 을 가진 **모든** 잡에
      `needs: runner-health` 를 넣는다. 이미 `needs:` 가 있는 잡은 배열에 더한다
      (예. `needs: [runner-health, modules]`).

```yaml
jobs:
  runner-health:
    uses: ./.github/workflows/runner-health.yml

  lint:                      # ← 기존 잡. needs 한 줄만 추가한다
    needs: runner-health
    runs-on: [self-hosted, bts-local]
```

- [ ] **Step 4.4** 판별식 통과 확인. `node --test scripts/workflow/runner-healthcheck-wiring.test.ts`
- [ ] **Step 4.5** 기존 판별식 회귀 없음 확인. `node --test scripts/workflow/*.test.ts` → 전량 pass.
      특히 `ci-runner-label-alignment` 가 새 워크플로우의 `runs-on` 도 보게 되므로
      `[self-hosted, bts-local]` 을 쓴 것이 맞는지 여기서 걸린다.
- [ ] **Step 4.6** 커밋.

```bash
git add .github/workflows scripts/workflow/runner-healthcheck-wiring.test.ts
git commit -m "test: 러너 헬스체크 재사용 워크플로우 + 4개 배선 판별식"
```

**검증**. 판별식 전량 pass + 이 PR 의 실제 CI 에서 `runner-health` 잡이 **초록으로 실행**되는 것을
`gh pr checks` 로 확인(= P1·P2 의 **정상 경로** 실측. 고장 경로는 범위 밖).

---

### Task 5. 비-공허 확인 — 배선 판별식이 실제로 무는가

**메타**.
- agent: `qa-engineer`
- files: [`scripts/workflow/runner-healthcheck-wiring.test.ts`]
- depends-on: [4]

- [ ] **Step 5.1** 뮤테이션 M4 — `infra-ci.yml` 의 `uses: ./.github/workflows/runner-health.yml`
      줄을 지운다. 기대 — 「모든 워크플로우가 호출한다」가 **red**. 역방향 Edit 으로 원복.
- [ ] **Step 5.2** 뮤테이션 M5 — `frontend-ci.yml` 의 `typecheck` 잡에서 `needs: runner-health`
      한 줄만 지운다. 기대 — 「needs 로 매단다」가 **red**(`runs-on 3 ≠ needs 2`). 원복.
- [ ] **Step 5.3** 뮤테이션 M6 — `runner-health.yml` 파일명을 임시로 바꾼다.
      기대 — **양성 대조군**이 red. 원복.
- [ ] **Step 5.4** 결과를 plan `## 실측 결과` 에 표로 기록. red 가 안 나온 것이 있으면 단언을 고친다.

**검증**. 뮤테이션 3종 전부 red + 원복 후 전량 pass.

---

### Task 6. 런북 문서화 + 트리거 정합 확인

**메타**.
- agent: `qa-engineer`
- files: [`docs/runbooks/self-hosted-runner.md`, `.github/workflows/workflow-scripts-ci.yml`]
- depends-on: [4]

- [ ] **Step 6.1** `docs/runbooks/self-hosted-runner.md` §4 끝(§5 앞)에 절을 추가한다.

```markdown
- **★러너는 켜져 있는데 엔진만 없을 수 있다 (2026-08-04 실측).** 홈 폴더 용량 정리로
  `~/actions-runner-bts` 아래 **큰 파일**이 지워지면 러너는 `online` 인데 모든 잡이
  `Checkout` 에서 죽는다. 실제로 `externals/node20`·`externals/node24`·툴캐시 `node`·
  툴캐시 JDK 의 `lib/modules` 4개가 사라져 **8/4~8/7 사흘간 CI 가 0회 실행**됐고,
  그 사이 #342·#343·#344 가 검증 없이 머지됐다.
  - **판별.** `gh run view <id> --log-failed | grep '##\[error\]'` 로 **어느 스텝에서
    죽었는지부터** 본다. `Checkout` 이면 코드가 아니라 러너다.
  - **점검.** `bash scripts/verify-runner-health.sh` (exit 0 이어야 정상).
    `/bts-start` Step 0 이 매 작업 시작 시 자동으로 돌린다.
  - **★파일 존재 확인으로는 못 잡는다.** `externals` 는 `corepack`·`npm`·`npx` 심볼릭이
    남고 `node` 만 없었고, 툴캐시는 `arm64.complete` 표식만 남아 `setup-*` 가 캐시 히트로
    오판했으며, JDK 는 `bin` 의 실행파일 30개가 전부 있는데 `lib/modules` 만 없었다.
  - **복구.** 툴캐시는 해당 버전 디렉터리의 `arm64.complete` 표식만 지우면 `setup-*` 가
    자가 재설치한다(바이너리를 손으로 갖다 놓지 말 것). `externals` 는 nodejs.org 공식
    배포본을 `SHASUMS256.txt` 로 대조 후 `bin/node` 만 복원한다.
  - **⚠️ 예방은 습관뿐이다.** 홈 폴더를 용량 정리할 때 **`~/actions-runner-bts` 를 제외**한다.
    이 폴더는 다 합쳐 350MB 남짓이라 지워도 공간 이득이 거의 없는데 CI 전체가 멈춘다.
  - **⚠️ CI 헬스체크 잡(`runner-health.yml`)의 전제는 미검증이다** — 순수 shell `run:` 스텝이
    node 부재 시에도 실행되는지를 직접 실측하지 않았다(러너를 일부러 고장내야 한다).
    그래서 `scripts/verify-runner-health.sh` + `/bts-start` 배선이 전제 무관 백스톱으로 있다.
```

- [ ] **Step 6.2** §3 판별표에 행을 더한다.

```markdown
| 잡 `failure`, 실패 스텝이 `Checkout` | **러너 엔진 부재** → §4 「러너는 켜져 있는데 엔진만 없을 수 있다」 |
```

- [ ] **Step 6.3** 트리거 정합 확인. 새 입력 경로가 `workflow-scripts-ci.yml` 의 `paths` 에
      이미 덮이는지 본다.
      - `scripts/workflow/**` ✅ 이미 있음 · `.github/workflows/**` ✅ 이미 있음
      - `.claude/skills/**` ✅ 이미 있음 · `docs/**` ✅ 이미 있음
      - **`scripts/verify-runner-health.sh` 는 `scripts/workflow/**` 에 안 덮인다** →
        `paths` 의 **`pull_request` · `push` 양쪽**에 `'scripts/verify-runner-health.sh'` 를 더한다.
        한쪽만 넣으면 봉인이 절반만 닫힌다([[discriminant-input-list-vs-ci-trigger-list]]).
- [ ] **Step 6.4** 문서 인덱스 정합. `node scripts/build-doc-index.mjs --check` → EXIT 0.
- [ ] **Step 6.5** 커밋.

```bash
git add docs/runbooks/self-hosted-runner.md .github/workflows/workflow-scripts-ci.yml
git commit -m "docs: 러너 엔진 소실 실패 모드 + 홈 정리 제외 경고 (런북 §4)"
```

**검증**. `node --test scripts/workflow/*.test.ts` 전량 pass · `build-doc-index --check` EXIT 0 ·
`bash scripts/verify-master-plan.sh` EXIT 0.

---

## Plan 메타

- task 수. **6**
- 예상 시간. 직렬 약 25분 (task 당 3~5분), wave 적용 시 약 15분 (예상 wave 3)
- 구현 규율. **TDD** (chore 이지만 판별식·스크립트 모두 계약이 있으므로 red-first 강제)
- 병렬 dispatch. Task 1 → (2, 3) → 4 → (5, 6). `files` 교집합으로 2·3 은 자동 직렬화될 수 있다
- 추가 검증. `node --test scripts/workflow/*.test.ts` · `build-doc-index --check` ·
  `verify-master-plan.sh` · 이 PR 자체의 CI 에서 `runner-health` 잡 초록 확인

### Self-Review (writing-plans §Self-Review)

**1. 요구사항 커버리지.**

| 요구 | task |
|---|---|
| (1) checkout 앞 shell 헬스체크 | 4 (재사용 워크플로우로 인라인 1벌) |
| (2) 존재 아닌 실행 판정 | 1 (계약 테스트가 이걸 못박음) · 2 (뮤테이션 M1) |
| (3) 「코드 문제 아님」 + 복구 절차 로그 | 1 (스크립트) · 4 (워크플로우) · 2 (뮤테이션 M3) |
| (4) 런북 문서화 + 홈 정리 제외 경고 | 6 |
| (5) 배선 판별식 여부 판단 | **넣는다** — 선례(`ci-runner-label-alignment`)가 있어 한계 비용이 낮고, 배선 누락이 곧 공허 가드라 안 넣으면 A 가 조용히 죽는다. Task 3·4·5 |

**2. 플레이스홀더 스캔.** TBD·TODO·「적절히 처리」 0건. 모든 코드 step 에 실제 코드가 있다.

**3. 타입·이름 일관성.** `check_exec` · `report` · `BTS_RUNNER_ROOT` · `runner-health.yml` ·
`runner-healthcheck-wiring.test.ts` 가 task 1·3·4·5·6 에서 동일하게 쓰인다.
스크립트는 `check_exec "$label" "$bin" "$flag" "$fix"` 4인자로 통일했다.

**4. 남은 위험.** P1·P2 미검증(위 표) — B 백스톱으로 완화하고 런북에 명시한다.
새 잡 4개가 붙으므로 러너 1대 직렬 벽시계가 **잡당 약 15초 × 4 = 약 1분** 늘어난다.

## 실측 결과

### Task 1~2 (2026-08-07)

**RED→GREEN.** 테스트 4건이 스크립트 부재로 4/4 fail → 스크립트 작성 후 4/4 pass.
커밋 — `test: 러너 엔진 헬스체크 계약 — 존재 아닌 실행으로 판정 (RED→GREEN)`.

**★ 첫 실행에서 실제 결함을 잡았다.** 실제 러너에 겨눈 `bash scripts/verify-runner-health.sh`
결과가 `EXIT=1` 이고, 잡힌 것이 **아직 복구 안 된 Java** 였다.

```
✅ externals/node20
✅ externals/node24
✅ toolcache node (22.23.2)
❌ toolcache java (21.0.11-10.0.LTS) — 파일은 있으나 실행 실패
```

**「파일은 있으나 실행 실패」** — 이 스크립트가 존재 확인 대신 실행 확인을 하는 바로 그 이유가
첫 실전에서 증명됐다. `test -f` 였다면 초록이었다.

### 뮤테이션 3종 — 전부 red 실측

| # | 뮤테이션 | 기대 red | 결과 |
|---|---|---|---|
| M1 | 실행 검사를 무력화 (`if ! "$bin" "$flag"` → `if false`) = 존재 확인만 남김 | 「파일은 있는데 실행 실패」 | ✅ red (pass 3 / fail 1) |
| M2 | 툴캐시 글로브를 **디렉터리 → 바이너리 경로**로 되돌림 (`*/*/bin/node`) | 「툴캐시 표식만 남고 알맹이 없음」 | ✅ red (pass 3 / fail 1) |
| M3 | 실패 문구에서 `러너 환경 결함 — 코드 문제 아님` 제거 | 「파일은 있는데 실행 실패」의 문구 단언 | ✅ red (pass 3 / fail 1) |

**★ M2 가 설계 판단의 하중을 증명했다.** 글로브를 바이너리 경로에 걸면 **바이너리가 지워지는
바로 그 순간 글로브가 0건이 되어 루프가 안 돌고 조용히 통과**한다 — 잡으려던 결함이 정확히
가드를 무력화하는 양식이다. 그래서 아직 남아 있는 **버전 디렉터리**를 훑고 그 안의 바이너리를
검사한다. 이 한 줄 차이가 가드의 생사를 가른다.

원복은 전부 **역방향 Edit** 으로 했다(`git checkout --` 은 동시 진행 중인 PR #346 worktree 를
건드릴 수 있다). 원복 후 4/4 pass 재확인.

### ★ 부수 발견 — Edit 도구가 셸 스크립트의 실행 비트를 떨어뜨린다

뮤테이션 원복 후 `git diff` 에 파일이 남아 있었는데 **내용 0줄 변경 · `old mode 100755 →
new mode 100644`** 였다. Edit 이 파일을 다시 쓰면서 exec 비트가 사라진 것이다. 인덱스는
100755 로 정상이었으므로 `git checkout -- <파일>` 로 모드까지 복구했다(내용 diff 가 0 이라
소실 위험 없음). **셸 스크립트를 Edit 으로 고친 뒤에는 `git diff` 의 mode 줄을 확인해야 한다.**

### Task 3~6 (2026-08-07)

**배선.** `runner-health.yml`(재사용 워크플로우, 인라인 셸 **저장소 전체 1벌**) 신설 +
4개 워크플로우가 `uses:` 로 호출하고 **`runs-on` 을 가진 잡 9개 전부**에 `needs: runner-health`.

| 워크플로우 | 실행 잡 | 배선 |
|---|---|---|
| `backend-ci.yml` | modules · assembly · lint | 3/3 |
| `frontend-ci.yml` | lint · typecheck · test | 3/3 |
| `infra-ci.yml` | nginx-log-masking · springdoc-not-exposed | 2/2 |
| `workflow-scripts-ci.yml` | discriminants | 1/1 |

**RED→GREEN.** 배선 판별식 4단언 중 A 3건이 RED(`runner-health.yml` 부재) → 배선 후 4/4 pass.

### 뮤테이션 4종 — 전부 red 실측

| # | 뮤테이션 | 기대 red | 결과 |
|---|---|---|---|
| M-B | `/bts-start` 의 `bash scripts/verify-runner-health.sh` 를 `echo` 로 교체 | B 백스톱 단언 | ✅ red |
| M4 | `infra-ci.yml` 의 `uses: ./.github/workflows/runner-health.yml` 제거 | 「모든 워크플로우가 호출」 | ✅ red |
| M5 | `frontend-ci.yml` `typecheck` 의 `needs: runner-health` 한 줄 제거 | 「needs 로 매단다」 (`runs-on 3 ≠ needs 2`) | ✅ red |
| M6 | 훑기 대상을 없는 디렉터리로 (`workflows` → `workflows-gone`) | **양성 대조군** | ✅ red |

**★★ M6 가 양성 대조군의 존재 이유를 그대로 증명했다.** 훑기가 0건이 되자
**단언 2·3 이 「ok」로 공허하게 통과**했고 오직 대조군만 잡았다. 대조군이 없었다면
「모든 워크플로우가 헬스체크를 부른다」가 **워크플로우를 하나도 안 읽은 채 초록**이 된다.

원복은 전부 역방향 Edit. 원복 후 `git diff` **비어 있음** 확인.

### 트리거 정합 (Task 6.3)

`runner-healthcheck-wiring` 판별식의 입력 4종 중 `.github/workflows/**` ·
`scripts/workflow/**` · `.claude/skills/**` 은 이미 덮였으나
**`scripts/verify-runner-health.sh` 는 어디에도 안 덮였다**(기존은 `scripts/workflow/**` 와
`scripts/doc-index/**` 뿐). `pull_request` · `push` **양쪽**에 추가했다 — 한쪽만 넣으면
봉인이 절반만 닫힌다.

### 최종 검증

| 항목 | 값 |
|---|---|
| `node --test scripts/workflow/*.test.ts` | **EXIT 0 · 104/104 pass** (기준선 96 + 신규 8) |
| `bash scripts/verify-runner-health.sh` (실제 러너) | Java 재설치 전에는 EXIT 1 로 **실결함 적발**, node 3종 ✅ |
| `node scripts/build-doc-index.mjs` | EXIT 0 · 고아 0 · 깨진 링크 0 |
| `bash scripts/verify-master-plan.sh` | **EXIT 0** · FR 139/139 |
| 커밋 메시지 `[skip ci]` | **0건** (squash 본문 승격 함정 회피) |

## ★★ 실제 CI 가 설계 결함을 잡았다 — 교착 (run 31139123013)

### P1 · P2 전제가 실측으로 확인됐다

plan 단계에서 「미검증」으로 남기려던 두 전제가 **첫 실행에서 그대로 증명**됐다.

```
Uses: maxihan1/BTS/.github/workflows/runner-health.yml@refs/pull/347/merge   ← P2
✅ externals/node20 / ✅ externals/node24 / ✅ toolcache node                  ← P1 (run: 스텝 실행됨)
❌ toolcache java (21.0.11-10.0.LTS) — 파일은 있으나 실행 실패
runner-health / 러너 엔진 점검   failure
nginx 접속로그 마스킹 봉인        skipped      ← 차단이 정확히 동작
springdoc 외부 미노출 봉인        skipped
```

즉 **CI 층은 무력하지 않다.** 다만 「고장 경로(node 부재)」는 여전히 미실측이고 —
이 실행은 「엔진은 있으나 하나가 죽은」 경로였다 — 그건 러너를 일부러 고장내야 확인된다.

### 그리고 교착이 드러났다

Java 표식은 **이미 지워진 상태**였다(= `setup-java` 가 캐시 미스로 자가 재설치할 예정).
그런데 헬스체크가 그것을 실패로 보고 **재설치를 수행할 바로 그 잡을 막았다.**
**영원히 초록이 될 수 없는 상태**다. 「고장을 막으려다 치유까지 막는」 양식.

### 봉합 — 표식 게이팅

`check_exec` 에 다섯째 인자(완료 표식 경로)를 더해 툴 캐시에만 게이팅을 건다.

| 표식 | 실행 | 판정 | 왜 |
|---|---|---|---|
| 있음 | 실패 | **❌ 차단** | `setup-*` 가 캐시 히트로 오판해 조용히 흘러내린다 — 2026-08-04 의 그 상태 |
| 없음 | 실패 | **⚠️ 경고만** | 어차피 캐시 미스로 자가 재설치된다. 막으면 치유를 막는다 |
| — | 성공 | ✅ | |

`externals` 는 표식 개념이 없고 **자가 치유도 안 되므로** 게이팅 없이 항상 차단이다.
RED(신규 테스트 1건 fail) → GREEN(5/5 pass) → 실제 러너 `EXIT=0` + 경고 출력 확인.
재실행 CI 에서 **4개 워크플로우의 헬스체크 잡 전부 pass**.

### ★ 구조적 제약 — 판정 규칙이 두 벌이고 통합이 불가능하다

CI 층은 checkout **앞**에서 돌아야 해서 저장소의 스크립트를 부를 수 없다. 그래서 같은 판정이
`scripts/verify-runner-health.sh` 와 `runner-health.yml` 인라인, **두 곳**에 존재한다 —
이 저장소가 반복해 당한 「서로를 안 보는 두 목록」이고, 이번엔 **완전 dedup 이 구조적으로 불가능**하다.

처방으로 **하중을 받는 술어 7개**가 양쪽에 다 있는지를 판별식으로 봉인했다
(실행 판정 · 표식 게이팅 2종 · 글로브 2종 · 표식 파생 · 진단 문구).
**이 판별식은 만들자마자 자기 자신의 낡은 불변식 문자열을 잡았다** — 리팩터링으로 조건문이
`if ! ...` 에서 `if [ ! -e ] || ! ...` 로 바뀌었는데 불변식은 옛 형태였다.

**뮤테이션 M7** — 워크플로우 인라인에서 `marker="${5:-}"` 만 `marker=""` 로 바꾸니
정합 판별식이 **red**. 한쪽만 고치는 것이 실제로 차단된다.

### 작업 중 사고 1건 — `git checkout --` 이 방금 만든 수정을 되돌렸다

실행 비트 복구 목적으로 `git checkout -- scripts/verify-runner-health.sh` 를 넣었는데
**같은 명령이 미커밋 GREEN 수정을 통째로 날렸다.** 유닛은 되돌리기 **전에** 돌아서 초록이었고
실제 러너 실행만 되돌린 버전으로 돌아 red 였다 — **한 명령 안에서 상태가 갈린 것**이라
결과만 보면 원인을 오독하기 쉽다. 재적용으로 복구.
원인은 [[mutation-test-requires-committed-baseline]] 의 동형이고, 실행 비트가 떨어진 원인은
**Edit 도구가 파일을 다시 쓰면서 mode 를 100644 로 바꾸기** 때문이다.
**셸 스크립트를 Edit 으로 고친 뒤에는 `git diff` 의 mode 줄을 본다.**

## 리뷰 결과 (← /bts-review-plan 채움)
