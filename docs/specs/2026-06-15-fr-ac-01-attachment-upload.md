# FR-AC-01 첨부 업로드 — 스펙

> BC: issue-tracking · 작성: 2026-06-15 · ADR: [docs/adr/2026-06-15-fr-ac-01-attachment-storage.md](../adr/2026-06-15-fr-ac-01-attachment-storage.md)
> 범위(Maxi 확정): 업로드 + 다운로드 + 목록 + 삭제. FR-AC-02(미리보기)는 별도 FR 제외.

## 사용자 시나리오 (Given-When-Then)

1. **업로드**
   - Given: 사용자가 `ATLAS-1` 이슈에 EDIT_ISSUE(UPDATE) 권한이 있다.
   - When: 100MB 이하 파일을 multipart로 `POST /api/v1/issues/ATLAS-1/attachments`에 올린다.
   - Then: MinIO에 객체가 저장되고 `issue_attachments` row가 생성되며 201 + 첨부 메타(AttachmentResponse)를 받는다.

2. **목록**
   - Given: 사용자가 `ATLAS-1`에 VIEW_ISSUE 권한이 있다.
   - When: `GET /api/v1/issues/ATLAS-1/attachments`.
   - Then: 해당 이슈의 첨부 메타 목록(업로드 시각 내림차순)을 200으로 받는다.

3. **다운로드**
   - Given: VIEW_ISSUE 권한 보유.
   - When: `GET /api/v1/issues/ATLAS-1/attachments/{id}`.
   - Then: MinIO에서 객체를 스트리밍 받아 원본 파일명·MIME으로 200 다운로드. `Content-Disposition: attachment`(인라인 실행 방지).

4. **삭제 (하드)**
   - Given: EDIT_ISSUE(UPDATE) 권한 보유.
   - When: `DELETE /api/v1/issues/ATLAS-1/attachments/{id}`.
   - Then: MinIO 객체와 DB row가 함께 제거되고 204를 받는다.

5. **권한 거부**
   - Given: 사용자가 해당 이슈에 권한이 없다.
   - When: 위 동작을 시도한다.
   - Then: 403 (업로드/삭제=UPDATE 미보유, 목록/다운로드=VIEW 미보유).

## 기능 요구사항 (FR)

- FR-AC-01-1. 멀티파트 파일 업로드 — 서버 경유 MinIO 스트리밍. 메타(filename, content_type, size_bytes, storage_key, uploaded_by, created_at) 영속.
- FR-AC-01-2. 이슈별 첨부 목록 조회.
- FR-AC-01-3. 첨부 단건 다운로드 — MinIO 스트리밍, 원본 파일명·MIME 복원.
- FR-AC-01-4. 첨부 하드 삭제 — DB row + MinIO 객체 제거.

## 비기능 요구사항 (NFR)

- NFR-1. 파일 크기 상한 **100MB/파일**. 초과 시 거부(413).
- NFR-2. 업로드/다운로드는 **스트리밍** — 100MB 파일을 전체 메모리에 적재하지 않는다(`InputStream` 기반 `putObject`/`getObject`).
- NFR-3. 다운로드 응답은 `Content-Disposition: attachment; filename="..."` — 브라우저 인라인 실행/XSS 방지. 파일명은 RFC 5987 인코딩(한글/특수문자).
- NFR-4. MinIO 자격증명·endpoint·bucket은 설정 외부화(application.yml + 환경변수). prod 기본값 하드코딩 금지.
- NFR-5. storage_key는 충돌 불가능하게 `issues/{issueId}/{attachmentId}` 형식(attachmentId는 UUID).
- NFR-6. **bucket 보장** — 앱 기동 시 대상 bucket이 없으면 생성(`bucketExists` → `makeBucket`)하거나, 부재 시 명확한 503. dev/test는 자동 생성, prod는 운영 사전 생성 가정 + 부재 시 fail-fast. bucket명 설정 외부화.

## API 인터페이스 (REST)

별도 컨트롤러 `IssueAttachmentController` (`/api/v1/issues/{key}/attachments`). 기존 IssueController(550줄+) 비대화 방지(메모리: 별도 컨트롤러 분리 선례).

