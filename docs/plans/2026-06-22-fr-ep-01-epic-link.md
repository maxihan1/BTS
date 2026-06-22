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
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponseTest.kt` (기존 파일 — 테스트 **추가**, 덮어쓰기 금지)]
- depends-on: []

**RED**: `IssueResponseTest`(기존 파일에 케이스 추가) — `IssueResponse.from(..., epic = EpicSummary(key, summary))` 시 epic 채움, default null(목록 경로) 단언. 실패: epic 프로퍼티/EpicSummary 부재.

**GREEN**: `IssueResponse.epic: EpicSummary? = null` + `EpicSummary(key, summary)` 중첩 클래스 + `from(...)` epic 파라미터 default null(@JsonInclude(NON_NULL) — parent 동형, 기존 호출처 무변경).

**REFACTOR**: KDoc — 단건 조회(findByKeyWithType)에서만 채움, parent 노출과 동형.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueResponseTest"`.

### Task 4. IssueRepository — epic 메서드 (updateEpic/clear · self-join 노출 · 자식 visibility 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryEpicIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**: `IssueRepositoryEpicIntegrationTest` (Testcontainers) —
- `updateEpic(childId, epicId)` / `updateEpic(childId, null)` 가 epic_id 설정/해제.
- `findByKeyWithType` 가 epic self-LEFT-JOIN으로 IssueResponse.epic(key, summary) 채움(소프트삭제 Epic은 null) + 목록(`listWithType`)은 epic null.
- `findByKey` 재구성 시 Issue.epicId 채움.
- `findEpicChildren(epicId, accessibleLevels, projectKey)` 가 보안 등급 필터 푸시다운(접근 불가 자식 제외) + 소프트삭제 자식 제외 — listVisibleForBoard 보안 술어 재사용. (**eng-C3**: `buildActiveSecureWhere`가 projectKey 필수 인자라 시그니처에 projectKey 포함, PROJECTS JOIN 동반.)
실패: 메서드 부재.

**GREEN**: 위 메서드 구현. self-join은 parent self-join 패턴(IssueResponse.kt:522 인근) 미러(deleted_at만 필터, parent 동형). 자식 조회는 `listVisibleForBoard`(IssueRepository:658) 보안 술어(`buildActiveSecureWhere`, projectKey+accessibleLevels) 재사용 + `WHERE epic_id = :epicId`. 정렬=issue key/created_at 안정 정렬.

**REFACTOR**: 공통 보안 술어 헬퍼 재사용 확인(중복 0). detekt 복잡도 점검.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueRepositoryEpicIntegrationTest"`.

### Task 5. IssueEpicService — 불변식 5종 + 권한 + 자식목록 (+ G1 changelog)

