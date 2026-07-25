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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
