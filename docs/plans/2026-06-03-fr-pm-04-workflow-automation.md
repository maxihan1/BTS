# FR-PM-04 — 워크플로우/자동화 관리 권한

> slug: fr-pm-04-workflow-automation
> type: auth
> agent: security-engineer
> 생성: 2026-06-03

## Brief

FR-PM-04 워크플로우/자동화 관리 권한 (identity-access §4.4). 선행 §4.2(FR-PM-02) 완료.
워크플로우/자동화 관리 동작에 대한 권한 가드 추가. FR-PM-02/03과 동형 구조 (MANAGE_* 권한코드 + prod resolver + 프론트 게이팅 + E2E).

## 도메인 정리

- **BC**: identity-access (권한 prod 판정 소관) + shared-kernel (포트 이동 대상)
- **권한 코드 (SDD 12.3 정본, 신규 생성 아님)**:
  - `MANAGE_WORKFLOW` — 워크플로우(스킴) 편집. **이번 범위**.
  - `MANAGE_AUTOMATION` — 자동화 규칙 관리. **이번 범위 제외** (automation BC 부재).
- **가드 대상 (실재 검증 완료)**:
  - `WorkflowSchemeController` (`/api/v1/workflow-schemes`) — create/update/delete + mapping CRUD. 이미 `WorkflowSchemePermissionResolver.requirePermission(...)` 결선됨 (Guard 패턴). non-prod는 `AlwaysAllowWorkflowSchemePermissionResolver`(`@Profile !prod`)가 통과. **prod 구현만 부재** → FR-PM-04가 채움.
  - 자동화: automation BC(FR-AT-01~07) 미구현 → 가드 대상 0.

### 결정 (Maxi 2026-06-03)

- **D1 범위**: 워크플로우 관리 권한(MANAGE_WORKFLOW)만 완결. 자동화 관리 권한은 automation BC(FR-AT) 착수 시 동반. `docs/plan/product/automation.md §0` 진입조건 문구를 "FR-PM-04 워크플로우 부분 완료"로 조정.
  - **근거**: automation BC 부재 → MANAGE_AUTOMATION 시드는 소비처 0인 dead 시드 (메모리 `no-cross-bc-deployment-assembly`). FR-PM-03 선례("기능 → 권한" 순서)와 일관.
- **D2 포트 이동**: `WorkflowSchemePermissionResolver` 포트 + `WorkflowSchemePermission` enum + `WorkflowSchemeScope`를 project-workflow → **shared-kernel**로 이동. actorId는 UUID로 단순화(BC 공통 분모). identity-access가 prod resolver(`@Profile prod`) 구현.
  - **근거**: identity-access는 project-workflow를 의존 불가(BC 격리 ArchUnit 룰). FR-PM-03 컴포넌트/버전 포트가 shared-kernel에 있던 선례 그대로.
  - **주의**: 이동 시 참조처(project-workflow service/adapter/test) 전수 grep 수정 필요 (메모리 `archunit-shared-class-move-repository-package`).
- **D3 전역(Global) scope 판정 모델**: **spec 단계에서 정밀 설계** (Maxi 결정). 워크플로우 스킴 생성/수정/삭제는 `WorkflowSchemeScope.Global`이나 현재 `role_permissions` 매트릭스는 프로젝트 단위 → 전역 자원 판정 모델 미정의. SDD 12.6 시스템 역할(OrgAdmin 등)·기존 테이블 근거로 spec에서 확정.

- **관련 ADR**: docs/decisions/2026-06-04-workflow-scheme-permission-prod-resolver.md (생성)
- **선례 ADR**: 2026-06-03-version-component-permission-prod-resolver (FR-PM-03 동형) · 2026-05-22-issue-permission-resolver-port
- **기존 결정 충돌**: 없음. WorkflowSchemePermissionResolver KDoc이 FR-PM-04를 명시적으로 예약함.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
