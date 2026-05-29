# project-workflow detekt 57건 정리 (코드 위생 cleanup)

> slug: project-workflow-detekt-57-cleanup
> type: chore (fast-track — domain/spec/plan-review skip)
> agent: backend-engineer
> 생성: 2026-05-29

## Brief

project-workflow 모듈의 PRE_EXISTING detekt 위반 57건을 일괄 정리한다. 기능 작업과 분리된
코드 위생(lint) 정리 PR이다. PR #38에서 Maxi 승인으로 deferral된 잔여 부채.

- PR #37 선례 `detekt-baseline-module-pattern` (모듈 detekt-baseline.xml 동결 또는 실제 코드 정리)을 따른다.
- shared-kernel:detekt의 Kotlin 2.0.10 vs 1.9.25 버전충돌도 함께 점검하되, 버전충돌인지 룰위반인지 먼저 재확인한다.
- 검증은 캐시 false-green을 피하려 반드시 `./gradlew --rerun-tasks` 로 한다.
- gradle 루트는 `backend/`, 모듈 경로는 `:modules:project-workflow`.

classify 결과: type=backend로 판정됐으나 Maxi 결정으로 chore fast-track (lint 정리는 도메인/스펙/plan리뷰 부적합).

## 도메인 정리

(fast-track skip — 코드 위생 작업, 도메인 변경 없음)

## 스펙

(fast-track skip — 사용자 시나리오/FR 없음)

## Brainstorming Check

(fast-track skip)

## Plan (← /bts-plan 채움)

## 리뷰 결과

(fast-track skip — plan 리뷰 생략, 게이트 1에서 Maxi 직접 검토)
