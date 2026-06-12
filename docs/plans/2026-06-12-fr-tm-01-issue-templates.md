# FR-TM-01 — 프로젝트+타입별 본문 템플릿 (백엔드 D1~D5)

> slug: fr-tm-01-issue-templates
> type: backend
> agent: backend-engineer
> 생성: 2026-06-12

## Brief

FR-TM-01 (issue-tracking BC, §5.2.1) — 프로젝트와 이슈 타입 조합마다 이슈 본문 기본
템플릿을 정의하고, 이슈 생성 시 자동으로 적용한다.

이번 /bts 실행 범위 = **백엔드 D1~D5** (Maxi 확정).
- D1. 도메인 — IssueTemplate
- D2. 명세
- D3. 데이터 모델 — `issue_templates(project_id, type_id, body)`
- D4. 백엔드 — CRUD API + 이슈 생성 시 적용
- D5. 백엔드 테스트

프론트 관리 페이지(D6) + E2E(D7)는 후속 /bts로 별도 PR.

classify 결과: type=backend, agent=backend-engineer, primary_bc=issue-tracking.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
