# FR-BD-03 — WIP 제한 + 스윔레인 (백엔드 D1~D5)

> slug: fr-bd-03-wip-swimlane
> type: api
> agent: backend-engineer (D3는 db-engineer)
> 생성: 2026-06-22

## Brief

FR-BD-03 (agile-planning BC, §2.3) — WIP 제한 + 스윔레인 백엔드.

- D1. 도메인
- D2. 명세 — WIP 초과 시 시각 경고만 (이동 차단 옵션)
- D3. 데이터 모델 — `board_columns.wip_limit`, `boards.swimlane_field`
- D4. 백엔드 — 카운트 + 경고 응답 API
- D5. 백엔드 테스트

선행 §2.1 FR-BD-01 완료됨. 직전 FR-BD-01/02와 동일하게 백엔드 먼저 → 프론트 D6/D7은 후속 PR.

SDD 참조. 13.1.1 (WIP 제한 = 컬럼당 최대 이슈 수), 13.1.3 (스윔레인 = 담당자별/Epic별/우선순위별 가로 분리).

## 도메인 정리

- **BC**: agile-planning
- **영향 엔티티**: `Board`(swimlaneField 추가), `BoardColumn`(wipLimit 추가). 신규 엔티티 0.
- **새 용어**:
  - **WIP 제한** (Work In Progress limit) — 컬럼당 동시 진행 카드 수 상한. 초과 시 경고만(차단 X).
  - **스윔레인** (Swimlane) — 보드를 담당자/우선순위 등으로 가로 분리하는 그룹 행.
- **기존 결정 충돌**: 없음. FR-BD-01 ADR과 일관 (보드는 워크플로우 상태에 종속, 카드 이동은 전환 재사용).
- **Maxi 확정 결정 (3건)**:
  1. Epic 스윔레인 이연 — `swimlane_field` enum = NONE/ASSIGNEE/PRIORITY만. EPIC은 FR-EP 미구현으로 카드에 데이터 부재 → 미포함.
  2. WIP 초과 = 경고 신호만 응답 (이동 차단 안 함). 백엔드는 wipLimit/wipExceeded 신호만.
  3. 스윔레인 그룹핑 = 프론트(D6). 백엔드는 swimlane_field 저장+echo, 카드 assigneeId/priority는 이미 노출됨.
- **카드 데이터 가용성**: assigneeId ✓, priority ✓ (BoardIssueView). epic ✗ (FR-EP 미구현) → EPIC 스윔레인 불가.
- **스키마 변경 (D3)**: `board_columns.wip_limit INTEGER NULL`(양수만), `boards.swimlane_field VARCHAR NOT NULL DEFAULT 'NONE'`.
- **미정 (→ spec)**: WIP 제한·swimlane_field 설정 쓰기 API 엔드포인트 형태 + 권한 (보드 설정 변경 권한).
- **관련 ADR**: [docs/decisions/2026-06-22-fr-bd-03-wip-swimlane.md](../decisions/2026-06-22-fr-bd-03-wip-swimlane.md) (생성됨)
- **glossary 추가 후보** (Maxi 승인 후 수동): "WIP 제한", "스윔레인"

## 스펙

전체 스펙. [docs/specs/2026-06-22-fr-bd-03-wip-swimlane.md](../specs/2026-06-22-fr-bd-03-wip-swimlane.md)

핵심 시나리오.
- 컬럼별 WIP 제한 설정(`PATCH /boards/{id}/columns/{columnId}` {wipLimit}). 조회 시 wipLimit/wipExceeded 신호 응답. 이동 차단 안 함.
- 보드 스윔레인 기준 설정(`PATCH /boards/{id}` {swimlaneField}: NONE/ASSIGNEE/PRIORITY). 백엔드 echo, 그룹핑은 프론트.
- 권한 = CREATE on Project(보드 생성과 동일). 401/403/404 게이트 기존 패턴 재사용.

신규: 마이그레이션 V501(+init_codegen 미러), 도메인 필드 2개, BoardRepository update 2메서드, PATCH 2엔드포인트, GET 응답 확장.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). gap 3건 보강(E11 가시성 기준 WIP / E3 enum 대소문자 / 생성 응답 범위). Maxi 결정 필요 gap 없음.

## Plan

> 모듈: agile-planning(단일). 공유 파일(BoardController/Service/Responses) 의존으로 wave 병렬 이점 제한적.
> 설계: 도메인 필드 기본값 부여(기존 생성자 비파괴), wipExceeded는 DTO 변환에서 산출(Service 변경 최소).

### Task 1. V501 마이그레이션 + jOOQ codegen + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V501__board_wip_swimlane.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSchemaMigrationTest.kt`]
- depends-on: []

**RED**:
- `BoardSchemaMigrationTest`에 검증 추가 — `board_columns.wip_limit`(INTEGER, nullable) 존재 + CHECK(wip_limit IS NULL OR > 0), `boards.swimlane_field`(VARCHAR, NOT NULL, DEFAULT 'NONE') 존재 + CHECK(IN NONE/ASSIGNEE/PRIORITY).
- 실패: 컬럼 미존재.

