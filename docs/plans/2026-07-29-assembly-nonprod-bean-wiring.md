# 조립 앱 비-prod 프로파일 부팅 봉합 — 빈 충돌 6종 + 빈 부재 3종

> slug: assembly-nonprod-bean-wiring
> type: backend
> agent: backend-engineer
> 생성: 2026-07-29
> base: origin/main (64d86af79)

## Brief

**사용자 원문.** 조립 앱(`backend/modules/app`)이 기본(비-prod) 프로파일에서 부팅되지 않는 문제를 **조립 계층에서만** 봉합한다. 다른 BC 코드 0 변경.

**classify 결과.** type=`backend` · agent=`backend-engineer` · primary_bc=`null`(조립 모듈) ·
자동 slug `app-prod-9-backend-modules-app` 은 가독성 문제로 `assembly-nonprod-bean-wiring` 로 대체.

### 실측 확정 — 9종 (정적 전수 열거 + 실부팅 대조군)

**판별식.** 파일 단위 grep 은 KDoc 언급을 세어 과대 계상한다(`@Profile` 포함 main 파일 57개 중 22개가 주석-only).
클래스/오브젝트/`@Bean` **선언에 붙은** `@Profile` 만 세고 상위 타입으로 그룹핑, `!prod` 구현 ≥2(중복) **와**
`prod` 전용 + 무조건 소비자(부재) 를 **양방향**으로 본다. 양성 대조군(`DevAllowIssuePermissionResolver` L48→L49) ·
음성 대조군(22 파일 전량 주석-only 역검증) 모두 통과. `@Profile` 실선언 총 36건 · UNRESOLVED 0.

**A. 중복 — 기본 프로파일에서 빈이 2개 (6종).**

| # | 타입 | identity-access | issue-tracking | 주입 지점 |
|---|---|---|---|---|
| 1 | `IssuePermissionResolver` | `DevAllowIssuePermissionResolver` `!prod` | `AlwaysAllowIssuePermissionResolver` `!prod` | 35 |
| 2 | `ComponentPermissionResolver` | `DevAllowComponentPermissionResolver` `!prod` | `AlwaysAllowComponentPermissionResolver` `!prod` | 6 |
| 3 | `CustomFieldPermissionResolver` | `DevAllowCustomFieldPermissionResolver` `!prod` | `AlwaysAllowCustomFieldPermissionResolver` `!prod` | 2 |
| 4 | `TemplatePermissionResolver` | `DevAllowTemplatePermissionResolver` `!prod` | `AlwaysAllowTemplatePermissionResolver` `!prod` | 2 |
| 5 | `VersionPermissionResolver` | `DevAllowVersionPermissionResolver` `!prod` | `AlwaysAllowVersionPermissionResolver` `!prod` | 3 |
| 6 | `SystemPermissionResolver` | `IdentityAccessSystemPermissionResolver` **`@Component` 만 — profile 없음 = 항상 활성** | `NonProdAllowSystemAdminResolver` `!prod` | 12 |

★ **6번은 1~5번과 성격이 다르다.** 1~5번은 "비-prod 스텁 둘"이 부딪히는 구조(셋 다 profile 로 배타 설계).
6번은 **실 구현이 프로파일 없이 항상 켜져 있는데** 그 위에 비-prod 스텁이 얹힌다. 같은 처방을 기계적으로 적용하면 안 된다.

**B. 부재 — 기본 프로파일에서 빈이 0개 (3종).** 유일 구현이 `@Profile("prod")` 인데 소비자는 프로파일 게이트 없는 무조건 빈.

| 타입 | 유일 구현 (prod 전용) | 소비자 | 주입 지점 |
|---|---|---|---|
| `AutomationPermissionResolver` | `IdentityAccessAutomationPermissionResolver` | `AutomationRuleService` `@Service` · `RuleExecutionService` `@Service` · `GitWebhookRegistrationService` `@Service` | 3 |
| `IssueMutationPort` | `AutomationIssueMutationAdapter` | `ActionExecutor` `@Component` · `SlackInteractionService` `@Component` | 2 |
| `IssueSnapshotPort` | `AutomationIssueSnapshotAdapter` | `ActionExecutor` `@Component` | 1 |

**탈출구 부재.** main 전체에 `@Primary` **0건**, 충돌 타입에 `@Qualifier` **0건**.

### 실증 대조군 (2026-07-29, 1회 부팅)

`./backend/gradlew -p backend :modules:app:bootRun` + dev postgres(5433, `bts-postgres-dev`).

```
APPLICATION FAILED TO START
Parameter 0 of constructor in com.bts.issue.cycletime.application.CycleTimeService
required a single bean, but 2 were found:
  - com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
  - com.atlas.bts.identity.permission.DevAllowIssuePermissionResolver
```

**예측한 중복 1번과 정확히 일치 → 판별식 검증됨.**
DataSource · Flyway · Tomcat 은 정상 통과(`Root WebApplicationContext: initialization completed in 1941 ms` 이후 실패)
→ 부팅 장애는 오직 빈 결선 문제이며 다른 층의 잠복 결함은 없다.

### 근본 원인

`DevAllowIssuePermissionResolver` KDoc 의 전제 —
> "identity-access 와 issue-tracking 은 각자 독립 `@SpringBootApplication` 이라 컨텍스트가 분리된다 … 충돌하지 않는다"

가 **배포 조립 모듈 `app` 이 생기며 깨졌다.** `BtsApplication` 이 `com.bts` + `com.atlas.bts` 전체를 스캔하므로
두 컨텍스트에 나뉘어 있던 스텁이 한 컨텍스트에 모인다. prod 는 `@Profile("prod")` 실구현만 살아 정상 부팅되므로
**비-prod 에서만 죽는다.** #319 가 뚫었다는 "실 백엔드 손검증 경로"는 프론트 쪽만 열렸고 백엔드는 애초에 닫혀 있었다.

