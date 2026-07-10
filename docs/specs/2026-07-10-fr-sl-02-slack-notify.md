# FR-SL-02 Slack 알림 발송 (백엔드 코어 D1~D5) — 스펙

> 날짜: 2026-07-10 · slug: fr-sl-02-slack-notify · BC: slack-integration
> 관련 ADR: [2026-07-10-fr-sl-02-slack-notification-delivery.md](../decisions/2026-07-10-fr-sl-02-slack-notification-delivery.md)
> 범위: D1~D5 백엔드 코어. D6(연결 UI)·D7(E2E)·채널 라우팅(FR-SL-06)은 범위 밖.

## 목표 한 줄

이슈 이벤트(멘션·할당)를 Slack **DM**으로 발송한다. notification BC가 SLACK 수신자를 `q_slack_deliveries` 큐로 발행하고, slack-integration BC의 워커가 소비해 `chat.postMessage`로 전송한다.

## 확정 사항 (Maxi 게이트)

1. **DM 트리거 이벤트 = 멘션 + 할당**. `issue.mentioned`·`issue.assigned` 전역 SLACK `notification_policies` 시드 2종. 연결(linking)이 opt-in, 연결 해제가 opt-out. 이벤트별 세밀 opt-out은 FR-NT-04 후속.
2. **할당 파이프라인 수리 포함** (Maxi 게이트, brainstorming 발견). `issue.assigned` 이벤트가 **발행되는 곳이 없어** 현재 IN_APP 할당 알림조차 dormant. issue-tracking `changeAssignee`가 `IssueAssigned` 이벤트를 발행하도록 수리. **notification 소비 측은 이미 완전 배선**(ISSUE_ASSIGNED enum + generic 워커 경로 + V401 IN_APP 정책 + resolver ASSIGNEE/PREVIOUS_ASSIGNEE via IssueRecipientLookupPort) — 워커 무변경. **부작용**: IN_APP 할당 알림도 이때부터 발화(의도된 기능 완성, SDD §9.1.2).
3. **연결 메커니즘 = 엔진 + 매핑 서비스만 D1~D5**. `user_slack_mapping` 테이블 + repository + `link/unlink/resolve` 서비스(통합 테스트로 검증). 사용자 연결 플로우(Slack OIDC "Sign in with Slack") + UI는 D6.
4. **워크스페이스 = 단일 가정**. `team_id` 컬럼은 저장하되, 현 사내 단일 Slack 워크스페이스이므로 봇 토큰은 `slack_installs`의 단일 install에서 해석(다중 install이면 team_id로 disambiguate).

### 영향 BC (3개, pgmq 경계 cross-BC)
- **issue-tracking**: `IssueAssigned` 이벤트 신설 + `changeAssignee` 발행(생산자 수리).
- **notification**: `SlackChannelSender`(→ q_slack_deliveries 발행) + q_slack_deliveries 큐 + SLACK 정책 시드 2종. 워커/resolver 무변경.
- **slack-integration**: user_slack_mapping·slack_delivery_log + SlackDeliveryWorker + SlackMessageClient + Block Kit.
- 코드 import 0(모든 경계 pgmq JSON) → BC 격리 유지. PR #13 frontend+same-BC view-layer 예외처럼 "완결된 이벤트 체인" 정당화.

## 사용자 시나리오 (Given-When-Then)

- **S1 (멘션 DM)**. Given Slack 계정을 연결한 사용자 U가 있고, When 다른 사용자가 이슈 PROJ-123 댓글에서 `@U`를 멘션하면, Then U의 Slack DM으로 "PROJ-123에서 멘션되었습니다" + 이슈 링크 Block Kit 메시지가 도착한다.
- **S2 (할당 DM)**. Given 연결한 사용자 U, When 이슈가 U에게 할당되면, Then U의 Slack DM으로 할당 알림이 도착한다.
- **S3 (미연결 사용자)**. Given Slack 계정을 연결하지 않은 사용자 V, When V가 멘션되면, Then Slack DM은 발송되지 않는다(매핑 없음 → graceful skip). 인앱 알림은 정상(별개 채널).
- **S4 (rate limit)**. Given Slack API가 429(rate limited)를 반환, When 워커가 전송을 시도하면, Then 메시지를 삭제하지 않고 재전달(vt 만료) → 지수 백오프 재시도. read_ct 초과 시 dead-letter(archive).
- **S5 (멱등)**. Given 같은 알림 이벤트가 pgmq at-least-once로 2회 전달, When 워커가 두 번 처리하면, Then Slack DM은 1회만 발송된다(dedup).
- **S6 (봇 미설치)**. Given slack_installs가 비어 있음(App 미설치), When SLACK 알림이 발행되면, Then 워커는 봇 토큰 부재로 전송 불가 → 경고 로그 + skip(재시도 무의미하므로 delete).

## 기능 요구사항 (FR)

