# FR-IM-02 PR-C — Import 값 매핑 (status/type/priority)

> slug: fr-im-02-pr-c-value-mapping
> type: backend
> agent: backend-engineer
> BC: search-export-import
> 생성: 2026-07-04

## Brief

FR-IM-02 Import 매핑 에픽(3-PR)의 마지막 백엔드 조각. 소스 파일(CSV/JSON)의 **status/type/priority 값**을 BTS canonical 값으로 명시 매핑. PR-A(#230 필드매핑) → PR-B(#233 사용자매핑) → **PR-C(값매핑, 이 PR)** → 프론트 D6/D7.

승인된 PR-A ADR [`2026-07-03-fr-im-02-import-mapping`](../decisions/2026-07-03-fr-im-02-import-mapping.md) D3 상속: `import_value_mappings(import_job_id, target_field, source_value, target_value)`, status/type/priority 한정(component/version은 기존 name-match 유지).

### Maxi 확정 결정 (2026-07-04)

1. **자동추천 3종 전부** — collect가 distinct 소스값을 BTS 타깃값에 자동매칭 추천.
   - status → 기존 `WorkflowStateCatalog.listStates`(shared-kernel SPI, project-workflow 구현) 재사용.
   - type → **신규 cross-BC 포트**(프로젝트 이슈타입 목록 조회, shared-kernel 인터페이스 + issue-tracking 구현).
   - priority → canonical 5종(Highest~Lowest) 고정.
2. **priority 포함** (ADR대로) — 단 파서가 priorityName을 사전 정규화(5종, 미인식→null)하므로 커스텀 우선순위명은 값매핑 전 소실. 값매핑은 canonical 5종 간 remap만 유효(실효 narrow, 명시).
3. **validate 폴딩** — 별도 value-validate 엔드포인트 없이 검증을 confirm에 폴딩(PR-B 선례, 프론트 마법사 D6/D7 후속).

### 엔지니어링 판단 (컨트롤러 결정)

- **값 치환은 프로세서 국소화** — 프로세서가 `import_value_mappings`를 1회 로드해 행별 `typeName`/`statusName`/`priorityName`(파싱된 문자열)을 매핑된 타깃값으로 치환. 커맨드는 이미 이 문자열 필드를 담으므로 **shared-kernel 커맨드 필드 추가 불필요·issue-tracking 어댑터 무변경**(PR-B보다 작은 blast radius).
- 모듈: shared-kernel(신규 type 카탈로그 포트) + issue-tracking(포트 구현) + search-export-import(주). project-workflow는 WorkflowStateCatalog 재사용(무변경).
- V608 `import_value_mappings`.

## 도메인 정리

- **BC**: search-export-import(주) + shared-kernel(신규 SPI) + issue-tracking(SPI 구현). project-workflow는 `WorkflowStateCatalog` 재사용(무변경).
- **영향 엔티티**: `ImportJob`(기존) · `import_value_mappings`(신규 매핑 레코드 테이블, `import_mappings`/`import_user_mappings`와 동형 — rich 도메인 엔티티 아님).
- **새 용어**: **값 매핑(value mapping)** — `target_field`(STATUS/TYPE/PRIORITY) × `source_value` → `target_value`. 필드 매핑(PR-A)/사용자 매핑(PR-B)의 자매 3번째 차원. glossary 미등재(필드/사용자 매핑도 미등재, 선례 일치 — 정의는 ADR).
- **신규 cross-BC SPI**: `IssueTypeCatalog.listTypes(): List<IssueTypeRef>` (shared-kernel 인터페이스 + issue-tracking `IssueTypeCatalogAdapter` 구현, `IssueTypeRepository.findAll()` 재사용). `WorkflowStateCatalog` 자매. **이슈 타입은 전역**(issue_types 스키마 project 스코프 없음·`findAll()` 존재)이라 projectKey 불필요. 기존 `IssueTypeLookupPort`(project-workflow 패키지)는 **ID 기반 조회**라 "전체 목록"에 부적합 → 신규 SPI 정당.
- **재사용 포트**: `WorkflowStateCatalog.listStates(projectKey, issueTypeKey=null)` — status 타깃 후보(기본 매핑 워크플로우 상태). `Propagation.MANDATORY`라 호출부 트랜잭션 필요(collect suggest 시 주의).
- **priority 타깃 후보**: canonical 5종(Highest~Lowest). 파서 정규화 집합과 **단일 출처 공유**(drift 방지). 파서 사전정규화로 커스텀 우선순위명은 값매핑 전 소실 → 값매핑은 5종 간 remap만 유효(실효 narrow, Maxi 확정).
- **값 치환 위치**: 프로세서가 `import_value_mappings` 1회 로드 → 행별 `typeName`/`statusName`/`priorityName`(파싱 문자열) 치환. 커맨드 기존 문자열 필드 재사용 → **shared-kernel 커맨드/issue-tracking 어댑터 무변경**(PR-B보다 작은 blast radius).
- **기존 결정 충돌**: 없음. PR-A ADR이 PR-C를 D3/D5에서 사전설계(3-테이블 모델·순서 의존).
- **관련 ADR**: [2026-07-03-fr-im-02-import-mapping](../decisions/2026-07-03-fr-im-02-import-mapping.md)(에픽 정본·D3 값매핑 상속) · [2026-05-27-shared-kernel-extraction] · [2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup](신규 SPI 패턴 선례). **신규 ADR 파일 미생성** — IssueTypeCatalog SPI는 WorkflowStateCatalog 미러 + 두 기존 ADR 패턴 계승. 도메인 정리에 결정 명시(bts-review-plan eng 리뷰가 이 판단 검증).

## 스펙

전체 스펙. [docs/specs/2026-07-04-fr-im-02-pr-c-value-mapping.md](../specs/2026-07-04-fr-im-02-pr-c-value-mapping.md)

핵심 시나리오 3줄 요약.
- collect(POST .../mapping/values): 필드매핑 기준 전량스캔 → status/type/priority distinct 소스값 + 자동추천(status=WorkflowStateCatalog·type=IssueTypeCatalog·priority=파서 canonical5).
- confirm 확장: valueMappings를 CAS 트랜잭션(필드→사용자→값 saveAll→enqueue) 저장, 타깃값 실재검증(type/priority 엄격·status 관대) 트랜잭션 밖.
- 프로세서가 매핑 로드해 행별 typeName/statusName/priorityName 치환(ValueMappingNormalizer 삼자일치), 미매핑=기존 name-match 폴백(하위호환).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 3건 보정).
- Gap A(수정): FR7 타깃값 검증 필드별 비대칭 — type/priority 엄격, status 관대(apply-time best-effort 위임, 타 타입 유효상태 오거부 회피).
- Gap B(단순화): collect 응답 occurrences 제거(PR-B 동형).
- Gap C(명확화): priority canonical 5 출처 = search 내부 ImportRowParser 정규화 집합 재사용(신규 하드코드/포트 0).

