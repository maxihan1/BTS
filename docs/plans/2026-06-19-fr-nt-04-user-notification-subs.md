# FR-NT-04 — 사용자별 알림 구독 설정

> slug: fr-nt-04-user-notification-subs
> type: feature
> agent: backend-engineer (프론트/E2E task는 plan 메타 agent override)
> primary_bc: notification
> 생성: 2026-06-19

## Brief

**사용자 원문**. "fr-nt-04 진행하자"

**FR-NT-04 — 사용자별 알림 구독 설정** (notification BC, `docs/plan/product/notification-dashboard.md §2.4`)
사용자가 **이벤트 타입 × 채널** 단위로 알림 수신 여부를 opt-in/out 할 수 있게 한다.

- D1. 도메인 — UserSubscription
- D2. 명세 — opt-in/out 단위
- D3. 데이터 모델 — `user_notification_subs(user_id, event_type, channel, enabled)`
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/notifications`
- D5. 백엔드 테스트
- D6. 프론트 UI — 개인 설정 페이지 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**classify 결과**. type=feature(qa 오판정 override), agent=backend-engineer, primary_bc=notification, slug=fr-nt-04-user-notification-subs

**선행**. §2.1 FR-NT-01(알림 정책) 완료, §2.2 FR-NT-02(인앱+이메일 채널) 완료, §2.3 FR-NT-03(수신자 정책) 백엔드 완료.

## 도메인 정리

- **BC**: notification (단일 BC, cross-BC 없음 — user_id는 UUID 직접 저장, `Notification.recipientUserId` 선례)
- **영향 엔티티**: `UserSubscription`(신규 도메인), `NotificationWorker`(필터 통합), `Channel`/`NotificationEventType`(기존 enum 재사용)
- **새 용어**: **UserSubscription (사용자 알림 구독)** — 사용자별 (이벤트타입 × 채널) opt-out 설정. glossary 추가 후보(Maxi 승인 대기)
- **발송 파이프라인 통합 지점**: `NotificationWorker.dispatch` ②(수신자 해석) 이후 ③(채널 발송) 직전 — resolved recipient를 사용자 구독으로 필터
- **핵심 결정 (Maxi 확정 2026-06-19, ADR 박제)**:
  - D1. **opt-out 기본 수신** — 행 없으면 enabled=true
  - D2. **관리자 정책과 AND 결합** — 발송 = adminPolicy AND userSubscribed. 사용자는 끄기만 가능(reduce-only)
  - D3. **채널 = IN_APP + EMAIL만** (SLACK/TEAMS/WEBHOOK 제외)
  - D4. **이벤트 = NotificationEventType 10종 전부**
  - D5. **저장 = explicit 토글 행 + sparse**, `UNIQUE(user_id, event_type, channel)`
  - D6. **BC 격리** — user_id UUID 직접 저장
- **데이터 모델**: `user_notification_subs(user_id, event_type, channel, enabled)` — Flyway **V404** + init_codegen 미러
- **API**: `GET/PATCH /api/v1/users/me/notifications` (본인 것만, `currentActorId` 401 패턴 — NotificationPolicyController 선례)
- **마이그레이션 번호**: 최신 V403(notification) → 신규 **V404**
- **기존 결정 충돌**: 없음. FR-NT-01 정책과 AND 결합으로 공존
- **관련 ADR**: [docs/decisions/2026-06-19-fr-nt-04-user-notification-subscription.md](../decisions/2026-06-19-fr-nt-04-user-notification-subscription.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-nt-04-user-notification-subs.md](../specs/2026-06-19-fr-nt-04-user-notification-subs.md)

핵심 시나리오 3줄 요약.
- 설정 이력 없으면 관리자 정책이 허용하는 모든 알림 수신(opt-out 기본).
- `/settings/notifications`에서 이벤트×채널(인앱/이메일) 토글 → 즉시 저장, 끈 조합은 미수신.
- 발송 = 관리자 정책 AND 사용자 구독. 관리자가 끈 것은 개인이 켤 수 없음(reduce-only).

핵심 계약.
- API: `GET/PATCH /api/v1/users/me/notifications` (본인만, 401 패턴). PATCH upsert.
- 데이터: V404 `user_notification_subs` + init_codegen 미러. `UNIQUE(user_id,event_type,channel)`.
- 통합: `NotificationWorker.dispatch` 수신자 배치 필터(IN_APP/EMAIL 한정, N+1 금지).

## Brainstorming Check

✅ 통과 (1회 self-review, gap 4건 보강 — 워커 채널 범위/배치조회/i18n 라벨 재사용/매트릭스 정렬 결정성).

## Plan (← /bts-plan 채움)

## Plan

> 전부 notification BC 단일. jOOQ codegen 의존: T1(init_codegen 미러) → T3(repository, 생성 클래스 import).
> 채널 화이트리스트(IN_APP/EMAIL) 단일 출처 = T2 도메인 상수. controller(EC1)·service(FR5)·worker(EC9) 공유.

### Task 1. V404 마이그레이션 + init_codegen 미러 (user_notification_subs)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V404__user_notification_subs.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/notification/src/test/kotlin/com/bts/notification/migration/UserNotificationSubsSchemaTest.kt`]
- depends-on: []

