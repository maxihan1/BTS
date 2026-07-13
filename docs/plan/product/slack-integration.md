<!-- slack-integration BC — Bot/알림/Unfurl/Slash/Interactive/매핑 6 FR -->

# slack-integration BC

**소속 FR**. 6개 (SL 6).
**책임**. Slack App + Bot Token + 알림 발송 + Unfurl + Slash 명령어 + 인터랙티브 + 채널 매핑.
**SDD 참조**. 09장 (알림/Slack).
**다른 BC와의 경계**. notification-dashboard BC가 발행하는 알림 이벤트를 Slack 채널로 변환. issue-tracking BC의 URL을 Unfurl. **Slack API 호출만, 다른 BC import 금지**.

## §0 진입 조건

- [ ] identity-access §2.9 (PAT) — Slack 인증과 매핑
- [ ] notification-dashboard §2.2 (FR-NT-02 채널) 완료
- [ ] issue-tracking §2~§6 (URL 구조 안정) 완료
- [ ] Slack 워크스페이스 + App 생성 + 권한 부여 (사전 수동 작업)

## §1 기술 검증

이 BC 자체의 PoC는 없음. Slack Bolt for Java + 표준 라이브러리 활용.

## §2 기본 (FR-SL-01, FR-SL-02, FR-SL-06)

### §2.1 FR-SL-01 — Slack App + Bot Token 방식

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `slack/bot-app`

