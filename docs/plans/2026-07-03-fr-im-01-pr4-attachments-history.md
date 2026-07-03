# FR-IM-01 PR4 — 첨부/이력 Import

> slug: fr-im-01-pr4-attachments-history
> type: backend (feature-equivalent, full workflow)
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 Import 에픽 4단계(마지막 백엔드 PR). PR1(코어)·PR2(컴포넌트/버전/상태)·PR3(댓글/Worklog) 완료 이후 후속.

SDD 10.6.3 마이그레이션 보존 대상 중 남은 두 가지 — **첨부파일**과 **이력(history)** 을 Import.

- 첨부. **zip 업로드 방식**(서버가 Jira URL을 fetch하지 않음 = SSRF 없음, Maxi 결정). Jira export 시 함께 받은 첨부 바이너리를 zip으로 업로드 → 생성된 이슈에 매핑.
- 이력. Jira changelog/history를 생성된 이슈 이력으로 보존(세부 정책 spec에서 확정).

에픽이므로 D박스 마킹은 FR-IM-01 전체 완료 시. FR 카운트(123) 불변, 신규 FR 0.

## 도메인 정리

- **BC**. 논리 search-export-import(파서/커맨드/업로드 계약) + 물리 issue-tracking(첨부·이력 어댑터 위임). PR1~3과 동일 구조, 신규 BC 0.
- **재사용 엔티티/서비스** (Explore 매핑, 실코드 확인).
  - 첨부. `IssueAttachmentService.upload(actor, issueKey, filename, contentType, sizeBytes, input)` (`issue-tracking/.../attachment/application/IssueAttachmentService.kt:101`). 권한 `IssuePermission.UPDATE`(댓글/워크로그와 동일 → 사전체크 패턴 재사용). 스캔 게이트 `VirusScanPort.scan`(ClamAV fail-closed) + `AttachmentTypePolicy.isAllowed`(MIME 화이트리스트, SVG/HTML/exe 제외). MinIO 버킷 `bts-attachments`(`minioClient`), storageKey=`issues/{issueId}/{attachmentId}`. **createdAt은 clock으로 서버 시각 박제**(원본 시각 보존하려면 시그니처 확장 필요).
  - 이력. `IssueHistoryRecorder.record(before, after, actor, projectId)`는 **before/after diff만** 산출 → 임의 Jira 이벤트 기록 불가. VO `IssueChangeGroup(issueId, issueKey, actorId?, items, createdAt?)` + `IssueChangeItem(field, fromValue?, toValue?, fromLabel?, toLabel?)`, 저장 `IssueChangeHistoryRepository.record(group)`(append-only). **author 주입 가능**(actorId), **occurredAt 주입 불가**(insert SQL이 created_at 생략 → NOW()). 스키마는 `created_at NOT NULL DEFAULT NOW()`라 **명시 삽입 허용 → 마이그레이션 불필요, insert SQL만 확장**.
- **Maxi 결정 (2026-07-03, 도메인 게이트)**.
  1. **이력 = 충실 재생(full replay)**. Jira `changelog.histories[]`의 각 history를 `IssueChangeGroup`(actor=원본 author, createdAt=원본 시각) 1건, 각 `item`을 `IssueChangeItem`(field=매핑, fromValue/toValue=Jira `fromString`/`toString`)으로 재생. **detector 우회 경로 신설**(`recordImported` 계열) + **occurredAt 주입**(insertGroup에 created_at 포함) 필요. Jira의 표시 문자열을 그대로 저장(BTS 라벨 resolver 미적용 — 과거 Jira 상태는 현 BTS 엔티티로 매핑 불가). status 변경도 텍스트 이력으로 기록(전이 재생 아님 → project-workflow BC 미개입).
  2. **첨부 zip = 두 part 한 요청**. `POST /api/v1/imports`에 매니페스트(`file`) + 첨부 zip(`attachmentsZip`, optional) 두 multipart part. `import_jobs`(V604)에 nullable `attachments_object_key` 컬럼 추가(**V605**, 머지 직전 재확인 — 동시 브랜치 충돌). worker가 zip 추출 후 행별 `fields.attachment[].filename` 매칭 → `IssueAttachmentService.upload` 위임.
  3. **PR 분할 = 한 PR**(첨부+이력). 두 기능 독립 → 병렬 dispatch. 규모 큼(예상 12~16 task).
