# FR-IS-02 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) — 백엔드

> slug: fr-is-02-issue-types-backend
> type: feature
> agent: backend-engineer
> 생성: 2026-05-29

## Brief

FR-IS-02 이슈 타입의 **백엔드 범위(D1~D5)** 구현. issue-tracking BC.

**범위 한정 (Maxi 결정)**. 백엔드만. 프론트(D6 타입 셀렉터)·E2E(D7)는 진행 중인 transition-e2e(PR #34) 정리 후 별 PR로 분리 — 이슈 상세 화면(`issues.$key.tsx`/`IssueMetaPanel.tsx`)에서 전이 UI와 충돌 회피.

**잔여 본업**.
- D1 도메인 — Issue 도메인에 type 연결 (IssueType 참조)
- D2 명세 — Epic-Subtask 계층 제약
- D3 데이터 모델 — `issues.type_id` FK 신규 Flyway 마이그레이션 (V005 예정). 책임. db-engineer
- D4 백엔드 — 커스텀 IssueType CRUD API (POST/PATCH/DELETE) + Epic-Subtask 계층 검증
- D5 백엔드 테스트 — MockK 단위 + Testcontainers 통합

**사전 도입분 재활용 (FR-WF-02 PR #31)**.
- `issue_types` 테이블 V003 + 5 표준 seed
- IssueType 엔티티 / IssueTypeRepository
- read-only GET API (IssueTypeController)
- 프론트 use-issue-types (이번 PR scope 외)

**분류**. classify 원결과 migration/db-engineer → Maxi 결정으로 feature/backend-engineer 교정 (plan FR-IS-02 D라인 책임자 일치).

## 도메인 정리

- **BC**. issue-tracking (단일)
- **영향 엔티티**. IssueType (CRUD + hierarchy_level 추가), Issue (type_id 연결 — `Issue.create()` 시그니처 변경)
- **새 용어**. "이슈 타입(IssueType)" glossary 등재 + "에픽(Epic)" 정의 보강 (이슈 타입의 하나, 최상위 hierarchy_level). Maxi 승인 완료.

### grill-with-docs 결정 (2026-05-29)

| # | 결정 | 내용 |
|---|---|---|
| Q1 | `issues.type_id` 모델 | **NOT NULL + DEFAULT `task`**. 모든 이슈는 타입 보유(도메인 불변). `Issue.create()` 에 `typeId` 필수 파라미터 추가. 신규 이슈 미지정 시 `task` fallback |
| Q2 | Epic-Subtask 계층 scope | **타입 `hierarchy_level` 속성만** (Epic=1, Story/Task/Bug=0, Subtask=-1). `issue_types` 에 `hierarchy_level` 컬럼 ADD. 실제 `parent_id` 강제는 후속 FR 연기 (현재 issues 에 parent_id 부재, parent-child 는 FR-LK 영역) |
| Q3 | 표준 타입 보호 | **완전 불변**. `is_standard=true` 5종은 PATCH/DELETE 모두 거부 (409/403). 커스텀만 수정/삭제 가능 |
| Q4 | 커스텀 타입 삭제 | **Jira 방식(재할당 후 삭제)**. `DELETE /api/v1/issue-types/{id}?reassignTo={targetId}` — 사용 중이면 `reassignTo` 필수(없으면 409 + 사용 건수), `reassignTo` 주면 한 트랜잭션에 이슈 type_id 일괄 변경 + 타입 소프트 삭제. 미사용이면 reassignTo 없이 소프트 삭제 |

### 기존 결정 충돌 / 정합

- **phantom ADR 해소**. `issue-type-cross-bc-introduction` 이 IssueType.kt / 마이그레이션 통합 테스트 / `bc-migration-prefix-policy` ADR / 여러 plan·spec 에서 참조되나 미실재였음 (learnings 2026-05-20 phantom 재발). 본 작업에서 정식 작성.
- **관련 ADR**. [docs/adr/2026-05-29-issue-type-cross-bc-introduction.md](../adr/2026-05-29-issue-type-cross-bc-introduction.md) (생성됨)
- **사전 도입분 재활용 (FR-WF-02 PR #31)**. `issue_types` V003 + IssueType Aggregate(factory + 표준 5종) + IssueTypeRepository + read-only GET API. 본 PR 은 그 위에 CRUD + hierarchy_level + Issue 연결만 추가.
- **마이그레이션 영향**. 신규 Flyway (V005 예정, issue-tracking 네임스페이스) — (a) `issue_types.hierarchy_level` 컬럼 ADD + 5 표준 backfill, (b) `issues.type_id` FK ADD NOT NULL DEFAULT (task 의 id). 책임. db-engineer.

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-is-02-issue-types-backend.md](../specs/2026-05-29-fr-is-02-issue-types-backend.md)

핵심 시나리오 요약.
- 커스텀 IssueType CRUD — POST/PATCH/DELETE `/api/v1/issue-types`. 표준 5종은 완전 불변(409).
- 삭제 = 소프트 삭제. 사용 중이면 reassignTo 로 이슈 일괄 재할당 후 삭제(Jira 방식, 단일 트랜잭션).
- 모든 이슈는 타입 보유 — `issues.type_id` NOT NULL FK(default task), `Issue.create()` typeId 필수.
- hierarchy_level(Epic=1/표준0/Subtask=-1) 메타데이터. parent_id 강제는 후속 FR.

> office-hours(YC 아이디어 진단) 대신 직접 기술 스펙 작성 — 정의된 FR + grill 완료 작업이라 부적합(Maxi 결정). 메모리 [[bts-spec-office-hours-mismatch]].

## Brainstorming Check

✅ 통과 (1회 iteration). gap 5건 발견·반영.
- **G1 (BLOCKER급)**. `workflow_scheme_issue_type_mappings.issue_type_id` 가 `ON DELETE RESTRICT` 로 issue_types 참조하나, 소프트 삭제엔 FK 무력 → 워크플로우 스킴이 사라진 타입 매핑 방치. **해소** — `IssueTypeUsagePort` SPI 로 스킴 매핑 참조도 "사용 중" 판정에 포함(issues + 스킴 둘 다 보호). project-workflow 에 adapter 1개 추가 = BC 격리 예외(plan-review 검토).
- **G2**. 재할당 시 이슈 version+1, pgmq 이벤트 생략(소비자 FR-HS 미구현).
- **G3**. IssueTypeResponse + GET 응답에 hierarchyLevel 노출.
- **G4**. IssueResponse 에 typeId/typeKey/typeName 요약 노출.
- **G5**. IssueType 낙관적 잠금 version 부재 명시(타입 변경 드뭄, last-write-wins 허용).

## Plan

> 전 task TDD red→green→refactor. agent 기본값 backend-engineer. 파일 겹침은 bts-impl 이 자동 직렬화.

### Task 1: 마이그레이션 V005 — hierarchy_level + issues.type_id FK

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V005__issue_type_hierarchy_and_issue_type_fk.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/migration/IssueTypeHierarchyAndFkMigrationIntegrationTest.kt`]
- depends-on: []

**RED**. Testcontainers 통합 테스트 — Flyway migrate 후 (a) `issue_types.hierarchy_level` 존재 + epic=1/subtask=-1/task=0, (b) `issues.type_id` NOT NULL + 기존 row 가 task id 로 backfill, (c) FK 제약 존재, (d) **(B1)** key 부분 unique 동작 — 같은 key 활성 2건 INSERT 거부 + soft-delete 후 같은 key 재INSERT 허용. 마이그레이션 미작성 → migrate 실패.

**GREEN**. V005 SQL (spec §5.1) —
- `issue_types`. hierarchy_level ADD + epic/subtask backfill.
- **(B1)** 기존 테이블 레벨 `key` UNIQUE 제약 DROP → `CREATE UNIQUE INDEX ux_issue_types_key_active ON issue_types(key) WHERE deleted_at IS NULL` (soft-delete row 제외, Jira 재사용 UX). 기존 non-unique `ix_issue_types_key_active` 와 중복되면 정리.
- `issues`. type_id ADD COLUMN nullable → UPDATE backfill → SET NOT NULL → ADD FK + 인덱스.
- **(C4)** backfill 서브쿼리 `(SELECT id FROM issue_types WHERE key='task' AND deleted_at IS NULL)` — 활성 task 단건 보장(다중 row 방어). V004 형식(멱등 주석) 참고.

**REFACTOR**. 컬럼 COMMENT + 마이그레이션 의도 주석(backfill 사유 + 부분 unique 교체 사유 B1). ADR `bc-migration-prefix-policy` 네임스페이스 일관.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests *MigrationIntegrationTest`

### Task 2: IssueType 도메인 — hierarchyLevel 필드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/domain/IssueType.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/domain/IssueTypeTest.kt`]
- depends-on: []

**RED**. `IssueType.create(..., hierarchyLevel=1)` 호출 테스트 + 표준 5종 상수 hierarchyLevel 검증(EPIC=1, SUBTASK=-1, 나머지 0). 필드 없음 → 컴파일 실패.

**GREEN**. `hierarchyLevel: Int` 필드 추가 + `create()` 파라미터(default 0) + 표준 5종 상수에 값 부여.

**REFACTOR**. KDoc hierarchyLevel 설명(계층 위계, parent_id 강제는 후속 FR). 허용범위 {-1,0,1} require 검증.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTypeTest`

### Task 3: IssueType 도메인 예외 + errorCode

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/domain/IssueTypeExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/domain/IssueTypeExceptionsTest.kt`]
- depends-on: []

**RED**. sealed `IssueTypeDomainException` + 서브클래스(StandardImmutable/KeyDuplicate/KeyInvalid/InUse(usageCount,schemeMappingCount)/ReassignTargetInvalid/NotFound) 생성 테스트.

**GREEN**. IssueExceptions.kt 패턴 모사 — sealed 베이스 + 6 서브클래스. InUse 는 usageCount/schemeMappingCount 보유.

**REFACTOR**. KDoc + errorCode 매핑 표(spec §4.3) 참조 주석.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTypeExceptionsTest`

### Task 4: IssueTypeUsagePort SPI (outbound) + project-workflow adapter (inbound)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/port/outbound/IssueTypeUsagePort.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/outbound/IssueTypeUsageAdapter.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/outbound/IssueTypeUsageAdapterTest.kt`]
- depends-on: []

