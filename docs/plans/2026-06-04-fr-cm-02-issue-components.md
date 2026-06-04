# FR-CM-02 — 이슈에 다중 컴포넌트 할당

> slug: fr-cm-02-issue-components
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary BC: issue-tracking
> 생성: 2026-06-04

## Brief

FR-CM-02 이슈에 다중 컴포넌트 할당. 한 이슈에 여러 컴포넌트(프로젝트 하위 영역 분류)를 붙인다.

- 선행: FR-CM-01(컴포넌트 CRUD, PR #59/#64) · FR-IS-03(담당자 전용 서브리소스 PATCH, PR #49/#51) · FR-PM-03(컴포넌트 권한 prod resolver, PR #70/#72) 모두 완료.
- D1 도메인: Issue Aggregate에 componentIds 다중 연결.
- D3 데이터: issue_components 다대다 테이블 (+ jOOQ init_codegen 미러).
- D4 백엔드: 이슈 컴포넌트 할당/해제 엔드포인트 (FR-IS-03 전용 서브리소스 패턴).
- D6 프론트: 다중 컴포넌트 셀렉터 UI.
- D7 E2E: Playwright.

분류 메모: classify-task 키워드 휴리스틱이 qa/migration으로 오분류 → Maxi 확인 후 feature 전체체인으로 override.

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: Issue(componentIds 추가), Component(기존 FR-CM-01), issue_components(신규 조인 테이블)
- 새 용어: 없음 (컴포넌트는 glossary 기존 항목). 관계 "이슈↔컴포넌트 다대다"만 명확화.
- 기존 결정 충돌: 없음. 라벨 ADR이 "컴포넌트=정규화 엔티티"로 구분 설계 → 조인 테이블 정합.

### 핵심 결정 (ADR로 기록)
- **D1 저장**: `issue_components` 정규화 조인 테이블(둘 다 issue-tracking 소유 → 실 FK). 복합 PK `(issue_id, component_id)` 멱등성. 관계라 소프트삭제 불요(연결 해제=행 DELETE). jOOQ init_codegen 미러 필수.
- **D2 API**: 전체교체(set) 의미론. `PATCH /api/v1/issues/{key}/components` 전용 서브리소스(FR-IS-03 담당자 동형) + expectedVersion 낙관락. 빈 배열=전부 해제.
- **D3 권한**: `IssuePermission.UPDATE` + `IssueScope.Project`(이슈 편집권). ComponentPermissionResolver(CRUD 관리권)와 구분. Global 사용 금지(prod 무조건 거부 함정).
- **D4 검증**: 같은 프로젝트 + 활성 컴포넌트만(ComponentRepository.findById(id, projectId) 활용). 위반 422 COMPONENT_NOT_FOUND. 요청 중복 ID distinct 정규화(도메인).
- **D5 읽기**: IssueResponse.componentIds 노출(초기엔 ID 목록만, 셀렉터가 이름 해소).

### 복제 선례 (ground-truth 확인됨)
- 도메인: `Issue.kt:62` assigneeId 옆 componentIds 추가, assignComponents()/clearComponents() 메서드.
- API: `IssueController.changeAssignee()` / `ChangeAssigneeRequest.kt` / `IssueApplicationService.changeAssignee()` / `IssueRepository.updateAssignee()`.
- 검증: UserLookupPort 422 패턴 → ComponentRepository.findById 422.
- 마이그레이션: 최신 V011 → 신규 **V012**. init_codegen.sql 미러. 조인 테이블 선례 bulk_operation_items(V008).
- 프론트: `IssueMetaPanel.tsx`(담당자 셀렉터 옆 다중 컴포넌트 셀렉터), `useChangeAssignee.ts` 복제, `components.ts`(fetchComponents).

- 관련 ADR: [docs/adr/2026-06-04-issue-component-assignment-model.md](../adr/2026-06-04-issue-component-assignment-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-04-fr-cm-02-issue-components.md](../specs/2026-06-04-fr-cm-02-issue-components.md)

핵심 시나리오 요약.
- `PATCH /api/v1/issues/{key}/components`에 컴포넌트 ID 전체 목록 + expectedVersion → 전체교체(set). 빈 배열=전부 해제.
- 검증 순서: 이슈 404 → 권한 403(UPDATE/Issue scope) → 같은프로젝트+활성 컴포넌트 422 COMPONENT_NOT_FOUND → 낙관락 409.
- 트랜잭션: version bump(낙관락) → issue_components DELETE → 신규 INSERT, 원자적.
- 읽기: componentIds는 단건 상세에만 노출(목록 생략), 활성 컴포넌트만 필터.
- 프론트: IssueMetaPanel 다중 셀렉터 + useChangeComponents(useChangeAssignee 복제) + fail-closed 게이팅.

Maxi 결정 3건: 목록=단건전용, 고아행=읽기시 활성필터, 개수상한=없음. 선례 자동결정 2건: 이력·알림 범위밖.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 6점 점검 후 3개 Maxi 결정 반영 + 2개 선례 자동결정 + 1개 트랜잭션 순서 스펙 보강. 스펙 §Brainstorming Check 참조.

## Plan

> 모든 백엔드 task는 `backend/modules/issue-tracking/`. gradlew는 repo 루트, task 경로 `:modules:issue-tracking`(메모리 subagent-ktlint-false-green).
> 의존성 핵심: jOOQ 상수는 `init_codegen.sql`에서 생성 → **T2(마이그레이션)가 T3(repository) 컴파일보다 먼저**(메모리 jooq-init-codegen-mirror).

### Task 1. 도메인 — Issue.componentIds + assignComponents/clearComponents

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueComponentsTest.kt`]
- depends-on: []

**RED**.
- 파일: `IssueComponentsTest.kt`
- 테스트:
  - `assignComponents distinct로 중복 제거`(입력 [C1,C1,C2] → componentIds [C1,C2])
  - `assignComponents null 요소 제외`
  - `clearComponents 빈 목록으로 비움`
  - `기본값은 빈 목록`
- 실패(예상): `Issue.componentIds`/`assignComponents`/`clearComponents` 미존재 → 컴파일 실패.

**GREEN**.
- `Issue.kt`: `assigneeId` 인근에 `val componentIds: List<UUID> = emptyList()` 추가.
- `fun assignComponents(ids: List<UUID>): Issue = copy(componentIds = ids.filterNotNull().distinct())`
- `fun clearComponents(): Issue = copy(componentIds = emptyList())`
- 개수 상한 없음(Maxi 결정). labels `validateAndNormalizeLabels` 정신만 차용(distinct).

**REFACTOR**. KDoc 추가, 정규화 헬퍼 일관성.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueComponentsTest'`

---

### Task 2. 데이터 — V012 issue_components + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V012__issue_components.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/component/IssueComponentsSchemaMigrationTest.kt`]
- depends-on: []

**RED**.
- 파일: `IssueComponentsSchemaMigrationTest.kt` (Testcontainers, 실 Postgres)
- 테스트: `information_schema`로 `issue_components` 테이블 존재 + 복합 PK `(issue_id, component_id)` + FK 2개(issues/components) + 인덱스 `idx_issue_components_component_id` 단언.
- 실패(예상): 테이블 없음 → 단언 실패.

**GREEN**.
- `V012__issue_components.sql`:
  ```sql
  CREATE TABLE issue_components (
      issue_id     UUID        NOT NULL REFERENCES issues(id),
      component_id UUID        NOT NULL REFERENCES components(id),
      created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
      PRIMARY KEY (issue_id, component_id)
  );
  CREATE INDEX idx_issue_components_component_id ON issue_components(component_id);
  ```
- `init_codegen.sql`에 **동일 DDL 미러**(jOOQ ISSUE_COMPONENTS 상수 생성 — 누락 시 T3 컴파일 불가, V005/V009 선례).

**REFACTOR**. 주석(이슈↔컴포넌트 다대다, 관계라 soft delete 없음).

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueComponentsSchemaMigrationTest'` + `./gradlew :modules:issue-tracking:generateJooq`(ISSUE_COMPONENTS 생성 확인).

---

### Task 3. Repository — 컴포넌트 교체/조회

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueComponentsRepositoryTest.kt`]
- depends-on: [2]   # jOOQ ISSUE_COMPONENTS 상수 필요

**RED**.
- 파일: `IssueComponentsRepositoryTest.kt` (Testcontainers)
- 테스트:
  - `replaceComponents 신규 목록 삽입 + version bump`(expectedVersion 일치 → rowcount 1)
  - `replaceComponents 기존 행 전체 삭제 후 교체`(set)
  - `replaceComponents stale version → 0 반환`(낙관락, 변경 없음)
  - `findActiveComponentIdsByIssue 활성 컴포넌트만`(소프트삭제 컴포넌트 제외)
- 실패(예상): 메서드 미존재.

**GREEN**.
- `IssueRepository.replaceComponents(key, issueId, componentIds, expectedVersion): Int`:
  - ① `UPDATE issues SET version=version+1, updated_at=now() WHERE key=? AND deleted_at IS NULL AND version=?` → rowcount 0이면 즉시 0 반환(낙관락 충돌, 삭제/삽입 안 함).
  - ② `DELETE FROM issue_components WHERE issue_id=?`
  - ③ `INSERT INTO issue_components(issue_id, component_id) VALUES ...`(목록, batch). 빈 목록이면 INSERT 생략.
  - 반환 1.
- `findActiveComponentIdsByIssue(issueId): List<UUID>`: `issue_components JOIN components ON component_id=components.id WHERE issue_id=? AND components.deleted_at IS NULL`. 카테시안 곱 주의(메모리 cartesian — 단일 컬렉션이라 안전, 다른 JOIN과 섞지 말 것).

**REFACTOR**. SQL 상수화, KDoc.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueComponentsRepositoryTest'`

---

### Task 4. Service — changeComponents + 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/AppChangeComponentsRequest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueChangeComponentsServiceTest.kt`]
- depends-on: [1, 3]   # Issue 도메인 메서드 + repo 메서드

**RED**.
- 파일: `IssueChangeComponentsServiceTest.kt` (MockK 단위)
- 테스트:
  - happy: 권한OK+이슈존재+컴포넌트 활성 → repo.replaceComponents 호출, IssueResponse 반환.
  - 422: componentId가 `ComponentRepository.findById(id, projectId)` null(없음/삭제/타프로젝트) → `ComponentNotFoundException`.
  - 404: 이슈 없음 → `IssueNotFoundException`.
  - 409: repo.replaceComponents 0 반환 → `IssueVersionConflictException`.
  - 403: permissionResolver false → `IssueAccessDeniedException`.
  - 중복 ID: 도메인 assignComponents distinct 경유(repository raw 우회 금지, 메모리 patch-merge-도메인-우회).
- 실패(예상): `changeComponents` 미존재.

**GREEN**.
- `AppChangeComponentsRequest(componentIds: List<UUID>, expectedVersion: Long)`.
- `changeComponents(actor, key, request): IssueResponse`:
  - `assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))` (담당자 선례 정확 일치).
  - `existing = repo.findByKey(key) ?: throw IssueNotFoundException`.
  - 각 componentId: `componentRepository.findById(id, existing.projectId) ?: throw ComponentNotFoundException(id)`(distinct 후 검증).
  - `updated = existing.assignComponents(request.componentIds)` (도메인 정규화).
  - `rows = repo.replaceComponents(key, existing.id.value, updated.componentIds, request.expectedVersion)`; 0이면 `IssueVersionConflictException`.
  - `log.info("issue_components_changed ...")`.
  - `repo.findByKeyWithType(key)?.withSingleDetail()` 반환(componentIds 포함).
- `ComponentNotFoundException`(422) 신설 — `AssigneeNotFoundException` 동형.

**REFACTOR**. KDoc, 검증 루프 추출.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueChangeComponentsServiceTest'`

---

### Task 5. Controller + DTO + IssueResponse.componentIds

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/ChangeComponentsRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueComponentsControllerTest.kt`]
- depends-on: [4]

**RED**.
- 파일: `IssueComponentsControllerTest.kt` (WebMvc 슬라이스 또는 MockMvc, service mock)
- 테스트: `PATCH /api/v1/issues/{key}/components` → 200 + componentIds 응답 / 422 / 409 / 404 매핑 / 잘못된 UUID 400.
- 실패(예상): 엔드포인트 미존재.

**GREEN**.
- `ChangeComponentsRequest(componentIds: List<UUID> = emptyList(), expectedVersion: Long?)` + `@field:NotNull expectedVersion`(담당자 ChangeAssigneeRequest 동형).
- `IssueController.changeComponents(@PathVariable key, @Valid @RequestBody req)`: `service.changeComponents(actor, IssueKey(key), AppChangeComponentsRequest(...))`. actor는 `ActorId(SYSTEM_ACTOR_UUID)`(선례 동형, 실추출 후속).
- `IssueResponse`에 `componentIds: List<UUID> = emptyList()` 추가. `from(...)` 단건 경로(`withSingleDetail`)에서 채움, 목록 경로는 빈 목록(Maxi 결정: 단건전용).
- 예외→상태 매핑: `ComponentNotFoundException`→422(GlobalExceptionHandler 또는 기존 핸들러에 추가).

**REFACTOR**. KDoc, errorCode 상수.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueComponentsControllerTest'`

---

### Task 6. 통합 테스트 — S1~S9 + prod 권한

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueComponentsIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**(end-to-end 검증 task — 실 Postgres+컨텍스트, 컨트롤러→서비스→repo 전 구간).
- 시나리오 S1(할당)/S2(부분교체)/S3(전부해제)/S4(없음·삭제 422)/S5(타프로젝트 422)/S6(409)/S8(404)/S9(중복정규화) + 읽기 시 활성 컴포넌트만 노출 검증.
- prod 권한: `@ActiveProfiles("prod")` 또는 prod resolver 주입으로 403(비prod AlwaysAllow 200) — 메모리 issue-scope-global-prod-hard-deny 회피 검증(Issue scope는 거부 아님).
- 메시지 삭제/멱등: 같은 목록 재전송 시 행 변화 없음(복합 PK).

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueComponentsIntegrationTest'`

---

### Task 7. 프론트 API + useChangeComponents 훅 + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/useChangeComponents.ts`, `apps/web/src/test/api/useChangeComponents.test.tsx`, `apps/web/src/mocks/handlers/issue-handlers.ts`]
- depends-on: []   # MSW 위에서 독립, 백엔드 컴파일 무관

**RED**.
- 파일: `useChangeComponents.test.tsx` (vitest + MSW)
- 테스트: mutation 성공 시 invalidateQueries(['issue', key]) / 422 onError componentNotFound / 409 versionConflict. (useChangeAssignee.test 패턴 복제.)
- 실패(예상): 훅 미존재.

**GREEN**.
- `issues.ts`: `changeComponents(key, {componentIds, expectedVersion})` → `PATCH /api/v1/issues/${key}/components`, X-XSRF-TOKEN(readXsrfToken), DataResponse 언래핑, 공유 ApiError(body.errorCode 헬퍼) — issue-tracking BC 관례(메모리 frontend-api-convention-per-bc).
- `useChangeComponents.ts`: useChangeAssignee 복제, invalidate-only, onError 분기.
- MSW: PATCH /issues/:key/components 핸들러 — **stateful 오버라이드 영속**(메모리 msw-mutation-stateful-refetch — 안 하면 refetch 후 롤백). 백엔드 분기순서 일치.

**REFACTOR**. 타입 공유, 헤더 헬퍼 재사용.

**검증**. `pnpm --filter @bts/web test -- useChangeComponents` + `pnpm --filter @bts/web typecheck`(tsconfig.app, 메모리 ci-typecheck-tsconfig-app-vs-local).

---

### Task 8. 프론트 UI — IssueMetaPanel 다중 컴포넌트 셀렉터

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/ComponentMultiSelect.tsx`, `apps/web/src/components/issue/ComponentMultiSelect.test.tsx`, `apps/web/src/api/components.ts`]
- depends-on: [7]   # useChangeComponents 사용

**RED**.
- 파일: `ComponentMultiSelect.test.tsx` (vitest + RTL)
- 테스트: 현재 할당 컴포넌트 칩 렌더 / 체크·해제 → onChange(componentIds) / 권한 없으면 비활성(fail-closed) / 검색 필터.
- 실패(예상): 컴포넌트 미존재.

**GREEN**.
- `ComponentMultiSelect.tsx`: 프로젝트 컴포넌트 목록(fetchComponents) 로드 + 다중선택(체크박스/칩). 순수 props(선택값+onChange+disabled). Zod fixture UUID v4 형식 주의(메모리 zod-v4-uuid-fixture-strictness).
- `IssueMetaPanel.tsx`: 담당자 셀렉터 인근에 컴포넌트 섹션 추가. canEdit(권한)로 disabled 게이팅(FR-PM-03 ComponentList 게이팅 선례). 현재 componentIds → 이름 해소(컴포넌트 목록 매핑).
- 텍스트 중복 버튼은 컨테이너 한정 셀렉터(메모리 playwright-getbyrole-exact / ui-pr-defer-e2e — 기존 이슈 상세 E2E 함께 실행).

**REFACTOR**. 칩 컴포넌트 분리, i18n 문자열.

**검증**. `pnpm --filter @bts/web test -- ComponentMultiSelect` + `pnpm --filter @bts/web typecheck`.

---

### Task 9. E2E — Playwright 컴포넌트 할당

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-components.spec.ts`, `apps/web/src/mocks/handlers/issue-handlers.ts`]
- depends-on: [8]