- **기존 결정과의 관계**.
  - FR-HS-01은 status를 이력 라벨 범위 밖으로 뒀으나(project-workflow BC), import 이력의 status는 **BTS 전이 재생이 아니라 Jira 표시 문자열의 텍스트 기록**이라 BC 침범 아님.
  - PR3 "Import는 이벤트 재생 아님"(worklog 이력 생략)과 표면상 긴장이나, PR4 이력은 **import 행위의 부수 이력이 아니라 원본 Jira 변경이력 자체의 보존**이라 대상이 다름. dry-run·best-effort·tx 오염 사전체크 원칙은 계승.
  - 보안. 첨부는 FR-AC-01의 ClamAV+MIME 게이트를 **그대로 재사용**하되 import 문맥에서 실패는 **best-effort 경고**로 강등(전건 실패 방지). 우회 없음.
- **새 용어**. 없음(첨부·이력·changelog 기존 용어). glossary 갱신 불필요.
- **관련 ADR**. [docs/decisions/2026-07-03-fr-im-01-pr4-attachments-history.md](../decisions/2026-07-03-fr-im-01-pr4-attachments-history.md) (생성) — PR1 Import ADR(`2026-07-02-fr-im-01-csv-json-import.md`)의 후속.

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-im-01-pr4-attachments-history.md](../specs/2026-07-03-fr-im-01-pr4-attachments-history.md)

핵심 요약.
- **첨부**. `POST /api/v1/imports` 두 part(매니페스트 `file` + `attachmentsZip`) → V605 `import_jobs.attachments_object_key`. worker가 zip 추출 후 `fields.attachment[].filename`을 `<sourceKey>/<filename>`→플랫 순 매칭 → `IssueAttachmentService.upload`(ClamAV·MIME 게이트 재사용, 실패=첨부 단위 best-effort 경고) 위임. 원본 시각·업로더 보존(upload에 optional createdAt/uploadedBy).
- **이력**. Jira `changelog.histories[]` 충실 재생 — history→`IssueChangeGroup`(actor=원본 author 해석, occurredAt=원본 created), item→`IssueChangeItem`(field 매핑, from/to=Jira 표시문자열). `recordImported`(detector 우회)+`insertGroup` created_at 명시삽입(스키마 0). 필드매핑 테이블(미매핑=스킵+경고), author 미해석=actorId null, 시각 실패=그룹 스킵, 이슈당 상한 1000.
- **공통**. 파서 `buildJsonRow` 확장(JSON 전용, sourceKey/attachments/changelog 추출 — issueNode 이미 materialize라 스트리밍 복잡도 0). shared VO `ImportAttachment`/`ImportChangeGroup`/`ImportChangeItem` additive. 어댑터 위임(UPDATE 사전체크·resolveByEmails 합류). dry-run 별도 경고 경로(FORBIDDEN 미엮음). CSV는 첨부/이력 미지원.

Maxi 확정(도메인 게이트). 이력=충실재생 · 첨부 zip=두-part · 한 PR. 게이트1 특기 — E11(BTS "created" 이력이 재생 이력과 additive 공존).

## Brainstorming Check

✅ 통과 (1회 iteration, 적대적 self-review). gap 4건 반영 — E10 MinIO 고아객체 한계 명시 · E11 BTS-native "created" additive 공존 · R6 이력 issueKey=새 BTS 키 · R4 contentType/sizeBytes zip 엔트리 파생. Maxi 결정 필요 gap 0.

## Plan

> 모듈 배치. shared-kernel(T1) → search 파서/처리기/업로드/worker(T4·T5·T6·T7·T8) · issue-tracking 첨부시그니처(T2)·이력recorder(T3)·어댑터(T9). 같은 모듈 task는 Gradle 컴파일 직렬화(memory `bts-plan-wave-gradle-module-compile`) — depends-on은 코드 의존만 선언. 마이그레이션 V605 잠정(머지 직전 재확인 — 동시 세션, memory `migration-vnumber-concurrent-branch-collision`). 통합테스트는 Testcontainers 실 tx(mockk 가짜그린 회피 memory `tx-aware-dslcontext-rollback-test-gap`·`issue-tracking-transition-test-mocks-workflow-repo`).

