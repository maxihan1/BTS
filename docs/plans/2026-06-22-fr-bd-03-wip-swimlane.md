# FR-BD-03 — WIP 제한 + 스윔레인 (백엔드 D1~D5)

> slug: fr-bd-03-wip-swimlane
> type: api
> agent: backend-engineer (D3는 db-engineer)
> 생성: 2026-06-22

## Brief

FR-BD-03 (agile-planning BC, §2.3) — WIP 제한 + 스윔레인 백엔드.

- D1. 도메인
- D2. 명세 — WIP 초과 시 시각 경고만 (이동 차단 옵션)
- D3. 데이터 모델 — `board_columns.wip_limit`, `boards.swimlane_field`
- D4. 백엔드 — 카운트 + 경고 응답 API
- D5. 백엔드 테스트

선행 §2.1 FR-BD-01 완료됨. 직전 FR-BD-01/02와 동일하게 백엔드 먼저 → 프론트 D6/D7은 후속 PR.

SDD 참조. 13.1.1 (WIP 제한 = 컬럼당 최대 이슈 수), 13.1.3 (스윔레인 = 담당자별/Epic별/우선순위별 가로 분리).

## 도메인 정리

- **BC**: agile-planning
- **영향 엔티티**: `Board`(swimlaneField 추가), `BoardColumn`(wipLimit 추가). 신규 엔티티 0.
- **새 용어**:
  - **WIP 제한** (Work In Progress limit) — 컬럼당 동시 진행 카드 수 상한. 초과 시 경고만(차단 X).
  - **스윔레인** (Swimlane) — 보드를 담당자/우선순위 등으로 가로 분리하는 그룹 행.
- **기존 결정 충돌**: 없음. FR-BD-01 ADR과 일관 (보드는 워크플로우 상태에 종속, 카드 이동은 전이 재사용).
- **Maxi 확정 결정 (3건)**:
  1. Epic 스윔레인 이연 — `swimlane_field` enum = NONE/ASSIGNEE/PRIORITY만. EPIC은 FR-EP 미구현으로 카드에 데이터 부재 → 미포함.
  2. WIP 초과 = 경고 신호만 응답 (이동 차단 안 함). 백엔드는 wipLimit/wipExceeded 신호만.
  3. 스윔레인 그룹핑 = 프론트(D6). 백엔드는 swimlane_field 저장+echo, 카드 assigneeId/priority는 이미 노출됨.
- **카드 데이터 가용성**: assigneeId ✓, priority ✓ (BoardIssueView). epic ✗ (FR-EP 미구현) → EPIC 스윔레인 불가.
- **스키마 변경 (D3)**: `board_columns.wip_limit INTEGER NULL`(양수만), `boards.swimlane_field VARCHAR NOT NULL DEFAULT 'NONE'`.
- **미정 (→ spec)**: WIP 제한·swimlane_field 설정 쓰기 API 엔드포인트 형태 + 권한 (보드 설정 변경 권한).
- **관련 ADR**: [docs/decisions/2026-06-22-fr-bd-03-wip-swimlane.md](../decisions/2026-06-22-fr-bd-03-wip-swimlane.md) (생성됨)
- **glossary 추가 후보** (Maxi 승인 후 수동): "WIP 제한", "스윔레인"

## 스펙

전체 스펙. [docs/specs/2026-06-22-fr-bd-03-wip-swimlane.md](../specs/2026-06-22-fr-bd-03-wip-swimlane.md)

핵심 시나리오.
- 컬럼별 WIP 제한 설정(`PATCH /boards/{id}/columns/{columnId}` {wipLimit}). 조회 시 wipLimit/wipExceeded 신호 응답. 이동 차단 안 함.
- 보드 스윔레인 기준 설정(`PATCH /boards/{id}` {swimlaneField}: NONE/ASSIGNEE/PRIORITY). 백엔드 echo, 그룹핑은 프론트.
- 권한 = CREATE on Project(보드 생성과 동일). 401/403/404 게이트 기존 패턴 재사용.

신규: 마이그레이션 V501(+init_codegen 미러), 도메인 필드 2개, BoardRepository update 2메서드, PATCH 2엔드포인트, GET 응답 확장.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). gap 3건 보강(E11 가시성 기준 WIP / E3 enum 대소문자 / 생성 응답 범위). Maxi 결정 필요 gap 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
