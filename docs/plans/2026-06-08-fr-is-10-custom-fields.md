# FR-IS-10 — 커스텀 필드 인프라

> slug: fr-is-10-custom-fields
> type: backend (feature — UI 폼 렌더 포함)
> agent: backend-engineer (+ frontend-engineer, qa-engineer)
> primary_bc: issue-tracking
> 생성: 2026-06-08

## Brief

신규 FR. 회사가 이슈에 커스텀 필드(예. "급여 영향도")를 직접 정의·관리하는 인프라.
필드 정의 CRUD + 값 저장·검증 + 이슈 폼 동적 렌더. SDD §05 데이터모델에 `issues.custom_fields JSONB` 컬럼 한 줄만 존재(미설계 영역).

**선후행**. FR-IS-10(이 작업, 커스텀 필드) → FR-PM-07(필드 수준 권한, 코어+커스텀 필드 대상). 필드 권한이 커스텀 필드를 대상으로 포함하므로 FR-IS-10이 선행.

**범위 결정 (Maxi 2026-06-08)**. 커스텀 필드를 신규 FR-IS-10으로 신설(FR-PM은 identity-access 전용 시리즈라 부적합). 필드 권한(FR-PM-07)은 별도 PR로 후행.

classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: issue-tracking (단일, cross-BC 없음)
- **새 엔티티**:
  - `CustomFieldDefinition` — 필드 정의. 프로젝트별(`project_id`). key/name/description/type/required/display_order/is_active. Resolution 마스터 테이블 패턴 차용(소프트 삭제, display_order).
  - `CustomFieldOption` — 선택형(SINGLE_SELECT/MULTI_SELECT/RADIO) 타입의 선택지. 정의에 종속.
- **값 저장**: `issues.custom_fields` JSONB (`{field_key: value}`). SDD §05 데이터모델 설계와 일치. 타입/참조무결성은 ApplicationService 책임(JSONB는 스키마리스). GIN 인덱스(SDD §05 line 266 명시).
- **필드 타입**(확장 가능 `FieldType` enum, 1차 10종): SHORT_TEXT · LONG_TEXT · NUMBER · DATE · DATETIME · SINGLE_SELECT · MULTI_SELECT · CHECKBOX · RADIO · URL. cross-BC 참조형(USER/GROUP/VERSION/COMPONENT picker)은 후속 — 타입만 추가.
- **적용 범위**: 프로젝트별 (이슈타입 무관). component/version 선례. Jira식 풀 컨텍스트(프로젝트×이슈타입)는 채택 안 함.
- **새 용어**: "커스텀 필드(Custom Field)", "필드 정의(Field Definition)", "필드 타입(Field Type)" — glossary 추가 대기(Maxi 승인 후).
- **관리 권한**(spec에서 확정): 프로젝트별 스코프 → PROJECT_ADMIN 후보. 신규 권한 코드(MANAGE_CUSTOM_FIELDS) 필요 시 role_permissions 시드 추가 → PermissionSchemaMigrationTest 카운트 영향(learnings: fr-pm-permission-seed-migration-test-coupling). identity-access BC 결합이라 spec에서 분리 검토.
- **기존 결정 충돌**: 없음 (신규 영역). SDD §05 custom_fields 컬럼(미설계) 결선.
- **관련 ADR**: docs/decisions/2026-06-08-custom-fields-model.md (생성)
- **신규 FR 등록 필요**(전수 동기화): fr-index · SDD(02 + 신규 §) · product/issue-tracking · README · CLAUDE 카운트.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
