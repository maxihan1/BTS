# FR-IS-09 — 라벨 자동완성

> slug: fr-is-09-label-autocomplete
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-04

## Brief

FR-IS-09 라벨 자동완성 (issue-tracking BC). spec 위치: `docs/plan/product/issue-tracking.md §2.2.2`.
이슈에 라벨을 부여할 때 입력값에 맞춰 후보 라벨을 자동 제안한다.

- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking, slug=fr-is-09

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: Issue 애그리거트 (기존). 신규 엔티티 없음.
- **데이터 모델**: 기존 `issues.labels TEXT[]` 배열 활용 (GIN 인덱스 `ix_issues_labels_gin` 존재).
  정규화 테이블(`labels`, `issue_labels`) **미도입** — 옵션 A 확정.
- **새 용어**: 없음 (라벨은 이미 존재하는 도메인 개념)
- **기존 결정 충돌**: plan §2.2.2 D3 "labels, issue_labels 신규" 표기가 SDD 정본
  (`05-data-model.md:18` labels TEXT[])과 drift. 본 작업에서 plan D3 표기를 정정.
- **선행 상태 (ground-truth)**:
  - 라벨 도메인 검증(`Issue.normalizeLabels()` — 최대 50자/20개, 공백 거부, 중복 제거): 구현됨
  - PATCH 라벨 교체(`UpdateIssueRequest.labels`), 클론 시 복사(`IssueApplicationService:214`): 구현됨
  - **미구현 = 본 작업 범위**: 자동완성 조회 엔드포인트 `GET /api/v1/labels?q=<prefix>` + 프론트 콤보박스 UI
- **Jira 정합**: Jira Label = 마스터 테이블 없는 글로벌 free-form 텍스트 태그. 현 구현이 이미 Jira 모델.
- **관련 ADR**: [docs/adr/2026-06-04-issue-label-freeform-tag-model.md](../adr/2026-06-04-issue-label-freeform-tag-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-04-fr-is-09-label-autocomplete.md](../specs/2026-06-04-fr-is-09-label-autocomplete.md)

