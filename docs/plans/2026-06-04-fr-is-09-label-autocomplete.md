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

**GREEN**: `findLabelsByPrefix(prefix: String, limit: Int): List<String>`. **raw SQL 디폴트**(C3, 선례 `BulkOperationWorker.kt:88` `dsl.fetch(sql, ?, ?)`) — `SELECT label, COUNT(DISTINCT id) AS freq FROM (SELECT id, UNNEST(labels) label FROM issues WHERE deleted_at IS NULL) t [WHERE label ILIKE ? ESCAPE '\\'] GROUP BY label ORDER BY freq DESC, label ASC LIMIT ?`. **prefix는 반드시 바인드 파라미터**(문자열 concat 금지 — injection). 앱단에서 `%`/`_`/`\` 이스케이프 후 `escaped || '%'`를 바인드. prefix 빈 문자열이면 ILIKE 절 생략 분기. cartesian 위험 없음(JOIN 아닌 unnest fan-out).

**REFACTOR**: SQL 상수/이스케이프 헬퍼 추출 + KDoc(왜 GIN 미활용·집계 방식인지).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueLabelAutocompleteRepositoryTest"`

### Task 2. LabelApplicationService — 권한 가드 + q 정규화

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/LabelApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/LabelApplicationServiceTest.kt`]
- depends-on: [1]

**RED**: MockK 단위테스트. `completeLabels(actor, "  bac ")` → trim 후 repo.findLabelsByPrefix("bac",10) 위임 검증. `completeLabels(actor, null/""/"   ")` → repo.findLabelsByPrefix("",10)(전체). 권한 없으면(resolver.hasPermission=false) `IssueAccessDeniedException`. resolver.hasPermission(actor, VIEW, IssueScope.Global) 호출 검증.

**GREEN**: `@Service` `LabelApplicationService(repo: IssueRepository, permissionResolver: IssuePermissionResolver)`. `@Transactional(readOnly=true)`. **권한 가드 자체 작성**(C4 — IssueApplicationService.assertPermission은 private라 재사용 불가): `if(!permissionResolver.hasPermission(actor.value, VIEW, Global)) throw IssueAccessDeniedException`. q trim/null→빈문자열 정규화 후 가드 → repo 위임. LIMIT 상수=10.

**REFACTOR**: LIMIT 상수 추출 + KDoc(글로벌 스코프 사유 ADR 링크).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*LabelApplicationServiceTest"`

### Task 3. LabelController — GET /api/v1/labels

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/LabelController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/LabelControllerTest.kt`]
- depends-on: [2]

**RED**: `@WebMvcTest` 슬라이스. `GET /api/v1/labels?q=bac` → 200 + `{"data":["backend"]}`(service mock). `q` 생략 → service.completeLabels(actor,"") 호출. 매칭 0건 → `{"data":[]}` 200.

**GREEN**: `@RestController` `@RequestMapping("/api/v1/labels")`. `@GetMapping fun complete(@RequestParam q: String?): ResponseEntity<DataResponse<List<String>>>`. SYSTEM_ACTOR_UUID는 IssueController private라 import 불가 → **동일 리터럴 `00000000-0000-0000-0000-000000000001` 재선언**(C5, fixture 정합). service 위임. DataResponse 래핑.

**REFACTOR**: 로깅 패턴(`log.info("label_autocomplete q={}")`) 기존 컨벤션 정렬.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*LabelControllerTest"` + 모듈 전체 `:modules:issue-tracking:test` + `detektMain` + `ktlintMainSourceSetCheck`/`ktlintTestSourceSetCheck`

### Task 4. 프론트 api/labels.ts + use-labels 훅 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/labels.ts`, `apps/web/src/hooks/use-labels.ts`, `apps/web/src/test/labels.test.ts`, `apps/web/src/mocks/label-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: vitest. `fetchLabels("bac")` → `GET /api/v1/labels?q=bac`(MSW) → `["backend"]` 파싱. q 빈값 → 전체. use-labels(q) react-query 캐싱 + 빈 q 처리.

**GREEN**: issue-tracking BC 컨벤션(C2 — `issues.ts`의 인라인 `dataResponseSchema(z.array(z.string())).parse` 패턴 따름, 새 헬퍼 invent 금지) `fetchLabels(q: string): Promise<string[]>`. `use-labels(q)` queryKey=['labels',q](debounce는 호출처). `mocks/label-handlers.ts`(고정 라벨셋 prefix 필터 + 빈도순 stateless) + **`mocks/handlers.ts`에 `labelHandlers` spread 등록**(C1 — 누락 시 E2E MSW 실패).

