# FR-IM-02 — Import 매핑 UI (필드/사용자 매핑)

> slug: fr-im-02-import-mapping
> type: api (풀스택 feature — 백엔드 검증 API + 프론트 매핑 마법사)
> agent: backend-engineer (프론트 task는 frontend-engineer, E2E는 qa-engineer)
> 생성: 2026-07-03

## Brief

FR-IM-02 Import 매핑 UI (필드/사용자 매핑). 선행 FR-IM-01(CSV/JSON Import, 전체 완료).
소스(CSV/JSON) 필드를 BTS 대상 필드에 매핑 + 소스 사용자를 BTS 사용자에 매핑 + 미매핑 처리.
매핑 검증 API + 매핑 마법사(다단계) 프론트.

- BC. search-export-import (물리 구현 위치는 domain 단계에서 확정 — FR-IM-01은 논리 search-export-import / 물리 자체 모듈 or issue-tracking 재사용 패턴 확인 필요)
- Plan slug (product). search/import-mapping
- product D단계.
  - D1. 도메인 — ImportMapping
  - D2. 명세 — 필드 매핑 + 사용자 매핑 + 미매핑 처리
  - D3. 데이터 모델 — import_mappings(import_job_id, source_field, target_field)
  - D4. 백엔드 — 매핑 검증 API
  - D5. 백엔드 테스트
  - D6. 프론트 UI — 매핑 마법사 (다단계)
  - D7. E2E

## 도메인 정리

- **BC**. search-export-import (물리 구현 `com.bts.search.imports`, FR-IM-01과 동일 모듈)
- **작업 범위 (이번 /bts 실행)**. **PR-A = 기반 + 필드 매핑**. 백엔드는 순차 에픽 3-PR로 분할 (Maxi 결정, 사용자/값 매핑 확장으로 범위 증가).
  - **PR-A (이번)**. V606(status CHECK + import_mappings), AWAITING_MAPPING, analyze, 매핑-aware CSV 파서, 필드 매핑 validate/confirm, 워커 통합.
  - **PR-B (후속)**. 사용자 매핑 — 전 작성자 필드(reporter/assignee + 댓글/worklog/첨부/changelog 작성자). import_user_mappings + distinct 수집 + 프로세서 해석 + VO authorUserId.
  - **PR-C (후속)**. 값 매핑 — status/type/priority. import_value_mappings + distinct 값 수집 + 프로세서 값 치환.
  - **프론트 D6/D7 (후속)**. 다단계 매핑 마법사 + E2E.

### Maxi 아키텍처 결정 (2026-07-03, AskUserQuestion 4건)

1. **2단계 흐름** — analyze(분석)→매핑→실행. 기존 즉시-업로드(`POST /api/v1/imports`, canonical 자동매핑)는 하위호환 유지. 새 매핑 경로는 `ImportJob`에 `AWAITING_MAPPING` 상태 추가.
2. **명시적 사용자 매핑 지속화** — 파일에서 distinct 리포터/담당자 식별자 수집→이메일 자동해석→사용자가 각 항목을 BTS 사용자로 확정/재지정→저장→워커가 사용.
3. **CSV 자유 필드매핑 + JSON canonical** — CSV는 임의 헤더 자유 매핑(핵심 가치), JSON은 Jira REST export 고정 구조라 canonical 유지(필드 매핑 대상 제외). 사용자 매핑은 CSV+JSON 공통.
4. **PR 분할** — 백엔드 먼저(PR1) → 프론트 후속(PR2). FR-IM-01 선례.

### 새 유비쿼터스 언어 (glossary 추가 후보 — Maxi 승인 대기)

