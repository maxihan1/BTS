# FR-API-01 — 이슈 CRUD REST API 표준화

> slug: fr-api-01-issue-crud
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-30

## Brief

사용자 원문: "fr-api-01 진행하자"

FR-API-01 — 이슈 CRUD REST API (표준화). 명세 `docs/plan/product/search-export-import.md §5.1`.
- 우선순위: 필수
- 선행: issue-tracking §2.1.1
- Plan slug(명세): `search/api-issue-crud`

D 단계 (명세):
- D1. 도메인 — API 응답 표준 (페이지네이션, 에러 포맷) (backend-engineer)
- D2. 명세 — OpenAPI 3.1 스펙 작성 (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — issue-tracking API에 cursor pagination + bulk ops 표준 적용 (backend-engineer)
- D5. 백엔드 테스트 — OpenAPI 스펙 검증 + contract test (backend-engineer)
- D6. 프론트 UI — (해당 없음 — API 문서는 §A) (-)
- D7. E2E — Postman/Insomnia 시나리오 (qa-engineer)

classify 결과: type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **구현 BC**: issue-tracking (논리적 FR은 search-export-import §5.1, 데이터·API 본체가 issue-tracking)
- **영향 엔티티**: 없음 — 도메인 신설 0. 이 작업은 **transport(API 계약) 표준화**. cursor 토큰/envelope/OpenAPI는 도메인 개념이 아니라 기술 계약 → glossary 미추가, ADR로 기록.
- **현재 상태 실측**:
  - 버저닝 `/api/v1` 이미 전면 적용 (신규 아님)
  - `Pageable`/`Page<` 사용 컨트롤러는 **`IssueController` 하나뿐** (이슈 목록 + changelog). 나머지 목록 API는 unpaged List 반환
  - 단건 응답 `DataResponse<T>`(`{data}`) — SDD 11.3 정합, meta 없음
  - 에러 포맷 이미 RFC 7807 `ProblemDetail` + `errorCode` (BC별 핸들러 분산, type 상대 토큰)
  - springdoc 미통합, bulk ops는 FR-IS-05로 이미 존재

- **핵심 결정 (Maxi 확정, 2026-06-30)**:
  1. **cursor pagination 병행 도입** — offset 미제거, 프론트 무회귀. cursor 모드만 envelope 반환, offset은 기존 `Page` 유지
  2. **OpenAPI 전역 springdoc 통합** + Swagger UI 게시 (명세 §A)
  3. **적용 범위 = issue-tracking 전 목록 API** — 단 cursor는 실효 대상(이슈 목록/changelog) 우선, unpaged 목록 envelope은 spec서 회귀 영향 조사 후 확정
  4. 에러 포맷은 RFC 7807 유지·보강(전환 아님), type 절대 URI화는 이슈 API 한정
  5. bulk ops는 FR-IS-05 정비/문서화만, 신규 도메인 0

- **기존 결정 충돌**: SDD 11.1 "offset 대신 cursor" 원칙 ↔ 현 offset 구현 + 프론트 소비. **병행 도입으로 해소**(전면 전환 기각).
- **관련 ADR**: [docs/decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md](../decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-api-01-issue-crud.md](../specs/2026-06-30-fr-api-01-issue-crud.md)

핵심 시나리오 3줄 요약.
- 외부 클라이언트는 `?cursor=&limit=N` → `{data, meta.page.next}` envelope으로 끊김 없이 순회(keyset seek), 기존 프론트는 `?page&size` offset Page 그대로(무회귀)
- 위변조/형식오류 cursor·모드 충돌(cursor+page)은 400 RFC 7807 ProblemDetail
- springdoc 통합 → `/swagger-ui`+`/v3/api-docs`(OpenAPI 3.1), 이슈 CRUD + 전 목록 API annotation, contract test로 스펙↔응답 동기화

**범위 해석(게이트1 재확인)**: cursor+envelope 실적용=이슈목록/changelog, "전 목록 API"=OpenAPI 문서화 대상(unpaged 목록 응답형태 불변, 무회귀 우선).

## Brainstorming Check

✅ 통과 (1회 iteration). adversarial sanity check로 gap 3건 발견·보강(cursor 위변조 방어 근거·OpenAPI Bearer 스킴·springdoc 범위 issue-tracking 한정). Maxi 결정 필요 gap 없음.

## Plan

> 전부 issue-tracking 모듈(단일 Gradle 모듈) → 컴파일 락으로 사실상 직렬. `IssueController.kt`를 Task 3·5·7이 공유해 직렬 병목. wave 병렬 이득 제한적이나 논리 TDD 분해 유지.
> 검증 명령의 모듈 경로는 `:backend:issue-tracking:test`로 표기(implementer가 settings.gradle 실제 경로로 조정).

### Task 1. CursorCodec — cursor 토큰 인코딩/디코딩 유틸

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/cursor/CursorCodec.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/cursor/CursorCodecTest.kt`]
- depends-on: []

**RED**: `CursorCodecTest`
- `encode(createdAt, id)` → `decode(token)` round-trip 동일값 (`OffsetDateTime` + `UUID`)
- 토큰은 `v1:` prefix + Base64URL
- 위변조/형식오류 토큰 → `CursorDecodeException` (빈 문자열은 "첫 페이지" 처리 — null 반환, 예외 아님)
- 다른 버전 prefix(`v2:`) → `CursorDecodeException`

**GREEN**: `CursorCodec` object — `encode`: `"$createdAt|$id"` → Base64URL + `v1:` prefix. `decode`: prefix 검증 → Base64URL 디코드 → split → 파싱. 실패 시 `CursorDecodeException`.

**REFACTOR**: prefix/구분자 상수화 + KDoc. `CursorPosition(createdAt, id)` 값 객체 도입.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*CursorCodecTest"`

---

### Task 2. 이슈 목록 cursor seek repository 쿼리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`]
- depends-on: []

**RED**: `IssueRepositoryTest`
- `listWithTypeByCursor(projectKey, cursorPosition?, limit, viewerId, access, filter)` — keyset seek
- cursor null(첫 페이지) → 최신 limit건, `(created_at DESC, id DESC)`
- cursor=(특정행) → 그 행보다 엄밀히 작은 `(created_at, id)`만, 중복/누락 0
- created_at 동률 2건 → id DESC tie-break로 안정 정렬(누락 없음)
- 기존 `listWithType`의 BROWSE visibility 술어 + filter(status/assignee/label/component) 그대로 적용
- `limit+1` fetch로 hasNext 판정

**GREEN**: jOOQ `WHERE (ISSUES.CREATED_AT, ISSUES.ID) < (:c, :id)` row-value 비교(또는 `(created_at < :c) OR (created_at = :c AND id < :id)`) `.orderBy(CREATED_AT.desc(), ID.desc()).limit(limit + 1)`. 기존 쿼리 빌더 재사용.
- **인덱스 실측**: `EXPLAIN`으로 `(created_at DESC, id DESC)` 커버 인덱스 사용 확인. 부재 시 별도 마이그레이션 추가(아래 Note).

**REFACTOR**: 정렬/seek 술어 helper 추출. 기존 `listWithType`과 공통 빌더 공유.

**Note (조건부 마이그레이션)**: issues keyset 인덱스 부재 확인 시 `V0xx__issues_cursor_index.sql`(`CREATE INDEX ... ON issues (created_at DESC, id DESC) WHERE deleted_at IS NULL`) 추가 + `init_codegen.sql` 하단 미러([[jooq-init-codegen-mirror]]) + V번호 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]). impl 중 db-engineer 협조. 있으면 no-op.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueRepositoryTest"`

