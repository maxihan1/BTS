# FR-DB-03 — 대시보드 공유 (URL, 임베드)

> slug: fr-db-03-dashboard-share
> type: feature
> agent: backend-engineer (D2 공개토큰/권한 = security-engineer 공동검토)
> BC: notification-dashboard (`backend/modules/notification`, `com.bts.notification.dashboard`)
> 생성: 2026-07-02

## Brief

FR-DB-03 대시보드 공유 (공유 URL 토큰 + iframe 임베드 + 권한). product 문서 §3.3.

- D1. 도메인 (backend-engineer)
- D2. 명세 — 공유 URL + iframe 임베드 + 권한 (backend-engineer + security-engineer 공동)
- D3. 데이터 모델 — `dashboard_share_tokens` (db-engineer)
- D4. 백엔드 — `POST /api/v1/dashboards/{id}/share` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 공유 모달 + 임베드 코드 복사 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 정정 (Maxi 확정 2026-07-02)**. classifier가 "권한/토큰" 키워드로 auth/identity-access 오분류
→ product §3.3 기준 notification-dashboard BC, backend-engineer 주도로 정정.
공개 토큰·권한(D2)만 security-engineer 공동검토.

**선행**. FR-DB-01(대시보드 CRUD·visibility PRIVATE/TEAM·PUBLIC은 FR-DB-03로 미룸) / FR-DB-02(가젯) 완료.

## 도메인 정리

- **BC**: notification-dashboard (`backend/modules/notification`, `com.bts.notification.dashboard`)
- **영향 엔티티**:
  - Dashboard (Aggregate Root, 기존) — 공유 토큰 발급/취소 진입점 (aggregate 통해서만 변경)
  - DashboardShareToken (**신규** 자식 엔티티) — (id, dashboardId, tokenHash SHA-256, createdBy, expiresAt?, revokedAt?)
  - DashboardVisibility (기존 enum) — **불변** (PRIVATE/TEAM/ORG 유지, PUBLIC 추가 안 함)
- **새 용어**: "공유 토큰"(Dashboard Share Token) — 대시보드를 비로그인 URL로 공유하는 불투명 토큰. Maxi 승인 후 머지 시 glossary 동기화.
- **핵심 도메인 결정 (Maxi 확정 2026-07-02)**:
  - D1. 직교 토큰 모델 (visibility enum 미확장) — 갈림길 1
  - D2. 익명/임베드 뷰 = 정적 가젯(text_widget/link_list)만, 데이터 가젯은 "로그인 필요" 플레이스홀더 — 갈림길 2
- **기존 결정 충돌**: 없음. FR-DB-01 ADR의 "PUBLIC(URL 토큰)은 FR-DB-03 범위"를 본 작업이 구체화.
- **관련 ADR**: [docs/decisions/2026-07-02-fr-db-03-dashboard-share.md](../decisions/2026-07-02-fr-db-03-dashboard-share.md) (생성됨)
- **보안 핵심**: BTS 첫 **비인증(anonymous) 읽기 경로** 도입 — SecurityFilterChain 화이트리스트 + 토큰 검증 + iframe 임베드 응답 한정 X-Frame-Options/CSP 완화. D2·D4는 security-engineer 공동검토.

## 스펙

전체 스펙. [docs/specs/2026-07-02-fr-db-03-dashboard-share.md](../specs/2026-07-02-fr-db-03-dashboard-share.md)

핵심 시나리오 요약.
- 소유자가 공유 모달에서 링크 생성 → 불투명 토큰 발급(원문 1회 노출, DB엔 SHA-256만)
- 익명 사용자가 `/dashboards/shared/{token}`로 읽기 전용 열람 — 정적 가젯만 렌더, 데이터 가젯은 "로그인 필요" 플레이스홀더
- iframe 임베드 코드 복사 지원, 링크 목록·개별 취소(하드 삭제)
- 무효/만료/삭제 대시보드 토큰 → 404(열거 차단), 익명 응답에 owner PII/version/데이터가젯 config 부재

API. 관리(인증) `POST/GET/DELETE /api/v1/dashboards/{id}/shares` + 익명(permitAll·GET) `GET /api/v1/public/dashboards/{token}`.
데이터. 신규 `dashboard_share_tokens`(token_hash BYTEA UNIQUE, FK CASCADE). `DashboardVisibility` 불변.
보안 핵심. BTS 첫 permitAll 데이터 경로 → 중앙 SecurityConfig(identity-access) `/api/v1/public/**` 등록(cross-BC, security-engineer 공동검토) + 정화 fail-closed + 토큰 열거차단.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 5건 스펙 반영. Maxi 결정 대기 항목: O-1 iframe 크로스오리진 프레이밍 범위(게이트1).

