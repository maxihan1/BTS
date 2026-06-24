<!-- FR-BL-02 백로그→스프린트 이동 백엔드(D1~D5) 구현 계획 -->
# FR-BL-02 — 백로그 → 스프린트 드래그 이동 (백엔드 D1~D5)

> slug: fr-bl-02-sprint-backend
> type: api
> agent: backend-engineer
> primary_bc: agile-planning 단독 (Sprint + sprint_issues 조인 — ADR 2026-06-24, issues 무변경)
> 생성: 2026-06-24
> ⚠️ 진실출처: 이 plan 파일 (.bts-cache/classify.json은 멀티세션 충돌 가능)

## Brief

FR-BL-02 백로그→스프린트 이동의 **백엔드 D1~D5만** 이번 PR 범위.
- Sprint 도메인 신설 (agile-planning BC 단독)
- `sprints` + `sprint_issues` 조인 테이블 (agile-planning, V503+ / issues 무변경)
- 스프린트 CRUD API + 이슈→스프린트 할당/해제 API
- 권한(security-engineer 공동 검토), 백엔드 테스트

프론트 D6/D7(@dnd-kit 백로그↔스프린트 드래그)은 **이번 PR 제외** — 후속에서 FR-BL-01 D6/D7(백로그 정렬 UI)과 통합.

classify: { type: api, agent: backend-engineer, primary_bc: agile-planning }
product agile-planning.md §3.2 / SDD §13 / fr-index §3.2
배경: docs/plan/README.md §2(stash 보관) — FR-BL-02가 cross-BC 병목(리포트 4종 FR-RP-01~04 선행)으로 최우선 지목됨.

## 도메인 정리

- **BC**: agile-planning **단독**. issue-tracking 무변경(board 선례 — cross-BC는 shared-kernel 포트로만 통신).
- **신규 엔티티**:
  - `Sprint` (agile-planning) — 프로젝트 단위 작업 기간. 상태(라이프사이클)·기간·목표 보유.
  - `SprintIssue` 연관 — `sprint_issues(sprint_id, issue_key)` 조인. `issue_key`는 이슈 키 **느슨 참조**(cross-BC FK 없음). board 표면이 issueKey 중심 + adapter가 issue-tracking 소유 → key 저장으로 포트 무확장·단일 BC 유지(ADR §식별자).
- **신규 테이블**: `sprints`, `sprint_issues` (agile-planning 마이그레이션 V503+). issues 테이블 변경 없음.
- **cross-BC 이슈 조회**: 기존 `BoardIssueLookupPort` 재사용/확장(board가 이슈를 읽는 패턴 동일). issue-tracking 직접 import 금지.
- **용어**: 스프린트(Sprint)·백로그(Backlog)는 glossary에 이미 정의됨 → 신규 용어 0. (sprint 상태 enum 명칭은 spec에서 확정.)
- **관계 모델 결정 (Maxi 확정)**: `sprint_issues` 조인 테이블 (모델 B). product §3.2 D3의 `issues.sprint_id`(모델 A)는 BC 격리·회귀위험(FR-BL-01 rank 254 파급류) 사유로 기각.
- **product/SDD drift 정정 대상**: agile-planning.md §3.2 D3 `issues.sprint_id` → `sprint_issues 조인`. fr-index/SDD 동기화는 머지 PR에서 전수 반영.
- **관련 ADR**: [docs/decisions/2026-06-24-fr-bl-02-sprint-issue-association.md](../decisions/2026-06-24-fr-bl-02-sprint-issue-association.md) (생성됨)
- **기존 결정 충돌**: 없음. FR-BD board 패턴과 일관. FR-BL-01 rank(issue-tracking)와는 별개 영역(rank=정렬, sprint=그루핑).

## 스펙

전체 스펙. [docs/specs/2026-06-24-fr-bl-02-sprint-backend.md](../specs/2026-06-24-fr-bl-02-sprint-backend.md)

