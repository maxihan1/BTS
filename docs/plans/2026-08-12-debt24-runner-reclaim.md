# 러너 자원 회수 — 좀비 run 차단 + Gradle CI 설정 (1파)

> slug: debt24-runner-reclaim
> type: chore (fast-track — domain / spec / review-plan 및 게이트 1 생략)
> agent: backend-engineer
> 생성: 2026-08-12
> PR: #366 (draft, 브랜치 `chore/debt24-runner-reclaim`)
> 선행: #365 (0파, 머지 `f90efaed7`) · 마스터 계획 [`2026-08-12-debt24-master.md`](2026-08-12-debt24-master.md)

## Brief

기술부채 24건 전수 처리의 **1파**. 검증 장비(self-hosted 러너 = 이 맥 1대)의 **대기 시간과
실행 시간을 둘 다** 회수한다.

**Maxi 확정 (2026-08-12) — 범위는 ①+②, ③은 후속 PR.**

| | 항목 | 축 | 이번 PR |
|---|---|---|---|
| ① | 낡은 `main` run 이 러너를 계속 점유 (`TODOS.md:2631`) | 대기 | **포함** |
| ② | Gradle 설정 — 데몬·병렬·빌드캐시 (`TODOS.md:1924` 후속 2) | 실행 | **포함** |
| ③ | PR 은 변경된 모듈만 테스트 (`TODOS.md:1924` 후속 3) | 실행 | **제외 — 후속** |

**③을 뺀 이유는 장부가 명시한 함정이다.** `TODOS.md:1969`.

> **★2 와 3 을 같이 넣을 때의 함정.** 빌드캐시는 「안 돌리고 UP-TO-DATE 통과」를,
> 모듈 선택은 「잡을 아예 안 만들기」를 만든다. **각각은 안전해도 겹치면 두 겹으로 미검증인데 초록**이다.

②의 기준선이 바뀐 뒤 ③을 별도 PR 로 연다. 러너 증설은 하지 않는다
(`[[free-tier-only-no-paid-github]]` — self-hosted 1대는 영구 설계).

---

## ★PR #366 본문의 근거가 실측과 어긋난다 (2026-08-12 재측정)

0파와 같은 양식이 반복됐다. **본문 수치는 2026-08-07 자원 고갈 최악일의 값**이고 현재와 다르다.

### ①-a 본문이 쓴 지표를 장부가 **금지**하고 있다

본문은 「`backend-ci` 최근 30건 중 **9건 cancelled**」를 근거로 든다. 그러나 `TODOS.md:2657`.

> **★개선폭을 `cancelled` 건수로 재지 말 것** — concurrency 취소도 같은 상태를 만든다.
> **대기 시간(벽시계 − 실행 합계)** 으로 잴 것.

참고로 실측하면 **10건**이다(본문 9 · 장부 정정 11 둘 다 아님). 애초에 **판정에 쓰면 안 되는 값**이다.

| 결론 | 건 |
|---|---|
| success | 16 |
| **cancelled** | **10** |
| failure | 4 |

### ①-b 올바른 지표로 재면 — 문제는 `backend-ci` 에만 있다

```
대기 시간 = 벽시계(run_started_at → updated_at) − 잡 실행 시간 합계
```

| 워크플로우 | 대기 시간 | 실행 합계 |
|---|---|---|
| **`backend-ci`** 최근 6건 | **28 · 90 · 56 · 19 · 4 · 13분** | 40 · 42 · 50 · 44 · 32 · 34분 |
| `frontend-ci` · `workflow-scripts-ci` 최근 8건 | **0 ~ 4분** | 0 ~ 10분 |

본문이 인용한 `frontend-ci` **98분 대기**(run 31144206854)는 2026-08-07 값이고,
같은 워크플로우의 현재 대기는 **2~4분**이다.

**★★대기를 0 으로 만들어도 `backend-ci` 는 40분이 남는다.** 실행 합계 중앙값이 ~41분이다.
그래서 이 PR 은 **대기(①)만이 아니라 실행(②)도 함께** 건드린다 — 본문 범위(스크립트+concurrency)로는
체감 개선폭이 절반이다.

