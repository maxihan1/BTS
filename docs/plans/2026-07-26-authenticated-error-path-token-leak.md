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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
