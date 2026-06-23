<!-- FR-BL-02 백로그→스프린트 이동 백엔드(D1~D5) 구현 계획 -->
# FR-BL-02 — 백로그 → 스프린트 드래그 이동 (백엔드 D1~D5)

> slug: fr-bl-02-sprint-backend
> type: api
> agent: backend-engineer
> primary_bc: agile-planning (Sprint 엔티티) + issue-tracking (issues.sprint_id)
> 생성: 2026-06-24
> ⚠️ 진실출처: 이 plan 파일 (.bts-cache/classify.json은 멀티세션 충돌 가능)

## Brief

FR-BL-02 백로그→스프린트 이동의 **백엔드 D1~D5만** 이번 PR 범위.
- Sprint 도메인 신설 (agile-planning BC)
- `sprints` 테이블 + `issues.sprint_id` 컬럼 (마이그레이션)
- 스프린트 CRUD API + 이슈→스프린트 할당/해제 API
- 권한(security-engineer 공동 검토), 백엔드 테스트

프론트 D6/D7(@dnd-kit 백로그↔스프린트 드래그)은 **이번 PR 제외** — 후속에서 FR-BL-01 D6/D7(백로그 정렬 UI)과 통합.

classify: { type: api, agent: backend-engineer, primary_bc: agile-planning }
product agile-planning.md §3.2 / SDD §13 / fr-index §3.2
배경: docs/plan/README.md §2(stash 보관) — FR-BL-02가 cross-BC 병목(리포트 4종 FR-RP-01~04 선행)으로 최우선 지목됨.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