---

### Task 3. 이슈 목록 service + controller cursor 모드 + envelope

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTest.kt`]
- depends-on: [1, 2]

**RED**: `IssueControllerTest`
- `GET /issues?projectKey=X&cursor=&limit=50` → 200 `{data:[...], meta:{page:{next, limit}}}` (cursor 모드)
- `GET /issues?projectKey=X&page=0&size=20` → 기존 `Page<IssueResponse>` 그대로 (offset 모드 **무회귀**)
- `cursor`+`page` 동시 → `PaginationModeConflictException` (Task 4가 400 매핑)
- `next` round-trip: 1페이지 `next`를 2페이지 `?cursor=`에 넣어 끊김 없이 순회, 마지막 `next=null`
- `limit > 100` → 400 (기존 제약)

**GREEN**:
- `IssueApplicationService.listIssuesByCursor(actor, projectKey, cursor: CursorPosition?, limit, filter): CursorPage<IssueResponse>` — BROWSE 권한 + `repo.listWithTypeByCursor` + `maskFieldsForPage` 재사용, hasNext로 `next` 토큰(`CursorCodec.encode`) 생성
- `IssueController.list` 분기: `cursor` param 존재 → cursor 모드(envelope), 아니면 기존 offset 경로 유지. 동시 지정 시 `PaginationModeConflictException` throw
- `CursorPageResponse<T>(data, meta)` + `PageMeta(page: PageCursor(next, limit))` DTO

**REFACTOR**: envelope DTO를 cursor 패키지로, KDoc에 모드 분기 정책 명시.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueControllerTest"`

---

### Task 4. cursor 에러 → RFC 7807 ProblemDetail 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandlerTest.kt`]
- depends-on: [3]

**RED**: `IssueExceptionHandlerTest`
- `CursorDecodeException` → 400, `errorCode="issue.invalid_cursor"`, `type="https://atlas.docs/errors/issue.invalid_cursor"`, RFC 7807 필드(title/status/detail/instance) 충족
- `PaginationModeConflictException` → 400, `errorCode="issue.pagination_mode_conflict"`
- detail에 내부 토큰/스택 비노출

**GREEN**: `IssueExceptionHandler`에 두 `@ExceptionHandler` 추가 → `ProblemDetail` 반환. type 절대 URI는 이슈 API 한정([[domain-exception-http-handler-basepackage-scope]] — 핸들러 스코프 확인).

**REFACTOR**: 에러코드/타입 URI 상수화. 기존 핸들러 패턴과 정렬.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueExceptionHandlerTest"`

