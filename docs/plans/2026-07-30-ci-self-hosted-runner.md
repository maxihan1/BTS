# GitHub Actions CI를 self-hosted 러너로 전환

> slug: ci-self-hosted-runner
> type: chore (인프라/CI 설정)
> agent: 컨트롤러 직접 수행 (sub-agent 호출 금지 지시)
> 생성: 2026-07-30

## Brief

### 왜 지금

2026-07-29 17:32 KST 부터 **GitHub Actions 결제 차단으로 CI 전체가 죽어 있다.**

- 실측 서명 — 잡이 `steps=0` · `runner_name` 없음 · 시작 2~12초 만에 종료.
- 어노테이션 원문 — `The job was not started because recent account payments have failed or your spending limit needs to be increased.`
- 차단 이후 실행 **12/12 전부** 같은 어노테이션. 경계 이전에는 `failure` 가 **0건**(성공 아니면 cancelled) — 양성·음성 대조가 깨끗하게 갈린다.

| 시각 (KST) | 실행 | 결과 |
|---|---|---|
| 07-29 17:15 | `fix/assembly-nonprod-bean-wiring` (#321) PR | ✅ 마지막 성공 |
| 07-29 **17:32** | main push (#321 머지) | 🔴 첫 차단 |
| 07-30 00:25 | main push (#322 머지) | 🔴 차단 |
| 07-30 07:25 | main push (#323 머지) | 🔴 차단 |

⇒ **#322 · #323 은 CI 0회로 머지됐다.** 로컬 검증 기록은 #323 에만 남아 있다.

### 이미 끝난 준비 (이 PR 범위 밖, 코드 변경 0)

- self-hosted 러너 `maxi-mac-bts` 등록 완료. 라벨 `self-hosted, macOS, ARM64, bts-local`.
  설치 위치 `~/actions-runner-bts`(저장소 밖), launchd 상시 서비스, 상태 `online`.
  tarball sha256 `8e8839c4…b079` 검증 통과.
- **R8 판별 완료** — 확인 사격(run `30508738275`, 폐기 브랜치)에서 `steps=4` · `success`.
  로그 실측 `RUNNER_OS=macOS ARCH=ARM64 NAME=maxi-mac-bts` · Docker `29.1.2` · `openjdk 21.0.11`.
  ⇒ **결제 차단은 GitHub 호스팅 러너에만 적용되고 self-hosted 는 무관하다**가 실증됨.
  폐기 브랜치·worktree 정리 완료.

### 이 PR 이 하는 일

`.github/workflows/` 3개 파일의 `runs-on` **9곳**을 self-hosted 로 옮기고, 옮기면서 **새로 생기는 충돌면**을 함께 봉합한다.

### 착수 전 신규 위험 전수 (#323 에서 유효했던 R표 방식)

「공유 자원(러너·포트·워크스페이스)을 옮기는 변경은 옮긴 뒤 무엇이 겹치는지 표로 셀 것」 — 메모리 `vite-preview-port-cors-align-done`.

| # | 충돌면 | 실측 근거 | 심각도 | 상태 |
|---|---|---|---|---|
| R1 | **5433 포트 선점** — `bts-postgres-dev` 컨테이너 상시 LISTEN | `lsof` PID 23698 · `docker ps` | 🔴 즉시 차단 | 설계 갈림길 |
| R2 | self-hosted 는 **워크스페이스를 재사용**한다 (ubuntu-latest 처럼 매번 새 VM 아님) | 러너 동작 | 🔴 이전 실행 `build/`·테스트 XML 잔재 → 거짓 초록 | 설계 갈림길 |
| R3 | 러너 1대 = 동시 1잡. 현재 **12잡 병렬** | 매트릭스 9 + 별도 3 | 🟡 벽시계 33분+ 회귀 | 설계 갈림길 |
| R4 | 러너 꺼지면 **queued 무한 대기** (실패로도 안 끝남) | 러너 동작 | 🟡 | 봉합 대상 |
| R5 | `runs-on` **9곳** (backend 4 · frontend 3 · infra 2) | grep 실측 | 🟡 일부만 옮기면 나머지 계속 차단 | 봉합 대상 |
| R6 | Testcontainers 가 로컬 Docker 공유 | `bts-postgres-dev` · `db`(mariadb) 상시 | 🟢 랜덤 포트라 충돌 낮음 | 관찰 |
| R7 | `setup-java`·`setup-node`·`setup-gradle`·`pnpm/action-setup` 의 macOS ARM64 지원 | Java 21.0.11 · Node 24.5.0 · pnpm 11.1.3 · arm64 실측 | 🟢 전부 지원 | 관찰 |
| R8 | 결제 차단이 self-hosted 까지 막는가 | 확인 사격 `steps=4 success` | ✅ **해소** | 완료 |

### 판별식 영향 실측

`scripts/workflow/ci-module-coverage.test.ts` 는 `backend-ci.yml` 을 읽지만 **`runs-on` 은 단언하지 않는다**
(매트릭스 모듈 집합 ↔ `settings.gradle.kts` 차집합 + `app` 별도 잡 존재만 확인).
⇒ **잡 구조를 유지하면 이 판별식은 안 깨진다.** 구조를 바꾸면 같은 PR 에서 판별식도 갱신해야 한다.

### 범위 밖 (별건으로 남긴다)

- `backend-ci` 트리거에 `docs/plan/product/**` 부재 — `bc-keyword-coverage.test.ts` 가 그 경로를 읽는데
  트리거에 없어 FR 문서만 바꾸는 PR 에서 판별식이 0회 실행된다. #320 에서 실증
  (`ui/fr-ux-07-active-project-key` 브랜치 실행 7건 전부 frontend-ci, backend-ci 0건 — CI 정상 구간).
  **결제 차단과 독립인 별개 결함.** 같은 파일 1줄이라 이 PR 에 합칠지는 게이트 1 판단 항목.
- GitHub 결제 자체 해결 (Maxi 계정 작업).

## 도메인 정리 (← /bts-domain)

**생략** — 도메인 용어·BC 코드 0. `classify` 의 `primary_bc=automation` 은 "Actions" 오매칭이며
BTS 의 automation BC(룰 자동화 도메인)와 무관하다.

## 스펙 (← /bts-spec)

**생략** — FR 없음, 사용자 노출 동작 변화 없음. FR 총수 불변 139.

## Brainstorming Check (← /bts-spec Phase B)

**생략** (위와 동일 사유)

## Plan

### 조사로 새로 드러난 것 (Brief 위험표 갱신)

**R1 에 깨끗한 해법이 있다.** `backend/modules/app/src/main/resources/application.yml:39` 이
`url: ${BTS_DB_URL:jdbc:postgresql://localhost:5433/bts}` — **이미 환경변수 오버라이드 지점이 있다.**
prod 배포도 같은 경로를 쓴다(`infra/docker-compose.prod.yml:113`). `BTS_DB_URL` 사용처 전수 5곳,
5433 하드코딩은 **전부 KDoc 주석**이고 실행 경로에는 없다.
⇒ 서비스 컨테이너 호스트 포트만 옮기고 잡 `env` 로 덮으면 된다. **Kotlin 0줄.**

**R2 는 처음 평가보다 가볍다.** 세 워크플로우 어디에도 `clean:` 오버라이드가 없어
`actions/checkout@v4` 기본값 `clean: true` = `git clean -ffdx && git reset --hard HEAD` 가 매 잡마다 돈다.
`-x` 가 gitignore 대상까지 지우므로 `build/`·`node_modules/`·테스트 XML 이 **잡마다 소멸**한다.
러너 워크스페이스는 `~/actions-runner-bts/_work/BTS/BTS` 로 **Maxi 작업 트리와 별개**다.
⇒ 잔재성 거짓 초록의 주 경로는 이미 닫혀 있다. 남는 것은 아래 R9.

**★R5 가 처음 평가보다 심각하다 — 재귀 결함이 여기에도 있다.**
`backend-ci` 트리거는 `.github/workflows/backend-ci.yml` **자기 자신만** 걸고 있고
`frontend-ci.yml`·`infra-ci.yml` 은 없다. 판별식(아래 T1)은 **3개 파일 전부**를 읽는다.
⇒ `frontend-ci.yml` 만 `ubuntu-latest` 로 되돌리는 PR 은 판별식을 **0회 실행**하고 통과한다.
**판별식을 무력화하는 PR 이 정확히 그 결함을 만드는 PR** — 메모리
`discriminant-input-list-vs-ci-trigger-list` 와 **동일 양식**. 처방도 동일: 목록을 늘리지 말고
`INPUTS` **한 선언에서 파생**시킨다.

**신규 위험 2건 추가.**

| # | 충돌면 | 근거 | 심각도 | 처방 |
|---|---|---|---|---|
| R9 | `~/.gradle`·Docker 데몬을 **Maxi 로컬 개발과 공유**한다 | 같은 머신 | 🟡 동시 사용 시 경합·데몬 상태 오염 | 문서화(T7). `--rerun-tasks` 는 lint 잡에 이미 있음 |
| R10 | Actions **캐시 서비스**(`setup-gradle`·`setup-node cache: pnpm`)가 결제 차단 하에서 동작하는지 미검증 | 미확인 | 🟢 최악이라도 캐시 미스(느려질 뿐, 실패 아님) | T5 실측으로 확인 |

**서비스 컨테이너 macOS 동작은 T5 에서 실증한다.** 확인 사격에서 러너가 `docker version` 을
성공적으로 호출했으므로 launchd 최소 PATH 문제는 이미 배제됐지만, 서비스 컨테이너는 러너가
스텝 이전에 **자체적으로** 띄우는 별개 경로라 별도 실증이 필요하다.

### 열린 설계 갈림길 (게이트 1 Maxi 판단)

| ID | 갈림길 | 안 | 컨트롤러 추천 |
|---|---|---|---|
| **D1** | assembly 잡의 DB | **A** 서비스 컨테이너 유지 + 호스트 포트 `55433` + `BTS_DB_URL` env / **B** 컨테이너 제거하고 로컬 dev postgres(5433) 재사용 / **C** CI 중 dev postgres 정지 | **A**. B 는 메모리 `shared-dev-db-preexisting-rows-fake-green`(영속 볼륨 선재 행이 가짜 초록) 정면 위반. C 는 취약 |
| **D2** | 병렬 전략 | **A** 러너 1대·12잡 직렬(~50–60분) / **B** 러너 3대 등록(~20–25분) / **C** 매트릭스를 단일 `./gradlew test` 잡으로 통합 | **A**. B 는 메모리 `concurrent-testcontainers-suite-flaky`·맥 리소스 경합. C 는 `ci-module-coverage.test.ts` 를 같은 PR 에서 갱신해야 하고 「한 모듈 실패해도 나머지 결과」 이점 상실. **먼저 정확성, 측정 후 재검토** |
| **D3** | 되돌리기 방식 | **A** 라벨 하드코딩(9곳) / **B** `runs-on: ${{ vars.CI_RUNNER \|\| 'ubuntu-latest' }}` 저장소 변수 | **A**. B 는 한 스위치로 복귀가 되지만 **git 밖 비가시 상태**를 만들어 「두 목록이 서로를 안 본다」를 재생산하고 판별식이 못 본다. 복귀는 어차피 1줄 치환 |

### Task 목록

`test:` 커밋이 `feat:` 커밋보다 먼저다 (TDD 강제).

---

### Task 1. 러너 라벨 정합 판별식 신설 (RED)

**메타**.
- agent: 컨트롤러 직접
- files: [`scripts/workflow/ci-runner-label-alignment.test.ts`]
- depends-on: []

**RED**. `scripts/workflow/ci-runner-label-alignment.test.ts` 신설. **`INPUTS` 한 선언**에서 파생.

```
INPUTS = [
  { path: '.github/workflows/backend-ci.yml',  coveredBy: '.github/workflows/backend-ci.yml' },
  { path: '.github/workflows/frontend-ci.yml', coveredBy: '.github/workflows/frontend-ci.yml' },
  { path: '.github/workflows/infra-ci.yml',    coveredBy: '.github/workflows/infra-ci.yml' },
]
REQUIRED_CI_TRIGGER_PATHS = [...new Set(INPUTS.map(i => i.coveredBy))]
```

단언 5종.
1. **양성 대조군** — `runs-on` 을 1개 이상 찾는다(0이면 파서 고장). 메모리 `zero-measurement-means-wrong-discriminant`
2. 모든 `runs-on` 이 `bts-local` 라벨을 포함한다 (`ubuntu-latest` 잔존 0)
3. `REQUIRED_CI_TRIGGER_PATHS` 전부가 `backend-ci.yml` 의 `pull_request.paths` 에 있다
4. 같은 것이 `push.paths` 에도 있다 (**양쪽 블록** — 메모리 `seal-closes-only-half-by-default`)
5. **`coveredBy` 짝 검사** — 선언된 `coveredBy` 경로가 실재 파일이거나 glob 이 그 파일을 덮는다
   (짝을 잘못 적으면 파생이 틀린 경로를 요구하며 통과한다. #323 의 M9 로 실증된 양식)

**예상 실패**. 단언 2 (현재 9곳 전부 `ubuntu-latest`) · 단언 3·4 (`frontend-ci.yml`·`infra-ci.yml` 부재)

**검증**. `pnpm test:workflow`

---

### Task 2. `runs-on` 9곳 전환 (GREEN)

**메타**.
- agent: 컨트롤러 직접
- files: [`.github/workflows/backend-ci.yml`, `.github/workflows/frontend-ci.yml`, `.github/workflows/infra-ci.yml`]
- depends-on: [1]

**GREEN**. `runs-on: ubuntu-latest` → `runs-on: [self-hosted, bts-local]` **9곳 전수**
(backend 4 · frontend 3 · infra 2). 잡 구조·이름·매트릭스 **불변** —
`ci-module-coverage.test.ts` 가 매트릭스와 `app` 잡 존재를 단언하므로 건드리면 그 판별식이 깨진다.

각 파일 헤더에 사유 1줄 추가 — 왜 self-hosted 인지, 결제 복구 시 되돌리는 법.

**검증**. `pnpm test:workflow` 단언 2 통과

---

### Task 3. R1 봉합 — assembly 잡 DB 포트 분리

**메타**.
- agent: 컨트롤러 직접
- files: [`.github/workflows/backend-ci.yml`]
- depends-on: [2]

**GREEN**. `assembly` 잡의 서비스 컨테이너 포트 매핑 `5433:5432` → **`55433:5432`**,
잡 레벨 `env: BTS_DB_URL: jdbc:postgresql://localhost:55433/bts` 추가.
이미지 `quay.io/tembo/pg16-pgmq:latest` **불변**(ADR `2026-05-22-pgmq-postgres-image.md`).

주석으로 사유 명시 — 로컬 5433 은 `bts-postgres-dev` 가 상시 점유하며, 그 DB 를 재사용하면
영속 볼륨의 선재 행이 가짜 초록을 만든다.

**검증**. T5 에서 assembly 잡 실측 초록

---

### Task 4. R5 봉합 — backend-ci 트리거에 파생 경로 반영

**메타**.
- agent: 컨트롤러 직접
- files: [`.github/workflows/backend-ci.yml`]
- depends-on: [2]

**GREEN**. `pull_request.paths` **와** `push.paths` **양쪽**에
`.github/workflows/frontend-ci.yml`·`.github/workflows/infra-ci.yml` 추가.
주석에 「이 목록은 손으로 유지하지 않는다 — `ci-runner-label-alignment.test.ts` 의 `INPUTS` 에서
파생돼 자동으로 요구된다」 명시 (기존 `preview-cors-origin-alignment` 선례와 동일 문구).

**검증**. `pnpm test:workflow` 단언 3·4 통과

---

### Task 5. 실측 — PR 자체가 self-hosted 에서 초록 (자기검증)

**메타**.
- agent: 컨트롤러 직접
- files: []
- depends-on: [3, 4]

이 PR 은 `.github/workflows/backend-ci.yml` 을 바꾸므로 **자기 자신의 트리거를 만족**한다.
push 후 다음을 실측한다.

1. 잡이 `steps>0` 으로 실제 실행됐는가 (결제 차단 서명 `steps=0` 과 대조)
2. **서비스 컨테이너가 macOS self-hosted 에서 뜨는가** (`assembly` 잡 — 이번 전환 최대 미지수)
3. R10 — `setup-gradle`·`setup-node cache: pnpm` 캐시 서비스가 동작하는가 (미스여도 실패 아님)
4. 12잡 직렬 실제 벽시계 (D2 재검토용 실측치)
5. `frontend-ci`·`infra-ci` 도 같은 PR 에서 도는가 (T4 로 트리거가 붙었으므로)

**검증**. `gh run view <id> --json jobs` 로 잡별 `conclusion`·`steps` 길이 확인

---

### Task 6. 뮤테이션 — 판별식이 실제로 잡는지 주입 검증

**메타**.
- agent: 컨트롤러 직접
- files: []
- depends-on: [5]

**★기준선을 먼저 커밋한다.** 메모리 `mutation-test-requires-committed-baseline` — 2회 재발했고,
미커밋 수정이 `git checkout --` 으로 삭제돼 red 의 원인이 주입인지 삭제인지 구분 불가해진다.
**하네스 첫 줄에 dirty 검사**를 넣는다. 검출 서명은 「최종 원복 후 클린인데 판별식이 red」.

주입 지점 — **뮤테이션 지점 수 = 검증 범위** (메모리 `mutation-site-count-equals-verified-scope`).
단언마다 최소 1발, 그리고 **삭제·추가 양방향**.

| M | 주입 | 기대 red |
|---|---|---|
| M1 | `frontend-ci.yml` 의 `runs-on` 하나를 `ubuntu-latest` 로 되돌림 | 단언 2 |
| M2 | `infra-ci.yml` 의 `runs-on` 하나를 `ubuntu-latest` 로 되돌림 | 단언 2 |
| M3 | `backend-ci` `pull_request.paths` 에서 `frontend-ci.yml` **삭제** | 단언 3 |
| M4 | `backend-ci` `push.paths` 에서만 `infra-ci.yml` **삭제** (절반 봉인 검사) | 단언 4 |
| M5 | `INPUTS` 의 `coveredBy` 를 실재하지 않는 경로로 바꿈 | 단언 5 |
| M6 | `INPUTS` 에 항목 **추가**(신규 워크플로우 가정) 후 트리거 미반영 | 단언 3·4 (추가 방향) |
| M7 | 파서가 0건을 반환하도록 정규식 훼손 | 단언 1 (양성 대조군) |

**검증**. 7/7 전부 **기대한 단언만** red. 원복 후 클린 + 전량 green

---

### Task 7. 러너 운영 문서화

**메타**.
- agent: 컨트롤러 직접
- files: [`docs/runbooks/self-hosted-runner.md`, `CHANGELOG.md`]
- depends-on: [5]

기록 대상.
- 러너 신원 — 이름 `maxi-mac-bts`, 라벨 4종, 설치 위치 `~/actions-runner-bts`, launchd plist 경로
- **R4 증상과 대처** — 러너가 꺼지면 잡이 `failure` 가 아니라 **`queued` 로 무한 대기**한다.
  `timeout-minutes` 는 큐 대기를 세지 않으므로 잡 레벨에서 못 막는다.
  확인 `gh api /repos/maxihan1/BTS/actions/runners`, 재기동 `~/actions-runner-bts/svc.sh start`
- **R9** — `~/.gradle`·Docker 를 로컬 개발과 공유. CI 실행 중 로컬 Gradle 동시 실행 자제
- 결제 복구 후 되돌리는 법 — `runs-on` 9곳 치환 + 판별식 단언 2 반전, 러너 제거 절차
- `CHANGELOG.md` `[Unreleased]` 갱신

**검증**. `bash scripts/verify-master-plan.sh` EXIT0 · FR 총수 불변 139

---

## Plan 메타

- task 수: 7
- 설계 갈림길: 3 (D1·D2·D3 — 게이트 1 Maxi 판단)
- TDD 강제: yes (T1 `test:` → T2~T4 `feat:`/`chore:`)
- 병렬 dispatch: **없음** (sub-agent 호출 금지 지시, 컨트롤러 직접 순차 수행)
- 파일 겹침 — T2·T3·T4 가 모두 `backend-ci.yml` 을 만지므로 **직렬 필수**
- 추가 검증: `pnpm test:workflow` · `scripts/verify-master-plan.sh` · 실제 CI 실행 실측(T5) · 뮤테이션 7종(T6)
- 예상 시간: 약 40분 (T5 의 CI 실행 대기 ~50분은 별도)

## 리뷰 결과

### 컨트롤러 직접 적대적 리뷰 (2026-07-30)

`bts-review-plan` 분기표는 `chore` 를 fast-track skip 으로 규정한다. 무거운 리뷰 체인은 건너뛰되,
설계 갈림길이 3개 열려 있어 4개 축으로 직접 검토했다. sub-agent 호출 금지 + codex CLI 미설치.

#### 🔴 P1-1 — 판별식 봉인이 절반만 닫힌다 (축 2)

T1 의 단언 5종은 `INPUTS` **안에 있는** 파일만 검사한다. **새 워크플로우 파일을 추가하면서
`INPUTS` 에 넣지 않으면 그 파일은 `ubuntu-latest` 를 써도 아무도 안 잡는다.**

`INPUTS` 목록과 `.github/workflows/` 실제 파일 집합이 **서로를 안 보는 두 목록**이다 —
MEMORY 최상단 지배 결함 양식 `two-lists-never-check-each-other` 그대로다.
봉인 안에서 봉인이 막으려던 양식을 재생산했다(#323 과 동일한 재귀).

**처방 — 단언 6 신설 (차집합, 양방향).**

```
단언 6. new Set(INPUTS.map(i => i.path))  ≡  new Set(glob('.github/workflows/*.yml'))
        어느 방향의 차집합도 비어 있어야 한다.
        - INPUTS 에만 있음 → 삭제된 워크플로우를 계속 요구 (파생이 틀린 경로를 만든다)
        - 파일에만 있음   → 검사받지 않는 워크플로우 (이번 결함)
```

뮤테이션 M8 추가 — `.github/workflows/dummy-ci.yml` 을 만들고 `INPUTS` 에 안 넣으면 단언 6 red.

#### 🔴 P1-2 — T4 와 D2=A 가 서로를 악화시킨다 (축 1, 자기 목적 파괴)

T4 는 `backend-ci` 트리거에 `frontend-ci.yml`·`infra-ci.yml` 을 추가한다. `paths` 는
**워크플로우 레벨**이라 잡별 필터가 없다 ⇒ **`frontend-ci.yml` 한 줄만 고치는 PR 이 backend 12잡을
전부 끌고 온다.** `ubuntu-latest` 에서는 병렬이라 감내할 만했지만, D2=A(러너 1대) 에서는
**맥이 50~60분 점유**된다. 프론트 CI 설정을 손보기가 사실상 불가능해진다.

원인은 판별식을 **backend-ci 안에** 두기로 한 선택이다. 그 잡(`workflow-scripts`)이 backend 트리거에
묶여 있는 이유는 `ci-module-coverage.test.ts` 가 `backend/settings.gradle.kts` 를 읽기 때문인데,
이제 같은 잡이 **워크플로우 파일**도 입력으로 갖게 되면서 두 트리거 요구의 합집합이
backend-ci 에 얹혔다.

**처방 — D4 신설 (아래 갈림길 표).** 판별식 전용 경량 워크플로우로 분리하는 안을 권한다.

#### 🟡 P2-1 — T5 자기검증의 주 단언이 공허에 가깝다 (축 4)

T5 항목 1 `steps>0` 은 **R8 재확인일 뿐** 이번 변경의 판별자가 아니다. 러너가 잡을 집는 순간
자동으로 만족한다. 이번 전환의 진짜 미지수는 **서비스 컨테이너가 macOS self-hosted 에서 뜨는가**이고,
그건 잡 `conclusion` 이 아니라 **러너의 서비스 컨테이너 초기화 로그 구간**을 봐야 판별된다.

**처방.** T5 항목 2 를 「`assembly` 잡 로그의 `Initialize containers` 그룹에서 컨테이너 생성 +
헬스체크 통과가 보이는가」로 구체화. 잡이 다른 이유로 실패했을 때
「컨테이너가 못 떴다」와 「테스트가 깨졌다」를 구분할 수 있어야 한다.

#### ✅ 검증되어 finding 이 아닌 것

- **D1=A 의 arm64 전제** — `quay.io/tembo/pg16-pgmq:latest` 는 `linux/amd64`·**`linux/arm64`**
  멀티아치 매니페스트를 갖고, 로컬 캐시본도 `linux/arm64`. Apple Silicon 에서 QEMU 에뮬레이션 불요.
- **세 워크플로우 모두 이 PR 에서 트리거된다** — 각자 자기 파일을 트리거 경로에 갖고 있어
  T5 자기검증의 전제는 성립한다.
- **D2=A·D3=A 는 근거와 일치**(축 3). D1=A 도 `shared-dev-db-preexisting-rows-fake-green` 근거와 일치.

#### BLOCKER

없음. P1 2건은 **착수 전 계획 수정으로 해소 가능**하며, 아래 갈림길에 반영했다.

### 리뷰 반영 — 갈림길 D4 신설

| ID | 갈림길 | 안 | 컨트롤러 추천 |
|---|---|---|---|
| **D4** | 판별식을 어디서 돌리나 (P1-2) | **A** 그대로 backend-ci 트리거에 추가 / **B** 판별식 전용 경량 워크플로우 신설, `workflow-scripts` 잡을 그쪽으로 이관(트리거는 `backend/settings.gradle.kts` + `.github/workflows/**` + `scripts/workflow/**` 합집합) / **C** A 유지하되 D2 를 B(러너 3대)로 바꿔 드래그 비용을 낮춤 | **B**. A 는 프론트 CI 한 줄 수정에 50~60분을 물린다. C 는 근본 원인(트리거 과대 결합)을 그대로 두고 리소스로 덮는 것이며 `concurrent-testcontainers-suite-flaky` 위험을 새로 들인다 |

### 계획 수정 사항 (게이트 1 승인 시 적용)

- T1 에 **단언 6**(`INPUTS` ⟺ 실제 워크플로우 파일 집합, 양방향 차집합) 추가
- T6 에 **뮤테이션 M8**(INPUTS 미등록 신규 워크플로우 파일) 추가 → 뮤테이션 8종
- T5 항목 2 를 서비스 컨테이너 **초기화 로그 구간** 확인으로 구체화
- D4 선택에 따라 T4 의 대상 파일이 달라짐 (A → `backend-ci.yml` / B → 신규 워크플로우 파일)