### Task 1. shared-kernel — IssueImportCommand VO 확장 + ImportAttachmentSource 포트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueImportCommand.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/ImportAttachmentSource.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueImportPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueImportPortTest.kt`]
- depends-on: []

**RED**: `IssueImportPortTest`에 (i) 커맨드가 `sourceKey`·`attachments: List<ImportAttachment>`·`changelog: List<ImportChangeGroup>`를 담고 기본값 null/emptyList, (ii) `ImportAttachmentSource.open`이 fun interface로 InputStream? 반환, (iii) `importIssue(cmd, source)` 2-arg 오버로드가 default source=null로 하위호환(1-arg 호출 컴파일) 검증 → 컴파일 실패(VO/인터페이스 없음).
**GREEN**: `IssueImportCommand`에 `sourceKey: String? = null`, `attachments: List<ImportAttachment> = emptyList()`, `changelog: List<ImportChangeGroup> = emptyList()` 추가(끝에, 기존 필드 불변) + `ImportAttachment(filename: String, authorEmail: String? = null, createdAt: Instant? = null, mimeType: String? = null, sizeBytes: Long? = null)`·`ImportChangeGroup(authorEmail: String? = null, occurredAt: Instant? = null, items: List<ImportChangeItem> = emptyList())`·`ImportChangeItem(field: String, fromValue: String? = null, toValue: String? = null)` data class + `fun interface ImportAttachmentSource { fun open(filename: String, sourceKey: String?): InputStream? }`(신규 파일, L1 한국어 주석) + `IssueImportPort`를 **2-arg로 확장**.
  - **★위임 방향(eng-review CONCERN-6)**. `importIssue(cmd, attachments: ImportAttachmentSource?)`가 **주 메서드**(default `failure(ADAPTER_UNAVAILABLE)` fail-closed). 기존 1-arg `importIssue(cmd)`는 **default로 `importIssue(cmd, null)` 위임**(fail-closed 아님 — 그래야 기존 59개 1-arg 테스트/PR1~3 호출이 어댑터의 2-arg override로 흘러 무회귀, 첨부만 스킵). 어댑터는 **2-arg를 override**([[interface-extension-default-method]] fail-safe 방향).
**REFACTOR**: 각 VO/인터페이스 KDoc(nullable 의미·field=raw Jira field·source 매칭 책임은 구현체) + 파일 L1 주석.
**검증**: `./gradlew :modules:shared-kernel:test --tests '*IssueImportPortTest'`

### Task 2. issue-tracking — IssueAttachmentService.upload createdAt/uploadedBy 주입

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/IssueAttachmentService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/application/IssueAttachmentServiceImportTest.kt`]
- depends-on: []

**RED**: `IssueAttachmentServiceImportTest`(기존 `AttachmentTestConfig` 명시 @Bean 조립 재사용 — memory `fr-ac-01-d2-clamav-done` B1) — `upload(..., createdAt=원본시각, uploadedBy=원본uploaderId)`가 저장 Attachment의 (i) createdAt==주입시각(≠clock), (ii) uploadedBy==주입값(≠actor), (iii) null 전달 시 기존 clock/actor 폴백을 검증 → 컴파일 실패(파라미터 없음).
**GREEN**: `upload` 시그니처에 `createdAt: Instant? = null`, `uploadedBy: UUID? = null` 추가. 도메인 조립 시 `createdAt = createdAt ?: clock.instant()`, `uploadedBy = uploadedBy ?: actor.value`. 스캔/타입/권한/put/insert 순서·보상삭제 로직 불변. 기존 upload 호출부(컨트롤러) 동작 불변(default).
**REFACTOR**: KDoc에 주입 파라미터 의미(import 원본 메타 보존) 추가.
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueAttachmentServiceImportTest'`

### Task 3. issue-tracking — IssueHistoryRecorder.recordImported + created_at 명시삽입

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueHistoryRecorder.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/IssueChangeImportHistoryTest.kt`]
- depends-on: []

**RED**: `IssueChangeImportHistoryTest`(Testcontainers, `IssueTestcontainersBase`) — `recordImported(group)`가 (i) group.createdAt(과거 시각)을 **명시 삽입**(NOW() 아님)·조회 시 그 시각 반환, (ii) group.actorId(원본 author)·null 허용, (iii) items(field/from/to) 그대로 저장, (iv) **detector 미경유**(before/after diff 없이 임의 항목 기록), (v) createdAt=null이면 기존대로 NOW() 폴백(하위호환) 검증 → 컴파일/실행 실패.
**GREEN**: `IssueHistoryRecorder.recordImported(group: IssueChangeGroup)` facade 추가 — detector 미경유, `repository.record(group)` 직접 위임. `JdbcIssueChangeHistoryRepository.insertGroup`을 **createdAt 유무로 SQL 분기**(eng-review CONCERN-3, 같은 파일 `:165-208`이 이미 문서화한 **JDBC null→TIMESTAMPTZ 바인딩 SQLException 함정** 회피 — `COALESCE(?, NOW())`에 untyped null 바인딩 금지). `group.createdAt == null` → **created_at 컬럼을 INSERT 목록에서 제외**(DB `DEFAULT NOW()` 발동, 기존 `SQL_INSERT_GROUP` 그대로) / non-null → created_at 포함 SQL(`Timestamp.from` 타입 명시 바인딩). 기존 `record` 경로 7곳(WorklogService·IssueEpicService·IssueApplicationService·IssueMoveService — createdAt=null) NOW() 폴백 무회귀.
**REFACTOR**: KDoc(import 전용 경로·occurredAt 주입 근거·일반 record와의 차이 — append-only 감사 예외) + `insertGroup` 시각 분기 헬퍼.
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueChangeImportHistoryTest'`

### Task 4. search — 파서 확장 (buildJsonRow: sourceKey/attachments/changelog 추출)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ParsedImportRow.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/parse/ImportRowParserTest.kt`]
- depends-on: []

**RED**: `ImportRowParserTest` — (i) JSON `issues[].key`→`sourceKey`, (ii) `fields.attachment[]`{filename, author.emailAddress, created, mimeType, size}→`ParsedImportAttachment` 목록, (iii) `issues[].changelog.histories[]`{author.emailAddress, created, items[]{field, fromString, toString}}→`ParsedImportChangeGroup` 목록(중첩 items 복합원소), (iv) CSV는 첨부/changelog 무시(빈 목록), (v) changelog 부재 시 빈 목록 검증 → 실패.
**GREEN**: `ParsedImportRow`에 `sourceKey: String?`·`attachments: List<ParsedImportAttachment>`·`changelog: List<ParsedImportChangeGroup>` 추가 + 파서-로컬 VO `ParsedImportAttachment(filename, authorEmail?, created: String?, mimeType?, sizeBytes: Long?)`·`ParsedImportChangeGroup(authorEmail?, created: String?, items: List<ParsedImportChangeItem>)`·`ParsedImportChangeItem(field, fromValue?, toValue?)`(raw 문자열 시각). `buildJsonRow`에 `sourceKey=textOf(issueNode, "key")` + `jsonAttachmentsOf(fields)`(`fields.attachment[]`, jsonCommentsOf 패턴) + `jsonChangelogOf(issueNode)`(`issueNode.changelog.histories[]`, 중첩 items 추출). **issueNode 이미 readTree라 추가 스트리밍 없음**. changelog `total>maxResults` truncated 감지 플래그(선택). CSV parseCsv 경로 불변(빈 목록).
**REFACTOR**: 중첩 배열→VO 추출 헬퍼 + JSON 필드 상수(`FIELD_ATTACHMENT`/`FIELD_CHANGELOG`/`FIELD_HISTORIES`/`FIELD_ITEMS`/`FIELD_FROM_STRING`/`FIELD_TO_STRING`/`FIELD_KEY`/`FIELD_MIME_TYPE`/`FIELD_SIZE`) + KDoc.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportRowParserTest'`

### Task 5. search — ImportJobProcessor.toCommand 매핑 (attachments/changelog → shared VO)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`]
- depends-on: [1, 4]

