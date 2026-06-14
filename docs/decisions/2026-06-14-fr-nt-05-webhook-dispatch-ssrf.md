# ADR: FR-NT-05 PR2 — Webhook 디스패처의 아웃바운드 HTTP + SSRF 방어 정책

> 날짜: 2026-06-14
> 상태: Accepted (Maxi 게이트 확정)
> 관련 FR: FR-NT-05 (Webhook 알림 채널)
> 관련 ADR: [2026-06-14-fr-nt-05-transition-event-outbox.md](2026-06-14-fr-nt-05-transition-event-outbox.md) (PR1 발행 파이프라인)
> 관련 PR: PR2 (notification BC 소비/디스패처)

## 맥락

FR-NT-05 PR2는 notification BC가 pgmq 큐 `q_transition_events`의 `WebhookRequested`(`{type, payload:{issueKey, url, method}}`)를 소비해 **외부 URL로 HTTP 요청을 전송**한다. 이것은 **BTS 백엔드 최초의 아웃바운드 HTTP** 경로다(grep 결과 기존 RestClient/RestTemplate/WebClient/HttpClient 사용 0건). 외부 URL로 서버가 요청을 보내는 구조는 SSRF(Server-Side Request Forgery — 서버가 공격자/오설정으로 내부 주소를 향해 요청을 쏘게 유도되는 공격) 위험을 동반한다.

## 결정

### D1. SSRF 방어 = 내부망 차단 리스트

전송 전 `WebhookUrlValidator`가 URL을 검증한다.
- 스킴이 `http`/`https`가 아니면 차단.
- 호스트를 `InetAddress.getAllByName`으로 DNS 해석 → 해석된 IP가 내부망이면 차단: loopback(127/::1)·link-local(169.254, 클라우드 metadata 포함)·site-local/private(10/172.16/192.168)·anyLocal·multicast.
- **IPv6 보강**: 내장 플래그가 못 잡는 IPv6 ULA(fc00::/7)와 IPv4-mapped IPv6(`::ffff:x.x.x.x`)를 명시 검사/언래핑.
- 차단/malformed URL은 `webhook_url_blocked` 보안 로그(host만, 전체 URL 비기록) 후 영구 거부 → 워커가 메시지 delete(재시도 무의미).

대안 기각.
- **검증 없이 전송**: webhook URL이 admin config라도 무방비 아웃바운드는 완제품 기준 미달.
- **허용 도메인 allowlist**: 가장 안전하나 등록 config/관리 UI 인프라가 필요해 PR2 범위 초과(현재 webhook 사용처 0건이라 과설계).

### D2. 요청 body = JSON 엔벨로프

`{"event":"WebhookRequested","issueKey":"<key>"}` + `Content-Type: application/json`. HTTP 메서드는 payload.method 사용(기본 POST, 알 수 없으면 POST fallback). 사내 인증/쿠키/내부 헤더는 외부 요청에 절대 싣지 않는다. 응답 본문은 사용하지 않고 상태 코드만 판정(2xx=성공).

### 클라이언트/리다이렉트

- HTTP 클라이언트 = **RestClient**(Spring 6.1, 기존 `spring-web` 내장 — 신규 의존성 0). 동기 방식이 `@Scheduled` 폴링 워커에 적합.
- **리다이렉트 자동 추적 비활성**(`HttpClient.Redirect.NEVER`). 외부 URL이 `3xx → 내부망 IP`로 응답해 SSRF 가드를 우회하는 것을 차단. 3xx는 non-2xx(실패)로 취급.
- connect 3s / read 5s 타임아웃(설정 가능)으로 워커 무한 블록 방지.

### 재시도/생명주기

pgmq 재전달을 재시도 메커니즘으로 재사용(별도 라이브러리 없음). 2xx→Sent→delete, 영구거부(Rejected)→delete, 일시실패(Failed: non-2xx/타임아웃/연결/DNS)→delete 보류(vt 만료 재전달)→`read_ct>MAX_RECEIVE_COUNT`시 archive(dead-letter). VT=60s/BATCH=5(NotificationWorker의 30/10 차용 금지 — read 5s×배치가 vt 넘기면 중복 POST).

## 알려진 한계 (수용)

- **TOCTOU / DNS rebinding**: 검증 시점(DNS 해석)과 RestClient 연결 시점의 해석이 달라질 수 있다. 완전 차단은 연결 IP 핀잉이 필요. admin-controlled URL + PR2 범위 기준 수용하고 KDoc/스펙에 명시. 후속 강화 대상.
- **at-least-once**: webhook은 중복 전송될 수 있다. 수신자 멱등 책임(문서화). 송신측 dedup은 범위 외.

## 영향 / 후속

- 본 ADR의 SSRF 가드(`WebhookUrlValidator`)·아웃바운드 HTTP 패턴(`WebhookHttpClientConfig`)은 향후 다른 BC의 아웃바운드 HTTP(예: Slack/외부 통합)가 참조할 재사용 결정이다.
- FR-NT-05 D6/D7(워크플로우 post-action 설정 UI)은 project-workflow BC post-action CRUD API 신설이 선행 필요해 후속 PR로 분리. FR-NT-05는 그때까지 `[~]`(백엔드 완성).