**RED→GREEN**(E2E happy path).
- S1 할당: 이슈 상세 → 컴포넌트 셀렉터 → 2개 선택 → 적용 → 칩 2개 표시.
- S2 부분교체: 1개 해제 → 칩 1개.
- S3 전부해제: 모두 해제 → 칩 0.
- MSW 핸들러 stateful(메모리 msw-mutation-stateful-refetch). fixture↔whoami userId 정합(메모리 e2e-fixture-whoami-userid-alignment). serviceWorker block 금지(메모리 e2e-msw-serviceworker-block).
- 기존 issue 상세 E2E 회귀 0 확인(메모리 ui-pr-defer-e2e-regression-latent).

**검증**. `pnpm --filter @bts/web test:e2e -- issue-components` + 전체 E2E 회귀 확인.

## Plan 메타

- task 수: 9
- wave 예상: W1[T1,T2,T7] → W2[T3,T8] → W3[T4] → W4[T5] → W5[T6,T9]. (T9는 T8 완료 후, T6은 T5 후.)
- 예상 시간: 직렬 약 27분, wave 병렬 약 12~15분.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR, test 커밋이 feat 커밋보다 먼저).
- 추가 검증: ktlintMainSourceSetCheck + detekt(issue-tracking baseline) + typecheck(tsconfig.app) + vitest + playwright.
- 핵심 함정(메모리): jooq-init-codegen-mirror(T2), patch-merge-도메인-우회(T4), msw-mutation-stateful-refetch(T7/T9), issue-scope-global-prod-hard-deny(T6), subagent-ktlint-false-green(머지 전 controller 직접 검증).

## 리뷰 결과 (← /bts-review-plan 채움)
