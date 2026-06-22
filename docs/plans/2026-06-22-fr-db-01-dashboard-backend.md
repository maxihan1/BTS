# FR-DB-01 — 사용자 정의 대시보드 (백엔드 D1~D5)

> slug: fr-db-01-dashboard-backend
> type: backend
> agent: backend-engineer
> BC: notification-dashboard
> 생성: 2026-06-22

## Brief

FR-DB-01 사용자 정의 대시보드 (notification-dashboard BC §3.1). 대시보드 컨테이너
CRUD + 그리드 레이아웃 저장 + 개인/팀/공유 visibility 권한.

이번 워크트리 범위 = **백엔드 D1~D5** (도메인 · 명세 · 마이그레이션 · CRUD API · 백엔드 테스트).
D6(react-grid-layout UI) / D7(E2E)은 별도 후속 PR로 분리 (Maxi 확정 2026-06-22).

분류 교정 메모. classify-task가 "대시보드/그리드/레이아웃" 단어로 type=ui·agent=frontend-engineer·
primary_bc=agile-planning 오판정 → 실제 백엔드 주도 풀스택이므로 backend/backend-engineer/
notification-dashboard로 교정.

### 명세 정본
- product: `docs/plan/product/notification-dashboard.md §3.1`
- SDD: `docs/sdd/14-dashboard-reports.md §14.1`

