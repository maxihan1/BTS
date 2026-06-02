# FR-IS-07 — 이슈 Resolution(해결 결과) 필드

> slug: fr-is-07-resolution-resolutions-e2e
> type: migration
> agent: db-engineer (primary; backend/frontend/qa 혼합)
> 생성: 2026-06-02

## Brief

종료(DONE 카테고리) 상태로 전이할 때 Resolution(Fixed/Won't Fix/Duplicate 등)을 필수로
선택하게 하고, Resolution 미설정 시 종료 전이를 거부한다.

- 데이터 모델: resolutions 테이블 + issues.resolution_id
- 백엔드: 종료 전이 가드(Resolution 미설정 시 reject)
- 프론트: 종료 모달(전이 시 Resolution 선택)
- E2E: 종료 시 Resolution 필수 흐름
- BC: issue-tracking. 선행 FR-IS-01(완료) + project-workflow 전이.
- classify: type=migration, agent=db-engineer
- 참조: docs/plan/product/issue-tracking.md §2.1.5

## 도메인 정리

- **BC**: issue-tracking (Resolution 데이터/엔티티). 단, "종료 시 필수" 강제는 project-workflow 게이트 프레임워크와 연관(아래 갈림길).
- **새 엔티티**: `Resolution`(resolutions 테이블: id, key, name, description?, display_order, is_standard) + `Issue.resolutionId`(nullable FK 미적용, BC격리). 표준 세트(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done 등) 불변 + 커스텀 추가 — IssueType 표준 5종 패턴과 동형.
- **새 용어**: glossary "해결 결과(Resolution)" 추가 완료. 상태(open/closed)와 별개 축.
- **핵심 발견 (아키텍처 갈림길)**: project-workflow에 이미 **전이 게이트 검증 프레임워크**가 존재. `RequiredFieldValidator`(`com.bts.workflow.validator.RequiredFieldValidator`)의 docstring 예시가 **정확히 `{ "field": "resolution" }`** — resolution을 염두에 두고 설계됨. `StateCategory.DONE` enum도 존재. 전이 시 `TransitionContext.request.issueFields[field]`로 이슈 필드 검사.
  - **옵션 A (워크플로우 게이트, Jira식)**: DONE 전이에 RequiredFieldValidator(field=resolution) 설정 + issue-tracking이 전이 시 issueFields에 resolution 전달. 기존 프레임워크 재사용, 워크플로우별 설정 가능(유연). 단 project-workflow YAML/seed 설정 변경 동반(2 BC 걸침 가능).
  - **옵션 B (issue-tracking 하드코딩 가드)**: 전이 목표 상태 category==DONE && resolutionId==null → reject(issue-tracking 단독). 단순·단일 BC. 단 항상 강제(워크플로우별 opt-out 불가), 기존 검증 프레임워크 미활용.
  - → **결정: 옵션 A 채택** (2026-06-03 Maxi). RequiredFieldValidator(config 예시가 정확히 `{ "field": "resolution" }`) 재사용 + Jira 방식. issue-tracking은 전이 시 `issueFields`에 resolution 전달(`IssueApplicationService` 전이/가용전이 경로, 현재 summary만 전달). 2 BC 걸침은 bts-plan에서 PR 분리 또는 learning 2026-05-22 선례 적용으로 처리.
- **기존 결정**: 충돌 없음. StateCategory.DONE([[domain/project-workflow]]), 워크플로우 게이트/validator 프레임워크 활용. IssueType 표준 불변 패턴 재사용.
- **관련 ADR**: docs/adr/2026-06-03-resolution-required-on-done-transition.md (옵션 A, 채택).
- **grill-with-docs 스킵**: 백엔드 구조 직접 grep으로 엔티티·게이트 프레임워크·StateCategory 확인. 핵심 결정은 갈림길 AskUserQuestion으로.

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-is-07-resolution-resolutions-e2e.md](../specs/2026-06-03-fr-is-07-resolution-resolutions-e2e.md)

핵심 시나리오.
- 종료(DONE) 전이 선택 시 Resolution 모달 → 선택 후 전이(미선택 시 거부, RequiredField validator).
- 재오픈(DONE→비DONE) 시 resolution_id 자동 clear(모든 전이가 resolution_id=request.resolutionId 영속, 비DONE은 null).
- 표준 5종 seed(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done) + `GET /api/v1/resolutions`. 커스텀 CRUD는 후속(IssueType 선례).

확정 결정.
- 결정 1: 가용 전이 응답에 `toCategory` additive 노출(프론트가 DONE 판별 → 모달 트리거). "서버가 정답지" ADR 일관.
- 결정 2: 커스텀 Resolution CRUD는 이번 범위 제외(후속).
- 결정 3 [Brainstorming BLOCKER]: **옵션 A — validator 단계(availability/execution) 구분 채택**. RequiredField=execution-only, availableTransitions는 availability만 평가(안 그러면 DONE 전이가 목록에서 사라져 이슈를 못 닫는 critical 버그). project-workflow SPI 변경 동반.
- 결정 4: 재오픈 clear는 카테고리 불필요(resolutionId 영속만으로 충족).
- 마이그레이션 = **V010**(V009는 components 선점). init_codegen 미러 필수.

## Brainstorming Check

✅ 통과 (1회 iteration). Critical gap 1건 발견·해소 — availableTransitions가 RequiredField로 DONE 전이를 숨겨 이슈를 못 닫는 버그(결정 3, 옵션 A로 해결). 부수 발견 — 재오픈 clear는 카테고리 불필요(결정 4), 마이그레이션 V009→V010 정정.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
