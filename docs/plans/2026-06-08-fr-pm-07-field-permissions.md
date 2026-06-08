# FR-PM-07 — 필드 수준 권한 (Field-Level Permissions)

> slug: fr-pm-07
> type: auth
> agent: security-engineer
> 생성: 2026-06-08

## Brief

FR-PM-07 필드 수준 권한 — 특정 필드(코어+커스텀)를 특정 역할만 보거나 편집할 수 있게 하는 필드 수준 권한.
identity-access BC. SDD §12.5, plan §4.7. 선행 FR-IS-10(커스텀 필드)·FR-PM-06(이슈 보안 수준) 완료됨.

- type: auth
- agent: security-engineer
- primary_bc: identity-access

## 도메인 정리

- **BC**: identity-access(권한 규칙·판정 소유) + issue-tracking(시행: 직렬화 필터·편집 거부)
- **영향 엔티티**: `FieldPermission`(신규, identity-access), `IssueResponse`/`IssueApplicationService`(issue-tracking 시행), `UserGroup`/`CustomFieldDefinition`(소비 참조)
- **새 용어**:
  - 필드 수준 권한(Field-Level Permission) — 이슈의 특정 필드(코어+커스텀)를 특정 사용자 그룹만 열람/편집하게 제어
  - 필드 권한 규칙(FieldPermission) — (프로젝트, 필드 key, 그룹, 접근수준 VIEW/EDIT) 한 행
  - 접근 수준(FieldAccessLevel) — VIEW · EDIT (EDIT ⊃ VIEW 함의)
- **Maxi 도메인 결정(2026-06-08, AskUserQuestion)**:
  1. 역할 축 = 사용자 그룹 기반(FR-PM-09)
  2. 그릇 = 프로젝트별 단순 테이블(스킴 계층 미채택)
  3. 시행 범위 = 열람 + 편집 둘 다
  4. (ADR 기본값) 관리자 우회 없음 — 규칙 지정 그룹 멤버만 값 열람. 게이트1 검토 대상
- **기존 결정 충돌**: 없음. FR-PM-02/06·FR-IS-10 권한 패턴 재사용
- **관련 ADR**: [docs/decisions/2026-06-08-field-level-permissions.md](../decisions/2026-06-08-field-level-permissions.md) (생성됨)
- **glossary 갱신 대기**: "필드 수준 권한", "필드 권한 규칙", "접근 수준" 3종 (Maxi 승인 후 머지 시 반영)
- **grill-with-docs 스킵 사유**: 핵심 갈림길 3종을 AskUserQuestion으로 사전 정렬 + 선행 인프라(그룹/resolver/직렬화)를 코드 grep으로 실재 검증 완료. 명확해진 백엔드 FR이라 대화형 재론 불요(메모리: bts-spec-office-hours-mismatch 정신)

## 스펙

전체 스펙. [docs/specs/2026-06-08-fr-pm-07-field-permissions.md](../specs/2026-06-08-fr-pm-07-field-permissions.md)

핵심 시나리오 3줄 요약.
- 프로젝트별 필드 권한 규칙(field_permissions) — (필드, 그룹, VIEW/EDIT). MANAGE_FIELD_PERMISSIONS로 관리.
- 이슈 조회 시 actor가 VIEW 불가 필드 마스킹(커스텀=맵 키 제거, nullable 코어=null) + restrictedFields 응답.
- 이슈 편집 시 actor가 EDIT 불가 필드 변경 거부(403, no-op은 통과). 관리자 우회 없음.

## Brainstorming Check

✅ 통과 (자체 점검). gap 6건 사전 해소 — 코어 마스킹 타입 문제(non-null summary/priority는 편집 제어만)·field_kind 네임스페이스 분리·이중 통제 회피(securityLevelId/componentIds 제외)·cross-BC 커스텀 key 형식만 검증·N+1 배치 판정·관리자 우회 F7 명시.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
