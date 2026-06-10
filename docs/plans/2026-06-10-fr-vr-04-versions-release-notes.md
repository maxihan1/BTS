# FR-VR-04 — 버전 릴리즈 노트 자동 생성

> slug: fr-vr-04-versions-release-notes
> type: api (풀스택 — 백엔드 GET API + 프론트 미리보기/복사 UI)
> agent: backend-engineer (+ frontend-engineer / qa-engineer)
> 생성: 2026-06-10
> FR: FR-VR-04 (issue-tracking BC, 중요도 중간)
> 선행: FR-VR-01(버전 생성) · FR-VR-03(Affects/Fix Version 연결) — 둘 다 완료

## Brief

특정 버전을 Fix Version으로 연결한 이슈들을 모아 Markdown 형식 릴리즈 노트를 자동 생성한다.

- D1. 도메인 (backend-engineer)
- D2. 명세 — Markdown 템플릿 (backend-engineer)
- D3. 데이터 모델 — (활용, 새 테이블 없음) (db-engineer)
- D4. 백엔드 — `GET /api/v1/versions/{id}/release-notes` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 미리보기 + 복사 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify: { type: api, agent: backend-engineer, primary_bc: issue-tracking }
product 정본: docs/plan/product/issue-tracking.md §3.2.4
SDD 참조: §3.2.4

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
