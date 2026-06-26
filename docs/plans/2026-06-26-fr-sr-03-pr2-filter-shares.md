# FR-SR-03 PR2 — 저장 필터 공유

> slug: fr-sr-03-pr2-filter-shares
> type: backend
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-sr-03 pr2 진행해줘" (저장 필터 공유 기능)

FR-SR-03 "필터 저장 및 공유"의 2번째 PR. PR1(#191)에서 SavedFilter CRUD(PRIVATE 전용) + 실행을 완료했고, PR2는 **공유** 기능을 담당한다.

PR1 완료 메모리 기준 PR2 범위(잠정 — bts-spec에서 확정).
- **공유 = 대상 지정** 방식(PROJECT / GROUP / AUTHENTICATED, 지라식). `saved_filter_shares` 조인 테이블 신설.
- **cross-BC 멤버십 포트** 2종(GroupMembership / ProjectAccess) 신설 — 둘 다 shared-kernel에 부재.
- **가시성 4경로**(소유 / PROJECT 공유 / GROUP 공유 / AUTHENTICATED 공유).
- 공유받은(소유 아님) 필터용 **403 재도입**(spec EC4). PR1에서 데드코드로 제거했던 `SavedFilterForbiddenException` + handleForbidden을 visible-but-not-owner 용도로 부활.
- 별표 UI 연동(FR-UX-02 favorites 재사용) — 프론트 범위 여부 spec에서 확정.

classify 결과: type=backend, agent=backend-engineer, slug=fr-sr-03-pr2-filter-shares.

## 도메인 정리

- **BC**: search-export-import (PR1에서 첫 영속성 부트스트랩 완료, PR2는 공유 확장)
- **영향 엔티티**:
  - `SavedFilter` (PR1 기존) — 변경 없음(본체).
  - `SavedFilterShare` (신규) — 공유 대상 1행. `saved_filter_shares(filter_id, share_type, target_id)`.
- **새 cross-BC 포트 (shared-kernel, 신규)** — phantom 아님, 직접 조사로 부재 확인.
  - `GroupMembershipPort` — "사용자가 특정 그룹 소속인가". identity-access 구현(`com.atlas.bts.identity.group.UserGroupRepository` 위임).
  - `ProjectMembershipPort` — "사용자가 특정 프로젝트(키) 멤버인가". identity-access 구현(`ProjectMembershipRepository` 위임). **기존 포트 재사용 불가** 확정:
    - `IssueVisibilityPort`(shared-kernel)는 이슈 VIEW 가시성 전용 — 프로젝트 멤버 판정 아님.
    - `ProjectMembershipRepository`(identity-access)는 BC 내부 전용(노출 포트 아님) + `project_id UUID` 키. 공유는 `project_key` 문자열 저장 → 키↔id 변환 포트 필요.
- **확립된 결정 (PR1 ADR `2026-06-26-fr-sr-03-saved-filters.md` D2/D3/D4 — Maxi 확정)**:
  - D2 공유 모델: 대상 지정(PROJECT=project_key / GROUP=group id / AUTHENTICATED=로그인 전체). view 전용 MVP(소유자만 수정/삭제, 공유받은 사용자는 조회/실행/복제).
  - D3 가시성 판정: OR 4경로(소유 / AUTHENTICATED / PROJECT 멤버 / GROUP 소속). **fail-closed**(멤버십 포트 default 금지, 빈 부재 시 부팅 실패 — `IssueVisibilityPort` 선례).
  - D4 부트스트랩: PR1에서 완료(V600). PR2는 V601 `saved_filter_shares`.
- **403 재도입**: PR1에서 데드코드로 제거한 `SavedFilterForbiddenException`(현재 예외 계층에 없음)을 부활. 의미 = "필터를 볼 수는 있으나(공유받음) 소유자가 아니라 수정/삭제/공유관리 불가"(spec EC4). 비가시(존재 은닉)는 기존 404(`SavedFilterNotFoundException`) 유지(EC5).
- **기존 결정 충돌**: 없음. PR1 ADR이 SDD 10.3 모델을 이미 superseded. 본 PR은 그 ADR의 PR2 범위 실행.
- **새 용어(glossary 후보, Maxi 승인 필요)**: "저장된 필터 공유(SavedFilter Share)", "GroupMembershipPort/ProjectMembershipPort(cross-BC 멤버십 포트)". 대시보드 공유(`dashboard_shares`, 명시 사용자 + user_groups 재사용 안 함)와는 **다른 모델**(필터는 Jira식 PROJECT/GROUP/AUTHENTICATED 대상 지정) — 의도된 차이.
- **관련 ADR**: [docs/decisions/2026-06-26-fr-sr-03-saved-filters.md](../decisions/2026-06-26-fr-sr-03-saved-filters.md) (PR1, PR2 설계 포함) · [2026-05-27-shared-kernel-extraction.md](../decisions/2026-05-27-shared-kernel-extraction.md) (shared-kernel 포트 패턴) · cross-BC resolver 선례 (`2026-06-04-workflow-scheme-permission-prod-resolver.md` 등). **신규 ADR 불필요** — PR1 ADR이 PR2 결정을 이미 포함.

## 스펙

전체 스펙. [docs/specs/2026-06-26-fr-sr-03-pr2-filter-shares.md](../specs/2026-06-26-fr-sr-03-pr2-filter-shares.md)

핵심 결정(Maxi).
- **공유 = 필터 본문 임베드**. `POST/PUT /api/v1/filters` 바디 `shares:[{shareType,targetId}]` 교체(replace-all), 기존 OCC version 재사용. 전용 공유 엔드포인트 없음.
- **공유받은 목록 = 신규 `GET /api/v1/filters/shared`**. 기존 `GET /filters`는 소유만 유지.

핵심 시나리오 5줄.
- 소유자가 필터 바디에 PROJECT/GROUP/AUTHENTICATED 공유 지정 → 대상이 조회/실행 가능.
- 읽기(get/search)는 가시성 4경로(소유/AUTHENTICATED/PROJECT멤버/GROUP소속), 비가시 404 은닉.
- 쓰기(put/delete)는 소유자만. 가시-비소유 = 403, 비가시 = 404.
- 실행은 viewerUserId=actor라 공유받아도 권한 상승 0(PR1 패턴).
- cross-BC 멤버십 포트 2종(shared-kernel 신규 + identity-access 구현, fail-closed). project_key는 ProjectDirectory read-only 선례로 매핑.

데이터: `V601__saved_filter_shares.sql`(FK CASCADE 동일 BC, UNIQUE NULLS NOT DISTINCT, target NULL=AUTHENTICATED).
403 재도입: `SavedFilterForbiddenException` 부활(PR1 데드코드).

## Brainstorming Check

✅ 통과 (1 iteration). U1(project_key↔id)·U2(group UUID)를 코드(ProjectDirectory/UserGroup)로 검증·해소. 가시성 404/403 분기·멱등·CASCADE·fail-closed 커버. EC13(미멤버 프로젝트 공유) 저위험 수용, 적대적 리뷰 재검토 표시.

## Plan

> 9 task / 3 모듈(search-export-import · shared-kernel · identity-access). TDD red→green→refactor 강제.
> 보안 핵심(가시성/멤버십/fail-closed)은 security-engineer, 일반 백엔드는 backend-engineer, 마이그레이션은 db-engineer.

### Task 1. V601 saved_filter_shares 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/resources/db/migration/search-export-import/V601__saved_filter_shares.sql`, `backend/modules/search-export-import/src/main/resources/db/init_codegen.sql`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/persistence/SavedFilterSharesSchemaTest.kt`]
- depends-on: []

**RED**. `SavedFilterSharesSchemaTest`(SearchPersistenceTestBase 상속) — `saved_filter_shares` 테이블 존재 + CHECK(share_type)·CHECK(AUTHENTICATED↔target NULL)·UNIQUE NULLS NOT DISTINCT·FK CASCADE 단언. 테이블 부재로 실패.

**GREEN**. V601 작성(스펙 §데이터 모델 DDL 그대로). `init_codegen.sql`에 동일 DDL 미러([[jooq-init-codegen-mirror]] — 미러 안 하면 jOOQ 생성코드에 테이블 누락).

**REFACTOR**. 인덱스 주석 + DATA.md V600~V699 범위표에 V601 행 추가. PR1 ADR(`2026-06-26-fr-sr-03-saved-filters.md`) §결과에 "saved_filter_shares 하드삭제(조인테이블 성격, replace=delete-then-insert + FK CASCADE)" 한 줄 추가(C9, §1.2 규칙7 하드삭제 ADR 커버).

**검증**. `./gradlew :modules:search-export-import:test --tests '*SavedFilterSharesSchemaTest'`. ⚠️ V601 미점유는 **머지 직전 재확인**(FR-TL-01 #192 병렬, [[migration-vnumber-concurrent-branch-collision]]).

---

### Task 2. shared-kernel 멤버십 포트 2종 + ArchUnit 경계

**메타**.
- agent: `backend-engineer` (보안 검토: security-engineer)
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/membership/GroupMembershipPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/membership/ProjectMembershipPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/architecture/SharedKernelBoundaryArchTest.kt`]
- depends-on: []

**RED**. `SharedKernelBoundaryArchTest`에 새 `com.bts.shared.membership` 패키지가 원시 타입(UUID/String/Set)만 사용하고 어느 BC도 역참조 안 함을 단언하는 룰 추가([[archunit-vacuous-rule-silent-pass]] — 일부러 위반 넣어 fail 확인). 포트 미존재로 실패.

**GREEN**. 두 인터페이스 작성. KDoc에 **fail-closed**(default 금지, 빈 부재 시 부팅 실패 — `IssueVisibilityPort` 선례) 명시.
```kotlin
interface GroupMembershipPort { fun groupIdsOf(userId: UUID): Set<String> }
interface ProjectMembershipPort { fun projectKeysOf(userId: UUID): Set<String> }
```

**REFACTOR**. KDoc에 "새 보안 경로 금지" + BC 경계 규칙 문단(IssueVisibilityPort 톤 일치).

**검증**. `./gradlew :modules:shared-kernel:test --tests '*SharedKernelBoundaryArchTest'`

---

### Task 3. SavedFilterShare 도메인 + ShareType enum + 요청 검증/dedupe

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/domain/SavedFilterShare.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/domain/ShareType.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/domain/SavedFilterShareTest.kt`]
- depends-on: []

**RED**. `SavedFilterShareTest` — (a) PROJECT/GROUP는 targetId 필수(blank→reject, EC9), (b) AUTHENTICATED는 targetId NULL 강제(값 있으면 reject, EC9), (c) 알 수 없는 shareType→reject(EC8), (d) 집합 정규화 dedupe(EC7), (e) **dedupe 후** 상한 초과→reject(EC10 — "동일 공유 다수→dedupe 1개→통과" 케이스 포함, C4 순서 못박기). 도메인 부재로 실패.

**GREEN**. `ShareType` enum(PROJECT/GROUP/AUTHENTICATED) + `from(String)` 안전 변환 — **unknown은 `IllegalArgumentException`** 던짐(NoSuchElement/enum valueOf 예외 금지 → catch-all 500 회피, C3). `SavedFilterShare(shareType, targetId)` + `create` 팩토리(불변식, `IllegalArgumentException`, PR1 `SavedFilter.create` 일관 — 도메인은 application 예외 미참조). 컬렉션 정규화 헬퍼는 **dedupe → 그 다음 `MAX_SHARES_PER_FILTER` 검사** 순서(C4). 전부 `handleIllegalArgument`로 **400** 매핑.

**REFACTOR**. 상수/KDoc 정리. enum↔문자열 firstOrNull 정합 패턴([[fr-tm-02-template-vars-done]] 교훈).

**검증**. `./gradlew :modules:search-export-import:test --tests '*SavedFilterShareTest'`

---

### Task 4. identity-access GroupMembershipPort 구현

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/group/GroupMembershipAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/group/GroupMembershipAdapterTest.kt`]
- depends-on: [2]

**RED**. 통합테스트(identity-access Testcontainers) — 사용자가 속한 그룹 id 집합 반환(**정확히 `UserGroup.id`의 `UUID.toString()` canonical 형식** = saved_filter_shares.target_id 저장 형식과 일치, C2), 미소속 사용자→빈 Set. 구현 부재로 실패.

**GREEN**. `@Component GroupMembershipAdapter(userGroupRepository)` 구현. `groupIdsOf` = 사용자 그룹 멤버십 조회→UUID.toString() 집합. 기존 `UserGroupRepository`/`user_group_members` 조회 재사용(직접 SQL 추가 시 바인딩만).

**REFACTOR**. KDoc(fail-closed 방향 — 빈 반환은 "그룹 0개"이지 allow-all 아님) + null 안전.

**검증**. `./gradlew :modules:identity-access:test --tests '*GroupMembershipAdapterTest'`

---

### Task 5. identity-access ProjectMembershipPort 구현 (project_memberships JOIN projects)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipAdapterTest.kt`]
- depends-on: [2]

