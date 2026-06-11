# FR-HS-02 — 이슈 변경 이력 조회 UI

> slug: fr-hs-02-history-view
> type: ui
> agent: frontend-engineer
> primary BC: issue-tracking
> 생성: 2026-06-11

## Brief

FR-HS-02 이슈 변경 이력 조회 UI 구현. FR-HS-01(백엔드 이슈 변경 이력 기록 #115 + cross-BC 라벨 박제 보강 #120)의 후속 PR2.
FR-HS-01에서 백엔드가 기록한 이슈 변경 이력을 사용자가 이슈 상세 화면에서 조회할 수 있는 프론트엔드 UI를 구현한다.

- classify: type=ui, agent=frontend-engineer, primary_bc=issue-tracking
- FR-NT-01(worktree/PR #118), FR-MF-02(worktree) 등 병행 작업은 유지하고 본 작업은 독립 worktree에서 진행.

## 도메인 정리

- **BC**: issue-tracking (단일 BC, cross-BC 직접 호출 없음)
- **영향 엔티티**: `IssueChangeGroup`, `IssueChangeItem` (FR-HS-01 도입, **읽기만** — 변경 없음)
  - `IssueChangeGroup { issueId, issueKey, actorId?, items[], createdAt? }`
  - `IssueChangeItem { field, fromValue?, toValue?, fromLabel?, toLabel? }` (라벨은 #120에서 assignee/securityLevel만 박제, status·기타는 null)
- **새 용어**: 없음 — ADR `2026-06-11-issue-change-history-model`에서 change group / change item / 박제 label 모두 정의됨
- **기존 결정 충돌**: 없음. ADR §단계 분할이 "PR2(FR-HS-02 조회 UI) — 박제된 label을 타임라인에 표시"를 명시적으로 예견
- **관련 ADR**: [docs/adr/2026-06-11-issue-change-history-model.md](../adr/2026-06-11-issue-change-history-model.md) (특히 §단계 분할 PR2, §보강 cross-BC 라벨 박제)
- grill-with-docs: 도메인이 FR-HS-01 ADR로 완전 확립 + 신규 용어·충돌 0 → 대화형 grilling 생략, 직접 기록 (learning `bts-review-plan-autoplan-overkill` 패턴)

### ⚠️ 범위 발견 — backend read 엔드포인트 부재 (중요)

FR-HS-01은 `IssueChangeHistoryRepository.findByIssue(issueId): List<IssueChangeGroup>` **조회 메서드까지만** 구현했고, 이력을 외부로 노출하는 **REST 조회 엔드포인트는 없다**(web 컨트롤러 목록·GetMapping grep 모두 빈 결과).

→ FR-HS-02 실제 범위 = **backend read 엔드포인트 + DTO (backend-engineer)** + **프론트 타임라인 UI (frontend-engineer)** + **E2E (qa)** 혼합. classify의 `type=ui`는 부분만 맞음.

- **BC 격리**: 이력 조회 엔드포인트는 **같은 issue-tracking BC**의 view-layer 확장 → 프론트 PR에 same-BC backend 추가는 BC 위반 아님 (learning `2026-05-22 same-BC view layer 예외` 패턴). cross-BC 직접 import 없음.
- **권한**: 단건 조회 `service.findByKey(actor, key)`가 view 권한(보안등급 포함) 강제. 새 changelog 엔드포인트도 **동일 가드 재사용** — 이슈를 못 보면 이력도 404 (데이터 누출 방지). learning `auth-extraction-before-resource-lookup` 정합.
- **엔드포인트 위치**: `IssueController` (`@RequestMapping("/api/v1/issues")`, `adapter/inbound/rest/`) — `/{key}/transitions`, `/{key}/pdf` 패턴 옆에 `/{key}/changelog` 추가 후보.
- **프론트 위치**: `apps/web/src/routes/issues.$key.tsx` (`IssueDetailPage`) — 상세 페이지에 활동/이력 섹션 형태로 부착 후보.

### spec에서 확정할 결정 (→ /bts-spec)

1. 엔드포인트 경로/이름 — `/{key}/changelog` vs `/history` vs `/activity`
2. actor 표시명 해석 — `group.actorId`(nullable)를 display_name으로 backend resolve (UserLookupPort, #120 선례) vs ID만 노출
3. `field` 키 → 사람이 읽는 필드명 매핑 위치 — backend DTO vs frontend 라벨 맵
4. UI 형태 — 상세 페이지 탭 vs 인라인 섹션, 그룹 묶음 표시 방식
5. 정렬/페이징 — 최신순 + 페이징 필요 여부
6. 생명주기 이벤트(created/soft_deleted) 표시 방식

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-hs-02-history-view.md](../specs/2026-06-11-fr-hs-02-history-view.md)

핵심 요약.
- backend `GET /api/v1/issues/{key}/changelog` (페이징·최신순) + 단건 조회와 동일 view 권한 가드(실패 404). actor 표시명은 백엔드가 `UserLookupPort`로 해석(actorName, graceful degrade).
- DTO = Spring Page<ChangeGroupResponse>{ actorId, actorName, createdAt, items[{field, fromValue, toValue, fromLabel, toLabel}] }. 기존 `GET /api/v1/issues` list 응답의 Page 직렬화 형식에 1:1로 맞춤.
- 프론트 = 이슈 상세 하단 전체폭 "변경 이력" 섹션(접기/펼치기), 그룹별 타임라인, "더 보기" 누적 페이징.
- 값 표시명 = #120 박제 label(assignee/securityLevel) 우선, 나머지(priority·impact·type·components·versions·resolution)는 **프론트가 페이지 로드 참조 데이터로 read-time 해석**(삭제 엔티티 폴백). lifecycle(created/deleted) 특수 렌더.
- Maxi 결정 3건(2026-06-11): actor=백엔드 해석 / UI=하단 전체폭 / 로딩=페이징. gap 결정 1건: 라벨 없는 필드=프론트 해석.

## Brainstorming Check

✅ 통과 (1 iteration). gap — 라벨 미박제 필드(priority Int·type ID·UUID 등) raw 노출 → Maxi 결정으로 프론트 read-time 해석 채택해 해소.

## Plan

> 스펙 §API/FR 기준. backend(B1~B3, backend-engineer) + frontend(F1~F5, frontend-engineer) + E2E(Q1, qa-engineer).
> 모든 경로는 repo 루트 기준. 기존 issue-tracking 선례 재사용(learning `frontend-api-convention-per-bc`).

### Task B1. 이력 페이징 조회 + count repository 메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**: 통합 테스트 — `findByIssuePaged(issueId, limit, offset)`가 created_at DESC,id DESC 순 N건만 반환 + `countByIssue(issueId)`가 총 그룹 수 반환. (시드 데이터로 페이지 경계 검증)
**GREEN**: 인터페이스에 `findByIssuePaged(issueId: UUID, limit: Int, offset: Int): List<IssueChangeGroup>` + `countByIssue(issueId: UUID): Long` 추가(default 구현 X — 추상). Jdbc에 기존 `findByIssue`의 `ORDER BY created_at DESC, id DESC` 쿼리에 `LIMIT ? OFFSET ?` + count 쿼리 추가. 기존 `findByIssue`는 유지(타 호출 영향 없음).
- **⚠️ items 로딩(eng-review C2)**: group 페이지 조회 후 items는 **기존 `fetchItemsByGroupIds(groupIds)` 배치 패턴 재사용**. group↔item을 단일 LEFT JOIN으로 합치지 말 것 — cartesian product 위험(learning `cartesian-product-jooq-leftjoin-count`). 즉 "페이지 group N건 조회 → 그 group ids로 items 배치 조회" 2-step 유지.
**REFACTOR**: SQL 상수 추출 + KDoc. 인터페이스 추가 메서드로 인한 인라인 fake 구현 영향 확인(learning `interface-extension-default-method` — 단, 여기선 prod repo 1개라 추상 추가 안전, 테스트 fake 있으면 grep).
**검증**: `./gradlew :backend:issue-tracking:test --tests "*JdbcIssueChangeHistoryRepositoryIntegrationTest*"`

### Task B2. changelog 조회 서비스 (view 가드 재사용 + actor 표시명 해석)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueChangelogService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueChangelogServiceTest.kt`]
- depends-on: [B1]
- **설계 변경(impl 단계 발견)**: `IssueApplicationService` 생성자 수정 금지 — 읽기 repo 추가 시 기존 테스트 30개 깨짐(learning `plan-files-constructor-injection-existing-tests`). 대신 **신규 `IssueChangelogService`(@Service)** 생성. VIEW 가드는 `IssueApplicationService.findByKey`(public, `IssueResponse.id:UUID` 노출) 호출로 재사용(코드베이스 분리-빈 패턴 정합 — historyRecorder도 별도 빈).

**RED**: 서비스 단위 테스트 —
  (a) VIEW 권한 없거나 미존재/소프트삭제 시 `IssueNotFoundException`(단건 조회 `findByKey` 가드 재사용),
  (b) actorId 있는 그룹에 `UserLookupPort.findDisplayNamesByIds`로 actorName 채워짐,
  (c) actorId=null(시스템) 또는 lookup 결과 없음 → actorName=null로 graceful degrade(이력은 반환),
  (d) 페이징 위임(limit/offset 변환) + 총건수.
**GREEN**: 신규 `IssueChangelogService`(@Service)에 `@Transactional(readOnly=true) fun findChangelog(actor, key: IssueKey, pageable: Pageable): Page<ChangeGroupView>` 추가. 생성자 = (issueApplicationService, changeHistoryRepository, userLookupPort). 흐름 (eng-review C3 명시) —
  1. `issueApplicationService.findByKey(actor, key)` 호출(VIEW 가드 재사용, 미인가/미존재/소프트삭제=`IssueNotFoundException`). 반환 `IssueResponse.id`(UUID, IssueResponse.kt:59) → 이 UUID로 이슈 식별.
  2. `repo.findByIssuePaged(issueId, size, page*size)` + `repo.countByIssue(issueId)`.
  3. actorId 집합을 한 번에 `userLookupPort.findDisplayNamesByIds(ids)`로 해석 — **#120 `IssueChangeLabelResolver.kt:164-166`의 try/catch + log.warn graceful degrade 패턴 재사용**(실패 시 actorName=null, 이력은 반환). prod 바인딩은 `identity-access/UserLookupAdapter.kt:91` 확인됨(eng-review B2 검증 — fail-open 아님).
  4. 뷰 모델 매핑 → `PageImpl`. (DTO 직렬화는 B3, 서비스는 뷰 모델 또는 도메인+해석맵 반환, cross-BC 직접 import 금지)
**REFACTOR**: actor 해석 헬퍼 추출 + KDoc(왜 graceful degrade인지 #120 선례 인용).
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueChangelogServiceTest*"`
**보안 노트**: VIEW 가드는 findByKey 경로 재사용(신규 인증 로직 0). codereview에서 security-engineer가 존재 probe 방지(actor 추출이 조회보다 먼저, learning `auth-extraction-before-resource-lookup`) + 404 일관 확인.

### Task B3. changelog DTO + 컨트롤러 엔드포인트 + HTTP 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueChangelogResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueChangelogControllerIntegrationTest.kt`]
- depends-on: [B2]

**RED**: HTTP 통합 테스트 — `GET /api/v1/issues/{key}/changelog?page=0&size=20` →
  (a) 200 + Spring Page 형태(`content[]` + 페이징 메타, 기존 `GET /api/v1/issues` list와 동일 직렬화),
  (b) content[].items[]에 field/fromValue/toValue/fromLabel/toLabel + actorId/actorName/createdAt,
  (c) 권한 없음/미존재/소프트삭제 → 404(단건 조회와 동일),
  (d) 페이지 경계(size보다 많으면 totalPages>1).
**GREEN**: `IssueChangelogResponse`(=ChangeGroupResponse{actorId, actorName, createdAt, items: List<ChangeItemResponse{field, fromValue, toValue, fromLabel, toLabel}>}) data class. `IssueController`에 `@GetMapping("/{key}/changelog")` 추가 — `CurrentActor.current()` → `service.findChangelog(actor, IssueKey(key), pageable)` → `Page<IssueChangelogResponse>` 반환(list 엔드포인트와 동일 패턴, `@PageableDefault(size=20)`). Instant ISO 직렬화는 기존 WebMvcConfigurer 설정 그대로(learning `fr-vr-04`).
**REFACTOR**: KDoc(권한·페이징·라벨 박제 범위 주석) + 엔드포인트 순서 `/{key}/transitions` 옆 배치.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueChangelogControllerIntegrationTest*"`

### Task F1. 프론트 changelog API fetch + Zod 스키마 + 조회 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/changelog.ts`, `apps/web/src/api/changelog.test.ts`, `apps/web/src/hooks/use-issue-changelog.ts`, `apps/web/src/hooks/__tests__/use-issue-changelog.test.tsx`]
- depends-on: []

**RED**: (a) api 테스트 — `fetchIssueChangelog(key, page, size)`가 Spring Page를 Zod로 파싱(`issues.ts`의 `pageSchema` 재사용), 404 시 ApiError. client(`apiGet`) mock(MSW 비의존). (b) 훅 테스트 — `useIssueChangelog(key)`가 useQuery로 fetch 위임, fetch 함수 mock.
**GREEN**: `changelog.ts` — `changeGroupSchema`/`changeItemSchema`(스펙 §API와 1:1, 필드 invent 금지·learning `frontend-zod-backend-dto-contract-gap`) + `fetchIssueChangelog` (raw Page 반환, `fetchIssues` 패턴, 래퍼 없음). `use-issue-changelog.ts` — `useQuery` 래퍼(`use-versions`/`use-issue-types` 선례). XSRF/apiFetch 관례는 issues.ts 선례 따름(GET이라 CSRF 무관).
**REFACTOR**: 타입 export 정리 + JSDoc.
**검증**: `pnpm --filter web test changelog use-issue-changelog && pnpm --filter web typecheck`

### Task F2. changelog MSW 핸들러 + fixtures

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/changelog-fixtures.ts`, `apps/web/src/mocks/changelog-handlers.ts`, `apps/web/src/mocks/__tests__/changelog-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: 핸들러 테스트 — `GET /api/v1/issues/:key/changelog`가 페이징 fixture(여러 그룹·필드 다양: lifecycle/priority/assignee박제/components UUID/status) 반환 + 권한 없는 key 404. stateful 오버라이드 영속(learning `msw-mutation-stateful-refetch`).
**GREEN**: `changelog-fixtures.ts`(스펙 §API 형태 그대로, 박제 label은 assignee/securityLevel만 non-null, 나머지 raw — 실제 백엔드 응답 반영) + `changelog-handlers.ts`(page/size 쿼리 페이징) + `handlers.ts`에 등록. Zod v4 UUID 형식 fixture(learning `zod-v4-uuid-fixture-strictness`).
**REFACTOR**: fixture 빌더 헬퍼.
**검증**: `pnpm --filter web test changelog-handlers`

### Task F3. 변경 이력 라벨 해석 유틸 (필드명 + 값 표시명)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/changelog-labels.ts`, `apps/web/src/lib/changelog-labels.test.ts`, `apps/web/src/i18n/ko.ts`]
- depends-on: []

**RED**: 유틸 테스트 (스펙 FR5/FR6) —
  (a) 필드 키→표시명(priority→"우선순위" 등, `customField:<key>`→정의명/폴백),
  (b) 값 해석 우선순위: 박제 label > 참조 데이터 해석 > raw 폴백 > "(없음)",
  (c) priority(Int)→i18n 라벨, type→types 맵, components/versions(UUID JSON 배열)→이름 맵, 삭제 엔티티 "(삭제됨)" 폴백,
  (d) lifecycle(created/deleted) 특수 문자열.
**GREEN**: `changelog-labels.ts` — 순수 함수 `resolveFieldLabel(field, refs)` / `resolveValueLabel(item, side, refs)` (refs = {types, components, versions, priorityMap, impactMap, customFieldDefinitions}). 표준 필드 라벨은 `i18n/ko.ts`에 추가(기존 issueDetailStrings 톤). 컴포넌트에 로직 X(테스트 용이).
- **customField(eng-review C5)**: `customField:<key>` 표시명은 refs.customFieldDefinitions(프론트 `use-custom-fields` 훅으로 로드, `api/custom-fields.ts` 정의명)에서 조회, 못 찾으면 key 원문 폴백. 정의 로드는 F4/F5에서 주입.
**REFACTOR**: refs 타입 정의 + 매핑 테이블 상수화.
**검증**: `pnpm --filter web test changelog-labels && pnpm --filter web typecheck`

### Task F4. IssueChangelog 타임라인 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueChangelog.tsx`, `apps/web/src/components/issue/IssueChangelog.test.tsx`]
- depends-on: [F1, F2, F3]

**RED**: 컴포넌트 테스트 (스펙 S1~S6) — 그룹별 "actorName · 상대시각" 헤더 + 필드별 `표시명: from → to`, 박제 label 우선, lifecycle 특수 렌더, "더 보기" 누적 페이징(다음 페이지 fetch), 마지막 페이지 버튼 숨김, 빈 상태, actorName=null→"시스템". props로 refs(types/components/versions/priorityMap/impactMap/customFieldDefinitions) 주입(순수, `IssueAssigneeSelect` 선례 — useState 초기화 금지 learning `react-usestate-stale-key-prop`).
**GREEN**: `IssueChangelog.tsx` — `useIssueChangelog` 훅 + `resolveFieldLabel`/`resolveValueLabel` 사용. shadcn/Radix 컴포넌트 재사용(신규 라이브러리 0). 접기/펼치기. 텍스트 중복 시 컨테이너 한정(learning `playwright-getbyrole-exact-strict-mode` 대비 test 작성).
**REFACTOR**: 서브 컴포넌트(ChangeGroupRow/ChangeItemRow) 분리 + 접근성(aria).
**검증**: `pnpm --filter web test IssueChangelog && pnpm --filter web typecheck`

### Task F5. 이슈 상세 페이지 하단 변경 이력 섹션 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [F4]

**RED**: 라우트 테스트 — 상세 페이지 2단 레이아웃 하단 전체폭에 `IssueChangelog` 렌더 + 페이지가 이미 로드한 refs(availableTypes/projectComponents/projectVersions/priorityMap/impactMap) + customFieldDefinitions(`use-custom-fields` 추가 로드) 주입 + 이슈 key 전달. 기존 상세 페이지 테스트 회귀 0.
**GREEN**: `issues.$key.tsx`의 2단 grid `</div>`(line~655) 아래 전체폭 `<section>` 추가 + `IssueChangelog` 배치(props로 refs/issueKey). customField 정의가 페이지에 아직 없으면 `use-custom-fields` 훅으로 추가 로드(eng-review C5). 기존 코드 최소 수정(surgical).
**REFACTOR**: 섹션 래퍼 + 제목 i18n.
**검증**: `pnpm --filter web test "issues.\$key" && pnpm --filter web typecheck && pnpm --filter web lint`

### Task Q1. E2E — 변경 이력 타임라인 happy path

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-changelog.spec.ts`, `apps/web/src/mocks/changelog-handlers.ts`(시나리오 시드 보강 시)]
- depends-on: [F5]

**RED**: Playwright — 이슈 상세 진입 → "변경 이력" 섹션 펼침 → 그룹/필드 from→to + 박제 label 확인 → "더 보기" 페이징 확인. MSW 시나리오 시드(learning `e2e-msw-scenario-toggle-localstorage-flag`/`msw-derived-behavior-shared-store-e2e`), `serviceWorkers:'block'` 금지(learning `e2e-msw-serviceworker-block`). 기존 issue E2E 함께 실행(learning `ui-pr-defer-e2e-regression-latent`).
**GREEN**: 셀렉터 컨테이너 한정(텍스트 중복 대비). getByRole exact.
**REFACTOR**: 헬퍼 정리.
**검증**: `pnpm --filter web test:e2e issue-changelog` + 기존 issue E2E 회귀 확인.

## Plan 메타

- task 수: 9 (backend 3 · frontend 5 · E2E 1)
- 의존성 그래프: B1→B2→B3 (백엔드 직렬, 같은 모듈) · F1·F2·F3 독립 → F4(F1,F2,F3) → F5(F4) → Q1(F5)
- 예상 wave: 약 4 (W1: B1·F1·F2·F3 / W2: B2·F4 / W3: B3·F5 / W4: Q1). 단 백엔드 3 task는 같은 issue-tracking 모듈이라 test 컴파일 직렬화(learning `bts-plan-wave-gradle-module-compile`)
- TDD 강제: yes (모든 task RED→GREEN→REFACTOR)
- 추가 검증: ktlint/detekt(backend), typecheck/lint/vitest(frontend), playwright(E2E)
- BC: 단일 issue-tracking. backend read 엔드포인트는 same-BC view-layer 예외(learning `2026-05-22`). cross-BC 직접 import 0.
- 데이터 모델/마이그레이션 변경 없음(읽기 전용, repo 페이징 메서드만 추가).

## 리뷰 결과

### eng 독립 리뷰 (2026-06-11, Explore 에이전트 적대적 검증)

**BLOCKER: 없음** (리뷰어 제기 2건 모두 기각/검증 통과)
- ~~B1 (findChangelog 메서드 부재)~~ → **오탐**. plan 단계라 코드 미작성이 정상(구현은 bts-impl). plan이 신규 코드를 올바르게 기술 중.
- ~~B2 (UserLookupPort prod 바인딩 미확인)~~ → **검증 통과**. `identity-access/UserLookupAdapter.kt:91`이 prod override 구현. #120 `IssueChangeLabelResolver.kt:164-166`이 이미 같은 포트로 graceful degrade 사용 중(fail-open 아님). B2에 패턴 재사용 명시 반영.

**CONCERN 반영**
- ✅ C2 (items 배치 로딩 cartesian product 위험) → B1 GREEN에 `fetchItemsByGroupIds` 2-step 배치 유지 명시.
- ✅ C3 (findByKey UUID 추출 흐름 모호) → B2 GREEN에 `findByKey`→`IssueResponse.id` 흐름 4단계 명시.
- ✅ C5 (customField 정의 로드 경로 누락) → F3/F4/F5에 `use-custom-fields` 경유 customFieldDefinitions ref 명시.
- 🔸 C1 (Page 직렬화 형식) → B3 통합 테스트가 기존 list 엔드포인트와 동일 `PageImpl` 패턴 사용으로 핀다운(프론트 `pageSchema`: content/totalElements/totalPages/size/number/first/last/empty 확인). 신규 형식 발명 금지.
- 🔸 C4 (공유 파일 겹침) → 본 PR 내 F1/F2/F3는 서로 다른 파일 수정(F2=handlers.ts, F3=i18n/ko.ts — 상호 겹침 0)이라 wave 병렬 안전. 단 병행 worktree(FR-NT-01/FR-MF-02)가 handlers.ts/i18n/ko.ts 동시 수정 시 머지 시점 충돌 가능 → bts-merge 전 `git pull --rebase origin main`로 해소(learning `migration-vnumber-concurrent-branch-collision` 유사).

**총평**: 도메인/스펙/API 정의 우수. eng 리뷰 반영 후 backend 정합성(권한 가드 재사용·actor degrade·페이징 N+1 회피) 명시 완료. 진행 가능.
