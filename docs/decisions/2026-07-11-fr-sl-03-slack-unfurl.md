# ADR: FR-SL-03 — Slack Unfurl (Atlas URL 자동 카드), BC 최초 인바운드 Events API

> 날짜: 2026-07-11
> 상태: Accepted (Maxi 게이트 확정 — 3결정 모두 권장안)
> 관련 FR: FR-SL-03 (Unfurl — Atlas URL 자동 카드)
> 관련 ADR: [2026-07-07-fr-sl-01-slack-bot-app.md](2026-07-07-fr-sl-01-slack-bot-app.md) (slack-integration BC·slack-api-client·SecretEncryptor·수신 엔드포인트=자체 컨트롤러) · [2026-07-10-fr-sl-02-slack-notification-delivery.md](2026-07-10-fr-sl-02-slack-notification-delivery.md) (user_slack_mapping·봇토큰 조회·아웃바운드 패턴)
> 관련 PR: PR #257

## 맥락

FR-SL-03은 Slack 대화에 붙은 Atlas 이슈 URL(`https://atlas.company.com/issues/PROJ-123`)을
Slack의 `link_shared` 이벤트로 수신해, 열람 권한을 확인한 뒤 이슈 스냅샷(키·제목·상태·우선순위·담당자)을
Block Kit 카드로 되돌려 Slack이 링크를 카드로 펼치게(unfurl) 한다(SDD §9.3.3).

**FR-SL-01/02와의 결정적 차이 — BC 최초의 인바운드 Events API.**
FR-SL-01(설치 콜백)·FR-SL-02(알림 아웃바운드)는 우리가 Slack으로 나가는 흐름이거나 브라우저 리다이렉트였다.
FR-SL-03은 Slack이 우리 서버로 **서버-투-서버 POST**를 보내는 첫 사례다. 따라서 Slack Events API의
요청 서명(`X-Slack-Signature`, `v0=HMAC-SHA256(signing_secret, "v0:{ts}:{raw_body}")`) 검증 + 타임스탬프
재전송 방어가 처음 필요하다. 기존 OAuth state HMAC(`SlackOAuthStateSigner`)과는 알고리즘·키가 달라 재구현한다.

**기존 자산 조사 결론.** 봇 토큰 조회(`SlackBotTokenResolver`), SDK 호출 패턴(`SlackMessageClient`),
가시성 판정(`IssueVisibilityPort.filterVisibleUserIds`, fail-closed), 담당자 표시명(`UserLookupPort.findDisplayNamesByIds`),
Block Kit 조립(`SlackBlockKitRenderer`)은 재사용. 반면 서명 검증기·`/slack/events` 수신 엔드포인트·`chat.unfurl` 호출·
Slack→Atlas 역방향 매핑 조회·단건 이슈 상세 read 포트·prod SecurityConfig의 slack permitAll은 전무하다.

## 결정

### D1. cross-BC 이슈 조회 = 단일 결합 fail-closed 포트 (Maxi 게이트)

Unfurl 카드는 이슈 상세(제목·상태·우선순위·담당자)를 issue-tracking BC에서 가져와야 한다.
**shared-kernel에 단일 결합 read 포트를 신설**한다.

```
// shared-kernel
interface IssueUnfurlPort {
    /** viewer가 볼 수 있으면 카드 스냅샷, 아니면 null (fail-closed). */
    fun getVisibleIssueCard(issueKey: String, viewerUserId: UUID): IssueUnfurlView?
}
data class IssueUnfurlView(issueKey, summary, statusLabel, priorityLabel, assigneeDisplayName?, ...)
```

- 권한 확인 + 상세 조회를 **issue-tracking 어댑터 한 곳**에서 원자적으로 수행한다. viewer가 볼 수 없으면 `null`을 반환한다.
- 근거. slack-integration BC는 "볼 수 없는 이슈의 데이터"를 **물리적으로 수신하지 못한다** → fail-open이 구조적으로 불가능.
  BTS의 반복된 cross-BC fail-open 사고(`crossbc-resolver-nullable-fail-open`·`crossbc-no-cross-project-visibility-predicate`·
  `best-effort-loop-permission-exception-nonprod-mask`)를 설계 단계에서 차단한다.
- 대안(기존 포트 조합 — `IssueVisibilityPort.filterVisibleUserIds` 재사용 + 별도 상세 포트) 기각.
  slack BC가 "가시성 확인 → 상세 조회" 순서를 스스로 지켜야 하고, 순서/조건 실수 시 상세가 누출된다.
- 어댑터는 내부적으로 기존 가시성 로직(`securityDirectory.accessibleLevels` + `existsVisibleIssue` 계열)을 재사용해
  판정하고, 상태/우선순위 라벨은 issue-tracking read model에서, 담당자 표시명은 `UserLookupPort.findDisplayNamesByIds`로 해석한다.

### D2. 권한 주체 = 링크 공유자(Slack user), 미매핑/무권한 → unfurl 안 함

`link_shared` 이벤트는 링크를 공유한 Slack `user` + `channel` + `links[]`를 준다.

- **공유자 Slack user_id → Atlas user_id**를 역방향 매핑으로 해석하고, 그 Atlas user의 열람 권한으로 판정한다(SDD §9.3.3 "볼 수 있는 이슈만").
- 미매핑(연결 안 한 사용자)이거나 무권한이면 `getVisibleIssueCard`가 null → **해당 URL은 unfurl하지 않는다**(silent, 카드 없음). fail-closed.
- 벤치마크. GitHub·Jira Cloud의 Slack 앱과 동일 모델(공유자 권한 기준).

