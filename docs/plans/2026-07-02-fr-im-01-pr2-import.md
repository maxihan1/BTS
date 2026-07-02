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

### Task 3. issue-tracking — 소스 상태 direct-set 애플리케이션 메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueImportStatusApplicationServiceTest.kt`]
- depends-on: []

**RED**. 신규 `IssueImportStatusApplicationServiceTest`(mockk) — `applyImportedStatus(actor, key, statusName, issueTypeKey, expectedVersion)`가 (a) TRANSITION 없음 → `NoPermission`, (b) name 미매칭 → `NoMatch`, (c) 목표==현재 → `NoOp`, (d) 매칭+권한 → `applyTransition(key, matchedStateKey, expectedVersion, resolutionId=null)` 호출 후 `Applied(newVersion)` 반환. 실패(메서드 없음).

**GREEN**. `IssueApplicationService.applyImportedStatus(...)` 추가 — `permissionResolver.hasPermission(TRANSITION, IssueScope.Issue)` **사전 체크**(assertPermission 아님 → 예외 없이 경고화, spec R6/G2), `workflowStateCatalog.listStates(projectKey, issueTypeKey)`로 name 대소문자 무시 매칭, 대상 상태집합 포함 검증, 목표==현재면 no-op, else `repo.applyTransition(key, toStateKey, expectedVersion, resolutionId=null)`(FSM 우회, IssueMoveService 선례; DONE 카테고리도 resolution 미주입=null 허용 — glossary "닫힘+해결결과 없음" 허용, resolution 파싱은 PR2 범위 밖). 결과 sealed `ImportStatusOutcome`(Applied/NoOp/NoMatch/NoPermission)을 `IssueApplicationRequests.kt`에 정의.

**REFACTOR**. KDoc — FSM 우회 근거(마이그레이션 소스 상태는 edge 경로 아님) + transitionIssue와의 차이 명시.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueImportStatusApplicationServiceTest*"`

### Task 4. issue-tracking — IssueImportAdapter 컴포넌트/버전 자동생성 + 링크 + 상태 + dry-run

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [1, 3]

**RED**. `IssueImportAdapterTest`(mockk) 케이스 추가 — ① 컴포넌트 미존재+MANAGE_COMPONENTS 있음 → `componentApplicationService.create` 호출 후 링크; ② 컴포넌트 미존재+권한 없음(create가 AccessDenied) → 경고+이슈 생성 지속; ③ fix/affects 버전 find-or-create + `changeAffectsVersions`/`changeFixVersions` 호출; ④ status → `applyImportedStatus` 호출, NoMatch/NoPermission → 경고; ⑤ dry-run이 컴포넌트/버전 create 권한·TRANSITION·버전링크 UPDATE를 미리 확인. 기존 생성자 mockk 세팅에 새 의존 3종 추가(memory plan-files-constructor-injection-existing-tests). 실패.

**GREEN**. 생성자에 `componentApplicationService`, `versionRepository`, `versionApplicationService` 주입(모두 same-BC 빈 — cross-BC 아님, boot 무영향). `resolveComponentIds`를 find-or-create로 변경(find→없으면 `componentApplicationService.create(actor=requester)`, AccessDenied는 catch→경고). 버전 find-or-create + 링크 헬퍼 추가(fix/affects replace-all 목록 전달). `executeImport` 순서 ①~⑥(spec 실행순서)로 확장하고 OCC version 스레딩 ⑤⑥까지 연장. status는 `applyImportedStatus` 결과가 NoMatch/NoPermission이면 warnings 추가. `validateDryRun`의 `rowTriggersUpdate`를 버전링크 포함으로 확장 + MANAGE_COMPONENTS/MANAGE_VERSIONS/TRANSITION 사전 확인(R7). `resolveFields` 반환에 componentIds(생성 포함)·versionIds(fix/affects)·statusName 반영.

**REFACTOR**. KDoc §책임에 자동생성/링크/상태 추가. 권한 3축(생성 admin=경고 / 편집 basic=행실패 / 전이=경고) 주석. `@Suppress` 재점검.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueImportAdapterTest*"`

### Task 5. search — ImportJobProcessor toCommand 매핑 + 경고 로그 노출 (G1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportErrorLogWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`]
- depends-on: [1, 2]

**RED**. `ImportJobProcessorTest` 케이스 추가 — ① `toCommand`가 새 3필드(statusName/fixVersionNames/affectsVersionNames)를 매핑; ② Success에 warnings 있으면 결과 로그에 severity=WARNING 행 기록; ③ 실패행 0 + 경고행 있음 → 로그 업로드됨(errorLogObjectKey non-null); ④ 경고행이 있어도 행은 succeeded 집계. 실패.

**GREEN**. `toCommand`에 3필드 추가. `FailedRowRecord`에 `severity`(FAILURE/WARNING) 추가(또는 `ImportLogRecord`로 일반화). `handleRow`의 Success 분기에서 `result.warnings`를 WARNING 레코드로 수집. `finalizeCompleted` 업로드 조건을 `failedRecords ∪ warningRecords 비어있지 않음`으로 확장. `ImportErrorLogWriter` CSV에 severity 컬럼 추가(정화 경로 유지). **스키마 무변경**(카운트 컬럼 미추가).

**REFACTOR**. `RowProcessingState`에 warningRecords 추가. KDoc §경고 노출 근거(PR1은 warnings 폐기, PR2가 노출).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests "*ImportJobProcessorTest*"`

### Task 6. issue-tracking — 통합 테스트 (Testcontainers 실 DB, cross-BC 진짜 동작)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterIntegrationTest.kt`]
- depends-on: [4, 5]

**RED**. 실 DB 통합 테스트(mockk 아님, PR1 IssueImportAdapterTest 통합 선례/시드 재사용) — ① 새 컴포넌트/버전 자동생성 후 `components`/`versions` 행 확인; ② `issue_affects_versions`/`issue_fix_versions` 링크 행 확인; ③ status direct-set 후 `current_state_key` 확인; ④ MANAGE_COMPONENTS 없는 actor → 경고+이슈 생성(컴포넌트 0); ⑤ **행 실패 시 자동생성 컴포넌트/버전 롤백**(tx 원자성 — mockk로 못 잡는 tx poison 실검증, memory tx-aware-dslcontext-rollback-test-gap); ⑥ dry-run 무생성(이슈/컴포넌트/버전 0). 실패.

**GREEN**. 어댑터 실 wiring이 통과하도록(필요 시 T4 미세 조정). 시드=프로젝트+워크플로우 스킴+권한 매트릭스.

**REFACTOR**. 시드 헬퍼 추출, 중복 제거.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueImportAdapterIntegrationTest*"` + `./gradlew :backend:modules:issue-tracking:test :backend:modules:search-export-import:test`(무회귀)

## Plan 메타

- task 수: 6
- 예상 wave: 3 (Wave1 T1·T2·T3 병렬[3모듈 분리] → Wave2 T4·T5[issue/search 분리] → Wave3 T6). 파일 겹침 0, depends-on만으로 직렬.
- 예상 시간: 직렬 약 24분 / wave 병렬 약 12분
- TDD 강제: yes (test 커밋이 feat 커밋보다 선행)
- 추가 검증: ktlintCheck, detekt, verify-master-plan(FR 카운트 123 불변), BC 격리 ArchUnit 무회귀

## 리뷰 결과 (← /bts-review-plan 채움)
