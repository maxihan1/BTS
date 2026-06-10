# FR-AU-10 D6/D7 — 인증 감사 로그 관리자 조회 UI + E2E (조회 API 포함)

> slug: fr-au-10-d6-d7-audit-admin
> type: feature (백엔드 조회 API + 프론트 UI + E2E)
> agent: security-engineer(백엔드 조회 API) + frontend-engineer(D6 UI + Header 게이팅) + qa-engineer(D7 E2E)
> primary_bc: identity-access (백엔드 view layer) + apps/web (그 프론트 소비자)
> 생성: 2026-06-10

## Brief

사용자 원문. "fr-au-10 d6, d7 진행해줘"

백엔드 1차 완료 — FR-AU-10 감사 로그 DB 영속화 + emit 12종 전수 배선(#108). 남은 작업은 D6(관리자 조회 UI)·D7(E2E).
**중요** — #108 완료 노트에 명시된 대로 **관리자 조회 API(GET 엔드포인트)는 아직 없음**(현재는 self-service `findRecent(userId, limit)`만 존재). 따라서 본 PR은 **조회 API + 프론트 UI + E2E** 3덩어리.

## 도메인 정리

- **BC**. identity-access(백엔드 read view layer 추가) + apps/web(프론트 소비자). 단일 PR. cross-BC 0(users·auth_audit_logs 모두 identity-access 소유 → LEFT JOIN 동일 BC 내부, FR-AU-08 D6/D7 same-BC view layer 선례).
- **작업 성격**. 백엔드 신규 read API(security-engineer) + 순수 프론트 D6(frontend-engineer) + Playwright E2E D7(qa-engineer). office-hours/grill 스킵 — 도메인은 ADR `2026-06-10-auth-audit-log-persistence`(D1~D5)로 확정. 신규 도메인 용어 0.
- **새 개념**. (1) 관리자 전역 감사 로그 조회(전 사용자, 필터+페이지네이션) (2) Header admin 메뉴 isSystemAdmin 게이팅 (3) 행위 주체 username/displayName LEFT JOIN 표시.
- **기존 결정 충돌**. 없음. 영속화 ADR 계승. 신규 ADR 0. 마이그레이션 0(V021 기존 테이블 조회만 — 인덱스 3종 이미 존재).

### Maxi 결정 (2026-06-10)

1. **주체 표시** — 백엔드 LEFT JOIN users. username/displayName 응답 포함. user_id 미상(LOGIN_FAILURE 등) 또는 미존재(삭제 사용자) → null → UI fallback "(알 수 없음)".
2. **필터 범위** — eventType + 기간(from/to) + 사용자(userId). V021 인덱스 3종과 정합.
3. **진입 경로** — Header에 admin 메뉴를 isSystemAdmin일 때만 노출. 기존 "관리 메뉴" nav(현재 전체 노출) 전체를 게이팅하고 거기에 감사 로그 링크 추가.

### 백엔드 API 계약 (신규 — Zod 1:1 정합 대상, frontend-zod-backend-dto-contract-gap)

**`GET /api/v1/admin/auth-audit-logs`** — `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` + SecurityFilterChain authenticated 이중 가드(UsersController.createUser 선례). PAT(ROLE_PAT)·비관리자 JWT → 403(Spring 기본 403, 권한 상세 미노출).

쿼리 파라미터(전부 선택).

| 파라미터 | 타입 | 의미 | 잘못된 값 |
|---|---|---|---|
| `eventType` | String(AuthEventType name) | 이벤트 유형 필터 | 미정의 enum → 400 |
| `userId` | UUID | 행위 주체 필터 | 잘못된 UUID → 400 |
| `from` | ISO-8601 Instant 문자열 | `created_at >= from` | 파싱 실패 → 400 |
| `to` | ISO-8601 Instant 문자열 | `created_at <= to`(상한 포함) | 파싱 실패 → 400 |
| `page` | Int ≥ 0 | offset 페이지(0-base) | 음수 → 400 |
| `size` | Int 1..100 | 페이지 크기(기본 50) | 범위 밖 → 400 |

성공 200 응답(평면 페이지 래퍼).

```json
{
  "items": [{
    "id": 123,
    "userId": "uuid|null",
    "username": "alice|null",
    "displayName": "Alice|null",
    "eventType": "LOGIN_SUCCESS",
    "providerId": "local",
    "ipAddress": "1.2.3.4|null",
    "userAgent": "Mozilla/5.0...|null",
    "metadata": { "sid": "..." },
    "createdAt": "2026-06-10T12:34:56Z"
  }],
  "page": 0,
  "size": 50,
  "totalElements": 1234,
  "totalPages": 25
}
```

- **deviceFingerprint 제외**. V021 컬럼은 있으나 현재 미사용(FR-MF-05 대비). 응답 lean 유지(SessionList deviceFingerprint 제외 선례).
- **정렬**. `created_at DESC, id DESC`(기존 findRecent tiebreaker 일관, idx 활용).
- **count**. 별도 `COUNT(*)`(필터 동일, JOIN 불요 — 필터는 auth_audit_logs 컬럼만). totalPages = ceil(totalElements / size).
- **from/to 경계**. 둘 다 포함(`>=` / `<=`). 프론트는 `to`에 선택일 종료시각(23:59:59.999Z) 전달. 문서화.
- **SQL 인젝션 방어**. 동적 WHERE는 named parameter 바인딩만(`NamedParameterJdbcTemplate`). 문자열 연결로 값 주입 금지(DEVELOPMENT.md §1.3, JdbcAuthAuditLogService 선례).
- **PII**. ip/userAgent/metadata는 관리자 응답에 포함(감사 목적). 로그 출력은 금지(DEVELOPMENT.md §1.2).

## Plan

> 경로 약어. `IA = backend/modules/identity-access/src` (패키지 `com.atlas.bts.identity`), `W = apps/web/src`, `E = apps/web/e2e`.
> TDD red→green→refactor 강제. 백엔드 2 task는 단일 모듈 test 컴파일 공유라 직렬(bts-plan-wave-gradle-module-compile). 프론트 task는 worktree 커밋 race 회피 위해 직렬 dispatch(worktree-lint-staged-shared-git-stash-collision), 자기 파일만 stage(parallel-dispatch-precommit-hook-race).
> Zod는 백엔드 DTO 1:1 미러(frontend-zod-backend-dto-contract-gap). 조회 전용이라 mutation 없음 → setQueryData/CSRF 트랩 N/A. MSW handler는 필터·페이지네이션을 백엔드와 동일 로직으로(브라우저 시드 가능 fixture, msw-derived-behavior-shared-store-e2e).

### Task 1. AuthAuditLogAdminQueryRepository + Jdbc 구현 (조회 read model)

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/audit/AuthAuditLogAdminQueryRepository.kt`, `IA/main/kotlin/com/atlas/bts/identity/audit/JdbcAuthAuditLogAdminQueryRepository.kt`, `IA/test/kotlin/com/atlas/bts/identity/audit/JdbcAuthAuditLogAdminQueryRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**. Testcontainers 통합테스트(JdbcAuthAuditLogServiceIntegrationTest 선례 — 실 PG). 시드 — users 2명(alice/bob) + auth_audit_logs 다건(alice LOGIN_SUCCESS, bob LOGIN_FAILURE, user_id=null LOGIN_FAILURE, 삭제/미존재 user_id 1건). `search(criteria)` 검증.
- 무필터 → 전체, created_at DESC·id DESC 정렬, totalElements 정확.
- eventType=LOGIN_SUCCESS → 해당만.
- userId=alice → alice만.
- from/to 범위 → 경계 포함.
- JOIN — 존재 사용자는 username/displayName 채움, user_id=null·미존재 user_id는 username/displayName=null.
- 페이지네이션 — size=2, page=0/1 분할 + totalElements 불변.
- 복합 필터(eventType+userId+from/to) AND 결합.

**GREEN**.
- read model — `AuthAuditLogAdminEntry(id: Long, userId: UUID?, username: String?, displayName: String?, eventType: AuthEventType, providerId: String, ipAddress: String?, userAgent: String?, metadata: Map<String,String>, createdAt: Instant)`, `AuthAuditLogAdminPage(items: List<AuthAuditLogAdminEntry>, totalElements: Long)`, `AuthAuditLogSearchCriteria(eventType: AuthEventType?, userId: UUID?, from: Instant?, to: Instant?, page: Int, size: Int)`. (별도 read model — 기존 `AuthAuditLogService`/`InMemoryAuthAuditLogService` 인터페이스 오염 회피, EC-6 2빈 충돌 방지.)
- 인터페이스 `AuthAuditLogAdminQueryRepository { fun search(criteria): AuthAuditLogAdminPage }`. 구현 `@Repository JdbcAuthAuditLogAdminQueryRepository(jdbc, objectMapper)`.
- SQL — `SELECT al.id, al.user_id, u.username, u.display_name, al.event_type, al.provider_id, al.ip_address, al.user_agent, al.metadata, al.created_at FROM auth_audit_logs al LEFT JOIN users u ON al.user_id = u.id WHERE <동적> ORDER BY al.created_at DESC, al.id DESC LIMIT :size OFFSET :offset`. count는 `SELECT COUNT(*) FROM auth_audit_logs al WHERE <동적>`(JOIN 없음).
- 동적 WHERE — criteria 비-null 항목만 `MutableList<String>`에 절 추가 + named param 바인딩(`event_type = :eventType` / `al.user_id = :userId` / `al.created_at >= :from` / `al.created_at <= :to`). offset = page * size. metadata JSONB → Map 역직렬화(JdbcAuthAuditLogService deserializeMetadata 패턴 재사용).

**REFACTOR**. SQL 상수화 + WHERE 빌더 헬퍼 + KDoc(LEFT JOIN 삭제사용자 보존·count 분리·SQL 인젝션 방어 named param·deviceFingerprint 제외 사유).
**검증**. `./gradlew :backend:modules:identity-access:test --tests "*JdbcAuthAuditLogAdminQueryRepositoryIntegrationTest"`

### Task 2. AuthAuditLogAdminController + DTO — GET /api/v1/admin/auth-audit-logs

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/web/AuthAuditLogAdminController.kt`, `IA/main/kotlin/com/atlas/bts/identity/web/dto/AuthAuditLogAdminDtos.kt`, `IA/test/kotlin/com/atlas/bts/identity/web/AuthAuditLogAdminControllerTest.kt`]
- depends-on: [1]

**RED**. MockMvc(repository mock, UsersController 테스트 선례) —
- SYSTEM_ADMIN JWT → 200, 응답 `{items:[...], page, size, totalElements, totalPages}` 형태·필드 정확(username/displayName null 포함, metadata 직렬화).
- 일반 인증 JWT(비관리자) → 403. PAT(ROLE_PAT) → 403.
- 미인증 → 401(SecurityFilterChain).
- 파라미터 — eventType 미정의 → 400, userId 잘못된 UUID → 400, from/to 파싱 실패 → 400, page 음수 → 400, size 0·101 → 400. 기본값(page=0,size=50) 적용.
- 필터 파라미터가 criteria로 정확히 매핑되는지(mock 인자 검증).

**GREEN**.
- `@RestController @RequestMapping("/api/v1/admin")`, `@GetMapping("/auth-audit-logs")`, `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`.
- 파라미터 — `eventType/userId/from/to/page/size` 전부 `@RequestParam(required=false)`. eventType은 String 수신 후 `AuthEventType.entries.find{it.name==..}` (미정의 → `IllegalArgumentException` → 400 핸들러). from/to는 String 수신 후 `Instant.parse`(실패 → 400). userId는 `UUID?` 바인딩(Spring 변환 실패 → 400). page/size 범위 검증(위반 → 400). **400 변환은 `@ExceptionHandler(IllegalArgumentException)` 인라인** 또는 명시적 `ResponseStatusException(BAD_REQUEST)` — message 미노출(일반 문구, fr-pm-04-guard-exception-message-http-leak).
- DTO — `AuthAuditLogEntryResponse`(id/userId/username/displayName/eventType(String)/providerId/ipAddress/userAgent/metadata/createdAt), `AuthAuditLogPageResponse`(items/page/size/totalElements/totalPages). repository entry → DTO 매핑.
- **catch-all advice 주의** — 401/403은 Spring Security가 처리. 400만 컨트롤러 책임. ResponseStatusException이 다른 advice에 삼켜지지 않는지 확인(catch-all-exceptionhandler-swallows-responsestatusexception, domain-exception-http-handler-basepackage-scope) — 단일 컨트롤러 인라인 핸들러 권장.

**REFACTOR**. 파라미터 파싱 헬퍼 + KDoc(이중 가드·PAT 403·400 사유·PII 응답 포함이나 로그 금지).
**검증**. `./gradlew :backend:modules:identity-access:test --tests "*AuthAuditLogAdminControllerTest"` + 모듈 ktlint/detekt `--rerun-tasks`(subagent-ktlint-false-green — 머지 전 직접 재검증).

### Task 3. W/api/audit-logs.ts — Zod 스키마 + fetchAuditLogs (GET, CSRF 불요)

**메타**.
- agent: `frontend-engineer`
- files: [`W/api/audit-logs.ts`, `W/api/audit-logs.test.ts`]
- depends-on: [2]

**RED**. api 단위테스트(MSW) — `fetchAuditLogs(params)`가 쿼리스트링 정확 조립(eventType/userId/from/to/page/size, undefined는 생략 — fetchUsers 선례)·`{items,page,size,totalElements,totalPages}` 파싱. entry의 userId/username/displayName null 허용. 빈 결과 `items:[]`. 403/401 → ApiError throw.
**GREEN**.
- `auditLogEntrySchema` — id:z.number(), userId:z.string().uuid().nullable(), username:z.string().nullable(), displayName:z.string().nullable(), eventType:z.string()(전방호환 — 백엔드 enum 추가 시 무파손, 라벨 매핑은 i18n), providerId:z.string(), ipAddress:z.string().nullable(), userAgent:z.string().nullable(), metadata:z.record(z.string()), createdAt:z.string().
- `auditLogPageSchema` — {items, page:z.number(), size:z.number(), totalElements:z.number(), totalPages:z.number()}.
- `AuditLogQueryParams` 인터페이스(eventType?/userId?/from?/to?/page?/size?) + `AUTH_EVENT_TYPES` const 12종 배열(백엔드 enum 미러 — 필터 드롭다운·라벨 키 출처. 주석에 "백엔드 AuthEventType.kt와 동기화" 명시).
- `fetchAuditLogs` — URLSearchParams 조립 후 `apiGet(path, auditLogPageSchema)`. GET이라 CSRF 불요(sessions GET 선례).
**REFACTOR**. JSDoc(에러코드·전방호환 eventType:string 사유) + z.infer 타입.
**검증**. `pnpm --filter web test -- audit-logs` + `pnpm --filter web typecheck`.

### Task 4. W/i18n/audit-log-labels.ts — 한국어 라벨 (이벤트 12종 포함)

**메타**.
- agent: `frontend-engineer`
- files: [`W/i18n/audit-log-labels.ts`, `W/i18n/audit-log-labels.test.ts`]
- depends-on: []

**RED**. 라벨 테스트(component-labels 패턴) — 페이지 제목/설명, 필터 라벨(이벤트유형/사용자/시작일/종료일/초기화), 테이블 헤더(시각/이벤트/주체/제공자/IP/상세), 빈상태, 페이지네이션(이전/다음/N개 중 X–Y), 주체/제공자 fallback("(알 수 없음)"), **AUTH_EVENT_TYPES 12종 전부 한국어 라벨 존재**(누락 0 단언 — 백엔드 enum과 카운트 정합).
**GREEN**. `auditLogLabels` 객체 + `authEventTypeLabels: Record<string,string>`(12종). 콜론 종결 금지(한국어 마침표, 글로벌 §5).
**REFACTOR**. 그룹 주석 + AUTH_EVENT_TYPES import해 키 누락 컴파일타임 방지(`Record<(typeof AUTH_EVENT_TYPES)[number], string>`).
**검증**. `pnpm --filter web test -- audit-log-labels`.

### Task 5. W/mocks/audit-log-handlers.ts + fixtures — MSW (필터·페이지네이션)

**메타**.
- agent: `frontend-engineer`
- files: [`W/mocks/audit-log-handlers.ts`, `W/mocks/audit-log-fixtures.ts`, `W/mocks/handlers.ts`, `W/mocks/audit-log-handlers.test.ts`]
- depends-on: [3]

**RED**. 핸들러 테스트 — `GET /api/v1/admin/auth-audit-logs`가 fixture에 대해 eventType/userId/from/to 필터 + page/size 슬라이싱 + totalElements/totalPages 계산을 백엔드와 동일하게. 정렬 created_at DESC. username/displayName 채움(fixture 사용자) + null 케이스 1건.
**GREEN**. `auditLogFixtures`(20건+, v4 UUID — zod-v4-uuid-fixture-strictness, alice/bob + user_id=null + 미존재 user_id 1건, eventType 다양·created_at 분산) + `auditLogHandlers`(필터→슬라이스→`{items,page,size,totalElements,totalPages}`). handlers.ts에 `...auditLogHandlers` 등록(알파벳순 그룹, **자기 import만 stage**). 조회 전용 — store 변이 없음(stateful 불요).
**REFACTOR**. 필터 함수 추출 + 페이지 계산 헬퍼.
**검증**. `pnpm --filter web test -- audit-log-handlers`.

### Task 6. W/auth/useAuditLogsQuery.ts — TanStack Query 훅 (필터 queryKey)

**메타**.
- agent: `frontend-engineer`
- files: [`W/auth/useAuditLogsQuery.ts`, `W/auth/useAuditLogsQuery.test.tsx`]
- depends-on: [3]

**RED**. 훅 테스트(QueryClient+MSW) — `useAuditLogsQuery(params)`가 params를 queryKey에 포함(필터 변경 시 재조회), data=page 응답. keepPreviousData(페이지 이동 시 깜빡임 방지). staleTime 적정.
**GREEN**. useSessionsQuery 선례. `AUDIT_LOGS_QUERY_KEY` 상수 + `[...KEY, params]` 키. `placeholderData: keepPreviousData`(페이지네이션 UX).
**REFACTOR**. queryKey 빌더 + JSDoc.
**검증**. `pnpm --filter web test -- useAuditLogsQuery`.

### Task 7. W/components/admin/AuditLogTable.tsx — 결과 테이블

**메타**.
- agent: `frontend-engineer`
- files: [`W/components/admin/AuditLogTable.tsx`, `W/components/admin/AuditLogTable.test.tsx`]
- depends-on: [3, 4]
- shared util: `W/lib/datetime.ts`의 `formatDateTime` 재사용(FR-AU-08 D6서 신설, 존재 확인 후 import. 부재 시 files 추가).

**RED**. 컴포넌트 테스트(RTL) — 행 렌더(시각 포맷·이벤트 라벨(authEventTypeLabels)·주체(displayName>username>"(알 수 없음)")·provider·IP(null→"—")·metadata 요약), 빈 목록 → 빈상태, 로딩 → 스켈레톤/표시. eventType 미지(라벨 없는 값) → 원문 표시(전방호환).
**GREEN**. 테이블(기존 admin 테이블 className 관례). 주체 셀 = displayName ?? username ?? labels.unknownSubject. metadata는 key=value 요약(JSON.stringify 축약). 날짜 = formatDateTime.
**REFACTOR**. 행 서브컴포넌트 + JSDoc.
**검증**. `pnpm --filter web test -- AuditLogTable`.

### Task 8. W/components/admin/AuditLogFilters.tsx — 필터 (이벤트/기간/사용자 typeahead)

**메타**.
- agent: `frontend-engineer`
- files: [`W/components/admin/AuditLogFilters.tsx`, `W/components/admin/AuditLogFilters.test.tsx`]
- depends-on: [3, 4]

**RED**. 컴포넌트 테스트 — eventType `Select`(전체+12종) onChange→onFilterChange, 시작일/종료일 `input[type=date]`→onFilterChange(date→ISO instant, to는 종료시각), 사용자 typeahead(useUserSearch, AddMemberDialog SearchResultList 선례)→선택 시 userId 세팅·선택칩·해제, 초기화 버튼→전체 리셋. 제어 컴포넌트(value props). **props 초기화 시 key prop/제어값 일관**(react-usestate-stale-key-prop 회피 — 내부 state 최소화, 제어형).
**GREEN**. `Select`(ui 래퍼 실재) + date input + 사용자 typeahead(useUserSearch 재사용). 날짜→Instant 변환 헬퍼(시작 00:00:00Z / 종료 23:59:59.999Z). onFilterChange(partial)로 부모에 전달. 사용자 선택 = username 표시 + userId 값.
**REFACTOR**. 변환 헬퍼 분리 + JSDoc(from/to 경계 의미).
**검증**. `pnpm --filter web test -- AuditLogFilters`.

### Task 9. W/routes/admin.audit-logs.tsx — 페이지 오케스트레이션 + router 등록

**메타**.
- agent: `frontend-engineer`
- files: [`W/routes/admin.audit-logs.tsx`, `W/router.ts`, `W/routes/__tests__/admin.audit-logs.test.tsx`]
- depends-on: [5, 6, 7, 8]

**RED**. 라우트 테스트(MSW) — Page가 Filters+Table+Pagination 조립. 필터 변경 → useAuditLogsQuery 재조회(page 0 리셋). 페이지네이션(이전/다음, 경계 disabled, "N개 중 X–Y"). admin.users.new 선례로 RouteAdapter. router.ts에 `adminAuditLogsRoute`(path `/admin/audit-logs`, `staticData:{requireAuth:true}`, `beforeLoad: composeGuards(requireAuth, requireSystemAdmin)`) 등록.
**GREEN**. Page+RouteAdapter. 필터 state(useState) + page state. 필터 변경 시 page=0. useAuditLogsQuery({...filters, page, size}). 페이지네이션 컨트롤. **router.ts 자기 변경만 stage**(병렬 race 회피). **router.ts 라우트 카운트 주석 동기화**(신규 1 라우트 반영, FR-AU-08 D6 B2 선례).
**REFACTOR**. 페이지네이션 서브컴포넌트 + JSDoc.
**검증**. `pnpm --filter web test -- admin.audit-logs` + `pnpm --filter web typecheck`.

### Task 10. W/components/Header.tsx — admin 메뉴 isSystemAdmin 게이팅 + 감사 로그 링크

**메타**.
- agent: `frontend-engineer`
- files: [`W/components/Header.tsx`, `W/components/Header.test.tsx`]
- depends-on: [9]

**RED**. Header 테스트 갱신 —
- isSystemAdmin=true → "관리 메뉴" nav 노출(워크플로우 스킴 + **감사 로그** 링크, href `/admin/audit-logs`).
- isSystemAdmin=false → "관리 메뉴" nav 전체 **미노출**(기존 line 94~100 단언을 admin 사용자 기준으로 변경 + 비관리자 미노출 케이스 신설).
- 로그아웃 드롭다운은 무관(유지).

**GREEN**. `user?.isSystemAdmin === true`일 때만 `<nav aria-label="관리 메뉴">` 렌더(routeGuard.requireSystemAdmin의 `=== true` 명시비교 일관 — undefined/null/'admin' 오인 방지). 감사 로그 `<Link to="/admin/audit-logs">`.
**REFACTOR**. admin 링크 배열 추출(확장 대비 최소).
**검증**. `pnpm --filter web test -- Header`.
**회귀 주의**. 워크플로우 스킴 E2E는 `page.goto` 직접 URL 진입(nav 링크 미사용) → 게이팅 영향 0(사전 확인 완료). Header.test.tsx 외 회귀 없음.

### Task 11. E/audit-logs.spec.ts — Playwright E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`E/audit-logs.spec.ts`, `E/fixtures/audit-log-fixtures.ts`(필요 시)]
- depends-on: [5, 10]

**RED→GREEN**. Playwright(MSW 브라우저, `serviceWorkers:'block' 금지` e2e-msw-serviceworker-block). 시나리오 —
- S1 admin 메뉴+조회 — `loginAsSystemAdmin`(fr-au-05-signup 선례, `__bts_e2e_is_system_admin` 플래그) → Header "감사 로그" 링크 노출 → 클릭 → `/admin/audit-logs` → 테이블 행 표시.
- S2 이벤트유형 필터 — Select LOGIN_FAILURE → 해당 행만.
- S3 페이지네이션 — 다음/이전, "N개 중" 표시.
- S4 비관리자 미노출 — `loginAsAlice`(isSystemAdmin:false 기본) → Header "감사 로그"/"관리 메뉴" 미노출 + `/admin/audit-logs` 직접 진입 → `/dashboard` redirect(requireSystemAdmin).
- 사용자 필터(typeahead) S5(선택) — username 검색 → 선택 → 필터.
텍스트 중복 시 컨테이너 한정/exact(playwright-getbyrole-exact-strict-mode). **fixture userId는 whoami userId 실측(grep) 후 정합**(e2e-fixture-whoami-userid-alignment). 기존 auth+workflow E2E 회귀 0(ui-pr-defer-e2e-regression-latent).
**검증**. `pnpm --filter web test:e2e -- audit-logs` + 기존 auth/workflow E2E 회귀 0.

## Plan 메타

- task 수: 11 (백엔드 2 + 프론트 8 + E2E 1)
- wave: 백엔드 직렬(T1→T2 모듈 test 컴파일 공유) → 프론트 직렬(T3·T4 무의존 먼저, T5/T6←3, T7/T8←[3,4], T9←[5,6,7,8], T10←9) → E2E(T11←[5,10]). 단일 백엔드 모듈 + 단일 프론트 worktree라 사실상 직렬.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 마이그레이션 0. 신규 권한코드 0. cross-BC 0(identity-access read view layer + 그 프론트). 신규 ADR 0.
- 트랩 인코딩: Zod↔DTO 1:1(T3), eventType 전방호환 z.string()+12종 라벨 정합(T3/T4), SQL 인젝션 named param(T1), 400 message 미노출(T2), catch-all advice 점검(T2), Header 게이팅 회귀 사전확인(T10), E2E admin 로그인 재사용·userId 정합(T11), subagent ktlint/detekt 직접 재검증(T2).
- FR 동기화(머지 시): product §2.10 D6/D7 [x] + FR-AU-10 완료 노트 + (FR-AU-10이 §2 마지막 미완 FR이면) BC 완료 영향. fr-index/SDD §19.9/README/dashboard. FR 총수 불변(D6/D7는 기존 FR 완성, 신규 FR 0). verify-master-plan.sh 통과 필수.

## 리뷰 결과

(게이트1 전 — bts-review-plan: security + 프론트 eng 독립 ground-truth 리뷰 예정. 아래는 본 plan 작성 시 자체 적대적 점검.)

### 자체 적대적 점검 (2026-06-10, plan 작성 시)

- **환각 점검(실재 확인)**. `JdbcAuthAuditLogService.deserializeMetadata`·`NamedParameterJdbcTemplate`·users(username/display_name, V001)·`AuthEventType`(12종)·`@PreAuthorize hasRole('SYSTEM_ADMIN')`(UsersController)·`apiGet`/`fetchUsers`/`readXsrfToken`·`useUserSearch`(@/hooks/use-user-directory)·`Select` ui 래퍼·`composeGuards`/`requireSystemAdmin`·`loginAsSystemAdmin`(fr-au-05)·`formatDateTime`(W/lib/datetime, FR-AU-08 D6 신설) — 전부 실재. `W/lib/datetime.ts` 존재만 T7 착수 시 재확인(부재 시 신설).
- **계약 drift 핵심**. eventType은 entry에서 z.string()(전방호환), 필터/라벨은 AUTH_EVENT_TYPES 12종 const(백엔드 enum 미러). 백엔드 enum 추가 시 라벨 누락만 발생(파싱 무파손) → T4 카운트 정합 테스트가 1차 가드. enum-add-breaks-crossmodule-count-guard 인지.
- **보안**. 이중 가드(filter authenticated + @PreAuthorize). PAT 403(ROLE_PAT≠SYSTEM_ADMIN). 400 message 미노출. SQL 인젝션 named param. PII 응답 포함이나 로그 금지. open-redirect/계정열거 무관(read admin).
- **회귀**. Header 게이팅 → workflow-scheme E2E는 goto 진입이라 무영향(확인). Header.test.tsx만 갱신(T10).