**수용 한계 (명시).** Slack unfurl 카드는 **채널 구성원 전원에게 노출**된다. 공유자가 권한을 가지면 같은 채널의
무권한자에게도 이슈 요약이 보인다. 이는 GitHub/Jira Slack 앱과 동일한 수용된 트레이드오프다(per-viewer 렌더 불가 —
Slack이 채널 단위로 카드를 표시). 민감 프로젝트의 채널 노출 정책은 FR-SL-06(채널 매핑) 심화 시 재검토.

### D3. Unfurl 카드 = 정보 카드만 (액션 버튼은 FR-SL-05로) (Maxi 게이트)

카드는 **키+제목, 상태, 우선순위, 담당자**까지만 렌더한다. SDD §9.3.3이 언급한 "빠른 액션 버튼(상태 변경/댓글)"은
버튼 상호작용(`block_actions` 핸들러 + 권한 가드 + 응답 갱신)을 요구하며 이는 **FR-SL-05(인터랙티브)의 책임**이다.
FR-SL-03에서 버튼까지 만들면 FR-SL-05 설계를 선점하고 BC 스코프가 번진다. `SlackBlockKitRenderer`를 확장해
정보 필드만 담은 unfurl용 카드 렌더 메서드를 추가한다.

### D4. 3초 룰 대응 = ack 200 즉시 + 경량 @Async 처리 (Maxi 게이트)

Slack Events API는 3초 내 200 ack가 없으면 재전송한다(`X-Slack-Retry-Num`).

- 컨트롤러는 서명 검증 후 **즉시 200 ack**하고, 이슈 해석 + `chat.unfurl` 호출은 Spring `@Async`로 처리한다.
- 새 pgmq 큐/워커/마이그레이션 없음. unfurl은 best-effort UX(실패해도 링크가 평문으로 남을 뿐)라 durable 재시도가 불요하다.
- 대안(pgmq `q_slack_unfurls`) 기각. FR-SL-02 아웃바운드와 패턴은 일관되나, 인바운드-트리거 best-effort UX에는
  durable 큐·dead-letter·워커 신설이 과설계. 향후 재시도 durability가 필요해지면 같은 경로로 전환 가능(후속 후보).
- `url_verification`(이벤트 구독 설정 시 1회) 챌린지는 동기적으로 `challenge` 값을 즉시 반환한다.

### D5. 역방향 매핑 조회 + 인덱스 (V702)

`user_slack_mapping`(V701)은 PK=`user_id` 단방향 조회만 지원한다(마이그레이션 주석이 역방향을 예상 안 함).
FR-SL-03은 `slack_user_id(+team_id) → user_id` 역방향이 핵심 전제다.

- `SlackUserMappingRepository`에 `findUserIdBySlackUserId(slackUserId, teamId): UUID?` 신설.
- 조회 성능을 위해 **V702 마이그레이션으로 `(slack_user_id, team_id)` 인덱스 추가**. 단일 워크스페이스 전제라도
  향후 다중 워크스페이스 대비 team_id 포함.

### D6. 수신 엔드포인트 인증 = X-Slack-Signature (prod SecurityConfig permitAll은 후속)

- `POST /slack/events`는 JWT 세션이 없다(Slack 서버가 호출). 인증은 **요청 서명 검증**으로 대체하고, raw body 기반
  HMAC이므로 **CSRF 무시 + permitAll**이 필요하다.
- prod 단일 `SecurityConfig`(identity-access)에 `/slack/events` permitAll + CSRF-ignore를 넣는 것은 **BC 격리 위반**이라,
  이번 PR은 slack **test-boot** SecurityConfig로만 검증한다(FR-SL-01 D7 `/slack/install/callback`·automation 웹훅과 동일 미결).
  배포 조립 시점의 중앙 등록은 DEVELOPMENT.md §1.4 예외로 ADR/게이트 승인이 필요한 후속(public-dashboards·ical-feed 선례).
- signing secret은 `BTS_SLACK_SIGNING_SECRET` 환경변수 → `bts.slack.signing-secret` 프로퍼티. 미설정이어도 빈은 등록,
  검증은 호출 시점으로 미룬다(`profile-scoped-bean-boot-failure`·FR-SL-01 D3 부팅 안전 관례).

## 범위 밖 (후속)

- **액션 버튼 + block_actions 핸들러** = FR-SL-05.
- **prod SecurityConfig 중앙 permitAll 등록** = 배포 조립 후속(FR-SL-01 D7·automation 웹훅과 함께).
- **채널 노출 정책(민감 프로젝트)** = FR-SL-06 심화.
- **이슈 키 리다이렉트(이동된 이슈)** 처리 — read 포트가 redirect를 따를지 여부는 스펙에서 확정(기본: 현재 키만 해석).

## 결과

- slack-integration ↔ issue-tracking 코드 import 0 유지(shared-kernel 포트 경유).
- fail-closed 결합 포트로 cross-BC 권한 누출 구조적 차단.
- BC 최초 인바운드 서명 검증기가 FR-SL-04(Slash)·FR-SL-05(Interactive)의 수신 검증에 재사용 기반이 된다.
