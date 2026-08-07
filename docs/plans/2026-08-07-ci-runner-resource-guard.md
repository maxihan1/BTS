# CI 러너 자원 고갈 판정 — runner-health 경고 전용 확장

> slug: ci-runner-resource-guard
> type: chore (classify-task 는 `feature` 로 오분류 — 프로덕션 코드 0줄, #347 과 동성격)
> agent: backend-engineer
> 생성: 2026-08-07

## Brief

`runner-health.yml` 이 지금 판정하는 것은 **실행 엔진의 생존**(node · java 바이너리가 실행되는가)
뿐이다. 2026-08-07 조사에서 **엔진이 멀쩡한데도 CI 가 2.4배 느려지는 실패 양식**을 확증했고,
이 양식은 **초록불인 채** 일어나므로 현재 가드가 통째로 못 본다.

그 판정을 같은 워크플로우에 **경고 전용으로** 더한다.

### 배경 — 2026-08-07 조사 결과 (A/B 확증 완료)

정본 메모리. [[ci-slowdown-is-runner-memory-not-code]]

**동일 커밋 run 31139616352** 를 `gh run rerun --job` 으로 재실행 — 커밋·테스트·Gradle 설정·
러너 라벨이 전부 고정되고 **바뀐 변수는 머신 가용 메모리 하나**다.

| 모듈 | 8/1 (정상, 4회) | 8/7 (swap 15,014M · load 34.43) | 정리 후 (swap 3,556M · load 7.72) |
|---|---|---|---|
| issue-tracking | 527 · 560 · 570 · 580s | **1,380s (2.44×)** | **554s (0.98×)** |
| identity-access | 419 · 419 · 420 · 427s | **739s (1.76×)** | **384s (0.91×)** |

테스트 케이스 수는 3,247 → 3,247 로 **증감 0**. 스위트 307개 중 1초 이상 24개의 감속 배수는
**중앙값 2.05×**, "거의 그대로"는 1개뿐 — **전 스위트 균등 감속**이 서명이다.
`IssueBcArchTest`(DB·Docker 무관 순수 바이트코드 스캔)조차 9s→27s 였다.

### 이 PR 이 닫는 구멍

「엔진은 살아 있으나 자원이 없어 시간만 2배가 된 run」이 **아무 흔적 없이 지나간다.**
사람이 "오늘 좀 느리네" 하고 넘어가고, 그 run 의 시간을 근거로 판단하면 전부 오독이다.

## 도메인 정리

**스킵 (fast-track — chore).** BC 작업이 아니다. `classify-task` 가 낸 `primary_bc:
project-workflow` 는 제목의 "워크플로우" 낱말에 반응한 오분류이며, 대상은 `.github/workflows/`
와 `scripts/workflow/` 로 **어느 BC 에도 속하지 않는 CI 인프라**다.

## 스펙

**스킵 (fast-track — chore).**

## Brainstorming Check

**스킵 (fast-track — chore).**

## Plan

### 열린 질문에 대한 결정 (착수 전 확정)

| 질문 | 결정 | 근거 |
|---|---|---|
| 임계값 | **`load / ncpu ≥ 2.0`** 또는 **`swap used ≥ 4096MB`** (OR) | 오늘 실측 — 고갈 `34.43/8 = 4.30` · 정상 `7.72/8 = 0.97`. 스왑은 고갈 15,014M · 정상 3,556M 이고 macOS 는 상시 소량을 쓰므로 0 을 기준으로 둘 수 없다. **비율**을 쓰는 이유는 절대 load 값이 코어 수에 종속이라 러너 교체 시 조용히 의미가 바뀌기 때문이다 |
| 어디에 남기나 | 스텝 로그 + **`$GITHUB_STEP_SUMMARY`** + **`::warning::`** 어노테이션 | 스텝 로그만으로는 묻힌다. run 요약 페이지와 어노테이션은 잡을 안 펼쳐도 보인다 |
| 판정 시점 | **잡 시작 1회** | `runner-health` 는 선행 잡이라 뒤따르는 잡과 시차가 크다(오늘 실측 27분). **그 한계를 경고 문구에 명시**해 시간을 과신하지 않게 한다 |
| 테스트 이음매 | **측정값 주입** (`BTS_RUNNER_FAKE_LOAD` · `BTS_RUNNER_FAKE_SWAP_MB` · `BTS_RUNNER_FAKE_NCPU`) | `BTS_RUNNER_ROOT` 와 동성격의 선례가 이미 있다. 임계값만 오버라이드하면 **판정 로직이 틀려도 통과**한다 — 경계 양쪽을 다 재려면 측정값 자체가 주입 가능해야 한다 |

### Task 1. 로컬 스크립트에 자원 고갈 판정 추가 (경고 전용)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/verify-runner-health.sh`, `scripts/workflow/verify-runner-health.test.ts`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/verify-runner-health.test.ts`
- 테스트 3건 (기존 `run(root)` 헬퍼에 env 주입 인자 추가).
  ```ts
  test('자원이 넉넉하면 경고가 없다 (음성 대조군)', ...)      // load 0.97·swap 3556 → 경고 문구 부재
  test('★고갈이면 경고하되 exit 0 을 유지한다', ...)          // load 34.43·swap 15014·ncpu 8 → ⚠️ 있고 code 0
  test('load 와 swap 이 각각 독립으로 판정을 켠다', ...)       // 한쪽만 고갈인 두 경우 모두 경고
  ```
- 실패 메시지 (예상). 경고 문구가 출력에 없음 (`assert.match` 불일치)

**GREEN**.
- 파일. `scripts/verify-runner-health.sh`
- 엔진 점검 **뒤**, `exit` 앞에 자원 측정 블록 추가. `FAILED` 변수를 **건드리지 않는다**.
  ```sh
  NCPU=${BTS_RUNNER_FAKE_NCPU:-$(sysctl -n hw.ncpu)}
  LOAD=${BTS_RUNNER_FAKE_LOAD:-$(uptime | sed 's/.*averages*: *//' | awk '{print $1}')}
  SWAP_MB=${BTS_RUNNER_FAKE_SWAP_MB:-$(sysctl -n vm.swapusage | sed 's/.*used = //' | sed 's/M.*//')}
  ```
- 판정이 켜지면 `⚠️` 로 시작하는 진단 + **왜 시간을 믿으면 안 되는지**를 출력. `exit` 코드 불변.

**REFACTOR**.
- 임계값을 파일 상단 상수로 (`LOAD_RATIO_WARN=2.0` · `SWAP_MB_WARN=4096`) + 2026-08-07 실측 근거 주석

**검증**.
```bash
node --test scripts/workflow/verify-runner-health.test.ts
# 뮤테이션 — 판정 블록을 통째로 지우면 위 3건 중 2건이 FAILED 여야 한다
```

### Task 2. CI 워크플로우에 같은 판정 인라인 + 두 복사본 동기화 강제

**메타**.
- agent: `backend-engineer`
- files: [`.github/workflows/runner-health.yml`, `scripts/workflow/runner-healthcheck-wiring.test.ts`]
- depends-on: [1]

**왜 복사본인가.** 이 잡은 `actions/checkout` **앞**이라 저장소가 아직 없다 —
`scripts/verify-runner-health.sh` 를 부를 수 없다. 엔진 점검이 이미 같은 이유로 인라인이고,
그래서 `runner-healthcheck-wiring.test.ts` 가 **두 층의 판정 규칙이 갈라지지 않는지**를
`INVARIANTS` 로 강제한다. 자원 판정도 그 목록에 들어가야 한다.

**RED**.
- 파일. `scripts/workflow/runner-healthcheck-wiring.test.ts`
- 기존 `INVARIANTS` 배열에 자원 판정의 **하중을 받는 술어**를 추가.
  ```ts
  'BTS_RUNNER_FAKE_LOAD',        // 주입 이음매가 양쪽에 있어야 테스트가 CI 층도 덮는다
  'hw.ncpu',                      // 비율 판정 — 절대 load 로 되돌리면 러너 교체 시 의미가 바뀐다
  'vm.swapusage',
  '시간을 믿지 마라',              // 진단 문구. 없으면 「그냥 느린 날」로 오독된다
  ```
- 실패 메시지 (예상). `runner-health.yml 에 없음. ...` 4건

**GREEN**.
- 파일. `.github/workflows/runner-health.yml`
- 엔진 점검 스텝 뒤에 **순수 shell** 자원 판정 스텝 추가. `uses:` 금지(node 부재 시 이 잡이 먼저 죽는다).

**REFACTOR**.
- 왜 차단이 아니라 경고인지를 주석으로 못박기 (run 31139123013 교착 참조)

**검증**.
```bash
node --test scripts/workflow/runner-healthcheck-wiring.test.ts
# 뮤테이션 — runner-health.yml 의 자원 판정 스텝을 지우면 FAILED
```

### Task 3. 경고를 run 요약과 어노테이션으로 표출

**메타**.
- agent: `backend-engineer`
- files: [`.github/workflows/runner-health.yml`, `scripts/workflow/runner-healthcheck-wiring.test.ts`]
- depends-on: [2]

**왜 별도 task 인가.** Task 2 는 **판정**이고 이것은 **표출**이다. 판정이 맞아도 스텝 로그
안에만 있으면 아무도 안 본다 — 오늘 사고의 본질이 「초록불이라 아무도 안 봤다」이므로
표출 실패는 판정 부재와 같은 결과를 낳는다.

**RED**.
- 파일. `scripts/workflow/runner-healthcheck-wiring.test.ts`
- 테스트 추가. 자원 판정 스텝이 `$GITHUB_STEP_SUMMARY` 기록과 `::warning::` 발행을 **둘 다** 한다
- 실패 메시지 (예상). 두 문자열 모두 부재

**GREEN**.
- `::warning title=러너 자원 고갈::...` + `>> "$GITHUB_STEP_SUMMARY"`

**REFACTOR**.
- 요약 표를 markdown 표로 (load / swap / 판정)

**검증**.
```bash
node --test scripts/workflow/runner-healthcheck-wiring.test.ts
# 실물 확인 — 이 PR 의 CI run 요약 페이지에 표가 뜨는지 눈으로 본다 (정상 상태이므로 경고는 없어야 정상)
```

### Task 4. TODOS.md 에 조사 결과 + 후속 3건 등재

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: []

**RED**. 해당 없음 (문서). 대신 기존 판별식이 형식을 강제한다 —
`node --test scripts/workflow/todos-resolved-section-purity.test.ts` 가 초록이어야 한다.

**GREEN**.
- §인프라 절에 「CI 벽시계 — 러너 1대 직렬 + 자원 경쟁」 항목 신설.
- 오늘의 A/B 실측표 · 대기/실행 분해(frontend-ci 108m47s 중 실행 10m46s) ·
  후속 3건(좀비 run 차단 · Gradle 설정 · 변경 모듈만 테스트)을 소관과 함께 적는다.
- 메모리 [[ci-slowdown-is-runner-memory-not-code]] 로 상호 링크.

**검증**.
```bash
node --test scripts/workflow/todos-resolved-section-purity.test.ts
node scripts/build-doc-index.mjs --check
```

## Plan 메타

- task 수: 4
- 예상 시간: 약 15분 (직렬), 병렬 wave 적용 시 약 9분 (예상 wave 수: 3 — T1·T4 동시 → T2 → T3)
- 구현 규율: **TDD** (chore 이나 판정 로직이 있으므로 red-first 유지. 문서인 T4 만 면제)
- 병렬 dispatch: T2·T3 는 `runner-health.yml` 을 공유하므로 파일 겹침 자동 직렬화
- 추가 검증: `pnpm test:workflow` 전량 (57건+) — 판별식이 서로를 깨지 않는지

## ★뮤테이션 확증 계획 (공허 가드 방지)

메모리 [[fr-ux-13-f16-f7-epic-control-test-contract-done]] 「봉인 자체가 영구초록」 ·
[[unreachable-state-fixture-is-fake-green]] 「도달 불가 조합을 지키는 테스트 = 가짜 그린」.

| # | 무엇을 무력화 | 기대 |
|---|---|---|
| M1 | `verify-runner-health.sh` 의 자원 판정 블록 삭제 | T1 테스트 3건 중 2건 FAILED |
| M2 | `runner-health.yml` 의 자원 판정 스텝 삭제 | T2 INVARIANTS FAILED |
| M3 | 비율 판정을 절대 load 로 되돌림 (`hw.ncpu` 제거) | T2 INVARIANTS FAILED |
| M4 | `exit 1` 로 바꿈 (차단화) | **T1 「exit 0 유지」 FAILED** ← 가장 중요. 이게 안 잡히면 교착 회귀를 못 막는다 |

**M4 가 핵심이다.** 이 PR 의 존재 이유가 「경고여야 한다」이므로, 그 제약을 지키는 테스트가
없으면 다음 사람이 좋은 의도로 차단화해 run 31139123013 교착을 재생산한다.

## 리뷰 결과

**한계 명시.** 세션 지시로 Agent 도구 호출이 금지되어 **작성자가 자기 코드를 리뷰**했다.
이 저장소 메모리에 「독립 리뷰가 공허 가드 4건 적발」·「리뷰 2종이 BLOCKER 3건」이 반복
기록돼 있듯 자기 리뷰에는 구조적 맹점이 있다. 대신 절대 규칙 19개 + learnings 양식으로
기계적으로 훑고, **모든 의심을 실물 실행으로 확증**했다.

### 🛑 BLOCKER 2건 (수정 완료)

**B1. awk 문자열 비교로 CI 전체가 멈출 수 있었다.**
`awk -v n="xyz"` 로 넘긴 값은 strnum 규칙상 **숫자로 해석 불가능하면 문자열**이다.
`n > 0` 이 `"xyz" > "0"` 문자열 비교가 되어 **참**이 되고, 0 나눗셈을 막으려던 삼항 가드를
그대로 통과해 `awk: division by zero` 로 죽는다.

두 층의 셸 옵션이 달라 거동이 갈렸다 — 이 PR 의 가장 위험한 사각이었다.

| 층 | 셸 옵션 | 비숫자 입력 시 |
|---|---|---|
| 로컬 `verify-runner-health.sh` | `set -uo pipefail` (errexit **없음**) | exit 0 — 조용히 지나감 |
| CI `runner-health.yml` | `bash -e` 기본 (errexit **있음**) | **exit 2 — 스텝 사망** |

`runner-health` 는 모든 워크플로우의 `needs:` 선행 잡이다. 죽으면 backend·frontend·infra·
workflow-scripts 의 **모든 잡이 안 돈다** — 자원 부족을 알리려던 장치가 CI 를 세운다.
이 PR 이 막으려던 「차단이 치유를 막는다」의 더 나쁜 판본이었다.

**★INVARIANTS 는 이것을 원리적으로 못 잡는다** — 문자열 존재만 보기 때문이다.
그래서 **워크플로우의 인라인 블록을 추출해 실제 `bash -e` 로 돌리는 판별식**을 신설했다
(정상·고갈·비숫자·0코어·음수 5케이스). 추출이 빈 스크립트면 5케이스가 전부 공허하게
통과하므로 비-공허 확인(최소 줄 수 + 판정 로직 존재)을 짝으로 넣었다.

**B2. 방금 만든 로컬 테스트가 공허했다.**
`execFileSync` 는 **성공 시 stdout 만** 돌려준다. stderr 로 나가는 `awk: division by zero`
가 exit 0 뒤에 숨어 「에러가 없다」 단언이 조용히 통과했다 — B1 을 **CI 층 테스트만
잡고 로컬 층 테스트는 초록**이었다. `spawnSync` 로 바꿔 stdout+stderr 를 함께 본다.

### 기각된 의심 1건

「두 자리 배수(`"12.50" >= "2.0"`)를 문자열 비교로 놓친다」고 의심했으나 **실측으로 기각**됐다.
awk 의 `-v` 할당은 strnum 규칙이라 **숫자로 보이는 값끼리는 정상적으로 숫자 비교**된다.
추측을 실물이 뒤집은 사례이고, 그 경계값(12.50배)을 회귀 확인에 남겼다.

### 절대 규칙 19개 (DEVELOPMENT.md §1)

프로덕션 코드 0줄이라 §1.1 보안 1~5 · §1.2 데이터 무결성 7~10 · §1.4 외부 의존성 17~19 는
해당 없음. 적용 대상만 확인했다.

| # | 규칙 | 판정 |
|---|---|---|
| 6 | 검증 안 된 입력으로 외부 명령 실행 금지 | ✅ `awk -v` 는 인용된 할당이라 주입 경로 없음. B1 은 주입이 아니라 타입 함정이었고 봉합됨 |
| 13 | 빈 catch 금지 | ✅ 해당 없음 (shell). 측정 실패는 「판정 건너뜀」으로 **명시 출력** |
| 14 | 테스트 없는 새 기능 금지 | ✅ TDD red→green. task-1/2/3 전부 `test:` → `feat:` |
| 15 | 디버깅 코드 커밋 금지 | ✅ `echo` 는 진단 출력이며 잔재 없음 |
| 16 | PoC 금지 | ✅ 뮤테이션 5종으로 가드 실효성 확증 |

### 뮤테이션 확증 (plan M1~M4 + 리뷰 중 추가 M5)

| # | 무력화 | 결과 |
|---|---|---|
| M1 | 로컬 판정 블록 삭제 | 3건 FAILED |
| M2 | CI 자원 판정 스텝 삭제 | 3건 FAILED |
| M3 | 비율 → 절대값 | 3건 FAILED (음성 대조군 포함 — 절대 load 면 정상 상태에도 경고) |
| M4 | 경고 → 차단 (`FAILED=1`) | 1건 FAILED ★핵심 계약 |
| M5 | **CI 층 `+= 0` 만** 되돌림 | 1건 FAILED, **로컬은 통과** — 두 층이 독립 검증됨 |

원복 무결성 — 매 뮤테이션 후 `git diff` 비어 있음 확인 (역방향 Edit 사용).

### 최종 검증

```
node --test scripts/workflow/*.test.ts    115/115 pass
node scripts/build-doc-index.mjs --check  drift 0 · 깨진 링크 0 · ★ 59/59
CI run 31153999452                        success — 실러너에서 `✅ 러너 자원 (load 1.24배 · swap 0.319)`
```

## ★설계 제약 — 반드시 지킬 것

### 1. 차단이 아니라 경고다

**실패(exit 1)시키면 안 된다.** `runner-health.yml` 자신이 실측한 교착을 재생산한다.

> 여기서 실패시키면 재설치를 수행할 바로 그 잡을 막아 영원히 초록이 될 수 없다
> (실측. run 31139123013 에서 실제로 교착이 났고 이 규칙이 그 봉합이다).
> — `runner-health.yml:56-57`

자원 고갈은 **자원을 회복시켜야 풀리는데**, 그 회복은 사람이 한다. CI 를 막으면 회복 전까지
모든 작업이 멈춘다. 메모리 [[runner-engine-healthcheck-done]] 의 「차단이 치유를 막음」.

### 2. 순수 shell 이어야 한다

`runner-health` 의 스텝은 `actions/checkout` **앞**에 있다. `uses:` 액션은 node 로 돌아가므로
node 가 없는 바로 그 상황에서 이 잡이 먼저 죽는다. 저장소도 아직 없어 `scripts/*.sh` 를 못 부른다.
→ **인라인 순수 shell**. 기존 엔진 점검 스텝과 같은 제약이다.

### 3. macOS 전용 명령이다

러너는 M1 Pro macOS(`self-hosted, macOS, ARM64, bts-local`)다.
`free`(Linux)는 없다. `sysctl -n vm.swapusage` · `vm_stat` · `uptime` 을 쓴다.
러너가 Linux 로 바뀌면 이 판정이 조용히 무력화되므로 **판별식이 그 짝을 잡아야 한다.**

### 4. 판별식이 「비-공허」여야 한다

메모리 [[fr-ux-13-f16-f7-epic-control-test-contract-done]] — 「봉인 자체가 영구초록」.
판별식이 워크플로우 파일에 문자열이 있는지만 보면, **판정 로직을 지워도 통과**한다.
뮤테이션으로 확증할 것 — 판정 스텝을 지우면 FAILED 가 나야 한다.

## 열린 질문 (plan 단계에서 결정)

1. **임계값을 무엇으로 잡나.** 오늘 실측은 load 34.43 / swap 15,014M(정상 8 / 3,556M)이다.
   절대값은 머신에 종속이므로 **코어 수 대비 비율**(load / ncpu)이 이식성이 있다.
2. **어디에 남기나.** 스텝 로그만으로는 묻힌다. `$GITHUB_STEP_SUMMARY` 에 쓰면 run 요약
   페이지에 뜬다 — 「no silent caps」 원칙에 부합.
3. **경고를 어떻게 눈에 띄게 하나.** `::warning::` 워크플로우 명령이 어노테이션을 만든다.
4. **판정 시점이 한 번뿐이어도 되나.** 잡 시작 시점만 재면 실행 중 악화를 놓친다.
   다만 `runner-health` 는 선행 잡이라 **뒤따르는 잡의 시작 시점**과도 시차가 크다
   (오늘 실측 — runner-health 실행 후 test 잡 시작까지 27분 대기).
