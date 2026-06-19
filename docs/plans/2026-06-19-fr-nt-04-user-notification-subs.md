# FR-NT-04 — 사용자별 알림 구독 설정

> slug: fr-nt-04-user-notification-subs
> type: feature
> agent: backend-engineer (프론트/E2E task는 plan 메타 agent override)
> primary_bc: notification
> 생성: 2026-06-19

## Brief

**사용자 원문**. "fr-nt-04 진행하자"

**FR-NT-04 — 사용자별 알림 구독 설정** (notification BC, `docs/plan/product/notification-dashboard.md §2.4`)
사용자가 **이벤트 타입 × 채널** 단위로 알림 수신 여부를 opt-in/out 할 수 있게 한다.

- D1. 도메인 — UserSubscription
- D2. 명세 — opt-in/out 단위
- D3. 데이터 모델 — `user_notification_subs(user_id, event_type, channel, enabled)`
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/notifications`
- D5. 백엔드 테스트
- D6. 프론트 UI — 개인 설정 페이지 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**classify 결과**. type=feature(qa 오판정 override), agent=backend-engineer, primary_bc=notification, slug=fr-nt-04-user-notification-subs

**선행**. §2.1 FR-NT-01(알림 정책) 완료, §2.2 FR-NT-02(인앱+이메일 채널) 완료, §2.3 FR-NT-03(수신자 정책) 백엔드 완료.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
