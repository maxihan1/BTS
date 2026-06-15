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

## Plan

> 전 task frontend(백엔드 변경 0). TDD red→green→refactor 강제. 단위는 `downloadAttachment`를 `vi.mock`으로 Blob 반환(MSW 비의존), E2E(Task 4)만 MSW 실바이트.

### Task 1. 미리보기 화이트리스트 헬퍼 (isPreviewable + previewCategory)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/attachment-preview.ts`, `apps/web/src/lib/attachment-preview.test.ts`]
- depends-on: []

**RED**: `attachment-preview.test.ts`
- `isPreviewable('image/png'|'image/jpeg'|'image/gif'|'image/webp'|'application/pdf'|'video/mp4'|'video/webm')` === true (7종)
- `isPreviewable('image/svg+xml'|'text/html'|'application/zip'|'application/octet-stream')` === false
- `previewCategory('image/png')` === `'image'`, `previewCategory('application/pdf')` === `'pdf'`, `previewCategory('video/mp4')` === `'video'`, 비화이트리스트 === `null`
- 실패: `attachment-preview` 모듈 없음

**GREEN**: `attachment-preview.ts`
- `PREVIEWABLE_MIME` 맵(MIME → category) `as const` 단일 출처
- `isPreviewable(contentType): boolean`, `previewCategory(contentType): 'image'|'pdf'|'video'|null`

**REFACTOR**: 파일 L1 한국어 헤더 주석, JSDoc, 카테고리 타입 export(`PreviewCategory`)

**검증**: `pnpm --filter web test attachment-preview`

### Task 2. AttachmentPreviewModal 컴포넌트 + i18n 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/AttachmentPreviewModal.tsx`, `apps/web/src/components/issue/AttachmentPreviewModal.test.tsx`, `apps/web/src/i18n/attachment-labels.ts`]
- depends-on: [1]

**RED**: `AttachmentPreviewModal.test.tsx` (`vi.mock('@/api/attachments')`로 `downloadAttachment` → Blob)
- **objectURL mock(C3)**: `URL` 전체 교체 금지(URL.parse 등 소실). `vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn().mockReturnValue('blob:x'), revokeObjectURL: vi.fn() })` 또는 `Object.defineProperty`(MfaSettings.test 선례)로 **두 메서드만** 교체.
- image 첨부 + open=true → `<img>` 렌더 (alt=filename)
- pdf → `<iframe>` 렌더 (sandbox 속성, src=objectURL)
- video → `<video controls>` 렌더
- 로딩 중 → 로딩 표시 / 다운로드 reject → 에러 표시
- 닫기(onOpenChange false) → `revokeObjectURL` 호출
- **G4/C1 prop 전환**: open=true 유지한 채 `attachment` prop이 바뀌면 → 이전 objectURL `revokeObjectURL` 호출 후 새 blob 로드 (연속 미리보기 누수 차단)
- 언마운트 → cleanup에서 `revokeObjectURL` 호출
- 실패: `AttachmentPreviewModal` 없음

**GREEN**: `AttachmentPreviewModal.tsx`
- `import { Dialog as DialogPrimitive } from 'radix-ui'` (기존 CloneIssueDialog 패턴)
- props: `{ issueKey, attachment(AttachmentResponse), open, onOpenChange }`
- `useEffect` deps=`[open, attachment.id]` → `downloadAttachment` → `URL.createObjectURL(blob)` → state. **cleanup에서 직전 objectURL `revokeObjectURL`**(G4/C1 — open 유지 prop 전환·진행 중 닫힘·언마운트 모두 한 경로로 해제). 진행 중 비동기 완료가 언마운트 후 setState 안 하도록 `ignore` ref 가드.
- **blob type(C2)**: 별도 type 재지정 안 함(blob.type은 이미 서버 Content-Type). 렌더러는 `previewCategory(attachment.contentType)`로만 선택 — 화이트리스트가 1차 방어, iframe sandbox가 2차. `downloadAttachment` 수정 불요.
- `previewCategory(attachment.contentType)` 분기: image→`<img>`, pdf→`<iframe sandbox>`, video→`<video controls>`
- 로딩/에러 상태 UI
- i18n 라벨 추가(콜론 종결 금지): `previewButton`, `previewTitle(filename)`, `previewLoading`, `previewError`, `previewClose`

**REFACTOR**: 파일 L1 한국어 헤더, JSDoc, blob type 명시(`new Blob([buf],{type})` 불요 — fetch blob이 이미 Content-Type 보유, 단 안전상 category 기준 렌더만)

**검증**: `pnpm --filter web test AttachmentPreviewModal` + `pnpm --filter web test ko` (콜론 검증)

### Task 3. AttachmentSection/Row 미리보기 버튼 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/AttachmentSection.tsx`, `apps/web/src/components/issue/AttachmentSection.test.tsx`]
- depends-on: [1, 2]

