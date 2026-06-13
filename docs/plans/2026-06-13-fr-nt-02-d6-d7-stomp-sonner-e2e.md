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

## 도메인 정리

- **BC**: notification (단일 — 프론트는 이 BC가 정한 계약을 소비만, BC 격리 위반 없음)
- **새 용어**: 없음. 백엔드 #126(762a8228)이 인앱 채널 도메인/STOMP 계약을 이미 정립. 프론트는 소비자.
- **기존 결정**: ADR [`docs/decisions/2026-06-12-notification-inapp-channel-delivery.md`] 준수. 충돌 없음.
- **관련 ADR(부트스트랩)**: `docs/decisions/2026-06-11-notification-policy-bc-bootstrap.md`

### 백엔드 STOMP 계약 (코드 직접 검증 — 프론트가 그대로 따름)

검증 출처: `backend/modules/notification/src/main/kotlin/com/bts/notification/`

| 항목 | 값 | 근거 |
|---|---|---|
| WebSocket endpoint | `/ws` (SockJS 없음, 순수 WS) | `config/WebSocketConfig.kt:39,68` |
| Simple broker prefix | `/queue` | `WebSocketConfig.kt:43,69` |
| User destination prefix | `/user` | `WebSocketConfig.kt:44,70` |
| 발송 destination | `convertAndSendToUser(recipientUserId, "/queue/notifications", payload)` | `channel/InAppChannelSender.kt:45-47,60` |
| **클라 구독 경로** | `/user/queue/notifications` (Spring이 userId 라우팅 → 서버측 `/user/{userId}/queue/notifications`) | `InAppChannelSender.kt:14-16` |
| CONNECT 인증 | STOMP CONNECT native header `Authorization: Bearer <accessToken>` | `config/StompAuthChannelInterceptor.kt:21,65,84-89` |
| 토큰 제약 | JWT 전용. PAT(`pat_` prefix) 거부. SEND frame 거부(push-only). CONNECT 시점만 검증 | `StompAuthChannelInterceptor.kt:36-39,66-74,111` |
| 사용자 식별 | JWT `sub`(subject) claim = userId UUID | `StompAuthChannelInterceptor.kt:45,101` |
| 메시지 크기 한도 | 64KB | `WebSocketConfig.kt:62,73` |

### 메시지 페이로드 (`channel/InAppNotificationPayload.kt:7-13`)

```
id: UUID (string)        — 알림 식별자
eventType: String        — wireValue, 예 "issue.assigned" (소문자.점 표기, FR-NT-01 enum 미러와 동일)
issueKey: String?        — 이슈키 또는 null
title: String            — 토스트 제목
body: String?            — 토스트 본문(nullable)
occurredAt: Instant      — ISO 8601 문자열
```
기본 Jackson 직렬화(카멜케이스, 날짜 ISO 문자열). 프론트 Zod 스키마는 이 형식을 그대로 미러(invent 금지 — `frontend-zod-backend-dto-contract-gap` 교훈).

### 프론트 인프라 현황 (조사 결과)

- ✅ `sonner@2.0.7` 설치 + `<Toaster />` `main.tsx`에 마운트됨 (`components/ui/sonner.tsx`)
- ✅ accessToken = `auth/authStore.ts` (Zustand + sessionStorage). STOMP connect 토큰은 `useAuthStore.getState().accessToken`
- ✅ FR-NT-01(#124) 선례 — `api/notification-policies.ts` (enum 미러 `issue.assigned` 등 / 대문자 errorCode 관례)
- ✅ E2E 로그인 fixture `loginAsAlice` (`e2e/fixtures/session-fixtures.ts`)
- ❌ `@stomp/stompjs` **미설치** → 신규 의존성 추가 필요 (절대 규칙 #17 — 새 라이브러리 Maxi 확인)
- ⚠️ E2E의 STOMP/WebSocket 처리 전략 미결 → spec 단계에서 결정 (MSW WebSocket vs Playwright routeWebSocket vs 실 백엔드)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
