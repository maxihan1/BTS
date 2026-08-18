# FR-BD-01 칸반 보드 (컬럼 표시, 드래그앤드롭)

> slug: fr-bd-01-kanban-board
> type: feature (classify=backend, 풀스택 feature로 교정)
> agent: backend-engineer + frontend-engineer (task별 지정 예정)
> primary_bc: agile-planning
> SDD: §2.1
> 생성: 2026-06-20

## Brief

사용자 원문. "FR-BD-01 진행. 다른 섹션에서 병행 작업 중이므로 워크트리 새로 만들어서 진행."

classify 결과. type=backend → 풀스택 feature로 교정 (칸반 보드 = 컬럼 표시 + 드래그앤드롭, UI 핵심). primary_bc=agile-planning.

비고. 직전 FR-PL-01(#160)에서 "agile-planning 모듈 신설 0" 확인됨 → 이번이 agile-planning BC의 첫 본격 작업일 수 있음. BC 신설 여부는 도메인 단계에서 결정.

## 도메인 정리

- **BC**: agile-planning (**신규 모듈 신설** — backend/modules에 5개만 존재, agile-planning 없음. FR-BD-01이 첫 작업).
- **영향 엔티티 (신규)**: `Board`, `BoardColumn`. Swimlane은 FR-BD-03(이번 범위 밖).
- **cross-BC 참조 (읽기/위임)**:
  - issue-tracking — 보드 카드 = 이슈 목록 읽기. 카드 이동 = **기존 전환 메커니즘 재사용**(전환 API는 `IssueController`/issue-tracking 소유).
  - project-workflow — 보드 컬럼 = **워크플로우 상태 카테고리(TODO/IN_PROGRESS/DONE) 매핑**. 상태 카탈로그는 `WorkflowStateCatalogImpl`이 제공. `kanban-basic.yaml` 워크플로우 기존재.
  - identity-access — 보드 조회 권한(기존 프로젝트 권한 resolver 재사용 예정).
- **새 용어 (glossary 추가 후보, Maxi 승인 필요)**:
  - 보드 (Board) — 프로젝트의 이슈를 컬럼별로 시각화하는 작업 현황판.
  - 보드 컬럼 (BoardColumn) — 보드의 세로 열. 하나 이상의 워크플로우 상태에 매핑.
  - (domain/agile-planning.md 노트에는 이미 Board/BoardColumn이 엔티티로 언급됨. glossary 정식 행은 없음.)
- **결정 사항 (Maxi 확정)**:
  1. 작업 범위 = **백엔드 D1~D5만**. 프론트 D6/D7(@dnd-kit, E2E)은 후속 PR.
  2. 드래그앤드롭 = **컬럼 간 이동만**(= 워크플로우 전환 재사용). 컬럼 내 순서 = 기본 정렬(우선순위/생성일). **LexoRank 불필요** → FR-BL-01로 미룸.
  3. §1 기술검증(LexoRank/@dnd-kit/Gantt PoC)은 이번 범위(백엔드, 컬럼 간 이동)에 직접 불필요. @dnd-kit은 후속 프론트 PR(D6)에서 자연 검증.
- **기존 결정 충돌**: 없음. BC 신설 + 컬럼=상태 매핑 + 카드 이동=전환 재사용은 ADR 신규 후보.
- **관련 ADR**: [docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md](../decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md) (생성됨). 간접 관련 — workflow-transition-identity-policy(2026-05-28), workflow-yaml-vs-db-storage(2026-05-21).

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-bd-01-kanban-board.md](../specs/2026-06-20-fr-bd-01-kanban-board.md)

핵심 시나리오 요약.
- 보드 CRUD(명시적 생성, 한 프로젝트 다중 보드). 생성 시 default 워크플로우 상태를 컬럼으로 자동 시드.
- 보드 조회 = 컬럼(상태별 1:1 매핑) + 컬럼별 카드(이슈). 카드는 current_state_key로 컬럼 배치, viewer visibility 필터, priority ASC 정렬.
- 카드 이동 = `POST /boards/{id}/cards/{issueKey}/move` → 대상 컬럼 state_key로 cross-BC 전환 포트(issue-tracking) 위임. 전환 규칙/권한/OCC는 issue-tracking 강제.