**RED**. 통합테스트 — 사용자가 멤버인 프로젝트의 **키** 집합 반환(**정확히 `projects.key` 원형** = saved_filter_shares.target_id 저장 형식과 일치, C2), 소프트삭제 프로젝트 제외, 미멤버→빈 Set. 실패.

**GREEN**. `@Component ProjectMembershipAdapter(jdbc)` — `SELECT p.key FROM project_memberships m JOIN projects p ON m.project_id=p.id WHERE m.user_id=:u AND p.deleted_at IS NULL`. `ProjectDirectory` read-only cross-BC 선례 패턴(같은 DB, projects 읽기 전용, 분리배포 시 SPI). 바인딩만(인젝션 방어).

**REFACTOR**. KDoc — projects 의존 컬럼(id/key/deleted_at) 명시(ProjectDirectory 톤) + fail-closed 방향.

**검증**. `./gradlew :modules:identity-access:test --tests '*ProjectMembershipAdapterTest'`

---

### Task 6. saved_filter_shares 리포지토리 (jOOQ) — replace/조회/sharedWith

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterShareRepository.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/persistence/JooqSavedFilterShareRepository.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterRepository.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/persistence/JooqSavedFilterRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/persistence/JooqSavedFilterShareRepositoryTest.kt`]
- depends-on: [1, 3]
- 분담: shares 테이블 CRUD(replaceShares/findByFilterIds)=`SavedFilterShareRepository`(신규). 가시성 쿼리(SavedFilter 반환: findVisibleById/findSharedWith)=`SavedFilterRepository` 확장(SavedFilter 행 매핑 재사용).

**RED**. Testcontainers 리포 테스트 — (a) `replaceShares(filterId, list)` delete-then-insert 원자성(EC2), (b) `findByFilterIds(Set)` 배치 로드(N+1 차단, B2 — 목록 응답 shares 적재용), (c) 필터 하드삭제 시 CASCADE 동반삭제(EC6), (d) 요청 중복→DB UNIQUE NULLS NOT DISTINCT 멱등(EC7), (e) `findSharedWith(actorId, projectKeys, groupIds, page, size)` — 비소유 가시 필터만 + **결정적 정렬(created_at,id) + LIMIT/OFFSET**(EC14/C1, 빈 집합 입력 시 AUTHENTICATED만), (f) **`findVisibleById(id, actorId, keys, groups)`** — owner OR 공유매칭이면 반환, 아니면 null(B3 단일 술어). **parity**: 같은 시드에서 `findVisibleById≠null` ⇔ `findSharedWith`에 포함(비소유일 때). 실패.

**GREEN**. 포트 인터페이스 + jOOQ 구현. **공유 술어 단일화(B3)** — `WHERE_VISIBLE_SHARES = (AUTHENTICATED OR (PROJECT AND target=ANY(:keys)) OR (GROUP AND target=ANY(:groups)))` 조각을 상수로 추출해 `findVisibleById`(= `id=:id AND (owner_id=:actor OR <조각>)`)와 `findSharedWith`(= `owner_id<>:actor AND <조각>`)가 **공유**. `findVisibleById`는 단건 get/search/쓰기게이트가 전부 재사용(Kotlin 이중평가 금지). 409는 native 23505 안전망([[jooq-exception-translator-409-dependency]] — 수동 DSLContext는 변환기 부재).

**REFACTOR**. SQL 상수 추출 + KDoc. ArchUnit 자체 jooq 룰 영향 없음 확인([[fr-sr-03-pr1-saved-filters-done]] — `com.bts.search.jooq` 한정).

**검증**. `./gradlew :modules:search-export-import:test --tests '*JooqSavedFilterShareRepositoryTest'`

---

### Task 7. SavedFilterForbiddenException + 서비스 가시성/게이트 로직

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterExceptions.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterService.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterWithShares.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/application/SavedFilterServiceVisibilityTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/application/SavedFilterServiceTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/SavedFilterIntegrationTest.kt`]
- depends-on: [2, 3, 6]
- 빌드그린 유지: `getByIdForOwner`는 **유지**(컨트롤러가 아직 호출 — Task 8이 전환 후 제거). 생성자에 SavedFilterShareRepository + GroupMembershipPort + ProjectMembershipPort 추가 → 기존 SavedFilterServiceTest(mockk 수동생성)·SavedFilterIntegrationTest(@TestConfiguration 빈 팩토리, 인라인 stub 포트 빈 emptySet 추가) 동반 갱신.

