# nginx 접속로그 경로토큰 마스킹

> slug: nginx-access-log-path-token-masking
> type: auth
> agent: security-engineer
> primary_bc: (해당 없음 — 인프라 전용. classify 는 identity-access 로 추정했으나 변경 대상은 `infra/prod/nginx.conf`)
> 생성: 2026-07-26

## Brief

### 사용자 원문

nginx 접속 로그(`access_log`)에 경로 세그먼트·쿼리의 비밀 토큰이 평문으로 매 요청 적재되는 것을 봉합한다.
처방 방향은 커스텀 `log_format` + `map` 기반 마스킹으로 관측성(메서드·상태·응답시간)은 유지하되 비밀값만
가리는 것이며, 같은 파일 L3 에 `map $http_upgrade` 선례가 이미 있다.
하드코딩 경로 목록이 눈가리개가 되지 않도록 봉인 테스트를 함께 요구한다.

### 실증 근거 (가설 아님 — 2026-07-26 조사에서 증거 사슬 완결)

```
infra/prod/Dockerfile.web:6        FROM nginx:1.27-alpine
nginx:1.27-alpine 기본 nginx.conf  log_format  main  '... "$request" ...'
                                   access_log  /var/log/nginx/access.log  main;   ← 켜져 있음
                                   include /etc/nginx/conf.d/*.conf;              ← BTS 설정 착지점
컨테이너 /var/log/nginx/access.log -> /dev/stdout                                  ← 표준출력 심볼릭
infra/prod/Dockerfile.web:10       COPY infra/prod/nginx.conf /etc/nginx/conf.d/bts.conf
infra/prod/nginx.conf (54줄)       access_log · log_format 재정의 0건              ← 상속 확정
```

`log_format main` 의 `$request` 는 **요청 첫 줄 통째**(`"GET /ical/feed/<원문토큰>.ics HTTP/1.1"`, 쿼리 포함).
⇒ **매 요청마다 컨테이너 표준출력으로 평문 출력** → `docker logs` · 중앙 로그 수집기로 그대로 적재.

**메서드·상태코드 무관, 정상 요청(200)에서도 상시 발생.** 오류일 필요가 없다.
iCal 은 캘린더 클라이언트가 주기 폴링하므로 동일 토큰이 반복 적재된다.

### 영향 경로 (2026-07-26 조사 §전수 열거 — `SecurityConfig.kt:177-238` + `INBOUND_WEBHOOK_PATHS:345-356`)

경로 세그먼트에 비밀값 — **4개** (체크포인트가 2개로 알던 것의 정정)

| # | 경로 | permitAll 메서드 | 선언 |
|---|---|---|---|
| R1 | `/api/v1/public/dashboards/{token}` | GET | `SecurityConfig.kt:209` |
| R2 | `/ical/feed/{token}.ics` | GET | `SecurityConfig.kt:213` |
| R3 | `/api/v1/webhooks/git/{token}` | POST | `INBOUND_WEBHOOK_PATHS` |
| R4 | `/api/v1/automation/webhooks/{token}` | POST | `INBOUND_WEBHOOK_PATHS` |

쿼리 문자열에 비밀값 — **2개**

| # | 경로 | 비밀 파라미터 |
|---|---|---|
| Q1 | `/slack/install/callback` | `code` |
| Q2 | OIDC 콜백 (`/login/oauth2/code/{regId}`) | `code` · `state` |

nginx 쪽 라우팅은 `infra/prod/nginx.conf:40` 의 단일 정규식 location
(`^/(api|\.well-known|ical|slack|saml2|oauth2|login/(oauth2|saml2))(/|$)`) 이 6경로를 모두 덮는다.

### classify 결과

- type: `auth` (fast-track 아님 — domain·spec·review-plan 전 단계 수행)
- agent: `security-engineer`
- slug: `nginx-access-log-path-token-masking`

### ★이 작업의 고유 난점 — 선례 부재

`Maxi_wiki/BTS/learnings.md` 792줄에 **인프라/nginx/도커 관련 교훈 0건.**
이 프로젝트에서 **인프라 설정만 바꾸는 PR 은 최초**다. 따라서.

1. **기존 검증 파이프라인이 이 파일을 안 탄다.** Gradle(`./gradlew test`)도 vitest 도 nginx.conf 를 읽지 않는다.
   메모리 `no-backend-ci-and-assembly-merge-verification-traps` — 백엔드 CI 자체가 부재.
2. **"봉인 테스트를 무엇으로 거는가" 가 스펙의 핵심 질문**이 된다. 후보는 spec 단계에서 결정.
3. 하드코딩 경로 목록은 눈가리개가 된다. 메모리 `spec-stated-count-becomes-blindfold` ·
   `orchestrator-instruction-counts-are-blindfolds` — 봉인은 **판별식**으로, 목록으로 하지 않는다.

### 관련 메모리 (구현 전 필독)

