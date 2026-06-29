# FR-EX-01 — 필터 결과 CSV/XLSX Export

> slug: fr-ex-01-csv-xlsx-export
> type: api
> agent: backend-engineer
> 생성: 2026-06-29

## Brief

FR-EX-01 (search-export-import BC, SDD §3.1, 필수). 필터(FR-SR-01)/AQL(FR-SR-02) 검색 결과를
CSV(UTF-8 BOM 한글 인코딩) + XLSX(Apache POI)로 **동기** 다운로드.
`POST /api/v1/exports`. 선행 §2.1 FR-SR-01 + §2.2 FR-SR-02 모두 완료.
대용량(>1만건) 비동기 Export는 FR-EX-02로 분리 (이번 범위 아님).

product D단계.
- D1. 도메인 — ExportRequest (backend-engineer)
- D2. 명세 — 필드 선택 + 한글 인코딩 (UTF-8 BOM) (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — POST /api/v1/exports (CSV + Apache POI XLSX) (backend-engineer)
- D5. 백엔드 테스트 — Excel 검증 (backend-engineer)
- D6. 프론트 UI — Export 다이얼로그 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