**RED**. `SavedFilterServiceVisibilityTest`(mockk 포트+repo) — (a) 가시성 4경로 각각(소유/AUTHENTICATED/PROJECT멤버/GROUP소속) get 성공 + **shares 동반 반환**(SavedFilterWithShares), (b) 비가시 get/search→404(EC5), (c) 가시-비소유 update/delete→403(EC4), (d) create/update 시 shares replace 위임(EC2, null=유지/[]=clear/[..]=교체 FR-2), (e) `listSharedWith`/`listOwned` 위임 + 각 항목 shares 배치 조합(B2), (f) **owner 단축경로** — 소유 필터 get 시 멤버십 포트 **미호출** 검증(C6, EC15). **mockk default+any() 가짜그린 주의**([[fr-sr-01-issue-filter-done]] — 실제 인자 매처/반환 단언, 포트 호출 횟수 verify). 실패.

**GREEN**. `SavedFilterForbiddenException(id)`(403 신호) 추가. read 메서드는 `SavedFilterWithShares`(filter+shares) 반환 — 단건은 `repo.findVisibleById`(**B3 단일 술어**) 사용하되 **owner면 포트 호출 없이 findById 단축**(C6); 목록은 `findByFilterIds` 배치로 shares 조합(B2, N+1 차단). `update`/`delete` 게이트 = `findById`로 로드(null→404) → `ownerId==actor`면 진행(단축) → 아니면 `findVisibleById≠null`이면 **403**(가시-비소유) else **404**(은닉). `create`/`update`에 shares 정규화+replace(단일 tx). `listSharedWith(actorId)` = 포트로 viewer 키/그룹 집합 조회 후 `repo.findSharedWith` 위임. 포트는 생성자 주입(non-null, fail-closed).

