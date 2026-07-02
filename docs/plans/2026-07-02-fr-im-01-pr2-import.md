# FR-IM-01 PR2 — Import 컴포넌트/버전 자동생성 + 소스 상태 전이

> slug: fr-im-01-pr2-import
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (issue-tracking로 cross-BC 쓰기 확장)
> 생성: 2026-07-02

## Brief

FR-IM-01 (CSV/JSON Import, Jira 마이그레이션) 에픽의 **PR2**.
에픽 구조 (Maxi 결정, SDD 10.6.3 풀 마이그레이션):
PR1 코어(완료, #218) → **PR2 컴포넌트/버전 자동생성 + 소스 상태 전이** → PR3 댓글/Worklog → PR4 첨부(zip)/이력.

PR2 범위 (초안, spec/domain에서 확정):
- Jira 데이터의 **컴포넌트** → 대상 프로젝트에 자동 생성 (없으면 생성, 있으면 재사용) + 이슈 연결
- Jira 데이터의 **버전** (affects/fix version) → 자동 생성 + 이슈 affects/fix 연결
- Jira **소스 이슈 상태** → BTS 워크플로우 상태로 전이 (생성 후 목표 상태로 이동)

PR1 유산:
- `com.bts.search.imports` 모듈, `ImportJobWorker`, CSV/JSON 스트리밍 파서
- `IssueImportPort` (shared-kernel, issue-tracking `IssueImportAdapter` 구현) — BTS 최초 cross-BC 쓰기 포트, default fail-closed
- 행별 best-effort = create+update 1 @Transactional 원자성

## 도메인 정리

- **BC**: search-export-import (import 오케스트레이션) + issue-tracking (cross-BC 쓰기 어댑터 확장). 신규 BC 없음.
- **확장 지점 3곳** (모두 기존 파일 확장, 신규 서비스 최소):
  1. `ParsedImportRow` (search) + `ImportRowParser` — 버전(affects/fix)·소스 상태 파싱 추가
  2. `IssueImportCommand` (shared-kernel) — 필드 추가 (주석이 "포트 시그니처 불변, 필드 추가" 명시 허용)
  3. `IssueImportAdapter` (issue-tracking) — 컴포넌트 auto-create, 버전 auto-create+링크, 상태 전이
- **영향 엔티티**: Component, Version, `issue_affects_versions`/`issue_fix_versions`(V017 기존), WorkflowState. **신규 마이그레이션 불필요**(모든 테이블 기존).
- **새 용어**: 없음 (Component/Version/전이 모두 glossary 기존 용어).

### 핵심 메커니즘 (Explore 조사, 파일:라인)
- 컴포넌트 생성: `ComponentApplicationService.create(actorId, projectIdOrKey, name, desc?, leadUserId?)` — `ComponentPermission.CREATE`→`MANAGE_COMPONENTS`(PROJECT_ADMIN). name 프로젝트 내 UNIQUE(부분, 활성). 중복→`DuplicateComponentNameException`(23505).
- 버전 생성: `VersionApplicationService.create(...)` — `VersionPermission.CREATE`→`MANAGE_VERSIONS`(PROJECT_ADMIN). 기본 status=UNRELEASED. name UNIQUE.
- 이슈↔버전: `IssueApplicationService.changeAffectsVersions`/`changeFixVersions` — `EDIT_ISSUE`(UPDATE)·IssueScope.Issue·replace-all. 분리 테이블 2개.
- 상태 전이 직접 set 선례: `IssueMoveService`(FR-MV-01)가 `workflowPort.plan()` 우회, `IssueRepository.moveIssue(... targetStateKey)`로 `current_state_key` 직접 set. 유효성=대상 워크플로우 상태집합 포함 여부(FSM edge 아님).
- 상태 name→key: `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `WorkflowStateView(key, name, isDone, category, displayOrder)`.
- 초기 상태: `createIssue`가 항상 워크플로우 시작 상태로 강제(status 파라미터 없음). 소스 상태 반영은 생성 후 별도 전이 필요.

### 기존 결정과의 관계 / 결정 갈림길 (→ 스펙에서 확정, 게이트1 제시)
- **D-A. 컴포넌트/버전 auto-create 권한 모델**. importer는 CREATE_ISSUE 보유. 자동생성은 MANAGE_COMPONENTS/MANAGE_VERSIONS(PROJECT_ADMIN) 필요. PR1 불변("search BC는 권한 우회 불가")을 지키려면 `ComponentApplicationService.create(actor=requester)` 위임 → 권한 없으면 예외. 폴백: 권한 없거나 생성 실패 시 **경고+이슈는 생성**(best-effort, PR1 컴포넌트 스킵 동형) vs **행 실패(FORBIDDEN)**. (추천: best-effort 경고)
- **D-B. 상태 전이 메커니즘**. FSM `transitionIssue`(유효 edge만, Jira 상태 대부분 도달불가) vs **직접 set(IssueMoveService 선례)**. (추천: 직접 set + 대상 상태집합 포함 검증 + IssuePermission.TRANSITION 게이트. 미매칭/미도달 상태 name은 경고+시작 상태 유지)
- **D-C. 트랜잭션 원자성**. 행 1건 = create+컴포넌트/버전 auto-create+링크+전이 한 tx. auto-create 실패의 best-effort 경고화는 tx poison(23505) 회피 위해 사전 find(findByProject+name) 후 없을 때만 insert 필요. 동시 행 race는 실 UNIQUE가 최종 방어.
- **관련 ADR**: PR1 `2026-07-02-fr-im-01-csv-json-import.md`(D8 에픽 분할). PR2 결정은 신규 ADR 또는 PR1 ADR 확장(스펙에서 결정).

## 스펙

전체 스펙. [docs/specs/2026-07-02-fr-im-01-pr2-import.md](../specs/2026-07-02-fr-im-01-pr2-import.md)

핵심 요약 (R1~R9).
- 파서/커맨드 확장(status·fix·affects) → 어댑터가 컴포넌트/버전 **find-or-create**(actor=requester 위임, admin 권한 부재=경고) + affects/fix 링크 + 소스 상태 **직접 set**(FSM 우회, name 매칭, TRANSITION).
- best-effort 경고를 **결과 로그 CSV에 severity로 노출**(G1, 스키마 무변경) — PR1이 경고를 폐기하던 구조 보정.
- 권한 3축(생성=admin 경고 / 편집=basic 행실패 / 전이=경고), OCC 버전 6단계 스레딩, dry-run 실경로 미러 확장.
- API/데이터 모델/마이그레이션 변경 0.

## Brainstorming Check

✅ 통과 (1회 iteration). G1(경고 폐기)·G2(권한 3축)·G3/G5(순서·OCC)·G4(dry-run 확장) 발견 후 스펙 보강. office-hours 스킵(에픽 연속).

## Plan

> 6 task / 예상 3 wave. 모듈 순서: shared-kernel(T1) → issue-tracking(T3,T4)·search(T2,T5) → 통합(T6).
> 전 task `backend-engineer`. 신규 마이그레이션 0. TDD red→green→refactor 강제.

### Task 1. shared-kernel — IssueImportCommand 필드 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueImportCommand.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueImportPortTest.kt`]
- depends-on: []

