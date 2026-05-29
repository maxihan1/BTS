# FR-AU-09 마무리 — 내 활성 세션 관리 (목록 조회 + 강제 종료)

> slug: fr-au-09-session-management
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-29

## Brief

**사용자 원문**.
> FR-AU-09 작업 마무리. 진척 확인 결과 D4(백엔드)·D6(프론트 UI)·D7(E2E)가 "내 활성 세션 관리" 기능으로 통째로 비어 있음.

**요구사항**.
- 백엔드. 활성 세션 목록 조회 API (`GET /api/v1/auth/sessions`) + 특정 세션 강제 종료 API (`DELETE /api/v1/auth/sessions/{sid}`).
- 프론트. 활성 세션 목록 화면 + 각 세션 강제 로그아웃 버튼.
- E2E. 목록 조회 → 강제 종료 → 해당 토큰 차단 확인 시나리오.

**선행 컨텍스트 (진척 확인, 2026-05-29)**.
- `SessionService.revoke(sid)` / `revokeAllOfUser(userId)` / `lookup(sid)` / `markLastSeen(sid)` 이미 구현됨 (`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`).
- `AuthController` (`/api/v1/auth`) 에 login / logout / refresh 만 존재. 세션 목록·강제종료 엔드포인트 부재.
- `SidRevokeJwtConverter` + `SessionService.lookup` 기반 revoke 검증 필터 이미 동작 (revoke 시 해당 sid JWT 차단). EC-29 Caffeine 5s TTL 캐시.
- 프론트 `apps/web/src/auth/` 에 login/logout mutation + authStore 만 존재. 세션 목록 UI 부재.
- `apps/web/e2e/` 에 login 계열 E2E 만 존재. 세션관리 E2E 부재.

**classify 보정**. classify-task 가 'E2E' 키워드로 qa 오분류 → Maxi 확인 후 auth / security-engineer / identity-access 로 확정 (2026-05-29).

**제약**.
- DEVELOPMENT.md §1.17 — 토큰 localStorage 금지 (sessionStorage 강제).
- 한 PR = 한 BC (identity-access). 단, frontend + same BC view layer 변경은 한 PR 내 허용 (learnings 2026-05-22 옵션 C 패턴).

## 도메인 정리

- **BC**. identity-access (단일 BC, 격리 유지). 단 frontend UI + same BC view layer 변경은 한 PR 내 허용 (learnings 2026-05-22 옵션 C).
- **영향 엔티티**. `Session` (기존, V004) — **신규 엔티티 없음**. 목록 표시에 필요한 필드 (createdAt / lastSeenAt / userAgent / ipAddress / providerId) 이미 보유.
- **신규 서비스 메서드 (백엔드 추가)**. `SessionService.findActiveByUser(userId)` — 사용자의 활성(revoked_at IS NULL AND expires_at > now) 세션 목록 조회. partial index `idx_sessions_user_active` 활용. 강제 종료는 기존 `revoke(sid, reason)` 활용.
- **개념 구분 (중요)**. 본 작업은 **self-service 세션 관리** (사용자 본인이 자기 세션 조회/종료). domain 노트 §미래 작업의 "관리자 화면 — 세션 강제 종료"(admin이 남의 세션 종료)와는 **별개**. 본 PR은 self-service 범위만.
- **권한 경계 (보안 핵심 — IDOR 방지)**. `GET /sessions`는 인증 사용자 본인 세션만 반환. `DELETE /sessions/{sid}`는 해당 sid의 `session.userId == 인증 사용자 id` 검증 후에만 revoke. 남의 sid 종료 시도는 거부 (404 권장 — 존재 여부 노출 방지).
- **결정 1 — 현재 세션 처리**. 목록에서 본인 JWT의 `sid`와 일치하는 세션을 "현재 세션"으로 표시 + 그 세션의 강제종료는 차단 (자기 발 그루기 방지). 현재 세션 종료는 기존 로그아웃 버튼으로. (GitHub/Google UX 패턴, Maxi 확정 2026-05-29)
- **결정 2 — 노출 정보**. Provider / 기기(User-Agent) / IP 주소 / 마지막 활동시각(lastSeenAt) / 로그인 시각(createdAt). `deviceFingerprint`(내부 신뢰 디바이스 판별용)는 응답 DTO에서 제외. (Maxi 확정 2026-05-29)
- **도메인 사실 — revoke 반영 지연**. `SidRevokeJwtConverter`의 EC-29 Caffeine 5s TTL 캐시로 인해, 강제 종료 후 해당 access token이 최대 5초간 유효할 수 있음. E2E 시나리오 (강제종료 → 토큰 차단 확인)는 이 지연을 고려해야 함.
- **기존 결정 충돌**. 없음. `session-pat-schema` ADR의 연장. SidRevokeJwtConverter revoke 흐름과 정합.
- **신규 ADR**. 불필요. 새 아키텍처 결정이 아니라 기존 스키마 위 기능 명세 — 결정 1/2는 spec의 FR/EC로 기록.
- **glossary 추가 후보 (Maxi 승인 영역)**. "활성 세션", "세션 강제 종료" — glossary.md에 세션 용어 부재. 머지 시점에 추가 제안 예정.
- **관련 ADR**. [session-pat-schema](../decisions/2026-05-20-session-pat-schema.md), [jwt-key-rotation-policy](../decisions/2026-05-20-jwt-key-rotation-policy.md)

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-au-09-session-management.md](../specs/2026-05-29-fr-au-09-session-management.md)