핵심 결정 (Maxi 확정).
- 범위: 스프린트 CRUD + 이슈 할당/해제 + 상태전이(PLANNED→ACTIVE→COMPLETED). 스프린트 내 순서(rank)는 이연. 동시 ACTIVE 다중 허용.
- 관계: `sprint_issues(sprint_id, issue_key)` 조인, `UNIQUE(issue_key)`로 1:N 강제(다른 스프린트 할당 시 원자적 이동). issues 무변경.
- 9개 엔드포인트(`/api/v1/sprints` CRUD 5 + start/complete 2 + 이슈 할당/해제 2). 권한 `IssuePermission`(CRUD/관리=CREATE, 조회=BROWSE) + `IssuePermissionResolver` 재사용(board 선례).
- 할당 가시성 검증은 기존 `BoardIssueLookupPort.listVisibleIssuesByProject`(key 중심) 재사용 — 포트 무확장.
- **백로그 조회 API는 D6 이연**(rank 정렬=포트 확장 회피). version: 할당/해제=no-bump, 상태전이/메타수정=bump. 소프트삭제 시 연관 제거→백로그 복귀.
- 데이터: V503 `sprints` + `sprint_issues`(agile-planning), init_codegen 미러.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회, gap 3건 발견·반영: 백로그 조회 API 누락·소프트삭제 연관 처리·version 동시성 정책). Maxi 추가 결정 불필요.

## Plan

> Gradle 모듈: `:modules:agile-planning` 단독. 검증은 `backend/`에서 실행.
> 모듈 컴파일 직렬(메모리 bts-plan-wave-gradle-module-compile): T2~T7은 같은 agile-planning 모듈 → 파일 안 겹쳐도 컴파일은 모듈 일괄.
> 신규 테이블(sprints/sprint_issues)이라 기존 jOOQ 레코드 무변경 → FR-BL-01 같은 기존테스트 대량파급 위험 없음(issues 무변경).

### Task 1. V503 마이그레이션 — sprints + sprint_issues + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V503__sprints.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/SprintSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `SprintSchemaMigrationTest`(Testcontainers) — V503 적용 후 `sprints`(status CHECK PLANNED/ACTIVE/COMPLETED, deleted_at nullable, version 기본0) + `sprint_issues`(issue_key NOT NULL, `UNIQUE(issue_key)`, PK(sprint_id, issue_key), FK sprint_id→sprints ON DELETE CASCADE) + 인덱스 `idx_sprints_project`(부분, deleted_at IS NULL)·`idx_sprint_issues_sprint` 존재 검증. UNIQUE(issue_key) 중복 INSERT 거부 검증.

**GREEN**: `V503__sprints.sql` 두 테이블 + 제약 + 인덱스. init_codegen.sql(agile)에 두 테이블 정의 인라인 미러(메모리 jooq-init-codegen-mirror).

**REFACTOR**: 한 줄 헤더 주석, CHECK/UNIQUE 의도 주석.

**검증**: `cd backend && ./gradlew :modules:agile-planning:flywayMigrate :modules:agile-planning:generateJooq` + 마이그레이션 테스트. ⚠️ V503 번호 머지 직전 재확인(메모리 migration-vnumber, 현재 최신 V502).

### Task 2. Sprint 도메인 — 엔티티 + 상태전이 규칙(start/complete)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/Sprint.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/SprintStatus.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/SprintTest.kt`]
- depends-on: []

**RED**: `SprintTest`(순수 단위) —
- `start()`: PLANNED→ACTIVE. ACTIVE/COMPLETED에서 start → `InvalidSprintTransitionException`(E1).
- `complete()`: ACTIVE→COMPLETED. PLANNED/COMPLETED에서 complete → `InvalidSprintTransitionException`(E1).
- 기간 검증: startDate > endDate → `require` 실패(E11). 둘 중 하나만/둘 다 null 허용.
- name 공백 → `require` 실패(E10, 도메인 레벨).

**GREEN**: `Sprint` data class(id, projectKey, name, goal?, status, startDate?, endDate?, version) + `start()/complete()`가 새 상태의 Sprint 반환(불변). `SprintStatus` enum(PLANNED/ACTIVE/COMPLETED) + 허용 전이 맵. 다중 ACTIVE 허용이라 전이에 외부 스프린트 조회 불필요(순수).