**RED**. `IssueImportPortTest`에 새 필드 기본값 케이스 추가 — `IssueImportCommand(projectKey, requesterUserId, summary)`가 `statusName=null`, `fixVersionNames=emptyList()`, `affectsVersionNames=emptyList()` 기본값을 가짐 assert. 실패(필드 없음).

**GREEN**. `IssueImportCommand`에 `statusName: String? = null`, `fixVersionNames: List<String> = emptyList()`, `affectsVersionNames: List<String> = emptyList()` 추가. 기존 필드/순서 불변, 기본값으로 하위호환.

**REFACTOR**. KDoc `@property` 3개 추가(Jira `status.name`/`fixVersions`/`versions` 출처 명시).

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests "*IssueImportPortTest*"`

### Task 2. search — ParsedImportRow + ImportRowParser status/버전 파싱

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ParsedImportRow.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/parse/ImportRowParserTest.kt`]
- depends-on: []

**RED**. `ImportRowParserTest`에 케이스 추가 — CSV 헤더 `status`,`fix version`,`affects version`(대소문자 무시) 추출 + 콤마/세미콜론 다중값; JSON `fields.status.name`(단일)·`fields.fixVersions[].name`·`fields.versions[].name`(배열) 추출. status 미존재 시 null, 버전 미존재 시 emptyList. 실패(필드 없음).

