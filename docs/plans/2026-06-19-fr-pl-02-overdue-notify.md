# FR-PL-02 지연/임박 자동 알림

> slug: fr-pl-02-overdue-notify
> type: feature
> agent: backend-engineer
> 생성: 2026-06-19

## Brief

**원문**. "fr-pl-02 진행하자"

**FR-PL-02 (agile-planning product §6.2)** — 지연/임박 자동 알림. 우선순위 높음. 선행 §6.1(FR-PL-01 일정 필드, 완료) + notification BC.

**핵심 흐름**. 이슈 마감일(due_date/target_date)을 매일 1회 스캔 → 지연(overdue)/임박(D-day 접근) 이슈를 찾아 pgmq 이벤트 발행 → 기존 notification 인프라가 토스트로 전달.

**product D단계**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — D-day 트리거 (스케줄러) (backend-engineer)
- D3. 데이터 모델 — (활용, 신규 스키마 없음) (db-engineer)
- D4. 백엔드 — Spring @Scheduled 일 1회 + pgmq 이벤트 (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 알림 토스트 (frontend-engineer)
- D7. E2E (qa-engineer)

**classify 보정 메모**. primary_bc=notification→issue-tracking (스케줄러는 일정필드 보유 BC 소유, BC 격리상 다른 BC 테이블 직접 조회 불가). type=backend→feature. cross-BC(스케줄러 위치 / notification 소비 / PR 분할 여부)는 도메인 단계에서 결정.

## 도메인 정리

- **BC**: issue-tracking (스케줄러 = 발행 측, Maxi 확정). notification BC는 **변경 0** — 이미 완비됨.
- **핵심 발견 (코드 실측)**. notification 소비 측이 FR-NT-01 구축 시 forward-looking으로 미리 만들어져 있음.
  - `NotificationEventType.kt:34,37` — `ISSUE_DUE_SOON("issue.due_soon", false)`, `ISSUE_OVERDUE("issue.overdue", false)` 이미 존재
  - `NotificationWorker.buildTitleBody()` L289-290 — "마감이 임박했습니다" / "마감이 초과되었습니다" 이미 처리
  - `V401__seed_default_policies.sql` L30/33-34 — due_soon→ASSIGNEE, overdue→ASSIGNEE+REPORTER (IN_APP, 전역) 이미 시드됨
  - 프론트 `useNotificationStream.ts` — 이벤트 타입 무관 제네릭 토스트(`toast(title, {description: body})`). **D6 신규 코드 0**
- **신규 구현 = issue-tracking 발행 측만**.
  1. `IssueDomainEvent` sealed interface에 `IssueDueSoon`/`IssueOverdue` 추가 (`@JsonTypeName("issue.due_soon")`/`"issue.overdue"` + `@JsonSubTypes` 등록). 페이로드 최소 = `issueKey`, `projectKey`, `occurredAt` (수신자는 notification의 resolver가 포트로 조회, actorId 부재 OK)
  2. `IssueRepository` — 열림(`resolution_id IS NULL AND deleted_at IS NULL`) + due_date 기준 범위 조회 메서드
  3. 신규 `@Scheduled` 워커 (BulkOperationCleanupWorker 패턴: cron + Clock 주입 + `@Transactional`) — 매일 스캔 → `IssueEventPublisher.publish()` (MANDATORY tx outbox)
  4. 백엔드 테스트 (단위 + Testcontainers 통합)
- **종료 이슈 제외**. `Issue.resolutionId != null` = 해결됨(종료). BC 격리상 워크플로우 상태 직접조회 불가 → `resolution_id IS NULL`이 cross-BC-safe "열림" 신호 (glossary "DONE 전환 시 resolution 필수").
- **재알림 멱등성**. `dedupKey = hash(eventType, issueKey, occurredAt, recipientUserId, channel)` (NotificationWorker L247). 스케줄러가 `occurredAt`을 **날짜 단위 정규화**하면 "하루 1회 재알림" (같은 날 재전달은 dedup), **고정**하면 "1회만". → 스펙 결정 사항.
- **스펙에서 정할 핵심 product 결정**. (1) 임박 기준 LEAD_DAYS (며칠 전부터 due_soon) (2) 재알림 정책 (매일 vs 1회) (3) due_date만 vs target_date 포함 (4) cron 시각.
- **새 용어**. "지연 알림(overdue)", "임박 알림(due_soon)" — glossary 추가 후보 (Maxi 승인 대기).
- **기존 결정 충돌**: 없음. 관련 ADR(notification BC bootstrap/in-app channel/webhook) 모두 파이프라인 구축 건, FR-PL-02는 신규 생산자.
- **관련 ADR**: 없음 (신규 아키텍처 결정 없음 — 기존 파이프라인 재사용). 재알림 정책은 spec에 인라인.

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-pl-02-overdue-notify.md](../specs/2026-06-19-fr-pl-02-overdue-notify.md)