| 메서드 | 경로 | 권한 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/v1/issues/{key}/attachments` | UPDATE | `multipart/form-data` (part name=`file`) | 201 + `DataResponse<AttachmentResponse>` + Location |
| GET | `/api/v1/issues/{key}/attachments` | VIEW | — | 200 + `DataResponse<List<AttachmentResponse>>` |
| GET | `/api/v1/issues/{key}/attachments/{id}` | VIEW | — | 200 + 바이너리 스트림(`Content-Type`, `Content-Disposition`, `Content-Length`) |
| DELETE | `/api/v1/issues/{key}/attachments/{id}` | UPDATE | — | 204 |

`AttachmentResponse` = { id: UUID, filename: String, contentType: String, sizeBytes: Long, uploadedBy: UUID, createdAt: Instant }. (storage_key는 내부 전용 — 응답 비노출.)

actor 추출 = `CurrentActor.current()`. 권한 검증 = application service `assertPermission(actor, IssuePermission.UPDATE|VIEW, IssueScope.Issue(key))`.

## 데이터 모델 변경

Flyway **V023** `issue_attachments` (issue-tracking 모듈). 컬럼은 ADR §6 표 따름(UUID PK/FK, `deleted_at` 없음, `issue_id` FK ON DELETE CASCADE). 인덱스: `(issue_id)` — 목록 조회용. jOOQ codegen 미러(`init_codegen.sql`) 필수(메모리: 컬럼 추가는 init_codegen에도 미러).

## 엣지 케이스

- 100MB 초과 → 413 (Spring `MaxUploadSizeExceededException` → 핸들러 매핑). max-request-size는 file-size보다 약간 크게.
- 0 byte 파일 / part 누락(`file` 없음) → 400.
- 존재하지 않거나 소프트 삭제된 이슈 → 404 (actor 추출 후 이슈 조회 — 메모리: 인증 추출을 리소스 조회보다 먼저).
- `{id}`가 다른 이슈 소속이거나 없음 → 404 (issue_id 불일치 = 존재 probe 방지).
- **업로드 트랜잭션 정합**: MinIO `putObject` 성공 → DB INSERT. DB INSERT 실패 시 MinIO 객체 best-effort 보상 삭제(고아 방지). (DB-first는 commit 후 MinIO put 실패 시 메타만 남는 더 나쁜 상태라 회피.)
- **삭제 트랜잭션 정합**: row 조회 → MinIO 객체 제거 → DB DELETE. MinIO 제거 실패는 로그 후 진행(고아 객체 < 좀비 메타). 권한/순서는 plan에서 확정.
- MinIO 연결 불가(업로드/다운로드) → 502/503. catch가 권한예외를 삼키지 않도록(메모리: best-effort catch 권한예외 가림 / catch-all이 ResponseStatusException 삼킴 주의).

## 제약 조건

- 한 PR = 한 BC(issue-tracking). MinIO 클라이언트 의존성·설정·Testcontainers는 issue-tracking 모듈 내. 인프라(docker-compose.dev.yml MinIO)는 공용 infra.
- 신규 외부 의존성 `io.minio:minio`(Maxi 승인 2026-06-15) + `org.testcontainers:minio`(test).
- IssuePermission enum 무변경(UPDATE/VIEW 재사용).

## 측정 가능한 완료 기준

- 4 엔드포인트 통합테스트(Testcontainers MinIO + Postgres): 업로드→목록→다운로드(바이트 동일성)→삭제 라운드트립 그린.
- 100MB 경계: 100MB 성공 / 초과 413.
- 권한: UPDATE 없는 actor 업로드/삭제 403, VIEW 없는 actor 목록/다운로드 403.
- 교차 이슈 `{id}` → 404. 소프트 삭제 이슈 업로드 → 404.
- 삭제 후 MinIO 객체·DB row 모두 부재 확인.
- ktlint/detekt 그린, jOOQ codegen 정합.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). 발견·보강: MinIO bucket 보장 정책 누락 → NFR-6 추가. 기존 메모리 함정 사전 반영(인증 추출 선행, catch-all 권한예외 가림, init_codegen 미러, 별도 컨트롤러 분리, 교차 이슈 404 probe 방지).
