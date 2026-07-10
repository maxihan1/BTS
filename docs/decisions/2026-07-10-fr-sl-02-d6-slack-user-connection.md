# FR-SL-02 D6 — 사용자 Slack 계정 연결 UX 결정 (이메일 자동해석)

> 날짜: 2026-07-10
> 상태: Accepted (Maxi 게이트 확정 — C. 이메일 자동해석)
> 관련 FR: FR-SL-02 (알림 발송 DM + 채널) D6(프론트)·D7(E2E)
> 관련 ADR: [2026-07-10-fr-sl-02-slack-notification-delivery.md](2026-07-10-fr-sl-02-slack-notification-delivery.md) (백엔드 코어 D1~D5) · [2026-07-08-fr-sl-01-d6-slack-connect-page.md](2026-07-08-fr-sl-01-d6-slack-connect-page.md) (D6/D7 관리자 연결 페이지 선례)
> 관련 PR: PR #252 (백엔드 코어 D1~D5), 이번 PR(D6/D7, 번호 미정)

## 맥락

FR-SL-02 백엔드(PR #252)는 이슈 멘션/할당을 Slack DM으로 발송하지만, `user_slack_mapping`(V701)에 해당 사용자 행이 없으면 수신 대상 해석 단계에서 조용히 skip된다. 즉 백엔드는 완성돼 있어도 **매핑이 하나도 없으면 실제로 아무도 DM을 못 받는다**. D6은 사용자가 본인 Slack 계정을 스스로 연결해 이 알림을 활성화하는 자가 서비스 진입점이다.

D6 착수 전 세 가지 연결 방식을 검토했다.

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| A. Sign in with Slack OAuth | 사용자가 Slack OAuth 왕복(slack.com 리다이렉트)으로 본인 identity를 증명 | 가장 견고(Slack이 신원 보증). 신규 사용자도 대응 | OAuth 왕복 구현 필요(전체 페이지 이동·state 서명·콜백). FR-SL-01 설치 흐름과 별개의 "사용자 OAuth" 앱 설정(Slack 앱 쪽 sign-in scope 추가) 필요 |
| B. `slack_user_id` 직접 입력 | 사용자가 자신의 Slack 사용자 ID를 수동으로 입력 | 구현 최소(백엔드 lookup 불필요) | 사용자가 Slack ID를 몰라 찾아야 함(UX 마찰). 오탈자/타인 ID 입력 시 **오배송 위험**(잘못된 사람에게 DM) — 검증 수단 없음 |
| C. 이메일 자동해석 | 사용자가 "연결" 클릭만 하면 백엔드가 봇 토큰으로 `users.lookupByEmail(본인 BTS 이메일)` 호출, 매칭된 `slack_user_id`를 자동 매핑 | 원클릭(입력 0). 이메일 일치가 소유권 검증 역할 — 오배송 0. OAuth 왕복 없음(me-scope API 호출만) | 봇 스코프 `users:read.email` 추가 필요(기존 설치 재연결 필요). BTS 이메일 ≠ Slack 이메일인 사용자는 실패(폴백 없음, 후속) |

## 결정

**C. 이메일 자동해석**을 채택한다.

**근거.**
- 사내 단일 워크스페이스 전제(FR-SL-01 EC6 — `findCurrentInstallation()` 최신 1건)라 이메일 도메인이 사실상 고정돼 있고, BTS 이메일과 Slack 이메일이 일치하는 것이 일반적이다.
- 오배송 0 — 이메일이 봇의 `users.lookupByEmail`로 확인되므로 타인 매핑 가능성이 구조적으로 없다(B안이 갖는 위험을 제거).
- OAuth 왕복 없음 — A안 대비 구현 범위가 작고(전체 페이지 리다이렉트 처리·별도 sign-in scope 불요), me-scope REST 호출(JWT Bearer)만으로 완결된다. FR-SL-01 D6이 이미 "Bearer 인증 vs 전체 페이지 이동" 제약을 겪었는데(`avatar-auth-image-cachebust` 계열 문제), C안은 애초에 전체 페이지 이동 자체가 없어 이 제약과 무관하다.
- A안은 신규 Slack 앱 sign-in scope·별도 OAuth 플로우가 필요해 이번 스코프 대비 과잉이다(향후 이메일 불일치 폴백이 필요해지면 재검토 후보).

## 결과 / 제약

### me-scope 엔드포인트 3종

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/v1/slack/me/connection` | JWT me | `{ connected, workspaceName?, linkedAt? }` |
| POST | `/api/v1/slack/me/connection` | JWT me | 연결(이메일 자동해석 실행) |
| DELETE | `/api/v1/slack/me/connection` | JWT me | 해제(하드삭제, 멱등) |

기존 D6(FR-SL-01)의 `/api/v1/slack/installation`·`/install-url`은 **관리자 전역** 설치 상태였다. 이번 3종은 **사용자 본인** 매핑 상태로 성격이 다르다(me-scope, 타인 조회/조작 불가).

### PAT 401 — `SlackActorExtractor` 재사용 금지

`SlackActorExtractor`(FR-SL-01 관리자 흐름에서 사용)는 PAT의 UUID subject를 그대로 통과시켜 200을 반환한다. 이는 me-scope 엔드포인트에서 PAT 사용자가 자신을 사칭한 JWT 세션인지 구분하지 못하게 만든다. D6은 이를 재사용하지 않고, `UserProfileController`/`PreferencesController` 선례를 따라 **`@AuthenticationPrincipal Jwt?` 타입 기반**으로 현재 사용자 ID를 추출한다 — PAT는 `Jwt` 타입이 아니므로 principal이 null → 401. 신규 인증 메커니즘 도입이 아니라 기존 컨트롤러 관례를 그대로 적용한 것이다.

### 외부 호출은 트랜잭션 밖

`users.lookupByEmail` 외부 Slack API 호출은 `SlackInstallService` CONCERN-1 교훈(외부 HTTP는 tx 밖)을 그대로 따른다. `SlackUserMappingService.link()`의 DB upsert만 트랜잭션으로 감싼다.

### SlackUserMappingService.link/unlink 재사용

매핑 영속화 자체(`user_slack_mapping` upsert/delete)는 FR-SL-02 백엔드 코어(PR #252)의 `SlackUserMappingService`를 그대로 재사용한다. D6이 새로 만드는 것은 "이메일 → slack_user_id 해석" 오케스트레이션(`SlackUserConnectionService`)과 REST 표면(`SlackConnectionController`)뿐이다.

### 봇 토큰 3중 미노출

FR-SL-01 관례를 연장한다. 응답에 봇 토큰(암호화/평문)·`slack_user_id`·조회에 사용한 이메일을 싣지 않는다. 상태 조회는 `connected`/`workspaceName`/`linkedAt`만 반환한다.

### EC6 단일 워크스페이스

`SlackInstallRepository.findCurrentInstallation()`(최신 1건, `installed_at DESC LIMIT 1`)의 설치만 사용한다. 다중 워크스페이스 순회는 레포지토리에 `findAll` 자체가 없어(FR-SL-01 스코프) 미지원 — 다중 설치 지원은 별도 후속.

### 신규 cross-BC 포트 0

`UserLookupPort.findEmailById`(shared-kernel, 이미 배선됨)를 그대로 재사용한다. identity-access 직접 import는 이번에도 0(BC 격리 유지).

### 신규 마이그레이션 0

`user_slack_mapping`(V701, FR-SL-02 백엔드 코어에서 이미 생성)을 그대로 재사용한다. 스키마 변경 없음.

## 운영 — 봇 스코프 추가로 인한 재연결 필요

`users.lookupByEmail` 호출에는 `users:read.email` 봇 스코프가 필요하다. 이 스코프를 `DEFAULT_SLACK_SCOPES`에 추가하는 것은 **기존 설치에는 소급 적용되지 않는다** — Slack OAuth 특성상 스코프 확장은 재설치(재인가) 시에만 반영된다.

**런북.** D6 배포 후, 이미 설치된 워크스페이스는 관리자가 `/admin/slack`에서 "다시 연결"을 1회 수행해야 `users:read.email` 스코프가 봇 토큰에 반영된다. 미재연결 상태에서 사용자가 연결을 시도하면 `SLACK_SCOPE_MISSING`(409)으로 명확히 안내되며 500으로 실패하지 않는다(graceful degradation).

## 후속 (범위 밖)

- **이메일 불일치 수동 입력 폴백** — BTS 이메일 ≠ Slack 이메일인 사용자를 위한 수동 `slack_user_id` 입력(B안 요소를 보조 경로로 재검토) 또는 A안(Sign in with Slack) 전환. 이번 스코프 아님(BLOCKER 아님).
- **연결 handle 표시** — 상태 카드에 Slack handle(`@username`) 노출은 별도 lookup 필요, 후속.
- **다중 워크스페이스** — `findCurrentInstallation()` 단일 설치 가정 해소는 FR-SL-06 심화 시.

## 영향

- FR-SL-02는 D6/D7 완료로 `[x]`(전 D단계 완료). product `slack-integration.md` §2.2 D6/D7 체크 + deviation 마킹.
- FR 카운트는 불변(D-step 완료는 FR 추가가 아니다) — `docs/plan/fr-index.md`·`docs/plan/README.md`·`CLAUDE.md`의 FR 총수·BC 카운트는 변경 없음.