**RED**. (project-workflow) `IssueTypeUsageAdapter.countSchemeMappings(typeId)` 가 `workflow_scheme_issue_type_mappings` 에서 해당 type 참조 수 반환 — Testcontainers 통합. 매핑 N건 → N 반환.

**GREEN**. issue-tracking 에 `interface IssueTypeUsagePort { fun countSchemeMappings(issueTypeId: Long): Long }`. project-workflow 에 `@Service` adapter 구현(SchemeIssueTypeMappingRepository 재활용, 스칼라 카운트 — cartesian product 회피).

**REFACTOR**. KDoc — **(C2)** 방향 선례는 `IssuePermissionResolver`(소비 BC=issue-tracking 이 port 정의, 데이터/구현 BC 가 adapter 제공)와 **일치**. `IssueTypeLookupPort`(project-workflow 가 정의, issue-tracking 이 구현)는 반대방향(읽기 뷰) 대비 사례로만 인용. adapter 는 데이터 소유자가 구현하는 **outbound** 성격 → `adapter/outbound/`(기존 IssueTypeLookupAdapter 네이밍 일관). BC 격리 예외 사유 + ADR `issue-type-cross-bc-introduction` / `workflow-bc-cross-bc-port` 참조. ArchUnit 경계 주석.

**검증**. `./gradlew :backend:modules:project-workflow:test --tests IssueTypeUsageAdapterTest`

