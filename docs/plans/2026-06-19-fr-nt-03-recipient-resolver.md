# FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할) RecipientResolver

> slug: fr-nt-03-recipient-resolver
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-19

## Brief

FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할) RecipientResolver 구현.
notification-dashboard BC. FR-NT-01 notification_policies.recipient_role을
실제 사용자 집합으로 해석.

- product: docs/plan/product/notification-dashboard.md §2.3
- SDD: docs/sdd/02-requirements.md FR-NT-03 (수신자 정책: R/A/W/Lead/역할)
- 선행: FR-NT-01 (notification_policies, recipient_role 필드 존재, PR #118)
- D1~D7 미완 (전부 [ ])

## 도메인 정리

- **BC**: notification
- **핵심 통찰**: FR-NT-03은 신규 컴포넌트가 아니라 기존 `EventRecipientResolver`의 `else → skip (FR-NT-03 대상)` 분기를 실제 cross-BC 조회로 채우는 작업. enum `RecipientRole`(9개)·정책 평가 엔진·NotificationWorker·dedup 인프라는 FR-NT-01/02에서 완성됨.
- **이미 구현(FR-NT-02)**: MENTIONED, REPORTER, ASSIGNEE
- **이번에 구현(Maxi 확정 2026-06-19, 데이터 있는 5개 전부)**:
  | RecipientRole | 데이터 출처 | 원본 BC | 조회 키 |
  |---|---|---|---|
  | WATCHER | `issue_watchers` (FR-WT-01) | issue-tracking | issueId |
  | COMPONENT_LEAD | `issue_components` 조인 → `components.lead_user_id` | issue-tracking | issueId |
  | PREVIOUS_ASSIGNEE | `issue_change_item` (field='assignee', from_value) (FR-HS) | issue-tracking | issueId |
  | PROJECT_MEMBER | `project_memberships` 전체 | identity-access | projectId/Key |
  | PROJECT_ADMIN | `project_memberships` WHERE role='PROJECT_ADMIN' | identity-access | projectId/Key |
- **skip 유지**: RULE_OWNER — automation BC 미존재(Phase 1 범위 밖). 정책 시드(V401)엔 행이 있으나 동작 안 함(시드 부트스트랩 ADR 결정 3과 일관 — "발행원 없는 정책은 무해하게 존재"). 향후 FR-AT 시리즈에서 결선.
- **PROJECT_LEAD enum 미추가(Maxi 확정)**: product 'Lead'='COMPONENT_LEAD'로 해석(SDD §9.1 issue.created 기본 수신자와 일치). `projects.lead_user_id` 데이터는 있으나 enum/시드/init_codegen 변경 회피 → drift 위험 0.
- **새 용어**: 없음 (모든 역할 개념 이미 glossary/enum에 존재)
- **cross-BC 포트 설계 방향(spec에서 시그니처 확정)**: ArchUnit BC 격리상 notification은 issue-tracking/identity-access 내부 패키지 직접 import 금지. shared-kernel 포트 인터페이스 정의 + 각 원본 BC가 adapter 구현(선례 `IssueRecipientLookupPort` 답습). nullable/fail-open 함정 주의([[crossbc-resolver-nullable-fail-open]]) — 기본값 non-null + 빈 수신자(알림 미발송) fail-safe.
- **기존 결정 충돌**: 없음. FR-NT-01 ADR(결정 2/결과) + FR-NT-02 ADR(결정 3/4)이 RecipientResolver=FR-NT-03 경계를 명시적으로 예약함.
- **관련 ADR**: FR-NT-03 cross-BC 포트 설계 ADR은 spec 단계에서 포트 분해(역할별 vs 통합) 확정 후 작성 검토. 현재 IssueRecipientLookupPort 선례 답습이라 신규 결정 비중 낮음.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
