# FR-SL-03 Slack Unfurl (Atlas URL 자동 카드) — 스펙

> slug: fr-sl-03-slack-unfurl · type: feature · agent: backend-engineer
> ADR: [2026-07-11-fr-sl-03-slack-unfurl.md](../decisions/2026-07-11-fr-sl-03-slack-unfurl.md)
> BC: slack-integration · 선행: FR-SL-01(설치·봇토큰), FR-SL-02(user_slack_mapping·SDK 패턴)

## 개요

Slack 대화에 붙은 Atlas 이슈 URL을 Slack `link_shared` 이벤트로 수신 → 링크 공유자의 열람 권한 확인 →
이슈 스냅샷(키·제목·상태·우선순위·담당자)을 Block Kit 카드로 반환해 링크를 카드로 펼친다(unfurl).
slack-integration BC 최초의 **인바운드 Events API** 구현.

## 사용자 시나리오 (Given-When-Then)

- **S1 (happy)**. Given 사용자 A가 본인 Slack 계정을 연결(user_slack_mapping 존재)했고 이슈 PROJ-123 열람 권한이 있음.
  When A가 채널에 `https://atlas.company.com/issues/PROJ-123`를 붙임.
  Then Slack이 `link_shared`를 `POST /slack/events`로 전송 → 서명 검증 통과 → 200 즉시 ack →
  @Async로 공유자 역매핑·권한확인·`chat.unfurl` 호출 → Slack에 카드(키+제목·상태·우선순위·담당자) 표시.

- **S2 (미매핑)**. Given 사용자 B가 Slack 계정 미연결(역매핑 null). When B가 이슈 URL을 붙임.
  Then 200 ack는 정상, 그러나 viewer를 특정할 수 없어 `getVisibleIssueCard` 미호출/카드 없음 → **unfurl 안 함**(silent).

- **S3 (무권한)**. Given 사용자 C는 매핑됐으나 PROJ-123(보안등급/프로젝트 권한)을 볼 수 없음.
  When C가 URL을 붙임. Then `getVisibleIssueCard(PROJ-123, C)` → null → **unfurl 안 함**(silent, 정보 누출 0).

- **S4 (url_verification)**. Given Slack 이벤트 구독을 처음 설정. When Slack이 `{type:"url_verification", challenge:"..."}` POST.
  Then 서명 검증 후 `challenge` 값을 즉시 동기 반환(200, `{"challenge":"..."}` 또는 text/plain).

- **S5 (잘못된 서명)**. Given 위조/누락된 `X-Slack-Signature`. When `POST /slack/events` 도착.
  Then **401 거부**, 이벤트 처리 안 함.

- **S6 (재전송/만료)**. Given `X-Slack-Request-Timestamp`가 현재보다 5분 초과 이탈. When 도착.
  Then 재전송 공격으로 간주 **401 거부**.

- **S7 (비-Atlas / 비-이슈 URL)**. Given 링크가 Atlas 이슈 URL 패턴이 아님(다른 도메인, `/issues/` 아님).
  When link_shared 도착. Then 해당 링크는 스킵, 카드 없음(정상 200 ack). *(원칙적으로 Slack App unfurl 도메인 등록으로 걸러지나 방어적으로 재검증.)*

- **S8 (없는 이슈 키)**. Given URL의 이슈 키가 존재하지 않음(오타/삭제). When 처리. Then `getVisibleIssueCard` null → unfurl 안 함.

- **S9 (다중 링크)**. Given 한 메시지에 Atlas 이슈 URL 여러 개(일부는 볼 수 있고 일부는 없음).
  When link_shared의 `links[]`가 여러 개. Then **볼 수 있는 것만** `unfurls` 맵에 담아 한 번의 `chat.unfurl` 호출.

## 기능 요구사항 (FR)

- **FR1**. `POST /slack/events` 엔드포인트. `url_verification`(챌린지)와 `event_callback`(`link_shared`) 두 타입 처리. 그 외 타입은 200 ack 후 무시.
- **FR2**. 요청 서명 검증. `v0=HMAC-SHA256(signing_secret, "v0:{X-Slack-Request-Timestamp}:{raw_body}")`를 `X-Slack-Signature`와 **상수시간 비교**. timestamp 5분 윈도우 밖이면 거부(재전송 방어). `X-Slack-Signature`/`X-Slack-Request-Timestamp` 헤더 **누락 시에도 401**(Brainstorming G3). SDK `SlackSignature.Verifier` 래핑 또는 동등 구현.
- **FR3**. Atlas 이슈 URL 파서. `{bts.atlas.base-url}/issues/{ISSUE_KEY}` 패턴에서 이슈 키 추출. base-url 불일치/패턴 불일치는 스킵. trailing slash·쿼리스트링·fragment 허용.
- **FR4**. 역방향 매핑. 이벤트의 공유자 `event.user`(slack_user_id) + top-level `team_id` → `findUserIdBySlackUserId(slackUserId, teamId): UUID?`. null이면 스킵.
- **FR5**. 결합 fail-closed 조회. `IssueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId): IssueUnfurlView?`(shared-kernel 신규 포트 + issue-tracking 어댑터). 권한확인+상세조회 원자적, 볼 수 없으면 null.
- **FR6**. Unfurl 카드 렌더. `SlackBlockKitRenderer` 확장. 필드: `[키] 제목`(이슈 링크), 상태 라벨, 우선순위 라벨, 담당자 표시명(없으면 "미지정"). **액션 버튼 없음**(FR-SL-05).
- **FR7**. `chat.unfurl` 호출. `channel`(event.channel) + `ts`(event.message_ts) + `unfurls`(url→block kit) 맵. 봇 토큰은 `SlackBotTokenResolver.resolve(teamId)`.
- **FR8**. 3초 룰. 서명 검증 후 **즉시 200 ack**, 이슈 해석·`chat.unfurl`은 Spring `@Async`. url_verification 챌린지만 동기 반환.