**RED**: `ImportJobProcessorTest` — `toCommand`가 (i) `sourceKey` 관통, (ii) `ParsedImportAttachment`→`ImportAttachment`(created→Instant `parseInstantOrNull`, email 소문자, size Long), (iii) `ParsedImportChangeGroup`→`ImportChangeGroup`(created→occurredAt Instant, author 소문자, items→`ImportChangeItem` raw field 보존)로 매핑 검증 → 실패.
**GREEN**: `toCommand`에 `sourceKey`·`attachments`·`changelog` 매핑 라인 + `toImportAttachment`/`toImportChangeGroup`/`toImportChangeItem` 헬퍼(comment/worklog 매핑 선례). 시각은 기존 `parseInstantOrNull` 재사용. **필드 매핑은 여기서 안 함**(raw Jira field 그대로 운반 — 어댑터가 BTS field로 매핑).
**REFACTOR**: 매핑 헬퍼 KDoc(raw field 운반 이유).
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportJobProcessorTest'`

### Task 6. search — V605 마이그레이션 + ImportJob.attachmentsObjectKey + init_codegen 미러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/resources/db/migration/search-export-import/V605__import_jobs_attachments.sql`, `backend/modules/search-export-import/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/domain/ImportJob.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/repository/ImportJobRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/repository/ImportJobRepositoryTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/**/SchemaMigrationImportTest.kt`]
- depends-on: []

**RED**: `ImportJobRepositoryTest`(Testcontainers) — insert 후 조회 시 `attachmentsObjectKey` round-trip(값/null) 검증 + `SchemaMigrationImportTest`의 **하드코딩 컬럼 카운트 17→18** 정정 및 `attachments_object_key`(VARCHAR(500) NULL) 존재/타입 단언 추가(eng-review CONCERN-7, memory `fr-pm-permission-seed-migration-test-coupling`·`enum-add-breaks-crossmodule-count-guard` 동류 — 컬럼 추가가 카운트 가드 깸) → 실패.
**GREEN**: `V605__import_jobs_attachments.sql` = `ALTER TABLE import_jobs ADD COLUMN attachments_object_key VARCHAR(500)`(nullable) + **init_codegen.sql 동일 DDL 미러**(memory `jooq-init-codegen-mirror`) + `ImportJob`에 `attachmentsObjectKey: String? = null` 필드(끝에) + `ImportJobRepository` insert/select 매핑(jOOQ `.repository` 유지). jOOQ codegen 재생성 포함.
**REFACTOR**: 필드 KDoc + L1 주석.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportJobRepositoryTest'` (codegen 재생성 포함)

### Task 7. search — 업로드 계약 (POST /imports attachmentsZip part → MinIO) + config

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/ImportController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobService.kt`, `backend/modules/search-export-import/src/main/resources/application-dev.yml`, `backend/modules/search-export-import/src/main/resources/application-test.yml`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/web/ImportControllerIntegrationTest.kt`]
- depends-on: [6]

**RED**: `ImportControllerIntegrationTest` — `POST /api/v1/imports`에 `file`+`attachmentsZip` 두 part → (i) zip이 MinIO `bts-imports`에 별도 오브젝트 저장·`import_jobs.attachments_object_key` 기록, (ii) `attachmentsZip` 없으면 key=null(하위호환), (iii) CSV+zip → zip 무시(key=null) 검증 → 실패.
**GREEN**: `ImportController` upload 핸들러에 `@RequestPart("attachmentsZip") attachmentsZip: MultipartFile?` 추가. `ImportAcceptCommand`에 zip stream/size/filename optional 필드 추가. `ImportJobService.accept`가 zip을 `{projectKey}/{jobId}-attachments.zip` 키로 `storage.put` 후 `attachmentsObjectKey` 세팅(CSV면 스킵). config `bts.import.attachments-zip.max-size`(기본 500MB) + `application-*.yml` `spring.servlet.multipart.max-request-size` 상향(매니페스트+zip 합).
**REFACTOR**: zip 저장 헬퍼 추출 + KDoc(SSRF 0·두-part 근거).
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportControllerIntegrationTest'`