핵심 시나리오 3줄 요약.
- `GET /api/v1/auth/sessions` — 본인 활성 세션 목록 (current 플래그 + 기기/IP/시각). `SessionService.findActiveByUser` wrapper 신규 (repo `findActiveByUserId` 기존).
- `DELETE /api/v1/auth/sessions/{sid}` — 본인 세션 강제 종료 (revoke + refresh chain 무효화). 타인 sid 404(IDOR), 현재 sid 409.
- 프론트 `/settings/sessions` 목록 UI + 강제 로그아웃 버튼(현재 세션 비활성) + E2E (목록→종료→토큰 차단).

API 변경 없음 (sessions V004 + idx_sessions_user_active 기존 활용). 신규 마이그레이션 0.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 (만료필터/refresh무효화/CSRF/감사로그) 전부 기존 logout 선례로 해소 — Maxi 결정 불필요.

## Plan

> 모든 응답 DTO/Zod 필드는 spec §FR-2 정의를 단일 진실원천으로 사용 (learnings 2026-05-22 Zod↔DTO drift 차단). plan 본문 축약 표기 금지 — spec §API 인터페이스 참조.

### Task 1. SessionService.findActiveByUser wrapper 추가

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionServiceTest.kt`]
- depends-on: []

**RED**. `SessionServiceTest` 에 `findActiveByUser(userId)` 가 활성 세션만, lastSeenAt DESC 정렬로 반환하는 테스트. 만료/폐기 세션 제외 케이스(EC-5) 포함. 실패: 메서드 없음.

**GREEN**. `@Transactional(readOnly = true) fun findActiveByUser(userId: UUID): List<Session> = repo.findActiveByUserId(userId)`. 정렬이 repo 쿼리에 없으면 Service 또는 SQL ORDER BY lastSeenAt DESC 보강.

**REFACTOR**. KDoc — self-service 세션 목록 조회 용도, GET /sessions 가 호출 명시.

**검증**. `(cd backend && ./gradlew :modules:identity-access:test --tests SessionServiceTest)`

### Task 2. GET /api/v1/auth/sessions + SessionResponse DTO

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/SessionResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on: [1]

**RED**. `AuthControllerTest` — 인증 사용자의 활성 세션 목록 200 반환, 응답 필드 spec §FR-2 정합(sid/providerId/userAgent/ipAddress/lastSeenAt/createdAt/current), 요청 JWT sid 와 일치 세션 `current=true`, deviceFingerprint 미포함, 미인증 401, **PAT 호출 시 403 `session_management_requires_interactive_login`(FR-6b/EC-8)**. 실패: 엔드포인트 없음.

**GREEN**. `@GetMapping("/sessions")` — `authentication.principal` 이 `Jwt` 아니면(=PAT) 403 반환(FR-6b). `Jwt` 에서 userId(subject) + 현재 sid(claim) 추출 → `sessionService.findActiveByUser(userId)` → `SessionResponse` 매핑(current = (session.id == 현재 sid)). `{ "sessions": [...] }` 래핑. `SessionResponse` data class 신규 (deviceFingerprint 제외). PAT 분기는 `WhoamiController` 선례 참조.

**REFACTOR**. 매핑 로직 private fun 추출 + KDoc.

**검증**. `(cd backend && ./gradlew :modules:identity-access:test --tests AuthControllerTest)`

### Task 3. DELETE /api/v1/auth/sessions/{sid} (IDOR 404 / 현재세션 409)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on: [1, 2]

**RED**. `AuthControllerTest` 6 케이스 — (a) 본인 다른 세션 강제종료 204 + `revoke("user_revoke")` + `revokeChainFromSession` 호출 검증, (b) 타인 sid → 404(IDOR, S-3), (c) 현재 세션 sid → 409 `cannot_revoke_current_session`(S-4), (d) 미존재/비활성 sid → 404(EC-2), (e) **PAT 호출 → 403(FR-6b/EC-8)**, (f) **잘못된 UUID 형식 sid → 400(EC-7)**. 실패: 엔드포인트 없음.

**GREEN**. `@DeleteMapping("/sessions/{sid}")` — principal 이 `Jwt` 아니면 403(FR-6b) → sid 파싱(형식 오류 시 Spring 400/EC-7) → `sessionService.lookup(sid)` → null 또는 `userId != 인증 userId` → 404 → sid == 현재 sid → 409 → 아니면 `revoke(sid,"user_revoke")` + `refreshTokenRepository.revokeChainFromSession(sid)` → 204. (logout 선례 `AuthController.kt:169-170` 동일 패턴.) IDOR 검증은 본 메서드 단일 지점(NFR-1).

**REFACTOR**. 404/409 분기 가독성 정리 + KDoc(IDOR 방어 사유 명시).

**검증**. `(cd backend && ./gradlew :modules:identity-access:test --tests AuthControllerTest)`

### Task 4. 프론트 세션 API 클라이언트 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/sessions.ts`, `apps/web/src/api/sessions.test.ts`]
- depends-on: []

