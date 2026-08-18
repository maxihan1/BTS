# FR-API-03 — 구독형 아웃바운드 Webhook — 스펙

> slug: fr-api-03-outbound-webhook · BC: search-export-import · type: feature
> ADR: [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md)
> **이 스펙 = 전체 FR 범위 + 4-PR 분할. 본 /bts run 실제 구현 범위 = PR1(shared 추출)만.**

## 0. 전체 범위 · PR 분할 (Maxi 확정)

FR-API-03은 규모가 커서 4개 PR로 분할한다(Maxi Q2=A). 이벤트 소싱은 issue-tracking dual-send(Maxi Q1=A).

| PR | 범위 | BC |
|---|---|---|
| **PR1 (본 run)** | SSRF URL 검증기 + 아웃바운드 HTTP 클라이언트 설정을 shared-kernel `com.bts.shared.http`로 추출, FR-NT-05가 shared 사용하도록 교체. **순수 리팩터·동작불변**. | shared-kernel + notification(교차 1회) |
| PR2 | `OutboundWebhook` 구독 도메인 + CRUD REST API + secret 암호화 + V603 마이그레이션(outbound_webhooks, webhook_deliveries). 발송 없음. | search-export-import |
| PR3 | issue-tracking dual-send(q_webhook_events) + search fanout/dispatch 워커 + HMAC-SHA256 서명 + circuit breaker + 발송 이력 기록 + 테스트. | issue-tracking(교차) + search-export-import |
| PR4 | 관리 UI(구독 CRUD 화면) + 발송 이력 화면 + E2E. | apps/web |

전체 이벤트 카탈로그(PR3에서 구독 대상): `q_issue_events`의 `IssueDomainEvent` 7종 — `issue.created`/`issue.updated`/`issue.transitioned`/`issue.soft_deleted`/`issue.mentioned`/`issue.due_soon`/`issue.overdue`.

---

## PR1 스펙 (본 /bts run 구현 대상)

### 1. 목표 · 사용자 시나리오

**목표**. 아웃바운드 HTTP 재사용 인프라(SSRF 방어 URL 검증기 + 강화된 HTTP 클라이언트 설정)를 notification BC에서 shared-kernel로 추출해, FR-NT-05(notification 전환 webhook)와 향후 FR-API-03(search 구독 webhook)이 **단일 구현**을 공유한다. 특히 SSRF 방어 로직이 한 곳에만 존재하도록 해 "한쪽만 패치되는" 보안 회귀를 원천 차단한다.

**Given-When-Then (개발자 관점 — 리팩터라 사용자 노출 동작 없음)**.
- Given notification BC의 `WebhookUrlValidator`/`UrlCheck`/HTTP 클라이언트 설정이 존재하고 FR-NT-05 전환 webhook이 이를 사용, When 이들을 shared-kernel `com.bts.shared.http`로 이동하고 notification이 shared 타입을 import하도록 교체, Then 전환 webhook의 **동작이 100% 동일**(같은 SSRF 차단 규칙·같은 리다이렉트 정책·같은 타임아웃)하고 기존 notification 테스트가 그대로 통과한다.

### 2. 기능 요구사항 (FR)

- **PR1-FR-1** `WebhookUrlValidator`(SSRF 내부망 차단·스킴 검사·IPv6 ULA/IPv4-mapped 언래핑)를 shared-kernel `com.bts.shared.http`로 이동. 재사용 맥락이 webhook에 한정되지 않으므로 이름을 일반화(`OutboundUrlValidator` 제안 — plan에서 확정). `@Component` 유지(shared-kernel은 spring-context 의존 보유, `IssueSecurityDirectory` 선례).
- **PR1-FR-2** `UrlCheck` sealed class(Allowed/Blocked/Malformed)를 함께 이동.
- **PR1-FR-3** HTTP 클라이언트 설정(RestClient + `HttpClient.Redirect.NEVER` + connect/read 타임아웃)을 shared-kernel `com.bts.shared.http.OutboundHttpClientConfig`로 이동. RestClient 빈을 제공. shared-kernel build.gradle에 `spring-web` 의존 추가(현재 spring-context/spring-tx만 보유).
- **PR1-FR-4** notification BC(`WebhookDispatcher`, 기존 `WebhookHttpClientConfig`, 관련 테스트)를 shared 타입 import로 교체. 중복 정의 제거.
- **PR1-FR-5** 이동한 클래스의 테스트도 shared-kernel test로 이전(또는 notification에서 shared import로 갱신). SSRF 차단 케이스(loopback/private/ULA/mapped/malformed) 회귀 테스트 보존.