## 비기능 요구사항 (NFR)

- **NFR1**. Unfurl ack 응답 ≤ 3s(Slack 정책). @Async 처리는 ack와 분리.
- **NFR2**. 봇 토큰(암호화/평문)·signing secret·slack_user_id를 로그·예외·응답에 미노출(FR-SL-01/02 3중 미노출 관례).
- **NFR3**. `BTS_SLACK_SIGNING_SECRET` 미설정이어도 애플리케이션 부팅 성공. 검증은 요청 처리 시점으로 미룸(`profile-scoped-bean-boot-failure` 회귀 방지). 미설정 상태에서 `/slack/events` 호출 시 명확히 거부(500 아님).
- **NFR4**. 서명 비교는 상수시간(타이밍 공격 방어, `SlackOAuthStateSigner` 선례).
- **NFR5**. fail-closed. 권한 판정 실패/예외 시 카드 미표시(unfurl 안 함)가 기본. 절대 fail-open 금지.
- **NFR6** (Brainstorming G1). `@Async` 실행기는 **경계 있는 `ThreadPoolTaskExecutor`**(core/max/queue 명시)로 정의. Spring 기본 `SimpleAsyncTaskExecutor`(스레드 무한 생성) 금지. `@EnableAsync`를 slack 모듈 config에 명시 배선(`@EnableScheduling` 관례와 동일, `module-first-scheduled-worker-detektmain-traps`). 테스트 결정성 — 통합 테스트는 async 메서드를 **직접(동기) 호출**하거나 test 프로파일에서 동기 executor로 대체해 chat.unfurl 호출을 확정적으로 검증.

## API 인터페이스 (REST)