- [x] D1. 도메인 — SlackInstall (책임. security-engineer) *[deviation. SlackWorkspace+BotInstall → 단일 SlackInstall VO]*
- [x] D2. 명세 — OAuth 2.0 설치 흐름 + 서명 state + Token 보관 (책임. security-engineer)
- [x] D3. 데이터 모델 — `slack_installs(team_id UNIQUE, bot_token_encrypted, ...)` (책임. db-engineer) *[deviation. workspace_id → team_id. JdbcTemplate]*
- [x] D4. 백엔드 — slack-api-client(client 층) + 설치 콜백 (`/slack/install/callback`) (책임. security-engineer) *[deviation. Bolt 프레임워크 → slack-api-client, ADR D2]*
- [x] D5. 백엔드 테스트 (책임. security-engineer) *[69 tests, PR #244]*
- [x] D6. 프론트 UI — 관리자 "Slack 연결" 페이지 `/admin/slack` (책임. frontend-engineer) *[PR #247. 상태 카드+연결/다시연결+결과 배너. Bearer 제약으로 view-layer JSON 엔드포인트 2종(`/api/v1/slack/installation`·`/install-url`) 신설. 라우트 `/settings/slack`→`/admin/slack`(게이트 결정)]*
- [x] D7. E2E (책임. qa-engineer) *[PR #247. MSW 5 시나리오]*

### §2.2 FR-SL-02 — 알림 발송 (DM + 채널)

**우선순위**. 필수 | **선행**. §2.1, notification-dashboard §2.2 | **Plan slug**. `slack/notifications`

- [x] D1. 도메인 — SlackNotificationChannel (책임. backend-engineer) *[PR #252. deviation. 비동기 pgmq 큐(`q_slack_deliveries`) — notification `SlackChannelSender`(producer) + slack `SlackDeliveryWorker`(consumer), BC 격리 JSON 경계. issue-tracking `IssueAssigned` 이벤트 신설로 할당 알림 파이프라인 수리(ADR)]*
- [x] D2. 명세 — DM vs 채널 라우팅. 메시지 포맷 (Block Kit) (책임. backend-engineer) *[PR #252. DM 중심(채널 라우팅은 FR-SL-06). `SlackBlockKitRenderer`(제목+이슈링크 mrkdwn). spec/ADR]*
- [x] D3. 데이터 모델 — `user_slack_mapping(user_id, slack_user_id)` (책임. db-engineer) *[PR #252. V701. `user_slack_mapping` + `slack_delivery_log`(dedup, effectively-once). JdbcTemplate]*
- [x] D4. 백엔드 — Channel 추상 구현 (Slack). Bolt `chat.postMessage` (책임. backend-engineer) *[PR #252. deviation. Bolt→slack-api-client `chat.postMessage`(FR-SL-01 관례). pgmq consumer 워커(vt/재시도/dead-letter), 봇토큰 3중 미노출, exists→send→record dedup 순서]*
- [x] D5. 백엔드 테스트 — Mockito + slack-api mock (책임. backend-engineer) *[PR #252. mockk. SlackMessageClientTest·SlackBlockKitRendererTest·SlackUserMappingServiceIntegrationTest·SlackDeliveryWorkerIntegrationTest(9, dead-letter/poison 포함). Testcontainers pgmq 이미지]*
- [x] D6. 프론트 UI — 사용자 ↔ Slack 계정 매핑 페이지 (책임. designer → frontend-engineer) *[deviation. 연결 UX=C 이메일 자동해석(`users.lookupByEmail`). me-scope 엔드포인트 3종(GET/POST/DELETE `/api/v1/slack/me/connection`). 봇 스코프 `users:read.email` 추가(기존 설치 재연결 필요). PAT는 `@AuthenticationPrincipal Jwt?` 타입 기반 401. 매핑 영속화·서비스(link/unlink `SlackUserMappingService`, PR #252)를 재사용. 신규 마이그레이션 0. PR(이번, 미정). ADR: [2026-07-10-fr-sl-02-d6-slack-user-connection.md](../../decisions/2026-07-10-fr-sl-02-d6-slack-user-connection.md)]*
- [x] D7. E2E (책임. qa-engineer) *[MSW 기반, happy/해제/오류 시나리오. PR(이번, 미정)]*

### §2.3 FR-SL-06 — 채널 ↔ 프로젝트 매핑

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `slack/channel-mapping`

> **PR 분할** (ADR [2026-07-13-fr-sl-06-channel-mapping](../../decisions/2026-07-13-fr-sl-06-channel-mapping.md)). PR-A=설정/CRUD(#264) · PR-B=라우팅(브로드캐스터+채널 워커+보안 게이트) · 후속=D6 UI/D7 E2E. prod 조립이 포트+어댑터 동반을 요구해 분할.

- [x] D1. 도메인 — ChannelProjectMapping (책임. backend-engineer) *[PR #264 PR-A. 불변 VO + `SlackChannelEventType` wire 미러(notification enum import 0)]*
- [x] D2. 명세 — 다대다 + 이벤트 종류별 필터 (책임. backend-engineer) *[PR #264 PR-A. 스펙 D1~D5 + PR-A/PR-B 분할·보안등급 이슈 제외(PR-B)]*
- [x] D3. 데이터 모델 — `slack_channel_project_map(channel_id, project_id, event_filter)` (책임. db-engineer) *[PR #264 PR-A. V704. deviation. `project_id`→`project_key`(라우팅 시점 cross-BC 조회 회피), event_filter=`event_types text[]`. JdbcTemplate(init_codegen 없음)]*
- [ ] D4. 백엔드 — CRUD + 알림 라우팅에서 매핑 조회 (책임. backend-engineer) *[PR #264 PR-A: CRUD(`/api/v1/slack/channel-mappings`)+PROJECT_ADMIN 게이트 완료. 라우팅 매핑 조회=PR-B]*
- [ ] D5. 백엔드 테스트 (책임. backend-engineer) *[PR #264 PR-A: CRUD 단위/통합 완료. 라우팅 테스트=PR-B]*
- [ ] D6. 프론트 UI — 프로젝트 설정 → Slack 채널 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 양방향 (FR-SL-03, FR-SL-04, FR-SL-05)

### §3.1 FR-SL-03 — Unfurl (Atlas URL 자동 카드)

**우선순위**. 높음 | **선행**. §2.1, issue-tracking §2.1.1 | **Plan slug**. `slack/unfurl`

- [x] D1. 도메인 — UnfurlPayload (책임. backend-engineer) *[PR #257. LinkSharedCommand(입력 VO) + shared-kernel `IssueUnfurlPort`/`IssueUnfurlView`(결합 fail-closed 포트, ADR D1)]*
- [x] D2. 명세 — `link_shared` 이벤트 처리 (책임. backend-engineer) *[PR #257. `POST /slack/events`(url_verification+event_callback), X-Slack-Signature 검증 선행. spec/ADR]*
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer) *[deviation. 활용이 아닌 V702 **UNIQUE** 역방향 인덱스 `(slack_user_id, team_id)` 신설 — 잘못된 viewer 과다노출 fail-open 스키마 차단]*
- [x] D4. 백엔드 — event handler + Block Kit 카드 생성 (책임. backend-engineer) *[deviation. Bolt 미도입→자체 `SlackEventsController`+`SlackUnfurlService`(@Async ack200). 서명검증 직접구현(SDK Verifier 부재). cross-BC `IssueUnfurlAdapter`(BROWSE 멤버십+보안등급 이중 fail-closed 게이트). `SlackUnfurlClient` chat.unfurl. PR #257]*
- [x] D5. 백엔드 테스트 (책임. backend-engineer) *[PR #257. 서명/파서/역매핑/어댑터/렌더/오케스트레이션/컨트롤러 단위·통합, 3모듈 green]*
- [x] D6. 프론트 UI — (해당 없음) (책임. -) *[UI 없음. classify ui/frontend 오판을 명세 근거로 backend 교정]*
- [x] D7. E2E — 가짜 Slack 이벤트 (책임. qa-engineer) *[PR #257. `SlackUnfurlEndToEndTest` happy/미매핑/무권한/challenge, 유효 서명 생성·chat.unfurl mock]*

### §3.2 FR-SL-04 — Slash 명령어 (`/atlas ...`)

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `slack/slash-command`

- [x] D1. 도메인 — SlashCommand (책임. backend-engineer) *[PR #258. sealed SlashCommand(Help/View/Search/Create/UsageError) + 신규 cross-BC `SlashIssueSearchPort`(shared-kernel, search BC가 AQL 파싱 소유). deviation. Bolt 미도입→자체 `SlackCommandsController`(FR-SL-03 관례)]*
- [x] D2. 명세 — `/atlas search <aql>`, `/atlas create <title>` 등 (책임. backend-engineer) *[PR #258. 4 서브커맨드 help/view/search/create. 인라인 프로젝트 인자 필수. ephemeral 응답. spec/ADR]*
- [x] D3. 데이터 모델 — (활용. Slack 사용자 → BTS 사용자 매핑) (책임. db-engineer) *[PR #258. 신규 마이그레이션 0 — `user_slack_mapping`(V701)·역매핑(V702) 재사용]*
- [x] D4. 백엔드 — command handler. 권한 가드 (책임. backend-engineer + security-engineer) *[PR #258. deviation. Bolt→자체 컨트롤러. `POST /slack/commands` 서명검증 재사용(form-urlencoded raw body)·즉시 ack+@Async→response_url. 권한은 전부 포트 위임(fail-closed): view=IssueUnfurlPort·search=SlashIssueSearchPort·create=IssueImportPort. 미매핑→친절 안내]*
- [x] D5. 백엔드 테스트 (책임. backend-engineer) *[PR #258. 9 TDD 태스크. slack 296 tests·shared-kernel·search-export-import green]*
- [x] D6. 프론트 UI — (해당 없음) (책임. -) *[UI 없음(Slack 내 명령)]*
- [x] D7. E2E (책임. qa-engineer) *[PR #258. `SlackSlashCommandEndToEndTest` 9 시나리오(실 서명·4 happy·401·미매핑·문법오류·무권한·결과0)]*

### §3.3 FR-SL-05 — 인터랙티브 메시지 (버튼/메뉴)

**우선순위**. 높음 | **선행**. §3.2 | **Plan slug**. `slack/interactive`

- [x] D1. 도메인 — InteractiveAction (책임. backend-engineer) *[PR #261, PR1/2 분할. InteractiveAction(block_actions 버튼·view_submission 모달) 도메인 확정. cross-BC 재사용 IssueTransitionPort + 신규 결합조회 IssueCompletionOptionsPort]*
- [x] D2. 명세 — 상태 전이, 담당자 변경 등 (책임. backend-engineer) *[PR #261, PR1/2 분할. 스펙 전체(F1~F13·S1~S7). PR1=완료 전이(resolution 모달)+상세보기+인바운드 인프라, PR2=담당자 변경(users_select)+코멘트(modal)]*
- [x] D3. 데이터 모델 — (활용→신규) (책임. db-engineer) *[PR #261. deviation. plan은 "활용"이었으나 완료 감사에 V703 slack_interaction_log 신설(Maxi 결정, append-only). DATA.md §4.1 V700~V703]*
- [x] D4. 백엔드 — block_actions handler + 권한 가드 + 응답 갱신 (책임. backend-engineer + security-engineer) *[PR1 #261 + PR2 #263. 인바운드 POST /slack/interactions(서명검증·크기상한·permitAll)·완료 전이·상세보기·V703 감사·타입 예외 분류(PR1). 담당자 변경(users_select 모달→cross-BC IssueMutationPort.assign)·코멘트(plain_text_input 모달→addComment)·ASSIGN/COMMENT 감사(PR2, 마이그레이션 0·V703 재사용·권한 전량 포트 위임). deviation. Bolt 미도입→자체 컨트롤러. 세 액션 버튼→모달→view_submission 대칭·공통 게이트 헬퍼]*
- [x] D5. 백엔드 테스트 (책임. backend-engineer) *[PR1 #261 완료 flow. PR2 #263 담당자/코멘트 단위(SlackInteractionServiceTest 31)·모달빌더·렌더러·StubIssueMutationPort. slack 378 tests green]*
- [x] D6. 프론트 UI — (해당 없음) (책임. -) *[UI 없음 — Slack 내 인터랙션(FR-SL-03/04 동형)]*
- [x] D7. E2E (책임. qa-engineer) *[PR1 #261 완료 왕복. PR2 #263 SlackInteractionEndToEndTest 담당자/코멘트 7 시나리오(버튼→모달·성공·대상미연결·무권한·actor미연결). 13 E2E green]*

## §NFR slack-integration BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| Slack 알림 발송 지연 | 3s | ___ | k6 + Slack mock |
| Unfurl 응답 (3초 제한 — Slack 정책) | 3s | ___ | Bolt handler |
| Slash 명령어 응답 | 3s | ___ | Bolt handler |
| Interactive 응답 | 3s | ___ | Bolt handler |
| Bot Token 암호화 (KMS or AES-256) | 적용 | AES-256-GCM (SecretEncryptor, FR-SL-01) | DEVELOPMENT.md §1.1. KMS는 v0.4+ |
| 권한 위반 액션 차단율 | 100% | ___ | 보안 가드 |

### BC 완료 조건

- [ ] §2~§3 (6 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] Slack 정책 변경 대응 가이드 1줄 (CHANGELOG.md)
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "slack-integration BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "slack-integration BC 완료"