- `path-token-leak-surface-four-findings-2026-07-26` — 이번 트랙 진실출처 (N1~N4)
- `fr-db-03-public-dashboard-error-instance-token-leak-done` — #310. 처방 단일지점화 원칙
- `negative-guard-needs-body-discriminator` — 음성 가드는 본문 판별자 필요
- `spec-stated-count-becomes-blindfold` · `orchestrator-instruction-counts-are-blindfolds`

## 도메인 정리

### BC 판정

- **BC: 해당 없음 — 인프라 전용.** 변경 대상은 `infra/prod/nginx.conf` 단일 파일이며 어떤 백엔드 모듈도
  건드리지 않는다. classify 가 `primary_bc=identity-access` 로 추정한 것은 **오분류**(제목의 "토큰" 키워드
  때문). BC 격리 규칙(한 PR = 한 BC)은 이 PR 에 적용되지 않는다 — 위반이 아니라 **대상 밖**이다.
- 다만 **영향은 cross-BC 다.** 마스킹 대상 경로가 notification · identity-access · automation ·
  slack-integration · issue-tracking(SPA 라우트) 에 걸쳐 있다. 코드 변경이 아니라 **관측 표면**의 교차다.

### 새 용어

**0건.** 관련 용어는 이미 `glossary.md` 에 등재돼 있다.

| 용어 | 위치 | 정의 요지 |
|---|---|---|
| 공유 토큰 (Dashboard Share Token) | glossary L34 | 불투명(opaque) 토큰, DB 는 SHA-256 hex 만 저장 |
| 캘린더 피드 토큰 (Calendar Feed Token) | glossary L35 | 불투명 토큰, 외부 캘린더 앱 구독용 |

⇒ glossary 갱신 불필요. **"불투명 토큰 = 소지만으로 인증"** 이라는 기존 정의가 이 작업의 위험 근거다
(소지자가 곧 권한자이므로 로그에 남은 원문은 그대로 자격증명이다).

### ★기존 결정 대조 — 선행 ADR 이 이 위험을 이미 명시했다

