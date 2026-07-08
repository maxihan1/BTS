# FR-PF-03 개인화 단축키 커스터마이즈

> slug: fr-pf-03-keymap-customize
> type: feature
> agent: backend-engineer (주) · db-engineer · frontend-engineer · qa-engineer
> primary_bc: personalization (물리 모듈 identity-access)
> 생성: 2026-07-08

## Brief

FR-PF-03 — 단축키 커스터마이즈. 사용자가 FR-UX-05에서 하드코딩된 전역 단축키(`?`·`c`·`/`·`g i`·`g d`)를 자기 취향대로 재배치(reassign)하고 백엔드(`user_keymap`)에 저장. 충돌 검출 포함.

**classify**. type=qa 오판(E2E 키워드) → product 문서 D1~D7 근거로 feature/backend-engineer 정정. 선례 FR-UX-04·FR-TL-03.

**product 문서 §3.3 D1~D7**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — 충돌 검출 (backend-engineer)
- D3. 데이터 모델 — `user_keymap(action, key_combo)` (db-engineer)
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/keymap` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 단축키 설정 + 실시간 reassign (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**선행 완료**. FR-UX-05(전역 5종 커스텀 훅 `useKeyboardShortcuts` + `shortcuts.ts` SHORTCUTS 상수), FR-PF-01(user_preferences), FR-PR-01(user_profiles, personalization 첫 백엔드 → identity-access 물리 모듈).

## 도메인 정리

- **BC**. 논리 personalization / 물리 identity-access (FR-PR-01·PF-01·PF-02 선례 유지)
- **영향 엔티티**. UserKeymap (신규) — 사용자별 action→key_combo 오버라이드 집합
- **새 용어** (glossary 추가 후보, Maxi 승인 대기).
  - `action` — 단축키가 실행하는 논리적 동작의 안정 식별자. 5종 화이트리스트(`help`·`create-issue`·`search`·`goto-my-issues`·`goto-dashboard`). 프론트 SHORTCUTS ↔ 백엔드 화이트리스트 SSOT.
  - `key_combo` — action에 배정된 키 시퀀스 정규화 문자열. single(`c`) 또는 leader(`g i`).
  - `keymap 충돌` — 두 action이 같은 key_combo(완전 중복) 또는 single↔leader 접두로 겹침.
- **스코프** (Maxi 결정 2026-07-08). 전역 5종 전부 커스터마이즈(도움말 `?` 포함) + single↔leader 자유 변환.
- **데이터 모델**. `user_keymap(user_id, action, key_combo)` V033, UNIQUE(user_id, action), action CHECK 화이트리스트, override 패턴(행 없으면 프론트 기본값). identity-access V032 최신 → V033(머지 직전 재확인).
- **충돌 검출**. (1) 완전 중복 (2) leader 접두 충돌 + 빈 key_combo 금지. 백엔드 PATCH가 SSOT, 프론트 실시간 복제.
- **정규화 전제**. FR-UX-05 `shortcuts.ts` SHORTCUTS에 안정 action ID 부여 + `resolveKeydown`이 "기본값+override 병합" 키맵 참조하도록 확장(same-BC view-layer).
- **기존 결정 충돌**. 없음. FR-UX-05 ADR이 커스텀 키맵을 명시적으로 FR-PF-03에 위임.
- **관련 ADR**. [docs/decisions/2026-07-08-fr-pf-03-keymap-customize.md](../decisions/2026-07-08-fr-pf-03-keymap-customize.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-08-fr-pf-03-keymap-customize.md](../specs/2026-07-08-fr-pf-03-keymap-customize.md)

핵심 요약.
- 5종 action(`help`·`create-issue`·`search`·`goto-my-issues`·`goto-dashboard`)의 키만 재배치, 동작은 고정.
- key_combo 형식: single(1글자) 또는 `g <key>` leader. leader 키 `g` 고정. single↔leader 자유 변환.
- 충돌 검출 6종(백엔드 SSOT): 화이트리스트·형식·빈값·완전중복·leader접두·dead(`g g`). 위반 400/409.
- `GET/PATCH /me/keymap` replace-all, JWT-only, override 정규화(기본값=행 미저장). PreferencesController 미러.
- `user_keymap(user_id, action, key_combo)` V033, PK(user_id,action), action CHECK.
- 프론트 SHORTCUTS에 action ID 부여 + resolveKeydown 병합 키맵 참조(FR5-a 도움말 닫기 키 동기화 포함).
- `/settings/keymap` UI, 실시간 충돌·기본 복원. 저장 후 invalidate 실시간 반영.

**FR5-b 확정 (plan)**. 초기 로드 override 반영 경로 = **(b) 앱 부트 시 `GET /me/keymap` + react-query 캐시**.
근거 — (1) whoami에 5종 배열을 얹으면 whoami 슬라이스 테스트 전수 mock fanout이 큼([[whoami-slice-mock-skipci-masking]] 잠복 은폐 위험), (2) keymap은 시각 렌더 블로킹이 아니라 FOUC 없음, (3) FR1 `GET /me/keymap` 재사용으로 whoami 경량 유지. whoami 미변경 → 슬라이스 mock fanout 0.

## Brainstorming Check

✅ 통과 (self adversarial sanity check, 1회 iteration). gap 3건 발견 후 스펙 보강.
- G1 → FR5-a: `help` 재배치 시 도움말 닫기 키 하드코딩(`?`) 동기화.
- G2 → FR3-6: `g g` dead leader combo 형식 거부.
- G3 → FR5-b: 초기 로드 override 반영 경로(whoami vs 부트 GET) plan 확정 위임.

## Plan

> 백엔드는 JdbcTemplate(identity-access, init_codegen/jOOQ codegen 없음 — preferences 선례). 순수 도메인(T2)만 DB 무관.
> 같은 identity-access 모듈 backend task(T2~T5)는 Gradle 모듈 컴파일이 직렬화되므로([[bts-plan-wave-gradle-module-compile]]) wave 병렬 이득은 제한적, 그래도 TDD 단위로 분리.

### Task 1. V033 user_keymap 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V033__user_keymap.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/migration/SchemaMigrationTest.kt`]
- depends-on: []