핵심 3줄 요약.
- 매일 KST 09:00(UTC 00:00) 스케줄러가 `due_date` 스캔 — 임박(due tomorrow, 1회)·지연(overdue, 매일) 이슈에 도메인 이벤트 발행. `resolution_id IS NULL AND deleted_at IS NULL`(열림) 필터.
- 신규 = issue-tracking 발행 측만(이벤트 2종 + due_date 쿼리 + @Scheduled 워커). notification 소비·프론트 토스트는 변경 0 (코드 실측 검증).
- 재알림 = occurredAt(스캔일 UTC 자정)로 제어 — 같은 날 멱등, 지연은 날마다 새 알림. 결함 격리 위해 발행 tx를 이슈/배치 단위로.

Maxi 확정 4결정. ①임박=마감 1일 전 ②혼합 재알림(임박1회+지연매일) ③due_date만 ④cron `0 0 0 * * *`(KST 09시).

## Brainstorming Check

✅ 통과 (1회 iteration — 직접 적대적 새너티 체크). 발견·반영 갭 2건.
- 발행 결함 격리(이슈/배치 단위 tx, self-invocation 함정) → NFR3 보강.
- due_date 재조정 시 임박 재발화 엣지 → 엣지 케이스 추가.
- "notification 변경 0" 가정을 소비 경로 코드 실측으로 검증 완료(가정→사실).

## Plan

> 모두 issue-tracking 단일 BC. agent=backend-engineer. notification/프론트 변경 0.
> 핵심 설계 — 워커(오케스트레이션, tx 없음)가 스캔 후 이슈마다 별도 `IssueDueEventEmitter`(`@Transactional` per-이슈) 호출 → 결함 격리 + MANDATORY publisher 충족 + self-invocation 함정 회피.

### Task 1. IssueDomainEvent에 IssueDueSoon/IssueOverdue 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt`]
- depends-on: []

**RED**: `IssueDomainEventTest`에 Jackson round-trip 테스트 — `IssueDueSoon(issueKey, projectKey, occurredAt)` 직렬화 시 JSON `type`="issue.due_soon", 역직렬화 시 동일 객체 복원. `IssueOverdue`는 "issue.overdue". 실패: 두 클래스 미존재.

**GREEN**: `IssueDomainEvent.kt`에 `@JsonSubTypes.Type(value=IssueDueSoon::class, name="issue.due_soon")`, `IssueOverdue::class name="issue.overdue"` 등록 + 두 data class(`@JsonTypeName` 부착, 필드 issueKey:String, projectKey:String, occurredAt:Instant). 기존 5종 패턴 그대로 미러.

**REFACTOR**: KDoc(발행 주체=스케줄러, 수신자=resolver 포트조회) 한 줄. ktlint KDoc 중괄호/백틱 금지([[ktlint-kdoc-brace-parse-failure]]).

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueDomainEventTest'`

### Task 2. IssueRepository due_date 범위 쿼리 + 부분 인덱스

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueDueDateScanQueryTest.kt`, `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V026__issue_due_date_scan_index.sql`]
- depends-on: []

**RED**: `IssueDueDateScanQueryTest`(Testcontainers) — 시드: 이슈 6종(due=내일/과거/오늘/null + resolution있음/deleted_at있음). `findOpenIssuesDueOn(tomorrow)` → 내일+열림 이슈만. `findOpenOverdueIssues(today)` → 과거+열림 이슈만. resolution/deleted/오늘/null 전부 제외. 반환 projection에 issueKey+projectKey 포함. 실패: 메서드 미존재.

**GREEN**: 두 메서드 추가. `WHERE due_date = ? AND resolution_id IS NULL AND deleted_at IS NULL` / `WHERE due_date < ? AND resolution_id IS NULL AND deleted_at IS NULL`. jOOQ DSL only. projectKey는 기존 IssueMentioned 발행 경로의 projectKey 도출 방식 미러(이슈키 substring 또는 컬럼). 경량 projection 데이터클래스(`IssueDueScanItem(issueKey, projectKey)`). V026 부분 인덱스 `CREATE INDEX idx_issues_due_date_open ON issues(due_date) WHERE deleted_at IS NULL AND resolution_id IS NULL`.

**REFACTOR**: WHERE 조건 공통화(열림 필터 헬퍼) 검토. init_codegen.sql 미러 불요 확인(인덱스는 컬럼 추가 아님 → jOOQ 코드젠 무영향, [[jooq-init-codegen-mirror]]는 컬럼 추가에만 적용).

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueDueDateScanQueryTest'`