| 용어 | 정의 |
|---|---|
| ImportMapping (Import 매핑) | 한 Import 작업에서 소스 파일을 어떻게 해석할지 정의한 구성. 필드 매핑 + 사용자 매핑. `import_jobs` 자식. |
| 소스 필드 (Source field) | 업로드 파일에서 감지된 컬럼(CSV 헤더). analyze 단계 산출물. |
| 대상 필드 (Target field) | 소스가 매핑될 수 있는 BTS 이슈 필드 카탈로그(summary·description·type·priority·reporter·assignee·labels·component·status·fixVersion·affectsVersion). |
| 필드 매핑 (Field mapping) | 소스 필드 → 대상 필드 대응(또는 무시/IGNORE). |
| 사용자 매핑 (User mapping) | 소스 사용자 식별자(이메일/이름) → BTS 사용자 UUID. |
| 미매핑 처리 (Unmapped handling) | 매핑 안 된 소스 필드는 무시. 미해석 사용자는 기존 FR-IM-01 폴백(reporter→requester, assignee→미배정). |
| 분석 (Analyze) | 업로드 파일을 파싱해 소스 필드·distinct 사용자 식별자·샘플 행을 감지하는 단계. |
| AWAITING_MAPPING | 분석 완료 후 매핑 대기 중인 `ImportJob` 상태(PENDING 이전). |

### 영향 엔티티/자산

- **신규 도메인**. `ImportMapping`(필드 매핑 컬렉션 + 사용자 매핑 컬렉션 — `ImportJob` aggregate 소유 또는 job-keyed 소형 aggregate).
- **enum 확장**. `ImportJobStatus`에 `AWAITING_MAPPING` 추가. (Kotlin enum 카운트 가드 없음 — 안전. **단 DB CHECK 제약 `chk_import_jobs_status` 확장 필수** + `SchemaMigrationImportTest` 검증 동반.)
- **신규 데이터 모델**. `import_mappings(import_job_id, source_field, target_field)` (필드 매핑) + **`import_user_mappings(import_job_id, source_identifier, target_user_id)`** (사용자 매핑 — product 문서 단일 테이블 스케치의 확장, 아래 §스펙 deviation 기록).
- **파서 확장**. `ImportRowParser`를 매핑-aware로 — CSV는 하드코딩 `HEADER_*` 대신 필드 매핑으로 컬럼 해석. JSON은 canonical 유지.
- **다음 V번호**. V606 (전 모듈 최대 605).

### 순서 의존 (중요)

사용자 식별자는 reporter/assignee로 **매핑된 컬럼 안에** 존재한다. 따라서 마법사/API 순서는 **필드 매핑 → (그 매핑 기준) distinct 사용자 수집 → 사용자 매핑**. 사용자 식별자 수집은 필드 매핑을 파라미터로 받는 2차 분석이다.

### 기존 결정 충돌

- 없음. FR-IM-01 ADR(`2026-07-02-fr-im-01-csv-json-import.md`)이 이 확장을 명시적으로 예정(`ImportRowParser` KDoc L36, `IssueImportCommand` KDoc L13). 커맨드 객체 설계로 포트 시그니처 변경 없이 필드 추가 가능.

### 스펙 deviation (product 문서 대비 — 전수 동기화 필요)

- product §4.2 D3은 `import_mappings(import_job_id, source_field, target_field)` **단일 테이블**만 명시. 사용자 매핑 지속화(Maxi 결정 2)를 위해 **`import_user_mappings` 테이블 추가**. → product 문서 D3 본문 갱신 + fr-index 무영향(FR 수 불변).
- `ImportJobStatus`에 `AWAITING_MAPPING` 신규 상태 → 흐름 확장은 SDD 10.6 참조 필요 시 반영.

### 관련 ADR

- [docs/decisions/2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-im-02-import-mapping.md](../specs/2026-07-03-fr-im-02-import-mapping.md)

**PR-A 핵심 (기반 + 필드 매핑)**.
- 2단계 흐름: `POST /imports/analyze`(persist+AWAITING_MAPPING+헤더/샘플 감지) → `POST /imports/{id}/mapping/validate`(필드매핑 검증) → `POST /imports/{id}/mapping`(저장+PENDING 전환+enqueue).
- 매핑-aware CSV 파서(임의 헤더→대상 필드, 매핑 없으면 canonical 폴백). JSON canonical 유지.
- V606: status CHECK에 AWAITING_MAPPING 추가 + import_mappings 테이블. 워커는 PENDING만 클레임.
- 대상 필드 카탈로그 11개 + IGNORE. 에러 4종·경고 1종.
- 기존 즉시-업로드 경로 회귀 0.