### 처방 — A안 확정 (2026-07-29 Maxi 결정, decision `1fb93a4e`)

조립 모듈(`app`) 안에서만 봉합한다.
1. **중복 6종** — `BtsApplication` 의 `excludeFilters` 를 확장해 조립 컨텍스트에서 중복 스텁을 배제
2. **부재 3종** — `app` 모듈 전용 `@Configuration` 으로 비-prod fallback 빈 등록
3. **다른 BC 코드 0 변경** — 각 BC 단독 부팅 동작 불변

**기각안.** B(스텁 자체 제거) = 두 BC 동시 수정 + 각 BC 단독 부팅 테스트까지 폭발 반경.
C(prod 프로파일로 개발) = 사용자·프로젝트 시드 0건이라 로그인 불가 → 손검증 목적 자체를 못 채움.

### 범위 밖 (명시)

- FR 카운트 변경 **없음** (139 불변) · 마이그레이션 **0건** · 신규 의존성 **0건**
- 조립 앱 dev 시드 전략(사용자·프로젝트 0건 → 로그인 불가)은 **별건**. 이 PR 은 "부팅된다"까지만 책임진다
- `verify-master-plan.sh` CI 미통합(Open Question #4)도 별건

## 도메인 정리

- **BC.** 특정 BC 아님 — **조립 계층**(`backend/modules/app`). 계약 타입은 shared-kernel `com.bts.shared.permission` 의 포트들.
  다른 BC(identity-access · issue-tracking · project-workflow · automation · slack-integration)는 **읽기만** 하고 수정 0.
- **영향 엔티티.** 없음 (도메인 모델 무변경 · 마이그레이션 0 · 신규 권한 enum 0).
- **glossary 신규 용어 후보 1건.** "**조립 컨텍스트**(assembly context)" — `BtsApplication` 이 `com.bts` + `com.atlas.bts` 전체를
  한 `ApplicationContext` 로 스캔한 상태. 각 BC 단독 `@SpringBootApplication` 컨텍스트와 구별되는 개념인데
  glossary 에 표제어가 없다. Maxi 승인 후 추가 (승인 전에는 plan/ADR 안에서만 사용).

### 기존 결정 대조 — 3건 발견 (충돌 1 · 범위 한계 1 · 방향 일치 1)

**① `2026-07-11-automation-prod-assembly.md` §fail-closed — 검증 범위가 prod 한정이었다 (무효화 아님, 한계 명시 필요).**
그 ADR 은 *"automation main 빈 13개의 생성자 의존을 전수 추적한 결과 미충족 의존은 0"* 이라고 선언했으나,
본문 그대로 **"조립+prod 컨텍스트에서"** 로 한정돼 있다(L36). 실제로 그 아래 열거가
`AutomationPermissionResolver → IdentityAccessAutomationPermissionResolver(@Profile("prod")) ✓` ·
`IssueMutationPort → AutomationIssueMutationAdapter(@Profile("prod")) ✓` 로, **prod 에서만 충족되는 항목을 ✓ 로 적었다.**
D2 의 회귀 가드(`BtsApplicationContextTest`)도 `ProdAssemblyHttpTestBase` 상속으로 `@ActiveProfiles("prod")` 고정이다.
→ **비-prod 조립 부팅은 처음부터 계약도 가드도 없었다.** 본 PR 이 그 공백을 메운다. 기존 결정 무효화는 아니고 **범위 확장**.

**② `2026-06-04-system-admin-role.md` L65 와 issue-tracking 스텁이 모순 (중복 6번의 처방 근거).**
그 ADR 은 명시적으로 결정했다 — *"판정기는 단순 DB 조회라 `@Profile` 분리(prod/non-prod stub)가 **불필요** —
**모든 프로파일에서 실제 판정한다.** `AlwaysAllow*` 같은 stub 없음"*. 그래서 `IdentityAccessSystemPermissionResolver` 는
`@Component` 만 달고 profile 이 없다. 그런데 issue-tracking 에 `NonProdAllowSystemAdminResolver`(`@Profile("!prod")`,
`isSystemAdmin` 항상 true)가 **따로 존재**해 조립 컨텍스트에서 이 결정을 뒤집는다.
→ 조립에서 스텁을 배제하는 것이 ADR 의도에 부합. **단, 부작용 있음** — `ProjectCreatePermissionProdBootTest` KDoc L34~37 이
*"기본(비-prod) 프로파일이면 `NonProdAllowSystemAdminResolver`(항상 true)가 살아나 세 시나리오가 전부 201 로 무의미하게 통과"*
라고 적었다. 즉 **스텁을 빼면 비-prod 조립에서 프로젝트 생성이 실제 권한 판정을 받는다.** 시드 0건 문제와 맞물리므로 스펙에서 결정.

**③ `2026-05-22-issue-permission-resolver-port.md` L115 — 스텁 제거는 원래 예정된 방향 (A안과 상충 없음).**
*"`AlwaysAllowIssuePermissionResolver` 제거 시점은 dev/staging 도 새 adapter 검증 완료 후"* 로 이미 예고돼 있다.
A안(조립 한정 배제)은 각 BC 단독 부팅의 스텁을 남기므로 그 로드맵을 앞당기지도 막지도 않는다.

### ★ 설계 제약 — 비-prod 가드를 순진하게 추가하면 알려진 지뢰를 밟는다

`ProdAssemblyHttpTestBase` KDoc L37~43 경고. *"`webEnvironment` 는 컨텍스트 캐시 키의 일부 … 같은 JVM 안에 다른
설정의 조립 테스트가 있으면 9-BC 컨텍스트가 **부팅 2회**로 중복되고 `@Scheduled` 워커도 2벌이 동일 5433 dev postgres 의
pgmq 큐를 **동시 폴링**한다."*
`@ActiveProfiles` 가 다르면 그 자체로 캐시 키가 갈리므로, **비-prod 조립 부팅 테스트를 그냥 추가하면 이 지뢰를 밟는다**
(메모리 `flaky-late-vs-never-arriving-message` — 워커 두 벌의 pgmq 메시지 도둑질과 동일 양식).
→ 가드 방식은 스펙에서 결정. 후보 (a) 부팅 없는 정적 빈 정의 검사 (b) 스케줄링 비활성 + `webEnvironment=NONE`
(c) 별도 Gradle test 태스크/JVM 분리. **(a) 채택 시 ArchUnit 공허 룰 함정 주의** — 일부러 위반을 넣어 fail 확인 필수.

- **관련 ADR.** 위 3건 + `2026-06-03-version-component-permission-prod-resolver` · `2026-06-04-workflow-scheme-permission-prod-resolver`
- **신규 ADR 필요.** 예 — `docs/decisions/2026-07-29-assembly-nonprod-bean-wiring.md` (스펙 확정 후 생성)

## 스펙

전체 스펙. [docs/specs/2026-07-29-assembly-nonprod-bean-wiring.md](../specs/2026-07-29-assembly-nonprod-bean-wiring.md)

**Maxi 확정 결정 4건 (2026-07-29).**

| ID | 질문 | 결정 |
|---|---|---|
| D4 | 중복 5종에서 어느 스텁을 조립에서 뺄까 | **A — issue-tracking 스텁 배제.** 규칙 한 줄 = *"조립에서 권한 리졸버 출처는 언제나 identity-access"* |
| D5 | `SystemPermissionResolver` 스텁 배제 부작용(비-prod 실판정) 수용? | **A — 수용.** `2026-06-04-system-admin-role` L65 *"모든 프로파일에서 실제 판정"* 의 명시 결정 그대로. 시드는 별건 |
| D6 | 재발 방지 가드 방식 | **B — 비-prod 실부팅 테스트 + 별도 Gradle 태스크(JVM 분리).** 고치는 증상이 "안 켜진다"라 실부팅만이 직접 증거. 분리로 pgmq 이중 폴링 원천 차단 |
| D7 | 부재 3종에 실 구현 vs 스텁 | **A — 실 구현을 비-prod 에도 연결.** 스텁이면 dev 에서 자동화가 조용한 no-op → 손검증 목적을 깬다. 생성자 변경은 조립 모듈 컴파일 에러로 드러남 |

**핵심 3줄 요약.**
- 조립 스캔에서 issue-tracking 스텁 **정확히 6개**를 `ASSIGNABLE_TYPE` 으로 배제 (정규식 금지 — 조용한 무효화 회피)
- `app` 모듈 `@Configuration @Profile("!prod")` 로 부재 3종의 **실 구현**을 `@Bean` 등록 (prod `@Component` 와 상호 배타)
- 비-prod 실부팅 테스트를 **별도 Gradle Test 태스크**로 신설 + 9종 각각 빈 1개 단언 + 뮤테이션 9종 RED 실증

## Brainstorming Check

✅ 통과 (1회 iteration) — **gap 1건 발견 후 보강.**

issue-tracking 의 `AlwaysAllow*`/`NonProdAllow*` 는 **8개**인데 중복인 것은 **6개뿐**이다.
패턴(`AlwaysAllow*`)으로 배제하면 `AlwaysAllowFieldPermissionResolver`(`FieldPermissionResolver` 유일 비-prod 구현) ·
`AlwaysAllowIssueSecurityDirectory`(`IssueSecurityDirectory` 유일 비-prod 구현) 가 함께 제거돼
**새 "빈 부재" 2종이 생긴다.** 봉합이 새 결함을 만드는 양식(메모리 `seal-closes-only-half-by-default`).
→ 스펙 FR-B 에 **배제 금지 목록**을 명시하고 와일드카드 배제를 금지했다.

**추가로 스펙에 못박은 함정 3건.**
- EC-1 **10번째 고장** — Spring fail-fast 라 9종 뒤에 가려진 항목이 있을 수 있다. 부팅 성공까지 반복 필수
- EC-2 **정규식 배제의 조용한 무효화** — 이름 변경 시 매칭이 풀려 중복 부활. `ASSIGNABLE_TYPE`+import 로 컴파일 에러화
- EC-5 **가드 공허화** — "컨텍스트만 뜨면 통과"면 무의미. 9종 각각 뮤테이션 주입해 RED 실증

## 착수 전 실측 (EC-3 등 선결 확인 — 완료)

| 확인 항목 | 결과 |
|---|---|
| `ObjectMapper` 빈 모호성 (EC-3) | **해소.** main 전량에 커스텀 `@Bean ObjectMapper` **0건** → Spring Boot 자동설정 단일 빈. 조립에서 이미 37곳이 주입 중 |
| `ProjectDirectory` 구현 | `@Repository` (identity-access) — 프로파일 게이트 없음 |
| `ProjectMembershipRepository` 구현 | `JdbcProjectMembershipRepository` `@Repository` — 게이트 없음 |
| `PermissionSchemeRepository` 구현 | `JdbcPermissionSchemeRepository` `@Repository` — 게이트 없음 |
| `IssueApplicationService` / `CommentApplicationService` | 둘 다 `@Service` — 게이트 없음 |
| `TransactionTemplate` | 직접 정의 0건 → Boot `TransactionAutoConfiguration` 제공 |
| app 모듈 `tasks.withType<Test>` | `useJUnitPlatform()` 전역 적용 중 → 신설 Test 태스크도 상속 |

→ **FR-C 의 실 구현 `@Bean` 등록은 의존 해소 가능.** 착수 차단 요인 0.

## Plan

### Task 1. 비-prod 조립 부팅 가드 신설 (RED) — FR-D

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/NonProdAssemblyBootTest.kt`, `backend/modules/app/build.gradle.kts`]
- depends-on: []

> **eng-review 반영.** CONCERN-1(webEnvironment) · CONCERN-3(태그 단일 출처) · CONCERN-4(`mustRunAfter`) 적용됨.

**RED**.
- 신규 `NonProdAssemblyBootTest` — **`@SpringBootTest(webEnvironment = RANDOM_PORT)`**,
  **`@ActiveProfiles` 미지정**(= 기본/비-prod), `@Tag("nonprod-assembly")`.
  ★ `NONE` 이 아니라 `RANDOM_PORT` 인 이유 — prod 가드와 **같은 충실도**. 별도 JVM 이라 컨텍스트가 1벌뿐이므로
  EC-7 위험 없이 실 Tomcat·필터체인까지 태울 수 있다. `NONE` 이면 웹 계층 결함(EC-1 후보)을 그냥 통과한다.
- 단언은 **컨텍스트 로드 성공만으로 끝내지 않는다**(EC-5 공허 회피). 봉합 대상 **9종 타입 각각**에 대해
  `context.getBeanNamesForType(T::class.java).size == 1` 을 단언하고, 실패 시 **발견된 빈 이름 전량을 메시지에 담는다**.
  - 중복 6종. `IssuePermissionResolver` · `ComponentPermissionResolver` · `CustomFieldPermissionResolver` ·
    `TemplatePermissionResolver` · `VersionPermissionResolver` · `SystemPermissionResolver`
  - 부재 3종. `AutomationPermissionResolver` · `IssueMutationPort` · `IssueSnapshotPort`
- 추가 단언(FR-B 회귀 가드). `FieldPermissionResolver` · `IssueSecurityDirectory` 도 **각 1개**여야 한다
  — 배제 금지 2종을 실수로 빼면 여기서 잡힌다.
- `build.gradle.kts` — **태그 분기를 기존 `withType<Test>` 한 블록 안에 둔다**(CONCERN-3 — 두 블록이면 선언 순서에 의존해
  누군가 순서를 바꾸면 `excludeTags` 가 조용히 덮인다).
  ```kotlin
  tasks.withType<Test> {
      useJUnitPlatform {
          if (name == "nonProdAssemblyTest") includeTags("nonprod-assembly")
          else excludeTags("nonprod-assembly")
      }
      System.getProperty("contract.snapshot.update")?.let { systemProperty("contract.snapshot.update", it) }  // 기존 유지
  }
  val nonProdAssemblyTest = tasks.register<Test>("nonProdAssemblyTest") {
      group = "verification"
      testClassesDirs = sourceSets["test"].output.classesDirs
      classpath = sourceSets["test"].runtimeClasspath
      mustRunAfter(tasks.named("test"))   // CONCERN-4 — 워커 두 벌 동시 폴링 방지
  }
  tasks.named("check") { dependsOn(nonProdAssemblyTest) }
  ```
  ★ **태그 분리가 EC-7 의 유일한 방어선**이다. 같은 JVM 에 두면 9-BC 컨텍스트 2벌 + pgmq 이중 폴링.
  ★ KDoc 에 *"dev 앱(`bootRun`)을 띄운 채 이 테스트를 돌리지 말 것"* 을 명시한다 — FR-C 이후
  automation 워커 4종이 비-prod 에서 **처음으로** 살아나 같은 `q_automation_events` 를 폴링한다.
- 실패 메시지 (예상). `NoUniqueBeanDefinitionException: ... IssuePermissionResolver ... found 2`
  (컨텍스트 로드 자체 실패 — 정상적인 RED)

**GREEN**. 없음 (이 task 는 RED 전용).

**REFACTOR**. KDoc 에 EC-7(이중 부팅) 사유 + `ProdAssemblyHttpTestBase` 와 **같은 JVM 금지** 명시.

**검증**. `./gradlew -p backend :modules:app:nonProdAssemblyTest` → **FAIL** 확인 (RED 실증).
`:modules:app:test` 는 이 테스트를 **제외**하고 여전히 GREEN 인지 확인.

---

### Task 2. 중복 6종 배제 (GREEN 1/2) — FR-A · FR-B

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/BtsApplication.kt`]
- depends-on: [1]

