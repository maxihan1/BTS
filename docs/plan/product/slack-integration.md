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

- [ ] D1. 도메인 — SlackNotificationChannel (책임. backend-engineer)
- [ ] D2. 명세 — DM vs 채널 라우팅. 메시지 포맷 (Block Kit) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_slack_mapping(user_id, slack_user_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — Channel 추상 구현 (Slack). Bolt `chat.postMessage` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — Mockito + slack-api mock (책임. backend-engineer)
- [ ] D6. 프론트 UI — 사용자 ↔ Slack 계정 매핑 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.3 FR-SL-06 — 채널 ↔ 프로젝트 매핑

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `slack/channel-mapping`

- [ ] D1. 도메인 — ChannelProjectMapping (책임. backend-engineer)
- [ ] D2. 명세 — 다대다 + 이벤트 종류별 필터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `slack_channel_project_map(channel_id, project_id, event_filter)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD + 알림 라우팅에서 매핑 조회 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 프로젝트 설정 → Slack 채널 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 양방향 (FR-SL-03, FR-SL-04, FR-SL-05)

### §3.1 FR-SL-03 — Unfurl (Atlas URL 자동 카드)

**우선순위**. 높음 | **선행**. §2.1, issue-tracking §2.1.1 | **Plan slug**. `slack/unfurl`

- [ ] D1. 도메인 — UnfurlPayload (책임. backend-engineer)
- [ ] D2. 명세 — `link_shared` 이벤트 처리 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — Bolt event handler + Block Kit 카드 생성 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (해당 없음) (책임. -)
- [ ] D7. E2E — 가짜 Slack 이벤트 (책임. qa-engineer)

### §3.2 FR-SL-04 — Slash 명령어 (`/atlas ...`)

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `slack/slash-command`

- [ ] D1. 도메인 — SlashCommand (책임. backend-engineer)
- [ ] D2. 명세 — `/atlas search <aql>`, `/atlas create <title>` 등 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용. Slack 사용자 → BTS 사용자 매핑) (책임. db-engineer)
- [ ] D4. 백엔드 — Bolt command handler. 권한 가드 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (해당 없음) (책임. -)
- [ ] D7. E2E (책임. qa-engineer)

### §3.3 FR-SL-05 — 인터랙티브 메시지 (버튼/메뉴)

**우선순위**. 높음 | **선행**. §3.2 | **Plan slug**. `slack/interactive`

- [ ] D1. 도메인 — InteractiveAction (책임. backend-engineer)
- [ ] D2. 명세 — 상태 전이, 담당자 변경 등 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — Bolt block_actions handler + 권한 가드 + 응답 갱신 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (해당 없음) (책임. -)
- [ ] D7. E2E (책임. qa-engineer)

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