### Task 3. IssueDueDateScanWorker + IssueDueEventEmitter (스캔 → 발행)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/duedate/IssueDueDateScanWorker.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/duedate/IssueDueEventEmitter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/duedate/IssueDueDateScanWorkerTest.kt`, `backend/modules/issue-tracking/src/main/resources/application.yml`]
- depends-on: [1, 2]

**RED**: `IssueDueDateScanWorkerTest`(단위, mockk repo+emitter, Clock.fixed) — (a) 임박 이슈 → `emitter.emitDueSoon(item, occurredAt)` 호출, occurredAt=scanDate UTC자정. (b) 지연 이슈 → `emitOverdue`. (c) today=고정일 때 repo에 `today+1`(임박)·`today`(지연 기준) 정확 전달. (d) emitter 한 건 예외 던져도 나머지 계속(결함 격리) + 로그. (e) 빈 결과 → 발행 0. 실패: 워커/emitter 미존재.

**GREEN**: 
- `IssueDueDateScanWorker`(@Component, clock·repo·emitter 주입). `@Scheduled(cron="\${bts.issue.due-scan.cron:0 0 0 * * *}")` `fun scan()` (tx 없음). today=`LocalDate.now(clock.withZone(Asia/Seoul))`. occurredAt=`today.atStartOfDay(UTC).toInstant()`. dueSoon=repo.findOpenIssuesDueOn(today.plusDays(1)), overdue=repo.findOpenOverdueIssues(today). 각 item try-catch로 emitter 호출(한 건 실패가 루프 중단 안 함). 카운트 로그.
- `IssueDueEventEmitter`(@Component). `@Transactional fun emitDueSoon(item, occurredAt)` → `publisher.publish(IssueDueSoon(item.issueKey, item.projectKey, occurredAt))`. `emitOverdue` 동일. per-call REQUIRED tx(비-tx 워커서 호출 → 새 tx → 결함 격리). MANDATORY publisher 충족.
- application.yml에 `bts.issue.due-scan.cron` 기본값 명시(주석으로 KST09시).

**REFACTOR**: BulkOperationCleanupWorker KDoc 톤 미러(Clock 주입 사유, cron). 패키지 `com.bts.issue.duedate` 신설.

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueDueDateScanWorkerTest'`

### Task 4. 발행 end-to-end 통합 테스트 (Testcontainers + pgmq)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/duedate/IssueDueDateScanIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**: `IssueDueDateScanIntegrationTest`(@SpringBootTest + Testcontainers) — 시드 이슈(임박1·지연1·종료1·삭제1·null1) → `worker.scan()` 직접 호출(Clock.fixed) → `q_issue_events`에서 `pgmq.read`로 메시지 조회 → 임박1건(type=issue.due_soon, issueKey 일치)·지연1건(type=issue.overdue)만 적재, 나머지 제외 확인. 멱등: 같은 Clock으로 scan() 2회 → 큐 메시지가 1회분만 늘어나는지(또는 dedup은 소비측이므로 발행은 2회분 — 발행 멱등이 아니라 소비 멱등임을 테스트로 명확화. 발행측은 매 실행 발행, dedup은 NotificationWorker dedupKey가 담당). 실패: 워커 미발행.

**GREEN**: 워커 wiring 확정(빈 등록·@Scheduled 미발화 환경에서 직접 호출). 큐 적재 단언 통과.

**REFACTOR**: 시드 헬퍼 정리. 동시 Testcontainers flaky 시 단독 재실행 확정([[concurrent-testcontainers-suite-flaky]]).

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueDueDateScanIntegrationTest'`

> **발행 vs 소비 멱등 주의**(Task 4 RED 명확화). 발행측(스케줄러)은 매 실행 이벤트를 발행한다(지연은 매일 발행이 정상). "하루 1회"의 멱등은 **소비측** NotificationWorker의 dedupKey(occurredAt 포함)가 보장한다. 따라서 같은 날 scan() 2회 시 큐에는 2회분이 쌓이되 occurredAt이 동일 → 소비측 알림은 1회. Task 4는 발행 정확성만, 소비 멱등은 notification 모듈 기존 테스트가 커버.