### 선행 조건 (§0 — 전부 충족)
- identity-access §2.9 세션: FR-AU-09 완료 (PR #37)
- issue-tracking 이벤트 발행: FR-NT 시리즈 완료
- §1 STOMP WebSocket 기술 검증: FR-NT-02에서 구현·검증됨

### 범위 경계 (명세 분리)
- FR-DB-01 = 대시보드 컨테이너 (그리드 레이아웃 + visibility 권한 + CRUD) ← 이번 작업
- FR-DB-02 = 가젯 10종 (별도 FR)
- FR-DB-03 = URL 공유/임베드 (별도 FR)

## 도메인 정리

- BC: notification-dashboard (백엔드 모듈 = `notification`)
- 영향 엔티티: Dashboard (신규 Aggregate Root), DashboardShare (신규 자식), DashboardVisibility (신규 enum)
- 패키지: `com.bts.notification.dashboard.{domain, application, repository, web}`
- ID/user_id 타입: UUID (BC 관례, identity-access users.id 논리 참조 — FK 미설정)
- 다음 마이그레이션: V405 (notification 모듈, 머지 직전 재확인)

### Aggregate 모델
- **Dashboard** (Root): id, ownerId, name, description?, visibility, layout(JSONB), createdAt, updatedAt, deletedAt(소프트 삭제), version(OCC)
- **DashboardShare** (자식): (dashboard_id, user_id) 복합 PK, FK ON DELETE CASCADE 안전망 — TEAM 전용. 자체 deleted_at 없음(부모 따라감)
- **DashboardVisibility** (enum): PRIVATE(owner만) / TEAM(owner+shared) / ORG(인증 사용자 전체). PUBLIC=FR-DB-03 제외
- **삭제 = 소프트 삭제** (deleted_at, DATA.md §3 Maxi 확정). 모든 쿼리 deleted_at IS NULL 필터.

### Maxi 핵심 결정 (2026-06-22)
- TEAM 공유 = 대시보드별 명시 사용자 목록 (`dashboard_shares`), user_groups 재사용 아님
- 모듈 위치 = notification 모듈 내 dashboard 패키지 (새 Gradle 모듈 아님)

### 새 용어 (glossary 추가 후보, 머지 시 동기화)
- 대시보드 (Dashboard), 공유 범위 (Visibility), 대시보드 공유 (Dashboard Share)
- 가젯 (Gadget) — FR-DB-02 예고

### 기존 결정 충돌
- 없음 (BTS 첫 대시보드)

### 관련 ADR
- [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md](../decisions/2026-06-22-fr-db-01-custom-dashboard.md) (생성됨)

### 절차 메모
- 무거운 대화형 grill-with-docs 대신 직접 도메인 정리 + Maxi 핵심 결정 2건 확인 (BTS 직접-진행 패턴). 신규 도메인이나 핵심 갈림길은 Maxi 결정 완료, 나머지는 SDD §14.1 / product §3.1에 명확.

## 스펙

전체 스펙. [docs/specs/2026-06-22-fr-db-01-dashboard-backend.md](../specs/2026-06-22-fr-db-01-dashboard-backend.md)

핵심 시나리오 요약.
- Dashboard CRUD — POST/GET 목록/GET 단건/PATCH/DELETE. owner 기반 + OCC version.
- visibility 3종: PRIVATE(owner) / TEAM(owner+dashboard_shares 명시 사용자) / ORG(인증 사용자 전체).
- 목록 = owned ∪ shared-to-me ∪ ORG (UNION DISTINCT) + limit/offset 페이지네이션 (Maxi 결정).
- 접근 불가=404(존재 숨김), 조회되나 비owner 수정=403, OCC 충돌=409.
- TEAM 아니면 shares 정규화로 비움, owner는 shares에서 제거.

## Brainstorming Check

✅ 통과 (self-review 1회). 목록 ORG 포함 gap → Maxi 결정으로 해소. office-hours/brainstorming 스킬 대신 직접 스펙+self-review (명세 명확·도메인 정리 완료, BTS 직접-진행 패턴).

## Plan

> 공통 경로 접두사. main = `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard`,
> test = `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard`.
> 검증 = notification 모듈 test (정확한 gradle task path는 impl에서 settings.gradle 확인).

### Task 1. 도메인 — Dashboard Aggregate + DashboardVisibility + DashboardShare

**메타**.
- agent: `backend-engineer`
- files: [`.../domain/Dashboard.kt`, `.../domain/DashboardVisibility.kt`, `.../domain/DashboardShare.kt`, `.../../test/.../domain/DashboardTest.kt`]
- depends-on: []

**RED**: `DashboardTest.kt`
- 빈/공백 name → 도메인 예외
- visibility != TEAM 이면 shares 빈 집합으로 정규화
- sharedUserIds 에 owner 포함 시 정규화로 제거 + 중복 dedup(Set)
- name > 200자 / sharedUserIds > cap → 예외
- **[C4] layout JSONB ≤ 64KB → 예외** (`layout.toString().toByteArray().size <= 65536`, Jakarta @Size 불가)
- withChanges(부분 수정) 시 version 증가, updatedAt 갱신

**GREEN**: domain 클래스 3종 + enum. 불변식을 팩토리(create)/변경(applyPatch) 메서드에 캡슐화.

**REFACTOR**: 상한 상수(MAX_NAME_LEN/MAX_SHARES) 추출 + KDoc(평문, ktlint KDoc 함정 회피).

**검증**: notification test --tests `*DashboardTest`

### Task 2. 데이터 모델 — V405 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V405__dashboards.sql`, `<init_codegen.sql 경로>`]
- depends-on: []

**RED**: **[C3] `V405DashboardsSchemaTest` 필수 작성** (기존 UserNotificationSubsSchemaTest 패턴) — dashboards/dashboard_shares 테이블·컬럼(deleted_at 포함)·부분 인덱스(WHERE deleted_at IS NULL)·FK CASCADE 존재 단언. 선택 아님(false-green 방지).

**GREEN**: 스펙 §데이터 모델의 DDL 그대로. dashboards(+owner/visibility 인덱스) + dashboard_shares(FK CASCADE + user_id 인덱스). init_codegen.sql에 동일 DDL 미러(jOOQ codegen 입력 — 누락 시 빌드 깨짐, 메모리 교훈).

**REFACTOR**: 컬럼 COMMENT 추가(기존 V402 스타일).

**검증**: jOOQ codegen(빌드) + Task 3 repository 테스트 그린.

### Task 3. Repository — DashboardRepository (jOOQ) CRUD + shares + 목록 UNION 페이지네이션

**메타**.
- agent: `backend-engineer`
- files: [`.../repository/DashboardRepository.kt`, `.../../test/.../repository/DashboardRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers
- insert/findById/update(version bump)/delete
- shares insert/replace, delete 시 CASCADE 정리
- 목록 3종: owned / shared-to-me / ORG → UNION DISTINCT(owned이면서 ORG는 1건) + updatedAt desc + limit/offset
- 빈 목록·페이지 경계

**GREEN**: DSLContext 기반 jOOQ 구현(기존 NotificationPolicyRepository 패턴). 목록 item = UNION(owned/shared/org) DISTINCT + updatedAt desc + limit/offset. **[C2] total count는 별도 COUNT 서브쿼리**(`SELECT COUNT(*) FROM (UNION) sub`) — item 쿼리와 분리해 cartesian product 회피. **[소프트 삭제] 모든 쿼리(단건/목록/update/delete)에 `deleted_at IS NULL` 필터. delete = `UPDATE dashboards SET deleted_at = now()`(하드 DELETE 금지). shares 읽기는 부모 deleted_at IS NULL JOIN 필터.**

**REFACTOR**: 매핑 함수 추출.

**검증**: notification test --tests `*DashboardRepositoryTest`

### Task 4. Service — DashboardService (권한 404/403 · 정규화 · OCC)

**메타**.
- agent: `backend-engineer`
- files: [`.../application/DashboardService.kt`, `.../../test/.../application/DashboardServiceTest.kt`]
- depends-on: [1, 3]

**RED**:
- create(actor) → owner=actor
- get: PRIVATE 남의 것 → 404, TEAM 공유대상 → 200, ORG → 200
- update/delete: 조회 불가 → 404, 조회되나 비owner → 403, owner → 성공
- **[C1] delete 404/403 분기** — findById(deleted_at IS NULL) → owner 비교(403) → 소프트 delete(deleted_at 설정). 단일 rowcount로 404/403 판정 금지. 이미 삭제된 건 404(멱등). version 조건은 delete에 불요.
- OCC version 불일치(update) → 409
- visibility 정규화(서비스에서 도메인 위임)
- 목록: actor 기준 owned ∪ shared ∪ ORG

**GREEN**: 서비스 + 권한 판정(actor 추출 → 리소스 조회 → owner/visibility 비교). SYSTEM_ADMIN 예외 없음. 단일 트랜잭션. **[C6] PATCH = 도메인 `applyPatch()` → 정규화 → repository.update() 단일 경로**(도메인 우회 금지).

**REFACTOR**: 권한 판정 헬퍼 추출. 예외 타입은 BC 내 신규 정의(plain 도메인 예외 → ExceptionHandler 매핑).

**검증**: notification test --tests `*DashboardServiceTest`

### Task 5. Web — DashboardController + DTO + ExceptionHandler 연동

**메타**.
- agent: `backend-engineer`
- files: [`.../web/DashboardController.kt`, `.../web/dto/DashboardDtos.kt`, `.../web/DashboardExceptionHandler.kt`(또는 기존 핸들러 확장), `.../../test/.../web/DashboardControllerTest.kt`]
- depends-on: [4]

**RED**: MockMvc/통합
- POST 201, GET 목록 200(+페이지네이션 쿼리), GET 단건 200/404, PATCH 200/400/403/404/409, DELETE 204/403/404
- 미인증 401
- 요청/응답 DTO 직렬화(NON_NULL ↔ 프론트 계약), version 필수

**GREEN**: 컨트롤러(currentActorId 사용) + 요청/응답 DTO + 도메인 예외→HTTP 매핑. 페이지네이션 응답은 기존 목록 API 관례 grep 후 동일 형식. **[C5] 에러코드 prefix = `NOTIF_DASHBOARD_*`**(notification BC 고정). 기존 NotificationExceptionHandler 확장 vs 별도 핸들러는 일관되게 1택.

**REFACTOR**: DTO 변환 from() 정리.

**검증**: notification test --tests `*DashboardControllerTest`

### Task 6. ArchUnit — BC 격리 룰 (identity-access import 0)

**메타**.
- agent: `backend-engineer`
- files: [`.../../test/.../architecture/NotificationBcArchTest.kt`]
- depends-on: [1, 4, 5]

**RED**: dashboard 패키지가 identity-access를 직접 import 하지 않음을 단언하는 룰 추가. **vacuous 통과 방지** — 일부러 위반 import 한 줄 넣어 룰이 실제로 fail 하는지 확인 후 제거(메모리 교훈).

**GREEN**: 기존 격리가 지켜지면 통과. 위반 시 cross-BC 포트로 교정.

**검증**: notification test --tests `*NotificationBcArchTest`

## Plan 메타

- task 수: 6
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- wave 예상: 대부분 직렬(같은 notification 모듈 + 의존 체인). Wave1=[T1,T2](files 비겹침이나 동일 모듈 컴파일 직렬 요인), Wave2=[T3], Wave3=[T4], Wave4=[T5], Wave5=[T6]
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산 (단일 모듈이라 직렬 dispatch 예상)
- 추가 검증: ktlint + detekt(--rerun-tasks, false-green 회피) + jOOQ codegen 빌드

## 리뷰 결과

### plan-eng-review (backend-engineer 독립 리뷰, 2026-06-22)

종합 판정: **PASS_WITH_CONCERNS** (BLOCKER 없음).

항목별: 권한판정 OK · OCC CONCERN · BC격리 OK · 마이그레이션 CONCERN · 목록UNION CONCERN · 트랜잭션 OK · TDD순서 OK · visibility정규화 CONCERN · scope OK.

**impl 전 반영 필수 CONCERN (plan task에 반영 완료).**
- C1 (Task 4). OCC DELETE의 404/403 분기를 단일 DELETE rowcount로 판정 금지 → findById → owner 비교(403) → delete 순서. version 조건은 DELETE에 불요.
- C2 (Task 3). 목록 total count는 별도 COUNT 서브쿼리로 분리(`SELECT COUNT(*) FROM (UNION) sub`). item 쿼리와 분리해 cartesian product 회피.
- C3 (Task 2). `V405DashboardsSchemaTest`를 **필수 RED**로 격상(테이블/컬럼/CASCADE/인덱스 단언). 기존 UserNotificationSubsSchemaTest 표준. false-green 방지.
- C4 (Task 1). layout JSONB ≤ 64KB 검증을 도메인 RED에 추가(`layout.toString().toByteArray().size <= 65536`). Jakarta @Size로는 불가.
- C5 (Task 5). 에러코드 prefix = `NOTIF_DASHBOARD_*` (notification BC 고정 prefix). 기존 NotificationExceptionHandler 확장 vs 별도 핸들러 결정은 impl에서 일관되게.
- C6 (Task 4/5). PATCH는 도메인 `applyPatch()` → 정규화 → repository.update() 단일 경로 강제(도메인 우회 금지, 메모리 교훈).

BLOCKER: 없음.

### 추가 발견 (controller DATA.md 대조, impl 직전, 2026-06-22)
- **소프트 삭제 누락** — plan/스펙이 하드 삭제(CASCADE) 가정 → DATA.md §1.2/§3 위반 소지. Maxi 확정 = 소프트 삭제(deleted_at). 스펙/plan/ADR DDL·쿼리·시나리오 전수 동기화 완료.
