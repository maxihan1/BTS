# FR-PR-02 상태 메시지 (이모지 + 텍스트)

> slug: fr-pr-02-user-statuses
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> BC: personalization (물리 모듈: identity-access)
> 생성: 2026-07-06

## Brief

FR-PR-02 — 사용자 상태 메시지(이모지 + 텍스트). Slack 스타일 개인 상태 표시.

- D1. 도메인 — UserStatus
- D2. 명세 — TTL(만료) 옵션
- D3. 데이터 모델 — `user_statuses(user_id, emoji, text, expires_at)`
- D4. 백엔드 — `PATCH /api/v1/users/me/status`
- D5. 백엔드 테스트
- D6. 프론트 UI — 아바타 옆 상태 + 설정 모달
- D7. E2E

선행: §2.1 FR-PR-01(프로필) 완료. classify E2E 키워드로 qa 오판 → feature 수동 정정.

## 도메인 정리

- **BC**: personalization (논리) / identity-access (물리 모듈) — FR-PR-01 ADR D1 계승
- **영향 엔티티**: `UserStatus` (신규, User와 1:1)
- **새 용어**: "상태 메시지 (Status Message)" — 이모지 + 텍스트 + 만료로 구성된 개인 상태 (Slack 스타일). glossary 추가 후보(머지 시 sync)
- **데이터 모델**: `user_statuses(user_id PK/FK, emoji NULL, text NULL, expires_at NULL, updated_at)` — user_profiles 미러, ON CONFLICT UPSERT
- **핵심 결정 (Maxi 확정)**:
  - TTL = **클라이언트 절대시각**(`expiresAt` ISO Instant/null 그대로 저장, 프론트가 프리셋→시각 변환). 만료는 조회 시 lazy 필터.
  - 검증 = 이모지·텍스트 **최소 하나**. 둘 다 빈값 → 상태 해제(원자적 replace 시맨틱, 3-state 병합 아님).
  - whoami view-layer +상태 노출(아바타 옆 표시, FR-PR-01 선례).
- **기존 결정 충돌**: 없음. FR-PR-01 패턴 계승.
- **관련 ADR**: [docs/decisions/2026-07-06-fr-pr-02-user-status.md](../decisions/2026-07-06-fr-pr-02-user-status.md) (생성됨)
- **회귀 주의**: V028 마이그레이션 번호 머지 직전 재확인(동시 브랜치 충돌). whoami mock fanout(`.nullable().optional()`, FR-PR-01 D6 선례).

## 스펙

전체 스펙. [docs/specs/2026-07-06-fr-pr-02-user-status.md](../specs/2026-07-06-fr-pr-02-user-status.md)

핵심 시나리오 3줄 요약.
- `PATCH /api/v1/users/me/status`로 이모지·텍스트·만료시각을 원자적 교체. 둘 다 빈값이면 상태 해제(row 삭제).
- 만료는 조회 시 lazy 필터(`expires_at > now()`). whoami가 활성 상태만 statusEmoji/statusText로 노출.
- 프론트: 계정 드롭다운 "상태 설정" 모달(프리셋→expiresAt 순수함수) + Header 아바타 상태 배지, 저장 후 whoami 재조회로 즉시 반영.

**스코프**: 풀스택 D1~D7 단일 PR(동일 BC, 소규모). FR-PR-01은 백엔드/프론트 분리했으나 FR-PR-02는 MinIO 없이 작아 통합.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 3건: whoami 경로 정정 / 마이그레이션 가드는 테이블별(전역 아님) / Avatar 배지는 Header 오버레이).

## Plan

> 패키지: `com.atlas.bts.identity.status` (신규, FR-PR-01 `profile` 미러). 프론트: `apps/web/src`.
> 백엔드(T1~T5)와 프론트(T6~T10)는 대부분 독립 — wave 병렬화 대상.

### Task 1. V028 user_statuses 마이그레이션 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V028__user_statuses.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/status/UserStatusesSchemaTest.kt`]
- depends-on: []

