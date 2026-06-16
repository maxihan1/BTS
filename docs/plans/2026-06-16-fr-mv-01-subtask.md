# FR-MV-01 서브태스크 동반 이동 (노드별 매핑) — 백엔드

> slug: fr-mv-01-subtask
> type: backend
> agent: backend-engineer
> BC: issue-tracking
> 생성: 2026-06-16

## Brief

부모 이슈를 다른 프로젝트로 이동할 때 자식 서브태스크를 함께 이동(노드별 매핑).
현재 단건 이동(#153)만 완료 — 자식 있으면 422 `ISSUE_HAS_SUBTASKS`로 거부 중.
ADR `2026-06-16-issue-move-semantics` 기준, D1(도메인 IssueMoveOperation 서브태스크 동반)·
D4(백엔드 preview/move 노드별 매핑) 확장. 신규 cross-BC SPI `WorkflowStateCatalog` 재사용.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