**REFACTOR**: 전이 규칙 상수화, KDoc(중괄호·백틱 금지, 메모리 ktlint-kdoc-brace).

**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests "*SprintTest"`

### Task 3. SprintRepository (jOOQ) — CRUD + 할당/해제 + no-bump

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/SprintRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/SprintRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: `SprintRepositoryTest`(Testcontainers) —
- `insert/findById(소프트삭제 제외)/findByProject(status별, deleted_at 제외)/updateMeta(version bump)/updateStatus(version bump)/softDelete`.
- `assignIssue(sprintId, issueKey)`: 원자적 이동 — 같은 issue_key가 다른 sprint에 있으면 기존 삭제 후 새 sprint에 INSERT(UNIQUE(issue_key) 충족). sprints row 불변(version·updated_at no-bump, FR10).
- `unassignIssue(sprintId, issueKey)`: 연관 DELETE. 없으면 0행(E9 service에서 404 판정).
- `findIssueKeys(sprintId)`: 할당된 issue_key 목록(단건 조회용).
- `softDelete`가 sprint_issues 연관도 제거(FR11, CASCADE 또는 명시 DELETE).
- 멱등 재할당(같은 sprint+issue_key)은 no-op(FR7).

**GREEN**: jOOQ 구현(V503 codegen 의존). 이동은 단일 트랜잭션 delete-then-insert 또는 `INSERT ... ON CONFLICT(issue_key) DO UPDATE SET sprint_id=`. no-bump = sprints UPDATE 미발생.

**REFACTOR**: SQL 상수, KDoc(no-bump 사유 메모리 no-bump-sidecar-version, 이동 원자성).

**검증**: `cd backend && ./gradlew :modules:agile-planning:compileKotlin :modules:agile-planning:integrationTest --tests "*SprintRepositoryTest"` (+ Task1 마이그레이션 테스트 이 시점 정식 실행).

### Task 4. SprintApplicationService — CRUD + 상태전이 + 할당/해제 + 권한

**메타**.
- agent: `backend-engineer` (권한 부분 security-engineer 검토)
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintExceptions.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/SprintApplicationServiceTest.kt`]
- depends-on: [3]

**RED**: `SprintApplicationServiceTest`(mockk repo/port + 분기 단위) —
- `create/update/softDelete/list/get`: 권한 `IssuePermission.CREATE`(쓰기)·`BROWSE`(조회) on `IssueScope.Project`(메모리 crossbc-permission-resolver-not-role-lookup). 권한 없음 → 403(E6). 대상 미존재/소프트삭제 → `SprintNotFoundException`(E2, 404).
- `start/complete`: 도메인 `Sprint.start()/complete()` 위임 → 잘못된 전이 `InvalidSprintTransitionException`(E1). 다중 ACTIVE 허용(기존 ACTIVE 조회·차단 없음).
- `assignIssue(actor, sprintId, issueKey)`: 권한 검증 → 스프린트 조회(404 E2) → **COMPLETED면 거부**(E5, 409) → `BoardIssueLookupPort.listVisibleIssuesByProject(projectKey, actor)` 결과에 issueKey 포함 확인(타프로젝트/미가시 → E3 400 / E4 404) → `repo.assignIssue`(이동 E8/멱등 E7). 인증 추출을 리소스 조회보다 먼저(메모리 auth-extraction-before-resource-lookup).
- `unassignIssue`: 권한 → COMPLETED 거부(E5) → repo.unassign(없으면 404 E9).
- 권한 resolver는 non-null fail-closed 주입(메모리 crossbc-resolver-nullable-fail-open).

**GREEN**: `SprintApplicationService`(@Service @Transactional, ArchUnit 통과). `SprintExceptions.kt`에 `InvalidSprintTransitionException`·`SprintNotFoundException`(도메인 예외). 가시성 검증은 listVisibleIssuesByProject 결과 set 멤버십(truncated 한계 주석 — board 동일).

**REFACTOR**: 권한 헬퍼 추출, KDoc.

