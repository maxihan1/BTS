# FR-NT-02 D6/D7 — 인앱 알림 프론트(STOMP + 토스트) + E2E 스펙

> slug: fr-nt-02-d6-d7-stomp-sonner-e2e · type: ui · BC: notification · 작성: 2026-06-13
> 백엔드 계약 출처: plan `## 도메인 정리` (코드 직접 검증). 기존 결정: ADR 2026-06-12-notification-inapp-channel-delivery.md

## 0. 스코프 (정본 product/notification-dashboard.md §2.2)

- **D6**: STOMP 클라이언트 연결 + 수신 알림을 sonner 토스트로 표시. (frontend-engineer)
- **D7**: E2E — STOMP 실시간 알림 수신 → 토스트 검증. (qa-engineer)
- **범위 밖**: 알림 센터/벨 아이콘/안읽음 카운트/목록(별도), 이메일·Webhook 채널(후속 PR), 알림 dedup, 멀티탭 조율.

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 실시간 수신**: Given 로그인한 사용자가 앱에 머무름, When 본인 대상 인앱 알림이 백엔드에서 STOMP로 푸시됨, Then 화면 우하단에 sonner 토스트로 title(+body) 표시.
- **S2 본문 없는 알림**: Given body가 null인 알림, When 수신, Then title만 표시(description 없이), 깨지지 않음.
- **S3 인증 연동 연결**: Given 미인증 상태, Then STOMP 연결 시도 안 함. When 로그인 성공, Then 연결 + 구독 시작.
- **S4 로그아웃 해제**: Given 연결된 상태, When 로그아웃, Then STOMP 연결 해제(리스너/소켓 정리, 누수 없음).
- **S5 토큰 만료 재연결**: Given accessToken 만료로 소켓 끊김, When 자동 재연결, Then 최신 accessToken으로 CONNECT 재시도(만료 토큰 재사용 금지).

## 2. 기능 요구사항 (FR)

- **FR-1** STOMP 클라이언트: `@stomp/stompjs` `Client`로 `ws(s)://<host>/ws` 연결. CONNECT 시 native header `Authorization: Bearer <accessToken>`.
- **FR-2** 구독: 연결 성공(onConnect) 시 `/user/queue/notifications` 구독. (Spring user-destination이 userId 라우팅)
- **FR-3** 메시지 파싱: 수신 프레임 body(JSON)를 Zod 스키마로 검증. 백엔드 `InAppNotificationPayload` 미러 — invent 금지(`frontend-zod-backend-dto-contract-gap`).
- **FR-4** 토스트: 파싱 성공 시 `toast(title, { description: body ?? undefined })`. 파싱 실패 시 토스트 미표시 + 콘솔 경고(앱 크래시 금지).
- **FR-5** 생명주기: 인증 상태(`useIsAuthenticated`)에 연동 — 인증 시 연결, 미인증/로그아웃 시 해제. 컴포넌트 언마운트 시 `client.deactivate()`.
- **FR-6** 토큰 갱신: 재연결 시 `beforeConnect`(또는 connectHeaders 동적 평가)에서 `useAuthStore.getState().accessToken` 최신값을 다시 읽어 CONNECT.

## 3. 계약 (백엔드 #126, 코드 검증 완료)

| 항목 | 값 |
|---|---|
| endpoint | `/ws` (순수 WS, SockJS 없음 → sockjs-client 불필요) |
| 구독 destination | `/user/queue/notifications` |
| CONNECT 헤더 | `Authorization: Bearer <accessToken>` (JWT 전용, PAT 거부) |
| payload | `{ id:UUID, eventType:string, issueKey:string|null, title:string, body:string|null, occurredAt:ISO8601 }` |

### Zod 스키마 (프론트 미러)
```ts
const inAppNotificationSchema = z.object({
  id: z.string().uuid(),
  eventType: z.string(),
  issueKey: z.string().nullable(),
  title: z.string(),
  body: z.string().nullable(),
  occurredAt: z.string(),  // ISO 8601, Date 변환 불요(표시만)
})
```

## 4. 파일 구조 (예정)

- `apps/web/src/api/notifications-stream.ts` — STOMP `Client` 생성 + 구독 추상(순수 함수, 테스트 가능). brokerURL 구성, connectHeaders, onMessage 콜백.
- `apps/web/src/api/notifications-stream.test.ts` — 단위 테스트(Client mock으로 구독 경로/헤더/파싱/콜백 검증).
- `apps/web/src/notifications/useNotificationStream.ts` (또는 components) — 인증 연동 hook. 메시지 → `toast`.
- `apps/web/src/notifications/useNotificationStream.test.tsx` — 인증 연동/toast 호출/해제 검증.
- 마운트: `apps/web/src/routes/__root.tsx`(인증 시 hook 활성) 또는 `main.tsx` Toaster 인접. (plan에서 확정)
- `apps/web/e2e/fr-nt-02-inapp-notification.spec.ts` — Playwright routeWebSocket.
- `apps/web/e2e/fixtures/stomp-fixtures.ts` — STOMP 프레임 빌더(CONNECTED/MESSAGE) 헬퍼.
- `apps/web/package.json` — `@stomp/stompjs` 추가.

