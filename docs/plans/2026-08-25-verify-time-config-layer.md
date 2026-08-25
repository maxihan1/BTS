# 검증 시간 단축 — 설정 계층 + jOOQ 캐시

> 티어: T2
> slug: verify-time-config-layer
> type: chore
> agent: backend-engineer
> 생성: 2026-08-25

## Brief

Maxi 원문 — 「작업할때마다 검증 테스트 실측 등 너무 많은 시간이 소요 되는거 같은데 이걸 개선
할 수 있는 방법이 없을까? 간단한 수정에도 몇시간이 걸리니깐 너무 답답하고 비효율적임」.

원인을 추측하지 않고 실측부터 했다. **느린 것은 테스트가 아니라 테스트 주변이었다.**

## 티어 근거 (표면 실측)

`backend/modules/identity-access/build.gradle.kts` 를 건드린다 — 보안 표면이라 T2 하한이다.
그 외 `backend/**` 빌드 스크립트 9개(BE_MAIN) · `scripts/workflow/*.ts`(GUARD_CI, T2) ·
`.claude/skills/**`(HARNESS, T1) · `.husky/pre-push`(T1). 섞이므로 최고 티어 **T2**.

## 착수 시점 실측 (기준선)

| 지표 | 실측 | 출처 |
|---|---|---|
| backend-ci 평균 벽시계 | 43.7분 (최대 55분, n=14) | `gh run list` 60건 파싱 |
| frontend-ci 평균 | 20.7분 (n=5) | 같음 |
| backend `test` 태스크 벽시계 | 20.7분 | `build/test-results/**/*.xml` 1,032 testsuite |
| ├ 실제 테스트 메서드 | 7.5분 (36%) | 같음 |
| └ **fixture 오버헤드** | **13.2분 (64%)** | 같음 |
| identity-access fixture 비중 | 86.8% (9.3분 중 8.1분) | 같음 |
| vitest 전량 | 448초 / 594파일 9,812건 | 로컬 실측 |
| └ jsdom environment | 1,438초 (누적 워커시간의 49%) | vitest 리포트 |
| **no-op gradle 빌드** | **12초** (3 tasks up-to-date) | 로컬 실측 3회 |
| pre-push 판별식 | 21.0초 (훅 주석엔 「5.1초」 — 4배 낡음) | 로컬 실측 |

## 무엇이 시간을 먹고 있었나

1. **`backend/gradle.properties` 6줄 중 3줄이 성능을 껐다.** `daemon=false` · `parallel=false` ·
   `caching` 미설정. 2,192개 Kotlin 파일을 데몬 없이 순차로 캐시 없이 매번 콜드 컴파일했다.
2. **`generateJooq` 가 항상 재실행됐다.** 사유는 입력 선언 부재가 아니라 `Task.upToDateWhen is
   false` — nu.studer 9.0 이 `allInputsDeclared` 가 꺼져 있으면 스킵 자체를 금지한다. 태스크는
   이미 `@CacheableTask` 였다. 5개 모듈이 clean 빌드마다 Postgres 컨테이너를 띄웠다.
3. **configuration cache 가 원천 봉쇄돼 있었다.** 9개 모듈 build script 가 configuration
   시점에 `docker context inspect` 서브프로세스를 띄웠다(고유 문제 8건, 전부 같은 원인).

## 한 일

| # | 변경 | 파일 |
|---|---|---|
| 1 | daemon·parallel·caching 켜기, heap 4096m/3072m, `workers.max=4` | `backend/gradle.properties` |
| 2 | `allInputsDeclared` + `init_codegen.sql` 입력 선언 | jOOQ 5개 모듈 `build.gradle.kts` |
| 3 | `ProcessBuilder` → `providers.exec` + `doFirst` 소비 | 9개 모듈 `build.gradle.kts` |
| 4 | `configuration-cache=true` | `backend/gradle.properties` |
| 5 | 린트 호출을 분리하고 `--rerun-tasks` 부착 | `.claude/skills/bts-impl/SKILL.md` |
| 6 | 공허한 가드 2건 수리 | `bts-impl/SKILL.md` · `.husky/pre-push` |

### ★`parallel=true` 는 실측으로 되돌렸다

원안은 `parallel=true` 였고 실제로 켰다가 껐다. 전량 `test --rerun-tasks` A/B —

| | 벽시계 | 실행 태스크 | 결과 |
|---|---|---|---|
| `parallel=true` | 762초 | **52/59 — 중단** | ❌ `:modules:notification:test` 실패 |
| `parallel=false` | **928초** | **59/59** | ✅ BUILD SUCCESSFUL |

