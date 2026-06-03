# FR-IS-08 D6/D7 — 이슈 PDF 출력 프론트엔드

> slug: fr-is-08-pdf-frontend
> type: ui (frontend-engineer 주도 + qa-engineer E2E)
> primary_bc: apps/web
> 생성: 2026-06-04

## Brief

FR-IS-08 D6/D7 — 이슈 PDF 출력의 프론트엔드 짝. 백엔드 D1-D5(PR #71, `GET /api/v1/issues/{key}/pdf` → application/pdf)는 머지 완료.

- D6. 프론트 UI — 이슈 상세에 인쇄/PDF 다운로드 버튼 (책임. designer → frontend-engineer)
- D7. E2E (책임. qa-engineer)

classify가 'E2E'로 qa 오분류 → UI 기능+E2E 혼합으로 정정(frontend-engineer 주도).

**핵심 기술 포인트**.
- 백엔드는 `application/pdf` 바이너리(`Content-Disposition: attachment; filename="{key}.pdf"`)를 반환.
- 프론트는 fetch → blob → 브라우저 다운로드 트리거(또는 새 탭). MSW로 mock.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
