# FR-NT-05 PR2 — Webhook 디스패처 스펙

> slug: fr-nt-05-webhook-dispatcher
> BC: notification
> type: backend
> 관련 PR: #140 (PR1 발행), 본 PR (PR2 소비/디스패처)
> 관련 ADR: [2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md)

## 개요

워크플로우 전이 시 발행되어 pgmq 큐 `q_transition_events`에 쌓인 `WebhookRequested` 이벤트를 notification BC의 스케줄 워커가 소비해, 이벤트가 지정한 외부 URL로 HTTP 요청을 보낸다. PR1에서 발행 파이프라인이 배선됐고, 본 PR2가 소비 + 외부 전송을 담당한다. 완료 시 FR-NT-05가 종료된다.

## Maxi 게이트 확정 결정 (D1/D2)

- **D1 SSRF 방어 = 내부망 차단 리스트.** 외부 URL 전송 전 호스트를 DNS 해석하고, 해석된 IP가 loopback(127/::1)·link-local(169.254, 클라우드 metadata 포함)·site-local/private(10/172.16/192.168)·anyLocal·multicast면 전송을 차단한다. http/https 외 스킴도 차단.
- **D2 요청 body = JSON 엔벨로프.** `{"event":"WebhookRequested","issueKey":"<key>"}` + `Content-Type: application/json`.

## 사용자 시나리오 (Given-When-Then)

> 사용자 = 워크플로우 전이로 webhook을 트리거한 행위자 + 외부 webhook 수신 서버. 본 PR은 백엔드 비동기 처리라 직접 UI는 없다.

1. **정상 전송**
   - Given. 워크플로우 전이가 `CALL_WEBHOOK` post-action을 실행해 `q_transition_events`에 `WebhookRequested{issueKey, url=공개URL, method=POST}` 1건이 쌓였다.
   - When. WebhookDispatchWorker가 폴링해 메시지를 읽는다.
   - Then. URL이 SSRF 가드를 통과하면 해당 URL로 `POST` + JSON 엔벨로프 body를 전송하고, 2xx 응답을 받으면 `pgmq.delete`로 메시지를 제거한다.

2. **내부망 URL 차단 (SSRF)**
   - Given. `WebhookRequested{url=http://169.254.169.254/...}` 또는 private/loopback IP로 해석되는 URL.
   - When. 워커가 메시지를 읽는다.
   - Then. SSRF 가드가 차단해 **전송하지 않고**, 보안 경고 로그(`webhook_url_blocked`)를 남긴 뒤 메시지를 `pgmq.delete`(ack)한다. 재전달해도 결과가 같은 결정적 차단이므로 dead-letter로 적재하지 않는다.

3. **수신 서버 일시 장애 (재전달)**
   - Given. 정상 URL이지만 수신 서버가 5xx/타임아웃/연결 거부.
   - When. 워커가 전송을 시도한다.
   - Then. 예외/실패로 `pgmq.delete`하지 않아 vt 만료 후 재전달된다(at-least-once). `read_ct`가 `MAX_RECEIVE_COUNT`를 초과하면 `pgmq.archive`로 dead-letter 처리한다.

4. **범위 외 이벤트 타입**
   - Given. 같은 큐에 `WatcherAdded`/`NotificationRequested`/`AutomationRequested`(현재 휴면, 프로듀서 0) 메시지가 들어온 경우.
   - When. 워커가 읽는다.
   - Then. `WebhookRequested`가 아니면 처리 없이 `pgmq.delete`(ack)해 무한 재전달을 막는다 (NotificationWorker의 미지원 type 처리 선례).

## 기능 요구사항 (FR)

- **FR1.** notification BC에 `q_transition_events`를 폴링하는 `@Scheduled` pgmq consumer(`WebhookDispatchWorker`)를 둔다. 생명주기는 NotificationWorker와 동일 — read → 성공 delete → 실패 keep(재전달) → `read_ct>MAX` archive.
- **FR2.** 메시지 `type`이 `WebhookRequested`일 때만 디스패치한다. payload에서 `issueKey`, `url`, `method`를 추출한다.
- **FR3.** 전송 전 `WebhookUrlValidator`로 URL을 검증한다(D1). 스킴이 http/https가 아니거나, 호스트가 내부망 IP로 해석되면 차단한다.
- **FR4.** `WebhookDispatcher`가 RestClient로 `method`(기본 POST) 요청을 보낸다. body는 JSON 엔벨로프(D2), `Content-Type: application/json`.
- **FR5.** 응답이 2xx면 성공(delete), 그 외(4xx/5xx)·예외(타임아웃/연결오류)면 실패(미delete, 재전달).
- **FR6.** SSRF 차단·malformed(url 누락/blank·스킴 위반) URL은 결정적 영구 실패로 간주해 경고 로그 후 delete(ack). 재시도하지 않는다.
- **FR7.** 범위 외 type / 미지원 type / JSON 파싱 실패(poison)는 NotificationWorker 선례대로 처리(delete 또는 read_ct>MAX시 archive).
- **FR8 (Brainstorming G1).** RestClient의 **자동 리다이렉트 추적을 비활성화**한다. 외부 URL이 `3xx → 내부망 IP`로 응답해 SSRF 가드를 우회하는 것을 막는다. 3xx는 따라가지 않고 non-2xx 응답(=실패)으로 취급한다.

## Brainstorming Check

스펙을 직접 sanity check(2건 보안 갭 발견 → 반영).

