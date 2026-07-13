<!-- FR-SL-06 채널↔프로젝트 매핑 아키텍처 결정 (라우팅 팬아웃·권한·projectKey·하드삭제·PR 분할·보안 게이트) -->

# ADR: FR-SL-06 — Slack 채널↔프로젝트 매핑 (라우팅 팬아웃 · 권한 · 하드삭제 · PR 분할)

- 상태. 수용(Accepted)
- 날짜. 2026-07-13
- 범위. slack-integration BC (+ notification producer는 PR-B)
- 관련. FR-SL-06. 선행 [2026-07-10-fr-sl-02-slack-notification-delivery](2026-07-10-fr-sl-02-slack-notification-delivery.md)(큐 경계 패턴), [2026-07-07-fr-sl-01-slack-bot-app](2026-07-07-fr-sl-01-slack-bot-app.md)(BC 신설). PR #264(PR-A).

## 맥락

FR-SL-06은 "프로젝트 활동 피드를 Slack 채널로 브로드캐스트"한다(SDD 09 §9.1.1). 현행 Slack 경로(FR-SL-02)는 **수신자 단위 DM**(`q_slack_deliveries`)이라 프로젝트 축·채널 게시가 없다. 채널 게시는 **이벤트당 1회(수신자 무관)** 로 granularity가 다르다. 핵심 설계 질문 — 채널 라우팅을 어디서 하고, 매핑 CRUD 권한/데이터 모델/삭제 정책을 어떻게 정하는가.

## 결정

### D1. 라우팅 팬아웃 = notification 브로드캐스트 (신규 큐, PR-B)
프로젝트 정보를 담은 `q_issue_events`는 notification 전용 **경쟁 소비 큐**라 slack이 두 번째 consumer로 붙을 수 없다(companion KDoc 명시). notification `NotificationWorker.dispatch()`가 projectKey가 있는 이벤트마다 **새 큐 `q_slack_channel_broadcasts`**로 이벤트당 1회 발행하고, slack 새 워커가 소비→매핑 조회→event_filter→채널당 1회 `chat.postMessage`. FR-SL-02 `SlackChannelSender` JSON 큐 경계 패턴 재사용(도메인 타입 import 0). **채널 라우팅은 NotificationPolicy(사용자 정책)와 독립** — event_filter가 유일 통제.

### D2. CRUD 권한 = PROJECT_ADMIN 직접 확인 (신규 권한 코드 없음)
매핑 CRUD는 프로젝트 관리자 게이트. 신규 cross-BC 포트 `SlackChannelMappingPermissionResolver`(shared-kernel, Boolean fail-closed, `AutomationPermissionResolver` 미러). prod 어댑터(identity-access, @Profile prod)는 **`ProjectSecuritySchemeService.requireProjectAdmin` 선례를 따라 멤버십 role == PROJECT_ADMIN 직접 확인**한다. 이유 — 채널 매핑 관리에 대응하는 권한 코드가 시드에 없고, 신규 권한 코드는 `SchemaMigrationTest` 시드 카운트 가드를 깨는 blast radius가 있다. role 직접 조회는 데이터 소유자(identity-access) 내부라 정당(cross-BC 소비자 slack은 포트 계약만 사용). non-prod은 consumer 소유 스텁(@Profile !prod, allow-all).

### D3. 매핑 키 = projectKey(String) — spec `project_id` deviation
product 명세 D3의 `slack_channel_project_map(channel_id, project_id, event_filter)`에서 `project_id`(UUID) 대신 **`project_key`(String)** 로 스코프한다. 라우팅 시점(PR-B 워커)의 projectKey→UUID cross-BC 조회를 없애기 위함(automation_rules.project_key·`AutomationPermissionResolver` 동형). team_id·project_key·channel_id는 cross-BC 참조라 FK 없음(BC 격리).

### D4. 하드 삭제 (소프트 삭제 없음) — DATA.md §1.2 예외
`slack_channel_project_map`은 `deleted_at` 없이 DELETE API가 **물리 삭제**한다. DATA.md §1.2("DELETE는 소프트 삭제 우선, 하드 삭제는 ADR 필수")의 명시 예외다. 근거 — 이 테이블은 **설정성 행**(audit/log 아님)으로, `saved_filters`(2026-06-26)·`favorites`(2026-06-24)·`dashboard_share_tokens`(2026-07-02)·`user_keymap`(2026-07-08)과 동일 성격이다. 이슈 키 같은 외부 영구 인용이 없고, 복구 가치가 낮으며 재설정으로 대체된다. 스펙 S8 — 삭제 후 이후 게시만 중단, 과거 게시는 소급 삭제 없음(Maxi 확정, 게이트 1 스펙 승인).

### D5. 보안등급 이슈 채널 게시 제외 (fail-closed, PR-B)
채널 피드는 뷰어별 이슈 권한을 우회하므로, **보안등급이 걸린 이슈는 채널 게시에서 제외**한다(Maxi 확정). PR-B 워커가 신규 cross-BC 포트 `IssueSecurityClassificationPort.isSecurityRestricted(issueKey)`로 issueKey마다 확인해 제한/판정불명이면 skip(제목·키 유출 차단). prod=issue-tracking(@Profile prod, `security_level_id` non-null→제한). issueKey 없는 이벤트는 게이트 우회(이슈-스코프 아님).

### D6. PR 분할 = PR-A(설정) / PR-B(라우팅)
prod 조립(`:modules:app`, #259)이 새 cross-BC 포트 소비의 prod 어댑터를 같은 PR에 요구(NoSuchBean 부팅 차단)하므로, 각 PR이 포트+어댑터를 함께 실어 독립 prod 부팅한다(FR-SL-05 PR1/PR2 관례).
- **PR-A(#264, 이 ADR)**. 매핑 CRUD + 권한 포트 + identity-access 어댑터 + non-prod 스텁 + V704.
- **PR-B(후속)**. notification 브로드캐스터 + slack 채널 워커 + `IssueSecurityClassificationPort` + issue-tracking 어댑터.
- **후속 PR**. D6 UI(프로젝트 설정 → Slack 채널) · D7 E2E.

## 대안 (기각)

- **producer 이중 발행**(issue-tracking/agile/automation이 slack 전용 큐에도 발행). 여러 BC 폭발 반경·BC 격리 약화. 기각.
- **`q_slack_deliveries` 재사용**(FR-SL-02 ADR가 예견). per-recipient DM granularity와 per-event 채널 브로드캐스트 불일치 → 별도 큐. 기각.
- **신규 MANAGE_SLACK_CHANNEL 권한 코드**. 시드 카운트 가드 blast radius. PROJECT_ADMIN 직접 확인으로 회피(D2).

## 결과

- 매핑 설정은 PR-A로 완결(관리자 CRUD, prod 부팅). 실제 채널 게시는 PR-B부터.
- `init_codegen.sql` 미러 없음 — slack은 JdbcTemplate 모듈(jOOQ codegen 부재)이라 미러 대상 없음(jOOQ 모듈 전용). plan Task 1의 "init_codegen 미러" 지시는 부정확했고 구현이 올바르게 정정.
- 하드닝(코드리뷰 후, Maxi 지시) — update/delete 권한 거부를 403 대신 404로 수렴(존재 비노출), malformed 입력을 500 대신 400으로.
- FR-SL-06는 PR-B·D6·D7 완료 시 slack-integration BC 6/6 완료.