**GREEN**.
- `@ComponentScan excludeFilters` 에 `FilterType.ASSIGNABLE_TYPE` 필터 1개 추가, `classes` 에 **정확히 6개**.
  `AlwaysAllowIssuePermissionResolver` · `AlwaysAllowComponentPermissionResolver` ·
  `AlwaysAllowCustomFieldPermissionResolver` · `AlwaysAllowTemplatePermissionResolver` ·
  `AlwaysAllowVersionPermissionResolver` · `NonProdAllowSystemAdminResolver`
- ★ **`FilterType.REGEX` 금지**(EC-2). 실제 import 로 이름 변경 시 컴파일 에러가 나게 한다.
- ★ **배제 금지**(FR-B). `AlwaysAllowFieldPermissionResolver` · `AlwaysAllowIssueSecurityDirectory` 는 **넣지 않는다**.
  KDoc 에 "왜 8개 중 6개인가"를 못박는다.

**REFACTOR**. 기존 excludeFilters KDoc 단락 아래에 배제 근거(D4 규칙 한 줄 + FR-B 금지 목록) 추가.

**검증**. `nonProdAssemblyTest` 재실행 → 중복 6종 단언은 통과하고 **부재 3종에서 실패**해야 한다
(다음 에러로 넘어간 것 = 진전 실증). prod 조립 테스트 전량 GREEN 유지 확인.

