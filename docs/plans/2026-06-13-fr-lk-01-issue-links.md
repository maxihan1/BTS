# FR-LK-01 — 이슈 링크 (blocks/relates/duplicates/clones/parent-child)

> slug: fr-lk-01-issue-links
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-13

## Brief

FR-LK-01 — 이슈 간 링크. blocks / relates / duplicates / clones / parent-child 관계를 이슈끼리 맺을 수 있게 한다. BC=issue-tracking, SDD §5.3.1, 우선순위 필수.

- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**. issue-tracking
- **범위(Maxi 확정)**. 백엔드 D1~D5만. 프론트 UI(D6)·E2E(D7)는 후속 PR.
- **핵심 결정(Maxi 확정 → ADR)**. 링크와 parent-child를 **별개 메커니즘**으로 분리(옵션 B).
  - `issue_links` 테이블 — `link_type ∈ {blocks, relates, duplicates, clones}` 4종.
  - `issues.parent_id UUID NULL` 컬럼 — parent-child(Subtask → 부모) 구조적 계층(SDD §5.8 정석).

### 영향 엔티티
- **IssueLink (신규)**. `issue_links(id BIGINT identity, source_id UUID, target_id UUID, link_type VARCHAR(30), created_by UUID, created_at)`. source/target은 같은 BC(issues) → 실 FK. created_by는 users.id(타 BC) → FK 미적용, ApplicationService guard.
- **Issue.parent_id (신규 컬럼)**. `issues.parent_id UUID NULL REFERENCES issues(id)`. 단일 부모.

### 유비쿼터스 언어
- **링크(Link)**. 이슈 간 참조 관계. 방향성 있음(relates만 대칭).
- **링크 타입(LinkType)**. blocks(막음)/relates(관련)/duplicates(중복)/clones(복제).
- **역방향 표시(inverse/bidirectional, D2)**. 한 방향 row 1개만 저장, 반대쪽 이슈 화면에선 역명칭("is blocked by" 등) 계산 표시. 이중 저장 안 함(Jira 정석).
- **부모-자식(parent-child)**. 구조적 계층. `issues.parent_id`로 표현, 링크와 별개.

### 도메인 불변식 (D2/D5 cycle 케이스)
- 링크. (1) 자기 링크 금지(source≠target), (2) 같은 (source,target,type) 중복 금지, (3) **blocks 그래프 acyclic**(A blocks B blocks …blocks A 거부), (4) 소프트 삭제 이슈 대상 링크 금지.
- parent-child. (1) 단일 부모(컬럼), (2) 자기 부모 금지, (3) **조상 체인 acyclic**(부모의 조상에 자신 금지), (4) hierarchy_level 위계(자식 타입 level < 부모 타입 level — Subtask < Story/Task), (5) 소프트 삭제 이슈 부모 금지.

### deviation (SDD ↔ 코드)
- SDD §5.7 `source_id/target_id BIGINT` → 실제 `issues.id`가 UUID(V001)이므로 **UUID FK**로 구현. SDD 본문 불변, ADR + 본 plan에 기록.
- `link_type` 4종은 SDD §5.7과 일치(parent-child 제외) → SDD 데이터 모델 정정 불필요.

### 새 용어 / 기존 결정
- glossary "링크"(line 33) 이미 존재. **LinkType 4종 세부 + 역방향 표시 + parent-child(parent_id)** 항목 추가 후보(Maxi 승인 영역).
- 기존 결정 충돌. 없음. fr-index↔SDD drift는 본 ADR로 해소(분리 근거 박제).
- 관련 ADR. [docs/decisions/2026-06-13-issue-link-vs-parent-child-separation.md](../decisions/2026-06-13-issue-link-vs-parent-child-separation.md) (생성됨)
- 관련 마이그레이션. issue-tracking 다음 버전 **V021**(현재 최신 V020), init_codegen 미러 필수.

## 스펙

전체 스펙. [docs/specs/2026-06-13-fr-lk-01-issue-links.md](../specs/2026-06-13-fr-lk-01-issue-links.md)

