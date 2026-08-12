# 러너의 Docker 데몬 의존을 프리플라이트로 드러내고 nginx 봉인 오진을 종료 코드로 가른다

> slug: runner-docker-preflight
> type: chore (fast-track — 게이트 1 생략, 게이트 2 만 정지)
> agent: backend-engineer
> 생성: 2026-08-12
> 부채. TODOS.md §인프라 — self-hosted 러너가 Docker 데몬 상태에 은밀히 의존한다 (매핑 30)

## Brief

**무엇.** `infra-ci` 와 `backend-ci` 는 Docker 데몬이 떠 있어야만 통과하는데 **떠 있는지를
아무도 먼저 확인하지 않는다.** 데몬이 꺼져 있으면 두 워크플로우가 **코드와 무관하게** 빨간불이
되고, 로그만 보면 「테스트가 깨졌다」로 읽힌다.

**실측 (2026-08-12 · PR #367 머지 직후).** 상관 **100%** — 실패한 잡 5건은 전부 Docker 준비
완료(07:49:32 UTC) **이전**에 시작했고, 이후 시작한 잡은 전부 통과했다. Docker 를 켜고
`scripts/verify/nginx-log-masking.sh` 를 **코드 무변경 상태로** 재실행하니 `EXIT=5 → 0` 이었다.
변수는 데몬 하나뿐이었다.

**★핵심은 오진 유도다.** `infra-ci` 는 「이 설정으로 배포하면 프론트 전체가 뜨지 않는다」고
말한다. **설정이 멀쩡한데 그렇게 말한다.** `nginx-log-masking.sh` 가 `command -v docker` 부재만
`exit 7` 로 구분하고 **데몬 부재는 문법 오류와 같은 `exit 5`** 로 뭉개기 때문이다. 그 파일이
「docker 부재를 SKIP 으로 넘기지 않는다 — 조용한 스킵은 vacuous 통과 경로다」라고 적어 둔
바로 그 자리에서, **CLI 존재와 데몬 가동을 구분하지 않아** 반대 방향 오진을 만든다.

**왜 여태 안 보였나.** `infra-ci` 마지막 성공은 2026-08-10(`53c83f79d`). 그 뒤 머지된
#362·#363·#364·#365·#366·#375 는 `paths` 필터에 안 걸려 **infra-ci 가 아예 안 돌았다.**
그 사이 어느 시점에 데몬이 꺼졌고, 8/10 이후 처음 도는 PR(#367)이 그것을 뒤집어썼다.

### 채택 처방 — ① + ② (장부가 「①만 하고 닫지 말 것」을 명시)

- **①** `nginx-log-masking.sh` 가 **데몬 가동을 따로 확인**하고 전용 종료 코드로 가른다.
  오진은 사라지지만 여전히 빨간불이다 — **그게 맞다. 조용한 스킵은 금지다.**
- **②** `runner-health` 를 확장해 **데몬 프리플라이트**를 둔다. 데몬이 없으면 그 잡만 빨간불이
  되고 뒤 잡은 스킵돼, **12개 잡이 줄줄이 오진 실패하는 비용**을 없앤다.
- **③**(러너 부팅 시 Docker 동반 기동)은 머신 상태 관리라 자동 검증이 어려워 이번 범위 밖.
  런북에 남긴다.

### 착수 전 실측한 제약 3건 (설계를 바꾼다)

1. **`runner-health.yml` 은 `on: workflow_call` 이고 입력이 없다.** `frontend-ci` ·
   `backend-ci` · `infra-ci` · `workflow-scripts-ci` **넷 다** `needs: runner-health` 다.
   그런데 **프론트와 워크플로우 판별식은 Docker 가 필요 없다** — 무조건 요구하면 데몬이 꺼진
   동안 그 PR 들까지 부당하게 막는다. ⇒ **`inputs.require_docker` 로 갈라야 한다.**

2. **두 층은 구조상 dedup 이 불가능하다.** `runner-health.yml` 의 인라인 블록은 checkout
   **앞**이라 저장소 파일을 못 부른다(node 가 없으면 checkout 자체가 죽으므로 앞에 있어야 한다).
   그래서 `scripts/verify-runner-health.sh` 와 **두 벌**로 존재하고,
   `runner-healthcheck-wiring.test.ts` 의 `INVARIANTS` 가 **같은 문자열이 양쪽에 다 있는지**로
   갈라짐을 막는다. ⇒ 데몬 판정을 넣으면 **양쪽에 동형으로 넣고 INVARIANTS 에 등재**해야 한다.

3. **판별식은 워크플로우 목록을 런타임에 훑는다**(`callerFiles()` = `runner-health.yml` 을 뺀
   전부). 즉 **모든 워크플로우가 헬스체크를 불러야 한다**는 계약이 이미 있고 `MIN_CALLERS = 4`
   가 훑기 고장을 막는다. 입력을 추가해도 이 계약을 깨면 안 된다.

### ★설계 갈림길 — 로컬 층의 Docker 판정은 차단인가 경고인가

CI 층은 `require_docker` 입력으로 가른다. 그런데 로컬 층(`/bts-start` Step 0)에서 Docker 부재를
**차단**으로 두면 프론트 작업까지 막힌다. 반대로 **경고 전용**으로 두면 두 층의 판정이 갈라져
`INVARIANTS` 의 동형 계약이 형식만 남는다.

⇒ **같은 판정 함수를 양쪽에 두고 「요구 여부」만 밖에서 주입한다.** 로컬은
`BTS_REQUIRE_DOCKER` 환경변수(기본 미설정 = 경고), CI 는 워크플로우 입력. 판정 코드 자체는
한 글자도 다르지 않으므로 `INVARIANTS` 가 실질을 지킨다.

**선례.** 이 파일의 자원 점검이 이미 같은 구조다 — 경고 전용이고 `BTS_RUNNER_FAKE_*` 이음매로
주입해 검증한다. 그 이음매가 없으면 「그 층은 검증 불가능해진다」고 `INVARIANTS` 주석이 적고 있다.

### 비-공허 짝 (필수)

- 데몬 부재를 **주입**할 이음매가 없으면 이 처방은 영영 못 잰다. 실제 Docker 를 끄는 검증은
  불가하므로 `BTS_DOCKER_PROBE`(또는 동등물) 이음매를 판정 코드에 심는다.
- `nginx-log-masking.sh` 는 이미 `BTS_*` 계열 이음매 관례가 있는지 확인하고 맞춘다.
- 뮤테이션으로 **①의 종료 코드 분리**와 **②의 프리플라이트 차단**이 각각 red 가 나는지 본다.

### 분류 오버라이드 기록

`classify-task.ts` 원출력은 `type=backend` 였다(신호 0 → 기본값). 이 작업은
`.github/workflows/**` 와 `scripts/**` 만 건드리고 Kotlin 모듈·BC 를 하나도 손대지 않는다.
부채 상환(인프라 CI)이므로 `chore` 로 고정 — 같은 계열인 PR #366·#377 선례.

## 도메인 정리 (← /bts-domain 채움)

_fast-track (chore) — 생략._

## 스펙 (← /bts-spec Phase A 채움)

_fast-track (chore) — 생략._

## Brainstorming Check (← /bts-spec Phase B 채움)

_fast-track (chore) — 생략._

## Plan (← /bts-plan 채움)

**Goal.** Docker 데몬 부재를 **데몬 부재라고 말하는** 상태로 만든다 — 프리플라이트가 먼저 잡고,
거기를 지나쳐도 nginx 봉인이 「문법 오류」가 아니라 「데몬 부재」로 가른다.

**Architecture.** 판정 코드는 **한 벌**을 두 층에 동형으로 심고, 「요구하느냐」만 밖에서 주입한다
(CI = `inputs.require_docker`, 로컬 = `BTS_REQUIRE_DOCKER`). 실제 Docker 를 끄지 않고 검증하려고
두 스크립트 모두 `BTS_DOCKER_BIN` 이음매를 받는다 — 이 저장소의 `BTS_GH_BIN`·`BTS_RUNNER_ROOT`
관례와 같다.

**Tech Stack.** GitHub Actions `workflow_call` inputs · bash · `node --test`

### 파일 구조

| 파일 | 책임 | 변경 |
|---|---|---|
| `.github/workflows/runner-health.yml` | CI 층 프리플라이트. `inputs.require_docker` | 수정 |
| `scripts/verify-runner-health.sh` | 로컬 층. `BTS_REQUIRE_DOCKER` | 수정 |
| `.github/workflows/backend-ci.yml` · `infra-ci.yml` | 호출부. `require_docker: true` | 수정 |
| `scripts/verify/nginx-log-masking.sh` | 데몬 부재를 `exit 8` 로 분리 | 수정 |
| `scripts/workflow/runner-healthcheck-wiring.test.ts` | 동형·배선 판별식 | 수정 |
| `scripts/workflow/nginx-docker-precondition.test.ts` | 오진 분리 판별식 | **신규** |
| `TODOS.md` · `docs/runbooks/self-hosted-runner.md` | 정본 · 처방 ③ 이관 | 수정 |

---

### Task 1. 두 층에 Docker 데몬 판정을 동형으로 심고 INVARIANTS 로 못박는다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/runner-healthcheck-wiring.test.ts`, `.github/workflows/runner-health.yml`, `scripts/verify-runner-health.sh`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/runner-healthcheck-wiring.test.ts` 의 `INVARIANTS` 배열 끝에 추가.

  ```ts
      // ── Docker 데몬 판정 (2026-08-12 추가 · 부채 매핑 30) ──────────────
      // 엔진도 자원도 정상인데 **데몬만 꺼진** 상태. 위 점검들은 전부 통과시킨다.
      // 2026-08-12 PR #367 실측 상관 100% — 데몬 준비 이전 시작 잡 5건 전부 실패.
      'docker info', // CLI 존재가 아니라 **데몬 가동**으로 판정한다 (그 구분이 결함의 핵심)
      'BTS_DOCKER_BIN', // 데몬 부재 주입 이음매. 없으면 그 층은 검증 불가능해진다
      'Docker 데몬이 꺼져 있다', // 진단 문구 — 없으면 「테스트가 깨졌다」로 오독된다
      '코드 문제 아님', // 오진 차단. 이 문구가 이 결함의 존재 이유다
  ```

- 실패 메시지 (예상). `두 층의 판정 규칙이 갈라졌다 — script 에 없음. docker info / runner-health.yml 에 없음. docker info …`

**GREEN**.
- 파일 ①. `.github/workflows/runner-health.yml`
  - `on:` 블록에 입력 추가.

    ```yaml
    on:
      workflow_call:
        inputs:
          require_docker:
            description: >-
              Docker 데몬 가동을 **요구**할지. 데몬을 쓰는 워크플로우만 true 다.
              false 여도 점검은 하고 경고만 남긴다 — 조용한 스킵은 만들지 않는다.
            required: false
            default: false
            type: boolean
    ```

  - `러너 자원 점검` 스텝 **앞**에 스텝 추가 (엔진 점검 뒤). 프리플라이트는 무거운 잡보다
    먼저여야 의미가 있다.

    ```yaml
          # ── Docker 데몬 점검 — 엔진이 살아 있어도 데몬이 꺼지면 잡이 줄줄이 오진 실패한다 ──
          #
          # ★`command -v docker` 로는 못 잡는다. CLI 는 설치돼 있고 **데몬만** 꺼진 상태이기
          #   때문이다. 2026-08-12 PR #367 실측 — 그 구분이 없어 nginx 봉인이 「이 설정으로
          #   배포하면 프론트 전체가 뜨지 않는다」고 말했다. 설정은 멀쩡했다.
          #
          # ★판정 규칙은 scripts/verify-runner-health.sh 와 **동형**이어야 한다.
          #   구조상 dedup 이 불가능한 두 복사본이고, runner-healthcheck-wiring.test.ts 의
          #   INVARIANTS 가 갈라짐을 차단한다.
          - name: Docker 데몬 점검
            env:
              REQUIRE_DOCKER: ${{ inputs.require_docker }}
            run: |
              DOCKER="${BTS_DOCKER_BIN:-docker}"

              if "$DOCKER" info > /dev/null 2>&1; then
                echo "✅ Docker 데몬 가동중"
                exit 0
              fi

              echo "❌ Docker 데몬이 꺼져 있다 — 코드 문제 아님"
              echo "   CLI 존재만으로는 못 잡는 상태다. 이 러너의 컨테이너 검증은 전부 실패한다."
              echo "   실측. 2026-08-12 PR #367 — 데몬 준비 이전 시작 잡 5건 전부 실패,"
              echo "         이후 시작한 잡 전부 통과. 코드 무변경 재실행으로 EXIT=5 → 0."
              echo "   복구. Docker Desktop 기동 후 'docker info' 가 0 을 낼 때까지 기다린다."
              echo "   docs/runbooks/self-hosted-runner.md §5"

              if [ "$REQUIRE_DOCKER" = "true" ]; then
                echo "   ⇒ 이 워크플로우는 데몬을 쓰므로 여기서 멈춘다 (뒤 잡이 오진 실패하는 것을 막는다)."
                exit 1
              fi
              echo "   ⇒ 이 워크플로우는 데몬을 쓰지 않으므로 차단하지 않는다 (경고만)."
              exit 0
    ```

- 파일 ②. `scripts/verify-runner-health.sh` — 엔진 점검 뒤, 자원 점검 앞에 **같은 판정**.

  ```bash
  # ── Docker 데몬 점검 ────────────────────────────────────────────────────
  #
  # ★`command -v docker` 로는 못 잡는다. CLI 는 설치돼 있고 **데몬만** 꺼진 상태이기 때문이다.
  #   2026-08-12 PR #367 실측 — 그 구분이 없어 nginx 봉인이 「이 설정으로 배포하면 프론트
  #   전체가 뜨지 않는다」고 말했다. 설정은 멀쩡했다.
  #
  # ★요구 여부만 밖에서 주입한다. 로컬 기본은 경고다 — 프론트 작업까지 막으면 안 된다.
  #   판정 코드 자체는 runner-health.yml 과 한 글자도 다르지 않아야 하고,
  #   runner-healthcheck-wiring.test.ts 의 INVARIANTS 가 그것을 강제한다.
  DOCKER="${BTS_DOCKER_BIN:-docker}"
  REQUIRE_DOCKER="${BTS_REQUIRE_DOCKER:-false}"

  if "$DOCKER" info > /dev/null 2>&1; then
    echo "✅ Docker 데몬 가동중"
  else
    echo "❌ Docker 데몬이 꺼져 있다 — 코드 문제 아님"
    echo "   CLI 존재만으로는 못 잡는 상태다. 이 러너의 컨테이너 검증은 전부 실패한다."
    echo "   실측. 2026-08-12 PR #367 — 데몬 준비 이전 시작 잡 5건 전부 실패,"
    echo "         이후 시작한 잡 전부 통과. 코드 무변경 재실행으로 EXIT=5 → 0."
    echo "   복구. Docker Desktop 기동 후 'docker info' 가 0 을 낼 때까지 기다린다."
    echo "   docs/runbooks/self-hosted-runner.md §5"
    if [ "$REQUIRE_DOCKER" = "true" ]; then
      echo "   ⇒ 데몬을 요구하는 호출이므로 여기서 멈춘다."
      FAILED=1
    else
      echo "   ⇒ 데몬을 쓰지 않는 작업이면 무시해도 된다 (차단하지 않는다)."
    fi
  fi
  ```

  ⚠️ **배치 주의.** 이 스크립트는 엔진 점검 뒤 `FAILED` 를 보고 `exit 1` 하는 블록이 있다.
  위 블록은 그 **판정 앞**에 둔다 — 뒤에 두면 `FAILED=1` 이 무시된다.

**REFACTOR**. 헤더 주석에 「데몬 점검」 한 줄 추가. 종료 코드 규약은 불변(0/1).

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/runner-healthcheck-wiring.test.ts
bash scripts/verify-runner-health.sh; echo "EXIT=$?"          # 데몬 켜진 상태 → 0
BTS_DOCKER_BIN=/bin/false bash scripts/verify-runner-health.sh; echo "EXIT=$?"   # 경고, 0
BTS_DOCKER_BIN=/bin/false BTS_REQUIRE_DOCKER=true bash scripts/verify-runner-health.sh; echo "EXIT=$?"  # 1
```

---

### Task 2. Docker 를 쓰는 워크플로우가 `require_docker: true` 를 넘기는지 **자동 판정**한다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/runner-healthcheck-wiring.test.ts`, `.github/workflows/backend-ci.yml`, `.github/workflows/infra-ci.yml`]
- depends-on: [1]

**RED**.
- 파일. `scripts/workflow/runner-healthcheck-wiring.test.ts`
- ★**목록을 손으로 적지 않는다.** 「docker 를 쓰는 워크플로우」를 손으로 적으면 그 목록과
  실제 워크플로우가 서로를 안 보는 두 목록이 된다 — 이 파일이 `callerFiles()` 를 런타임에
  훑는 것과 같은 이유다. **실제 사용을 훑어 판정**한다.

  ```ts
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
   * ★스크립트를 따라가지 않으면 `infra-ci` 를 놓친다. 그 파일의 docker 언급은 주석 한 줄뿐이고
   *   실제 사용은 `scripts/verify/nginx-log-masking.sh` 안에 있다.
   */
  function usesDocker(name: string): boolean {
    const body = withoutComments(readWorkflow(name));
    if (/\bdocker\b/.test(body)) return true;

    for (const m of body.matchAll(/(scripts\/[\w./-]+\.(?:sh|mjs|ts))/g)) {
      const p = path.join(REPO_ROOT, m[1]);
      if (fs.existsSync(p) && /\bdocker\b/.test(withoutComments(fs.readFileSync(p, 'utf8')))) {
        return true;
      }
    }
    return false;
  }
  ```

  ```ts
  test('★★Docker 를 쓰는 워크플로우는 require_docker 를 넘긴다 (목록을 손으로 적지 않는다)', () => {
    // 데몬이 꺼지면 이 워크플로우들의 잡이 **코드와 무관하게** 줄줄이 오진 실패한다.
    // 프리플라이트가 있어도 호출부가 안 넘기면 아무것도 안 막는다 — 이 저장소가 여러 번 겪은
    // 「가드는 있는데 배선이 없다」 양식이다.
    const needing = callerFiles().filter(usesDocker);
    assert.ok(
      needing.length >= 2,
      `docker 를 쓰는 워크플로우를 ${needing.length}개만 찾았다 — 훑기가 고장나면 아래가 공허하다.`,
    );

    const missing = needing.filter(
      (name) => !/require_docker:\s*true/.test(readWorkflow(name)),
    );
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
    // 위 단언만 있으면 「전부 true」가 통과하므로 이 짝이 없으면 계약이 절반이다.
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
  ```

- 실패 메시지 (예상). `Docker 를 쓰면서 require_docker 를 안 넘기는 워크플로우가 있다: backend-ci.yml, infra-ci.yml`

**GREEN**.
- `.github/workflows/backend-ci.yml` · `infra-ci.yml` 의 `runner-health` 잡에 입력을 넘긴다.

  ```yaml
    runner-health:
      uses: ./.github/workflows/runner-health.yml
      # ★이 워크플로우는 Docker 데몬을 쓴다(Testcontainers · generateJooq · nginx 컨테이너).
      #   데몬이 꺼지면 뒤 잡이 코드와 무관하게 줄줄이 오진 실패하므로 여기서 먼저 멈춘다.
      with:
        require_docker: true
  ```

**REFACTOR**. 없음.

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/runner-healthcheck-wiring.test.ts
```

---

### Task 3. nginx 봉인이 「데몬 부재」와 「문법 오류」를 다른 종료 코드로 가른다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/nginx-docker-precondition.test.ts`, `scripts/verify/nginx-log-masking.sh`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/nginx-docker-precondition.test.ts` (**신규**)

  ```ts
  // nginx 봉인이 「Docker 데몬 부재」를 「설정 문법 오류」로 오진하지 않는지 검증하는 판별식
  //
  // ## 왜 이 판별식이 필요한가
  //
  // 2026-08-12 PR #367 에서 infra-ci 가 「이 설정으로 배포하면 프론트 전체가 뜨지 않는다」고
  // 말했다. **설정은 멀쩡했고 Docker 데몬만 꺼져 있었다.** 스크립트가 `command -v docker`
  // 부재만 exit 7 로 가르고 데몬 부재는 문법 오류와 같은 exit 5 로 뭉갰기 때문이다.
  //
  // 오진은 「빨간불이 났다」보다 나쁘다 — **엉뚱한 곳을 파게 만든다.** 그날 사람이 12개 잡의
  // 로그를 하나씩 뒤졌다.
  //
  // ## 실제 Docker 를 끄지 않고 어떻게 재현하나
  //
  // `BTS_DOCKER_BIN` 이음매로 **가짜 docker** 를 주입한다 — `info` 는 실패하고 나머지는
  // 성공하는 것, 아예 없는 것 등. 이 저장소의 `BTS_GH_BIN`·`BTS_RUNNER_ROOT` 관례와 같다.

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

  interface RunResult { code: number; output: string }

  /** `info` 만 실패하는 가짜 docker 를 주입해 스크립트를 돌린다. */
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
          `${EXIT_SYNTAX} 로 떨어지면 「설정이 잘못됐다」로 읽혀 엉뚱한 곳을 파게 된다.\n${r.output}`,
      );
      assert.match(
        r.output,
        /데몬/,
        `출력이 데몬 부재를 말하지 않는다 — 오진 문구가 그대로다.\n${r.output}`,
      );
      assert.ok(
        !/이 설정으로 배포하면/.test(r.output),
        `데몬이 꺼졌을 뿐인데 설정 탓을 한다 — 이 PR 이 없애려는 바로 그 문구다.\n${r.output}`,
      );
    });

    test('★CLI 부재와 데몬 부재를 다른 코드로 가른다', () => {
      const missing = runWithFakeDocker('missing');
      const down = runWithFakeDocker('daemon-down');
      assert.notEqual(
        missing.code,
        down.code,
        `CLI 부재와 데몬 부재가 같은 코드(${down.code})다 — 로그만 보고 구분할 수 없다.`,
      );
    });
  });
  ```

- 실패 메시지 (예상). `데몬 부재의 종료 코드가 5 다 (기대 8)`

**GREEN**.
- 파일. `scripts/verify/nginx-log-masking.sh`
- ① 헤더의 종료 코드 규약에 한 줄 추가.

  ```
  #            7 = 전제조건 부재(docker CLI) / 8 = Docker 데몬 부재
  ```

- ② `NGINX_IMAGE` 선언 아래에 이음매 추가.

  ```bash
  # 실제 Docker 를 끄지 않고 전제조건 분기를 검증하는 유일한 통로.
  # scripts/workflow/nginx-docker-precondition.test.ts 가 이 이음매를 쓴다.
  DOCKER="${BTS_DOCKER_BIN:-docker}"
  ```

- ③ 기존 전제조건 줄을 **두 갈래로** 나눈다.

  ```bash
  # docker 부재를 SKIP 으로 넘기지 않는다 — 조용한 스킵은 vacuous 통과 경로다.
  command -v "$DOCKER" >/dev/null 2>&1 || fail "docker 가 필요하다(문법·실효 검증). 조용히 건너뛰지 않는다" 7

  # ★CLI 존재와 데몬 가동은 다른 것이다. 이 구분이 없어서 2026-08-12 PR #367 에서
  #   「이 설정으로 배포하면 프론트 전체가 뜨지 않는다」는 오진이 났다 — 설정은 멀쩡했다.
  #   여전히 빨간불이다(조용한 스킵 금지). 다만 **어디를 봐야 하는지**를 바르게 말한다.
  "$DOCKER" info >/dev/null 2>&1 \
      || fail "Docker 데몬이 꺼져 있다 — 설정 문제가 아니다. 데몬을 켜고 재실행할 것" 8
  ```

- ④ 이후 `docker run` 호출을 전부 `"$DOCKER" run` 으로 바꾼다 (이음매가 실제로 먹히도록).

**REFACTOR**. 없음.

**검증**.
```bash
node --experimental-strip-types --test scripts/workflow/nginx-docker-precondition.test.ts
bash scripts/verify/nginx-log-masking.sh; echo "EXIT=$?"    # 데몬 켜진 실환경 → 0
```

---

### Task 4. 정본 동기화 + 뮤테이션으로 비-공허 확인

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`, `docs/runbooks/self-hosted-runner.md`, `.github/workflows/runner-health.yml`, `scripts/verify/nginx-log-masking.sh`]
- depends-on: [1, 2, 3]