재실행하면 실패 대상이 바뀐다(SUB-2 → SUB-1). **결함이 아니라 흔들림**이다. 깨지는 쪽은
`NotificationWorkerSubscriptionFilterTest` 처럼 `Awaitility` + `poll-interval 50ms` +
`Thread.sleep(2_000)` 로 **구조적으로 시간에 민감한** 워커 테스트다. CPU 를 뺏기면 진다.
`parallel=true` 가 그 민감성을 만든 게 아니라 **드러냈다.**

그리고 이득이 작다. 프로파일상 `parallel=true` 의 태스크 합계가 **28.5분**이었다 — 경합으로
각 태스크가 2배 느려진 것을 병렬로 겨우 되돌린 것뿐이라 실질 이득은 **~18%**다. 게다가 일상
루프(pre-push 는 바뀐 모듈 1~3개만)는 모듈 간 병렬을 거의 안 쓴다. **흔들림과 바꿀 값이 아니다.**

되살리려면 **먼저 그 테스트들의 시간 민감성을 없애라.** 순서가 반대면 가짜 초록이 된다.
판별식 `gradle-perf-contract.test.ts` 의 `DELIBERATELY_OFF` 가 이 값을 잡고 근거를 띄운다.

### 모듈별 test 벽시계 (parallel=true 프로파일, fixture 표적 선정용)

| 모듈 | 벽시계 | 컨테이너 선언 파일 | 공용 base |
|---|---|---|---|
| **issue-tracking** | **10분 9초** | 67 | 58 상속 |
| **identity-access** | **9분 48초** | 110 | **없음** |
| project-workflow | 3분 19초 | 30 | — |
| notification | 1분 42초 | 11 | 7 |
| automation | 1분 40초 | 2 | 0 |
| agile-planning | 57초 | 3 | — |
| app | 53초 | 2 | — |

**상위 2개가 태스크 합계 28.5분 중 20분(70%).** `identity-access` 는 110파일이 각자 컨테이너를
띄우는데 공용 postgres base 가 아예 없다 — fixture 공사의 1순위 표적이다.

**16GB 머신이라 heap 을 원안(6144m/4096m)에서 낮췄다.** Testcontainers 가 Docker 쪽 메모리를
따로 먹어 여유를 남기지 않으면 병렬 테스트에서 컨테이너와 JVM 이 서로를 굶긴다.

### 5. 가 필요한 이유

`caching=true` 는 ktlint/detekt 를 캐시로 UP-TO-DATE 통과시킬 수 있다. `backend-ci.yml:383-387`
은 이미 그 사실을 알고 `--rerun-tasks` 로 우회하지만 **로컬 검증 명령에는 그 우회가 없었다.**
성능 스위치와 우회는 한 계약이라 `scripts/workflow/gradle-perf-contract.test.ts` 가 둘을
같이 잰다 — 갈라지면 red 다.

### 6. 의 내용

- `bts-impl/SKILL.md` 의 `pnpm typecheck lint test` 는 `tsc -p ... --noEmit lint test` 로
  전개돼 **TS5042 로 즉사**했다. 프론트 검증이 typecheck 첫 줄에서 죽고 lint 도 test 도 안 돌았다.
- `.husky/pre-push` 주석의 「전량 5.1초」가 오늘 21.0초였다. 그 낡은 값이 「pre-commit 으로
  옮겨도 된다」 판단 근거로 쓰이고 있었다. 이력 표로 바꾸고 「먼저 다시 재라」를 명시했다.

## 실측 결과

| 지표 | 전 | 후 |
|---|---|---|
| **no-op gradle 빌드** | 12초 | **0.65~0.98초** |
| **백엔드 전량 (`test --rerun-tasks`, 59/59 강제)** | 33분(2026-07-27) · 55분(훅 주석) | **15분 27초** |
| `generateJooq` 무변경 재실행 (1모듈) | 11초 (매번 `1 executed`) | **3초** (`1 up-to-date`) |
| 원복 후 재실행 | 11초 | **2초** (`FROM-CACHE`) |
| jOOQ 5개 모듈 무변경 재실행 | 55초 상당 | **3초** (`5 up-to-date`) |
| configuration cache | 저장 불가 (문제 8건) | **`entry reused`** |

## 검증

- **red-first.** `gradle-perf-contract.test.ts` 를 먼저 만들어 4 fail / 4 pass 를 눈으로 봤다.
  양성 대조군 4건이 초록이라 판별식이 공허하지 않다.