## Brainstorming Check

✅ 통과 (자기-비평 1회). 수정가능 갭 6건 스펙 반영. 범위 경계 2건(전 작성자 사용자매핑·값 매핑)은 Maxi 결정으로 PR-B/PR-C 분리 → 이번 PR-A는 필드 매핑에 집중.

## Plan

> **PR-A 범위 — search-export-import 모듈 단일 BC. shared-kernel/issue-tracking 무변경**(사용자/값 매핑은 PR-B/C). 회귀면 최소.
> 신규 패키지 `com.bts.search.imports.mapping`. 파일 경로는 repo 루트 기준.

### Task 1. V606 마이그레이션 + init_codegen 미러 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/resources/db/migration/search-export-import/V606__import_mappings.sql`, `backend/modules/search-export-import/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/SchemaMigrationImportTest.kt`]
- depends-on: []

**RED**. `SchemaMigrationImportTest`에 (1) `import_mappings` 테이블 존재+3컬럼+PK(import_job_id, source_field)+FK ON DELETE CASCADE, (2) `chk_import_jobs_status`가 `AWAITING_MAPPING` INSERT 허용, (3) 기존 status INSERT 여전히 허용 검증 테스트 추가 → migration 부재로 FAIL.

**GREEN**. V606 작성.
```sql
ALTER TABLE import_jobs DROP CONSTRAINT chk_import_jobs_status;
ALTER TABLE import_jobs ADD CONSTRAINT chk_import_jobs_status
  CHECK (status IN ('AWAITING_MAPPING','PENDING','RUNNING','COMPLETED','FAILED'));
CREATE TABLE import_mappings (
  import_job_id UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
  source_field  TEXT NOT NULL,
  target_field  TEXT NOT NULL,
  PRIMARY KEY (import_job_id, source_field)
);
```
`init_codegen.sql`에 동일 DDL 미러(jOOQ `ImportMappingsRecord` 생성용).

**REFACTOR**. DDL 주석(L1 역할 + CHECK 확장 사유). V602 export_jobs 미러 스타일 정합. **`idx_import_jobs_expires` 주석("expires_at 채워진 COMPLETED job만") 갱신** — AWAITING_MAPPING도 expires_at 사용하므로 부정확(NIT).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*SchemaMigrationImportTest*'`. **머지 직전 V606 번호 재확인**(동시 브랜치 충돌).

### Task 2. 도메인 — AWAITING_MAPPING + TargetField 카탈로그 + ImportMapping VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/domain/ImportJobStatus.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/TargetField.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/ImportMapping.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/TargetFieldTest.kt`]
- depends-on: []

**RED**. `TargetFieldTest` — 카탈로그 11개 키(summary required·multi 플래그)·`fromKey("summary")` 해석·`IGNORE` 인식·미지 키 null → 클래스 부재 FAIL.

**GREEN**. `ImportJobStatus`에 `AWAITING_MAPPING` 추가(맨 앞). `TargetField` enum/카탈로그 — key·label·required·multi. `ImportMapping` VO(`Map<sourceField,targetField>` 래핑 + IGNORE 필터).

**REFACTOR**. KDoc(각 필드 대응 ParsedImportRow 필드 명시). enum 추가 파급 grep(`ImportJobStatus` 카운트 가드 없음 확인 — 안전).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*TargetFieldTest*'`.

### Task 3. 매핑-aware CSV 파서 (field-mapping + canonical 폴백 + bounded read)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/parse/ImportRowParserTest.kt`]
- depends-on: [2]