**메타**.
- agent: `backend-engineer` (권한/visibility — security-engineer plan-review 검토 완료, 아래 보안 보정 반영)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/application/IssueEpicService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/domain/EpicChildExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/application/IssueEpicServiceTest.kt`, *(G1=기록 시)* `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeDetector.kt`]
- depends-on: [4]

**RED**: `IssueEpicServiceTest` (mockk repo/resolver/securityDirectory/typeRepo) —
- `connect(epicKey, childKey, actorId)`: **권한 UPDATE(child, IssueScope.Issue) 선행**(repo 조회 전, probe 방지) → child 404 → epic 404(**미존재·미가시 동일 — 존재 숨김, security N1**) → self 422 → 자식 hierarchyLevel≠0 422 → epic hierarchyLevel≠1 422 → cross-project 422 → child.epicId≠null 409 → updateEpic 호출.
- `disconnect(epicKey, childKey, actorId)`: UPDATE(child) → child 404 → child.epicId≠이 epic 404 → updateEpic(null).
- `listChildren(epicKey, actorId)`: **BROWSE(epic 프로젝트, IssueScope.Project) 진입**(security C1·N3 — board 동형, VIEW(epic) scope=Issue 아님) → epic 404 → accessibleLevels 조회 → findEpicChildren(epicId, accessibleLevels, projectKey) 위임. **accessibleLevels 단독≠VIEW 매트릭스**라 BROWSE 진입이 매트릭스 담당.
- 권한 없음 → IssueAccessDeniedException(403, child UPDATE). 불변식 위반 → 각 전용 예외.
- **[G1 권장=기록]** connect/disconnect 성공 시 IssueHistoryRecorder로 자식 changelog("epic" 필드) 기록 단언. *(게이트1 G1 미기록 결정 시 이 단언+recorder 호출+SCALAR_FIELD_EXTRACTORS 항목 제거, IssueChangeDetector.kt files 제외.)*
실패: IssueEpicService/예외 부재.

**GREEN**: 서비스 구현. `@Transactional` + `@Service`(무력화 회피, learnings 2026-05-20). 권한=`IssuePermissionResolver.hasPermission(actorId, UPDATE/BROWSE, scope)`. **fail-closed (security C3)**: `IssuePermissionResolver`/`IssueSecurityDirectory`는 **default 없는 non-null 생성자 주입**(AlwaysAllow default 금지 — prod 빈 미주입 시 부팅 실패, crossbc-resolver-nullable-fail-open). hierarchyLevel·project는 `findByKeyWithType`(IssueRepository:507) 단일 쿼리로 동반 조회 권장(eng-C4, child·epic 각 1쿼리). visibility=`IssueSecurityDirectory.accessibleLevels`. 예외=plain 도메인 예외(ISSUE_EPIC_* 매핑). **[G1=기록]** `IssueChangeDetector.SCALAR_FIELD_EXTRACTORS`에 `"epic" to { it.epicId?.toString() }` 추가 + 서비스가 `IssueHistoryRecorder.record(before, after)` 호출(전용 엔드포인트라 PATCH 경로와 무관, before==after 시 no-op이라 PATCH 경로 부작용 0). 프론트 changelog-label("에픽")은 D6/D7 후속 PR.

**REFACTOR**: 검증 순서 KDoc. ThrowsCount @Suppress(IssueParentService 선례, 사유 주석). 권한 선행 단언 보존. 예외 detail 내부정보 비노출(security N2).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueEpicServiceTest"`.

### Task 6. IssueEpicController + DTO + 예외 핸들러 + 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/IssueEpicController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/dto/CreateEpicChildRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/dto/EpicChildSummaryResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/dto/EpicChildListResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/EpicChildExceptionHandler.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/EpicChildErrorCodes.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/web/IssueEpicControllerIntegrationTest.kt`]
- depends-on: [5]

**RED**: `IssueEpicControllerIntegrationTest`(@SpringBootTest, Testcontainers) — S1~S11 전 경로 + 오류 매핑(**401**/404/422/409/403/400) HTTP 상태·errorCode 단언. **적대리뷰 vacuous 회피 — actor별 시드로 실제 403/누출0 검증**. (security C1): **VIEW_ISSUE 매트릭스로 제한된 자식이 GET 목록에서 빠지는** actor별 시드 단언 필수.

**GREEN**: `@RestController @RequestMapping("/api/v1/issues/{key}")` IssueEpicController(POST/DELETE/GET epic-children). DataResponse 봉투. **actor=CurrentActor.current() 컨트롤러 최상단 호출**(미인증 401·findByKey(404)보다 앞, auth-extraction-before-resource-lookup)→service 전달(위조 차단, FR-BD-01 선례). EpicChildExceptionHandler `@RestControllerAdvice(assignableTypes=[IssueEpicController])` 스코프 한정. **핸들러 순서(B1)**: `ResponseStatusException`(401 — WatcherExceptionHandler:112 `handleResponseStatus` 패턴) + `MethodArgumentTypeMismatch`/`HttpMessageNotReadable`(400) 구체 핸들러 **먼저**, `Exception` fallback 최후(catch-all-exceptionhandler-swallows-responsestatusexception 회피). errorCode prefix=`ISSUE_EPIC_`(eng-C5).

**REFACTOR**: KDoc 엔드포인트 표 + errorCode 정본화. 예외 detail 내부정보 비노출.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueEpicControllerIntegrationTest"` + 모듈 전체 ktlintCheck/detekt(--rerun-tasks).

## Plan 메타