*마지막 `backend-ci` 실행은 2026-08-10 이다. 그 뒤 머지가 전부 문서·프론트였다 —
이것 자체가 ①이 말하는 `paths` 사각의 조건이다.*

### ①-c 본문의 「Maxi 수동 정리」가 겨냥한 대상이 지금은 없다

| 본문 인용 (2026-08-07) | 2026-08-12 실측 |
|---|---|
| Chrome **~10.4GB** | **2.66GB** |
| Docker VM **8.1GB** (유휴 6.6GB) | **0.87GB** (컨테이너 1개 — `bts-postgres-dev`) |

**그런데 고갈은 그대로다.** 원인이 바뀌었다.

---

## 재부팅 전 기준선 (2026-08-12T02:19:43Z · Maxi 확정으로 재부팅 선행)

**이 표가 「자원 회수」의 before 다.** 재부팅 후 같은 항목을 재서 after 로 쓴다.

| 항목 | 값 |
|---|---|
| uptime | **14 days** |
| load (1/5/15분) | 5.52 / 8.43 / 9.48 — 8코어 대비 **0.69배** |
| swap used | **8,922 MB** / 16,384 MB = **0.545** |
| pages free | **68.5 MB** |
| **압축기가 쥔 데이터** | **15.10 GB** |
| **압축기가 쓰는 물리 메모리** | **7.22 GB** ← 16GB 중 |
| Chrome RSS 합 | 2.66 GB |
| Docker/VM RSS 합 | 0.87 GB |
| 가드 판정 | **⚠️ 고갈** |

**★진짜 압박은 압축기다.** 물리 16GB 중 **7.22GB 를 압축기가 점유**해 15.1GB 를 쥐고 있다.
Chrome·Docker 를 지워도 이건 안 풀린다 — **14일 누적이라 재부팅이 유일한 회수 수단**이다.
CPU 는 한산했다(최상위 `WindowServer` 59%) — load 가 높았던 것은 CPU 가 아니라 **I/O 대기**다.

### ★부수 관찰 — 가드의 swap 항이 「고장난 눈금」일 수 있다

`scripts/verify-runner-health.sh:110-111` 은 **OR** 판정이다.

```
LOAD_RATIO_WARN=2.0   SWAP_RATIO_WARN=0.5
OVER = (load_ratio >= 2.0 || swap_ratio >= 0.5)
```

지금 **load 0.69(정상)인데 swap 0.545 하나로 경고**가 뜬다. 그러면서 메시지는
「이 러너에서 도는 테스트는 **2배 이상 느려진다**」고 단언한다 — load 가 정상인 지금 그 단언은
근거가 없다. **macOS `vm.swapusage` 의 `used` 는 압박이 풀려도 잘 줄지 않는다**(스왑파일이 축소되지 않음).
사실이면 이 항은 **한 번 켜지면 계속 켜져 있는 영구 경고**가 되고, 영구 경고는 아무도 안 읽는다.

**재부팅이 그 자연 실험이다.** 재부팅 직후 swap 이 0 부근으로 떨어지고 **부하 없이 다시 오르면**
그 항은 「현재 압박」이 아니라 「누적」을 재는 것이다. **이번 PR 은 관찰만 등재하고 임계를
건드리지 않는다** — 표본 1개로 임계를 바꾸면 그게 새 오탐이다.

---

## Plan

**dispatch 정책.** 셸·YAML·TS 테스트뿐이라 sub-agent 를 쓰지 않고 메인 에이전트가 직접 수행한다
(`[[orchestrator-instruction-counts-are-blindfolds]]` 회피). 검증은 **전수 열거 + 뮤테이션**으로만 한다.

**TDD.** ①②는 각각 기존 하네스가 있다 — `scripts/workflow/merged-pr-run-cleanup.test.ts`(케이스 11개)와
`scripts/verify-master-plan.sh`. `test:` 커밋이 구현 커밋보다 먼저다.

---

### Task 1. 재부팅 후 기준선 재측정 (Maxi 수동 재부팅 선행)

**메타**. agent: `backend-engineer` · files: [`docs/plans/2026-08-12-debt24-runner-reclaim.md`] · depends-on: []

