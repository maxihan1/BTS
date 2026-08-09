<!-- 후속 기술부채 추적 — 리팩토링이 필요하나 현재 PR 범위 밖이라 보류한 항목 기록 -->

# TODOS

## ✅ 인프라 — 전체 스위트 동시 실행 시 flaky (2026-07-27 해소)

**해소.** 근본 원인은 **느림이 아니라 메시지 도둑질**이었다. 타임아웃을 늘려도 절대 안 고쳐지는 종류다.

**진짜 원인.** `RecipientResolutionTestcontainersConfig` 는 **싱글턴 컨테이너**를 제공한다.
`RecipientResolutionIntegrationTest` 와 `IssueCommentedNotificationIntegrationTest` 는 각자
Spring 컨텍스트를 띄우므로 `@Scheduled` `NotificationWorker` 도 **두 벌**이 되고, 둘이
**같은 pgmq 큐(`q_issue_events`)를 동시 폴링**한다. 먼저 읽은 쪽이 메시지를 소비하면
다른 쪽은 **영원히 0건**을 본다 ⇒ `awaitility` 15초 타임아웃.
「제때 안 온다」로 보였지만 실제로는 **아예 안 온다**.

**처방 — 자동 폴링 제거 + 동기 드레인.**
1. `@SpringBootTest(classes = ...)` 에서 `NotificationDeliverySchedulingConfig` 를 **뺐다**.
   `NotificationTestBootApplication` 은 `@EnableScheduling` 이 없으므로 자동 폴링이 꺼지고,
   이 컨텍스트가 남의 메시지를 훔칠 수 없게 된다.
2. `await().atMost(15, SECONDS).untilAsserted { ... }` → `drainQueue()` (워커를 **동기 호출**).
   대기가 사라지므로 회차당 30초 가까이 빨라지기까지 했다.

**실측.**
```
백엔드 :modules:notification:test --rerun-tasks  →  4/4 SUCCESS (이전 6회 중 3회 실패)
프론트 pnpm vitest run                            →  5/5 SUCCESS (520 파일)
```

**뮤테이션 검증.** `drainQueue` 의 폴링 호출을 지우면 RR-1·RR-2 가 **FAILED** —
드레인이 실제로 하중을 받는다(장식이 아니다).

**★교훈.** 타임아웃 증상을 「자원 경합」으로 뭉뚱그리면 늘리는 처방으로 간다.
**「메시지가 늦게 오나, 아예 안 오나」를 먼저 가르면** 처방이 정반대가 된다.
등재 시점의 원인 가설 3개(컨테이너 기동 경합 · 폴링 주기 여유 · DB 상태 공유)는 **전부 틀렸다.**

## ✅ 인프라 — 계약 검증 확대 (판별식 신설 + 1차 확대 · 2026-07-27)

**해소.** 단일 결함이 아니라 프로그램이므로 **①측정·동결 판별식 + ②실제 1차 확대** 둘 다 했다.

### ① 커버리지 판별식 — `ContractCoverageTest` (app 모듈)
- 덮는 수가 **줄면 실패**(기전이 조용히 썩는 것 차단), **늘면 동결값을 올리라고** 알려준다
- 갭을 **출력**한다 — 실패시키면 프로그램이 끝날 때까지 CI 가 빨갛다. 대신 규모를 매번 남긴다
  (**침묵하는 상한 금지** 원칙)
- 스냅샷 > 등록이면 실패 — 삭제된 endpoint 의 죽은 항목 탐지
- `docs/contracts/` 를 훑으므로 **새 스냅샷 파일은 자동 반영**(파일 목록 하드코딩 없음)
- 비-공허 짝 + **뮤테이션 확증**(동결값 8→12 로 올리자 FAILED)

### ② 1차 확대 — 커버리지 **8 → 11** (동결값도 11 로 상향)
「소비 폭이 넓고 시드가 싼」 축을 먼저 골랐다 — `core-read.snapshot.json` 신설.

| endpoint | 소비 폭 |
|---|---|
| `GET /api/v1/users/me/whoami` | **모든 화면** — 로그인 직후 세션·권한 게이팅 진입점 |
| `GET /api/v1/issue-types` | 이슈 생성·필터·보드 타입 드롭다운 전역 |
| `GET /api/v1/projects` | 사이드바 프로젝트 트리·프로젝트 선택 전역 |

백엔드(조립 실응답 → 스냅샷)와 프론트(`core-read.contract.test.ts` → Zod `.strict()`)를 **양방향**으로 물렸다.
케이스 목록이 스냅샷 전량을 덮는지 확인하는 단언도 뒀다 — 없으면 백엔드가 endpoint 를 추가해도
`it.each` 가 내 목록만 돌아 프론트는 반응하지 않는다(같은 「두 목록」 양식).

**★뮤테이션이 봉인의 절반이 열려 있음을 잡았다.** 처음엔 `WhoamiResponseSchema` 를 그대로 썼는데,
**필드 추가 주입이 통과**했다(`.strict()` 아님). 프로덕션 스키마는 관대해야 하고(백엔드가 필드를
늘려도 화면이 죽으면 안 된다) **계약 테스트는 엄격해야 한다** — 목적이 달라 `.strict()` 를
테스트에서만 걸었다([[seal-closes-only-half-by-default]]).
재확증 — **추가·삭제 양방향 주입 모두 FAILED**.

### 잔여 (프로그램 계속)
갭 **289**. 다음 우선순위는 ①쓰기 계열(`POST/PATCH/PUT/DELETE` 174개 — 파손 시 데이터 영향)
②에러 응답 형태(RFC7807 일관성) ③다운로드 헤더. 판별식이 진척을 강제하므로 **후퇴는 불가능**하다.

⚠️ `-Dcontract.snapshot.update=true` 재생성 PR 은 diff 의 `null` 증감을 눈으로 확인할 것 —
프론트 Zod `.nullable()` 은 `null` 과 `"string"` 을 둘 다 통과시켜 그 축의 감시자는 스냅샷 한 곳뿐이다.

**★이 항목은 단일 결함이 아니라 프로그램이다.** 292 endpoint 를 덮는 것은 endpoint 마다
시드·인증이 필요한 장기 작업이지 한 번의 수정이 아니다. 그래서 **전수 봉인 대신 커버리지를
측정·동결**하는 판별식을 먼저 세웠다 — 이 저장소의 지배 처방(「두 목록이 서로를 안 본다」)과 같은 형태다.

**실측 (조립 컨텍스트, 2026-07-27).**
```
계약 커버리지 — 스냅샷 8 / 등록 300 endpoint (갭 292)
```

**신설 — `ContractCoverageTest` (app 모듈).**
- 스냅샷이 덮는 endpoint 수가 **줄면 실패**한다 (기전이 조용히 썩는 것을 막는다)
- 늘면 **"동결값을 올려라"** 라고 알려준다 (느슨해진 채 방치되는 것을 막는다)
- 갭을 **출력**한다 — 실패시키지 않는 이유는 프로그램이 끝날 때까지 CI 가 빨갛게 되기 때문이다.
  대신 규모를 매번 눈에 보이게 남긴다(**침묵하는 상한을 두지 않는다**)
- 스냅샷이 등록보다 많으면 실패 — 삭제된 endpoint 의 죽은 항목 탐지
- `docs/contracts/` 디렉터리를 훑으므로 **새 스냅샷 파일은 자동 반영**된다(파일 목록 하드코딩 없음)
- 비-공허 테스트 짝 — 수집 0건 방지 하한. **뮤테이션 확증** — 동결값을 8→12 로 올리자 FAILED

**착수 우선순위 (실측 기반 제안).** 전량을 한 번에 덮으려 하지 말 것.
1. **쓰기 계열** — `POST/PATCH/PUT/DELETE` 174개. 파손 시 데이터 영향이 있다
2. **에러 응답 형태** — RFC7807 일관성. 현재 mock 은 `errorCode`-only 가 55파일 vs ProblemDetail 16파일
3. **다운로드/바이너리** — 헤더 계약(`Content-Disposition`). CORS `exposedHeaders` 와 짝이다

**⚠️ 확장 전에 닫아야 할 것.** #317 스냅샷의 한계를 먼저 해소했는지 확인할 것 —
숫자 타입 붕괴는 2026-07-27 에 해소됐다(`ContractSnapshotCanonicalizer` 분리 + 정수/실수 구분).
**남은 한계** — `description` 의 `null`↔`''` 는 백엔드 스냅샷은 잡지만 **프론트 Zod
(`z.string().nullable()`)는 둘 다 통과시킨다.** 즉 그 축의 감시자는 백엔드 한 곳뿐이며,
`-Dcontract.snapshot.update=true` 로 무심코 재생성하면 변질이 저항 없이 박제된다.
재생성 플래그를 쓰는 PR 은 스냅샷 diff 의 `null` 증감을 눈으로 확인할 것.

**소관**. 프론트↔백엔드 공동. **Maxi 우선순위 결정 필요.**

**증상.** PR #317 이 `docs/contracts/workflow-schemes.snapshot.json` 으로 워크플로우 스킴
**8 endpoint** 에 계약 스냅샷을 세웠다. 나머지 **295 endpoint 는 「MSW 가 MSW 와 맞는」 상태**다.

```
백엔드 REST 매핑 (src/main)  303   GET 122 · POST 87 · DELETE 47 · PATCH 40 · PUT 7
MSW 모크 핸들러              276   GET 117 · POST 81 · DELETE 40 · PATCH 33 · PUT 5
계약 스냅샷 커버              8
```

**왜 중요한가.** 프론트 테스트 8,099건이 전량 초록이어도 **백엔드와의 정합을 보장하지 않는다.**
모크가 백엔드와 다르게 답해도 아무도 모른다 — 이 저장소는 이 기전으로 이미 두 번 데였다.
- #317 — 스킴 계약 8 endpoint 중 정합은 1건뿐이었다(응답 7종 + 요청 1종 파손)
- 아래 §댓글 MSW 항목 — 에러 형태(RFC7807 vs `errorCode`)와 판정 순서가 둘 다 다르다

**★즉 아래 「댓글 MSW 모크가 백엔드와 다르다」 항목은 이 구조적 갭의 한 사례일 뿐이다.**
개별 핸들러를 하나씩 맞추는 방식으로는 295개를 다 못 쫓아간다.

**착수 시 첫 단계.** #317 이 만든 기전(백엔드 prod 조립 부팅에서 실응답 스냅샷 생성 →
프론트가 같은 파일을 `.strict()` 파싱)을 **엔드포인트 단위로 확장 가능한 형태**로 일반화한다.
전량을 한 번에 덮으려 하지 말고 **우선순위 축**을 먼저 정할 것 — 후보는
①쓰기(POST/PATCH/PUT/DELETE 174개, 파손 시 데이터 영향) ②에러 응답 형태(RFC7807 일관성)
③다운로드/바이너리(헤더 계약).

**⚠️ #317 이 남긴 스냅샷 자체의 한계 2건**(아래 §워크플로우 스킴 항목의 잔여)을 일반화 전에 닫을 것 —
**숫자 타입 붕괴**(정규화가 모든 숫자를 `1` 로 만들어 `Long`→`Double` 변경을 못 잡음)를 안 고치고
확장하면 295개에 같은 사각을 복제한다.

**소관**. 프론트↔백엔드 공동. Maxi 우선순위 결정 필요.

## ✅ 인프라 — 백엔드 CI 신설 (해소 2026-07-27)

**해소.** `.github/workflows/backend-ci.yml` 신설 — 잡 4개.

| 잡 | 내용 |
|---|---|
| `modules` (매트릭스 9) | BC 모듈별 `:modules:<m>:test` 병렬. `fail-fast: false` — 한 모듈이 깨져도 나머지 결과를 봐야 범위를 좁힌다 |
| `assembly` | `:modules:app:test` — **서비스 컨테이너 필요**. `quay.io/tembo/pg16-pgmq` + **5433:5432** |
| `lint` | `ktlintCheck detekt --rerun-tasks` — 캐시가 있으면 위반이 UP-TO-DATE 로 통과해 거짓 초록이 된다 |
| `workflow-scripts` | `pnpm test:workflow` (아래 참조) |

**★두 종류의 DB 의존을 구분해야 한다.** Testcontainers(428파일)는 러너 기본 Docker 로 스스로 뜨지만,
`:modules:app` 조립 부팅(10파일)은 `ProdAssemblyHttpTestBase` 가 Testcontainers 를 관리하지 않고
`application.yml` 의 `jdbc:postgresql://localhost:5433/bts` 를 그대로 쓴다. ⇒ 그 잡에만 서비스 컨테이너를 붙였다.
이미지는 **반드시 pgmq 판** — 일반 `postgres:16` 은 마이그레이션에서 실패한다.

**★부수 발견 — `pnpm test:workflow`(57건)를 어느 CI 도 돌리지 않고 있었다.**
그 안에 하드코딩 목록 정합 판별식 3종이 들어 있다(BC 키워드 · 스킬 분기표 ↔ TaskType ·
CI 매트릭스 ↔ Gradle 모듈). 안 돌리면 봉인이 로컬 1회성 확인으로 끝나고 썩는다 —
`infra-ci.yml` 이 존재하는 이유와 같은 실패 양식이다. 전용 잡으로 배선했다.

**매트릭스도 하드코딩 목록이라 판별식을 붙였다.** `ci-module-coverage.test.ts` 가
`settings.gradle.kts` 와의 **차집합 0** 을 강제한다(+ pgmq 이미지·5433 포트·조립 잡 존재 단언).
**뮤테이션 확증** — 매트릭스에서 `automation` 을 지우자 FAILED.

**⚠️ 첫 실행 시 선재 flaky 가 드러날 수 있다.** 이 저장소는 로컬 검증을 전제로 굴러왔다
([[concurrent-testcontainers-suite-flaky]] · [[flaky-determination-needs-repeat-not-single-contrast]]).
빨간불이 나면 **같은 명령을 연속 2회** 돌려 flaky 인지 회귀인지 먼저 가를 것.

<details><summary>원 기록 (보존)</summary>

**증상.** `.github/workflows/` 에 `frontend-ci.yml` · `infra-ci.yml` 둘뿐이다.
**백엔드 테스트가 CI 에서 한 번도 돌지 않는다** — 머지 검증이 전적으로 로컬 수동 실행에 의존한다
(메모리 `no-backend-ci-and-assembly-merge-verification-traps` 가 기록한 상태).

**규모 (2026-07-27 실측).**

| 항목 | 값 |
|---|---|
| 백엔드 테스트 파일 | 940 |
| 실행 테스트 수 | **10,025** (XML 집계, 실패 0) |
| 전체 소요 | 33분 9초 (로컬 M-series) |
| Testcontainers 사용 파일 | 428 (러너 Docker 로 가능) |
| 외부 5433 postgres 의존 | 10 (`modules/app` 조립 부팅) |

**★`infra-ci.yml` 이 `backend/**` 를 경로 트리거로 걸고 있어 "백엔드도 CI 가 있다" 로 오인하기 쉽다.**
그 잡이 돌리는 것은 nginx 마스킹 봉인과 springdoc 미노출 봉인뿐이고 Gradle 테스트는 0건이다.

**착수 레시피 (실측 기반).**
1. `services:` 로 postgres 를 띄운다 — 이미지는 **`quay.io/tembo/pg16-pgmq`** 여야 한다
   (일반 `postgres:16` 은 pgmq 확장이 없어 조립 부팅이 실패한다. ADR `2026-05-22-pgmq-postgres-image`).
   포트 매핑 **5433:5432**, `POSTGRES_DB/USER/PASSWORD = bts/bts/bts`
   (`app` 모듈 `application.yml` 의 `spring.datasource.url` 기본값이 `jdbc:postgresql://localhost:5433/bts`).
2. Testcontainers 는 러너 기본 Docker 로 동작한다 — 별도 설정 불요.
3. 33분은 PR 마다 돌리기엔 길다. **모듈별 잡 분할**(9 BC 병렬)이 현실적이며,
   `:modules:app:test`(조립 부팅)만 별도 잡으로 두면 서비스 컨테이너를 그 잡에만 붙일 수 있다.
4. 경로 트리거는 `backend/**` + `.github/workflows/backend-ci.yml`.

**⚠️ 착수 전 확인.** 이 저장소는 로컬 검증을 전제로 굴러왔다. CI 를 켜는 순간 **선재 flaky 가
드러날 수 있다** — 메모리 `concurrent-testcontainers-suite-flaky` · `flaky-determination-needs-repeat-not-single-contrast`.
켜기 전에 같은 명령을 **연속 2회** 돌려 flaky 목록을 먼저 확보할 것.

**소관**. 인프라 / Maxi 결정(빌드 시간 정책).

</details>

## ✅ identity-access — 컨트롤러 권한 게이트 DRY 추출 (해소 2026-07-27)

**해소.** `web/support/ControllerAuthSupport.kt` 신설로 중복 3종을 단일 지점에 모았다 (**-113줄**).

**★기록의 "14파일" 은 과다 계상이었다.** grep 패턴이 접미사 부분일치라 **별개 상수**
`PAT_FORBIDDEN_RESPONSE` 8건을 삼켰다. 단어경계로 재측정하면 **6파일**이다.

**합치지 않은 것 2종 (실측 근거).**
- `PAT_FORBIDDEN_RESPONSE` 8파일 — 본문 오류코드가 3종으로 갈리고(`session_management_…` /
  `account_linking_…` / `calendar_feed_…`) `CalendarFeedController` 는 타입까지 다르다.
  합치면 프론트가 분기하는 오류코드가 뭉개진다.
- `My*PermissionController` 의 `resolveActorId(request, jwt)` — 이름만 같고 인자·반환·실패처리가
  전부 다르다(PAT 검사 + throw). 추출 대신 **`resolveActorIdOrThrow` 로 rename** 해 혼동을 없앴다.

shared-kernel 로 올리지 않았다 — 반환이 웹 계층 타입이고 타 모듈 동명 함수는 throw 계약이다.

**뮤테이션 2회.** 가드 무력화 → 15건 FAILED(3개 컨트롤러 전부). 공유 상수 변조 → 4파일 반응.
**★M2 가 6파일 중 4파일만 잡은 것도 원인을 규명했다** — 나머지 2파일의 401 케이스는 토큰 없음 →
필터체인 401 이라 컨트롤러 상수를 아예 안 탄다. **선재 커버리지 갭이며 이번 변경이 만든 것이 아니다.**

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, plan-eng-review D19)**. `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 · `resolveActorId` ·
`requireSystemAdmin` 3종 복제는 **선재 부채**다. `global_permission_grants`(FR-PM-10, PR-1)가 신설한
`GlobalPermissionGrantController`가 기존 패턴을 한 벌 더 복제했다. **공통화는 지금 하지 않는다** — 공통화하면
PR-1이 N파일 리팩토링이 되어 글로벌 CLAUDE.md §3(surgical, 변경은 요청받은 것만)과 충돌하고 권한 PR의 리뷰
단위가 무너진다. 대신 복사 + 이 기록으로 후속 작업을 명시한다.

**중복 3종 (2026-07-17 grep 재확인 — PR #277 T4/T5/T6 반영 후 실측치, 추측 아님)**.

| 패턴 | 파일 수 (src/main) | 재확인 명령 |
|---|---|---|
| `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 | 14 | `grep -rlE "FORBIDDEN_RESPONSE\|UNAUTHORIZED_RESPONSE" backend/modules/identity-access/src/main --include="*.kt"` |
| `resolveActorId` 함수 | 7 | `grep -rl "resolveActorId" backend/modules/identity-access/src/main --include="*.kt"` |
| `requireSystemAdmin` 함수 | 3 | `grep -rl "fun requireSystemAdmin" backend/modules/identity-access/src/main --include="*.kt"` |

> ⚠️ **`requireSystemAdmin`은 plan D19가 인계한 "5파일"과 다르다.** plan-eng-review 원안(`docs/plans/2026-07-17-project-management-crud.md:1312`)은 pre-T6 기준 "4파일"로 적었고, T6이 `GlobalPermissionGrantController`에 1벌을 더 복제해 "5파일"이 될 것으로 예상했다(구두 인계). **grep 재확인 결과 실제로는 3파일**뿐이다 — `GlobalPermissionGrantController` · `IssueSecuritySchemeController` · `UserGroupController`. plan의 "4파일" 원안 자체가 애초 과다 계상이었던 것으로 보인다([[spec-stated-count-becomes-blindfold]] 재발 — 숫자를 그대로 물려받지 말 것). 후속 리팩토링 착수 시 이 grep 명령으로 다시 재검증할 것.
>
> `resolveActorId`는 검색 결과 8개 파일에서 매치되나, 1개(`GlobalPermissionGrantControllerTest.kt`)는 KDoc/주석에서 개념을 언급할 뿐 실제 중복 구현이 아니라 제외했다(`src/main`만 집계).

**후속 작업**. 공통 베이스 클래스 또는 shared-kernel 유틸로 추출 — 별도 PR로 분리해 리팩토링 리뷰 단위를 권한 변경과 섞지 않는다.

</details>

## ✅ identity-access — ADR D-1 이중 방어 정합 무가드 (해소 2026-07-27)

**해소.** `GlobalPermissionGrantSchemaMigrationTest` 에 화이트리스트↔CHECK 정합 단언을 추가했다.
`ALLOWED_GLOBAL_PERMISSIONS` 를 `private` → `internal` 로 승격해 리플렉션 없이 직접 읽는다
(리플렉션 가드는 필드명 변경에 조용히 깨진다). `assertThat(allowed).isNotEmpty()` 선단언으로
빈 집합 vacuous 통과를 막았다.

**뮤테이션 확증** — 화이트리스트에 `"MANAGE_NOTHING"` 을 넣자 새 테스트가 FAILED (커밋된 기준선에서 수행 후 원복).
**잡는 방향은 화이트리스트→CHECK 한 쪽뿐**임을 KDoc 에 명시했다 — 반대 방향(CHECK 만 확장)은
다음 마이그레이션 작성 시점의 문제이고, V036 `COMMENT ON COLUMN` 이 그 지점에서 이 테스트를 가리킨다.

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-17 게이트 2)**. **후속으로 미룬다.** ADR이 이미 **잔여 위험 4**로 의식적으로 수용한
항목이고, 두 집합이 **현재 일치하므로 잠복 부채이지 현행 결함이 아니다**. 가드를 넣으려면 private companion
상수에 리플렉션을 걸거나 상수를 `internal`로 승격해야 하는데(prod 코드 변경), 권한 PR의 리뷰 단위를 흐린다.

**무엇이 안 잠겨 있나**. ADR D-1의 이중 방어는 **두 겹이 같은 집합**이어야 성립한다.

| 겹 | 위치 | 값 |
|---|---|---|
| 앱 | `GlobalPermissionGrantService.kt:129` | `val ALLOWED_GLOBAL_PERMISSIONS = setOf("CREATE_PROJECT")` |
| DB | `V036__global_permission_grants.sql:26` | `CHECK (permission IN ('CREATE_PROJECT'))` |

**두 값을 함께 읽는 테스트가 0건이다.** 서비스 KDoc `:34-36`이 스스로 *"🛑 권한코드 추가 시 3곳을 동시에
갱신한다(ADR 잔여 위험 4) … 하나만 놓치면 fail-closed로 조용히 막힌다"*라고 **수동** 동기화를 지시하는데,
그 지시를 강제하는 자동 가드가 없다. BTS가 FR 카운트 drift에 `verify-master-plan.sh`를 붙인 것과 같은 종류의 부채다.

**드리프트 방향별 결과**.
- 화이트리스트만 확장 → 서비스 통과 후 DB CHECK 위반 → `DataIntegrityViolationException` → **400이어야 할 것이 500으로 변질** (서비스 KDoc `:31-32`가 예고한 바로 그 변질)
- CHECK만 확장 → 부여가 **400으로 조용히 거부**

**후속 작업**. `GlobalPermissionGrantSchemaMigrationTest`에 "화이트리스트 전량이 V036 CHECK를 통과하는지"
확인하는 테스트 추가(`@JdbcTest` 자동 롤백에 기댄다). 상수가 private companion이라 리플렉션이 필요하며,
꺼려지면 `internal`로 승격해 직접 참조하는 편이 깔끔하다. **빈 집합이면 루프가 vacuous하게 통과하므로
`assertThat(allowed).isNotEmpty()` 선단언 필수**([[verify-logic-vs-verify-guard]]).

</details>

## ✅ CREATE_PROJECT 상수 단일화 (해소 2026-07-27)

**해소.** identity 의 리터럴을 shared-kernel `GlobalPermissionCodes.CREATE_PROJECT` 참조로 교체했다.

**★기록보다 겹이 하나 더 많았다.** 원 기록은 "shared-kernel · identity 화이트리스트 · DB CHECK 3겹" 이라
적었으나 **앱 안에만 2벌**이었다 — `GlobalPermissionGrantService:129` 와
`WhoamiController:94` 의 자체 상수 `PERMISSION_CREATE_PROJECT`. 둘 다 교체했고,
`WhoamiController` 의 companion 은 이 상수만 담고 있어 함께 제거했다(내 변경이 만든 고아).