API. POST /boards · GET /boards/{id} · GET /boards?projectKey · POST /boards/{id}/cards/{issueKey}/move
데이터. boards / board_columns (V500~, project_key 문자열 BC격리)
cross-BC 포트. WorkflowStateCatalog 확장(category/displayOrder) · BoardIssueLookupPort 신규(visibility 필터) · IssueTransitionPort 신규(fail-closed) · 보드 권한 포트

## Brainstorming Check

✅ 통과 (직접 adversarial 점검, gap 3건 발견 후 보강 — G1 visibility 누출, G2 권한 레벨, G3 카드 정렬).

## Plan

> 모듈 직렬화 원칙. agile-planning(T1·T6·T7·T8·T9)·issue-tracking(T4·T5)은 같은 모듈이므로 test 컴파일 race 회피 위해 직렬 depends-on. 다른 모듈은 병렬 허용.
> 교훈 반영. WorkflowStateView 확장=default로 기존 호출자/fake 보호(interface-extension-default-method, FR-MV-01 isDone 전례). IssueTransitionPort=fail-closed(crossbc-resolver-nullable-fail-open). 신규 cross-BC 포트 non-null이 기존 전체-컨텍스트 통합테스트 부팅 깸→config stub 빈(fr-nt-02/03 전례). 마이그레이션 V번호 머지 직전 재확인. detekt baseline regen 금지. implementer ktlint false-green→controller --rerun-tasks 직접검증.

### Task 1. agile-planning BC 모듈 부트스트랩

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `backend/modules/agile-planning/build.gradle.kts`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/package-info.kt`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/architecture/AgilePlanningBcArchTest.kt`]
- depends-on: []