**검증**. 위 「재부팅 전 기준선」과 **같은 9개 항목**을 재서 after 표로 등재.
`bash scripts/verify-runner-health.sh` 판정 변화 기록. **표본 1개로 임계를 바꾸지 않는다.**

---

### Task 2. RED — `main` 의 낡은 run 취소 케이스

**메타**. agent: `backend-engineer` · files: [`scripts/workflow/merged-pr-run-cleanup.test.ts`] · depends-on: []

**RED**. 기존 하네스(케이스 11개)에 추가한다. 보호 계약을 **좁히는** 것이라 계약 테스트가 짝으로 필요하다.

| 케이스 | 기대 |
|---|---|
| `main` 의 run 중 **현재 HEAD 커밋** 것 | **절대 취소 안 함** (기존 계약의 핵심) |
| `main` 의 run 중 **HEAD 가 아닌 낡은 커밋** 것 | **취소** |
| `main` 인자인데 HEAD 조회 실패 | **아무것도 안 함** (fail-open — 기존 계약) |
| `master`/`HEAD` 인자 | 기존대로 무접촉 |

**실패 메시지 (예상)**. 현재 `PROTECTED=("main" "master" "HEAD")` 가 `main` 을 통째로 거르므로
「낡은 커밋 run 취소」 케이스가 **0건 취소**로 red.

**검증**. `node --experimental-strip-types --test scripts/workflow/merged-pr-run-cleanup.test.ts` red.

---

### Task 3. GREEN — `cancel-merged-pr-runs.sh` 에 main 전용 경로

**메타**. agent: `backend-engineer` · files: [`scripts/cancel-merged-pr-runs.sh`] · depends-on: [2]

**GREEN**. `TODOS.md:2652` 처방 ① — 보호 계약을 **「main 무접촉」에서 「현재 HEAD 검증 무접촉」으로** 좁힌다.

- `master`·`HEAD` 는 **기존대로 통째 무접촉** (좁히는 것은 `main` 하나뿐)
- `main` 은 `git rev-parse origin/main` 으로 현재 HEAD 를 구하고 **`headSha` 가 다른 run 만** 취소
- HEAD 조회 실패·`gh` 부재·API 오류 → **아무것도 안 하고 exit 0** (기존 fail-open 계약 유지)

**★이 변경이 자기 발등을 찍는 경로.** 머지 직후 main push CI 는 **새 HEAD** 로 돈다.
그 run 을 죽이면 이 도구가 고치려던 문제(현재 main 검증 0건)를 스스로 만든다 — Task 2 의 1번 케이스가 그것을 잡는다.

**검증**. 하네스 green. `bash -n` 구문 검사.

---

### Task 4. 뮤테이션 — 보호 계약이 실제로 배선됐는가

**메타**. agent: `backend-engineer` · files: [`scripts/cancel-merged-pr-runs.sh`] · depends-on: [3]

| 뮤테이션 | 기대 |
|---|---|
| M1. HEAD 비교를 제거(전부 취소) | **red** — 현재 HEAD 보호 케이스가 잡는다 |
| M2. `main` 을 다시 `PROTECTED` 로 되돌림 | **red** — 낡은 커밋 취소 케이스가 잡는다 |
| M3. `master` 를 `PROTECTED` 에서 제거 | **red** — 좁힌 범위가 `main` 뿐임을 고정 |
| M4. HEAD 조회 실패 경로를 「전부 취소」로 | **red** — fail-open 케이스 |

**0파 교훈 적용.** 「없으면 검사 안 함」 경로를 함께 본다 — `gh` 부재 시 조용히 통과하는지.

---

### Task 5. RED — Gradle CI 설정 판별식

**메타**. agent: `backend-engineer` · files: [`scripts/workflow/*.test.ts` 또는 `verify-master-plan.sh`] · depends-on: []

**RED**. 두 목록을 **서로 검사**한다 (`[[two-lists-never-check-each-other]]`).

