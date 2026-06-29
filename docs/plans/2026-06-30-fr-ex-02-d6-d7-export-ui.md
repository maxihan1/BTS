# FR-EX-02 D6/D7 — 대용량 비동기 Export 프론트 UI + E2E

> slug: fr-ex-02-d6-d7-export-ui
> type: ui
> agent: frontend-engineer (D6 주력) + qa-engineer (D7 E2E 보조)
> 생성: 2026-06-30

## Brief

FR-EX-02(대용량 >1만건 비동기 Export)의 백엔드 D1~D5는 PR #204로 완료.
이번 작업은 D6(프론트 UI — 진행률 + 알림 + 다운로드) + D7(E2E).

백엔드 계약(PR #204):
- POST /api/v1/search/export-jobs (202 + jobId)
- GET /api/v1/search/export-jobs/{id} (폴링 — status/progress)
- GET /api/v1/search/export-jobs/{id}/download (완료 시 프록시 스트리밍)
- 24h TTL 후 하드삭제

classify 원분류 qa(E2E 키워드 오판) → ui로 정정. FR-EX-01(동기 Export 다이얼로그, PR #203) 위에 비동기 경로를 얹는 작업.

## 도메인 정리

- **BC**: search-export-import (FR-EX-01·FR-EX-02 동일 본거지, BC 변경 0). 프론트는 `apps/web` SPA.
- **영향 범위**: 프론트 view-layer 소비만. 백엔드 도메인/계약은 PR #204에서 확정 — 이 PR은 0 백엔드 변경 목표.
  - 새 API 클라이언트 (`@/api/search`에 비동기 export-jobs 3종 함수 추가)
  - 새 UI (잡 생성 → 진행률 폴링 → 완료 다운로드)
- **백엔드 계약 (PR #204 확정, 직접 코드 검증)**:
  - `POST /api/v1/search/export-jobs` — body `ExportRequest`(projectKey, query=AQL, format?, columns?), 202 `{ jobId, status:"PENDING" }`, AQL/검증 오류 400
  - `GET /api/v1/search/export-jobs/{id}` — 200 `ExportJobResponse{ jobId, status, progress(0~100), rowCount?, format, errorCode?, downloadReady }`, 타인/없음 404 (@JsonInclude NON_NULL)
  - `GET /api/v1/search/export-jobs/{id}/download` — 200 octet-stream + Content-Disposition / 미완료 409(SEARCH_EXPORT_NOT_READY) / 타인·없음 404
  - status enum 4종: PENDING→RUNNING→COMPLETED / (PENDING|RUNNING)→FAILED (종단 COMPLETED·FAILED)
- **★핵심 제약 — 완료 이벤트 발행 없음**: 백엔드 plan §187 "ExportJob은 완료 이벤트 발행이 없다". 백엔드는 in-app/이메일 알림을 보내지 않음. **프론트가 폴링으로 완료 감지 + 자체 진행률/완료 표시**. product 문서 D6의 "알림"은 프론트 자체 통지(진행률 바·완료 표시)로 해석.
- **재사용 자산 (FR-EX-01 동기 Export, PR #203)**:
  - `ExportDialog.tsx`/`ExportForm` — 형식(CSV/XLSX) + 9컬럼 선택 UI, `/search` 페이지(search.tsx:604)에서 "내보내기" 버튼으로 열림
  - `exportIssues()` (`@/api/search`), `triggerBlobDownload` (`@/lib/download`), `resolveExportError` 에러 추출 패턴
  - `EXPORT_COLUMNS` 9컬럼 정의(백엔드 ExportColumn enum 1:1)
- **새 용어**: 없음. ExportJob은 백엔드 도메인 용어(glossary 미등재 — PR #204에서 Maxi 승인 보류). 프론트는 view 소비라 용어 신설 불요.
- **기존 결정 충돌**: 없음 (FR-EX-01 ADR §D5가 `/search/export-jobs`를 명시 예고).
- **관련 ADR**: FR-EX-01 `docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md` §D5 + FR-EX-02 백엔드 `docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md`. 프론트 UX 결정은 본 PR plan/spec에 기록(별도 ADR 후보 — 게이트1 판단).
- **grill-with-docs 생략 근거**: 백엔드 계약 100% 확정된 view-layer 소비 작업. 새 도메인 결정 0. 대화형 도메인 challenge 과함 (메모리: bts-spec office-hours mismatch / bts-review-plan autoplan overkill). 직접 계약 검증으로 대체.
- **★spec 단계 핵심 미결정 (Maxi taste)**: 동기(FR-EX-01) ↔ 비동기(FR-EX-02) UX 진입점 통합 방식 + 진행률 표시 위치. SDD 미명시 → spec 전 Maxi 결정.

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-ex-02-d6-d7-export-ui.md](../specs/2026-06-30-fr-ex-02-d6-d7-export-ui.md)

핵심 시나리오 3줄 요약.
- 소량(≤1만) → 기존 동기 Export 그대로(회귀 0). 대용량(>1만) → 동기 거부 LIMIT_EXCEEDED 감지 → 비동기 제안.
- "백그라운드 내보내기" → POST export-jobs(jobId) → 같은 다이얼로그가 진행률 폴링(1500ms, GET /{id})로 전환.
- COMPLETED → "다운로드" 버튼 → GET /{id}/download → blob. FAILED → errorCode 한국어 사유 표시.

핵심 설계 = ExportDialog **4단계 상태 머신** (form → confirmAsync → tracking → done). 폴링=TanStack Query refetchInterval(종단 중단·unmount cleanup).
신규 API 클라이언트 3종(submitExportJob/fetchExportJobStatus/downloadExportJobResult) + Zod 스키마. 백엔드 변경 0.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check — 완료된 FR 프론트 단계라 office-hours/design-shotgun 부적합, 메모리 bts-spec-office-hours-mismatch). gap 8건 전부 spec 내 해소(Maxi 결정 추가 불요).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