**RED**: `SchemaMigrationTest`(있으면)에 user_keymap 테이블 존재 + PK + action CHECK 검증 추가 → 테이블 없어 실패. (SchemaMigrationTest 부재 시 Testcontainers Flyway 부팅 테스트로 RED.)
**GREEN**: `V033__user_keymap.sql` — 스펙 §데이터 모델 DDL 그대로. `PK(user_id, action)`, `action CHECK IN (5종)`, `FK users(id) ON DELETE CASCADE`, `key_combo VARCHAR(16) NOT NULL`, created/updated_at.
**REFACTOR**: 컬럼 주석 + CHECK 제약 이름 명시.
**함정**: identity-access는 init_codegen/jOOQ codegen 미러 불필요(JdbcTemplate). 마이그레이션 카운트 가드 테스트가 있으면 카운트도 +1 동기화([[fr-pm-permission-seed-migration-test-coupling]]). V번호는 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).
**검증**: `./gradlew :modules:identity-access:test --tests '*SchemaMigrationTest*'`

### Task 2. 도메인 순수 로직 — KeymapAction · KeyCombo · 충돌 검출 6종

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/KeymapAction.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/KeymapBinding.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/KeymapValidator.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/keymap/KeymapValidatorTest.kt`]
- depends-on: []

**RED**: `KeymapValidatorTest` — 충돌 검출 6종 각각 실패 케이스. (1)화이트리스트 미완비/불명 action, (2)형식 위반(2글자 single, `g` 없는 leader), (3)빈값, (4)완전중복, (5)leader접두(`g` single + `g X`), (6)dead(`g g`). + 정상 통과 케이스(기본 키맵·재배치·single↔leader 변환).
**GREEN**: `KeymapAction`(enum 5종 + 기본 key_combo + 표시명), `KeymapBinding`(action+keyCombo, trigger 파생 `parse`/`normalize`), `KeymapValidator.validate(bindings): 위반 목록 or throw`.
**REFACTOR**: 검증 규칙을 named 술어로 분리 + KDoc. 기본 키맵 상수는 KeymapAction에 응집(프론트 SHORTCUTS와 값 일치 — spec §배경 표).
**함정**: 순수 로직(DB/Spring 무관)이라 단독 단위 테스트. detektMain type-resolved 엄격([[module-first-scheduled-worker-detektmain-traps]]).
**검증**: `./gradlew :modules:identity-access:test --tests '*KeymapValidatorTest*'`

### Task 3. Repository — user_keymap CRUD (JdbcTemplate)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/UserKeymapRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/JdbcUserKeymapRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/keymap/JdbcUserKeymapRepositoryTest.kt`]
- depends-on: [1]