### Task 5: IssueTypeRepository — CRUD 확장 (insert/update/softDelete + hierarchy_level)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/repository/IssueTypeRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/repository/IssueTypeRepositoryCrudTest.kt`]
- depends-on: [1, 2]

**RED**. Testcontainers — insert(커스텀) → findById 일치 / update(name 변경) / softDelete(deleted_at 설정 후 findById null) / countIssuesByTypeId(이슈 참조 수) / reassignIssues(type_id 일괄 변경 + version+1). hierarchy_level 매핑 포함.

**GREEN**. 기존 read-only repo 에 hierarchyLevelField 추가 + insert/update/softDelete/countIssuesByTypeId/reassignIssues 메서드. 일괄 변경은 단일 UPDATE(`WHERE type_id=A`, `version=version+1`). 카운트는 스칼라(cartesian product 회피, learnings).

**REFACTOR**. KDoc + 부분 unique index(`deleted_at IS NULL`) 전제 주석.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTypeRepositoryCrudTest`

### Task 6: IssueTypeApplicationService — CRUD + 재할당 트랜잭션

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/application/IssueTypeApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/application/IssueTypeApplicationServiceTest.kt`]
- depends-on: [2, 3, 4, 5, 7]   # (C5) 7 추가 — Issue.create() 시그니처 변경이 issue-tracking 테스트 모듈 컴파일을 흔들므로 T7 먼저 안정화 (race 회피, learnings 2026-05-26 GREEN commit 흡수)

