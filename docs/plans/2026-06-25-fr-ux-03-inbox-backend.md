# FR-UX-03 개인 알림 보관함 (Inbox) — 백엔드 D1~D5

> slug: fr-ux-03-inbox-backend
> type: feature
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-25

## Brief

**원문**. FR-UX-03 개인 알림 보관함 (Inbox) — notification 모듈에 inbox 읽음/보관 상태 관리 + 조회 화면.

**범위 (Maxi 확정 2026-06-25)**. 백엔드 D1~D5만 본 PR. 프론트 D6/D7(Inbox 페이지 + 카운트 뱃지 + E2E)은 후속 PR. FR-UX-02(즐겨찾기) 선례와 동일 패턴(#184 백엔드 / #185 프론트 분리).

**classify 정정**. classify-task가 'Inbox/조회 화면' 키워드로 `type=ui`로 오판 → 풀스택 feature로 정정, agent=backend-engineer.

**기존 인프라 (선행 발견)**.
- FR-NT-02(인앱 채널, V402)가 `notifications` 테이블을 이미 생성. `read_at` 컬럼 + `payload`(Inbox 딥링크용) + 인덱스 `ix_notifications_recipient (recipient_user_id, created_at DESC)`까지 FR-UX-03을 염두에 두고 미리 마련됨.
- 단, "읽는 조회 API"·"읽음/보관 상태 변경 API"·보관(`archived_at`) 컬럼은 아직 없음.
- 다음 마이그레이션 V번호 = **V407** (머지 직전 재확인 필요 — 동시 브랜치 충돌 주의).

**선행 도메인 쟁점 (→ /bts-domain에서 결정)**.
- product D3 계획은 별도 `inbox_items(user_id, notification_id, read_at, archived_at)` 조인 테이블을 제안. 그러나 `notifications`가 이미 수신자별(recipient_user_id) row로 fanout 저장되어 `read_at`도 보유 → 별도 조인 테이블이 불필요할 수 있음. `notifications`에 `archived_at`만 추가하는 방향 vs 별도 테이블 방향을 도메인 단계에서 확정.

## 도메인 정리

- **BC**: notification (`backend/modules/notification`, `com.bts.notification` 패키지 직속 — favorite/dashboard처럼 하위 패키지 신설 불필요. Notification Aggregate 자체 확장)
- **영향 엔티티**: `Notification` (기존 Aggregate). `archivedAt: Instant?` 필드 추가 + read/archive 상태 전이 메서드(`markRead`/`markUnread`/`archive`/`unarchive`, copy 기반).
- **새 용어**: 보관함(Inbox), 읽음/안읽음(read/unread), 보관(archive), 그룹화(grouping). glossary 추가 후보 (Maxi 승인 후 머지 시 동기화).
- **데이터 모델 (Maxi 확정 2026-06-25)**: 별도 `inbox_items` 테이블 폐기 → `notifications`에 `archived_at` 컬럼만 추가(V407). notification이 이미 수신자별 fanout row + `read_at` 보유. product D3 deviation → 같은 PR에서 §5.2 D3 동기화.
- **기능 범위 (Maxi 확정 2026-06-25)**: SDD 9.2 고급 포함 — 코어(탭 조회·페이지네이션·미읽음 카운트·읽음/보관 변경) + 그룹화(issue_key) + 검색(텍스트/발신자/기간) + 일괄 읽음.
- **상태 모델**: read(`read_at`) / archive(`archived_at`) 2축 독립. 탭 = 전체(archived IS NULL) / 안읽음(read IS NULL AND archived IS NULL) / 보관함(archived IS NOT NULL).
- **조회 경로**: 본인(recipient_user_id) + IN_APP 채널 한정. 인덱스 `ix_notifications_recipient` 재사용.
- **기존 결정 충돌**: 없음. FR-NT-02 V402 `read_at` 설계 의도 완성.
- **관련 ADR**: [docs/decisions/2026-06-25-fr-ux-03-inbox-data-model.md](../decisions/2026-06-25-fr-ux-03-inbox-data-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-25-fr-ux-03-inbox-backend.md](../specs/2026-06-25-fr-ux-03-inbox-backend.md)

핵심 요약.
- `notifications` 확장(V407: `archived_at` + `actor_user_id`). 별도 inbox_items 없음. read/archive 2축.
- API 5종: 목록 조회(탭/검색/페이지네이션) · 미읽음 카운트 · 읽음 토글 · 보관 토글 · 일괄 읽음.
- 검색 = 텍스트(title ILIKE) + 발신자(actor_user_id) + 기간(created_at). 그룹화 = issueKey 평면 필터.
- NotificationWorker가 event.actorId를 actor_user_id에 저장(FR-NT-02 코드, 같은 BC). 기존 행 NULL graceful.
- 본인 + IN_APP 한정. 401/404 격리, no-bump UPDATE.

## Brainstorming Check

✅ 통과 (office-hours 대신 직접 기술 스펙 작성 — 메모리 교훈 `bts-spec-office-hours-mismatch`).
sanity check 정신으로 2개 gap을 Maxi 결정으로 해소.
- gap1: product D3의 별도 inbox_items 테이블이 fanout 구조와 1:1 중복 → notifications 확장으로 변경(ADR).
- gap2: SDD 9.2 "발신자 검색"의 데이터 토대 부재(payload=null) → actor_user_id 컬럼 추가 + worker 저장 확정.

## Plan

> 모두 notification 단일 BC / 단일 Gradle 모듈. 같은 모듈 test 컴파일 직렬 특성상 wave 병렬
> 이득은 제한적(메모리 `bts-plan-wave-gradle-module-compile`). depends-on은 코드 의존성만 표기.

### Task 1. V407 마이그레이션 — notifications 확장 (archived_at + actor_user_id)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V407__notifications_inbox.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/notification/src/test/kotlin/com/bts/notification/migration/NotificationsInboxSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `NotificationsInboxSchemaMigrationTest` (Testcontainers, `V405DashboardsSchemaTest`/`FavoritesSchemaMigrationTest` 패턴) — V407 적용 후 `notifications.archived_at`·`notifications.actor_user_id` 컬럼 존재 + partial index `ix_notifications_recipient_unread` 존재 단언. 컬럼/인덱스 부재로 실패.

**GREEN**: V407 SQL — `ALTER TABLE notifications ADD COLUMN archived_at TIMESTAMPTZ; ADD COLUMN actor_user_id UUID;` + COMMENT 2종 + `CREATE INDEX ix_notifications_recipient_unread ON notifications (recipient_user_id) WHERE read_at IS NULL AND archived_at IS NULL;`. **init_codegen.sql에 동일 컬럼/인덱스 미러**(메모리 `jooq-init-codegen-mirror` — 누락 시 jOOQ codegen에 컬럼 안 생김).

**REFACTOR**: COMMENT 문구 정리, 마이그레이션 헤더 주석(L1 한국어).

**검증**: `./gradlew :backend:modules:notification:test --tests "*NotificationsInboxSchemaMigrationTest"` + `:backend:modules:notification:generateJooq` 새 컬럼 생성 확인.

### Task 2. Notification 도메인 확장 — archivedAt/actorUserId + 상태 전이

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/domain/Notification.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/NotificationTest.kt`]
- depends-on: []

**RED**: `NotificationTest` — `markRead(at)`→readAt 설정·이미 읽음이면 시각 보존(멱등), `markUnread()`→readAt null, `archive(at)`→archivedAt 설정·멱등, `unarchive()`→archivedAt null. read/archive 독립(archive해도 readAt 불변). 메서드 부재로 실패.

**GREEN**: `archivedAt: Instant? = null`, `actorUserId: UUID? = null`를 **trailing nullable default**로 추가(메모리 `no-bump-sidecar`/FR-NT-05 패턴 — 기존 생성자 호출처 무변경). `markRead`/`markUnread`/`archive`/`unarchive` copy 기반 메서드(불변 data class 유지). 멱등: markRead는 readAt 이미 있으면 그대로 반환.

**REFACTOR**: KDoc(@param 2개 추가), 메서드 KDoc.

**검증**: `./gradlew :backend:modules:notification:test --tests "*NotificationTest"`

### Task 3. NotificationRepository 확장 — 매핑 + findInbox + countUnread + no-bump UPDATE

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/repository/NotificationRepository.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/repository/InboxQuery.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/NotificationRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: `NotificationRepositoryIntegrationTest`(기존 확장, Testcontainers) —
  (a) `toDomain`/`insertIfAbsent`가 archived_at·actor_user_id 왕복 보존,
  (b) `findInbox(recipientUserId, InboxQuery, Pageable)` 탭(all/unread/archived) + 검색(q=title ILIKE, senderId=actor_user_id, issueKey, from/to=created_at) AND 결합 + 최신순 + IN_APP 한정 + 타인 격리 + 페이지네이션(totalElements),
  (c) `countUnread(recipientUserId)` = read_at NULL AND archived_at NULL AND IN_APP,
  (d) `updateReadAt(id, recipientUserId, Instant?)`/`updateArchivedAt(...)` 본인만(타인 0행)·no-bump(다른 컬럼 불변),
  (e) `markAllRead(recipientUserId, ids: List<UUID>?)` ids null→미읽음 전체, 지정→교집합, 반환=변경 건수.

**GREEN**: `InboxQuery` VO(tab enum, q?, senderId?, issueKey?, from?, to?). jOOQ DSL — 동적 조건 빌드(`Condition` 누적), `IN_APP` 고정 필터, `READ_AT`/`ARCHIVED_AT` 부분 UPDATE. `toDomain`/insert에 새 컬럼 매핑.

**REFACTOR**: 조건 빌더 private 추출, 탭→Condition 매핑 함수.

**검증**: `./gradlew :backend:modules:notification:test --tests "*NotificationRepositoryIntegrationTest"`

### Task 4. NotificationWorker — 발신자(actor_user_id) 저장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/worker/NotificationWorker.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/worker/NotificationWorkerTest.kt`]
- depends-on: [2]

