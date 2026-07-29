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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