| 단언 | 이유 |
|---|---|
| `backend/gradle.properties` 의 `daemon`·`parallel` 은 **`false` 유지** | 로컬 wave 병렬 dispatch 안정성 (주석의 원래 사유) |
| `backend-ci.yml` 의 gradle 호출은 **`--parallel --build-cache` 를 명시적으로 전달** | CI 는 잡이 러너를 독점하므로 전제가 없다 |
| 두 목록이 **어긋나면 FAIL** | 한쪽만 고치면 「로컬에서 켜져 사고」 또는 「CI 에서 안 켜져 무효」 |

**현재 값 실측.**
```
backend/gradle.properties
  org.gradle.daemon=false   org.gradle.parallel=false   org.gradle.configureondemand=false
  org.gradle.caching        ← 항목 자체가 없음 (기본 false)
```

**검증**. 판별식 추가 직후 **red** (아직 워크플로우가 플래그를 안 넘김).

---

### Task 6. GREEN — CI 에서만 Gradle 최적화 켜기

**메타**. agent: `backend-engineer` · files: [`.github/workflows/backend-ci.yml`] · depends-on: [5]

**GREEN**. `gradle.properties` 는 **손대지 않는다.** 워크플로우의 gradle 호출에 플래그만 붙인다.

**★`--build-cache` 는 「안 돌리고 UP-TO-DATE 통과」를 만든다.** 그것이 장부가 경고한 함정이라
Task 7 의 짝 판별식 없이 이 task 를 닫지 않는다.

**검증**. Task 5 판별식 green.

---

### Task 7. ★짝 판별식 — 「미검증인데 초록」 차단

**메타**. agent: `backend-engineer` · files: [`.github/workflows/backend-ci.yml`] · depends-on: [6]

빌드캐시가 켜지면 **테스트를 안 돌리고 통과**할 수 있다. 그것을 run 요약에서 보이게 한다.

| 단언 | 근거 |
|---|---|
| 테스트 XML 산출물이 **이번 run 에서 생성**됐는가 (신선도) | `[[lint-fails-first-leaves-stale-test-xml]]` — 미실행인데 직전 결과가 남는다 |
| 테스트 케이스 수가 기준선(**3,247**) 대비 **감소 0** | `[[measured-the-wrong-thing-twice]]` — 개수가 아니라 분포까지 |
| 캐시 히트로 **건너뛴 태스크를 run 요약에 남긴다** | 「무엇을 안 쟀나」가 보이지 않으면 초록이 거짓말한다 |

**★조용한 축소 금지.** 건너뛴 것이 있으면 **숫자로** 남긴다.

---

### Task 8. 개선폭 측정 + 장부 갱신

**메타**. agent: `backend-engineer` · files: [`TODOS.md`, `docs/progress.html`] · depends-on: [1, 4, 7]

**측정은 `cancelled` 건수가 아니라 대기 시간과 실행 합계로 한다** (`TODOS.md:2657`).

| 지표 | before (2026-08-12 실측) | after |
|---|---|---|
| `backend-ci` 대기 시간 | 28 · 90 · 56 · 19 · 4 · 13분 | ? |
| `backend-ci` 실행 합계 | 40 · 42 · 50 · 44 · 32 · 34분 | ? |
| 자원 9항목 | 위 기준선 표 | Task 1 |

**장부 갱신.**
- `TODOS.md:2631` (낡은 main run) → **✅ 해소** (①)
- `TODOS.md:1924` → **⬜ 유지**, 후속 표를 **1건(③ 모듈 선택)** 으로 정정 + ②의 해소 블록 등재
- ⬜ 카운트 **23 → 22**, `docs/progress.html` 재생성

**★해소 범위를 넘겨 읽지 말 것.** ②를 닫아도 `backend-ci` 는 여전히 전 모듈을 돈다.

## Plan 메타

- task 수: 8
- 구현 규율: TDD (T2 RED → T3 GREEN → T4 뮤테이션 / T5 RED → T6 GREEN → T7 짝)
- 병렬 dispatch: **미사용**
- 직렬 제약: T2→T3→T4 (좀비 run), T5→T6→T7 (Gradle), 둘 다 T8 선행
- **선행 수동 작업**: Maxi 재부팅 (Task 1 의 전제)

## 리뷰 결과 (← /bts-review-plan 채움)

fast-track 스킵 — 게이트 2(`/bts-codereview`)에서만 정지.