**RED**: `UserNotificationSubsSchemaTest` (Testcontainers) — flyway migrate 후 `user_notification_subs` 테이블·`UNIQUE(user_id,event_type,channel)`·partial index(`WHERE enabled=false`) 존재 단언. 테이블 없어 실패.

**GREEN**:
- `V404__user_notification_subs.sql` — 스펙 §데이터 모델 DDL 그대로(id UUID PK, user_id/event_type/channel NOT NULL, enabled BOOL NOT NULL, created_at/updated_at, UNIQUE, partial index).
- `init_codegen.sql`에 동일 `CREATE TABLE user_notification_subs ...` 미러([[jooq-init-codegen-mirror]]) — jOOQ `USER_NOTIFICATION_SUBS` 생성 트리거.

**REFACTOR**: 컬럼 주석 + 마이그레이션 헤더 한국어 1줄.

**검증**: `./gradlew :backend:notification:generateJooq :backend:notification:test --tests UserNotificationSubsSchemaTest`

### Task 2. UserSubscription 도메인 + 채널 화이트리스트 상수

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/domain/UserSubscription.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/UserSubscriptionTest.kt`]
- depends-on: []

**RED**: `UserSubscriptionTest` — (1) `UserSubscription` data class(userId, eventType, channel, enabled, createdAt, updatedAt) 불변·copy 토글, (2) `UserSubscription.CONFIGURABLE_CHANNELS == setOf(Channel.IN_APP, Channel.EMAIL)`, (3) `isConfigurable(channel)` 헬퍼. 클래스 없어 실패.

**GREEN**: `UserSubscription.kt` — 도메인 + companion `CONFIGURABLE_CHANNELS` 상수(단일 출처) + `withEnabled(newEnabled, now)` 불변 copy. 시각은 Clock 주입(도메인 직접 생성 금지, NFR4).

**REFACTOR**: KDoc(opt-out 기본·AND 결합·화이트리스트 사유).

**검증**: `./gradlew :backend:notification:test --tests UserSubscriptionTest`

### Task 3. UserSubscriptionRepository (jOOQ upsert + 조회 + 워커 배치)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/repository/UserSubscriptionRepository.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/UserSubscriptionRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: `UserSubscriptionRepositoryTest` (Testcontainers) — (1) `upsert` 신규 INSERT + 재호출 시 `ON CONFLICT(user_id,event_type,channel)` UPDATE(멱등, EC3), (2) `findByUser(userId)` 저장 행만 반환, (3) `fetchDisabled(eventType, channel, userIds)` enabled=false 행의 userId만 Set 반환(워커용). 클래스 없어 실패.

**GREEN**: jOOQ 생성 `USER_NOTIFICATION_SUBS` 사용. upsert=`insertInto...onConflict(USER_ID,EVENT_TYPE,CHANNEL).doUpdate().set(ENABLED).set(UPDATED_AT)`. event_type=wireValue 문자열, channel=name 문자열 저장. updated_at은 호출자 now 주입.

**REFACTOR**: 컬럼 매핑 헬퍼 + KDoc.

**검증**: `./gradlew :backend:notification:test --tests UserSubscriptionRepositoryTest`

### Task 4. UserSubscriptionService (매트릭스 조회 + PATCH 검증/upsert)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/application/UserSubscriptionService.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/application/UserSubscriptionServiceTest.kt`]
- depends-on: [2, 3]

