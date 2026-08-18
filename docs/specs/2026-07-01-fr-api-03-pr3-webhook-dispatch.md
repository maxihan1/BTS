# FR-API-03 PR3 — Webhook 발송 실행 계층 — 스펙

> slug: fr-api-03-pr3-webhook-dispatch · BC: issue-tracking(교차) + search-export-import · type: api
> ADR: [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md)
> 상위 spec: [2026-07-01-fr-api-03-outbound-webhook](2026-07-01-fr-api-03-outbound-webhook.md) · 선행 PR1(#211)·PR2(#212)
> Maxi 확정: circuit breaker = in-memory + 이번 PR 포함 (2026-07-01 AskUserQuestion)

## 0. 배경 · 범위

FR-API-03 4-PR 분할 중 **PR3 — 발송 실행 계층**. PR1(shared 추출)·PR2(구독 CRUD + `outbound_webhooks`/`webhook_deliveries` 테이블) 완료.

이번 PR = "이벤트 발생 → 구독 매칭(fanout) → HMAC 서명 발송 → 발송 이력 기록 → circuit breaker 보호"의 실행 파이프라인.

**두 BC 교차 (BC 격리 예외 명시)**.
- issue-tracking: `IssueEventPublisher`가 발행 가능 이벤트를 `q_webhook_events` 큐로도 **dual-send**. 순수 이벤트 발행 추가 — 다른 BC 코드 직접 import 0이라 BC 격리 위반 아님.
- search-export-import: `q_webhook_events` 소비 워커 + HMAC + circuit breaker + 발송 이력 (webhook 발송 주체).

## 1. 이벤트 카탈로그 확정 (drift 정리)

상위 spec §0(line 18)은 "PR3 구독 대상 = `IssueDomainEvent` 7종"이라 표기했으나, **PR2가 `WebhookEventCatalog.PUBLISHABLE`을 2종**(`issue.created`, `issue.transitioned`)으로 좁혀 구현·머지했다(#212). 구독 CRUD가 이 카탈로그로 `eventFilter`를 검증하므로, PR3는 **PR2가 확정한 2종을 정본으로 따른다**.

- PR3 dual-send 대상 = `WebhookEventCatalog.PUBLISHABLE` = { `issue.created`, `issue.transitioned` }.
- 상위 spec의 "7종" 표현은 초기 계획 표기 → PR2에서 2종으로 확정됨. **전수 동기화**: 상위 spec §0 line 18을 "초기 발행 가능 2종(issue.created·issue.transitioned), 확장은 WebhookEventCatalog 갱신으로"로 정정.
- 확장(나머지 5종)은 `WebhookEventCatalog.PUBLISHABLE` 추가 + dual-send 대상 자동 확대로 가능하나, **이번 PR 범위 밖**.

## 2. 사용자 시나리오 (Given-When-Then)

- **S1 (기본 발송)**. Given `issue.created` 구독(URL+secret)이 등록돼 있고, When 이슈가 생성되면, Then 그 URL로 HMAC-SHA256 서명 헤더가 붙은 POST 요청이 전송되고 발송 결과가 `webhook_deliveries`에 기록된다.
- **S2 (프로젝트 스코프)**. Given 구독이 `projectKey=ATLAS`로 제한돼 있고, When 다른 프로젝트(BETA) 이슈 이벤트가 발생하면, Then 그 구독으로는 발송되지 않는다.
- **S3 (이벤트 필터)**. Given 구독의 `eventFilter=[issue.transitioned]`이고, When `issue.created` 이벤트가 발생하면, Then 그 구독으로는 발송되지 않는다.
- **S4 (fanout)**. Given 같은 이벤트를 구독하는 활성 구독이 3개 있고, When 이벤트가 발생하면, Then 3개 URL 모두에 각각 발송되고 각각 이력이 남는다.
- **S5 (circuit breaker)**. Given 한 구독 URL이 연속 5회 실패했고, When 다음 이벤트가 발생하면, Then 그 구독은 60초간 발송이 차단(circuit OPEN)되어 워커 자원을 잠식하지 않는다. 60초 후 half-open으로 1회 탐침한다.
- **S6 (secret 없는 구독)**. Given secret 미설정 구독이고, When 발송하면, Then 서명 헤더 없이 발송된다(수신자가 서명 검증 없이 수용).
- **S7 (SSRF 재검증)**. Given 등록 시점엔 정상이었으나 발송 시점에 내부망으로 해석되는 URL이고, When 발송하려 하면, Then `OutboundUrlValidator`가 차단하고 발송하지 않으며 이력에 실패로 기록한다.
- **S8 (발송 이력 조회)**. Given 관리자(SYSTEM_ADMIN)가 특정 webhook의 발송 이력을 조회하면, Then 발송 시도별 status/response_code/시각을 페이지네이션으로 받는다.

## 3. 기능 요구사항 (FR)

- **FR1 (dual-send)**. `IssueEventPublisher.publish()`가 `WebhookEventCatalog.PUBLISHABLE`에 속하는 이벤트를 기존 `q_issue_events`에 더해 `q_webhook_events`로도 발행한다. 발행은 호출자 트랜잭션에 묶인다(`@Transactional MANDATORY` outbox). `q_issue_events`는 NotificationWorker 독점 소비라 경합 없음.
- **FR2 (새 큐)**. `q_webhook_events` pgmq 큐를 issue-tracking 마이그레이션(V034)으로 생성(발행자 소유 관례, `q_transition_events` V022 패턴). init_codegen.sql 미러.
- **FR3 (fanout 워커)**. search-export-import에 `@Scheduled` 워커 신설. `q_webhook_events` 폴링 → 이벤트 JSON 파싱 → **event_filter 매칭(GIN overlap) + projectKey 매칭 + enabled + not-deleted** 활성 구독 조회 → 매칭된 각 구독에 발송(fanout).
- **FR4 (HMAC-SHA256 서명)**. secret 있는 구독은 `webhookSecretEncryptor.decrypt(secret_encrypted)`로 평문 복원 후, 발송 body에 대해 HMAC-SHA256 서명 계산 → 헤더 부착. secret 없으면 서명 헤더 생략.
- **FR5 (발송 이력)**. 각 발송 시도마다 `webhook_deliveries`에 레코드 INSERT: `webhook_id`, `event_type`, `status`(SUCCEEDED/FAILED), `response_code`, `attempt_count`, `error_detail`, `delivered_at`. append-only.
- **FR6 (circuit breaker)**. 워커 in-memory 상태(`Map<webhookId, {consecutiveFailures, openedAt}>`). 연속 5회 실패 → OPEN 60초 → half-open 1회 탐침 → 성공 시 CLOSED(카운터 리셋)·실패 시 재OPEN. OPEN 구독은 발송 스킵.
- **FR7 (SSRF 재검증)**. 발송 직전 `OutboundUrlValidator.check(url)`로 재검증(등록↔발송 TOCTOU 완화). Blocked/Malformed면 발송 안 하고 실패 이력.
- **FR8 (pgmq 생명주기 / 재시도)**. FR-NT-05 워커 패턴 계승. 메시지 처리(모든 매칭 구독 발송 시도) 완료 시 `pgmq.delete`(ack). 처리 중 워커 크래시/예외로 미완료면 VT 만료 후 재전달(at-least-once). `read_ct > MAX_RECEIVE_COUNT`면 `pgmq.archive`(dead-letter). 개별 구독 발송 실패는 재전달로 전체 재시도하지 않고(중복 방지) 이력+circuit로 관찰.
- **FR9 (발송 이력 조회 API)**. `GET /api/v1/webhooks/{id}/deliveries` — SYSTEM_ADMIN 전역 게이트(PR2 `OutboundWebhookController` 관례), offset 페이지네이션(DEFAULT 20/MAX 100), 최신순. PR4 UI가 소비.

## 4. API 인터페이스 (REST)

```
GET /api/v1/webhooks/{id}/deliveries?limit=20&offset=0
  권한: SYSTEM_ADMIN (PR2 admin 게이트 재사용, 리소스 조회보다 먼저)
  200 → [ { id, eventType, status, responseCode, attemptCount, errorDetail, createdAt, deliveredAt }, ... ]
  404 → webhook 미존재 (비-admin도 404로 존재 probe 차단 = PR2 관례)
```

발송 자체는 백그라운드 워커라 REST 엔드포인트 없음. 신규 엔드포인트는 이력 조회 1개.

## 5. 데이터 모델 변경

- **신규 마이그레이션**: issue-tracking V034 — `SELECT pgmq.create('q_webhook_events')` (+ init_codegen 미러). circuit breaker 상태 컬럼 **없음**(in-memory).
- **재사용(변경 0)**: `outbound_webhooks`(PR2 V603) 구독 조회, `webhook_deliveries`(PR2 V603) 이력 INSERT/조회. GIN(event_filter)·enabled 부분 인덱스 PR2에서 이미 생성.

## 6. 발송 payload · 헤더 (계약)

발송 body(수신자가 받는 JSON, HMAC 서명 대상).
```json
{ "event": "issue.created", "deliveryId": "<stable-key>", "occurredAt": "<ISO-8601>", "data": { ... } }
```

**data 계약 — 이벤트별 화이트리스트 (내부 VO/사용자 UUID 미노출, C3)**. 워커가 wire JSON을 파싱해 **안전 스칼라만** 재구성한다. `ActorId`/`reporterId` 등 내부 VO(`{"value":"<uuid>"}` 래핑)와 내부 사용자 UUID는 외부 공개 webhook에 노출하지 않는다(MVP — 필요 시 후속에서 bare UUID로 평탄화).
- `issue.created`: `{ issueKey, projectKey, summary }`
- `issue.transitioned`: `{ issueKey, projectKey, fromState, toState }` (projectKey는 issueKey에서 파싱 — EC1)

헤더.
```
Content-Type: application/json
X-BTS-Event: issue.created
X-BTS-Delivery: <stable-key>
X-BTS-Signature: sha256=<hex>       # secret 있을 때만 (GitHub webhook 관례)
```
- 서명 = `HMAC_SHA256(key=평문secret, message=raw body bytes)` → hex, `sha256=` 접두.

**멱등키 — 재전달 안정성 (B1)**. `deliveryId`(=`X-BTS-Delivery`)는 pgmq `msg_id` 기반 **결정적 안정 키**(예: `UUIDv5(ns, "${webhookId}:${msgId}")`). VT 만료 재전달(워커 크래시 복구) 시 같은 `msg_id`라 **동일 키** → 수신자가 dedup 가능(at-least-once + 수신자 멱등, ADR). `webhook_deliveries.id`(내부 per-attempt 감사 PK)와 **분리** — 이력 조회 응답 DTO엔 내부 `id`, 외부 발송 헤더/payload엔 안정 키. 재전달로 인한 중복 발송은 이 안정 키로 수신자단 무해화(개별 재시도 없음, EC4).

## 7. 엣지 케이스

- **EC1**. `IssueTransitioned`에는 `projectKey` 필드 없음 → issueKey(`ATLAS-42`)에서 프로젝트 접두(`ATLAS`) 파싱해 projectKey 매칭. `IssueCreated`는 projectKey 직접 보유.
- **EC2**. secret 미설정 구독 → 서명 헤더 생략, 나머지 발송 정상.
- **EC3**. 매칭 구독 0개 → 발송 없음, 메시지 즉시 delete.
- **EC4 (fanout 부분 실패)**. 구독 3개 중 1개 실패 → 성공 2·실패 1 각각 이력 기록, 메시지는 delete(재전달 안 함 — 성공분 중복 방지). 실패분은 circuit breaker 카운터 반영.
- **EC5**. circuit OPEN 구독 → 발송 스킵(이력 기록 안 함, half-open 시각 도래까지 대기).
- **EC6**. SSRF 차단 URL → 발송 안 함, `webhook_deliveries` FAILED(error_detail=차단 사유 상수, 내부 host 미echo) + circuit 카운터 반영.
- **EC7**. 이벤트 JSON 파싱 실패(poison) → read_ct>MAX archive(FR-NT-05 handlePoison 패턴).
- **EC8**. PUBLISHABLE 아닌 이벤트가 q_webhook_events에 유입(방어) → 매칭 구독 0(카탈로그 검증됨)이거나 무시 후 delete.
- **EC9**. disabled/soft-deleted 구독 → 조회 술어(`enabled=true AND deleted_at IS NULL`)로 매칭 제외.
- **EC10**. 발송 성공(2xx)이나 body 대용량 응답 → response_code만 기록, body 미저장(이력 비대화 방지).

## 8. 제약 조건

- **BC 격리**. issue-tracking은 이벤트 발행만(직접 import 0). search 워커는 issue-tracking 도메인 타입 import 금지 — pgmq JSON wire 포맷 그대로 파싱.
- **보안 로그**. URL은 host만 로그(FR-NT-05 `extractHost` 패턴). secret 평문·서명값 로그 금지. SSRF 차단 사유는 상수(내부 host 미echo).
- **@Scheduled 결선**. search 모듈 첫 `@Scheduled`면 `@EnableScheduling` 결선 확인(메모리 module-first-scheduled-worker). 이미 FR-EX-02 export worker가 있으면 재사용.
- **VT/BATCH 타이밍 — fanout 재산정 (C1)**. FR-NT-05 산식 `read timeout × BATCH < VT`(VT=60/BATCH=5)는 **메시지당 발송 1건** 전제다. PR3는 fanout(메시지 1 → 매칭 구독 N 순차 POST)이라 최악 `per_call_timeout × N × BATCH`가 VT를 초과하면 처리 중 VT 만료 → 재전달 → 중복 fanout. **완화**: `BATCH_SIZE=1`(한 이벤트의 전체 fanout이 VT 안에 완료)로 시작 + per-call 타임아웃(shared connect3s/read5s) 명시. B1 안정 멱등키로 잔여 중복은 수신자단 무해화.
- **pgmq consumer 생명주기**. 메시지 delete/archive 필수(메모리 pgmq-consumer-message-lifecycle-p0). 무한 재전달 차단.

## 9. 측정 가능한 완료 기준

- [ ] dual-send: PUBLISHABLE 이벤트 발행 시 `q_issue_events`·`q_webhook_events` 두 큐 모두 메시지 존재(통합테스트). non-PUBLISHABLE(issue.updated 등)은 q_webhook_events 미유입.
- [ ] fanout: event_filter+projectKey 매칭 구독만 발송, 비매칭/비활성/타프로젝트 제외(통합테스트, positive+negative control로 vacuous 방지).
- [ ] HMAC: secret 있는 구독은 유효한 `sha256=` 서명 헤더, secret 없으면 헤더 부재(단위/통합).
- [ ] circuit breaker: 5회 실패→OPEN, OPEN 중 스킵, 60초 후 half-open 상태 전환(단위테스트, Clock 주입).
- [ ] 발송 이력: 성공/실패/차단 각각 `webhook_deliveries` 정확 기록(status/response_code).
- [ ] 이력 조회 API: SYSTEM_ADMIN 200, 비-admin 403, 미존재 404, 페이지네이션(통합테스트).
- [ ] SSRF 발송 시점 재검증: 차단 URL 미발송(통합테스트).
- [ ] pgmq 생명주기: 정상 delete, poison archive, 크래시 재전달(통합테스트).
- [ ] 전 모듈 빌드+테스트 그린, ktlint/detekt 0, ArchUnit BC 격리 통과.

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 정의된 발송 파이프라인, office-hours/brainstorming 대화형 부적합[bts-spec-office-hours-mismatch]).

Sanity check로 gap 3건 식별·해소.
1. **이벤트 카탈로그 2종 vs 7종 drift** — 상위 spec은 7종, PR2 코드는 2종(`WebhookEventCatalog.PUBLISHABLE`). PR2 확정(2종)을 정본으로 채택 + 상위 spec §0 동기화(§1에 명시).
2. **발송 이력 조회 API 범위** (Maxi 결정) → **PR3 포함** (FR9). PR4는 순수 프론트 유지, BC 경계 깔끔.
3. **fanout 부분 실패 재시도** (Maxi 결정) → **메시지 delete + 이력/circuit 관찰** (FR8/EC4). 개별 재시도 큐 미도입(MVP 범위·복잡도 회피), "재시도"=pgmq VT 재전달(크래시 복구)로 해석. at-least-once + 수신자 멱등(ADR).

미해소 gap 없음. 선반영 엣지 케이스 §7(EC1~EC10), 제약 §8.