**RED**. (1) 임의 헤더 CSV("제목,설명" + fieldMapping 제목→summary·설명→description)가 매핑대로 파싱됨. (2) fieldMapping=null이면 canonical 동작 불변(회귀). (3) **mapped 모드에서 리터럴 "summary" 헤더가 없어도**(예: "제목") throw 없이 파싱됨(canonical summary 하드-throw 회피 검증 — 아래 BLOCKER-1/CONCERN-6). (4) **mapped 모드에서도 CSV 다중 `Comment` 컬럼 수집 불변**(canonical 회귀 방지). (5) `readHeaderAndSample(input, n=5)`가 헤더+최대 5행만 읽고 조기중단 → 미구현 FAIL.

**GREEN**. ⚠️ **오버로드 사용(중간 기본인자 금지 — BLOCKER-1)**. 기존 `fun parseCsv(input, onRow)` 시그니처 **유지**(canonical, 기존 positional 호출·trailing-lambda 테스트 불변) + 신규 `fun parseCsv(input, fieldMapping: Map<String,String>, onRow)` 오버로드 추가(`onRow` 항상 마지막). fieldMapping 있으면 `TargetField`별 컬럼 위치를 매핑으로 해석(trim+lowercase), 2-arg는 기존 `HEADER_*` canonical. 공통 `resolveTargetColumns(headers, fieldMapping?)` → `Map<TargetField,Int>`. **summary 필수 검사는 canonical 리터럴 헤더가 아니라 resolveTargetColumns 결과의 summary 위치 존재로 판정**(mapped 모드는 confirm 시 `SUMMARY_NOT_MAPPED`로 이미 게이트됨). 댓글 다중컬럼 수집(`commentColumnPositions`)은 두 모드 공통 유지. `readHeaderAndSample`(analyze용, 헤더 + N행 후 중단).

**REFACTOR**. 중복 제거(canonical/mapped 공통 resolveTargetColumns). KDoc §매핑-aware 절 + 오버로드 사유. JSON 경로·댓글 수집 불변 명시.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportRowParserTest*'`.

### Task 4. ImportMappingRepository + ImportJobRepository.transitionToPending

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/repository/ImportMappingRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/repository/ImportMappingRepositoryTest.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/repository/ImportJobRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/repository/ImportJobRepositoryTest.kt`]
- depends-on: [1]

**RED**. (1) `ImportMappingRepository.saveAll(jobId, mappings)` + `findByJobId(jobId)` 라운드트립. (2) `ImportJobRepository.transitionToPending(jobId)` — AWAITING_MAPPING→PENDING CAS + expiresAt=null 반환 true, 다른 상태면 false(멱등). (3) **`findExpired`가 만료된 AWAITING_MAPPING job도 반환**(status-agnostic 확인). (4) **`deleteIfExpired(id, now)` 가드 삭제** — expires_at 지난 행만 삭제(confirm으로 expires_at=NULL 된 job은 0행 → cleanup 레이스 차단, CONCERN-4). → 미구현 FAIL. **(jOOQ codegen: T1 migration→init_codegen 반영 후 build로 `ImportMappingsRecord` 생성 확인. 스테일 캐시 방지 `--rerun-tasks`/clean-codegen — 메모리 backend-clean-build-broken)**.

**GREEN**. jOOQ 기반 saveAll(멱등 — 기존 삭제 후 삽입 or ON CONFLICT)·findByJobId. `transitionToPending` — `UPDATE ... SET status='PENDING', expires_at=NULL WHERE id=? AND status='AWAITING_MAPPING'` rowsAffected>0. `deleteIfExpired` — `DELETE ... WHERE id=? AND expires_at IS NOT NULL AND expires_at < ?` (기존 무조건 `deleteById`를 대체하지 않고 cleanup 전용 가드 삭제 추가).

**REFACTOR**. Testcontainers 시드(import_jobs FK 충족). KDoc CAS·가드삭제 의도(레이스 차단).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingRepositoryTest*' --tests '*ImportJobRepositoryTest*'`.