**RED**: `UserSubscriptionServiceTest` (mockk repo) — (1) `getMatrix(userId)` 저장 이력 0건 → 10 event × {IN_APP,EMAIL} = 20셀 전부 enabled=true(opt-out 기본, FR2/FR5), 저장 행 있으면 그 값 오버레이, 선언순서 정렬(EC11). (2) `patch(userId, entries)` 각 항목 upsert. (3) channel ∉ CONFIGURABLE → `IllegalArgumentException`(EC1). (4) 빈 entries → no-op + 현 매트릭스(EC10). 클래스 없어 실패.

**GREEN**: `@Service` + `@Transactional`. Clock 주입. getMatrix=기본 매트릭스 생성 후 findByUser 오버레이. patch=검증 후 일괄 upsert(부분적용 금지 EC8) → getMatrix 반환.

**REFACTOR**: 기본 매트릭스 생성 헬퍼 추출.

**검증**: `./gradlew :backend:notification:test --tests UserSubscriptionServiceTest`

### Task 5. Controller GET/PATCH /api/v1/users/me/notifications + DTO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/web/UserNotificationSubscriptionController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/dto/UserSubscriptionDto.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/web/UserNotificationSubscriptionControllerTest.kt`]
- depends-on: [4]

**RED**: WebMvc 통합 테스트 — (1) GET 인증 사용자 200 + 20셀, (2) PATCH (issue.commented,EMAIL,false) 200 + 반영 매트릭스, (3) channel=SLACK PATCH 400(EC1), 미지원 eventType 400(EC2), (4) 미인증 401(EC6). 컨트롤러 없어 실패.

**GREEN**: `@RestController("/api/v1/users/me/notifications")`. `currentActorId()` 401 패턴(NotificationPolicyController 선례 복제). enum 파싱 실패→IllegalArgumentException→`NotificationExceptionHandler` 400. DataResponse 래퍼 재사용. 요청/응답 DTO(SubscriptionEntry, SubscriptionMatrixResponse, PatchRequest).

**REFACTOR**: KDoc + enum 파싱 헬퍼.

**검증**: `./gradlew :backend:notification:test --tests UserNotificationSubscriptionControllerTest`

### Task 6. NotificationWorker 구독 배치 필터 통합

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/worker/NotificationWorker.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/worker/NotificationWorkerSubscriptionFilterTest.kt`]
- depends-on: [3]

**RED**: `NotificationWorkerSubscriptionFilterTest` (Testcontainers, 실 repo + 시드) — (1) 수신자 A가 (event,EMAIL) enabled=false 행 보유 → A의 EMAIL 알림 미생성/미발송, A의 IN_APP·타 수신자는 발송(FR7). (2) channel ∉ {IN_APP,EMAIL} 수신자는 필터 통과(EC9). (3) 비활성 행 0건 → 전원 발송(EC5). 필터 없어 실패(현재 전원 발송).

**GREEN**: `NotificationWorker`에 `UserSubscriptionRepository` 주입. `dispatch()`에서 `recipientResolver.resolve()` 직후 — 설정가능 채널 수신자 대상 `fetchDisabled` 배치 조회(이벤트당 ≤2쿼리, N+1 금지 NFR1)로 enabled=false 수신자 제거 후 발송 루프. 생성자 확장 → 기존 통합테스트 부팅 영향 점검([[fr-nt-03-recipient-resolver-done]] config stub 교훈).

**REFACTOR**: 필터 헬퍼 추출 + KDoc(AND 결합 지점 명시).

**검증**: `./gradlew :backend:notification:test --tests NotificationWorkerSubscriptionFilterTest`

### Task 7. 프론트 API + Zod 스키마 + React Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/user-notification-subscriptions.ts`, `apps/web/src/api/useUserNotificationSubscriptions.ts`, `apps/web/src/api/user-notification-subscriptions.test.ts`]
- depends-on: []

