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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan — fast-track 스킵, /bts-codereview 는 실행)

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