핵심 요약.
- 링크(issue_links, 4종 blocks/relates/duplicates/clones, UUID source/target, surrogate BIGINT id) — POST/GET/DELETE `/api/v1/issues/{key}/links`.
- parent-child(issues.parent_id) — PATCH `/api/v1/issues/{key}/parent` 2-state(set/clear). 구조만 강제(단일부모·acyclic·self금지), hierarchy_level 위계 이연(Maxi 확정 B).
- 불변식 7종(자기링크·중복 409·blocks 순환 409 전이탐색·self-parent 422·부모 순환 409·소프트삭제 404·링크id 404).
- 마이그레이션 V021(issue_links 테이블 + issues.parent_id 컬럼) + init_codegen 미러.
- 비목표. 이력·알림 미발행, 권한 placeholder resolver, 프론트 D6/D7 후속.

## Brainstorming Check

✅ 통과 (1회 iteration). well-specified 백엔드 FR → 직접 기술 스펙. gap 1건(hierarchy_level 위계) Maxi 확정 B(구조만)로 해소. 나머지는 선례·범위 기반 자기 결정.

## Plan

> 경로 약어. `M/` = `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/`, `T/` = `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/`, `MIG/` = `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/`, `CG/` = `backend/modules/issue-tracking/src/main/resources/db/codegen/`

### Task 1. V021 마이그레이션 + jOOQ codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`MIG/V021__issue_links_and_parent.sql`, `CG/init_codegen.sql`, `T/link/repository/IssueLinkSchemaTest.kt`]
- depends-on: []

**RED**. `IssueLinkSchemaTest`(Testcontainers) — DSL/`information_schema`로 `issue_links` 테이블(컬럼·UNIQUE·CHECK)과 `issues.parent_id` 컬럼 존재 단언. 실패. 테이블/컬럼 없음.

**GREEN**. V021 DDL 작성.
- `issue_links(id BIGINT identity PK, source_id UUID FK→issues ON DELETE CASCADE, target_id UUID FK→issues ON DELETE CASCADE, link_type VARCHAR(30), created_by UUID, created_at TIMESTAMPTZ)` + `CHECK(source_id<>target_id)` + `CHECK(link_type IN (...4종))` + `UNIQUE(source_id,target_id,link_type)` + idx(source_id), idx(target_id).
- `ALTER TABLE issues ADD COLUMN parent_id UUID NULL REFERENCES issues(id)` + idx(parent_id).
- **`CG/init_codegen.sql`에 동일 미러**(메모리 jooq-init-codegen-mirror — 누락 시 jOOQ 클래스 미생성).

**REFACTOR**. COMMENT ON COLUMN(DATA.md §4 컨벤션), V017 주석 스타일 정렬.

**검증**. `./gradlew :modules:issue-tracking:generateJooq` 성공 + `:modules:issue-tracking:test --tests *IssueLinkSchemaTest`. **다음 task 컴파일 전 codegen 필수**.

### Task 2. Link 도메인 — LinkType + IssueLink + 예외

**메타**.
- agent: `backend-engineer`
- files: [`M/link/domain/LinkType.kt`, `M/link/domain/IssueLink.kt`, `M/link/domain/LinkExceptions.kt`, `T/link/domain/LinkTypeTest.kt`, `T/link/domain/IssueLinkTest.kt`]
- depends-on: []

**RED**.
- `LinkTypeTest` — `fromCode("blocks")`=BLOCKS, 잘못된 코드 throw, `BLOCKS.outwardLabel="blocks"`/`inwardLabel="is blocked by"`, `RELATES` 대칭(out=in="relates to"), DUPLICATES/CLONES 라벨. **enum↔DDL CHECK 4종 정합 테스트**(`entries.map{code}`가 {blocks,relates,duplicates,clones}와 일치 — 메모리 enum-add-breaks-count-guard).
- `IssueLinkTest` — `IssueLink.create(source,target,type)`가 source==target 시 `LinkSelfReferenceException` 던짐, 필드 보유.