### Task 8. search — ZipImportAttachmentSource + worker 배선

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ZipImportAttachmentSource.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ZipImportAttachmentSourceTest.kt`]
- depends-on: [1, 5, 6]   # [5]와 `ImportJobProcessor.kt` 공유 → 직렬화(eng-review CONCERN-5, memory `parallel-dispatch-precommit-hook-race` 동일파일 편집경쟁 회피)

**RED**: `ZipImportAttachmentSourceTest` — (i) `open(filename, sourceKey)`가 `<sourceKey>/<filename>`→플랫 `<filename>` 순 매칭·미발견 null, (ii) 비압축 100MB 초과 엔트리 거부(null+로그), (iii) zip-slip(`..`/절대경로) 엔트리 거부, (iv) 손상 zip → 전 open null(예외 대신), (v) `close()`가 ZipFile+임시파일 정리 검증 → 실패.
**GREEN**: `ZipImportAttachmentSource`(shared `ImportAttachmentSource` **+ `AutoCloseable`** 겸 — eng-review PASS 유의) — MinIO에서 zip을 임시파일로 다운로드 후 `java.util.zip.ZipFile` 인덱스, `open`이 매칭 엔트리 InputStream 반환(**반환 스트림 close 책임은 소비 어댑터** — T9 BLOCKER-1 참조, 여기선 매칭만). `ImportJobProcessor.processRows`가 `storage.get(manifest).use{...}` 바깥에 **`openZipSourceOrNull(job).use{ source -> ... }`** 중첩(job당 1회 open·행 재사용·종료 close). `importPort.importIssue(cmd, source)`로 주입(source null이면 첨부 스킵). **dry-run이면 zip 다운로드/소스 생성 스킵**(eng-review NIT — 부수효과 미리보기라 500MB 낭비 회피).
**REFACTOR**: 매칭·검증 헬퍼 + 상수(MAX_ENTRY_SIZE) + KDoc(BC 격리 — 매칭은 zip 소유 search에·반환스트림 close는 어댑터).
**검증**: `./gradlew :modules:search-export-import:test --tests '*ZipImportAttachmentSourceTest'`

### Task 9. issue-tracking — IssueImportAdapter 위임 (첨부 upload·이력 recordImported·매핑·best-effort·dry-run) + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [1, 2, 3]

**★eng-review 반영(BLOCKER 1·2, CONCERN 4)**. 이 task가 첨부 안전성 핵심 — 아래 3점 필수.
- **BLOCKER-1 (스트림 close)**. `IssueAttachmentService.upload`의 `input`은 **호출자 close 책임**(`IssueAttachmentService.kt:92`, upload는 `Files.copy`만·close 안 함). 어댑터는 `source.open(...)?.use { stream -> attachmentService.upload(..., input = stream) }`로 **반드시 close**(FR-AC-01 MinIO 스트림 누수 회귀 방지 [[fr-ac-01-d2-clamav-done]]).
- **BLOCKER-2 (insert throw 사전체크)**. `AttachmentRepository.insert`는 `@Transactional`(REQUIRED)이라 행 tx에 참여 → **길이 초과(`filename` VARCHAR(500)·`content_type` VARCHAR(100)) insert throw가 rollback-only로 행 전체 롤백**(catch 무력). 잔여 throw 강등 집합에 **`filename.isNotBlank() && filename.length ≤ 500 && contentType.length ≤ 100` 사전체크** 필수(초과 시 첨부 스킵+경고, comment/worklog 사전체크와 동형). upload는 insert **이전** 실패(권한·MIME·scan·put·zip부재)만 best-effort — insert throw는 행 원자성.
- **CONCERN-4 (테스트 조립)**. `IssueImportAdapterTest`는 `@ContextConfiguration` 수동 @Bean 조립(`:93`, `:292-325`)이라 생성자 2개 추가 시 **보조 빈 열거 필요** — fake `AttachmentStoragePort`(no-op put/remove)·fake `VirusScanPort`(CLEAN 반환)·실 `AttachmentRepository`(issue_attachments round-trip)·실 `IssueHistoryRecorder`(recordImported가 detector/resolver 우회하므로 그 둘은 `mockk` 허용, **`IssueChangeHistoryRepository`는 실빈**=occurredAt/actor 실검증). `NamedParameterJdbcTemplate` 빈 컨텍스트 확인.

**RED**: `IssueImportAdapterTest`(Testcontainers 실 tx) 시나리오 추가 — (S1)첨부 upload+원본 시각/uploader 보존+조회(fake `ImportAttachmentSource`로 스트림 주입, **어댑터가 스트림 close 확인**), (S2)changelog recordImported+occurredAt/actor 보존+field 매핑, (S3)UPDATE 권한 없음→첨부/이력 스킵+집약 경고·이슈 생성, (S4)MIME 거부/스캔 미가용→첨부 단위 경고·이슈 커밋, (S5)author 미해석→첨부=requester·이력=actorId null, (S6)미매핑 field→스킵+경고, (S7)시각 파싱 실패→그룹 스킵+경고, (S8)이슈당 이력 상한 1000 초과→스킵+경고, (S9)dry-run→insert 0·**FORBIDDEN 미엮음**·warn 별도경로, (S10a)**긴 파일명(>500자) 사전체크→첨부만 스킵+이슈 커밋**(insert throw 미발생 실증), (S10b)예상외 throw→행 롤백 → 실패.
**GREEN**: `IssueImportAdapter` 생성자에 `IssueAttachmentService`·`IssueHistoryRecorder` 주입(위 CONCERN-4 테스트 조립 갱신). `importIssue(cmd, attachments)` **2-arg override**(1-arg는 T1 default가 `(cmd, null)` 위임). `executeImport`에 createIssue 후 `applyAttachments`(사전 UPDATE 권한+**파일명/타입 길이** 체크 → `attachments.open(filename, sourceKey)?.use { stream -> attachmentService.upload(createdAt/uploadedBy 주입, input=stream) }`, put/scan/MIME 실패 catch→첨부 단위 경고. **sizeBytes**=`ZipEntry.getSize()`가 -1이면 복사 바이트 카운트 폴백[NIT]) → `applyChangelog`(Jira→BTS field 매핑 테이블 R8·미매핑 스킵+경고·author resolveByEmails→null 폴백·occurredAt parse 실패 스킵·상한 1000, `IssueChangeGroup` 조립 후 `historyRecorder.recordImported`). `FieldResolution`에 첨부/이력 author 이메일 합류(`resolveByEmails` 단일 배치). dry-run `warnAttachmentsIfNeeded`/`warnChangelogIfNeeded`(`validateDryRun` FORBIDDEN early-return **이후**·`rowTriggersUpdate` 미엮음, dry-run은 zip 미오픈이라 **권한·filename 비어있음만 검사**, 실제 스캔은 미리보기 불가).
**REFACTOR**: 첨부/이력 처리·집약경고·필드매핑 헬퍼 추출 + KDoc(사전체크 잔여 throw 집합={권한·filename/type 길이·zip부재·MIME}·매핑 근거·스트림 close 책임).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueImportAdapterTest'`

