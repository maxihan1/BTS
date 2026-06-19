# FR-PL-02 지연/임박 자동 알림 — 스펙

> slug: fr-pl-02-overdue-notify · BC: issue-tracking (발행 측만) · type: feature
> 선행: FR-PL-01(일정 필드, 완료), FR-NT-01/02/03(notification 파이프라인, 완료)
> 작성: 2026-06-19

## 요약

이슈 마감일(`due_date`)을 매일 1회 스캔해, **임박(due tomorrow)**·**지연(overdue)** 이슈에 대해 도메인 이벤트를 pgmq `q_issue_events` 큐에 발행한다. notification BC의 기존 소비 파이프라인(워커→정책→수신자 resolver→인앱 채널→STOMP 토스트)이 이를 받아 토스트로 전달한다. **notification BC와 프론트엔드는 변경 없음** (FR-NT-01/02에서 forward-looking으로 이미 구축됨).

## Maxi 확정 결정 (스펙 게이트)

1. **임박 기준** = 마감 **1일 전** (due_date == 내일).
2. **재알림 정책** = **혼합** — 임박은 **1회만**, 지연은 **매일** (해결될 때까지).
3. **날짜 필드** = **due_date만** (target_date·start_date 무관).
4. **스캔 시각** = 매일 **KST 09:00 = UTC 00:00** (cron `0 0 0 * * *`, property로 조정 가능).

## 사용자 시나리오 (Given-When-Then)

**S1 (임박, 1회)**. Given 이슈 A의 due_date=내일, resolution 미설정, 미삭제. When 오늘 09:00(KST) 스캔. Then `ISSUE_DUE_SOON` 1건 발행 → ASSIGNEE에게 "A 마감이 임박했습니다" 토스트 1회. (다음 날 A는 due_date=오늘이 되어 임박/지연 어디에도 안 잡힘 → 추가 알림 없음.)

**S2 (지연, 매일)**. Given 이슈 B의 due_date가 과거, resolution 미설정, 미삭제. When 매일 09:00 스캔. Then 매일 `ISSUE_OVERDUE` 1건 발행 → ASSIGNEE + REPORTER에게 "B 마감이 초과되었습니다" 토스트 (매일 1회).

**S3 (종료 이슈 제외)**. Given 이슈 C의 due_date가 과거 BUT resolution 설정됨(완료/종결). When 스캔. Then 알림 없음.

**S4 (삭제 이슈 제외)**. Given 이슈 D가 soft-delete(deleted_at 설정). When 스캔. Then 알림 없음.

**S5 (마감일 없음)**. Given 이슈 E의 due_date=null. When 스캔. Then 알림 없음.

**S6 (멱등 — 같은 날 재실행)**. Given 스캔이 같은 날 두 번 실행(앱 재시작/pgmq 재전달). When 두 번째 처리. Then `dedupKey` 동일 → 중복 알림 0건 (`insertIfAbsent` ON CONFLICT).

**S7 (담당자 없음)**. Given 지연 이슈 F의 assignee=null, reporter 존재. When 스캔. Then REPORTER에게만 토스트(overdue 정책=ASSIGNEE+REPORTER, ASSIGNEE 해석 결과 빈 → REPORTER만 남음). 임박 이슈(정책=ASSIGNEE만)에 assignee 없으면 → 수신자 0 → 알림 없음.

## 기능 요구사항 (FR)

- **FR1**. issue-tracking에 신규 `@Scheduled(cron=...)` 워커. 매일 KST 09:00(UTC 00:00) 1회 실행. cron은 property(`bts.issue.due-scan.cron`)로 주입, 기본값 `0 0 0 * * *`. `Clock` 주입(테스트 결정성).
- **FR2 (임박)**. `due_date == today+1 AND resolution_id IS NULL AND deleted_at IS NULL` 인 이슈마다 `IssueDueSoon(issueKey, projectKey, occurredAt)` 발행. exact-day 매칭이라 이슈당 생애 1회 발화(혼합 정책의 "임박 1회").
- **FR3 (지연)**. `due_date < today AND resolution_id IS NULL AND deleted_at IS NULL` 인 이슈마다 `IssueOverdue(issueKey, projectKey, occurredAt)` 발행. 매일 재발화("지연 매일").
- **FR4**. 발행은 기존 `IssueEventPublisher.publish()` (`@Transactional(propagation=MANDATORY)`, outbox). 워커가 `@Transactional` 메서드로 스캔+발행을 한 트랜잭션에 감쌈.
- **FR5 (이벤트 계약)**. 신규 `IssueDueSoon`·`IssueOverdue`를 `IssueDomainEvent` sealed interface에 추가. `@JsonSubTypes.Type(name="issue.due_soon")` / `"issue.overdue"` + `@JsonTypeName` 등록. payload 필드 = `issueKey: String`, `projectKey: String`, `occurredAt: Instant`. (수신자 ASSIGNEE/REPORTER는 notification resolver가 `IssueRecipientLookupPort`로 issueKey 기반 조회 — event에 reporterId 불요.)
- **FR6 (occurredAt = 재알림 제어)**. `occurredAt` = 스캔 기준일의 UTC 자정 Instant(`scanDate.atStartOfDay(UTC)`). 같은 날 재실행은 동일 occurredAt → dedupKey 동일 → 중복 차단. 다음 날 지연 재스캔은 occurredAt 변화 → 새 알림(매일 1회).
- **FR7 (비교 기준일)**. `today` = 스캔 시각을 **Asia/Seoul** 존으로 본 `LocalDate` (사용자 캘린더 날짜와 일치). due_date도 캘린더 날짜(타임존 무관)라 같은 존 기준 비교. (UTC 00:00 = KST 09:00이라 현 cron에서는 UTC LocalDate와 동일하지만, cron 변경에 견고하도록 KST 명시.)

