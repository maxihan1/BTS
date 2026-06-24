# FR-UX-02 즐겨찾기 / Star 기능 (백엔드 D1~D5)

> slug: fr-ux-02-favorites-backend
> type: feature
> agent: backend-engineer
> 생성: 2026-06-24

## Brief

FR-UX-02 즐겨찾기 / Star 기능 구현 (notification-dashboard BC §5.1 — 이슈/필터/대시보드/프로젝트를 즐겨찾기 등록·해제, Star 버튼 + 즐겨찾기 사이드바).

본 PR 범위: **백엔드 D1~D5** (도메인 Favorite · 명세 대상 4종 · 데이터 모델 `favorites` · API `POST/DELETE /api/v1/favorites` · 백엔드 테스트). 프론트 D6/D7(Star 버튼 + 즐겨찾기 사이드바)은 후속 PR.

classify 정정: type ui→feature, agent frontend-engineer→backend-engineer, primary_bc issue-tracking→notification(물리 모듈, FR-DB-01 dashboard 패키지 선례).

## 도메인 정리

- **BC**: notification (notification-dashboard 논리 BC). 물리 모듈 `backend/modules/notification`, 패키지 `com.bts.notification.favorite.{domain,application,repository,web}` (FR-DB-01 dashboard 패키지 선례 계승).
- **신규 엔티티**: `Favorite` (Aggregate Root) — id(UUID) · userId(UUID) · targetType(enum) · targetId(문자열) · createdAt. 자식 없음(개인 북마크).
- **신규 enum**: `FavoriteTargetType` = ISSUE / FILTER / DASHBOARD / PROJECT (4종, Maxi 확정). FILTER는 FR-SR-03 미구현이라 정의만·실사용 후속.
- **신규 용어 (glossary 대기)**: 즐겨찾기(Favorite), 즐겨찾기 대상(Favorite Target). Maxi 승인 후 머지 단계 동기화.
- **핵심 결정 (Maxi 확정 2026-06-24)**:
  - 대상 타입 4종 (명세 충실, FILTER는 후속 실사용)
  - 즐겨찾기 해제 = **하드 삭제** → DATA.md §하드 삭제 허용 영역에 favorites 추가 동기화 필요
  - 등록 시 **형식만 검증** (대상 존재/VIEW 권한 미검증, BC 결합 회피, 끊어진 참조는 graceful)
  - target_id 문자열 저장 + user_id/target FK 미적용 (BC 격리, FR-DB-01·FR-NT-01 관례)
  - 멱등: UNIQUE(user_id, target_type, target_id)
- **데이터**: 신규 마이그레이션 **V406** (`favorites`) + `init_codegen.sql` 미러 필수.
- **기존 결정 충돌**: 없음 (FR-DB-01 대시보드 ADR 패턴 계승·일관).
- **관련 ADR**: [docs/decisions/2026-06-24-fr-ux-02-favorites.md](../decisions/2026-06-24-fr-ux-02-favorites.md) (생성됨)
- **선행 의존**: FR-SR-03(필터 저장) 미구현 — FILTER 대상은 실대상 부재. 본 PR은 enum 정의만.

## 스펙

전체 스펙. [docs/specs/2026-06-24-fr-ux-02-favorites-backend.md](../specs/2026-06-24-fr-ux-02-favorites-backend.md)

핵심 시나리오 요약.
- POST /api/v1/favorites { targetType, targetId } → 등록(201) / 멱등 재등록(200, 행 1개)
- DELETE /api/v1/favorites?targetType=&targetId= → 대상 기준 하드 삭제(204, 멱등)
- GET /api/v1/favorites?targetType= → 본인 즐겨찾기만 createdAt DESC (타인 누출 0)
- 형식만 검증(targetType enum·targetId non-blank), 대상 실존/권한 미검증

핵심 함정 (구현 시 필수).
- ★ notification 모듈 Bean Validation provider 부재 → `@Valid` 무동작. 입력 검증은 명시적 도메인 예외로만.
- ★ 신규 favorite 패키지 컨트롤러는 형제 @RestControllerAdvice 미적용 → 자체 FavoriteExceptionHandler 필수.
- ★ V406 마이그레이션은 init_codegen.sql 미러 동반 필수.
- ★ 하드 삭제 → DATA.md §하드 삭제 허용 영역 동기화 필요.

## Brainstorming Check

✅ 통과 (직접 sanity check, 1회 — office-hours/brainstorming은 백엔드 기술 스펙 overkill, memory: bts-spec-office-hours-mismatch). 엣지 E1~E9 전수 점검. 잔여 결정(중복 POST 멱등 200·DELETE 대상 기준 query)은 watcher 선례+product 명세 근거로 명시, review-plan 게이트 재확인 가능.

## Plan

> 모듈 `backend/modules/notification`, 패키지 `com.bts.notification.favorite.{domain,application,repository,web}`.
> gradle prefix `:modules:notification`. jOOQ codegen 소스 = `src/main/resources/db/codegen/init_codegen.sql`.