**RED**: `UserStatusesSchemaTest` — Testcontainers 부팅 후 `user_statuses` 테이블/컬럼(user_id PK/FK, emoji varchar(32), text varchar(100), expires_at timestamptz, created_at, updated_at) + `ON DELETE CASCADE` + `CHECK (emoji IS NOT NULL OR text IS NOT NULL)` 존재를 information_schema로 검증. 테이블 없음 → 실패. `UserProfilesSchemaTest.kt` 미러.

**GREEN**: `V028__user_statuses.sql` 작성(스펙 §데이터 모델 DDL 그대로).

**REFACTOR**: 컬럼 주석(SQL `COMMENT`) + DDL 정렬.

**검증**: `./gradlew :backend:identity-access:test --tests '*UserStatusesSchemaTest'`. **머지 직전 V번호 재확인**(동시 브랜치 충돌).

### Task 2. UserStatus 도메인 + Repository (Jdbc)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/status/UserStatus.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/status/UserStatusRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/status/JdbcUserStatusRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/status/JdbcUserStatusRepositoryTest.kt`]
- depends-on: [1]

**RED**: `JdbcUserStatusRepositoryTest`(Testcontainers) — `upsert`(INSERT ON CONFLICT), `findActiveByUserId`(만료 필터 `expires_at IS NULL OR expires_at > now()`), `deleteByUserId`(멱등), 만료행은 findActive에서 null. 클래스 없음 → 실패.

**GREEN**: `UserStatus(userId, emoji, text, expiresAt: Instant?)` data class. `UserStatusRepository` 인터페이스 + `JdbcUserStatusRepository`(NamedParameterJdbcTemplate, `@Repository @Transactional`, FR-PR-01 `JdbcUserProfileRepository` 패턴). SQL: UPSERT(전 컬럼 SET — replace), `findActiveByUserId`(만료 필터), `deleteByUserId`.

**REFACTOR**: SQL 상수 companion + RowMapper + KDoc.

**검증**: `./gradlew :backend:identity-access:test --tests '*JdbcUserStatusRepositoryTest'`.

### Task 3. UserStatusService (조회/replace/clear + 검증)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/status/UserStatusService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/status/UserStatusServiceTest.kt`]
- depends-on: [2]

**RED**: `UserStatusServiceTest`(mockk repo) — `getActiveStatus` 미설정→all-null / `setStatus` emoji·text blank 정규화→null / 둘 다 null→`deleteByUserId`(해제) / 최소 하나 있으면 upsert / text>100→ValidationException / emoji>32→ValidationException / expiresAt 과거→ValidationException. 클래스 없음 → 실패.

**GREEN**: `@Service UserStatusService`. `getActiveStatus(userId): StatusView`(repo.findActiveByUserId, 없으면 all-null view). `setStatus(userId, patch): StatusView`(blank→null 정규화 → 둘 다 null이면 delete, 아니면 검증 후 upsert). `StatusView(emoji?, text?, expiresAt?)`, `StatusPatch(emoji?, text?, expiresAt?)`, `StatusValidationException`. `@Transactional`(부분 적용 방지, FR-PR-01 선례).

**REFACTOR**: 검증 helper + KDoc(트랜잭션 경계 명시).

**검증**: `./gradlew :backend:identity-access:test --tests '*UserStatusServiceTest'`.

### Task 4. UserStatusController + DTO (GET/PATCH /me/status)