## Plan 메타

- task 수: 4
- 예상 wave: 2 (wave1: T1·T2 병렬, wave2: T3, wave3: T4 — 단일 모듈이라 test 컴파일 직렬화 영향 [[bts-plan-wave-gradle-module-compile]])
- TDD 강제: yes (각 task test→impl 커밋 순서)
- 단일 BC(issue-tracking) → security/db/frontend/qa sub-agent 불요. db 성격 마이그레이션(V026)은 backend-engineer가 인덱스 1개라 직접 처리.
- D6/D7 deviation: 프론트 토스트 무코드(FR-NT-02 제네릭), E2E는 통합테스트 대체. product 체크박스 마킹 시 근거 명시.
- 머지 전 동기화 대상: product/agile-planning.md §6.2 D단계 + fr-index/README/CLAUDE 카운트(완료 FR +1) + dashboard 재생성 + verify-master-plan.sh.

## 구현 결과 (← /bts-impl)

- wave1 [T1 이벤트·T2 repo+V026] 병렬 → wave2 [T3 워커+emitter] → wave3 [T4 통합테스트]. 전 task TDD red→green→refactor 순서 검증 ✅.
- issue-tracking 모듈 전체 test + ktlint + detekt **BUILD SUCCESSFUL** (신규 빈 Spring 컨텍스트 wiring 정상, 기존 테스트 무손상).
- **controller 직접 검증이 sub-agent false-green 2건 적발**.
  1. T2 ktlint false-green — IssueDueDateScanQueryTest 스타일 2건(빈줄·expr body). ktlint↔detekt 라인길이 seesaw → 블록body로 해소.
  2. **T4 occurredAt 계약 가짜 그린(중대)** — 에이전트가 TestConfig의 bare ObjectMapper(WRITE_DATES_AS_TIMESTAMPS 미해제)로 occurredAt을 숫자 발행하게 두고, 테스트를 숫자 파싱에 맞춰 통과시킴. 운영(스프링 부트 자동구성)은 ISO 문자열이고 notification 소비측 parseInstant는 ISO만 수용 → 테스트가 진짜 계약 미검증. **수정**: TestConfig mapper를 운영과 동일(WRITE_DATES_AS_TIMESTAMPS off)로 ISO 발행 + parseOccurredAt을 Instant.parse(소비측 계약)로 단순화. 직렬화 회귀 시 즉시 fail.
- D7 deviation: 스케줄러 시간기반 → 브라우저 E2E 대신 T4 통합테스트. 토스트 렌더는 FR-NT-02 기존 E2E 커버. qa-engineer SKIP.

## 리뷰 결과

### plan-eng-review (2026-06-19, eng 집중 독립 리뷰)

**Step 0 — 스코프 챌린지**.
- 기존 코드 재사용 극대화 확인 ✅. notification 소비(NotificationEventType/buildTitleBody/V401 seed/NotificationWorker/EventRecipientResolver)·프론트 토스트(useNotificationStream)·발행(IssueEventPublisher)·스케줄링(SchedulingConfiguration)·워커 패턴(BulkOperationCleanupWorker) 모두 재사용. 신규는 발행 측 최소(이벤트2·쿼리2·워커1·emitter1·인덱스1).
- 최소 변경 ✅. 4 task, 단일 BC. notification/프론트 0.
- 복잡도 — 신규 클래스 2개(worker+emitter)로 "2+ 신규 서비스" smell 약하게 트리거. 단 emitter 분리는 tx 경계(self-invocation 회피) 목적이라 정당. 아래 ⚠️1.
- 검색 — @Scheduled(Layer1 built-in)·pgmq(프로젝트 표준)·occurredAt dedup(기존 패턴). 재발명 0.