`docs/decisions/2026-07-25-public-dashboard-error-instance-sanitization.md:128-129` (#310, 어제) §잔여 위험 1.

> **경로 기반 토큰 설계 자체.** 토큰이 URI 에 있는 한 브라우저 히스토리·리퍼러·**웹서버 접근 로그**에는
> 계속 남는다. 이는 선행 ADR D1 이 택한 설계이며 **본 결정의 범위 밖**이다. 본 결정은 **응답 본문**만 다룬다.

**⇒ 본 작업의 도메인적 기여는 "새 위험 발견" 이 아니라 "분류의 번복" 이다.**
선행 ADR 은 이것을 *경로 기반 토큰 설계의 불가피한 귀결*로 분류했다. 본 작업은 그 셋 중
**웹서버 접근 로그만은 URL 설계를 바꾸지 않고 인프라 계층에서 봉합 가능함**을 보인다.

| 선행 ADR 이 묶어서 "불가피" 로 분류한 3종 | 본 작업 이후 |
|---|---|
| 브라우저 히스토리 | 여전히 설계 귀결 (클라이언트 소관, 봉합 불가) |
| 리퍼러 | **완화 가능** — `Referrer-Policy` 헤더 미설정 상태. 인접 후속으로 등재(범위 밖) |
| **웹서버 접근 로그** | **본 PR 에서 봉합** — `log_format` 은 URL 설계와 직교 |

`docs/decisions/` 전체에 `nginx`·`access_log` 언급 **0건** → 로그 계층에 대한 선행 결정 자체가 없다.

### 조사가 해소한 선행 ADR 잔여 위험

어제 ADR 의 잔여 위험 4건 중 3건이 2026-07-26 조사로 판정됐다.

| 선행 ADR 잔여 위험 | 조사 결과 |
|---|---|
| 1. 웹서버 접근 로그 | **실증 확정 + 봉합 가능** → 본 PR (N1) |
| 2. `IcalFeedController` 미봉합 | **기각** — identity-access 에 `ProblemDetail` 0건, 동형 결함 없음 |
| 3. 비-GET 응답 형태 미확인 | **기각**(익명은 빈 401). 단 **인증 요청**에서 별개 결함 발견 → N2 로 등재 |
| 4. 교차모델 검증 부재 | **여전히 유효** — `codex` 미설치. 본 PR 에서도 동일 편향 잔존 |

### ★열거 정정 — 6경로가 아니라 최소 7경로

Brief 작성 시점의 목록(경로 4 + 쿼리 2)은 **API 만 보고 SPA 라우트를 누락**했다.

| 추가 | 경로 | 성격 |
|---|---|---|
| **R5** | `/dashboards/shared/{token}` (`router.ts:586`) | **프론트 SPA 라우트.** nginx `location /` SPA 폴백이 서빙. 사람이 브라우저로 직접 여는 경로라 **트래픽이 가장 많고**, 브라우저 히스토리·리퍼러에도 동시 적재된다 |

`?embed=1` 은 불리언 플래그이지 토큰이 아니다(`router.ts:589-591`) — 토큰은 **경로 세그먼트**에 있다.

**⇒ 이 누락이 봉인 설계의 근거다.** 하드코딩 경로 목록은 그 자체로 눈가리개이며, 내가 방금 실제로 걸렸다.
봉인은 **"비밀값을 경로/쿼리에 싣는 경로를 코드에서 파생 열거하고, 마스킹 미적용을 실패시키는 판별식"** 으로 건다.
메모리 `spec-stated-count-becomes-blindfold` · `orchestrator-instruction-counts-are-blindfolds`.

### 기존 결정 충돌

**없음.** 선행 ADR 의 "범위 밖" 선언은 *충돌*이 아니라 *범위 이관*이다. 본 PR 이 그 범위를 이어받는다.
선행 ADR D1(경로 기반 토큰 설계)은 **유지된다** — 본 작업은 URL 설계를 바꾸지 않는다.

### ADR 필요 여부

**필요.** 사유 2가지.
1. 선행 ADR 이 "불가피" 로 분류한 항목의 **분류를 번복**한다 (문서 간 정합을 위해 명시 필요).
2. **관측성 ↔ 비밀유지 균형**을 어디에 둘지가 새 결정이다 (로그를 끌 것인가 / 마스킹할 것인가 /
   무엇을 남길 것인가). 이는 spec 단계에서 확정 후 `docs/decisions/2026-07-26-nginx-access-log-token-masking.md` 로 기록.

### 인접 미해결 등재 (본 PR 범위 밖)

- **`Referrer-Policy` 헤더 미설정.** `infra/prod/nginx.conf` · `apps/web/index.html` 모두 0건.
  R5(브라우저로 여는 공유 뷰)에서 토큰이 외부 사이트 리퍼러로 샐 수 있다. **별도 등재.**
- **브라우저 히스토리.** 클라이언트 소관, 봉합 불가. 선행 ADR 판단 유지.

### 워크플로우 편차 (게이트 1 에서 Maxi 확인 필요)

- **`grill-with-docs` 미실행.** 사유 — 도메인 질문(이 위험이 유효한 결정 사안인가)이 선행 ADR 실물
  대조로 이미 결론났고, 신규 용어 0건이며, 실질 설계 질문(마스킹 방식·봉인 수단)은 도메인이 아니라
  spec 소관이다. #310 에서도 동일 사유로 Maxi 가 생략을 승인한 선례가 있다(D3=A).
  **이의 있으면 게이트 1 에서 되돌릴 수 있다.**

## 스펙

전체 스펙. [docs/specs/2026-07-26-nginx-access-log-path-token-masking.md](../specs/2026-07-26-nginx-access-log-path-token-masking.md)

### ★핵심 결정 — 경로 목록 없이 구조적 판별식으로 마스킹한다

전 토큰이 32바이트 CSPRNG 이고 인코딩만 다르다(실측).

| 대상 | 길이 |
|---|---|
| 최소 토큰 (base64url no-pad) | **43자** |
| 최대 토큰 (소문자 hex) | 64자 |
| **최대 정상 식별자 (UUID)** | **36자** |

**43 > 36 이라 길이만으로 완전 분리된다** ⇒ 하드코딩 경로 목록이 불필요하다.
경로에서 `[A-Za-z0-9_-]` 연속 **40자 이상** 세그먼트를 `***` 로 치환한다(임계값 40 = 36 과 43 사이).
새 토큰 경로가 추가돼도 **설정 갱신 없이 자동 마스킹**된다(S5).

### 핵심 시나리오 3줄

- 캘린더 앱이 주기 폴링할 때마다 남던 원문 토큰이 `GET /ical/feed/***.ics 200` 으로 바뀐다
- 공유 링크를 브라우저로 열 때 SPA 라우트·API·**Referer 헤더** 세 곳 모두 마스킹된다
- 운영자는 메서드·상태·**응답시간·업스트림시간**(신규)으로 장애를 그대로 조사할 수 있다

### 요구 7건

RQ-1 경로 구조적 마스킹 · RQ-2 쿼리 값 미로깅(일반 규칙) · RQ-3 관측성 보존 ·
**RQ-4 Referer 마스킹** · **RQ-5 3축 봉인(길이·알파벳·설정실효)** · RQ-6 문법 검증 · RQ-7 실효 검증

### ★게이트 1 에서 Maxi 확정 필요 2건

- **D1 — `error_log` 를 어떻게 다룰 것인가.** nginx 는 **error_log 포맷 커스터마이즈를 지원하지 않는다.**
  오류 항목에 `request: "GET /path…"` 로 URI 가 실린다. 접속 로그와 달리 오류 시에만 발생하지만
  **본 PR 로 완전 봉합되지 않는다.** (A) 범위 밖 등재 (B) 로그 레벨 상향 — 장애 조사 능력과 상충 (C) 수집 경로 분리
- **D2 — RQ-4(Referer 마스킹) 포함 여부.** 포함이 기본안. 제외 시 공유 링크 시나리오가 반쪽 봉합

## Brainstorming Check

✅ **통과 (1회 iteration).** 중대 gap 3건 + 경미 2건 발견 → 전부 스펙 반영.

| gap | 내용 | 처리 |
|---|---|---|
| **G1** | E3(다중 토큰 전부 치환)이 `map` 으로 **구현 불가** — nginx map 은 전역 치환을 못 한다 | 검사 가능한 불변식(다중 비밀값 라우트 부재 단언)으로 대체 |
| **G2** | 봉인이 **봉인 대상을 안 봤다** — `map` 블록을 지워도 길이 검사는 통과 | RQ-5 에 축 C(설정 실효) 추가 |
| **G3** | **알파벳 가정 미검사** — 표준 base64(`+`·`/`)·JWT(`.`) 토큰은 세그먼트가 쪼개져 마스킹을 빠져나가는데 길이 검사는 통과 | RQ-5 에 축 B(알파벳) 추가 |
| **G4** | RQ-6·RQ-7 의 **실행 주체 미정** — 자동화 안 되면 1회성으로 썩는다 | plan 단계에서 확정 필수 |
| **G5** | 완료기준의 "5개 토큰 형식" 부정확 (5 라우트 / 2 형식) | 완료기준 정정 |

**반증했으나 gap 아니었던 것.** UUID 오탐(36<40, 게다가 fail-safe 방향) · 퍼센트 인코딩 우회(`$uri` 는
디코딩된 값) · 공격자 우회 프레임(위협 모델은 정상 토큰의 실수 적재이지 조작 입력이 아니다).

**편차.** `superpowers:brainstorming` 은 설계 **생성** 스킬이라 완성 스펙 비평에 형식이 안 맞고
종착점(`writing-plans`)이 `bts-plan` 과 충돌한다. 그 스킬의 `Spec Self-Review` 절만 적용했다. #310 동일 판단.

## Plan

### ★G4 해결 — 검증 실행 주체 확정 (plan 의 최우선 결정)

조사 결과 이 레포의 실제 상태.

| 후보 | 실물 | 판정 |
|---|---|---|
| `scripts/verify/` | 존재. `bootjar-no-fakes.sh` (레포 불변식 검사 셸 스크립트) **선례 1건** | ✅ **봉인의 집** |
| `.github/workflows/` | `frontend-ci.yml` **1개뿐**. 경로 필터가 `apps/web/**` 라 **`infra/**` 변경에 안 돈다** | ⚠️ 신규 워크플로우 필요 |
| Gradle · vitest | `nginx.conf` 를 읽지 않음 | ❌ |
| docker | 로컬·GitHub Actions 양쪽 사용 가능 (`nginx:1.27-alpine` ~20MB) | ✅ RQ-6·RQ-7 실행기 |

**확정.**
1. 봉인 3축 + 문법·실효 검증을 **`scripts/verify/nginx-log-masking.sh`** 단일 스크립트로 만든다
   (`bootjar-no-fakes.sh` 와 동일 패턴 — 실행 가능 셸, 종료코드로 판정).
2. **`.github/workflows/infra-ci.yml`** 을 신설해 자동 실행한다.
   경로 필터는 `infra/**` **+ `backend/**`** — 축 A·B 가 **Kotlin 토큰 발급기 소스를 읽기** 때문에,
   `infra/**` 만 걸면 *토큰을 짧게 바꾸는 변경* 이 검사를 우회한다(S6 가 정확히 그 시나리오다).
3. 자동화 없이 로컬 1회 확인으로 끝내지 않는다 — **G4 가 경고한 실패 양식이고,
   선행 #310 ADR 의 "범위 밖" 항목이 3주 잠복한 것이 같은 양식이다.**

### ★기전 실측 완료 — 계획 확정 전에 핵심 위험을 닫았다 (2026-07-26)

"nginx `map` 단일 정규식으로 이 마스킹이 되는가" 는 **의견을 구할 문제가 아니라 돌려보면 되는 문제**라,
`nginx:1.27-alpine` 컨테이너로 직접 검증했다. **7개 요구가 한 번에 전부 통과**했다.

검증한 설정 (구현 원안 그대로).
```nginx
map $uri $bts_masked_uri {
    default                             $uri;
    "~^(.*/)[A-Za-z0-9_-]{40,}(.*)$"    "$1***$2";
}
map $http_referer $bts_masked_referer {
    default                             $http_referer;
    ""                                  "-";
    "~^(.*/)[A-Za-z0-9_-]{40,}(.*)$"    "$1***$2";
}
log_format bts_masked '$remote_addr "$request_method $bts_masked_uri $server_protocol" '
                      '$status $body_bytes_sent "$bts_masked_referer" "$http_user_agent" rt=$request_time';
access_log /dev/stdout bts_masked;
```

실측 결과.

| 입력 | 로그 출력 (실제) | 검증 요구 |
|---|---|---|
| `/ical/feed/<64자hex>.ics` | `/ical/feed/***.ics` | RQ-1 · **E2 확장자 보존 확인** |
| `/dashboards/shared/<43자b64url>` | `/dashboards/shared/***` | RQ-1 · **SPA 라우트(R5) 커버 확인** |
| `/api/v1/issues/<UUID 36자>` | `3f2504e0-4f89-11d3-9a0c-0305e82c3301` **그대로** | **E4 과잉마스킹 없음 확인** |
| `/x/<39자>` (경계 미달 대조군) | 그대로 | **임계값 40 이 정확히 작동함** |
| `/slack/install/callback?code=…&state=…` | `/slack/install/callback` (**쿼리 전체 소실**) | **RQ-2 목록 없이 자동 충족 확인** |
| `Referer: …/shared/<43자>` | `…/shared/***` | **RQ-4 확인** |
| 전 항목 | 메서드·상태·바이트·UA·`rt=` 전부 잔존 | **RQ-3 관측성 보존 확인** |

`nginx -t` 문법 검증도 통과(RQ-6 수단 실증).

**⇒ G1(다중 치환 불가)을 제외한 설계 전제가 전부 실증됐다. 남은 불확실성은 "구현 가능한가" 가 아니라
"회귀를 어떻게 막는가"(봉인) 다.** Task 3 은 이 검증된 설정을 옮기는 작업이 된다.

⚠️ **여백이 좁다는 사실도 함께 확인됐다.** 임계값 40 · 최대 정상 식별자 36 · 최소 토큰 43 —
양쪽 여백이 각각 4자·3자뿐이다. **봉인 축 A(길이) 가 장식이 아니라 하중을 받는 이유가 이것이다.**

### TDD 적용 방식 (대상이 Kotlin/TS 가 아니라 nginx 설정)

`red → green → refactor` 를 다음으로 사상한다.

- **RED** — 봉인 스크립트를 **먼저** 작성하고 현재 `nginx.conf` 에 대해 돌려 **실패를 확인**한다
- **GREEN** — `nginx.conf` 를 고쳐 통과시킨다
- **REFACTOR** — 주석·구조 정리 (동작 불변)

**커밋 순서 강제** — `test:` 커밋(봉인 스크립트)이 `feat:` 커밋(nginx.conf)보다 **먼저** 와야 한다.

---

### Task 1. 봉인 스크립트 — 축 A(길이)·축 B(알파벳) 파생 검사

**메타**.
- agent: `security-engineer`
- files: [`scripts/verify/nginx-log-masking.sh`]
- depends-on: []

**RED**. 스크립트를 작성하고 즉시 실행. 축 A·B 는 **현재 통과해야 정상**이다(토큰이 이미 43자 이상,
base64url/hex 둘 다 판별 문자군 안). 통과하는 것 자체가 정상이므로 **red 는 위반 주입으로 만든다**
(Task 4). 이 Task 의 red 판정은 **"파생 개수 하한 5 미만이면 실패"** 로 확인한다 — 파생기를 일부러
빈 디렉토리로 향하게 해 `0건 → 실패` 를 눈으로 본다.

**GREEN**. 다음을 만족하는 스크립트.
- 토큰 발급 지점을 **코드에서 파생 열거**한다 (하드코딩 경로 목록 금지).
  파생 기준 = `TOKEN_BYTES` 상수 + 인코딩 호출(`Base64.getUrlEncoder`·`%02x` hex)을 갖는 발급기
- 각 발급기의 **출력 길이**를 바이트수·인코딩으로 계산해 **임계값 40 초과**를 단언 (축 A)
- 각 발급기의 **인코딩 알파벳 ⊆ `[A-Za-z0-9_-]`** 를 단언 (축 B) — 표준 base64(`+`·`/`)·JWT(`.`) 거부
- **미분류(길이나 알파벳을 판정할 수 없는 발급기)는 실패**
- **파생 개수 하한 5** 단언
- **다중 비밀값 라우트 부재** 단언 (E3 대체 조건)

**REFACTOR**. 임계값·하한을 파일 상단 상수로. 각 단언에 실패 사유 한 줄 출력.

**검증**. `bash scripts/verify/nginx-log-masking.sh` → 축 A·B 구간 통과, 축 C 구간 **실패**(아직 미구현)

---

### Task 2. 봉인 스크립트 — 축 C(설정 실효) + RQ-6 문법 검증

**메타**.
- agent: `security-engineer`
- files: [`scripts/verify/nginx-log-masking.sh`]
- depends-on: [1]

**RED**. 축 C 를 추가하고 실행 → **현재 `nginx.conf` 에 마스킹이 없으므로 실패한다.**
**이것이 이 PR 의 진짜 red 다.** 실패 메시지 예상 — `access_log 재정의 없음 — 이미지 기본 main 포맷 상속`

**GREEN**. (Task 3 에서 `nginx.conf` 를 고쳐 통과시킨다. 이 Task 는 red 를 만드는 것까지.)
- 축 C 검사 내용. `infra/prod/nginx.conf` 가 (a) `log_format` 을 정의하고 (b) `access_log` 로 그것을
  명시 지정하며 (c) `$request`·`$request_uri`·`$args` 를 **로그 포맷에 쓰지 않고** (d) `map` 마스킹
  변수를 경로와 **Referer 양쪽에** 사용하는지
- RQ-6. `docker run --rm nginx:1.27-alpine nginx -t` 동형으로 **문법 파싱 검증**
  (설정을 컨테이너에 마운트해 `-t` 실행. 실패 시 즉시 종료 — NFR-2 가용성 요구)

**REFACTOR**. docker 미설치 환경에서 축 C 를 `SKIP` 이 아니라 **명시적 실패**로 처리
(조용한 스킵은 vacuous 통과 경로다).

**검증**. `bash scripts/verify/nginx-log-masking.sh` → **축 C 실패로 종료코드 비0** (기대된 red)

---

### Task 3. `nginx.conf` 마스킹 구현 (GREEN)

**메타**.
- agent: `security-engineer`
- files: [`infra/prod/nginx.conf`]
- depends-on: [2]

**GREEN**. `infra/prod/nginx.conf` 에 추가.
- `map $uri $bts_masked_uri` — `[A-Za-z0-9_-]` 연속 40자 이상 세그먼트를 `***` 로 치환.
  앵커를 붙여 백트래킹 억제 (NFR-1)
- `map $http_referer $bts_masked_referer` — 동일 판별식 (RQ-4, Maxi 확정)
- `log_format bts_masked` — 시각·`$remote_addr`·`$request_method`·**`$bts_masked_uri`**·
  `$server_protocol`·`$status`·`$body_bytes_sent`·**`$bts_masked_referer`**·`$http_user_agent`·
  `$http_x_forwarded_for`·**`$request_time`**·**`$upstream_response_time`**
  ⚠️ `$request`·`$request_uri`·`$args` **금지** (RQ-2 는 이것만으로 자동 충족)
- `access_log /var/log/nginx/access.log bts_masked;` — 이미지 기본 상속을 끊는다

**REFACTOR**. 각 블록에 **왜 이렇게 하는지** 한글 주석. 특히 임계값 40 의 근거(UUID 36 < 40 < 토큰 43)를
숫자와 함께 남긴다 — 나중에 누가 "왜 40?" 하고 바꾸지 못하게.

**검증**. `bash scripts/verify/nginx-log-masking.sh` → **3축 전부 통과**(green)

---

### Task 4. 위반 주입 — 봉인이 실제로 잡는지 확인 (3축 각각)

**메타**.
- agent: `security-engineer`
- files: [`scripts/verify/nginx-log-masking.sh`]
- depends-on: [3]

**RED→GREEN 검증 (뮤테이션)**. 각 축에 위반을 주입해 **반드시 실패하는지** 확인한 뒤 원복한다.
**커밋된 상태에서만 수행**한다 (메모리 `mutation-test-requires-committed-baseline` — 미커밋 상태의
`git checkout --` 원복은 작업 소실이다).

| 주입 | 기대 |
|---|---|
| M-A. 토큰 `TOKEN_BYTES` 를 32 → 8 로 (base64url 11자) | **축 A 단독 실패** |
| M-B. 발급 인코딩을 `getUrlEncoder` → `getEncoder`(표준 base64, `+`·`/`) | **축 B 단독 실패** |
| M-C. `nginx.conf` 의 `access_log` 한 줄 삭제 | **축 C 단독 실패** |
| M-D. `log_format` 에서 `$bts_masked_referer` → `$http_referer` 로 되돌림 | **축 C 실패** (RQ-4 회귀 감지) |
| M-E. 파생기를 빈 경로로 향하게 (0건 파생) | **하한 단언 실패** |

**판별력 대조군.** M-A 에서 **축 B·C 는 green 이어야 한다.** 하나 건드렸는데 전부 red 면 과잉결합이고,
축이 서로를 가리고 있다는 뜻이다 (#310 의 뮤테이션 D 와 동일한 대조 설계).

**검증**. 5종 주입 결과가 위 표와 일치. 원복 후 3축 전부 green.

---

### Task 5. RQ-7 실효 검증 — 실제 요청을 흘려 로그 전문 확인

**메타**.
- agent: `security-engineer`
- files: [`scripts/verify/nginx-log-masking.sh`]
- depends-on: [3]

**RED**. 마스킹 **이전** 설정(`git show HEAD~1:infra/prod/nginx.conf` 동형)으로 컨테이너를 띄우고
토큰이 담긴 요청을 흘리면 **로그에 원문 토큰이 나타난다** — 대조군.

**GREEN**. 새 설정으로 동일 요청 → **로그 전문에 원문 토큰 0회.**
- 표본. **5 라우트 × 2 인코딩**(base64url 43자 · hex 64자) — 실제 형식의 더미 토큰 생성
- 검사 축 2개. **경로**(`$bts_masked_uri`) · **Referer 헤더**(`$bts_masked_referer`)
- **판별자 = 로그 출력 문자열에 원문 토큰 부분문자열이 존재하지 않을 것.**
  상태코드나 `***` 존재는 판별자가 아니다 (메모리 `negative-guard-needs-body-discriminator` —
  "여전히 401" 류의 vacuous 단언 금지)
- **역방향 단언(RQ-3).** 같은 로그 줄에 메서드·상태코드·`$request_time` 이 **남아 있음**을 확인.
  마스킹이 관측성을 죽이지 않았다는 증거

**REFACTOR**. 더미 토큰을 상수로, 실패 시 **로그 전문을 출력**해 사후 조사가 가능하게.

**검증**. 대조군에서 토큰 발견(red 재현) → 신규 설정에서 0회(green) → 관측성 필드 전부 존재

---

### Task 6. CI 배선 — `infra-ci.yml` 신설 (G4 자동화)

**메타**.
- agent: `security-engineer`
- files: [`.github/workflows/infra-ci.yml`]
- depends-on: [4, 5]

**RED**. 워크플로우 없이 `infra/prod/nginx.conf` 를 바꾸면 **아무 검사도 돌지 않는다**(현행).
`frontend-ci.yml` 의 경로 필터가 `apps/web/**` 뿐임을 근거로 제시.

**GREEN**. `.github/workflows/infra-ci.yml`.
- 트리거. `pull_request` + `push`, **경로 필터 `infra/**` · `backend/**` · 워크플로우 자신**
  ⚠️ **`backend/**` 를 반드시 포함** — 축 A·B 가 Kotlin 토큰 발급기를 읽으므로,
  `infra/**` 만 걸면 *토큰을 짧게 바꾸는 변경*(S6)이 검사를 통과해 버린다
- 잡. `ubuntu-latest` 에서 `bash scripts/verify/nginx-log-masking.sh` 단일 실행 (docker 내장)

**REFACTOR**. 워크플로우 상단에 **왜 `backend/**` 가 필터에 있는지** 주석 (지우면 S6 가 뚫린다).

**검증**. PR 에서 잡이 실행되고 통과. `infra/prod/nginx.conf` 를 건드리는 커밋에서 트리거 확인

---

### Task 7. ADR + 문서 동기화

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-26-nginx-access-log-token-masking.md`, `docs/plans/2026-07-26-nginx-access-log-path-token-masking.md`]
- depends-on: [6]

**작업**.
- ADR 작성. **선행 #310 ADR §잔여 위험 1 의 분류를 번복**한다는 점을 명시적으로 기록
  (3종 중 접근 로그만 인프라 계층에서 봉합 가능, 나머지 2종은 설계 귀결 유지)
- **잔여 위험 등재 — `error_log`(D1=A 확정).** nginx 가 포맷 커스터마이즈를 지원하지 않아 마스킹 불가.
  ⚠️ **"범위 밖" 으로만 적고 끝내지 않는다** — 선행 ADR 이 그렇게 적은 항목이 3주 잠복해 이 PR 이 됐다.
  **후속 작업으로 명시 등재**하고 메모리에도 남긴다
- **인접 미해결 등재.** `Referrer-Policy` 헤더 부재(브라우저가 외부 사이트로 토큰을 흘릴 수 있음)
- **FR 카운트 129 불변** 확인 — `bash scripts/verify-master-plan.sh` 통과

**검증**. `verify-master-plan.sh` 종료코드 0

---

## Plan 메타

- **task 수**. 7
- **wave 구성**. T1 → T2 → T3 → {T4, T5 병렬} → T6 → T7 (T4·T5 는 `files` 가 같은 스크립트라 실제로는 직렬화됨 → 실질 7 wave)
- **TDD 강제**. yes — `test:` 커밋(T1·T2 봉인 스크립트)이 `feat:` 커밋(T3 nginx.conf)보다 **먼저**
- **변경 파일 3개**. `infra/prod/nginx.conf`(수정) · `scripts/verify/nginx-log-masking.sh`(신규) ·
  `.github/workflows/infra-ci.yml`(신규) + 문서 2
- **백엔드 코드 0 · 프론트 코드 0 · 마이그레이션 0 · 신규 의존성 0 · FR 카운트 129 불변**
- **추가 검증**. `verify-master-plan.sh`. ktlint/detekt/vitest 는 **대상 파일 없음**(변경이 Kotlin/TS 아님)

### 워크플로우 편차 (게이트 1 확인 대상)

**`superpowers:writing-plans` 미호출.** `bts-plan` SKILL 이 요구 형식(메타 블록 `agent`/`files`/
`depends-on` + RED/GREEN/REFACTOR/검증)을 **그 자리에 전문으로 명시**하고 있어 그 형식대로 직접 작성했다.
누적 편차 4건째이므로 게이트 1 에서 **일괄 확인 대상**이다 (앞의 3건 — grill-with-docs · office-hours ·
brainstorming 은 형식 불일치가 사유였고 이번은 형식이 이미 주어져 있다는 사유다).

## 리뷰 결과

### plan-eng-review (2026-07-26) — 발견 3건, Maxi 확정 = 2건 이번 PR 반영

**Step 0 스코프 챌린지.** 변경 파일 3(+문서) · 신규 클래스/서비스 0 → **복잡도 게이트 미발동**, 스코프 그대로.

**[P1] (확신 9/10) 발견 1 — 배포 시점 게이트 부재. → 반영 확정.**
`NFR-2` 가 "설정 오류 시 nginx 기동 실패 → 프론트 전체 사망" 을 명시하는데 `RQ-6` 문법 검증이 **CI 에서만**
돈다. CI 를 우회하거나 CI 없이 이미지를 빌드하면 깨진 설정이 그대로 배포되고, Docker Compose 단일 호스트라
컨테이너가 안 뜨면 프론트가 통째로 내려간다.
**처방** — `infra/prod/Dockerfile.web` 에 `RUN nginx -t`. 깨진 설정이 **배포가 아니라 이미지 빌드에서** 터진다.
→ **Task 8 신설.**

**[P1] (확신 8/10) 발견 2 — 임계값 40 이 두 곳에 하드코딩. → 반영 확정.**
숫자 40 이 `nginx.conf` 정규식과 봉인 스크립트 축 A **양쪽에** 나타난다. 어긋나면 —
누가 nginx 를 50 으로 올리고 스크립트는 40 으로 두면 — **봉인은 "토큰 43 > 40 통과" 라 하는데 실제 nginx 는
43자를 안 가린다.** 자물쇠가 존재하지 않는 문을 점검하게 된다.
**처방** — 봉인 스크립트가 임계값을 **`nginx.conf` 에서 파싱**해 쓴다. 하드코딩 금지, 파싱 실패 시 실패.
→ **Task 1 수정.**

**[P3] (확신 7/10) 발견 3 — 판별식 중복.** 같은 정규식이 경로 map · Referer map 두 곳에 나온다.
nginx `map` 은 정규식 공유를 지원하지 않아 **구조적으로 불가피**하다. 발견 2 의 파싱이 부분적으로 커버한다
(스크립트가 두 map 의 정규식이 동일한지 대조). 별도 조치 없음.

**성능 — 이슈 0건.** `map` 은 기동 시 컴파일, 요청당 앵커된 정규식 2회. CI docker pull 20MB.

**NOT in scope (명시 이관).**
- `error_log` 유출 — D1=A 확정, ADR 잔여 위험 + 후속 등재
- `Referrer-Policy` 헤더 부재 — 인접 결함, 별도 등재
- 브라우저 히스토리 — 클라이언트 소관, 봉합 불가
- 토큰을 경로에서 헤더로 옮기는 재설계 — 선행 ADR D1 뒤집기, 범위 밖

**What already exists (재사용).**
- `scripts/verify/bootjar-no-fakes.sh` — 봉인 스크립트의 형식·배치 선례. 새 패턴 만들지 않고 그대로 따른다
- `infra/prod/nginx.conf:3` `map $http_upgrade` — `map` 사용 선례. 새 문법 도입 아님
- `PublicDashboardErrorTokenLeakTest.kt` — **검사 기법만** 차용(raw 바이트 · 실패사유 출력). 하네스는 이식 불가

**편차.** 스코프 게이트(리뷰 대상 확인) 미호출 — 대상이 절대경로로 이미 지정돼 있어 재질의가 무의미.
외부 모델 교차검증(codex) 미실행 — `codex` CLI 미설치(#308·#309·#310 동일). **구현자 편향 잔존.**

---

### Task 8. 배포 게이트 — Dockerfile 문법 검증 (리뷰 발견 1)

**메타**.
- agent: `security-engineer`
- files: [`infra/prod/Dockerfile.web`]
- depends-on: [3]

**RED**. 현재 Dockerfile 은 `COPY nginx.conf` 만 하고 검증하지 않는다 → 깨진 설정도 이미지가 빌드된다.
일부러 깨진 설정으로 빌드해 **성공해 버리는 것**을 확인(= red).

**GREEN**. `COPY infra/prod/nginx.conf …` **다음 줄**에 `RUN nginx -t`.
깨진 설정이면 이미지 빌드가 실패한다.

**검증**. 정상 설정 → 빌드 성공. 일부러 깨뜨린 설정 → **빌드 실패**(양방향 확인)