**추출하지 않는 것(notification 잔류 — FR-NT-05 전용)**. `WebhookDispatcher`(전환 엔벨로프 `{"event":"WebhookRequested","issueKey":...}` 빌드), `WebhookDispatchResult`(Sent/Rejected/Failed), `WebhookDispatchWorker`(q_transition_events 소비). 이들은 전환 webhook 고유 로직. PR3의 search 디스패처는 HMAC·발송이력·circuit breaker로 요구가 달라 별도 구현하며 shared 검증기+HTTP설정만 재사용.

### 3. 비기능 요구사항 (NFR)

- **동작 불변(최우선)**. SSRF 차단 리스트·리다이렉트 정책·타임아웃 기본값(connect 3000ms/read 5000ms) 전부 동일. FR-NT-05 전환 webhook의 관측 가능한 동작 변화 0.
- 신규 마이그레이션 0, 신규 공개 API 0, 신규 pgmq 큐 0.
- 전체 백엔드 빌드 + 전 모듈 테스트 그린. shared-kernel ArchUnit(BC 역참조 금지) 통과 — 이동 대상은 BC 의존 0이라 위반 없음.

### 4. API 인터페이스 / 데이터 모델

없음(PR1은 내부 구조 리팩터). API·테이블·마이그레이션은 PR2 이후.

### 5. 엣지 케이스 · 리스크

- **설정 프로퍼티 키 이관(동작불변 핵심)**. 기존 키 `bts.notification.webhook.connect-timeout-ms`/`read-timeout-ms`는 notification 네임스페이스. shared로 옮기면서 일반 키(예: `bts.outbound-http.connect-timeout-ms`)로 변경하되, **application.yml/properties에 기존 키 오버라이드가 있으면 함께 이관**해야 동작이 유지된다. plan은 `grep -rn "notification.webhook.*timeout" backend/**/resources` 로 오버라이드 전수 확인. 오버라이드 없으면 기본값(3000/5000) 그대로라 무해.
- **RestClient 빈 이름·주입**. `WebhookDispatcher`가 주입받는 RestClient 빈 이름/한정자를 shared config가 동일하게 제공해야 주입 실패 없음. 빈 이름 충돌 여부 확인.
- **spring-web를 shared-kernel에 추가**. shared-kernel은 detekt kotlin-version 강등 우회 설정 보유(build.gradle 주석) — 의존 추가 후 detekt/ktlint 재검증 필수. spring-web는 BC 패키지가 아니라 ArchUnit 위반 아님.
- **테스트 이전 누락 = 가짜 그린**. 이동한 검증기의 SSRF 회귀 테스트가 shared-kernel 또는 notification 중 정확히 한 곳에서 실제 실행되는지 확인(양쪽 삭제/중복 방지).
- **notification ArchUnit**. notification이 `com.bts.shared.http`를 import하는 것은 허용(BC→shared 정방향). NotificationBcArchTest에 금지 룰 없는지 확인.

### 6. 측정 가능한 완료 기준

- [ ] `com.bts.shared.http`에 URL 검증기 + `UrlCheck` + HTTP 클라이언트 설정 물리 존재.
- [ ] notification `WebhookDispatcher`/워커가 shared 타입 사용, 중복 정의 제거.
- [ ] `./gradlew test`(또는 clean 빌드) 전 모듈 그린 — 특히 notification webhook 테스트 + shared-kernel ArchUnit.
- [ ] SSRF 차단 회귀 테스트(loopback/private/ULA/mapped/malformed) 정확히 한 곳에서 실행·통과.
- [ ] FR-NT-05 전환 webhook 통합 테스트(있으면) 동작 불변 확인.
- [ ] 타임아웃/리다이렉트/차단 규칙 기본값 diff 0.

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 정의된 순수 리팩터, office-hours 부적합[bts-spec-office-hours-mismatch]).
근거 조사(Explore agent)로 4대 사실 확정: 이벤트 카탈로그(q_issue_events 7종)·암호화 인프라(SecretEncryptor AES-256-GCM)·REST 봉투 표준(AqlSearchPageResponse)·추출 대상 순수성(validator BC의존 0). Sanity gap으로 (1)설정키 이관 (2)RestClient 빈 주입 (3)spring-web 의존 추가 (4)테스트 이전 가짜그린 (5)ArchUnit을 §5에 선반영.