**REFACTOR**. KDoc 갱신(PR1 "PR2에서 403 분기" 주석 실현). ThrowsCount 억제 주석. self-invocation 없음 확인.

**검증**. `./gradlew :modules:search-export-import:test --tests '*SavedFilterServiceVisibilityTest'`

---

### Task 8. 웹 레이어 — DTO shares + 컨트롤러(임베드/`/shared`) + 403 핸들러 + 실행 가시성

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/web/dto/SavedFilterDtos.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/web/SavedFilterController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/web/SavedFilterSearchController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/web/SavedFilterExceptionHandler.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/savedfilter/application/SavedFilterService.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/web/SavedFilterControllerSharesTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/web/SavedFilterControllerTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/application/SavedFilterServiceTest.kt`]
- depends-on: [7]
- 고아 제거: 컨트롤러를 getVisibleById로 전환 후 `getByIdForOwner`가 미사용→SavedFilterService에서 제거(+SavedFilterServiceTest 해당 케이스 정리). Task 7 산출 위에서 작업(순차).

**RED**. MockMvc 웹 테스트 — (a) POST/PUT 바디 `shares` 파싱+검증(EC8~EC10 → **400**, 알 수 없는 shareType이 500 아닌 400임을 명시 단언 C3), (b) **모든 읽기 응답**(`GET /filters`·`/{id}`·`/shared`)에 `shares`+`isOwner` 포함, (c) **`GET /{id}` 가시-비소유 → 200 + shares**(B1 — 단건 조회도 가시성, S1 "조회"), (d) `GET /filters/shared` 200 비소유 목록 + **page/size 파라미터**(FR-7/C1), (e) 가시-비소유 PUT/DELETE→**403**, 비가시 모든경로→404(상태 매핑), (f) `GET /{id}/search` 가시성 게이트(비가시 404). 실패.

**GREEN**. `ShareRequest`/`ShareDto` + Create/Update Request에 `shares` 추가, `SavedFilterResponse.from(filter, shares, actorId)` 시그니처 확장. 컨트롤러 `list`/`get`/`create`/`update`가 `SavedFilterWithShares` 받아 응답 조립. **`SavedFilterController.get`을 `getByIdForOwner`→`getVisibleById`로 전환(B1)**. `/shared` 핸들러(page/size 검증, /search와 동일 정책). `SavedFilterSearchController`를 `getVisibleById`로 전환. `SavedFilterExceptionHandler`에 `handleForbidden`(403) 부활 — **detail은 일반 메시지("이 작업을 수행할 권한이 없습니다.")**, id 미노출(C5, [[fr-pm-04-guard-exception-message-http-leak]]). basePackages 커버 유지([[fr-sr-03-pr1-saved-filters-done]]).

**REFACTOR**. required 검증 헬퍼 재사용. catch-all이 403/404 안 삼키는지 확인([[catch-all-exceptionhandler-swallows-responsestatusexception]]). **기존 PR1 `SavedFilterControllerTest`의 단건 GET mock을 `getVisibleById`로 갱신**(B1 — 안 바꾸면 owner-게이트 잔존 vacuous green, [[plan-files-constructor-injection-existing-tests]]).

**검증**. `./gradlew :modules:search-export-import:test --tests '*SavedFilterControllerSharesTest'`

---

### Task 9. 통합 테스트 — HTTP+실 repo+stub 포트 가시성 매트릭스

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/SavedFilterShareIntegrationTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/MembershipPortTestConfig.kt`]
- depends-on: [8]

