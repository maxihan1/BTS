# ADR: FR-SL-04 — Slack Slash 명령어 (`/atlas ...`)

> 날짜: 2026-07-11
> 상태: Proposed (Maxi 게이트1 대기 — 도메인 결정 3건은 확정)
> 관련 FR: FR-SL-04 (Slash 명령어 — `/atlas search|create|view|help`)
> 관련 ADR: [2026-07-11-fr-sl-03-slack-unfurl.md](2026-07-11-fr-sl-03-slack-unfurl.md) (BC 최초 인바운드 서명검증·역매핑·IssueUnfurlPort) · [2026-07-10-fr-sl-02-slack-notification-delivery.md](2026-07-10-fr-sl-02-slack-notification-delivery.md) (user_slack_mapping·봇토큰 조회)
> 관련 PR: PR #258

## 맥락

FR-SL-04는 Slack 사용자가 `/atlas ...` slash 명령을 입력하면 BTS가 응답하는 **인바운드 명령 처리** 기능이다(SDD §9.3.4). FR-SL-03(Unfurl)이 BC 최초의 인바운드 Events API(`POST /slack/events`)를 세웠고, FR-SL-04는 Slack이 별도 URL(`POST /slack/commands`)로 보내는 **slash command 요청**을 받는다.

**FR-SL-03과의 결정적 차이 — 요청 본문 형식.**
Events API는 `application/json`이었으나 slash command는 **`application/x-www-form-urlencoded`**(`command=/atlas&text=search+PROJ+...&user_id=U123&team_id=T1&channel_id=C1&response_url=https://...&trigger_id=...`)로 온다. 서명(`X-Slack-Signature`)은 여전히 **raw body 바이트**에 대해 계산되므로 [SlackSignatureVerifier]를 그대로 재사용하되, 검증 통과 **후에만** form-decode 한다.

**기존 자산 조사 결론(재사용).**
- 서명 검증 — `SlackSignatureVerifier.isValid(ts, sig, rawBody)` (스킴 동일, 그대로 재사용).
- Slack→BTS 사용자 역매핑 — `SlackUserMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)` (FR-SL-03 V702).
- 이슈 카드 조회 — `IssueUnfurlPort.getVisibleIssueCard(issueKey, viewerUserId)` (fail-closed 결합 포트, `view`에 그대로 재사용).
- 이슈 생성 — `IssueImportPort.importIssue(IssueImportCommand)` (CREATE_ISSUE 게이트+단일 tx, `create`에 재사용).
- Block Kit 조립 — `SlackBlockKitRenderer` (unfurl 카드 렌더 메서드 확장).

**신규가 필요한 부분.**
- `POST /slack/commands` 수신 엔드포인트 + form-decode.
- `/atlas <sub> <args>` 파서(SlashCommand VO).
- **AQL 텍스트 검색 cross-BC 포트** — search BC가 AQL 파싱을 소유(파서 `com.bts.search.aql`는 slack이 import 불가). `IssueSearchPort`는 이미 파싱된 AST를 요구하므로 raw 문자열용 별도 포트가 필요.
- `response_url`로 지연 응답을 POST하는 아웃바운드 클라이언트(봇 토큰 불요 — response_url 자체가 인증된 웹훅 URL).

## 결정

### D1. 수신 엔드포인트 = `POST /slack/commands`, 서명검증 재사용 + form-decode는 검증 후

- 컨트롤러는 `@RequestBody String rawBody`로 원문을 그대로 받아 `SlackSignatureVerifier.isValid` 로 **먼저** 검증하고, 통과 후에만 form-urlencoded 파싱(`command`·`text`·`user_id`·`team_id`·`channel_id`·`response_url`·`trigger_id`)을 수행한다.
- 서명 실패(위조·헤더 누락·재전송·secret 미설정) → 빈 401. 비밀값·원문·예외 message 미노출(FR-SL-03 NFR2 동형).
- 근거. Slack 서명은 재조립되지 않은 수신 원문 바이트 기준이다. Spring이 `@ModelAttribute`로 form 파싱하면 원문 재구성이 불가능해 서명 검증이 깨진다.

### D2. 서브커맨드 4종 (Maxi 확정) — `/atlas <help|view|search|create> [args]`

- `help` — 사용법 안내. cross-BC 없음. 미매핑 사용자도 응답(계정 연결 안내 포함).
- `view <KEY>` — 이슈 요약 카드. `IssueUnfurlPort.getVisibleIssueCard` 재사용(fail-closed — 매핑된 BTS 사용자가 볼 수 없으면 "찾을 수 없음").
- `search <PROJ> <aql>` — AQL 검색(D4 신규 포트). 결과 목록 카드.
- `create <PROJ> <title>` — 이슈 생성. `IssueImportPort.importIssue` 재사용(CREATE_ISSUE 게이트).
- 알 수 없는 서브커맨드/빈 명령 → `help` 안내로 폴백(ephemeral).

### D3. search/create 프로젝트 스코프 = 인라인 인자 필수 (Maxi 확정)

- `/atlas search PROJ status=open`, `/atlas create PROJ "버그 제목"`. 첫 토큰이 프로젝트 키.
- 근거. 채널→프로젝트 매핑(FR-SL-06)은 미구현이라 의존 시 이 FR이 블록된다. 인라인 인자는 이 FR 안에서 자족적이며 명시적이다.
- 프로젝트 키 누락 시 → ephemeral 사용법 오류 안내.

### D4. AQL 검색 = 신규 shared-kernel 포트, search BC가 파싱 소유

```
// shared-kernel
interface SlashIssueSearchPort {
    /** raw AQL 문자열을 파싱+검색. 문법 오류는 결과 타입으로 전달(예외 아님, fail-safe 빈 결과 default). */
    fun search(rawAql: String, projectKey: String, viewerUserId: UUID, page: Int, size: Int): SlashSearchOutcome
}
```