**GREEN**:
- `V501__board_wip_swimlane.sql` 작성 (ADD COLUMN 2개 + 2 CHECK 제약, COMMENT).
- `init_codegen.sql`의 boards/board_columns 정의에 동일 컬럼 미러 (메모리 jooq-init-codegen-mirror).
- jOOQ codegen 재생성 (`./gradlew :backend:modules:agile-planning:generateJooq` 또는 프로젝트 codegen task).

**REFACTOR**: SQL 주석 정리, V번호 머지 직전 재확인 메모.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests "*BoardSchemaMigrationTest"`

### Task 2. 도메인 — SwimlaneField enum + BoardColumn.wipLimit + Board.swimlaneField

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/SwimlaneField.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/Board.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardColumn.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardWipSwimlaneDomainTest.kt`]
- depends-on: []   # 순수 도메인. 기본값으로 기존 생성자 비파괴(codegen 무관)

**RED**:
- `BoardWipSwimlaneDomainTest` — (1) `BoardColumn(wipLimit=0)` → IllegalArgumentException, `wipLimit=-1` → 예외, `wipLimit=null/양수` → 정상. (2) `SwimlaneField` valueOf("NONE"/"ASSIGNEE"/"PRIORITY") + "EPIC" 미존재. (3) `Board` 기본 swimlaneField=NONE.
- 실패: SwimlaneField 클래스/필드 없음.

**GREEN**:
- `SwimlaneField.kt` — `enum class SwimlaneField { NONE, ASSIGNEE, PRIORITY }` + 파일 L1 한국어 주석.
- `BoardColumn`에 `val wipLimit: Int? = null` 추가 + init `require(wipLimit == null || wipLimit > 0)`.
- `Board`에 `val swimlaneField: SwimlaneField = SwimlaneField.NONE` 추가.

**REFACTOR**: KDoc 보강(@property).

**검증**: `./gradlew :backend:modules:agile-planning:test --tests "*BoardWipSwimlaneDomainTest"`

### Task 3. Repository — 새 컬럼 read/write + update 2메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/BoardRepositoryTest.kt`]
- depends-on: [1, 2]   # jOOQ codegen(컬럼) + 도메인 필드

**RED**:
- `BoardRepositoryTest`(Testcontainers) — (1) insert/findById 라운드트립이 swimlaneField/wipLimit 보존. (2) `updateSwimlaneField(boardId, ASSIGNEE)` 후 findById 반영. (3) `updateColumnWipLimit(boardId, columnId, 5)` 후 반영, `null`로 해제. (4) 타 보드 columnId → 0 affected(404 신호).
- 실패: update 메서드 없음 / 새 컬럼 매핑 누락.

**GREEN**:
- `insert`에 swimlane_field/wip_limit 영속, `findById`/`findAllByProjectKey`에 read 매핑 추가.
- `updateSwimlaneField(boardId, swimlaneField): Board?` (boards UPDATE WHERE id AND deleted_at IS NULL, 갱신 후 재조회).
- `updateColumnWipLimit(boardId, columnId, wipLimit): BoardColumn?` (board_columns UPDATE WHERE id=columnId AND board_id=boardId, affected=0 → null로 소속 검증).

**REFACTOR**: SQL 상수 추출, KDoc.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests "*BoardRepositoryTest"`

### Task 4. GET 응답 확장 — DTO에 wipLimit/wipExceeded/swimlaneField

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/dto/BoardResponsesTest.kt`]
- depends-on: [2]   # 도메인 필드(BoardColumn.wipLimit, Board.swimlaneField)

**RED**:
- `BoardResponsesTest` — (1) `BoardColumnWithCardsResponse.from(placed)`가 wipLimit echo + wipExceeded = (wipLimit != null && cards.size > wipLimit). wipLimit=null → wipExceeded=false. cards 0건 → false. (2) `BoardDetailResponse.of(board, result)`가 swimlaneField=board.swimlaneField.name 노출.
- 실패: DTO 필드 없음.

**GREEN**:
- `BoardColumnWithCardsResponse`에 `wipLimit: Int?`, `wipExceeded: Boolean` 추가 + `from`에서 산출.
- `BoardDetailResponse`에 `swimlaneField: String` 추가 + `of`에서 `board.swimlaneField.name`.

