# FR-SL-03 Slack Unfurl (Atlas URL 자동 카드)

> slug: fr-sl-03-slack-unfurl
> type: feature
> agent: backend-engineer
> 생성: 2026-07-11

## Brief

**사용자 원문**. "fr-sl-03 진행해줘"

FR-SL-03 — Slack Unfurl (Atlas URL 자동 카드). Slack 대화에 Atlas 이슈 URL을 붙이면
Slack이 `link_shared` 이벤트를 백엔드로 전송 → 백엔드가 열람 권한을 확인한 뒤
이슈 키·제목·상태·담당자를 담은 Block Kit 카드를 반환해 Slack이 링크를 카드로 펼침(unfurl).

**분류 결과**. classify-task가 `ui/frontend-engineer`로 오판 → 명세(`slack-integration.md §3.1`)
근거로 `feature/backend-engineer`로 교정. D6 프론트 UI = 해당 없음, D1~D5 backend-engineer, D7 qa-engineer.

**명세 위치**. `docs/plan/product/slack-integration.md §3.1`, `docs/sdd/09-notifications-slack.md §9.3.3`

## 도메인 정리

- **BC**: slack-integration (`com.bts.slack`). issue-tracking/identity-access 직접 import 금지 → shared-kernel 포트 경유.
- **신규 엔티티/VO**: `UnfurlPayload`(link_shared 파싱 결과), `IssueUnfurlView`(카드 이슈 스냅샷). shared-kernel에 `IssueUnfurlPort` 신설.
- **재사용 자산**: `SlackBotTokenResolver.resolve(teamId)`·`SlackMessageClient` SDK 패턴·`IssueVisibilityPort`(가시성 로직 재사용)·`UserLookupPort.findDisplayNamesByIds`·`SlackBlockKitRenderer`·SDK 번들 `SlackSignature.Verifier`.
- **신규 구현**: 서명 검증기(X-Slack-Signature)·`POST /slack/events`·역방향 매핑 조회(+V702 인덱스)·`IssueUnfurlPort`(결합 fail-closed)·`chat.unfurl` 클라이언트·unfurl 카드 렌더.

### Maxi 게이트 결정 (3)
1. **cross-BC 이슈 조회 = 단일 결합 fail-closed 포트** — `getVisibleIssueCard(issueKey, viewerUserId): IssueUnfurlView?`. 볼 수 없으면 null → slack BC가 데이터 물리적 미수신(fail-open 구조 차단).
2. **카드 = 정보 카드만** — 키+제목/상태/우선순위/담당자. 액션 버튼은 FR-SL-05로 미룸.
3. **3초 룰 = ack 200 즉시 + 경량 @Async** — chat.unfurl 비동기. 새 pgmq 큐/마이그레이션 없음.

### 권한 모델
- 공유자(Slack user) → 역매핑 → Atlas user → 열람 권한 판정. 미매핑/무권한 → unfurl 안 함(fail-closed).
- 수용 한계: unfurl 카드는 채널 전원 노출(GitHub/Jira 동일 트레이드오프). ADR 명시.

- **기존 결정 충돌**: 없음. FR-SL-01(수신=자체 컨트롤러)·FR-SL-02(봇토큰/매핑) 자산 연장.
- **관련 ADR**: [docs/decisions/2026-07-11-fr-sl-03-slack-unfurl.md](../decisions/2026-07-11-fr-sl-03-slack-unfurl.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-11-fr-sl-03-slack-unfurl.md](../specs/2026-07-11-fr-sl-03-slack-unfurl.md)

핵심 시나리오 3줄 요약.
- 연결된 사용자가 볼 수 있는 이슈 URL을 채널에 붙이면 `POST /slack/events`(서명검증)로 수신 → 200 즉시 ack → @Async로 카드 unfurl.
- 미매핑/무권한/없는 키 → `getVisibleIssueCard` null → unfurl 안 함(fail-closed, 정보 누출 0).
- 다중 링크는 볼 수 있는 것만 unfurls 맵에 담아 1회 `chat.unfurl`.

신규 구현. ① X-Slack-Signature 검증기(+`BTS_SLACK_SIGNING_SECRET`) ② `POST /slack/events` 컨트롤러 ③ 역방향 매핑 `findUserIdBySlackUserId`+V702 인덱스 ④ `IssueUnfurlPort`(shared-kernel 결합 fail-closed)+issue-tracking 어댑터 ⑤ `chat.unfurl` 클라이언트 ⑥ unfurl 카드 렌더 ⑦ 경계 `@Async` executor.

## Brainstorming Check

✅ 통과 (1회 iteration). 갭 4건 발견·보강 — G1 @Async 경계 executor(NFR6)·G2 test-boot Stub 배선·G3 서명헤더 401+라벨 해석 책임·G4 봇 채널멤버 런북. Maxi 결정 필요 0.

