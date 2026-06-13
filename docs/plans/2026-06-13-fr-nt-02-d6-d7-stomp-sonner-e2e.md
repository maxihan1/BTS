# FR-NT-02 D6/D7 — 인앱 알림 채널 프론트엔드(STOMP + 토스트) + E2E

> slug: fr-nt-02-d6-d7-stomp-sonner-e2e
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7)
> BC: notification
> 생성: 2026-06-13

## Brief

FR-NT-02의 프론트(D6) + E2E(D7) 마무리.
- 백엔드 인앱 채널은 PR #126(squash 762a8228)으로 머지 완료 — pgmq consumer → STOMP `convertAndSendToUser`로 사용자별 실시간 발송까지 동작.
- D6: 프론트 STOMP 클라이언트(@stomp/stompjs) 연결 + 수신 알림을 sonner 토스트로 표시.
- D7: E2E 테스트(Playwright) — STOMP 실시간 알림 수신 시나리오.

classify: type=ui, agent=frontend-engineer (classifier가 qa로 치우쳐 보정), BC=notification

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