**RED**: `NotificationWorkerTest`(기존 있으면 확장) — `buildNotification`이 만든 Notification의 `actorUserId == event.actorId`(mention 이벤트 actorId 채워짐), actorId 없는 이벤트는 null. 현재 미저장이라 실패.

**GREEN**: `buildNotification`에 `actorUserId = event.actorId` 한 줄 추가(`NotificationSourceEvent.actorId` 이미 존재).

**REFACTOR**: KDoc에 actor 저장 의도 추가.

**검증**: `./gradlew :backend:modules:notification:test --tests "*NotificationWorkerTest"`

### Task 5. InboxService — 조회/카운트/상태변경 비즈니스 로직

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/application/InboxService.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/application/InboxServiceTest.kt`]
- depends-on: [3]

**RED**: `InboxServiceTest`(repository mockk) — `listInbox`/`unreadCount` 위임, `markRead(actor,id,read)`→repository updateReadAt 반환 0이면 NotFound 예외(타인/부재 404 매핑), `markArchive`, `readAll(actor, ids?)` 반환 건수. 본인 검증이 repository 행수 기반인지.

**GREEN**: `@Service` `InboxService` — repository 위임 + 단건 변경 시 영향 행 0 → `InboxItemNotFoundException`(404). read true=now/false=null, archive 동일.

**REFACTOR**: now() 주입 가능하도록 `Clock`(메모리 `authcontroller-revokesession-timebomb` — 시각 의존 Clock 주입), 예외 클래스 분리.

**검증**: `./gradlew :backend:modules:notification:test --tests "*InboxServiceTest"`

### Task 6. InboxController + DTO — REST API 5종

**메타**.
- agent: `backend-engineer` (권한 게이트는 security-engineer 검토 대상 — currentActorId 401/404 격리)
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/web/InboxController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/dto/InboxItemResponse.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/web/dto/InboxRequests.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/web/InboxControllerTest.kt`]
- depends-on: [5]

