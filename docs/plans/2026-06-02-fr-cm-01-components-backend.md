# FR-CM-01 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드 (백엔드 D1~D5)

> slug: fr-cm-01-components-backend
> type: api
> agent: backend-engineer (+ security-engineer: D4 권한 가드)
> primary_bc: issue-tracking
> 생성: 2026-06-02

## Brief

FR-PM-03(버전/컴포넌트 등록 권한)의 **기능 선행**. 권한을 얹을 컴포넌트 CRUD 기능 자체가
미구현이라, 먼저 이 기능을 만든다. plan §3.1.1 FR-CM-01 — 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드.

- 범위: 백엔드 D1~D5 (D1 도메인 Component Aggregate / D2 명세 / D3 데이터모델 components(lead_user_id) / D4 CRUD API + 권한 가드 / D5 백엔드 테스트). D6 프론트 UI·D7 E2E는 후속 PR.
- 선행 충족: §2.1.1 FR-IS-01(이슈 CRUD) ✅ 완료. 권한 인프라 FR-PM-02 ✅ 완료.
- classify 정정: identity-access 오판 → issue-tracking(컴포넌트는 issue-tracking BC).

## 도메인 정리

- **BC**: issue-tracking (classify의 identity-access 오판 정정)
- **영향 엔티티**: Component (신규 Aggregate Root). Issue 연결은 FR-CM-02(다중 컴포넌트 할당) 범위, FR-CM-01 아님.
- **새 용어**: 없음 — "컴포넌트(Component)"/"버전(Version)" glossary 기등록. "컴포넌트 리드(Component Lead)"는 glossary 한 줄 추가 후보(Maxi 승인 대기).
- **데이터 모델**: `components(id, project_id FK→projects, name, description?, lead_user_id?, created_at, updated_at, deleted_at)`. 같은 BC라 projects 실 FK. 활성 기준 (project_id, name) 유일. soft delete(DATA.md §3).
- **권한 가드 결정(Maxi, ①)**: 리졸버 포트 패턴 — `ComponentPermissionResolver`(비prod AlwaysAllow + !prod fallback 빈 + 부팅 가드), prod 실판정은 **FR-PM-03 이연**. 이슈 권한(FR-IS-01→FR-PM-02)과 동형. 인증 필수 + 프로젝트 존재 + 리드 검증은 FR-CM-01에서.
- **리드 검증**: `UserLookupPort`(shared-kernel, FR-IS-03 선례)로 존재 검증 → 없으면 422 `COMPONENT_LEAD_NOT_FOUND`. lead nullable.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: [docs/adr/2026-06-02-component-model-and-permission-deferral.md](../adr/2026-06-02-component-model-and-permission-deferral.md) (생성됨)
- **선행 충족**: FR-IS-01(이슈 CRUD) ✅, FR-PM-02(권한 인프라) ✅.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