### Task 1. 도메인 — FavoriteTargetType enum + Favorite 엔티티 + 도메인 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/domain/FavoriteTargetType.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/domain/Favorite.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/domain/FavoriteDomainException.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/favorite/domain/FavoriteTest.kt`]
- depends-on: []

**RED**: `FavoriteTest.kt`
- `FavoriteTargetType`이 ISSUE/FILTER/DASHBOARD/PROJECT 4종 + `from(wire)` 무효값 시 `FavoriteDomainException`
- `Favorite.create(userId, targetType, targetId)` — targetId blank → 예외, 255자 초과 → 예외, 정상 생성 시 id/createdAt 부여
- 실패 메시지(예상): `Favorite`/`FavoriteTargetType` 클래스 없음

**GREEN**:
- `FavoriteTargetType` enum 4종 + `from(String): FavoriteTargetType`(명시 파싱, 무효→예외)
- `Favorite` data class(id:UUID, userId:UUID, targetType, targetId:String, createdAt:Instant) + `create()` 팩토리(형식 검증 require → `FavoriteDomainException`). ★ Bean Validation 미사용(notification provider 부재) — 명시 require
- `FavoriteDomainException(message)`

**REFACTOR**: `MAX_TARGET_ID_LENGTH=255` 상수, KDoc, 파일 L1 한글 주석

**검증**: `./gradlew :modules:notification:test --tests 'com.bts.notification.favorite.domain.*'`

### Task 2. 마이그레이션 — V406 + init_codegen 미러 + DATA.md 동기화

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V406__favorites.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`, `DATA.md`, `backend/modules/notification/src/test/kotlin/com/bts/notification/favorite/repository/FavoritesSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `FavoritesSchemaMigrationTest.kt` (Testcontainers, 기존 스키마 마이그레이션 테스트 패턴 따름)
- `favorites` 테이블 존재 + 컬럼(id,user_id,target_type,target_id,created_at) + UNIQUE(user_id,target_type,target_id) + `deleted_at` 컬럼 없음
- 실패(예상): relation "favorites" does not exist

**GREEN**:
- `V406__favorites.sql` — spec §데이터 모델 DDL (FK 없음, deleted_at 없음, UNIQUE 복합)
- `init_codegen.sql`에 ★ 동일 DDL 미러(memory: jooq-init-codegen-mirror) — 누락 시 jOOQ FAVORITES 클래스 미생성
- `DATA.md` §하드 삭제 허용 영역에 `favorites` 추가(ADR 2026-06-24-fr-ux-02-favorites 인용, "북마크 토글·복구 가치 낮음·deleted_at 없음")

**REFACTOR**: DDL 주석

**검증**: `./gradlew :modules:notification:generateJooq :modules:notification:compileKotlin :modules:notification:test --tests '*FavoritesSchemaMigrationTest'`

### Task 3. Repository — FavoriteRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/repository/FavoriteRepository.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/favorite/repository/FavoriteRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: `FavoriteRepositoryIntegrationTest.kt` (Testcontainers)
- `save` 멱등 — 같은 (user,type,id) 두 번 저장 시 행 1개 + 기존 반환
- `deleteByTarget` — 있으면 삭제(true/count), 없으면 무해(false/0)
- `findByUser` — 본인 것만, targetType 옵션 필터, `created_at DESC` 정렬

**GREEN**:
- jOOQ INSERT … ON CONFLICT (user_id,target_type,target_id) DO NOTHING **RETURNING** — ★ 반환 행 유무로 신규/기존 구분. `save(): SaveResult(favorite, created: Boolean)`(RETURNING 행 있으면 created=true, 없으면 재조회 후 created=false). controller 201/200 매핑에 필요(eng-review CONCERN-1)
- DELETE WHERE user_id+target_type+target_id
- SELECT WHERE user_id (+옵션 target_type) ORDER BY created_at DESC
- record→`Favorite` 매퍼

**REFACTOR**: 매퍼/상수 추출

**검증**: `./gradlew :modules:notification:test --tests '*FavoriteRepositoryIntegrationTest'`

### Task 4. Service — FavoriteService

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/application/FavoriteService.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/favorite/application/FavoriteServiceTest.kt`]
- depends-on: [1, 3]

**RED**: `FavoriteServiceTest.kt` (mockk repository)
- `addFavorite(actorId, targetTypeRaw, targetId)` — targetType 파싱(무효→예외), `Favorite.create` 형식 검증, `repository.save`, 멱등 경로(기존 반환)
- `removeFavorite(actorId, targetTypeRaw, targetId)` — `deleteByTarget` 위임(멱등)
- `listFavorites(actorId, targetTypeRaw?)` — `findByUser` 위임, targetType 무효→예외

**GREEN**: `@Service @Transactional` FavoriteService. 형식 검증은 `FavoriteTargetType.from` + `Favorite.create`에 위임. ★ @Transactional 클래스에 @Service 필수(memory: @Service 누락 시 무력화)

**REFACTOR**: KDoc

**검증**: `./gradlew :modules:notification:test --tests 'com.bts.notification.favorite.application.*'`

### Task 5. Controller + ExceptionHandler + DTO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/web/FavoriteController.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/web/FavoriteExceptionHandler.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/favorite/web/dto/FavoriteDtos.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/favorite/web/FavoriteControllerIntegrationTest.kt`]
- depends-on: [1, 4]

