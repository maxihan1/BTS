# workflow-scheme/already-authed E2E 회귀 17건 — 로그인 fixture 2단계 통일

> slug: e2e-login-fixture-2step
> type: qa
> agent: qa-engineer
> 생성: 2026-06-25

## Brief

FR-AU-07(identifier-first 2단계 로그인) 도입 후, 일부 E2E 로그인 헬퍼가 옛 1단계 방식으로 잔존해
전체 E2E 실행 시 17건 실패. 회귀 수정(구현 코드 src/ 무수정, E2E fixture/spec만).

- 실패 원인: 새 로그인 첫 화면엔 '사용자명' 필드가 없음(이메일+계속만) → 옛 1단계 헬퍼가 `getByLabel('사용자명')` 30초 timeout.
- 실패 17건: workflow-scheme-* 16건(`workflow-scheme-fixtures.ts`의 옛 loginAsAlice 공유) + already-authed.spec.ts 1건(인라인 1단계 직접 작성).
- 정상: inbox 등은 `issue-fixtures.ts`의 2단계 loginAsAlice 사용(이미 수정됨) → 통과.
- 메모리 [[e2e-loginasalice-fixture-fr-au-07-regression]] 미해결 항목.

## fast-track 사유

type=qa + 회귀 수정(도메인 모델 변경 0, 스펙=옛 동작 복원으로 자명) → domain/spec/review-plan 스킵, plan 직행.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-codereview 채움)