- [ ] **Step 1. 정본 동기화** — `TODOS.md` 의 매핑 30 항목에 §해소 절 신설(처방 ①② 채택 · ③ 은
      런북 이관). `docs/runbooks/self-hosted-runner.md` 에 §5 Docker 데몬 절 신설
      (스크립트가 그 경로를 인용하므로 **없으면 죽은 링크**다).

- [ ] **Step 2. 기준선** — `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` EXIT=0 확인.

- [ ] **Step 3~6. 뮤테이션 4종** (각각 넣고 red 확인 후 `git checkout --` 원복).

  | # | 훼손 | 기대 red |
  |---|---|---|
  | M1 | `nginx-log-masking.sh` 의 `docker info` 줄 삭제 | 데몬 부재를 문법 오류로 오진하지 않는다 |
  | M2 | 데몬 부재 종료 코드를 `8` → `5` 로 | 같은 케이스 + CLI/데몬 구분 |
  | M3 | `backend-ci.yml` 의 `require_docker: true` 제거 | Docker 를 쓰는 워크플로우는 require_docker 를 넘긴다 |
  | M4 | `frontend-ci.yml` 에 `require_docker: true` 추가 | 음성 대조군 (안 쓰는데 요구한다) |

  ★M3·M4 는 **반대 방향**이다. 한쪽만 있으면 「전부 true」 또는 「전부 false」가 통과한다.

