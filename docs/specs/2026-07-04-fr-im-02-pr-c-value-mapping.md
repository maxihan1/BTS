# FR-IM-02 PR-C — Import 값 매핑 (status/type/priority) 스펙

> BC: search-export-import · type: backend · 2026-07-04
> 상속 ADR: [2026-07-03-fr-im-02-import-mapping](../decisions/2026-07-03-fr-im-02-import-mapping.md) (D3 값매핑 · D5 순서 의존)
> 선행: PR-A(#230 필드매핑) · PR-B(#233 사용자매핑). 이 PR = 에픽 마지막 백엔드.

## 배경 요약

소스(CSV/JSON)의 status/type/priority **값**을 BTS canonical 값으로 명시 매핑. 미매핑 값은 기존 name-match 폴백(하위호환). 값 치환은 프로세서에서 `ParsedImportRow`의 `statusName`/`typeName`/`priorityName`(파싱 문자열)을 매핑된 타깃값으로 교체 — 커맨드 기존 문자열 필드 재사용, shared-kernel 커맨드/issue-tracking 어댑터 **무변경**.

## 사용자 시나리오 (Given-When-Then)

- **S1 값 수집.** Given AWAITING_MAPPING job + 필드매핑 확정. When `collect values` 호출. Then status/type/priority로 흘러드는 컬럼의 **distinct 소스값** + 각 값의 **자동추천 타깃값**(대소문자 무시 정확 일치)을 target_field별로 반환.
- **S2 값 확정.** Given 사용자가 값매핑 확정(일부는 자동추천 수락, 일부 수동). When `confirm(valueMappings)`. Then 유효성 검증 후 `import_value_mappings` 저장 + `AWAITING_MAPPING→PENDING` 전이 + enqueue(기존 워커 경로).
- **S3 치환 적용.** Given 확정된 값매핑. When 워커가 행 처리. Then 프로세서가 매핑 1회 로드해 행별 status/type/priority 값을 타깃값으로 치환 → 어댑터의 기존 name 해석에 전달. 미매핑 값은 원본 유지(name-match 폴백).
- **S4 하위호환.** Given 즉시-업로드 경로(매핑 없음) 또는 값매핑 미제공. When 워커 처리. Then 기존 동작 완전 불변(회귀 0).

## 기능 요구사항 (FR)

- **FR1. collect 엔드포인트.** `POST /api/v1/imports/{jobId}/mapping/values`. body = 확정 필드매핑(PR-A `MappingValidateRequest` 본문 재사용). 전량 스캔으로 파싱된 각 행에서 `statusName`/`typeName`/`priorityName`의 distinct 소스값을 `ValueMappingNormalizer.normalize`로 정규화해 target_field별 수집. CSV는 필드매핑으로, JSON은 canonical로 파싱된 결과를 동일하게 스캔(파서가 CSV/JSON 차이 흡수).
- **FR2. 자동추천.** 각 distinct 소스값에 대해 타깃 후보 중 **정규화 정확 일치**를 추천값으로 제시(없으면 null=미추천).
  - status → `WorkflowStateCatalog.listStates(projectKey, issueTypeKey=null)`의 상태명.
  - type → `IssueTypeCatalog.listTypes()`의 타입명(전역).
  - priority → canonical 5종(Highest/High/Medium/Low/Lowest). **출처 = search 내부 `ImportRowParser`의 기존 정규화 집합**(파서가 이미 이 5종으로 정규화하므로 단일 출처 재사용, 신규 하드코드/포트 불필요·drift 0).
- **FR3. C1 500 회피.** collect가 전량 스캔 전에 필드매핑을 선검증(PR-A `computeValidationResult`) — 무효면 422(ImportMappingInvalidException), `ImportParseException`→500 차단. JSON은 필드매핑 검증 스킵 후 스캔.
- **FR4. IssueTypeCatalog SPI 신설.** `com.bts.shared.issue.IssueTypeCatalog.listTypes(): List<IssueTypeRef>` (shared-kernel). issue-tracking `IssueTypeCatalogAdapter`가 `IssueTypeRepository.findAll()` 재사용해 구현(타입 전역). `@Transactional(readOnly=true)`.
- **FR5. confirm valueMappings 확장.** `MappingConfirmRequest`에 `valueMappings: List<ValueMappingEntry(targetField, sourceValue, targetValue)>` 추가. `confirm(...)` 시그니처에 `valueMappings: List<Triple<ValueTargetField, String, String>>` 추가(PR-B userMappings 뒤, 기존 컨트롤러 호출부 파급 최소).
- **FR6. CAS 트랜잭션 저장.** validate(타깃값 실재검증, cross-BC I/O)는 트랜잭션 **밖**. `transitionToPending`(CAS) → 필드 saveAll(PR-A) → 사용자 saveAll(PR-B) → **값 saveAll(신규)** → enqueue 순으로 한 트랜잭션. CAS false → 상태충돌 예외로 중복 enqueue·중복 저장 차단.
- **FR7. 타깃값 실재검증 (필드별 강도 비대칭 — Brainstorming Gap A, eng 리뷰 C1/C2 교정).** `targetValue` 유효성을 target_field별로 다른 강도로 검증. 미실재 → 422(ImportValueMappingInvalidException, errorCode=IMPORT_VALUE_MAPPING_INVALID).
  - **type → 엄격.** `IssueTypeCatalog.listTypes()`에 대소문자 무시 일치하는 타입명이 없으면 422. **이유(교정)**: 어댑터 `resolveTypeId`는 미매칭 타입명에 hard-fail이 아니라 **경고+기본 Task 폴백**(best-effort). 그러나 **명시 값매핑은 사용자 의도**이므로 오타(예 "Storyy")를 confirm에서 즉시 차단해 조용한 Task 강등을 예방한다(UX 근거). **의도적 경로 비대칭**: 명시 매핑 대상=검증(엄격), 미매핑 원본 값=apply-time best-effort 폴백. 문서화된 의식적 결정.
  - **priority → 엄격.** canonical 5종에 대소문자 무시 일치 없으면 422. type과 동일 UX 근거.
  - **status → 관대.** targetValue가 비공백이면 통과. **이유**: 어댑터 `applyImportedStatus`가 apply 시점 `statusNameMatches`로 best-effort(미매칭=경고+시작상태 유지, tx 오염 0), 상태는 타입별 워크플로우라 `listStates(null)` 엄격검증 시 타 타입 유효 상태를 오거부한다. status는 apply-time best-effort에 위임(회귀 0). collect 자동추천(FR2)은 listStates(null)로 힌트만 제시.
  - **★C1 저장값 canonical화.** type/priority는 검증 시 catalog와 대소문자 무시 매칭한 **canonical 정확형(예 "Medium")을 target_value로 저장**(사용자 입력 소문자 그대로 저장 금지). **이유**: 프로세서 priority 치환은 대소문자 정확 일치 맵(`PRIORITY_NUMBER_BY_NAME`, 대문자 키)이라 소문자 target 저장 시 조용히 소실된다. status는 어댑터가 ignoreCase 매칭이라 원본 저장 무해.
- **FR8. import_value_mappings 저장(멱등).** delete-then-batchInsert(jobId 스코프). jOOQ DSL 전용. `@Transactional`.
- **FR9. 프로세서 값 치환.** `ImportJobProcessor`가 `findByJobId`로 매핑 1회 로드. 행별로 `normalize(statusName)` 등을 키로 `(targetField, normalizedSource)→targetValue` 조회해 치환. 미매핑이면 원본 값 유지(name-match 폴백). null 소스값은 치환 대상 아님.
- **FR10. F2 정규화 삼자 일치.** `ValueMappingNormalizer`(신규 object)가 수집(FR1)·저장 키(FR6/8)·치환 조회(FR9)의 유일 정규화 원천(`trim().lowercase()`). 별도 정규화 잔재 금지 — 어긋나면 조용한 오치환.

## 비기능 요구사항 (NFR)

- **NFR1. 하위호환.** 매핑 미제공(즉시 경로)·빈 valueMappings 시 기존 name-match 동작 완전 불변. 회귀 테스트로 봉쇄.
- **NFR2. collect 트랜잭션 경계.** `WorkflowStateCatalog.listStates`는 `Propagation.MANDATORY`(호출부 트랜잭션 필수). collect의 타깃후보 조회 부분만 짧은 `transactionTemplate`으로 감싸 파일 스트리밍 중 DB 커넥션 장기 점유 회피(collectUsers 패턴 계승).
- **NFR3. 로그 안전.** 로그에 소스값/타깃값 원문 대량 노출 금지(jobId·count만). PII/토큰 노출 0.
- **NFR4. full-boot 회귀.** search 모듈 full-boot 테스트 컨텍스트에 신규 `IssueTypeCatalog` + 기존 `WorkflowStateCatalog` stub 빈(@MockBean) 배선(PR-B UserLookupPort 회귀 교훈).

## API 인터페이스 (REST)

```
POST /api/v1/imports/{jobId}/mapping/values          # collect (자동추천 포함)
  body: { fieldMappings: [{ sourceField, targetField }] }   # PR-A MappingValidateRequest 재사용
  200: { fields: [
          { targetField: "STATUS"|"TYPE"|"PRIORITY",
            values: [{ sourceValue, suggestedTargetValue|null }] } ] }   # occurrences 미포함(Gap B, PR-B 동형)
  401 미인증 · 404 미소유/미존재 · 409 상태불일치 · 422 필드매핑 무효

PUT/POST /api/v1/imports/{jobId}/mapping/confirm     # 기존 confirm 확장
  body: { fieldMappings, userMappings, valueMappings: [{ targetField, sourceValue, targetValue }], dryRun? }
  200 · 422 IMPORT_VALUE_MAPPING_INVALID(미실재 타깃) · 409 상태불일치
```

- collect는 actor를 서비스 호출보다 **먼저** 추출(미인증 401), `requireOwnedAwaitingMapping`으로 소유·상태 확인(auth-extraction-before-resource-lookup).

## 데이터 모델 변경

```sql
-- V608__import_value_mappings.sql (+ init_codegen.sql 미러)
CREATE TABLE import_value_mappings (
    import_job_id UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    target_field  TEXT NOT NULL,               -- 'STATUS' | 'TYPE' | 'PRIORITY'
    source_value  TEXT NOT NULL,               -- 정규화된 소스 값
    target_value  TEXT NOT NULL,               -- 매핑된 BTS canonical 값
    PRIMARY KEY (import_job_id, target_field, source_value),
    CONSTRAINT chk_import_value_mappings_field
        CHECK (target_field IN ('STATUS','TYPE','PRIORITY'))
);
```

- `target_value` **NOT NULL** — 값매핑 행은 항상 "매핑됨"을 의미(PR-B 사용자매핑의 NULL=미매핑 폴백과 **비대칭**). 미매핑 소스값은 행을 두지 않음 → 프로세서 원본 유지(name-match 폴백).
- FK `ON DELETE CASCADE`(부모 job 하드삭제 동반). 소프트삭제 없음(종속 하위 테이블).
- 복합 PK로 job 범위 (target_field, source_value) 유일. `SchemaMigrationImportTest` 동반 검증.

## 엣지 케이스

- **E1 빈 valueMappings.** 저장 0행(delete만), 전량 폴백. 정상.
- **E2 미실재 타깃값.** 존재하지 않는 status/type name 또는 범위 밖 priority → 422(FR7). 저장·전이 없음.
- **E3 priority 사전정규화(narrow).** 파서가 priorityName을 canonical 5로 정규화·미인식→null. 따라서 collect의 priority distinct = canonical 5 부분집합. 값매핑은 5종 간 remap만 유효(커스텀명은 이미 소실). 스펙에 명시, 저장/치환은 정상 동작.
- **E4 대소문자/공백 변형.** 같은 소스값 변형은 `ValueMappingNormalizer`로 dedup(수집·저장·치환 동일 키).
- **E5 CSV/JSON 공통.** 값매핑은 CSV(필드매핑 파싱)·JSON(canonical 파싱) 모두 적용 — 파서가 이미 `statusName`/`typeName`/`priorityName`으로 정규화하므로 collect/치환은 파싱 결과만 본다.
- **E6 중복 (targetField, sourceValue).** 요청에 정규화 후 동일 키 2건 이상이면 값이 같아도 422(DUPLICATE, PR-B 보수적 거부 선례) — `.toMap()` 조용한 붕괴 방지.
- **E7 스캔에 없는 소스값 저장.** 유효 타깃이면 저장 허용(무해, 워커 미매칭). 거부하지 않음.
- **E8 status issueTypeKey.** listStates에 issueTypeKey=null(기본 매핑 워크플로우) 사용 — 값매핑은 타입별 워크플로우 구분 없이 상태명 후보 제시.

## 제약 조건

- 값 치환은 **프로세서**에서 커맨드 기존 문자열 필드 교체 — 어댑터/커맨드 무변경.
- BC 격리: 신규 SPI는 shared-kernel 인터페이스(consumer는 인터페이스만 의존), 구현은 issue-tracking. 테스트 stub 빈.
- Flyway만·jOOQ DSL만·트랜잭션 경계 명시·인증 우회 불가(절대 규칙).
- 신규 ADR 파일 미생성(PR-A ADR 상속 + SPI는 기존 두 ADR 패턴 계승).

## 측정 가능한 완료 기준

1. collect가 3 target_field별 distinct 소스값 + 자동추천을 반환(status/type/priority 각 통합테스트).
2. confirm이 valueMappings를 CAS 트랜잭션에서 저장, 상태충돌 시 값 saveAll 0회(단위테스트).
3. F1: import_value_mappings 실 insert round-trip 통합테스트(NOT NULL target_value).
4. F2: ValueMappingNormalizer 수집·저장·치환 삼자 동일 정규화(단위테스트 대소문자 dedup).
5. 프로세서 치환 통합테스트: 매핑된 값 치환 + 미매핑 폴백(IssueImportPort stub이 커맨드 값 캡처).
6. 미실재 타깃값 422(FR7) · 필드매핑 무효 422(FR3) · 미인증 401.
7. 하위호환: 매핑 미제공 시 기존 워커 동작 회귀 0.
8. 3모듈 test + ktlint + detekt green, verify-master-plan 123/123(FR수 불변).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 3건 보정).
- **Gap A (수정)**: FR7 타깃값 검증을 필드별 비대칭으로 — type/priority 엄격(미존재=행 hard-fail 방지), status 관대(apply-time best-effort 위임, 타 타입 유효상태 오거부 회피).
- **Gap B (단순화)**: collect 응답에서 occurrences 카운트 제거(PR-B 동형, YAGNI).
- **Gap C (명확화)**: priority canonical 5 출처 = search 내부 ImportRowParser 정규화 집합 재사용(신규 하드코드/포트 0, drift 0).
