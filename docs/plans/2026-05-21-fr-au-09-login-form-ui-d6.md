# FR-AU-09 인증 API를 사용하는 로그인 폼 UI (D6 작업)

> slug: fr-au-09-login-form-ui-d6
> type: ui (classify-task 보정 — 원래 `api`로 분류됐으나 사용자 승인 후 `ui`로 변경)
> agent: frontend-engineer (designer 협업)
> primary_bc: identity-access
> 생성: 2026-05-21

## Brief

### 사용자 원문

`FR-AU-09 인증 API를 사용하는 로그인 폼 UI를 만들어줘 (D6 작업)`

### 배경 (Context)

직전 PR #8 (FR-AU-09 세션/토큰 관리)가 머지되면서 백엔드 인증 API가 완성된 상태. 이제 그 API를 실제로 호출하는 **첫 화면**을 만든다 — D6 = `docs/plans/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` 의 "D6 로그인 폼 UI" 잔여 작업. 직전 세션 체크포인트 `20260521-141610-pr8-fr-au-09-auth-system-shipped.md` §Remaining Work #3 에서 우선순위 3순위로 명시.

### 활용 백엔드 API (PR #8 완성품 — 변경 없음)

- `POST /api/v1/auth/login` — username + password → access_token + refresh_token
- `POST /api/v1/auth/refresh` — refresh_token → 새 access_token (rotation)
- `POST /api/v1/auth/logout` — 세션 종료

### 작업 분류 결과

- type: `ui`
- agent: `frontend-engineer`
- primary_bc: `identity-access`
- task_count: 0 (plan 단계에서 분해)

### classify-task 보정 메모

`classify-task.ts`가 입력의 "API" 키워드를 잡고 `type=api, agent=backend-engineer`로 분류. 사용자 확인 후 `ui / frontend-engineer`로 보정. 향후 유사 케이스 회귀 가드 후보 — UI/API 키워드 동시 등장 시 우선순위 규칙 검토 (learnings 후보).

## 도메인 정리

### BC

- **identity-access** (단일).

### 영향 엔티티 (백엔드)

**없음**. 본 PR #8에서 완성된 인증 API를 호출만 한다. User / Session / RefreshToken / PersonalAccessToken / StoredPasswordCredential / AuthenticationProvider 모두 변경 없음.

### 활용 API (변경 없음)

- `POST /api/v1/auth/login` — username + password → access_token + refresh_token
- `POST /api/v1/auth/refresh` — refresh_token rotation chain
- `POST /api/v1/auth/logout` — 세션 종료

### 새 도메인 용어

**없음**. glossary.md는 DDD 유비쿼터스 언어(백엔드 도메인) 사전. UI 영역의 패턴 용어 (AuthContext / PrivateRoute / TokenInterceptor 등)는 구현 용어이지 도메인 용어가 아니다. 따라서 glossary.md 변경 없음.

### 기존 결정 충돌

