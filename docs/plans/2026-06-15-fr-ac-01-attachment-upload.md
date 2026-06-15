# FR-AC-01 첨부 업로드 (최대 100MB/파일)

> slug: fr-ac-01-attachment-upload
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-01 — 이슈에 파일 첨부 업로드. 최대 100MB/파일. issue-tracking BC, SDD §4.2.1.

- 사용자 원문: "fr-ac-01 진행해줘"
- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: Attachment (신규, 첫 구현). 대상 Issue.
- 용어: "어테처(Attachment)" — glossary 기등재(37행, "이슈에 첨부된 파일") → 신규 용어 추가 불필요
- 핵심 발견:
  - backend `Attachment` 클래스 0건 → FR-AC-01이 첨부 첫 구현
  - MinIO gradle 의존성·dev/test 인프라 전무 → **신규 외부 의존성(io.minio:minio) + 인프라 구성 필요**
  - `issues` PK = UUID → attachment도 UUID PK/FK (SDD §05.7의 BIGINT 표기는 stale → deviation)
  - SDD §11 첨부 API 미정의 → spec서 신규 설계
  - 다음 Flyway V번호: V023 (issue-tracking 최신 V022)
- Maxi 결정 (2026-06-15):
  - 저장소 SDK = MinIO Java SDK (`io.minio:minio`)
  - 업로드 방식 = 서버 경유 멀티파트 스트리밍
  - 범위 = 업로드 + 다운로드 + 목록 + 삭제 (FR-AC-02 미리보기는 별도 FR 제외)
- 기존 결정 충돌: 없음
- 관련 ADR: [docs/adr/2026-06-15-fr-ac-01-attachment-storage.md](../adr/2026-06-15-fr-ac-01-attachment-storage.md) (생성됨)


## 스펙

전체 스펙. [docs/specs/2026-06-15-fr-ac-01-attachment-upload.md](../specs/2026-06-15-fr-ac-01-attachment-upload.md)

핵심 요약.
- 별도 `IssueAttachmentController` (`/api/v1/issues/{key}/attachments`) — 업로드(POST)/목록(GET)/다운로드(GET {id})/삭제(DELETE {id})
- 권한: 업로드·삭제=UPDATE(EDIT_ISSUE), 목록·다운로드=VIEW(VIEW_ISSUE+보안등급 게이트). enum 무변경
- MinIO 서버 경유 스트리밍, 100MB 상한(413), 다운로드 Content-Disposition: attachment
- V023 `issue_attachments` (UUID PK/FK, 하드 삭제 = deleted_at 없음, FK ON DELETE CASCADE)
- 신규 의존성 io.minio:minio + testcontainers:minio

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). bucket 보장 정책 누락 발견 → NFR-6 보강. 메모리 함정 사전 반영.

## Plan

패키지 = `com.bts.issue.attachment.*` (기능별 패키지 컨벤션: domain/application/repository/web/adapter). 검증 = `cd backend && ./gradlew :modules:issue-tracking:test`.

### Task 1. V023 `issue_attachments` 마이그레이션 + jOOQ codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V023__issue_attachments.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/AttachmentSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `AttachmentSchemaMigrationTest` (Testcontainers Postgres) — V023 적용 후 `issue_attachments` 테이블·컬럼(id UUID PK, issue_id UUID FK CASCADE, filename, content_type, size_bytes, storage_key, uploaded_by, created_at)·`(issue_id)` 인덱스 존재 검증. 실패: 테이블 없음.

**GREEN**: V023 작성(ADR §6 표). `init_codegen.sql`에 동일 DDL 미러(메모리: 컬럼 추가는 init_codegen에도 미러 필수 — 누락 시 jOOQ codegen drift).

**REFACTOR**: 컬럼 주석(COMMENT) + DDL 정렬.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*AttachmentSchemaMigrationTest"` + jOOQ codegen 컴파일 그린.

### Task 2. AttachmentStoragePort + MinioStorageAdapter (MinIO 의존성·설정·Testcontainers)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/build.gradle.kts`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/AttachmentStoragePort.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/adapter/MinioStorageAdapter.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/adapter/MinioStorageConfig.kt`, `backend/modules/issue-tracking/src/main/resources/application-dev.yml`, `backend/modules/issue-tracking/src/main/resources/application-test.yml`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/MinioStorageAdapterTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/AttachmentMinioTestcontainersBase.kt`]
- depends-on: []

**RED**: `MinioStorageAdapterTest` (Testcontainers MinIO, singleton `.apply{start()}` — 메모리: stale port 회피) — `put(key, stream, size, contentType)` → `get(key)` 바이트 동일성, `remove(key)`, bucket 부재 시 보장(`bucketExists`→`makeBucket`). 실패: `MinioStorageAdapter` 없음.

**GREEN**: build.gradle.kts에 `implementation("io.minio:minio:8.5.x")` + `testImplementation("org.testcontainers:minio")` (Maxi 승인 신규 의존성). `AttachmentStoragePort` 인터페이스(put/get/remove). `MinioStorageAdapter` 구현(스트리밍 putObject/getObject). `MinioStorageConfig` — `@ConfigurationProperties`(endpoint/accessKey/secretKey/bucket) + MinioClient 빈 + 기동 시 bucket 보장. application-dev/test.yml에 minio 설정(환경변수 외부화).

**REFACTOR**: 설정 프로퍼티 검증(빈 endpoint fail-fast) + KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*MinioStorageAdapterTest"`.

