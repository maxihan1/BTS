# 인증된 요청의 `/error` 경로 토큰 유출 봉합 (N2)

> slug: authenticated-error-path-token-leak
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-07-26

## Brief

**사용자 원문.**

> 인증된 요청의 `/error` 응답에서 경로 토큰이 노출되는 문제 봉합 (N2 — identity-access BC).
> STATELESS + `RequestAttributeSecurityContextRepository` 로 JWT 요청의 SecurityContext 가
> ERROR 디스패치에서 복원되어 `/error` 의 `authenticated()` 통과 → `BasicErrorController` 가
> `includePath=ALWAYS` 로 요청 경로(토큰 포함)를 응답 본문에 노출. 익명은 안전, 인증은 유출.
> #310 의 "`/error` 를 안 열어서 안전" 전제를 깬다. PAT 는 `PatAuthenticationFilter` 가
> `saveContext` 미호출이라 **우연히** 안전 — 회귀 방지 테스트 필요.
> 검증은 슬라이스 불가, `ProdAssemblyHttpTestBase` 상속 + JDK `HttpClient`
> (`TestRestTemplate` 은 본문 있는 401 에서 터짐), dev postgres 5433 필요.

**classify 결과.**

| 항목 | 값 |
|---|---|
| type | `auth` |
| agent | `security-engineer` |
| primary_bc | `identity-access` |
| slug | `authenticated-error-path-token-leak` (classify 원본 `authenticated-request-error-path-token-leak-seal-i` 를 기존 보안 PR 관례에 맞춰 정리) |
| 브랜치 | `auth/authenticated-error-path-token-leak` |

**출처 (진실출처).**

- 메모리 `path-token-leak-surface-four-findings-2026-07-26` — 2026-07-26 읽기전용 병렬 조사 3건의 N1~N4 확정 전문. N2 항목이 이 작업의 근거
- 메모리 `fr-db-03-public-dashboard-error-instance-token-leak-done` — #310 (선행 봉합). "안전 전제" 의 출처
- 메모리 `nginx-access-log-token-masking-done` — #311 (N1, 직전 완료)
- 체크포인트 `20260726-070247-n1-nginx-log-masking-merged-311-n2-n4-remain.md`

**조사 단계에서 이미 확정된 사실 (재조사 불필요, 단 구현 전 실측 재확인 대상).**

- `BearerTokenAuthenticationFilter` 가 `saveContext` 호출 → SecurityContext 가 요청 attribute 저장 → ERROR 디스패치에서 복원
- `ErrorProperties.includePath = ALWAYS`, yml 오버라이드 0건
- `PatAuthenticationFilter` 는 `saveContext` 미호출 → **우연히** 안전
- 판별자 주의. 상태코드도 `WWW-Authenticate: Bearer` 도 판별자가 못 된다 (필터 401 과 `/error` 401 양쪽에 붙음). **유일한 판별자는 응답 본문의 토큰 문자열**
- `problem()` 헬퍼는 공유 자산이 아니다 — 3곳 전부 `private fun`, shared-kernel 공용 없음

## 도메인 정리

### BC / 영향 범위

- **BC.** `identity-access` 단일. cross-BC 변경 0 (봉합 지점이 `SecurityConfig` 또는 `ErrorAttributes` 로, 둘 다 identity-access 소관)
- **영향 엔티티.** **없음.** 신규/변경 엔티티 0, 마이그레이션 0. 도메인 모델 무영향
- **영향 구성요소.** `SecurityConfig.filterChain` 의 `/error` 경로 정책 · Spring Boot `ErrorAttributes` / `BasicErrorController` (프레임워크 기본 동작)

### ★ 착수 전 반증 결과 — 작업의 성격이 바뀐다

`SecurityConfig.kt:223-237` 에 **이 위험을 이미 알고 못 박은 20줄짜리 주석**이 있다 (FR-AT-07 PR-C T15 도입).

> `/error` 가 이 `anyRequest()` 에 걸려 authenticated 인 덕분에 `BasicErrorController` 가 실행되지 못하고
> 필터가 빈 401 을 준다 — 즉 **지금의 안전은 이 한 줄에 얹혀 있다**.

**N2 의 주장은 이 전제와 정면으로 긴장 관계다.**

