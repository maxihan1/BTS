# ADR — FR-NT-04 사용자별 알림 구독 설정 (UserSubscription)

> 날짜: 2026-06-19
> 상태: 채택
> BC: notification
> 관련 FR: FR-NT-04 (`docs/plan/product/notification-dashboard.md §2.4`)
> 선행 ADR: [[2026-06-11-notification-policy-bc-bootstrap]] · [[2026-06-12-notification-inapp-channel-delivery]]

## 맥락

FR-NT-01(`NotificationPolicy`)은 **관리자**가 전역/프로젝트 단위로 (이벤트×역할×채널) 발송 여부를 정한다.
FR-NT-04는 그 위에 **개인**이 자신이 받을 알림을 (이벤트×채널) 단위로 끄고 켤 수 있게 한다.

기존 발송 파이프라인(`NotificationWorker.dispatch`):
```
① PolicyEvaluator.evaluate(eventType, projectKey)  → List<PolicyMatch(role, channel)>   (관리자 정책)
② EventRecipientResolver.resolve(event, matches)   → List<ResolvedRecipient(userId, channel)> (수신자)
③ for each recipient → buildNotification → insertIfAbsent → channel.send                  (채널)
```

## 결정

### D1. opt-out(기본 수신) 모델

사용자가 명시적으로 끄지 않은 (이벤트×채널)은 **수신**한다. DB에 행이 없으면 `enabled=true`로 간주한다.
- 근거: Jira/GitHub 업계 표준. 신규 사용자가 설정을 안 해도 알림을 받아 누락 위험이 낮다.
- Maxi 확정(2026-06-19).

### D2. 사용자 구독 = 관리자 정책과 AND 결합 (reduce-only)

```
발송 = adminPolicyEnabled(event, role, channel) AND userSubscribed(user, event, channel)
```
사용자는 관리자가 **켜 둔** 알림을 끌 수만 있다. 관리자가 끈 것을 개인이 켤 수는 없다.
- 근거: 관리자 정책이 "가능 집합", 개인 구독은 그 안에서의 "거부 집합". 권한·정책 일관성.
- 통합 지점: ② 이후 ③ 직전. `NotificationWorker`가 resolved recipient를 사용자 구독으로 필터링한다.

### D3. 설정 가능 채널 = IN_APP + EMAIL

`Channel` enum 5종 중 IN_APP·EMAIL만 사용자별 설정 대상. SLACK(slack-integration BC 미구현)·TEAMS(범위 밖)·WEBHOOK(FR-NT-05 전환 post-action, per-user 모델 아님)은 제외.
- product 문서 §2.2 "채널 구독 on/off는 FR-NT-04" 표기와 일치. Maxi 확정.

### D4. 이벤트 타입 = NotificationEventType 10종 전부

현재 카탈로그 10종 모두 (event×{IN_APP,EMAIL}) 토글 대상. 일관성·단순성. Maxi 확정.

### D5. 저장 모델 — explicit 토글 행 + sparse

테이블 `user_notification_subs(user_id, event_type, channel, enabled)`. 행이 존재하면 그 `enabled`를 따르고, 없으면 D1대로 기본 `true`. PATCH는 upsert(끄기=enabled false 행 삽입/갱신, 켜기=enabled true 행 또는 행 삭제). product 문서 스키마와 일치.
- `UNIQUE(user_id, event_type, channel)` — 조합당 1행.

### D6. BC 격리 — user_id는 UUID 직접 저장

notification BC는 identity-access의 users 테이블을 조회하지 않는다. `Notification.recipientUserId`가 이미 UUID를 직접 보유하는 선례를 따른다. (FR-NT-01의 `projectKey` 문자열 저장과 동일 정신.)

## 영향

- 신규 도메인 `UserSubscription`(userId, eventType, channel, enabled).
- 신규 테이블 V404 `user_notification_subs` + init_codegen 미러([[jooq-init-codegen-mirror]]).
- `NotificationWorker.dispatch`에 사용자 구독 필터 추가(D2 통합 지점).
- 신규 API `GET/PATCH /api/v1/users/me/notifications` (본인 것만, currentActorId 패턴).
- 프론트 개인 설정 페이지(이벤트×채널 매트릭스 토글) + E2E.

## 대안 (기각)

- opt-in(기본 차단): 알림 누락 위험 + 신규 사용자 경험 저하 → 기각(D1).
- 사용자 override가 관리자 정책을 켤 수 있게(OR 결합): 정책 일관성 붕괴, 관리자 통제 무력화 → 기각(D2).
- 5종 채널 전부 노출: SLACK/TEAMS/WEBHOOK은 per-user 발송 경로가 없어 무의미 → 기각(D3).
