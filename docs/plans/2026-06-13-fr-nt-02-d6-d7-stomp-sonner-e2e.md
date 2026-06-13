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

## 스펙

전체 스펙: [docs/specs/2026-06-13-fr-nt-02-d6-d7-stomp-sonner-e2e.md](../specs/2026-06-13-fr-nt-02-d6-d7-stomp-sonner-e2e.md)

핵심 요약.
- D6: `@stomp/stompjs`로 `/ws` 연결(CONNECT 헤더 `Authorization: Bearer`) → `/user/queue/notifications` 구독 → 수신 payload Zod 파싱 → sonner `toast`. 인증 상태 연동(연결/해제), 재연결 시 토큰 갱신.
- D7: Playwright `routeWebSocket`으로 STOMP 핸드셰이크+MESSAGE 프레임 주입 → 토스트 표시 검증.

**Maxi 확정 결정 2건.**
- ① `@stomp/stompjs` 신규 의존성 추가 승인(절대 규칙 #17).
- ② E2E 전략 = Playwright `routeWebSocket`(실 클라 경로 관통, 백엔드 불필요).

## Brainstorming Check

✅ 통과 (직접 gap 점검 — 완료 FR 후속 D6/D7 경량). Maxi 결정 필요 gap 없음. 구현 디테일(brokerURL/connectHeaders 동적갱신/__root 마운트/중복구독 방지)은 plan에서 해소.

## Plan

> 분해 방식: 직접 TDD 분해(선례 `bts-review-plan autoplan overkill`와 일관 — 완료 FR 후속 D6/D7). BTS 형식(메타+RED/GREEN/REFACTOR) 준수.
> 백엔드 계약은 `## 도메인 정리` 표 참조. brokerURL = `${location.protocol==='https:'?'wss:':'ws:'}//${location.host}/ws` (dev=vite `/ws` 프록시, prod=동일 호스트).

### Task 1. @stomp/stompjs + vite /ws 프록시 + Zod 스키마 + STOMP 클라이언트 추상

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/package.json`, `apps/web/pnpm-lock.yaml`, `apps/web/vite.config.ts`, `apps/web/src/api/notifications-stream.ts`, `apps/web/src/api/notifications-stream.test.ts`]
- depends-on: []

**RED**:
- 파일: `apps/web/src/api/notifications-stream.test.ts`
- `@stomp/stompjs`의 `Client`를 mock(`vi.mock('@stomp/stompjs')`). `createNotificationStream({ getToken, onMessage })` 검증:
  - Client가 `brokerURL` = `ws://localhost/ws`(jsdom `location.host` 기반)로 생성된다.
  - `connectHeaders.Authorization` === `Bearer <getToken()>`.
  - `onConnect` 콜백 실행 시 `client.subscribe('/user/queue/notifications', cb)` 호출.
  - 구독 콜백에 유효 JSON 프레임(`{ body: JSON.stringify(payload) }`) 전달 시 `inAppNotificationSchema` 파싱 후 `onMessage(parsed)` 호출.
  - body가 스키마 불일치/비JSON이면 `onMessage` 미호출 + `console.warn` 1회(앱 크래시 금지).
- 실패(예상): `createNotificationStream`/`inAppNotificationSchema` 미존재.

**GREEN**:
- `apps/web/src/api/notifications-stream.ts` — `inAppNotificationSchema`(스펙 §3) + `createNotificationStream` 구현. `Client({ brokerURL, connectHeaders: ()=>... 또는 beforeConnect 토큰 갱신, reconnectDelay, onConnect: subscribe })`. 메시지 핸들러에서 `JSON.parse`→`safeParse`→성공 시 onMessage, 실패 시 warn.
- `vite.config.ts` proxy에 `'/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: true }` 추가.
- `package.json`에 `@stomp/stompjs` 추가 후 `pnpm install`로 lockfile 갱신.

**REFACTOR**:
- 상수 추출(`WS_PATH='/ws'`, `DESTINATION='/user/queue/notifications'`). 파일 L1 한글 헤더 주석. KDoc.

**검증**: `pnpm --filter web test notifications-stream` + `pnpm --filter web exec tsc -p tsconfig.app.json --noEmit`

### Task 2. useNotificationStream hook (인증 연동 + toast)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/notifications/useNotificationStream.ts`, `apps/web/src/notifications/useNotificationStream.test.tsx`]
- depends-on: [1]

**RED**:
- 파일: `apps/web/src/notifications/useNotificationStream.test.tsx`
- `createNotificationStream`(Task1) + `sonner`의 `toast` mock. 검증:
  - 인증 상태(`useAuthStore` 시드 accessToken)면 `createNotificationStream` 활성화(`activate` 호출).
  - 스트림 `onMessage(payload)` → `toast(payload.title, { description: payload.body ?? undefined })`(S1).
  - body=null → `toast(title, { description: undefined })`(S2, description 미표시).
  - 미인증이면 스트림 생성/활성화 안 함(S3).
  - 언마운트 시 `deactivate` 호출(S4). 재연결 토큰 갱신은 getToken이 store 최신값 읽음으로 보장(S5).
- 실패(예상): `useNotificationStream` 미존재.

**GREEN**:
- `apps/web/src/notifications/useNotificationStream.ts` — `useEffect`(deps: isAuthenticated)로 인증 시 `createNotificationStream({ getToken: ()=>useAuthStore.getState().accessToken, onMessage: p=>toast(...) })` 1회 생성(`useRef`로 중복 방지), cleanup에서 `deactivate`.

**REFACTOR**:
- toast 매핑 분리(작은 순수 함수). 파일 L1 한글 헤더 주석.

**검증**: `pnpm --filter web test useNotificationStream` + tsc

### Task 3. __root.tsx 마운트 (인증 시 스트림 활성)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/__root.tsx`, `apps/web/src/routes/__root.test.tsx`]
- depends-on: [2]

**RED**:
- 파일: `apps/web/src/routes/__root.test.tsx`(없으면 신규)
- `useNotificationStream` mock. `RootLayout` 렌더 시 `useNotificationStream`이 호출되는지 검증(hook이 내부에서 인증 가드). 기존 라우팅/Header 동작 회귀 0.
- 실패(예상): `RootLayout`이 hook 미호출.

**GREEN**:
- `RootLayout`에 `useNotificationStream()` 한 줄 추가(hook 내부에서 isAuthenticated 판정).

**REFACTOR**: 불필요 시 생략.

**검증**: `pnpm --filter web test __root` + `pnpm --filter web test`(전체 단위 회귀) + tsc

### Task 4. E2E — routeWebSocket STOMP 알림 수신 → 토스트

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/fixtures/stomp-fixtures.ts`, `apps/web/e2e/fr-nt-02-inapp-notification.spec.ts`]
- depends-on: [3]

**RED/E2E**(Playwright는 실패→통과 사이클을 spec 작성으로):
- `stomp-fixtures.ts` — STOMP 1.2 프레임 빌더: `connectedFrame()`, `messageFrame(destination, subId, jsonBody)`. 프레임 종료 `\x00`.
- `fr-nt-02-inapp-notification.spec.ts`:
  - `page.routeWebSocket('**/ws', ws => { ws.onMessage(frame => { CONNECT→ws.send(connectedFrame()); SUBSCRIBE→subId 캡처 }) })`.
  - `loginAsAlice(page)` 후, 테스트가 `messageFrame('/user/queue/notifications', subId, payload)` 푸시.
  - S1: title+body 토스트 표시 검증(`getByText` 컨테이너 한정, strict mode 회피 — `ui-pr-defer-e2e-regression-latent`).
  - S2: body=null → title만, 깨짐 없음.
- 기존 E2E 회귀 확인: 신규 spec이라 기존 셀렉터 영향 없음 — `pnpm --filter web exec playwright test`로 인접 spec 그린 확인.

**검증**: `pnpm --filter web exec playwright test fr-nt-02-inapp-notification` + 기존 E2E 회귀 0

## Plan 메타

- task 수: 4
- 의존성: 1→2→3→4 (프론트 특성상 자연 직렬 — 클라 추상→hook→마운트→E2E). 파일 겹침 없음.
- 예상 wave 수: 4 (직렬). 병렬 여지 적음.
- agent: Task1~3 frontend-engineer, Task4 qa-engineer.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR).
- 추가 검증: tsc(tsconfig.app.json) 필수 동반(vitest 타입무시), playwright(Task4).

## 리뷰 결과 (← /bts-review-plan 채움)
