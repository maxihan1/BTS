# FR-EP-01 D6/D7 — 에픽 페이지 + 자식 이슈 연결 UI + 보드 EPIC 스윔레인

> slug: fr-ep-01-d6-d7-epic-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning (보드) + issue-tracking (에픽/자식 연결 — 백엔드 계약)
> 생성: 2026-06-22

## Brief

FR-EP-01(에픽 이슈 타입 + 자식 이슈 연결)의 D6/D7 프론트 구현. 백엔드 D1~D5는 PR #174로 완료됨.

작업 범위.
1. 에픽 페이지 + 자식 이슈 목록 UI
2. 에픽 연결/해제 UI
3. 보드 EPIC 스윔레인 enum 추가 (FR-BD-03 #173에서 이연됨)
4. changelog 라벨 "에픽" (field="epic")

확정된 백엔드 계약 (#174).
- `POST /api/v1/issues/{epicKey}/epic-children` — 자식 연결
- `DELETE /api/v1/issues/{epicKey}/epic-children` — 연결 해제
- `GET /api/v1/issues/{epicKey}/epic-children` — 자식 목록
- `IssueResponse.epic` — {key, summary} 단건 (parent 동형 self-join, 단건 GET만 채움)
- changelog `field="epic"` — 한글 라벨 "에픽" 매핑 필요
- 보드 스윔레인 EPIC: BoardCardResponse.epic view-layer patch 필요 가능성 (PRIORITY 스윔레인 #173 옵션C 선례)

완료 시 FR-EP-01 전체 [x] 마킹 (69/123 → 70/123).

## 도메인 정리

- **BC**: agile-planning(보드 EPIC 스윔레인) + issue-tracking(에픽/자식 연결 — 백엔드 계약 소유). 프론트는 단일 SPA라 BC 격리는 백엔드만 적용. view-layer 소비 작업.
- **영향 엔티티(프론트 관점)**: IssueResponse.epic, BoardCardResponse(EPIC 스윔레인용 epic 필드 view-layer patch 가능성). 신규 도메인 엔티티 0 — 백엔드 #174에서 확정.
- **새 용어**: "에픽"(Epic) — glossary 이미 등재(이슈 타입 hierarchy_level=1, agile-planning 묶음 단위). "스윔레인"은 glossary 미등재이나 FR-BD-03(#173)에서 코드 정착 → 이번엔 EPIC 옵션만 추가(glossary 추가는 후속/Maxi 승인 영역).
- **기존 결정 충돌**: 없음. ADR [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md)가 도메인 계약 확정, 프론트는 소비만.
- **프론트가 지켜야 할 백엔드 계약(ADR + #174 확정)**:
  - `IssueResponse.epic` = {key, summary} 단건. **단건 GET(findByKeyWithType)만 채워지고 목록 응답은 null**(parent 동형 self-join). → 에픽 표시는 단건 상세 화면에서만.
  - `GET /api/v1/issues/{epicKey}/epic-children` — 자식 목록(BROWSE + accessibleLevels, 백엔드가 누출 차단).
  - `POST /api/v1/issues/{epicKey}/epic-children` — 자식 연결(자식 UPDATE 권한).
  - `DELETE /api/v1/issues/{epicKey}/epic-children` — 연결 해제(자식 UPDATE 권한).
  - 불변식: 자식=hierarchy_level 0(story/task/bug), 대상=Epic(level 1), 동일 프로젝트, 단일 Epic(이미 소속 409), 자기참조 금지. 위반 시 백엔드 4xx → 프론트는 에러 토스트.
  - changelog `field="epic"` → 한글 라벨 "에픽" (changelog-labels 매핑 추가).
  - 보드 EPIC 스윔레인: `BoardCardResponse.epic` view-layer patch 필요 가능성(PRIORITY 스윔레인 #173 옵션C, same-BC view-layer 선례 PR #13). spec 단계에서 확정.
- **관련 ADR**: [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (#174 생성), [2026-06-13-issue-link-vs-parent-child-separation](../decisions/2026-06-13-issue-link-vs-parent-child-separation.md)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
