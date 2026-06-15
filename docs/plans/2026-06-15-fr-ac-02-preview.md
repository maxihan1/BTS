# FR-AC-02 첨부 미리보기 (이미지/PDF/동영상)

> slug: fr-ac-02-preview
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-02 첨부파일 미리보기 기능 구현 (이미지/PDF/동영상 인라인 미리보기).
선행 FR-AC-01(#145 백엔드 + #146 프론트/E2E) — 서버 경유 멀티파트 스트리밍 업로드/다운로드/목록/삭제 완료.

classify 결과. type=backend, agent=backend-engineer, primary_bc=issue-tracking.

**핵심 설계 긴장점 (spec에서 해소 필요)**.
- product 문서 D4는 "Presigned GET URL"로 표기되어 있으나, FR-AC-01(#145)이 이미 presigned URL을 폐기하고 "서버 경유 스트리밍"으로 deviation(권한 일원화·고아 객체 회피). FR-AC-02도 같은 deviation 일관성 필요 가능성.
- FR-AC-01 다운로드는 `Content-Disposition: attachment`로 인라인 실행을 의도적으로 차단. 미리보기는 정반대로 브라우저 인라인 렌더링 필요 → 보안(특히 SVG/HTML XSS) 재검토 필수.

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: `Attachment` (FR-AC-01 재활용, 신규 엔티티 0). `id/issueId/filename/contentType/sizeBytes/storageKey/uploadedBy/createdAt`.
- **신규 용어**: 없음. "어테처/Attachment" 이미 glossary 등재. "미리보기(preview)"는 도메인 개념이 아닌 표현(presentation) 관심사.
- **기존 결정 충돌**: 없음. ADR `2026-06-15-fr-ac-01-attachment-storage.md`가 미리보기를 명시적으로 FR-AC-02로 분리·위임(line 36/38/85). 미리보기에 대한 사전 결정 없음 → FR-AC-02가 첫 결정.
- **재활용 자산 (FR-AC-01)**:
  - 백엔드: `IssueAttachmentController` (download 엔드포인트 `GET /api/v1/issues/{key}/attachments/{id}`가 이미 바이트 스트림 + `Content-Type` 반환, 단 `Content-Disposition: attachment`), `IssueAttachmentService.download`, `AttachmentStoragePort.get`.
  - 프론트: `downloadAttachment(key, id) → Blob` (apiFetch 경유, 인증/세션 처리 완료), `AttachmentSection.tsx`, `attachment-handlers.ts`(MSW), `attachmentResponseSchema`(`contentType` 포함).
- **관련 ADR**: 없음 (FR-AC-02가 미리보기 첫 ADR 후보 — 서버경유 vs presigned, 인라인 렌더 보안 정책).

### spec에서 해소할 갈림길 (도메인 아님 — 아키텍처·보안)
1. **전송 방식**: presigned GET URL(product 문서 D4 표기) vs 서버경유(FR-AC-01 일관성). FR-AC-01이 이미 presigned 폐기 → 서버경유 유력.
2. **렌더 경로**: 기존 download 엔드포인트 blob 재사용(`URL.createObjectURL`) vs `Content-Disposition: inline` 신규 엔드포인트 vs `<img/video src>` 직접 URL(Range 요청).
3. **MIME 화이트리스트**: 인라인 렌더 허용 타입 한정(image/*, application/pdf, video/mp4 등). SVG/HTML 인라인 = XSS 위험 → 차단 또는 안전 컨텍스트.
4. **의존성**: product D6 "react-pdf + video.js" vs FR-AC-01 정신("의존성 0" 네이티브 HTML5). DEVELOPMENT.md §17 신규 의존성 Maxi 승인 필요.
5. **동영상 100MB**: blob 전체 로드(메모리 부담) vs Range 요청 스트리밍(`<video src>` 직접).

## 스펙

전체 스펙. [docs/specs/2026-06-15-fr-ac-02-preview.md](../specs/2026-06-15-fr-ac-02-preview.md)

핵심 3줄 요약.
- 기존 download 엔드포인트 Blob을 `URL.createObjectURL`로 받아 네이티브 HTML5(`<img>`/`<iframe>`/`<video>`)로 인라인 미리보기 (백엔드 신규 0, 의존성 0).
- 미리보기 허용 MIME 화이트리스트(image 4종 + application/pdf + video/mp4·webm)만 미리보기 버튼 노출. SVG/HTML 제외(XSS).
- radix Dialog 모달, 닫기 시 objectURL revoke(메모리 누수 0).

**Maxi 확정 결정 3건**. D-1 blob 재사용(presigned 폐기) · D-2 네이티브 HTML5(react-pdf/video.js 폐기) · D-3 MIME 화이트리스트.
**classify 정정**. type=backend로 판정됐으나 백엔드 변경 0 → 실질 frontend. task는 frontend-engineer + qa(E2E)로 dispatch.

## Brainstorming Check

✅ 통과 (1회 self sanity-check). gap 4건(G1 MSW 바이트 서빙 · G2 jsdom objectURL mock · G3 PDF iframe sandbox 검증 · G4 로딩 중 닫기 가드) 모두 구현 수준 보강, scope 변경 없음. spec §11 반영.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