**Architecture** ✅.
- 데이터 흐름 깔끔: 스케줄러→repo(열림+날짜필터)→per-이슈 emitter(tx)→IssueEventPublisher→q_issue_events→[기존 소비 파이프라인]. BC 격리 준수.
- ⚠️1 (taste) **per-이슈 emitter tx vs 단일 tx**. 현 설계=per-이슈(결함격리: 한 이슈 실패가 그날 전체 롤백 안 함, "systems over heroes"). 반론: 이벤트 payload가 trivial(issueKey/projectKey/occurredAt 전부 non-null)이라 직렬화 실패 비현실적, 실패는 DB/pgmq 연결뿐(모든 방식 동일). 단일 @Transactional은 BulkOperationCleanupWorker 선례와 정확히 일치(단순성). → **권장: per-이슈 emitter 유지**(알림 누락=핵심 실패모드라 격리가 옳음, 추가 복잡도=빈 1개로 미미). Maxi 게이트1 확인 항목.
- ⚠️2 (non-blocker) **무조건 발행 낭비**. 정책 비활성 프로젝트의 지연 이슈도 매일 발행→소비측서 정책필터로 폐기. 1K 규모서 허용, BC 격리상 발행측서 정책 조회 불가하니 현 설계가 맞음. 기록만.
- ⚠️3 (non-blocker) **보관/비활성 프로젝트 이슈**. 스캔이 모든 due_date 이슈 대상. 보관 프로젝트 멤버도 알림 받을 수 있음. resolver 가시성 필터가 접근은 차단하나 알림 자체는 발생. 엣지, 후속 고려.

**Code Quality / Tests** ✅.
- TDD RED/GREEN/REFACTOR 각 task 구비. 경계일(today/today±1)·종료/삭제/null 제외·멱등·결함격리 커버.
- DRY: 열림 필터 두 쿼리 중복 → REFACTOR서 헬퍼 추출 명시. 좋음.
- ⚠️4 (확인 필요, non-blocker) **소비측 due_soon/overdue 테스트 커버리지**. 발행측은 Task4가 완전 커버. 소비측(이벤트→토스트)은 notification 기존 테스트 의존. enum/seed 사전등록 시 소비 테스트도 추가됐는지 확인 권장. cross-BC라 이 PR서 추가 시 FR-NT-04 충돌 위험 → 발행측 커버 + 수동 verify로 갈음 가능.

**Performance** ✅.
- V026 부분 인덱스가 due_date<today(범위)·=today+1(포인트) 둘 다 지원. 일일 스캔 효율적.
- per-이슈 N tx 오버헤드 미미(1K 규모). 필요 시 배치(100/tx) 중간안 가능하나 현재 premature.

**BLOCKER: 없음.** plan 진행 가능. 게이트1 확인 항목 = ⚠1(emitter tx 설계, 권장=유지).

### PR 단위 리뷰 (2026-06-19, 게이트2 직전)

**superpowers:code-reviewer → PASS** (절대규칙 19 위반 0, BLOCKER 0). 검증: occurredAt ISO 계약 발행↔소비 일치, 결함격리 tx self-invocation 우회 없음, jOOQ DSL only+readOnly, V026 부분인덱스 정렬, BC격리(notification import 0), enum/이벤트 추가 카운트가드 비해당. CONCERNS 2(비차단): C1 테스트 시드 .copy(dueDate)는 dueDate 검증 추가 시 재점검(현 무해), C2 발행/소비 멱등 경계는 소비측 dedup 의존(cross-BC 계약 인지).

**적대적 리뷰(general-purpose) → PASS** (BLOCKER 0). 8지점 정확성 추적: 날짜경계 off-by-one 없음(임박=today+1 1회·지연=<today 매일·당일 미포함 의도), occurredAt UTC자정 정규화로 같은날 dedupKey 동일(소비 멱등), per-item try-catch 결함격리 실증, 이벤트 와이어값 1:1, 발행/소비 멱등 구분 정확. CONCERN 1: **PROJECTS INNER JOIN이 DELETED_AT 미필터** → 삭제 프로젝트 이슈 알림 가능. **단 프로젝트 소프트삭제 기능 미구현(deleteProject 0건)이라 현재 무효 + 기존 전 list 쿼리 관례와 동일** → 이 PR 미수정(프로젝트 삭제 구현 시 일괄 재검토). NIT 2(페이징 없음·prod yml 부재): 둘 다 1K 규모서 무해 + BulkOperationCleanupWorker 선례 동일.

**종합: BLOCKER 0.** 

### 게이트2 결정 — CONCERN 수정 후 머지 (Maxi)

적대적 리뷰 CONCERN(소프트삭제 프로젝트 이슈 미제외)을 **수정**. 두 스캔 쿼리에 `.and(PROJECTS.DELETED_AT.isNull)` 추가 + TDD(T3 테스트: 소프트삭제 프로젝트 DELP의 임박·지연 이슈가 스캔서 제외됨, red→green). 프로젝트 소프트삭제 기능은 아직 미구현이라 현재 효과는 0이나, 알림 기능 한정 방어로 선제 적용. V026 인덱스는 issues 필터 가속 그대로(프로젝트 필터는 join predicate). 모듈 전체 재검증 BUILD SUCCESSFUL.