**없음**. 본 작업이 [[domain/identity-access#절대 규칙]] 의 다음 규칙을 정확히 구현해야 한다.

- 평문 비밀번호 저장 금지 → Argon2id (서버 측 — 클라이언트는 평문 전송 후 백엔드가 검증)
- 토큰 `localStorage` 저장 금지 → `sessionStorage` (Access JWT) 또는 HttpOnly Cookie (Refresh)
- CSRF 검증 비활성화 금지 → CookieCsrfTokenRepository + SameSite=Strict + secure=true

### 관련 ADR (누적 13건, 변경 없음)

[[domain/identity-access#관련 ADR]] 의 13건 모두 본 작업이 준수 대상.

특히 본 UI 작업과 직접 관련된 ADR.
- [[decisions/2026-05-20-jwt-issuer-strategy]] — Access JWT 형식 (BTS 자체 발급).
- [[decisions/2026-05-20-session-pat-schema]] — Refresh rotation chain 동작 (클라이언트가 401 시 refresh 호출).
- [[decisions/2026-05-20-csrf-cookie-mode]] — CSRF 토큰 클라이언트 처리 정책.

### 신규 ADR 후보 1건

- **`2026-05-21-client-token-refresh-policy`** (가칭) — 클라이언트의 401 응답 시 토큰 자동 갱신 + 재시도 정책. 예. 401 → `/refresh` 호출 → 새 access 받아서 원래 요청 1회 retry → 실패 시 `/login` 강제. spec/plan 단계에서 결정 후 작성.

### grill-with-docs 진행 메모

**스킵**. 본 작업은 백엔드 도메인 모델 변경이 0에 가깝고 (UI 패턴 = 구현 용어), 신규 도메인 용어/엔티티가 없어 grill 비용 > 효익. `/bts-domain` SKILL §Fast-track 스킵 정신과 동일하게 처리. spec/plan 단계에서 새 결정 사항이 등장하면 그때 별도 ADR 생성.

## 스펙

전체 스펙. [docs/specs/2026-05-21-fr-au-09-login-form-ui-d6.md](../specs/2026-05-21-fr-au-09-login-form-ui-d6.md)

### scope 결정

**monster PR — 한 PR로 일괄** (사용자 게이트 결정).

- frontend Phase 0 인프라 부트스트랩 (pnpm workspaces + apps/web Vite + Tailwind v4 + shadcn/ui + TanStack Router/Query + Zustand + RHF/Zod + Vitest + Playwright)
- DESIGN.md 첫 작성
- 인증 코어 (`authStore` + `apiClient` + 401 인터셉터 + race lock)
- 라우팅 (`__root`, `/login`, `/dashboard` placeholder + 라우트 가드)
- 로그인 폼 (shadcn/ui + RHF + Zod)
- 로그아웃 (헤더 메뉴)
- Playwright E2E (S1/S2/S8)

### 핵심 시나리오 (3줄)

- 미인증 사용자가 보호 라우트 진입 → `/login?returnTo=<...>` 리다이렉트 → username/password 입력 후 `POST /api/v1/auth/login` → 200 시 access JWT를 `sessionStorage` + Zustand 갱신 → returnTo 리다이렉트
- 인증 사용자가 인증 필요 API 호출 → access 만료 시 401 → 인터셉터가 `POST /api/v1/auth/refresh` 자동 호출 + retry 1회 (race lock) → 사용자 무중단
- 인증 사용자 로그아웃 → `POST /api/v1/auth/logout` → 세션 클리어 → `/login` 리다이렉트

### Out of Scope

회원가입 / 비밀번호 재설정 / 2FA UI / 다크 모드 / i18n / 관리자 화면 / 백엔드 API 변경.

### 신규 ADR 후보 4건

`docs/specs/...` §9.
- `client-token-refresh-policy` (401 자동 갱신 + race lock)
- `frontend-monorepo-bootstrap` (pnpm workspaces 구조)
- `tanstack-router-vs-react-router` (code-based vs file-based 결정 기록)
- `design-md-initial` (DESIGN.md 토큰 + shadcn 활용 + 다크 모드 정책)

## Brainstorming Check

✅ 통과 (1회 iteration, 자체 진행).

### 큰 gap 5건 (plan/impl 단계에서 명시 결정)

| ID | gap | 1차 추천 처리 |
|---|---|---|
| G1 | 로그인 후 user 정보 획득 (PR #8에 `/me` 있나?) | plan 단계에서 backend grep → 없으면 JWT body decode (`jose`) |
| G2 | seed user `alice` 자격 증명 위치 (LDAP/Local) | plan 단계에서 backend Testcontainers seed 확인 |
| G3 | 테스트 전략 (msw 단위 + 실제 backend E2E) | plan task에 명시 |
| G4 | dev proxy + prod base URL 전략 | `apiClient`가 분기 (impl) |
| G5 | Tailwind v4 + shadcn/ui 호환 (CSS-first 설정) | impl 1번째 task — 비호환 발견 시 fallback BLOCKER 가능 |

### 작은 보강 7건 (plan task에 흡수)

B1. CSRF 헤더 echo / B2. 422 처리 / B3. 헤더 위치 / B4. Vite React 플러그인 / B5. StrictMode / B6. TanStack Router code-based / B7. Playwright CI 후속 PR.

### grill-with-docs 스킵 + sub-skill 압축 메모

`/bts-spec` SKILL의 Phase A 3종 sub-skill (`design-consultation` / `design-shotgun` / `office-hours`) 호출을 압축. 이유. (a) 활용 API + 토큰 저장 정책이 PR #8 + §절대 규칙으로 명확. (b) shadcn/ui 표준 폼 패턴이라 디자인 변형 가치 작음. (c) monster PR 자체가 무거워 sub-skill 풀 호출 비용 > 효익. 결과물은 게이트 1 풀 검토.

## Plan

총 20 task / 6 wave. spec §13의 게이트 1 결정 항목 (G6 옵션 D / G8 S3 제거 / G9 MFA alert / G10 CSRF 후속 / dev seed Local alice)을 모두 반영했다.

**TDD 적용 정책**.
- W0 (인프라 부트스트랩): TDD 면제. chore 커밋 (`chore(web):` prefix). 단, 각 task 종료 시 검증 명령으로 상태 확인.
- W1~W5: TDD red→green→refactor 강제. `feat:` / `test:` 커밋.
- 인프라 task에 TDD 면제 명시는 `/bts-impl`이 wave dispatch 시 implementer에게 전달.

**files 메타 표기**. 신규 디렉토리 (`apps/web/`)는 W0-T1에서 생성. 이후 파일 경로는 `apps/web/src/<...>` 가정.

### Wave 0 — 인프라 부트스트랩 (TDD 면제, 5 task 병렬)

#### Task 1. pnpm workspaces + apps/web Vite 6 + React 19 + TS strict + ESLint + Prettier

**메타**.
- agent: `frontend-engineer`
- files: [`pnpm-workspace.yaml`, `package.json`, `apps/web/package.json`, `apps/web/vite.config.ts`, `apps/web/tsconfig.json`, `apps/web/tsconfig.app.json`, `apps/web/tsconfig.node.json`, `apps/web/index.html`, `apps/web/src/main.tsx`, `apps/web/src/App.tsx`, `apps/web/.eslintrc.cjs`, `apps/web/.prettierrc`, `.gitignore`]
- depends-on: []
- tdd: false (chore — 인프라)

**GREEN (인프라 부트스트랩)**.
- 루트 `pnpm-workspace.yaml`: `packages: ['apps/*', 'packages/*']`
- 루트 `package.json` 보강: `"private": true`, `"packageManager": "pnpm@9.x"`, scripts (`dev`, `test`, `lint`, `typecheck`, `build`, `verify`).
- `apps/web/package.json`: name `@bts/web`, type module, React 19 + React-DOM 19, Vite 6, `@vitejs/plugin-react-swc`, TypeScript 5.x.
- `apps/web/vite.config.ts`: React plugin + 5173 port.
- `apps/web/tsconfig.json`: strict + `noUncheckedIndexedAccess: true` + project references.
- `apps/web/src/main.tsx`: `<StrictMode>` + `createRoot`.
- `apps/web/src/App.tsx`: minimal placeholder.
- ESLint flat config + Prettier 기본 룰.
- `.gitignore`에 `node_modules/`, `dist/`, `.turbo/`, `apps/web/dist/` 추가.

**검증**. `pnpm install && pnpm --filter @bts/web dev` → `localhost:5173` "Hello BTS" 표시. `pnpm --filter @bts/web typecheck` 0 에러. `pnpm --filter @bts/web lint` 0 경고. `pnpm --filter @bts/web build` 성공.

#### Task 2. Tailwind v4 + shadcn/ui 셋업

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/index.css`, `apps/web/postcss.config.mjs`, `apps/web/components.json`, `apps/web/src/components/ui/button.tsx`, `apps/web/src/components/ui/input.tsx`, `apps/web/src/components/ui/label.tsx`, `apps/web/src/components/ui/form.tsx`, `apps/web/src/components/ui/card.tsx`, `apps/web/src/lib/utils.ts`, `apps/web/package.json`, `pnpm-lock.yaml`, `apps/web/vite.config.ts`, `apps/web/src/App.tsx`]
- depends-on: [1]
- tdd: false
- 메타 보정 (2026-05-21 W2 dispatch 직전). package.json/pnpm-lock.yaml 추가 — controller가 wave 2 의존성 일괄 install 후 shadcn CLI 추가 install 시 lockfile 보강 가능. vite.config.ts/App.tsx는 Tailwind 플러그인 + Button 렌더 검증.

**GREEN**.
- Tailwind CSS v4 (`@tailwindcss/postcss` + `@tailwindcss/vite` 선택). `index.css`에 `@import 'tailwindcss'` + `@theme { ... }` (디자인 토큰).
- shadcn/ui CLI: `npx shadcn@latest init` → `components.json` 생성. **호환 검증** (Tailwind v4 + shadcn/ui 최신 — G5 gap. 비호환 발견 시 BLOCKED 보고 + Maxi 결정).
- 5 컴포넌트 설치: `npx shadcn@latest add button input label form card`.

**검증**. `App.tsx`에 `<Button>Test</Button>` 렌더 → 스타일 적용 확인. `pnpm typecheck` 0 에러.

#### Task 3. Vitest + RTL + jsdom + msw 셋업

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/vitest.config.ts`, `apps/web/src/test/setup.ts`, `apps/web/src/test/server.ts`, `apps/web/src/test/handlers.ts`, `apps/web/package.json`]
- depends-on: [1]
- tdd: false

**GREEN**.
- 의존성. `vitest`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`, `jsdom`, `msw`.
- `vitest.config.ts`: jsdom 환경 + setupFiles `src/test/setup.ts`.
- `src/test/setup.ts`: `@testing-library/jest-dom` 등록 + msw `server.listen()` / `server.resetHandlers()` / `server.close()` 라이프사이클.
- `src/test/server.ts`: msw `setupServer(...handlers)`.
- `src/test/handlers.ts`: 기본 handler 빈 배열 (각 테스트가 자체 추가).

**검증**. `apps/web/src/App.test.tsx` smoke 테스트 (`renders without crashing`) → `pnpm --filter @bts/web test` 통과.

#### Task 4. Playwright + 첫 fixture (스모크)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/playwright.config.ts`, `apps/web/e2e/smoke.spec.ts`, `apps/web/package.json`]
- depends-on: [1]
- tdd: false

**GREEN**.
- 의존성. `@playwright/test`.
- `playwright.config.ts`: baseURL `http://localhost:5173`, webServer `pnpm dev`, chromium만 (Phase 0).
- `e2e/smoke.spec.ts`: `await page.goto('/'); await expect(page).toHaveTitle(/BTS/);` — 5173 살았는지 확인.

**검증**. `pnpm --filter @bts/web test:e2e` → smoke 통과 (vite dev 자동 부팅).

#### Task 5. DESIGN.md 첫 작성 (designer 협업)

**메타**.
- agent: `designer` (스펙 작성), 합의 후 `frontend-engineer`가 Tailwind 토큰 반영
- files: [`DESIGN.md`, `apps/web/src/index.css` (보강)]
- depends-on: [2]
- tdd: false

**GREEN**.
- `DESIGN.md` 섹션. 디자인 토큰 / 컬러 / 타이포그래피 / 간격 / 라운드 / 그림자 / 다크 모드 정책 (라이트만, 후속) / 접근성 (WCAG AA) / shadcn/ui 활용 가이드.
- Tailwind `@theme` 블록을 토큰 정의와 동기화.

**검증**. Maxi 검토 게이트 (게이트 1에서 일괄). impl 단계에서는 designer agent가 초안 작성 → frontend-engineer가 CSS 반영.

### Wave 1 — API 스키마 + 상태 + 클라이언트 기본 (TDD, 3 task 병렬)

#### Task 6. Zod 스키마 — LoginRequest / TokenResponse / WhoamiResponse / ApiErrorResponse

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/schemas.ts`, `apps/web/src/api/schemas.test.ts`]
- depends-on: [1]

**RED**. `schemas.test.ts`. 잘못된 응답 (필드 누락 / 타입 오류)에 `safeParse` 실패. 정상 응답 통과. backend Jackson `@JsonProperty` 매핑 (`access_token` snake_case) 일치 검증.

**GREEN**. `schemas.ts`. 
- `LoginRequestSchema`: `{ provider: z.enum(['local', 'ldap-corp']), username: z.string().min(1), password: z.string().min(1) }`.
- `TokenResponseSchema`: `{ access_token: z.string().min(1), token_type: z.literal('Bearer'), expires_in: z.number() }`.
- `WhoamiResponseSchema`: `{ username: z.string(), email: z.string(), authMethod: z.string(), userId: z.string() }`.
- `ApiErrorResponseSchema`: `{ error: z.string() }`.

**REFACTOR**. 타입 export (`type LoginRequest = z.infer<typeof LoginRequestSchema>` 등).

**검증**. `pnpm --filter @bts/web test src/api/schemas`.

#### Task 7. authStore (Zustand)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/authStore.ts`, `apps/web/src/auth/authStore.test.ts`]
- depends-on: [1, 6]

**RED**. `authStore.test.ts`. `setSession({ user, accessToken })` 후 `getState().user` 반영, `clearSession()` 후 null, `sessionStorage`에 access 저장/제거, 페이지 새로고침 (스토어 재생성) 시 `sessionStorage`에서 access hydrate.

**GREEN**. Zustand `create` + `persist` middleware (sessionStorage scope) — 단, `accessToken`만 persist, `user`는 whoami로 재조회. 또는 둘 다 persist (decode 비용 회피).

**REFACTOR**. `clearSession` 시 sessionStorage 명시적 `removeItem`. 헬퍼 `useAuthUser()`, `useIsAuthenticated()`.

**검증**. `pnpm --filter @bts/web test src/auth/authStore`.

#### Task 8. apiClient — fetch wrapper + Authorization 헤더 + credentials

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/client.ts`, `apps/web/src/api/client.test.ts`, `apps/web/.env.example`]
- depends-on: [1, 6, 7]

**RED**. `client.test.ts` (msw). 
- `apiFetch('/api/v1/auth/login', { method, body })` 호출 시 `credentials: 'include'` + `Content-Type: application/json` + `Authorization: Bearer <access>` (access 존재 시).
- access 없을 때 Authorization 헤더 미포함.
- base URL은 `VITE_API_BASE_URL` 환경 변수 우선, 미설정 시 빈 문자열 (dev proxy 의존).

**GREEN**. `client.ts`. fetch wrapper + Zod 응답 스키마 파싱 헬퍼.

**REFACTOR**. `apiPost<T>(path, body, schema)` / `apiGet<T>(path, schema)` 헬퍼 추출.

**검증**. `pnpm --filter @bts/web test src/api/client`.

### Wave 2 — 인증 흐름 (TDD, 3 task)

#### Task 9. 401 인터셉터 + race lock (refresh 자동 호출)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/client.ts` (보강), `apps/web/src/api/interceptor.test.ts`]
- depends-on: [8]

**RED**. `interceptor.test.ts` (msw).
- 401 응답 → `/refresh` 자동 호출 → 새 access로 원래 요청 retry 1회.
- 4 요청 동시 401 → `/refresh` 1회만 호출 (race lock, Promise 캐싱).
- refresh 401 → authStore clearSession + reject (호출자가 라우트 가드로 /login 리다이렉트).

**GREEN**. `client.ts`에 인터셉터 추가. `refreshPromise` 전역 변수 (모듈 스코프) — 진행 중인 refresh가 있으면 그것을 await.

**REFACTOR**. refresh 중인지 알 수 있는 헬퍼.

**검증**. `pnpm --filter @bts/web test src/api/interceptor`.

#### Task 10. useLoginMutation — login + whoami 연쇄 + dev seed 결정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/useLoginMutation.ts`, `apps/web/src/auth/useLoginMutation.test.tsx`, `backend/modules/identity-access/src/main/resources/data-dev.sql` (G2 dev seed — 단 백엔드 spec C6 위배 가능, 게이트 1에서 결정)]
- depends-on: [6, 7, 8]

**RED**. `useLoginMutation.test.tsx` (msw + React Query QueryClientProvider).
- `mutation.mutate({ provider: 'local', username, password })` 성공 → access setSession → whoami 호출 → user setSession → returnTo 콜백 호출.
- 실패 (`error: 'invalid_credentials'`) → onError → authStore 미변경.
- 실패 (`error: 'mfa_required'`) → onError, message 분기.

**GREEN**. TanStack Query `useMutation`. `mutationFn`이 login → access 저장 → whoami → user 저장 순차 실행.

**REFACTOR**. 에러 코드 → 한국어 매핑 헬퍼.

**검증**. `pnpm --filter @bts/web test src/auth/useLoginMutation`.

**dev seed 메모 (G2)**. `data-dev.sql` 추가는 backend 변경. C6 위배 회피하려면 backend Spring profile `dev` 활성 + `application-dev.yml`의 `spring.sql.init.data-locations`로 SQL 실행하는 방향. **게이트 1에서 결정**. 옵션. (a) `backend/modules/identity-access/src/main/resources/data-dev.sql` 추가 (Local alice — Argon2 해시 박힌 SQL) — 백엔드 자원 추가지만 prod 영향 없음. (b) 본 PR scope에서 dev seed 제외, manual SQL 가이드만 README에 추가.

#### Task 11. useLogoutMutation

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/useLogoutMutation.ts`, `apps/web/src/auth/useLogoutMutation.test.tsx`]
- depends-on: [7, 8]

**RED**. logout API 호출 (204) → authStore clearSession → onSuccess 호출.

**GREEN**. `useMutation` 단순 구현.

**REFACTOR**. 에러 시 graceful (서버 실패해도 클라이언트 상태는 클리어).

**검증**. `pnpm --filter @bts/web test src/auth/useLogoutMutation`.

### Wave 3 — 라우팅 (TDD, 2 task)

#### Task 12. TanStack Router 셋업 + __root layout + 라우트 트리

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/routes/__root.tsx`, `apps/web/src/routes/login.tsx`, `apps/web/src/routes/dashboard.tsx`, `apps/web/src/main.tsx` (보강)]
- depends-on: [2, 7]

