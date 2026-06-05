# FR-CM-04 — 컴포넌트 리드 부재 시 프로젝트 리드 기본 담당자 폴백

> slug: fr-cm-04-project-lead-fallback
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking

## Brief

FR-CM-03 후속. 이슈 생성 시 컴포넌트 리드를 기본 담당자로 자동 할당하되,
리드가 없을 때 프로젝트 리드로 폴백한다. admin ≠ lead.
프로젝트 리드는 issue-tracking 소유 `projects.lead_user_id` 컬럼으로 표현(옵션 B, in-BC) —
도메인 단계에서 cross-BC 포트 대신 `components.lead_user_id` 동형 모델로 확정.

## 도메인 정리

- **BC**: issue-tracking (in-BC — 옵션 B 결정으로 cross-BC 포트 불필요)
- **영향 엔티티**:
  - `Project` / `projects` 테이블 — `lead_user_id UUID NULL` 컬럼 신설 (FK 미적용, BC 격리)
  - `DefaultAssigneeResolver` — 폴백 체인 확장 (컴포넌트 리드 → 프로젝트 리드 → 미할당)
  - `IssueApplicationService` — 프로젝트 리드 조회 후 resolver에 주입 (오케스트레이션)
- **새 용어**: "프로젝트 리드" (Project Lead) — 프로젝트 단위 **단일** 업무 책임자(자동배정 2순위 대상).
  `PROJECT_ADMIN`(다수·권한)과 구분 (admin ≠ lead). glossary 추가 후보 (Maxi 승인 대기).
- **모델 결정 (D2)**: 옵션 B — `projects.lead_user_id` 컬럼. `components.lead_user_id`(FR-CM-03) 동형.
  옵션 A(project_memberships PROJECT_LEAD 역할) 기각 — cross-BC 포트 + UNIQUE 제약 비용.
- **범위 결정**: 폴백 로직 + 프로젝트 리드 지정/해제 API 포함. 지정 UI는 후속 FR 분리.
- **폴백 체인**: 컴포넌트 리드(1순위, FR-CM-03) → 프로젝트 리드(2순위, FR-CM-04) → 미할당.
  `current != null`이면 덮어쓰지 않음(FR-CM-03 S3 규칙 유지).
- **기존 결정 충돌**: 없음. components.lead_user_id 패턴 재사용.
- **명세 deviation**: product §3.1.4 D2/D4의 cross-BC 포트 가정 → 옵션 B로 in-BC. 같은 PR에서 동기화.
- **관련 ADR**: [docs/adr/2026-06-06-project-lead-default-assignee-fallback.md](../adr/2026-06-06-project-lead-default-assignee-fallback.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