---

### Task 5. changelog cursor 모드 + envelope

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueChangelogService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepositoryTest.kt`]
- depends-on: [1]

**RED**: changelog cursor seek 테스트
- `GET /issues/{key}/changelog?cursor=&limit=N` → envelope `{data, meta.page.next}`
- `?page&size` → 기존 `Page` 유지(무회귀)
- 이미 `(created_at DESC, id DESC)` 정렬 + `idx_issue_change_group_issue` 커버 인덱스 활용
- VIEW 권한(404 게이트) 기존 동작 유지

**GREEN**: `findChangelogByCursor(actor, key, cursor, limit)` keyset seek(기존 ORDER BY 재사용). controller `changelog` 분기.

**REFACTOR**: 이슈 목록 cursor와 공통 envelope DTO 공유.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*ChangeHistoryRepositoryTest" --tests "*IssueControllerTest"`

---

### Task 6. springdoc 통합 + OpenApiConfig + Swagger UI + bearerAuth

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/build.gradle.kts`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/config/OpenApiConfig.kt`, `backend/modules/issue-tracking/src/main/resources/application.yml`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/config/OpenApiDocsIntegrationTest.kt`]
- depends-on: []

**RED**: `OpenApiDocsIntegrationTest` (Testcontainers 부팅)
- `GET /v3/api-docs` → 200 + `openapi` 필드가 `3.1.x`
- `securitySchemes.bearerAuth`(http/bearer/JWT) 정의 존재
- `GET /swagger-ui/index.html`(또는 `/swagger-ui.html`) → 200
- 경로 별칭 `/api/v1/docs`·`/api/v1/openapi.json` 매핑(설정 가능 시)

**GREEN**:
- `build.gradle.kts`: `implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")` (정확 버전 고정, Maxi 확인 대상 의존성)
- `application.yml`: `springdoc.api-docs.version=openapi_3_1`, 경로 설정
- `OpenApiConfig`: `@OpenAPIDefinition` + `@SecurityScheme(name="bearerAuth", type=HTTP, scheme="bearer", bearerFormat="JWT")` + API info

**REFACTOR**: info(제목/버전/설명) 상수화, KDoc.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*OpenApiDocsIntegrationTest"`

---

### Task 7. OpenAPI annotation — 이슈 CRUD/목록 + 전 목록 컨트롤러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, 전 목록 컨트롤러(watcher/component/version/worklog/link/template/customfield/attachment web 패키지), `backend/modules/issue-tracking/src/test/kotlin/.../OpenApiAnnotationTest.kt`]
- depends-on: [6]

**RED**: `OpenApiAnnotationTest`
- 생성된 `/v3/api-docs`에 이슈 CRUD/목록/전이 + 전 목록 GET 엔드포인트의 operationId/요약/응답 스키마 존재
- cursor envelope 응답 스키마 등록 확인
- **응답 형태 변경 0**(annotation만, 기존 응답 불변 회귀 가드)

**GREEN**: `@Operation`/`@ApiResponse`/`@Parameter`/`@Schema` 부여. unpaged 목록은 annotation만(응답 불변).

**REFACTOR**: 공통 에러 응답(`@ApiResponse` 400/401/403/404/409) 공유 정의.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*OpenApiAnnotationTest"`

---

### Task 8. contract test — OpenAPI 스펙 ↔ 실제 응답 일치

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/OpenApiContractTest.kt`]
- depends-on: [3, 6, 7]

**RED**: `OpenApiContractTest` (Testcontainers 부팅)
- `/v3/api-docs` 스펙에서 `GET /api/v1/issues` cursor 응답 스키마 추출 → 실제 cursor 응답 JSON이 스키마(필드 `data`/`meta.page.next`/`meta.page.limit`)와 일치
- 이슈 단건/생성 응답(`data` 래퍼)이 스펙과 일치
- 에러 응답(400 ProblemDetail)이 스펙 컴포넌트와 일치

**GREEN**: 스펙 fetch + 응답 구조 단언(JSON path 기반). springdoc 생성 스키마와 실응답 대조.

**REFACTOR**: 공통 단언 helper.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*OpenApiContractTest"`

## Plan 메타

- task 수: 8
- 예상 wave: 같은 모듈 + `IssueController.kt` 공유(Task 3·5·7)로 사실상 직렬. Wave 1(독립): T1·T2·T6 / 이후 T3→T4→T5→T7→T8 직렬 경향. bts-impl이 depends-on+files로 실제 wave 계산.
- TDD 강제: yes (모든 task RED→GREEN→REFACTOR)
- 신규 의존성: `springdoc-openapi-starter-webmvc-ui:2.6.0` (Maxi 확인 대상, Task 6)
- 조건부 마이그레이션: issues keyset 인덱스(Task 2, 부재 시만)
- 무회귀 게이트: offset/Page 기존 테스트 + apps/web 미변경
- 추가 검증: ktlint/detekt, 백엔드 clean 빌드

## 리뷰 결과 (← /bts-review-plan 채움)