## 5. E2E 전략 (Playwright routeWebSocket — Maxi 확정)

- `page.routeWebSocket('**/ws', ...)`로 WS 가로채기.
- 클라 CONNECT 프레임 수신 → `CONNECTED` 프레임 응답(STOMP 1.2 형식).
- 클라 SUBSCRIBE 프레임(`/user/queue/notifications`) 수신 → subscription id 기록.
- 테스트가 `MESSAGE` 프레임(destination + JSON body) 푸시 → 토스트 표시 검증.
- 시나리오: S1(title+body 토스트), S2(body null → title만).
- 로그인은 기존 `loginAsAlice` fixture 재사용. STOMP 프레임 빌더는 `stomp-fixtures.ts`로 분리(재사용).
- 기존 E2E 회귀 0 — STOMP는 신규 spec, 기존 화면 셀렉터 영향 없음 확인(`ui-pr-defer-e2e-regression-latent`).

## 6. 비기능 요구사항 (NFR)

- **성능**: WebSocket 알림 지연 p95 < 1s(product 측정 항목). 프론트는 "수신 즉시 토스트"로 충족, 측정은 통합 영역.
- **보안**: 토큰은 connectHeaders로만 전달(URL 쿼리 금지). PAT 사용 금지(백엔드가 거부). 로그아웃 시 즉시 해제.
- **안정성**: 파싱 실패/연결 실패가 앱 전체를 깨지 않음(격리된 경고).

## 7. 엣지 케이스

- body=null → description 생략, title만.
- 잘못된/스키마 불일치 메시지 → 토스트 미표시 + 경고(크래시 금지).
- 연결 실패 → @stomp/stompjs 자동 재연결(reconnectDelay). 무한 토스트 스팸 금지.
- 미인증 시 연결 시도 안 함(불필요한 401 CONNECT 거부 회피).
- 언마운트/로그아웃 시 deactivate — 좀비 소켓/리스너 누수 금지.

## 8. 제약 조건

- **절대 규칙 #17**: `@stomp/stompjs` 신규 의존성 — Maxi 승인 완료(2026-06-13).
- **BC 격리**: notification 프론트만. 다른 BC import 금지.
- **검증**: `pnpm verify`(lint+typecheck+test+build). vitest는 타입 무시 → `tsc --noEmit` 필수 동반(`ci-typecheck-tsconfig-app-vs-local`).
- **Zod v4**: UUID는 RFC4122 v4 — fixture UUID는 v4 형식(`zod-v4-uuid-fixture-strictness`).
- **i18n**: 사용자 노출 문자열은 콜론 종결 금지(ko.test 자동검증, `fr-mf-05` 교훈). 단 알림 title/body는 백엔드 페이로드라 프론트 i18n 대상 아님(연결 상태 등 UI 문구만 해당).

## 9. 측정 가능한 완료 기준

1. `@stomp/stompjs` package.json 추가 + lockfile 갱신.
2. STOMP 클라이언트가 `/ws` 연결, `Authorization: Bearer` 헤더, `/user/queue/notifications` 구독 — 단위 테스트 통과.
3. 메시지 수신 → Zod parse → `toast` 호출(S1/S2) — 단위 테스트 통과.
4. 인증 연동 연결/해제(S3/S4), 토큰 갱신 재연결(S5) — 단위 테스트 통과.
5. E2E: routeWebSocket으로 MESSAGE 푸시 → 토스트 표시(S1/S2) 통과.
6. `pnpm verify` 통과(typecheck 포함) + 기존 E2E 회귀 0.
7. 정본 동기화: product `notification-dashboard.md` §2.2 D6/D7 `[x]` 마킹 + fr-index/README 카운트 + dashboard 재생성(merge 단계).

## Brainstorming Check ✅ 통과

직접 gap 점검(완료 FR 후속 D6/D7 — 경량). Maxi 결정 필요 gap 없음. plan에서 해소할 구현 디테일만 식별:
brokerURL 구성(dev 프록시 vs prod 동일호스트) · connectHeaders 동적갱신(beforeConnect) · hook 마운트 __root · routeWebSocket↔MSW 레이어 분리 공존 · 중복구독 방지(Client 1개+deactivate) · eventType별 토스트는 기본 toast로 최소화.