### Task 5. MappingValidator — 필드 매핑 검증 규칙

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/MappingValidator.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/MappingValidatorTest.kt`]
- depends-on: [2]

**RED**. `validate(sourceFields, fieldMappings)` → errors/warnings. 케이스. summary 미매핑→`SUMMARY_NOT_MAPPED`; 둘이 같은 target→`DUPLICATE_TARGET`; 카탈로그 밖 target→`UNKNOWN_TARGET`; 감지 밖 source→`UNKNOWN_SOURCE`; 미매핑 감지 필드→warning `SOURCE_FIELD_IGNORED`; 정상→valid. → 미구현 FAIL.

**GREEN**. 순수 검증 로직(DB 무관). `MappingValidationResult(valid, errors, warnings)` + 코드 상수.

**REFACTOR**. 코드 카탈로그 companion. IGNORE는 DUPLICATE 제외.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*MappingValidatorTest*'`.

### Task 6. ImportJobService.analyze

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobService.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobServiceAnalyzeTest.kt`]
- depends-on: [2, 3]

**RED**. `analyze(command)` — `validateAndAuthorize` 재사용(권한/크기/형식) → storage.put → `insert(AWAITING_MAPPING, expiresAt=now+ABANDON_TTL_SECONDS)` → 파서 bounded read로 sourceFields+sampleRows 감지 → `ImportAnalysisResult(job, sourceFields, sampleRows, targetFields)`. JSON이면 sourceFields=canonical. → 미구현 FAIL(mockk storage/repo/parser).

**GREEN**. accept 미러하되 status=AWAITING_MAPPING·enqueue 없음·expiresAt 설정. `ABANDON_TTL_SECONDS = 24*60*60`(방치 매핑 job 정리 기준, `RESULT_TTL_SECONDS` 선례값 — 매핑 세션보다 충분히 김). CSV는 `readHeaderAndSample`, JSON은 canonical 카탈로그.

**REFACTOR**. buildSourceObjectKey 재사용. KDoc §분석 흐름.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportJobServiceAnalyzeTest*'`.

### Task 7. ImportMappingService — validate + confirm

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/ImportMappingService.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceTest.kt`]
- depends-on: [3, 4, 5]

**RED**. (1) `validate(jobId, actor, fieldMappings)` — 소유+AWAITING_MAPPING 아니면 예외, persist 파일 헤더 재읽기로 sourceFields 확보 → MappingValidator → result. (2) `confirm(jobId, actor, fieldMappings, dryRun)` — 검증 실패→`ImportMappingInvalidException`(422), 성공→transactionTemplate{ **CAS 먼저** }. (3) **반복/동시 confirm 시 단일 enqueue + 두 번째 409**(CONCERN-3 — CAS 게이트가 saveAll/enqueue보다 먼저). → 미구현 FAIL.

**GREEN**. 서비스 조립(ImportMappingRepository·MappingValidator·ImportJobRepository·enqueuePublisher·storage·parser·transactionTemplate). 소유 검증 `findByIdForRequester`. **confirm 트랜잭션 순서(CONCERN-3)**. `transactionTemplate{ transitionToPending(CAS) == false → throw ImportMappingStateConflictException(롤백); else saveAll; enqueue }`. CAS를 saveAll/enqueue보다 **먼저** 실행해 이미-PENDING job의 매핑 덮어씀·중복 enqueue 차단.

**REFACTOR**. 예외 클래스(web와 공유 위치). KDoc §확정 트랜잭션 경계(CAS-게이트 우선, outbox 선례 `ImportJobEnqueuePublisher`).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingServiceTest*'`.