---

### Task 3. 부재 3종 실 구현 등록 (GREEN 2/2) — FR-C

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/NonProdAssemblyPortConfig.kt`]
- depends-on: [2]

**GREEN**.
- 신규 `@Configuration @Profile("!prod")` 클래스. 첫 줄 한글 역할 주석 필수 (CLAUDE.md §6).
- `@Bean` 3개로 **prod 구현 클래스를 그대로** 생성한다 (스텁 신설 금지 — D7-A).

  | `@Bean` | 생성 클래스 | 파라미터 |
  |---|---|---|
  | `automationPermissionResolver` | `IdentityAccessAutomationPermissionResolver` | `ProjectDirectory`, `ProjectMembershipRepository`, `PermissionSchemeRepository` |
  | `issueMutationPort` | `AutomationIssueMutationAdapter` | `IssueApplicationService`, `CommentApplicationService`, `ObjectMapper`, `TransactionTemplate` |
  | `issueSnapshotPort` | `AutomationIssueSnapshotAdapter` | `IssueApplicationService` |

- ★ prod 의 `@Component @Profile("prod")` 와 **상호 배타**임을 KDoc 에 명시 (prod 중복 불가 근거).
- ★ EC-4. 생성자가 바뀌면 이 파일이 **컴파일 에러**로 드러난다 — 그것이 스텁 대신 실 구현을 택한 이유임을 KDoc 에 남긴다.

**REFACTOR**. 빈 이름을 `FullyQualifiedAnnotationBeanNameGenerator` 와 충돌하지 않게 확인
(`@Bean` 은 메서드명이 빈 이름 — FQN 생성기는 `@Component` 스캔에만 적용).

**검증**. `nonProdAssemblyTest` → **PASS** 기대. 실패 시 Task 4 로 인계.

---

### Task 4. 실부팅 성공까지 반복 — EC-1 (10번째 고장 흡수)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/main/kotlin/com/bts/app/BtsApplication.kt`, `backend/modules/app/src/main/kotlin/com/bts/app/NonProdAssemblyPortConfig.kt`, `docs/specs/2026-07-29-assembly-nonprod-bean-wiring.md`]
- depends-on: [3]