**메타**.
- agent: `backend-engineer` (권한 가드는 security-engineer 검토 대상 — JWT subject 식별)
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UserStatusController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/StatusResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/StatusPatchRequest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserStatusControllerTest.kt`]
- depends-on: [3]

**RED**: `UserStatusControllerTest`(MockMvc slice, `@Import` MockSecurityBeans, jwt 주입) — GET 200 형태 / PATCH 200 replace / 해제 all-null / text 초과 400 / 과거 expiresAt 400 / PAT(jwt=null)→401. 클래스 없음 → 실패.

**GREEN**: `@RestController @RequestMapping("/api/v1/users") UserStatusController`. `getMyStatus`(GET `/me/status`) / `patchMyStatus`(PATCH `/me/status`). `currentUserId(jwt)`(FR-PR-01 선례, PAT→401). `StatusPatchRequest(emoji, text, expiresAt: Instant?)` 평이 nullable(replace). `StatusResponse(emoji, text, expiresAt)`. 로컬 `@ExceptionHandler(StatusValidationException)`→400(catch-all 변질 가드, FR-PR-01 선례).

**REFACTOR**: DTO 변환 helper + KDoc.

**검증**: `./gradlew :backend:identity-access:test --tests '*UserStatusControllerTest'`.

### Task 5. whoami view-layer +statusEmoji/statusText

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/WhoamiResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt`]
- depends-on: [2]

**RED**: `WhoamiControllerTest`(기존) — JWT 분기 활성 상태 사용자 → `statusEmoji`/`statusText` 채워짐, 만료/미설정 → null, PAT → null. 필드 없음 → 실패.

**GREEN**: `WhoamiResponse`에 `statusEmoji: String?`, `statusText: String?` 추가. `WhoamiController`에 `UserStatusRepository` 주입 + `statusFor(userId)` helper(`findActiveByUserId` → emoji/text, avatarUrlFor 선례). PAT 분기는 둘 다 null 고정.

**REFACTOR**: helper KDoc.

**검증**: `./gradlew :backend:identity-access:test --tests '*WhoamiControllerTest'`. (depends [2]만 — T3/T4와 병렬 가능, 파일 무겹침)

### Task 6. 프론트 계약층 — api/Zod schema/MSW mocks

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/status.ts`, `apps/web/src/api/status.test.ts`, `apps/web/src/api/useStatus.ts`, `apps/web/src/api/useStatus.test.ts`, `apps/web/src/api/schemas.ts`, `apps/web/src/mocks/status-handlers.ts`, `apps/web/src/mocks/status-fixtures.ts`, `apps/web/src/mocks/status-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: `status.test.ts`/`useStatus.test.ts`/`status-handlers.test.ts`(MSW) — GET/PATCH 파싱, replace/clear stateful 오버라이드 영속(memory: msw-mutation-stateful-refetch), Zod 파싱. 모듈 없음 → 실패.

**GREEN**: `api/status.ts`(apiFetch GET/PATCH), `useStatus.ts`(react-query `useStatusQuery`/`useUpdateStatusMutation`). `schemas.ts`에 `statusResponseSchema` + **WhoamiResponse 스키마에 `statusEmoji`/`statusText` `.nullable().optional()` 추가**(mock fanout 방어, memory). `mocks/status-handlers.ts`+`status-fixtures.ts`(시드 가능 공유 store, stateful). `mocks/handlers.ts`에 등록 + **기존 whoami 핸들러에 statusEmoji/statusText 추가**(grep으로 위치 확인).

**REFACTOR**: 핸들러 store 헬퍼 정리.

**검증**: `pnpm --filter web test -- status`.

### Task 7. 프리셋→expiresAt 순수함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/status-expiry.ts`, `apps/web/src/lib/status-expiry.test.ts`]
- depends-on: []

**RED**: `status-expiry.test.ts` — `resolveExpiry(preset, now)`: 'none'→null / '30m'→now+30분 / '1h' / '4h' / 'today'→로컬 당일 끝 / 'week'→로컬 이번 주 끝. 함수 없음 → 실패.

**GREEN**: `resolveExpiry(preset: ExpiryPreset, now: Date): string | null`(ISO Instant). `now` 주입(테스트 결정성, jsdom/실브라우저 무관).

**REFACTOR**: 프리셋 상수 + KDoc.

**검증**: `pnpm --filter web test -- status-expiry`.