- [ ] **Step 7. 원복 확인** — `git diff --stat` 무출력 + 전체 스위트 EXIT=0.

- [ ] **Step 8. 결과표를 §뮤테이션 결과에 기록** — 어느 케이스가 잡았는지까지.

---

## Plan 메타

- task 수: 4
- 예상 시간: 약 20분. T1·T3 은 files 교집합이 없어 병렬 가능하나 단독 진행이므로 직렬
- 구현 규율: TDD (red-first 강제)
- 추가 검증: 전체 판별식 · `verify-master-plan.sh` · 실환경 `bash scripts/verify/nginx-log-masking.sh`
- FR 영향: **없음** (139 불변). 프로덕션 코드 0줄, 백엔드 0줄, `apps/web` 0파일

## 뮤테이션 결과

기준선 **231 pass / 0 fail EXIT=0**. 각 뮤테이션을 하나씩 넣고 돌린 뒤 `git checkout --` 로 원복
(마지막에 `git status --porcelain` 무출력 확인).

| 뮤테이션 | 결과 | 잡은 케이스 |
|---|---|---|
| M1. nginx 봉인의 데몬 점검 삭제 | **RED** | 데몬 부재를 문법 오류로 오진하지 않는다 |
| M2. 데몬 부재 종료 코드 `8` → `5` | **RED** | 〃 |
| M3. `backend-ci` 의 `require_docker` 제거 | **RED** | Docker 를 쓰는 워크플로우는 넘긴다 |
| M4. `frontend-ci` 에 `require_docker` 추가 | **RED** | 음성 대조군 (안 쓰는데 요구한다) |
| M5. 로컬 층의 데몬 판정 삭제 | **RED** | 두 복사본의 판정 규칙이 갈라지지 않는다 |
| M6. 런북 인용 `§7` → `§5` | **RED** | 인용한 런북 섹션이 실재한다 |