**절차**.
1. dev postgres 기동 → `./backend/gradlew -p backend :modules:app:bootRun` (프로파일 미지정).
2. `Started BtsApplication` 이 뜰 때까지 반복. 새 에러가 나오면 **같은 판별식으로 분류**
   (중복이냐 부재냐) 후 Task 2/3 의 방식 그대로 흡수하고, **스펙 FR-A/FR-C 표에 행을 추가**한다.
3. 새 항목이 나오면 Task 1 의 단언 목록에도 추가한다 (가드 범위 = 실제 봉합 범위).
4. 종료 후 컨테이너·포트 정리.

**검증**. `Started BtsApplication` 로그 캡처 + 최종 항목 수를 스펙과 대조. 9종에서 늘었으면 그 수를 정본으로 갱신.

---

### Task 5. 뮤테이션 실증 — EC-5 (가드 공허화 차단)

**메타**.
- agent: `backend-engineer`
- files: []  *(검증 전용 — 소스 커밋 없음, 결과만 plan 에 기록)*
- depends-on: [4]

**절차**. 봉합 항목 **각각**에 대해 되돌리는 변이를 1개씩 주입하고 `nonProdAssemblyTest` 가 **RED** 인지 확인 후 원복.

| 뮤턴트 | 주입 | 기대 |
|---|---|---|
| M1~M6 | excludeFilters 에서 배제 클래스 1개씩 제거 | 해당 타입 빈 2개 → RED |
| M7~M9 | `NonProdAssemblyPortConfig` 의 `@Bean` 1개씩 주석 처리 | 해당 타입 빈 0개 → RED |
| M10 | 배제 목록에 `AlwaysAllowFieldPermissionResolver` **추가** (FR-B 위반 주입) | `FieldPermissionResolver` 빈 0개 → RED |
| M11 | 배제 목록에 `AlwaysAllowIssueSecurityDirectory` **추가** | `IssueSecurityDirectory` 빈 0개 → RED |

★ **뮤테이션은 커밋된 기준선에서만** 수행한다 (메모리 `mutation-test-requires-committed-baseline`).
★ 전량 RED 가 아니면 가드가 그 지점을 **못 본다**는 뜻이므로 Task 1 단언을 보강한다.

**검증**. 뮤턴트 11종 전부 RED + 원복 후 `git diff` 0.

---

### Task 6. ADR + 문서 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/decisions/2026-07-29-assembly-nonprod-bean-wiring.md`, `docs/plans/2026-07-29-assembly-nonprod-bean-wiring.md`]
- depends-on: [4]

**내용**.
- ADR 신설. 맥락(조립 모듈 등장으로 "독립 컨텍스트" 전제 파기) · 결정 D4~D7 · 기각안 B/C ·
  **선행 ADR 3건과의 관계 명시** — `2026-07-11-automation-prod-assembly`(검증 범위 prod 한정이었음을 정정 단락으로 기록) ·
  `2026-06-04-system-admin-role`(L65 의도를 조립에서 실현) · `2026-05-22-issue-permission-resolver-port`(스텁 제거 로드맵과 무충돌)