| | 코드 주석(T15)의 전제 | N2 조사의 주장 |
|---|---|---|
| 익명 요청 | `authenticated()` 가 막음 → 빈 401 | **동의** (같은 결론) |
| 인증(JWT) 요청 | (언급 없음 — **측정 안 함**) | SecurityContext 가 ERROR 디스패치에서 복원 → `authenticated()` **통과** → `BasicErrorController` 실행 → `path` 유출 |

T15 의 실측은 **`form-urlencoded` 415 = 익명 요청**이었다. 인증 요청은 측정되지 않았다.
즉 **"지금의 안전"이라는 주석의 단언은 익명 표본 위에서만 검증됐다.**

→ **본 작업의 1순위 산출물은 코드 수정이 아니라 "인증 요청에서 실제로 새는가"의 실측이다.**
재현되지 않으면 봉합할 것이 없고, 재현되면 그 20줄 주석을 함께 정정해야 한다.
(#310 교훈 — "소스 리딩이 아니라 실측으로 확정". 조사 단계가 체크포인트 가설 2건을 기각한 것과 같은 이유)

### 반증 grep 결과 (2026-07-26, 이 worktree 기준)

| 확인 항목 | 결과 |
|---|---|
| `SessionCreationPolicy.STATELESS` | `SecurityConfig.kt:135` 확인 |
| `/error` 경로 정책 | 명시 매처 0건 → `anyRequest().authenticated()` (`:237`) 에 흡수 |
| `server.error.include-path` yml 오버라이드 | **0건** → `includePath` 기본값(`ALWAYS`) 유지 |
| BTS 자체 코드의 `saveContext` / `SecurityContextRepository` 호출 | **0건** → `PatAuthenticationFilter`(`:241`) 는 저장 안 함(우연한 안전) 확인. 저장 주체는 Spring 의 `BearerTokenAuthenticationFilter` 기본값(프레임워크) — **미실측** |

### 경로에 비밀값이 실리는 permitAll 경로 (SecurityConfig 실물 기준)

- `PUBLIC_DASHBOARDS_PATH` (`:209`) — 대시보드 공유 토큰
- `ICAL_FEED_PATH` (`:213`) — 캘린더 피드 토큰
- `INBOUND_WEBHOOK_PATHS` (`:219-221`) — git 웹훅 · automation 웹훅 토큰

### 새 용어 후보 (Maxi 승인 필요)

**「경로 토큰」 (Path Token).** URI **경로 세그먼트**에 원문이 실리는 불투명 자격증명.
현재 glossary 에는 개별 항목(`공유 토큰`·`캘린더 피드 토큰`)만 있고 **이들을 묶는 상위 용어가 없다.**
그래서 봉합이 매번 개별 경로 단위로 반복됐다(#310 → #311 → N2). 상위 용어가 서면 불변식을 한 줄로 쓸 수 있다 —
*"경로 토큰은 어떤 응답 본문·로그·헤더에도 원문으로 남지 않는다."*

→ glossary 등재 여부는 Maxi 승인 후. 미승인 시 스펙/코드에서 이 용어 사용 금지.

### 기존 결정과의 관계

| ADR | 관계 |
|---|---|
| `2026-07-25-public-dashboard-error-instance-sanitization.md` (#310) | **직계 선행.** 이 ADR 의 **잔여 위험 #2** ("`IcalFeedController` … Spring 기본 `/error` 응답의 `path` 필드로 샐 가능성이 있으나 **실증하지 않았다**") 가 곧 N2. 별도 PR 등재가 예고돼 있었다 |
| `2026-07-26-nginx-access-log-token-masking.md` (#311) | **형제.** 같은 유출 표면(N1). 웹서버 로그 축을 덮었고 본 건은 응답 본문 축 |
| `SecurityConfig.kt:223-237` 주석 (FR-AT-07 PR-C T15) | **정정 대상 후보.** 안전 단언의 표본이 익명 한정. 실측 결과에 따라 주석 자체를 함께 고쳐야 한다 |

**충돌 없음.** 세 결정 모두 같은 방향(경로 토큰 원문 비노출)이며, 본 작업은 그 적용 범위를 인증 요청까지 넓힌다.

### 워크플로우 편차

`grill-with-docs` 미호출. 사유 — 신규/변경 엔티티 0 · 도메인 모델 무영향으로 grilling 대상이 되는 관계/불변식이 없다.
용어 1건(「경로 토큰」)은 위에 후보로 명시해 Maxi 승인 경로로 올렸다. (게이트 1에서 Maxi 재확인 대상)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