**GREEN**. `ParsedImportRow`에 `statusName: String?`, `fixVersionNames: List<String>`, `affectsVersionNames: List<String>` 추가. 파서 CSV `buildCsvRow`/JSON `buildJsonRow`에 추출 로직 추가(기존 `splitMultiValue`/`textArrayOf`/`textOf` 재사용). 새 헤더/필드 상수 companion 추가(`HEADER_STATUS="status"`, `HEADER_FIX_VERSION="fix version"`, `HEADER_AFFECTS_VERSION="affects version"`; `FIELD_STATUS="status"`, `FIELD_FIX_VERSIONS="fixVersions"`, `FIELD_VERSIONS="versions"`).

**REFACTOR**. KDoc §값 정규화에 status/버전 추가. 임의 Jira 헤더(`/s`)·자유 매핑은 FR-IM-02 몫 주석 1줄.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests "*ImportRowParserTest*"`

### Task 3. issue-tracking — 소스 상태 direct-set 전용 서비스 (별도 소형 서비스, CONCERN-2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueImportStatusService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueImportStatusServiceTest.kt`]
- depends-on: []

**설계**. `IssueApplicationService`(20-param 거대 클래스)에 넣지 않고 **IssueMoveService 선례**대로 별도 `@Service IssueImportStatusService`(주입: `IssueRepository`, `WorkflowStateCatalog`, `IssuePermissionResolver`, `IssueTypeRepository`)로 분리(거대 클래스/테스트 슈트 오염 회피).

**RED**. 신규 `IssueImportStatusServiceTest`(mockk) — `applyImportedStatus(actor, key, statusName, expectedVersion): ImportStatusOutcome`가 (a) TRANSITION 없음 → `NoPermission`, (b) name 미매칭 → `NoMatch`, (c) 목표==현재 상태 → `NoOp`, (d) 매칭+권한 → `applyTransition(key, matchedStateKey, expectedVersion, resolutionId=null)` 호출 후 `Applied(newVersion)`. 실패(클래스 없음).

**GREEN**. `IssueImportStatusService.applyImportedStatus(...)` — ① 이슈 조회로 현재 상태·`typeId` 확보 → `issueTypeRepository.findById(typeId)?.key`로 **issueTypeKey 재조회**(per-type 워크플로우 대비, null default-mapping 금지). ② `permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue)` **사전 체크**(assertPermission 아님 → 예외 없이 경고화). ③ `workflowStateCatalog.listStates(projectKey, issueTypeKey)`로 name 대소문자 무시 매칭 + 상태집합 포함 검증. ④ 목표==현재면 no-op. ⑤ else `repo.applyTransition(key, toStateKey, expectedVersion, resolutionId=null)`(FSM 우회 raw setter). `ImportStatusOutcome` sealed(Applied(version)/NoOp/NoMatch/NoPermission) 동일 파일 정의.

> **CONCERN-1 (DONE resolution)**. `applyTransition(resolutionId=null)`은 DONE 카테고리 상태에도 resolution 없이 진입(FSM validator B7 우회). 게이트1 Maxi 확정에 따라 처리(기본안: null 허용 — 신규 import는 resolution 원천 미파싱, moveIssue도 신규엔 null. 대안: DONE 진입 시 프로젝트 기본 resolution 주입). **impl 전 게이트1 답 확인**.

**REFACTOR**. KDoc — FSM 우회 근거(마이그레이션 소스 상태는 edge 경로 아님) + transitionIssue/IssueMoveService와의 차이.

**검증**. `./gradlew :modules:issue-tracking:test --tests "*IssueImportStatusServiceTest*"` (backend 루트)

### Task 4. issue-tracking — IssueImportAdapter 컴포넌트/버전 자동생성 + 링크 + 상태 + dry-run

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [1, 3]