| 메서드 | 경로 | 인증 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/slack/events` | 익명(permitAll) + X-Slack-Signature 검증 | Slack Events JSON (`url_verification` 또는 `event_callback`/`link_shared`) | url_verification: `{"challenge":"<값>"}` 200 · event_callback: 빈 200 즉시 ack · 서명 실패: 401 |

내부 포트(REST 아님):
```
// shared-kernel: com.bts.shared.issue (또는 기존 permission 패키지 인접)
interface IssueUnfurlPort {
    fun getVisibleIssueCard(issueKey: String, viewerUserId: UUID): IssueUnfurlView?
}
data class IssueUnfurlView(
    val issueKey: String,
    val summary: String,
    val statusLabel: String,
    val priorityLabel: String,
    val assigneeDisplayName: String?,   // null → 카드에 "미지정"
)
```

**라벨 해석 책임(Brainstorming G3)**. `IssueUnfurlView`는 이미 **사람이 읽는 라벨**로 반환된다. 즉 상태 키(`in_progress`)→상태 라벨, 우선순위 Int→라벨(Highest/High/…), 담당자 UUID→표시명 변환은 **issue-tracking 어댑터 내부**에서 수행한다(BoardIssueView의 원시 `currentStateKey/priority/assigneeId`를 slack BC로 넘기지 않음 — BC 경계에서 표현 계층 해석 완료). 담당자 표시명은 `UserLookupPort.findDisplayNamesByIds` 재사용. issueKey에서 프로젝트는 어댑터가 키 prefix로 내부 도출(slack은 projectKey 미보유).

## 데이터 모델 변경

- **V702** (`db/migration/slack-integration/`). 테이블 변경 없음. `user_slack_mapping(slack_user_id, team_id)` **인덱스 추가**(역방향 조회 성능). 예: `CREATE INDEX idx_user_slack_mapping_slack_user ON user_slack_mapping(slack_user_id, team_id);`
- `SlackUserMappingRepository.findUserIdBySlackUserId(slackUserId, teamId): UUID?` 신규 쿼리(`WHERE slack_user_id = ? AND team_id = ?`).

## 엣지 케이스

- **EC1**. 봇 미설치(`findCurrentInstallation` null) → 봇 토큰 없음 → chat.unfurl 불가 → 스킵(로그 warn).
- **EC2**. signing secret 미설정 → 부팅 통과(NFR3), 요청 시 검증 실패로 401.
- **EC3**. Slack 재전송(`X-Slack-Retry-Num` 존재) → @Async 재실행 가능하나 `chat.unfurl`이 동일 url을 덮어써 멱등(무해). 별도 dedup 불요.
- **EC4**. 이슈 키 리다이렉트(이동된 이슈, IssueKeyRedirect) → **이번 스코프는 현재 키만 해석**. redirect 추적은 ADR 후속(옛 키 URL은 unfurl 안 함).
- **EC5**. `chat.unfurl` API 실패(429/네트워크) → best-effort, 로그만. 재시도 없음(ADR D4). 링크는 평문 유지.
- **EC6**. `links[]` 다수 + 일부 무권한 → 볼 수 있는 것만 unfurls에 포함(S9).
- **EC7**. 이벤트 `team_id`가 저장된 설치와 불일치(다중 워크스페이스) → 단일 워크스페이스 전제라 `findCurrentInstallation` 기준. team_id 불일치 매핑은 조회 null → 스킵.
- **EC8**. raw body를 서명 검증 후 파싱 — 프레임워크가 body를 미리 소비해 서명 대상 raw string이 훼손되지 않도록 `@RequestBody String`(또는 HttpServletRequest raw)로 원문 확보.
- **EC9**. 상태/우선순위 라벨 미해석(read model에 라벨 없음) → 어댑터가 키/숫자 대신 표시 라벨을 반환하도록 보장. 담당자 미지정(null) → "미지정".

## 제약 조건

- BC 격리. slack-integration은 issue-tracking/identity-access 직접 import 0. `IssueUnfurlPort`·`UserLookupPort`(shared-kernel) 경유.
- prod SecurityConfig `/slack/events` permitAll 중앙 등록은 **후속**(ADR D6). 이번 PR은 test-boot SecurityConfig로 검증.
- 새 pgmq 큐 없음(@Async, ADR D4). 마이그레이션은 V702 인덱스 1건.
- **운영 런북(코드 외, ADR 반영)**. ① Slack App에 `atlas.company.com`을 **App Unfurl Domains**로 등록. ② 봇 스코프 `links:read`·`links:write` 추가 → 기존 설치는 관리자가 `/admin/slack` "다시 연결" 1회 필요(FR-SL-02 D6 `users:read.email` 선례와 동일). ③ Event Subscriptions Request URL = `/slack/events`, `link_shared` 이벤트 구독. ④ (Brainstorming G4) `link_shared`는 **봇이 해당 채널의 멤버**일 때만 발화 — 봇을 채널에 초대해야 카드가 뜬다.

### 테스트 배선(Brainstorming G2)

- slack test-boot 컨텍스트가 신규 `IssueUnfurlPort`를 소비 → 기존 `StubUserLookupPort`/`StubSystemPermissionResolver` 패턴대로 **`StubIssueUnfurlPort`** 를 test `@TestConfiguration`에 배선(누락 시 full-boot NoSuchBean, `new-crossbc-dep-openapi-mockbean-regression`).
- issue-tracking의 `IssueUnfurlPort` 어댑터 통합 테스트는 issue-tracking 모듈 스키마/시드(프로젝트·권한스킴·이슈) 필요(`bts-cross-bc-test-migration`·`no-project-creation-feature-issue-needs-5-layer-seed`). 가시/불가시/없는키 3분기 검증은 issue-tracking 쪽에서 수행.

## 측정 가능한 완료 기준

- [ ] link_shared happy path 통합 테스트(가짜 Slack 이벤트 JSON) → `chat.unfurl` 호출 인자(channel/ts/unfurls) 검증.
- [ ] 서명 검증 단위 테스트: 유효 통과 / 위조 401 / 타임스탬프 만료 401.
- [ ] url_verification 챌린지 테스트: challenge 값 반환.
- [ ] fail-closed 테스트: 미매핑(S2)·무권한(S3)·없는 키(S8) → chat.unfurl 미호출.
- [ ] 다중 링크(S9): 볼 수 있는 것만 unfurls에 포함.
- [ ] 역방향 매핑 `findUserIdBySlackUserId` 리포지토리 통합 테스트(Testcontainers) + V702 인덱스 존재.
- [ ] `IssueUnfurlPort` 어댑터: 가시/불가시/없는키 3분기 통합 테스트(issue-tracking, fail-closed).
- [ ] 부팅 안전: signing secret 미설정 시 컨텍스트 로드 성공, 호출 시 401.

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 재검토로 갭 4건 발견·보강 — G1 @Async 경계 executor+@EnableAsync+테스트 결정성(NFR6), G2 test-boot Stub 배선+cross-BC 어댑터 테스트(테스트 배선 §), G3 서명헤더 누락 401+라벨 해석 책임(FR2·API §), G4 봇 채널 멤버 런북(제약 §). Maxi 결정 필요 항목 0.