- **FR0 (할당 이벤트 생산자 수리)**. issue-tracking에 `IssueAssigned(issueKey, actorId, occurredAt)` 이벤트 신설(`IssueDomainEvent` sealed 서브타입 + `@JsonTypeName("issue.assigned")`). `changeAssignee`가 `assigneeChanged` 시 `recordHistory` 이후 `eventPublisher.publish(IssueAssigned(...))`(Propagation.MANDATORY 동일 트랜잭션). occurredAt은 형제 이벤트(IssueTransitioned)와 동일 소스. projectKey 미포함(IssueTransitioned 선례, issueKey 기반 resolver). notification 소비 측 무변경 검증.
- **FR1**. notification BC에 `SlackChannelSender`(implements `NotificationChannelSender`, `supports(Channel.SLACK)`) 추가. `send(notification)`이 `q_slack_deliveries`에 JSON 발행. slack-integration 코드 import 0.
- **FR2**. `q_slack_deliveries` pgmq 큐 신규(notification 모듈 마이그레이션, producer-creates 관례 = q_issue_events/q_transition_events와 동일).
- **FR3**. 발행 JSON 계약: `{ recipientUserId, eventType, issueKey, title, occurredAt }`. (body는 현재 null이므로 title 중심. dedupKey는 slack 쪽에서 재계산 or 페이로드에 포함 — 아래 FR7 참조.)
- **FR4**. slack-integration에 `user_slack_mapping(user_id UUID PK, slack_user_id TEXT, team_id TEXT, linked_at TIMESTAMPTZ)` 테이블 + `SlackUserMappingRepository`(JdbcTemplate) + `SlackUserMappingService`(link/unlink/resolveByUserId).
- **FR5**. slack-integration에 `SlackDeliveryWorker`(@Scheduled, `q_slack_deliveries` consumer). 흐름: read → JSON 파싱 → recipientUserId로 매핑 조회 → 미매핑이면 delete(skip) → 봇 토큰 해석 → Block Kit 렌더 → `chat.postMessage`(DM) → 성공 delete / 429·5xx 재전달 / read_ct 초과 archive.
- **FR6**. `SlackMessageClient`(slack-api-client `MethodsClient.chatPostMessage` 래퍼). DM 대상 = slack_user_id(Slack이 IM 채널로 자동 해석). 봇 토큰 = `slack_installs`에서 복호화(SecretEncryptor 재사용).
- **FR7 (멱등)**. Slack 발송 중복 방지. notification의 `Notification.dedupKey`를 페이로드에 포함해 전달하고, slack 쪽 `slack_delivery_log(dedup_key UNIQUE, ...)`로 발송 이력 기록 → 이미 있으면 skip. (또는 pgmq 단일 전달 신뢰 + best-effort. dedup 테이블이 안전.)
- **FR8**. Block Kit 메시지 = 헤더(이벤트 설명) + 이슈 링크. 이슈 URL = `{bts.atlas.base-url}/issues/{issueKey}`(신규 config property). 이슈 summary·actor 이름 등 rich 콘텐츠는 후속(현 인앱 알림과 동일 수준 = key + eventType).
- **FR9**. SLACK `notification_policies` 시드 — `issue.mentioned/MENTIONED/SLACK`, `issue.assigned/ASSIGNEE/SLACK`(전역, enabled=TRUE). notification 모듈 마이그레이션.

## 비기능 요구사항 (NFR)

- **NFR1 (전달 보장)**. at-least-once + dedup으로 정확히 1회 발송 수렴. 워커에 @Transactional 없음(pgmq vt atomic, learnings `transaction-self-invocation-REQUIRES_NEW`).
- **NFR2 (rate limit)**. 429 시 재전달 기반 지수 백오프. VT/BATCH는 WebhookDispatchWorker(VT=60, BATCH=5) 참고 — 외부 HTTP 발송 특성상 read timeout×BATCH < VT 보장(중복 POST 방지).
- **NFR3 (BC 격리)**. notification↔slack 코드 의존 0. pgmq JSON 계약만. 양쪽 방어적 파싱.
- **NFR4 (토큰 비노출)**. 봇 토큰 평문 로그/응답 금지(FR-SL-01 3중 미노출 관례 계승).
- **NFR5 (dead-letter)**. read_ct > MAX(5) 시 archive. poison(파싱 실패)도 동일.

## API 인터페이스 (REST)

- **D1~D5 신규 공개 REST 없음**. 전달은 순수 이벤트 구동(REST 무관). `SlackUserMappingService`는 내부 서비스 계약 — D6의 연결 플로우(OIDC 콜백 컨트롤러)가 호출. (D6에서 `POST /api/v1/slack/link` 류 추가 예정, 이번 범위 밖.)

## 데이터 모델 변경

- **slack-integration V701** — `user_slack_mapping` 테이블 + `slack_delivery_log` 테이블(dedup, FR7).
- **notification V409** (V408 이미 점유) — `SELECT pgmq.create('q_slack_deliveries')`(producer-creates 관례) + SLACK `notification_policies` 시드 2행.
- init_codegen.sql 미러 갱신 필요(DATA.md, jOOQ codegen — 단 slack은 JdbcTemplate이라 codegen 무관. notification은 정책 시드만, 스키마 변경 없음 → 큐 생성은 pgmq 함수 호출).