**RED**. `routes/__root.test.tsx`. router가 `/` → `/dashboard` (or `/login`) 리다이렉트, `/login` 라우트 마운트, `/dashboard` 라우트 마운트.

**GREEN**. code-based router (B6 결정). `__root` layout + Outlet. 3개 라우트 트리.

**REFACTOR**. 라우트 별 meta 타입 (`requireAuth: boolean`).

**검증**. `pnpm --filter @bts/web test src/routes`.

#### Task 13. 라우트 가드 — beforeLoad

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/routeGuard.ts`, `apps/web/src/auth/routeGuard.test.tsx`, `apps/web/src/routes/dashboard.tsx` (보강)]
- depends-on: [7, 12]

**RED**. 
- 미인증 + `requireAuth: true` 라우트 → `/login?returnTo=<현재>` 리다이렉트.
- 인증 + `/login` 진입 → `returnTo` 또는 `/dashboard` 리다이렉트.
- `returnTo` 외부 URL (`http://evil.com`) → 차단 + `/dashboard` 리다이렉트.

**GREEN**. `requireAuth` / `redirectIfAuth` 헬퍼 — `beforeLoad`에 export.

**REFACTOR**. `returnTo` 검증 (`^/` 패턴) 분리.

**검증**. `pnpm --filter @bts/web test src/auth/routeGuard`.

