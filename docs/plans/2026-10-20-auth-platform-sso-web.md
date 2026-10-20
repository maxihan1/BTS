# Auth_platform SSO 연결 — web (SSO 착지 세션 복구 · LOCAL 꺼짐 로그인 화면 · 계정 설정 링크)

티어: T2
타입: auth

BTS 를 사내 통합 로그인 Auth_platform 의 OIDC client 로 붙이는 web 쪽이다 (FR-AU-04 · 백엔드는 #507).
1. SSO 성공 핸들러는 `refresh_token` cookie 만 굽고 `/dashboard` 로 보내는데, SPA 는 sessionStorage 에 access token 이 없으면 바로 `/login` 으로 튕긴다 (`routeGuard.ts:74-82`) → 앱 기동 때 token 이 없으면 refresh 를 한 번 부른다 (`requireAuth` 를 async 로 바꾸면 모든 가드 · test 가 바뀐다).
2. Vite dev·preview 프록시가 `/oauth2` · `/login/oauth2` 를 안 넘긴다 → 운영 nginx(`infra/prod/nginx.conf:138`)와 같은 경로를 Host 를 지켜 넘긴다 (`{baseUrl}` = 5173).
3. 관리자가 LOCAL 을 끄면 providers 가 `[]` 인데 SPA 가 빈 목록을 LOCAL 로 바꿔(`LOCAL_FALLBACK`) 비밀번호 칸이 남고 제출마다 401 → 빈 목록이면 비밀번호 칸을 숨긴다. 요청 실패 때 LOCAL 기본값(EC-06-06)은 그대로.
4. 비밀번호 · MFA 는 Auth_platform 이 맡는다 → 빌드 env `VITE_AUTH_ACCOUNT_URL` 이 있으면 `/settings/password` · `/settings/mfa` 가 폼 대신 Auth 계정 링크를 보여 준다. 없으면 지금 그대로.

## Jira 대조

| BTS | Atlassian Cloud | 출처 (조회 2026-10-21) |
|---|---|---|
| LOCAL 꺼짐 → 비밀번호 칸 없이 SSO 버튼만 | SSO 가 켜진 사용자는 로그인하면 바로 IdP 로 보내지고 비밀번호 로그인은 쓰지 않는다 | [Login issues related to SSO](https://support.atlassian.com/atlassian-account/docs/login-issues-related-to-single-sign-on-sso/) · [Authentication policy settings](https://support.atlassian.com/security-and-access-policies/docs/authentication-policy-settings-for-your-organizations/) |
| 비밀번호 · MFA 설정 → IdP 계정 화면 링크 | 대응 없음 — Atlassian 은 SSO 사용자의 비밀번호를 IdP 가 관리한다고만 적는다. ADS `SectionMessage`(info) 준용: 한 문장 + 링크 | 위 Authentication policy settings |

## 할 일
- [ ] 앱 기동 때 token 이 없으면 refresh 1회 — `apps/web/src/auth/restoreSession.ts` · `apps/web/src/main.tsx`
**검증**: `pnpm --filter web test -- restoreSession`
- [ ] dev·preview 프록시 `/oauth2` · `/login/oauth2` (Host 유지) — `apps/web/vite.config.ts`
**검증**: `pnpm --filter web typecheck`
- [ ] providers 빈 목록 → 비밀번호 칸 숨김 — `apps/web/src/auth/LoginForm.tsx`
**검증**: `pnpm --filter web test -- LoginForm`
- [ ] `VITE_AUTH_ACCOUNT_URL` → 비밀번호 · MFA 설정 화면을 Auth 계정 링크로 — `apps/web/src/components/auth/ExternalAccountNotice.tsx` · `routes/settings.password.tsx` · `routes/settings.mfa.tsx`
**검증**: `pnpm --filter web test -- ExternalAccountNotice`