## Plan

> **범위**: 이번 PR(#216) = 백엔드 PR1(D1~D5). 프론트 D6/D7은 후속 PR2.
> 모듈: `backend/modules/notification` (dashboard 패키지), 일부 cross-BC(identity-access SecurityConfig).
> 신규 마이그레이션 V번호 = **V408** (notification 최신 V407 다음, 머지 직전 재확인).

### Task 1. DashboardShareToken 도메인 엔티티 + 토큰 발급/해싱

**메타**.
- agent: `security-engineer`  # CSPRNG 토큰 생성 + SHA-256 해싱 = 보안 핵심
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/DashboardShareToken.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/ShareTokenMinter.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/DashboardShareTokenTest.kt`]
- depends-on: []

**RED**: `DashboardShareTokenTest`
- `mint 은 256bit base64url 불투명 토큰(원문)과 그 SHA-256 해시를 반환한다`
- `동일 원문 토큰은 항상 동일 해시로 매핑된다(결정적)`
- `isExpired(now) 는 expiresAt<=now 에서 true, null expiresAt 은 항상 false`
- `엔티티는 원문 토큰을 보관하지 않는다(tokenHash 필드만 존재)`

**GREEN**: `DashboardShareToken`(data class: id, dashboardId, tokenHash ByteArray, createdBy, createdAt, expiresAt?) + `isExpired(now: Instant)`. `ShareTokenMinter`: `SecureRandom` 32바이트 → base64url 인코딩(원문) + `MessageDigest("SHA-256")` 해시. `mint(dashboardId, createdBy, expiresAt, now): MintedToken(plaintext, entity)`.
- 상수: `MAX_SHARE_TOKENS = 20`(Dashboard/도메인 상수), 토큰 바이트수 32.

**REFACTOR**: KDoc(원문 1회 노출·미저장 명시), ByteArray equals/hashCode 주의(data class 함정) — 해시 비교는 `MessageDigest.isEqual` 사용.

**검증**: `./gradlew :backend:notification:test --tests '*DashboardShareTokenTest'`

---

### Task 2. 익명 뷰 layout 정화기 (fail-closed 화이트리스트)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/application/AnonymousLayoutSanitizer.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/application/AnonymousLayoutSanitizerTest.kt`]
- depends-on: []

**RED**: `AnonymousLayoutSanitizerTest`
- `text_widget/link_list(STATIC) 항목은 config 포함 원본 유지`
- `데이터 가젯(assigned_to_me 등 non-STATIC)은 config 제거 + requiresAuth=true 로 치환(i,x,y,w,h,gadgetType 만 유지)`
- `gadgetType 미지정(legacy 타일)은 정화 대상 아님(위치만) — config 없으면 그대로`
- `알 수 없는 gadgetType 은 fail-closed(placeholder 처리, 통과 금지)`
- `owner PII/version 은 정화 결과 어디에도 없다`

**GREEN**: `AnonymousLayoutSanitizer.sanitize(layoutJson: String): String` — Jackson으로 배열 파싱, 각 항목 `gadgetType` → `GadgetType.fromKey`? category==STATIC 이면 통과, 그 외(미지정 제외 규칙은 스펙대로) config 제거 + `requiresAuth:true`. 화이트리스트: `GadgetCategory.STATIC` 만.

**REFACTOR**: 순수 함수 유지(Clock/DB 무관), KDoc에 fail-closed 원칙 명시.

**검증**: `./gradlew :backend:notification:test --tests '*AnonymousLayoutSanitizerTest'`

---

### Task 3. 마이그레이션 V408 dashboard_share_tokens + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V408__dashboard_share_tokens.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: []

**RED**: (마이그레이션은 스키마 테스트/저장소 테스트로 검증) 기존 `SchemaMigrationTest` 류가 테이블 카운트를 단언하면 갱신 필요 — 있으면 카운트 +1(memory fr-pm-permission-seed-migration-test-coupling).