### Task 3. Attachment 도메인 + AttachmentRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/domain/Attachment.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/repository/AttachmentRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/AttachmentRepositoryTest.kt`]
- depends-on: [1]

**RED**: `AttachmentRepositoryTest` (Testcontainers Postgres + 이슈 시드) — `insert`, `findByIssueId`(created_at 내림차순), `findById`, `deleteById`(하드), 교차 이슈 격리. 실패: `AttachmentRepository` 없음.

**GREEN**: `Attachment` 도메인 data class(UUID id/issueId, filename, contentType, sizeBytes, storageKey, uploadedBy, createdAt). `AttachmentRepository` — jOOQ DSLContext insert/select/delete.

**REFACTOR**: record→domain 매핑 함수 추출 + KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*AttachmentRepositoryTest"`.

### Task 4. IssueAttachmentService (업로드/목록/다운로드/삭제 + 권한 + 트랜잭션 정합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/IssueAttachmentService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/AttachmentCommands.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentServiceTest.kt`]
- depends-on: [2, 3]

**RED**: `IssueAttachmentServiceTest` (mockk StoragePort/Repository/PermissionResolver/IssueLookup) — (a) 업로드: UPDATE 권한 미보유 403, 보유 시 MinIO put 후 DB insert, (b) insert 실패 시 MinIO 보상 삭제(고아 방지), (c) 목록/다운로드: VIEW 미보유 403, (d) 삭제: UPDATE 미보유 403, 보유 시 MinIO remove + DB delete(하드), (e) 교차 이슈 attachmentId → 404, (f) 소프트 삭제/부재 이슈 업로드 → 404, (g) actor 추출을 이슈 조회보다 먼저(메모리: 존재 probe 방지). 실패: 서비스 없음.

**GREEN**: `IssueAttachmentService` — `@Service @Transactional`(메모리: @Service 누락 시 @Transactional 무력화). 권한은 IssuePermissionResolver.hasPermission(UPDATE/VIEW, IssueScope.Issue(key)). 업로드/삭제 트랜잭션 정합(spec 엣지). 이슈 키→ID 조회는 기존 IssueLookup/포트 재사용.

**REFACTOR**: 보상 삭제 best-effort 로직 + KDoc(권한예외를 catch가 삼키지 않도록 — 메모리).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueAttachmentServiceTest"`.

### Task 5. IssueAttachmentController + 응답 DTO + 에러 핸들러 + multipart 설정

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/web/IssueAttachmentController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/web/AttachmentResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/web/AttachmentExceptionHandler.kt`, `backend/modules/issue-tracking/src/main/resources/application-dev.yml`, `backend/modules/issue-tracking/src/main/resources/application-test.yml`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentControllerTest.kt`]
- depends-on: [4]

**RED**: `IssueAttachmentControllerTest` (MockMvc 슬라이스, service mock) — POST multipart(part=`file`) 201+Location, GET 목록 200, GET {id} 다운로드 `Content-Disposition: attachment`+`Content-Type`+`Content-Length`, DELETE 204, 413(MaxUploadSizeExceeded), 400(file part 누락). 실패: 컨트롤러 없음.

**GREEN**: `IssueAttachmentController(/api/v1/issues/{key}/attachments)` — actor=`CurrentActor.current()`, `DataResponse` 래퍼, 다운로드는 `StreamingResponseBody`/`InputStreamResource`. `AttachmentResponse`(storage_key 비노출). `AttachmentExceptionHandler`(@RestControllerAdvice basePackageClasses 한정 — 메모리: 도메인예외 HTTP핸들러 스코프). application-*.yml에 `spring.servlet.multipart.max-file-size=100MB`, `max-request-size=110MB`.

**REFACTOR**: 파일명 RFC 5987 인코딩 헬퍼 + KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueAttachmentControllerTest"`.

### Task 6. 통합테스트 라운드트립 (Testcontainers MinIO + Postgres)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentIntegrationTest.kt`]
- depends-on: [5]

**RED**: `IssueAttachmentIntegrationTest` (전체 컨텍스트 + Testcontainers MinIO+Postgres) — (a) 업로드→목록→다운로드(업로드 바이트 == 다운로드 바이트)→삭제 라운드트립, (b) 삭제 후 MinIO 객체·DB row 모두 부재, (c) 100MB 성공 / 초과 413, (d) 권한 거부(UPDATE/VIEW 미보유 403), (e) 교차 이슈 {id} 404. 실패: 엔드포인트 미통합.

**GREEN**: 위 RED를 통과시키는 wiring 보정(빈 등록·프로필).

**REFACTOR**: 헬퍼(업로드 multipart 빌더) 추출.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueAttachmentIntegrationTest"` + 모듈 풀 테스트 + ktlint/detekt 그린.

## Plan 메타

- task 수: 6
- 의존 그래프: T1[], T2[], T3[1], T4[2,3], T5[4], T6[5]
- 예상 wave: 5 (Wave1=T1·T2 병렬, Wave2=T3, Wave3=T4, Wave4=T5, Wave5=T6) — layered 구조라 대부분 직렬(메모리: 같은 모듈 test 컴파일도 직렬화 요인)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 신규 외부 의존성: io.minio:minio + org.testcontainers:minio (Maxi 승인 2026-06-15)
- 추가 검증: ktlint, detekt(aggregate), jOOQ codegen 정합, init_codegen 미러

## 리뷰 결과 (← /bts-review-plan 채움)
