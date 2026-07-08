# FR-SL-01 D6/D7 — 관리자 Slack 연결 페이지 + view-layer 조회 엔드포인트 (스펙 델타)

> 날짜: 2026-07-08
> 선행 스펙: [2026-07-07-fr-sl-01-slack-bot-app.md](2026-07-07-fr-sl-01-slack-bot-app.md) (백엔드 코어 D1~D5)
> ADR: [2026-07-08-fr-sl-01-d6-slack-connect-page.md](../decisions/2026-07-08-fr-sl-01-d6-slack-connect-page.md)
> 범위: D6(프론트 + 같은 BC view-layer 백엔드 read) · D7(E2E). Maxi 확정 = A1(상태 조회 + 연결).

## 목적

시스템 관리자가 `/admin/slack`에서 (1) 현재 Slack 워크스페이스 연결 상태를 확인하고, (2) "연결/다시 연결"로 OAuth 설치를 개시하며, (3) 콜백 복귀 시 성공/실패 결과를 확인한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 미연결 진입**. Given 시스템 관리자, 아직 Slack 미설치. When `/admin/slack` 진입. Then "아직 연결되지 않았습니다" + `[Slack에 연결]` 버튼.
- **S2 연결됨 진입**. Given 관리자, 이미 설치됨. When 진입. Then "연결됨: `<teamName>`" + "설치일: `<installedAt>`" + `[다시 연결]` 버튼.
- **S3 연결 개시**. Given 관리자, When `[Slack에 연결]`/`[다시 연결]` 클릭. Then `GET /api/v1/slack/install-url`을 apiFetch → 응답 `url`로 `window.location.href` 이동(slack.com authorize).
- **S4 성공 복귀**. Given 콜백이 `/admin/slack?installed=Acme%20Corp`로 302. When 페이지 로드. Then 상단 성공 배너 "Acme Corp 워크스페이스에 연결되었습니다" + 상태 카드 새로고침(연결됨 표시).
- **S5 실패 복귀**. Given 콜백이 `/admin/slack?error=<code>`로 302. When 페이지 로드. Then 상단 오류 배너(코드→한국어 메시지 매핑, 미지원 코드는 일반 메시지).
- **S6 취소 복귀**. Given 사용자가 Slack 동의 화면에서 취소 → `?error=access_denied`. When 로드. Then "Slack 연결이 취소되었습니다" 배너.
- **S7 비관리자 차단**. Given 비-시스템관리자. When `/admin/slack` 진입. Then 라우트 가드가 `/dashboard`로 리다이렉트(`requireSystemAdmin`). 직접 API 호출 시에도 백엔드 403.

## 기능 요구사항 (FR)

- **FR1 상태 조회 API**. `GET /api/v1/slack/installation` — JWT + 시스템 관리자. 응답 `{ connected: boolean, teamId: string|null, teamName: string|null, installedAt: string|null }`. 미설치 시 `{ connected:false, teamId:null, teamName:null, installedAt:null }`. **봇 토큰(평문/암호문)·installedBy·scopes·appId 미포함**(비-비밀 최소).
- **FR2 authorize URL API**. `GET /api/v1/slack/install-url` — JWT + 시스템 관리자. 응답 `{ url: string }`(신선한 서명 state 포함 Slack authorize URL). 기존 `SlackInstallService.startInstall(actorId)` 재사용(302 대신 JSON).
- **FR3 관리자 가드**. FR1·FR2 모두 actor 추출(401) → `SystemPermissionResolver.isSystemAdmin`(403). 순서 = actor 추출 먼저(`auth-extraction-before-resource-lookup`). `SlackForbiddenException` → 403 매핑(기존 `SlackInstallExceptionHandler` 재사용/확장).
- **FR4 페이지**. `/admin/slack` route, `composeGuards(requireAuth, requireSystemAdmin)`. 상태 카드(연결됨/미연결) + 연결/다시연결 버튼 + 결과 배너.
- **FR5 결과 배너**. URL 쿼리 `?installed=` / `?error=` 파싱 → `role="alert"` 인라인 배너(admin.webhooks 배너 관례). 성공=teamName 표시, 실패=코드 매핑 메시지. 배너 표시 후 쿼리 파라미터는 히스토리에서 정리(replace)해 새로고침 재표시 방지.
- **FR6 에러 코드 매핑**. 백엔드 실패 코드 → 한국어 메시지(§에러 코드 매핑). 매핑에 없는 코드는 일반 메시지 fallback.
- **FR7 발견성(nav 링크)**. `Header.tsx`의 `adminLinks`(isSystemAdmin 게이팅) 배열에 `{ to: '/admin/slack', label: 'Slack 연결' }` 추가. 관리자만 헤더 관리 메뉴에서 페이지에 도달. Header 테스트 동반 갱신. *(brainstorming 발견 gap)*
- **FR8 로딩/에러 상태**. 상태 조회는 TanStack Query(`useQuery`)로 로드 — 로딩 스켈레톤/플레이스홀더, 조회 실패 시 재시도 가능한 배너(다른 조회 페이지 관례).

## API 인터페이스 (REST)

| Method | Path | 인가 | 응답 |
|---|---|---|---|
| GET | `/api/v1/slack/installation` | JWT + 시스템 관리자 | 200 `{ connected, teamId, teamName, installedAt }` |
| GET | `/api/v1/slack/install-url` | JWT + 시스템 관리자 | 200 `{ url }` |

- 401 = 미인증(actor 추출 실패), 403 = 비관리자. 둘 다 body는 비-비밀.
- 기존 `GET /slack/install`(302)·`GET /slack/install/callback`(302) **로직 불변**. 단 콜백 결과 리다이렉트 경로 상수(`FRONT_RESULT_PATH`)만 `/settings/slack` → `/admin/slack`으로 갱신(백엔드 상수 1 + KDoc 3 + 통합테스트 assertion 5, Maxi 게이트 1 결정).