**RED**. MockK 단위 — create(key 중복→KeyDuplicate) / update(표준→StandardImmutable) / delete 미사용→소프트삭제 / delete 사용중 reassignTo 없음→InUse / delete reassignTo→일괄재할당+소프트삭제 / 스킴매핑 참조시(usagePort>0)→InUse(schemeMappingCount) / reassignTo 자기자신·미존재→ReassignTargetInvalid / SPI 실패→fail-closed(거부).

**GREEN**. `@Service @Transactional` (ArchUnit 룰 준수). repo + usagePort 조합. 표준 가드 우선(EC-7). 사용중 판정 = countIssuesByTypeId + usagePort.countSchemeMappings. 재할당은 단일 트랜잭션(reassignIssues + softDelete). 이벤트 미발행(G2).

**REFACTOR**. KDoc 트랜잭션 경계 + fail-closed 정책. 가드 순서 명시.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTypeApplicationServiceTest`

### Task 7: Issue 도메인 typeId + createIssue 반영

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [1, 2, 5]

**RED**. `Issue.create(..., typeId)` 필수 테스트 + createIssue 시 typeId 미지정→task fallback / 지정→그 타입. 기존 IssueApplicationServiceTest 갱신(typeId 반영).

**GREEN**. Issue 에 `typeId: IssueTypeId` 필드 + create() 시그니처. CreateIssueRequest 에 `typeId: IssueTypeId?`(nullable, null→task). IssueApplicationService.createIssue 가 typeId null 시 `issueTypeRepository.findByKey(IssueTypeKey("task"))` 로 fallback. insert 에 type_id 반영.

**REFACTOR**. KDoc + task fallback 사유(FR-6). 기존 호출자/fixture 전수 갱신(컴파일 강제).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTest --tests IssueApplicationServiceTest`

### Task 8: IssueTypeController CRUD 엔드포인트 + DTO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/IssueTypeController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/dto/IssueTypeResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/dto/CreateIssueTypeRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/dto/UpdateIssueTypeRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/IssueTypeExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/web/IssueTypeControllerTest.kt`]
- depends-on: [3, 6]

**RED**. MockMvc/슬라이스 — POST 201 / PATCH 200 / DELETE 204 / 표준 PATCH·DELETE 409 / key 중복 409 / key 형식 400 / DELETE?reassignTo= 204 / 사용중 reassignTo 없음 409. **(C3)** ProblemDetail 본문에 `usageCount`(이슈 참조)와 `schemeMappingCount`(스킴 매핑 참조)를 **별도 필드**로 노출 + schemeMappingCount>0 이면 reassignTo 로 해소 불가 안내 메시지 단언. RFC 7807 errorCode 6종(spec §4.3).