**주입 확장(6종)**. `componentApplicationService`, `versionApplicationService`, `versionRepository`, `componentPermissionResolver`, `versionPermissionResolver`, `IssueImportStatusService`(T3). 기존 `issueApplicationService`(버전 링크용 changeAffectsVersions/changeFixVersions), `componentRepository`(find), `issueTypeRepository` 유지. **모두 same-BC 빈**(cross-BC 아님 → full-boot NoSuchBean 무영향). 기존 mockk 테스트 생성자 세팅 동반 갱신(memory plan-files-constructor-injection-existing-tests).

**★BLOCKER 해소 — 사전 체크 설계**. 컴포넌트/버전 자동생성은 **catch-throw 강등 금지**(참여 @Transactional throw=shared tx 오염). 대신 `resolveComponentIds`/버전 헬퍼가 find→미존재 시 `xxxPermissionResolver.hasPermission(actor, Xxx Permission.CREATE, projectId)` **사전 체크** → false면 create 미호출+경고(throw 0, 오염 0), true면 `create(actor=requester)` 호출. `create`의 기타 예외(부적합 name 등 `IllegalArgumentException`)·23505 race는 tx 오염 → **행 실패**(경고 아님, spec R8 명시).

**RED**. `IssueImportAdapterTest`(mockk) 케이스 — ① 컴포넌트 미존재+CREATE 권한 있음 → `componentApplicationService.create` 후 링크; ② 컴포넌트 미존재+`hasPermission=false` → create **미호출**+경고+이슈 생성 지속(create mock이 호출되지 않음을 verify); ③ fix/affects 버전 find-or-create + `changeAffectsVersions`/`changeFixVersions`; ④ status → `IssueImportStatusService.applyImportedStatus`, NoMatch/NoPermission → 경고; ⑤ dry-run이 `ComponentPermission.CREATE`/`VersionPermission.CREATE`/TRANSITION/버전링크 UPDATE를 사전 확인. 실패.

**GREEN**. `resolveComponentIds`/버전 헬퍼를 위 사전 체크 find-or-create로 구현. `executeImport` 순서 ①컴포넌트→②createIssue→③update(priority/labels)→④changeAssignee(**반환 version 캡처**)→⑤버전 create+링크→⑥status 로 확장, OCC version을 ④⑤⑥까지 스레딩. status 결과 NoMatch/NoPermission → warnings 추가(Applied면 version 갱신). `validateDryRun`의 `rowTriggersUpdate`를 버전링크(UPDATE) 포함으로 확장 + `hasPermission(ComponentPermission.CREATE)`/`hasPermission(VersionPermission.CREATE)`/`hasPermission(TRANSITION)` 사전 확인(실행 경로와 동일 호출 → 미러 자명, R7).

**REFACTOR**. KDoc §책임에 자동생성/링크/상태 추가. 권한 3축(생성 admin=사전체크 경고 / 편집 basic=행실패 / 전이=경고) 주석. `@Suppress` 재점검.

**검증**. `./gradlew :modules:issue-tracking:test --tests "*IssueImportAdapterTest*"` (backend 루트)

### Task 5. search — ImportJobProcessor toCommand 매핑 + 경고 로그 노출 (G1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportErrorLogWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportErrorLogWriterTest.kt`]
- depends-on: [1, 2]

> **NIT 반영**. severity 컬럼 추가로 `ImportErrorLogWriter` CSV 헤더(`Row,Field,Reason,Message`→severity 포함)가 바뀌므로 기존 `ImportErrorLogWriterTest`의 헤더/컬럼 assert 동반 갱신(정화 sanitize→escape 순서는 재사용).

**RED**. `ImportJobProcessorTest` 케이스 추가 — ① `toCommand`가 새 3필드(statusName/fixVersionNames/affectsVersionNames)를 매핑; ② Success에 warnings 있으면 결과 로그에 severity=WARNING 행 기록; ③ 실패행 0 + 경고행 있음 → 로그 업로드됨(errorLogObjectKey non-null); ④ 경고행이 있어도 행은 succeeded 집계. 실패.

