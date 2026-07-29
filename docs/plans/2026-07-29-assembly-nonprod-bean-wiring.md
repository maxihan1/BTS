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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