**RED**: `JdbcUserKeymapRepositoryTest`(Testcontainers) — findByUserId(빈/일부 저장), replaceOverrides(기본값 제외분만 upsert + 기본 복원분 삭제) 검증 → 구현 없어 실패.
**GREEN**: `UserKeymapRepository` 인터페이스 + `JdbcUserKeymapRepository`(NamedParameterJdbcTemplate, `INSERT ... ON CONFLICT (user_id, action) DO UPDATE`, delete for reset). JdbcUserPreferencesRepository 트랜잭션 경계 패턴 미러.
**REFACTOR**: RowMapper 상수화 + SQL 상수 + KDoc.
**함정**: 조인테이블 FK CASCADE 테스트 정리([[join-table-fk-cascade-testcontainers-cleanup]]). Testcontainers singleton lifecycle([[concurrent-testcontainers-suite-flaky]]).
**검증**: `./gradlew :modules:identity-access:test --tests '*JdbcUserKeymapRepositoryTest*'`

### Task 4. Service — UserKeymapService (병합 조회 + 검증우선 PATCH + override 정규화)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/UserKeymapService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/KeymapValidationException.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/keymap/KeymapConflictException.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/keymap/UserKeymapServiceTest.kt`]
- depends-on: [2, 3]

**RED**: `UserKeymapServiceTest` — getKeymap(행 없음→전부 기본, 일부→병합), patchKeymap(검증 실패 시 write 0, 충돌→KeymapConflictException, 정상→override 정규화 저장[기본값과 같은 action 미저장]) → 구현 없어 실패.
**GREEN**: `UserKeymapService`(@Service, @Transactional). getKeymap=repo override + KeymapAction 기본값 병합. patchKeymap=KeymapValidator.validate 먼저(부분 적용 없음, UserPreferencesService 원칙) → 통과 시 기본값과 다른 것만 repo.replaceOverrides. 검증 예외 2종(형식/화이트리스트/빈값=Validation, 충돌=Conflict).
**REFACTOR**: 병합 헬퍼 추출 + KDoc(검증 우선·override 정규화 명시).
**함정**: 검증을 repo 조회/쓰기보다 먼저(UserPreferencesService 선례). self-invocation @Transactional 주의([[transaction-self-invocation-requires-new]]).
**검증**: `./gradlew :modules:identity-access:test --tests '*UserKeymapServiceTest*'`

### Task 5. Controller + DTO — GET/PATCH /me/keymap (JWT-only)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/KeymapController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/KeymapResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/KeymapPatchRequest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/KeymapControllerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/KeymapControllerIntegrationTest.kt`]
- depends-on: [4]

**RED**: `KeymapControllerTest`(@WebMvcTest 슬라이스, MockSecurityBeans) — GET 200 병합 응답, PATCH 200/400(KEYMAP_VALIDATION_FAILED)/409(KEYMAP_CONFLICT), JWT 아니면 401. `KeymapControllerIntegrationTest`(prod+RANDOM_PORT, PAT 403·부분 저장자) → 구현 없어 실패.
**GREEN**: `KeymapController`(@RequestMapping `/api/v1/users`, PreferencesController 미러 — JWT subject currentUserId, 로컬 @ExceptionHandler로 KeymapValidationException→400·KeymapConflictException→409). `KeymapResponse`(bindings: action/keyCombo/trigger/customized), `KeymapPatchRequest`(bindings replace-all).
**REFACTOR**: toResponse/toPatch 헬퍼 + 에러코드 상수(PreferencesController 형식 일관).
**함정**: 새 컨트롤러 슬라이스 test-boot 협력자 전수 mock([[whoami-slice-mock-skipci-masking]] — 단 whoami는 미변경). identity-access prod+RANDOM_PORT 부팅 레시피([[identity-access-prod-randomport-boot-recipe]]). catch-all 없음(401 전파, [[catch-all-exceptionhandler-swallows-responsestatusexception]]). 새 @Service 빈 full-boot 배선([[new-bc-first-repository-testboot-context-regression]] 정신).
**검증**: `./gradlew :modules:identity-access:test --tests '*KeymapController*'`