**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests "*SprintApplicationServiceTest"`

### Task 5. SprintController + DTO + ExceptionHandler — 9 엔드포인트

**메타**.
- agent: `backend-engineer` (권한 매핑 security-engineer 검토)
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/SprintController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/SprintRequests.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/SprintResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/SprintExceptionHandler.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/SprintControllerTest.kt`]
- depends-on: [4]

**RED**: `SprintControllerTest`(MockMvc, service mock) — 9 엔드포인트(`@RequestMapping("/api/v1/sprints")`): POST 생성 201, GET 단건/목록 200, PATCH 수정 200, DELETE 204, POST start/complete 200, POST issues 할당 200/201, DELETE issues 해제 204. 도메인예외 HTTP 매핑 단언(MockMvc): `InvalidSprintTransitionException`→**409**, `SprintNotFoundException`→**404**, 권한→403, @Valid(name 공백/기간역전)→400. catch-all `Exception`→500이 409/404를 삼키지 않는지 명시 검증(메모리 catch-all-exceptionhandler-swallows / domain-exception-http-handler-basepackage-scope).

**GREEN**: `SprintController`(actor 추출 먼저) + `RerankIssueRequest`류 DTO(@Valid) + `DataResponse`. `SprintExceptionHandler` — **`@RestControllerAdvice(assignableTypes=[SprintController::class])`로 한정**(기존 `BoardExceptionHandler`가 같은 `basePackages=com.bts.agileplanning.web` 커버 → 입력 예외 400은 Board가 이미 처리, Sprint 도메인 예외만 신규 advice. 메모리 fr-db-01 형제 advice 미적용 주의). `InvalidSprintTransitionException`→409, `SprintNotFoundException`→404.

**REFACTOR**: DTO KDoc, 권한 주석.

**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests "*SprintControllerTest"`

### Task 6. HTTP 통합테스트 — S1~S8 / E1~E12

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/integration/SprintIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**: 실 repo + 시드 end-to-end(board 통합테스트 시드 패턴 참고) — S1 생성 / S2 할당 / S3 해제 / S4 start / S5 complete / S6 단건(할당목록) / S7 목록 / S8 수정·삭제 + E1(전이 409) E2(404) E3(타프로젝트 400) E4(미가시 404) E5(COMPLETED 할당 409) E6(권한 403) E7(멱등 200) E8(이동 원자성) E9(해제 없음 404) E11(기간역전 400) E12(UNIQUE 동시 409). 1개 이상 일부러 위반 넣어 vacuous 아님 확인(메모리 archunit-vacuous / 통합테스트 실 repo 메모리 issue-tracking-transition-test-mocks).

**검증**: `cd backend && ./gradlew :modules:agile-planning:integrationTest --tests "*SprintIntegrationTest"`

### Task 7. ArchUnit — agile→issue-tracking import 0 + @Transactional @Service

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/architecture/AgilePlanningBcArchTest.kt`]
- depends-on: [4]

**RED→GREEN**: 기존 `AgilePlanningBcArchTest` 확장 — 신규 Sprint 클래스 포함 agile-planning이 `com.bts.issue..`(issue-tracking 내부) 직접 import 0(cross-BC는 shared-kernel 포트만, NFR1). `SprintApplicationService` 등 `@Transactional` 보유 클래스가 `@Service` 부착(메모리 learnings @Service 누락 가드). 룰이 vacuous 아님 확인 — 일부러 위반 클래스 임시 추가해 fail 확인 후 제거(메모리 archunit-vacuous-rule-silent-pass).

**검증**: `cd backend && ./gradlew :modules:agile-planning:test --tests "*AgilePlanningBcArchTest"`

## Plan 메타