- **`CHANGELOG.md` 는 변경하지 않는다.** BC 요약 표는 FR 단위 제품 산출 요약이고 이 PR 은 FR 0 · 조립 계층 결함 봉합이다.
  (판단 근거를 ADR §결과 에 남겨 리뷰가 반박할 수 있게 한다.)
- FR 카운트 표기 **전부 불변 139** — `fr-index` · `README` · `CLAUDE.md` · product 문서 손대지 않는다.

**검증**. `bash scripts/verify-master-plan.sh` 종료 0.

---

### Task 7. prod 대칭 단언 — FR-F (eng-review CONCERN-2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`]
- depends-on: [4]

**내용**. Task 1 이 만든 빈-개수 단언 헬퍼를 **prod 프로파일에서도** 실행한다.
동일한 11종에 대해 `getBeanNamesForType(T).size == 1` 을 단언.

**왜 필요한가.** `excludeFilters` 는 **프로파일을 가리지 않는다.** 실수로 prod 빈을 배제해도
"기존 prod 테스트가 통과하니까 괜찮다"는 **간접 증거**로는 못 잡을 수 있다. 삭제·추가 양방향 봉인.

**검증**. `:modules:app:test` GREEN. 뮤테이션 — 배제 목록에 prod 활성 클래스
(`IdentityAccessSystemPermissionResolver`)를 넣으면 이 테스트가 RED 여야 한다.

---

### Task 8. CI 배선 — FR-E (eng-review BLOCKER-1)

**메타**.
- agent: `backend-engineer`
- files: [`.github/workflows/backend-ci.yml`, `scripts/workflow/ci-module-coverage.test.ts`]
- depends-on: [1]

**내용**.
1. `backend-ci.yml` `assembly` 잡(L96~)의 Gradle 실행에 `:modules:app:nonProdAssemblyTest` 추가.
   서비스 컨테이너(`quay.io/tembo/pg16-pgmq` + 5433:5432)는 이미 그 잡에 있으므로 **추가 인프라 0**.
2. `ci-module-coverage.test.ts` 에 존재 단언 추가 — 현재 L80 이 `/:modules:app:test/` 만 단언한다.
   `/:modules:app:nonProdAssemblyTest/` 단언을 같은 양식으로 추가.

**왜 BLOCKER 였나.** 배선하지 않으면 태그로 분리된 신설 가드가 **CI 에서 0회 실행**된다.
`TODOS.md §151` 이 이미 같은 실패 양식을 기록했다 — *"안 돌리면 봉인이 로컬 1회성 확인으로 끝나고 썩는다"*.

**검증**. 신규 룰에 **일부러 위반을 주입해 FAIL 확인**(CI 파일에서 태스크 이름 삭제 → `pnpm test:workflow` RED) 후 원복.

---

### Task 9. 최종 검증 — 완료 기준 10개 전수

**메타**.
- agent: `backend-engineer`
- files: []
- depends-on: [5, 6, 7, 8]

**절차**.
1. `./backend/gradlew -p backend :modules:app:test :modules:app:nonProdAssemblyTest` → 전량 GREEN
2. 각 BC 모듈 테스트 전량 GREEN (회귀 0) — 특히 identity-access · issue-tracking · automation
3. `ktlintCheck` + `detekt` (app 모듈) — baseline 갱신 필요 시 갱신
4. **N1 경로 제약 실증**. `git diff --stat origin/main` 에서 허용 경로 밖 변경 **0줄**
   (허용 = `backend/modules/app/**` · `docs/**` · `.github/workflows/backend-ci.yml` · `scripts/workflow/ci-module-coverage.test.ts`)
   — 특히 **다른 BC `src/**` 0줄**
5. **S2 회귀 금지 실증**. `ProdAssemblyHttpTestBase` 하위 전량 GREEN
6. `bash scripts/verify-master-plan.sh` 종료 0
7. 프로파일 미지정 `bootRun` 최종 1회 → `Started BtsApplication`
8. `pnpm test:workflow` GREEN (신규 CI 룰 포함)
9. **CI 4잡 전부 그린** — `assembly` 잡 로그에서 `nonProdAssemblyTest` 가 실제로 실행됐는지 확인
   (⚠️ 첫 빨간불은 선재 flaky 일 수 있다 — `TODOS.md §160`. **같은 명령 연속 2회**로 flaky/회귀를 먼저 가른다)

**검증**. 위 9개 결과를 plan 에 실측값으로 기록 (개수·초·종료코드·CI run URL).

## Plan 메타

- task 수: **9** (eng-review BLOCKER-1/CONCERN-2 반영으로 7 → 9)
- wave 예상: T1 → {T2, T8} → T3 → T4 → {T5, T6, T7} → T9
  (T8 은 CI 파일만 건드려 T2~T7 과 파일 겹침 0 → T1 직후 병렬 가능)
- TDD 강제: yes (T1 = RED, T2·T3 = GREEN)
- 병렬 dispatch: 코드 봉합 축은 **직렬 성격**이 강하다 — 부팅 에러가 fail-fast 라 순차로만 드러난다
- 추가 검증: ktlint · detekt · **뮤테이션 11종** · `pnpm test:workflow` · verify-master-plan · 실부팅 · CI 4잡

## 구현 결과 (전부 실측, 2026-07-29)

### 완료 기준 10개 전수