**GREEN**: `V408` — 스펙 §데이터 모델 DDL 그대로(id PK, dashboard_id FK CASCADE, token_hash BYTEA NOT NULL, created_by, created_at, expires_at?, last_accessed_at?[미갱신]). UNIQUE(token_hash), INDEX(dashboard_id). init_codegen.sql에 동일 CREATE 미러(jOOQ 코드젠 소스, memory jooq-init-codegen-mirror).

**REFACTOR**: 컬럼 주석, init_codegen 순서 정렬.

**검증**: `./gradlew :backend:notification:flywayMigrate` (dev) + 모듈 test 부팅.

---

### Task 4. DashboardShareTokenRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/repository/DashboardShareTokenRepository.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/repository/DashboardShareTokenRepositoryTest.kt`]
- depends-on: [1, 3]

**RED**: `DashboardShareTokenRepositoryTest`(Testcontainers)
- `insert 후 findActiveByTokenHash 로 조회(부모 deleted_at IS NULL 조인 필터)`
- `부모 대시보드 soft-delete 시 findActiveByTokenHash 는 null`
- `deleteById 후 조회 null`
- `listByDashboard 는 메타만(token_hash 반환하되 서비스/DTO에서 미노출)`
- `countByDashboard 정확`

**GREEN**: jOOQ 리포지토리 — `insert`, `findActiveByTokenHash(hash): DashboardShareToken?`(dashboards JOIN, deleted_at IS NULL), `listByDashboard(dashboardId)`, `deleteById(id, dashboardId)`, `countByDashboard(dashboardId)`. ArchUnit `com.bts..jooq..` 패키지 규칙 준수(memory archunit-shared-class-move-repository-package).

**REFACTOR**: 상수 쿼리 추출, BYTEA 바인딩 확인.

**검증**: `./gradlew :backend:notification:test --tests '*DashboardShareTokenRepositoryTest'`

---

### Task 5. DashboardService 공유 토큰 유스케이스

**메타**.
- agent: `security-engineer`  # 소유자 가드·교차조회 차단·타이밍 안전 = 보안 핵심
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/application/DashboardService.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/application/DashboardExceptions.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/application/DashboardServiceTest.kt`]
- depends-on: [1, 2, 4]

**RED**: `DashboardServiceTest`(기존 파일 확장 — 생성자 주입 추가 시 기존 mock 테스트도 갱신, memory plan-files-constructor-injection-existing-tests)
- `issueShareToken: 소유자만, 원문 토큰 1회 반환, MAX_SHARE_TOKENS 초과 시 도메인 예외`
- `issueShareToken: 비소유자 403 / 없는 대시보드 404(actor 추출을 findByKey 앞, memory auth-extraction-before-resource-lookup)`
- `listShareTokens: 소유자만, 메타만(원문/해시 미포함)`
- `revokeShareToken: 소유자만, 다른 대시보드의 shareId 취소 시도 차단(교차조회 불가)`
- `getPublicByToken: 유효 토큰→정화된 스냅샷(Task2 사용), 무효/만료(Clock)/삭제→동일 not-found(404 수렴)`

**GREEN**: 서비스에 `ShareTokenMinter`+`DashboardShareTokenRepository`+`Clock`+`AnonymousLayoutSanitizer` 주입. 유스케이스 5종. 신규 예외(`ShareTokenLimitExceeded`, `ShareTokenNotFound` 등)를 `DashboardExceptions`에 추가. getPublicByToken은 SHA-256 해시로 `findActiveByTokenHash` 조회 + isExpired 확인 → 정화.

**REFACTOR**: 가드 헬퍼 재사용(기존 owner 체크), 예외 message에 민감정보 미포함(memory fr-pm-04-guard-exception-message-http-leak).

**검증**: `./gradlew :backend:notification:test --tests '*DashboardServiceTest'`

---

### Task 6. 공유 토큰 관리 REST API (인증·소유자)

**메타**.
- agent: `backend-engineer`  # 권한 가드는 security-engineer 리뷰
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/DashboardShareController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/dto/DashboardShareDtos.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/DashboardExceptionHandler.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardShareControllerTest.kt`]
- depends-on: [5]

**RED**: `DashboardShareControllerTest`(MockMvc)
- `POST /api/v1/dashboards/{id}/shares → 201 + {id,token,createdAt,expiresAt?}`
- `GET /api/v1/dashboards/{id}/shares → 200 items(token 필드 부재 단언 — 유출 회귀가드 EC-9)`
- `DELETE .../shares/{shareId} → 204`
- `비소유자 403 / 없는 대시보드 404 / 상한초과 400`
- `미인증 401(currentActorId)`