### Wave 4 — UI 페이지 (TDD, 2 task)

#### Task 14. /login 페이지 + LoginForm 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/login.tsx` (실제 구현), `apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/LoginForm.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [2, 6, 10, 13]

**RED**. `LoginForm.test.tsx`.
- 입력 + 제출 → `useLoginMutation.mutate` 호출 (msw 응답 모킹).
- 검증 실패 (빈 username) → Zod 메시지 표시.
- 401 응답 → "사용자명 또는 비밀번호가 올바르지 않습니다" 표시.
- `mfa_required` 응답 → "추가 인증이 필요합니다. 관리자에게 문의하세요." 표시 (G9).
- provider 드롭다운 (Local / LDAP) — 기본값 Local (G6 옵션 D).
- 키보드 탐색 + label + aria-invalid + aria-describedby 검증.

**GREEN**. shadcn/ui Form + RHF + Zod resolver + Input + Label + Button. 한국어 카피는 `i18n/ko.ts` 상수.

**REFACTOR**. 에러 카피 매핑 별도 함수.

**검증**. `pnpm --filter @bts/web test src/auth/LoginForm`.

#### Task 15. /dashboard placeholder + 헤더 + 로그아웃 메뉴

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/dashboard.tsx` (실제 구현), `apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`]
- depends-on: [7, 11, 13]

**RED**. `Header.test.tsx`. 인증 사용자 username 표시, 로그아웃 클릭 → `useLogoutMutation.mutate` 호출 → `/login` 리다이렉트.

**GREEN**. shadcn/ui DropdownMenu + 사용자 이름. dashboard 페이지는 "환영합니다, {username}".

**REFACTOR**. 헤더가 `__root` layout 내부에 마운트.

**검증**. `pnpm --filter @bts/web test src/components/Header`.

### Wave 5 — dev/prod 환경 + E2E (4 task)

#### Task 16. Vite dev proxy + .env.example + apiClient 환경 분기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/vite.config.ts` (보강), `apps/web/.env.example`, `apps/web/.env.development.local.example`, `apps/web/README.md` (간단)]
- depends-on: [8]
- tdd: false (config)