**DB 겹(V036 CHECK)은 이전 대상이 아니다** — 적용된 마이그레이션 편집 금지([[app-test-persistent-db-migration-checksum-trap]]).
DB 축 드리프트 방어는 위 ADR D-1 정합 테스트가 담당한다. 그래서 상수 통일 → 정합 테스트 순서였다.

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-18 plan-eng-review)**. **후속으로 미룬다.** FR-PJ PR-2 가 issue-tracking 게이트에서
쓸 `CREATE_PROJECT` 문자열을 **shared-kernel `com.bts.shared.permission`에 `const val` 로 신설**한다(그 패키지의
권한 enum `toPermissionCode()` 관례와 정합). 하지만 identity-access 의 기존 `ALLOWED_GLOBAL_PERMISSIONS`
(`GlobalPermissionGrantService.kt:129`)를 그 상수 참조로 바꾸는 것은 **PR-1(#277) 파일 변경**이라 이번 PR 밖이다
(글로벌 CLAUDE.md §3 surgical + BC 경계 모호 — 권한 PR 리뷰 단위를 흐림).

**무엇이 중복인가 (2026-07-18 실측)**.

| 겹 | 위치 | 값 |
|---|---|---|
| shared-kernel (FR-PJ PR-2 신설) | `com.bts.shared.permission` const val | `"CREATE_PROJECT"` |
| identity 앱 | `GlobalPermissionGrantService.kt:129` | `setOf("CREATE_PROJECT")` |
| DB | `V036__global_permission_grants.sql:26` | `CHECK (permission IN ('CREATE_PROJECT'))` |

**후속 작업**. identity 화이트리스트를 shared-kernel 상수 참조로 교체. 위 [[ADR D-1 이중 방어]] 항목과 **같이 처리**하면
(화이트리스트↔CHECK 정합 테스트 + 상수 단일화) 한 번에 drift 근원 3겹을 2겹으로 줄인다. **의존**. FR-PJ PR-2 머지
후(shared-kernel 상수 실재해야 함). shared-kernel 은 9 모듈이 의존하므로 변경 시 광범위 재컴파일 — 리뷰 단위를 권한
변경과 섞지 않게 별도 PR.

</details>

## ✅ project-workflow — 워크플로우 스킴 프론트↔백엔드 계약 파손 (해소 #317)

**해소 (2026-07-27, PR #317).** 계약 스냅샷 기전 + 뷰 어휘 정렬 + Zod 형태별 분리로 봉합했다.
정본 어휘는 **`key` + `isStandard`**(스킴), **`isDefault`**(기본 매핑)이다. 마이그레이션 0.

**★이 항목의 기록이 두 군데 틀렸다 — 정정.**
1. **규모.** "불일치 3종" 이 아니라 **응답 7종 + 요청 1종**이었다. 8 endpoint 중 정합은 1건뿐이었다
   (이미 `.transform()` 정규화를 하던 assignable 목록). 실측 근거는
   `docs/plans/2026-07-27-workflow-scheme-contract-align.md` §Task 2 A9-② 증거표.
2. **지목 DTO 2건이 오기.** `assignmentResponseSchema` 의 대응 백엔드는 `ProjectWorkflowSchemeController.kt`
   의 `SchemeResponse`(GET)와 `AssignmentResponse`(PUT) **두 개**다 — 한 스키마가 서로 다른 DTO 2개를
   덮고 있던 것이 교집합 0 의 실제 원인이다. 근본 원인은 endpoint 수가 아니라 **형태 수**만큼 스키마를
   나누지 않은 것이었다(스키마 3장이 각각 백엔드 DTO 2개씩을 겸했다).

**봉합 방식.** `docs/contracts/workflow-schemes.snapshot.json` 이 유일 계약 정본이다. 백엔드는
prod 조립 부팅(`WorkflowSchemeContractSnapshotTest`)에서 8 endpoint 실응답과 문자열 동등을 단정하고,
프론트는 같은 파일을 `.strict()` 로 파싱한다(`workflow-schemes.contract.test.ts`). 한쪽이 어긋나면
그 지점에서 즉시 빨간불이 켜진다 — 「MSW 가 MSW 와 맞는다」 상태가 끝났다.

**신규 이연 8건 → 2026-07-28 현재 전량 해소.**
잔여 2건은 ✅ 섹션 본문에 묻혀 있어 「열림 0건」 오보고를 낳았다 — 아래 자기 섹션으로 **승격**했다.
- ✅ `description` 의 **`null`↔`''` 왕복** — `SchemeMetaPanel.handleSave` 에서 역변환 +
  `UpdateSchemeInput.description` 을 `string | null` 로 확장. 전송 body 를 단정하는 회귀 테스트 1건 추가.
- ✅ **낙관적 배정의 key↔name 불일치** — 배정 후보 캐시(`useAssignableWorkflowSchemes`)의 정합 객체를
  통째로 낙관값으로 쓴다. 못 찾으면 낙관적 쓰기를 **생략**한다(틀린 이름보다 옛 카드 유지가 낫다).
- ✅ **롤백 가드 비대칭** — 조건 두 개를 맞추는 대신 `applied` 플래그로 **구조로** 짝지었다.
  쓰기 시점의 사실을 컨텍스트로 넘겨 onError 가 같은 것을 본다. 캐시 엔트리가 없던 경우는
  `removeQueries` 로 원상복구(값을 쓰는 게 아니라 엔트리를 없애야 한다).

**기존 이연 3건.**
- **도메인·DB 어휘 이연** — 도메인 `WorkflowScheme.isDefault` 와 DB 컬럼 `is_default` 는 그대로다
  (ADR D2 — 이번 변경은 뷰 레이어 한정, 마이그레이션 0). 이름이 「표준 스킴」 의미인데 `default` 라
  DB 주석(`V201__workflow_schemes.sql:37`)과도 어긋나 있다. rename 하려면 마이그레이션 + jOOQ 재생성이
  필요하다.
- **cross-BC 이슈타입 조회 실패가 무음** — `issueTypeKey`/`issueTypeName` 이 null 로만 표현돼, 화면은
  「조회 실패」와 「기본 매핑」을 구분해 보여줄 수 없다. `isDefault` 신설로 **오분류는 막았으나**
  실패 자체를 사용자에게 알리는 신호는 아직 없다(ADR 잔여위험 2).
- **`classify-task.ts:45` 의 `'스키마'` 키워드가 Zod·GraphQL·JSON schema 작업을 전부 `migration` 으로
  오분류한다.** 같은 작업 제목 3종이 `migration`/`qa`/`backend` 3개 결과를 냈다. 기존 항목
  「`bts-review-plan` 분기 표에 `type=backend` 가 없다」의 형제 — 하드코딩 키워드가 아니라 **판별식**이 필요하다.
  > 2026-07-27 — 형제 2건(BC_KEYWORDS · 분기표)은 판별식 2종으로 해소했고
  > (`bc-keyword-coverage.test.ts` · `skill-type-coverage.test.ts`), **`'스키마'` → `migration`
  > 오분류는 아직 남아 있다**. type 축은 BC 축과 달리 대조할 정본 목록이 없어 별도 접근이 필요하다.

**별도 작업으로 남은 것 — ★2026-07-27 실측으로 판정이 뒤집혔다.** 아래 §springdoc 항목으로 이관.

## ✅ springdoc `/v3/api-docs` — "미인증 노출" 판정 뒤집힘 + 봉인 신설 (해소 2026-07-27)

**해소.** 원 기록(#317 §별도 작업)이 **두 군데 틀렸다**. 실측으로 정정하고, 지금 안전한 이유를
고정하는 봉인을 세웠다 — `scripts/verify/springdoc-not-exposed.sh` + `infra-ci.yml` 잡.

**정정 ① — 지목 파일이 틀렸다.** 원 기록은 "`OpenApiSecurityConfig.kt` + `OpenApiConfig.kt` 두 곳" 이라
적었는데, `OpenApiConfig.kt` 는 **두 모듈에 동명으로 존재**하고 보안 코드가 있는 쪽은 search 모듈이다.

| 파일 | `web.ignoring()` |
|---|---|
| `issue-tracking/.../issue/config/OpenApiSecurityConfig.kt:20-23` | ✅ 보안 지점 1 |
| `search-export-import/.../search/config/OpenApiConfig.kt:60-63` | ✅ 보안 지점 2 |
| `issue-tracking/.../issue/config/OpenApiConfig.kt` | ❌ 메타데이터만 (`@OpenAPIDefinition`) |

원 기록대로 두 파일을 열면 **issue-tracking 의 메타데이터 파일을 고치고 search 의 진짜 빈을 놓친다.**
"한 곳만 고치면 효과 0" 이라는 경고 자체는 유효하다 — 대상만 틀렸다.

**정정 ② — "노출" 전제가 성립하지 않는다.** 외부 도달 표면이 **0** 이다.

```
infra/prod/nginx.conf:87
  location ~ ^/(api|\.well-known|ical|slack|saml2|oauth2|login/(oauth2|saml2))(/|$)
      → /v3/api-docs · /swagger-ui 는 어디에도 안 걸려 L98 `location /` SPA fallback 으로 떨어진다
infra/docker-compose.prod.yml
      → bts-backend 에 `ports:` 키 자체가 없다. 호스트 발행은 bts-web "18080:80" 하나뿐
        (bts-minio 콘솔은 "127.0.0.1:19001:9001" 루프백 한정)
```

⇒ 미인증인 것은 사실이나 도달 가능 주체는 `bts-net` 도커 네트워크 내부 컨테이너뿐이다.
따라서 *"이번 정렬로 공개 계약임이 확정됐으므로 우선순위가 올랐다"* 는 판단 근거가 무너진다 —
**공개된 적이 없다.**

**그래서 무엇을 했나.** 두 Kotlin 빈을 고치는 대신 **지금 안전한 이유를 고정**했다. 안전성이
배포 토폴로지라는 우연한 성질에 얹혀 있어서, 누가 nginx 정규식에 `v3` 를 한 단어 넣거나
bts-backend 에 `ports:` 를 열면 즉시 노출된다. **어떤 Kotlin 테스트도 이것을 못 잡는다** —
MockMvc 는 nginx 를 모르고 Gradle 은 compose 파일을 읽지 않는다.

봉인 3축(모두 **위반 주입으로 비-공허 확증**, 기준선 EXIT=0 선확인).

| 축 | 불변식 | 주입 뮤테이션 | 결과 |
|---|---|---|---|
| C(대조군) | 알려진 프록시 경로 7개가 매치된다 | 정규식 location 제거 | EXIT=3 ✅ |
| A(라우팅) | springdoc 경로 5개가 백엔드로 안 간다 | 정규식에 `v3\|swagger-ui` 추가 | EXIT=1 ✅ |
| B(포트) | bts-backend 호스트 발행 포트 0개 | `ports: ["8080:8080"]` 추가 | EXIT=2 ✅ |

축 C 를 **가장 먼저** 판정한다 — 정규식 추출이 고장나 빈 목록이 되면 축 A 가 공허하게 통과하기
때문이다([[archunit-vacuous-rule-silent-pass]] 와 같은 실패 양식).
`mapfile`(bash 4+)은 쓰지 않았다 — macOS 기본 bash 3.2 에 없어 로컬에서 조용히 빈 목록이 된다.

**남은 것 (심층방어를 하려면).** 두 빈에 `@Profile("!prod")` 를 **함께** 붙인다. 2 BC +
security-engineer 소관. 현 토폴로지에서는 노출량 0 이므로 우선순위는 낮다.

## ✅ project-workflow — 404/403 순서 (해소 2026-07-27)

**해소.** 세 핸들러 **전수**에서 `requirePermission` 을 `findIdByKey` 앞으로 옮겼다.
한 개만 바꾸면 같은 컨트롤러 안에서 순서가 갈린다.

**★기록된 착수 함정이 이미 무효였다.** 원 기록은 "순서를 바꾸면 프론트 `fetchProjectAssignment` 의
404→null=미할당 로직이 깨진다" 고 적었으나, 백엔드는 **미배정에 404 를 내지 않는다**
(`WorkflowSchemeApplicationService` 가 자동 배정). 그 404 는 「프로젝트 없음」 하나뿐이다.
착수 전 재확인했고, 프론트 쪽 404 해석도 같은 라운드에서 함께 정리했다.

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-26 D4=2A)**. **이번 PR 범위 밖.** 한계 노출량이 0(같은 정보를 기존 2핸들러로
이미 얻을 수 있음)이고, 순서 변경은 3핸들러 동시 수정이라 보안 봉합 PR 의 리뷰 단위를 흐린다.

**무엇이 문제인가**. 세 핸들러 모두 `projectLookupPort.findIdByKey`(404) 를 `requirePermission`(403)
**보다 먼저** 호출한다(`ProjectWorkflowSchemeController.kt:86-93` 외 2곳). 인증됐지만 권한 없는 사용자가
응답 코드 차이(404 vs 403)로 프로젝트 키의 실재를 열거할 수 있다.

**착수 시 함정**. 순서를 바꾸면 `fetchProjectAssignment`(`workflow-schemes.ts:233-243`)의
**404 → null = "미할당"** 로직이 깨진다. 미할당 상태를 404 가 아닌 다른 신호로 표현하도록 계약을 먼저
정해야 한다. 세 핸들러를 한꺼번에 정렬할 것 — 한 개만 바꾸면 같은 컨트롤러 안에서 순서가 갈린다.

**Depends on / blocked by**. 없음. 우선순위 낮음(한계 노출량 0).

</details>

## ✅ identity-access — MANAGE_WORKFLOW 시드 (기각 · 도달 불가 · 2026-07-27)

**기각(REJECTED_AS_NOT_REAL).** 결함 서술 자체는 참이지만 **그 상태에 도달할 방법이 프로덕션에 없다.**

```
# 비-기본 권한 스킴을 만드는 프로덕션 쓰기 경로
grep -rniE "insert into (permission_schemes|project_permission_scheme|role_permissions)|update ..." \
  backend --include="*.kt" --include="*.sql" | grep "/src/main/" | grep -v "/db/migration/"
→ 0줄 (전 9모듈)
```
쓰기는 마이그레이션과 **테스트**뿐이다(`project_permission_scheme` INSERT 6건 전부 `/src/test/`).

⇒ **도달 불가 상태에 마이그레이션을 넣는 것은 dead 시드다.** 대신 두 가지를 남겼다.
1. `PermissionSchemeRepository` KDoc — 도달 불가 근거 + **전제가 깨지는 조건과 그때 할 일**
   (9종 시드를 새 스킴에도 적용하는 마이그레이션 동반).
2. **tripwire 테스트** — 두 번째 스킴이 생기는 순간 깨져서 다음 담당자를 KDoc 으로 보낸다.

**★부수 발견 — 이 결함은 `MANAGE_WORKFLOW` 고유가 아니다.** 같은 구조의 시드가 9종이다.
V013 만의 문제로 등재돼 있던 것이 좁은 시야였다.

**함께 등재분 (코드 미변경, 실측만).** `IdentityAccessWorkflowSchemePermissionResolver:77` 이
멤버십 null 이면 즉시 거부하며 SYSTEM_ADMIN 을 다시 묻지 않는다(fallback 부재 확인).
클래스 KDoc 은 이를 **의도된 deny-by-default 로 서술**하고 있어 문서상 누락이 아니다.
다만 "전역 관리자가 남의 프로젝트를 관리할 수 있어야 하는가" 는 제품 판단이라 **Maxi 확인 필요**.

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-26 D10=A)**. **이번 PR 범위 밖.** 신규 기능 손실이 아니다 — 배정 실행(PUT)이
이미 같은 게이트라 해당 사용자는 오늘도 적용 단계에서 403 을 맞는다. 마이그레이션 0 이라는 PR 전제를
깨면서까지 지금 할 일이 아니다.

**무엇이 안 잠겨 있나**. `V013__manage_workflow_permission.sql:14-16` 이 `MANAGE_WORKFLOW` 를
**기본 권한 스킴(`00000000-0000-0000-0000-000000000001`)의 `PROJECT_ADMIN`** 에만 1행 시드한다.
프로젝트가 어느 스킴을 쓰는지는 `COALESCE(project_permission_scheme, permission_schemes WHERE is_default)`
로 결정된다(`JdbcPermissionSchemeRepository.kt:65-72`).

⇒ **비-기본 권한 스킴에 명시 매핑된 프로젝트**의 관리자는 `roleHasPermission` 이 false 가 되어
워크플로우 스킴 배정이 불가능하다. ADR `2026-07-26-workflow-scheme-read-permission-gate` D4 의
"시드 변경 0" 은 **기본 스킴 프로젝트에 한해** 참이다.

**함께 등재 — 프로젝트 비멤버인 SYSTEM_ADMIN**. `WorkflowSchemeScope.Project` 판정에 시스템 관리자
fallback 이 없다(`IdentityAccessWorkflowSchemePermissionResolver.kt:52-56, 76-78`). 멤버십 조회가 null 이면
곧바로 거부다. 사내 전체 관리자가 자기가 멤버가 아닌 프로젝트의 워크플로우를 배정할 수 없다.
이것이 의도된 정책인지 누락인지는 **미확정** — 착수 시 먼저 정할 것.

**동반 필요**. 시드를 넓히면 `PermissionSchemaMigrationTest` 의 정확 카운트 단언을 함께 갱신해야 한다
(메모리 `fr-pm-permission-seed-migration-test-coupling`).

**Depends on / blocked by**. 권한 스킴을 실제로 둘 이상 운용하기 시작하는 시점. 그전까지는 잠복.

</details>

## ✅ project-workflow — SchemeHandlerPermissionMatrix 봉인 (해소 2026-07-27)

**해소.** 런타임 대조 테스트로 세 한계를 동시에 닫았다.
`CapturingPermissionResolverStub` 을 최상위로 추출하고, MockMvc 로 **10핸들러를 전수 호출**해
캡처된 `(permission, scope)` 를 `HANDLER_CLASSIFICATION` 과 대조한다.

**★이 작업의 본질은 「맵 값을 처음으로 읽게 만드는 것」이었다.** 그 전까지
`HandlerClassification(permission, scopeKind)` 의 두 필드는 **읽는 코드가 0곳인 죽은 데이터**였고,
축2 는 `containsKey` 로 등록 여부만 봤다. 실제로 핸들러를 태우므로 축1 의 "안 타는 분기" 도 해소된다.

**판별 범위를 패키지가 아니라 의존 관계로** 바꿨다 — `WorkflowSchemeApplicationService` 를 주입받는
`@RestController` 를 ArchUnit 으로 수집한다. 패키지 밖 컨트롤러가 생기면 자동 편입된다.

<details><summary>원 기록 (보존)</summary>

**부분 해소.** 감사가 제시한 **즉시 조치**를 적용했다 — `WorkflowSchemeControllerTest` 의
`PUT 수정`·`DELETE 삭제` 테스트에 `verify { permissionResolver.requirePermission(actor,
MANAGE_SCHEME, Global) }` 를 추가해 **무방비였던 2핸들러의 권한 인자를 고정**했다.
뮤테이션 확증 — update 핸들러의 `MANAGE_SCHEME` → `ASSIGN_SCHEME` 주입 시 FAILED
(봉인 축2 는 이 뮤테이션에 green 을 유지한다).

**★한계 (3)이 기록보다 나쁘다 — 맵 값이 죽은 데이터다.** 원 기록은 축2 가 `containsKey` 만 본다고
적었는데, 실측하면 `HandlerClassification(val permission, val scopeKind)` 의 **두 필드를 읽는 코드가
0곳**이다(`HANDLER_CLASSIFICATION` 등장 위치 전수 = 선언·`containsKey`·메시지뿐).
즉 맵은 등록부조차 아니고 **값의 절반이 아무 역할도 하지 않는다**.

**잔여 — 런타임 대조로 3한계 동시 해소.** `CapturingPermissionResolverStub`
(FQN `com.bts.workflow.scheme.web.ProjectWorkflowSchemeControllerTest.CapturingPermissionResolverStub`,
중첩 클래스라 최상위로 추출 필요)을 써서 MockMvc 로 **10핸들러 전수 호출** → 캡처된
`(permission, scope)` 를 `HANDLER_CLASSIFICATION` 과 대조한다. 이 작업의 본질은
**맵 값을 처음으로 읽게 만드는 것**이다 — 그러면 축2 의 죽은 데이터가 살아나고, 실제로 핸들러를
태우므로 축1 의 "안 타는 분기" 도 자동 해소된다.
판별 범위는 패키지가 아니라 **의존 관계**로 바꾼다 — `WorkflowSchemeApplicationService` 를 주입받는
`@RestController` 를 ArchUnit 으로 전 모듈 수집(현재 2클래스/10핸들러로 동일하나, 패키지 밖
컨트롤러가 생기면 자동 편입).

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-26 D12=B)**. **이번 PR 범위 밖.** N4 의 결함 클래스(가드 자체가 없음)는
축1(호출 강제)이 이미 완전히 막고 있고, 맵 값 drift 는 더 좁은 클래스다. 대신 테스트 KDoc 에 한계를
명시해 과신을 막고 여기 등재한다.

**무엇이 안 잠겨 있나**. 축2 는 `HANDLER_CLASSIFICATION.containsKey(HandlerKey(owner, name))` 로
**등록 여부만** 본다(`SchemeHandlerPermissionMatrixTest.kt:78-80`). 맵에 적힌 `(permission, scopeKind)` 가
코드가 실제로 넘기는 인자와 같은지는 대조하지 않는다.

**실증 (뮤테이션 M3, 2026-07-26)**. `listAssignableSchemes` 의 스코프를 `Project(projectKey)` → `Global`
로 바꿨을 때 **축2 는 green 을 유지**했다. 그 뮤테이션을 잡은 것은 `ProjectWorkflowSchemeControllerTest`
의 `capturedScope` 단언(개별 단위 테스트)이었다. ⇒ 맵은 **등록부**이지 계약이 아니다.

**비대칭 위험**. 신규 2핸들러(`listAssignableSchemes`)와 `create` 는 스코프 단언을 가진 단위 테스트가
있으나, 나머지 기존 핸들러 전부가 그런 단언을 갖는지는 미확인이다. 착수 시 **먼저 전수 확인**할 것.

**처방 후보**. MockMvc + 기존 `CapturingPermissionResolverStub` 으로 10 핸들러를 전수 호출해
캡처된 `(permission, scope)` 가 `HANDLER_CLASSIFICATION` 과 일치하는지 대조한다. 스텁이 이미 있으므로
신규 인프라는 불요. 비용은 10 핸들러의 요청 URL·바디 구성.
⚠️ ArchUnit 만으로는 못 한다 — 바이트코드에서 상수 인자 값을 읽어야 해서 `JavaMethodCall` 로는 부족하다.

**Depends on / blocked by**. 없음. 이 갭이 남는 동안에는 **축2 의 green 을 "권한/스코프가 맞다"로
읽지 말 것** — "핸들러가 등록은 돼 있다" 까지만 참이다.

**추가 한계 2종 (2026-07-26 코드리뷰가 직접 주입해 실증)**.

- **축1 도 "호출이 존재하는가" 만 본다.** 가드가 `if (schemes.isEmpty()) { requirePermission(…) }` 처럼
  **정상 응답 경로 밖**에 있어도 양 축을 통과한다. 즉 N4 와 실질이 같은 핸들러가 봉인을 빠져나간다.
  대조군(호출 아예 제거)에서는 FAILED — 이 축이 잡는 것은 정확히 "호출 0건" 이다.
- **판별 범위가 `com.bts.workflow.scheme.web` 패키지 한정이다.**
  `WorkflowSchemeApplicationService.list()`·`listWithCounts()` 자체는 권한 호출 0건이므로,
  다른 패키지 컨트롤러가 그 서비스를 소비하면 봉인이 그 클래스를 임포트조차 하지 않아 무음 통과한다.

⇒ **런타임 대조(MockMvc + capturing stub 전수 호출)로 강화하면 축1·축2 한계가 동시에 해소된다** —
실제로 핸들러를 태워 캡처값을 보므로 "안 타는 분기" 도 잡힌다. 범위는 스킴 서비스를 소비하는
핸들러 전체로 잡을 것(패키지가 아니라 **의존 관계**를 판별식으로).

</details>

## ✅ apps/web — SCHEME_KEYS.assignable 캐시 무효화 (해소 2026-07-27)

**해소.** queryKey 를 레포 지배 관례인 **리소스-우선**(`['assignable-workflow-schemes', projectKey]`,
`use-components`·`use-boards` 동형)으로 바꿔 prefix 하나로 잡고, 생성·수정·삭제 **3지점 전수**에
`invalidateQueries({ queryKey: SCHEME_KEYS.assignableAll })` 를 넣었다.

**★원 기록의 처방을 채택하지 않았다.** 원 기록은 `predicate` 기반 부분 매칭을 "맞는 방향" 이라 적고
"이 레포의 다른 훅 관례와 맞는지 먼저 확인할 것" 이라 단서를 달았다. 확인 결과
**`predicate:` 선례가 저장소 전체에 0건**이다(`invalidateQueries` 호출 359건 / 115파일이 전부 완전키 또는 prefix).
키 구조를 관례에 맞추면 predicate 없이 같은 목적을 달성하므로 그쪽을 택했다.

생산 지점 3곳을 전수 검증하는 테스트를 뒀다 — 하나만 걸면 나머지가 무방비다([[mutation-site-count-equals-verified-scope]]).

<details><summary>원 기록 (보존)</summary>

**결정 (Maxi 확정, 2026-07-26 게이트 2 = A)**. **범위 밖.** 영향이 작고, 성급한 처방이 더 위험하다.

**증상**. `useAssignableWorkflowSchemes` 는 `staleTime: 30_000`(`use-workflow-schemes.ts:78-81`)인데
스킴 생성·수정·삭제 훅(`:100`·`:171`·`:189`)은 `SCHEME_KEYS.list`/`detail` 만 invalidate 한다.
⇒ SYSTEM_ADMIN 겸 PROJECT_ADMIN 이 `/admin/workflow-schemes` 에서 스킴을 만든 뒤 **30초 안에**
배정 화면으로 이동하면 새 스킴이 드롭다운에 없다.

**★처방 함정 (착수 시 반드시 고려)**. 단순히 `invalidateQueries({ queryKey: ['projects'] })` 를 넣으면
**프로젝트 관련 전 쿼리를 무효화**해 관계없는 화면까지 재요청이 폭발한다. 관리자 뮤테이션은
`projectKey` 를 모르므로(전역 자원 조작) 정확한 키를 만들 수 없다.
⇒ predicate 기반 부분 매칭(`predicate: q => q.queryKey[0]==='projects' && q.queryKey[2]==='assignable-workflow-schemes'`)
이 맞는 방향이다. 그게 이 레포의 다른 훅 관례와 맞는지 먼저 확인할 것.

**Depends on / blocked by**. 없음. 우선순위 낮음(30초 staleness, 두 역할 겸임자 한정).

</details>

## ✅ issue-tracking — 핵심 엔티티 glossary 미등재 (해소 2026-07-27)

**해소.** `Maxi_wiki/BTS/glossary.md` 에 누락 2종을 등재하고 §변경 규칙에 **배치 판별식**을 명문화했다.

**★원 기록이 틀렸다 — 개수도 구성원도.** 원문은 "미등재 3종 = Worklog · Attachment · Watcher" 였으나
실측하면 **Attachment·Watcher 는 이미 등재돼 있었다**.

```
glossary.md:50  | 워처   | Watcher. 이슈 변경 알림 수신자 |
glossary.md:51  | 어테처 | Attachment. 이슈에 첨부된 파일 |
```

둘은 §핵심 엔티티가 아니라 **§관계/연결** 섹션(L41~51)에 있었다. 원 기록은 §핵심 엔티티만 훑고
그 섹션을 놓쳤다. 이 오류가 항목의 논거 전체를 무너뜨린다 — "domain 엔 있는데 glossary 엔 없다,
그래서 두 문서 기준이 다르다" 는 주장이 Attachment·Watcher 에 대해 **성립하지 않는다**.

**실제 차집합은 {Worklog, IssueHistory} 2종**이었다. `domain/issue-tracking.md` §핵심 엔티티 12종을
glossary 70개 term 과 대조한 결과다. **`IssueHistory` 는 원 기록이 아예 언급조차 하지 않은 누락**이고,
`댓글` 정의문의 `[[작업로그]]` 는 **대상 문서가 없는 깨진 백링크**였다.

⇒ 메모리 [[spec-stated-count-becomes-blindfold]] 의 정확한 재현. **기록된 개수를 물려받으면 안 된다.**