**RED**. `@SpringBootTest`(Testcontainers) end-to-end — 실 jOOQ repo + **userId 인지 stub 멤버십 포트 빈**(MembershipPortTestConfig: **userId→projectKeys/groupIds 시드 맵**, 사용자마다 다른 집합 반환 — C11, 무관 동일집합이면 음성케이스 가짜통과)으로 4경로 전수(소유/AUTHENTICATED/PROJECT/GROUP) + 403(EC4)/404(EC5) + CASCADE(EC6) + `/shared` 페이지네이션(EC14/C1) + 실행 권한상승0(FR-8) 검증. **parity(B3)** — 동일 시드에서 "단건 `GET /{id}` 가시 ⇔ `/shared` 목록 포함"(비소유 사용자) 양방향 일치. **vacuous 방지**([[archunit-vacuous-rule-silent-pass]]) — 음성 케이스(비멤버 404) 반드시 포함. 실패(빈/구현 미완).

**GREEN**. userId 인지 stub 포트 빈 등록(U3 부팅 레시피 — search 단독 컨텍스트는 identity-access 미로드라 stub 필수, [[no-cross-bc-deployment-assembly]] test-assembled 표준). 통합테스트 그린.

**REFACTOR**. 시드 헬퍼 정리. `fr-pm-permission-seed-migration-test-coupling` 류 카운트 결합 없음 확인.

