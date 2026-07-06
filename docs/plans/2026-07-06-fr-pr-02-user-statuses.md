# FR-PR-02 상태 메시지 (이모지 + 텍스트)

> slug: fr-pr-02-user-statuses
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> BC: personalization (물리 모듈: identity-access)
> 생성: 2026-07-06

## Brief

FR-PR-02 — 사용자 상태 메시지(이모지 + 텍스트). Slack 스타일 개인 상태 표시.

- D1. 도메인 — UserStatus
- D2. 명세 — TTL(만료) 옵션
- D3. 데이터 모델 — `user_statuses(user_id, emoji, text, expires_at)`
- D4. 백엔드 — `PATCH /api/v1/users/me/status`
- D5. 백엔드 테스트
- D6. 프론트 UI — 아바타 옆 상태 + 설정 모달
- D7. E2E

선행: §2.1 FR-PR-01(프로필) 완료. classify E2E 키워드로 qa 오판 → feature 수동 정정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
