# FR-NT-04 사용자별 알림 구독 설정 — 스펙

> slug: fr-nt-04-user-notification-subs · BC: notification · 작성: 2026-06-19
> 도메인 결정: [docs/decisions/2026-06-19-fr-nt-04-user-notification-subscription.md](../decisions/2026-06-19-fr-nt-04-user-notification-subscription.md)

## 사용자 시나리오 (Given-When-Then)

**S1. 기본 수신 (opt-out 기본값)**
- Given 사용자가 알림 구독 설정을 한 번도 건드린 적 없다
- When 자신이 수신자인 이슈 이벤트가 발생한다
- Then 관리자 정책이 허용하는 모든 (이벤트×채널) 알림을 받는다 (행 없음 = enabled=true)

**S2. 개인 opt-out**
- Given 사용자가 설정 페이지에서 "이슈 댓글 — 이메일"을 끈다
- When 자신이 수신자인 `issue.commented` 이벤트가 발생한다
- Then 이메일 알림은 받지 않고, 인앱 알림은(켜져 있으면) 받는다

**S3. 관리자 정책과 AND 결합 (reduce-only)**
- Given 관리자가 전역에서 `issue.commented`의 EMAIL 채널 정책을 비활성화했다
- When 사용자가 설정 페이지에서 "이슈 댓글 — 이메일"을 켠다
- Then 사용자 설정과 무관하게 해당 이메일 알림은 발송되지 않는다 (관리자가 끈 것을 개인이 켤 수 없음)

**S4. 설정 페이지 조회/토글**
- Given 사용자가 `/settings/notifications`에 진입한다
- When 페이지가 로드된다
- Then 이벤트 10종 × 채널(인앱/이메일) 매트릭스가 현재 유효 상태로 표시된다
- When 한 셀을 토글한다
- Then 즉시 저장되고(낙관적 갱신), 새로고침 후에도 유지된다

**S5. 인증 격리**
- Given 미인증 사용자
- When `GET/PATCH /api/v1/users/me/notifications` 호출
- Then 401 (currentActorId 패턴). 경로에 userId가 없어 타인 설정 접근(IDOR) 불가

## 기능 요구사항 (FR)

- **FR1.** 사용자별 (eventType × channel) 단위 구독 상태를 저장·조회·변경한다.
- **FR2.** 행이 없는 조합은 `enabled=true`(수신)로 간주한다 (opt-out 기본).
- **FR3.** 설정 가능 채널은 `IN_APP`, `EMAIL` 2종으로 한정한다. 그 외 채널 PATCH 요청은 400.
- **FR4.** 설정 대상 이벤트는 `NotificationEventType` 10종 전부.
- **FR5.** `GET /api/v1/users/me/notifications` — 현재 사용자의 (10×2=20) 유효 매트릭스를 반환한다.
- **FR6.** `PATCH /api/v1/users/me/notifications` — 구독 항목(들)을 upsert한다. 본인 것만.
- **FR7.** `NotificationWorker.dispatch`는 수신자 해석 직후, 채널 발송 직전에 사용자 구독으로 필터한다.
  발송 = `adminPolicyEnabled(event,role,channel) AND userSubscribed(user,event,channel)`.
  `userSubscribed`는 행이 없거나 `enabled=true`면 true, `enabled=false` 행이 있으면 false.
  **필터는 channel ∈ {IN_APP, EMAIL}인 수신자에만 적용**한다(설정 가능 채널 한정). 그 외 채널 수신자는
  구독과 무관하게 통과시킨다. 이벤트당 **1회 배치 조회**로 비활성 행만 가져와 필터한다(N+1 금지, NFR1).
- **FR8.** 설정 페이지 UI — `/settings/notifications` 이벤트×채널 토글 매트릭스.

## 비기능 요구사항 (NFR)

- **NFR1.** 워커 구독 필터는 알림 p95<1s 예산을 침해하지 않는다. 이벤트당 1회 배치 조회
  (`event_type, channel` 고정 + 수신자 userId 집합 → 비활성 행만 조회). N+1 금지.
- **NFR2.** BC 격리 — notification BC는 identity-access users 테이블을 조회하지 않는다. user_id UUID 직접 저장.
- **NFR3.** PATCH 멱등 — 같은 값 재전송 시 no-op, 200. `ON CONFLICT (user_id, event_type, channel)` upsert.
- **NFR4.** 시각은 도메인이 생성하지 않는다. Clock 주입 ([[authcontroller-revokesession-timebomb]] 교훈).

## API 인터페이스 (REST)

### GET /api/v1/users/me/notifications
- 인증 필수. 응답 200:
```json
{ "data": { "subscriptions": [
  { "eventType": "issue.created", "channel": "IN_APP", "enabled": true },
  { "eventType": "issue.created", "channel": "EMAIL", "enabled": true },
  ... (10 eventType × {IN_APP, EMAIL} = 20개)
] } }
```
- `enabled`는 유효값(저장 행 있으면 그 값, 없으면 true). 라벨은 응답에 없음 — 프론트가 i18n으로 매핑(정책 테이블 선례).

