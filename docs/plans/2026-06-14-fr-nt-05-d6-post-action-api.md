# FR-NT-05 D6 선행 PR-A — 워크플로우 전이 post-action CRUD API

> slug: fr-nt-05-d6-post-action-api
> type: api
> agent: backend-engineer (권한 가드는 security-engineer 검토)
> primary_bc: project-workflow
> 생성: 2026-06-14

## Brief

FR-NT-05 D6(워크플로우 post-action 설정 프론트 UI)의 백엔드 enabler. 현재 post-action(CALL_WEBHOOK의 url/method 등)은 `YamlSeedService` → `workflow_post_actions`(V200) 부팅 시드 전용이고 **런타임 편집 엔드포인트가 없다**. 관리자가 전이별 post-action을 추가/수정/삭제할 수 있는 REST API(GET/POST/PUT/DELETE `.../transitions/{transitionKey}/post-actions`)를 admin 권한으로 신설한다.

- workflow_post_actions 스키마 기존(id/transition_id/type/config jsonb/display_order).
- **핵심 결정거리**: YAML seed와 런타임 편집 공존(시드 재적재가 런타임 편집을 덮어쓰지 않게), 편집 가능 post-action type 범위(CALL_WEBHOOK만? 전부?), 권한 모델(전역 admin vs 프로젝트 admin), CALL_WEBHOOK config 검증(url 형식·SSRF 사전검증 위치).
- 직전: PR2(#141) notification 디스패처 백엔드 완성. 후속: PR-B(프론트 설정 UI + E2E).

classify: type=api, agent=backend-engineer, primary_bc=project-workflow, slug=fr-nt-05-d6-post-action-api

## 도메인 정리

- **BC: project-workflow** (코드 실측 확정). 신규 용어 없음(post-action·전이는 기존 유비쿼터스 언어).

### Q1 (핵심) — YAML seed가 런타임 편집을 덮어쓰는가? **YES, 소실됨** (에이전트 오판 정정)
`YamlSeedService.isDirty`(L313-323)는 `differsInPostActions`(L384-394) 포함. **YAML 정의 전이별로 DB post-action type 목록 vs YAML 목록 비교**: `dbTypes != dtoTypes`. 런타임 API로 YAML 시드 전이에 post-action을 추가하면 DB=[CALL_WEBHOOK]≠YAML=[] → isDirty=true → `deleteWorkflow`(L495, CASCADE)→reinsert → **런타임 post-action 전멸**(부팅마다). → **seed↔런타임 공존 설계가 이 PR의 핵심 결정**(spec 게이트에서 Maxi 확정).

### Q2 — 식별자 구조 (V200)
- `workflows`(id UUID PK, key UNIQUE) → `workflow_states`(UNIQUE(workflow_id,key)) → `workflow_transitions`(id UUID, UNIQUE(workflow_id,from_state_id,to_state_id)) → `workflow_post_actions`(id UUID PK, transition_id FK CASCADE, type TEXT, config JSONB, display_order INT). **post-action UNIQUE 없음**(중복 가능).
- 전이 자연키=`(workflow_key, from_state_key, to_state_key)`. post-action 식별=id(UUID). jOOQ `WorkflowPostActions.kt` 생성됨, 스키마 변경 시 init_codegen 미러.

### Q3 — admin 권한 모델 (선례)
`WorkflowSchemeController`가 모든 변이에 `permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)` Guard. resolver=shared-kernel SPI, prod adapter=identity-access `@Profile("prod")`. **post-action CRUD도 MANAGE_SCHEME+Global 차용** 또는 신규 `MANAGE_WORKFLOW` — spec서 결정. 메모리 crossbc-resolver-nullable-fail-open 준수(fail-closed, non-null allow-all 금지).

### Q4 — post-action type 5종 + config 검증
`DefaultWorkflowPostActionFactory.create(type,config)`: SET_FIELD{field}·NOTIFY{channel,recipients}·ADD_WATCHER{watcher}·RUN_AUTOMATION{automationKey}·CALL_WEBHOOK{url,method}. 미지원 type→IllegalArgumentException, 각 createXxx `requireConfigString`+init 제약. 현재 YAML 4워크플로우 모두 post_action 0건 → **런타임 API가 첫 사용처**.

- 기존 결정 충돌: 없음. seed 공존은 신규 ADR 후보. 권한은 WorkflowScheme 선례 계승.

## 스펙

전체. [docs/specs/2026-06-14-fr-nt-05-d6-post-action-api.md](../specs/2026-06-14-fr-nt-05-d6-post-action-api.md)

3줄 요약.
- project-workflow에 전이별 post-action CRUD REST API(`.../transitions/{transitionKey}/post-actions`) 신설, MANAGE_SCHEME+Global Guard.
- 공존(B): `isDirty`에서 `differsInPostActions` 제거 → 런타임 post-action이 재시드 안 당함(평상시 보존). 마이그레이션 0.
- config 검증=DefaultWorkflowPostActionFactory + CALL_WEBHOOK url http(s) 체크. SSRF는 발사 시점 notification 책임(BC 격리).

Maxi 게이트. 공존=B(런타임 전용), 권한=MANAGE_SCHEME+Global, type=5종 제네릭, config=factory검증.

## Brainstorming Check

✅ 통과. 캐시 무효화 배선 grep·FR6 헬퍼 정리·감사 cross-BC 후속·GET admin전용 — 전부 구현 주의(Maxi 결정 불요).

## Plan

> 패키지 `com.bts.workflow`. 전이 식별=(workflowKey, transitionKey=`from__to`). 권한=WorkflowSchemeController 패턴 복사.

### Task 1. PostActionRepository — workflow_post_actions jOOQ CRUD

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionRepositoryIntegrationTest.kt`]
- depends-on: []

**RED** (Testcontainers, V200 마이그레이션 + 전이 시드). findByTransitionId 빈 목록·insert→조회·update→반영·deleteById→삭제·displayOrder ASC 정렬.
**GREEN**. jOOQ로 workflow_post_actions CRUD. transition_id(UUID) 기준. config는 jsonb(ObjectMapper String 직렬화, 기존 YamlSeedService.insertPostActions 패턴 차용).
**REFACTOR**. KDoc + 쿼리 상수.
**검증**: `cd backend && ./gradlew :modules:project-workflow:test --tests "*PostActionRepositoryIntegrationTest*"`

### Task 2. 전이 해석 + PostActionAdminService (검증·캐시무효화)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminExceptions.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionAdminServiceTest.kt`]
- depends-on: [1]