**RED**: `AttachmentSection.test.tsx`
- image/png 첨부 행 → "미리보기" 버튼 렌더
- application/zip 첨부 행 → "미리보기" 버튼 미렌더 (다운로드만)
- 미리보기 버튼 클릭 → 모달 open (selected attachment 전달)
- 기존 업로드/다운로드/삭제 테스트 회귀 0

**GREEN**: `AttachmentSection.tsx`
- `AttachmentRow`에 `isPreviewable(attachment.contentType)` 게이팅 "미리보기" 버튼 추가
- 미리보기 모달 open 상태 + selected attachment state(섹션 또는 행 수준), `AttachmentPreviewModal` 배선
- 기존 다운로드/삭제 버튼·레이아웃 보존

**REFACTOR**: 버튼 그룹 정리, aria-label(파일명 포함)

**검증**: `pnpm --filter web test AttachmentSection`

### Task 4. E2E + MSW 바이트 서빙 확장

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/mocks/attachment-handlers.ts`, `apps/web/e2e/issue-attachment-preview.spec.ts`]
- depends-on: [3]

**RED→GREEN** (E2E는 실패→통과 시나리오 기준):
- MSW download 핸들러(G1) 확장 — 시드 첨부별 Content-Type + 실제 더미 바이트(작은 png/pdf/**mp4**) 반환. 기존 stateful 시드 패턴(`X-MSW-Seed-Attachment`) 유지.
- **C4 회귀 가드**: 기존 download 핸들러의 단위 테스트 경로(`mock-content` blob 응답)는 **불변**으로 유지. 미리보기용 실바이트는 미리보기 시드 첨부에 한정한 **additive 분기**로 추가 → Task 3 `AttachmentSection.test.tsx` 회귀 0.
- `issue-attachment-preview.spec.ts`: S1 이미지 미리보기 모달(`<img>` 표시) / S2 PDF(`<iframe>`) / S3 동영상(`<video>`, **video/mp4로 한정 — C6: Playwright Chromium 번들 webm 코덱 불확실, mp4만 사용**) / S4 화이트리스트 밖(zip) 버튼 미노출 / S6 다운로드 실패 에러 / S7 Esc 닫기.
- **C5 S5 생략 사유**: S5(권한 없는 사용자 403·목록 진입 불가)는 기존 FR-AC-01 `issue-attachments.spec.ts`/권한 E2E가 이미 커버하므로 FR-AC-02 E2E에서 생략.
- G3: PDF iframe sandbox 실렌더 확인(깨지면 sandbox 완화).
- 기존 `issue-attachments.spec.ts`(FR-AC-01) 회귀 0 동반 실행.

**검증**: `pnpm --filter web test:e2e issue-attachment-preview` + `pnpm --filter web test:e2e issue-attachments`

## Plan 메타

- task 수: 4 (전 TDD 사이클)
- 의존성 체인: 1 → 2 → 3 → 4 (대부분 직렬, frontend 단일 영역). 예상 wave 4
- 예상 시간: 약 12~16분
- TDD 강제: yes (단위 red→green, E2E 시나리오)
- 백엔드 변경: 0 (D4/D5 해당 없음 — Maxi D-1 결정)
- 추가 검증: typecheck, lint, vitest, playwright, `pnpm verify`, package.json diff 0(의존성 추가 없음)

## 리뷰 결과

### 독립 eng 리뷰 (frontend-engineer, 2026-06-15)

- **BLOCKER: 없음.**
- **CONCERN 6건 — 전부 반영 완료**.
  - C1. 연속 미리보기(open 유지+attachment prop 전환) 시 이전 objectURL revoke 누락 → Task 2 RED/GREEN에 `useEffect deps=[open, attachment.id]` + cleanup revoke 명시.
  - C2. "blob type 재지정으로 위조 차단" 서술 부정확(blob.type=서버 Content-Type) → spec §2 방어층 정확화(1차 화이트리스트 게이팅 + 2차 iframe sandbox), Task 2 GREEN에 type 재지정 안 함 명시.
  - C3. objectURL mock으로 `URL` 전체 교체 시 URL.parse 등 소실 → Task 2 RED에 `{...URL, createObjectURL, revokeObjectURL}` 부분 교체 가이드(MfaSettings.test 선례).
  - C4. MSW download 핸들러 변경이 단위 테스트 회귀 유발 위험 → Task 4에 기존 `mock-content` 경로 불변 + 미리보기 시드 한정 additive 분기 명시.
  - C5. E2E S5(권한) 누락 → Task 4에 "기존 FR-AC-01 권한 E2E가 커버, 생략" 사유 명시.
  - C6. video/webm Playwright Chromium 코덱 불확실 → S3을 video/mp4로 한정.
- **잘된 점**. blob type+iframe sandbox 이중 방어층, G4 누수 가드 선제, classify 정정+단일 직렬 wave로 race 위험 최소.

> autoplan 4종 대신 eng 집중 독립 리뷰 1회(메모리 `bts-review-plan-autoplan-overkill`). 보안 민감(인라인 렌더 XSS) 차원이 design보다 핵심이라 eng 관점 채택.