**산출물.**
1. glossary §핵심 엔티티에 `작업로그(Worklog)` · `이슈 변경 이력(IssueHistory)` 2행 추가 (깨진 백링크 해소).
2. §변경 규칙에 **배치 판별식** 표 추가 — 「자체 테이블 + 자체 생명주기 → 핵심 엔티티 / 이슈에 딸린
   연결·참조 → 관계·연결」. 원 기록이 추측한 "설명 필요도" 기준은 실제 배치와 맞지 않아 채택하지 않았다.
3. 판별식을 **기등재 70개에 역적용해 검증**하라는 후속을 규칙 안에 남겼다 (메모리
   [[rule-reverse-validated-on-completed-batch]] — 하드코딩 목록이 아니라 판별식이어야 재발하지 않는다).

## ✅ 워크플로우 — `bts-review-plan` 분기 표에 `type=backend` 가 없다 (해소 2026-07-27)

**해소.** 표에 `backend` 행 + **fallback 행**(「그 외(표에 없는 타입)」)을 추가하고,
`scripts/workflow/skill-type-coverage.test.ts` 로 **차집합 0 을 강제**했다.
`backend` 매핑은 `bts-workflow/SKILL.md` 가 이미 갖고 있던 것이라 새 결정이 아니라 **미러 누락**이었다.

**뮤테이션 확증** — `backend` 행을 지우자 새 테스트 FAILED. fallback 행 존재도 별도 단언.
개별 타입 추가로 끝내지 않은 이유는 원 기록이 지적한 대로다 — 판별식이 없으면 재발한다.

<details><summary>원 기록 (보존)</summary>