## Plan

> 경로는 worktree 루트 기준. slack 모듈 = `backend/modules/slack-integration/src/main/kotlin/com/bts/slack`(이하 `SLK`), test = `.../src/test/kotlin/com/bts/slack`(이하 `SLKT`). shared-kernel = `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared`(이하 `SHK`). issue-tracking = `backend/modules/issue-tracking`(이하 `ISS`). 정확한 하위 패키지는 implementer가 인접 파일 관례로 확정.

### Task 1. Slack 요청 서명 검증기 (X-Slack-Signature) + signing-secret 프로퍼티

**메타**.
- agent: `security-engineer`  # HMAC/crypto·인증 게이트
- files: [`SLK/security/SlackSignatureVerifier.kt`, `SLK/config/SlackProperties.kt`, `SLKT/security/SlackSignatureVerifierTest.kt`]
- depends-on: []

**RED**: `SlackSignatureVerifierTest` — 유효 서명 통과 / 위조 서명 거부 / timestamp 5분 초과 거부 / 헤더 누락 거부. 실패: `SlackSignatureVerifier` 없음.
**GREEN**: `v0=HMAC-SHA256(signingSecret, "v0:{ts}:{rawBody}")` 상수시간 비교(SDK `SlackSignature.Verifier` 래핑 또는 동등). `SlackProperties`에 `signingSecret` 추가(미설정 시 빈 등록 유지·검증 시점 예외, NFR3). timestamp 윈도우 검사.
**REFACTOR**: 윈도우/헤더명 상수화 + KDoc(왜 상수시간·왜 부팅 안전).
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackSignatureVerifierTest'`

### Task 2. Atlas 이슈 URL 파서

**메타**.
- agent: `backend-engineer`
- files: [`SLK/unfurl/AtlasIssueUrlParser.kt`, `SLKT/unfurl/AtlasIssueUrlParserTest.kt`]
- depends-on: []

**RED**: `AtlasIssueUrlParserTest` — `{base}/issues/PROJ-123` → `PROJ-123`, trailing slash/쿼리/fragment 허용, 다른 도메인·`/issues/` 아님 → null, 다중 URL 리스트 파싱. 실패: 파서 없음.
**GREEN**: base-url(`bts.atlas.base-url`) 접두 매칭 + 이슈키 정규식 추출. 리스트 입력 → 매칭된 (url, issueKey) 쌍만 반환.
**REFACTOR**: 정규식 상수화 + KDoc.
**검증**: `./gradlew :modules:slack-integration:test --tests '*AtlasIssueUrlParserTest'`

### Task 3. V702 역방향 매핑 인덱스 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V702__slack_user_mapping_reverse_index.sql`]
- depends-on: []

**RED**: 마이그레이션 스키마 테스트(있으면)에 `(slack_user_id, team_id)` 인덱스 존재 단언 추가. 실패: 인덱스 부재.
**GREEN**: `CREATE INDEX idx_user_slack_mapping_slack_user ON user_slack_mapping(slack_user_id, team_id);`. V700/V701 다음 번호 확인(머지 직전 재확인, `migration-vnumber-concurrent-branch-collision`).
**REFACTOR**: 주석(역방향 조회 용도 — V701 주석의 "역방향 미예상" 정정).
**검증**: `./gradlew :modules:slack-integration:test --tests '*SchemaMigration*'` 또는 flyway validate.

### Task 4. 역방향 매핑 조회 메서드 (slack_user_id → user_id)

**메타**.
- agent: `backend-engineer`
- files: [`SLK/application/SlackUserMappingRepository.kt`, `SLK/persistence/JdbcSlackUserMappingRepository.kt`, `SLKT/persistence/JdbcSlackUserMappingRepositoryReverseTest.kt`]
- depends-on: [3]   # 인덱스 마이그레이션 후 Testcontainers 부팅

**RED**: `findUserIdBySlackUserId(slackUserId, teamId)` 통합 테스트(Testcontainers) — 매핑 존재 시 user_id, team_id 불일치·미존재 시 null. 실패: 메서드 없음.
**GREEN**: 인터페이스 + JdbcTemplate `SELECT user_id WHERE slack_user_id=? AND team_id=?`(DATA.md §5 `?` 바인딩).
**REFACTOR**: SQL 상수화.
**검증**: `./gradlew :modules:slack-integration:test --tests '*ReverseTest'`

### Task 5. IssueUnfurlPort + IssueUnfurlView (shared-kernel)

**메타**.
- agent: `backend-engineer`
- files: [`SHK/issue/IssueUnfurlPort.kt`]
- depends-on: []

