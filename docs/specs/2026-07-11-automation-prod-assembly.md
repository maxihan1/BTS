# automation 모듈 prod 조립 배선 (:modules:app) + 부팅 검증 — 스펙

> slug: automation-prod-assembly · type: feature · BC: automation(배선) / :modules:app(조립)
> 작성: 2026-07-11 · FR-AT-02(#256) C4 후속

## 배경

automation BC(FR-AT-01 트리거 + FR-AT-02 액션)가 prod 조립 앱 `:modules:app`(#253)에 미배선. app이 8개 BC만 조립하고 automation을 빠뜨려, automation의 pgmq consumer·@Scheduled 워커·cross-BC 커맨드 포트가 prod에서 미가동. 배포 전 반드시 해소.

## 사용자 시나리오 (Given-When-Then)

- **S1 (조립 부팅)**: Given `:modules:app`이 prod 프로파일로, When 부팅하면, Then automation 빈(워커·서비스·어댑터 소비자)이 단일 ApplicationContext에 결선되고 automation 마이그레이션(V300~V303)이 `flyway_history_automation` 이력 테이블로 적용된다.
- **S2 (검증 테스트)**: Given `BtsApplicationContextTest`(@ActiveProfiles prod), When 컨텍스트 로드, Then 통과(빈 충돌·미배선·마이그레이션 실패 없음)하고 automation 빈 존재가 명시적으로 단언된다.
- **S3 (운영 동작)**: Given 조립 앱 기동, When 룰이 발화(FR-AT-01)해 `q_automation_execution`에 enqueue되면, Then `AutomationExecutionWorker`가 전역 `@EnableScheduling` 스케줄러에 의해 폴링·소비해 실제 이슈를 변경(FR-AT-02)한다.

## 기능 요구사항 (FR)

> 신규 제품 FR 아님(D-step 후속·인프라 배선). FR 총수 123 불변.

- **R1**: `backend/modules/app/build.gradle.kts`가 `implementation(project(":modules:automation"))`를 의존한다.
- **R2**: `FlywayAssemblyConfig`의 모듈 목록에 `"automation" to "classpath:db/migration/automation"`이 포함돼, 조립 부팅 시 automation 마이그레이션이 자기 이력 테이블에 적용된다.
- **R3**: `BtsApplicationContextTest`가 prod 프로파일로 automation 빈(예: `AutomationExecutionWorker`) 존재를 단언한다(vacuous contextLoads 보강).
- **R4**: "8개 BC" 표기가 나온 모든 조립 지점("9개 BC"로 정정): app `build.gradle.kts`(L1·L41 주석), `FlywayAssemblyConfig` KDoc, `BtsApplicationContextTest` L1 주석, `BtsApplication` KDoc(L1·L21).
- **R5 (스케줄링)**: automation 워커는 조립 앱의 전역 `@EnableScheduling`이 구동(notification 동형). `AutomationSchedulingConfig` opt-in property는 조립에서 불요 → inert 유지 + KDoc에 "조립 시 전역 스케줄러가 구동" 정정.
- **R6 (ADR)**: FR-AT-02 C4 절 framing 정정("모듈 신설 불가"→"기존 :modules:app에 automation 추가") + 신규 ADR `2026-07-11-automation-prod-assembly`.

## 비기능 요구사항 (NFR)

- **N1**: automation 모듈 **소스 불변**(BC 격리 유지). 예외: `AutomationSchedulingConfig` KDoc 한정 정정(R5, 동작 무관).
- **N2**: 신규 **필수** prod 설정 0(워커 poll-interval은 인라인 기본값 보유). 선택 설정만 문서화.
- **N3**: 조립 부팅 결정론적 — 마이그레이션 순서·pgmq 확장·cross-BC FK 무해 확인.
- **N4**: `AutomationBcArchTest`(BC 격리) 및 기존 automation 251 test 회귀 0. 조립 모듈은 BC 격리 예외(전 BC 의존 정당).
- **N5**: ktlint/detekt clean(app 모듈), `verify-master-plan.sh` 123/123.

## 데이터 모델 변경

**신규 스키마 없음.** automation의 기존 `V300__automation_rules`·`V301__pgmq_queue_automation_execution`·`V302__automation_actions`·`V303__automation_rules_actor_user_id`가 조립 DB(public 스키마)에 `flyway_history_automation` 이력 밴드로 처음 적용될 뿐. 대역 V300~은 identity(V001~)·issue(V001~)와 밴드 분리라 충돌 없음.

## 엣지 케이스 (plan/impl에서 실측 확인)

- **E1 (pgmq 확장 순서)**: V301이 pgmq 큐 생성. pgmq 확장(`create extension pgmq`)이 automation 마이그레이션 내부에서 생성되는지, 아니면 선행 BC(notification/slack) 마이그레이션에 의존하는지 확인. 확장 생성은 `if not exists` 멱등이어야 하며, 아니면 순서 보장 필요.
- **E2 (워커 조기 폴링)**: 전역 `@EnableScheduling`이 automation 워커를 컨텍스트 로드 직후 구동. `FlywayAssemblyConfig`(InitializingBean, 조기 실행)가 워커 폴링 전에 마이그레이션 완료 보장 확인 → 없는 테이블 조회로 인한 스케줄러 스레드 예외 방지.
- **E3 (cross-BC FK 순서)**: automation_* 테이블이 issue-tracking/기타 BC 테이블을 FK 참조하는지 확인. 참조 시 `FlywayAssemblyConfig` 실행 순서에서 automation을 참조 대상 뒤로 배치.
- **E4 (빈 이름 충돌)**: automation이 다른 BC와 같은 단순 클래스명/`@Bean` 메서드명을 갖는지 확인(`[[shared-util-per-bc-bean-naming-collision]]`). FQN 네임 제너레이터가 클래스명 충돌은 방어하나 `@Bean` 메서드명 충돌은 별도.
- **E5 (부팅 검증 인프라)**: `BtsApplicationContextTest`는 Testcontainers가 아니라 **외부 dev postgres(5433, pgmq 포함)** 필요(KDoc 명시). 검증 실행 전 `docker compose -f infra/docker-compose.dev.yml up -d postgres` 필수(`[[no-backend-ci-and-assembly-merge-verification-traps]]` — 백엔드 CI 부재, 로컬 검증).

## 제약 조건

- 완제품 기준. automation 소스 불변(N1).
- FR 총수 123 불변. 그래도 `scripts/verify-master-plan.sh` 통과 필수(카운트 표기 정합).
- 조립 계약 변경이므로 관련 ADR 정본·미러 동기화(CLAUDE.md §전수 동기화).

## 측정 가능한 완료 기준

1. `docker compose ... up -d postgres` 후 `./gradlew :modules:app:test` **green** — `BtsApplicationContextTest`가 prod 프로파일로 로드 + automation 빈 존재 단언 통과.
2. 부팅 로그에 `Flyway[automation] 마이그레이션 적용 4건 (location=classpath:db/migration/automation)` 출력.
3. automation 251 test·전 모듈 컴파일·app 모듈 ktlint/detekt **clean**.
4. `bash scripts/verify-master-plan.sh` 123/123.
5. automation 소스 diff 0(KDoc 정정 제외).

## Brainstorming Check (self sanity)

- ✅ 부팅 계약: cross-BC 포트 3개 전부 prod 구현 존재(도메인 실측) → 미배선/미구현 어댑터 위험 없음.
- ⚠️ **gap→plan 이관**: E1(pgmq 확장 순서)·E3(cross-BC FK)·E5(dev postgres 인프라)는 스펙 차단이 아니라 **plan/impl 실측 항목**. E2(조기 폴링)는 FlywayAssemblyConfig가 InitializingBean이라 완화되나 실측 확인.
- ⚠️ **gap→확인 필요**: `settings.gradle.kts`에 `:modules:automation` 등록 여부, app `application-prod.yml`에 automation 필수 설정 유무 — plan Task 0(사전 실측)로 편입.
- 결론: Maxi 결정 필요 gap 없음. 설계 갈림길(D1 스케줄링·D2 검증 강도)은 게이트 1에서 제시.