**GREEN**. `vite.config.ts`의 `server.proxy: { '/api': 'http://localhost:8080' }` (dev). `.env.example`에 `VITE_API_BASE_URL=` (prod 미지정 — 동일 도메인 가정). `README.md`에 로컬 셋업 가이드 (백엔드 8080 + 프론트 5173 + alice 시드 명령).

**검증**. `pnpm dev` 후 dev tools network 탭에서 `/api/v1/auth/login` 요청이 8080으로 proxied 확인.

#### Task 17. Playwright E2E — S1 정상 로그인

**메타**.
- agent: `frontend-engineer` (또는 `qa-engineer` 보조)
- files: [`apps/web/e2e/login-happy-path.spec.ts`]
- depends-on: [4, 14, 15, 16]

**RED**. test.expect: `/login` → fill alice/password + provider Local → submit → URL `/dashboard` 확인 + "환영합니다, alice" 표시.

**GREEN**. webServer + backend 의존 (manual `./gradlew :backend:bootRun` + dev seed alice 가정).

**REFACTOR**. fixture로 backend 헬스체크 wait.

**검증**. `pnpm --filter @bts/web test:e2e -- login-happy-path`.

#### Task 18. Playwright E2E — S2 잘못된 비밀번호

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/e2e/login-invalid.spec.ts`]
- depends-on: [4, 14, 16]

**RED**. 잘못된 비밀번호 → 401 에러 메시지 표시, URL `/login` 유지.

**GREEN**. test 작성.

**검증**. `pnpm --filter @bts/web test:e2e -- login-invalid`.

#### Task 19. Playwright E2E — S8 이미 인증 시 /login 리다이렉트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/e2e/already-authed.spec.ts`]
- depends-on: [4, 13, 14, 16, 17]

