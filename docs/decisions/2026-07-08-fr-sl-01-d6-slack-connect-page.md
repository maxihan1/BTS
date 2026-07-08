# ADR: FR-SL-01 D6/D7 — 관리자 Slack 연결 페이지 + SPA용 view-layer 조회 엔드포인트

> 날짜: 2026-07-08
> 상태: Accepted (Maxi 게이트 확정 — A1 상태 조회 + 연결)
> 관련 FR: FR-SL-01 (Slack App + Bot Token 방식) D6(프론트)·D7(E2E)
> 관련 ADR: [2026-07-07-fr-sl-01-slack-bot-app.md](2026-07-07-fr-sl-01-slack-bot-app.md) (백엔드 코어 D1~D5)
> 관련 PR: PR #244(백엔드 코어), PR #247(D6/D7)

## 맥락

FR-SL-01 백엔드 코어(D1~D5, PR #244)는 브라우저 전체 페이지 흐름을 가정한 두 엔드포인트를 제공한다.
- `GET /slack/install` — `SecurityContextHolder`에서 시스템 관리자 판정 후 Slack authorize URL로 **302**.
- `GET /slack/install/callback` — permitAll(서명 state 자체 검증) 후 프론트 결과 경로 `/settings/slack?installed=|error=`로 **302**.

D6은 이 흐름을 개시·표시하는 관리자 UI다. 그런데 BTS SPA의 인증 모델과 정면으로 부딪히는 제약이 있다.

**Bearer 인증 제약.** BTS의 access token은 메모리(zustand store)에 두고 `apiFetch`가 `Authorization: Bearer` 헤더로만 전송한다(`apps/web/src/api/client.ts`). refresh token만 HttpOnly 쿠키다. 브라우저의 **전체 페이지 이동**(`window.location` / `<a>`)은 커스텀 헤더를 실을 수 없으므로 Bearer 토큰이 전달되지 않는다. 따라서 "연결" 버튼을 `/slack/install`(302, SecurityContext 관리자 판정)로 직접 이동시키면 SecurityContext가 익명 → **401**이다(아바타 인증 이미지 `<img src>` 401과 동일 원리, `avatar-auth-image-cachebust`).

OAuth 설치는 본질적으로 전체 페이지 리다이렉트(slack.com 왕복)를 요구하므로, 인증이 필요한 구간(설치 개시)과 리다이렉트 구간(slack.com)을 분리해야 한다.

## 결정

### D6-1. SPA용 JSON view-layer 엔드포인트 2종 신설 (`/api/v1/slack/*`, 관리자 가드)

기존 302 `/slack/install`은 그대로 두고(배포 조립/쿠키 기반 경로용), SPA가 Bearer로 호출할 JSON 엔드포인트를 같은 BC에 추가한다. 프론트 PR이 같은 BC의 view-layer 백엔드를 포함하는 것은 확립된 패턴이다(learnings 2026-05-22 "옵션 C").

| Method | Path | 인가 | 응답 |
|---|---|---|---|
| GET | `/api/v1/slack/installation` | JWT + 시스템 관리자 | `{ connected: bool, teamName?, teamId?, botUserId?, installedAt? }` |
| GET | `/api/v1/slack/install-url` | JWT + 시스템 관리자 | `{ url: <신선한 서명 state를 실은 Slack authorize URL> }` |

프론트 흐름. 페이지 진입 시 `installation`으로 연결 상태 표시 → "연결/다시 연결" 클릭 시 `install-url`을 apiFetch로 받아 `window.location.href = url`(slack.com은 인증 불필요). Slack 콜백은 기존 302 흐름 그대로 `/settings/slack?installed=|error=`로 복귀.

- **관리자 가드**는 기존 백엔드 코어와 동일하게 cross-BC `SystemPermissionResolver` 포트(shared-kernel, fail-closed)로 판정. identity-access import 0(BC 격리).
- `install-url`은 `SlackInstallService.startInstall(actorId)`가 만드는 authorize URL을 그대로 반환한다(관리자 판정·서명 state 발급 로직 재사용). 302 대신 JSON body로 감싸는 차이만 있다.
- **인가 순서**. actor 추출(401)을 관리자 판정(403)·리소스 조회보다 먼저 수행한다(`auth-extraction-before-resource-lookup`).

### D6-2. 상태 조회는 비-비밀 메타만, users 조인 없음 (BC 격리 유지)

`installation`은 `slack_installs`의 비-비밀 컬럼(`team_id`·`team_name`·`bot_user_id`·`installed_at`)만 반환한다. **봇 토큰(암호화/평문)은 절대 응답에 싣지 않는다**(백엔드 코어의 3중 미노출 원칙 연장). `installed_by`(user id)는 값으로도 반환하지 않는다 — "설치자 이름" 표시는 identity-access users 조인이 필요해 FR-SL-01 스펙의 명시적 범위 밖(후속). 미설치 시 `{ connected: false }`.

### D6-3. 라우트 = `/settings/slack` (백엔드 `FRONT_RESULT_PATH` 상수와 일치)

백엔드 콜백이 이미 `/settings/slack`으로 302하도록 상수 박제(`SlackInstallController.FRONT_RESULT_PATH`)돼 있으므로, D6 페이지는 그 경로에 둔다. 시스템 관리자 전용 내용이지만(페이지 내 `isSystemAdmin` 게이팅), 백엔드가 예약한 결과 경로를 존중해 상수 변경 churn을 피한다.

### D7. E2E = MSW 기반 (실 Slack/OAuth 왕복 없음)

D7 E2E는 slack.com 실제 왕복을 재현하지 않는다(외부 리다이렉트). MSW로 `installation`/`install-url` 응답을 stub하고, 콜백 결과는 `/settings/slack?installed=|error=`로 직접 진입해 배너 렌더를 검증한다. 비관리자 게이팅·연결됨/미연결 상태·성공/실패 배너를 커버.

## 알려진 한계 (수용)

- **disconnect/revoke 없음**. 연결 해제(하드 삭제)는 별도 ADR(삭제 시맨틱·감사) 필요 — 후속.
- **배포 조립 미완**. `/slack/install/callback` permitAll·`/api/v1/slack/*` 관리자 가드의 중앙 `SecurityConfig` 등록은 여전히 배포 조립 후속(D7 ADR 선례). 이번 PR은 test-boot SecurityConfig로 검증.
- **단일 워크스페이스 표시**. `installation`은 최신/단일 설치를 표시. 다중 워크스페이스 UI는 FR-SL-06 심화 시.

## 영향 / 후속

- FR-SL-01은 D6/D7 완료로 `[x]`(전 D단계 완료). product `slack-integration.md` §2.1 D6/D7 체크 + 마킹.
- `install-url`·`installation` 조회 패턴은 FR-SL-02(알림 매핑 UI)·FR-SL-06(채널 매핑 UI)이 재사용 후보.