## Plan

> 경로 접두. search = `backend/modules/search-export-import/src`, issue = `backend/modules/issue-tracking/src`, shared = `backend/modules/shared-kernel/src`.
> PR-B 산출물 미러: UserMappingNormalizer→ValueMappingNormalizer, ImportUserMappingRepository→ImportValueMappingRepository, UserCollectionResult→ValueCollectionResult, UserCollectionResponse→ValueCollectionResponse.

### Task 1. IssueTypeCatalog SPI + issue-tracking 어댑터

**메타**.
- agent: `backend-engineer`
- files: [`shared/main/kotlin/com/bts/shared/issue/IssueTypeCatalog.kt`, `issue/main/kotlin/com/bts/issue/type/adapter/outbound/IssueTypeCatalogAdapter.kt`, `issue/test/kotlin/com/bts/issue/type/adapter/outbound/IssueTypeCatalogAdapterTest.kt`]
- depends-on: []

**RED**: `IssueTypeCatalogAdapterTest` — 2개 이슈타입 시드 후 `listTypes()`가 `IssueTypeRef(key,name)` 2건 반환. 빈 DB → 빈 리스트.
**GREEN**: `interface IssueTypeCatalog { @Transactional(readOnly=true) fun listTypes(): List<IssueTypeRef> }`(shared) + `IssueTypeCatalogAdapter`(issue-tracking)가 `IssueTypeRepository.findAll().map { IssueTypeRef(it.key.value, it.name) }`.
**REFACTOR**: KDoc — WorkflowStateCatalog 미러, 타입 전역 명시, ADR 2건 참조.
**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueTypeCatalogAdapterTest'`

### Task 2. V608 import_value_mappings + init_codegen 미러

**메타**.
- agent: `backend-engineer`
- files: [`search/main/resources/db/migration/search-export-import/V608__import_value_mappings.sql`, `search/main/resources/db/codegen/init_codegen.sql`, `search/test/kotlin/com/bts/search/imports/job/SchemaMigrationImportTest.kt`]
- depends-on: []

**RED**: `SchemaMigrationImportTest` 확장 — import_value_mappings 존재·3컬럼·복합PK(import_job_id,target_field,source_value)·`chk_import_value_mappings_field` CHECK·FK CASCADE 행동·`target_value` NOT NULL.
**GREEN**: V608 DDL(스펙 §데이터 모델) + init_codegen.sql 동일 미러 추가.
**REFACTOR**: DDL 주석(NULL 비대칭=PR-B와 다름 명시).
**검증**: `./gradlew :modules:search-export-import:test --tests '*SchemaMigrationImportTest'`

### Task 3. ValueTargetField enum + ValueMappingNormalizer (F2 삼자일치)

**메타**.
- agent: `backend-engineer`
- files: [`search/main/kotlin/com/bts/search/imports/mapping/ValueTargetField.kt`, `search/main/kotlin/com/bts/search/imports/mapping/ValueMappingNormalizer.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ValueMappingNormalizerTest.kt`]
- depends-on: []

**RED**: 정규화 `"  In Progress "`→`"in progress"`; `collectValues(rows)`가 `statusName`/`typeName`/`priorityName` distinct를 `Map<ValueTargetField, Set<String>>`로 수집(null/blank 제외·대소문자 dedup).
**GREEN**: `enum class ValueTargetField { STATUS, TYPE, PRIORITY }` + `object ValueMappingNormalizer { fun normalize(raw)=trim().lowercase(); fun collectValues(rows: List<ParsedImportRow>): Map<ValueTargetField, Set<String>> }`.
**REFACTOR**: KDoc — F2 유일 정규화 원천(수집·저장·치환), UserMappingNormalizer 미러.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ValueMappingNormalizerTest'`

### Task 4. ImportValueMappingRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`search/main/kotlin/com/bts/search/imports/mapping/repository/ImportValueMappingRepository.kt`, `search/test/kotlin/com/bts/search/imports/mapping/repository/ImportValueMappingRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**: `saveAll(jobId, Map<Pair<ValueTargetField,String>, String>)` delete-then-batchInsert; `findByJobId` 실 DB round-trip으로 저장 값 재조회(F1). 빈 맵=삭제만.
**GREEN**: jOOQ DSL saveAll/findByJobId(target_value NOT NULL — `?: error` 방어 불요, source/field/target 모두 non-null).
**REFACTOR**: KDoc(멱등·jOOQ DSL 전용·PR-B NULL 비대칭 대비).
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportValueMappingRepositoryTest'`

### Task 5. priority canonical 노출 + collectValues + confirm 확장 + full-boot @MockBean