## Plan 메타

- task 수: 9
- 예상 wave. Wave1 = T1·T2·T3·T4·T6(depends-on []), Wave2 = T5(1,4)·T7(6)·T9(1,2,3), Wave3 = T8(1,**5**,6 — T5와 `ImportJobProcessor.kt` 공유 직렬화). 같은 모듈(issue-tracking T2/T3/T9·search T4/T5/T6/T7/T8) Gradle 컴파일 직렬화.
- TDD 강제: yes (test 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on+files로 wave 계산
- 추가 검증: ktlint, detekt, ArchUnit(BC 격리·jOOQ `.repository`), verify-master-plan(카운트 불변 123), 통합테스트(Testcontainers 실 tx), 전 모듈 풀빌드
- 마이그레이션: V605 1건(search-export-import, 머지 직전 재확인). 이력은 코드-only(스키마 0), 첨부 테이블 변경 0
- E2E: 생략. UI 없는 백엔드 — Playwright 표면 없음. Testcontainers 실 tx(S1~S10)가 전 경로 커버. D6/D7 프론트 후속 PR에서 E2E
- FR 동기화: 에픽 내부(신규 FR 0, 카운트 123 불변). product/search-export-import.md §4.1 PR4 진행노트 추가(머지 단계). D박스는 FR-IM-01 전체 완료 시 일괄 마킹
- **구현 결과**. 9 task 직렬 TDD 완료(test→feat 정순 전건 검증). 전 3모듈 풀빌드 0 failures + ktlint/detekt clean(9m53s). **T9가 실회귀 발견·해소** — 포트 1-arg default 위임이 @Transactional 프록시 self-invocation을 우회해 tx 미시작(NoTransactionException) → 어댑터가 1-arg/2-arg 모두 override+@Transactional. `IssueImportPort` KDoc 정합화 커밋. BLOCKER-1(스트림 close, CloseTrackingInputStream 실측)·BLOCKER-2(insert 전 filename/type 길이 사전체크, FaultInjectingAttachmentRepository로 행 롤백 실증) 반영. stale base 2커밋(CFD 프론트 #227, 백엔드 무충돌) — 머지 시 rebase.

## 리뷰 결과

### plan-eng-review (2026-07-03, 적대적 엔지니어링 리뷰 — 실코드 대조)

6개 리스크 주장을 실코드에 대조. **BLOCKER 2건 + CONCERN 5건 전부 plan/spec/ADR에 반영 완료**.

- **BLOCKER-1 (첨부 스트림 미close → FD 누수)**. `IssueAttachmentService.upload`의 `input`은 호출자 close 책임(`:92`, upload는 `Files.copy`만)인데 어댑터가 `source.open` 스트림을 안 닫으면 prod 1만이슈×N첨부 FD 고갈 — TDD 미검출(테스트 소수 스트림). FR-AC-01 MinIO 누수 회귀. → **T9에 `source.open(...)?.use{}` close 명시 + S1에 close 검증**.
- **BLOCKER-2 (첨부 insert throw가 best-effort→행 롤백 상향)**. `AttachmentRepository.insert`는 `@Transactional`(REQUIRED) 행 tx 참여 → 긴 filename(>VARCHAR(500)) insert throw가 rollback-only로 이슈/이력/댓글까지 롤백하며 "경고"로 위장. ADR D4 무조건 "미오염" 거짓(put/scan은 insert 전이라 미오염 맞으나 insert는 참여 write). → **T9 사전체크에 filename/contentType 길이 추가·S10a/S10b 분리, spec R15·ADR D4 정정**.
- **CONCERN-3 (COALESCE null 바인딩 함정)**. `JdbcIssueChangeHistoryRepository`가 이미 문서화한 JDBC null→TIMESTAMPTZ SQLException 함정. 이력 write 전역 반경. → **T3을 "분기 방식"(null이면 컬럼 제외 DEFAULT)으로 고정, 실 Postgres 테스트**.
- **CONCERN-4 (어댑터 생성자 주입 테스트 조립)**. `IssueImportAdapterTest` 수동 @Bean 조립이라 fake StoragePort/ScanPort·실 AttachmentRepository·실 HistoryRecorder 보조빈 다수 필요. → **T9에 테스트 빈 목록 명시**.
- **CONCERN-5 (T5·T8 `ImportJobProcessor.kt` 공유 병렬충돌)**. → **T8 depends-on에 [5] 추가 직렬화, wave3로**.
- **CONCERN-6 (포트 2-arg default 위임 방향)**. 1-arg default가 fail-closed면 전 import 붕괴 / 어댑터 1-arg override 버리면 59테스트 붕괴. → **T1에 "1-arg default=`(cmd,null)` 위임·어댑터 2-arg override" 명시**.
- **CONCERN-7 (V605가 SchemaMigrationImportTest 17컬럼 카운트 깸)**. → **T6 files에 SchemaMigrationImportTest.kt 추가(17→18)**.
- **PASS**. Claim4(이력 필드매핑 insert 제약 — 매핑후 짧은 BTS field라 VARCHAR(64) 안전)·Claim2(BC격리 — shared 인터페이스 대칭)·MIME 정책 접근성·wave 그래프(CONCERN-5 외)·ZipFile 생명주기(AutoCloseable 겸 유의).
- **NIT 반영**. dry-run zip 미다운로드(T8)·`ZipEntry.getSize()` -1 폴백(T9/spec R4).

- **BLOCKER: 없음(2건 모두 plan 반영 해소)**. 설계 결함 아닌 plan 정밀도 문제라 수정으로 완결.
