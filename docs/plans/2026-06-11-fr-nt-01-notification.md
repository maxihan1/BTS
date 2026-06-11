# FR-NT-01 — 이벤트별 알림 정책

> slug: fr-nt-01-notification
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-11

## Brief

FR-NT-01 — 이벤트별 알림 정책 (notification BC).
사용자 원문. "FR-NT-01 진행하자"
classify. type=backend, agent=backend-engineer, primary_bc=notification

product 명세. `docs/plan/product/notification-dashboard.md` §2.1
fr-index. `| FR-NT-01 | 이벤트별 알림 정책 | 필수 | notification-dashboard | §2.1 |`

## 도메인 정리

- **BC**: notification (신규 모듈 — `backend/modules/notification/` 부트스트랩 필요. BTS 첫 notification BC 작업)
- **영향 엔티티 (신규)**: NotificationPolicy (이벤트별 수신자×채널 규칙)
- **새 용어 (glossary 후보, Maxi 승인 대기)**:
  - 알림 정책 (NotificationPolicy) — "어떤 event_type이 발생하면 어떤 수신자 역할에게 어떤 채널로 알린다"는 규칙 데이터
  - 수신자 역할 (RecipientRole) — Reporter / Assignee / Watcher / ComponentLead / ProjectAdmin / 프로젝트 멤버 등. 실제 사용자 해석은 FR-NT-03
  - 알림 채널 (Channel) — 이메일 / 인앱(Inbox) / Slack / Teams / Webhook (SDD §9.1.1)
  - 정책 평가 엔진 (PolicyEvaluation) — 이벤트 → 적용 정책(수신자역할×채널) 목록을 순수 반환. 실제 전달 X
- **범위 경계 (Maxi 확정)**: 정책 도메인 + CRUD API + 평가 엔진까지. pgmq consumer/fanout/채널 전달/Inbox 기록은 FR-NT-02·03·04·UX-03로 분리
- **event_type 카탈로그 (Maxi 확정)**: SDD §9.1.2 전체 (미래 이벤트 포함). 발행원 없는 이벤트도 정책 정의 가능. 정확한 enum 목록은 스펙에서 확정
- **선행 조건 (Maxi 확정)**: STOMP WebSocket PoC(§1)는 차단 조건 아님 (정책은 실시간 전송 무관). identity-access 세션 ✅ / issue-tracking 이벤트 발행 ✅
- **기존 이벤트 인프라 (소비 대상)**:
  - `q_issue_events` ← issue.created/updated/transitioned/soft_deleted/mentioned (`IssueEventPublisher`, Jackson 다형성 `{"type":...}`)
  - `q_workflow_scheme_events` ← WorkflowSchemeAssigned/Updated/Deleted
  - 소비 방식: `SELECT * FROM pgmq.read(queue, vt, qty)` → JSON `type` 필드로 라우팅
- **기존 결정 충돌**: 없음 (notification BC 첫 ADR)
- **관련 ADR**: [docs/decisions/2026-06-11-notification-policy-bc-bootstrap.md](../decisions/2026-06-11-notification-policy-bc-bootstrap.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-nt-01-notification.md](../specs/2026-06-11-fr-nt-01-notification.md)

핵심 3줄 요약.
- notification 새 BC 모듈 부트스트랩 + `notification_policies(project_id?, event_type, recipient_role, channel, enabled)` 테이블 + SDD §9.1.2 매트릭스 시드(IN_APP)
- 3 enum(EventType 9종/RecipientRole/Channel 5종) + 정책 CRUD API 4종 + 카탈로그 API + 평가 엔진(전역/프로젝트 override = event_type 단위 replace)
- 모든 CRUD 권한 = SYSTEM_ADMIN(`SystemPermissionResolver`). 실제 전달/소비는 FR-NT-02+로 분리. 백엔드 D1~D5 (UI/E2E 후속 분리 권장)

## Brainstorming Check

✅ 통과 (self-review 1회 iteration). 발견 gap 1건 — 프로젝트별 정책 권한 배선이 cross-BC(identity-access prod adapter 추가)가 되어 "한 PR=한 BC" 충돌. Maxi 결정으로 해소(모든 CRUD = SYSTEM_ADMIN, 단일 BC). 데이터 모델 프로젝트별 override는 유지.

## Plan