**RED**: `InboxControllerTest`(MockMvc 슬라이스, `FavoriteControllerTest` 패턴) — 5 엔드포인트 200/스키마, 미인증 401, 타인/부재 404, 비-UUID senderId·잘못된 from/to 400, read-all updated 건수, 탭/검색 쿼리 바인딩. 컨트롤러 부재로 실패.

**GREEN**: `InboxController` `/api/v1/users/me/inbox` — `currentActorId()`(actor를 리소스 조회보다 먼저, probe 방지·메모리 `auth-extraction-before-resource-lookup`), `@PageableDefault(size=20)`, 5 핸들러. DTO: `InboxItemResponse.from(Notification)`, `ReadRequest{read}`, `ArchiveRequest{archived}`, `ReadAllRequest{ids:List<UUID>?}`. 검증 실패 400은 기존 `NotificationExceptionHandler`/`InboxItemNotFoundException` 매핑(basePackages 한정 확인 — 메모리 `domain-exception-http-handler-basepackage-scope`).

**REFACTOR**: DTO KDoc, size cap(EC9) 상수.

**검증**: `./gradlew :backend:modules:notification:test --tests "*InboxControllerTest"` + 전체 `:backend:modules:notification:test` 그린.

## Plan 메타

- task 수: 6
- 예상 시간: 직렬 약 30분. 의존성 그래프 longest path 4단계(T1/T2 → T3 → T5 → T6, T4는 T2 후 병렬). 단 단일 모듈 test 컴파일 직렬로 실질 병렬 이득 제한.
- 의존성 그래프:
  - T1 (V407) ← []
  - T2 (도메인) ← []
  - T3 (repository) ← [1, 2]
  - T4 (worker) ← [2]
  - T5 (service) ← [3]
  - T6 (controller) ← [5]
- 예상 wave: wave1{T1,T2} · wave2{T3,T4} · wave3{T5} · wave4{T6}
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 추가 검증: ktlint + detekt(모듈 baseline) + `:backend:modules:notification:test` 전체. E2E는 프론트 D6/D7 후속 PR.
- 전수 동기화 대상(머지 시): product `notification-dashboard.md` §5.2 D1~D5 `[x]` + D3 표기 정정 / fr-index 무변경(FR 카운트 불변) / DATA.md(하드삭제 영역 — notifications 이미 등재 확인) / glossary(보관함/읽음/보관).

## 리뷰 결과 (← /bts-review-plan 채움)