**RED**: `AgilePlanningBcArchTest` — BC 격리 룰(issue-tracking `com.bts.issue..`/project-workflow `com.bts.workflow..`/**identity-access `com.atlas.bts.identity..`(다른 BC와 패키지 prefix 다름, NotificationBcArchTest L101 참조)** 직접 import 금지, jOOQ 생성코드는 repository 패키지만, @Transactional=@Service/@Component). 모듈/패키지 부재로 실패. **vacuous 방지**: 룰이 대상 클래스를 실제로 잡는지 일부러 위반(import) 넣어 fail 확인 후 제거(archunit-vacuous-rule 교훈).
**GREEN**: settings.gradle.kts에 `include(":modules:agile-planning")`. build.gradle.kts = notification 템플릿 복제(jOOQ codegen packageName=`com.bts.agileplanning.jooq`+init_codegen.sql, Flyway `db/migration/agile-planning`, detekt/ktlint, `implementation(project(":modules:shared-kernel"))`). `com.bts.agileplanning.{domain,application,repository,web,config}` 레이아웃 + ArchUnit 통과 최소 클래스.
**REFACTOR**: init_codegen.sql 헤더 주석(한국어). ArchUnit 룰 KDoc.
**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests *AgilePlanningBcArchTest`

### Task 2. shared-kernel cross-BC 포트 정의

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/WorkflowStateView.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/IssueTransitionPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardPortContractTest.kt`]
- depends-on: []

**RED**: `BoardPortContractTest` — (a) `WorkflowStateView`가 category/displayOrder 기본값으로 생성됨(기존 2-arg 호출 보호), (b) `BoardIssueLookupPort.listVisibleIssuesByProject` default=빈 목록, (c) `IssueTransitionPort`는 default 없음(구현 필수) 컴파일 검증.
**GREEN**:
- `WorkflowStateView`에 `category: String = "TODO"`, `displayOrder: Int = 0` 추가(기존 `key/name/isDone` 유지, default로 기존 호출 보호).
- `BoardIssueLookupPort { fun listVisibleIssuesByProject(projectKey: String, viewerUserId: UUID): List<BoardIssueView> = emptyList() }` + `BoardIssueView(key, summary, currentStateKey, assigneeId, priority, version)`.
- `IssueTransitionPort { fun transition(cmd: BoardTransitionCommand): BoardTransitionResult }`(default 없음, fail-closed) + `BoardTransitionCommand(issueKey, toStateKey, expectedVersion, resolutionId?)` + `BoardTransitionResult(issueKey, currentStateKey, version)`. **actorUserId 인자 없음(리뷰 정정)** — adapter가 CurrentActor 추출(actor 위조 차단, sec CONCERN-3).
**REFACTOR**: KDoc(BC 격리 사유·fail-closed·actor=CurrentActor 추출 계약 명시).
**검증**: `cd backend && ./gradlew :modules:shared-kernel:test --tests *BoardPortContractTest`

### Task 3. project-workflow WorkflowStateCatalogImpl view 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowStateCatalogImpl.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowStateCatalogImplTest.kt`]
- depends-on: [2]

**RED**: 기존 `WorkflowStateCatalogImplTest`에 **명시 단언 추가**(vacuous 방지, eng CONCERN-1) — `result.first { it.key == "open" }.category == "TODO"`, displayOrder 일치 단언. default 값으로 통과하는 vacuous pass를 차단(필드 추가만으로 통과하면 안 됨, ArchUnit vacuous 교훈 동형).
**GREEN**: `WorkflowStateView(key, name, isDone=..., category=state.category.name, displayOrder=state.displayOrder)`.
**REFACTOR**: 매핑 정리.
**검증**: `cd backend && ./gradlew :modules:project-workflow:test --tests *WorkflowStateCatalogImplTest`

### Task 4. issue-tracking BoardIssueLookupPort 구현 (visibility 필터)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapterTest.kt`]
- depends-on: [2]

**RED (리뷰 정정, sec BLOCKER-1)**: 통합테스트를 **목록 보안필터 정석(`IssueSecurityListFilterTest` S1~S8 형태)**으로 작성 — 주입된 `IssueSecurityAccess`로 SQL WHERE 술어가 비멤버 등급 이슈를 content·count 양쪽에서 제외. (a) soft-deleted 제외, (b) **viewer 미가시 보안수준 이슈 제외(content+count)**, (c) priority 포함, (d) N+1 없이 단일 쿼리.
**GREEN (리뷰 정정)**: `@Component BoardIssueLookupAdapter : BoardIssueLookupPort`. **새 보안 경로 만들지 말 것** — `IssueSecurityDirectory.accessibleLevels(viewerUserId, projectKey)` → `IssueRepository.listWithType`(또는 같은 `buildSecurityPredicate` 술어 재사용하는 비페이지 메서드)로 SQL 푸시다운 필터. `IssueVisibilityPort`/`IssueSecurityDecider`(수신자용 단건 위임·N+1) 사용 금지. priority/current_state_key/assignee 매핑.
**REFACTOR**: 술어 단일 source 재사용. ktlint↔detekt 라인길이 블록body.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests *BoardIssueLookupAdapterTest`

### Task 5. issue-tracking IssueTransitionPort 구현 (전환 위임)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/IssueTransitionAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/IssueTransitionAdapterTest.kt`]
- depends-on: [2, 4]

**RED**: 통합테스트 — `transition(cmd)`가 기존 전환 application service에 위임해 상태 변경. 전환 불가→예외 전파, 버전 충돌→예외 전파, 권한 강제, DONE+resolution 누락→예외.
**GREEN**: `@Component IssueTransitionAdapter : IssueTransitionPort`. 기존 `IssueApplicationService.transitionIssue`(IssueController가 쓰는 동일 경로) 위임. **actor는 `CurrentActor.current()`로 추출(cmd에서 받지 않음, sec CONCERN-3)** — 동기 호출이라 SecurityContext 유효. 도메인 직접 UPDATE 금지(불변식 우회 회피).
**REFACTOR**: 예외 매핑 정리.
**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests *IssueTransitionAdapterTest`

### Task 6. agile-planning 마이그레이션 V500 boards/board_columns

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V500__boards.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSchemaMigrationTest.kt`]
- depends-on: [1]

**RED**: `BoardSchemaMigrationTest`(Testcontainers) — Flyway 적용 후 boards/board_columns 테이블·UNIQUE(board_id,state_key)·FK CASCADE·idx_boards_project_key 존재 단언.
**GREEN**: spec §데이터 모델 DDL. `init_codegen.sql`에 동일 DDL 미러(jOOQ codegen, jooq-init-codegen-mirror 교훈).
**REFACTOR**: 주석. V번호는 머지 직전 재확인(migration-vnumber 교훈).
**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests *BoardSchemaMigrationTest`

### Task 7. agile-planning 도메인 + 컬럼 시드/카드 배치 로직

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/Board.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardColumn.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardCardPlacement.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardCardPlacementTest.kt`]
- depends-on: [1, 2, 6]

**RED**: 순수 단위테스트 — (a) 워크플로우 상태 목록(WorkflowStateView)→컬럼 시드(state_key/name/category/displayOrder 매핑, displayOrder 순), (b) 이슈(BoardIssueView) 목록을 current_state_key로 컬럼 배치, (c) 미매핑 상태 이슈 제외(E2), (d) 컬럼 내 priority ASC + created 보조 정렬.
**GREEN**: Board/BoardColumn 도메인 + 시드/배치 순수 함수.
**REFACTOR**: VO 정리.
**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests *BoardCardPlacementTest`

### Task 8. agile-planning 보드 서비스 + repository (CRUD + 이동 위임)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/AgilePlanningTestBootApplication.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/AgilePlanningTestcontainersConfig.kt`]
- depends-on: [3, 5, 6, 7]

**RED**: 통합테스트(Testcontainers) — (a) 보드 생성 시 WorkflowStateCatalog(포트)로 default 상태 조회→컬럼 시드+영속, (b) E1 스킴 미할당→422, (c) 조회 시 BoardIssueLookupPort로 카드 배치, (d) 카드 이동=toColumnId→state_key 도출 후 IssueTransitionPort 위임, (e) E3 같은 컬럼 no-op 200, (f) E8 보드-이슈 프로젝트 정합.
**GREEN**: `@Service BoardApplicationService`(@Transactional) + `BoardRepository`(jOOQ). cross-BC는 포트만 호출(BC 격리). 신규 포트 non-null 주입. **테스트 부팅 인프라(eng BLOCKER-1)**: 신규 모듈이라 `AgilePlanningTestBootApplication`(test @SpringBootApplication) + `AgilePlanningTestcontainersConfig`(WorkflowStateCatalog/BoardIssueLookupPort/IssueTransitionPort/IssuePermissionResolver stub 빈) 필요(notification TestBootApplication/TestcontainersConfig 동형). non-null 포트가 test-assembled stub로 주입돼야 부팅(no-cross-bc-deployment-assembly).
**REFACTOR**: 쿼리/매핑 정리.
**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests *BoardApplicationServiceTest`

### Task 9. agile-planning 보드 컨트롤러 + 권한 결선 + HTTP 통합테스트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [8]

**RED (리뷰 정정)**: HTTP 통합테스트 — POST/GET/move 4 엔드포인트. **권한 2단 게이트**: 조회 = `IssuePermission.BROWSE`(VIEW 아님, sec BLOCKER-2) on `IssueScope.Project`, **생성 = `IssuePermission.CREATE`(Maxi 게이트1 확정)**, 카드 노출은 행단위 보안필터(T4) 별도 적용, 이동 = TRANSITION(전환 포트 강제). **actor 추출 → 권한 → 리소스 조회(404) → 보드-이슈 정합(E8) 순서**(존재 probe 차단, sec CONCERN-4). 권한/정합 거부 message 일반화(누출 차단). 에러코드(400/403/404/409/422) + 응답 봉투(DataResponse/{error}). 도메인예외 HTTP 매핑(catch-all이 401/타입미스매치 삼키지 않음 — catch-all-exceptionhandler 교훈).
**GREEN (리뷰 정정)**: `@RestController BoardController`. 권한은 **기존 `IssuePermissionResolver`(shared-kernel 포트) 주입 재사용**(신규 BoardPermissionPort 만들지 않음, eng BLOCKER-2 + sec CONCERN-2). non-null 주입(fail-closed, 빈 부재=부팅실패). 명시 ExceptionHandler. actor=`CurrentActor.current()`.
**REFACTOR**: DTO/핸들러 정리.
**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests *BoardControllerIntegrationTest && ./gradlew :modules:agile-planning:ktlintCheck detekt`

## Plan 메타

- task 수: 9
- 모듈 분포: agile-planning(T1·T6·T7·T8·T9 직렬) · shared-kernel(T2) · project-workflow(T3) · issue-tracking(T4·T5 직렬)
- 예상 wave: 5 (W1: T1·T2 / W2: T3·T4·T6 / W3: T5·T7 / W4: T8 / W5: T9)
- TDD 강제: yes (RED→GREEN→REFACTOR, test 커밋 선행)
- agent 분담: backend-engineer(T1·T2·T3·T5·T7·T8) · security-engineer(T4·T9, visibility/권한) · db-engineer(T6)
- 추가 검증: ktlint + detekt(--rerun-tasks, false-green 방지) + 모듈 통합테스트(Testcontainers)
- 후속 PR: 프론트 D6(@dnd-kit)/D7(E2E+NFR). **D6 인계(eng CONCERN-2)**: T2의 WorkflowStateView category/displayOrder 추가가 기존 `MovePreviewResponse`(POST /issues/{key}/move/preview) 응답 JSON에 additive 필드 → D6/이슈이동 프론트 Zod가 `.strict()`면 깨짐, nullish/passthrough 확인 필요.
- NIT(sec NIT-1): BoardIssueView.priority는 정렬 내부용 → 보드 조회 응답 카드 DTO에서 priority 제외(spec 응답 필드 정합).

## 리뷰 결과

eng(backend-engineer) + security(security-engineer) 독립 plan 리뷰 병행(autoplan 대신, bts-review-plan-autoplan-overkill). 두 리뷰 모두 실제 코드 grep/read로 사실 확인.

### eng plan-review (2026-06-20)
- **BLOCKER-1 (해소)**: T8 통합테스트 인프라 파일(AgilePlanningTestBootApplication/TestcontainersConfig) files 누락 → T8 files 추가.
- **BLOCKER-2 (해소)**: T9 BoardPermissionPort 결선 파일 양방향 누락 → 신규 포트 폐기, 기존 IssuePermissionResolver 재사용으로 변경(security CONCERN-2와 수렴).
- **CONCERN-1 (반영)**: T3 RED vacuous pass 위험(category/displayOrder 단언 없으면 default로 통과) → T3 RED 명시 단언 추가.
- **CONCERN-2 (반영)**: WorkflowStateView 확장이 MovePreviewResponse 응답에 additive 필드 → Plan 메타 D6 인계 메모.
- **NIT-1 (반영)**: identity-access 패키지 `com.atlas.bts.identity..` → T1 ArchUnit 명시.

### security plan-review (2026-06-20)
- **BLOCKER-1 (해소)**: 보드 카드 visibility 경로 오지정 — IssueSecurityDecider/IssueVisibilityPort(수신자용 단건, N+1+누출 위험)가 아니라 **목록 정석(accessibleLevels + listWithType SQL 푸시다운, FR-PM-06)** 재사용 → T4 RED/GREEN + spec FR-BD-01-4b 정정.
- **BLOCKER-2 (해소)**: 보드 조회 권한 VIEW(단건)↔BROWSE(목록) 불일치 → BROWSE로 정정 + 카드 노출 행단위 보안필터 2단 게이트(T9, spec FR-BD-01-6).
- **CONCERN-1 (Maxi 게이트1 결정)**: 보드 생성을 BROWSE(조회 동급)로 두면 과대 허용(쓰기) → 생성 권한 강도 Maxi 확정 필요(BROWSE/CREATE/MANAGE_SCHEME).
- **CONCERN-2 (반영)**: BoardPermissionPort 불필요 → IssuePermissionResolver 재사용(eng BLOCKER-2 수렴).
- **CONCERN-3 (반영)**: IssueTransitionPort actorUserId 무검증 신뢰 위험 → cmd에서 actorUserId 제거, adapter가 CurrentActor 추출(T2/T5).
- **CONCERN-4 (반영)**: E8 actor 추출 순서/거부 message 누출 → T9 RED에 actor→권한→리소스 순서 + message 일반화.
- **NIT-1 (반영)**: 응답 카드 priority 제외(정렬 내부용) → Plan 메타. **NIT-2**: V500 머지 직전 재확인.

### 종합
- BLOCKER 4건 전부 plan/spec 정정으로 해소. CONCERN 6건 반영.
- **Maxi 게이트1 결정 완료**: 보드 생성 권한 = `IssuePermission.CREATE`(이슈 생성 동급). 게이트1 승인 → 구현 진입.