> 범위: 백엔드 D1~D5 (UI D6 / E2E D7은 후속 PR — 게이트1에서 Maxi 확인).
> 제약: 새 notification 모듈은 단일 컴파일 단위 → wave 병렬 이득 제한적, 의존 체인 직렬 위주.
> 모든 코드는 완제품 품질 (PoC 금지). TDD red→green→refactor (Task 1 부트스트랩만 빌드검증).

### Task 1. notification 모듈 부트스트랩 + 마이그레이션 (인프라)

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `DATA.md`, `backend/modules/notification/build.gradle.kts`, `backend/modules/notification/detekt-baseline.xml`, `backend/modules/notification/src/main/resources/application.yml`, `backend/modules/notification/src/main/resources/db/migration/notification/V400__notification_policies.sql`, `backend/modules/notification/src/main/resources/db/migration/notification/V401__seed_default_policies.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/notification/src/test/kotlin/com/bts/notification/architecture/NotificationBcArchTest.kt`]
- depends-on: []

**비-TDD (인프라 task)**. 모듈 빌드 설정은 단위 테스트로 검증 불가 → 빌드 성공 + ArchUnit 그린으로 검증. 단 ArchUnit 룰은 "테스트 먼저" 역할(빈 모듈에 격리 룰 작성 후 코드가 그 위에 쌓임).

**구현**:
- `settings.gradle.kts`에 `include(":modules:notification")` 추가
- `build.gradle.kts` — project-workflow 템플릿 복제. jOOQ codegen 패키지 `com.bts.notification.jooq`, target `src/generated/jooq`, Testcontainers jdbc:tc URL로 `db/codegen/init_codegen.sql` 초기화. Flyway `classpath:db/migration/notification`. detekt baseline.
- `V400__notification_policies.sql` — 스펙 §4 DDL (테이블 + UNIQUE NULLS NOT DISTINCT + 부분 인덱스). `gen_random_uuid()` 사용(pgcrypto/PG13+). **V400** = DATA.md §4.1 새 BC 범위(V400~V499).
- `V401__seed_default_policies.sql` — SDD §9.1.2 매트릭스 19행(전역 project_id=NULL, 채널 IN_APP). memory: enum 카운트가드 영향 없음(타 모듈 무관).
- `init_codegen.sql` — V400 테이블 DDL 미러 (시드 제외 — codegen은 구조만 필요). memory: jooq-init-codegen-mirror.
- `DATA.md` §4.1 표 — `| notification | V400~V499 | V400, V401 |` 행 추가 (CLAUDE.md 전수 동기화 규칙).
- `NotificationBcArchTest.kt` — BC 격리(issue-tracking/project-workflow/identity-access 내부 패키지 import 금지) + jOOQ repository 화이트리스트 + @Transactional+@Service 룰. memory: archunit-vacuous-rule-silent-pass — 빈 모듈이라 vacuous PASS 위험, 일부러 위반 클래스 1개 넣어 룰 동작 확인 후 제거.

**검증**: `./gradlew :modules:notification:generateJooq :modules:notification:compileKotlin :modules:notification:test --tests '*NotificationBcArchTest'`

### Task 2. enum 3종 + NotificationPolicy 도메인 (TDD)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/domain/NotificationEventType.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/domain/RecipientRole.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/domain/Channel.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/domain/NotificationPolicy.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/NotificationPolicyTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/NotificationEventTypeTest.kt`]
- depends-on: [1]

**RED**: enum 카탈로그 + 도메인 불변식 테스트
- `NotificationEventType`: 9종 존재 + 문자열 매핑(`issue.created` 등) + `publishable` 메타(스펙 §3.1 표) + `fromString` 역매핑(미존재→null/예외)
- `RecipientRole`: 9종, `Channel`: 5종
- `NotificationPolicy`: (id, projectId?, eventType, recipientRole, channel, enabled, ...) 생성 + `toggle(enabled)` 불변식

**GREEN**: enum 3종 + NotificationPolicy 데이터 클래스 최소 구현

**REFACTOR**: KDoc(파일 L1 한국어 주석 포함), publishable 상수화

**검증**: `./gradlew :modules:notification:test --tests 'com.bts.notification.domain.*'`