**메타**.
- agent: `backend-engineer`
- files: [`search/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `search/main/kotlin/com/bts/search/imports/mapping/ImportMappingService.kt`, `search/main/kotlin/com/bts/search/imports/mapping/ValueCollectionResult.kt`, `search/main/kotlin/com/bts/search/imports/mapping/ImportMappingExceptions.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceValueTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceUserTest.kt`, `search/test/kotlin/com/bts/search/config/OpenApiAnnotationTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportMappingFlowIntegrationTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportUserMappingFlowIntegrationTest.kt`]
- depends-on: [1, 3, 4]

**RED**: `ImportMappingServiceValueTest`(mockk) — (a) collectValues: 무효 필드매핑 조기종료(C1, storage 헤더 1회), distinct 값+자동추천(status=WorkflowStateCatalog·type=IssueTypeCatalog·priority=canonical5); (b) confirm valueMappings: CAS 트랜잭션 저장, 상태충돌 시 값 saveAll 0회, FR7 비대칭(type/priority 미실재 422·status 관대), E6 중복 422, **저장된 target_value가 catalog canonical 정확형인지 단언(C1 casing)**.
**GREEN**: ImportRowParser에 canonical priority 이름 집합 노출(`internal val canonicalPriorityNames` 또는 접근자); ImportMappingService에 `issueTypeCatalog`·`workflowStateCatalog`·`importValueMappingRepository`·`transactionTemplate`(기존) 주입 → `collectValues(jobId,actor,fieldMappings)`(C1 선검증→전량스캔→ValueMappingNormalizer.collectValues→NFR2 짧은 tx로 후보조회+suggest) + `confirm(...valueMappings: List<Triple<ValueTargetField,String,String>> = emptyList())`(validateValueMappings 트랜잭션 밖→CAS 안 값 saveAll); `ValueCollectionResult`; `ImportValueMappingInvalidException`(errorCode IMPORT_VALUE_MAPPING_INVALID). **★C1 casing 수정**: validateValueMappings가 type/priority 타깃을 catalog와 대소문자 무시 매칭 후 **매칭된 canonical 정확형(대문자)을 저장값으로 반환**(프로세서 `PRIORITY_NUMBER_BY_NAME` exact-case 맵과 정합, 소문자 target 조용한 소실 차단). status는 관대라 원본 저장(어댑터 ignoreCase 매칭). **★C3 로그**: collect/confirm 로그에 소스/타깃 원문 대량 노출 금지(jobId·count만, 기존 패턴 계승). **기존 mockk 테스트(ImportMappingServiceTest·ImportMappingServiceUserTest)·통합 TestConfig(2개 Flow 통합테스트)·OpenApiAnnotationTest에 신규 생성자 인자/@MockBean(IssueTypeCatalog·WorkflowStateCatalog) 배선**(생성자 파급+NFR4 module-compile, B2 수정).
**REFACTOR**: KDoc(값매핑 흐름·FR7 비대칭 근거).
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportMappingServiceValueTest' --tests '*ImportMappingServiceTest' --tests '*OpenApiAnnotationTest'`

### Task 6. ImportJobProcessor 값 치환

**메타**.
- agent: `backend-engineer`
- files: [`search/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `search/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportMappingFlowIntegrationTest.kt`, `search/test/kotlin/com/bts/search/imports/mapping/ImportUserMappingFlowIntegrationTest.kt`]
- depends-on: [3, 4, 5]

**RED**: `ImportJobProcessorTest` — 값매핑 로드 후 행별 `typeName`/`statusName`/`priorityName`이 타깃값으로 치환됨; 미매핑 값은 원본 유지(폴백); null 소스값 미치환; **priority 치환 후 canonical 정확형이라 `PRIORITY_NUMBER_BY_NAME` 조회 성공(C1 회귀)**.
**GREEN**: `importValueMappingRepository` 주입, `findByJobId` 1회 로드, `toCommand` 전/중 `ValueMappingNormalizer.normalize` 키로 `(field,source)→target` 조회 치환. **기존 mockk 테스트(ImportJobProcessorTest) + 2개 Flow 통합 TestConfig의 ImportJobProcessor 생성자 호출부(+importValueMappingRepository) 배선**(B1 module-compile 수정).
**REFACTOR**: KDoc.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportJobProcessorTest'`

### Task 7. collect 엔드포인트 + confirm valueMappings DTO + 예외 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`search/main/kotlin/com/bts/search/imports/web/ImportMappingController.kt`, `search/main/kotlin/com/bts/search/imports/web/dto/MappingConfirmRequest.kt`, `search/main/kotlin/com/bts/search/imports/web/dto/ValueCollectionResponse.kt`, `search/main/kotlin/com/bts/search/imports/web/ImportExceptionHandler.kt`, `search/test/kotlin/com/bts/search/imports/web/ImportMappingControllerTest.kt`]
- depends-on: [5, 6]

**RED**: `ImportMappingControllerTest` — `POST /imports/{jobId}/mapping/values`(actor 우선추출·미인증 401·미소유 404·200 3필드 응답); confirm에 valueMappings 전달 200; 미실재 타깃 422 IMPORT_VALUE_MAPPING_INVALID; 필드매핑 무효 422.
**GREEN**: collect 핸들러(currentActorId 먼저→collectValues), MappingConfirmRequest에 `valueMappings: List<ValueMappingEntry>` + `toValueMappingTriples()`, ValueCollectionResponse, ImportExceptionHandler에 ImportValueMappingInvalidException→422 매핑.
**REFACTOR**: KDoc/OpenAPI 주석.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportMappingControllerTest'`

### Task 8. 값매핑 통합 flow + 하위호환 회귀

**메타**.
- agent: `backend-engineer`
- files: [`search/test/kotlin/com/bts/search/imports/mapping/ImportValueMappingFlowIntegrationTest.kt`]
- depends-on: [5, 6, 7]

**RED**: 실 DB+IssueImportPort stub — (a) 전체 flow: collect→confirm(valueMappings)→process, stub이 치환된 커맨드 `typeName`/`statusName`/`priorityName` 캡처; (b) 회귀: 값매핑 미제공 시 커맨드 원본 값 불변(하위호환). vacuous 방지(stub 캡처 단언).
**GREEN**: (통합테스트라 GREEN 구현 없음 — 앞 task 산출물로 통과 확인). TestConfig에 IssueTypeCatalog·WorkflowStateCatalog seeded fake + ImportValueMappingRepository 실 빈.
**REFACTOR**: 시나리오 KDoc.
**검증**: `./gradlew :modules:search-export-import:test --tests '*ImportValueMappingFlowIntegrationTest'`

## Plan 메타

- task 수: 8
- 예상 wave: 6 (Wave1=[T1,T2,T3] 병렬·독립/타모듈, Wave2=[T4], Wave3=[T5], Wave4=[T6], Wave5=[T7], Wave6=[T8]). 무거운 search 모듈 task(T5~T8)는 생성자/모듈컴파일 파급 회피 위해 **직렬화**.
- TDD 강제: yes
- 병렬 dispatch: Wave1만 3-병렬 — **엄격 커밋 위생**(자기파일 pathspec `git commit --no-verify -- <path>`만, `--amend`/`-A`/rebase/reset/stash 금지, [[parallel-dispatch-precommit-hook-race]]).
- 추가 검증: 3모듈 test + ktlintCheck + detekt `--rerun-tasks`(search CI 없음, false-green 방지) + verify-master-plan(FR수 불변 123).
- 회귀 가드: F1(insert round-trip)·F2(ValueMappingNormalizer 삼자)·CAS 중복 enqueue·NFR4(full-boot @MockBean)·하위호환(미매핑 폴백).

## 리뷰 결과

### plan-eng-review (2026-07-04, Plan 아키텍트 에이전트, 코드 대조)

초기 판정 **BLOCKER 2 + CONCERN 3** → plan/spec 수정 완료 후 **해소**.

- **B1 (수정됨)**: T6 files에 ImportJobProcessor 생성자 호출부 2개 Flow 통합테스트(ImportMappingFlowIntegrationTest·ImportUserMappingFlowIntegrationTest) 누락 → `:search:test` 컴파일 붕괴. **T6 files에 추가.**
- **B2 (수정됨)**: T5 files에 ImportMappingServiceUserTest.kt(PR-B 산출물, 8-인자 positional 생성자 호출) 누락 → 컴파일 붕괴. **T5 files에 추가.**
- **C1 (수정됨)**: 프로세서 priority 치환이 대소문자 정확 일치 맵(PRIORITY_NUMBER_BY_NAME)이라 소문자 target_value 저장 시 priority 조용히 소실. **FR7에 저장값 canonical화(type/priority 매칭 canonical 정확형 저장) 추가**, T5 RED에 casing 단언·T6 RED에 조회성공 회귀 추가.
- **C2 (수정됨)**: FR7 type "엄격" 근거 오류(어댑터 resolveTypeId는 hard-fail 아닌 Task 폴백 best-effort). **근거를 "명시 매핑 오타 조기차단 UX"로 교정 + mapped-vs-unmapped 의도적 경로 비대칭 문서화**(엄격 유지). status 관대 근거는 정확(그대로).
- **C3 (수정됨)**: NFR3 로그 안전 → T5 GREEN에 "jobId·count만 로깅" 한 줄 명시.
- **확인/반영됨**: FR6 CAS 순서·중복 enqueue 차단(PR-B 정합) · NFR2 collect tx 경계(listStates MANDATORY→짧은 tx) · IssueTypeCatalog BC 격리(IssueTypeLookupAdapter 동일 패턴, ArchUnit 안전) · V608 번호·init_codegen 미러 · F1/F2 회귀 가드 · NFR4 full-boot @MockBean(OpenApiAnnotationTest 유일 full-boot).

- **BLOCKER: 없음 (전부 plan 반영 완료)**

### plan-ceo-review — skip (type=backend, auth/migration 아님)
