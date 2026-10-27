<!-- ADR — FR-API-04 PAT 발급을 Auth_platform 하나로: BTS 는 Auth PAT 를 whoami 로 확인하고 자체 발급 API 를 뺀다 (S20) -->

# ADR: PAT 는 Auth_platform 하나가 발급한다

> 결정일. 2026-10-27
> 상태. Accepted
> 컨텍스트. FR-API-04(Personal Access Token) · FR-AU-04(OIDC SSO — Auth_platform 연결)
> 대체. [2026-07-02-fr-api-04-pat-management](2026-07-02-fr-api-04-pat-management.md) 의 「BTS 셀프서비스 발급 · 목록 · 취소」 부분.
> 계획. Auth_platform `docs/superpowers/plans/2026-10-27-s20-s21-pat-login.md` §3 (S20)

## 컨텍스트

BTS · QA Hub · Device Farm 이 모두 `auth.maxihan.com` 통합계정으로 로그인한다(Auth_platform S15, BTS #507 · #571 · #573).
Device Farm 은 이미 Auth PAT 를 받는데 BTS 는 `pat_` 를 자기 `personal_access_tokens` 에서만 찾아서 Auth PAT 가 401 이었다.
Maxi 결정 — 「통합계정인데 각각 PAT 가 있는 게 더 이상하다」.

## 결정

1. **PAT 발급 · 목록 · 폐기는 Auth `/account` 하나.** BTS 의 `POST/GET/DELETE /api/v1/users/me/pats` 와 `/settings/pats` 화면을 뺀다.
2. **BTS 는 `pat_` 를 BTS DB 먼저, 없으면 Auth 로 확인한다.** `PatUserResolver.resolvePatUserId(raw)` 하나로 PAT 필터와
   header 를 직접 읽는 컨트롤러(whoami · 내 프로젝트 권한 · 내 이슈 권한)가 모두 같은 길을 쓴다.
3. **Auth 확인.** `GET {bts.auth-platform.issuer-uri}/api/v1/users/me/whoami` + `Authorization: Bearer <raw>`.
   200 · `authMethod=pat` 이면 `userId`(= Auth OIDC `sub`)로 `user_external_accounts(provider=OIDC seed, external_subject=sub)` 를 찾는다.
   SSO 로그인이 사용자를 찾는 행과 같아서 새 신원 연결은 생기지 않는다. **연결 행이 없으면 401** — 새 계정은 만들지 않는다.
4. **캐시.** Caffeine, key `sha256Hex(raw)`, 성공 5분 · 거절 1분(Device Farm identity-bts 와 같은 값). Auth 가 whoami 마다 감사 1줄을 남긴다.
   Auth 장애(5xx · 망 오류)는 기억하지 않고 401 이다 — 재시도와 거절을 구분할 필요가 생기면 503 으로 바꾼다.
5. **기존 BTS PAT 는 만료까지 산다.** `verify` · 표 · 검사 경로는 남는다. 급히 끊을 BTS PAT 는 SQL 로 폐기한다.
   표와 검사 경로는 활성 BTS PAT 가 0 이 된 뒤 따로 지운다.
6. principal 은 지금처럼 BTS 사용자 UUID 문자열 + `ROLE_PAT` 이다. `PAT_USED` 감사도 그대로 남긴다.

## 결과

- 발급 정책(scope 카탈로그 · 1..365일 · 활성 20개 상한)은 이제 Auth_platform 의 몫이다. BTS 의 `PatScopeCatalog` 와 발급 전용 repository 함수는 지웠다.
- Auth PAT 폐기가 BTS 에 닿기까지 최대 5분 걸린다(성공 캐시).
- `bts.auth-platform.issuer-uri` 가 비면 Auth 확인이 꺼진다 — 로컬 · test 는 BTS PAT 만 본다.