**RED**. setup: 정상 로그인 후 `/login` 진입 → `/dashboard` 즉시 리다이렉트.

**GREEN**. test 작성.

**검증**. `pnpm --filter @bts/web test:e2e -- already-authed`.

### Wave 6 — 통합 검증 (1 task)

#### Task 20. pnpm verify + 백엔드 회귀 + DESIGN.md Maxi 검토

**메타**.
- agent: `frontend-engineer` (controller가 실행)
- files: []
- depends-on: [모든 W0~W5]
- tdd: false (검증)

**검증 명령**.
1. `pnpm --filter @bts/web typecheck` — 0 에러
2. `pnpm --filter @bts/web lint` — 0 경고
3. `pnpm --filter @bts/web test` — 0 fail (단위 + 컴포넌트)
4. `pnpm --filter @bts/web test:e2e` — 0 fail (S1/S2/S8)
5. `pnpm --filter @bts/web build` — 성공
6. `./gradlew :modules:identity-access:test` — 432 tests 0 fail (회귀 검증)
7. 로컬 manual 검증 — backend 8080 + frontend 5173 + alice 로그인 → /dashboard 진입 / 로그아웃 / 401 시 자동 갱신 (manual로 access TTL을 짧게 설정해서 검증, 머지 전 원복)
8. DESIGN.md Maxi 검토 (게이트 2 항목)