**RED**: (인터페이스 전용, 컨슈머 컴파일로 검증) — 별도 단위테스트 없이 T6/T10이 계약 검증. 최소 계약 KDoc 명시.
**GREEN**: `interface IssueUnfurlPort { fun getVisibleIssueCard(issueKey: String, viewerUserId: UUID): IssueUnfurlView? }` + `data class IssueUnfurlView(issueKey, summary, statusLabel, priorityLabel, assigneeDisplayName?)`. fail-closed 계약 KDoc(볼 수 없으면 null).
**REFACTOR**: 패키지 위치를 기존 `IssueVisibilityPort` 인접으로 정렬(shared-kernel scan 회귀 방지, `shared-kernel-component-extraction-scan-regression`).
**검증**: `./gradlew :modules:shared-kernel:compileKotlin`

### Task 6. IssueUnfurlPort 어댑터 (issue-tracking) — 결합 fail-closed + 라벨 해석

**메타**.
- agent: `backend-engineer`  # 권한 판정 로직은 security-engineer 리뷰
- files: [`ISS/src/main/kotlin/com/bts/issuetracking/adapter/IssueUnfurlAdapter.kt`, `ISS/src/test/kotlin/com/bts/issuetracking/adapter/IssueUnfurlAdapterIntegrationTest.kt`]
- depends-on: [5]

**RED**: 통합 테스트(issue-tracking 스키마+5계층 시드, `no-project-creation-feature-issue-needs-5-layer-seed`) — 가시 이슈→View(라벨/담당자명 해석 확인), 무권한→null, 없는키→null. 실패: 어댑터 없음.
**GREEN**: 기존 가시성 로직(`BoardIssueLookupAdapter`의 `securityDirectory.accessibleLevels`+`existsVisibleIssue` 패턴) 재사용해 fail-closed. issueKey prefix로 프로젝트 도출. 상태키→라벨·우선순위Int→라벨·담당자 UUID→표시명(`UserLookupPort.findDisplayNamesByIds`) 해석해 View 반환.
**REFACTOR**: 라벨 매핑 추출 + KDoc(fail-closed 근거).
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueUnfurlAdapterIntegrationTest'`

### Task 7. chat.unfurl Slack API 클라이언트

**메타**.
- agent: `backend-engineer`
- files: [`SLK/message/SlackUnfurlClient.kt`, `SLKT/message/SlackUnfurlClientTest.kt`]
- depends-on: []

**RED**: `SlackUnfurlClientTest`(MethodsClient mock) — `unfurl(botToken, channel, ts, unfurls)` 호출 시 `chatUnfurl` 인자 매핑 확인, 실패 3분류(Sent/Retryable/Permanent). 실패: 클라이언트 없음.
**GREEN**: `SlackMessageClient` 패턴 복제 — `methods.chatUnfurl { req -> req.token(botToken).channel(ch).ts(ts).rawUnfurls(json) }`. 봇토큰 미노출.
**REFACTOR**: 결과 분류 공용화 검토(과제거 금지) + KDoc.
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackUnfurlClientTest'`

### Task 8. Unfurl 카드 Block Kit 렌더러

**메타**.
- agent: `backend-engineer`
- files: [`SLK/message/SlackBlockKitRenderer.kt`, `SLKT/message/SlackBlockKitRendererUnfurlTest.kt`]
- depends-on: []

**RED**: `renderUnfurlCard(IssueUnfurlView): ObjectNode`(url→blocks) — 키+제목 링크·상태·우선순위·담당자("미지정" 폴백) 블록 확인. **액션 버튼 없음** 단언. 실패: 메서드 없음.
**GREEN**: 기존 Jackson 블록 조립 확장. `IssueUnfurlView`(shared-kernel) 소비 → depends-on 없지만 T5 타입 필요 시 컴파일 순서는 wave가 처리.
**REFACTOR**: 블록 빌더 헬퍼 추출.
**검증**: `./gradlew :modules:slack-integration:test --tests '*RendererUnfurlTest'`

> 주. T8은 `IssueUnfurlView`(T5 산출) 타입을 import → 실제 depends-on: [5]. (표기 보정)

### Task 9. @Async 경계 executor + @EnableAsync 배선

**메타**.
- agent: `backend-engineer`
- files: [`SLK/config/SlackAsyncConfig.kt`, `SLKT/config/SlackAsyncConfigTest.kt`]
- depends-on: []

**RED**: `SlackAsyncConfigTest` — executor 빈 존재 + 경계(core/max/queue) 값 확인. 실패: 빈 없음.
**GREEN**: `@Configuration @EnableAsync` + `ThreadPoolTaskExecutor`(bounded) 빈("slackUnfurlExecutor"). `SimpleAsyncTaskExecutor` 금지(NFR6).
**REFACTOR**: 파라미터 프로퍼티화 + KDoc(무한 스레드 방지 이유).
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackAsyncConfigTest'`