**GREEN**.
- `LinkType` enum(code + outwardLabel + inwardLabel + `isSymmetric`) + `fromCode`.
- `IssueLink` data class + `create` 팩토리(self-check).
- `LinkExceptions.kt` — `LinkedIssueNotFoundException`(404), `LinkSelfReferenceException`(422), `DuplicateLinkException`(409), `LinkCycleException`(409), `LinkNotFoundException`(404), `ParentSelfReferenceException`(422), `ParentCycleException`(409). **모두 link 패키지 로컬**(코어 IssueNotFound 재사용 시 핸들러 스코프 500 트랩 — 메모리 domain-exception-http-handler-basepackage-scope·duplicate-exception-name-cross-package-status 회피).

**REFACTOR**. KDoc(역방향 라벨 의미), 라벨 상수.

**검증**. `:modules:issue-tracking:test --tests "LinkTypeTest" --tests "IssueLinkTest"`.

### Task 3. IssueLinkRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`M/link/repository/IssueLinkRepository.kt`, `T/link/repository/IssueLinkRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**. `IssueLinkRepositoryTest`(Testcontainers, 실 repo + 이슈 시드 — 메모리 issue-tracking-transition-test-mocks-workflow-repo) — `insert`, `findBySourceId`, `findByTargetId`, `existsLink(src,tgt,type)`, `deleteById`(0행 시 false), `existsBlocksPath(fromId,toId)`(재귀 CTE — 전이 순환 탐색). FK CASCADE cleanup(메모리 join-table-fk-cascade).

**GREEN**. jOOQ `IssueLinkRepository` — 생성 jOOQ 클래스 사용. `existsBlocksPath`는 `WITH RECURSIVE`로 blocks 그래프 도달성.

**REFACTOR**. 쿼리 상수/KDoc.

**검증**. `:modules:issue-tracking:test --tests "IssueLinkRepositoryTest"`.

### Task 4. Issue.parent_id 도메인 + IssueRepository parent 메서드

**메타**.
- agent: `backend-engineer`
- files: [`M/domain/Issue.kt`, `M/repository/IssueRepository.kt`, `T/repository/IssueRepositoryParentTest.kt`]
- depends-on: [1]

**RED**. `IssueRepositoryParentTest`(Testcontainers) — `updateParent(issueId,parentId)`, `updateParent(issueId,null)`(해제), `collectAncestors(issueId)`(재귀 CTE, parent_id 체인). `Issue.parentId` 필드 매핑 확인.

**GREEN**.
- `Issue` 도메인에 `parentId: UUID? = null` 추가(**기본값 null — 기존 생성자 호출 보호**, 메모리 plan-files-constructor-injection / interface-extension default).
- `IssueRepository` — rowMapper에 parent_id 매핑 추가, `updateParent`, `collectAncestors` 추가. **기존 issues SELECT/INSERT 매핑 깨지지 않게**(전 컬럼 정합).

**REFACTOR**. KDoc.

**검증**. `:modules:issue-tracking:test --tests "IssueRepositoryParentTest"` + **기존 IssueRepository/IssueApplicationService 테스트 회귀 0**(`grep -rl "Issue(" T/` 로 생성자 호출 확인).

### Task 5. LinkApplicationService (링크 생성/조회/해제 + 순환)

**메타**.
- agent: `backend-engineer`
- files: [`M/link/application/LinkApplicationService.kt`, `T/link/application/LinkApplicationServiceTest.kt`]
- depends-on: [3]

**RED**. `LinkApplicationServiceTest`(mockk repo — 검증 분기 집중) —
- `createLink`. target 미존재/소프트삭제→`LinkedIssueNotFoundException`(404), self→422, 중복(existsLink)→`DuplicateLinkException`(409), blocks이고 `existsBlocksPath(target,source)` true→`LinkCycleException`(409). created_by=actor.
- `listLinks`. outward(source=key)+inward(target=key), 소프트삭제 상대 이슈 제외, relates 대칭 라벨.
- `deleteLink`. 미존재→`LinkNotFoundException`(404).