## 엣지 케이스

- **EC1**. recipientUserId 매핑 없음 → delete(skip), 재시도 무의미. (S3)
- **EC2**. slack_installs 비어 있음(봇 미설치) → 경고 + delete(skip). (S6)
- **EC3**. Slack 429/5xx → 재전달. 4xx(invalid_auth 등 영구 오류) → delete or archive(재시도 무의미). Slack 오류 코드 분기 필요.
- **EC4**. JSON malformed → poison 처리(read_ct 초과 archive).
- **EC5**. 같은 dedupKey 재전달 → slack_delivery_log UNIQUE 충돌 → skip. (S5)
- **EC6**. 사용자가 다중 team에 매핑(다중 워크스페이스) → 현재 단일 가정, team_id로 install 선택. install 없으면 EC2.
- **EC7**. 봇이 해당 사용자에게 DM 불가(예: 사용자가 봇 차단, `channel_not_found`) → 영구 오류로 간주 delete + 경고.

## 제약 조건

- notification 모듈에 slack 코드 import 금지(pgmq JSON만).
- slack 워커 @Transactional 금지(pgmq vt 규칙).
- 봇 토큰 평문 미노출.
- 한 PR이 issue-tracking + notification + slack-integration 3모듈 touch — pgmq 경계 cross-BC 패턴으로 문서화(코드 의존 0이므로 BC 격리 유지). 각 모듈 변경은 외과적(issue-tracking=이벤트 1종 발행, notification=sender+시드, slack=엔진).

## 측정 가능한 완료 기준

1. `SlackChannelSender` 단위 테스트 — SLACK notification → `pgmq.send('q_slack_deliveries', <expected JSON>)` 호출 검증(DSLContext mock).
2. `SlackUserMappingService` 통합 테스트(Testcontainers) — link/resolve/unlink round-trip.
3. `SlackDeliveryWorker` 통합/단위 테스트 — 큐 메시지 → 매핑 조회 → `chat.postMessage` 호출(MethodsClient mock), 미매핑 skip, 429 재전달, dedup skip, poison archive.
4. SLACK 정책 시드 마이그레이션 적용 후 `notification_policies`에 2행 존재 검증.
5. end-to-end(가능 시) — q_issue_events(멘션) → NotificationWorker → q_slack_deliveries → SlackDeliveryWorker → postMessage mock 호출. (모듈 격리상 어려우면 각 경계 단위로 분할 검증 + 계약 테스트.)
6. `./gradlew :backend:modules:slack-integration:test :backend:modules:notification:test ktlintCheck detekt` green.

## 구현 유의(→ plan/impl 인계)

- **cross-BC 테스트 마이그레이션 함정**(memory `bts-cross-bc-test-migration`·`new-bc-first-repository-testboot-context-regression`). q_slack_deliveries가 notification 마이그레이션에 있으면 slack 워커 테스트가 이를 필요로 함 → slack Testcontainers 셋업에서 큐 생성(`pgmq.create` idempotent) 또는 `@TestConfiguration` 배선. 각 모듈 테스트가 상대 모듈 마이그레이션에 의존하지 않도록 설계.
- **첫 @Scheduled 워커**(slack 모듈 최초) — `@EnableScheduling` 결선 확인(memory `module-first-scheduled-worker-detektmain-traps`).
- **new-crossbc-dep OpenApi/@MockBean**(memory) — SlackDeliveryWorker가 새 협력자(매핑 repo·MethodsClient) 주입 시 test-boot NoSuchBean 회귀 주의.
- **detektMain type-resolved** 엄격성 · **ktlint expr body** 함정(memory 백엔드 섹션).
- **IssueAssigned occurredAt** — 형제 이벤트가 Clock 주입인지 Instant.now()인지 확인 후 동일 소스(memory Clock 주입 패턴, dedupKey 결정성).
- **changeAssignee 회귀** — 기존 changeAssignee 테스트가 "이벤트 발행 없음"을 전제로 하는지 확인(publish 추가가 기존 단언 깨는지). TDD RED로 이벤트 발행 검증 추가.

## Brainstorming Check

✅ 통과 (2 gap 발견 후 실코드 검증 + Maxi 게이트 확정).
- **Gap A (할당 파이프라인 dead)**: `issue.assigned` 이벤트 생산자 부재 → 현재 IN_APP 할당조차 dormant. `changeAssignee`가 q_issue_events에 아무것도 발행 안 함 확인(recordHistory만). Maxi 결정=파이프라인 수리 포함(FR0). notification 소비 측은 이미 완전 배선(ISSUE_ASSIGNED enum + generic 워커 + V401 정책 + resolver lookup port) → 워커 무변경.
- **Gap B (publishable=false)**: `issue.mentioned` publishable=false는 정책 카탈로그 API 메타일 뿐 발송 경로 미강제 확인 → SLACK 멘션 시드 기능 동작. SDD §9.4 "멘션→Slack DM"과 일치(direct push).
- 검증 방식: phantom 가정을 `grep`/`Read`로 실코드 대조(learnings 2026-05-20 phantom 엔티티 교훈 적용).
