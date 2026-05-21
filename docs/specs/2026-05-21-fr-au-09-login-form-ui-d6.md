# FR-AU-09 인증 API를 사용하는 로그인 폼 UI (D6 작업) — 스펙

> slug: fr-au-09-login-form-ui-d6
> 작성: 2026-05-21
> scope: monster PR — frontend Phase 0 인프라 + DESIGN.md + 인증 상태 + 라우트 가드 + 토큰 인터셉터 + 로그인 폼 + Playwright E2E (D6 + D7 일부 흡수)

## 0. 컨텍스트

- 직전 PR #8 (FR-AU-09 세션/토큰 관리)가 머지되며 백엔드 인증 API 완성. 본 PR이 그 API를 호출하는 **첫 frontend 코드**.
- BTS의 **frontend Phase 0 진입**도 본 PR이 겸한다. 코드 0줄 상태에서 시작.
- 기술 스택은 SDD 03-tech-stack에서 결정 완료 (React 19 + Vite 6 + TS strict + pnpm + Tailwind v4 + shadcn/ui + TanStack Router/Query + Zustand + React Hook Form + Zod + Vitest + Playwright).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 정상 로그인 (Happy Path)

- **Given** 사용자가 미인증 상태로 `/dashboard` 접근
- **When** 라우트 가드가 미인증 감지 → `/login?returnTo=/dashboard` 리다이렉트 → 사용자가 `alice` / `password` 입력 후 "로그인" 클릭
- **Then** `POST /api/v1/auth/login` 호출 → 200 + access_token (응답) + refresh_token (HttpOnly Cookie) → Access JWT를 `sessionStorage`에 저장 + Zustand `authStore` 상태 갱신 → `returnTo` 또는 `/dashboard`로 리다이렉트

### S2. 잘못된 자격 증명

- **Given** 사용자가 로그인 폼에 잘못된 비밀번호 입력
- **When** `POST /api/v1/auth/login` 401 응답 (`code: INVALID_CREDENTIALS`)
- **Then** 폼 하단에 한국어 에러 메시지 "사용자명 또는 비밀번호가 올바르지 않습니다" 표시 + 비밀번호 필드 클리어 + 사용자명 유지 + 폼 enabled

### S3. 계정 잠금

- **Given** 5회 연속 실패 후 6번째 시도
- **When** `POST /api/v1/auth/login` 423 응답 (`code: ACCOUNT_LOCKED` + Retry-After 헤더)
- **Then** "계정이 잠겼습니다. 잠시 후 다시 시도하세요." 메시지 + Retry-After 초까지 폼 disabled + 카운트다운 표시

### S4. Access JWT 만료 → 자동 갱신 (무중단)

- **Given** 인증 사용자가 인증 필요 API 호출 (예. 이슈 목록)
- **When** Access JWT 만료 (5분 후) → 백엔드 401 응답
- **Then** HTTP 인터셉터가 자동으로 `POST /api/v1/auth/refresh` 호출 → 새 access_token 받아 `sessionStorage` 갱신 → 원래 요청 1회 retry → 사용자는 만료 사실 모름

### S5. Refresh 만료 → 강제 로그인

- **Given** 24h 후 access + refresh 모두 만료
- **When** API 호출 → 401 → 인터셉터가 `/refresh` 호출 → 그것도 401
- **Then** `sessionStorage` 클리어 + Zustand 상태 클리어 + `/login?returnTo=<현재 URL>` 강제 리다이렉트 + "세션이 만료되었습니다. 다시 로그인해 주세요." toast

### S6. 동시 401 (race condition)

- **Given** 페이지가 4개 API를 병렬 호출, access 막 만료
- **When** 4개 모두 동시에 401 받음
- **Then** 인터셉터가 `/refresh`를 **1회만** 호출 (mutex/promise 캐싱) → 4 요청 모두 같은 새 access로 retry

### S7. 로그아웃

- **Given** 인증 사용자가 헤더 우상단 "로그아웃" 메뉴 클릭
- **When** `POST /api/v1/auth/logout` 호출
- **Then** 백엔드 200 응답 + HttpOnly Cookie 삭제 + 클라이언트 `sessionStorage` + Zustand 클리어 → `/login` 리다이렉트

### S8. 이미 인증된 사용자가 /login 접근

- **Given** 인증 상태에서 `/login` 직접 진입
- **When** 라우트 가드가 인증 감지
- **Then** 즉시 `/dashboard` 또는 `returnTo` 리다이렉트 (폼 표시 안 함)

