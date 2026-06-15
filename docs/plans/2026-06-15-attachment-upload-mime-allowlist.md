# 첨부 업로드 MIME 화이트리스트 검증

> slug: attachment-upload-mime-allowlist
> type: backend (보안 — 입력 검증)
> agent: backend-engineer (codereview 보안 관점)
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-01 D2 미완분 처리(Maxi 확정). D2는 크기 제한(100MB, 완료) / MIME 화이트리스트(미완) / ClamAV(미완·범위 밖)로 구성. 이 작업은 **업로드 MIME 화이트리스트**만 — 허용 타입만 업로드 허용, 나머지 거부(415 등). ClamAV는 별도 후속.

업로드 경로. `IssueAttachmentController.upload` / `IssueAttachmentService.upload` (`POST /api/v1/issues/{key}/attachments`).

**spec에서 확정할 정책 결정(핵심)**.
1. 화이트리스트 vs 차단리스트(denylist), 허용 타입 집합(협업 도구라 광범위 vs 보안 우선 협소).
2. 클라이언트 Content-Type 신뢰(위조 가능, 의존성 0) vs 내용 기반 탐지(magic bytes/Tika, 의존성 필요).
3. 거부 HTTP 상태(415 Unsupported Media Type 유력).
4. 확장자 병행 검증 여부.

## 도메인 정리

- BC: issue-tracking. 영향: `IssueAttachmentService.upload` / `IssueAttachmentController.upload`. 신규 엔티티 0(Attachment 재활용).
- 신규 용어 0. 기존 결정 충돌 0. ADR 없음(정책 결정은 spec 인라인 + 본 plan 기록).
- 재활용: `AttachmentExceptionHandler`(415 매핑 추가), FR-AC-02 `attachment-preview.ts`의 essence MIME 정규화 교훈(lowercase+파라미터 strip) 백엔드에도 동일 적용.

## 스펙

전체 스펙. [docs/specs/2026-06-15-attachment-upload-mime-allowlist.md](../specs/2026-06-15-attachment-upload-mime-allowlist.md)

핵심 3줄.
- 업로드 시 essence(Content-Type)와 확장자를 **각각 독립적으로 allowlist** 대조(octet-stream/빈값은 확장자에 위임) → 둘 다 통과해야 허용, 아니면 415.
- 광범위 화이트리스트(이미지·PDF·오피스·텍스트/CSV·아카이브·동영상/오디오), 실행파일·HTML·SVG·스크립트 차단. 의존성 0.
- 검증은 권한 검증 직후·MinIO put 이전. ClamAV는 범위 밖(별도 후속).

**Maxi 확정**. 탐지=Content-Type+확장자 allowlist(의존성0) · 모델=광범위 화이트리스트.

## Brainstorming Check

✅ self sanity-check. 오탐(docx-as-zip) 회피를 독립 allowlist+octet 위임으로 해소. 내용 위조 미탐지 한계는 Maxi 선택(의존성0), 다운로드 nosniff/attachment·미리보기 blob가 실행 차단.

## Plan

### Task 1. AttachmentTypePolicy — allowlist 상수 + isAllowed

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/AttachmentTypePolicy.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/AttachmentTypePolicyTest.kt`]
- depends-on: []

**RED**: `AttachmentTypePolicyTest`
- `isAllowed("image/png", "a.png")`=true, `("application/zip","report.docx")`=true, `("application/octet-stream","report.docx")`=true
- `("text/html","evil.html")`=false, `("image/svg+xml","x.svg")`=false, `("application/x-msdownload","a.exe")`=false
- `("image/png","evil.html")`=false(ext 차단), `("application/pdf","noext")`=false
- 대문자/파라미터 정규화: `("IMAGE/PNG; q=1","A.PNG")`=true

**GREEN**: `AttachmentTypePolicy` object — `ALLOWED_MIME: Set<String>`, `ALLOWED_EXT: Set<String>`(spec §2/§3), `fun isAllowed(contentType: String, filename: String): Boolean`(essence 정규화 + octet 위임 규칙). 파일 L1 한국어 헤더, KDoc.

**검증**: `./gradlew :modules:issue-tracking:test --tests '*AttachmentTypePolicyTest*'`

### Task 2. 서비스 배선 + 예외 + 415 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/IssueAttachmentService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/application/UnsupportedAttachmentTypeException.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/web/AttachmentExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentServiceTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentControllerTest.kt`]
- depends-on: [1]

**RED**:
- 서비스 테스트 — 차단 타입 upload 시 `UnsupportedAttachmentTypeException` + `storagePort.put`/`attachmentRepository.insert` **미호출**(verify exactly=0). 허용 타입은 기존대로 통과.
- 컨트롤러 테스트 — 차단 시 **415** + 에러코드(`ATTACHMENT_UNSUPPORTED_TYPE`).

**GREEN**:
- `UnsupportedAttachmentTypeException`(message에 허용형식 일반 안내, 내부정보 비노출).
- `IssueAttachmentService.upload`: `checkPermission` 직후 `AttachmentTypePolicy.isAllowed` 검증 → 위반 시 예외(put/insert 이전).
- `AttachmentExceptionHandler`: 예외 → 415 ProblemDetail/에러코드. 기존 핸들러 스코프 유지(타 응답 변질 금지).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueAttachmentServiceTest*' --tests '*IssueAttachmentControllerTest*'`

### Task 3. 통합 테스트 (Testcontainers MinIO)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentIntegrationTest.kt`]
- depends-on: [2]

**RED→GREEN**: 허용(png) 201 + 버킷 오브젝트 1 / 차단(html) 415 + 버킷 오브젝트 0(고아 미발생) end-to-end. 기존 통합 시나리오 회귀 0.

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueAttachmentIntegrationTest*'`

## Plan 메타
- task 수: 3 (직렬 1→2→3), 전 backend-engineer, 단일 모듈
- TDD 강제: yes. 의존성 추가 0. 마이그레이션 0.
- codereview 보안 관점 필수(입력 검증)

## 리뷰 결과 (fast-track 아님 — 단, backend 보안이라 eng 집중 독립 리뷰는 plan 단계 생략하고 codereview에서 강하게)