## Plan 메타

- task 수: **20**
- wave 수: **6** (의존성 그래프 기반)
- 예상 시간 (직렬 기준): task × 3-5분 = 약 60-100분 (인프라 task는 더 길 수도)
- 예상 시간 (병렬 wave): 약 20-30분 (W0 인프라 부트스트랩이 가장 큼)
- TDD 강제: yes (W0 제외 — 인프라 부트스트랩)
- 병렬 dispatch: `/bts-impl`이 task 메타로 wave 계산
- 추가 검증: pnpm typecheck / lint / test / test:e2e / build, 백엔드 회귀 (`./gradlew :modules:identity-access:test`)
- 폭발 반경. monster PR (PR #8과 같은 패턴), wave 0 BLOCKER 발견 시 (G5 Tailwind v4 호환 등) Maxi에게 즉시 보고.

## 게이트 1 결정 결과 (2026-05-21, Maxi 승인)

| # | 항목 | 결정 |
|---|---|---|
| 1 | G6 provider 필드 | ✅ **드롭다운 (Local / LDAP-corp, 기본 Local)** |
| 2 | G8 S3 계정 잠금 | ✅ **본 PR scope 제거** (백엔드 미구현) |
| 3 | G9 MFA 응답 | ✅ **단순 alert "추가 인증이 필요합니다..."** (2FA UI는 후속 PR) |
| 4 | G10 CSRF echo | ✅ **본 PR 미구현** (인증 API는 백엔드 면제, 다른 API 추가 시 후속 PR) |
| 5 | dev seed | ✅ **`data-dev.sql` 추가** (Local alice, Argon2 해시 박힌 SQL, Spring profile dev 활성 시 자동 실행) |
| 종합 | 게이트 1 | ✅ **승인 — /bts-impl 진입** |

### impl 단계 지침 (결정 반영)

- **Task 14 (LoginForm)** — provider 드롭다운 추가, 옵션 `local` + `ldap-corp` (LdapProvider config의 providerId), 기본값 `local`. shadcn/ui `Select` 컴포넌트 추가 설치.
- **Task 6 (Zod 스키마)** — `LoginRequestSchema.provider` enum 값 `['local', 'ldap-corp']`로 확정.
- **Task 10 (useLoginMutation)** — `mfa_required` 에러 시 한국어 alert + `i18n/ko.ts` 매핑. Account lockout (S3) 케이스 RED test 작성 안 함.
- **Task 10 (dev seed)** — `backend/modules/identity-access/src/main/resources/data-dev.sql` 신규. SQL 한 줄로 alice INSERT. Argon2id 해시는 impl 시 Argon2PasswordEncoder로 1회 생성 후 SQL에 hardcode. backend `application-dev.yml`에 `spring.sql.init.mode: always` + `data-locations: classpath:data-dev.sql` 설정 (또는 Spring profile dev 활성 시 자동 실행되도록 보강).
- **Task 8 (apiClient)** — `X-XSRF-TOKEN` 헤더 echo 구현 안 함 (G10 결정 반영).
- **Task 17/18/19 (E2E)** — alice / password (dev seed 후) 사용. backend는 dev profile로 기동 (`./gradlew :backend:bootRun --args='--spring.profiles.active=dev'`). README.md (T16)에 명령 명시.

## 리뷰 결과

4종 자체 리뷰 (sub-skill 압축, 게이트 1 풀 검토). 모두 통과, BLOCKER 없음. ⚠️ concern 3건은 게이트 1 결정 항목으로 이미 명시 (G5/dev seed).

### plan-design-review (자체, 2026-05-21)

- ✅ DESIGN.md 첫 작성 (W0-T5) + shadcn/ui 표준 활용 + Tailwind `@theme` 토큰
- ✅ 다크 모드 본 PR 라이트만 (후속 PR 명시)
- ✅ 접근성 (WCAG AA / aria-invalid + aria-describedby / 키보드 탐색)
- ✅ LoginForm 디자인 디테일은 impl 단계에서 designer agent 협업으로 보강 가능
- 결론. ✅ 통과. 디자인 리스크 작음.

### plan-eng-review (자체, 2026-05-21)

- ✅ 라우팅 code-based (TanStack Router, B6 결정)
- ✅ 상태 Zustand persist (sessionStorage scope) + TanStack Query mutation
- ✅ 401 인터셉터 race lock (Promise 캐싱) — RED test case 명시 (T9)
- ✅ 보안 §절대 규칙 5건 plan에 반영
- ✅ 테스트 — Vitest unit (msw) + RTL component + Playwright E2E (S1/S2/S8)
- ✅ 백엔드 회귀 검증 task (T20)
- ✅ TDD 강제 — W0 면제 + W1~W5 강제 명시
- ✅ wave 의존성 그래프 명시 (depends-on)
- ⚠️ **concern-1 (G5 Tailwind v4 + shadcn/ui 호환)** — impl T2 wave 0에서 비호환 발견 시 BLOCKER. fallback (Tailwind v3) 선택 시 SDD 03 결정과 충돌 → plan-eng-review BLOCKER 가능. impl 단계에서 즉시 Maxi 보고.
- ⚠️ **concern-2 (dev seed = backend 변경)** — T10 의 `data-dev.sql` 추가가 backend resource 변경 → C6 (백엔드 변경 0) 위배 가능. 단 dev profile만 동작, prod 영향 없음. 게이트 1 결정 항목.
- ⚠️ **concern-3 (E2E 백엔드 수동 의존)** — `./gradlew :backend:bootRun` 수동 기동 가정. CI 통합은 후속 PR. 머지 전 manual 검증으로 보완.
- 결론. ⚠️ 통과 with concerns. BLOCKER 없음.

### plan-ceo-review (자체, 2026-05-21)

- ✅ scope: monster PR 사용자 명시 결정 (옵션 A, 한 PR 일괄)
- ✅ 가치: BTS 첫 frontend 도입 → 회사 사용자에게 처음으로 보이는 인증 진입점. 큰 momentum.
- ✅ 분할 가능성 검토: PR #8 monster 패턴 성공 사례 있음. 분할 시 PR-B/PR-C가 인프라 PR-A 머지 대기로 느려짐. 일괄이 유리.
- ✅ 후속 PR 명확화: 회원가입 / 비밀번호 재설정 / 2FA / 다크 모드 / OIDC SSO / 관리자 화면 / 멀티-Provider / sync-obsidian — 본 PR Out of Scope 명시
- 결론. ✅ 통과. 큰 가치 / 분명한 후속 로드맵.

### plan-devex-review (자체, 2026-05-21)

- ✅ `pnpm dev` → 5173 즉시 로그인 폼 (Vite HMR 기본값)
- ✅ `pnpm test` 단위 (Vitest msw) + `pnpm test:e2e` E2E 분리
- ✅ `pnpm verify` 통합 검증 (lint + typecheck + test + build)
- ✅ T16 README.md 셋업 가이드 (백엔드 8080 + 프론트 5173 + alice 시드 명령)
- ✅ DESIGN.md (T5) → 컴포넌트 작성 시 가이드
- 개선 후보 (후속 PR 권장):
  - Storybook 도입 (UI 컴포넌트 카탈로그) — 본 PR scope 외
  - CI 통합 (GitHub Actions에서 lint/typecheck/test/e2e + backend 자동 기동) — 본 PR scope 외
  - 자동 alice 시드 (dev profile 활성 시) — 본 PR T10 또는 후속 PR
- 결론. ✅ 통과. 개선 후보는 후속 PR로 이관.

### 리뷰 종합

- design-review ✅
- eng-review ⚠️ (concern 3건, 모두 게이트 1 결정 항목)
- ceo-review ✅
- devex-review ✅
- **BLOCKER 없음** — 게이트 1 진입 가능.
- 게이트 1에서 사용자가 결정해야 할 사항 5건 (G6 옵션 D / G8 S3 제거 / G9 MFA alert / G10 CSRF 후속 / dev seed 전략) — Plan §"게이트 1 결정 항목" 참고.