### Task 6. 프론트 도메인 — SHORTCUTS action ID + resolveKeydown 병합 키맵 참조 (FR5-a 포함)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/keyboard-shortcuts/shortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts`]
- depends-on: []

**RED**: `shortcuts.test.ts` — (1)각 ShortcutDef에 안정 action id 존재, (2)`resolveKeydown(e, pending, helpOpen, keymap)`가 override 키맵으로 발화(c→n 재배치 시 n 발화·c 무동작), (3)FR5-a: help를 다른 키로 재배치 시 도움말 열림 중 그 키로 toggle-help·기존 `?`는 무동작. + 기존 5종 기본 동작 무회귀. → 시그니처/병합 없어 실패.
**GREEN**: `ShortcutDef`에 `action: KeymapActionId` 추가. `resolveKeydown`에 effective keymap 인자 추가 — SHORTCUTS 기본 대신 병합 키맵으로 매칭. helpOpen 분기의 `?` 하드코딩 → help action의 effective combo 참조(FR5-a). leader/single 파싱은 keyCombo 형식(`g X`/단일)에서 유도.
**REFACTOR**: 기본 키맵 상수 export(백엔드 KeymapAction 기본값과 값 일치 — 계약). KDoc 갱신.
**함정**: 기존 shortcuts.test.ts + useKeyboardShortcuts.test.tsx + ShortcutsHelpDialog.test.tsx 무회귀 필수(resolveKeydown 호출부 전수). `?`는 e.key로 판별([[jsdom-browser-textarea-selectionstart]] 계열 주의).
**검증**: `pnpm --filter web test shortcuts && pnpm --filter web test keyboard-shortcuts`

### Task 7. keymap API 클라이언트 + Zod + react-query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/keymap.ts`, `apps/web/src/api/keymap.test.ts`, `apps/web/src/mocks/keymap-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [5]

**RED**: `keymap.test.ts` — `keymapResponseSchema` parse(Task 5 실제 DTO 형식), `useKeymap`(GET query), `useUpdateKeymap`(PATCH mutation, 성공 시 invalidate) → 구현 없어 실패.
**GREEN**: `api/keymap.ts`(apiFetch 기반 GET/PATCH + Zod 스키마 + useQuery/useMutation, preferences.ts 패턴). `mocks/keymap-handlers.ts`(GET/PATCH stateful) + `handlers.ts` 등록.
**REFACTOR**: queryKey 상수 + 타입 export.
**함정**: Zod는 backend DTO invent 금지 — Task 5 KeymapResponse 필드 grep 정합([[frontend-zod-backend-dto-contract-gap]]). Zod `.optional()`/`.nullable()` 신중([[zod-schema-strengthen-inline-mock-fanout]]). MSW mutation stateful([[msw-mutation-stateful-refetch]]). handlers.ts는 이 task만 수정(공유파일 race 회피).
**검증**: `pnpm --filter web test keymap && pnpm --filter web typecheck`

### Task 8. useKeyboardShortcuts 배선 + 앱 부트 keymap 로드 (FR5-b)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/keyboard-shortcuts/useKeyboardShortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/useKeyboardShortcuts.test.tsx`]
- depends-on: [6, 7]

**RED**: `useKeyboardShortcuts.test.tsx` — 로그인 시 useKeymap로 병합 키맵을 resolveKeydown에 전달(override 반영), 비로그인은 기본값(GET 없음, enabled 가드). → 배선 없어 실패.
**GREEN**: `useKeyboardShortcuts`가 `useKeymap`(FR5-b 부트 GET, enabled=로그인) 구독 → effective 키맵을 resolveKeydown 호출에 주입. 로딩 중엔 기본 키맵.
**REFACTOR**: 병합 키맵 useMemo + KDoc(FR5-b 부트 로드 근거).
**함정**: useKeyboardShortcuts는 이미 앱 전역 마운트(위치 grep 확인). 기존 훅 테스트 무회귀. react-query 캐시라 추가 라운드트립 1회.
**검증**: `pnpm --filter web test useKeyboardShortcuts`