**REFACTOR**: Zod 스키마 export + queryKey 팩토리.

**검증**: `pnpm --filter @bts/web test labels` + `pnpm --filter @bts/web typecheck`

### Task 5. 라벨 자동완성 입력 컴포넌트 (cmdk) — 기존 input 대체용

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/labels/LabelAutocompleteInput.tsx`, `apps/web/src/components/labels/LabelAutocompleteInput.test.tsx`, `apps/web/src/components/ui/command.tsx`, `apps/web/package.json`]
- depends-on: [4]

> **B1**. 멀티선택 칩/검증/저장은 기존 `IssueLabelsEdit`에 이미 있음. 본 task는 그 컴포넌트의 plain `<input>`(`IssueMetaPanel.tsx:504`)을 대체할 **단일 라벨 입력+자동완성 드롭다운**만 만든다. 칩/검증/저장 로직 재구현 금지.

**RED**: vitest + Testing Library. `LabelAutocompleteInput({value, onChange, onCommit, disabled})` — 입력 "b" → use-labels mock 후보 드롭다운(빈도순) 렌더. 후보 클릭 → onCommit(label). 신규 라벨 입력+Enter → onCommit(newValue)(free-form, 자동완성에 없어도). 빈 입력 포커스 → 인기 라벨. disabled 시 비활성.

**GREEN**: cmdk 기반(**cmdk 도입 확정** — 게이트1 Maxi 승인 2026-06-04. `package.json`에 cmdk 추가 + `components/ui/command.tsx`(shadcn Command) 추가). 입력 `use-debounce`(기존 `hooks/use-debounce.ts` 재사용, C1) 250ms → use-labels. IssueLabelsEdit의 addLabel/검증 계약(trim/50자/20개/중복)에 맞는 onCommit 시그니처.

**REFACTOR**: debounce 상수 + a11y(role/aria-label) + strict mode 대비 컨테이너 한정 셀렉터(learnings playwright-getbyrole).

**검증**: `pnpm --filter @bts/web test LabelAutocompleteInput` + `pnpm --filter @bts/web typecheck`

### Task 6. IssueLabelsEdit에 자동완성 in-place 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/components/issue/IssueMetaPanel.test.tsx`]
- depends-on: [5]

> **B1**. 신규 컴포넌트/mutation 생성 금지. 기존 `IssueLabelsEdit`(`:455`)의 `<input>`(:504)을 `LabelAutocompleteInput`으로 교체하고 addLabel(:469)에 onCommit 연결. 저장 경로(`labelsMutation`/`updateIssue` PATCH)는 그대로 재사용.

**RED**: vitest. IssueMetaPanel의 라벨 편집 영역에서 입력 → 자동완성 후보 표시 → 후보 선택 → 칩 추가 → 저장(`data-testid="labels-save"`) → 기존 onLabelsSave(PATCH) 호출. 기존 IssueMetaPanel 테스트 회귀 0.

**GREEN**: `IssueLabelsEdit` 내부 `<input>`→`LabelAutocompleteInput` 교체. addLabel을 onCommit으로 배선. 칩/검증/저장/props 동기화(:459) 로직 유지. 새 UI 문자열은 `issueDetailStrings`에 추가(N2).

**REFACTOR**: 자동완성 드롭다운 위치/스타일 메타패널 정합 + 권한 게이트(canEdit 기존 prop 재사용).

**검증**: `pnpm --filter @bts/web test IssueMetaPanel` + `typecheck` + `lint` + 기존 라우트/E2E 셀렉터 회귀 0(learnings ui-pr-defer-e2e, labels-save testid 유지)

### Task 7. E2E 라벨 자동완성 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/label-autocomplete.spec.ts`]
- depends-on: [3, 6]

**RED/GREEN**: Playwright. S1 이슈 편집 → 라벨 입력 "b" → 자동완성 드롭다운(빈도순) → 후보 선택 → 칩 추가 → `labels-save` 클릭 → 메타 반영. S4 신규 라벨 입력 → 추가. S3 빈 포커스 → 인기 라벨. MSW label-handlers(T4 정본 재사용) + issue PATCH stateful(선택 후 재조회 일관). strict mode 대비 컨테이너 한정 셀렉터 + 기존 `data-testid`(labels-save) 활용.