### Task 10. SlackUnfurlService — 오케스트레이션 (@Async)

**메타**.
- agent: `backend-engineer`  # 권한 흐름 security-engineer 리뷰
- files: [`SLK/unfurl/SlackUnfurlService.kt`, `SLKT/unfurl/SlackUnfurlServiceTest.kt`]
- depends-on: [2, 4, 5, 7, 8, 9]

**RED**: `SlackUnfurlServiceTest`(협력자 mock/stub) — link_shared payload → 파서·역매핑·`getVisibleIssueCard`·렌더·`chat.unfurl` 호출 순서. fail-closed: 미매핑(S2)·무권한(S3)·없는키(S8) → chat.unfurl 미호출. 다중링크(S9) → 볼 수 있는 것만 unfurls. 실패: 서비스 없음.
**GREEN**: `@Async("slackUnfurlExecutor") fun handleLinkShared(payload)` — 파싱→역매핑(null skip)→port(null skip)→render→unfurl. 봇토큰 `SlackBotTokenResolver.resolve(teamId)`(EC1 미설치 skip).
**REFACTOR**: 링크별 처리 추출 + KDoc.
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackUnfurlServiceTest'`

### Task 11. SlackEventsController — POST /slack/events

**메타**.
- agent: `backend-engineer`  # 서명 게이트·permitAll은 security-engineer 리뷰
- files: [`SLK/web/SlackEventsController.kt`, `SLK/web/dto/SlackEventPayloads.kt`, `SLKT/web/SlackEventsControllerTest.kt`]
- depends-on: [1, 10]

**RED**: 컨트롤러 슬라이스/통합 테스트 — url_verification→challenge 반환, 잘못된 서명→401(S5), 헤더 누락→401, event_callback link_shared→200 즉시 ack + 서비스 위임 검증, 그외 타입→200 무시. 실패: 컨트롤러 없음.
**GREEN**: `@RequestBody String rawBody`로 원문 확보(EC8) → `SlackSignatureVerifier` 검증 → 타입 분기(challenge 동기 / event_callback은 파싱 후 `unfurlService.handleLinkShared` 위임하고 즉시 200).
**REFACTOR**: 페이로드 DTO 방어적 파싱 + KDoc.
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackEventsControllerTest'`

### Task 12. test-boot 배선(StubIssueUnfurlPort·SecurityConfig permitAll) + 가짜 이벤트 E2E

**메타**.
- agent: `qa-engineer`
- files: [`SLKT/config/SlackTestSecurityConfig.kt`, `SLKT/support/StubIssueUnfurlPort.kt`, `SLKT/unfurl/SlackUnfurlEndToEndTest.kt`]
- depends-on: [5, 11]

**RED**: 가짜 Slack `link_shared` JSON을 `POST /slack/events`로(유효 서명) → chat.unfurl(mock) 호출 검증(happy). fail-closed 시나리오(미매핑/무권한) → 미호출. url_verification 왕복. 실패: 배선/엔드포인트 permitAll 부재.
**GREEN**: `StubIssueUnfurlPort`(가시/불가시 시드 가능, `StubUserLookupPort` 패턴) test `@TestConfiguration` 배선. `SlackTestSecurityConfig`에 `POST /slack/events` permitAll + CSRF-ignore(`new-crossbc-dep-openapi-mockbean-regression` 방지). @Async는 테스트서 동기 실행(NFR6).
**REFACTOR**: 이벤트 fixture 빌더 추출.
**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackUnfurlEndToEndTest'`

## Plan 메타

- task 수: 12
- 예상 시간: 직렬 약 40분, wave 병렬 적용 시 약 20분(예상 wave 3~4)
- TDD 강제: yes (RED→GREEN→REFACTOR)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산. **단, slack-integration 동일 모듈 task(T1·T2·T4·T7·T8·T9·T10·T11·T12)는 Gradle 모듈 컴파일/worktree git race 회피 위해 controller가 직렬 dispatch**(`bts-plan-wave-gradle-module-compile`·FR-API-04 선례). 별도 모듈(T3 마이그레이션 리소스·T5 shared-kernel·T6 issue-tracking)은 slack task와 병렬 가능.
- 권한/보안 리뷰: T1(서명)·T6(fail-closed 판정)·T10(권한 흐름)·T11(permitAll)은 security-engineer 리뷰 대상.
- 추가 검증: `./gradlew :modules:slack-integration:test :modules:shared-kernel:test :modules:issue-tracking:test`, ktlint/detekt.

## 리뷰 결과 (← /bts-review-plan 채움)