- task 수: 6
- 예상 시간: 직렬 약 30분 (단일 모듈 직렬 dispatch 권장)
- TDD 강제: yes (test 커밋 먼저)
- 병렬 dispatch: 논리 wave W1{T1,T2,T3}→W2{T4}→W3{T5}→W4{T6}이나 단일 Gradle 모듈 컴파일 직렬화 + lint-staged race로 **직렬 권장**
- 추가 검증: ktlint, detekt(--rerun-tasks), verify-master-plan(FR 123 불변), clean 빌드(jOOQ codegen)
- 🛑 게이트1 결정: **G1(changelog 기록)** — 권장=기록(T5에 포함). 미기록 시 T5의 IssueHistoryRecorder 호출+단언 제거.

## 리뷰 결과

eng(backend-engineer) + security(security-engineer) 독립 병행 리뷰. 둘 다 **PASS_WITH_CONCERNS**. ceo/design 생략(정의된 FR).

### plan-eng-review (2026-06-22) — PASS_WITH_CONCERNS
- **B1 (해결)**: T6에 `ResponseStatusException`(401) 구체 핸들러 누락→catch-all이 401을 500 변질. WatcherExceptionHandler:112 패턴을 T6 GREEN+RED(401)에 반영함.
- **B2 (해결)**: G1 changelog 기록 방법 미명시(IssueChangeDetector.SCALAR_FIELD_EXTRACTORS에 epicId 없음). T5 GREEN에 "epic" extractor 추가 + record(before,after) 호출로 명시함(IssueChangeDetector.kt를 T5 files에 추가, G1=기록 시).
- **C1 (해결)**: T3~T6 테스트 경로 축약→완전 경로로 보정.
- **C2 (해결)**: IssueResponseTest 기존 파일→"테스트 추가, 덮어쓰기 금지" 명시.
- **C3 (해결)**: T4 findEpicChildren에 projectKey 인자 + PROJECTS JOIN 명시.
- **C4 (반영)**: hierarchyLevel/project를 findByKeyWithType 단일 쿼리 동반 조회 권장(쿼리 수↓).
- **C5 (해결)**: errorCode prefix EPIC_→ISSUE_EPIC_(에이전트 §6 ISSUE_ 우산).
- **C6/N1~N3**: depends-on 정확(조치 불요)·예외 위치 KDoc·@Suppress 사유 주석(REFACTOR 반영).

### security plan-review (2026-06-22) — PASS_WITH_CONCERNS, BLOCKER 0
- **C1 (해결, 핵심 누출)**: listChildren visibility가 accessibleLevels 단독이면 VIEW_ISSUE 매트릭스 누락→제목 누출(FR-NT-03 반례). **board 동형으로 보정**: BROWSE(epic 프로젝트, Project scope) 진입 + accessibleLevels 푸시다운(T5). T6 통합테스트에 VIEW-제한 자식 목록 제외 actor별 시드 단언 필수로 명시.
- **C2 (해결)**: 단건 IssueResponse.epic은 parent self-join 동형(보안등급 무필터)—의도적 수용, ADR deviation + 스펙 엣지케이스 명시.
- **C3 (해결, fail-closed)**: IssueEpicService는 resolver/securityDirectory default 없는 non-null 주입(prod 빈 미주입=부팅 실패). T5 GREEN 반영.
- **C4 (해결, probe)**: actor=CurrentActor.current() 컨트롤러 최상단, findByKey(404)보다 앞. T6 GREEN 반영.
- **N1 (해결)**: epic VIEW 실패→404(존재 숨김, 403 아님), 단건 VIEW 404-hide 정책 일관. 스펙/T5 반영. child UPDATE 실패는 403 유지.
- **N2/N3**: 예외 detail 비노출·listChildren scope=Project(C1 통합).
- 종합: 기존 IssueLinkController/ParentService의 권한 검증 전무 갭을 메우는 방향이라 보안 개선. C1·C3는 impl 게이트2 통합테스트에서 actor별 시드 누출0 단언으로 강제.

### 게이트1 결정 (2026-06-22, Maxi 확정)
- **G1 = 기록**. epic 연결/해제를 자식 changelog에 기록(T5의 IssueChangeDetector "epic" extractor + IssueHistoryRecorder.record 포함, IssueChangeDetector.kt를 T5 files에 포함). 프론트 changelog-label("에픽")은 D6/D7 후속 PR.
- **게이트1 = 승인**. /bts-impl 진행.