핵심 요약.
- `GET /api/v1/labels?q=<prefix>` — 활성 이슈 라벨에서 prefix(대소문자 무시) 매칭, 사용 빈도순(동률 알파벳 tiebreak) 최대 10개, `DataResponse<List<String>>`.
- `q` 빈값 → 전체 인기 라벨 top-10. 매칭 0건 → 빈 배열 200. 스코프=글로벌, 권한=Global VIEW.
- ILIKE 와일드카드(`%`/`_`/`\`) 이스케이프 필수. 삭제 이슈 제외. 데이터 모델 변경 0.
- 프론트 cmdk 콤보박스(신규 라벨 입력 허용) + 기존 PATCH로 라벨 저장 + E2E.

## Brainstorming Check

✅ 통과 (자체 적대적 sanity check, gap 5건 발견·반영: ILIKE 이스케이프 / q 공백 처리 / 케이스 보존 / 삭제이슈 제외 / 빈도동률 tiebreak)

## Plan

라벨 자동완성을 백엔드 3 task(repository → service → controller) + 프론트 3 task(api → 콤포넌트 → 통합) + E2E 1 task로 분해. 데이터 모델 변경 0(신규 마이그레이션 없음). 백엔드/프론트는 계약(`DataResponse<List<String>>`)만 일치하면 독립 병렬 가능.

### Task 1. IssueRepository 라벨 집계 쿼리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueLabelAutocompleteRepositoryTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합테스트. 활성 이슈 여러 개(라벨 분포: "bug"×3, "backend"×1, "billing"×1) + 삭제 이슈 1개("deleted-only" 라벨) seed 후 `findLabelsByPrefix("b", 10)` → `["bug","backend","billing"]`(빈도순). 삭제 이슈 라벨 제외 단언. `findLabelsByPrefix("", 10)` → 전체 top-N. `findLabelsByPrefix("b%", 10)` → ILIKE 이스케이프로 0건(리터럴 "b%" 라벨 없음). 메서드 미존재로 컴파일 실패.

**GREEN**: `findLabelsByPrefix(prefix: String, limit: Int): List<String>`. jOOQ raw SQL 또는 DSL로 `SELECT label, COUNT(*) FROM (SELECT UNNEST(labels) label FROM issues WHERE deleted_at IS NULL) GROUP BY label [WHERE label ILIKE :p || '%' ESCAPE '\\'] ORDER BY COUNT(*) DESC, label ASC LIMIT :limit`. prefix 빈 문자열이면 ILIKE 절 생략. 와일드카드(`%`/`_`/`\`) 이스케이프 헬퍼.

**REFACTOR**: SQL 상수/이스케이프 헬퍼 추출 + KDoc(왜 GIN 미활용·집계 방식인지).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueLabelAutocompleteRepositoryTest"`

### Task 2. LabelApplicationService — 권한 가드 + q 정규화

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/LabelApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/LabelApplicationServiceTest.kt`]
- depends-on: [1]

**RED**: MockK 단위테스트. `completeLabels(actor, "  bac ")` → trim 후 repo.findLabelsByPrefix("bac",10) 위임 검증. `completeLabels(actor, null/""/"   ")` → repo.findLabelsByPrefix("",10)(전체). 권한 없으면(resolver false) `IssueAccessDeniedException`. `assertPermission(VIEW, IssueScope.Global)` 호출 검증.

**GREEN**: `@Service` `LabelApplicationService(repo: IssueRepository, permissionResolver: IssuePermissionResolver)`. `@Transactional(readOnly=true)`. q trim/null→빈문자열 정규화 후 권한 가드 → repo 위임. LIMIT 상수=10.

**REFACTOR**: LIMIT 상수 추출 + KDoc(글로벌 스코프 사유 ADR 링크).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*LabelApplicationServiceTest"`

### Task 3. LabelController — GET /api/v1/labels

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/LabelController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/LabelControllerTest.kt`]
- depends-on: [2]

**RED**: `@WebMvcTest` 슬라이스. `GET /api/v1/labels?q=bac` → 200 + `{"data":["backend"]}`(service mock). `q` 생략 → service.completeLabels(actor,"") 호출. 매칭 0건 → `{"data":[]}` 200.

**GREEN**: `@RestController` `@RequestMapping("/api/v1/labels")`. `@GetMapping fun complete(@RequestParam q: String?): ResponseEntity<DataResponse<List<String>>>`. ActorId(SYSTEM_ACTOR_UUID) 기존 임시 패턴 재사용. service 위임. DataResponse 래핑.

**REFACTOR**: 로깅 패턴(`log.info("label_autocomplete q={}")`) 기존 컨벤션 정렬.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*LabelControllerTest"` + 모듈 전체 `:modules:issue-tracking:test` + `detektMain` + `ktlintMainSourceSetCheck`/`ktlintTestSourceSetCheck`

### Task 4. 프론트 api/labels.ts + useLabels 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/labels.ts`, `apps/web/src/hooks/useLabels.ts`, `apps/web/src/test/labels.test.ts`, `apps/web/src/mocks/handlers/label-handlers.ts`]
- depends-on: []

**RED**: vitest. `fetchLabels("bac")` → `GET /api/v1/labels?q=bac`(MSW) → `["backend"]` 파싱(DataResponse 언랩). q 빈값 → 전체. Zod 스키마 `z.object({data: z.array(z.string())})`. useLabels(q) react-query 캐싱 + 빈 q 처리.

**GREEN**: issue-tracking BC api 컨벤션(apiFetch + DataResponse 언랩 헬퍼, learnings frontend-api-convention-per-bc) 따라 `fetchLabels(q: string): Promise<string[]>`. `useLabels(q)` — debounce는 호출처(콤보박스)에서, 훅은 queryKey=['labels',q]. label-handlers.ts(MSW, 고정 라벨셋 prefix 필터 stateless).

**REFACTOR**: Zod 스키마 export + queryKey 팩토리.

**검증**: `pnpm --filter @bts/web test labels` + `pnpm --filter @bts/web typecheck`

### Task 5. LabelCombobox 컴포넌트 (cmdk)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/labels/LabelCombobox.tsx`, `apps/web/src/components/labels/LabelCombobox.test.tsx`, `apps/web/package.json`]
- depends-on: [4]

**RED**: vitest + Testing Library. 입력 "b" → useLabels mock 후보 드롭다운 렌더. 후보 선택 시 onSelect(label) 호출. 자동완성에 없는 신규 라벨 입력+Enter → onSelect(newLabel)(free-form). 이미 선택된 라벨 칩 제거. 빈 입력 포커스 → 인기 라벨 표시.

**GREEN**: cmdk 기반(기존 shadcn `Command` 컴포넌트 있으면 재사용, 없으면 cmdk 추가 — **새 의존성, 게이트에서 확인**) `LabelCombobox({value: string[], onChange})`. 입력 debounce(250ms)→useLabels. 멀티 선택 칩 + 신규 입력 허용. 도메인 제약(최대 20개/50자)은 입력단 가드(서버 PATCH가 최종 방어).

**REFACTOR**: debounce 상수/칩 서브컴포넌트 분리 + a11y(role/aria-label, learnings playwright strict mode 대비 컨테이너 한정 셀렉터 고려).

**검증**: `pnpm --filter @bts/web test LabelCombobox` + `pnpm --filter @bts/web typecheck`

### Task 6. 이슈 메타패널 라벨 편집 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueLabelsField.tsx`, `apps/web/src/components/issue/IssueLabelsField.test.tsx`, `apps/web/src/routes/issues.$key.tsx`]
- depends-on: [5]

**RED**: vitest. 이슈 상세 메타패널에 LabelCombobox 마운트 → 라벨 변경 시 기존 `useUpdateIssue`(PATCH labels) 호출. 저장 성공 시 invalidate/refetch로 화면 반영(learnings msw-mutation-stateful-refetch — MSW PATCH 핸들러 stateful). 낙관/롤백 동작.

**GREEN**: `IssueLabelsField`(현재 라벨 표시 + LabelCombobox 편집) — 기존 이슈 PATCH 경로 재사용(신규 mutation 만들지 않음). issues.$key 메타패널에 배치. MSW label-handlers는 T4 정본 재사용, issue PATCH 핸들러 stateful 확인.

**REFACTOR**: 메타패널 필드 일관 스타일 + 권한 게이트(편집 권한 없으면 읽기 전용, FR-PM 후속이면 서버 403 토스트).

**검증**: `pnpm --filter @bts/web test IssueLabelsField` + `typecheck` + `lint` + 기존 라우트 단위테스트 회귀 0(learnings ui-pr-defer-e2e — 기존 E2E 셀렉터 영향 점검)

### Task 7. E2E 라벨 자동완성 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/label-autocomplete.spec.ts`, `apps/web/src/mocks/handlers/label-handlers.ts`]
- depends-on: [3, 6]

**RED/GREEN**: Playwright. S1 이슈 편집 → 라벨 입력 "b" → 자동완성 드롭다운(빈도순) → 후보 선택 → 저장 → 메타 반영. S4 신규 라벨 입력 → 추가. S3 빈 포커스 → 인기 라벨. MSW label-handlers stateful(선택 후 재조회 일관). strict mode 대비 dialog/컨테이너 한정 셀렉터 + data-testid.

**검증**: `pnpm --filter @bts/web test:e2e label-autocomplete` + 전체 E2E 회귀 0(orphan vite 5173 주의, learnings)

## Plan 메타

- task 수: 7 (각 TDD 사이클)
- 예상 wave: 4 — W1[T1,T4] · W2[T2,T5] · W3[T3,T6] · W4[T7]. 백엔드 T1~T3는 issue-tracking 모듈 test 컴파일 단위 공유로 실제 병렬은 직렬화되나 의존성으로 어차피 순차. 프론트 T4~T6는 apps/web 별도라 백엔드와 진짜 병렬.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 데이터 모델 변경: 0 (마이그레이션/init_codegen 미러 불요)
- 신규 의존성: cmdk (또는 기존 shadcn Command 재사용) — 게이트1에서 확인
- 추가 검증: detekt, ktlint(Main+Test SourceSetCheck 직접 실행), typecheck(tsconfig.app), vitest, playwright

## 리뷰 결과 (← /bts-review-plan 채움)