### Task 3. NotificationPolicyRepository (jOOQ) — CRUD + 조회 (TDD 통합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/repository/NotificationPolicyRepository.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/NotificationPolicyRepositoryIntegrationTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/support/NotificationTestcontainersBase.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers 통합 테스트 (memory: concurrent-testcontainers-suite-flaky / singleton 패턴 `.apply { start() }`)
- insert/findAll(projectKey?)/findById/toggle(enabled)/delete
- UNIQUE 멱등 — 동일 (projectId NULL, event, role, channel) 재삽입 → 충돌(예외 또는 ON CONFLICT). memory: pg-null-distinct-on-conflict-idempotency
- 시드 검증 — V401 시드된 전역 정책 19행 중 `issue.created` 전역 정책 조회 확인
- 평가용 조회 — `findEnabledByEventType(eventType, projectId?)`

**GREEN**: jOOQ Repository 구현 (`com.bts.notification.jooq` 화이트리스트 — repository 레이어만)

**REFACTOR**: 쿼리 상수화 + KDoc

**검증**: `./gradlew :modules:notification:test --tests '*NotificationPolicyRepositoryIntegrationTest'`

### Task 4. NotificationPolicyEvaluator — 평가 엔진 (TDD)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/application/NotificationPolicyEvaluator.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/application/PolicyMatch.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/application/NotificationPolicyEvaluatorTest.kt`]
- depends-on: [2, 3]

**RED**: 평가 분기 테스트 (스펙 §6 알고리즘)
- 전역 기본 반환 (projectKey 없음 / 프로젝트가 해당 event_type 정책 0개)
- 프로젝트 override **replace** — 프로젝트가 event_type 정책 1개+ 보유 시 전역 완전 무시
- 프로젝트가 event_type 행 보유하나 **전부 enabled=false → 빈 목록**(전역 무시=독자관리, Maxi 확정). eng-review 발견
- enabled=false 제외
- 알 수 없는 / 정책 0개 event_type → 빈 목록(예외 아님)

**GREEN**: `evaluate(eventType, projectKey?): List<PolicyMatch>` — repository 조회 + replace 병합

**REFACTOR**: 병합 로직 분리 + KDoc. `@Transactional(readOnly=true)` + `@Service`(ArchUnit 룰)

**검증**: `./gradlew :modules:notification:test --tests '*NotificationPolicyEvaluatorTest'`

### Task 5. NotificationPolicyService — CRUD + SYSTEM_ADMIN 권한 (TDD)

**메타**.
- agent: `backend-engineer` (권한 게이트는 codereview에서 security-engineer 검토)
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/application/NotificationPolicyService.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/application/NotificationPolicyServiceTest.kt`]
- depends-on: [2, 3]

**RED**: CRUD + 권한 테스트 (SystemPermissionResolver mock)
- create/list/toggle/delete — 정상 흐름
- 비-SYSTEM_ADMIN actor → 403 성격 예외 (memory: fr-pm-04-guard-exception-message-http-leak — message 일반화)
- 중복 생성 → 409 성격 예외 (UNIQUE 위반 매핑)
- 잘못된 enum → 400 성격 예외

**GREEN**: `NotificationPolicyService` — `SystemPermissionResolver.isSystemAdmin()` 게이트 + repository 위임. `@Service` + `@Transactional`

**REFACTOR**: 예외 타입 정리(동명 예외 cross-package 주의 — memory: duplicate-exception-name-cross-package-status) + KDoc

**검증**: `./gradlew :modules:notification:test --tests '*NotificationPolicyServiceTest'`

### Task 6. REST 컨트롤러 + DTO + 카탈로그 API (TDD MVC)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/web/NotificationPolicyController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/dto/NotificationPolicyDto.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/dto/NotificationPolicyCatalogDto.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/NotificationExceptionHandler.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/web/NotificationPolicyControllerTest.kt`]
- depends-on: [5, 2]

**RED**: MVC 테스트 (스펙 §5)
- GET `/catalog` → enum 목록 + publishable (인증 사용자)
- GET/POST/PATCH/DELETE `/api/v1/notification-policies` → 정상 + 403(비admin)/409(중복)/400(enum)/404(미존재)
- actor 추출이 리소스 조회보다 먼저 (memory: auth-extraction-before-resource-lookup)
- catch-all 핸들러가 ResponseStatusException 삼키지 않게 (memory: catch-all-exceptionhandler-swallows-responsestatusexception)

**GREEN**: Controller + DTO + 카탈로그 + 예외→HTTP 상태 매핑

