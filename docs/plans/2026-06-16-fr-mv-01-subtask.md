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

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: `Issue`(parent_id 보존/끊기 분기), `issue_key_redirects`(노드별 INSERT), 조인 테이블(컴포넌트/버전 노드별 교체), 커스텀필드.
- **신규 필요**:
  - `IssueRepository` 자식 **목록** 조회 메서드 (현재 `countDirectChildren` 만 존재)
  - `IssueMoveOperation` 검증을 노드별로 확장 + 자식의 자식 거부 EC 추가
  - `MovePreviewService`/`IssueMoveService` 를 노드 배열(부모+자식) 처리로 확장
- **Maxi 결정 (2026-06-16)**:
  - 범위 = **직접 자식(1레벨)** 동반. 서브태스크는 `hierarchy_level=-1` 최하위 = 1레벨 모델. 자식이 또 자식 가지면 거부(불변식 강제).
  - 매핑 = **완전 노드별** (부모+각 자식 독립 매핑).
- **불변식**: 각 노드 id 보존 / 새 키 발번(부모→자식) / redirect+308 / 노드별 OCC·상태·매핑. **자식 parent_id 는 부모 id 보존으로 자동 유지** (단건 `parent_id=null` 강제는 부모 노드에만).
- **새 용어**: "서브태스크 동반 이동", "노드별 매핑" — glossary 추가 후보 (Maxi 승인 대기).
- **기존 결정 충돌**: 없음. ADR `2026-06-16-issue-move-semantics` 에 "후속 결정 — 서브태스크 동반 이동" 단락 확장.
- **관련 ADR**: [docs/adr/2026-06-16-issue-move-semantics.md](../adr/2026-06-16-issue-move-semantics.md) (확장됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
