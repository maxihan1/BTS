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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