- task 수: 7
- 예상 wave: 5 (W1: T1‖T2 / W2: T3 / W3: T4 / W4: T5‖T7 / W5: T6). agile-planning 모듈 컴파일 직렬 요인 존재.
- TDD 강제: yes (RED→GREEN→REFACTOR, test 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: ktlint, detekt(baseline), ArchUnit(@Transactional @Service + BC import), generateJooq
- BC: agile-planning 단독. issue-tracking 무변경(포트 무확장, cross-BC는 BoardIssueLookupPort 재사용만).

## 리뷰 결과

독립 적대적 리뷰 2종 병렬 dispatch (autoplan overkill 회피, 메모리 bts-review-plan-autoplan-overkill).

### plan-eng-review (2026-06-24, 독립 backend-engineer dispatch)

**BLOCKER 3.**
- B1. ExceptionHandler `assignableTypes` vs 기존 `BoardExceptionHandler` `basePackages` 우선순위 미확정 → Sprint 입력예외 400이 500/오분류 위험. → **SprintExceptionHandler에 입력예외(MethodArgumentNotValid/HttpMessageNotReadable/TypeMismatch/ResponseStatus) 핸들러도 포함**(board 패턴 복제). 자동 반영.
- B2. 할당 구현 경로(delete-then-insert vs `ON CONFLICT DO UPDATE`) 미확정 → UPSERT면 E12 동시 409가 vacuous green. → **delete-then-insert 확정**(E12 실검증 유지). 자동 반영.
- B3. COMPLETED 할당 거부(E5) service-check TOCTOU(no-bump라 OCC 없음). → **조건부 DML**(`WHERE (SELECT status FROM sprints WHERE id=?) <> 'COMPLETED'`, affected=0이면 E5/E2 구분) 확정. 자동 반영.

**CONCERN 6.** C1 FR-MV 키변경 orphan spec 명시 / C2 truncated 가시성(→ security-B2와 동일, Maxi) / C3 ArchUnit vacuous 절차 git 잔류 / C4 unknown projectKey 생성 403 테스트 / C5 소프트삭제 CASCADE 불가→명시 DELETE 확정 / C6 no-bump version stale spec 명시. → C1·C3·C4·C5·C6 자동 반영.

**NIT 3.** N1 spec §8 `issue_id`→`issue_key` 오타 / N2 S6 rank 정렬 포트 불가→D6 명시 / N3 통합테스트 vacuous 표현 교체. → 자동 반영.

### plan-security-review (2026-06-24, 독립 security-engineer dispatch)

**BLOCKER 2 (정책 — Maxi 게이트1 결정).**
- B1. **할당/해제 권한 = CREATE는 board 카드이동(BROWSE+TRANSITION) 동형 선례 위반·과대권한**(BoardController.kt:47-48). 옵션 A(BROWSE) / **B(UPDATE, 권장 — 이슈 소속 메타 변경)** / C(CREATE 유지+근거명시). → **Maxi 결정**.
- B2. truncated 상한 가시성 검증 결함 — 대규모 프로젝트 정당 이슈 **오거부**(E4 404) + 미가시 이슈 **probe oracle**(E3 400 vs E4 404). 단건 가시성 포트 추가(=포트 확장=issue-tracking 변경=두 BC) vs fail-closed vs 현 한계 수용. → **Maxi 결정**(BC 경계 영향).

**CONCERN 4.** C1 권한 판정 순서 — **actor 추출→sprint 조회(projectKey 확보,404)→IssueScope.Project 권한판정(403)**으로 정정(plan Task4 "권한→조회" 순서 오류, board loadBoard* 동형). 자동 반영 / C2 예외 message에 issueKey/projectKey/sprintId echo 누출(board 선례 오염, 베끼지 말 것)→detail 일반화·403 인자없는 예외. 자동 반영 / C3 가시성 포트 fail-safe(빈 페이지)가 권한경로서 fail-closed인지 통합테스트(adapter 빈→할당거부). 자동 반영 / C4 resolver 우회(멤버십 role 직접조회) codereview 체크포인트. 자동 반영.

**NIT 3.** N1 전이권한 CREATE 근거 명시(B1과 묶음) / N2 E9 404 vs 멱등204→멱등204 채택(probe 표면 작음) / N3 할당-INSERT 사이 soft-delete TOCTOU 인지주석(무해, 고아연관 가시성필터 배제). → 자동 반영.

### 종합 판정

기술 BLOCKER 3(eng) + CONCERN/NIT 대부분 = **plan/spec 수정으로 자동 해소**. **정책 BLOCKER 2(security-B1 권한, security-B2 가시성) = Maxi 게이트1 결정 필요** → 결정 후 일괄 반영하고 게이트1 최종 승인.