**RED**. `sessions.test.ts` — `sessionSchema` Zod 가 spec §FR-2 필드 1:1 파싱(MSW mock 응답 기반), `listSessions()` GET 호출, `revokeSession(sid)` DELETE 호출 + XSRF 헤더 포함. 실패: 모듈 없음.

**GREEN**. `sessionSchema` (sid/providerId/userAgent nullable/ipAddress nullable/lastSeenAt/createdAt/current) + `listSessions` + `revokeSession`. 기존 `api/client.ts` 인터셉터(XSRF) 재사용.

**REFACTOR**. 스키마 export + 타입 추론(`z.infer`) 정리.

**검증**. `pnpm --filter web test sessions`

### Task 5. useSessionsQuery + useRevokeSessionMutation

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/useSessionsQuery.ts`, `apps/web/src/auth/useRevokeSessionMutation.ts`, `apps/web/src/auth/useSessionsQuery.test.tsx`, `apps/web/src/auth/useRevokeSessionMutation.test.tsx`]
- depends-on: [4]

**RED**. 훅 테스트 — `useSessionsQuery` 가 listSessions 결과 반환, `useRevokeSessionMutation` 성공 시 sessions 쿼리 invalidate + toast. 실패: 훅 없음.

**GREEN**. TanStack Query `useQuery`/`useMutation`. mutation onSuccess → `queryClient.invalidateQueries(['sessions'])` + sonner toast. (access token 은 기존 authStore sessionStorage 사용 — localStorage 금지.)

**REFACTOR**. query key 상수화.

**검증**. `pnpm --filter web test useSessionsQuery useRevokeSessionMutation`

### Task 6. /settings/sessions 라우트 + SessionList UI

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.sessions.tsx`, `apps/web/src/components/auth/SessionList.tsx`, `apps/web/src/components/auth/SessionList.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [5]

**RED**. `SessionList.test.tsx` — 세션 목록 카드 렌더(기기/IP/마지막활동), current 세션 "현재 세션" 배지 + 강제로그아웃 버튼 `disabled`, 다른 세션 버튼 클릭 시 revoke mutation 호출, userAgent/ipAddress null 시 fallback 텍스트(EC-6), **"세션 종료는 최대 몇 초 내 적용" 안내 문구 렌더(FR-8)**. 실패: 컴포넌트 없음.

**GREEN**. shadcn `card`/`button`/`badge` 사용. TanStack Router code-based 패턴(learnings 2026-05-22 — routes/*.tsx 에 page+adapter export, router.ts 에 adapter import). current 세션 버튼 disabled. 강제종료 영역에 5초 지연 안내 문구.

**REFACTOR**. 세션 항목 sub-component 분리 + 날짜 포맷 유틸.

**검증**. `pnpm --filter web test SessionList && pnpm --filter web typecheck`

### Task 7. Playwright E2E — 세션 관리 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/session-management.spec.ts`, `apps/web/e2e/fixtures/session-fixtures.ts`]
- depends-on: [3, 6]

**RED→검증**. happy path — 로그인 → /settings/sessions 진입 → 세션 목록 표시 → 다른 세션 강제종료 → 목록에서 사라짐 + (EC-29 5s 캐시 고려) 그 토큰 차단 확인. edge — 현재 세션 버튼 disabled 확인, IDOR(타인 sid 직접 DELETE) 404. 기존 e2e fixture/패턴 재사용. mermaid 무관.

