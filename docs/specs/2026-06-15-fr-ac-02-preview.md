# FR-AC-02 첨부 미리보기 (이미지/PDF/동영상) — 스펙

> slug: fr-ac-02-preview · BC: issue-tracking · type: 실질 frontend (백엔드 신규 0)
> 작성: 2026-06-15 · 선행: FR-AC-01 (#145 백엔드 + #146 프론트/E2E)

## 0. 핵심 결정 (Maxi 확정 2026-06-15)

| # | 결정 | product 문서 대비 |
|---|---|---|
| D-1 | **전송 = 기존 download 엔드포인트 blob 재사용**. `GET /api/v1/issues/{key}/attachments/{id}` → `downloadAttachment()` Blob → `URL.createObjectURL`. 백엔드 신규 엔드포인트 0. | **deviation** — D4 "Presigned GET URL" 폐기 (FR-AC-01이 이미 presigned 폐기·서버경유 일관). |
| D-2 | **렌더 = 의존성 0 네이티브 HTML5**. 이미지 `<img>`, PDF `<iframe>`, 동영상 `<video controls>`. | **deviation** — D6 "react-pdf + video.js" 폐기 (FR-AC-01 "의존성 0" 기조, DEVELOPMENT.md §17). |
| D-3 | **미리보기 허용 MIME 화이트리스트**. 화이트리스트 밖 = 미리보기 버튼 미노출(다운로드만). SVG/HTML 인라인 제외(XSS). | FR-AC-01 D2가 후속으로 미룬 MIME 화이트리스트가 **미리보기 안전성으로 일부 흡수** (업로드 게이트는 여전히 후속). |

## 1. 범위

이슈 상세 페이지 `AttachmentSection`의 각 첨부 행에서, **미리보기 가능한 타입**에 한해 인라인 미리보기(모달)를 제공한다.

**범위 밖**. 썸네일 사전 생성, 업로드 시 MIME 화이트리스트 강제(FR-AC-01 D2 후속 유지), ClamAV 바이러스 스캔(인프라 후속), 이미지 갤러리/슬라이드쇼, PDF 페이지 네비게이션 커스텀 UI(브라우저 내장 뷰어 사용).

## 2. 미리보기 허용 MIME 화이트리스트 (보안 핵심)

```
이미지   image/jpeg, image/png, image/gif, image/webp
PDF      application/pdf
동영상   video/mp4, video/webm
```

- **image/svg+xml 제외** — SVG는 스크립트 실행 가능(XSS). `<img>` 컨텍스트에선 스크립트가 실행되지 않으나, 보수적으로 화이트리스트에서 배제.
- **text/html 제외** — 인라인 실행 위험.
- 판정 기준은 첨부 메타데이터의 `contentType`(서버가 업로드 시 저장, FR-AC-01 `AttachmentResponse.contentType`).
- 화이트리스트 밖 타입(application/zip, application/octet-stream 등)은 미리보기 버튼을 **렌더하지 않는다** → 기존 다운로드 버튼만.

**방어층 (정확한 모델 — C2 반영)**. 미리보기 blob은 `downloadAttachment()`의 `res.blob()`로 받으며, 이 blob의 `type`은 서버가 내려준 `Content-Type`(= 저장된 `contentType`)을 그대로 반영한다. 따라서 "blob type 재지정으로 위조를 막는다"는 것은 부정확하다 — 실질 방어선은 두 가지다.
1. **화이트리스트 게이팅(1차)** — 렌더러 선택을 `previewCategory(contentType)`로만 결정. SVG/HTML 등 스크립트 실행 가능 타입은 애초에 미리보기 진입 불가(버튼 미노출).
2. **렌더러별 격리(2차)** — 이미지는 `<img>`(스크립트 미실행 컨텍스트). PDF는 `<iframe>`로 브라우저 내장 PDF 뷰어 사용. 만약 공격자가 HTML을 `contentType=application/pdf`로 위장 업로드해도, blob URL은 선언 MIME(application/pdf)으로만 렌더되고 콘텐츠 스니핑하지 않으므로 PDF 뷰어가 파싱 실패할 뿐 HTML 스크립트는 실행되지 않는다(렌더 실패는 안전한 실패). `<video>`도 디코드 실패 시 안전.
- **G3 해소(구현 확정)**. 초기 `<iframe sandbox="">`는 브라우저 내장 PDF 뷰어를 막아 빈 미리보기가 되어(Task 4 E2E qa 확인) **sandbox 속성을 제거**했다. PDF의 XSS 방어는 1차 화이트리스트(text/html·svg는 애초에 미리보기 진입 불가)와 blob의 MIME-typed 렌더(스니핑 없음)로 충분하다. allow-scripts/allow-same-origin을 둘 다 줘야 뷰어가 동작하는 sandbox는 격리 효과가 없어 채택하지 않는다.

## 3. 사용자 시나리오 (Given-When-Then)

- **S1 이미지 미리보기**. Given VIEW 권한 사용자가 `image/png` 첨부가 있는 이슈를 본다. When 해당 행의 "미리보기"를 클릭한다. Then 모달이 열리고 이미지가 표시된다. 닫으면 objectURL이 해제된다.
- **S2 PDF 미리보기**. Given `application/pdf` 첨부. When 미리보기. Then 모달 안 `<iframe>`(브라우저 내장 PDF 뷰어)으로 표시된다.
- **S3 동영상 미리보기**. Given `video/mp4` 첨부. When 미리보기. Then 모달 안 `<video controls>`로 재생 가능하다.
- **S4 미리보기 불가 타입**. Given `application/zip` 첨부. Then 미리보기 버튼이 없고 다운로드만 가능하다.
- **S5 권한**. Given VIEW 권한 없는 사용자. Then 목록 자체가 403(기존 FR-AC-01 동작) — 미리보기 진입 불가.
- **S6 로딩/실패**. Given 미리보기 클릭 후 blob 다운로드 진행 중. Then 로딩 표시. When 다운로드 실패(네트워크/404). Then 모달 내 에러 메시지 + 닫기 가능.
- **S7 닫기/키보드**. Given 미리보기 모달 열림. When Esc 또는 배경 클릭 또는 닫기 버튼. Then 모달이 닫히고 objectURL이 해제된다(메모리 누수 0).

## 4. 기능 요구사항 (FR)

- **FR-1** 미리보기 허용 MIME 화이트리스트(§2)를 단일 출처로 정의한다(`isPreviewable(contentType)` 헬퍼).
- **FR-2** `AttachmentRow`는 첨부 `contentType`이 화이트리스트에 속할 때만 "미리보기" 버튼을 렌더한다.
- **FR-3** 미리보기는 기존 `downloadAttachment(key, id)` Blob을 받아 `URL.createObjectURL`로 렌더한다(신규 API 0).
- **FR-4** 렌더러는 MIME 카테고리(image/pdf/video)별로 분기한다.
- **FR-5** 모달 종료/언마운트 시 `URL.revokeObjectURL`로 objectURL을 해제한다.
- **FR-6** 미리보기 로딩 중 상태와 실패 상태(다운로드 에러)를 모달 내부에 표시한다.
- **FR-7** 모든 사용자 노출 문자열은 `attachment-labels.ts`에 추가한다(콜론 종결 금지, ko.test 대상).

## 5. 비기능 요구사항 (NFR)

- **NFR-1 의존성 0** — react-pdf/video.js 등 신규 의존성 금지. radix-ui Dialog(기존 사용 중) + 네이티브 HTML5 엘리먼트만.
- **NFR-2 접근성** — 모달 Esc 닫기, 배경 클릭 닫기, `<img alt={filename}>`, 포커스 트랩(radix Dialog 기본 제공).
- **NFR-3 메모리** — objectURL 누수 0(FR-5). 동영상은 최대 100MB blob 전체 로드 — 큰 파일 로딩 표시로 UX 완화(트레이드오프 수용, D-1 결정).
- **NFR-4 보안** — §2 화이트리스트 + blob type 명시 + PDF iframe sandbox.
- **NFR-5 회귀 0** — 기존 FR-AC-01 업로드/목록/다운로드/삭제 E2E·단위 테스트 무영향.

## 6. API 인터페이스

신규 **0**. 기존 `GET /api/v1/issues/{key}/attachments/{id}`(FR-AC-01) 재사용. VIEW 권한 가드/404 probe 차단/Content-Type 반환 모두 기존 동작 그대로.

## 7. 데이터 모델 변경

**0**. `issue_attachments` 테이블·`Attachment` 도메인·`AttachmentResponse` DTO 모두 변경 없음. `contentType` 컬럼을 화이트리스트 판정에 활용.

## 8. 엣지 케이스

- `contentType == application/octet-stream`(브라우저가 타입 미인식) → 화이트리스트 밖 → 미리보기 버튼 없음.
- 화이트리스트 타입이지만 실제 바이트가 손상/타입 불일치 → `<img>`/`<video>`는 렌더 실패(빈/깨짐, 안전), iframe PDF는 브라우저 뷰어가 처리. 에러 상태 표시로 완화.
- 동영상 코덱 브라우저 미지원(예: 일부 webm) → `<video>` 재생 불가 → 브라우저 기본 처리 + 다운로드 안내.
- 매우 큰 이미지/PDF/동영상 → 로딩 스피너 유지.
- 미리보기 중 다른 행 미리보기 클릭 → 기존 모달 objectURL 해제 후 새 모달.
- 다운로드 권한 도중 만료(403) → 에러 상태.

## 9. D 단계 매핑 (product 문서 §4.2.2)

| D | product 표기 | 본 작업 | 담당 |
|---|---|---|---|
| D1 | 도메인 | Attachment 재활용, 신규 0 | — |
| D2 | 명세 — MIME별 렌더러 | 본 spec (화이트리스트 §2 + 렌더러 분기 §4) | frontend |
| D3 | 데이터 모델 (활용) | 변경 0 | — |
| D4 | 백엔드 — Presigned GET URL | **deviation. 백엔드 신규 0**(기존 download 재사용) | — |
| D5 | 백엔드 테스트 | **해당 없음**(백엔드 변경 0) | — |
| D6 | 프론트 UI — react-pdf+video.js | **deviation. 네이티브 HTML5**(img/iframe/video) + radix Dialog | frontend-engineer |
| D7 | E2E | image/pdf/video 미리보기 + 화이트리스트 밖 버튼 미노출 | qa-engineer |

> **classify 정정**. classify가 type=backend로 판정했으나, Maxi 결정(D-1/D-2)으로 백엔드 변경이 0이 되어 **실질 frontend 작업**이다. plan task는 frontend-engineer(+E2E는 qa-engineer)로 dispatch한다.

## 10. 측정 가능한 완료 기준

1. `isPreviewable()` 단위 테스트 — 화이트리스트 7종 true, svg/zip/html/octet-stream false.
2. `AttachmentRow` 단위 — 화이트리스트 타입에만 미리보기 버튼 렌더.
3. 미리보기 모달 단위 — image→`<img>`, pdf→`<iframe>`, video→`<video>` 분기 렌더 + 닫기 시 revokeObjectURL 호출 검증.
4. E2E(D7) — S1(이미지)/S2(PDF)/S3(동영상)/S4(화이트리스트 밖 버튼 미노출)/S6(로딩·실패)/S7(Esc 닫기).
5. 기존 FR-AC-01 E2E·단위 회귀 0.
6. `pnpm verify`(lint + typecheck + test + build) 그린. 의존성 추가 0(package.json diff 없음).

## 11. 구현 유의 (Brainstorming 발견 — 4건, 모두 구현 수준 보강)

- **G1 E2E MSW 바이트 서빙**. `<img>`/`<video>`/`<iframe>` 실렌더(E2E chromium)는 MSW download 핸들러가 올바른 Content-Type + 실제 바이트(작은 더미 png/pdf/mp4)를 반환해야 한다. 기존 `attachment-handlers.ts` download 핸들러의 응답 바디·Content-Type를 미리보기 시나리오에 맞게 확장(stateful 시드 패턴 유지, 메모리 `e2e-msw-scenario-toggle`/`msw-derived-behavior-shared-store`).
- **G2 jsdom objectURL 모킹**. vitest/jsdom은 `URL.createObjectURL`/`revokeObjectURL` 미구현 가능 → 단위 테스트 setup에 mock 필요. `BackupCodesSection.test`가 동일 API를 쓰므로 기존 setup 재사용 여부 확인 후 없으면 추가.
- **G3 PDF iframe sandbox**. `sandbox`(allow-scripts 미부여)가 일부 브라우저의 내장 PDF 뷰어를 막을 수 있다 → 적용 후 E2E(S2)로 실렌더 확인, 뷰어가 깨지면 sandbox 완화(콘텐츠 격리는 blob type 명시로 유지).
- **G4 로딩 중 닫기 가드**. 다운로드 진행 중 모달 닫힘 → setState-on-unmounted 회피 + 진행 중이던 objectURL 해제. cleanup(useEffect return 또는 ref 가드).

## Brainstorming Check

✅ 통과 (1회, self sanity-check). gap 4건(G1~G4) 모두 구현 수준 보강 — 범위/scope 변경 없음, Maxi 결정 불요. spec §11에 반영. 핵심 결정(D-1/D-2/D-3)은 Maxi 사전 확정.