| # | 기준 | 결과 |
|---|---|---|
| 1 | 프로파일 미지정 `bootRun` | ✅ **`Started BtsApplicationKt in 8.13 seconds`** — EC-1 의 10번째 고장 **없음** |
| 2 | 뮤테이션 전량 RED | ✅ **11/11 RED.** 원인 확정 — M1 `NoUniqueBeanDefinitionException … found 2: AlwaysAllowIssuePermissionResolver, DevAllowIssuePermissionResolver` · M11 `NoSuchBeanDefinitionException: com.bts.shared.permission.IssueSecurityDirectory` |
| 3 | `app:test` + `nonProdAssemblyTest` | ✅ 63 / 0 실패 (14 XML) · 2 / 0 실패 (1 XML) — XML 집계, 캐시 아님 |
| 4 | 각 BC 회귀 0 | ✅ **구조적으로 불가** — 다른 BC `src/**` 0줄(기준 5). app 은 BC 를 의존하는 하류라 역방향 영향 없음. CI `modules` 매트릭스 9잡이 최종 확인 |
| 5 | N1 경로 제약 | ✅ 변경 11파일 전부 허용 경로. **다른 BC `src/**` 0줄** |
| 6 | prod 회귀 금지(S2) | ✅ `ProdAssemblyHttpTestBase` 하위 전량 GREEN + **신규 prod 대칭 단언이 XML 에 기록됨**(공허 통과 아님) |
| 7 | ktlint + detekt | ✅ `--rerun-tasks` 로 캐시 우회, BUILD SUCCESSFUL |
| 8 | `verify-master-plan.sh` | ✅ exit 0 · FR **139/139** · 카운트 정합 |
| 9 | CI 4잡 그린 | ⏳ 푸시 후 확인. 로컬 선행 검증은 전부 통과 |
| 10 | CI 룰 뮤테이션 확증 | ✅ yml 에서 태스크 이름 제거 → *"비-prod 조립 부팅 가드가 CI 에 배선되지 않았다"* FAIL → 복원 후 PASS |

### D8 — 선재 실패 재동결 (Maxi 확정)

`pnpm test:workflow` 의 `bc-keyword-coverage` 가 *"불일치 57건 > baseline 49"* 로 실패했다.
**PRE_EXISTING 확정** — main 트리에서도 동일하게 **57건**이고, 이 PR 은 FR 문서·`classify-task` 를 **0줄** 바꿨다.
원인은 PR #320 의 FR 분할(131→139, +8)이고, #320 은 `docs/**`·`apps/web/**` 만 건드려 **backend-ci 가
트리거되지 않아** 가드가 안 돌았다. 이 PR 이 `scripts/workflow/**` 를 건드리며 처음 드러난 것이다.
→ **A안 채택** — `MAX_MISMATCHES` 49 → 57 재동결 + 사유·출처 주석. 이후 `61/61 pass`.
(그 가드의 세 번째 테스트가 "느슨해지면 낮춰라"를 강제하므로 방치되지 않는다.)
★ **파생 발견 (별건).** FR 계획 문서만 바꾸는 PR 은 이 판별식의 **입력을 바꾸면서도 가드를 건너뛴다.**
`backend-ci.yml` 트리거 경로에 FR 문서를 넣을지는 별도 판단 사안 — 주석에 남겼다.

### TDD 커밋 사슬 (전부 `test:` → `feat:` 순서)

| Task | RED | GREEN |
|---|---|---|
| T1 가드 | `ff2a20ece test:` | (T2·T3 이 GREEN) |
| T8 CI 배선 | `54a71f9ca test:` | `4de37c6ad feat:` |
| T2 중복 6종 배제 | ← T1 | `148270c39 feat:` |
| T3 부재 3종 등록 | ← T1 | (같은 커밋 묶음) |
| T7 prod 대칭 단언 + ADR | `test:` | — |

## 리뷰 결과

### plan-eng-review (2026-07-29)

**Step 0 — 스코프 도전.** 코드 4파일(+CI 반영 시 6) · 신규 production 클래스 1개 · 신규 테스트 1개.
복잡도 임계(8파일 / 2 신규 서비스) 미만 → 축소 권고 없음. 기존 코드 재사용 여지 확인 —
`ProdAssemblyHttpTestBase` 의 프로파일 강제 패턴을 그대로 비-prod 로 복제하면 되므로 새 인프라 0.

---

#### 🛑 BLOCKER-1 — 신설 가드가 CI 에서 **0회** 실행된다

`.github/workflows/backend-ci.yml` 의 `assembly` 잡(L96~)은 `:modules:app:test` **만** 돌린다.
Task 1 이 `nonprod-assembly` 태그를 `test` 에서 제외하므로, 신설 가드는 **CI 에서 한 번도 실행되지 않는다.**
이것은 `TODOS.md §151` 이 이미 기록한 실패 양식과 동일하다 —
*"안 돌리면 봉인이 로컬 1회성 확인으로 끝나고 썩는다"*.

**처방 (같은 PR 안에서).**
1. `backend-ci.yml` `assembly` 잡의 Gradle 실행에 `:modules:app:nonProdAssemblyTest` 추가
   (서비스 컨테이너 `quay.io/tembo/pg16-pgmq` + 5433 은 이미 그 잡에 있으므로 추가 인프라 0).
2. `scripts/workflow/ci-module-coverage.test.ts` 에 **존재 단언 추가** — 현재 L80 이
   `/:modules:app:test/` 만 단언한다. 하드코딩 목록에 판별식을 붙이는 이 저장소 관례를 그대로 따른다.
   (룰 추가 시 일부러 위반을 넣어 FAIL 확인 — `CLAUDE.md §강제` 동형.)
3. **N1(변경 허용 경로)을 넓힌다** — `.github/workflows/**` · `scripts/workflow/**` 추가.

#### 🛑 BLOCKER-2 — 스펙의 "CI 부재" 전제가 낡았다