**RED**: vitest(MSW) — (1) `getSubscriptions()` GET 파싱(dataWrapper + Zod 20셀), (2) `patchSubscriptions(entries)` PATCH + `X-XSRF-TOKEN`(readXsrfToken) 주입 검증([[frontend-api-convention-per-bc]] CSRF 수동), (3) 훅 invalidate. 함수 없어 실패.

**GREEN**: Zod 스키마(eventType/channel/enabled, backend DTO 1:1 미러 — invent 금지 [[frontend-zod-backend-dto-contract-gap]]). apiGet/apiFetch(PATCH). React Query useQuery/useMutation(낙관적 또는 invalidate-only [[mutation-setquerydata-partial-response-flicker]]).

**REFACTOR**: 스키마 export 정리.

**검증**: `pnpm --filter web test user-notification-subscriptions`

### Task 8. /settings/notifications 매트릭스 토글 페이지 + 라우트 + i18n + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.notifications.tsx`, `apps/web/src/components/settings/NotificationSubscriptionMatrix.tsx`, `apps/web/src/components/settings/NotificationSubscriptionMatrix.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/i18n/ko.ts`, `apps/web/src/mocks/user-notification-subscription-handlers.ts`, `apps/web/src/mocks/user-notification-subscription-fixtures.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [7]

**RED**: 컴포넌트 vitest — (1) 매트릭스 렌더(이벤트행 × 인앱/이메일 열), (2) 셀 토글 → patch 호출 + 낙관적 반영, (3) 라벨은 ko.ts 재사용(EC12). 컴포넌트 없어 실패.

**GREEN**: settings.password.tsx 레이아웃(`mx-auto max-w-2xl`) 패턴. 토글은 버튼/체크박스(Switch 미설치 — NotificationPolicyTable 선례 버튼 토글). 이벤트/채널 라벨 ko.ts(기존 정책 라벨 재사용·누락분 보강). router.ts adapter 등록(code-based 패턴). MSW stateful 핸들러 + reset 헤더([[msw-mutation-stateful-refetch]]·[[e2e-msw-scenario-toggle-localstorage-flag]]).

**REFACTOR**: 매트릭스 셀 컴포넌트 추출 + a11y(aria-label 행 컨텍스트).

**검증**: `pnpm --filter web test NotificationSubscriptionMatrix && pnpm --filter web typecheck`

### Task 9. E2E (Playwright) — 설정 토글 + 영속

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/user-notification-subscriptions.spec.ts`]
- depends-on: [8]

**RED**: Playwright — (S4) loginAsAlice → /settings/notifications 진입 → 셀 토글 → 새로고침 후 유지, (추가) 채널 2열 표시. MSW stateful 핸들러 관통. spec 없어 실패.

**GREEN**: issue-fixtures loginAsAlice 재사용. getByRole + exact/컨테이너 한정([[playwright-getbyrole-exact-strict-mode]]). MSW store reset 헤더. serviceWorkers:'block' 금지([[e2e-msw-serviceworker-block]]).

**REFACTOR**: 셀렉터 헬퍼.

**검증**: `pnpm --filter web test:e2e user-notification-subscriptions`

## Plan 메타

- task 수: 9 (각 TDD 사이클)
- 코드 의존 그래프: T1·T2·T7 (no dep) → T3(1,2)·T8(7) → T4(2,3)·T6(3)·T9(8) → T5(4)
- 예상 wave (bts-impl 계산): W1[T1,T2,T7] · W2[T3,T8] · W3[T4,T6,T9] · W4[T5]
  - 단, backend 6 task는 notification 단일 모듈 test 컴파일 공유 → 동일 모듈 병렬은 직렬화 요인([[bts-plan-wave-gradle-module-compile]]). bts-impl이 실제 wave 결정.
- TDD 강제: yes (test→feat 커밋 순서 검증)
- 추가 검증: generateJooq, ktlintCheck, detekt, vitest, typecheck, playwright(qa)
- classify.json task_count: 9 — 단 현재 classify.json은 병렬 FR-PL-02가 점유([[bts-cache-multisession-collision]]). FR-NT-04 진실출처=이 plan. bts-impl 직전 classify-fr-nt-04 복원 시 task_count=9 기록.

## 리뷰 결과 (← /bts-review-plan 채움)