### Task 8. 웹 계층 — 3 엔드포인트 + DTO + ExceptionHandler

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/ImportMappingController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/dto/ImportAnalysisResponse.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/dto/MappingValidationResponse.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/ImportExceptionHandler.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/web/ImportMappingControllerTest.kt`]
- depends-on: [6, 7]

**RED**. 슬라이스/통합 — `POST /api/v1/imports/analyze` **200**+분석결과·`POST /imports/{id}/mapping/validate` 200+검증·`POST /imports/{id}/mapping` 200(PENDING). 미인증 401·타인 404·검증실패 422·상태충돌 409·actor 추출 우선(auth-extraction-before-resource-lookup). ⚠️ **슬라이스 테스트는 `webAppContextSetup`(컨텍스트 스캔 — assignableTypes 스코프 실제 적용)**, standalone `setControllerAdvice` **금지**(스코프 무시로 가짜그린, BLOCKER-2). → 미구현 FAIL.

**GREEN**. `ImportMappingController`(신규, `imports.web`). ⚠️ **`ImportExceptionHandler`의 `@RestControllerAdvice(assignableTypes)`에 `ImportMappingController::class` 추가**(BLOCKER-2 — assignableTypes는 클래스 리스트, 같은 패키지로 커버 안 됨. 메모리 domain-exception-http-handler-basepackage-scope). analyze는 multipart(기존 ImportController currentActorId/validateProjectKey 패턴 재사용). ExceptionHandler에 `ImportMappingInvalidException`→422 `IMPORT_MAPPING_INVALID`·`ImportMappingStateConflictException`→409 `IMPORT_MAPPING_STATE_CONFLICT` 추가. analyze는 동기 완결(분석결과 body 반환)이라 202 아닌 **200**.

**REFACTOR**. DTO from() 팩토리. currentActorId 헬퍼 중복은 기존 BC 격리 관례(재사용 안 함) 유지. **ImportExceptionHandler.kt를 files에 포함**(assignableTypes 수정).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingControllerTest*'`.

### Task 9. 워커 통합 — Processor field mapping 로드 + Cleanup AWAITING_MAPPING 정리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/worker/ImportJobCleanupWorker.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/worker/ImportJobCleanupWorkerTest.kt`]
- depends-on: [3, 4]

**RED**. (1) job에 import_mappings 있으면 프로세서가 로드해 3-arg `parseCsv(input, fieldMapping, onRow)` 호출(임의 헤더→대상 필드). (2) 매핑 없으면 2-arg canonical 파싱(회귀 불변). (3) **CleanupWorker가 만료된 AWAITING_MAPPING job의 원본 MinIO 오브젝트 삭제 + `deleteIfExpired`로 행 삭제(FK CASCADE로 매핑 동반 삭제)** — 방치 정리 검증(CONCERN-5, 지금까지 untested). → 미구현 FAIL(mockk repository.findByJobId/findExpired).

**GREEN**. `ImportJobProcessor`에 `ImportMappingRepository` 주입. `processRows` CSV 분기 시 fieldMapping 로드(있으면 3-arg, 없으면 2-arg). JSON 불변. `ImportJobCleanupWorker`가 `deleteById` 대신 `deleteIfExpired`(가드 삭제, CONCERN-4) 사용 + AWAITING_MAPPING 원본 오브젝트도 삭제 대상 포함(sourceObjectKey 이미 존재).

**REFACTOR**. mapping 없을 때 2-arg 오버로드로 canonical 폴백. KDoc §매핑 로드·§방치 정리.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportJobProcessorTest*' --tests '*ImportJobCleanupWorkerTest*'`.

### Task 10. 통합테스트 — happy path + canonical 회귀

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingFlowIntegrationTest.kt`]
- depends-on: [8, 9]

**RED**. 실 DB/MinIO(Testcontainers) — 임의 헤더 CSV(예: "Título,담당,비고") 업로드 → analyze(AWAITING_MAPPING) → confirm(Título→summary) → 워커 process → COMPLETED, 이슈 생성 확인(mockk/stub IssueImportPort success). canonical 즉시경로(기존 accept) 여전히 동작(회귀). → FAIL.

**GREEN**. 전 경로 결선 확인. IssueImportPort는 test stub(BC 격리 — cross-BC 조립 없음, test-assembled).

**REFACTOR**. 시나리오 KDoc. 기존 `ImportControllerIntegrationTest` 스타일 정합.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingFlowIntegrationTest*'` + 모듈 전체 `./gradlew :backend:modules:search-export-import:test`(회귀 0).