스펙·계획이 메모리(`no-backend-ci-and-assembly-merge-verification-traps`)를 근거로 "백엔드 CI 없음"을
전제했으나, **2026-07-27 에 `backend-ci.yml` 이 신설**돼 잡 4개(`modules` 매트릭스 9 · `assembly` ·
`lint` · `workflow-scripts`)가 돌고 있다 (`TODOS.md §135 해소`). Task 7 의 검증 절차가 로컬 전용으로만
쓰여 있어 CI 결과 확인이 빠졌다. → 완료 기준에 **CI 4잡 그린** 추가. 해당 메모리도 갱신 대상.

---

#### ⚠️ CONCERN-1 — 가드가 `bootRun` 보다 약하다 (`webEnvironment`)

Task 1 이 `webEnvironment = NONE` 이면 서블릿·시큐리티 자동설정이 backoff 되어 **웹 계층 결함을 못 본다.**
EC-1 의 "10번째 고장" 후보 중 필터체인/시큐리티 빈 문제가 있으면 가드를 그냥 통과한다.
그런데 이 테스트는 **별도 Gradle 태스크 = 별도 JVM** 이라 그 JVM 안에는 컨텍스트가 1벌뿐이다
→ **EC-7(이중 부팅) 위험 없이 `RANDOM_PORT` 로 올릴 수 있다.**
**권고.** prod 가드(`ProdAssemblyHttpTestBase`)와 **같은 충실도**로 맞춘다. `webEnvironment = RANDOM_PORT`.

#### ⚠️ CONCERN-2 — N2(prod 빈 구성 불변)를 **직접** 단언하는 테스트가 없다

계획은 "기존 prod 테스트가 통과하면 prod 불변"이라는 **간접 증거**에만 기댄다.
그런데 `excludeFilters` 는 프로파일을 가리지 않으므로 실수로 prod 빈을 지울 수 있는 유일한 축이다.
**권고.** Task 1 의 빈-개수 단언 헬퍼를 **prod 프로파일에서도** 돌린다
(기존 `BtsApplicationContextTest` 에 같은 11종 단언 추가). 삭제·추가 **양방향** 봉인
(메모리 `seal-closes-only-half-by-default` — 봉인은 기본값으로 절반만 닫힌다).

#### ⚠️ CONCERN-3 — Gradle 태그 설정이 두 블록으로 갈린다

`tasks.withType<Test> { useJUnitPlatform() }`(기존)와 `tasks.named<Test>("test") { useJUnitPlatform { excludeTags } }`(신규)가
분리되면 **선언 순서에 의존**한다. 누군가 블록을 위로 옮기면 `excludeTags` 가 조용히 덮인다.
**권고.** 한 블록에서 `name` 으로 분기해 단일 출처로 둔다.
```kotlin
tasks.withType<Test> {
    useJUnitPlatform {
        if (name == "nonProdAssemblyTest") includeTags("nonprod-assembly") else excludeTags("nonprod-assembly")
    }
    // 기존 contract.snapshot.update 전달 유지
}
```

#### ⚠️ CONCERN-4 — 비-prod 조립에서 automation 워커가 **처음으로** 살아난다

FR-C 로 `IssueMutationPort`·`IssueSnapshotPort` 가 생기면 automation `@Scheduled` 워커 4종
(`AutomationExecutionWorker` · `AutomationScheduleWorker` · `AutomationEventWorker` ·
`GitWebhookDeliveryCleanupWorker`)이 **비-prod 에서 처음 활성화**된다. 지금까지는 부팅 자체가 실패해
한 번도 없던 상태다. 이들은 dev postgres(5433)의 `q_automation_events` 를 폴링한다.

- `test` ↔ `nonProdAssemblyTest` 는 같은 Gradle 프로젝트라 **기본 직렬** → 동시 폴링 위험은 낮다.
- 그러나 개발자가 `bootRun` 을 띄운 채 테스트를 돌리면 **두 벌**이 된다 (메모리 `flaky-late-vs-never-arriving-message`).
- 실피해는 낮다 — ADR `2026-07-11-automation-prod-assembly` §배포 런북이 *"룰이 없으면 전부 무해 delete"* 로 기록.

**권고.** `nonProdAssemblyTest { mustRunAfter(tasks.named("test")) }` 로 순서를 못박고,
테스트 KDoc 에 *"dev 앱을 띄운 채 실행 금지"* 를 명시한다. 이 상태 변화 자체를 ADR §결과 에 남긴다.

---

#### 💬 NIT-1 — 뮤테이션 개수 불일치

스펙 §측정 가능한 완료 기준 2 는 "**9종** 각각에 뮤테이션", 계획 Task 5 표는 **11종**(M1~M11 — FR-B 위반 2종 포함).
→ **11로 통일.** (이 저장소가 반복해서 다친 "문서 간 카운트 drift" 양식.)

#### 💬 NIT-2 — ADR 에 BC 격리 예외를 명시할 것

`NonProdAssemblyPortConfig` 가 다른 BC 의 **어댑터 내부 클래스 3종**을 직접 import·생성한다
(`com.bts.issue.adapter.outbound.automation.*` 등). `BtsApplication` 이 이미 두 BC 의 `*Application` 을
import 하는 선례가 있고 조립 모듈의 직무상 정당하지만, **기록이 없으면 다음 리뷰가 다시 문제 삼는다.**
ADR §결정 에 "조립 모듈은 BC 격리 규칙의 명시적 예외" 를 근거와 함께 남긴다.

---

**BLOCKER: 2건 (CI 미배선 · 낡은 전제)** · CONCERN 4건 · NIT 2건
**판정. 계획 수정 후 진행 권고** — BLOCKER-1/2 는 "만들었는데 안 도는 가드"를 낳으므로 착수 전 반영 필요.
