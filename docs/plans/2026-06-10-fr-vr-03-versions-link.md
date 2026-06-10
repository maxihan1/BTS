# FR-VR-03 — Affects/Fix Version 연결

> slug: fr-vr-03-versions-link
> type: feature (backend + frontend + e2e 풀스택)
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary_bc: issue-tracking
> 생성: 2026-06-10

## Brief

이슈를 버전에 다대다로 연결한다 (Jira 정석).
- Affects Version (영향 버전) — 이 이슈/버그가 발견된·영향받는 버전
- Fix Version (수정 버전) — 이 이슈가 수정될·수정된 목표 버전
- 둘 다 다대다 — 조인 테이블 2개: issue_affects_versions, issue_fix_versions

product 정본: docs/plan/product/issue-tracking.md §3.2.3
선행: FR-VR-01 (버전 생성, PR #67), FR-VR-02 (버전 상태, PR #105)
Plan slug(정본): issue/versions-link

### D단계 (product 정본)
- [ ] D1. 도메인 — Affects vs Fix 의미 (backend-engineer)
- [ ] D2. 명세 (backend-engineer)
- [ ] D3. 데이터 모델 — issue_affects_versions, issue_fix_versions (db-engineer)
- [ ] D4. 백엔드 (backend-engineer)
- [ ] D5. 백엔드 테스트 (backend-engineer)
- [ ] D6. 프론트 UI — 버전 셀렉터 2종 (designer → frontend-engineer)
- [ ] D7. E2E (qa-engineer)

## 도메인 정리 (/bts-domain)

- **BC**: issue-tracking (단일 BC, 격리 위반 없음 — versions·issues 모두 같은 모듈)
- **새 용어**: 없음. glossary.md 이미 정의 — "버전 (Version) — 릴리스 단위. fix/affects 관계로 이슈에 연결"
- **영향 엔티티**:
  - `Issue` (애그리거트) — `affectsVersionIds: List<UUID>`, `fixVersionIds: List<UUID>` 신규 필드 (기존 `componentIds` 패턴 동형)
  - `Version` (읽기 참조만 — 검증 시 프로젝트·존재 확인)
  - 신규 조인 테이블 2개: `issue_affects_versions`, `issue_fix_versions`
- **선례 (정확한 동형 패턴)**: 이슈↔컴포넌트 연결 (FR-CM-02/03)
  - 조인 테이블: `V012__issue_components.sql` (issue_id, component_id PK, ON DELETE CASCADE, FK 인덱스는 후미 컬럼만)
  - 도메인: `Issue.componentIds` + `assignComponents()`/`clearComponents()` + `create(componentIds=)`
  - API: `PATCH /api/v1/issues/{key}/components` + `ChangeComponentsRequest{componentIds, expectedVersion}` (전체 교체, OCC 낙관적 잠금)
  - 검증: `IssueApplicationService.validateComponents` — 프로젝트 불일치/비활성 시 422
  - 응답: `IssueResponse.componentIds` (단건 경로에서만 채움)
  - repo: `IssueRepository.insertComponents` (배치 INSERT)
- **기존 결정 충돌**: 없음
- **관련 ADR**:
  - `docs/adr/2026-06-03-version-model-and-permission-deferral.md` (Version 모델 + 권한 이연 — VersionPermissionResolver 포트)
  - `docs/adr/2026-06-10-version-status-and-transitions.md` (버전 상태 전이)
  - 신규 ADR 후보: affects/fix 연결 정책 (ARCHIVED 허용 + 권한 재사용) — bts-spec/plan에서 판단

### Maxi 확정 결정 (2026-06-10)
1. **생성 시점 미지원** — PATCH 교체만 (`/affects-versions`, `/fix-versions`). 생성 시 지정은 후속 이연. FR-VR-02와 동일 결의 범위.
2. **ARCHIVED 정책** — API는 삭제 안 된 모든 버전(UNRELEASED/RELEASED/ARCHIVED) 연결 허용. 프론트 셀렉터에서만 ARCHIVED 기본 숨김 (Jira 정석). 이미 연결된 ARCHIVED 링크는 보존.

### 기본값 (선례 따름, 미질의)
- **두 엔드포인트 분리** — affects/fix는 독립 관계이므로 `/affects-versions`, `/fix-versions` 별도 PATCH (컴포넌트 1관계 1엔드포인트 패턴 확장).
- **전체 교체 + OCC** — `{versionIds, expectedVersion}`, changeComponents와 동일하게 expectedVersion 필수 + version bump.
- **타 프로젝트 버전 422** — 이슈 프로젝트 ≠ 버전 프로젝트면 422 (컴포넌트 validateComponents 동형).
- **읽기 측 노출** — `IssueResponse`에 `affectsVersionIds`/`fixVersionIds` 단건 경로 채움 (D6 프론트 표시용).

## 스펙 (/bts-spec)

전체 스펙. [docs/specs/2026-06-10-fr-vr-03-versions-link.md](../specs/2026-06-10-fr-vr-03-versions-link.md)

핵심 시나리오 요약.
- `PATCH /api/v1/issues/{key}/affects-versions`, `.../fix-versions` 2종 — `{versionIds, expectedVersion}` 전체 교체 + OCC.
- 검증: 이슈 프로젝트 소속 + 미삭제 버전만 (422 `ISSUE_VERSION_NOT_FOUND`). ARCHIVED 허용.
- 단건 조회에 `affectsVersionIds`/`fixVersionIds` 노출. 권한은 `IssuePermission.UPDATE` 재사용.
- 신규 V017 (issue_affects_versions, issue_fix_versions) + init_codegen 미러. Issue 도메인 2필드 추가 (componentIds 동형).
- 프론트 셀렉터 2종 (ARCHIVED 기본 숨김) + E2E.

## Brainstorming Check (/bts-spec)

✅ 통과 (1회, gap 없음). 히스토리/PDF/클론 모두 컴포넌트도 미처리 → 동형 불필요. Maxi 결정 2건 도메인 단계 확정.

## Plan (/bts-plan)

> 동형 선례: 이슈↔컴포넌트 (FR-CM-02/03). 모든 task는 그 패턴을 그대로 따른다 — implementer는 대응 컴포넌트 파일을 grep해 동일 구조로 작성.
> 네이밍 주의: version 패키지에 이미 `VersionNotFoundException`이 있으므로 issue-side 신규 예외는 **`IssueLinkedVersionNotFoundException`** (com.bts.issue.domain), 에러코드는 **`ISSUE_LINKED_VERSION_NOT_FOUND`** 로 명명. OCC 충돌은 기존 `IssueVersionConflictException`/`ISSUE_VERSION_CONFLICT` 재사용("version" 의미 중복 회피).

### Task 1. V017 마이그레이션 + init_codegen 미러 + 스키마 마이그레이션 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V017__issue_version_links.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/IssueVersionLinksSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `IssueVersionLinksSchemaMigrationTest` — issue_affects_versions / issue_fix_versions 테이블 존재 + 컬럼(issue_id, version_id, created_at) + 복합 PK + version_id 인덱스 + FK CASCADE 검증. (선례: `IssueComponentsSchemaMigrationTest.kt`)
실패: 테이블 미존재.

**GREEN**: V017 작성 (스펙 §데이터 모델 SQL 그대로). init_codegen.sql에 동일 DDL 미러(메모리 jooq-init-codegen-mirror — jOOQ가 ISSUE_AFFECTS_VERSIONS/ISSUE_FIX_VERSIONS 타입 생성하도록).

**REFACTOR**: 테이블/컬럼 COMMENT (V012 스타일).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueVersionLinksSchemaMigrationTest"`

### Task 2. Issue 도메인 — affectsVersionIds / fixVersionIds + assign/clear

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: IssueTest — `assignAffectsVersions(ids)` distinct 정규화, `clearAffectsVersions()` 빈 목록, fix 변형 동일. create 기본값 emptyList. (선례: componentIds 테스트)
**GREEN**: `affectsVersionIds`/`fixVersionIds: List<UUID> = emptyList()` 필드 + `assignAffectsVersions`/`clearAffectsVersions`/`assignFixVersions`/`clearFixVersions` (componentIds의 assignComponents/clearComponents 동형, copy + filterNotNull().distinct()). create 시그니처엔 추가 안 함(생성 시점 미지원).
**REFACTOR**: KDoc.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueTest"`

### Task 3. IssueRepository — replaceAffectsVersions / replaceFixVersions + 단건 조회 시 버전 채움

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueVersionLinksRepositoryTest.kt`]
- depends-on: [1, 2]   # 테이블(jOOQ codegen) + 도메인 필드 필요

**RED**: `IssueVersionLinksRepositoryTest` — replaceAffectsVersions(key, issueId, ids, expectedVersion) DELETE+INSERT+version bump 왕복, expectedVersion 불일치 시 rows=0, findByKeyWithType이 affects/fixVersionIds 채움. fix 변형 동일. (선례: `IssueComponentsRepositoryTest.kt`)
**GREEN**: jOOQ로 `replaceAffectsVersions`/`replaceFixVersions` (replaceComponents 동형 — DELETE FROM issue_affects_versions WHERE issue_id + batch INSERT + UPDATE issues SET version=version+1 WHERE id AND version=expectedVersion, 반환 rows). 단건 조회 경로(withSingleDetail)에 **affects/fix 각각 독립 단일-컬렉션 SELECT 2개** (`findAffectsVersionIdsByIssue`, `findFixVersionIdsByIssue` — componentIds의 `findActiveComponentIdsByIssue` 동형). **한 쿼리에서 두 조인 테이블 동시 LEFT JOIN 금지**(곱집합 — 메모리 cartesian-product-jooq-leftjoin-count, CONCERN-3). **읽기 쿼리는 `versions.deleted_at` 필터 미적용 + status 필터 절대 금지** — ARCHIVED 링크는 반드시 보여야 함(EC8). dangling 소프트삭제 링크는 프론트가 이름 미해소로 graceful(CONCERN-4).
**REFACTOR**: 공통 헬퍼 추출 고려(affects/fix 거의 동일 — 테이블 인자화). 단, 가독성 우선.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueVersionLinksRepositoryTest"`

### Task 4. IssueApplicationService — changeAffectsVersions / changeFixVersions + validateVersions + 신규 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueChangeVersionsServiceTest.kt`]
- depends-on: [3]

**RED**: `IssueChangeVersionsServiceTest` — validateVersions: 타 프로젝트/삭제 버전 → `IssueLinkedVersionNotFoundException`(422), ARCHIVED 통과, OCC rows=0 → 409, distinct, 반환 IssueResponse에 채워짐. (선례: `IssueChangeComponentsServiceTest.kt`)
**GREEN**: `changeAffectsVersions`/`changeFixVersions` (changeComponents 동형, **자동 담당자 단계 제외**): assertPermission(UPDATE) → findByKey(404) → assignXxxVersions → validateVersions(versionRepository.findById(id, projectId) ?: throw IssueLinkedVersionNotFoundException) → repo.replaceXxxVersions → rows==0 → IssueVersionConflictException → findByKeyWithType.withSingleDetail(). `AppChangeVersionsRequest{versionIds, expectedVersion}` 추가. 신규 예외 `IssueLinkedVersionNotFoundException(versionId)`.
**REFACTOR**: affects/fix 공통부 private 헬퍼.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueChangeVersionsServiceTest"`

### Task 5. IssueController — 2 PATCH 엔드포인트 + DTO + IssueResponse 노출 + 예외 핸들러 + MVC 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/ChangeVersionsRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueVersionLinksControllerTest.kt`]
- depends-on: [4]

**RED**: `IssueVersionLinksControllerTest` (@WebMvcTest) — PATCH /affects-versions, /fix-versions 200, 422(ISSUE_LINKED_VERSION_NOT_FOUND), 409(ISSUE_VERSION_CONFLICT), 404, expectedVersion 누락 400. IssueResponse에 affectsVersionIds/fixVersionIds 직렬화. (선례: `IssueComponentsControllerTest.kt`)
**GREEN**: `ChangeVersionsRequest{versionIds: List<UUID> = emptyList(), @NotNull expectedVersion}` (affects/fix 공용 1 DTO). IssueController에 `@PatchMapping("/{key}/affects-versions")` `changeAffectsVersions` + `/{key}/fix-versions` `changeFixVersions`. IssueResponse에 `affectsVersionIds`/`fixVersionIds: List<UUID> = emptyList()`. IssueExceptionHandler에 `IssueLinkedVersionNotFoundException` → 422 매핑(메모리 domain-exception-http-handler-basepackage-scope — basePackages 스코프 확인, HTTP 통합으로 검증).
**REFACTOR**: KDoc + log.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueVersionLinksControllerTest"`

### Task 6. 통합 테스트 S1~S12 (Testcontainers)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueVersionLinksIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**: 스펙 S1~S12 end-to-end (실 repo + 시드). ARCHIVED 허용(S5), 타 프로젝트 422(S6), 소프트삭제 422(S7), OCC 409(S8), distinct(S11), 단건 조회 노출(S12), affects/fix 독립(EC7). **422/409 응답은 HTTP 바디의 `errorCode` 문자열까지 단언** (`ISSUE_LINKED_VERSION_NOT_FOUND` / `ISSUE_VERSION_CONFLICT`) — 도메인예외가 잘못된 핸들러로 새어 500 변질되는지 검출(CONCERN-2, 메모리 domain-exception-http-handler-basepackage-scope). FK CASCADE cleanup 주의(메모리 join-table-fk-cascade-testcontainers-cleanup). (선례: `IssueComponentsIntegrationTest.kt`)
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueVersionLinksIntegrationTest"`

### Task 7. 프론트 — VersionMultiSelect 2종 + api 훅 + Zod + MSW + IssueMetaPanel 배선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/VersionMultiSelect.tsx`, `apps/web/src/components/issue/VersionMultiSelect.test.tsx`, `apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/api/issue-versions.ts`, `apps/web/src/api/issue-versions.test.ts`, `apps/web/src/api/issues.types.ts`, `apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/issue-fixtures.ts`]
- depends-on: [5]   # API 계약 필요. T6과 병렬(gradle vs pnpm 별도 모듈)

**RED**: VersionMultiSelect.test — 멀티셀렉트, **ARCHIVED 버전 기본 숨김**(이미 연결된 ARCHIVED는 표시), 변경 시 useChangeAffectsVersions/useChangeFixVersions 호출. issue-versions.test — Zod 파싱 + PATCH 호출. (선례: `ComponentMultiSelect.tsx/test`)
**GREEN**: `VersionMultiSelect`(variant: 'affects'|'fix' props로 재사용) + `useChangeAffectsVersions`/`useChangeFixVersions`(invalidate-only 또는 캐시머지 — 메모리 mutation-setquerydata-partial-response-flicker). IssueResponse Zod에 affectsVersionIds/fixVersionIds optional 추가 + 기존 issue 인라인 mock/fixture에 필드 보강(메모리 zod-schema-strengthen-inline-mock-fanout — grep 전수). IssueMetaPanel에 셀렉터 2개 배선. MSW issue-handlers에 PATCH /affects-versions, /fix-versions stateful 핸들러(메모리 msw-mutation-stateful-refetch).
**REFACTOR**: i18n 라벨, 접근성.
**검증**: `pnpm --filter web test VersionMultiSelect issue-versions && pnpm --filter web typecheck`

### Task 8. E2E — affects/fix 버전 연결/교체/해제 happy path

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-versions-link.spec.ts`]
- depends-on: [7]

**RED→GREEN**: 이슈 상세 진입 → Fix Version 셀렉터 열기 → v1.3 선택 → 저장 → 표시 확인 → 교체 → 해제. Affects 동일. ARCHIVED 버전이 셀렉터에 안 보이는지 확인. (선례: `issue-components.spec.ts`) 셀렉터 텍스트 중복 시 컨테이너 한정(메모리 ui-pr-defer-e2e-regression-latent, playwright-getbyrole-exact-strict-mode). loginAsAlice fixture FR-AU-07 회귀 주의(메모리 e2e-loginasalice-fixture-fr-au-07-regression) — 이슈 E2E 1단계 로그인 깨졌으면 별도 확인.
**검증**: `pnpm --filter web test:e2e issue-versions-link`

### Task 9. product 정본 D1~D7 마킹 + 문서 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/issue-tracking.md`]
- depends-on: [6, 7, 8]