**검증**. `./gradlew :modules:search-export-import:test --tests '*SavedFilterShareIntegrationTest'`

## Plan 메타

- task 수: 9
- 모듈: search-export-import(6) · shared-kernel(1) · identity-access(2)
- 예상 wave: 5 (W1: T1,T2,T3 / W2: T4,T5,T6 / W3: T7 / W4: T8 / W5: T9) — search 모듈 test 컴파일 직렬화 요인([[bts-plan-wave-gradle-module-compile]])로 실제는 더 직렬에 가까움
- TDD 강제: yes (test: 커밋이 feat: 보다 먼저)
- 추가 검증: ktlint + **detekt 별도 실행**(부트스트랩 모듈 ktlint-only 함정 [[backend-detekt-lint-debt-unmasked]]) + verify-master-plan.sh
- 보안 task(2/4/5/7): cross-BC 멤버십·가시성·fail-closed — security-engineer 책임 + codereview 적대적 검토 집중

## 리뷰 결과

### eng 독립 리뷰 (code-reviewer 적대적, 2026-06-26)

백엔드 plan은 autoplan 대신 eng 집중 독립 리뷰([[bts-review-plan-autoplan-overkill]]). PR1 실물 코드·선례(IssueVisibilityPort/ProjectDirectory)·DEVELOPMENT.md §1·DATA.md 대조.

