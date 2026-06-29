# FR-EX-02 — 대용량(>1만건) 비동기 Export (백엔드 D1~D5)

> slug: fr-ex-02-export-async-backend
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-29

## Brief

대용량(>1만건) 비동기 Export. FR-EX-01(동기, PR #203)의 진화판.
사용자 입력: "FR-EX-02 대용량(>1만건) 비동기 Export — pgmq job + 백그라운드 worker + export_jobs 테이블 + 진행률/결과URL TTL + 프론트 진행률/다운로드 UI"

**범위 결정 (Maxi 게이트, 2026-06-29)**: 이번 PR = 백엔드 D1~D5만. 프론트 D6/D7은 별도 PR.
- D1. ExportJob 도메인
- D2. 명세 — 큐 + 진행률 + 결과 URL TTL
- D3. 데이터 모델 — export_jobs(status, progress, result_minio_key, expires_at)
- D4. 백엔드 — pgmq job + 백그라운드 worker
- D5. 백엔드 테스트 — 1만건 시나리오

**classify 교정 메모**: 원판 type=ui(프론트 키워드 끌림) → feature/backend-engineer 교정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