### Task 8. 상태 설정 모달 + i18n

**메타**.
- agent: `frontend-engineer` (designer 스펙 불요 — 기존 Dialog/Input 토큰 재사용, DESIGN.md 준수)
- files: [`apps/web/src/components/status/StatusModal.tsx`, `apps/web/src/components/status/StatusModal.test.tsx`, `apps/web/src/i18n/status-labels.ts`, `apps/web/src/i18n/status-labels.test.ts`]
- depends-on: [6, 7]

**RED**: `StatusModal.test.tsx` — 이모지/텍스트 입력 + 프리셋 select + 저장 시 `useUpdateStatusMutation` 호출(resolveExpiry 적용), 해제 버튼(빈값 PATCH), 저장 성공 시 whoami 재조회+setUser(store 갱신), 검증(둘 다 빈값 시 저장=해제). 컴포넌트 없음 → 실패.

**GREEN**: `StatusModal`(Radix Dialog + Input + Select, DESIGN.md 토큰). 저장 핸들러가 resolveExpiry→mutation→onSuccess whoami refetch+setUser(FR-PR-01 D6 Header 연동 패턴). `status-labels.ts` i18n 상수(프리셋 라벨/플레이스홀더). **Radix controlled Dialog 토글닫기 stale 주의**(memory: react-usestate-stale-key-prop / onOpenChange).

**REFACTOR**: 프리셋 옵션 상수 + a11y(라벨/포커스).

**검증**: `pnpm --filter web test -- StatusModal`.

### Task 9. Header 상태 배지 + 모달 결선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`]
- depends-on: [6, 8]

**RED**: `Header.test.tsx`(기존 확장) — 활성 상태(user.statusEmoji) 시 아바타 상태 배지 렌더, 없으면 미렌더, 드롭다운 "상태 설정" 항목 클릭→StatusModal 오픈. 실패.

**GREEN**: 아바타를 상대 컨테이너로 감싸 상태 이모지 배지 오버레이(우하단, title=statusText). 드롭다운에 "상태 설정" `DropdownMenuItem` + 모달 open state. StatusModal 렌더.

**REFACTOR**: 배지 컴포넌트 추출(선택) + aria.

**검증**: `pnpm --filter web test -- Header`.