**GREEN**. `toCommand`에 3필드 추가. `FailedRowRecord`에 `severity`(FAILURE/WARNING) 추가(또는 `ImportLogRecord`로 일반화). `handleRow`의 Success 분기에서 `result.warnings`를 WARNING 레코드로 수집. `finalizeCompleted` 업로드 조건을 `failedRecords ∪ warningRecords 비어있지 않음`으로 확장. `ImportErrorLogWriter` CSV에 severity 컬럼 추가(정화 경로 유지). **스키마 무변경**(카운트 컬럼 미추가).

**REFACTOR**. `RowProcessingState`에 warningRecords 추가. KDoc §경고 노출 근거(PR1은 warnings 폐기, PR2가 노출).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests "*ImportJobProcessorTest*"`

### Task 6. issue-tracking — 통합 테스트 (Testcontainers 실 DB, cross-BC 진짜 동작)

> **impl 조정**. 기존 `IssueImportAdapterTest`가 이미 Testcontainers **통합 테스트**(실 DB+실 tx, PR1 S1~S9 + tx-aware DataSourceProxy 인프라)였다. T4가 여기에 신규 시나리오 S10~S20(컴포넌트/버전 자동생성·링크·상태 direct-set·권한거부 경고[SelectiveAllow deny resolver=non-vacuous]·dry-run)을 추가해 T6 시나리오 ①②③④⑥을 이미 포괄. **별도 파일 신설은 중복**이라 폐기하고, 유일 갭인 ⑤(자동생성 컴포넌트가 행 실패 시 롤백=고아0)만 **S21**로 기존 파일에 추가(controller). 최종 27 tests green. 상태 매칭은 BC 격리로 FixedStatesWorkflowStateCatalog fake(name→key 로직은 T3 IssueImportStatusServiceTest가 mockk 검증).

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterIntegrationTest.kt`]
- depends-on: [4, 5]

**RED**. 실 DB 통합 테스트(mockk 아님, PR1 IssueImportAdapterTest 통합 선례/시드 재사용) — ① 새 컴포넌트/버전 자동생성 후 `components`/`versions` 행 확인; ② `issue_affects_versions`/`issue_fix_versions` 링크 행 확인; ③ status direct-set 후 `current_state_key` 확인; ④ **권한 거부 actor → 경고+이슈 생성(컴포넌트/버전 0)** — ★CONCERN-3: `AlwaysAllow*PermissionResolver`(@Profile("!prod"))가 vacuous(항상 true)라 거부 시나리오 미재현 → **denying resolver를 명시 주입/`@MockkBean`으로 CREATE=false 강제** 후 검증(vacuous 금지, memory best-effort-loop-permission-exception-nonprod-mask); ⑤ **행 실패 시 자동생성 컴포넌트/버전 롤백**(tx 원자성 — mockk로 못 잡는 tx poison 실검증, memory tx-aware-dslcontext-rollback-test-gap; 사전 체크 설계가 실제로 오염 없는지 실 DB로 확증); ⑥ dry-run 무생성(이슈/컴포넌트/버전 0). 실패.

**GREEN**. 어댑터 실 wiring이 통과하도록(필요 시 T4 미세 조정). 시드=프로젝트+워크플로우 스킴+권한 매트릭스. 거부 시나리오는 권한 resolver를 실제로 false 반환시켜 구성(profile/mock).

**REFACTOR**. 시드 헬퍼 추출, 중복 제거.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueImportAdapterIntegrationTest*"` + `./gradlew :backend:modules:issue-tracking:test :backend:modules:search-export-import:test`(무회귀)

## Plan 메타

- task 수: 6
- 예상 wave: 3 (Wave1 T1·T2·T3 병렬[3모듈 분리] → Wave2 T4·T5[issue/search 분리] → Wave3 T6). 파일 겹침 0, depends-on만으로 직렬.
- 예상 시간: 직렬 약 24분 / wave 병렬 약 12분
- TDD 강제: yes (test 커밋이 feat 커밋보다 선행)
- 추가 검증: ktlintCheck, detekt, verify-master-plan(FR 카운트 123 불변), BC 격리 ArchUnit 무회귀

## 리뷰 결과

### plan-eng-review (2026-07-02, 독립 code-reviewer 서브에이전트 · 코드 대조)

초안 판정 **BLOCKER 1 + CONCERN 3 + NIT 5**. 아래 반영 후 재검토 통과 기준 충족.