### Task 9. /settings/keymap UI — 재배치·실시간충돌·기본복원

**메타**.
- agent: `frontend-engineer` (designer 스펙 불요 — settings.preferences 레이아웃 재사용)
- files: [`apps/web/src/routes/settings.keymap.tsx`, `apps/web/src/routes/__tests__/settings.keymap.test.tsx`, `apps/web/src/components/settings/KeymapForm.tsx`, `apps/web/src/components/settings/KeymapForm.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/i18n/ko.ts`]
- depends-on: [6, 7]

**RED**: `KeymapForm.test.tsx` + `settings.keymap.test.tsx` — 5종 현재 키 표시, 키 캡처 재배치, 실시간 충돌 표시(Task 6 병합/검증 규칙 복제), 기본 복원, 저장 mutation 호출 → 구현 없어 실패.
**GREEN**: `KeymapForm`(action별 행 + 키 캡처 input + 충돌 배지 + 복원 버튼 + 저장), `settings.keymap.tsx`(라우트 페이지+adapter), router.ts에 `settingsKeymapRoute` 등록(requireAuthAndPasswordChanged), i18n 문자열.
**REFACTOR**: 충돌 검사 순수함수 재사용(Task 6 export) + a11y 라벨(WCAG).
**함정**: 키 캡처 중 Esc/Enter 제외(스펙 E6). router.ts는 이 task만 수정. 텍스트 중복 버튼 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]] 대비). CI typecheck는 tsconfig.app([[ci-typecheck-tsconfig-app-vs-local]]).
**검증**: `pnpm --filter web test settings.keymap KeymapForm && pnpm --filter web typecheck`

### Task 10. E2E — 재배치→발화·충돌거부·기본복원·빈값거부

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/keymap.spec.ts`, `apps/web/src/mocks/keymap-handlers.ts`]
- depends-on: [5, 9]

**RED**: `keymap.spec.ts` — /settings/keymap 재배치 저장 후 새 키로 네비게이션 발화, 완전중복·leader접두·빈값 저장 거부 표시, 기본 복원. MSW 시나리오 토글.
**GREEN**: MSW keymap-handlers 시나리오 확장(Task 7 stateful 위에 E2E 시드) + spec 작성.
**REFACTOR**: i18n 셀렉터(hardcoded string 금지) + 공용 로그인 헬퍼.
**함정**: MSW serviceWorker block 금지([[e2e-msw-serviceworker-block]]). fixture userId=whoami 정합([[e2e-fixture-whoami-userid-alignment]]). worktree remove 후 5173 kill([[e2e-orphan-vite-after-worktree-remove]]). 기존 E2E 회귀 동반 확인([[ui-pr-defer-e2e-regression-latent]]). keymap-handlers는 Task 7 이후 확장(depends-on 5,9로 직렬).
**검증**: `pnpm --filter web test:e2e keymap`

## Plan 메타

- task 수: 10 (db 1 · backend 4 · frontend 4 · qa 1)
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산. 예상 — Wave1[T1·T2·T6 독립] → T3 → T4 → T5 → T7 → T8·T9 → T10. 단 identity-access backend(T2~T5)는 모듈 컴파일 직렬화.
- 공유 파일 명시(race 회피): shortcuts.ts=T6만 · useKeyboardShortcuts.ts=T8만 · router.ts=T9만 · handlers.ts=T7만 · keymap-handlers.ts=T7생성/T10확장(직렬) · i18n=T9만.
- 추가 검증: ktlint/detekt(backend), typecheck(tsconfig.app)/vitest/playwright(frontend).
- 전수 동기화 대상(머지 전): fr-index FR-PF-03 D체크박스·product §3.3 D단계·SDD 20/02·README·verify-master-plan.sh.

## 리뷰 결과 (← /bts-review-plan 채움)
