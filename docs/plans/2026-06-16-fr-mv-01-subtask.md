# FR-MV-01 서브태스크 동반 이동 (노드별 매핑) — 백엔드

> slug: fr-mv-01-subtask
> type: backend
> agent: backend-engineer
> BC: issue-tracking
> 생성: 2026-06-16

## Brief

부모 이슈를 다른 프로젝트로 이동할 때 자식 서브태스크를 함께 이동(노드별 매핑).
현재 단건 이동(#153)만 완료 — 자식 있으면 422 `ISSUE_HAS_SUBTASKS`로 거부 중.
ADR `2026-06-16-issue-move-semantics` 기준, D1(도메인 IssueMoveOperation 서브태스크 동반)·
D4(백엔드 preview/move 노드별 매핑) 확장. 신규 cross-BC SPI `WorkflowStateCatalog` 재사용.

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: `Issue`(parent_id 보존/끊기 분기), `issue_key_redirects`(노드별 INSERT), 조인 테이블(컴포넌트/버전 노드별 교체), 커스텀필드.
- **신규 필요**:
  - `IssueRepository` 자식 **목록** 조회 메서드 (현재 `countDirectChildren` 만 존재)
  - `IssueMoveOperation` 검증을 노드별로 확장 + 자식의 자식 거부 EC 추가
  - `MovePreviewService`/`IssueMoveService` 를 노드 배열(부모+자식) 처리로 확장
- **Maxi 결정 (2026-06-16)**:
  - 범위 = **직접 자식(1레벨)** 동반. 서브태스크는 `hierarchy_level=-1` 최하위 = 1레벨 모델. 자식이 또 자식 가지면 거부(불변식 강제).
  - 매핑 = **완전 노드별** (부모+각 자식 독립 매핑).
- **불변식**: 각 노드 id 보존 / 새 키 발번(부모→자식) / redirect+308 / 노드별 OCC·상태·매핑. **자식 parent_id 는 부모 id 보존으로 자동 유지** (단건 `parent_id=null` 강제는 부모 노드에만).
- **새 용어**: "서브태스크 동반 이동", "노드별 매핑" — glossary 추가 후보 (Maxi 승인 대기).
- **기존 결정 충돌**: 없음. ADR `2026-06-16-issue-move-semantics` 에 "후속 결정 — 서브태스크 동반 이동" 단락 확장.
- **관련 ADR**: [docs/adr/2026-06-16-issue-move-semantics.md](../adr/2026-06-16-issue-move-semantics.md) (확장됨)

## 스펙

전체 스펙. [docs/specs/2026-06-16-fr-mv-01-subtask.md](../specs/2026-06-16-fr-mv-01-subtask.md) (단건 spec `2026-06-16-fr-mv-01.md` 대비 deviation 명시: 1레벨·완전 노드별).

핵심 시나리오 3줄 요약.
- 부모 이슈 이동 시 직접 자식(서브태스크, 1레벨) 동반 — 각 노드 id 보존·새 키 발번·redirect+308, 자식 `parent_id` 자동 유지(부모만 `parent_id=null` detach)
- preview/move 페이로드에 노드별 `subtasks` 추가(완전 노드별 매핑, 자식 식별=`issueKey`), 단일 트랜잭션·노드별 OCC·노드별 `issueTypeKey` 상태 조회
- EC15 재정의(불완전 매핑 422 `INCOMPLETE_SUBTASK_MAPPING`)·EC16 다단계 거부(`SUBTASK_HAS_OWN_SUBTASKS`)·EC17 자식 OCC 409. 자식 없으면 단건 회귀 보존

## Brainstorming Check

✅ 통과 (1회 iteration). adversarial sanity check 보강.
- G1 식별자 통일(`issueKey`) · G2 자식 워크플로우 미설정 EC19 흡수 · G3 자식 비관락 id 오름차순 · G4 자식 보안수준 단건 일관(프로젝트 범위)

## Plan

**Goal**. 부모 이슈 이동 시 직접 자식(서브태스크, 1레벨)을 노드별 매핑으로 함께 이동(단일 트랜잭션). 단건(#153) 회귀 보존.

**Architecture**. 단건 구현(`MovePreviewService`/`IssueMoveService`/`IssueMoveOperation`/`MoveDtos`/`IssueMoveController`) 위 확장. 도메인 검증은 노드별 + 트리 수준(다단계 거부·매핑 완전성), repo는 자식 목록 조회·비관락 추가, 서비스는 루트+자식 노드 순회.

**Tech Stack**. Kotlin/Spring, jOOQ, Testcontainers. 모듈 `:modules:issue-tracking`. gradlew는 `backend/` 하위. 마이그레이션 신규 없음.

> 전부 issue-tracking 모듈 → test 소스셋 컴파일은 직렬(같은 모듈). wave는 논리 의존만 표현.

### Task 1. 도메인 — IssueMoveOperation 노드별 검증 + 신규 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueMoveOperation.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueMoveOperationTest.kt`]
- depends-on: []

- [ ] **Step 1: RED — 실패 테스트 추가** (`IssueMoveOperationTest.kt`)
  - `validateWithSubtasks` 가 자식이 또 자식을 가지면(`childKeysWithOwnChildren` 비어있지 않음) `SubtaskHasOwnSubtasksException`
  - 제공된 자식 키 집합 ≠ 실제 직접 자식 키 집합이면 `IncompleteSubtaskMappingException`
  - 루트 ctx 에 `hasSubtasks=true` 여도 동반 경로에선 EC15 던지지 않음(자식 허용)
  - 각 자식 ctx 의 EC7(상태)/EC8(매핑)/EC9(필수필드)가 노드별로 검증됨
  - **(B3) vacuous 차단 — populated 입력으로 실패 증명**: 자식 ctx 의 `componentMappingTargetIds` 에 대상 프로젝트에 **없는** UUID, `targetProjectComponentIds` 에 **다른** UUID 집합을 넣어 `InvalidTargetMappingException` 발생을 단언. 두 집합을 같게 두면 항상 통과(단건 #153 BLOCKER 전례)이므로 테스트가 이를 잡아야 함. EC9도 동일(자식 requiredFieldKeys 에 있고 providedFieldKeys 에 없는 키)
  ```kotlin
  @Test fun `동반 경로는 자식의 자식이 있으면 SubtaskHasOwnSubtasks`() { ... }
  @Test fun `제공 자식 키가 실제 자식 집합과 다르면 IncompleteSubtaskMapping`() { ... }
  @Test fun `동반 경로 루트는 hasSubtasks여도 EC15 미발생`() { ... }
  @Test fun `자식 노드 매핑대상이 대상프로젝트에 없으면 InvalidTargetMapping (populated 위반입력)`() { ... }
  @Test fun `자식 노드 필수필드 누락이면 RequiredFieldMissing (populated)`() { ... }
  ```
- [ ] **Step 2: 테스트 실패 확인** — `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueMoveOperationTest" -i` → 컴파일 실패(`validateWithSubtasks`/신규 예외 없음)
- [ ] **Step 3: GREEN — 최소 구현** (`IssueMoveOperation.kt`)
  - 신규 예외 2종 추가(기존 단건 예외와 동일 `IssueDomainException` 상속):
    ```kotlin
    class SubtaskHasOwnSubtasksException(val childKeys: Set<String>) :
        IssueDomainException("Subtask cannot have its own subtasks: $childKeys")
    class IncompleteSubtaskMappingException(val expected: Set<String>, val provided: Set<String>) :
        IssueDomainException("Subtask mapping incomplete: expected=$expected provided=$provided")
    ```
  - `validateWithSubtasks(rootCtx, childCtxs: List<IssueMoveContext>, actualChildKeys: Set<String>, providedChildKeys: Set<String>, childKeysWithOwnChildren: Set<String>)`:
    1. `childKeysWithOwnChildren` 비어있지 않으면 `SubtaskHasOwnSubtasksException`
    2. `actualChildKeys != providedChildKeys` 면 `IncompleteSubtaskMappingException`
    3. 루트: `checkSameProject`/`checkTargetState`/`checkMappings`/`checkRequiredFields`(checkSubtasks 제외)
    4. 각 자식 ctx: `checkTargetState`/`checkMappings`/`checkRequiredFields`
  - 기존 `validate(ctx)`(단건, checkSubtasks→EC15)는 그대로 유지
- [ ] **Step 4: 테스트 통과 확인** — 위 명령 재실행 → PASS
- [ ] **Step 5: REFACTOR** — 루트/자식 공통 노드 검증을 private `validateNode(ctx, includeSubtaskCheck)` 로 추출, KDoc
- [ ] **Step 6: 커밋** — `test:`+`feat:` 순서 (TDD). `git -C .worktrees/fr-mv-01-subtask add ...; commit -m "feat: IssueMoveOperation 노드별 검증 + 다단계/불완전매핑 예외"`

### Task 2. IssueRepository — findDirectChildren + 자식 비관락

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`]
- depends-on: []

- [ ] **Step 1: RED — 통합 테스트(Testcontainers)** (`IssueRepositoryTest.kt`)
  - `findDirectChildren(parentId)` 가 활성 직접 자식만 반환(소프트삭제 제외, 손자 미포함)
  ```kotlin
  @Test fun `findDirectChildren는 활성 직접 자식만 반환하고 소프트삭제 제외`() { ... }
  @Test fun `findDirectChildren는 손자(자식의 자식) 미포함`() { ... }
  ```
- [ ] **Step 2: 실패 확인** — `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueRepositoryTest" -i` → `findDirectChildren` 없음
- [ ] **Step 3: GREEN** (`IssueRepository.kt`)
  - `fun findDirectChildren(parentId: UUID): List<Issue>` — `SELECT ... WHERE parent_id = ? AND deleted_at IS NULL`. 기존 row→Issue 매핑(`findByKey` 경로) 재사용. 결과 id 오름차순 정렬(데드락 회피 락 순서 기반)
  - 자식 비관락은 move 에서 `findByKeyForUpdate(key)` 를 자식 키별로 재사용(기존 메서드). 신규 락 메서드 불필요
- [ ] **Step 4: 통과 확인** — 위 명령 재실행 → PASS
- [ ] **Step 5: REFACTOR** — 공통 SELECT 빌더 재사용 확인, KDoc(소프트삭제/손자 제외 명시)
- [ ] **Step 6: 커밋** — `feat: IssueRepository.findDirectChildren 추가`

### Task 3. MoveDtos subtasks 필드 + 예외 핸들러/에러코드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/dto/MoveDtos.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandlerTest.kt`]
- depends-on: [1]   # 신규 예외 클래스(T1)를 핸들러가 import

- [ ] **Step 1: RED — 예외 핸들러 테스트** (기존 핸들러 테스트 파일 패턴 따름)
  - `SubtaskHasOwnSubtasksException` → 422 + `SUBTASK_HAS_OWN_SUBTASKS`
  - `IncompleteSubtaskMappingException` → 422 + `INCOMPLETE_SUBTASK_MAPPING`
  ```kotlin
  @Test fun `SubtaskHasOwnSubtasks는 422 SUBTASK_HAS_OWN_SUBTASKS`() { ... }
  @Test fun `IncompleteSubtaskMapping은 422 INCOMPLETE_SUBTASK_MAPPING`() { ... }
  ```
- [ ] **Step 2: 실패 확인** — `--tests "*IssueExceptionHandlerTest"` → 핸들러/에러코드 없음
- [ ] **Step 3: GREEN**
  - `MoveDtos.kt`:
    ```kotlin
    data class SubtaskMoveMapping(
        @field:NotBlank val issueKey: String,
        val expectedVersion: Long,
        val targetStateKey: String? = null,
        val targetStateIsDone: Boolean = false,
        val componentMapping: Map<UUID, UUID?> = emptyMap(),
        val affectsVersionMapping: Map<UUID, UUID?> = emptyMap(),
        val fixVersionMapping: Map<UUID, UUID?> = emptyMap(),
        val customFieldValues: Map<String, Any?> = emptyMap(),
    )
    data class MovedSubtask(val previousKey: String, val issueKey: String)
    // MoveRequest 에 추가: val subtasks: List<SubtaskMoveMapping> = emptyList()
    // MoveResponse 에 추가: val movedSubtasks: List<MovedSubtask> = emptyList()
    ```
  - `IssueExceptionHandler.kt`: 두 예외 `@ExceptionHandler` → `ProblemDetail` 422 (기존 `handleIssueHasSubtasks` 패턴 복사) + `IssueErrorCodes` 상수 2개 추가
  - **(C4)** 기존 `IssueHasSubtasksException`(`IssueMoveOperation.validate` 단건 경로 전용)은 **유지**. 동반/단건 분기는 서비스/컨트롤러에서 `subtasks` 배열 비어있음 + 실제 자식 존재 여부로 결정(동반 경로는 EC15 미발생). 단건 detail 문구는 그대로 두되, 동반 이동이 정식 지원됨을 KDoc 에 보강
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: REFACTOR** — KDoc, 에러코드 상수 정렬
- [ ] **Step 6: 커밋** — `feat: 동반 이동 DTO(subtasks) + 신규 예외 422 매핑`

### Task 4. MovePreviewService — 노드별 subtasks 섹션

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/MovePreviewService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/MovePreviewServiceTest.kt`]
- depends-on: [1, 2]

- [ ] **Step 1: RED — 통합 테스트**
  - 자식 있는 이슈 preview → `subtasks` 비어있지 않음, 각 자식의 workflow/components/versions/customFields 계산
  - 자식 워크플로우 후보는 **자식 issueTypeKey** 로 조회(루트와 다른 타입)
  - 자식 없는 이슈 → `subtasks` 빈 배열(단건 회귀)
  ```kotlin
  @Test fun `자식 있는 이슈 preview는 노드별 subtasks 섹션 반환`() { ... }
  @Test fun `자식 워크플로우는 자식 issueTypeKey로 조회`() { ... }
  @Test fun `자식 없으면 subtasks 빈 배열`() { ... }
  ```
- [ ] **Step 2: 실패 확인** — `--tests "*MovePreviewServiceTest"` → `MovePreview.subtasks` 없음
- [ ] **Step 3: GREEN**
  - `MovePreview` 에 `subtasks: List<SubtaskPreviewNode> = emptyList()` 추가. `SubtaskPreviewNode(issueKey, issueTypeKey, version, workflow, components, affectsVersions, fixVersions, customFields)`
  - `preview()`: `issueRepository.findDirectChildren(issue.id.value)` → 각 자식의 issueTypeKey 조회(**(C2) `findByKeyWithType(childKey)?.typeKey` — `Issue` 도메인엔 typeId 만 있어 typeKey 없음. `findByKeyWithType` 은 `@Transactional(readOnly=true)` 라 preview 의 readOnly 트랜잭션 안에서 호출 가능**) → 기존 `buildWorkflowSection`/`buildComponentSection`/`buildVersionSection`/`buildCustomFieldSection` 을 자식별로 호출. 워크플로우는 자식 issueTypeKey 전달
  - 루트 워크플로우도 루트 issueTypeKey 로 조회(단건은 null — 노드별 정확도). `buildWorkflowSection` 시그니처에 issueTypeKey 추가
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: REFACTOR** — 노드 섹션 빌더를 루트/자식 공통 `buildNodeSections(issue, issueTypeKey, ...)` 로 추출
- [ ] **Step 6: 커밋** — `feat: MovePreviewService 노드별 subtasks 섹션`

### Task 5. IssueMoveService — 노드별 이동 (단일 트랜잭션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueMoveService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueMoveServiceTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveControllerTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueMoveIntegrationTest.kt`]
- depends-on: [1, 2]   # 도메인 검증 + findDirectChildren. (T2와 IssueRepository.kt 파일 겹침 → 직렬)
- **리뷰 반영(B1/C1)**: `move()` 반환 타입 변경이 호출처(Controller)·기존 테스트 3종 컴파일을 깨므로 같은 task files 에 포함 (시그니처 변경 task = 모든 호출/테스트 동반 — learnings)

- [ ] **Step 1: RED — 통합 테스트**
  - 동반 이동 happy: 루트+자식 새 키 발번, redirect 노드별, 자식 `parent_id`=루트 id 유지(이동 후 자식이 이동된 루트 가리킴), 루트 `parent_id`=null(외부 부모 끊김)
  - 다단계(자식의 자식) → `SubtaskHasOwnSubtasksException`(422), 전체 미이동
  - 불완전 매핑(자식 일부 누락) → `IncompleteSubtaskMappingException`, 전체 미이동
  - 자식 OCC 충돌(자식 expectedVersion 불일치) → 409, **전체 롤백**(루트도 미이동)
  - 자식 없음 + subtasks 빈 배열 → 단건 #153과 동일(회귀)
  ```kotlin
  @Test fun `동반 이동은 루트+자식 새키+redirect+자식 parent유지+루트 detach`() { ... }
  @Test fun `자식의 자식 있으면 다단계 거부 전체롤백`() { ... }
  @Test fun `자식 매핑 불완전이면 거부`() { ... }
  @Test fun `자식 OCC 충돌이면 전체 롤백`() { ... }
  @Test fun `자식 없으면 단건 회귀`() { ... }
  ```
- [ ] **Step 2: 실패 확인** — `--tests "*IssueMoveServiceTest"`
- [ ] **Step 3: GREEN**
  - `IssueRepository.moveIssue` 에 `newParentId: UUID?` 파라미터 추가 → `.set(ISSUES.PARENT_ID, newParentId)`. **기존 단건 호출부(IssueMoveService)는 `newParentId = null`** 전달
  - `IssueMoveRequest` 에 `subtasks: List<SubtaskMoveSpec> = emptyList()` 추가. `SubtaskMoveSpec(issueKey, expectedVersion, targetStateKey, targetStateIsDone, componentMapping, affectsVersionMapping, fixVersionMapping, additionalCustomFields)`
  - **반환 타입 `IssueKey` → `MoveResult(newKey: IssueKey, movedSubtasks: List<MovedNode>)`** (단건 경로 movedSubtasks=empty). **(B1) 같은 Step 에서 `IssueMoveController.move()` 호출부도 `MoveResult` 를 받아 `MoveResponse(issueKey, previousKey, movedSubtasks)` 구성하도록 동시 수정**(컴파일 보존). 기존 테스트(IssueMoveServiceTest/IssueMoveControllerTest/IssueMoveIntegrationTest)도 새 반환 타입에 맞춰 갱신
  - `move()` 확장(자식 있을 때):
    1. 루트 `findByKey`(락 없이 읽어 id·자식조회용) → `findDirectChildren(루트.id)` → 자식 목록
    2. **(B2) 락 순서**: `(루트 + 자식들)` 을 **id 오름차순 정렬** 후 순차 `findByKeyForUpdate(각 key)`. 루트를 별도로 먼저 락하지 않음 — 데드락 회피(spec §동시성, 루트 포함 정렬)
    3. 각 자식 `countDirectChildren > 0` 인 키 집합 → `childKeysWithOwnChildren`
    4. `actualChildKeys`(findDirectChildren 결과 키) vs `providedChildKeys`(request.subtasks 의 issueKey)
    5. **(B3) 노드별 ctx 구성 — vacuous 차단**. 대상 프로젝트 실조회 집합을 루트/자식이 공유하되, 매핑 대상 집합과 **절대 같은 집합을 넘기지 않는다**:
       ```
       targetComponentIds = componentRepository.findByProject(targetProjectId).mapNotNull{it.id}.toSet()  // DB 실조회(단건 패턴)
       targetVersionIds   = versionRepository.findByProject(targetProjectId).mapNotNull{it.id}.toSet()
       requiredFieldKeys  = customFieldDefinitionRepository.findActiveByProject(targetProjectId).filter{it.required}.map{it.key}.toSet()
       // 노드(루트/자식)별:
       //   componentMappingTargetIds = spec.componentMapping.values.filterNotNull().toSet()    // 노드별 요청값(≠ targetComponentIds)
       //   versionMappingTargetIds   = (affects+fix).values.filterNotNull().toSet()
       //   providedFieldKeys         = spec.customFieldValues.keys + node.customFields.keys
       //   targetProjectComponentIds/VersionIds, requiredFieldKeys = 위 공유 DB 집합
       ```
       → `IssueMoveOperation.validateWithSubtasks(rootCtx, childCtxs, actualChildKeys, providedChildKeys, childKeysWithOwnChildren)`
    6. 키 발번: 루트 `incrementKeySequence` → 각 자식 `incrementKeySequence`(순서대로)
    7. **(C3) 노드별 before 캡처**: 각 노드 `moveIssue` **호출 전** before snapshot 보관. 루트 `moveIssue(newParentId=null)`, 각 자식 `moveIssue(newParentId=루트.id)` + 조인 교체 + redirect + `historyRecorder.record(before, after, actor, projectId=before.projectId)`(각 노드 원본 projectId)
    8. `MoveResult(newKey, movedSubtasks)` 반환
    - 자식 없으면(subtasks 빈 배열 + 실제 자식 0) 기존 단건 경로 그대로(회귀 보존)
- [ ] **Step 4: 통과 확인** → PASS (단건 회귀 테스트 포함)
- [ ] **Step 5: REFACTOR** — 노드 이동을 private `moveNode(issue, newKey, newParentId, mapping, ...)` 공통 추출(루트/자식 재사용). **(C5)** 자식 순회 추가로 `move()` 가 `LongMethod`/`CyclomaticComplexity` 추가 위반 가능 → 노드 순회를 위 private 헬퍼로 분리해 임계 내 유지, 불가피하면 `@Suppress` 사유 갱신. **ktlintFormat 모듈 실행 금지(파일 단위 수동 수정)**, detekt baseline regen 금지
- [ ] **Step 6: 커밋** — `feat: IssueMoveService 노드별 동반 이동(단일 트랜잭션)`

### Task 6. IssueMoveController 결선 + HTTP 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueMoveControllerIntegrationTest.kt`]
- depends-on: [3, 5]   # DTO(T3) + 서비스(T5)

- [ ] **Step 1: RED — HTTP 통합테스트**
  - `POST /{key}/move` 동반 이동 → 200 + `movedSubtasks` 정확
  - 다단계 → 422 `SUBTASK_HAS_OWN_SUBTASKS`, 불완전 → 422 `INCOMPLETE_SUBTASK_MAPPING`
  - 자식 없는 이동 → 200(단건 회귀), 옛 키 308 redirect 보존
  ```kotlin
  @Test fun `동반 이동 POST move는 200과 movedSubtasks`() { ... }
  @Test fun `다단계는 422 SUBTASK_HAS_OWN_SUBTASKS`() { ... }
  ```
- [ ] **Step 2: 실패 확인** — `--tests "*IssueMoveControllerIntegrationTest"`
- [ ] **Step 3: GREEN** (`IssueMoveController.kt`)
  - `move()`: `request.subtasks` → `IssueMoveRequest.subtasks` 매핑(`SubtaskMoveMapping`→`SubtaskMoveSpec`). 서비스 `MoveResult` → `MoveResponse(issueKey, previousKey, movedSubtasks)`
  - `preview()` 는 `MovePreview`(subtasks 포함)를 그대로 반환 — 변경 최소
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: REFACTOR** — DTO 매핑 헬퍼 추출, KDoc(throws 신규 예외 추가)
- [ ] **Step 6: 커밋** — `feat: IssueMoveController 동반 이동 결선 + HTTP 통합테스트`

## Plan 메타

- task 수: 6
- 예상 시간: 6 task × ~5분 = 약 30분(직렬). 같은 모듈이라 test 컴파일 직렬 → wave 병렬 이득 제한적
- TDD 강제: yes (각 task test→impl 커밋 순서)
- wave(논리 의존): wave1 [T1, T2] → wave2 [T3, T4, T5] → wave3 [T6]. 단 T2↔T5 가 `IssueRepository.kt` 겹침 → 직렬
- 추가 검증: ktlint/detekt(모듈 baseline, ktlintFormat 금지·파일 단위 수동), 단건 회귀 테스트 필수, 머지 전 전체 `:modules:issue-tracking:test`
- 마이그레이션: 없음 (기존 테이블/컬럼 재사용)

## 리뷰 결과

### plan-eng-review (독립 backend-engineer, 2026-06-16)

`plan-eng-review`/`autoplan` 스킬 미설치 → backend-engineer 에이전트로 독립 적대적 리뷰(메모리 교훈: 백엔드는 eng 집중 독립 리뷰).

**BLOCKER 3건 — 모두 plan 보강으로 해소(본문 반영 완료)**.
- **B1** T5 `move()` 반환 `IssueKey→MoveResult` 인데 `IssueMoveController`/기존 테스트가 T5 files 누락 → 컴파일 파괴. **해소**: T5 files 에 `IssueMoveController.kt`+`IssueMoveControllerTest.kt`+`IssueMoveIntegrationTest.kt` 추가, Step3 에 컨트롤러 동시 적응 명시.
- **B2** 락 순서 — plan 은 루트 먼저 락, spec 은 "루트 포함 id 오름차순"(UUID v4 랜덤이라 루트 먼저 락 시 데드락). **해소**: T5 Step3-2 를 `(루트+자식) id 오름차순 통합 락`으로 수정.
- **B3** `validateWithSubtasks` 자식 ctx 의 매핑/필수필드 집합 채우는 코드 미명시 → vacuous PASS 위험(단건 #153 EC8/EC9 전례). **해소**: T5 Step3-5 에 노드별 ctx 구성 pseudo-code(대상 DB 실조회 공유, 매핑값과 다른 집합) + T1 RED 에 populated 위반 입력 테스트 명시.

**CONCERN 5건 — 반영**.
- **C1** `IssueMoveIntegrationTest` 누락 → T5 files 포함.
- **C2** T4 issueTypeKey 조회 경로 모호 → `findByKeyWithType(childKey)?.typeKey`(readOnly 내 호출 가능)로 구체화.
- **C3** 자식 history before/after projectId → `moveIssue` 호출 전 before 캡처 명시.
- **C4** `IssueHasSubtasksException` 단건/동반 분기 + 유지 명시.
- **C5** detekt `LongMethod`/`CyclomaticComplexity` 위험 → 노드 순회 private 헬퍼 분리, ktlintFormat 금지.

**통과**: 트랜잭션 원자성(단일 @Transactional), 키 발번 순서(advisory lock projectKey 단위), 자식 parent_id 유지(id 불변 전제 정확), cross-BC SPI 부팅 영향 없음(기존 SPI 재사용·mockk stub 확립), TDD task 경계/의존성 일관.

**plan 수정 필요: YES → BLOCKER 3·CONCERN 5 전부 본문 반영 완료. BLOCKER 0 상태로 게이트1 진입.**
