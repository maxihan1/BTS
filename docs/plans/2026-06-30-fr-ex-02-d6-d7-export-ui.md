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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
