<!-- /ws STOMP 핸드셰이크 HTTP permitAll — DEVELOPMENT.md §1.4 정식 예외 · 인증 지점 이연 · 잔여위험 4종 -->

# ADR — `/ws` STOMP 핸드셰이크 permitAll (DEVELOPMENT.md §1.4 정식 예외)

- 날짜: 2026-08-24
- 상태: 채택 (Accepted) — **2026-08-24 Maxi 게이트1 승인** (절대 규칙 §1.4 정식 예외 승인 포함)
- 관련 FR: **FR-NT-02** (인앱 WebSocket 알림)
- 관련 slug: `prod-ui-defects-12-ws-permitall` (PR #399)
- 관련 ADR: `2026-07-15-slack-inbound-permitall-central` · `2026-07-17-git-webhook-inbound-permitall`
  (같은 §1.4 예외 계열 — 그 둘의 논거를 **그대로 쓸 수 없다**. D3 참조)

## 맥락 (Context)

`notification` BC 의 `WebSocketConfig` 는 `/ws` 가 **HTTP 계층에서는 열려 있다**고 전제하고,
인증을 한 단계 뒤인 STOMP `CONNECT` frame 의 native header
`Authorization: Bearer <accessToken>` 으로 미룬다. 검증은 `StompAuthChannelInterceptor` 가 단독으로 진다.

그런데 중앙 `SecurityConfig` 에는 그 permitAll 이 **없었다.** `anyRequest().authenticated()` 가
업그레이드 요청을 먼저 잘라, 브라우저 콘솔에
`WebSocket connection to 'wss://…/ws' failed: HTTP Authentication failed` 만 남기고
**프로덕션 전 화면에서 실시간 인앱 알림이 죽어 있었다.**

두 모듈 어느 테스트도 상대 설정을 읽지 않아 유닛은 전부 초록이었다 — 저장소 지배 결함 양식
(「두 목록이 서로를 검사하지 않는다」).

### 왜 브라우저에서 헤더를 못 싣는가

BTS 는 `STATELESS` + JWT Bearer 라 세션 쿠키가 없고, 브라우저 `WebSocket` API 는 업그레이드
요청에 임의 헤더를 실을 수 없다. 이것은 우리 설계 선택이 아니라 **웹 플랫폼의 구조적 제약**이며,
Spring 의 STOMP + JWT 표준 배치가 그래서 인증을 CONNECT frame 으로 미룬다.

## 결정 (Decision)

### D1. `/ws` 한 경로만, GET 고정, MVC 비의존 매처로 연다

```kotlin
auth.requestMatchers(antMatcher(HttpMethod.GET, WS_HANDSHAKE_PATH)).permitAll()
```

- **단일 경로** — 하위 와일드카드를 쓰지 않는다. 훗날 이 아래 매핑이 생겨도 자동 노출되지 않는다.
- **GET 고정** — 형제 두 줄(`PUBLIC_DASHBOARDS_PATH` · `ICAL_FEED_PATH`)이 쓰는 defense-in-depth.
  핸드셰이크는 스펙상 항상 GET 이라 기능 손실 0 이다.
- **`antMatcher`** — 문자열 매처는 MVC 가 있으면 `MvcRequestMatcher` 로 해석되는데 `/ws` 는
  MVC 핸들러가 아니라 매칭이 보장되지 않는다(`SamlSecurityConfig` 선례).

### D2. §1.4 예외 정당화 — 「열린 것은 핸드셰이크뿐이다」

절대 규칙 §1.1-4 는 「인증 없는 엔드포인트 추가 금지」다. 이 permitAll 이 정식 예외인 근거.

| 점검 | 실측 |
|---|---|
| 인증이 사라졌는가 | **아니다.** CONNECT 검증은 `StompAuthChannelInterceptor` 단독 — 헤더 부재·형식 오류·decode 실패·subject 부재·PAT 를 모두 거부하고 클라이언트발 SEND 도 막는다(push-only). **CONNECT 성공 전의 소켓은 어떤 데이터도 나르지 못한다.** |
| 본문 적재 경로 | `/ws` 는 컨트롤러가 아니라 `registry.addEndpoint("/ws")` 로 등록된 STOMP 엔드포인트다. `@RequestBody` **0건** — PR #274/#275 의 OOM 양식이 성립하지 않는다 |
| 하위 폴백 경로 | `withSockJS()` 호출 **0** → `/ws/info`·`/ws/xhr_send` 부재. 단일 경로로 완결된다 |
| CSRF | 핸드셰이크는 GET 이라 `CsrfFilter` 대상이 아니고, csrf ignore 목록에 `/ws` 를 **넣지 않았다**. 비-GET 은 CSRF 가 그대로 막는다 |
| CSWSH | 인증이 앰비언트 쿠키가 아니라 CONNECT frame Bearer 라 교차 출처 소켓이 사용자 자격을 도용할 수 없다. 더해 `setAllowedOrigins` 로 허용 출처를 **소스에 명시**한다 |

### D3. ★ 선행 ADR 두 건의 논거를 그대로 쓸 수 없다

`slack-inbound-permitall-central`·`git-webhook-inbound-permitall` 은 **서명 검증**이 인증을
대신한다는 구조다(요청마다 HMAC). `/ws` 는 다르다 — **요청 단위 인증이 아예 없고**, 대신
그 뒤에 오는 STOMP 프레임이 인증을 진다. 즉 「permitAll 지점에서 무엇이 막는가」의 답이
「같은 요청 안의 서명」이 아니라 「다음 프레임의 Bearer」다.

그 차이의 대가가 D4 의 R1 이다.

### D4. 남은 방어선을 프레임워크 기본값에 맡기지 않는다

permitAll 로 HTTP 계층 방어선이 하나 줄었으므로, 남은 손잡이를 명시한다.

- `setAllowedOrigins(bts.security.cors.allowed-origins)` — HTTP CORS 와 **같은 프로퍼티**를 읽는다.
  브라우저가 이 소켓을 여는 출처와 REST 를 호출하는 출처는 같은 SPA 하나이므로 갈릴 이유가 없고,
  갈라 두면 한쪽만 바뀌었을 때 어느 테스트도 그 어긋남을 보지 못한다.
- `"*"` 는 `require` 로 **부팅 실패**시킨다. `CorsConfig` 는 `allowCredentials = true` 라 Spring 이
  `"*"` 를 시끄럽게 거절하는데 WebSocket 은 **조용히 전 출처 허용**이다 — 같은 프로퍼티를
  공유하면서 실패 모드가 비대칭이라 코드가 막아야 한다.
- `setTimeToFirstMessage(30초)` — 인증 **전** 소켓의 수명. permitAll 이전에는 CONNECT 를 안 보내는
  익명 클라이언트가 필터에서 401 로 끊겨 자원을 한 톨도 잡지 못했다. 이제는 업그레이드가 성립해
  CONNECT 전에 커넥션·세션 엔트리가 할당된다.
- 기존 백프레셔 3종(`64KB` · `10초` · 송신 버퍼)은 그대로.

### D5. 짝 판별식은 **두 개**이고 역할이 다르다

| 판별식 | 무엇을 증명하나 |
|---|---|
| `identity-access` `WebSocketHandshakePathGuardTest` | 상수의 **폭**(와일드카드 없는 단일 경로)과 **매처 종류**(MVC 비의존 + GET 고정)를 소스 텍스트로 고정 |
| `:modules:app` `WebSocketHandshakePermitAllTest` | 조립 컨텍스트 실 HTTP — `/ws` 400(= 필터를 지나 핸들러에 닿았다) **양성 증명** + 인접 경로(`/wsx`·`/ws/anything`·`/ws/info`) 401 **음성 증명** |

★identity-access 에서 HTTP 로 검증하면 **공허하다.** 그 모듈에는 `/ws` 핸들러가 없어 permitAll
통과 후 `/error`(authenticated)에서 401 이 다시 나고, 그 401 은 필터가 자른 401 과 상태코드도
`WWW-Authenticate` 헤더도 같다(`GitWebhookInboundPermitAllTest` KDoc 의 실측). 그래서 역할을 나눴다.

## 결과 (Consequences)

- 프로덕션 실시간 인앱 알림이 복구된다(FR-NT-02).
- `/ws` GET 이 익명으로 **핸드셰이크까지** 도달한다. 그 뒤는 CONNECT 인증이 진다.
- permitAll 목록이 한 줄 늘어 `SecurityConfig` 의 예외 표면이 넓어진다.

### 잔여 위험 (수용) — 4종

| # | 위험 | 수용 근거 |
|---|---|---|
| R1 | 익명 클라이언트가 업그레이드에 성공해 **CONNECT 전까지** 커넥션·세션 엔트리를 잡는다 | `setTimeToFirstMessage` 30초로 창을 좁혔다. 다만 **동시 연결 수 상한은 여전히 없다**(`server.tomcat.max-connections` 미설정 = 기본값). 1,000명 사내 단일 호스트 규모에서 수용 |
| R2 | `Origin` 헤더 **부재** 요청(비브라우저 클라이언트)은 `setAllowedOrigins` 를 통과한다 | 통과해도 CONNECT Bearer 가 없으면 아무 데이터도 못 받는다. 브라우저 CSWSH 방어가 목적이라 의도된 범위 |
| R3 | 허용 출처가 HTTP CORS 와 한 프로퍼티를 공유한다 — 훗날 두 용도가 갈려야 하면 분리 비용이 든다 | 지금 두 출처 집합은 같은 SPA 하나다. 갈라 둘 때의 drift 위험이 더 크다고 판단 |
| R4 | `notification` BC 프로덕션 코드를 `identity-access` PR 이 함께 건드렸다 (「한 PR = 한 BC」 이탈) | 같은 FR-NT-02 결함의 짝이라 분리하면 반쪽 수정이 된다. 게이트 1 D5-A 승인. **cross-BC `import` 는 0건** — 상호 참조는 KDoc 링크뿐이고 컴파일 의존이 아니다 |

## 대안 (Rejected)

| 안 | 기각 사유 |
|---|---|
| 쿼리 파라미터로 토큰 전달(`/ws?token=…`) | 토큰이 URL 에 실려 액세스 로그·Referer·브라우저 히스토리에 남는다. 절대 규칙 §1.1-2(로그에 토큰 금지)와 정면 충돌 |
| 핸드셰이크 전용 단명 티켓 발급 | 엔드포인트 1개 + 저장소 1개 + 만료 정책이 새로 생긴다. CONNECT frame 인증이 이미 같은 보증을 주는데 표면만 늘린다 |
| SockJS 도입 후 쿠키 인증 | `STATELESS` 를 깨고 세션을 되살린다. 폴백 경로(`/ws/info` 등)까지 열어야 해 permitAll 표면이 오히려 넓어진다 |
| permitAll 없이 필터에서 CONNECT 를 검사 | HTTP 필터는 업그레이드 시점에 STOMP 프레임을 볼 수 없다. 순서상 불가능 |

## 승인 (Approval)

- **게이트 1** — 2026-08-24 Maxi 승인. 결정 D5-A(`setAllowedOrigins` 명시) 포함.
- **plan-eng-review** — `docs/plans/2026-08-24-prod-ui-defects-12-ws-permitall.md` `## 리뷰 결과` 렌즈 1.
- **plan-ceo-review** — 같은 파일 렌즈 3. `DATA.md §1-5`(필터 체인 변경은 두 리뷰 필수)를 충족한다.
- **독립 코드 리뷰** — `code-reviewer` 서브에이전트가 이 ADR 부재를 BLOCKER B2 로 잡았고, 그 지적으로 이 문서가 생겼다.

## 후속 (본 ADR 범위 밖)

- 동시 WebSocket 연결 수 상한(`server.tomcat.max-connections`) 검토 — R1.
- 개발용 오리진 기본값이 4개 파일로 늘었다. 그 값들이 갈라지지 않는지 재는 차집합 판별식 부재.
- permitAll 3경로군이 **전부 메서드를 고정하는지** 재는 판별식 부재 — 네 번째가 또 안 따라올 수 있다.