## 비기능 요구사항 (NFR)

- **NFR1 (멱등)**. 같은 날 N회 실행 시 수신자당 알림 ≤ 1 (dedupKey 결정성). 통합 테스트로 검증.
- **NFR2 (성능)**. 스캔 쿼리는 `due_date` 부분 인덱스 활용. 신규 마이그레이션 1개로 부분 인덱스 추가 권장 — `CREATE INDEX CONCURRENTLY ... ON issues(due_date) WHERE deleted_at IS NULL AND resolution_id IS NULL`. (product D3 "활용"의 성능 보강. plan에서 최종 확정.)
- **NFR3 (트랜잭션 경계)**. 스캔=readOnly, 발행=MANDATORY. 일일 지연 이슈 수가 매우 많을 경우(>수천) 단일 트랜잭션 부담 → 배치 발행 고려(현 규모 1K 사용자에서는 단일 tx 허용, plan에서 판단).
- **NFR4 (테스트 결정성)**. `Clock` 주입 필수. `Clock.fixed()`로 임박/지연/경계일 검증.
- **NFR5 (관측성)**. 발행 건수 로그(`due_scan_published dueSoon={} overdue={}`). PII 미포함.

## API 인터페이스 (REST)

없음. 백그라운드 스케줄러 전용 (외부 API 표면 0). 기존 토스트 전달 경로 재사용.

## 데이터 모델 변경

- **신규 테이블/컬럼 없음** (product D3 "활용").
- **인덱스 1개 추가 권장** (NFR2) — issue-tracking 마이그레이션 `V0xx__issue_due_date_scan_index.sql`. `init_codegen.sql` 미러 불요(인덱스는 jOOQ 코드 생성에 영향 없음 — plan에서 확인). 부분 인덱스 조건이 스캔 쿼리 WHERE절과 정렬.

## 엣지 케이스

- **due_date == today (당일 마감)**. 임박(today+1)도 지연(<today)도 아님 → 알림 없음. 의도된 동작(전날 임박 알림으로 갈음).
- **스캔 누락(다운타임)**. 임박은 해당일 놓치면 미발송(수용 — "1회만"의 비용). 지연은 다음 스캔에 재포착(robust).
- **assignee=null 임박**. 정책=ASSIGNEE만 → 수신자 0 → 알림 없음(S7).
- **actor 부재**. 스케줄러 이벤트는 actorId 없음 → resolver의 actor 제외 로직 무영향(아무도 제외 안 함). `buildSourceEvent`의 actorId는 MissingNode 허용.
- **대량 동일 발행**. notification 워커가 배치(qty=10) 폴링이라 다수 이벤트도 순차 소비. 백프레셔 문제 없음.
- **타임존 경계**. due_date가 캘린더 날짜라 시/분 무관. FR7 비교 존(KST)으로 일관.

## 측정 가능한 완료 기준

- [ ] 단위 테스트: 임박 1회 / 지연 매일 / 종료(resolution) 제외 / 삭제 제외 / due_date null 제외 / occurredAt 정규화(멱등) / 경계일(today, today+1, today-1).
- [ ] 통합 테스트(Testcontainers): 시드 이슈 → 스캔 실행(Clock.fixed) → `q_issue_events`에 기대 이벤트 적재 확인. 가능하면 NotificationWorker 소비까지 end-to-end(이벤트 타입·수신자).
- [ ] 멱등 통합 테스트: 같은 Clock으로 2회 실행 → 알림 1회.
- [ ] ktlint + detekt green, ArchUnit(@Scheduled→@Component) 통과.
- [ ] D6/D7 deviation 명시: 프론트 토스트는 FR-NT-02 제네릭 경로가 커버(신규 코드 0). 스케줄러는 시간기반이라 별도 Playwright E2E 대신 통합 테스트로 대체.

## D6/D7 처리 (deviation)

- **D6 (프론트 토스트)**. `useNotificationStream.ts`가 이벤트 타입 무관 제네릭 토스트. 백엔드가 한국어 title 생성("마감이 임박/초과되었습니다") → **신규 프론트 코드 0**. 기존 FR-NT-02 토스트 E2E가 렌더 경로 커버.
- **D7 (E2E)**. 스케줄러 발화는 시간 의존 → 브라우저 E2E 부적합. 백엔드 통합 테스트(발행→소비)로 대체. product 체크박스는 이 대체를 근거로 마킹.