**RED**: `FavoriteControllerIntegrationTest.kt` (Testcontainers + 인증 컨텍스트, DashboardControllerTest 패턴)
- POST 201(신규)/200(멱등 재등록, 행 1개)
- DELETE 204(있음/없음 모두)
- GET 본인 것만·targetType 필터·createdAt DESC, 타 user 누출 0
- 401 미인증, 400 targetType 무효·targetId blank

**GREEN**:
- `FavoriteController` — `currentActorId()` 먼저 추출(probe 차단, memory: auth-extraction-before-resource-lookup), `DataResponse<T>` 래퍼 재사용. POST(body) → ★ `SaveResult.created` 시 201, 아니면 200(eng-review CONCERN-1) / DELETE(query targetType+targetId) 204 / GET(query targetType?) 200
- `FavoriteExceptionHandler` — ★ `@RestControllerAdvice(basePackages=["com.bts.notification.favorite.web"])` 한정(DashboardExceptionHandler 선례 일관, eng-review CONCERN-2 정정. assignableTypes 아님). 형제 `NotificationExceptionHandler`는 `com.bts.notification.web` 한정이라 favorite 미가로챔(확인됨). FavoriteDomainException→400, 에러코드 접두사 `FAV_`(또는 BC 관례 확인). catch-all은 ResponseStatusException 먼저 rethrow(memory: catch-all-swallows-responsestatusexception)
- `FavoriteDtos` — CreateFavoriteRequest(targetType,targetId), FavoriteResponse(id,targetType,targetId,createdAt), FavoriteListResponse(items)
- 통합테스트 인증 컨텍스트는 `DashboardControllerTest` 패턴 따름(currentActorId 주입)

**REFACTOR**: KDoc, 응답 매퍼

**검증**: `./gradlew :modules:notification:test --tests '*FavoriteControllerIntegrationTest'`

## Plan 메타

- task 수: 5
- depends-on 그래프: T1[] · T2[] · T3[1,2] · T4[1,3] · T5[1,4]
- 예상 wave: wave1=T1,T2(병렬) → wave2=T3 → wave3=T4 → wave4=T5 (★ 같은 notification 모듈 test 컴파일은 직렬화 요인, memory: bts-plan-wave-gradle-module-compile — bts-impl이 최종 wave 계산)
- TDD 강제: yes (test 커밋 먼저)
- 추가 검증: detekt/ktlint(notification baseline), generateJooq(init_codegen 반영)
- 머지 단계 동기화: fr-index FR-UX-02 상태, product notification-dashboard.md §5.1 D1~D5 [x], glossary 즐겨찾기, dashboard regen

## 리뷰 결과

### eng 집중 독립 리뷰 (2026-06-24)

백엔드 단일 BC라 autoplan(ceo/design 포함) 대신 eng 집중 리뷰(memory: bts-review-plan-autoplan-overkill).

**✅ 통과 항목**
- depends-on 그래프 정합(T1[]·T2[]→T3[1,2]→T4[1,3]→T5[1,4]), 순환 없음
- jOOQ codegen 타이밍 정상 — T2가 init_codegen 미러 + generateJooq, T3 repository가 그 위에서 FAVORITES 사용
- BC 격리(FK 미적용·문자열 식별자), 하드삭제 DATA.md 동기화, Bean Validation 부재→명시검증, init_codegen 미러 모두 반영
- 형제 `NotificationExceptionHandler`(basePackages=com.bts.notification.web 한정)는 favorite 예외 미가로챔 — 확인됨

**보강 반영 (CONCERN)**
- **CONCERN-1 (보강 완료)**. 멱등 POST 201/200 구분 정보 누락 → Task 3 `save(): SaveResult(favorite, created)` (INSERT…ON CONFLICT DO NOTHING RETURNING), Task 5 controller가 created로 201/200 매핑.
- **CONCERN-2 (정정 완료)**. ExceptionHandler 스코프 `assignableTypes` → `basePackages=["com.bts.notification.favorite.web"]`(DashboardExceptionHandler 선례 일관).

**⚠️ 주의 (impl 시 유의)**
- V406 머지 직전 재확인(memory: migration-vnumber-concurrent-branch-collision). 현재 최대 V405, 충돌 없음.
- GET 목록 안전 상한 — 즐겨찾기 무한 증가 대비 합리적 cap 고려(개인 소량이라 minor).
- notification 통합테스트 부팅 레시피 + 인증 컨텍스트는 DashboardControllerTest 선례 따름.
- detekt는 --rerun-tasks 실검증(memory: backend-detekt-lint-debt-unmasked, subagent-ktlint-false-green).

**BLOCKER: 없음.**