**★M3·M4 는 반대 방향이다.** 한쪽만 있으면 「전부 true」 또는 「전부 false」가 통과한다.
M1·M2 를 같은 케이스가 잡는 것은 그 케이스가 **코드와 문구를 함께** 재기 때문이고,
나머지 넷은 각자 고유한 케이스가 잡는다.

### ★★1차에서 2건이 살아남았다 — 내 가드가 공허했다

| 뮤테이션 | 1차 | 왜 살아남았나 |
|---|---|---|
| M5 | GREEN ❌ | `INVARIANTS` 에 `'docker info'` 라고 적었는데, 그 문자열이 **판정식이 아니라 복구 안내 문구**(「`docker info` 가 0 을 낼 때까지 기다린다」)에서 만족되고 있었다. **판정식을 통째로 지워도 통과**했다 |
| M6 | GREEN ❌ | 런북 인용 검사가 두 층을 **합쳐서** 봤다. 로컬 층만 `§5` 로 되돌려도 CI 층의 `§7` 이 가려 줘서 통과했다 |

처방. M5 는 `'"$DOCKER" info'` 로 **실제 판정식**을 못박고, M6 는 **층별로 따로** 본다.
그 뒤 둘 다 RED 가 됐다. 커밋 `fix: … 공허했던 가드 2건 교정 (뮤테이션 M5·M6 이 적발)`.