**GREEN**: `DashboardShareController`(`/api/v1/dashboards/{id}/shares`), 요청/응답 DTO. 신규 예외 → HTTP 매핑을 `DashboardExceptionHandler`에 추가(핸들러 basePackage 스코프가 새 컨트롤러 커버 확인, memory domain-exception-http-handler-basepackage-scope). literal `shares` vs `{id}` 경로 우선순위 확인(memory FR-DB-02 gadget-catalog).

**REFACTOR**: DTO from() 팩토리, KDoc.

**검증**: `./gradlew :backend:notification:test --tests '*DashboardShareControllerTest'`

---

### Task 7. 익명 읽기 엔드포인트 + SecurityConfig permitAll (cross-BC)

**메타**.
- agent: `security-engineer`  # BTS 첫 permitAll 데이터 경로 + cross-BC 보안 변경
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/PublicDashboardController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/dto/PublicDashboardDtos.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/PublicDashboardControllerTest.kt`]
- depends-on: [5]

**RED**: `PublicDashboardControllerTest`(MockMvc standalone)
- `GET /api/v1/public/dashboards/{token} 유효→200, 정화된 스냅샷(name,description?,layout) — ownerId/sharedUserIds/version 부재`
- `무효/만료/삭제 토큰→404 동일 형식(열거차단 EC-1/3/5)`
- `이상 토큰 문자열→404(500 금지)`

**GREEN**: `PublicDashboardController` GET(permitAll·GET 전용) → `service.getPublicByToken(token)` → `PublicDashboardResponse`(정화). 무효는 컨트롤러에서 404 직접 반환(apiFetch 리다이렉트 회피는 프론트 몫). `SecurityConfig`에 `/api/v1/public/**` permitAll 등록(+ CSRF 무관 GET). MfaEnrollmentGate/SidRevoke는 익명(principal 없음) 통과 확인.

**REFACTOR**: permitAll 경로 상수 + KDoc(왜 익명·GET전용·정화 근거 ADR 링크).

**검증**: `./gradlew :backend:notification:test --tests '*PublicDashboardControllerTest'` + `:backend:identity-access:test`(SecurityConfig 회귀).

---

### Task 8. HTTP end-to-end 통합 테스트 (Testcontainers)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardShareIntegrationTest.kt`]
- depends-on: [6, 7]

**RED/GREEN**: 라운드트립 — 발급→익명 GET 정화 렌더(정적 유지·데이터 가젯 requiresAuth)→취소→404→만료(Clock 주입)→404→부모 soft-delete→404→**교차 대시보드 토큰으로 타 대시보드 조회 불가**→`GET /shares` 응답 token 미포함. 보안 술어(정화 fail-closed) 실경로 capture.

**리스크 노트**: 익명 permitAll 실동작(비인증 200)은 SecurityConfig(identity-access)에 의존 — notification 모듈 테스트 슬라이스가 그 체인을 부팅하지 않으면 permitAll 자체는 code-review로 검증하고, 통합 테스트는 컨트롤러 로직(정화·404·교차차단)을 커버. 실제 permitAll wiring은 assembled/smoke 레벨(memory no-cross-bc-deployment-assembly) 존재 시 거기서, 없으면 게이트2 리뷰 명시.

**검증**: `./gradlew :backend:notification:test --tests '*DashboardShareIntegrationTest'`

## Plan 메타

- task 수: 8
- 예상 wave: 5 (W1: T1·T2·T3 병렬 / W2: T4 / W3: T5 / W4: T6·T7 병렬 / W5: T8)
- 크리티컬 패스: T1→T4→T5→T7→T8 (약 5 wave)
- TDD 강제: yes (test 커밋이 feat 커밋보다 선행)
- 보안 task: T1·T5·T7 = security-engineer. 나머지 backend/db-engineer.
- 추가 검증: ktlint + detekt(--rerun-tasks) + verify-master-plan(카운트 drift). FR-DB-03 D1~D5 체크박스는 머지 시 마킹.
- cross-BC 주의: T7이 identity-access SecurityConfig 수정 — 한 기능이 요구하는 인접 보안 레이어 변경(FR-WF-01 옵션C 선례), security-engineer 공동검토.

## 리뷰 결과 (← /bts-review-plan 채움)