- **G1 (반영, FR8).** 리다이렉트 자동 추적 차단 — 3xx redirect를 통한 SSRF 우회 방지.
- **G2 (한계 명시).** TOCTOU/DNS rebinding — 검증 시점과 연결 시점의 DNS 해석 불일치는 resolve-then-connect 구조의 알려진 한계. admin-controlled URL + PR2 범위 기준 수용하고 KDoc/스펙에 명시. 완전 차단은 연결 IP 핀잉이 필요해 별도 추적.
- 부수 확인 — `method` 파싱 실패(알 수 없는 메서드)는 POST로 fallback. 신규 `@Scheduled @Component` 워커가 기존 전체-컨텍스트 통합테스트 부팅을 깨지 않는지 확인(RestClient 빈은 외부 의존 0이라 안전, 메모리 fr-nt-02-email-channel-done의 부팅 깨짐 패턴 주의).

## 비기능 요구사항 (NFR)

- **보안.** 첫 백엔드 아웃바운드 HTTP. D1 SSRF 가드 필수. 요청에 사내 인증/쿠키/내부 헤더를 절대 싣지 않는다(외부 URL이므로). 응답 본문은 신뢰하지 않고 사용하지 않는다(상태 코드만 판정).
- **성능/안정성.** HTTP connect/read 타임아웃을 둬 워커 스레드가 무한 블록되지 않게 한다(기본 connect 3s / read 5s, 설정 가능). 한 메시지 처리 지연이 큐 전체를 막지 않도록 batch 소량 처리.
- **신뢰성.** at-least-once. webhook은 중복 전송될 수 있으므로 "수신자가 멱등 처리해야 함"을 KDoc/스펙에 명시(본 PR은 송신측 dedup 미구현 — 범위 외).
- **BC 격리.** notification은 pgmq JSON을 직접 파싱한다. issue-tracking/project-workflow/shared-kernel의 도메인 타입을 import하지 않는다(`DomainEvent` 등 발행측 클래스 직접 의존 금지, 와이어 JSON 기준).
- **트랜잭션.** 워커에 `@Transactional` 없음(NotificationWorker와 동일, learnings transaction-self-invocation). pgmq.read/delete/archive는 트랜잭션 밖 raw SQL.

## API 인터페이스 (REST)

없음. 본 PR은 내부 비동기 워커다. 외부로 나가는 HTTP는 다음 형태.

```
<method, 기본 POST> <event.payload.url>
Content-Type: application/json

{"event":"WebhookRequested","issueKey":"<issueKey>"}
```

## 데이터 모델 변경

없음. 큐 `q_transition_events`는 PR1의 `V022`(issue-tracking namespace)가 이미 생성했고, pgmq 큐는 DB 전역이라 notification 워커의 `DSLContext`가 `pgmq.read('q_transition_events', ...)`로 읽을 수 있다(NotificationWorker가 issue-tracking이 만든 q_issue_events를 읽는 것과 동일 패턴). **notification 모듈 신규 마이그레이션 불요.**

## 엣지 케이스

- `payload.url` 누락/blank → malformed → 경고 로그 + delete(ack).
- 비-http(s) 스킴(file://, gopher:// 등) → 차단 + delete.
- 호스트가 내부망 IP로 해석 → 차단 + delete.
- DNS 해석 자체가 예외(UnknownHost 등) → 일시적일 수 있으므로 전송 실패로 간주 → 재전달 → read_ct>MAX시 archive (영구 차단 delete와 구분).
- `method`가 PUT 등 → 그대로 사용. 누락/blank → POST.
- 수신 서버 2xx지만 본문이 크거나 비어있음 → 본문 무시, 성공 처리.
- 수신 서버 4xx → 실패로 간주(재전달 → dead-letter). (4xx를 영구 실패로 즉시 버리지 않음 — 단순화: 모든 non-2xx 동일 처리.)
- 동일 메시지 재전달로 webhook 2회 발사 → 수신자 멱등 책임(문서화).
- 다른 type(WatcherAdded 등) → delete(ack).

## 제약 조건

- DEVELOPMENT.md §1 절대 규칙 전수 준수(특히 #17 외부 의존성 — RestClient는 기존 spring-web 내장, 신규 의존성 0).
- pgmq raw SQL은 `?` 바인딩만(문자열 결합 금지) — NotificationWorker 선례.
- 큐 이름·VT·BATCH·MAX_RECEIVE_COUNT 상수는 NotificationWorker 값을 차용/일관 유지.
- TDD red→green 강제.

## 측정 가능한 완료 기준

1. `WebhookRequested` 통합 시나리오 — 공개 URL(테스트용 stub) 메시지 → POST + JSON 엔벨로프 전송 → 2xx → `pgmq.delete` 검증.
2. 내부망 IP/metadata URL → 미전송 + 보안 로그 + delete 검증(단위).
3. 비-http 스킴/url 누락 → 미전송 + delete 검증(단위).
4. 수신 서버 5xx/타임아웃 → 미delete(재전달) 검증.
5. 범위 외 type → 미전송 + delete 검증.
6. 기존 NotificationWorker / q_issue_events 회귀 0(전체 notification 모듈 테스트 BUILD SUCCESSFUL).
7. ktlint/detekt green, `bash scripts/verify-master-plan.sh` exit 0 — FR-NT-05 `[~]`→완료 반영(fr-index/product/README/CLAUDE/SDD 전수 동기화).
