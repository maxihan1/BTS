# 이슈 첨부 저장 모델 (FR-AC-01)

> 상태: Accepted
> 날짜: 2026-06-15
> BC: issue-tracking
> 관련 FR: FR-AC-01 (첨부 업로드, 최대 100MB/파일)
> 관련: SDD §4.2.1 (아키텍처 — MinIO 첨부), §05.7 Attachment 테이블, §16 인프라(MinIO), domain/issue-tracking.md (Attachment 핵심 엔티티, "소프트 삭제 우선" 규칙)

## 맥락

이슈에 파일을 첨부하는 첫 구현(FR-AC-01). `domain/issue-tracking.md`는 `Attachment`를 핵심 엔티티로, SDD §4.2.1·§16은 첨부 저장소로 MinIO를 못 박아 이 기능을 예견했다. 그러나 조사 결과 실제 코드에는 첨부 관련 인프라가 전무했다.

조사로 드러난 제약.
- backend에 `Attachment` 클래스 0건 → 첫 구현.
- gradle에 MinIO/S3 클라이언트 의존성 없음 → **신규 외부 의존성 필요**(DEVELOPMENT.md §외부 의존성 — Maxi 승인 대상).
- dev/test 인프라(docker-compose.dev.yml, Testcontainers)에 MinIO 없음 → 새로 구성.
- `issues` 테이블 PK는 **UUID**(`gen_random_uuid()`)다. SDD §05.7이 표기한 `BIGINT`는 코드와 어긋난 stale 표기 → deviation.
- SDD §11(API 설계)에 첨부 API 정의 없음 → spec에서 신규 설계.

## 결정

### 1. 저장소 백엔드 — MinIO + MinIO Java SDK

첨부 바이너리는 PostgreSQL이 아닌 MinIO(오브젝트 스토리지)에 저장한다. 클라이언트 라이브러리는 **MinIO Java SDK(`io.minio:minio`)**를 신규 도입한다(Maxi 승인 2026-06-15).

근거. (a) SDD §4.2.1·§16의 정본 결정(MinIO)과 직접 일치, (b) AWS S3 SDK도 S3 호환으로 작동하나 endpoint override 설정이 번거롭고 SDD 표기와 어긋남, (c) 큰 바이너리를 RDB BLOB로 두면 백업/덤프 비대화 — SDD §16이 "Object 500GB = 첨부+백업"으로 분리 설계.

### 2. 업로드 방식 — 서버 경유 멀티파트 스트리밍

`client → Spring(multipart) → MinIO`. 서버가 멀티파트 요청을 받아 MinIO로 **스트리밍**(`putObject(InputStream, size, partSize)`)한다. 100MB 파일도 전체를 메모리에 적재하지 않는다.

근거. (a) 권한 검증(이슈 접근 권한)을 서버 한 곳에서 일원화, (b) presigned URL 직접 업로드는 presign→PUT→confirm 3-step이라 confirm 누락 시 고아 객체 발생 — 1K 사용자·단일 호스트 규모에 과한 복잡도, (c) Spring multipart의 `max-file-size`/`max-request-size`로 크기 게이트를 표준 경로에서 강제.

### 3. 구현 범위 — 업로드 + 다운로드 + 목록 + 삭제

FR-AC-01 문구는 "업로드"만 명시하나, 업로드만으로는 실사용 불가하므로 **다운로드·목록·삭제**까지 한 PR에 포함한다(Maxi 확정 2026-06-15). **FR-AC-02(이미지/PDF/동영상 미리보기)는 별도 FR로 제외.**

근거. 첨부 한 사이클(올리고/받고/보고/지움)이 완결돼야 production 기준을 충족. 미리보기(인라인 렌더/썸네일)는 별도 관심사라 FR-AC-02로 분리.

### 4. 데이터 모델 — UUID PK/FK, 소프트 삭제

`issue_attachments` 테이블(Flyway V023). 컬럼은 SDD §05.7 따르되 PK/FK는 `issues`와 정합되게 **UUID**.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID PK | `gen_random_uuid()` |
| issue_id | UUID FK → issues(id) | 대상 이슈 |
| filename | VARCHAR(500) | 원본 파일명 |
| content_type | VARCHAR(100) | MIME 타입 |
| size_bytes | BIGINT | 크기(바이트) |
| storage_key | VARCHAR(500) | MinIO 객체 키 |
| uploaded_by | UUID | 업로더 사용자 ID |
| created_at | TIMESTAMPTZ | 업로드 시각 |
| deleted_at | TIMESTAMPTZ NULL | 소프트 삭제 시각 |

근거. (a) issues PK가 UUID라 FK 정합, (b) domain "DELETE는 소프트 삭제 우선" 규칙 → `deleted_at`. MinIO 객체는 삭제 시 즉시/지연 정리는 spec에서 확정. (c) SDD §05.7의 `uploaded_by`/`size_bytes`/`mime_type`/`storage_key`를 코드 컨벤션(`content_type` 등)으로 매핑.

## Deviation 기록

- SDD §05.7 Attachment 테이블 `id/issue_id BIGINT` → 실제 `UUID`(issues 정합). SDD 표기가 stale.
- SDD §11에 첨부 API 미정의 → 본 작업에서 신규 설계(spec §API).

## 결과

- 신규 외부 의존성: `io.minio:minio` (gradle version catalog 등재).
- 신규 인프라: docker-compose.dev.yml MinIO 서비스 + Testcontainers MinIO 컨테이너.
- 신규 마이그레이션: V023 `issue_attachments`.
- FR-AC-02(미리보기)는 후속 FR로 남김.