**REFACTOR**: DTO ↔ 도메인 매핑 분리 + KDoc. `@JsonInclude(NON_NULL)` ↔ 응답 스키마 정합

**검증**: `./gradlew :modules:notification:test --tests '*NotificationPolicyControllerTest'`

### Task 7. end-to-end 통합 테스트 (HTTP → DB)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/NotificationPolicyEndToEndIntegrationTest.kt`]
- depends-on: [6]

**RED→GREEN**: 실 Testcontainers + MockMvc end-to-end
- 정책 생성(HTTP) → 평가 엔진 반영 확인
- 프로젝트 override replace end-to-end
- 권한 403 / 중복 409 실제 HTTP 응답
- 시드된 전역 기본 정책 조회

**검증**: `./gradlew :modules:notification:test` (전체 그린)

## Plan 메타

- task 수: 7
- 예상 wave (bts-impl 계산): 1 → 2 → 3 → {4, 5} → 6 → 7 (약 6 wave). 단일 모듈 컴파일 공유로 사실상 직렬에 가까움 (memory: bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (Task 1 부트스트랩만 빌드검증 예외 — git log 검증 시 ArchUnit test 커밋이 코드보다 먼저)
- 추가 검증: ktlint, detekt(모듈 baseline 동결 — memory: detekt-baseline-module-pattern), 전체 backend 회귀 (`./gradlew :modules:notification:test`)
- D6 UI / D7 E2E: 후속 PR 분리 권장 (게이트1 Maxi 확인)

## 리뷰 결과

### plan-eng-review (2026-06-11) — eng 집중 독립 리뷰 (autoplan 스킵, memory: bts-review-plan-autoplan-overkill)

- **Scope challenge** ✅ — 새 BC라 파일 수 많지만 부트스트랩의 필연(overbuilt 아님). SystemPermissionResolver·project-workflow 빌드 템플릿 재사용 양호. UI/E2E 후속 분리로 범위 축소.
- **아키텍처** ✅ — BC 격리(ArchUnit), pgmq 소비측 분리, 평가 엔진 REST 비노출, SystemPermissionResolver 소비. project_id FK 부재는 BC 격리상 의도(orphan은 평가서 호출 안 됨).
- **코드품질** ✅ — 레이어 분리 명확, 예외 매핑(409/400/403/404), 메모리 함정(catch-all 핸들러·동명예외·guard 메시지 누출) plan 반영.
- **테스트** ✅ — 각 task RED 명시, Testcontainers singleton, end-to-end(Task 7), 시드 검증(Task 3).
- **성능** ✅ — 평가 단일 쿼리 + (event_type, project_id) 부분 인덱스, p95<50ms. 캐시는 FR-NT-02로 분리.
- **발견 1건 (해소)** — 평가 엔진 override의 enabled 판정 모호(프로젝트 행 보유+전부 비활성 시 동작). Maxi 결정으로 확정: 행 존재=독자관리→전역 무시, 활성 행만 반환(전부 비활성=빈 목록). spec §6·EC2·plan Task 4 반영.
- **BLOCKER**: 없음

### NOT in scope (명시적 제외)

- pgmq consumer + 실제 채널 전달(EMAIL/IN_APP/SLACK 발송) → FR-NT-02
- 수신자 역할 → 사용자 목록 해석(RecipientResolver) → FR-NT-03
- 사용자별 구독 override → FR-NT-04, Inbox 기록 → FR-UX-03
- 프로젝트 관리자에게 정책 위임(현재 전원 SYSTEM_ADMIN) → 후속 FR
- 정책 평가 캐시 → FR-NT-02 consumer 성능 단계
- UI(D6) / E2E(D7) → 후속 PR (게이트1 Maxi 확인)

### What already exists (재사용)

- `SystemPermissionResolver.isSystemAdmin()` (shared-kernel + identity-access prod adapter FR-PM-08) — 권한 게이트 재사용, 신규 0
- project-workflow `build.gradle.kts` / ArchUnit / jOOQ codegen 패턴 — 모듈 부트스트랩 템플릿
- issue-tracking/project-workflow pgmq 발행 인프라 — 소비 대상(본 FR은 정책만, 소비는 FR-NT-02)

### 병렬화 (worktree)

단일 notification 모듈 = 단일 컴파일 단위. 별도 worktree 병렬화 이득 없음. wave 내 병렬(T4/T5)은 bts-impl이 처리.