**REFACTOR**: KDoc.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests "*BoardResponsesTest"`

### Task 5. PATCH 2종 — Service + Controller + 요청 DTO + 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [2, 3, 4]   # 도메인 + Repository update + 응답 DTO(BoardResponses 겹침→직렬)

**RED**:
- `BoardControllerIntegrationTest` 시나리오 — S1(컬럼 wipLimit 설정 200), S3(null 해제), S4(swimlaneField 설정 200), E1(wipLimit≤0 → 400), E3(미허용 swimlaneField → 400), E4(타 보드 columnId → 404), E5(보드 미존재 404), E6(권한 미충족 403), E7(미인증 401). + GET 조회로 wipExceeded(S2)/swimlaneField echo 확인.
- 실패: 엔드포인트 없음.

**GREEN**:
- 요청 DTO: `UpdateColumnWipLimitRequest(wipLimit: Int?)`(`@field:Positive` — null 허용, 양수만 검증), `UpdateBoardSwimlaneRequest(swimlaneField: String)`(@NotBlank) + 응답 DTO(컬럼/보드 메타).
- `BoardApplicationService.updateColumnWipLimit(...)` / `updateSwimlaneField(...)` — 둘 다 `@Transactional`(쓰기, DEVELOPMENT.md §1.4). swimlaneField enum 파싱 실패 → `ResponseStatusException(BAD_REQUEST)`, repo null(보드/컬럼 미존재) → `ResponseStatusException(NOT_FOUND)` 또는 `BoardNotFoundException`. (기존 BoardExceptionHandler가 ResponseStatusException 상태 전파 + catch-all 변질 차단 — 신규 핸들러 불필요)
- `BoardController` — `PATCH /{id}/columns/{columnId}`, `PATCH /{id}` (기존 getBoard의 `{id}` path variable과 통일 — devex D1). actor 추출 선행 → 보드 메타 조회(404) → requirePermission(CREATE) → 위임. (loadBoardWithBrowse 패턴을 CREATE 권한용 헬퍼로 재사용/확장)

**REFACTOR**: 권한 헬퍼 공통화, KDoc, ktlint/detekt 정리.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests "*BoardControllerIntegrationTest"` + 전체 모듈 회귀.

## Plan 메타

- task 수: 5
- 예상 wave: 3 (wave1: T1·T2 / wave2: T3·T4 / wave3: T5). 단일 모듈 test 컴파일 직렬화로 실질 직렬에 가까움.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 추가 검증: ktlintCheck, detekt, BoardSchemaMigrationTest(Testcontainers), 전체 모듈 회귀
- 권한: shared-kernel IssuePermissionResolver 재사용(CREATE on Project), 신규 enum 0
- 주의: V번호 머지 직전 재확인(DATA.md §4.1), init_codegen 미러(T1), 도메인 기본값으로 생성자 비파괴(T2)

## 리뷰 결과

### plan-eng-review (2026-06-22) — 직접 eng 집중 리뷰

- ✅ TDD 구조 명확(각 task RED→GREEN→REFACTOR), 의존성 그래프 순환 없음(T1·T2 [] / T3 [1,2] / T4 [2] / T5 [2,3,4]).
- ✅ 파일 겹침 직렬화 정합 — T4·T5의 BoardResponses.kt 겹침을 T5 depends-on에 4 포함으로 해소.
- ✅ 마이그레이션 V501 + init_codegen 미러(T1) + V번호 머지 직전 재확인 명시 (메모리 jooq-init-codegen-mirror, migration-vnumber).
- ✅ 도메인 기본값(wipLimit=null, swimlaneField=NONE)으로 기존 생성자 비파괴 (메모리 plan-files-constructor-injection).
- ✅ 권한 CREATE on Project 재사용, 신규 권한 enum 0.
- ⚠️ **CONCERN-1 (보강 완료)**: Task 5 서비스 메서드 `@Transactional` 명시 누락 → GREEN에 추가(쓰기, DEVELOPMENT.md §1.4).
- ⚠️ **CONCERN-2 (impl 주의)**: jOOQ codegen task 이름 + agile-planning detekt baseline 존재 여부는 impl에서 실측 (메모리 backend-detekt-lint-debt-unmasked, detekt-baseline-module-pattern). codegen은 clean 빌드 함정 주의(backend-clean-build-broken).
- ✅ **CONCERN-3 (해소)**: 예외 처리 — BoardExceptionHandler가 ResponseStatusException 상태 전파(400/404) + catch-all 변질 차단을 이미 갖춤. PATCH 핸들러는 ResponseStatusException만 던지면 됨(신규 핸들러 불필요).
- **BLOCKER: 없음**

### plan-devex-review (2026-06-22) — API 일관성/DX

- ✅ 엔드포인트 RESTful 일관(PATCH + DataResponse 봉투), 에러 형식 ProblemDetail + AGILE_ 접두사 일관.
- ✅ GET 응답 확장은 가산만(truncated/unplacedCount/카드 정렬 보존), 추가 cross-BC 조회 0.
- ⚠️ **D1 (보강 완료)**: path variable을 기존 getBoard `{id}`와 통일(보드 PATCH `/{id}`, 컬럼 `/{id}/columns/{columnId}`).
- ℹ️ D2 (수용): cardCount 별도 필드 미추가 — cards 배열 length로 충분(미니멀).
- ℹ️ D3 (수용): 컬럼 404와 보드 404가 동일 일반 메시지 — 보안 정책상 의도(내부 정보 미노출).
- **BLOCKER: 없음**