**🔴 BLOCKER 3건 — 전부 plan/spec 수정으로 해소**.
- **B1** `GET /{id}` 단건 조회가 가시성 게이트로 전환 안 됨(FR-5↔Task8 불일치) + 테스트 공백(vacuous green). → **해소**: Task 8에 `SavedFilterController.get`→`getVisibleById` 전환 + 가시-비소유 200 RED + 기존 `SavedFilterControllerTest` mock 갱신 명시.
- **B2** 응답 `shares` 적재 경로 미정 + U4 미확정. → **해소**: U4=분리조회+서비스조합 확정. `SavedFilterWithShares` + `findByFilterIds` 배치 + `SavedFilterResponse.from(filter,shares,actorId)`(Task 6/7/8, spec FR-9).
- **B3** 가시성 4경로 이중 구현(SQL+Kotlin) fail-open 침투면. → **해소**: repo `findVisibleById`+`findSharedWith`가 단일 WHERE 조각 공유, 단건 get/search/게이트 전부 재사용. parity 테스트(Task 9).

**🟡 CONCERN 반영**.
- C1(`/shared` 페이지네이션) → FR-7/Task 6·8에 page/size+정렬 추가.
- C3(shares 검증 400, 500 회피) → Task 3 `IllegalArgumentException` 통일·`ShareType.from` 안전.
- C4(dedupe→cap 순서) → Task 3.
- C5(403 메시지 id 누출) → Task 8 일반 메시지.
- C6(owner 단축경로) → Task 7/EC15.
- C9(shares 하드삭제 ADR 커버) → Task 1 REFACTOR.
- C10(shares null=생략=유지) → spec FR-2.
- C11(stub userId 인지) → Task 9.
- C7(V601 충돌 재확인) → Task 1 검증 + 머지 직전.
- C8(대상 소프트삭제/비로그인 무해 잔류) → spec EC16/EC17.
- C2(어댑터↔search 계약 형식 drift) → T4/T5 어댑터 테스트가 저장 형식(UUID canonical/project key 원형) 단언.

**🟢 PASS**. BC 격리(project_memberships/user_group_members=identity-access 소유, projects만 read-only cross-BC=ProjectDirectory 선례)·fail-closed 기계화(생성자 non-null)·권한상승0(viewerUserId)·DDL 무결성(CHECK/UNIQUE NULLS NOT DISTINCT/CASCADE)·403/404 은닉 정합.

**총평**. BLOCKER 3 + 주요 CONCERN 전부 plan 반영 완료 → 구현 진입 가능.
