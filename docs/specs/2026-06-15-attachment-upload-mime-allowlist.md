# 첨부 업로드 MIME 화이트리스트 검증 — 스펙

> slug: attachment-upload-mime-allowlist · BC: issue-tracking · type: backend(보안 입력 검증)
> 작성: 2026-06-15 · FR-AC-01 D2 미완분(MIME 화이트리스트) 처리. ClamAV는 범위 밖(별도 후속).

## 0. 정책 결정 (Maxi 확정 2026-06-15)

- **탐지 방식**: 클라이언트 Content-Type + 파일 확장자를 allowlist와 대조. **의존성 0**(내용기반 Tika 미도입).
- **정책 모델**: 광범위 화이트리스트(이미지·PDF·오피스·텍스트/CSV·아카이브·동영상/오디오 허용, 실행파일·HTML·SVG·스크립트 제외).
- 위조 가능성은 다운로드 `Content-Disposition: attachment` + nosniff(#148) + 미리보기 blob 화이트리스트(FR-AC-02)가 이미 실행 위험을 차단하므로, 이 검증의 목적은 **저장소 오염(부적절·악성 파일 반입) 감소**다.

## 1. 검증 규칙 (오탐 회피 핵심)

업로드 1건에 대해.
1. `normMime` = essence(Content-Type) — 소문자 + `;` 이후 파라미터 제거. (FR-AC-02 `attachment-preview.ts` 정규화와 동일 원리, 백엔드 재구현)
2. `ext` = 파일명 마지막 `.` 이후 소문자 확장자. (없으면 빈 문자열)
3. **`mimeOk`** = `normMime ∈ ALLOWED_MIME` **또는** `normMime ∈ {"application/octet-stream", ""}`(브라우저 미판별 → unknown으로 보고 확장자에 위임).
4. **`extOk`** = `ext ∈ ALLOWED_EXT`.
5. **허용 = `mimeOk && extOk`**. 아니면 **415 거부**.

> **왜 "대응"이 아니라 "각자 독립 allowlist"인가**. docx/xlsx/pptx는 zip 컨테이너라 브라우저가 `application/zip`·`application/octet-stream`으로 보고하는 경우가 흔하다. "MIME과 확장자가 서로 대응(예: docx ↔ wordprocessingml)"을 요구하면 정상 업무 파일이 오탐 거부된다. 각자 독립 allowlist + octet-stream 위임이면 오탐 없이 위험 확장자/타입을 차단한다.

검증 사례.
- `report.docx` + `application/zip` → zip∈MIME✓, docx∈EXT✓ → 허용.
- `report.docx` + `application/octet-stream` → unknown→위임, docx∈EXT✓ → 허용.
- `evil.html` + `text/html` → html∉MIME, octet아님→mimeOk false → 거부(ext html도 ∉EXT).
- `evil.svg` + `image/svg+xml` → ∉MIME, ext svg∉EXT → 거부.
- `photo.png`을 `evil.html`로 rename + `image/png` → mimeOk✓이나 ext html∉EXT → 거부.
- `a.exe` + `application/x-msdownload` → ∉MIME, ext∉EXT → 거부.
- 확장자 없는 `report` + `application/pdf` → ext "" ∉EXT → 거부(명확 메시지, 사용자 rename 안내).

## 2. ALLOWED_MIME (essence 소문자)

```
이미지   image/jpeg image/png image/gif image/webp image/bmp image/tiff
문서     application/pdf
         application/msword
         application/vnd.openxmlformats-officedocument.wordprocessingml.document
         application/vnd.ms-excel
         application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
         application/vnd.ms-powerpoint
         application/vnd.openxmlformats-officedocument.presentationml.presentation
         application/vnd.oasis.opendocument.text
         application/vnd.oasis.opendocument.spreadsheet
         application/vnd.oasis.opendocument.presentation
텍스트   text/plain text/csv text/markdown application/json application/xml text/xml
아카이브 application/zip application/x-7z-compressed application/x-tar application/gzip
동영상   video/mp4 video/webm video/quicktime video/x-msvideo
오디오   audio/mpeg audio/wav audio/ogg audio/mp4 audio/webm
```

## 3. ALLOWED_EXT (소문자, `.` 제외)

```
jpg jpeg png gif webp bmp tif tiff
pdf
doc docx xls xlsx ppt pptx odt ods odp
txt log md csv json xml
zip 7z tar gz
mp4 webm mov avi
mp3 wav ogg m4a
```

**명시적 차단(allowlist 부재의 결과)**. html htm xhtml svg js mjs exe bat sh cmd com msi dll scr — MIME(text/html·image/svg+xml·application/javascript·application/x-msdownload 등)·확장자 모두 allowlist에 없어 거부.

## 4. 사용자 시나리오 (G-W-T)

- **S1 허용**. Given UPDATE 권한 사용자가 `image/png` `.png` 파일을. When `POST .../attachments`. Then 201 정상 업로드(기존 동작).
- **S2 차단(위험 타입)**. Given `text/html` `.html` 또는 `image/svg+xml` `.svg`. Then **415** + 에러코드, 저장 안 함(MinIO put·DB insert 모두 미발생).
- **S3 오탐 회피**. Given `report.docx` + `application/octet-stream`. Then 201 허용.
- **S4 확장자 위장**. Given `evil.html`을 `image/png`으로 위장. Then 415(ext 차단).
- **S5 권한 우선**. Given UPDATE 권한 없는 사용자. Then 403(기존 — 타입 검증보다 먼저, 존재/타입 probe 차단).

## 5. FR / NFR

- **FR-1** 업로드 시 §1 규칙으로 검증, 위반 시 415 + 명확한 에러코드/메시지(허용 형식 안내).
- **FR-2** 검증은 `IssueAttachmentService.upload`에서 **권한 검증 직후, MinIO put·DB insert 이전**(고아 객체·불필요 I/O 방지).
- **FR-3** allowlist는 단일 출처 상수(`AttachmentTypePolicy`)로 정의, 매직값 금지.
- **NFR-1** 의존성 0(Tika 등 미도입). **NFR-2** 기존 첨부 업로드/목록/다운로드/삭제·미리보기 회귀 0. **NFR-3** 100MB 스트리밍 경로 영향 0(검증은 메타데이터만, 바이트 미독).

## 6. API / 데이터 모델

- 엔드포인트 신규 0. `POST /api/v1/issues/{key}/attachments` 동작에 415 분기 추가.
- 거부 응답. **415 Unsupported Media Type** + `AttachmentExceptionHandler`에 신규 예외 매핑. 에러코드는 같은 BC 첨부 관례 따름(예: `ATTACHMENT_UNSUPPORTED_TYPE`, 메시지에 PII·내부정보 비노출).
- 데이터 모델 변경 0.

## 7. 측정 가능한 완료 기준

1. 단위(정책) — ALLOWED_MIME/EXT 표본 true, html/svg/exe/octet-only-bad-ext false, octet+good-ext true, mime-good+bad-ext false.
2. 서비스 — 위험 타입 업로드 시 예외 발생 + `storagePort.put`/`repository.insert` **미호출** 검증(mockk verify(exactly=0)).
3. 컨트롤러 — 415 + 에러코드 응답(MockMvc).
4. 통합(Testcontainers MinIO) — 허용 201 / 차단 415 end-to-end, 차단 시 버킷 오브젝트 0.
5. 기존 첨부 테스트(컨트롤러/서비스/통합/E2E) 회귀 0.
6. ktlint·detekt green, 의존성 추가 0.

## Brainstorming Check

✅ self sanity-check. 핵심 정책(탐지·모델) Maxi 사전 확정. 오탐(docx-as-zip) 회피를 독립 allowlist+octet 위임으로 해소. 잔여 한계 명시 — 내용 위조(png 바이트에 html) 미탐지는 Maxi 선택(의존성0)이며 다운로드 nosniff/attachment·미리보기 blob가 실행 차단. ClamAV는 범위 밖.