### S9. 네트워크 오류

- **Given** 사용자가 로그인 클릭, 백엔드 다운
- **When** fetch가 network error throw
- **Then** "서버에 연결할 수 없습니다. 잠시 후 다시 시도하세요." 메시지 + 폼 enabled + 자동 재시도 없음 (사용자 액션 기다림)

## 2. 기능 요구사항 (FR)

### FR-INFRA — frontend Phase 0 부트스트랩

- **FR-INFRA-01** pnpm 모노레포 초기화. `pnpm-workspace.yaml` + 루트 `package.json` 수정.
- **FR-INFRA-02** `apps/web` 패키지 (Vite 6 + React 19 + TS 5 strict + noUncheckedIndexedAccess).
- **FR-INFRA-03** Tailwind CSS v4 + shadcn/ui CLI 초기화 + 기본 컴포넌트 (Button, Input, Label, Form, Card) 생성.
- **FR-INFRA-04** TanStack Router 셋업 (file-based routing 또는 code-based 결정 — 본 spec에서는 **code-based** 권장, 명시적 라우트 트리).
- **FR-INFRA-05** TanStack Query v5 셋업 (`QueryClient` + `QueryClientProvider`).
- **FR-INFRA-06** Zustand v5 셋업 (`authStore`).
- **FR-INFRA-07** React Hook Form + Zod 의존성 추가.
- **FR-INFRA-08** Vitest + @testing-library/react + jsdom 셋업.
- **FR-INFRA-09** Playwright E2E 셋업 (`playwright.config.ts` + 1st test fixture).
- **FR-INFRA-10** `DESIGN.md` 첫 작성 (디자인 토큰 + 컴포넌트 컨벤션).
- **FR-INFRA-11** ESLint + Prettier 셋업 (TypeScript strict 호환).

### FR-AUTH — 인증 코어

- **FR-AUTH-01** `authStore` (Zustand) — `user: AuthUser | null`, `accessToken: string | null`, `setSession()`, `clearSession()`.
- **FR-AUTH-02** HTTP 클라이언트 (`apiClient`) — fetch wrapper + Authorization 헤더 자동 부착 (`Bearer <access>`).
- **FR-AUTH-03** 401 응답 인터셉터 — `/refresh` 자동 호출 + retry 1회 + race lock (Promise 캐싱).
- **FR-AUTH-04** Access JWT는 `sessionStorage` 저장 (key: `bts.access`). Refresh는 백엔드가 HttpOnly Cookie로 자동 설정.
- **FR-AUTH-05** `useAuth()` hook — `authStore` 값 + login/logout mutation.

### FR-ROUTE — 라우팅

- **FR-ROUTE-01** `__root.tsx` — 전역 layout (헤더 + outlet).
- **FR-ROUTE-02** `/login` 라우트 — 로그인 폼 페이지.
- **FR-ROUTE-03** `/` (또는 `/dashboard`) — 인증 후 진입 페이지. 본 PR에서는 **placeholder** ("환영합니다, {username}").
- **FR-ROUTE-04** 라우트 가드 — TanStack Router `beforeLoad` 사용. `requireAuth: true` 라우트는 미인증 시 `/login?returnTo=<현재>` 리다이렉트.
- **FR-ROUTE-05** `/login`은 이미 인증 시 `returnTo` 또는 `/`로 리다이렉트 (S8).

### FR-FORM — 로그인 폼

- **FR-FORM-01** 사용자명 + 비밀번호 입력 필드 (shadcn/ui `Input`).
- **FR-FORM-02** Zod 스키마 클라이언트 검증 — 사용자명 ≥1자, 비밀번호 ≥1자 (백엔드와 일치, 실제 강도 검증은 백엔드).
- **FR-FORM-03** "로그인" 버튼 + 로딩 스피너 (TanStack Query `mutation.isPending`).
- **FR-FORM-04** 에러 메시지 alert (S2~S6, S9 케이스별 한국어 카피).
- **FR-FORM-05** Enter 키로 제출 가능.
- **FR-FORM-06** 키보드 탐색 (Tab) 정상 동작 + label 명시 + `aria-describedby` 에러 연결.

### FR-LOGOUT — 로그아웃

- **FR-LOGOUT-01** 헤더에 사용자명 + 로그아웃 버튼 (인증 상태일 때만).
- **FR-LOGOUT-02** 클릭 시 `POST /api/v1/auth/logout` + clearSession + `/login` 리다이렉트.