## Plan 메타

- task 수: 10
- 예상 wave: 5 (W1: T1,T2 / W2: T3,T4,T5 / W3: T6,T7,T9 / W4: T8 / W5: T10)
- 단일 모듈(search-export-import) — Gradle 모듈 컴파일 wave 내 직렬(bts-plan-wave-gradle-module-compile), shared-kernel/issue-tracking 무변경
- TDD 강제: yes (test: 커밋 먼저)
- 추가 검증: ktlint, detekt(--rerun-tasks로 캐시 false-green 방지), 모듈 전체 test 회귀 0, verify-master-plan
- jOOQ codegen: T1 migration→init_codegen 미러 후 build로 ImportMappingsRecord 생성(T4 선행)

### 이월 노트 (후속 PR)
- **프론트 Zod enum**. `apps/web/src/api/imports.ts:21` `z.enum(['PENDING','RUNNING','COMPLETED','FAILED'])`에 `AWAITING_MAPPING` 추가는 **프론트 PR**에서(PR-A는 기존 클라이언트가 즉시경로 job만 폴링 → AWAITING_MAPPING 미노출, 회귀 0). 메모리 frontend-zod-backend-dto-contract-gap.
- **multi 필드 단일컬럼**. CSV에서 multi target(labels 등)도 단일 컬럼 매핑(구분자 분리). Jira 반복 동명 컬럼은 첫 컬럼만(putIfAbsent) — PR-A 문서화(버그 아님).

## 리뷰 결과

### eng + devex 독립 리뷰 (2026-07-03, general-purpose 서브에이전트, 코드 대조)

**BLOCKER 2건 (수정 완료 → plan 반영)**.
- **B1** 매핑 파서 중간 기본인자(`parseCsv(input, fieldMapping=null, onRow)`)가 기존 positional 호출(`ImportJobProcessor.kt:145`)을 깨고 단일 모듈이라 Task 3 컴파일 불가 → **오버로드**로 변경(Task 3/9 반영).
- **B2** `ImportExceptionHandler @RestControllerAdvice(assignableTypes=[ImportController])`는 클래스 스코프 — 새 컨트롤러 미커버("같은 패키지" 가정 오류) → assignableTypes에 `ImportMappingController` 추가 + 슬라이스 `webAppContextSetup`(standalone 가짜그린 금지). Task 8 반영.

**CONCERN 4건 (수정 완료 → plan 반영)**.
- **C3** confirm 트랜잭션: transitionToPending(CAS)를 saveAll/enqueue보다 **먼저** → 반복 confirm 시 중복 enqueue 차단(Task 7).
- **C4** cleanup 삭제 레이스: `deleteIfExpired`(expires_at 가드) 추가 — confirm으로 expires_at=NULL 된 job은 0행(Task 4/9).
- **C5** 방치 정리 untested + ABANDON_TTL 미정의 → CleanupWorker 테스트 추가 + `ABANDON_TTL_SECONDS=24h`(Task 6/9).
- **C6** 매핑 파서 회귀: mapped 모드 summary 하드-throw 회피 + CSV 댓글 수집 불변 명시(Task 3).

**NIT (반영/이월)**. analyze 202→**200**(동기완결), idx 주석 갱신(Task 1), Zod enum·multi 단일컬럼 이월노트, 워커 poison 방어 스킵(선택), codegen --rerun-tasks.

**코드 대조 확인된 긍정**. enum 추가 카운트가드 무영향·`findExpired`/cleanup가 AWAITING_MAPPING 처리 가능(expires_at set 시)·워커 PENDING-only 불변·`validateAndAuthorize` private 동일클래스 재사용 유효·V606+init_codegen 정확.

**BLOCKER 잔여: 없음** (2건 모두 plan 수정 반영). 게이트 1 진입 가능.
