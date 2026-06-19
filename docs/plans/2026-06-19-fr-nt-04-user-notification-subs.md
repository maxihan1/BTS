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

## 도메인 정리

- **BC**: notification (단일 BC, cross-BC 없음 — user_id는 UUID 직접 저장, `Notification.recipientUserId` 선례)
- **영향 엔티티**: `UserSubscription`(신규 도메인), `NotificationWorker`(필터 통합), `Channel`/`NotificationEventType`(기존 enum 재사용)
- **새 용어**: **UserSubscription (사용자 알림 구독)** — 사용자별 (이벤트타입 × 채널) opt-out 설정. glossary 추가 후보(Maxi 승인 대기)
- **발송 파이프라인 통합 지점**: `NotificationWorker.dispatch` ②(수신자 해석) 이후 ③(채널 발송) 직전 — resolved recipient를 사용자 구독으로 필터
- **핵심 결정 (Maxi 확정 2026-06-19, ADR 박제)**:
  - D1. **opt-out 기본 수신** — 행 없으면 enabled=true
  - D2. **관리자 정책과 AND 결합** — 발송 = adminPolicy AND userSubscribed. 사용자는 끄기만 가능(reduce-only)
  - D3. **채널 = IN_APP + EMAIL만** (SLACK/TEAMS/WEBHOOK 제외)
  - D4. **이벤트 = NotificationEventType 10종 전부**
  - D5. **저장 = explicit 토글 행 + sparse**, `UNIQUE(user_id, event_type, channel)`
  - D6. **BC 격리** — user_id UUID 직접 저장
- **데이터 모델**: `user_notification_subs(user_id, event_type, channel, enabled)` — Flyway **V404** + init_codegen 미러
- **API**: `GET/PATCH /api/v1/users/me/notifications` (본인 것만, `currentActorId` 401 패턴 — NotificationPolicyController 선례)
- **마이그레이션 번호**: 최신 V403(notification) → 신규 **V404**
- **기존 결정 충돌**: 없음. FR-NT-01 정책과 AND 결합으로 공존
- **관련 ADR**: [docs/decisions/2026-06-19-fr-nt-04-user-notification-subscription.md](../decisions/2026-06-19-fr-nt-04-user-notification-subscription.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-nt-04-user-notification-subs.md](../specs/2026-06-19-fr-nt-04-user-notification-subs.md)

핵심 시나리오 3줄 요약.
- 설정 이력 없으면 관리자 정책이 허용하는 모든 알림 수신(opt-out 기본).
- `/settings/notifications`에서 이벤트×채널(인앱/이메일) 토글 → 즉시 저장, 끈 조합은 미수신.
- 발송 = 관리자 정책 AND 사용자 구독. 관리자가 끈 것은 개인이 켤 수 없음(reduce-only).

핵심 계약.
- API: `GET/PATCH /api/v1/users/me/notifications` (본인만, 401 패턴). PATCH upsert.
- 데이터: V404 `user_notification_subs` + init_codegen 미러. `UNIQUE(user_id,event_type,channel)`.
- 통합: `NotificationWorker.dispatch` 수신자 배치 필터(IN_APP/EMAIL 한정, N+1 금지).

## Brainstorming Check

✅ 통과 (1회 self-review, gap 4건 보강 — 워커 채널 범위/배치조회/i18n 라벨 재사용/매트릭스 정렬 결정성).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