- 흐름. `slack → SlashIssueSearchPort(shared-kernel) ← search-export-import 어댑터(AqlLexer+AqlParser로 파싱) → IssueSearchPort(shared-kernel) ← issue-tracking 어댑터(visibility AND 결합)`. 2-홉, BC 격리 유지.
- **AQL 문법 오류 전달**. `AqlSyntaxException`은 search BC 내부 타입이라 slack이 못 본다. 예외 클래스명 문자열 매칭 금지([[crossbc-failure-classification-typed-not-name]]) → 어댑터가 파싱 실패를 잡아 `SlashSearchOutcome`(성공 페이지 | 문법오류 메시지) 결과 타입으로 반환. slack은 이를 ephemeral 오류로 렌더.
- default 구현은 fail-safe 빈 결과(읽기 포트 — `IssueSearchPort` 선례). 어댑터 부재 환경(단위 테스트)에서 안전.

### D5. 이슈 생성 = 기존 `IssueImportPort` 재사용

- `/atlas create PROJ "제목"` → `IssueImportCommand(projectKey, requesterUserId=매핑된 BTS user, summary=제목)`(필수 3필드, 나머지 기본값)으로 `importIssue` 호출.
- 근거. `IssueImportPort`는 이미 **fail-closed 쓰기 포트**로 CREATE_ISSUE 권한 게이트+단일 tx를 강제한다. slash create를 위해 별도 쓰기 포트를 신설하면 issue-tracking 어댑터 로직이 중복된다.
- **전제 검증 필요(스펙)**. `IssueImportResult`가 생성된 이슈 **키**(`PROJ-123`)를 반환해야 응답에 표시할 수 있다. 미반환 시 결과 타입에 key 추가(issue-tracking same-BC view 확장) 또는 얇은 신규 포트 — 스펙에서 확정.
- 대안(신규 `IssueCreatePort`) 기각 후보. import 시맨틱(sourceKey·changelog 재생)이 slash create엔 불필요하나 전부 optional이라 부담 없음. eng-review에서 재확인.

### D6. 권한 주체 + 미매핑 처리 = 매핑된 BTS 사용자, 미매핑 → 친절한 안내(unfurl과 다름)

- 모든 명령은 **Slack user_id → BTS user_id 역매핑**으로 해석한 BTS 사용자의 권한으로 실행한다(FR-SL-03 D2 동형).
- **미매핑 사용자** → unfurl은 silent였지만, slash는 사용자가 **명시적으로 호출**했으므로 ephemeral로 "계정 연결이 필요합니다(`/settings` 안내)"를 응답한다. 침묵은 slash UX에서 고장으로 오인된다.
- 무권한(view 볼 수 없음/create 권한 없음/search 결과 0) → 각 명령별 적절한 ephemeral 메시지. fail-closed(권한 없으면 노출 0).

### D7. 3초 룰 대응 = 즉시 ack + `@Async` → `response_url` 지연 응답, 전부 ephemeral

- Slack slash는 3초 내 응답이 없으면 실패 표시. 명령별 처리(cross-BC 조회/쓰기)가 3초를 넘을 수 있어, 컨트롤러는 **즉시 200 ack**(경량 ephemeral "처리 중" 또는 빈 200)하고, 실제 처리+응답은 `@Async`로 `response_url`에 `{"response_type":"ephemeral", "blocks":[...]}`를 POST한다.
- 근거. FR-SL-03 D4(ack 즉시 + @Async)와 일관. `response_url`은 인증된 웹훅 URL이라 봇 토큰 불요 → 신규 `SlackResponseUrlClient`(단순 POST).
- help처럼 즉각 응답 가능한 명령도 동일 경로로 통일(분기 단순화). 별도 pgmq 큐/워커 없음 — best-effort UX(실패해도 사용자가 재입력).

### D8. prod SecurityConfig permitAll(`/slack/commands`)은 배포 조립 후속

- `POST /slack/commands`는 JWT 세션이 없고 서명 검증으로 인증을 대체하므로 permitAll + CSRF-ignore가 필요하다.
- FR-SL-03 D6과 동일하게, prod 단일 SecurityConfig(identity-access) 중앙 등록은 **BC 격리 위반**이라 이번 PR은 slack **test-boot** SecurityConfig로만 검증한다. 중앙 등록은 배포 조립 후속(`/slack/events`·`/slack/install/callback`·automation 웹훅과 함께, DEVELOPMENT.md §1.4 예외 게이트).

## 범위 밖 (후속)

- **인터랙티브 버튼/메뉴** = FR-SL-05.
- **채널→프로젝트 매핑 기반 프로젝트 자동 스코프** = FR-SL-06.
- **prod SecurityConfig 중앙 permitAll 등록** = 배포 조립 후속.
- **create 확장 필드**(우선순위·라벨·담당자 등 인라인 플래그) = 후속. 이번엔 프로젝트+제목만.
- **이슈 키 리다이렉트**(이동된 이슈의 `view`) = IssueUnfurlPort 현행 동작 상속(현재 키만).

## 결과

- slack-integration ↔ issue-tracking/search-export-import 코드 import 0 유지(shared-kernel 포트 경유).
- FR-SL-03 인바운드 서명검증기·역매핑이 재사용되어 신규 코드 표면이 좁다.
- 신규 `SlashIssueSearchPort`가 향후 다른 인바운드 채널(챗봇 등)의 AQL 텍스트 검색에 재사용 기반이 된다.
- fail-closed cross-BC 포트(view·create·search)로 권한 누출 구조적 차단.