**GREEN**. 기존 IssueTypeController 에 POST/PATCH/DELETE 추가 + 요청 DTO 2종 + IssueTypeResponse 에 hierarchyLevel(G3). ExceptionHandler(IssueTypeDomainException→ProblemDetail) — IssueExceptionHandler 패턴. reassignTo 쿼리 파라미터.

**REFACTOR**. KDoc + Bean Validation(@field 검증) + errorCode 상수 정리.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueTypeControllerTest`

### Task 9: IssueResponse type 요약 노출

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`]
- depends-on: [7]

**RED**. 이슈 조회 시 IssueResponse 에 typeId/typeKey/typeName 포함 검증(Testcontainers). type join 매핑.

**GREEN**. IssueResponse 에 `typeId: Long` + `typeKey: String` + `typeName: String`(G4). IssueRepository 조회 SQL 에 issue_types join(스칼라 또는 단일 join, cartesian product 주의). toResponse 매핑.

**REFACTOR**. KDoc + join 성능 주석(type 단건).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests IssueRepositoryTest`

## Plan 메타

- task 수: 9
- 예상 wave (depends-on + 파일 겹침 기준, C5 반영):
  - wave1: T1, T2, T3, T4 (depends []) — 4 병렬 (SQL / IssueType.kt / Exceptions / SPI port·adapter, 파일 겹침 없음)
  - wave2: T5(1,2) — repo CRUD
  - wave3: T7(1,2,5) — Issue 도메인 시그니처 변경 (단독, 테스트 모듈 컴파일 안정화)
  - wave4: T6(2,3,4,5,7), T9(7) — service / IssueResponse (파일 겹침 없음, 2 병렬)
  - wave5: T8(3,6) — controller
- 예상 시간: 직렬 약 30분, wave 병렬 약 16분
- TDD 강제: yes (test→feat 커밋 순서 검증)
- BC 격리 예외: T4 (project-workflow adapter 1개, `adapter/outbound/`) — ADR issue-type-cross-bc-introduction 정당화
- 리뷰 반영: B1(부분 unique) + C2(SPI 방향/패키지) + C3(usageCount/schemeMappingCount 분리) + C4(서브쿼리 방어) + C5(wave 직렬화) 모두 반영 완료

## 리뷰 결과

### eng 집중 독립 리뷰 (code-reviewer, 2026-05-29) — 권고 GO_WITH_FIXES

**BLOCKER**.
- ~~**B1. EC-1이 실제 V003 스키마와 충돌 + T1 마이그레이션 누락.**~~ **✅ 해소 (2026-05-29 Maxi 결정 = Jira처럼 재사용 가능).** T1 V005 에 테이블 레벨 unique DROP + 부분 unique index(`WHERE deleted_at IS NULL`) 생성 추가. spec §5.1 (a') 반영. EC-1 유효.

**CONCERN (✅ 전부 반영 완료)**.
- C2. T4 SPI 방향 선례 인용 부정확. 패턴 자체(소비자 issue-tracking 정의 + 데이터소유자 project-workflow 구현)는 `IssuePermissionResolver` 선례와 방향 일치하나, plan/ADR이 인용한 `IssueTypeLookupPort`는 반대방향(읽기 뷰)이라 오도. adapter 패키지 inbound/outbound 명확화 필요.
- C3. EC-10 응답에 usageCount / schemeMappingCount **분리 노출** 보장 필요(T8 RED). schemeMappingCount>0은 reassignTo로 해소 불가 안내.
- C4. T1 backfill 서브쿼리 `WHERE key='task'`에 `AND deleted_at IS NULL`(또는 is_standard) 방어 추가.
- C5. wave3 T6/T7 동시 컴파일 race — T7이 Issue.create() 시그니처 변경→IssueApplicationServiceTest 다수 깨짐. T7→T6 직렬화 권장(plan 메타 "2병렬" 재검토).

**PASS**. fail-closed(EC-11) / 마이그레이션 순서(ADD→backfill→NOT NULL→FK) / V003<V005 task seed 보장 / @Transactional→@Service ArchUnit / 스칼라 서브쿼리 cartesian 회피 / TDD 강제 / 백엔드만 범위(transition-e2e 충돌회피).
