# FR-IM-01 PR2 — Import 컴포넌트/버전 자동생성 + 소스 상태 전이 — 스펙

> FR: FR-IM-01 (에픽 PR2 / 4). FR 신규 없음 → FR 카운트(123) 불변.
> BC: search-export-import(파서/커맨드) + issue-tracking(어댑터/서비스 확장).
> 선행: PR1(#218) 코어 완료. 도메인 정리: `docs/plans/2026-07-02-fr-im-01-pr2-import.md §도메인 정리`.
> 결정 확정(Maxi): D-A best-effort 경고 · D-B 직접 set + name 매칭.

## 배경 — PR1 대비 추가 범위

PR1은 코어 이슈 필드(summary/description/type/priority/reporter·assignee/labels)를 생성하고,
컴포넌트는 **기존 것만 매칭(없으면 스킵+경고)**, 버전·상태는 **미처리**였다.
PR2는 다음 3가지를 추가한다.

1. **컴포넌트 자동생성** — 이름이 대상 프로젝트에 없으면 생성 후 연결(PR1의 "스킵" → "find-or-create").
2. **버전 자동생성 + 연결** — fix/affects 버전 이름을 파싱, 없으면 생성(UNRELEASED) 후 이슈에 affects/fix 링크.
3. **소스 상태 전이** — Jira 이슈 상태를 대상 워크플로우 상태로 직접 set(FSM 우회, IssueMoveService 선례).

API/데이터 모델은 **변경 없음**(신규 마이그레이션 0). 입력 파일에 새 컬럼/필드가 있으면 처리, 없으면 무시(PR1 하위호환).

## 사용자 시나리오 (Given-When-Then)

- **S1 (기존 컴포넌트 연결, PR1 보존)**. Given 프로젝트에 "Core" 컴포넌트 존재, When 행의 component="Core", Then 기존 컴포넌트에 연결(대소문자 무시), 자동생성 안 함.
- **S2 (컴포넌트 자동생성)**. Given 실행자가 MANAGE_COMPONENTS 보유 + "Backend" 컴포넌트 없음, When 행의 component="Backend", Then "Backend" 컴포넌트 생성 후 연결.
- **S3 (컴포넌트 권한 없음 → best-effort)**. Given 실행자가 MANAGE_COMPONENTS 없음 + "Backend" 없음, When 행의 component="Backend", Then 컴포넌트 스킵 + 경고, **이슈는 생성**.
- **S4 (버전 자동생성 + 링크)**. Given 실행자 MANAGE_VERSIONS 보유 + "1.0" 버전 없음, When 행의 fix version="1.0", Then "1.0" 버전 생성(UNRELEASED) 후 이슈 fix 버전으로 링크. affects version도 동형.
- **S5 (버전 권한 없음 → best-effort)**. Given MANAGE_VERSIONS 없음 + 버전 없음, Then 버전 스킵 + 경고, 이슈는 생성.
- **S6 (소스 상태 전이)**. Given 실행자 TRANSITION 보유 + 대상 워크플로우에 "In Progress" 상태 존재, When 행의 status="In Progress", Then 이슈를 "In Progress"로 직접 이동(생성 직후 시작 상태 → 목표 상태).
- **S7 (상태 미매칭/권한없음 → best-effort)**. Given status="Frozen"이 대상 워크플로우에 없음(또는 TRANSITION 없음), Then 경고 + 이슈는 **시작 상태 유지**.
- **S8 (dry-run 미러)**. When dryRun=true, Then 실제 생성/전이 없이 컴포넌트/버전 생성 권한·TRANSITION 권한·상태 name 매칭 여부를 미리 검증하고 동일 경고를 리포트.

## 요구사항 (PR2, R번호 — FR ID 신규 없음)

- **R1 파싱 확장**. `ImportRowParser`가 CSV/JSON에서 status·fix version·affects version을 추출한다.
  - CSV 헤더(대소문자 무시, canonical): `status`, `fix version`, `affects version`. 다중값은 콤마/세미콜론 분리(PR1 labels/components 동형).
  - JSON(Jira REST): `fields.status.name`(단일), `fields.fixVersions[].name`(배열), `fields.versions[].name`(affects, 배열).
  - 임의 Jira 헤더(`Component/s` 등)·자유 매핑은 **FR-IM-02(매핑 UI) 몫**(SDD 10.6.2) — 본 PR 범위 밖.
- **R2 커맨드 확장**. `ParsedImportRow`·`IssueImportCommand`에 `statusName: String?`, `fixVersionNames: List<String>`, `affectsVersionNames: List<String>` 추가(기존 필드 불변, 커맨드 주석이 필드 추가 허용). `ImportJobProcessor.toCommand` 매핑 추가.
- **R3 컴포넌트 find-or-create**. 어댑터가 이름을 프로젝트 활성 컴포넌트에서 먼저 find(대소문자 무시), 없으면 `ComponentApplicationService.create(actor=requester, ...)` 위임(MANAGE_COMPONENTS 게이트 자동 적용, 권한 우회 0). 결과 id를 createIssue의 componentIds로 전달.
- **R4 버전 find-or-create + 링크**. 어댑터가 fix/affects 버전 이름을 find, 없으면 `VersionApplicationService.create(actor=requester, ...)` 위임(MANAGE_VERSIONS 게이트, 기본 UNRELEASED). 이슈 생성 후 `IssueApplicationService.changeAffectsVersions`/`changeFixVersions`(EDIT_ISSUE 게이트)로 링크. fix/affects 각각 replace-all이므로 행의 전체 목록을 한 번에 전달.
- **R5 소스 상태 직접 set**. issue-tracking에 import용 상태 반영 경로 추가(신규 `IssueApplicationService` 메서드) — TRANSITION 권한 게이트(IssueScope.Issue) + `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)`로 status name→stateKey 대소문자 무시 매칭 + 대상 상태집합 포함 검증 + `current_state_key` 직접 set(FSM edge 우회, IssueMoveService 선례). 목표 == 현재 시작 상태면 no-op(경고 없음).
- **R6 best-effort 경고**. R3~R5의 실패 중 **권한 거부**(DB 쓰기 전 예외)·**상태 name 미매칭**은 경고로 흡수하고 이슈 생성은 지속. 경고는 `IssueImportResult.Success.warnings`에 누적 → 에러로그 CSV(ExportCellSanitizer 정화)와 성공 카운트에 PR1과 동일 반영.
- **R7 dry-run 실경로 미러**. dryRun에서 PR1의 CREATE/UPDATE 확인에 더해 — 버전 링크 유발 행(fix/affects 존재)은 UPDATE(EDIT_ISSUE)로 `rowTriggersUpdate` 확장, 컴포넌트 생성 필요 행은 MANAGE_COMPONENTS, 버전 생성 필요 행은 MANAGE_VERSIONS, 상태 전이 유발 행은 TRANSITION을 project 스코프로 미리 확인하고 동일 경고를 산출한다(PR1 CONCERN C1 원칙 계승 — dry-run 결과 == 실행 결과). admin 축 권한 부재는 dry-run에서도 경고(행 유효), basic UPDATE 부재는 dry-run에서도 FORBIDDEN(행 실패 예측).
- **R8 행 원자성 + tx 오염 회피 (eng-review BLOCKER 반영)**. 행 1건은 여전히 1 `@Transactional`. `ComponentApplicationService.create`는 이 tx에 REQUIRED **참여**하므로, 그 안에서 예외가 던져지면 `globalRollbackOnParticipationFailure`(기본 true)로 **shared tx가 rollback-only 오염** — catch-후-continue해도 커밋 시 `UnexpectedRollbackException`으로 행 전체 롤백(DB 쓰기 여부 무관, memory `transaction-self-invocation-requires-new`). 따라서 자동생성은 **`hasPermission` 사전 체크**로 처리 — 권한 없으면 `create`를 **아예 호출하지 않고**(throw 0, 오염 0) 경고. 존재 시 find로 재사용(23505 회피). 남는 실패는 (a) `create`의 부적합 name 등 기타 예외, (b) 동시 다른 job의 동명 생성 23505 race — 둘 다 tx 오염 → 해당 행 **실패**(rollback, reasonCode 기록, 재업로드 정정). 즉 **권한 부재=경고(사전 체크), 그 외 create 실패=행 실패**.
- **R9 경고 노출 (Brainstorming G1)**. PR1 `ImportJobProcessor.handleRow`는 `Success.warnings`를 버린다(succeededRows만 증가) → PR2 best-effort 경고가 사용자에게 보이지 않는다. **경고를 결과 로그 CSV에 severity 컬럼(WARNING/FAILURE)으로 기록**한다. 실패행이 없어도 경고행이 있으면 로그를 업로드(`finalizeCompleted`의 업로드 조건을 `failedRecords ∪ warningRecords`로 확장). **스키마 변경 없음**(카운트 컬럼 추가 대신 로그로만 노출 — 신규 마이그레이션 0 유지). 행은 여전히 succeeded로 집계(경고는 성공-with-경고).

### 권한 3축 (Brainstorming G2 + eng-review — 명시화)
- **생성(admin 축)** — 컴포넌트=`ComponentPermission.CREATE`·버전=`VersionPermission.CREATE`(resolver가 매트릭스 권한코드 MANAGE_COMPONENTS/MANAGE_VERSIONS=PROJECT_ADMIN으로 매핑, enum 값 아님). 어댑터가 `xxxPermissionResolver.hasPermission` **사전 체크** → 없으면 create 미호출+**best-effort 경고**(R3~R8, D-A).
- **편집(basic 축)** — 버전 링크=EDIT_ISSUE(UPDATE). PR1이 priority/labels/assignee에서 이미 UPDATE 부재 시 **행 실패(FORBIDDEN)**로 처리하므로 버전 링크도 동일(일관). 즉 admin 권한 부재=경고, basic 편집 권한 부재=행 실패.
- **전이 축** — status=`IssuePermission.TRANSITION`. D-B에 따라 **권한 부재·상태 미매칭 모두 경고+시작 상태 유지**. status-set(별도 `IssueImportStatusService`)이 `hasPermission` **사전 체크**로 판정해 예외 없이 경고화. 마이그레이션 특성상 "시도"(경고)로 처리(D-B 확정).

### 실행 순서 + OCC 버전 스레딩 (Brainstorming G3/G5)
행 처리 순서. ① 컴포넌트 find-or-create → ② `createIssue(componentIds)` → ③ priority/labels면 `updateIssue` → ④ assignee면 `changeAssignee` → ⑤ 버전 find-or-create + `changeAffectsVersions`/`changeFixVersions` → ⑥ status면 direct-set. ②~⑥은 각각 OCC version을 bump하므로 직전 응답의 version을 다음 호출 `expectedVersion`으로 스레딩(PR1 create→update→assignee 스레딩을 ⑤⑥까지 연장).

## NFR

- **스트리밍 유지**. 파서 확장이 10만 행 스트리밍(콜백/부분 트리)을 깨지 않는다. status/version 필드 추가는 행 1건 처리 범위 내.
- **BC 격리(ArchUnit)**. search-export-import는 issue-tracking을 직접 gradle 의존하지 않는다 — `IssueImportCommand` 필드만 확장. issue-tracking 어댑터는 자기 BC 서비스(Component/Version/Issue ApplicationService, WorkflowStateCatalog)만 호출.
- **권한 위임 불변**. 컴포넌트/버전 생성·상태 전이 모두 actor=requester로 기존 게이트 재사용. search BC는 권한을 알지 못하고 우회 불가(PR1 핵심 불변 계승).
- **동시성**. 컴포넌트/버전 name UNIQUE(부분 인덱스)가 최종 방어. find-or-create 재조회로 이미 존재하면 흡수.

## API 인터페이스

변경 없음. `POST /api/v1/imports`(multipart, projectKey/format/dryRun)·`GET /api/v1/imports/{jobId}`·`/errors` 모두 PR1 그대로. 새 컬럼/필드는 파일 내용으로만 전달(하위호환).

## 데이터 모델 변경

**없음.** components·versions·issue_affects_versions·issue_fix_versions(V017)·워크플로우 상태 모두 기존. 신규 Flyway 마이그레이션 0.

## 엣지 케이스

- **E1**. 컴포넌트/버전/상태 이름 대소문자만 다름 → 기존 매칭(ignoreCase), 중복 생성 안 함.
- **E2**. 같은 import 내 여러 행이 같은 새 컴포넌트/버전 참조 → 첫 행이 생성(tx 커밋 후 가시), 이후 행 재사용. 첫 행 이슈 생성 실패로 롤백 시 컴포넌트/버전도 롤백 → 다음 행이 재생성(데이터 유실 0, 고아 0).
- **E3**. fix version과 affects version에 동일 이름 → 같은 version 엔티티 1개를 두 링크(affects+fix)에 연결.
- **E4**. status == 시작 상태 → 전이 no-op, 경고 없음.
- **E5**. 버전이 타 프로젝트/삭제 상태 → auto-create는 항상 대상 프로젝트 스코프라 무관(대상에 새로 생성/매칭).
- **E6**. dry-run 시점엔 컴포넌트/버전 미존재가 정상 → 존재 여부가 아니라 "생성 권한 보유 여부"로 판정(R7).
- **E7**. 빈 status/version 셀 → PR1 빈 셀 처리 동형(무시, 경고 없음).
- **E8**. 경고 메시지의 formula injection → PR1 ExportCellSanitizer 에러로그 정화 경로 그대로.
- **E9 (eng-review CONCERN-1, 게이트1 Maxi 확정)**. 소스 status가 DONE 카테고리 상태로 매칭될 때, direct-set은 FSM validator(B7 "DONE 진입 시 resolution 필수")를 우회하므로 resolution 없이 진입 가능. 기본안=resolution NULL 허용(신규 import는 resolution 원천 미파싱, moveIssue도 신규엔 null 부여). 하위 소비자(예: FR-RP-01 번다운의 "완료" 판정이 state category `isDone` 기준이면 무영향, resolution 기준이면 영향) impl 시 확인. 대안=DONE 매칭 시 프로젝트 기본 resolution 주입. **게이트1 결정 반영**.

## 제약 조건

- 절대 규칙(`DEVELOPMENT.md §1`) 준수. TDD red→green→refactor.
- PR1 자산 최대 재사용, 신규 서비스 최소(어댑터·파서·커맨드 확장 + issue-tracking 상태 set 메서드 1개).
- 새 라이브러리 도입 0.

## 측정 가능한 완료 기준

- 단위 테스트. 파서(status/fix/affects CSV·JSON 추출) · 어댑터(컴포넌트 find-or-create · 버전 생성+affects/fix 링크 · 상태 direct-set · best-effort 경고 3종 · dry-run 미러).
- 통합 테스트(Testcontainers 실 DB). 새 컴포넌트/버전 생성 확인 · affects/fix 링크 테이블 행 확인 · current_state_key 전이 확인 · 권한 없을 때 경고+이슈 생성 확인 · dry-run 무생성 확인.
- BC 격리 ArchUnit 무회귀. PR1 전체 테스트 무회귀.
- `./gradlew ktlintCheck detekt test` 그린. `bash scripts/verify-master-plan.sh` 통과(FR 카운트 불변).

## Brainstorming Check

✅ 통과 (1회 iteration, 적대적 자체 갭 분석). 발견 후 스펙 보강.
- **G1 (중대)**. PR1이 `Success.warnings`를 폐기 → best-effort 경고 불가시. **R9 경고 로그 노출** 추가(스키마 무변경).
- **G2**. 권한 3축(생성=admin 경고 / 편집=basic 행실패 / 전이=경고) 명시화 + status는 `hasPermission` 사전체크로 경고화.
- **G3/G5**. OCC 버전 스레딩(create→…→version-link→status)·실행 순서 6단계 명시.
- **G4**. dry-run `rowTriggersUpdate`를 버전 링크(UPDATE)·상태(TRANSITION)로 확장(R7).
- office-hours(builder-mode)는 완료 FR 에픽 연속이라 스킵(메모리 bts-spec-office-hours-mismatch 준수).