### FR-DESIGN — DESIGN.md

- **FR-DESIGN-01** 디자인 토큰 (컬러 / 타이포그래피 / 간격 / 라운드 / 그림자) 정의.
- **FR-DESIGN-02** shadcn/ui 컴포넌트 활용 가이드 (어떤 컴포넌트를 언제, 커스텀 wrapper 작성 기준).
- **FR-DESIGN-03** 다크 모드 지원 정책 (본 PR. 라이트 모드만, 다크 모드는 후속 PR).
- **FR-DESIGN-04** 접근성 가이드 (WCAG AA 목표).

## 3. 비기능 요구사항 (NFR)

### NFR-SEC — 보안

- **NFR-SEC-01** Access JWT는 `sessionStorage` 사용. `localStorage` 금지 ([[domain/identity-access#절대 규칙]]).
- **NFR-SEC-02** Refresh Token은 HttpOnly Cookie (백엔드가 set, 클라이언트 JS 접근 불가).
- **NFR-SEC-03** CSRF 토큰 — 백엔드 `CookieCsrfTokenRepository`가 cookie로 발급. fetch가 `credentials: 'include'`로 자동 송신. 변경 요청 (POST/PUT/DELETE)은 `X-XSRF-TOKEN` 헤더 echo.
- **NFR-SEC-04** XSS 회귀 가드 — 로그인 에러 메시지는 텍스트 노드로 렌더 (`dangerouslySetInnerHTML` 금지).
- **NFR-SEC-05** 비밀번호 입력은 `type="password"`, 자동 완성 `current-password`.

### NFR-PERF — 성능

- **NFR-PERF-01** 첫 페이지 로딩 < 3s (3G 모바일 시뮬레이션).
- **NFR-PERF-02** 로그인 폼 제출 → 응답 처리 < 2s (백엔드 200 응답 기준).
- **NFR-PERF-03** 번들 크기 — `apps/web` 메인 청크 < 300KB gzip (Phase 0 목표, 후속 PR에서 분할).

### NFR-A11Y — 접근성

- **NFR-A11Y-01** WCAG AA 목표 — color contrast ≥ 4.5:1.
- **NFR-A11Y-02** 모든 form input에 `<label>` 명시.
- **NFR-A11Y-03** 에러 상태에 `aria-invalid` + `aria-describedby` 연결.
- **NFR-A11Y-04** 키보드만으로 로그인 가능 (마우스 없이).

### NFR-I18N — 국제화

- **NFR-I18N-01** 본 PR. **한국어 하드코딩** (i18n 인프라 미래 도입). 다만 카피는 별도 상수 파일 (`i18n/ko.ts`)로 분리하여 미래 i18n 도입을 쉽게.

### NFR-TEST — 테스트

- **NFR-TEST-01** Vitest 단위 — `authStore`, `apiClient`, 토큰 인터셉터 race lock.
- **NFR-TEST-02** Vitest 컴포넌트 — 로그인 폼 (정상 / 에러 / 검증).
- **NFR-TEST-03** Playwright E2E — S1 (정상 로그인), S2 (잘못된 비밀번호), S8 (이미 인증).
- **NFR-TEST-04** 백엔드 회귀 0 (`./gradlew :modules:identity-access:test`).

## 4. API 인터페이스 (PR #8 완성품, 변경 없음)

### POST /api/v1/auth/login

- 요청. `{ username: string, password: string }`
- 응답 200. `{ access_token: string, token_type: "Bearer", expires_in: number }` + `Set-Cookie: refresh_token=<...>; HttpOnly; Secure; SameSite=Strict; Path=/`
- 응답 401. `{ code: "INVALID_CREDENTIALS", message: string }`
- 응답 423. `{ code: "ACCOUNT_LOCKED", message: string }` + `Retry-After: <초>`

### POST /api/v1/auth/refresh

- 요청. body 없음 (refresh_token은 HttpOnly Cookie에서 자동 송신)
- 응답 200. `{ access_token: string, ... }` + 새 refresh cookie
- 응답 401. refresh 만료 → 강제 로그아웃 처리

### POST /api/v1/auth/logout

- 요청. body 없음
- 응답 200. cookie 삭제 (Set-Cookie: refresh_token=; Max-Age=0)

## 5. 데이터 모델 변경

**없음**. 백엔드 변경 0.

## 6. 엣지 케이스 (EC)

- **EC-01** `sessionStorage` 비활성화 (브라우저 private mode + 제한 설정) — fallback: 인메모리 보관 (페이지 새로고침 시 로그인 필요). 첫 진입 시 가능 여부 확인 후 warning toast.
- **EC-02** 시계 어긋남 — JWT `exp` 검증은 백엔드 측. 클라이언트는 만료 사전 추정 안 함 (만료 시 401 받고 처리). 클럭 스큐 회피.
- **EC-03** 백엔드 5xx — 401과 같지 않게 처리. retry 안 함, 사용자에게 "서버 오류" 메시지.
- **EC-04** 백엔드 응답 형식 변경 — Zod 응답 스키마로 런타임 검증 (`access_token`, `expires_in` 필드 보장).
- **EC-05** SPA 새로고침 시 인증 복원 — `sessionStorage`의 access JWT를 Zustand에 hydrate. 단, JWT 자체에서 username 추출 (decode without verify). 또는 첫 요청 시 401 받으면 정상 흐름.
- **EC-06** 동시 로그인 (다른 탭) — 각 탭이 자체 `sessionStorage`. tab 1 로그아웃 → tab 2는 다음 요청 401 시 강제 로그아웃 흐름.
- **EC-07** `returnTo` 파라미터 검증 — 외부 URL 차단 (open redirect 회피). `^/` 으로 시작하는 상대 URL만 허용.
- **EC-08** Vite dev server CORS — `apps/web` (5173) → 백엔드 (8080)는 dev proxy로 해결 (`vite.config.ts`의 `server.proxy`).

## 7. 제약 조건

- **C1** [[domain/identity-access#절대 규칙]] 5종 모두 준수 (특히 토큰 저장 + CSRF).
- **C2** shadcn/ui 표준 컴포넌트만 사용 (커스텀 wrapper는 정당화 필요). UI 자유도 자제.
- **C3** TypeScript strict + noUncheckedIndexedAccess. `any` 사용 금지.
- **C4** 모든 form은 React Hook Form + Zod. 직접 `useState`로 폼 상태 관리 금지.
- **C5** 본 PR scope에 다크 모드, i18n, 회원가입, 비밀번호 재설정, 2FA UI **포함 안 함**. 후속 PR.
- **C6** 백엔드 변경 0. 본 PR이 백엔드 코드 수정하면 BLOCKER.

## 8. 측정 가능한 완료 기준

- [ ] `pnpm typecheck` — 0 에러
- [ ] `pnpm lint` — 0 경고
- [ ] `pnpm test` (Vitest) — 0 fail, 핵심 시나리오 (authStore / apiClient / form) 커버
- [ ] `pnpm test:e2e` (Playwright) — S1 / S2 / S8 시나리오 통과
- [ ] `./gradlew :modules:identity-access:test` — 백엔드 회귀 0 (432 tests 그대로 통과)
- [ ] 로컬 검증 — `pnpm dev` → `localhost:5173/login` → `alice` / `password` → `/dashboard` 진입
- [ ] 라우트 가드 검증 — 미인증 `/dashboard` 진입 → `/login?returnTo=/dashboard`
- [ ] 자동 갱신 검증 — Vitest mock 또는 dev 환경 access 만료 흉내 (`expires_in: 5`로 백엔드 임시 설정, 본 PR 머지 전 원복)
- [ ] DESIGN.md 작성됨 + Maxi 검토
- [ ] `pnpm verify` (lint + typecheck + test + build) — 0 fail

## 9. 신규 ADR 후보

- **`2026-05-21-client-token-refresh-policy.md`** — 클라이언트 토큰 자동 갱신 정책. 401 → /refresh 1회 → 새 access로 retry → 실패 시 강제 로그아웃. race lock (Promise 캐싱).
- **`2026-05-21-frontend-monorepo-bootstrap.md`** — pnpm workspaces 구조 + apps/web + packages/ 약속 (본 PR이 첫 패키지 도입).
- **`2026-05-21-tanstack-router-vs-react-router.md`** — SDD 03에서 TanStack Router 결정됐으나 실제 도입 시 발견 사항 (code-based vs file-based 선택 등) 정리.
- **`2026-05-21-design-md-initial.md`** — DESIGN.md 초안 결정 (토큰 / shadcn 활용 / 다크 모드 정책).

## 10. Out of Scope (본 PR 명시 제외)

- 회원가입 UI / 비밀번호 재설정 UI
- 2FA UI (TOTP / 백업 코드)
- 다크 모드 토글
- i18n 전면 도입
- 관리자 화면
- 이슈/프로젝트 UI (다음 BC)
- 백엔드 API 변경
- LDAP SSO UI 흐름 (현재 폼 = Local + LDAP 통합 진입점, /api/v1/auth/login이 백엔드에서 분기)

## 11. 검증 후 plan 단계로 진입

`/bts-plan`이 본 spec을 읽고 wave 병렬 dispatch 가능한 TDD task로 분해한다.

## 12. Brainstorming Sanity Check (자체)

자체 점검으로 발견된 gap 정리. plan/impl 단계에서 결정/보강.

### 큰 gap (plan 단계 또는 impl 단계에서 명시 결정)

- **G1. 로그인 후 사용자 정보 획득** — Zustand `authStore.user`에 username 외 다른 정보 (id, email, group 등) 필요 시 어떻게 획득? PR #8에 `/api/v1/auth/me` 같은 엔드포인트 있는지 plan 단계에서 backend grep 확인. 없으면 (a) JWT body decode without verify (`jose` 라이브러리), 또는 (b) PR #8에 후속 보강 PR, 또는 (c) 본 PR에서 backend에 `/me` 추가 (단 C6 백엔드 변경 0 제약 위배). 1순위 (a) 추천.
- **G2. seed user `alice` 자격 증명** — alice가 LDAP에 있는지 (PR #4), Local Provider `StoredPasswordCredential`에 있는지 (PR #6 도입), Local + LDAP 모두에 있는지 backend Testcontainers seed 확인 필요. 본 PR E2E가 의존하는 user는 (a) seed로 박힌 LDAP user, (b) impl 중 SQL로 INSERT, (c) backend test 픽스처 재활용 중 결정. plan 단계에서 backend 코드 확인.
- **G3. 테스트 전략** — (a) Vitest 단위는 **msw (Mock Service Worker)**로 API mock. (b) E2E는 **실제 dev backend 의존** (`./gradlew :backend:bootRun`이 8080에서 실행 중이어야). CI 통합은 본 PR scope 외 (후속 PR). plan task에 명시.
- **G4. base URL 전략** — dev. `vite.config.ts` `server.proxy`로 `/api → localhost:8080`. prod. `VITE_API_BASE_URL` 환경 변수 (`apps/web/.env.example`에 기본값 명시). impl 단계에서 apiClient가 이 둘을 분기.
- **G5. Tailwind v4 + shadcn/ui 호환** — Tailwind v4 (2025년~)는 CSS-first 설정 (`@theme` 디렉티브). shadcn/ui CLI가 v4 호환 버전인지 확인 + 도입 가이드 (impl 1번째 task). 비호환 발견 시 fallback: Tailwind v3 사용 (단 SDD 03 결정과 충돌, plan-eng-review BLOCKER 가능).

### 작은 보강 (plan task에 흡수)

- **B1. CSRF 헤더 echo** — `apiClient`가 `XSRF-TOKEN` cookie 값을 `X-XSRF-TOKEN` 헤더로 echo. 단, login 자체는 본 헤더 없어도 통과 (백엔드 spec 확인).
- **B2. 422 응답 처리** — Zod 클라이언트 검증이 통과한 후 백엔드 422 가능성 작음. 받으면 일반 에러 alert로 fallback.
- **B3. 헤더 위치 명확화** — `__root.tsx` layout이 헤더 호스트. `/login` 라우트는 헤더 없음 (또는 minimal). `/dashboard` placeholder는 헤더 표시.
- **B4. Vite React 플러그인** — `@vitejs/plugin-react-swc` 추천 (빠른 빌드). React 19 호환 확인.
- **B5. React 19 StrictMode** — `main.tsx`에서 `<StrictMode>` 활성. 개발 시 부수 효과 검증.
- **B6. TanStack Router code-based 결정** — 본 PR scope에서 라우트 3개 (`/`, `/login`, `/dashboard`)만이라 code-based 권장 (file-based 부담 없이 시작). 후속 PR에서 라우트 폭증 시 재평가.
- **B7. Playwright CI 흐름** — 본 PR. 로컬만 동작. CI에서 backend + frontend 동시 기동 + E2E 흐름은 별도 후속 PR (infra).

### sanity check 결과 (1회차)

✅ 통과 (큰 gap 5건 + 작은 보강 7건 발견 → plan 단계로 이관).

## 13. plan 단계 추가 발견 (backend grep 후)

`/bts-plan` 단계의 backend grep으로 G1/G2 해소 + 5건 새 gap 발견.

### G1/G2 해소

- **G1 해소** — `/api/v1/users/me/whoami` 엔드포인트가 PR #8에 이미 존재. `GET` 요청 + `Authorization: Bearer <jwt>` 헤더. 응답 `WhoamiResponse { username, email, authMethod, userId }`. 401 또는 200. **클라이언트 흐름**: login 성공 → access JWT 저장 → whoami 호출하여 user 정보 hydrate → authStore에 set.
- **G2 해소 (부분)** — alice는 LdapProvider/LocalProvider 단위 테스트에 등장. 통합 테스트는 LDAP container (OpenLDAP) LDIF + Testcontainers seed로 보임. dev 환경(`pnpm dev` + `./gradlew :backend:bootRun`)에서 LDAP container를 별도 띄울지, 또는 Local user를 SQL seed로 INSERT할지 plan 단계에서 결정. **추천**. impl 1번째 인증 흐름 task에서 dev seed SQL 추가 (예. `data/dev-seed.sql` 또는 application-dev.yml hbm2ddl seed). LDAP은 후속 dev 환경 셋업 PR로.

### 새로 발견된 gap (G6~G10) — 게이트 1 결정 필요

- **G6. `provider` 필드 처리** — `LoginRequest`에 `provider` 필드 (예. `"local"`, `"ldap-corp"`)가 **필수**. 옵션.
  - (A) UI에 "provider 선택" 드롭다운 (Local / LDAP) — 사용자가 자기 자격이 어느 provider인지 알아야 함. 사용자 친화 낮음.
  - (B) `"local"` 하드코딩 — Local user만 로그인 가능. LDAP user 못 들어옴 (PR #4의 LDAP Auto-provisioning 사장).
  - (C) 백엔드에 username 자동 분기 endpoint 추가 — C6 위배 (백엔드 변경 0).
  - (D) **본 PR scope에서 "local" + "ldap-corp" 두 옵션 드롭다운 + 기본값 `local`** — 추천. 후속 PR에서 username 패턴 추정 또는 backend 분기로 개선.
  - 게이트 1에서 결정. plan은 옵션 D 가정으로 작성.
- **G7. 에러 코드 형식** — 백엔드는 snake_case (`invalid_credentials`, `mfa_required`, `refresh_token_expired`, `refresh_token_reused`, `refresh_token_invalid`). spec §1의 `INVALID_CREDENTIALS` 등 UPPER_SNAKE_CASE는 잘못된 가정. **수정**: 클라이언트는 백엔드 실제 형식 그대로 받아 `i18n/ko.ts`에서 한국어 메시지로 매핑.
- **G8. 계정 잠금 S3 본 PR scope 제거** — 백엔드 `AuthController`가 LOCKED 응답 미구현 (`AuthnResult.Failure`만 처리). S3 시나리오는 후속 PR로 이관 (FR-AU의 잠금 기능 자체가 후속 작업). spec §1.S3 제거 표시.
- **G9. MFA 응답 (`mfa_required`)** — 본 PR에서 단순 alert ("추가 인증이 필요합니다. 관리자에게 문의하세요.") 표시 + 로그인 폼 유지. MFA UI 흐름은 후속 PR (2FA).
- **G10. CSRF echo 구현 본 PR scope 외** — 백엔드가 `/api/v1/auth/*` 라우트는 CSRF skip 설정. 본 PR이 호출하는 API 3종 (login/refresh/logout) 모두 CSRF 면제. **수정**: `apiClient`에 CSRF 헤더 echo는 본 PR 구현 안 함. 본 PR 외 API (이슈 등) 추가 시 후속 PR에서 도입. spec NFR-SEC-03 보강.

### logout 응답 정정

- 백엔드 logout은 **204 No Content** (spec §4의 200 정정).

### plan 단계 결정 게이트 (게이트 1 일괄 검토 항목)

게이트 1에서 Maxi가 함께 결정.

1. **G6 옵션 D 채택** (드롭다운 Local + LDAP 기본 Local) — 추천
2. **G8 S3 본 PR 제거 승인**
3. **G9 MFA 단순 alert 처리 승인**
4. **G10 CSRF echo 후속 PR 이관 승인**
5. **dev seed 전략** — `data/dev-seed.sql`로 Local alice 추가 (LDAP은 후속) — 추천
