# FR-IM-01 — CSV/JSON Import (Jira 마이그레이션)

> slug: fr-im-01-csv-json-import
> type: feature
> agent: backend-engineer
> 생성: 2026-07-02
> BC: search-export-import (§4.1)

## Brief

FR-IM-01 CSV/JSON Import (Jira 마이그레이션). search-export-import BC §4.1, 우선순위 필수.
선행: issue-tracking §2~§3 (이슈/컴포넌트/버전 작성 API) — 완료.
작업 범위 D1~D7:
- D1. 도메인 — ImportJob (backend-engineer)
- D2. 명세 — CSV/JSON 파싱 + 트랜잭션 정책 + dry-run (backend-engineer)
- D3. 데이터 모델 — import_jobs(status, error_log_minio_key) (db-engineer)
- D4. 백엔드 — POST /api/v1/imports + 백그라운드 worker (backend-engineer)
- D5. 백엔드 테스트 — Jira CSV 샘플 (backend-engineer)
- D6. 프론트 UI — 파일 업로드 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

강력한 선례: FR-EX-02(비동기 Export) — pgmq job + MinIO + 폴링 worker 패턴의 역방향.

classify 정정: 'Jira 마이그레이션' → DB migration 오인(type=migration/db-engineer)을 feature/backend-engineer로 정정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
