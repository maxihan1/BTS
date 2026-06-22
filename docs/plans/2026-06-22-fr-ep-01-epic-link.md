# FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 (백엔드 D1~D5)

> slug: fr-ep-01-epic-link
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-22

## Brief

FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 구현.

- 현재 `epic`은 이슈 타입으로만 존재(V003/V005 level 1). 이슈↔에픽 연결 메커니즘은 미구현.
- 선행 FR-IS-02(이슈 타입), FR-LK-01(이슈 링크 + parent-child) 모두 완료.
- **설계 갈림길(도메인 단계 결정)**: 기존 `issues.parent_id`(parent-child, FR-LK-01) 재사용 vs 별도 `issues.epic_id` 컬럼. FR-EP-02 진행률 집계 의미론과 맞물림 → bts-domain에서 결정 후 게이트 1에서 Maxi 확정.
- **이번 PR 범위**: 백엔드 D1~D5(에픽 연결 메커니즘 + API). 프론트 D6/D7(이슈상세 에픽 연결 UI · 보드 에픽 스윔레인)은 후속 PR(fr-ep-01-d6-d7-*).

classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: issue-tracking (에픽=이슈 타입 + 연결=issues 컬럼이라 데이터가 issue-tracking 소유. agile-planning §7 FR이나 issue-tracking 구현 — FR-PL-01/FR-TT-01 선례).
- **영향 엔티티**: Issue (epic_id 필드 신규).
- **핵심 결정(Maxi 확정 2026-06-22)**: Epic↔자식 연결 = **별도 `issues.epic_id UUID NULL REFERENCES issues(id)` 컬럼**. parent_id 재사용·issue_links 둘 다 폐기. → ADR [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md)
- **데이터 모델 사실**:
  - `parent_id`(V021): Subtask→부모 Story/Task, UUID NULL 자기참조 FK (FR-LK-01). **epic_id와 별개 유지**.
  - `epic_id`(신규 V028): 자식→소속 Epic, parent_id 패턴 미러(UUID NULL · 자기참조 FK · ON DELETE NO ACTION · 인덱스 · init_codegen 미러).
  - hierarchy_level(코드 정본): epic=1, story/task/bug=0, subtask=-1.
- **도메인 불변식**:
  1. epic_id는 `hierarchy_level=0`(story/task/bug) 이슈에만. Epic(1)·Subtask(-1)은 불가.
  2. epic_id 대상 이슈는 Epic 타입(hierarchy_level=1)이어야 함.
  3. 순환 불가(level 0→1 단방향, 추가 가드 불요). 단일 Epic(컬럼 1개).
  4. 같은 프로젝트 제약(Jira parity) — spec에서 명문화.
- **API(명세)**: `POST /api/v1/issues/{key}/epic-children`(자식 연결). 해제/조회 엔드포인트는 spec 확정.
- **기존 결정 충돌**: 없음 (SDD §5.8/§5.1 정석 일치, ADR 2026-06-13 분리 원칙 일관).
- **deviation**: SDD §5.1 epic_id BIGINT→실제 UUID(V001 issues.id UUID), parent_id 동형. SDD §5 hierarchy_level "0/1/2"→코드 "1/0/-1"(코드 정본). ADR + product 인라인에 기록.
- **glossary**: "에픽"·"이슈 타입" 기존 등재. 신규 용어 없음(epic_id는 구현 디테일). 갱신 불요.
- **관련 ADR**: [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-22-fr-ep-01-epic-link.md](../specs/2026-06-22-fr-ep-01-epic-link.md)

핵심 요약.
- 별도 `IssueEpicController` 신설(IssueLinkController 패턴). 엔드포인트 3종 + IssueResponse.epic 확장.
  - `POST /issues/{epicKey}/epic-children {childKey}` → 201 (UPDATE child + VIEW epic)
  - `DELETE /issues/{epicKey}/epic-children/{childKey}` → 204 (UPDATE child)
  - `GET /issues/{epicKey}/epic-children` → 200 (VIEW epic + 자식 visibility 필터)
  - `GET /issues/{childKey}` IssueResponse.epic = {epicKey, summary} (parent 동형, 단건만)
- 불변식 5종: 자식 level=0 / 대상 Epic level=1 / 동일 프로젝트 / 자기참조 금지 / 단일 Epic(이미 소속 409).
- 권한 검증 repo 조회 선행(probe 방지). visibility 필터 SQL 푸시다운(N+1 금지, 누출 0).
- V028 + init_codegen 미러. 신규 권한·보안경로 0(IssuePermissionResolver/IssueSecurity 재사용).

## Brainstorming Check