**RED** (MockK: repository + factory). transitionKey→transition_id 해석(미존재 404 예외), create는 factory.create() 검증 후 insert, 미지원type/필수키누락/비-http url→400 예외, post-action id 미존재→404.
**GREEN**. transitionKey `from__to` 파싱→workflowKey로 transition_id 조회(기존 repository/조회 재사용). `DefaultWorkflowPostActionFactory.create(type,config)`로 검증(예외→400 매핑). CALL_WEBHOOK url http/https 스킴 추가 체크. 캐시 무효화 불필요(post-action은 전이 실행 시 DB 직접 조회, WorkflowCache 비캐시 대상 — codereview CONCERN-1).
**REFACTOR**. 예외 계층 + KDoc.
**검증**: `./gradlew :modules:project-workflow:test --tests "*PostActionAdminServiceTest*"`

### Task 3. PostActionController + 권한 Guard (security-engineer 검토)

**메타**.
- agent: `backend-engineer` (권한 Guard는 security-engineer 검토 대상)
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/web/PostActionController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/web/PostActionDtos.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/web/PostActionControllerTest.kt`]
- depends-on: [2]

**RED** (MockMvc/WebMvcTest 또는 통합). GET/POST/PUT/DELETE 경로, 변이 진입 직후 `requirePermission(actor, MANAGE_SCHEME, Global)` Guard 호출(권한 없음→403, 리소스 조회 전), 검증 실패→400, 미존재→404, 성공→201/200/204 + DTO.
**GREEN**. WorkflowSchemeController 패턴 복사: actor 추출→requirePermission→service 호출. DTO(요청 {type,config,displayOrder} / 응답 {id,type,config,displayOrder}). 권한 예외 message 일반화(누출 금지).
**REFACTOR**. KDoc + DTO 정리.
**검증**: `./gradlew :modules:project-workflow:test --tests "*PostActionControllerTest*"`

### Task 4. 공존(B) — isDirty에서 differsInPostActions 제거 + 회귀

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`]
- depends-on: []

**RED**. 런타임 post-action 추가 후 재시드 호출 → 런타임 행 보존 검증(현재는 소실=RED). 기존 4워크플로우 재시드 회귀(state/transition dirty는 여전히 감지) 유지 검증.
**GREEN**. `isDirty`에서 `differsInPostActions(existing, dto)` 항 제거. 미사용 시 `fetchPostActionTypesByTransition` 등 헬퍼 detekt 대비 정리(또는 @Suppress 사유). insertWorkflow의 post-action 적재 경로는 유지(YAML 0건 no-op).
**REFACTOR**. KDoc에 "post-action은 런타임 전용(API 관리), seed dirty 비교 제외" + structural reinsert 한계 명시.
**검증**: `./gradlew :modules:project-workflow:test --tests "*YamlSeedServiceTest*"`

### Task 5. End-to-end 통합 (실 API + 실 DB)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionE2EIntegrationTest.kt`]
- depends-on: [3, 4]

**RED→GREEN** (Testcontainers + 실 컨트롤러/서비스/repo). admin이 CALL_WEBHOOK 추가→GET 조회→PUT 수정→DELETE, 권한 거부 403. 모듈 전체 회귀 0. 캐시 무효화 mock/verify 불필요(post-action DB 직접 조회, WorkflowCache 비캐시 대상 — codereview CONCERN-1).
**검증**: `./gradlew :modules:project-workflow:test`(전체).

## Plan 메타

- task 수: 5. 의존: T1→T2→T3, T4 독립, T5←[3,4]. 단일 모듈/backend-engineer라 사실상 직렬.
- TDD 강제(T1~T5). 추가 검증: ktlint/detekt --rerun-tasks.
- security-engineer는 T3 권한 Guard 코드리뷰 단계 검토.

## 리뷰 결과

### eng + 보안 집중 직접 리뷰 (2026-06-14, api+권한 — autoplan 과적용 회피)
- ✅ 공존(B) 건전(런타임 전용, 마이그레이션0). 권한 MANAGE_SCHEME+Global(워크플로우=전역 공유 스킴이라 Global 적합, WorkflowSchemeController 선례). TDD 커버리지·BC 격리 정합.
- 📌 **SSRF config 시점 갭(수용)**. config 저장은 http(s) 형식만, 완전 SSRF는 발사 시점 notification WebhookUrlValidator가 차단(egress=진짜 경계, PR2 완비). BC 격리상 validator 중복 금지라 분담이 옳음. 내부 URL 저장돼도 발사 차단→실 노출 0.
- 📌 **감사 로그 후속**. webhook config 변경 감사(FR-AU-10)는 cross-BC, PR-A 범위 외.
- 📌 권한 Guard는 codereview서 **security-engineer 검토**(T3).
- **BLOCKER: 없음.**