### PATCH /api/v1/users/me/notifications
- 인증 필수. 요청:
```json
{ "subscriptions": [
  { "eventType": "issue.commented", "channel": "EMAIL", "enabled": false }
] }
```
- 각 항목 upsert. 알 수 없는 eventType/channel, 또는 channel ∉ {IN_APP, EMAIL} → 400 (enum 파싱, NotificationPolicyController 선례).
- 응답 200 + 갱신된 전체 매트릭스(GET와 동일 형태) — 프론트 캐시 동기화.

## 데이터 모델 변경

신규 테이블 (Flyway **V404**, notification 모듈) + `init_codegen.sql` 미러 ([[jooq-init-codegen-mirror]]):
```sql
CREATE TABLE user_notification_subs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL,
  event_type  TEXT NOT NULL,         -- NotificationEventType.wireValue (예: issue.created)
  channel     TEXT NOT NULL,         -- Channel.name (IN_APP | EMAIL)
  enabled     BOOLEAN NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, event_type, channel)
);
-- 워커 배치 조회용: 특정 (event,channel)에서 비활성한 사용자만 빠르게 조회
CREATE INDEX idx_user_notif_subs_disabled
  ON user_notification_subs (event_type, channel, user_id)
  WHERE enabled = false;
```

## 엣지 케이스

- **EC1.** PATCH에 channel=SLACK/TEAMS/WEBHOOK → 400 (설정 불가 채널).
- **EC2.** PATCH에 미지원 eventType wireValue → 400.
- **EC3.** 같은 (user,event,channel) 중복 토글(enabled 동일) → 멱등 200, updated_at만 갱신.
- **EC4.** enabled=true로 되돌리기 → 행 upsert enabled=true (행 삭제 아님, 명시 저장). 효과는 기본값과 동일.
- **EC5.** 워커: 수신자 0명이면 구독 조회 생략. 수신자 있어도 비활성 행 0건이면 전원 발송.
- **EC6.** 미인증 GET/PATCH → 401. 비-UUID 주체 → 401.
- **EC7.** 워커 dedup과의 관계 — 구독 필터는 `insertIfAbsent` 이전에 적용(끈 알림은 notifications 행 자체를 만들지 않음).
- **EC8.** 한 PATCH에 여러 항목 + 일부 무효 → 전체 400(부분 적용 금지, 검증 후 일괄 upsert).
- **EC9.** 워커 수신자 channel ∉ {IN_APP, EMAIL}(예: 향후 SLACK fan-out) → 구독 필터 통과(항상 발송).
- **EC10.** 빈 PATCH(`subscriptions: []`) → no-op, 현재 매트릭스 200 반환.
- **EC11.** GET/PATCH 매트릭스 정렬은 NotificationEventType 선언 순서 × (IN_APP, EMAIL) 고정(결정성).
- **EC12.** 프론트 이벤트/채널 라벨은 기존 정책 UI i18n(ko.ts) 재사용 — 신규 라벨 invent 금지, 누락 시 보강.

## 제약 조건

- notification BC 단일. cross-BC import 금지.
- enum 카탈로그(NotificationEventType/Channel)는 기존 것 재사용 — 신규 enum 없음.
- 채널 화이트리스트(IN_APP/EMAIL)는 단일 출처(상수/도메인)로 정의해 컨트롤러·워커·프론트 drift 방지.

## 측정 가능한 완료 기준

1. `GET /me/notifications`가 설정 이력 없는 사용자에게 20개 전부 enabled=true 반환.
2. `PATCH`로 (issue.commented, EMAIL, false) 저장 후 GET에 반영, 재PATCH 멱등.
3. 워커 통합 테스트: enabled=false 행이 있는 수신자에게 해당 채널 알림 미발송, 다른 채널/사용자는 발송.
4. 관리자 정책 OFF + 사용자 ON → 미발송(AND 결합) 검증.
5. channel=SLACK PATCH → 400, 미인증 → 401.
6. `/settings/notifications` 토글 → 저장 → 새로고침 유지 (E2E).
7. ktlintCheck/detekt/test 그린, 프론트 lint/typecheck/test 그린.

## Brainstorming Check

✅ 통과 (1회 self-review). gap 4건 발견 후 보강.
- 워커 구독 필터의 채널 범위를 IN_APP/EMAIL로 한정, 그 외 채널 통과(FR7, EC9).
- 이벤트당 1회 배치 조회로 N+1 차단(FR7, NFR1).
- 프론트 이벤트/채널 라벨은 기존 정책 UI i18n 재사용(EC12).
- GET/PATCH 매트릭스 정렬 결정성(EC11).
- 채널 화이트리스트 단일 출처로 controller·worker·frontend drift 차단(제약 조건).