- **jOOQ 뮤테이션.** `init_codegen.sql` 에 컬럼 1개를 넣으니 `1 executed` 로 재실행됐다.
  stale 생성물이 조용히 쓰이지 않는다. 원복 후 `FROM-CACHE`.
- **Testcontainers 회귀.** `providers.exec` 전환 뒤 실제로 컨테이너를 띄우는 3개 모듈
  **1,722건 통과 · 0 실패** (project-workflow 731 · agile-planning 504 · slack-integration 487).
  결과 XML 갱신 시각으로 신선도까지 대조했다.
- **판별식 전량** 420건 · 14초 · 0 실패.
- **백엔드 전량** `./gradlew test` **793초(13분 12초) · EXIT=0**. 종전 실측은 55분
  (`.husky/pre-push` 주석) · 33분 (`backend-ci.yml:9-14`, 2026-07-27).
  결과 XML 전수 집계 **1,032 클래스 / 10,411 tests / failures 0 / errors 0**.
  ★단 이 실행은 `5 executed, 54 up-to-date` 였다 — 초록을 「전부 돌았다」로 읽으면 안 된다
  ([[gradle-batched-task-partial-test-run]]). XML 갱신 시각으로 실제 실행을 대조하고,
  안 돈 모듈은 `--rerun-tasks` 로 강제 실행했다. **최종 — 10개 모듈 11개 test 태스크가
  전부 configuration cache 를 켠 상태에서 실행됐고 클래스 1,034 · tests 10,419 ·
  failures 0 · errors 0.**
- **★그 강제 실행이 잠복 회귀를 잡았다.** `app:test --rerun-tasks` 에서
  `Task ':modules:issue-tracking:generateJooq': invocation of 'Task.project' at execution
  time is unsupported` 가 터졌다. `doFirst` 안에서 `project.projectDir` 로 코드젠 입력
  파일을 읽고 있었다. **configuration cache 의 실행시점 위반은 그 태스크가 실제로 돌 때만
  드러난다** — generateJooq 는 위 2. 적용 후 거의 항상 스킵되므로 특히 잘 숨는다. 경로를
  configuration 시점에 `project.file(...)` 로 잡아 캡처하도록 고쳤다.
  ([[config-cache-violation-hides-behind-uptodate]])
- **린트 캐시 우회 확인.** `--rerun-tasks` 없이는 `3 up-to-date`, 붙이면 `3 executed`.
  캐시가 실제로 걸리고 우회도 실제로 듣는다.

## 하지 않은 것 — 이유와 함께

- **fixture 대공사 (64%, 최대 표적).** identity-access 110파일 공용 base 이관, `Flyway.configure()`
  267회 호출 제거, Spring context 67시그니처 통합. 테스트 산토리를 건드리므로 재측정 후 결정한다.
- **vitest jsdom 분할.** 실측으로 기각했다. `.test.ts` 252개를 node 환경으로 돌리니 97개만
  통과하고 155개가 실패했는데, **사유가 DOM 사용이 아니라 `fetch('/api/…')` 상대 URL 에 base 가
  없어서**였다(jsdom 이 `http://localhost` 를 준다). `localStorage` 도 같다. 즉 설정 수준 분할이
  아니라 node 환경 shim 이 필요하다. 깨끗한 글롭은 `src/i18n` 24개뿐이고 7워커 기준 체감 6초라
  새 목록을 유지할 값이 아니다.
- **`maxParallelForks` 상향.** 전 모듈 1. `issue-tracking/build.gradle.kts` 주석이 OOM 방지라고
  이유를 적어 뒀고 그 이유가 실재한다.
- **ktlint `setSource` 오버라이드 수정.** 3개 모듈이 task input 을 강제 교체해 up-to-date 판정을
  망친다. `--rerun-tasks` 로 계속 우회한다.
- **e2e 713개 배선.** 훅·CI 어디에도 없다. 별개 스코프.

## 다음에 정할 것

새 숫자를 보고 하나를 고른다. 지금 고르지 않는다.

1. fixture 대공사 — identity-access 86.8% 오버헤드가 그대로면 여기가 다음 표적이다.
2. Spring context 통합 — 67시그니처 중 52종이 단일 사용. `automation` 은 24파일에 23종.
   `@MockBean` 308회가 캐시 키를 쪼갠다.
3. `BulkOperationIntegrationTest` 분리 — 단독 122초로 백엔드 테스트 시간의 27%.
4. vitest node 환경 shim — `fetch` base URL + `localStorage` 를 주면 184파일이 열린다.
