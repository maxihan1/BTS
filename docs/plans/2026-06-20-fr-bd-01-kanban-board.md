# FR-BD-01 칸반 보드 (컬럼 표시, 드래그앤드롭)

> slug: fr-bd-01-kanban-board
> type: feature (classify=backend, 풀스택 feature로 교정)
> agent: backend-engineer + frontend-engineer (task별 지정 예정)
> primary_bc: agile-planning
> SDD: §2.1
> 생성: 2026-06-20

## Brief

사용자 원문. "FR-BD-01 진행. 다른 섹션에서 병행 작업 중이므로 워크트리 새로 만들어서 진행."

classify 결과. type=backend → 풀스택 feature로 교정 (칸반 보드 = 컬럼 표시 + 드래그앤드롭, UI 핵심). primary_bc=agile-planning.

비고. 직전 FR-PL-01(#160)에서 "agile-planning 모듈 신설 0" 확인됨 → 이번이 agile-planning BC의 첫 본격 작업일 수 있음. BC 신설 여부는 도메인 단계에서 결정.

## 도메인 정리

- **BC**: agile-planning (**신규 모듈 신설** — backend/modules에 5개만 존재, agile-planning 없음. FR-BD-01이 첫 작업).
- **영향 엔티티 (신규)**: `Board`, `BoardColumn`. Swimlane은 FR-BD-03(이번 범위 밖).
- **cross-BC 참조 (읽기/위임)**:
  - issue-tracking — 보드 카드 = 이슈 목록 읽기. 카드 이동 = **기존 전이 메커니즘 재사용**(전이 API는 `IssueController`/issue-tracking 소유).
  - project-workflow — 보드 컬럼 = **워크플로우 상태 카테고리(TODO/IN_PROGRESS/DONE) 매핑**. 상태 카탈로그는 `WorkflowStateCatalogImpl`이 제공. `kanban-basic.yaml` 워크플로우 기존재.
  - identity-access — 보드 조회 권한(기존 프로젝트 권한 resolver 재사용 예정).
- **새 용어 (glossary 추가 후보, Maxi 승인 필요)**:
  - 보드 (Board) — 프로젝트의 이슈를 컬럼별로 시각화하는 작업 현황판.
  - 보드 컬럼 (BoardColumn) — 보드의 세로 열. 하나 이상의 워크플로우 상태에 매핑.
  - (domain/agile-planning.md 노트에는 이미 Board/BoardColumn이 엔티티로 언급됨. glossary 정식 행은 없음.)
- **결정 사항 (Maxi 확정)**:
  1. 작업 범위 = **백엔드 D1~D5만**. 프론트 D6/D7(@dnd-kit, E2E)은 후속 PR.
  2. 드래그앤드롭 = **컬럼 간 이동만**(= 워크플로우 전이 재사용). 컬럼 내 순서 = 기본 정렬(우선순위/생성일). **LexoRank 불필요** → FR-BL-01로 미룸.
  3. §1 기술검증(LexoRank/@dnd-kit/Gantt PoC)은 이번 범위(백엔드, 컬럼 간 이동)에 직접 불필요. @dnd-kit은 후속 프론트 PR(D6)에서 자연 검증.
- **기존 결정 충돌**: 없음. BC 신설 + 컬럼=상태 매핑 + 카드 이동=전이 재사용은 ADR 신규 후보.
- **관련 ADR**: [docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md](../decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md) (생성됨). 간접 관련 — workflow-transition-identity-policy(2026-05-28), workflow-yaml-vs-db-storage(2026-05-21).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