**GREEN**. `@Service @Transactional` — IssueLinkRepository + IssueRepository(존재/삭제 조회) 주입. relates는 순환검사 skip(blocks만).

**REFACTOR**. 중복 검증 helper.

**검증**. `:modules:issue-tracking:test --tests "LinkApplicationServiceTest"`.

### Task 6. IssueParentService (부모 지정/해제 + 순환)

**메타**.
- agent: `backend-engineer`
- files: [`M/link/application/IssueParentService.kt`, `T/link/application/IssueParentServiceTest.kt`]
- depends-on: [4]

**RED**. `IssueParentServiceTest`(mockk repo) —
- `setParent(key,parentKey)`. parent 미존재/소프트삭제→404, key==parentKey→`ParentSelfReferenceException`(422), parentKey의 조상에 key 존재(collectAncestors)→`ParentCycleException`(409). 성공 시 updateParent.
- `clearParent(key)`. updateParent(null). hierarchy_level 위계 검사 없음(Maxi 확정 B).

**GREEN**. `@Service @Transactional`.

**REFACTOR**. KDoc(구조만 강제 명시).

**검증**. `:modules:issue-tracking:test --tests "IssueParentServiceTest"`.

### Task 7. IssueLinkController + DTO + ErrorCodes/ExceptionHandler + HTTP 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`M/link/web/IssueLinkController.kt`, `M/link/web/dto/CreateLinkRequest.kt`, `M/link/web/dto/IssueLinkResponse.kt`, `M/link/web/dto/LinkListResponse.kt`, `M/link/web/dto/SetParentRequest.kt`, `M/link/web/dto/IssueParentResponse.kt`, `M/link/web/LinkErrorCodes.kt`, `M/link/web/LinkExceptionHandler.kt`, `T/link/web/IssueLinkControllerIntegrationTest.kt`]
- depends-on: [5, 6]

**RED**. `IssueLinkControllerIntegrationTest`(`@SpringBootTest` 풀 컨텍스트 + Testcontainers + MockMvc — **HTTP 상태코드 검증 필수**, 메모리 domain-exception-http-handler-basepackage-scope / catch-all-swallows-ResponseStatusException) —
- `POST /api/v1/issues/{key}/links` 201, self 422, 중복 409, target 404, blocks 순환 409.
- `GET /api/v1/issues/{key}/links` 200 {outward,inward}.
- `DELETE /api/v1/issues/{key}/links/{linkId}` 204, 미존재 404.
- `PATCH /api/v1/issues/{key}/parent` {parentKey} 200, null 해제 200, self 422, 순환 409, parent 404.
- 잘못된 linkType 400(Bean Validation).

**GREEN**. 컨트롤러(`/api/v1/issues/{key}` 베이스, `DataResponse<T>` 래퍼, actorId=인증주체 placeholder=`SYSTEM_ACTOR_UUID` component 선례) + DTO `from()` 팩토리 + `LinkErrorCodes`(ISSUE_NOT_FOUND/LINK_SELF_REFERENCE/DUPLICATE_LINK/LINK_CYCLE/LINK_NOT_FOUND/PARENT_SELF_REFERENCE/PARENT_CYCLE, 대문자) + `LinkExceptionHandler`(`@RestControllerAdvice` **link 패키지 스코프** 또는 basePackages 명시).

**REFACTOR**. KDoc(엔드포인트 목록), DTO 정리.

**검증**. `:modules:issue-tracking:test --tests "IssueLinkControllerIntegrationTest"` + **모듈 전체** `:modules:issue-tracking:test ktlintCheck detekt`(회귀 0).

## Plan 메타

- task 수: 7
- 예상 wave: 4 (wave1. T1·T2 / wave2. T3·T4 / wave3. T5·T6 / wave4. T7). 같은 모듈이라 test 컴파일은 직렬(메모리 bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: generateJooq(T1 후), 모듈 ktlintCheck/detekt, 기존 IssueRepository/Service 회귀
- 비목표 재확인: 이력·알림 미발행, 권한 placeholder, 프론트 D6/D7 후속

## 리뷰 결과 (← /bts-review-plan 채움)