### Task 10. Playwright E2E

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/status.spec.ts`]
- depends-on: [9]

**RED**: `status.spec.ts` — (1) 상태 설정→헤더 아바타 배지에 이모지 표시, (2) 해제→배지 사라짐, (3) 프리셋 선택 저장, (4) 검증(둘 다 빈값 저장=해제), (5) whoami mock fanout 회귀(기존 헤더/프로필 E2E 그린 유지, memory: ui-pr-defer-e2e-regression-latent). MSW 시나리오 시드.

**GREEN**: E2E 통과까지 MSW 핸들러/시드 조정(구현 코드 수정 금지 — qa 범위).

**검증**: `pnpm --filter web test:e2e -- status`.

## Plan 메타

- task 수: 10 (백엔드 5 + 프론트 4 + E2E 1)
- 예상 wave: 4~5 (백엔드 T1→T2→{T3‖T5}→T4 체인, 프론트 {T6‖T7}→T8→T9, E2E T10 마지막. 백/프론트 병렬)
- TDD 강제: yes (test 커밋 선행 자동 검증)
- 병렬 dispatch: depends-on + files 교집합으로 wave 계산
- 추가 검증: ktlint/detekt/ArchUnit(백) + pnpm lint/typecheck/test/e2e(프론트)
- 회귀 주의: V028 번호 재확인 · whoami Zod fanout · MSW stateful · Radix Dialog stale · text 컬럼(PG 비예약어, 사용 가능)

## 리뷰 결과

> 저위험 plan(FR-PR-01 미러·격리 테이블·인증 표면 최소) → autoplan 4-phase 대신 **eng + design 집중 리뷰**(memory: bts-review-plan-autoplan-overkill).

### plan-eng-review (2026-07-06)

**Step 0 스코프 챌린지.**
- 기존 코드 재사용: FR-PR-01 전 계층(JdbcTemplate repo·service·controller·whoami view-layer·프론트 api/mocks/i18n/Header)이 그대로 template. 병렬 인프라 신설 0 — DRY 강함.
- 최소 변경: D1~D7 전부 기능 성립에 필요. deferrable 항목 없음. 신규 의존성 0(이모지 피커 라이브러리 미도입 → 네이티브 입력, boring-by-default).
- **⚠️ 복잡도 스멜(Maxi 결정 사안)**: 10 task / ~25 파일 / 신규 클래스 6개(UserStatus·Repository·JdbcRepository·Service·Controller·StatusModal)는 단일-PR 스멜 임계(8파일/2클래스) 초과. FR-PR-01은 백엔드(#238)/프론트(#239) 분리 선례. → **Gate 1에서 단일 풀스택 PR vs 백엔드/프론트 분리 결정**. 권장: 단일 PR 수용 가능(로직 사소·미러 패턴 검증됨·파일당 diff 작음), 단 Maxi 판단.

**Architecture / Quality.**
- ✅ replace 시맨틱이 FR-PR-01 3-state(JsonNode)보다 단순 — StatusPatchRequest 평이 nullable. 우발적 복잡도 회피(Brooks).
- ✅ 만료 lazy 필터(스케줄러 0) — 1K 규모에 boring 선택. whoami·GET 공통 `findActiveByUserId` SQL로 DRY.
- ✅ CHECK(emoji OR text) + upsert(≥1 non-null일 때만) + delete(해제) 상호작용 정합 — CHECK 위반 경로 없음.
- ⚠️ T5 whoami depends [2](repo만) — T3/T4와 병렬 가능, WhoamiController/Response 파일 무겹침. wave 안전. 단 새 repo 주입이 whoami full-boot 테스트에 `@MockBean` 필요할 수 있음(memory: new-crossbc-dep-openapi-mockbean 유사 — 동일 모듈이라 위험 낮으나 T5 verifier 확인).
- ⚠️ `text` 컬럼명 = PG 비예약어(사용 가능). SQL에서 혼동 없게 명시적 컬럼 리스트 사용(select *).

**Tests.**
- ✅ 전 task RED-first TDD. 스키마(Testcontainers)·repo 통합·service 단위(mockk)·controller slice·whoami·프론트 단위·E2E 커버. under-tested 없음.
- ⚠️ 만료 필터는 `now()` 의존 — repo 통합테스트에서 과거 expires_at 시드로 검증(T2 RED에 명시됨). 실시간 `now()` flakiness 방지 위해 충분한 과거값 사용.
- BLOCKER: 없음.

### plan-design-review (2026-07-06)

- ✅ 상태 배지 = 아바타 우하단 오버레이(DESIGN.md 토큰, Avatar 시그니처 불변). a11y: 배지 `title=statusText` + aria(T9 명시).
- ⚠️ StatusModal Radix controlled Dialog 토글닫기 stale 함정(memory: react-usestate-stale-key-prop·onOpenChange 미발화) — T8에 명시됨. useEffect([open]) 초기화 준수.
- ⚠️ 이모지 입력 = 네이티브(피커 라이브러리 없음). 데스크톱서 "이모지 타이핑" UX 약함 → status-labels에 추천 이모지 quick-pick 상수 세트 제공 권장(T8, 신규 의존성 없이 UX 보강). 선택.
- ✅ 프리셋(안 지움/30분/1시간/4시간/오늘/이번 주) = Slack 표준. i18n 상수화.
- BLOCKER: 없음.

**종합**: BLOCKER 0. 유일한 Maxi 결정 = PR 스코프(단일 vs 분리). 나머지는 반영 완료 또는 구현 단계 verifier 체크 항목.
