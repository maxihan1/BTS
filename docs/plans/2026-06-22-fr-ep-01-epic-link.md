# FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 (백엔드 D1~D5)

> slug: fr-ep-01-epic-link
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-22

## Brief

FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 구현.

- 현재 `epic`은 이슈 타입으로만 존재(V003/V005 level 1). 이슈↔에픽 연결 메커니즘은 미구현.
- 선행 FR-IS-02(이슈 타입), FR-LK-01(이슈 링크 + parent-child) 모두 완료.
- **설계 갈림길(도메인 단계 결정)**: 기존 `issues.parent_id`(parent-child, FR-LK-01) 재사용 vs 별도 `issues.epic_id` 컬럼. FR-EP-02 진행률 집계 의미론과 맞물림 → bts-domain에서 결정 후 게이트 1에서 Maxi 확정.
- **이번 PR 범위**: 백엔드 D1~D5(에픽 연결 메커니즘 + API). 프론트 D6/D7(이슈상세 에픽 연결 UI · 보드 에픽 스윔레인)은 후속 PR(fr-ep-01-d6-d7-*).

classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: issue-tracking (에픽=이슈 타입 + 연결=issues 컬럼이라 데이터가 issue-tracking 소유. agile-planning §7 FR이나 issue-tracking 구현 — FR-PL-01/FR-TT-01 선례).
- **영향 엔티티**: Issue (epic_id 필드 신규).
- **핵심 결정(Maxi 확정 2026-06-22)**: Epic↔자식 연결 = **별도 `issues.epic_id UUID NULL REFERENCES issues(id)` 컬럼**. parent_id 재사용·issue_links 둘 다 폐기. → ADR [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md)
- **데이터 모델 사실**:
  - `parent_id`(V021): Subtask→부모 Story/Task, UUID NULL 자기참조 FK (FR-LK-01). **epic_id와 별개 유지**.
  - `epic_id`(신규 V028): 자식→소속 Epic, parent_id 패턴 미러(UUID NULL · 자기참조 FK · ON DELETE NO ACTION · 인덱스 · init_codegen 미러).
  - hierarchy_level(코드 정본): epic=1, story/task/bug=0, subtask=-1.
- **도메인 불변식**:
  1. epic_id는 `hierarchy_level=0`(story/task/bug) 이슈에만. Epic(1)·Subtask(-1)은 불가.
  2. epic_id 대상 이슈는 Epic 타입(hierarchy_level=1)이어야 함.
  3. 순환 불가(level 0→1 단방향, 추가 가드 불요). 단일 Epic(컬럼 1개).
  4. 같은 프로젝트 제약(Jira parity) — spec에서 명문화.
- **API(명세)**: `POST /api/v1/issues/{key}/epic-children`(자식 연결). 해제/조회 엔드포인트는 spec 확정.
- **기존 결정 충돌**: 없음 (SDD §5.8/§5.1 정석 일치, ADR 2026-06-13 분리 원칙 일관).
- **deviation**: SDD §5.1 epic_id BIGINT→실제 UUID(V001 issues.id UUID), parent_id 동형. SDD §5 hierarchy_level "0/1/2"→코드 "1/0/-1"(코드 정본). ADR + product 인라인에 기록.
- **glossary**: "에픽"·"이슈 타입" 기존 등재. 신규 용어 없음(epic_id는 구현 디테일). 갱신 불요.
- **관련 ADR**: [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
