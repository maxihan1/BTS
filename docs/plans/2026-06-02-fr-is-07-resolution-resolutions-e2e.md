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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
