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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