- **🛑 BLOCKER (해소)**. 컴포넌트/버전 auto-create를 "AccessDenied catch→경고 강등"으로 설계했으나, `ComponentApplicationService.create`가 어댑터 `@Transactional`에 REQUIRED 참여 → throw 시 `globalRollbackOnParticipationFailure`(기본 true)로 shared tx가 rollback-only 오염 → catch 후 continue해도 커밋 시 `UnexpectedRollbackException`으로 행 전체 롤백. R8/D-C의 "DB 쓰기 전이라 무오염" 논거는 틀림(판단기준=참여 @Transactional throw 여부). memory `transaction-self-invocation-requires-new`(FR-IS-05 동일 함정). **해소**: 실행 경로도 `hasPermission` **사전 체크**로 전환 — 권한 없으면 create 미호출(throw 0, 오염 0)+경고. `ComponentPermissionResolver`/`VersionPermissionResolver`를 어댑터에 주입(실행+dry-run 공용). → Task 4 GREEN 정정, spec R6/R8 정정.
- **⚠️ CONCERN-1 (Maxi 결정 → 게이트1)**. status direct-set이 `applyTransition(resolutionId=null)`로 DONE 카테고리 상태에도 resolution 없이 진입 → "DONE 진입 시 resolution 필수" 불변식(FSM validator B7) 우회. Jira Done/Closed는 다수라 산출물 상당수가 "DONE인데 resolution NULL". IssueMoveService 선례는 `moveIssue`(별개 setter)+DONE시 기존 resolution 보존이라 정확히 동일하진 않음(신규 import는 어차피 resolution 없음). 하위 소비자(FR-RP-01 번다운 등 "done=resolution 존재" 가정) 영향 점검 필요. → 게이트1에서 Maxi 확정.
- **⚠️ CONCERN-2 (해소)**. Task 3이 `IssueApplicationService`에 `WorkflowStateCatalog` 주입 가정했으나 미주입(20-param 거대 클래스). **해소**: IssueMoveService 선례처럼 **별도 소형 서비스 `IssueImportStatusService`**(IssueRepository+WorkflowStateCatalog+IssuePermissionResolver 주입)로 분리 → 거대 클래스/테스트 슈트 오염 회피. → Task 3 정정.
- **⚠️ CONCERN-3 (해소)**. Task 6 권한거부 통합테스트가 `AlwaysAllow*PermissionResolver`(@Profile("!prod"))로 vacuous(항상 true)→ BLOCKER·경고경로 둘 다 가림. **해소**: deny 경로를 denying resolver 명시 주입/prod 프로파일로 실제 거부시켜 검증. memory `best-effort-loop-permission-exception-nonprod-mask`. → Task 6 정정.
- **NIT (반영)**. (a) `MANAGE_COMPONENTS/MANAGE_VERSIONS`는 enum 아님 → API 게이트는 `ComponentPermission.CREATE`/`VersionPermission.CREATE`(resolver가 매트릭스 권한코드로 매핑). (b) Gradle 태스크 경로 `:modules:issue-tracking:test`(backend 루트 기준). (c) `issueTypeKey`는 `issueTypeRepository.findById(created.typeId)?.key`로 재조회(per-type 워크플로우 대비 null default-mapping 금지). (d) assignee 단계 반환 version 캡처 필수(⑤⑥ 스레딩). (e) `ImportErrorLogWriter` 헤더/테스트 severity 컬럼 동반 수정 → Task 5 files에 writer 테스트 추가.
- **PASS**. 시그니처 정합(applyTransition/changeAffectsVersions/create/listStates 실재), G1 경고구조 일치, wave/의존 그래프 겹침0·순환0, BC 격리 무위반.

### 정정 반영 완료 (Task 3/4/5/6 + spec R6/R8/E9 갱신).

### 🛑 게이트1 승인 (2026-07-02, Maxi). **CONCERN-1 확정 = resolution NULL 허용**(옵션 A) — DONE 상태 direct-set 시 resolution 미주입(null). Task 3 `applyTransition(resolutionId=null)` 그대로. impl 시 하위 소비자(번다운=isDone 기준 확인됨, 무영향) 재확인만.