## 데이터 모델 변경

- **마이그레이션 없음**. `slack_installs`(V700) 재사용.
- **신규 read projection** `SlackInstallationView(teamId, teamName, installedAt)` — 암호화 토큰을 읽지 않는 경량 조회(방어적). 
- **신규 repo 메서드** `SlackInstallRepository.findCurrentInstallation(): SlackInstallationView?` — 단일 워크스페이스 가정, `SELECT team_id, team_name, installed_at FROM slack_installs ORDER BY installed_at DESC LIMIT 1`. 미설치 시 null.

## 에러 코드 매핑 (백엔드 콜백 코드 → 한국어)

| 코드 | 출처 | 메시지 |
|---|---|---|
| `access_denied` | 사용자 취소(EC2) | Slack 연결이 취소되었습니다. |
| `invalid_state` | state 검증 실패(EC1) | 연결 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요. |
| `missing_params` | code/state 부재 | 연결 정보가 누락되었습니다. 다시 시도해 주세요. |
| `exchange_failed` | 전송 오류 | Slack과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요. |
| `unsupported_install_type` | enterprise(EC5) | 워크스페이스 단위 설치만 지원합니다(조직 전체 설치 불가). |
| `missing_access_token` | ok:true·토큰 부재(EC7) | Slack이 유효한 봇 토큰을 반환하지 않았습니다. 다시 시도해 주세요. |
| `invalid_code`·`oauth_failed`·`install_failed`·기타 | Slack ok:false·fallback | Slack 연결에 실패했습니다. 다시 시도해 주세요. |

- 매핑 키에 없는 임의 코드는 마지막 행(일반 메시지)로 처리. 코드 원문을 화면에 그대로 노출하지 않는다(주입/혼란 방지).

## 엣지 케이스

- **EC-A `?installed=`와 `?error=` 동시**. 비정상. `error` 우선(실패 우선 표기).
- **EC-B teamName에 특수문자/이모지**. URL 디코드 후 텍스트로만 렌더(React 기본 이스케이프, dangerouslySet* 금지).
- **EC-C 상태 API 401**. 세션 만료 → apiFetch가 refresh 후 재시도(기존 인터셉터). 그래도 실패면 로그인 리다이렉트(전역 처리).
- **EC-D 상태 API 403**. 라우트 가드가 이미 비관리자를 막지만, 방어적으로 페이지 내 403 시 "권한이 없습니다" 배너.
- **EC-E install-url fetch 실패**. 버튼 클릭 후 네트워크/500 → 인라인 오류 토스트/배너, 페이지 유지(재시도 가능).
- **EC-F 배너 후 새로고침**. 쿼리 정리(replace)로 배너가 다시 뜨지 않게 한다.
- **EC-G 미설치 상태에서 성공 배너 없이 진입**. 정상. 미연결 카드만.
- **EC-H installedAt 타임존**. Instant(ISO) 저장 → 표시 레이어에서 사용자 로캘 포맷(기존 date 표시 관례). ISO 원문 노출 금지.

## 제약 조건

- **BC 격리**. slack-integration은 identity-access import 0. 관리자 판정은 `SystemPermissionResolver` 포트만.
- **봇 토큰 미노출**. 상태 조회 경로는 암호화 토큰을 아예 로드하지 않는다(projection).
- **Bearer 제약**. install-url은 apiFetch(Bearer)로 받고 nav. `/slack/install`(302) 직접 nav 금지(401).
- **기존 302 흐름 로직 불변**. 콜백/install의 302 로직은 그대로. `FRONT_RESULT_PATH` 상수만 `/admin/slack`으로 갱신(게이트 1 결정).
- **disconnect/revoke 제외**. 후속(하드삭제 ADR).

## 측정 가능한 완료 기준

- [ ] `GET /api/v1/slack/installation` — 관리자 200(연결/미연결 두 케이스), 비관리자 403, 미인증 401. 봇 토큰 미포함 실증.
- [ ] `GET /api/v1/slack/install-url` — 관리자 200 `{ url }`(authorize URL 형식), 비관리자 403.
- [ ] `/admin/slack` — 관리자 렌더, 비관리자 `/dashboard` 리다이렉트.
- [ ] 상태 카드 — 연결됨(teamName+installedAt)·미연결 분기 렌더.
- [ ] 연결 버튼 — install-url fetch → nav 실행(단위: fetch 호출·nav 인자 검증).
- [ ] 결과 배너 — `?installed`/`?error` 7개 코드 매핑 + fallback + 쿼리 정리.
- [ ] E2E(D7) — 관리자 진입/비관리자 게이팅/연결됨·미연결/성공·실패 배너.
- [ ] 백엔드 test + ktlint + detekt + ArchUnit(BC 격리) green. 프론트 lint + typecheck + test green.
- [ ] Header 관리 메뉴에 "Slack 연결"(관리자만) 링크 표시.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 1건 — **페이지 발견성**(어떤 nav로 `/admin/slack`에 도달?). 기존 `Header.tsx` `adminLinks`(isSystemAdmin 게이팅) 패턴으로 해소 → FR7 추가. Maxi 결정 필요 gap 없음(기존 관례 재사용). 검토한 나머지: installedAt DB 컬럼 존재(impl 검증)·Instant ISO 직렬화 함정·projection으로 토큰 미로드·reconnect 멱등(확인 다이얼로그 불요)·배너 후 쿼리 정리(EC-F)·loading 상태(FR8) — 모두 스펙 반영.