집중 갭 점검(정의된 FR). 발견 2건.
- **G1 (changelog) — 🛑 게이트 1 Maxi 결정**: epic 연결/해제를 자식 changelog(IssueHistoryRecorder)에 기록할지. parent_id 선례=미기록 vs FR-PL-01 교훈=기록. **권장=기록**(Jira parity·적대리뷰 사전차단). 결정에 따라 plan task ±1.
- **G2 (이미 Epic 소속) — 해결**: 409(EPIC_CHILD_ALREADY_LINKED), explicit 해제 후 재연결. 스펙 반영.

## Plan

> 모두 issue-tracking 단일 Gradle 모듈 → 모듈 test 컴파일 직렬화 + lint-staged race 회피 위해 **직렬 dispatch 권장**(bts-plan-wave-gradle-module-compile, FR-BD-03/FR-NT-04 선례). depends-on/files는 정확히 기재하되 bts-impl이 wave 결정.

### Task 1. V028 마이그레이션 — issues.epic_id + 인덱스 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V028__issue_epic_link.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/V028MigrationTest.kt`]
- depends-on: []

**RED**: `V028MigrationTest` — Testcontainers 마이그레이션 후 `issues.epic_id`(UUID, nullable, FK→issues.id) 컬럼 + `idx_issues_epic_id` 인덱스 존재 단언. (V006MigrationTest 패턴). 실패: 컬럼 부재.

**GREEN**: V028 작성 (스펙 §데이터모델 SQL 그대로) + `init_codegen.sql` 하단 trailing 미러(jooq-init-codegen-mirror — issues 테이블 정의에 epic_id 컬럼 추가).

**REFACTOR**: COMMENT + parent_id 컬럼과의 별개성 주석.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*V028MigrationTest"` + clean 빌드(jOOQ codegen이 ISSUES.EPIC_ID 생성 확인, backend-clean-build-broken).

### Task 2. Issue 도메인 — epicId 필드 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: `IssueTest` — Issue 재구성 시 epicId 보존 + `create` 팩토리는 epicId 기본 null 단언. 실패: epicId 프로퍼티 부재.

**GREEN**: `Issue.epicId: UUID? = null` 추가(생성자 + create 팩토리 default null — 기존 호출처/테스트 무변경 보장, plan-files-constructor-injection 팬아웃 회피). 도메인 불변식 가드는 application 레이어(T5).

**REFACTOR**: KDoc — epic_id는 소속 Epic, parent_id(Subtask 계층)와 별개 명시.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueTest"`.

### Task 3. IssueResponse.epic — EpicSummary DTO 필드 (단건 노출)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../IssueResponseTest.kt`]
- depends-on: []

**RED**: `IssueResponseTest` — `IssueResponse.from(..., epic = EpicSummary(key, summary))` 시 epic 채움, default null(목록 경로) 단언. 실패: epic 프로퍼티/EpicSummary 부재.

**GREEN**: `IssueResponse.epic: EpicSummary? = null` + `EpicSummary(key, summary)` 중첩 클래스 + `from(...)` epic 파라미터 default null(@JsonInclude(NON_NULL) — parent 동형, 기존 호출처 무변경).

**REFACTOR**: KDoc — 단건 조회(findByKeyWithType)에서만 채움, parent 노출과 동형.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueResponseTest"`.

### Task 4. IssueRepository — epic 메서드 (updateEpic/clear · self-join 노출 · 자식 visibility 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../IssueRepositoryEpicIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**: `IssueRepositoryEpicIntegrationTest` (Testcontainers) —
- `updateEpic(childId, epicId)` / `updateEpic(childId, null)` 가 epic_id 설정/해제.
- `findByKeyWithType` 가 epic self-LEFT-JOIN으로 IssueResponse.epic(key, summary) 채움(소프트삭제 Epic은 null) + 목록(`listWithType`)은 epic null.
- `findByKey` 재구성 시 Issue.epicId 채움.
- `findEpicChildren(epicId, accessibleLevels)` 가 보안 등급 필터 푸시다운(접근 불가 자식 제외) + 소프트삭제 자식 제외 — listVisibleForBoard 보안 술어 재사용.
실패: 메서드 부재.

**GREEN**: 위 메서드 구현. self-join은 parent self-join 패턴(IssueResponse.kt:274 인근) 미러. 자식 조회는 `listVisibleForBoard`(IssueRepository:658) 보안 술어(buildActiveSecureWhere) 재사용 + `WHERE epic_id = :epicId`.

**REFACTOR**: 공통 보안 술어 헬퍼 재사용 확인(중복 0). detekt 복잡도 점검.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueRepositoryEpicIntegrationTest"`.

### Task 5. IssueEpicService — 불변식 5종 + 권한 + 자식목록 (+ G1 changelog)

**메타**.
- agent: `backend-engineer` (권한/visibility — security-engineer plan-review 검토)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/application/IssueEpicService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/domain/EpicChildExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../IssueEpicServiceTest.kt`]
- depends-on: [4]

**RED**: `IssueEpicServiceTest` (mockk repo/resolver/securityDirectory/typeRepo) —
- `connect(epicKey, childKey, actorId)`: 권한 UPDATE(child Issue scope) **선행**(repo 조회 전, probe 방지) → child 404 → epic 404 → epic VIEW → self 422 → 자식 hierarchyLevel≠0 422 → epic hierarchyLevel≠1 422 → cross-project 422 → child.epicId≠null 409 → updateEpic 호출.
- `disconnect(epicKey, childKey, actorId)`: UPDATE(child) → child 404 → child.epicId≠이 epic 404 → updateEpic(null).
- `listChildren(epicKey, actorId)`: VIEW(epic) → accessibleLevels 조회 → findEpicChildren 위임.
- 권한 없음 → IssueAccessDeniedException(403). 불변식 위반 → 각 전용 예외.
- **[G1 권장=기록]** connect/disconnect 성공 시 IssueHistoryRecorder로 자식 changelog("epic" 필드 변경) 기록 단언. *(Maxi가 게이트1에서 G1 미기록 결정 시 이 단언 + recorder 호출 제거.)*
실패: IssueEpicService/예외 부재.

**GREEN**: 서비스 구현. `@Transactional` + `@Service`(@Transactional 무력화 회피, learnings 2026-05-20). 권한=`IssuePermissionResolver.hasPermission(actorId, UPDATE/VIEW, IssueScope.Issue)`. hierarchyLevel=`IssueTypeRepository.findById(issue.typeId).hierarchyLevel`. visibility=`IssueSecurityDirectory.accessibleLevels`. 예외=plain 도메인 예외(EpicChildInvalidType/TargetNotEpic/CrossProject/SelfReference/AlreadyLinked/NotFound).

**REFACTOR**: 검증 순서 KDoc. ThrowsCount @Suppress(IssueParentService 선례). 권한 선행 단언 보존.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueEpicServiceTest"`.

### Task 6. IssueEpicController + DTO + 예외 핸들러 + 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/IssueEpicController.kt`, `.../epic/web/dto/CreateEpicChildRequest.kt`, `.../epic/web/dto/EpicChildSummaryResponse.kt`, `.../epic/web/dto/EpicChildListResponse.kt`, `.../epic/web/EpicChildExceptionHandler.kt`, `.../epic/web/EpicChildErrorCodes.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../IssueEpicControllerIntegrationTest.kt`]
- depends-on: [5]

**RED**: `IssueEpicControllerIntegrationTest`(@SpringBootTest, Testcontainers) — S1~S11 전 경로 + 오류 매핑(404/422/409/403/400) HTTP 상태·errorCode 단언. 적대리뷰 vacuous 회피 — actor별 시드로 실제 403/누출0 검증.

**GREEN**: `@RestController @RequestMapping("/api/v1/issues/{key}")` IssueEpicController(POST/DELETE/GET epic-children). DataResponse 봉투. EpicChildExceptionHandler `@RestControllerAdvice(assignableTypes=[IssueEpicController])` 스코프 한정(catch-all-swallow 회피, MethodArgumentTypeMismatch/HttpMessageNotReadable 구체 핸들러 먼저). actor=SecurityContext 추출→service 전달(위조 차단, FR-BD-01 선례).

**REFACTOR**: KDoc 엔드포인트 표 + errorCode 정본화.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueEpicControllerIntegrationTest"` + 모듈 전체 ktlintCheck/detekt(--rerun-tasks).

## Plan 메타

- task 수: 6
- 예상 시간: 직렬 약 30분 (단일 모듈 직렬 dispatch 권장)
- TDD 강제: yes (test 커밋 먼저)
- 병렬 dispatch: 논리 wave W1{T1,T2,T3}→W2{T4}→W3{T5}→W4{T6}이나 단일 Gradle 모듈 컴파일 직렬화 + lint-staged race로 **직렬 권장**
- 추가 검증: ktlint, detekt(--rerun-tasks), verify-master-plan(FR 123 불변), clean 빌드(jOOQ codegen)
- 🛑 게이트1 결정: **G1(changelog 기록)** — 권장=기록(T5에 포함). 미기록 시 T5의 IssueHistoryRecorder 호출+단언 제거.

## 리뷰 결과 (← /bts-review-plan 채움)