**검증**: `pnpm --filter @bts/web test:e2e label-autocomplete` + 전체 E2E 회귀 0(orphan vite 5173 주의, learnings)

## Plan 메타

- task 수: 7 (각 TDD 사이클)
- 예상 wave: 4 — W1[T1,T4] · W2[T2,T5] · W3[T3,T6] · W4[T7]. 백엔드 T1~T3는 issue-tracking 모듈 test 컴파일 단위 공유로 실제 병렬은 직렬화되나 의존성으로 어차피 순차. 프론트 T4~T6는 apps/web 별도라 백엔드와 진짜 병렬.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 데이터 모델 변경: 0 (마이그레이션/init_codegen 미러 불요)
- 신규 의존성: **cmdk 도입 확정**(게이트1 Maxi 승인 2026-06-04). shadcn Command 컴포넌트 신규 추가
- 미검증 플래그: prod Global VIEW 권한 결선은 전역역할 부재(FR-PM-04 보류)로 prod 동작 미검증 — non-prod/테스트는 AlwaysAllow로 통과(C4)
- 추가 검증: detekt, ktlint(Main+Test SourceSetCheck 직접 실행), typecheck(tsconfig.app), vitest, playwright

## 리뷰 결과

### code-reviewer ground-truth 독립 리뷰 (2026-06-04)

**BLOCKER (plan 보정 완료).**
- **B1**. 라벨 편집 UI가 이미 end-to-end 존재 — `IssueMetaPanel.tsx:455 IssueLabelsEdit`(칩 추가/삭제 + 클라이언트 검증 trim/50자/20개/중복 + 저장버튼 `data-testid="labels-save"`), 라우트 `issues.$key.tsx:266 labelsMutation`(updateIssue PATCH labels), `api/issues.ts:263 updateIssue`. plan이 `LabelCombobox`+`IssueLabelsField` greenfield로 오인 → **기존 `IssueLabelsEdit`의 plain `<input>`(:504)을 자동완성 입력으로 in-place 교체**로 재정의. 중복 컴포넌트 생성 금지. (`useUpdateIssue` 훅은 미존재 — 실제는 라우트 `labelsMutation`.)
- **B2**. cmdk / shadcn Command 부재(`components/ui/`에 command·popover 없음, package.json에 cmdk 없음). "기존 재사용" 전제 거짓 → 신규 의존성. 게이트1에서 Maxi에게 cmdk 도입 vs 의존성0 simple listbox 선택 받음.

**CONCERN (반영 완료).**
- **C1**. 파일 컨벤션 — `hooks/use-labels.ts`(kebab), `mocks/label-handlers.ts`(flat), `mocks/handlers.ts`에 spread 등록(누락 시 E2E MSW 실패), 기존 `hooks/use-debounce.ts` 재사용.
- **C2**. 언랩은 `issues.ts` 인라인 `dataResponseSchema(...).parse` 패턴 따름(같은 BC). 계약 `{data:array(string)}`↔`DataResponse<List<String>>` 정합 확인됨.
- **C3**. Task 1은 `dsl.fetch(rawSql, boundPrefix, limit)` raw SQL + 앱단 와일드카드 이스케이프 디폴트(선례 `BulkOperationWorker.kt:88`). prefix 바인드 파라미터 필수(concat 금지). cartesian 위험 없음(JOIN 아닌 unnest). `COUNT(DISTINCT id)` 방어적.
- **C4**. `IssueApplicationService.assertPermission`은 private → `LabelApplicationService`가 권한 가드 자체 작성(resolver.hasPermission + IssueAccessDeniedException). prod Global VIEW 결선은 identity-access(FR-PM) 책임이며 **전역역할 부재로 prod 미검증** — 게이트 노트.
- **C5**. `SYSTEM_ACTOR_UUID`는 IssueController private → LabelController가 동일 리터럴 `00000000-0000-0000-0000-000000000001` 재선언(fixture 정합).

**NIT.** OpenAPI 산출물 없음(의식적 생략), i18n는 `issueDetailStrings`에 추가, q 길이 상한 50 권장, 인증(Bearer JWT)은 코드 전역 미결선(별도 FR, 본 작업 책임 아님).

**판정.** 백엔드 3 task 정합 → 진행 가능. 프론트 B1/B2 보정 후 승인 권고 → **반영 완료, 게이트1 상정**.