**검증**. `pnpm --filter web test:e2e session-management`

## Plan 메타

- task 수: 7
- 예상 wave: 4 (longest path). Wave1 [T1,T4] → Wave2 [T2,T5] → Wave3 [T3,T6] → Wave4 [T7]
- 백엔드(T1-3)/프론트(T4-6)는 파일 영역 분리 → wave 내 병렬. E2E(T7)는 양쪽 완성 후.
- TDD 강제: yes (test→feat 커밋 순서 자동 검증)
- 추가 검증: ktlint, detekt, typecheck, vitest, playwright
- 신규 마이그레이션: 0

## 리뷰 결과

### plan-eng-review (외부 sub-agent fresh 시각, 2026-05-29)
- ✅ 통과. SessionService/Repository/RefreshToken 가정 실재(phantom 없음), findActiveByUserId 만료필터 충족, JWT sid 신뢰 가능, CSRF 판단 정확, DTO↔spec 1:1, 인증 경로 정합, TDD 형식·메타블록·의존성 그래프·wave(4) 정확.
- ⚠️ 주의 4건. (1) IDOR 강제 위치 — spec NFR-1 "Service 단계" vs plan Task3 "Controller lookup 후 비교" 문구 어긋남(검증 자체는 존재). (2) EC-7(잘못된 UUID 400) plan Task3 RED 누락. (3) audit 무감사는 후속 보안 부채(FR-AU-10 트래킹 필수). (4) 404 vs 403 → 404 정답(OWASP).
- 🛑 BLOCKER-1. **PAT 인증 시 `@AuthenticationPrincipal Jwt` null → 500/NPE.** PatAuthenticationFilter 가 principal 을 `UsernamePasswordAuthenticationToken(userId)` 로 설정 → Jwt 아님. `/api/**` authenticated 라 PAT 도 sessions 도달. PAT 엔 sid 없어 "현재 세션" 개념 무의미. WhoamiController(`Jwt?` nullable + PAT 분기) 선례 미준수. → **Maxi 결정 필요.**
- 🛑 BLOCKER-2. **Gradle 검증 경로 오류.** `./gradlew :backend:modules:identity-access:test` → repo 루트엔 gradlew 없음 + `:backend:` 세그먼트 부재. 실제. `backend/` 에서 `./gradlew :modules:identity-access:test`. → 즉시 정정(아래 반영).
- 종합. loop back 권장 (BLOCKER 2건).

### plan-ceo-review (외부 sub-agent fresh 시각, 2026-05-29)
- ✅ 세로 슬라이스 한 PR 타당, 현재세션 종료차단=GitHub/Google UX, IDOR 404+deviceFingerprint 미노출=정답, 신규 마이그레이션 0 절제. audit FR-AU-10 위임·admin 제외·YAGNI 모두 합리.
- ⚠️ EC-29 5초 캐시 지연이 "즉시 차단" 사용자 기대의 약한 고리. UI 카피 한 줄로 해소 가능 — Maxi 택1 권장. (a) "최대 몇 초 내 적용" 안내 / (b) "새 토큰 발급은 즉시 차단"으로 충분 명시.
- 🛑 BLOCKER 없음. 게이트1 진입 권장.

### 메인 세션 감수 + BLOCKER 해소 (2026-05-29)
- ✅ BLOCKER-2 해소. 검증 명령 전부 `(cd backend && ./gradlew :modules:identity-access:test ...)` 로 정정.
- ✅ BLOCKER-1 해소. **Maxi 결정 — JWT 전용 + PAT 403 (Jira 방식, 관심사 분리).** spec FR-6b/EC-8 추가, API 403/400 명시. Task 2/3 GREEN 에 principal `Jwt` 타입 가드, RED 에 PAT 403 케이스 추가 (Task 3 는 EC-7 400 케이스도). Controller 단일 지점 검증(SecurityConfig 경로 가드 불필요 — `WhoamiController` 선례 결과 동일).
- ✅ ⚠️ EC-29 5초 UX 해소. **Maxi 결정 — 안내 문구 추가.** spec FR-8 + Task 6 RED 에 "최대 몇 초 내 적용" 문구 반영 (task 추가 없이 카피로 흡수).
- ✅ ⚠️ EC-7(400) → Task 3 RED 반영. ⚠️ NFR-1 위치 문구 → Controller 단일 지점으로 spec 정합. ⚠️ audit 부채 → FR-AU-10 트래킹 유지.
- 종합. **BLOCKER 2/2 해소, 잔여 0. 게이트1 진입 권장.** (task 수 7 유지 — 신규 task 없이 기존 task RED/GREEN 보강.)