**증상**. `.claude/skills/bts-review-plan/SKILL.md` Step 2 의 타입별 리뷰 체인 표는
`auth`·`migration`·`ui`·`api`·`feature`·`design` + `{bugfix,chore,qa}` skip **7종만** 다룬다.
그런데 `scripts/workflow/classify-task.ts` 는 `backend` 를 내보낸다(PR #315 실측). **매칭되는 행이
없어 분기가 미정의**다.

**임시 대응 (PR #315)**. 메모리 `bts-review-plan-autoplan-overkill`(Maxi 피드백)에 따라
**eng 집중 + UI 포함이면 design 추가**로 수동 선택했다. CEO·DevEx 는 제외.

**★같은 형태의 누락이 더 있을 수 있다.** classify 가 낼 수 있는 타입 집합과 스킬 분기 표의 행 집합을
**대조**해야 한다 — 표에 없는 타입이 조용히 미정의로 떨어지는 구조다. 하드코딩 목록끼리 어긋나는
전형적 형태(메모리 `guard-handler-matrix-blindfold` 와 동질).

**착수 시 첫 단계**. `classify-task.ts` 의 type 유니온을 열거하고 `bts-review-plan`·`bts-spec`·`bts-plan`·
`bts-impl` 각 스킬의 분기 표와 **집합 차이**를 낸다. 차집합이 0 이 되게 채우고, 앞으로 어긋나면 깨지는
검증을 하나 둔다(스킬 문서 대조 스크립트 또는 classify 출력 화이트리스트).

**산출물**. 분기 표 보강 + 타입 집합 정합 검증. 개별 타입 추가만으로 끝내지 말 것 — 판별식이 없으면 재발한다.

</details>

## ✅ apps/web mocks — fixtures id 정합 (해소 2026-07-27)

**해소.** `auth-fixtures` 를 정본으로 두고 id 상수를 export, `user-fixtures` 가 그것을 참조하게 했다.
**형제 mock 5파일**(issue·board·changelog·audit-log·timeline)의 하드코딩 리터럴도 함께 스윕했다 —
mock 데이터라 안 고치면 담당자/작성자 해석이 깨진 채 남는다.

**회귀 봉인** — `useUsersByIds` 를 `vi.mock` 하지 **않는** 통합 테스트를 남겼다.
원 결함이 브라우저 눈확인에서만 드러난 이유가 바로 전 테스트가 그 훅을 통째로 mock 했기 때문이다.
판별식 — **"토큰이 만든 authorId 를 사용자 디렉터리가 되찾아 이름으로 바꿔줄 수 있는가."**

<details><summary>원 기록 (보존)</summary>

**증상**. 모든 "작성자 이름" 표시가 mock/E2E/로컬 dev 에서 **원시 UUID 로 나온다.** 헤더는 `김앨리스`
를 정상 표시하고 담당자 드롭다운도 정상인데, 목록 항목의 작성자만 UUID 다.

**근본 원인 (2026-07-27 실측)**. 같은 `username: 'alice'` 가 두 픽스처 파일에서 **다른 id** 를 갖는다.

| 파일 | 필드 | 값 |
|---|---|---|
| `src/mocks/auth-fixtures.ts` | `aliceUser.userId` | `00000000-0000-4000-8000-000000000001` |
| `src/mocks/user-fixtures.ts` | `userListFixture[0].id` | `c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f` |

`grep -c "00000000-0000-4000-8000" src/mocks/user-fixtures.ts` → **0**. 교집합이 공집합이다.

**왜 드롭다운은 되고 목록은 안 되나.** `GET /api/v1/users` 의 두 모드가 다른 픽스처를 탄다 —
`?query=` 모드는 `userListFixture` 를 그대로 반환하므로 `김앨리스` 가 보이고, `?ids=` 모드는
`userListFixture.filter(u => ids.includes(u.id))` 이므로 **auth UUID 로는 아무것도 안 걸린다.**
그러면 `useUsersByIds` 가 빈 배열을 받고 컴포넌트가 UUID 폴백을 탄다.

**범위 — 이 PR 만의 문제가 아니다.** `useUsersByIds` 를 쓰는 모든 화면이 같다.
`WorklogSection`(작성자) · `CommentSection`(작성자) · 그 외 authorId → displayName 을 해석하는 곳 전부.
`worklog-handlers`·`comment-handlers` 가 Bearer 토큰(=auth-fixtures UUID)에서 저작자를 도출하기 때문이다.

**프로덕션은 정상이다.** 실 `/api/v1/users?ids=` 는 실제 사용자를 돌려주므로 이름이 해석된다.
**mock 전용 결함**이며, UUID 폴백 자체는 탈퇴·삭제 사용자를 위한 **의도된 동작**이다(고장 아님).

**★왜 아무도 몰랐나.** 컴포넌트 테스트가 `useUsersByIds` 를 `vi.mock` 으로 대체해 실제 조회 경로를
타지 않는다. E2E 도 작성자 이름을 단정하지 않았다. **브라우저 눈확인에서만 드러났다** —
FR-UX-06 이 22 PR 을 끝내고도 미실시로 남긴 그 절차다.

**PR #315 에서 고치지 않은 이유**. `user-fixtures.ts` 는 공유 mock 데이터이고 프론트 8,044 테스트가
의존한다(항목 수·드롭다운 내용을 단정하는 테스트가 있을 수 있다). mock 전용 표시 문제를 위해
기능 PR 에서 공유 픽스처를 바꾸는 것은 폭발 반경이 맞지 않는다.

**착수 시 첫 단계**. 어느 쪽을 정본으로 삼을지 먼저 정한다 — `auth-fixtures` UUID 를 정본으로 두고
`user-fixtures` 를 맞추는 편이 자연스럽다(토큰에서 도출되는 값이 곧 실사용 id 이므로). 바꾼 뒤
**`useUsersByIds` 를 mock 하지 않는 통합 테스트 1건**을 남겨 같은 회귀가 다시 숨지 못하게 한다.

</details>

## ✅ issue-tracking — 이슈 링크/부모 권한 가드 (해소 2026-07-27)

**해소.** `LinkApplicationService` · `IssueParentService` 두 서비스에 `IssuePermissionResolver` 를
주입하고 4 핸들러 전수에 게이트를 걸었다. 형제 `WorklogService.checkPermission` 과 **같은 형태**다.

- `createLink` — source·target **양끝** `UPDATE`. 한쪽만 보면 볼 수 없는 이슈를 target 으로 지목해
  404/409 차이로 실재를 확인할 수 있다. 링크는 양방향 관계라 의미상으로도 양쪽이 맞다.
- `setParent` 양끝 · `clearParent`/`deleteLink` `UPDATE` · `listLinks` `VIEW`
- **권한을 리소스 조회보다 먼저** 건다([[auth-extraction-before-resource-lookup]]).

**파생 결함 2건도 함께 닫았다.**
1. `LinkExceptionHandler` 에 `IssueAccessDeniedException` → 403 매핑이 없어 **500 으로 변질**됐다
   (형제 Watcher·Comment·CycleTime 은 전부 갖고 있다). detail 에 actor/permission/scope 미노출.
2. `ResponseStatusException` 전파 핸들러가 없어 catch-all 이 **401 을 500 으로** 삼켰다
   ([[catch-all-exceptionhandler-swallows-responsestatusexception]] 재현).
   ★핸들러 KDoc 이 *"컨트롤러가 actor 를 추출하지 않아 401 경로가 구조적으로 없다"* 는 전제를
   적어두었는데 **이 작업이 바로 그 전제를 뒤집었다.** 낡은 전제도 함께 정정했다.

**뮤테이션 확증** — target 게이트 제거 시 SEC2 FAILED.
테스트 7건 — 양끝 거부 4 · 조회순서 1(존재하지 않는 키로도 403) · VIEW 1 · **대조군 1**.
대조군이 없으면 권한을 과하게 걸어도 6건이 통과해 초록으로 보인다.

<details><summary>원 기록 (보존)</summary>

**등급.** 보안. 다른 부채와 달리 **현행 결함**이며 잠복 부채가 아니다.

**증상.** `LinkApplicationService` 에 `IssuePermission` 검사가 **하나도 없다.**

```
$ grep -c "IssuePermission\|permissionResolver\|hasPermission\|checkPermission\|PreAuthorize" \
    backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/application/LinkApplicationService.kt
0

# 생성자 (L87-91) — permissionResolver 자체가 주입되지 않는다
class LinkApplicationService(
    private val issueRepository: IssueRepository,
    private val linkRepository: IssueLinkRepository,
    private val archiveGuard: ProjectArchiveGuard,
)
```

**대조군.** 형제 서비스는 전부 건다 — `WorklogService` 는 `checkPermission(actor, issueKey,
IssuePermission.UPDATE)` 를 4지점(:111·:183·:241·:315)에서 호출한다.

**무엇이 막고 있나 — 인증뿐이다.** `SecurityConfig:222` 의 `auth.requestMatchers("/api/**").authenticated()`
가 유일한 게이트다. ⇒ **인증된 사용자면 누구나** 자기가 멤버가 아닌 프로젝트의, 심지어 **볼 수 없는
기밀 이슈**에도 링크를 걸고 지우고 부모를 바꿀 수 있다. 영향 엔드포인트 4개 —
`POST /links` · `GET /links` · `DELETE /links/{linkId}` · `PATCH /parent`
(`IssueLinkController.kt:92·129·152·180`).

**★가드 계약이 문서로 존재하는데 이 서비스만 안 지킨다.** `ProjectArchiveGuard` KDoc `:31` 이
*"항상 `assertPermission` → `check`/`checkByIssue` 순서로 호출한다(D-ORDER)"* 라고 명시한다.
`LinkApplicationService` 는 `archiveGuard.checkByIssue`(:127·:128·:234) 만 부르고
`assertPermission` 을 **부르지 않는다** — 계약의 앞 절반을 건너뛴 것이다.

**★왜 아무도 몰랐나.** `IssueLinkController` KDoc `:53-54` 가 이렇게 적어두었다.

> ### actorId
> issue_links / parent_id 는 created_by 를 저장하지 않으므로 actor 추출이 불필요하다.

**"누가 만들었는지 기록하지 않는다" 와 "누가 만들어도 되는지 검사하지 않아도 된다" 를 혼동**한
문장이다. 감사 흔적의 부재를 권한 검사 면제의 근거로 쓴 셈이고, 그 문장이 리뷰에서
"의도된 설계" 로 읽히게 만들었다.

**★2026-07-27 추가 실측 — 무가드 서비스가 하나가 아니라 둘이다.**
`IssueParentService`(같은 `link/application` 패키지, 생성자 = `issueRepository, archiveGuard`)도
권한 검사 **0건**이다. 컨트롤러 `setParent`/`clearParent`(`PATCH /parent`)가 그 서비스를 쓴다.
⇒ 봉합 대상은 **서비스 2개 · 핸들러 4개**다.

**폭발 반경 실측.** `IssueLinkControllerIntegrationTest` **19 @Test** ·
`LinkApplicationServiceTest` **0 @Test**(파일은 있으나 테스트가 없다 — 그 자체가 신호다).

**착수 시 첫 단계.** `LinkApplicationService` 와 `IssueParentService` 에 `IssuePermissionResolver` 를 주입하고
`createLink`·`deleteLink`·`setParent` 에 `checkPermission(actor, IssueScope.Issue(key),
IssuePermission.UPDATE)` 를, `listLinks` 에 `VIEW` 를 건다. 컨트롤러가 현재 actor 를 **받지 않으므로**
(`deleteLink(key, linkId)` 시그니처) `CurrentActor.current()` 결선이 함께 필요하다.
**인증 추출은 리소스 조회보다 먼저** 둘 것([[auth-extraction-before-resource-lookup]]) — 안 그러면
미인증자가 404/200 차이로 이슈 실재를 열거한다.

⚠️ 링크는 **양끝 이슈**가 있다. source 만 검사하면 볼 수 없는 이슈를 target 으로 지목해
존재를 확인할 수 있다. `createLink` 는 sourceKey·targetKey **둘 다** 게이트를 통과시킬 것.

**회귀 폭.** 권한을 **줄이는** 방향이라 기존 테스트가 깨진다. 착수 전
`IssueLinkController`·`LinkApplicationService` 테스트에서 actor 시드가 없는 케이스를 전수 열거할 것.

**소관**. security-engineer 공동 검토 필요 (FR-LK).

</details>

## ✅ issue-tracking — 자식 엔티티 소유권 정책 정본화 (해소 2026-07-27 · 결함 2건은 분리)

**해소 근거는 이 항목이 스스로 정한 종료 조건이다** — *"먼저 필요가 실재하는지 확인한다. 없으면
비대칭을 유지하고 대신 **사유를 제품 문서에 명시**하는 것으로 끝낸다."*

운영에서 "남의 워크로그를 지워야 했다" 사례는 제기된 바 없다. 따라서 **비대칭을 유지**하고
`docs/plan/product/issue-tracking.md §A` 에 **5종 정책 행렬을 근거 위치와 함께 정본화**했다.
새 자식 엔티티를 추가할 때 그 표의 판별식 중 하나를 고르게 해 여섯 번째 정책이 생기는 것을 막는다.

**Comment↔Worklog 비대칭이 정당한 근거는 재확인됐다** — automation `ActionType` 전 5종
(`SET_FIELD·ASSIGN·ADD_COMMENT·CALL_WEBHOOK·SET_FIX_VERSIONS`)에 worklog 액션이 **0건**이다
(`rg -in "worklog" backend/modules/automation/src/main` → 0). 자동화가 만든 댓글은 저작자가
룰 소유자로 고정돼 작성자 한정이면 방치되지만, worklog 엔 그 생산자가 없어 질문 자체가 없었다.

**⚠️ 운영 필요가 나중에 확인되면** worklog 삭제에 댓글과 **같은 술어**(작성자 ∨ `SOFT_DELETE`)를
적용한다. 새 술어를 발명하지 말 것.

**★표의 5종 중 2종은 정책 선택이 아니라 결함이라 분리 등재했다** — 아래 🚨 Link 항목과
🚨 Attachment 항목. 정책 통일 논의와 섞으면 결함이 "정책 차이" 로 읽혀 안 닫힌다.

<details><summary>원 기록 (보존)</summary>

**★원 기록은 "댓글 vs Worklog 2종 비대칭" 이었으나, 자식 엔티티를 전수 열거하면 5종에 정책이 5개다.**

| # | 엔티티 | 수정 | 삭제 | 근거 위치 |
|---|---|---|---|---|
| 1 | Comment | `UPDATE` ∧ 작성자 | `UPDATE` ∧ (작성자 ∨ `SOFT_DELETE`) | `CommentApplicationService:194·203` / `:279·290-293` |
| 2 | Worklog | `UPDATE` ∧ 작성자 | `UPDATE` ∧ 작성자 | `WorklogService:254`·`:328` |
| 3 | Attachment | — | **`UPDATE` 만 (업로더 검사 0건)** | `IssueAttachmentService:227` |
| 4 | Watcher | — | self→`VIEW` / 타인→`UPDATE` | `IssueWatcherService:206` |
| 5 | Link | **권한 검사 0건** | **권한 검사 0건** | 위 🚨 항목 참조 |

3번은 **`EDIT_ISSUE` 보유자가 남의 첨부를 지울 수 있다**는 뜻이고, 5번은 별도 보안 항목으로 올렸다.

**Comment↔Worklog 비대칭은 정당하다 (근거 재확인).** 생산자 구성이 다르다 —
`ActionType` 전 5종은 `SET_FIELD · ASSIGN · ADD_COMMENT · CALL_WEBHOOK · SET_FIX_VERSIONS` 이고
(`ActionType.kt:17-23`), `rg -in "worklog" backend/modules/automation/src/main` → **0건**.
즉 자동화가 만드는 댓글은 저작자가 룰 소유자로 고정돼 작성자 한정이면 아무도 못 지우지만,
worklog 에는 그 생산자가 없어 질문 자체가 없었다. **Worklog 선례가 조용했던 이유는 답이 같아서가
아니라 질문이 없어서다.**

**착수 시 첫 단계 (원 기록 유지).** 운영에서 "남의 워크로그를 지워야 했다" 사례가 실재하는지 먼저 확인한다.
없으면 비대칭을 유지하고 **사유를 제품 문서에 명시**하는 것으로 끝낸다. 있으면 댓글과 **같은 판별식**을
쓴다 — 술어를 새로 발명하면 여섯 번째 정책이 생긴다.

**★단, 3·5번은 "정책 선택" 이 아니라 결함이다.** 위 표에서 1·2·4 는 의도된 차이로 설명되지만
3(업로더 무검사)·5(권한 무검사)는 어느 정책에도 해당하지 않는다. 정책 통일 논의와 **분리해서**
먼저 닫아야 한다.

**소관**. FR-WL · FR-AC · FR-LK. `docs/plan/product/issue-tracking.md`.

</details>

<details><summary>원 기록 (보존)</summary>

**무엇이 어긋나 있나.** 같은 이슈 화면의 두 자식 엔티티가 삭제 정책이 다르다.

| 엔티티 | 수정 | 삭제 | 근거 |
|---|---|---|---|
| Comment (FR-CO-02) | 작성자 한정 | 작성자 **OR** `SOFT_DELETE` 보유자 | ADR `2026-07-27-fr-co-02-comment-moderation` |
| Worklog (FR-WL) | 작성자 한정 | **작성자 한정** (`WorklogService.kt:254`·`:328`, 관리자 우회 분기 0건) | 선례 그대로 |

**왜 정당한 비대칭인가.** 생산자 구성이 다르다. 댓글에는 automation `AddCommentAction` 이 있어
자동화가 만든 댓글의 저작자가 **룰 소유자**로 고정된다 — 작성자 한정으로 두면 그 룰 소유자 말고는
아무도 못 지운다. worklog 에는 automation 액션이 **0건**이라 이 문제 자체가 없다. 즉 Worklog 선례가
조용했던 이유는 답이 같아서가 아니라 질문이 없어서다.

**그런데 사용자에게는 그렇게 안 보인다.** 관리자가 부적절한 댓글은 지울 수 있는데 부적절한 작업
기록은 못 지운다. 설명 없이 마주치면 일관성 없는 제품으로 읽힌다.

**착수 시 첫 단계**. 먼저 **필요가 실재하는지** 확인한다 — 운영에서 "남의 워크로그를 지워야 했다"
사례가 있었나. 없으면 비대칭을 유지하고 대신 **사유를 제품 문서에 명시**하는 것으로 끝낸다.
있으면 worklog 삭제에 같은 `SOFT_DELETE` 술어를 적용하되, 댓글과 **같은 판별식**(작성자 OR
모더레이터, 수정은 작성자 한정)을 쓴다. 술어를 새로 발명하면 세 번째 정책이 생긴다.

**소관**. FR-WL. `docs/plan/product/issue-tracking.md` 워크로그 절.

</details>

## ✅ issue-tracking — 첨부 삭제 소유권 게이트 (해소 2026-07-27)

**해소.** `IssueAttachmentService.delete` 에 **업로더 ∨ `SOFT_DELETE`** 게이트를 넣었다 —
댓글 삭제와 **같은 술어**다(§A 행렬에 없는 술어를 새로 만들지 않는다).

질의형 `hasPermission` 을 쓴다 — throwing 검사를 쓰면 `SOFT_DELETE` 미보유 업로더가 조용히 403 이 된다
(댓글과 같은 이유). 게이트는 `storagePort.remove` **앞**에 둔다 — 뒤면 비가역 삭제가 이미 일어난 뒤다.

**뮤테이션 확증** — 게이트 조건을 무력화하자 「업로더가 아니고 SOFT_DELETE 도 없으면」 테스트 FAILED.
**대조군 2건** — 업로더 본인은 `SOFT_DELETE` 없이도 삭제 가능 / 모더레이터는 남의 것도 삭제 가능.
대조군이 없으면 "전부 거부" 로 무너져도 초록으로 보인다.

**부수** — 기존 delete 테스트 3건이 strict mock 에서 `SOFT_DELETE` 미스텁으로 red 가 됐다.
그 자체가 「새 권한 질의가 실제로 발생한다」 는 증거라, 스텁을 추가하며 사유를 주석으로 남겼다.

**후속 (분리)** — 첨부 삭제는 **하드 삭제**(`deleteById` + MinIO `remove`)라 모더레이터 오삭제가
비가역이다. 소프트 전환 또는 삭제 감사 로그가 있어야 사후 추적이 된다.

<details><summary>원 기록 (보존)</summary>

**등급.** 보안. 위 🚨 Link 항목과 함께 **정책 차이가 아니라 결함**이다.

**증상.** `IssueAttachmentService` 의 삭제 경로가 이슈 `UPDATE` **하나만** 보고 업로더를 확인하지 않는다.

```
IssueAttachmentService.kt:227   checkPermission(actor, IssuePermission.UPDATE, issueKey)
                                ← 그 뒤로 소유자(업로더) 비교 없음
```

⇒ **`EDIT_ISSUE` 를 가진 사람이면 누구나 남이 올린 첨부를 지운다.**

**형제와의 대조.** 같은 이슈의 자식 엔티티 중 소유자 검사가 없는 것은 이것과 Link 뿐이다.
- Comment 삭제 = `UPDATE` ∧ (작성자 ∨ `SOFT_DELETE`) — 모더레이션 우회를 **명시적으로 설계**했다
- Worklog 삭제 = `UPDATE` ∧ 작성자
- Attachment 삭제 = `UPDATE` **만** ← 어느 쪽 판별식에도 해당하지 않는다

**착수 시 첫 단계 — 정책을 먼저 고른다.** 새 술어를 발명하지 말고 위 §A 행렬의 둘 중 하나를 택한다.
- **댓글형**(작성자 ∨ `SOFT_DELETE`) — 부적절한 첨부를 모더레이터가 지울 수 있어야 한다면 이쪽
- **워크로그형**(작성자 한정) — 첨부는 증거물 성격이 강하니 업로더만 지우게 하려면 이쪽

**★2026-07-27 판정 — 댓글형(업로더 ∨ `SOFT_DELETE`)을 택한다.**

처음엔 "automation 생산자가 없으니 Worklog 형(업로더 한정)" 으로 기울었으나, 그 논거를 다시 보면
**automation 은 댓글이 왜 모더레이션을 *필요로 했는지* 를 설명하는 논거이지, 다른 엔티티가
모더레이션을 *필요로 하지 않는다* 는 논거가 아니다.** 두 진술을 혼동한 것이다.

첨부에 고유한 사정 두 가지가 판정을 뒤집는다.
1. **첨부는 바이너리다.** 악성코드·불법물이 올라왔는데 업로더만 지울 수 있으면 대응 경로가 없다.
   텍스트 댓글보다 모더레이션 필요가 **더 강하다**.
2. ⚠️ **삭제가 하드 삭제다** (`attachmentRepository.deleteById` + MinIO `storagePort.remove`).
   댓글의 소프트 삭제와 달리 되돌릴 수 없다 — 모더레이터 오삭제의 대가가 크다.

⇒ (1)이 (2)보다 무겁다고 본다. 지울 수 없는 악성 첨부가 남는 쪽이 더 나쁘고, `SOFT_DELETE` 는
이미 모더레이션 등급 권한이라 광범위하게 부여되지 않는다. **댓글과 같은 술어를 쓴다** —
행렬에 여섯 번째 정책을 만들지 않는다는 원칙에도 맞는다.

**착수 시 함께 등재할 후속** — 첨부 하드 삭제의 비가역성. 소프트 삭제 전환 또는 삭제 감사 로그가
있어야 모더레이터 오삭제를 사후 추적할 수 있다. 이번 범위 밖.

**회귀 폭.** 권한을 **줄이는** 방향이라 기존 테스트가 깨진다. 착수 전
첨부 삭제 테스트에서 actor≠업로더인 케이스를 전수 열거할 것.

**소관**. FR-AC. security-engineer 공동 검토.

</details>

## ✅ issue-tracking / identity-access — 보안등급 게이트 확대 (해소 2026-07-27)

**해소.** 등급 게이트를 `VIEW` 단독에서 **이슈 내용 접근 계열 전체**로 넓혔다 —
`SECURITY_GATED_PERMISSIONS = {VIEW, UPDATE, TRANSITION, SOFT_DELETE, HARD_DELETE}`.
`IssuePermission` 8종을 전수 판정한 결과이며, 판정표를 상수 KDoc 에 박았다.

**제외 2종.**
- `BROWSE`·`CREATE` — 프로젝트 스코프라 특정 이슈의 등급과 무관하다.
- **`SET_SECURITY` — 의도적 제외.** 등급 변경 권한까지 게이트에 넣으면 **잘못 설정된 등급을
  아무도 되돌릴 수 없다**(등급 멤버가 아니라 못 보고, 못 보니 못 고친다). 우회로 없는 락아웃이다.
  **이 결정을 고정하는 테스트를 뒀다** — 없으면 다음 사람이 "빠뜨렸네" 하고 넣어 락아웃을 만든다.
  그 테스트는 `securityLookup` 을 스텁하지 **않는 것**으로 "게이트를 안 탄다" 를 증명한다(strict mock).

**★게이트를 매트릭스 판정 뒤에 뒀다.** `TRANSITION`·`HARD_DELETE` 는 `toCodeOrNull` 매핑이 없어
`code == null` 조기 반환으로 매트릭스를 건너뛴다. 게이트를 그 앞에 두면 **그 둘이 게이트도 빠져나간다.**

**뮤테이션 확증** — `UPDATE`·`TRANSITION` 을 집합에서 빼자(확대 이전 상태) 대응 테스트 2건 FAILED.
**대조군** — 등급 미지정 이슈는 `UPDATE` 통과("전부 거부" 로 무너지지 않았음을 확인).

**회귀 1건 — 확대가 드러낸 의존.** `MyIssuePermissionIntegrationTest` 가 깨졌다.
`UPDATE` 판정이 이제 `IssueSecurityLookup` 을 타면서 cross-BC `issues` 테이블을 읽는데,
그 테스트 DB 에는 `projects` 스텁만 있었다. 기존 `ensureProjectsTableExists` 와 **같은 방식**으로
`issues` 최소 스텁을 추가했다(등급 NULL = 공개로 시드). 결함이 아니라 **의존이 늘어난 결과**다.

**비용 인식** — `UPDATE`/`TRANSITION`/`SOFT_DELETE` 판정마다 `issues` 조회 1회가 추가된다.
`VIEW` 는 원래 그 비용을 내고 있었다.

<details><summary>원 기록 (보존)</summary>

**증상**. 이슈 보안 등급(security level, FR-PM-06)은 **`VIEW` 에만** 적용된다. `UPDATE`·`SOFT_DELETE`
는 등급 게이트를 통과하지 않는다.

```
IdentityAccessIssuePermissionResolver.kt:86
    if (permission == IssuePermission.VIEW && scope is IssueScope.Issue) {
        return passesSecurityGate(actorId, scope.key, membership.role.name)
    }
    return true          ← UPDATE·SOFT_DELETE 는 여기로 빠진다
```

**결과.** 기밀 이슈를 **볼 수 없는** 프로젝트 멤버가, 매트릭스에 `EDIT_ISSUE` 만 있으면 그 이슈에
대해 `UPDATE` 를 요구하는 경로를 통과한다. 댓글 수정·삭제는 물론 이슈 자체의 `PATCH` 도 해당한다.

**선재 성질이다.** 이 PR 이 만든 결함이 아니고, FR-CO-01 스펙이 *"`IssueScope.Issue` 고정 = 보안등급
우회 차단"* 이라고 적어둔 것이 **사실과 달랐다**(PR #316 독립 리뷰 F5 가 실측으로 반증). 다만 이 PR 이
`UPDATE` 를 요구하는 **쓰기 표면을 2개 늘렸으므로**(댓글 `PATCH`·`DELETE`) 노출 면적은 커졌다.

**★2026-07-27 설계 결정 (실측 후 확정).** `IssuePermission` 전 8종을 열거해 판정했다 —
`BROWSE · VIEW · CREATE · UPDATE · TRANSITION · SOFT_DELETE · SET_SECURITY · HARD_DELETE`.

| 권한 | 게이트 적용 | 근거 |
|---|---|---|
| `VIEW` | ✅ (현행) | 원 설계 |
| `UPDATE` · `TRANSITION` · `SOFT_DELETE` · `HARD_DELETE` | ✅ **확대 대상** | **볼 수 없는 것을 바꿀 수는 더더욱 없어야 한다.** 내용 접근을 전제하는 조작들 |
| `BROWSE` · `CREATE` | ❌ | `IssueScope.Issue` 가 아니라 프로젝트 스코프 — 특정 이슈의 등급과 무관 |
| `SET_SECURITY` | ❌ **의도적 제외** | 아래 참조 |

**`SET_SECURITY` 를 제외하는 이유 — 복구 불가 상태 방지.** 등급 변경 권한까지 게이트에 넣으면
**잘못 설정된 등급을 아무도 되돌릴 수 없다**(등급 멤버가 아니라서 못 보고, 못 보니 못 고친다).
우회로가 없는 락아웃이라 운영상 치명적이다. 반대급부로 `SET_SECURITY` 보유자에게는
「등급을 자기가 볼 수 있는 것으로 바꾼 뒤 열람」 이라는 경로가 남지만, 그 권한은 이미 높은 권한이고
현행도 동일하다. **제외는 누락이 아니라 결정이므로, 그 사실을 고정하는 테스트를 함께 둔다** —
안 그러면 다음 사람이 "빠뜨렸네" 하고 넣어 락아웃을 만든다.

**착수 시 첫 단계**. 등급 게이트를 어느 권한까지 확대할지 **먼저 결정**한다 — 전 권한인지, 쓰기 계열
(`UPDATE`·`SOFT_DELETE`·`TRANSITION`)인지. 그 다음 `IdentityAccessIssuePermissionResolver:86` 의
조건을 넓히고, **넓히기 전에 실패하는 테스트를 먼저** 둔다(현재 이 성질을 고정한 테스트가 0건이라
지금 상태로는 넓혀도 좁혀도 아무 테스트가 반응하지 않는다). 확대는 기존 사용자의 권한을 **줄이는**
방향이라 회귀 폭이 크다 — 프로젝트 멤버십 시드가 걸린 테스트를 전 모듈 grep 할 것.

**소관**. FR-PM-06.

</details>

## ✅ issue-tracking — offset 페이징 응답 크기 상한 (해소 2026-07-27)

**해소.** `requirePageSizeWithinLimit(pageable)` 단일 헬퍼를 **offset 분기 2곳 전수**에 주입했다.
cursor 모드와 같은 상한(100)·같은 400 을 쓴다.

**★실측이 범위를 넓혔다.** 원 기록은 changelog 단독 문제로 적었으나 `@PageableDefault` 전수 grep 결과
`IssueController` 에 offset 지점이 **2개**였다 — `list()` 와 `changelog()`. 한 곳만 막으면 절반만 닫힌다.

**★문서가 구현보다 앞서 있었다.** `MAX_CURSOR_LIMIT` 의 KDoc 이 이미 *"cursor 모드 + offset 모드
공통 최대 페이지 크기"* 라 적고 있었는데 offset 은 강제하지 않았다. 이번 변경으로 그 문장이 참이 됐다.

**무음 절단 대신 400 을 택한 이유** — 무음 절단은 **API 를 직접 호출하는 소비자**에게 "덜 받았다" 를
알리지 않아, 페이지네이션을 직접 도는 스크립트가 데이터를 조용히 누락한다.

**뮤테이션 확증** — 두 주입 지점을 모두 제거하자 대응 테스트 2건이 각각 FAILED.
경계값(size=100 → 200) 테스트도 함께 뒀다 — "전부 400" 으로 무너진 상태를 초록으로 오인하지 않기 위해서다.

**TODOS 옵션 (c)(이력 본문 서버 절단)는 별건으로 남긴다** — 상한과 직교이고 마스킹 계약과 얽힌다.

<details><summary>원 기록 (보존)</summary>

**증상**. `GET /api/v1/issues/{key}/changelog` 의 offset 모드는 `@PageableDefault(size = 20)`
(`IssueController.kt:514`) **기본값만** 있고 애플리케이션 정책 상한이 없다. `?size=` 로 올릴 수 있고,
남는 것은 Spring Data Web 프레임워크 기본값(`spring.data.web.pageable.max-page-size`, 기본 2000)뿐이다
— 이 키는 설정 파일에 **없다**(전 backend grep 0건). cursor 모드는 `limit` 초과를 400 으로 막는데
offset 모드에는 같은 가드가 없다.

**이 FR 이 증폭 계수를 키운다.** 댓글 수정 이력 1건은 `from_value`(이전 본문) + `to_value`(새 본문)
= 최대 32,000자 × 2 를 싣는다. 기존 필드 변경(`priority`, `status` 등)은 값이 짧아 문제가 드러나지
않았다. 프레임워크 상한까지 긁으면 한 응답이 2,000행 × 64,000자 = 최대 1.28억 자(UTF-8 한글이면
수백 MB) 가 된다.

**착수 시 첫 단계**. 상한을 **어느 계층에 둘지** 정한다 — (a) `spring.data.web.pageable.max-page-size`
를 명시 설정, (b) 컨트롤러에서 `size` 검증 후 400, (c) 이력 응답에서 `comment:` 항목 본문을 서버가
절단. (c) 는 프론트가 이미 화면 절단을 하므로 중복이나, **API 를 직접 호출하는 소비자**까지 막는
유일한 지점이다. 셋은 배타적이지 않다. 결정 후 **큰 `size` 로 요청하는 통합 테스트 1건**을 남긴다.

**소관**. 페이지네이션 정책. 이 PR 이 만든 성질이 아니라 드러낸 성질이다.

</details>

## ✅ issue-tracking / notification — 댓글 삭제 이벤트 + 모더레이션 통지 (해소 2026-07-27)

**해소.** FR-CO-02 모더레이션의 **빠진 절반**을 구현했다 — 내 댓글이 모더레이터에게 지워지면
작성자에게 인앱 알림이 간다. 감사 이력은 조회해야 보이는 기록이지 밀어주는 신호가 아니었다.

**★비용의 핵심이던 cross-BC 조회 포트를 만들지 않았다.** 알림 BC 가 댓글 저작자를 알아야 하는데,
조회로 얻으려면 notification → issue-tracking 방향 신규 포트가 필요하다. 대신
**이벤트 페이로드에 `commentAuthorId` 를 실었다** — `IssueMentioned.mentionedUserIds` 가 이미
같은 방식으로 동작한다(`EventRecipientResolver.resolveMentioned` — 포트 조회 없음).
이 한 가지 판단이 예상 범위를 3 BC 대공사에서 **얇은 수직 슬라이스**로 줄였다.

**구현 (6지점).**
| 지점 | 내용 |
|---|---|
| `IssueDomainEvent` | `IssueCommentDeleted` + `@JsonSubTypes` 등록 |
| `IssueEventPublisher` | 웹훅·automation 분류 `when` 2곳 (**컴파일러가 강제** — else 없는 exhaustive) |
| `CommentApplicationService.delete` | 발행. **자기 삭제도 발행**한다 — 자기제외는 수신자 해석 단계 책임 |
| `NotificationEventType` | `ISSUE_COMMENT_DELETED` (`publishable=false` — 삭제 사실이 외부로 새면 안 된다) |
| `RecipientRole` | `COMMENT_AUTHOR` — 페이로드에서 해석 |
| V410 마이그레이션 | 전역 기본 정책 1행. **수신자가 작성자 하나뿐**인 이유를 주석에 명시 |

**수신자를 작성자로만 한정한 이유.** 다른 이벤트는 REPORTER/ASSIGNEE/WATCHER 에게도 알리지만,
삭제는 다르다 — 「누군가의 댓글이 지워졌다」를 참여자 전체에 알리면 **삭제된 내용이 있었다는 사실
자체가 확산**돼 모더레이션 목적에 반한다.

**뮤테이션 확증** — 페이로드에 작성자 대신 **삭제자**를 실으면(알림이 엉뚱한 사람에게 간다)
`CO2-10b` FAILED. 그래서 테스트를 **모더레이션 삭제**(actor ≠ author) 상황으로 짰다 —
자기 삭제로 검증하면 두 값이 같아 뒤바뀜을 못 잡는다(vacuous).

**개수 가드 4종이 정확히 작동했다** ([[enum-add-breaks-crossmodule-count-guard]] 예고대로) —
`NotificationEventType` 10→11 · `RecipientRole` 9→10 · 구독 매트릭스 20→22셀 · publishable 8→9종.
전부 갱신했다.

**의도적으로 하지 않은 것.**
- **`IssueCommentUpdated`** — 수요 근거가 없다. 수정은 이력에 before/after 가 남고 당사자가 본인이다.
- **automation 트리거** — 대응 `TriggerType` 이 없고(ADR D2 의 6종에 없음) 룰 스키마·조건 평가까지
  번진다. 그 수요가 확인되면 이벤트는 **이미 있으므로** 소비만 추가하면 된다.
  `isAutomationPublishable` 의 `false` 분기에 그 사유를 주석으로 남겼다.

**★이 항목은 기술부채가 아니라 미구현 기능이다.** 결함이 아니라 **없는 기능**이며,
착수 전 **제품 판단**(무엇을 위해 필요한가)이 선행돼야 한다. 부채 목록에서 성격을 구분해 둔다.

**왜 부채가 아닌가.** 부채는 「지금 코드가 잘못돼 있다」이고, 이 항목은 「이런 기능이 없다」이다.
현재 동작이 틀린 것이 아니다 — 댓글 작성만 이벤트를 내고 수정·삭제는 안 내는 것이 **현 스펙**이다.

**2026-07-27 범위 실측 — 3 BC · 신규 enum 2종 · 신규 cross-BC 포트 1개.**

| 단계 | 위치 | 비고 |
|---|---|---|
| 1 | `IssueDomainEvent.kt` — 이벤트 타입 + `@JsonSubTypes` 등록 | 현재 9종 |
| 2 | `CommentApplicationService.update/delete` — 발행 | |
| 3 | `NotificationEventType` — 신규 항목 | 현재 10종, `wireValue`+`publishable` |
| 4 | **`RecipientRole` 신규 `COMMENT_AUTHOR`** | 현재 5종. **"내 댓글이 지워졌다" 를 받을 역할이 없다** |
| 5 | **신규 cross-BC 조회 포트** — notification 이 댓글 저작자를 알아야 한다 | ★가장 비싼 단계 |
| 6 | V410 마이그레이션 — 기본 정책 시드 (`V401` 19행 형식) | |
| 7 | `NotificationTitleBuilder` — 제목 문구 | `when` 이 else 없이 전수라 컴파일러가 강제 |

**★결정이 선행돼야 한다 — automation 트리거인가 알림인가.** 둘은 **필요한 페이로드가 다르다.**
automation 은 변경 전후 본문이 필요할 수 있고(조건 평가), 알림은 **누가 지웠는지**가 핵심이다.
지금 정하지 않고 만들면 한쪽에 맞춘 페이로드가 다른 쪽에서 부족해 이벤트를 두 번 고치게 된다.

**가장 정당화가 쉬운 슬라이스.** FR-CO-02 의 모더레이션은 **작성자에게 통지하지 않는다** —
내 댓글이 모더레이터에게 지워져도 아무 신호가 없다. 그 한 가지만 놓고 보면
「삭제 이벤트 → 작성자 알림(삭제자 ≠ 작성자일 때)」 은 **이미 배포된 기능의 마무리**라
투기적이지 않다. 반면 `IssueCommentUpdated` 와 automation 트리거는 **수요 근거가 아직 없다**.

**착수 시 첫 단계.** 위 슬라이스만 할지, automation 까지 할지 **먼저 정한다.**
슬라이스만 하면 4·5 단계(`COMMENT_AUTHOR` 역할 + cross-BC 포트)는 그대로 필요하다.

**⚠️ 알림 종류를 늘리면** 타 모듈의 개수 가드를 확인할 것 —
현재 `NotificationPolicyControllerTest` 등은 `NotificationEventType.entries.size` 로 **동적 집계**라
자동 추종하지만, DB 시드 카운트를 단정하는 곳이 있는지 전 모듈 grep 이 필요하다
([[enum-add-breaks-crossmodule-count-guard]]).

**소관**. notification BC 공동. **Maxi 제품 판단 필요** (FR-AT 또는 FR-NT).

**현황**. 댓글 **작성**은 `IssueCommented` 를 발행한다(`IssueDomainEvent.kt:182`, automation COMMENTED
트리거가 소비). 수정·삭제에 대응하는 이벤트 타입은 **없다** — `IssueCommentUpdated`·`IssueCommentDeleted`
가 존재하지 않는다.

**그래서 무엇이 안 되나.** 자동화 룰이 "댓글이 수정되면" / "댓글이 삭제되면" 을 조건으로 걸 수 없다.
알림도 마찬가지 — 내 댓글이 모더레이터에게 지워져도 아무 통지가 없다. 감사(audit) 관점에서는
`issue_change_group` 이력이 남지만, 그건 조회해야 보이는 기록이지 밀어주는 신호가 아니다.

**왜 이 PR 에서 안 했나.** 신규 이벤트 타입 1개는 이벤트 클래스에서 끝나지 않는다 —
`@JsonSubTypes` 등록 · pgmq 발행 · notification BC 의 알림 종류 시드 · 소비자 핸들러 · 사용자
알림 설정(preferences) 항목까지 번진다. issue-tracking 한 BC 안에서 닫히지 않아 "한 PR = 한 BC" 가
깨진다.

**착수 시 첫 단계**. **수요가 있는 쪽을 먼저 정한다** — automation 트리거인지 알림인지. 둘은 필요한
페이로드가 다르다(automation 은 변경 전후 본문이 필요할 수 있고, 알림은 누가 지웠는지가 핵심).
정한 뒤 `IssueCommented` 의 발행·소비 경로를 그대로 미러한다. 알림 종류를 늘리면 **타 모듈의 알림
종류 개수 가드**가 깨지므로(메모리 `enum-add-breaks-crossmodule-count-guard`) 추가 전 전 모듈 grep.

**소관**. notification BC 와 공동. FR-AT(automation) 또는 FR-NT.

## ✅ 워크플로우 — `BC_KEYWORDS` 누락 + 한국어 부분일치 (해소 2026-07-27)

**해소.** 원 기록보다 **증상이 나빴다**. `--title "댓글 리액션 추가"` 는 BC=null 이 아니라
**`automation` 으로 조용히 오라우팅**된다 — `리`+`액션` 의 `액션` 이 automation 키워드에 부분일치했다.
미정의(null)보다 나쁜 결함이다.

**근본 처방 3단.**
1. **한국어 경계 규칙** — `includes` → `includesWithBoundary`. 한글 키워드는 **앞에 한글 음절이 붙으면
   매치로 치지 않는다**(조사는 뒤에 붙으므로 뒤는 막지 않는다). ASCII 키워드는 무영향.
2. **키워드 배치 교정** — `aql`·`검색`·`search` 가 automation 에 잘못 있었다 →
   `search-export-import` BC 를 신설해 이관. `personalization` 도 함께 신설.
   누락 도메인 명사 보강(댓글·링크·히스토리·타임라인·워크로그·대시보드·가젯·인박스 등).
3. **판별식** — `bc-keyword-coverage.test.ts` 가 `docs/plan/product/*.md` 의 FR 제목 131건을
   classify 에 태워 소속 BC 와 대조한다. 불일치 **80건(61.1%) → 49건(37.4%)**.

**★남은 49건을 0 으로 만들지 않았다.** 상당수가 **오라클의 모호성**이다 — 예로 FR-IS-01
"이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림" 은 제목 하나에 3개 BC 어휘가 동시에 들어 있다.
계획 문서 편제에 맞추려고 키워드를 더 밀어넣으면 **실사용 입력의 라우팅이 오히려 나빠진다**(과적합).
그래서 baseline 으로 동결하고 **회귀만 차단**하며, baseline 이 느슨해지면 알려주는 하한 단언도 함께 뒀다.

**뮤테이션 확증** — `'댓글'` 키워드 제거 → FAILED, 경계 규칙 무력화 → FAILED.

<details><summary>원 기록 (보존)</summary>

**증상**. `scripts/workflow/classify-task.ts:122` 의 issue-tracking 키워드 목록은
`'이슈', 'issue', '코멘트', 'comment', '첨부', 'attachment', ...` 인데 **`댓글` 이 빠져 있다.**
한국어 실사용에서는 "코멘트" 보다 "댓글" 이 압도적으로 흔하다.

**언제 터지나**. 제목에 `이슈` 가 함께 들어가면 그쪽이 매치돼 가려진다 — FR-CO-02 도 제목이
`FR-CO-02 이슈 댓글 수정·삭제` 라 우연히 맞았다. 제목이 `댓글 리액션 추가` 처럼 `이슈` 없이
`댓글` 만 담으면 **BC=null 로 떨어져** 후속 스킬의 BC 분기가 미정의가 된다.

**같은 형태가 더 있을 수 있다.** 이건 하드코딩 목록의 누락이므로 개별 단어 추가로 끝내면 재발한다
(메모리 `guard-handler-matrix-blindfold` 와 동질 — 행 집합 자체가 눈가리개).

**착수 시 첫 단계**. `댓글` 한 단어만 넣지 말고, **BC 별 도메인 용어의 출처를 하나로 묶는다** —
`Maxi_wiki/BTS/glossary.md` 와 `docs/plan/product/<bc>.md` 의 FR 제목에 등장하는 한국어 명사를
뽑아 `BC_KEYWORDS` 와 **차집합**을 낸다. 차집합이 0 이 되게 채우고, 앞으로 어긋나면 깨지는 검증을
하나 둔다. 위의 *"`bts-review-plan` 분기 표에 `type=backend` 가 없다"* 항목과 **같은 뿌리**다 —
둘 다 하드코딩 목록끼리의 정합을 아무도 안 보고 있다.

**소관**. `scripts/workflow/classify-task.ts`.

</details>

## ✅ identity-access — CORS `PATCH`·`Content-Disposition` 누락 (해소 2026-07-27)

**해소.** `allowedMethods` 에 `PATCH` 추가 + `exposedHeaders = listOf("Content-Disposition")` 신설.
조립 레벨 판별식 `CorsAllowedMethodsCoverageTest`(app 모듈)로 차집합 0 을 강제한다.

**★누락이 하나가 아니라 둘이었다.** `exposedHeaders` 자체가 없었다 — `Content-Disposition` 은
CORS-safelisted 응답 헤더가 아니라 명시하지 않으면 cross-origin 에서 JS 가 읽지 못한다.
프론트 4곳(`search.ts:222·298`, `automation-rules.ts:186`, `imports.ts:137`)이 이 헤더에서
다운로드 파일명을 뽑는다. **PATCH 만 고치고 두면 같은 사고가 한 번 더 난다.**

**★테스트가 결함을 정답으로 못박고 있었다.** `CorsConfigTest` 가 `containsExactlyInAnyOrder` 로
PATCH 없는 집합을 단정해, 결함 상태가 "테스트 통과" 로 보였다. 하드코딩 목록끼리의 대조라 필연이다.

**판별식** — `RequestMappingInfoHandlerMapping` 에서 **실제 등록된** 핸들러의 메서드 집합을 뽑아
`allowedMethods` 와 차집합을 낸다. 목록끼리 대조하지 않는다. 조립 모듈에 둔 이유는 `CorsConfig` 가
identity-access 에 있어도 그 설정은 **9 BC 전체 요청**에 적용되기 때문이다.
수집 0건 방지 하한 + PATCH 등록 존재 단언으로 공허 통과를 막았다.

**HEAD 는 불필요 판정** — `RequestMethod.HEAD` / `@RequestMapping(method=HEAD)` 생산 지점 0건.

**뮤테이션 확증** — `allowedMethods` 에서 PATCH 를 빼자 커버리지 테스트 FAILED.

**부수 — ktlint baseline 633→632.** 이 파일 편집으로 줄이 밀리자 baseline 이 **line 번호로** 고정하던
선재 위반이 되살아났다. baseline 을 늘리지 않고 코드로 해소한 뒤 stale 엔트리를 제거했다.

<details><summary>원 기록 (보존)</summary>

**증상**. `CorsConfig.kt:24` 의 허용 메서드 목록이
`listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")` 로 **`PATCH` 가 빠져 있다.**

**폭발 반경**. 프로덕션 `@PatchMapping` **40개**
(2026-07-27 실측 — `grep -rn "@PatchMapping" backend/modules --include='*.kt' | grep -v "/test/" | wc -l`).
댓글 수정·이슈 수정을 포함한 전 PATCH 표면이 한꺼번에 걸린다.

**지금 무해한 이유**. `infra/prod/nginx.conf:51~100` 이 **단일 `server` 블록**이다 —
SPA(`location /`)와 백엔드 프록시(`location ~ ^/(api|...)`)가 같은 오리진이므로
브라우저가 preflight(사전 확인 요청) 자체를 보내지 않는다. 즉 현 토폴로지에서 CORS 설정은 사문이다.

**언제 터지나**. `backend/modules/app/src/main/resources/application.yml:83` 에
`BTS_CORS_ALLOWED_ORIGINS` 오버라이드가 이미 배선돼 있다. 프론트를 별도 도메인으로 분리하는 순간
**PATCH 40개가 동시에 preflight 에서 차단**된다.

**★왜 어떤 테스트도 못 잡나**. MSW 는 네트워크 계층 이전에서 가로채고, Vite dev 프록시는 요청을
동일 오리진으로 만들며, MockMvc 는 `CorsFilter` 를 타지 않는다. **전 계층 초록 + 실배포만 빨강**
이라는, 이 저장소에서 가장 늦게 발견되는 유형이다.

**착수 시 첫 단계**. `PATCH` 한 단어 추가로 끝내지 말 것. `allowedMethods` 하드코딩 목록과
**실제 컨트롤러 매핑 애너테이션이 쓰는 메서드 집합의 차집합**을 낸다(`HEAD` 필요 여부도 이때 판정).
차집합 0 을 강제하는 검증을 하나 둔다. 위의 *`bts-review-plan` 분기 표* · *`BC_KEYWORDS`* 항목과
**같은 뿌리** — 하드코딩 목록끼리의 정합을 아무도 안 보고 있다.

**소관**. `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/CorsConfig.kt`.

</details>

## ✅ issue-tracking — 댓글 관통 실 DB 테스트 (해소 2026-07-27)

**해소.** `CommentEditDeleteHistoryE2EIntegrationTest` 신설 —
컨트롤러 → 서비스 → 리포지토리 → `IssueHistoryRecorder` → `issue_change_group`/`item` →
조회 시 `maskDeletedCommentBodies` 까지 **한 테스트로 관통**한다.
`IssueChangeHistoryE2EIntegrationTest` 의 빈 배선 구조를 선례로 재사용했다.

**뮤테이션 확증** — `maskDeletedCommentBodies` 호출을 지우자 이 테스트가 red 가 됐다.
그전까지 "삭제 후 이력 본문이 실제로 가려지는지" 는 mock 위에만 있었다.

<details><summary>원 기록 (보존)</summary>

**증상**. FR-CO-02 의 쓰기 경로 전 구간이 **실 PostgreSQL 을 한 번도 통과한 적이 없다.**

**실측 (2026-07-27)**. 댓글 테스트 3종의 성격이 이름과 다르다.

| 파일 | 실제 성격 |
|---|---|
| `comment/web/CommentControllerIntegrationTest.kt` | **MockMvc 슬라이스** (L1 주석이 그렇게 밝힘, `MockMvcBuilders.webAppContextSetup`, 서비스는 `every { }` 스텁) |
| `comment/application/CommentApplicationServiceTest.kt` | mock |
| `comment/repository/CommentRepositoryTest.kt` | **실 DB** (`IssueTestcontainersBase` 상속) — 단, 리포지토리 단층 |

**끊긴 구간**. 컨트롤러 → 서비스 → 리포지토리 → `IssueHistoryRecorder` →
`issue_change_group`/`item` 기록 → 조회 시 `maskDeletedCommentBodies`.
각 층은 개별 검증되지만 **이어붙인 상태로 실 DB 를 통과한 적이 없다.**

**왜 중요한가**. 트랜잭션 경계·실제 SQL 제약·마스킹 조인은 mock 으로 드러나지 않는다.
메모리 `transaction-aware-dslcontext-rollback-test-gap` 의 거짓 red 기전과 같은 계열이다.
특히 **삭제 후 이력 본문이 실제로 가려지는지**를 단정하는 테스트가 지금은 mock 위에만 있다.

**착수 시 첫 단계**. `history/IssueChangeHistoryE2EIntegrationTest`(같은 `IssueTestcontainersBase`
선례)를 따라 **수정 → 삭제 → 이력 조회 마스킹까지 한 테스트로 관통**시킨다. 통과를 확인한 뒤
`maskDeletedCommentBodies` 호출을 지우는 뮤테이션으로 그 테스트가 실제로 red 가 되는지 확증한다
(메모리 `verify-logic-vs-verify-guard`).

**소관**. `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/comment/`.

</details>

## ✅ issue-tracking — `findByIssue` 마스킹 갭 (해소 2026-07-27 · ArchUnit 차단)

**해소.** 마스킹을 이 메서드에 하나 더 붙이는 대신 **프로덕션 유입 경로 자체를 0 으로 고정**했다.
`IssueBcArchTest` 에 룰 3 을 추가 — `com.bts.issue.history` 밖의 프로덕션 클래스가
`IssueChangeHistoryRepository.findByIssue` 를 호출하면 실패한다.

**왜 마스킹을 추가하지 않았나.** 계층 역전(history repo → comment repo)이 생기고,
**다음 읽기 메서드가 추가되면 같은 실수가 반복된다.** 가드를 만들고 생산 지점 일부에만 주입한
상태가 정확히 지금 문제다([[mutation-site-count-equals-verified-scope]]).
정말 필요해지면 `findByIssuePaged(issueId, limit, 0)` 가 상위집합이다.

**★ArchUnit 의 green 은 두 가지를 뜻할 수 있다** — "위반이 없다" 또는 "대상 집합이 비었다".
프로덕션 호출자가 0건이라 이 룰은 즉시 green 이므로, **비-공허 테스트를 별도로 뒀다**
(대상 클래스가 import 범위에 실재하는지 + import 된 프로덕션 클래스 수 하한).

**뮤테이션 확증** — `IssueChangelogService` 에 `findByIssue` 호출을 주입하자 룰이 FAILED.

**남은 후속** — 마스킹이 실 DB 에서 동작한다는 증거는 아직 mock 위에만 있다.
아래 §관통 실 DB 테스트 항목이 그것을 세운다.

<details><summary>원 기록 (보존)</summary>

**증상**. 이력 읽기 경로가 **셋인데 마스킹은 둘에만** 있다.

| 읽기 경로 | 마스킹 |
|---|---|
| `IssueChangelogService.kt:246` (offset) | ✅ `maskDeletedCommentBodies` |
| `IssueChangelogService.kt:301` (cursor) | ✅ `maskDeletedCommentBodies` |
| `IssueChangeHistoryRepository.findByIssue` (`:40`, 구현 `JdbcIssueChangeHistoryRepository.kt:85`) | **❌ 없음** |

**현재 노출은 0**. 2026-07-27 실측 — `findByIssue(` 호출 지점이 **전부 테스트**다
(`IssueChangeHistoryE2EIntegrationTest` · `IssueMoveHistoryIntegrationTest` ·
`JdbcIssueChangeHistoryRepositoryIntegrationTest` 등 33곳). 프로덕션 호출자 0 건.

**그런데 왜 등재하나**. FR-CO-02 D7 이 마스킹을 **조회 시점 정책**으로 정했기 때문이다.
새 기능이 이 메서드를 호출하는 순간 삭제된 댓글 본문이 그대로 응답에 실린다. 가드를 만들고
생산 지점 일부에만 주입한 상태 — 메모리 `mutation-site-count-equals-verified-scope` 의 정확한 재현이다.

**착수 시 첫 단계**. 개별 경로에 마스킹을 하나 더 붙이는 방향으로 가지 말 것.
**마스킹을 단일 지점으로 끌어내리거나**(메모리 `fr-db-03-public-dashboard-error-instance-token-leak-done`
의 "헬퍼 단일 지점화" 처방과 동형), `findByIssue` 를 프로덕션에서 못 쓰게 막는다(ArchUnit 규칙).
어느 쪽이든 **읽기 경로 3종을 먼저 전수 열거**하고 시작한다.

**소관**. `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/` +
`.../issue/application/IssueChangelogService.kt`.

</details>

## ✅ apps/web mocks — 댓글 MSW 판정 순서 해소 / 에러 형태 기각 (2026-07-27)

**두 주장을 분리 판정했다. (b)는 사실이라 고쳤고, (a)는 실해 0 이라 기각한다.**

### ✅ (b) 판정 순서 역전 — 해소
백엔드 `CommentApplicationService` 는 이슈 `UPDATE` 게이트를 **댓글 조회보다 먼저** 통과시킨다
(`create:150` · `update:194`→`:200` · `delete:279`→`:290`). 모크에는 그 게이트가 아예 없어
같은 상황에서 **404** 가 나갔다 — 「권한 없음」과 「댓글 없음」이 뒤바뀐다.

`issueUpdateGate(request)` 헬퍼를 만들어 **POST·PATCH·DELETE 3지점 전수**에 **가장 먼저** 주입했다.

**★이 갭이 안 보인 진짜 이유는 관측 수단의 부재였다.** `UPDATE=false` 인 픽스처 사용자가 없어
(alice=ADMIN·bob=MEMBER 둘 다 `UPDATE=true`) **어떤 테스트도 게이트를 밟을 수 없었다.**
`viewerPermissionsFixture` + `carolUser`(VIEWER) 를 신설해 판별자를 만들었다 —
없으면 게이트를 지워도 전량 green 이다. 대조군(권한 보유자는 404·201)도 함께 뒀다.

### ❌ (a) 에러 본문 형태 — 기각
원 기록은 「MSW 는 `errorCode`, 백엔드는 RFC7807 ProblemDetail」 이라 적었으나 실측하면 **실해가 0**이다.
- 백엔드 `CommentExceptionHandler.kt:261` 이 `pd.setProperty("errorCode", errorCode)` 로
  **ProblemDetail 에 최상위 `errorCode` 를 같이 싣는다.**
- 프론트 파서가 읽는 키가 정확히 그것이다 — `extract-error-code.ts:21` `b['errorCode'] ?? b['error']`.
  인라인 파서 4곳도 동일. ⇒ **형태를 바꿔도 깨지는 파서는 0곳이고, 지금도 파싱은 성립한다.**
- 댓글 UI 는 `errorCode` 를 아예 안 읽는다 — `CommentSection.tsx:54·318·330` 전부 고정 문구다.
  원 기록이 경고한 "화면 문구가 조용히 깨진다" 는 댓글에 해당 없음.
- **댓글만의 문제도 아니다** — `errorCode:` 를 쓰는 mock 파일 **55개** vs ProblemDetail 흉내 **16개**.
  `errorCode`-only 가 오히려 지배 관례다(`worklog-handlers` 도 같다).

⇒ comment-handlers 만 고치면 55 대 16 의 불일치는 그대로다. 굳이 한다면
`problemDetail(status, type, errorCode, detail)` **공용 헬퍼**로 16파일 관례에 수렴시키는 **별건**으로 잡을 것.
위 §계약 검증 8/303 항목의 하위 작업이 적절하다.

### 잔여
`worklog-handlers.ts`(PATCH `:247-` · DELETE `:310-`)에 **같은 게이트 갭**이 있다.
같은 판별식으로 처리할지 명시 결정 필요 — 안 할 거면 사유를 여기 남길 것.

<details><summary>원 기록 (보존)</summary>

**증상 (a) — 에러 본문 형태**. MSW 는 `{ errorCode: 'COMMENT_NOT_FOUND' }` 를 낸다
(`comment-handlers.ts:219 · 225 · 232 · 262 · 271`). 백엔드는 RFC 7807 `ProblemDetail` 이다
(`comment/web/CommentExceptionHandler.kt`, issue-tracking 예외 핸들러 공통 관례).

**증상 (b) — 판정 순서 역전**. 백엔드는 `CommentApplicationService.update:194` ·
`delete:279` 에서 **이슈 `UPDATE` 권한 게이트를 가장 먼저** 통과시킨다 → 권한 없는 사용자는
**댓글 존재 여부와 무관하게 403**. MSW 에는 그 게이트가 **아예 없고** 댓글 조회 404 를 먼저 낸다
(`comment-handlers.ts:224` PATCH · `:260` DELETE) → 같은 상황에서 **404**.

**어긋나는 지점은 정확히 하나다**. 작성자 판정(404 → 403)은 양쪽이 일치한다
(백엔드 `update:200 → 203`, MSW `224 → 231`). 갈리는 건 **이슈 수준 권한 게이트의 부재**다.

**왜 안 걸렸나**. 프론트 테스트는 mock 만 본다. 모크가 백엔드와 다르게 답해도 전량 초록이며,
「MSW 가 MSW 와 맞는」 상태가 유지된다(위 §워크플로우 스킴 계약 파손 항목의 L91 과 같은 기전).

**착수 시 첫 단계**. `comment-handlers` 에 게이트를 하나 끼워 넣는 것으로 끝내지 말 것 —
다음 엔드포인트에서 또 갈린다. **모크와 백엔드의 판정 순서를 대조하는 기준을 먼저 정한다.**
에러 형태 정렬(`errorCode` → ProblemDetail)은 **프론트 에러 파서가 실제로 무엇을 읽는지**
확인한 뒤에 착수한다 — 파서까지 같이 안 바꾸면 화면 문구가 조용히 깨진다.
관련 — 위 *`ProjectWorkflowSchemeController` 의 404/403 순서* 항목과 같은 계열,
메모리 `msw-dual-handler-e2e-shadow`.

**소관**. `apps/web/src/mocks/comment-handlers.ts`.

</details>

## ✅ issue-tracking — 렌더 단일 지점 판별자 (기각 · 2026-07-27 종결)

**닫는다.** 이 항목이 스스로 정한 종료 조건 — *"'어떤 판별자가 단일 단정에 의존하는가' 를 먼저 특정한다.
특정되지 않으면 이 항목을 닫는다"* — 이 충족됐다. **원 체크포인트 메모는 기각**이다.

**원 메모**. "하네스 판별자가 단일 단정(`CommentControllerIntegrationTest:498`)에 의존".

**실측 (2026-07-27)**. 렌더 단일 지점은 `CommentView.kt:44-52` 의 `fun of(comment)` 안
`bodyHtml = MarkdownRenderer.renderSafe(comment.body)` (L49) 이고, KDoc 이 스스로 그렇게 선언한다.

1. **프로덕션 진입 경로 3개 전부 그 지점을 통과한다.** 목록 `CommentApplicationService.kt:337`
   (`comments.map(CommentView::of)`) · 작성 `CommentController.kt:141` · 수정 `:197` — 뒤 둘은
   `CommentResponse.kt:58` 의 `from(comment) = from(CommentView.of(comment))` 로 수렴한다. **우회 0.**
2. **`bodyHtml` 을 값으로 생산하는 지점은 `CommentView.kt:49` 단 하나다** (src/main 전수 grep — 나머지는
   선언·전달·KDoc). 단일 지점 밖에서 댓글 HTML 을 만드는 경로가 없다.
3. **검증 테스트는 5개·단정 8개다** — `CommentApplicationServiceTest:300` T3-D(목록 경로 실렌더) ·
   `CommentControllerIntegrationTest` CO2-P1 `:497` · CO2-P2 `:516,517` · CO2-P3 `:533,534` · CO2-P4 `:550`.
   어느 한 줄을 지워도 나머지가 사망을 잡는다. ⇒ **단일 단정 의존이라는 판별자 자체가 존재하지 않는다.**

원 기록이 "세 테스트"라 적은 것도 과소집계였다(서비스 계층 T3-D 와 CO2-P1 누락 → 실제 5개).
`:498` 이 `updatedAt` 단정이라는 재확인은 정확했다.

**★이 항목이 미검증으로 남은 경위 (재발 방지 — 존치)**. 체크포인트 메모에 *"TODOS.md 등재 6건"* 으로
적혀 있었으나 **실제로는 5건 전부 미등재**였다(`git show c90ca8fb6 -- TODOS.md | grep "^+## "` 로 확정).
**체크포인트의 "등재했다" 진술은 저장소에서 검증해야 한다.**

**→ 닫으면서 아래 신규 1건을 분리 등재한다** (댓글이 아니라 이슈 description 소관이라 같은 항목이 아니다).

## ✅ issue-tracking — `descriptionHtml` 생산 지점 1개로 수렴 (해소 2026-07-27)

**해소.** `IssueResponse.from` 의 `renderHtml: Boolean` 파라미터와 그 `renderSafe` 분기를 제거해
**생산 지점을 `IssueApplicationService.withSingleDetail()` 하나로 굳혔다.** 호출자 0건인 죽은 분기였다.

살려두면 XSS 방어의 검증 대상이 두 갈래로 갈려, 한 쪽을 깨도 다른 쪽 테스트가 초록을 유지한다.
**뮤테이션 확증** — 남은 단일 지점의 `renderSafe(...)` 를 원문 통과로 바꾸자
`IssueControllerIntegrationTest` 의 「PATCH description Markdown 렌더 후 GET descriptionHtml XSS 차단」 FAILED.
내 변경이 만든 고아 import 도 함께 제거했다.

<details><summary>원 기록 (보존)</summary>

**증상**. 댓글은 렌더 단일 지점(`CommentView.of`)이 확립돼 우회 경로가 0인데,
**이슈 `description` 은 `MarkdownRenderer.renderSafe` 직접 호출이 2곳**이다.

| 생산 지점 | 상태 |
|---|---|
| `IssueApplicationService.kt:2028` | 살아 있음 |
| `IssueResponse.kt:357` (`renderHtml=true` 분기) | **호출자 0건 — 죽은 분기** |

**왜 지금 등재하나**. `renderHtml` 분기가 죽어 있는 **지금이 폭발 반경이 가장 작은 시점**이다.
누군가 이 파라미터를 다시 쓰는 순간 XSS 방어(`renderSafe`)의 검증 대상이 두 갈래로 갈린다 —
메모리 [[mutation-site-count-equals-verified-scope]] 가 말한 "가드를 만들고 일부 지점에만 주입한" 상태의
이슈 description 판이다. 실제로 FR-MN-01 에서 XSS SUPPRESS 회귀가 한 번 났던 영역이다
([[fr-mn-01-xss-suppress-inline-html-regression]]).

**착수 시 첫 단계**. 둘 중 하나로 **수렴**시킨다 — ① `IssueResponse.kt:357` 의 `renderHtml` 파라미터와
그 `renderSafe` 분기를 제거해 `withSingleDetail()` 을 유일 생산자로 굳히거나, ② 반대로
`withSingleDetail()` 을 걷어내고 `from(renderHtml=true)` 로 모은다. 목표는 **생산 지점 1개**다.
굳힌 뒤 그 지점의 `renderSafe(...)` 를 원문 통과로 바꾸는 **뮤테이션으로 테스트가 실제로 빨강이 되는지**
확증한다(기준선 EXIT=0 선확인 — [[verify-logic-vs-verify-guard]]).

**소관**. `backend/modules/issue-tracking/.../issue/web/IssueResponse.kt` +
`.../issue/application/IssueApplicationService.kt`.

</details>

## ✅ project-workflow — 계약 스냅샷 숫자 타입 붕괴 (승격 후 실측 해소 2026-07-28)

> 원래 `## ✅ project-workflow — 계약 파손 (해소 #317)` **본문에 묻혀** 있었다.
> ✅ 섹션 안의 미해결 마커 는 헤딩만 세는 집계에서 조용히 사라진다 — 그래서 자기 섹션으로 올렸다.
> 재발 차단은 `scripts/workflow/todos-resolved-section-purity.test.ts` 가 한다.
>
> **★승격하고 보니 이미 닫혀 있었다.** 커밋 `1f283c7a0` 이 `ContractSnapshotCanonicalizer` 에
> `CANONICAL_INTEGRAL`(정수) / `CANONICAL_FRACTIONAL`(실수) 분리를 넣었는데 **TODOS 만 안 고쳤다.**
> 「부채 기록 자체가 부정확하다」 양식의 재발이다 — 착수 전에 실물부터 확인해야 하는 이유.
>
> 뮤테이션 실측 — 정규화를 `if (true) IntNode(...)` 로 되돌리면
> `정수와 실수는 서로 다른 표준값으로 정규화된다` FAILED. 판별식이 살아 있다.

계약 스냅샷의 **숫자 타입 붕괴** — 정규화가 모든 숫자를 `1` 로 만들어 `Long`→`Double` 변경을 못 잡는다

## ✅ project-workflow — fetchProjectAssignment 404 해석 (승격 후 실측 해소 2026-07-28)

> 위와 같은 이유로 승격했고, **역시 이미 닫혀 있었다** (같은 커밋 `1f283c7a0`).
> `workflow-schemes.ts` 가 404 를 `null` 로 삼키지 않고, KDoc 이
> 「404 는 미할당이 아니라 프로젝트 없음」을 정본으로 적어 두었다.
>
> 뮤테이션 실측 — `if (res.status === 404) return null` 을 되살리면
> `promise resolved "null" instead of rejecting` 으로 FAILED.

**`fetchProjectAssignment` 404 해석(선재)** — 백엔드는 미배정에 404 를 안 낸다. 실제 404 는 「프로젝트 없음」.
  <br>2026-07-27 실측 — 프론트 `workflow-schemes.ts:243-247`(기록의 `:233-243` 은 줄 밀림)이 404→`null`→「미할당」로 읽는다.
  백엔드 `ProjectWorkflowSchemeController.kt:115-117` 의 404 는 `projectLookupPort.findIdByKey` 실패 **한 곳뿐**이고,
  배정 조회는 `WorkflowSchemeApplicationService.kt:468-477` 에서 미배정 시 **software-scheme 을 자동 배정**한다.
  ⇒ **존재하지 않는 프로젝트 URL 로 들어가면 「스킴 미할당」 안내가 뜬다.**
  처방 A(프론트를 백엔드에 맞춤)가 이연 범위에 맞으나, `UnassignedSchemeCard` 분기 · MSW 핸들러 ·
  e2e `E2E-5 S10` 을 **함께** 제거해야 죽은 코드가 안 남는다. 처방 B(GET 의 자동 배정 = 부수효과 있는 GET 재고)는
  계약 스냅샷 8 endpoint 전부에 영향 → 별도 스펙 작업.

## ⬜ personalization — 접힘 레일에서 「최근 항목」 접근 경로 (FR-UX-08 PR-B 의 **의도된 대가** · 미착수)

> **⚠️ 이건 결함이 아니라 결정이다.** 「고쳐야 할 버그」로 오인해 조용히 되돌리지 말 것.

**무엇.** 사이드바를 접은(64px 레일) 상태에서 「최근 항목」에 닿는 단일 진입점
(아이콘 1개 + `popover.tsx` 팝업) 도입 검토.

**왜 지금 없나.** FR-UX-08 PR-B `/plan-design-review` **D-B (2026-07-31 Maxi 확정)** 이
접힘 시 **섹션 전체 미렌더**를 택했다. 근거는 스펙 §8-A D-B —
`ProjectTree.tsx:217-223` 의 첫 글자 뱃지를 쓰면 `ATLAS-12`·`ATLAS-13` 이 **둘 다 `A`** 라
5칸을 먹으면서 구분은 0 이다. E10 의 *"아이콘만 노출 · 텍스트 `sr-only`"* 는
**단일 링크** 관례지 목록 관례가 아니다.

**대가.** 접은 채로 쓰는 사용자는 최근 본 이슈에 접근할 수 없다. (「내 작업」은 단일 링크라
기존 관례대로 접힘에서도 아이콘 + `sr-only` 로 살아 있다.)

**Pros.** 접힘 사용자도 히스토리 접근 가능 · `popover.tsx` 가 PR-A 로 이미 들어와 있어 신규 의존성 0.
**Cons.** 렌더 경로가 접힘/펼침 둘로 갈려 컴포넌트 테스트가 두 배 · 64px 레일의 시각 예산을 또 쓴다.

**선행 조건.** 「사이드바를 접은 채 쓰는 사용자 비율」에 대한 실사용 신호. 그 전에는 추측 구현이다.
**착수 시 읽을 것.** 스펙 `docs/specs/2026-07-30-fr-ux-08-project-switcher.md` §8-A D-B · D-C.

> **★2026-08-09 전수 실측 검증 — 판정 `결함 아님 · 유지`.**
> - **정정 1.** `popover.tsx` 유입 PR 은 PR-A 가 아니라 **FR-UX-06 Phase 0 PR2(#286)**. FR-UX-08 PR-A(#326)는 그걸 **소비**했을 뿐이다. 결론(신규 의존성 0)은 그대로.
> - **정정 2.** 대가는 「최근 본 이슈에 접근 불가」가 아니라 **「MRU 목록에 접근 불가」**다. ⌘K 자유 텍스트 검색으로 이슈 자체에는 도달한다.
> - **정정 3.** Cons 의 「테스트가 두 배」는 과장. 렌더 경로는 **이미** 갈려 있다(`RecentIssuesMenu.tsx:71·82`). 진짜 비용은 신규 테스트가 아니라 **기존 봉인 2개(유닛 T-RM-7 · e2e S9-b)의 단언을 정반대로 다시 쓰는 것**이다.
> - **★정정 4 (착수 차단).** 선행 조건 「실사용 신호」는 **원리적으로 충족 불가**다 — 저장소에 분석/텔레메트리 코드가 **0건**이라 「접은 채 쓰는 비율」을 잴 방법이 없다. 조건을 그대로 두면 이 항목은 영구 보류다.
> - **되돌림 금지 — 단, 두 가드의 보호 수준이 다르다 (게이트2 리뷰 정정).**
>   · `:82` 의 `if (collapsed) return null` 은 T-RM-7(유닛)·S9-b(e2e)가 지킨다. 지우면 둘 다 red 다.
>   · **`:71` 의 `keysToResolve` 가드는 무검증이다.** 지워도 두 테스트가 **초록으로 통과**한다 — `:82` 가 남아 DOM 단언이 그대로 만족되기 때문이다. 그 순간 접은 사용자가 MRU 키 개수만큼 `GET /api/v1/issues/{key}` 를 계속 쏘는 회귀가 되는데, 「테스트가 잡아준다」고 믿어 아무도 확인하지 않는다.
>   ⇒ 착수 시 T-RM-7 에 **접힘 상태 요청 0건** 단언(핸들러 호출 카운터)을 추가할 것. 인용 좌표 `ProjectTree.tsx:217-223` 은 밀리지 않았다(실측 일치).
> - **착수하게 될 경우 권장안은 원안(A)이 아니라 B.** 레일에 아이콘을 더하는 대신 `CommandPalette.tsx:473` 의 `showQuickLinks` 빈 입력 분기에 「최근 본 이슈」 그룹을 넣는다 — 64px 레일 시각 예산 0, §8-A D-B 를 뒤집지 않는다.

---

## ⬜ issue-tracking — 도메인 `require` 실패가 500 으로 나간다 (FR-UX-09 B1 의 **의도된 이연** · 미착수)

> **⚠️ 결함이지만 이 PR 이 만든 게 아니다.** FR-UX-09 B1 이 **생성 경로만** 봉합했다.

**무엇.** `PATCH /api/v1/issues/{key}` 에 51자 라벨 또는 공백-only 라벨을 보내면
도메인 `Issue.validateAndNormalizeLabels`(`Issue.kt:370-379`)의 `require` 가
`IllegalArgumentException` 을 던지는데, `IssueExceptionHandler`
(`@RestControllerAdvice(basePackages = ["com.bts.issue.adapter.inbound.rest"])`)에
**`IllegalArgumentException` 핸들러가 없어 500** 이 된다. 사용자 입력 오류가 서버 장애로 기록된다.

**실측 (2026-07-31).**
- `IllegalArgumentException` 핸들러는 `BulkOperationExceptionHandler:99` · `EpicChildExceptionHandler:104`
  **둘뿐이고 각자 자기 패키지 스코프**다. `IssueController` 는 어느 쪽도 안 덮는다
- `UpdateIssueRequest.kt:80` 의 `List<@Size(max = 50) String>` **컨테이너 원소 제약은 동작하지 않는다**
  (장식). 그래서 51자가 400 으로 안 걸리고 도메인까지 내려간다.
  `IssueApplicationServiceTest.kt:955` 가 *"도메인 검증"* 이라고 적어둔 게 그 증거다

**왜 지금 안 고치나.** 2026-07-31 **Maxi 확정 D-6 = A안**(생성 경로만 400 보장).
전역 `IllegalArgumentException → 400` 핸들러는 **진짜 버그까지 400 으로 위장**해
살아있어야 할 500 을 숨긴다(`catch-all-exceptionhandler-swallows-responsestatusexception` 계열).

**생성 경로는 어떻게 닫았나.** `CreateIssueRequest.isLabelsValid` (`@AssertTrue`) —
컨테이너 원소 제약을 복사하지 않고 실제로 동작하는 방식으로 길이 + 공백-only 를 400 으로 막았다.

**착수 시 선택지.** ① `UpdateIssueRequest` 에도 동일한 `@AssertTrue` (좁고 안전, 비대칭 해소)
② 도메인에 전용 예외 타입 신설 후 422 매핑 (근본적, 폭발 반경 큼)
**착수 시 읽을 것.** ADR `docs/decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md` §D-6 배경.

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (반증 실패 · 어떤 층도 안 닫고 있음).**
> - **원인 확정.** 「원소 제약이 장식」이 지금까지 **행동 관찰**로만 기록돼 있었는데, 이번에 **바이트코드로 확정**했다 — Kotlin 이 `List<@Size String>` 의 타입-use 애노테이션을 런타임 보존 형태로 심지 않는다.
> - **정정 1.** 도메인 함수 범위는 `Issue.kt:370-379` 가 아니라 **370-385**. 370-379 는 개수 상한 `require`(381-383)를 빠뜨린다.
> - **정정 2.** `EpicChildExceptionHandler` 는 「자기 **패키지** 스코프」가 아니라 `@RestControllerAdvice(assignableTypes = [IssueEpicController::class])` — **컨트롤러 타입** 한정이다. `basePackages` 인 것은 `BulkOperationExceptionHandler` 뿐. 결론(IssueController 를 안 덮는다)은 양쪽 다 유효.
> - **정정 3.** ADR `2026-07-31-…-b1-create-issue-fields.md:150` 이 두 DTO 를 함께 지목한 서술은 **봉합 이전 상태의 화석**이다. 현재 `CreateIssueRequest.kt:64` 는 `List<String>? = null` 로 원소 제약이 없다.
> - **★선택지 ② 는 기각 — 단 사유를 정정한다 (게이트2 리뷰).** 처음에 적은 「생성 경로의 400 계약을 깨뜨린다」는 **성립하지 않는다.** 인용한 생성 테스트의 400 은 전부 DTO Bean Validation 에서 나오고 도메인은 도달조차 하지 않으므로, 도메인 예외 타입을 바꿔도 그 테스트들은 그대로 초록이다.
>   진짜 기각 사유는 ADR D-6 의 원래 논거다 — **전역 `IllegalArgumentException` 매핑은 진짜 버그를 사용자 오류로 위장해 살아 있어야 할 500 을 숨긴다.**
> - **2026-08-09 Maxi 확정 — ①(400, 생성/수정 대칭)으로 좁게 봉합.** 상수 `LABEL_MAX_LENGTH` 사본 수렴은 이 PR 범위 밖(신규 항목 등재).
> - **★처방의 구멍(반증이 적발).** `@get:JsonIgnore` 를 빠뜨리면 springdoc 이 `labelsValid` 를 스키마에 흘리는데 **현재 `OpenApiContractTest` 는 create 쪽만 봉인(:204)해서 아무도 못 잡는다.** update 쪽 대칭 단언을 같은 PR 에 넣지 않으면 이 처방 자체가 새 결함을 심는다. 또한 `@AssertTrue` 는 getter 이름 규약(`isLabelsValid` → property `labelsValid`)에 묶여 있어 이름을 바꾸면 **지금 고치는 장식 애노테이션과 같은 양식으로 조용히 무력화**된다 — 뮤테이션 검증 필수.

---

## ⬜ issue-tracking — `cloneIssue` 는 담당자를 정해도 `IssueAssigned` 를 발행하지 않는다 (미착수)

**무엇.** `cloneIssue` 는 `assigneeId = if (request.includeAssignee) source.assigneeId else null`
로 담당자를 설정하면서 `IssueCreated` 만 발행한다(`IssueApplicationService.kt` clone 블록).
FR-UX-09 B1 이 `createIssue` 에 「담당자가 확정되면 `IssueAssigned`」를 넣었으나
**clone 은 `createIssue` 를 경유하지 않는 별도 함수**라 자동으로 포함되지 않았다.

**결과.** 복제로 배정받은 사용자는 알림을 못 받는다. 같은 「배정」인데 경로에 따라 알림이 갈렸다.

**왜 지금 안 하나.** ADR D-5 가 범위를 **REST 생성 경로**로 한정했다(2026-07-31 Maxi 확정).
clone 은 별건으로 남긴다 — 회귀 표면과 PR 범위를 동시에 넓히지 않기 위해서다.

**착수 시 주의.** clone 은 대량 복제 시나리오가 있으므로 `notifyAssignment` 같은
**명시적 게이트 없이 무조건 발행하면 안 된다**. D-5 와 같은 fail-safe 기본값을 쓸 것.

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (반증 실패 · 인용 15건 전부 실측 일치).**
> - **정정 1 (좌표).** `cloneIssue` 는 현재 `IssueApplicationService.kt:348-394`. 담당자 설정 `:379`, `IssueCreated` 발행 `:382-391`. ADR 이 가리킨 `:365` 는 밀렸다.
> - **★정정 2.** 「clone 은 대량 복제 시나리오가 있으므로」는 **지금 코드 기준 거짓**이다. `BulkOperationType` 은 BULK_EDIT/BULK_TRANSITION 둘뿐이고(`BulkOperationType.kt:11-14`) 클론 진입점은 단건 REST **하나**(`IssueController.kt:872`)뿐이다. fail-safe 게이트는 「현존 대량 경로 방어」가 아니라 **「미래 생산자 대비 defense-in-depth」**로 정당화해야 한다.
> - **정정 3 (수정 지점).** `notifyAssignment` 는 REST DTO 필드가 아니라 **애플리케이션 계층** DTO 필드다(`IssueApplicationRequests.kt:46`). 따라서 고칠 곳은 `adapter/inbound/rest/CloneIssueRequest.kt` 가 아니라 `IssueApplicationRequests.kt:269` 이며, 그러면 **OpenAPI 계약·프론트 변경이 0**이다.
> - **★정정 4 (피해가 서술보다 크다).** 알림뿐 아니라 `cloneIssue` 는 `autoWatch` 도 호출하지 않는다(`:295` 와 대비). `issue.created` 의 수신자 역할에 ASSIGNEE 가 없으므로(`V401:10-12`) **클론 배정자는 인앱 알림을 단 1건도 받지 못한다.**
> - **2026-08-09 Maxi 확정 — A안(알림만)으로 좁게.** `IssueAssigned` 만 추가하고 자동 워처 승격은 별건. 클론 담당자는 issue.transitioned·commented·due_soon 은 ASSIGNEE 역할로 이미 받는다(V401 시드).
> - **★함정.** `cloneIssue` 스코프에 `sourceKey` 와 `saved.key` 가 공존한다. `sourceKey` 를 쓰면 **원본 담당자에게 잘못 알림이 가는 더 나쁜 결함**이 된다. 테스트에서 issueKey 값을 반드시 클론 키로 단언할 것.
> - **미등재 형제 결함 발견.** `changeComponents` 자동배정(`IssueApplicationService.kt:880-913`, `:901` `repo.setAssignee`)이 **정확히 같은 양식**이다 — 아래 신규 항목으로 등재했다.

---

## ⬜ issue-tracking — Import 가 **원본에 없던 담당자**를 만든다 (선재 · 미착수)

**무엇.** `IssueImportAdapter` 는 `createIssue` 에 담당자를 넘기지 않는다(`:537-544`).
그러면 `resolveDefaultAssignee` 가 컴포넌트/프로젝트 리드를 담당자로 넣는다.
이후 `applyAssigneeIfPresent` 는 `resolution.assigneeId ?: return currentVersion`(`:599`) 이라
**원본에 담당자가 없으면 그냥 반환**한다 → 자동 배정 담당자가 그대로 남는다.

**결과.** 반입된 이슈가 원본에 없던 담당자를 갖는다. **반입 충실도(fidelity) 위반.**

**처방이 이미 있다.** FR-UX-09 B1 이 도입한 `AssigneeIntent.None`(명시 미할당)을
Import 가 넘기면 자동 배정이 꺼진다. 코드 1줄 수준이나 **FR-IM 스펙 확인이 선행**이다
(「원본에 담당자가 없으면 미할당이어야 한다」가 명시돼 있는지).

**착수 시 읽을 것.** `IssueImportAdapterTest` 의 `S8b` 테스트가 자동 배정 발동 픽스처를 이미 갖고 있다.

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (반증 실패 · 코드 봉합은 반쪽 아님을 확인).**
> - **★정정 1 (BC 가 틀렸다).** 결함 코드와 수정 대상은 전부 **issue-tracking** 모듈의 `IssueImportAdapter.kt` 다. search-export-import 모듈 소스에는 반입 후 담당자를 다루는 코드가 **0건**(command 조립까지만). 「한 PR = 한 BC」 규칙상 이 작업은 **issue-tracking BC** 다.
> - **정정 2 (선행 조사 완료).** 「FR-IM 스펙 확인이 선행」은 해소. `docs/specs/2026-07-02-fr-im-01-csv-json-import.md:13`(시나리오 4)·`:26`(FR7)이 **「미매칭 assignee → null」을 이미 명시**한다. 최소한 「컬럼은 있으나 매칭 실패」는 스펙 위반 확정이다. 「담당자 컬럼 자체가 없는 행」에 대해서만 스펙이 침묵한다.
> - **정정 3.** 「코드 1줄 수준」은 **프로덕션 코드에 한해서만** 참이다. `AssigneeIntent.None` 을 넘기면 기존 `S8b` 의 양성 대조군(`readAssigneeIdOf != null`, `:1130`)이 **반드시 실패**한다 — 테스트 개편이 동반된다.
> - **정정 4.** 「S8b 픽스처를 이미 갖고 있다」는 사실이나(`:1117 setProjectLead`) 그건 **재사용 대상이 아니라 수정 대상**이다. S8b 는 현재 동작(자동 배정이 붙는다)을 기대값으로 못박고 있다.
> - **TODOS 가 빠뜨린 사실.** 이미 존재하는 `S3 assigneeEmail 미매칭 - assigneeId가 null로 유지된다`(`:951-968`)가 정확히 옳은 단언을 하고 있으나 **픽스처에 리드가 없어 공허하게 통과 중**이다.
> - **2026-08-09 Maxi 확정 — ①균일 처리.** 컬럼 유무와 무관하게 항상 `AssigneeIntent.None`. 「반입본은 원본과 같아야 한다」를 단일 원칙으로 둔다. ②조건 분기는 매핑 마법사 UI 에서 「빈 칸」과 「칸 없음」을 구별할 수 없어 예측 불가능한 결과를 만든다.
> - **★부작용(반드시 기록).** 수정 후 import 경로는 `resolvedAssignee` 가 항상 null 이라 **D-5 `notifyAssignment` 게이트가 도달 불가**가 된다. 게이트 자체는 fail-safe 로 남기되, 유일한 비-공허 증인이 `IssueApplicationServiceCreateTest:481-507` 임을 코드 주석으로 못박을 것 — 아니면 「도달 불가 상태를 지키는 가짜 그린」이 된다.

---

## ⬜ issue-tracking — `componentIds` 가 OpenAPI 에서 required 로 표기된다 (선재 · 미착수)

**무엇.** `CreateIssueRequest.componentIds: List<UUID> = emptyList()` 는 기본값이 있는데도
springdoc 이 **Kotlin non-null 타입**이라 `required` 로 판정한다. 생성된 클라이언트가
`componentIds` 를 강제한다.

**어떻게 발견.** FR-UX-09 B1 의 `assigneeId`(`JsonNullable<UUID>`)에서 **같은 함정**이 재현돼
`OpenApiContractTest.C1b` 가 잡았다. `assigneeId` 는
`@field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)` 로 봉합했으나
`componentIds` 는 이 PR 범위 밖이라 그대로 뒀다.

**주의.** 기본값이 있는 non-null Kotlin 프로퍼티는 **전부 같은 함정**이다.
다른 DTO 에도 있는지 전수 조사가 필요하다(이번엔 `CreateIssueRequest` 만 봤다).

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` · 범위가 크게 과소 서술됐다.**
> - **현상 확정.** `CreateIssueRequest.required = [componentIds, projectKey, summary]` — componentIds 가 진짜 required 다.
> - **★전수 조사 실행 결과.** 「이번엔 CreateIssueRequest 만 봤다」로 남긴 조사를 실제로 돌렸다. 1차 측정 **5 DTO · 10 프로퍼티**, 반증 단계의 `@RequestBody` DTO **32종 전수** 재측정에서 **11 클래스 · 27 프로퍼티**. 즉 **1차 처방조차 17개를 빠뜨렸다** — 「손으로 열거하면 반드시 샌다」의 증거.
>   대표. `CreateIssueRequest.componentIds` · `ChangeComponentsRequest.componentIds` · `ChangeVersionsRequest.versionIds` · `CloneIssueRequest.includeAssignee` · `UpdateIssueRequest` 의 JsonNullable 6필드 · `CreateCustomFieldRequest` 의 `required`/`displayOrder`/`options` 등.
> - **★심각도가 뒤집힌다.** 제목이 지목한 `componentIds` 는 이 중 **가장 가벼운 축**이다. 진짜 문제는 `UpdateIssueRequest` 의 JsonNullable 6필드 — `JsonNullable` 은 「필드 부재 = 변경 없음」을 표현하려고 도입한 타입인데(ADR D-2) 스펙이 그 6개를 전부 필수로 문서화한다. **PATCH 계약 자체가 문서상 파손 상태다.**
> - **정정.** 「생성된 클라이언트가 componentIds 를 강제한다」는 **이 저장소 안에서는 성립하지 않는다** — OpenAPI 코드 생성기가 리포에 없고(grep 0건) `apps/web` 은 수작업 클라이언트다. 피해는 「외부 소비자/문서가 거짓말한다」로 정정.
> - **절대 건드리지 말 것.** `expectedVersion`(`ChangeComponentsRequest.kt:21` · `ChangeVersionsRequest.kt:22` · `UpdateIssueRequest`)은 `@field:NotNull` 이라 런타임에 진짜 필수다. 여기에 `NOT_REQUIRED` 를 붙이면 「스펙은 optional, 서버는 400」이라는 **반대 방향 거짓말**이 된다.
> - **★판별식 함정.** `com.bts.issue.application.{CreateIssue,UpdateIssue,CloneIssue}Request` 가 REST DTO 와 **동명**이다(`IssueApplicationRequests.kt:34,176,269`). 판별식을 simple-name 매칭으로 짜면 엉뚱한 application DTO 를 검사하고 **초록인 채 아무것도 안 지킨다.**
> - **2026-08-09 Maxi 확정 — 좁게(componentIds 1건)만 봉합하고 나머지는 신규 등재.** 아래 신규 항목 참조.

---

## ⬜ apps/web — 이슈 상세 담당자 셀렉터가 **검색 전에 사용자 전량**을 노출한다 (선재 · 미착수)

**무엇.** `useUsers(query)` 는 `enabled` 가드가 없어(`hooks/use-users.ts:16-22`) 빈 검색어에도
조회가 나가고 **전체 사용자 목록**을 돌려준다. 이슈 상세는 그 결과를 그대로 후보로 넘긴다
(`routes/issues.$key.tsx:246` → `:810` `users={users}`). `IssueAssigneeSelect:110` 의
`users.length > 0 &&` 은 「검색했는가」가 아니라 「목록이 비었는가」만 본다.

**결과.** 담당자 칸을 열자마자 **아무것도 검색하지 않았는데 사용자 목록이 펼쳐진다.**
사내 1,000명 규모에서는 첫 페이지가 이름으로 가득 찬다.

**어떻게 발견.** FR-UX-09 F2(#331) 의 **생성 폼에서 눈확인으로 먼저 잡혔다** — 데스크톱
스크린샷에 검색도 안 했는데 4명(김앨리스·bob·캐럴·데이브)이 떠 있었다. 생성 폼은
`debouncedQuery.trim() === '' ? [] : allCandidates` 로 그 PR 안에서 닫았고
(`components/issue/create/use-assignee-picker.ts`), **상세 화면은 그 PR 범위 밖이라 그대로 뒀다.**
게이트 2 코드리뷰에서 같은 구조임을 대조로 확정했다.

**처방.** 생성 폼과 같은 형태 — 검색어가 비면 후보를 넘기지 않는다. 두 화면이 같은 규칙을
갖게 되므로 `IssueAssigneeSelect` 안으로 내리는 것도 후보다(그러면 미래의 세 번째 소비처도
자동으로 닫힌다). **단, 상세 화면은 `canEdit=false` 경로와 `useUsersByIds` 표시 경로가
얽혀 있으니 그 둘을 건드리지 않는지 확인할 것.**

**착수 시 읽을 것.** `use-assignee-picker.ts` 의 동일 처방과 그 주석 ·
`IssueCreateForm.test.tsx` 의 「담당자 후보는 검색해야 나온다」 2건(그대로 상세용으로 복제 가능).

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (실브라우저 아닌 코드 대조로 재현 확인).**
> - **정정 1 (좌표 4건 전부 밀림).** `hooks/use-users.ts:16-22` → **`:23-29`** · `routes/issues.$key.tsx:246` → **`:304`** · `:810` → **`:1120`** · `IssueAssigneeSelect:110` → **`:125`**. 결함 서술 자체는 네 곳 모두 지금도 맞다.
> - **정정 2 (홉 하나 누락).** 상세 라우트는 `IssueAssigneeSelect` 로 직결되지 않고 **`IssueMetaPanel.tsx:329`** 를 경유한다.
> - **★정정 3.** 「미래의 세 번째 소비처」는 **미래가 아니다.** `components/issues/cells/AssigneeCell.tsx:250-253`(FR-UX-11 F9 · PR #338)이 이미 같은 결함으로 존재한다. TODOS 작성 이후 표면이 하나 늘었다. 추가로 `ComponentLeadSelect.tsx:102` · `ProjectLeadSelect.tsx:116` 도 동형.
> - **★정정 4 (처방이 틀렸다).** 「`IssueAssigneeSelect` 안으로 내리면 미래의 세 번째 소비처도 자동으로 닫힌다」는 **거짓**이다. 실제 세 번째 소비처 `AssigneeCell` 은 `IssueAssigneeSelect` 를 쓰지 않고 자체 `AssigneeCellEditor` 를 갖는다 — 컴포넌트 내부 가드로는 안 닫힌다. ⇒ **수정 지점은 라우트 층**(`issues.$key.tsx`).
> - **정정 5.** 「`canEdit=false` 경로와 `useUsersByIds` 표시 경로가 얽혀 있다」는 과장. `currentAssignee` 는 `issues.$key.tsx:312-315` 에서 독립적으로 오고 `canEdit` 은 `useIssuePermissions` 에서 온다. `users` 배열만 걸러도 둘 다 안 건드린다.
> - **★기존 초록이 결함을 지키고 있다.** `issues.$key.test.tsx` **T4-A3(:1383)·T4-A5(:1421)** 는 타이핑 없이 '김앨리스' 버튼을 기다린다(5/5 초록 실행 확인). 수정과 테스트 갱신을 같은 커밋에 묶지 않으면 「봉합이 스위트를 깼다」로 오진된다.
> - **★파일 경로 함정 (반증이 적발).** `routes/issues.$key.test.tsx` 와 `routes/__tests__/issues.$key.test.tsx` 가 **둘 다 실재**한다. T4-A1~A5 는 **전자**(`:1355~:1424`)에 있고 후자(645줄)에는 `T4-A`·`김앨리스` grep **0건**이다. 경로를 헷갈리면 엉뚱한 파일을 고친다.
> - **가드 공허 위험.** 후보가 애초에 비면 「검색 전 안 나온다」 단언은 조회 실패로도 통과한다 — **「검색하면 나온다」 짝 테스트 필수.**
> - **2026-08-09 Maxi 확정 — 이슈 상세만 좁게. 나머지 3곳은 신규 등재.**

---

## ⬜ 인프라 — 전체 스위트 실행에서 `pnpm test` 가 **간헐적으로 exit≠0** 이 된다 (미착수)

**무엇.** 유닛 테스트가 **전건 통과(8378/8378)인데 종료 코드가 0이 아닌** 실행이 섞인다.
vitest 가 `Errors 1` 로 보고하는 **unhandled rejection** 이 원인이고, 발생 지점은
`components/automation/AutomationYamlImportDialog.test.tsx` 를 도는 동안의
`Mutation.execute` 다(`AutomationYamlImportDialog.tsx:340·345` 부근의 mutation 콜백).

**재현율.** 2026-08-02 실측 — 같은 커밋에서 **2회 중 1회**. 해당 파일 **단독 실행은 항상 초록**
(exit 0). `origin/main` 1회 실행은 초록이었다. ⇒ 파일 간 실행 간섭에 의한 **간헐**이며
특정 PR 귀책이 아니다(`[[flaky-determination-needs-repeat-not-single-contrast]]` 적용).

**결과.** **CI 가 무작위로 빨간불이 된다.** 더 나쁜 것은 진단 표면 —
「Tests 8378 passed」만 읽고 초록으로 보고하면 종료 코드 실패를 놓친다.
실제로 FR-UX-09 F2 세션 체크포인트가 **그렇게 기록돼 있었고** 게이트 2 재검증에서 교정됐다.

**처방 방향.** mutation 의 rejection 이 테스트 종료 후 도착하는 것이라, 테스트가
`unmount`/`queryClient.clear()` 없이 끝나 pending mutation 이 남는 경로를 찾는 것이 먼저다.
`AutomationYamlImportDialog.test.tsx` 의 에러 케이스에서 `await` 누락 여부를 본다.

**착수 시 읽을 것.** `[[lint-fails-first-leaves-stale-test-xml]]` ·
`[[github-actions-billing-block-steps-zero]]` — 둘 다 **「통과 건수 ≠ 종료 코드」** 같은 양식이다.
판별식으로 굳힐 거면 CI 가 `Errors` 줄을 별도로 낚아채게 하는 쪽이 싸다.

> **★2026-08-09 전수 실측 — 판정 `VALID` 이나 「고쳤다」로 닫을 근거가 없다. 항목을 ⬜ 로 유지한다.**
> - **★재현율 무효.** 「2026-08-02 2회 중 1회」는 오늘 기준 틀렸다 — **2026-08-09 전체 스위트 5회 실행에서 0회 재현**(그중 4회는 파이프 없이 종료 코드를 직접 확인해 EXIT=0). 미재현은 부재 증명이 아니다(p=0.5 라면 4연속 초록 확률 6.25%).
> - **기준선 이동.** 「8378/8378」 → 오늘 **574 파일 / 9286 테스트**. 스위트가 900건 이상 커져 워커 경합 조건 자체가 달라졌다. 과거 재현율을 현재 재현율로 인용하지 말 것.
> - **좌표 정정.** `AutomationYamlImportDialog.tsx:340·345` → 현재 `onSuccess` **337**, `onError` **343**. 파일은 #292 이후 무변경이므로 원 서술이 애초에 부정확했다.
> - **★인과가 성립하지 않는다 (가장 중요).** 「mutation 의 rejection 이 테스트 종료 후 도착」만으로는 unhandled rejection 이 **되지 않는다** — `useMutation.js:33` 이 `observer.mutate(...).catch(noop)` 을 붙인다. 전역으로 새는 경로는 `mutation.js:156/166/178/189` · `mutationObserver.js:94/105/116/127` 의 `void Promise.reject(e)`, 즉 **`onSuccess`/`onError`/`onSettled` 콜백이 던질 때**와 **`.catch` 없이 호출된 `mutateAsync`** 뿐이다. ⇒ 「pending mutation 이 남는 경로」는 필요조건일 뿐 충분조건이 아니다.
> - **정정.** 「CI 가 `Errors` 줄을 낚아채게 하는 쪽이 싸다」는 **불필요**하다. vitest 가 `cli-api…:13897-13899` 에서 이미 `process.exitCode=1` 을 세우고 `frontend-ci.yml:106-107` 이 그 종료 코드를 그대로 잡 성패로 쓴다. **실제 구멍은 CI 배선이 아니라 「Tests N passed 만 읽고 초록으로 보고하는」 사람/에이전트 쪽**이다.
> - **2026-08-09 Maxi 확정 원칙 적용 — (b) 관측 유지.** 이번 라운드의 산출물은 「고침」이 아니라 **「다음 발생 때 테스트 이름 + 전체 스택이 로그에 남는다」**로 정의한다.
>   ① `src/test/setup.ts` 에 `unhandledRejection` 로깅 리스너 추가(try/catch 로 감싸 리스너 자신이 새 실패면이 되지 않게).
>   ② 확정 누수 2건 봉합 — `AutomationYamlImportDialog.test.tsx:136-154`(EC5)·`:367-389`(CRITICAL-1)이 50ms·300ms 지연 핸들러를 건 채 정착을 기다리지 않고 끝난다. **기존 단언은 그대로 두고 테스트 끝에만 정착 대기를 덧붙인다** — 대기를 앞에 끼우면 「in-flight 창」을 보는 원 의도가 죽어 「엉뚱한 걸 쟀다」가 재발한다.
>   ③ 같은 파일에 pending mutation 0 불변식 `afterEach` 추가.
>   ④ **절대 금지 — `dangerouslyIgnoreUnhandledErrors: true`.** 종료 코드는 초록이 되지만 유일한 진단 표면이 사라진다(「봉인이 자기 결함을 재생산」 양식).
> - **미승계.** 지연 MSW 핸들러를 쓰는 테스트 파일이 **34개**이고 그중 몇 개가 같은 누수를 갖는지는 미측정이다. 전면 승계는 별건.

---

## ⬜ apps/web — **required MULTI_SELECT** 커스텀 필드가 클라이언트 검증을 그냥 통과한다 (선재 · 미착수)

**무엇.** `isRequiredFieldEmpty` 의 MULTI_SELECT 분기가
`return Array.isArray(raw) && raw.length === 0` 다(`components/issue/IssueCreateForm.tsx:63-64`).
사용자가 그 필드를 **한 번도 건드리지 않으면** 값은 `undefined` 이고
`Array.isArray(undefined)` 는 `false` 라 **「빈값 아님」으로 판정**된다.
빈 배열(`[]`)만 잡고 **미입력(`undefined`)은 못 잡는다.**

**결과.** **데이터 무결성은 안전하다** — 백엔드 `CustomFieldValueValidator.checkRequiredFields`
가 `value == null` 을 거부한다(`customfield/domain/CustomFieldValueValidator.kt:79-92`).
문제는 **사용자에게 보이는 것**이다. 폼 안 필드 옆의 친절한 경고
(`custom-fields-required-error`) 대신 서버 왕복 후 **일반 에러**가 뜬다.
게다가 `resolveCreateErrorMessage` 는 `PROJECT_NOT_FOUND`·`ASSIGNEE_NOT_FOUND` 만 매핑하므로
`errorDefault` 인 **「이슈 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.」** 가 뜬다 —
**재시도해도 안 되는데 재시도를 권하는 문구다.**

**어떻게 발견.** FR-UX-09 F2(#331) 게이트 2 코드리뷰. `origin/main` 의
`routes/issues.new.tsx` 와 **로직이 완전히 동일**(F2 는 파일만 옮겼다) ⇒ 선재.

**처방.** `return raw === undefined || raw === null || (Array.isArray(raw) && raw.length === 0)`.
**같은 PR 에서 에러 코드 매핑도 함께 볼 것** — 커스텀 필드 검증 실패의 errorCode 를
`resolveCreateErrorMessage` 에 얹지 않으면 다른 경로에서 같은 오해가 반복된다.

**착수 시 읽을 것.** 스펙 E-3(required 빈값 1차 클라 검사) ·
`IssueCreateForm.test.tsx` 의 커스텀 필드 required 테스트(현재 MULTI_SELECT 케이스 부재).

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (인용 20건 좌표 밀림 0).**
> - **★정정 1 (착수 안내가 틀렸다).** 「`IssueCreateForm.test.tsx` 의 커스텀 필드 required 테스트(MULTI_SELECT 케이스 부재)」는 거짓 — 그 파일에 커스텀 필드 테스트가 **한 건도 없다**(459줄 전수 grep 0건). required 테스트가 실제로 있는 곳은 `routes/issues.new.test.tsx:369`(T9-C4-1)·`:418`(T9-C4-2)이고 둘 다 SHORT_TEXT 픽스처만 쓴다. **「테스트가 깨졌다」가 아니라 「테스트가 없다」 양식.**
> - **★정정 2 (사본 2곳).** 동일 함수의 두 번째 사본이 `components/issue/meta/IssueCustomFieldsEdit.tsx:31-52` 에 있고 MULTI_SELECT 분기가 글자 단위로 같다. TODOS 처방(한 줄 교체)을 그대로 적용하면 **편집 화면은 그대로 남는 반쪽 봉합**이 된다.
> - **정정 3.** `case 'CHECKBOX': return false` 도 동형 결함이다. `CheckboxWidget` 이 마운트 시 `onChange` 를 안 쏘므로 required CHECKBOX 를 안 건드리면 `undefined` 로 제출된다.
> - **2026-08-09 Maxi 확정 — MULTI_SELECT + CHECKBOX 둘 다 막는다. 편집 화면 사본 통합은 범위 밖(신규 등재).**
> - **★★구현 시 절대 주의 (반증이 적발한 치명 구멍).** 「선판정 뒤집기(`undefined|null` → 빈값)」를 CHECKBOX 에 **그대로 적용하면 사용자를 데드락에 가둔다.** `undefined → 빈값(true)` · `false → 유효` 가 되어, required 체크박스를 해제 상태로 두려는 사용자는 **체크했다 해제하는 2회 조작 없이는 제출할 수 없다.** 화면상 두 상태는 픽셀 단위로 동일한데 한쪽만 경고가 뜬다. 게다가 백엔드(`CustomFieldValueValidator.kt:88`)는 `value == null` 만 거부하므로 **프론트가 백엔드보다 엄격해진다.**
>   ⇒ **채택한 처방 (2026-08-09 구현).** `CHECKBOX: raw !== true` — 「필수 체크박스는 체크해야 제출 가능」. 화면상 해제된 두 상태(첫 방문 / 토글 왕복)가 **같게** 판정되므로 데드락이 없다. 백엔드(`value == null` 만 거부)보다 엄격한 것은 의도다 — 필수 체크박스의 실제 용도가 약관 동의류다.
>   ⇒ **기각한 대안 — 「마운트 시 `onChange(false)` 1회 발화」.** 언뜻 백엔드 의미와 정렬돼 보이지만 **편집 경로에 새 결함을 연다** (게이트2 리뷰 적발). 사용자가 만지지도 않은 **optional** CHECKBOX 가 `draft` 에 키를 만들어 `buildNormalizedPatch` 의 `if (!(key in draft)) continue` 를 통과하고, 텍스트 필드 하나만 고쳐도 `null → false` 로 함께 덮어쓴다. 굳이 쓰려면 `field.required === true` 로 한정해야 한다.
> - **잔여 422 경로(범위 밖·신규 등재).** `IssueCustomFieldsEdit.tsx:101` 은 `visibleFieldDefs`(FR-PM-07 숨김 제외) 기준으로 검증하는데 백엔드 `mergeCustomFieldsAndValidate`(`IssueApplicationService.kt:1508-1511`)는 **활성 정의 전량** 기준이다. 사용자에게 restricted 인 required 필드가 비어 있으면 **화면에 없는 필드 때문에 영원히 저장 실패**한다.
> - **고아 헤더.** `apps/web/e2e/custom-fields.spec.ts:365` 에 「S6 — required 필드 미입력 시 422」 **헤더만 있고 테스트가 없다.** 다음 세션이 「이미 커버됨」으로 오독하므로 헤더를 지우거나 채울 것.

---

## ⬜ apps/web — 이슈 **제목** placeholder 만 i18n 키 없이 하드코딩돼 있다 (선재 · 미착수)

**무엇.** `placeholder="이슈 제목을 입력하세요"` 가 리터럴이다
(`components/issue/create/IssueCreateBasicFields.tsx:110`).
같은 폼의 **본문** placeholder 는 `issueCreateStrings.descriptionPlaceholder` 를 쓴다 —
**한 폼 안에서 두 필드의 처리가 다르다.**

**결과.** 지금 당장 깨지는 것은 없다(문구가 한국어 하나뿐). 비용은 **비대칭**이다 —
문구를 바꿀 때 한쪽은 `i18n/ko.ts`, 한쪽은 컴포넌트를 고쳐야 하고,
다국어를 열 때 이 한 줄만 조용히 번역에서 빠진다.

**어떻게 발견.** FR-UX-09 F2(#331) 게이트 2 코드리뷰(체크리스트 Pass 2 「사용자 노출 문자열의
i18n 키 누락」). `origin/main` 의 `routes/issues.new.tsx:243` 에 같은 리터럴이 있었다 ⇒ 선재.
**같은 PR 이 `descriptionPlaceholder` 는 i18n 에 새로 넣으면서 이 줄은 그대로 옮겼다** —
비대칭이 그때 굳었다.

**처방.** `issueCreateStrings.summaryPlaceholder` 신설 후 참조. 한 줄짜리다.

**착수 시 확인.** 이 리터럴을 참조하는 테스트/E2E 셀렉터가 있는지
(`grep -rn "이슈 제목을 입력하세요" apps/web/`) — 현재는 이 1건뿐이다.

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` (좌표 밀림 0 · `:110` 지금도 정확).**
> - **★정정 1.** 「다국어를 열 때 **이 한 줄만** 조용히 번역에서 빠진다」는 **거짓**이다. `apps/web/src` 의 비-테스트 `.tsx` 에 한글 리터럴 placeholder 가 **28곳** 있다(`CreateBoardForm.tsx:109` · `DashboardForm.tsx:294` · `admin.workflow-schemes.new.tsx:123` · `CustomFieldFormDialog.tsx:222` · `search.tsx:353` 등). `ComponentMultiSelect.tsx:78` 의 반쪽 i18n 까지 더하면 29곳. 원 서술은 **「그 특정 문자열의 grep 히트 수」로는 맞지만** 「i18n 에서 빠지는 줄이 이것뿐」이라는 뜻으로 읽히면 틀린다.
> - **정정 2.** 「같은 폼의 **본문** placeholder 는 i18n 을 쓴다」는 과소 서술. 본문뿐 아니라 **프로젝트 셀렉터**도 `issueCreateStrings.projectPlaceholder`(`:62`)를 쓴다 — 이 파일의 placeholder 3개 중 **2개가 i18n, 1개만 리터럴**이다.
> - **2026-08-09 Maxi 확정 — 이 1줄만 좁게. 나머지 28곳은 신규 등재.**
> - **★★회귀 가드 설계 주의 (반증이 적발).** 「`issueCreateStrings.summaryPlaceholder` 자체와 대조하는 렌더 테스트」는 **공허하다.** i18n 값이 현재 리터럴과 바이트 동일해야 하므로 DOM 의 `placeholder` 속성 문자열이 두 경우(i18n 참조 / 하드코딩 복귀)에 완전히 같다 — **속성값은 출처를 싣지 않는다.** 그 가드가 실제로 잡는 것은 (a)속성 삭제 (b)다른 키 교체 둘뿐이고, **막겠다고 선언한 「하드코딩 복귀」는 못 잡는다.**
>   ⇒ **올바른 가드.** `apps/web/eslint.config.js:69·116` 에 이미 있는 `no-restricted-syntax` AST 선택자 배열에 한 항목을 더한다 — `JSXAttribute[name.name='placeholder'] Literal[value=/[가-힣]/]`. 현재 히트를 예외 파일 목록으로 등재한 뒤 이번 PR 에서 `IssueCreateBasicFields.tsx` 만 목록에서 뺀다(**래칫**). 문법을 보므로 출처 판별이 성립하고, 29번째 신규 하드코딩도 자동 차단된다.
>   미확정 2건 — ① `ComponentMultiSelect.tsx:78` 은 템플릿 리터럴이라 `Literal` 선택자에 안 걸린다(`TemplateElement[value.raw=/[가-힣]/]` 병용 여부 판단 필요). ② 내가 센 28 은 grep 기준이라 **ESLint 히트와 일치한다는 보장이 없다** — 규칙을 한 번 돌려 실제 목록을 확정한 뒤 등재할 것. 로컬 lint 목록과 CI lint 목록이 어긋난 선례 있음(`[[fr-ux-14-b2-card-fields-done]]`).
> - **중복 추적 주의.** 같은 부류가 이미 `docs/plan/product/personalization.md:479-483` ⑤(「라벨 5종이 컴포넌트 모듈 잔류」)로 **따로 추적 중**이다. 신규 TODOS 항목을 또 만들면 같은 부류가 3곳에 흩어져 `[[two-lists-never-check-each-other]]` 를 새로 만든다 — 아래 신규 항목에서 그 문서를 상호 링크했다.

---

## ⬜ apps/web — 상단바 「만들기」 버튼만 **CREATE 권한 게이트가 없다** (선재 · 미착수)

**무엇.** 이슈 생성 진입점 5곳 중 상단바 하나만 권한을 안 본다.

| 진입점 | 게이트 | 근거 |
|---|---|---|
| 이슈 목록 「새 이슈」 | ✅ fail-closed | `routes/issues.index.tsx:390-410` `NewIssueButton` |
| 백로그 칸 · 스프린트 칸 · 보드 헤더 | ✅ fail-closed | `CreateIssueEntryButton`(FR-UX-09 F3, PR #333) |
| **상단바 「만들기」** | ❌ **없음** | `components/layout/TopBar.tsx:80-89` — `onClick` 이 무조건 모달을 연다 |

**결과.** CREATE 권한이 없는 사용자도 상단바로 생성 모달을 열고 폼을 다 채운 뒤
**제출에서야 서버 거부**를 만난다. 다른 4곳은 버튼이 비활성이라 애초에 못 연다.
**데이터는 안전하다**(백엔드가 거부한다) — 깨지는 것은 일관성과 헛수고다.

**어떻게 발견.** FR-UX-09 F3(#333) 스펙 단계에서 **진입점 5곳의 게이트를 전수 대조**하다가
상단바만 비어 있는 것을 확인했다. F2(#331)가 상단바 진입점을 만들 때 이슈 목록의
선례를 따르지 않은 것이 원인이다. F3 은 **새 진입점 3곳만** 닫고 상단바는 범위 밖으로 뒀다.

**처방.** `TopBar` 는 프로젝트 스코프가 아니라 전역이라 `useProjectPermissions(projectKey)` 를
그대로 쓸 수 없다 — **활성 프로젝트 기준으로 볼지, 어느 프로젝트든 CREATE 가 하나라도 있으면
열지**를 먼저 정해야 한다. 후자가 맞다면 신규 API 가 필요할 수 있으므로 **범위를 먼저 확정할 것.**

**착수 시 읽을 것.** `components/issue/CreateIssueEntryButton.tsx`(fail-closed 판정 단일 시험대) ·
`routes/issues.index.tsx:379-410`(선례) · `hooks/use-active-project.ts`.

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` 이나 표제가 결함을 과소 서술한다.**
> - **정정 1 (좌표 3건).** `TopBar.tsx:80-89` → **`:137-146`** · `routes/issues.index.tsx:390-410` → **`:425-452`**(판정식은 `:526`) · 「착수 시 읽을 것」의 `:379-410` → **`:406-452`**.
> - **★정정 2 (핵심 서술이 거짓).** 「진입점 5곳 중 상단바 **하나만** 권한을 안 본다」는 **거짓**이다. `/issues/new` 라우트 자체가 무게이트(`routes/issues.new.tsx:32-64`)이고, 게이트된 버튼을 전혀 거치지 않는 도달 경로가 3개 더 있다 — **`c` 단축키**(`shortcuts.ts:88`) · **명령 팔레트 `/issue`**(`CommandPalette.tsx:200`) · **북마크/직접 URL**. 상단바만 막으면 무게이트 경로가 3개 남는다.
> - **정정 3.** 「다른 4곳은 버튼이 비활성이라 애초에 못 연다」는 **버튼에 한해서만** 참이다. 같은 화면에서 `c` 를 누르면 비활성 버튼과 무관하게 모달이 열린다.
> - **★정정 4 (구조 문제).** 모달의 프로젝트 셀렉터가 접근 가능한 프로젝트 **전부**를 필터 없이 나열하고(`IssueCreateBasicFields.tsx:56-68`) 폼에는 선택된 프로젝트의 CREATE 검사가 없다. ⇒ **어느 버튼 게이트를 골라도** 「게이트 통과 후 폼 안에서 무권한 프로젝트로 갈아타기」가 남는다. 게이트를 버튼에 둔 **구조 자체의 문제**다.
> - **정정 5.** 「신규 API 가 필요할 수 있으므로」 → 「어느 프로젝트든 CREATE 하나라도」 안을 고르면 **필요하다로 확정**(현재 API 는 projectKey 필수, 없으면 400).
> - **2026-08-09 Maxi 확정 — 폼(선택된 프로젝트) 기준 게이트.** 모든 진입 경로가 `IssueCreateForm` 하나를 지나므로(`CreateIssueDialog.tsx:99` 가 유일 사용처, 소비처 4곳 = TopBar:150 · issues.new:56 · BacklogBoard:489 · board:435) 여기 한 곳이 무게이트 경로 4개 + 프로젝트 갈아타기를 동시에 닫는다. 신규 백엔드 작업 0.
> - **★★구현 시 절대 주의 (반증이 적발한 치명 구멍 2건).**
>   ① **판정식을 그대로 복사하면 「거짓말하는 가드」가 된다.** `issues.index.tsx:526` 의 `!isPermLoading && permData?.permissions.CREATE === true` 는 시각적으로는 버튼을 회색으로 만들 뿐이다.
>   **★단 「아무 주장도 안 한다」는 거짓이다 (게이트2 리뷰).** 같은 컴포넌트 `:447` 이 `aria-label="새 이슈 (권한 없음)"` 로 **이미 사실 주장을 하고 있어**, 권한 조회 중이거나 조회 실패 구간에서 보조기술 사용자에게 **거짓 안내**가 나간다. 목록 화면도 같은 `=== false` 처방의 적용 대상이다. 같은 식을 `setServerError('권한이 없습니다')` 에 물리면 **사실 주장**이 되어, 권한 조회 중(`isPermLoading=true`)에 Enter 를 치면 **거짓 문구**를 본다. 프로젝트를 바꿀 때마다 queryKey 가 바뀌어 **오탐이 반복 재발**한다.
>   ② **쿼리 실패 시 영구 오탐 거부.** `use-project-permissions.ts:31-38` 에 `retry:false` 도 에러 폴백도 없다. 500/네트워크 단절이면 `permData=undefined` 로 안착해 **CREATE 를 실제로 가진 사용자를 영구 차단**한다.
>   ⇒ **최소 수정.** `!isPermLoading && … === true`(미지=거부) 대신 **`permData?.permissions.CREATE === false`(명시 거부만 차단)**. 토큰 하나 차이인데 실패 모드가 정반대이고 ①②가 동시에 닫힌다.
> - **★더 싼 기준선을 먼저 깔 것.** 실제 피해(「다 채우고 제출에서야 거부」)가 아픈 이유는 `IssueCreateForm.tsx:80-95` 의 `resolveCreateErrorMessage` 가 **403 을 매핑하지 않아** `ko.ts:755` 의 「잠시 후 다시 시도해 주세요」로 떨어져 **사용자가 권한 문제를 일시 장애로 오인해 재시도**하기 때문이다. 403/ACCESS_DENIED 분기 **3줄**이 ①신규 네트워크 호출 0 ②오탐 거부 0 ③모든 진입 경로 자동 커버 를 달성한다. 사전 게이트는 그 위의 선택적 개선이다.
> - **범위 밖 명시.** 이슈를 만드는 서버 경로가 셋 더 있고 어느 것도 `IssueCreateForm` 을 지나지 않는다 — 클론(`IssueApplicationService.kt:355`) · 이동(`IssueMoveService.kt:190`) · 임포트(`IssueImportAdapter.kt:288`). 특히 **클론은 결함이 동일한데 코드에 「의도적」이라고 박혀 있고**(`IssueMetaPanel.tsx:437-441`) 그 정당화 주석(「프론트 권한 API 가 CREATE 를 안 줘서 불가능」)은 `project-permissions.ts:24-34` 가 `CREATE: z.boolean()` 을 내주는 지금 **거짓**이다 — 신규 등재.

---

## ⬜ apps/web(테스트 인프라) — MSW 리졸버가 **요청 1회에 2번 실행**된다 (선재 · 미착수)

**무엇.** jsdom 환경에서 `fetch` 한 번에 MSW 핸들러가 **두 번** 돈다.
2026-08-03 계측 — `server.events.on('request:start')` 가 POST 1건에 **2회** 발화하고,
같은 요청으로 백로그 저장소 길이가 **2 → 4** 로 늘었다(한 번의 생성이 두 줄을 만들었다).

**결과.** **상태를 누적(append)하는 목 핸들러가 전부 조용히 중복된다.**
값을 덮어쓰거나 이동시키는 핸들러는 멱등이라 증상이 안 보이고, **추가하는 핸들러만** 드러난다.
그래서 오래 잠복하기 쉽다.

**지금까지 확인된 것.** FR-UX-09 F3(#333)이 추가한 `appendCreatedIssueToBacklog` 가 이 함정에
걸렸고, **키 중복 금지 불변식**으로 그 경로만 닫았다(`mocks/backlog-fixtures.ts`).
불변식 자체는 우회가 아니라 올바른 의미다 — 실제 백로그도 같은 키를 두 번 담지 않는다.

**처방.** (1) 근본 원인을 먼저 규명한다 — 인터셉터 이중 등록인지, jsdom `fetch` 폴리필이
`http` 계층과 겹치는지. (2) 그 전까지는 **append 형 핸들러 전수**를 찾아 각자 불변식을 갖게 한다.
🛑 「호출됐다」만 보는 상위 테스트는 이 결함을 못 본다 — **상태 변화로 단언**해야 드러난다.

**착수 시 읽을 것.** `mocks/backlog-fixtures.ts` 의 `appendCreatedIssueToBacklog` 주석 ·
`mocks/create-issue-backlog-sync.test.ts`(목 계약 테스트 + 「201 만 보면 가짜 그린」 경고 테스트).

> **★2026-08-09 근본 원인 확정 — A/B 실측으로 두 가설을 갈랐다. 판정 `VALID`.**
>
> | 조건 | resolver 호출 | `request:start` | 고유 requestId |
> |---|---|---|---|
> | 전역 `server` 단독 | **1** | 1 | 1 |
> | 로컬 `setupServer` + 전역 공존 | **2** | 2 | **1** |
>
> `uniqueIds=1` 이 결정적이다 — **요청이 두 번 나간 게 아니라 같은 요청이 두 번 디스패치**된다.
> - **★정정 1.** 「jsdom 환경에서 fetch 한 번에 MSW 핸들러가 두 번 돈다」는 **과일반화**다. jsdom 은 필요조건일 뿐이고 진짜 트리거는 **`setupServer` 인스턴스 2개 동시 listen** 이다.
> - **★정정 2 (처방이 낡았다).** 「근본 원인을 먼저 규명한다 — 인터셉터 이중 등록인지, jsdom fetch 폴리필이 http 계층과 겹치는지」는 이미 해소돼 있었다. `import-handlers.test.ts` 주석(2026-07-03) · `profile-handlers.test.ts` 주석(2026-07-06) · 메모리 `[[msw-dual-setupserver-double-dispatch]]` 에 **처방까지 적혀 있다.** 2026-08-03 계측은 재발견이다.
> - **★정정 3 (범위).** 로컬 `setupServer(` 를 만드는 테스트 파일이 **60개** (2026-08-09 실측 · `src/test/server.ts` 제외), 「하지 마라」 주석을 단 파일은 **7개**, 이를 **강제하는 린트 룰·판별식은 0개**. `[[two-lists-never-check-each-other]]` 양식 그대로다.
> - **정정 4.** 「지금까지 확인된 것」에 F3 의 백로그 경로 1건만 적혀 있으나 실제 보상 가드는 **2곳**이다 — `automation-execution-handlers.ts` 의 `replayExecutionHandler` requestId 단발 캐시가 두 번째.
> - **★정정 5.** `automation-execution-handlers.ts:15-17` 이 기록한 「본문을 읽는 핸들러는 두 번째 호출이 `Body already read` 로 자동 실패해 무해하다」는 **신뢰할 수 없다.** `issue-handlers.ts:484` 는 `request.clone().json()` 으로 읽는데도 append 가 2회 났다. **「본문을 읽으니 안전」을 전수 조사의 제외 기준으로 쓰지 말 것.**
> - **정정 6.** 배증 폭은 핸들러마다 다르다(합성 GET 은 2배, 실 `bulkOperationHandlers` GET 은 단일 dispatch 서명). **「모든 요청이 정확히 2배」로 가정하면 틀린다.**
> - **2026-08-09 Maxi 확정 원칙 적용 — B(동결)로 시작.** ①`apps/web/src/test/msw-single-setupserver.test.ts` 차집합 판별식 신설(허용목록 `src/test/server.ts` + 현재 59개 baseline) → **신규 로컬 `setupServer` 만 차단**. 비-공허 짝 필수(훑은 파일 수 400+ 이고 스캐너가 `src/test/server.ts` 를 실제로 찾았음을 같은 테스트에서 단언 — 글롭이 깨지면 0건 훑고 공허 통과). ②전면 이주(A)는 신규 항목으로 분할 등재.
> - **★★이주 시 최대 함정 (반증이 적발).** 로컬 서버가 뜨면 **전역 핸들러가 통째로 죽는다**(실측: 전역 `server.use()` 도 `/auth/refresh` 도 `fetch failed`). 이주하면 그 59개 파일에서 **`/auth/refresh` 가 처음으로 살아나** `apiFetch` 의 401 자동 재시도가 지금은 실패하던 자리에서 성공한다 — **401/403 을 단언하는 테스트의 결과가 뒤집힌다.** 「기계적 치환」이 아니다.
> - **이주 시 함정 2.** 전역 `setup.ts:21` 의 `afterEach(server.resetHandlers())` 때문에 핸들러 등록은 **반드시 `beforeEach`** 여야 한다. `beforeAll` 에 두면 첫 테스트 뒤 조용히 사라진다. 선례 3건 — `import-handlers.test.ts:13-22` · `profile-handlers.test.ts:8-18` · `status-handlers.test.ts:8-16`.
> - **이주 시 함정 3.** 지금은 두 서버 중 한쪽이 매치하면 넘어가던 요청이 단일 서버가 되면 `onUnhandledRequest: 'error'` 에 그대로 걸린다 — **이주 중 대량 RED 를 전제**할 것.
> - **걷어낼 것 / 남길 것.** 이주 완료 후 `automation-execution-handlers.ts:117-119, 228-229, 260-261` 의 단발 캐시는 제거 후보. 단 `backlog-fixtures.ts:165` 의 키 중복 금지 가드는 **남긴다** — 그건 우회가 아니라 실제 백로그의 도메인 불변식이고, 제거하면 판별자만 사라진다(`[[seal-blinds-existing-guard]]`).
> - **★증거의 소재 (게이트2 리뷰 지적).** 위 A/B 수치는 **일회성 진단 파일**(`apps/web/src/test/__tmp-probe-*.test.ts`)로 얻었고 그 파일은 **저장소에 커밋되지 않았다.** 재검증하려면 동일 조건(로컬 `setupServer` + 전역 공존)을 다시 만들어야 한다.
>   정식 테스트로 승격하지 않은 이유 — 승격하려면 테스트가 스스로 이중 등록 상태를 만들어야 하는데 그것이 곧 판별식이 금지하는 상태라 자기모순이 된다. 대신 판별식(`msw-single-setupserver.test.ts`) 주석에 수치를 표로 남겼다.

## ⬜ 인프라 — CI 벽시계가 실제 실행의 10배다 (러너 1대 직렬 + 자원 경쟁 · 후속 3건 · 착수)

**증상.** PR 하나의 검증이 1~2시간이다. 그래서 결과가 나오기 전에 다음 커밋이 올라가고,
최근 `backend-ci` 30건 중 **9건이 cancelled** 다 — 검증이 실질적으로 무의미해지는 구간이다.

**두 축이 겹쳐 있다. 실측(2026-08-07).**

| 워크플로우 | 벽시계 | 실제 실행 합계 | 대기 |
|---|---|---|---|
| frontend-ci (run 31144206854) | 108m47s | **10m46s** | 98m |
| workflow-scripts-ci (run 31144206870) | 42m36s | **1m45s** | 41m |
| backend-ci (run 31139616352) | 85m57s | 59m1s | 27m |

**축 A — 러너 1대(`maxi-mac-bts`) 직렬.** `backend-ci` 만 잡 12개(BC 매트릭스 9 + assembly +
lint + runner-health)이고 전부 한 대를 두고 줄을 선다. 증설은 Maxi 보류 —
8코어·16GB 에서 Gradle 2벌이 CPU·메모리를 다투고, `backend-ci.yml:146` 이 예고한
assembly 컨테이너명·55433 포트 충돌을 함께 닫아야 한다.

**축 B — 자원 경쟁으로 실행 시간 자체가 2.4배.** 정본 메모리 [[ci-slowdown-is-runner-memory-not-code]].
동일 커밋 `gh run rerun --job` A/B 로 확증했다.

| 모듈 | 8/1 (정상, 4회) | 8/7 (swap 15,014M · load 34.43) | 정리 후 (swap 3,556M · load 7.72) |
|---|---|---|---|
| issue-tracking | 527 · 560 · 570 · 580s | **1,380s (2.44×)** | **554s (0.98×)** |
| identity-access | 419 · 419 · 420 · 427s | **739s (1.76×)** | **384s (0.91×)** |

테스트 케이스 수는 3,247 → 3,247 로 **증감 0**. 스위트 307개 중 1초 이상 24개의 감속 배수는
중앙값 **2.05×**, "거의 그대로"는 1개뿐 — **전 스위트 균등 감속**이 「코드가 아니라 머신」의 서명이다.
DB·Docker 무관한 `IssueBcArchTest` 조차 9s→27s 였다. 주범은 Chrome ~10.4GB + Docker VM 8.1GB
(그중 6.6GB 유휴)로 물리 16GB 에 24GB 를 얹은 상태였다.

**✅ 이 PR(#348)이 닫은 것 — 축 B 의 「조용함」.**
`runner-health.yml` + `scripts/verify-runner-health.sh` 에 자원 고갈 판정을 **경고 전용**으로
넣었다. 이제 고갈 상태에서 돈 run 은 요약 페이지와 어노테이션에 「이 run 의 소요 시간을 믿지
마라」를 남긴다. **차단하지 않는다** — 자원 회복은 사람이 하는데 막으면 회복 작업까지 멈춘다
(`run 31139123013` 교착과 동형).

**⬜ 남은 3건.** 순서 고정 — 앞 항목이 뒤 항목의 측정 기준선을 바꾼다.

| # | 무엇 | 근거 |
|---|---|---|
| 1 | **머지된 PR 의 좀비 run 차단** | 8/7 큐 8건 중 3건이 **이미 머지되고 브랜치까지 삭제된** PR #346 의 검증이었고, 나머지도 낡은 main 커밋이라 **현재 main 을 검증하는 run 이 0건**이었다 |
| 2 | **Gradle 설정** — 데몬·병렬·빌드캐시 | `backend/gradle.properties` 가 셋 다 off 다. 「wave 3 병렬 dispatch 안정성」 주석대로 **로컬 서브에이전트 충돌 방지용**인데 CI 에도 그대로 걸린다 — CI 는 잡이 러너를 독점하므로 그 전제가 없다 |
| 3 | **PR 은 변경된 모듈만 테스트** | 대부분의 PR 에서 59분 → 5~10분. 단 의존 그래프를 **Gradle 에서 직접 뽑아야** 한다 — 하드코딩하면 [[two-lists-never-check-each-other]] 양식이 재발한다 |

**★2 와 3 을 같이 넣을 때의 함정.** 빌드캐시는 「안 돌리고 UP-TO-DATE 통과」를, 모듈 선택은
「잡을 아예 안 만들기」를 만든다. 각각은 안전해도 겹치면 **두 겹으로 미검증인데 초록**이다.
서로를 검사하는 판별식을 짝으로 넣고, 무엇을 건너뛰었는지 run 요약에 남길 것.

**소관.** 인프라 / Maxi 결정(러너 증설 여부 · 검증 범위 축소 허용선).

> **★2026-08-09 전수 실측 + 적대적 반증 — 판정 `VALID` · 후속 3건 전부 미구현 확인.**
> - **수치 정정.** 「최근 `backend-ci` 30건 중 **9건** cancelled」 → 2026-08-09 실측 **11건/30**. 방향(취소 상시화)은 유지되나 수치는 이미 밀렸다. **착수 PR 에서 재측정할 것.**
> - **★정정 1 (항목 2 의 전제가 틀렸다).** 「`backend/gradle.properties` 가 셋 다 off」가 부정확하다. 명시적으로 `false` 인 셋은 **daemon · parallel · configureondemand** 이고, **`org.gradle.caching` 은 파일에도 저장소 어디에도 선언이 없다**(전수 grep 0건 — Gradle 기본값 off). 「세 줄을 true 로 뒤집으면 된다」로 착수하면 **빌드캐시는 못 켜고 configureondemand 만 잘못 건드린다.**
> - **정정 2 (근거 교체).** 「‘wave 3 병렬 dispatch 안정성’ 주석대로 로컬 서브에이전트 충돌 방지용」 — 파일 주석 원문은 `# Gradle JVM 메모리 설정 — wave 3 병렬 dispatch 안정성 + main 디버깅 옵션 통합` 이고 「로컬 서브에이전트 충돌 방지용」이라는 문구는 **없다**. 다만 결론은 `docs/runbooks/self-hosted-runner.md:84`(러너가 `~/.gradle` 을 로컬 개발과 공유)로 **별도 근거로 성립**한다 — 근거를 그쪽으로 갈아끼울 것.
> - **정정 3 (실측표 재검증 불가).** backend-ci 행(85m57s / 59m1s / 27m)은 지금 재검증할 수 없다. run 31139616352 이 축 B 확증을 위해 `gh run rerun --job` 으로 재실행돼 잡 attempt 가 덮였다.
> - **① 좀비 run 차단 — 미구현 확인.** `concurrency` grep 실측: backend-ci 1 · frontend-ci 1 · workflow-scripts-ci 1 · **infra-ci 0 · runner-health 0**.
> - **2026-08-09 Maxi 확정 — 3건을 고정 순서대로, 「PR 검증 범위 축소」는 보류.** 즉 ①좀비 run 차단 → ②Gradle 설정까지 진행하고 **③변경 모듈만 테스트는 착수하지 않는다.** 회귀가 머지 후 main 에서 처음 빨개지는 것을 받아들이지 않겠다는 결정이다.
> - **★★①(b) 처방은 실측이 반증한다 (반증이 적발).** 「`runner-health` 는 모든 워크플로우의 `needs:` 선행이라 여기서 취소하면 뒤따르는 20잡이 아예 안 뜬다」는 논거가 성립하지 않는다 — runner-health 는 run 의 **맨 앞**이라 run 생성 직후에 돌고, 나머지 잡은 그 뒤 **몇 시간에 걸쳐** 배수된다(run 31139616352: created 01:56:08 → runner-health 01:57:09~). 즉 **좀비가 되는 시점에는 runner-health 가 이미 끝나 있다.** 취소 스텝을 그 잡에 넣으면 「PR 이 아직 열려 있던 시점」의 판정만 하게 된다.
> - **✅ ①(a) 완료 (2026-08-10 · `fix/infra-ci-concurrency`).** `infra-ci.yml` 에 다른 3개와 동일한 `concurrency: {group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true}` 를 넣고, 되돌림을 막는 판별식 `scripts/workflow/ci-concurrency-coverage.test.ts` 를 신설했다.
>   **★닫히는 범위를 정확히 적을 것 (게이트2 리뷰 정정).** 이 선언이 닫는 것은 **같은 PR/브랜치에 새 push 가 겹치는** 경로다. 「이미 머지되고 브랜치가 삭제된 PR 의 큐 잔존분」은 **원리적으로 못 닫는다** — 그 ref 에는 후속 run 이 영영 생기지 않아 취소가 발화할 계기가 없다. 2026-08-07 큐의 PR #346 3건이 정확히 그 경우였고, 그건 별도 처방(`pull_request: types: [closed]` 에서 `gh run cancel`, 또는 머지 스킬 확장)이 필요하다 — 아래 신규 항목.
> - **★①(b)·② 착수 시 기준선 주의.** ①(a) 이후 `cancelled` 건수는 **설계된 동작**이라 회복 지표로 쓸 수 없다. ②(Gradle 설정)·③(모듈 선택)의 개선폭은 **대기 시간(벽시계 − 실행 합계)** 으로 재야 한다. **fail-open 계약**(API 실패·빈 응답·비 pull_request 이벤트에서는 아무것도 안 하고 `exit 0`)은 자원 점검 스텝(`:120-188`)의 선례를 그대로 따를 것.

---

# 2026-08-09 전수 실측에서 새로 드러난 항목

> 위 12건을 전수 검증(실측 12 + 적대적 반증 11, **기각 0건**)하면서 발견한 **같은 결함의 다른 표면**들이다.
> 2026-08-09 Maxi 확정 — 「원 항목은 좁게 고치고, 새로 드러난 표면은 전부 신규 등재」.
> 그러지 않으면 원 항목이 ✅ 로 닫히면서 나머지가 **기록조차 없는 상태**가 된다.

## ⬜ issue-tracking — `changeComponents` 자동배정도 `IssueAssigned` 를 발행하지 않는다 (선재 · 미착수)

**무엇.** `IssueApplicationService.kt:880-913` 의 `changeComponents` 는 `:901` 에서 `repo.setAssignee` 로
컴포넌트 리드를 담당자로 넣으면서 `IssueAssigned` 를 발행하지 않는다.
**「cloneIssue 가 알림을 안 보낸다」와 정확히 같은 양식**이다.

**결과.** 컴포넌트를 바꿔 자동 배정된 담당자는 알림을 못 받는다. 같은 「배정」인데 경로에 따라 알림이 갈린다.

**어떻게 발견.** 2026-08-09 clone 항목의 **적대적 반증**이 형제 진입점을 전수로 훑다가 잡았다.
clone 만 고치면 이 경로가 그대로 남아 **반쪽 봉합**이 된다.

**착수 시 주의.** clone 과 같은 fail-safe 게이트(기본 false)를 쓸 것. 배정 통로가 셋(create·clone·changeComponents)이
되므로 ADR D-5 의 「REST 생성 경로 한정」 문구를 함께 개정해야 한다.

---

## ⬜ issue-tracking — OpenAPI required 오표기 **잔여 26 프로퍼티** + 전수 판별식 부재 (선재 · 미착수)

**무엇.** 기본값이 있는 non-null Kotlin 프로퍼티가 springdoc 에서 `required` 로 오표기되는 함정이
`@RequestBody` DTO **11 클래스 · 27 프로퍼티**에 걸려 있다(2026-08-09 전수 측정).
그중 `CreateIssueRequest.componentIds` 1건만 좁게 봉합했고 **26건이 남는다**.

**가장 심각한 것.** `UpdateIssueRequest` 의 `JsonNullable` 6필드
(`securityLevelId`·`startDate`·`dueDate`·`targetDate`·`originalEstimateSeconds`·`remainingEstimateSeconds`).
`JsonNullable` 은 「필드 부재 = 변경 없음」을 표현하려고 도입한 타입인데(ADR D-2)
스펙이 그 6개를 전부 필수로 문서화한다 — **PATCH 계약 자체가 문서상 파손 상태**다.

**★손 열거는 반드시 샌다.** 1차 측정이 「5 DTO · 10 프로퍼티」였는데 전수 재측정은 **11 클래스 · 27 프로퍼티**였다.
17개를 빠뜨렸다. ⇒ **차집합 판별식이 진짜 산출물**이고 봉합은 그 부산물이다.

**절대 건드리지 말 것.** `expectedVersion` 3곳은 `@field:NotNull` 이라 런타임에 진짜 필수다.
`NOT_REQUIRED` 를 붙이면 「스펙은 optional, 서버는 400」이라는 **반대 방향 거짓말**이 된다.

**★판별식 함정.** `com.bts.issue.application.{CreateIssue,UpdateIssue,CloneIssue}Request` 가 REST DTO 와 **동명**이다
(`IssueApplicationRequests.kt:34,176,269`). simple-name 매칭으로 짜면 엉뚱한 DTO 를 검사하고
**초록인 채 아무것도 안 지킨다** — FQCN 으로 고정할 것.

**대안 검토.** springdoc `PropertyCustomizer` 로 「기본값 있고 `NotNull`/`NotBlank` 없는 Kotlin 프로퍼티는
required 에서 제외」를 전 모듈에 거는 전역 처방도 있다. 폭발 반경이 9 BC 전체라 별도 스펙 필요.

---

## ⬜ issue-tracking — 라벨 상한 상수 `LABEL_MAX_LENGTH` 사본이 3개가 된다 (미착수)

**무엇.** `LABEL_MAX_LENGTH = 50` 이 도메인 `Issue.kt`(파일 private) 1곳 + `CreateIssueRequest.kt` 1곳에 있고,
PATCH 봉합에서 `UpdateIssueRequest` 에 그대로 복사되면 **3개**가 된다.

**왜 지금 안 합쳤나.** 2026-08-09 Maxi 확정 「좁게」. 공용 상수로 수렴하려면 **이연 항목 봉합 PR 이
생성 경로 파일까지 만지게** 되므로 범위를 넘긴다.

**결과.** `[[two-lists-never-check-each-other]]` 양식. 한 곳만 바꾸면 나머지 둘이 조용히 어긋난다.

**처방.** `IssueLabelConstraints.kt` 같은 공용 지점 신설 후 3곳이 참조. 값 일치를 단언하는 짝 테스트 필수
(도메인 상수는 파일 private 이라 리플렉션이 아니라 **경계 동작**(50자 200 / 51자 400)으로 대조해야 한다).

---

## ⬜ apps/web — 담당자/리드 후보가 검색 전에 전량 노출되는 곳이 **3군데 더** 있다 (선재 · 미착수)

**무엇.** 「검색해야 후보가 나온다」 규칙이 적용되지 않은 표면이 이슈 상세 말고도 3곳이다.

| 표면 | 좌표 | 비고 |
|---|---|---|
| 이슈 목록 담당자 셀 | `components/issues/cells/AssigneeCell.tsx:250-253` | FR-UX-11 F9 · PR #338 로 **새로 생긴** 표면 |
| 컴포넌트 리드 셀렉터 | `ComponentLeadSelect.tsx:102` | |
| 프로젝트 리드 셀렉터 | `ProjectLeadSelect.tsx:116` | |

**★컴포넌트 내부 가드로는 안 닫힌다.** `AssigneeCell` 은 `IssueAssigneeSelect` 를 쓰지 않고
자체 `AssigneeCellEditor` 를 갖는다. 각 소비처에서 걸러야 한다.

**착수 시 주의.** `AssigneeCell` 은 `useUsers` 를 `vi.mock` 으로 고정하고 있어 **red 를 만들려면 mock 부터 뜯어야** 한다.
리드 셀렉터 2곳은 후보 모수가 작아 「누가 있는지 훑어본다」가 정상 사용일 수 있으므로 **결함 여부부터 판정**할 것.

**하지 말 것.** `useUsers` 에 전역 `enabled` 를 다는 「더 깔끔한」 처방 — 소비처 8곳 중 필터 계열이 빈 검색어 전체 목록에 의존한다.

---

## ⬜ apps/web — 한글 리터럴 placeholder 28곳이 i18n 밖에 있다 (선재 · 미착수)

**무엇.** `apps/web/src` 의 비-테스트 `.tsx` 에 한글 리터럴 `placeholder` 가 **28곳**
(`CreateBoardForm.tsx:109` · `DashboardForm.tsx:294` · `admin.workflow-schemes.new.tsx:123` ·
`CustomFieldFormDialog.tsx:222` · `search.tsx:353` 등). `ComponentMultiSelect.tsx:78` 의 반쪽 i18n 까지 29곳.

**처방 — 손 열거가 아니라 ESLint 래칫.** `apps/web/eslint.config.js:69·116` 에 이미 있는
`no-restricted-syntax` AST 선택자 배열에 `JSXAttribute[name.name='placeholder'] Literal[value=/[가-힣]/]` 를 더하고,
현재 히트를 예외 파일 목록으로 등재한 뒤 고칠 때마다 목록에서 뺀다.
**렌더 테스트로는 못 잡는다** — i18n 값과 하드코딩 값이 바이트 동일하면 DOM 속성 문자열이 같아
「속성값은 출처를 싣지 않는다」.

**착수 전 확정할 것 2건.** ① `ComponentMultiSelect.tsx:78` 은 템플릿 리터럴이라 `Literal` 선택자에 안 걸린다
(`TemplateElement[value.raw=/[가-힣]/]` 병용 여부). ② 위 28 은 grep 기준이라 **ESLint 히트와 일치한다는 보장이 없다**
— 규칙을 한 번 돌려 실제 목록을 확정한 뒤 등재. 로컬 lint 목록 ≠ CI lint 목록 선례 있음(`[[fr-ux-14-b2-card-fields-done]]`).

**★중복 추적 주의.** 같은 부류가 `docs/plan/product/personalization.md:479-483` ⑤(「라벨 5종이 컴포넌트 모듈 잔류」)로
따로 추적 중이다. 착수 시 **그쪽을 이 항목으로 흡수**할 것 — 두 곳에 나뉘어 있으면 서로를 검사하지 않는다.

---

## ⬜ apps/web — `isRequiredFieldEmpty` 사본 2곳이 갈라진다 (미착수)

**무엇.** 동일 함수가 `components/issue/IssueCreateForm.tsx:48-68`(생성)과
`components/issue/meta/IssueCustomFieldsEdit.tsx:31-52`(편집)에 **글자 단위로 같은 사본**으로 존재한다.

**왜 지금 안 합쳤나.** 2026-08-09 Maxi 확정 「좁게」 — 이번엔 생성 폼만 고쳤다.
⇒ **편집 화면에는 같은 결함(required MULTI_SELECT 미입력이 통과)이 그대로 남아 있다.**

**처방.** `apps/web/src/components/custom-fields/required-empty.ts` 로 단일 출처를 만들고 두 사본을 import 로 대체.
`IssueCustomFieldsEdit` 의 `eslint-disable react-refresh/only-export-components` 주석도 함께 제거된다.
이 named export 를 파일 밖에서 import 하는 곳은 전수 grep **0건**이라 re-export 없이 안전.

**짝 테스트.** FieldType 10종 × 입력 9종(`undefined`·`null`·`''`·`[]`·`['a']`·`0`·`NaN`·`false`·`true`) 표 테스트를
`toBe(true)`/`toBe(false)` **양쪽 다** 명시. `expect(ALL).toHaveLength(10)` 로 신규 FieldType 유입도 막을 것.

---

## ⬜ apps/web — 권한상 **숨겨진** required 커스텀 필드가 영구 저장 실패를 만든다 (선재 · 미착수)

**무엇.** `IssueCustomFieldsEdit.tsx:101` 은 `visibleFieldDefs`(FR-PM-07 숨김 제외) 기준으로 검증하고
`handleSave`(`:166-176`)도 그 기준이다. 반면 백엔드 `mergeCustomFieldsAndValidate`
(`IssueApplicationService.kt:1508-1511`)는 **활성 정의 전량** 기준으로 병합 후 검증한다.

**결과.** required 필드가 특정 사용자에게 restricted 이고 그 이슈에 아직 값이 없으면
(사후 추가 또는 required 플립), 클라는 그 필드를 **렌더도 검증도 하지 않고** 저장을 통과시키는데 백엔드가 422 를 낸다.
⇒ **사용자는 화면에 없는 필드 때문에 영원히 저장에 실패한다.** 원인을 알 방법이 없다.

**처방 후보.** ① 검증 기준을 `visibleFieldDefs` 가 아니라 `fieldDefs` 로 올리고
「권한 없는 필수 필드가 비어 있어 저장할 수 없습니다」를 띄운다 ② 편집 경로에도
`CUSTOM_FIELD_VALIDATION_FAILED` 에러 매핑을 넣는다. ①이 근본적이다.

---

## ⬜ apps/web — 클론 액션이 CREATE 권한을 안 보고 노출되고, 그 정당화 주석이 거짓이다 (선재 · 미착수)

**무엇.** `IssueMetaPanel.tsx:437-441` 이 클론 액션을 **서버 403 + 토스트 fail-safe** 로 두면서
「프론트 권한 API 가 CREATE 를 안 줘서 사전 게이트가 불가능하다」를 근거로 적어 두었다.
그 근거는 **지금 거짓**이다 — `project-permissions.ts:24-34` 가 `CREATE: z.boolean()` 을 내준다.

**결과.** 「UI 가 서버가 403 할 생성 액션을 내놓는다」가 남는다. 이슈 생성 폼 게이트를 넣어도 클론은 그 통로를 안 지난다.

**같은 부류의 서버 경로.** 클론(`IssueApplicationService.kt:355`) · 이동(`IssueMoveService.kt:190`) ·
임포트(`IssueImportAdapter.kt:288`) 셋 다 CREATE 를 요구하는데 `IssueCreateForm` 을 지나지 않는다.

**처방.** 낡은 주석을 먼저 지우고, 클론 버튼에 선택 프로젝트 기준 CREATE 판정을 붙인다.
판정식은 **`permissions.CREATE === false`(명시 거부만 차단)** 형태여야 한다 — `!isLoading && === true` 는
로딩/조회실패 구간에서 정상 사용자를 막는다.

---

## ⬜ apps/web(테스트 인프라) — 로컬 `setupServer` 59개 전면 이주 (미착수)

**무엇.** 이중 디스패치의 근본 원인인 로컬 `setupServer` 인스턴스가 테스트 파일 **60개** (2026-08-09 실측 · `src/test/server.ts` 제외)에 남아 있다.
판별식(동결)만 세워 신규 유입은 막았으나 기존 59개는 그대로다.

**왜 한 PR 로 안 하나.** ①이주 중 대량 RED 가 예상되고 ②프론트 전 스위트 반복 실행이
러너 1대 직렬 문제와 정면 충돌해 검증이 취소될 공산이 크다. **BC/디렉토리 단위로 쪼갤 것.**
판별식의 허용목록을 점차 줄이는 방식으로 진척을 강제한다.

**★기계적 치환이 아니다.** 로컬 서버가 뜨면 **전역 핸들러가 통째로 죽는다**(실측).
이주하면 그 파일들에서 **`/auth/refresh` 가 처음으로 살아나** `apiFetch` 의 401 자동 재시도가
지금은 실패하던 자리에서 성공한다 — **401/403 을 단언하는 테스트의 결과가 뒤집힌다.**

**필수 규율.** 핸들러 등록은 반드시 `beforeEach`(전역 `setup.ts:21` 의 `afterEach(server.resetHandlers())` 때문).
선례 3건 — `import-handlers.test.ts:13-22` · `profile-handlers.test.ts:8-18` · `status-handlers.test.ts:8-16`.

---

## ⬜ apps/web(테스트 인프라) — 지연 MSW 핸들러 34개 파일의 pending mutation 누수 미측정 (미착수)

**무엇.** 「테스트가 끝났는데 mutation 이 아직 날고 있다」 누수를 `AutomationYamlImportDialog.test.tsx` 한 파일에서만 닫았다.
**지연 MSW 핸들러를 쓰는 테스트 파일이 34개**이고 그중 몇 개가 같은 누수를 갖는지는 **미측정**이다.

**왜 승계를 미뤘나.** 34개 파일에 `afterEach` 불변식을 한 번에 넣으면 **한 PR 에서 대량 red** 가 터지고
「한 PR = 한 BC」 규칙과도 부딪힌다.

**처방.** 공용 `createTestQueryClient` 헬퍼 + 전역 `afterEach` 로
`queryClient.getMutationCache().getAll().filter(m => m.state.status === 'pending')` 가 빈 배열임을 단언.
디렉토리 단위로 나눠 넣을 것.

---

## ⬜ apps/web — `mutateAsync` 를 catch 없이 호출하는 곳 2군데 (프로덕션 경로 · 선재 · 미착수)

**무엇.** `BulkTransitionDialog.tsx:158` 과 `BulkEditDialog.tsx:101` 이 `mutateAsync` 를 catch 없이 호출하고
`void handleApply()` 로 띄운다.

**결과.** 실패하면 **프로덕션에서도 unhandled rejection** 이 된다.
**★단 「사용자에게 피드백이 없다」는 거짓이다 (게이트2 리뷰).** `use-bulk-operation.ts:58` 의 `onError` 가 이미 `toast.error` 를 띄운다.
남는 것은 **re-throw 된 rejection 이 전역으로 새는 것**뿐이므로, 봉합할 때 `CloneIssueDialog` 처럼 **빈 catch 만** 넣고 토스트를 새로 추가하지 말 것 — 추가하면 같은 실패 1회에 토스트가 2건 뜬다.
테스트 인프라 문제가 아니라 **실사용 결함**이다.

**정본 패턴.** `CloneIssueDialog.tsx:76-81` 의 try/catch 가 이미 같은 이유를 주석으로 적어 두었다 — 그 형태를 따를 것.

---

## ⬜ 인프라 — 대시보드 TODOS 파서가 `📌` 마커를 모른다 (잠복 · 미착수)

**무엇.** `scripts/workflow/todos-resolved-section-purity.test.ts:129` 는 섹션 헤딩 마커로
`✅` · `📌` · `⬜` **셋을** 허용한다. 그런데 `scripts/build-dashboard.mjs:217` 의 정규식은
`/^##\s+(✅|⬜)\s+(.+?)\s*$/` 로 **둘만** 인식한다.

**결과.** `📌 보류` 섹션을 하나라도 만들면 그 헤딩과 본문이 **앞 섹션의 본문으로 흡수**되고
대시보드 집계에서 **통째로 사라진다.** 게다가 앞 섹션이 ✅ 인데 흡수된 본문에 `⬜` 가 있으면
순수성 판별식이 엉뚱한 섹션을 지목한다.

**왜 아직 안 터졌나.** 현재 `📌` 섹션이 **0건**이라 잠복 중이다. 「보류」로 분류하고 싶은 항목이 생기는 순간 터진다.

**어떻게 발견.** 2026-08-09 기술 부채 12건 전수 검증 중, 「결함 아님·유지」 항목(접힘 레일)을
`📌` 로 옮길지 검토하다 두 목록이 어긋난 것을 확인했다. `[[two-lists-never-check-each-other]]` 양식.

**처방.** 파서 정규식에 `📌` 를 추가하고 `parseTodos` 가 `보류` 상태를 돌려주게 한다.
**짝 판별식 필수** — 판별식의 허용 마커 집합과 파서의 인식 마커 집합이 **같음**을 단언하는 테스트를 세운다.
한쪽만 고치면 다음 마커에서 같은 일이 반복된다.


## ⬜ apps/web — 이슈 목록 「새 이슈」 버튼이 로딩·조회실패 구간에 「권한 없음」이라고 거짓말한다 (선재 · 미착수)

**무엇.** `routes/issues.index.tsx:526` 의 `const canCreate = !isPermLoading && permData?.permissions.CREATE === true`
(미지 = 거부)가 `:447` 의 `aria-label="새 이슈 (권한 없음)"` 와 묶여 있다.

**결과.** CREATE 권한을 **실제로 가진** 사용자가 `/issues` 를 열면 권한 응답이 오기 전까지
`aria-label="새 이슈 (권한 없음)"` 인 disabled 버튼이 렌더된다. 스크린리더 사용자는 **매 진입마다
「권한 없음」이라는 거짓 안내**를 듣는다. `use-project-permissions.ts` 에 `retry:false` 도 에러 폴백도
없으므로 500·네트워크 단절이면 그 상태로 **영구히 안착**한다.

**어떻게 발견.** 2026-08-09 이슈 생성 CREATE 게이트 작업의 게이트2 리뷰. 그 작업은 판정식을
`permissions.CREATE === false`(**명시 거부만** 차단)로 뒤집어 이 함정을 피했는데, 목록 화면은 범위 밖이었다.

**처방.** 같은 `=== false` 형태로 뒤집는다. 시각적 disabled 는 남기더라도 **접근성 이름이 사실을
주장하지 않게** 하는 것이 핵심이다 — 미지 상태에서는 「권한 없음」이라고 말하지 않는다.

**착수 시 읽을 것.** `components/issue/IssueCreateForm.tsx` 의 `isCreateExplicitlyDenied` KDoc(정본 논거) ·
`hooks/use-project-permissions.ts:31-38`(retry·폴백 부재).

---

## ⬜ 인프라 — 머지·닫힌 PR 의 큐 잔존 run 차단 (concurrency 로는 못 닫는다 · 미착수)

**무엇.** `concurrency` 는 **같은 그룹에 새 run 이 생길 때만** 이전 run 을 취소한다. 이미 머지되고
브랜치까지 삭제된 PR 의 큐 잔존분은 그 ref 에 후속 run 이 영영 생기지 않아 **취소가 발화할 계기 자체가 없다.**
2026-08-07 큐 8건 중 3건이 정확히 그 경우(PR #346)였다.

**★원안은 실측이 반증했다.** 「`runner-health` 잡에 취소 스텝을 넣는다」는 성립하지 않는다 —
`runner-health` 는 run 의 **맨 앞**이라 run 생성 직후에 돌고, 나머지 잡은 그 뒤 몇 시간에 걸쳐 배수된다
(run 31139616352: created 01:56:08 → runner-health 01:57:09~). 좀비가 되는 시점에는 이미 끝나 있어
「PR 이 아직 열려 있던 시점」만 판정하게 된다.

**처방 후보.** ① `pull_request: types: [closed]` 트리거를 가진 얇은 워크플로우가 그 PR 의 진행 중 run 을
`gh run cancel` 한다(자기 자신은 즉시 끝나므로 큐 점유가 거의 없다) ② `/bts-merge` 스킬이 머지 직후
같은 정리를 한다(러너를 아예 안 쓴다). **fail-open 계약 필수** — API 실패·비대상 이벤트에서는 아무것도 하지 않는다.

**착수 시 주의.** 이 항목의 개선폭은 `cancelled` 건수로 재면 안 된다 — concurrency 취소도 같은 상태를 만든다.
**대기 시간(벽시계 − 실행 합계)** 으로 잴 것.