**작업**: §3.2.3 D1~D7 체크박스 [x] + PR #107 표기. FR 카운트 **변화 없음**(FR-VR-03은 기존 등록 FR — fr-index/SDD/README 수치 불변). glossary "버전" 항목에 affects/fix 연결 구현 완료 반영은 선택. 대시보드 재생성은 bts-merge가 처리. `bash scripts/verify-master-plan.sh` 통과 확인.
**검증**: `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: 9
- 예상 wave: 6~7 (백엔드 레이어는 본질적 직렬 — 메모리 bts-plan-wave-gradle-module-compile). Wave1: T1+T2 병렬 / W2: T3 / W3: T4 / W4: T5 / W5: T6+T7 병렬(gradle·pnpm 별도) / W6: T8 / W7: T9
- TDD 강제: yes (T1~T8). T9는 문서.
- BC: issue-tracking 단일 (격리 위반 없음)
- 신규 권한 코드: 없음 → FR-PM 권한 시드 카운트 테스트 영향 0
- 추가 검증: ktlint, detekt(baseline 동결), vitest, playwright

## 리뷰 결과 (/bts-review-plan)

### eng 집중 독립 리뷰 (Plan 에이전트, 2026-06-10)
type=backend → autoplan 대신 독립 엔지니어링 리뷰 (메모리 bts-review-plan-autoplan-overkill). 코드 대조 기반.

- 🔴 **BLOCKER-1 (해소됨)** — 에러코드 spec/plan 불일치 (`ISSUE_VERSION_NOT_FOUND` vs `ISSUE_LINKED_VERSION_NOT_FOUND`). T5/T6 테스트가 다른 코드 단언 → 게이트 결정적 차단 위험. **해소: spec 4곳 + NFR3을 `ISSUE_LINKED_VERSION_NOT_FOUND` / `IssueLinkedVersionNotFoundException`로 통일** (ISSUE_VERSION_CONFLICT는 보존).
- 🟡 **CONCERN-2 (반영)** — T6 통합 테스트에서 422/409 HTTP 바디 `errorCode` 문자열까지 단언 (도메인예외 500 변질 검출). → T6 GREEN에 명시.
- 🟡 **CONCERN-3 (반영)** — 읽기측 affects/fix를 각각 독립 SELECT 2개로 (한 쿼리 동시 LEFT JOIN 곱집합). → T3 GREEN에 명시.
- 🟡 **CONCERN-4 (반영)** — 버전 읽기 쿼리 deleted_at 필터 미적용 + status 필터 절대 금지(ARCHIVED 표시 보장). → T3 GREEN에 명시.
- ✅ **확인됨 (안전)**:
  - CONCERN-1 — `VersionRepository.findById`가 `deleted_at IS NULL` 필터(코드 확인) → S7(소프트삭제 422) 정상, ARCHIVED는 통과(S5). BLOCKER 아님.
  - CONCERN-5 — `replaceComponents` @Transactional 단일 메서드, version bump 정확히 1회, partial update 차단. 자동 담당자 제외로 이중 bump 위험 원천 없음.
  - SUGGESTION-1 — V017 정확(최신 V016 다음), init_codegen 미러는 V012 형식 따름.
  - SUGGESTION-2 — T6(gradle)/T7(pnpm) 파일 겹침 0, 병렬 안전. depends-on 정확.
  - SUGGESTION-3 — T7 Zod 2필드는 optional+default emptyArray, 기존 fixture 회귀 방지 명시됨.

- **종합 판정**: BLOCKER 해소 + CONCERN 3건 plan 반영 완료 → **승인 가능**.

### plan-eng-review BLOCKER: 없음 (해소 후)