**교훈.** 「문자열이 양쪽에 있는가」로 동형을 재는 구조는 **그 문자열이 어디서 만족되는지**를
보지 않는다. 도움말·주석·에러 메시지에 같은 낱말이 있으면 판정식을 지워도 통과한다.
`INVARIANTS` 에 넣을 문자열은 **실행되는 코드에만 나타나는 형태**여야 한다.

## 계획 대비 실제 (deviation)

| 계획 | 실제 | 사유 |
|---|---|---|
| 런북 `§5` 에 Docker 절 | **`§7`** 신설 | `§5` 는 이미 「GitHub 호스팅 러너로 되돌리는 절차」였다. 착수 전 확인 없이 계획에 번호를 적은 것이 오류 |
| 「docker 를 쓰는 워크플로우」 훑기 = 경로 언급 | **인터프리터가 앞에 붙은 실행만** | 경로 언급으로 훑었더니 `workflow-scripts-ci` 의 `paths` **필터 목록**을 호출로 오인했다(실측) |
| 인용 절 검사 = 번호 실재 | **+ Docker 절 번호 일치 · 층별** | 번호만 보면 「번호는 있는데 다른 절」을 못 잡는다. 실제로 그 상태였다 |
| 뮤테이션 4종 | **6종** | 로컬 층 갈라짐(M5)·런북 인용(M6) 을 별도로 재야 했다 |

## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track (chore) — 생략. 게이트 2 의 /bts-codereview 는 그대로 실행한다._
