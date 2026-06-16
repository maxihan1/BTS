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
  ```kotlin
  @Test fun `동반 경로는 자식의 자식이 있으면 SubtaskHasOwnSubtasks`() { ... }
  @Test fun `제공 자식 키가 실제 자식 집합과 다르면 IncompleteSubtaskMapping`() { ... }
  @Test fun `동반 경로 루트는 hasSubtasks여도 EC15 미발생`() { ... }
  @Test fun `자식 노드 매핑 대상 미존재면 InvalidTargetMapping`() { ... }
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
  - `preview()`: `issueRepository.findDirectChildren(issue.id.value)` → 각 자식의 issueTypeKey 조회(`findByKeyWithType` 또는 타입 repo) → 기존 `buildWorkflowSection`/`buildComponentSection`/`buildVersionSection`/`buildCustomFieldSection` 을 자식별로 호출. 워크플로우는 자식 issueTypeKey 전달
  - 루트 워크플로우도 루트 issueTypeKey 로 조회(단건은 null — 노드별 정확도). `buildWorkflowSection` 시그니처에 issueTypeKey 추가
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: REFACTOR** — 노드 섹션 빌더를 루트/자식 공통 `buildNodeSections(issue, issueTypeKey, ...)` 로 추출
- [ ] **Step 6: 커밋** — `feat: MovePreviewService 노드별 subtasks 섹션`

### Task 5. IssueMoveService — 노드별 이동 (단일 트랜잭션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueMoveService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueMoveServiceTest.kt`]
- depends-on: [1, 2]   # 도메인 검증 + findDirectChildren. (T2와 IssueRepository.kt 파일 겹침 → 직렬)

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
  - `IssueRepository.moveIssue` 에 `newParentId: UUID?` 파라미터 추가 → `.set(ISSUES.PARENT_ID, newParentId)`. **기존 단건 호출부는 `newParentId = null`** 전달
  - `IssueMoveRequest` 에 `subtasks: List<SubtaskMoveSpec> = emptyList()` 추가. `SubtaskMoveSpec(issueKey, expectedVersion, targetStateKey, targetStateIsDone, componentMapping, affectsVersionMapping, fixVersionMapping, additionalCustomFields)`
  - `move()` 확장(자식 있을 때):
    1. 루트 `findByKeyForUpdate`
    2. `findDirectChildren(루트.id)` → 자식 목록(id 오름차순). 각 자식 `findByKeyForUpdate`(락)
    3. 각 자식 `countDirectChildren > 0` → `childKeysWithOwnChildren`
    4. `actualChildKeys`(실제) vs `providedChildKeys`(request.subtasks.issueKey)
    5. 루트+자식 ctx 구성 → `IssueMoveOperation.validateWithSubtasks(...)`
    6. 키 발번: 루트 `incrementKeySequence` → 각 자식 `incrementKeySequence`(순서대로)
    7. 루트 `moveIssue(newParentId=null)` + 조인 교체 + redirect + 히스토리
    8. 각 자식 `moveIssue(newParentId=루트.id)` + 조인 교체 + redirect + 히스토리(각 노드 원본 projectId)
    9. `MoveResult`(newKey + movedSubtasks) 반환
    - 자식 없으면(subtasks 빈 배열) 기존 단건 경로 그대로
  - 반환 타입을 `IssueKey` → `MoveResult(newKey, movedSubtasks)` 로 변경(controller가 movedSubtasks 사용). 단건 경로는 movedSubtasks=empty
- [ ] **Step 4: 통과 확인** → PASS (단건 회귀 테스트 포함)
- [ ] **Step 5: REFACTOR** — 노드 이동을 private `moveNode(issue, newKey, newParentId, mapping, ...)` 공통 추출(루트/자식 재사용), `@Suppress` 사유 갱신
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

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
