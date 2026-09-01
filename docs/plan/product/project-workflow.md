<!-- project-workflow BC — 워크플로우 FSM 7 FR + 워크플로우 FSM PoC + pgmq 트랜잭션 PoC -->

# project-workflow BC

**소속 FR**. 7개 (WF 7).
**책임**. 상태 기계(FSM) 기반 워크플로우, 정의 편집(CRUD·초안/발행), 전환 검증, 후처리.
**SDD 참조**. 07장 (워크플로우 엔진).
**다른 BC와의 경계**. issue-tracking BC의 상태 전환 호출을 받아 검증. pgmq 이벤트 발행.

## §0 진입 조건

- [x] identity-access §2.1, §4.4 (워크플로우 관리 권한) 완료 — 2026-07-27 실측: `identity-access.md` §2.1 D1~D7 7/7 `[x]`, §4.4 D1~D5 5/5 `[x]` (D6/D7은 "범위 외" 명시), 미완 0
- [ ] DATA.md §트랜잭션 + §pgmq 규칙 숙지 — 미측정. `DATA.md §6 트랜잭션 경계` · `§7 PostgreSQL 특화 (FTS / pgmq / 인덱스)` 두 절은 실재하나, "숙지"는 사람의 행위라 저장소에서 검증 불가. 규칙 준수의 간접 근거는 §1.2 마지막 항목(롤백 정합성 통합 테스트 `[x]`)
- [x] §1 기술 검증 통과 (아래) — 2026-07-27 실측: §1.1 5/5 + §1.2 5/5 = 10/10 `[x]`, 미완 0

## §1 기술 검증

### §1.1 워크플로우 FSM PoC (3일)

**SDD**. 07장. **checklist.md 위임**. §1.1. **ADR 후보**. 없음.

- [x] PostgreSQL 테이블 스키마 1차 안정 (Flyway V001) (PR #10, 2026-05-22)
- [x] Spring Boot 진입점 (`./gradlew :backend:bootRun` 성공) (PR #10, 2026-05-22)
- [x] TDD 사이클 1회 완료 (`test:` → `feat:` → `refactor:`) (PR #10, 2026-05-22)
- [x] Testcontainers 통합 테스트 1개 통과 (상태 전환 invariant 검증) (PR #10, 2026-05-22)
- [x] Maxi 검토 통과 (PR #10, 2026-05-22)

### §1.2 pgmq 트랜잭션 일관성 PoC (1일)

**SDD**. 03.6. **checklist.md 위임**. §1.5. **ADR 후보**. **pgmq 이미지 선정** (fr-index.md §A.3 #1).

- [x] pgmq 이미지 선정 ADR 작성 (`docs/adr/<date>-pgmq-image.md`) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] PostgreSQL 16 + pgmq 컨테이너 기동 (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] `pg_trgm` 확장 설치 (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] jOOQ routine 래퍼 (pgmq 함수 호출) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] 트랜잭션 일관성 통합 테스트 — "이슈 생성 ↔ 알림 큐 발행" 동일 트랜잭션 (롤백 시 큐도 롤백) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)

## §2 워크플로우 (FR-WF, 7개)

### §2.1 FR-WF-01 — FSM 워크플로우 (상태/전환/조건/검증/후처리)

**우선순위**. 필수 | **선행**. §1 | **Plan slug**. `workflow/fsm`

- [x] D1. 도메인 — Workflow Aggregate, State, Transition, Guard, PostAction (책임. backend-engineer + Maxi) (PR #10, 2026-05-22)
- [x] D2. 명세 — Given/When/Then. 표준 4종 워크플로우 (Software Dev, Bug Tracking, Service Desk, Task) (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D3. 데이터 모델 — `workflows`, `workflow_states`, `workflow_transitions`, `workflow_guards` (책임. db-engineer) (PR #10, 2026-05-22)
- [x] D4. 백엔드 — `WorkflowEngine` + 상태 전환 API + invariant 검증 (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D5. 백엔드 테스트 — TDD + property-based test (전환 무결성) (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D6. 프론트 UI — 워크플로우 다이어그램 (mermaid 또는 SVG) (책임. designer → frontend-engineer) (PR #13, 2026-05-22; 후속 cleanup PR #16/#20/#21)
- [x] D7. E2E + NFR — 상태 전환 6단계 시나리오 (책임. qa-engineer) (PR #13 + PR #19, 2026-05-22 ~ 2026-05-23; 후속 cleanup PR #16/#20/#21)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 상태 전환 처리 | 100ms | ___ |

### §2.2 FR-WF-02 — 프로젝트별 워크플로우 스킴 + 타입별 매핑

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `workflow/scheme`

- [x] D1. 도메인 — WorkflowScheme (책임. backend-engineer) — PR #18
- [x] D2. 명세 (책임. backend-engineer) — PR #18
- [x] D3. 데이터 모델 — `workflow_schemes`, `project_workflow_scheme_map`, `scheme_issue_type_workflow` (책임. db-engineer) — PR #18
- [x] D4. 백엔드 — Scheme 관리 API — REST 10 endpoint (Scheme CRUD 5 + Mapping 2 + Project assignment 2 + IssueType read 1) + 예외 핸들러 (책임. backend-engineer + security-engineer) — PR #18
- [x] D5. 백엔드 테스트 — 단위·Controller·Repository 통합 완료 (책임. backend-engineer) — PR #18. *S1~S8 시나리오 통합테스트 + ADR·SDD 정정은 Wave 6 후속*
- [x] D6. 프론트 UI — 프로젝트 설정 → 워크플로우 (책임. designer → frontend-engineer) — PR #31
- [x] D7. E2E — 워크플로우 스킴 5 시나리오 Playwright (스킴 CRUD + 매핑 편집 + 표준 보호 + 사용 중 차단 모달 + 프로젝트 할당) (책임. qa-engineer) — PR #35, 2026-05-29

### §2.3 FR-WF-03 — 워크플로우 전환 validator/PostAction 런타임 결선

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `workflow/validator-runtime-wiring`

> FR-WF-01(PR #10)이 SPI 인터페이스 + 구현체(validator 4종/PostAction 5종)만 만들고 production 결선은 안 한 비계(테스트 익명 object만, 실동작 FSM invariant뿐)를 완성. FR-IS-07(resolution 종료 전환 필수) 선행. 배포 조립 부재(no-cross-bc-deployment-assembly)로 검증은 test-assembled 컨텍스트 한정 — 실배포 부팅 결선은 BC 조립 모듈 후속.

- [x] D1. 도메인 — validator 적용 단계(phase) 구분 AVAILABILITY/EXECUTION, 결선 범위 확정 (책임. backend-engineer + Maxi) (PR #66, 2026-06-03)
- [x] D2. 명세 — factory/repo/seed 결선 + test-assembled 검증. 확정 D8=A(phase 구분)/D9=A(PostAction 계산만, GAP-2)/D10=B(test-assembled 검증) (책임. backend-engineer) (PR #66, 2026-06-03)
- [x] D4. 백엔드 — DefaultWorkflowValidatorFactory/PostActionFactory + DefaultWorkflowDefinitionRepository(jOOQ, workflowKey로 transition_id 해석) + WorkflowEngineConfig(SpelEvaluator·ExecutorService @Bean) + YamlSeedService validators/post_actions 시드(시드 시점 type fail-fast) + WorkflowValidator.phase (책임. backend-engineer) (PR #66, 2026-06-03)
- [x] D5. 백엔드 테스트 — TDD 8 task(red→green→refactor) + test-assembled @ContextConfiguration 통합검증(B2 cross-workflow 격리, validator plan 거부/availableTransitions 포함, PostAction plan 누적) (책임. backend-engineer) (PR #66 + #69 테스트 토큰 정본화, 2026-06-03)

> **D3/D6/D7 비해당**. 마이그레이션 신규 없음(`workflow_validators`/`workflow_post_actions` V200 기존, jOOQ 상수 자동 생성). UI/E2E 없음(백엔드 기반 인프라). 잔존 비차단 — `YamlSeedService.isDirty`가 validator/post_action config-only 변경 미감지(type만 비교), 프레임워크가 config를 런타임 실소비하는 시점에 확장.

---

> **§2.4~§2.7 공통 배경 (2026-08-18 신설)**. §2.1~§2.3 이 만든 것은 **런타임 엔진**이고, 워크플로우 **정의를 사람이 고치는 수단**은 만들어진 적이 없다. 표준 4종이 `resources/workflows/*.yaml` 에 하드코딩돼 있고 `YamlSeedService` 가 기동마다 YAML↔DB 를 비교해 다르면 삭제 후 재삽입하므로, DB 를 고쳐도 재기동하면 되돌아간다. §2.2(FR-WF-02)가 구현한 것은 **기존 워크플로우를 이슈 타입에 배정하는 스킴 매핑**이지 워크플로우 자체의 편집이 아니다. Jira Cloud 패리티를 목표로 넷으로 나눈다 — 넷은 각각 마이그레이션·API·화면이 따로 필요해 하나의 D1~D7 로 묶으면 체크박스가 섞인다(FR-UX-07 분할 선례). 로드맵 정본 `~/.claude/plans/cozy-hatching-otter.md` (10 PR). 관련 ADR — `docs/adr/2026-08-18-workflow-*.md` 4건.
>
> **상태 키는 불변이다.** `issues.current_state_key` · `board_columns.state_key` 가 FK 없이 문자열로 참조하므로, 편집은 `name` 만 바꾸고 `key` 는 생성 시점에 확정해 절대 바꾸지 않는다. 네 FR 전체를 관통하는 안전 축이다.

### §2.4 FR-WF-04 — 워크플로우 CRUD + 전역 상태 카탈로그

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `workflow/definition-crud`

> 상태를 워크플로우 종속에서 **사이트 전역 카탈로그**로 승격하고(Jira Cloud 동일), 워크플로우 자체의 생성·이름/설명 수정·복제·삭제와 워크플로우에 상태를 넣고 빼고 순서를 바꾸는 API 를 만든다. YAML 은 빈 DB 최초 1회 부트스트랩 전용으로 축소하고 재기동 되돌림을 폐지한다.

- [x] D1. 도메인 — `statuses` 전역 카탈로그 · `workflow_statuses` N:M · 키 불변 원칙 · 표준 워크플로우 편집 허용 범위 (책임. backend-engineer + Maxi)
- [x] D2. 명세 — 읽기 API 응답 형태 불변 계약(`GET /workflows/{key}` 는 states[]/transitions[] 유지) · 권한 모델 (책임. backend-engineer)
- [x] D3. 마이그레이션 — V203 카탈로그 생성 · V204 백필(키당 name/category 유일성 가드) · V205 workflows 컬럼(version/origin/deleted_at/is_locked) · **V206 소프트삭제 부분 유니크 인덱스**(#393 — 지운 key 재사용 차단 해소) (책임. db-engineer)
- [x] D4. 백엔드 — 워크플로우·상태 CRUD + `YamlSeedService` 부트스트랩 전환 + `WorkflowCache` 무효화 결선 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — TDD red-first · 마이그레이션 체인 검증 · 재기동 후 편집분 생존 · 캐시 무효화 누락 감지 (책임. backend-engineer)
- [x] D6. 프론트 — `/admin/workflows` 목록 + 목록 모드 편집기(상태 추가·제거·순서) (책임. frontend-engineer) (PR #400, 2026-08-24)
- [x] D7. E2E — 이름 수정 · 상태 추가 · 재기동 생존 (책임. qa-engineer) (PR #400, 2026-08-24). *스펙 4시나리오. 처음엔 Maxi 지시로 작성만 했으나, 리뷰가 그중 1건이 **돌리면 red** 임을 실행으로 밝혀 재설계 후 Maxi 승인으로 실행했다 — **4/4 통과**(PR #400)*

### §2.5 FR-WF-05 — 전환 ID 식별자 (다중 전환 + 전역/최초 전환)

**우선순위**. 필수 | **선행**. §2.4 | **Plan slug**. `workflow/transition-id-identity`

> 전환 identity 를 `(from,to)` 2튜플에서 **전환 ID** 로 옮겨 같은 상태쌍에 이름이 다른 전환을 여러 개 둘 수 있게 하고, `kind` 로 `NORMAL`/`GLOBAL`(모든 상태에서)/`INITIAL`(생성 시 진입) 을 가른다. `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 를 대체한다 — 그 ADR 이 열어 둔 「대안 채택 조건」 탈출구를 쓰는 것이다.

- [x] D1. 도메인 — `Workflow.of()` invariant 재정의((from,to) 중복 금지 제거 · kind 규칙) (책임. backend-engineer + Maxi) (PR #395, 2026-08-20)
- [x] D2. 명세 — 하위호환 계약(`transitionId` 우선 · `toStatusKey` 는 유일 해석 가능할 때만 · 모호하면 409) (책임. backend-engineer) (PR #395, 2026-08-20)
- [x] D3. 마이그레이션 — V207 UNIQUE 해제 · `from_status_id` NULL 허용 · `kind` 추가 · INITIAL 백필(현행 최소 displayOrder 동작 보존) (책임. db-engineer) (PR #395, 2026-08-20). *`to_status_id` NOT NULL 승격과 `ck_transition_kind_from` CHECK 는 `DATA.md §4-1` 3단 분할의 3단계(=`workflow_states` DROP 과 같은 PR)로 이연*
- [x] D4. 백엔드 — 전환 CRUD + 엔진의 전역 전환 처리 + shared-kernel 계약 확장(nullable 추가로 기존 호출부 무변경) (책임. backend-engineer) (PR #395, 2026-08-20)
- [x] D5. 백엔드 테스트 — 같은 쌍 다중 전환 · 전역 전환 후보 노출 · 모호 시 409 (책임. backend-engineer) (PR #395, 2026-08-20)
- [x] D6. 프론트 — 이슈 상태 드롭다운이 전환 이름을 구분해 표시 + **고른 전환을 그대로 실행**(`transitionId` 전달, 409 왕복 제거) (책임. frontend-engineer) (PR #398, 2026-08-24)
- [x] D7. E2E — 같은 쌍 두 전환이 각각 보이고 각각 실행된다 (책임. qa-engineer) (PR #398, 2026-08-24)

### §2.6 FR-WF-06 — 전환 규칙(조건/검증기/후처리) 편집

**우선순위**. 필수 | **선행**. §2.5 | **Plan slug**. `workflow/transition-rule-editing`

> 엔진은 이미 규칙을 DB(`workflow_validators`/`workflow_post_actions`, `type` + `config` JSONB)에서 읽고 Jira 의 조건/검증기 구분도 `ValidatorPhase` 로 표현돼 있다. 없는 것은 **편집 수단**뿐이라 CRUD API 와 화면만 얹는다.

- [x] D1. 도메인 — validator type 4종의 config 스키마 확정 (책임. backend-engineer) (PR #404, 2026-08-25). *확정하며 SDD §7.3·§7.4 의 「타입」 열이 팩토리 `when` 분기의 실제 `type` 문자열과 어긋나 있던 것을 바로잡았다(`permission-check`·`not-status-category` 등). 표↔팩토리 일치는 `scripts/workflow/validator-type-catalog.test.ts` 가 강제한다*
- [x] D2. 명세 — 알 수 없는 type·잘못된 config 의 400 계약 · post-action 경로를 transitionId 로 정렬(구 경로 유지) (책임. backend-engineer) (PR #404, 2026-08-25)
- [x] D4. 백엔드 — validator CRUD API (책임. backend-engineer) (PR #404, 2026-08-25). *`ValidatorController` 4 엔드포인트 · `ValidatorAdminService` · `ValidatorRepository` · validator/post-action 공용 기반 `TransitionRuleRepository`*
- [x] D5. 백엔드 테스트 — 잘못된 config 400 · 저장한 규칙이 실제 전환에서 동작(엔진 통합) (책임. backend-engineer) (PR #404, 2026-08-25). *테스트 +25건*
- [x] D6. 프론트 — 전환 규칙 편집 다이얼로그 (책임. frontend-engineer) (PR #407, 2026-08-26). *형제 post-action UI(FR-NT-05)의 대칭 구현 — `/workflows/{key}` 상세에 `ValidatorConfigSection` 을 나란히 뒀다. **응답의 `editable` 을 그대로 소비**하고 편집 가능 type 목록을 화면에 베끼지 않는다*
- [x] D7. E2E — ~~규칙을 걸면 전환이 막히고, 풀면 통과한다~~ → **화면 계약 + 편집 불가 행**으로 축소 (책임. qa-engineer) (PR #407, 2026-08-26). **스펙 deviation — 아래 각주**

> **★ D7 스펙 deviation** (Maxi 결정, PR #407). `apps/web/e2e/workflow-validator.spec.ts` 는 규칙 CRUD ·
> 편집 불가 행 · 비admin 게이팅 · 프리필 격리를 덮고, **「규칙을 걸면 전환이 막힌다」는 재지 않는다.**
> 착수 시 그것을 `/transitions/plan` 응답 픽스처로 표현하려 했으나 **화면이 그 엔드포인트를 한 번도
> 부르지 않음**이 실측됐다(`planTransition` 호출부가 API 정의와 자기 단위 테스트 밖에 0건 · 이슈 상태
> 드롭다운의 실제 출처는 `useIssueTransitions` → `GET /api/v1/issues/{key}/transitions`). 소비자 0건인
> 픽스처 위에 세우면 가짜 그린이 된다.
> **대신 두 곳이 이미 덮는다.** ① 규칙이 후보를 감추는 계산 — `ValidatorEngineIntegrationTest` 의
> `not-status-category 를 걸면 목록에서 사라지고 지우면 다시 나온다`(Testcontainers 실 DB + 실 엔진)
> ② 화면이 받은 목록을 그대로 그리는 것 — #398 이 만든 `apps/web/e2e/issue-transition.spec.ts` S8.
> `mocks/issue-handlers.ts` 에 시나리오 플래그를 넣는 길은 **그 핸들러를 쓰는 E2E 가 7개**라 영향이 넓고
> 새로 증명되는 것이 ②와 겹쳐 택하지 않았다.

> **D3 비해당 확정** (PR #404, 2026-08-25). 착수 시 재확인한 결과 예정대로였다 — `workflow_validators`/`workflow_post_actions` 는 V200 기존 테이블이라 이 PR 의 **마이그레이션은 0건**이고, 스키마 변경 없이 CRUD 만 얹었다.

### §2.7 FR-WF-07 — 워크플로우 초안·발행 + 상태 이관 마법사

**우선순위**. 필수 | **선행**. §2.5 | **Plan slug**. `workflow/draft-publish-migration`

> 사용 중인 워크플로우를 직접 고치면 편집 중간 상태가 운영에 샌다. 초안(JSONB)을 따로 두고 발행할 때만 정규 테이블에 반영한다. 발행 시 빠지는 상태에 이슈가 남아 있으면 **어디로 옮길지 묻는 마법사**를 띄운다 — 지금은 FK 가 없어 상태를 지우면 이슈가 유령 상태를 가리키고 그 이슈는 이후 어떤 전환도 계산할 수 없다.

- [x] D1. 도메인 — 초안 표현(JSONB) · 발행 이력 append-only · 기본값 복원의 의미 (책임. backend-engineer + Maxi)
- [x] D2. 명세 — 낙관적 락(base_version) 충돌 409 · 이관 대상 산출 규칙 · cross-BC 포트 계약 (책임. backend-engineer)
- [x] D3. 마이그레이션 — V208 `workflow_drafts` · `workflow_publications` (책임. db-engineer)
- [x] D4. 백엔드 — 초안 CRUD · 발행 · 기본값 복원 · 이관 포트(읽기 `IssueStatusUsagePort` = project-workflow 로컬 · 쓰기 `IssueStatusMigrationPort` = shared-kernel) (책임. backend-engineer) (PR #411 초안·발행 · PR #414 이관 실행 경로 · **PR #417 결선**, 2026-09-01)
- [x] D5. 백엔드 테스트 — 발행 전 런타임 불변 · 동시 발행 409 · 이관 후 이슈 상태 전량 이동 (책임. backend-engineer)
- [ ] D6. 프론트 — 발행 다이얼로그 · 상태 이관 마법사 · 기본값 복원 (책임. frontend-engineer)
- [ ] D7. E2E — 상태를 빼고 발행하면 마법사가 뜨고 이관 후 발행된다 (책임. qa-engineer)
- [ ] D8. 프론트 — `@xyflow/react` 다이어그램 편집기 (책임. frontend-engineer)

> **D3 의 번호를 V207 → V208 로 정정했다** (2026-08-26). 계획 당시 예약해 둔 V207 을 FR-WF-05
> (전환 identity, #395)가 먼저 가져갔다. 착수 시점 실측으로 확인해 다음 가용 번호로 바꿨다 —
> 계획서의 번호를 그대로 믿고 파일을 만들었으면 Flyway 가 중복 버전으로 부팅을 막았을 것이다.

> **D8 은 2026-08-26 에 신설했다.** 로드맵(`~/.claude/plans/cozy-hatching-otter.md`) PR 9(xyflow
> 다이어그램 편집기)가 §2 진척표에는 「FR-WF-07 소관」으로 적혀 있는데 D 마커 어디에도 자리가
> 없었다. 두 기록이 서로를 검사하지 않아 그 작업이 어느 쪽에서도 미완으로 세어지지 않는
> 상태였다(`[[two-lists-never-check-each-other]]` 양식). Maxi 확인 후 D 마커로 등재해 진척 계산에
> 들어오게 했다.

> **D1·D3 완료 · D2·D4·D5 는 부분 완료** (PR #411, 2026-08-26). 이 PR 이 낸 것은 로드맵 PR 6
> (project-workflow) 범위다 — V208 스키마 · 초안 CRUD · 발행 · 낙관적 락 409 · 기본값 복원 ·
> 이관 필요 판정(상태별 잔여 건수 응답). **미완으로 남긴 것은 전부 issue-tracking BC 소관**이라
> 「한 PR = 한 BC」 규칙상 같은 PR 에 넣을 수 없다.
> - D2 잔여 — cross-BC 포트 계약(이관 큐잉). 읽기 포트 `IssueStatusUsagePort` 는 #400 이 이미
>   만들어 뒀고 이 PR 이 발행 판정에 재사용했다. 쓰기(큐잉) 포트가 로드맵 PR 7 의 몫이다
> - D4 잔여 — 이관 큐잉 호출. 지금은 이슈가 남아 있으면 409 로 **막고** 상태별 건수를 응답에 싣는다
> - D5 잔여 — 「이관 후 이슈 상태 전량 이동」. 이관 실행이 PR 7 이라 그 테스트도 그쪽이다

> **D4 완료** (PR #417, 2026-09-01 · 로드맵 PR 7b). 위 「D4 잔여 — 이관 큐잉 호출」이 채워졌다.
> `POST /api/v1/workflows/{key}/publish/migrate` 가 이관을 큐잉하고, `POST /publish` 는 의미가
> 그대로다(결정 D2 — 「발행하지 않고 202」인 상태를 만들지 않는다).
> - **매핑 가드 8종** — 빠지지 않는 출발지 · 초안에 없는 도착지 · 발행 전 도착지(F16) · 빈 목록 ·
>   중복 출발지 · 범위 없음 · 형제 워크플로우(F11 fail-closed) · 상한 초과(F14). 전부 포트에
>   닿기 전에 막는다 — 큐잉이 pgmq 에 닿으면 되감아도 메시지가 남는 경로가 있다
> - **교체 직후 재카운트**(F10) — 첫 검사와 정의 교체 사이에 들어온 이슈를 잡는다. 부채 143 을
>   **축소**한 것이지 닫은 것이 아니다. 재카운트→COMMIT 잔여 창은 전환 핫패스가 정의 행을 잠가야
>   닫히고 그 경로는 issue-tracking BC 소관이다
> - **in-flight 중복 거부**(F15) — 끝나지 않은 이관이 있으면 409. 발행과 같은 키 공간의 advisory
>   lock 안에서 검사·큐잉하므로 동시 요청도 직렬화된다
> - **아카이브 프로젝트**(E7) — 이관 범위에는 **넣고** 발행 차단 카운트에서만 뺀다. 범위에서까지
>   빼면 그 이슈가 흔적 없이 사라지고, 넣어 두면 `bulk_operation_items` 에 `PROJECT_ARCHIVED` 로
>   남아 「몇 건이 왜 안 옮겨졌는지」를 셀 수 있다
>
> **D6·D7·D8 은 그대로 남는다** — 관리자가 이관을 시작할 화면이 아직 없다. API 만 열린 상태다.

> **D6·D7 착수 — 초안 전환·발행·복원 (로드맵 PR 10a, 2026-09-01)**. ★**둘 다 아직 `[ ]` 다.**
> D 마커는 완주 단위라(`docs/plan/fr-index.md:295`) 이관 마법사(PR 10b)까지 머지돼야 닫는다.
> - **낸 것** — 편집기를 **초안 기반으로 전환**했다. 상태 추가·제거·순서와 전환 CRUD 가 더는
>   정규 테이블에 즉시 쓰이지 않고 로컬 초안에 쌓여 자동저장된다. 발행 다이얼로그(이관이
>   필요 없는 경로) · 기본값 복원 · 초안 폐기 · 낙관적 락 충돌 배너.
> - **남는 것** — 상태 이관 마법사와 진행률 폴링(PR 10b) · xyflow 다이어그램(D8).
>
> **★ D8 을 D6·D7 보다 뒤로 미뤘다** (Maxi 결정). 로드맵 PR 10 의 선행 표기가 `6,7,9` 였으나
> **9(xyflow)와의 코드 의존이 실측상 0** 이다 — 발행 다이얼로그는 탭 셸에 붙고 캔버스와
> 무관하다. PR 8 의 선행에서 5 를 뺐던 선례와 같은 정정이고, 로드맵 파일의 그 절도 함께 고쳤다.
>
> **착수 중 실측한 결함 4건.** 둘은 차단 결함이었고 둘 다 **목으로는 초록**이라 늦게 드러났을 자리다.
> - **G1 (닫음)** — `apps/web/src/api/bulk-operations.ts` 의 Zod 계약이 백엔드보다 뒤처져
>   상태 이관 진행률 폴링이 첫 응답에서 throw 한다. 한 파일에 셋이었다 — `BulkOperationType`
>   에 `STATUS_MIGRATION` 누락 · `FailureReasonCode` 9종 중 7종만 · **payload union 에
>   `StatusMigration` 갈래 없음**. 세 번째가 결정적이라 enum 둘만 고쳐도 여전히 죽는다.
>   백엔드 `.kt` 4개를 읽어 enum 4종을 양방향 대조하는 판별식을 함께 넣었다
>   (`api/__tests__/bulk-operation-enum-parity.test.ts`).
> - **G2 (닫음)** — 「기본값으로 복원」의 표시 조건을 화면이 알 수 없었다. `origin` 이
>   `WorkflowVersionRow` 에만 있고 응답 DTO 에 없어 CUSTOM 워크플로우에서도 버튼이 뜨고
>   눌러야 400 을 안다. `DraftResponse.canResetToDefault` 를 얹었다 — origin 을 그대로
>   노출하지 않은 것은 판정 규칙이 화면에 복제되면 조건이 늘 때 서버와 갈리기 때문이다.
> - **G3 (축소)** — 로드맵이 요구한 「`board_columns` 가 같은 상태를 쓰면 경고 배너」는
>   **조건부로 구현할 수 없다**. 보드 조회는 `projectKey` 가 필수이고 이관 범위 프로젝트
>   목록은 서버가 일부러 응답에 안 싣는다(`MigrateRequest` KDoc — 범위를 요청이 정하게 두면
>   남의 프로젝트를 옮길 수 있다). **무조건부 고지**로 축소했다. 조건 판정을 흉내 내면
>   그것이 거짓말이 된다.
> - **G4 (이월 · D6b 의 진짜 선행)** — 아래 별도 각주.

> **★ G4 — 이관 진행률 폴링이 항상 403 이다 (2026-09-01 실측 · `TODOS.md` X10 서술 정정)**.
> 마법사(PR 10b)의 핵심 경로가 백엔드 수정 없이는 **동작하지 않는다**.
> - 이관은 `WorkflowStatusMigrationAdapter.kt:161` 이 `actorId = cmd.actorUserId` 로 **실제
>   발행자 UUID** 를 심는데, 조회는 `BulkOperationController.kt:153` 이 `SYSTEM_ACTOR_UUID` 로
>   비교하고 `:161` 이 불일치면 403 을 던진다. `GET /api/v1/bulk-operations/{id}` 가 이관
>   작업에 대해 언제나 403 이다.
> - **왜 안 드러났나** — 기존 `BULK_EDIT`·`BULK_TRANSITION` 은 같은 컨트롤러가 만들어
>   (`:103` 도 SYSTEM UUID) 생성과 조회가 자기들끼리 맞는다. 이관만 다른 경로(project-workflow
>   → 포트 → 어댑터)로 실제 UUID 를 넣어 어긋나고, 결선(#417)이 그 경로를 처음 열었다.
> - **장부 서술이 실물보다 가볍다.** `TODOS.md` 는 이것을 「누가 옮겼나가 전부 같은 UUID 로
>   찍힌다」는 감사 추적 정확도 문제로 적었는데, 실제로는 **기능을 막는다**. M2 결정의
>   「한계를 화면에 표시하면 된다」가 이 항목에는 성립하지 않는다.
> - **처방** — `BulkOperationController` 3곳(`:103`·`:153`·`:207`)이 `SecurityContextHolder` 의
>   인증 UUID 를 쓰게 하는 **issue-tracking 단독 PR** 을 D6b 의 명시적 선행으로 둔다. 코드
>   주석이 이미 「security-engineer wave 에서 교체 예정」이라 적어 둔 자리다.

> **D2·D5 완료 · D4 는 결선만 남았다** (로드맵 PR 7 — 이관 실행, 2026-08-27). 위 #411 각주가 「PR 7 의
> 몫」으로 넘긴 것을 이 PR 이 받았다. 「한 PR = 한 BC」 규칙대로 이번 범위는 전부 issue-tracking BC 다.
> - **D2 완료** — 쓰기 포트 계약 `shared-kernel com.bts.shared.issue.IssueStatusMigrationPort`
>   (fail-closed · default 구현 0)와 이관 대상 산출 규칙(매핑 키 ∩ `projectKeys` · **실행 시점 재조회**)이
>   확정됐다. ★**읽기 포트와 다른 물건이다** — 읽기 `IssueStatusUsagePort` 는
>   `project-workflow/.../application/port/` 에 **BC 로컬**로 실재하고 shared-kernel 에 있지 않다.
>   「읽기 전용 스칼라 count」라서 받았던 면제가 쓰기에는 넘어오지 않아 쓰기만 shared-kernel 로 올렸다
> - **D5 완료** — 「이관 후 이슈 상태 전량 이동」을 이관 실행 경로에서 단언한다. 큐잉 이후 그 상태로
>   들어온 이슈도 옮겨지는가(「옮기면서 센다」) · 범위 밖 프로젝트가 무변경인가 · 실패 건이
>   `bulk_operation_items` 에 FAILED 로 남는가가 함께 걸렸다
> - **D4 잔여는 결선뿐** — `WorkflowPublishService` 가 실제로 포트를 호출하는 자리다. 이 PR 에는
>   **호출자가 없어 사용자에게 보이는 변화가 0** 이다. 의도된 분할이고, 발행 루프가 필요한 뒤쪽
>   TOCTOU 창과 함께 **로드맵 PR 7b** 로 간다
> - ★**로드맵 정본도 같은 날 정정했다** — `~/.claude/plans/cozy-hatching-otter.md` PR 7 절이
>   `transitionId` 수용 · `toStatusKey` 모호 시 409 · `GET /transitions` 응답의 `transitionId` 를 아직
>   「할 일」로 적고 있었다. 셋 다 **#395(커밋 `727207a01`)가 이미 구현했다.** 그 절을 안 고치면 다음
>   사람이 범위를 두 배로 잡는다 — 착수 세션이 실제로 그렇게 잡았다. 같은 정정으로 PR 7b 절도 신설했다

> **로드맵 PR 7b 착수 조건 (전수 열거 · 이 각주가 정본)**. `TODOS.md` 의 「워크플로우 — 이관 필요 판정과
> 실제 교체 사이에 이슈가 끼어들 수 있다」 항목이 여기를 가리킨다. 저장소에 안 남는 약속은 약속이 아니다.
> - **결선** — `WorkflowPublishService` → `IssueStatusMigrationPort` 호출
> - **뒤쪽 TOCTOU 창** — 이관 완료 → 정의 교체 사이. 「센다 → 옮긴다 → 다시 센다 → 남으면 다시 옮긴다」
>   발행 루프가 필요하다. 앞쪽 창(큐잉 → 실행)은 PR 7 이 닫았다
> - **`countIssuesInStatus` 프로젝트 스코프** — 프로젝트 → 스킴 → 워크플로우 3단으로 좁힌다. 지금은 상태
>   키가 전역이라 다른 워크플로우를 쓰는 이슈까지 세어 과하게 막는다. 권한을 넓히기 전 선행이다
> - **배포 순서·롤백** — 구버전 워커가 새 `bulk_operations.operation_type` 값을 `enumValueOf` 로 풀다
>   죽는다. PR 7 은 호출자가 없어 그 값의 행이 안 생기므로 지금은 안전하지만 **결선 즉시 실재한다**
> - **권한 검사 순서 계약** — 발행 경로가 `WorkflowDefinitionPermission.PUBLISH` 를 검사한 **뒤에만**
>   포트를 부르는지 판정을 붙인다. 포트 KDoc 이 「위조 차단은 호출자의 발행 권한 책임」이라 약속했는데
>   PR 7 엔 호출자가 없어 그 약속을 검사할 장치가 없다
> - **`cause` 결선·검증** — 이관 이벤트의 `cause = "STATUS_MIGRATION"` 을 사외 웹훅
>   (`search-export-import/.../WebhookDispatchWorker.kt`)과 알림이 **실제로 거르는지**. PR 7 은 표시만
>   싣는다. 이관 N 건이 되돌릴 수 없는 웹훅 N 건이 되는 자리라 표시만 두고 끝내면 절반이다

> **로드맵 PR 7b 착수 (2026-08-31 · plan `docs/plans/2026-08-31-workflow-status-migration-wiring.md`)**.
> 위 착수 조건 각주가 정본이고, 이 각주는 **그 여섯 줄이 어떻게 처리되는지**를 적는다.
> ★**D4 는 아직 `[ ]` 다** — 착수했을 뿐 구현이 끝나지 않았다. 체크는 머지 시점에 한다.
> - **결선** — 닫는다. `POST /api/v1/workflows/{key}/publish/migrate` 를 새로 두고 그것이
>   `IssueStatusMigrationPort.enqueueStatusMigration` 을 부른다. `POST …/publish` 는 **의미 불변**이다 —
>   한 호출로 묶으면 발행 API 가 「발행하지 않고 202」를 돌려주는 상태를 갖게 되고, 나누면 부수적으로
>   트랜잭션이 BC 를 안 넘는다. 지라는 조작 1회(**Update workflow**)라 이것이 **의도적 편차**다
> - **뒤쪽 TOCTOU 창** — **축소**다. 닫혔다고 적지 않는다. `replaceDefinition` 직후 같은 트랜잭션에서
>   교체 전 `removed` 집합으로 재카운트하고 잔여가 있으면 롤백한다. 「재카운트 → COMMIT」 구간은 남고,
>   완전 폐쇄는 전환 핫패스가 정의 행을 잠가야 해서 **issue-tracking BC** 소관이다
> - **`countIssuesInStatus` 프로젝트 스코프** — 닫는다. 포트를 `(statusKey, projectIds)` 로 넓히고
>   `projectIds` 를 워크플로우 → 스킴 → 프로젝트 3단 JOIN 으로 채운다. 요청에서 받지 않는다
> - **배포 순서·롤백** — 닫되 **결론이 바뀌었다.** 「구버전 워커가 먼저 올라가야 한다」는 롤링 배포와
>   워커 별도 배포물을 전제하는데 **둘 다 없다**(실측 — `bts-deploy.sh` 가 단일 호스트에서
>   `docker compose up -d` · `bts-backend` 는 `container_name` 이 박혀 복제 불가 · 워커는 같은 프로세스의
>   `@Scheduled` 폴러). 순서 축이 성립하지 않고 남는 축은 **롤백(신→구)** 뿐이다. 절차 정본은 plan 의
>   `#### C3 상세`. 죽는 지점도 하나가 아니라 **둘**이다 — `BulkOperationRepository.kt:531-533` 과
>   `FailureReasonCode` 를 푸는 `:205`
> - **권한 검사 순서 계약** — 닫는다. 권한 없는 actor 의 `migrate` 에서 **포트 호출 0회**를 스파이로
>   단언한다. 403 만 재면 「포트를 먼저 부르고 나중에 던지는」 순서를 못 잡는다
> - **`cause` 결선·검증** — **이월.** 소비자가 `search-export-import` · `notification` ·
>   `slack-integration` 3 BC 라 「한 PR = 한 BC」에 걸린다. 실측(2026-08-31) — 그 문자열이
>   issue-tracking 밖 전 BC 통틀어 **0건**이고 `parseIssueTransitioned` 는 `issueKey`·`projectKey`·
>   `fromState`·`toState` 4필드 화이트리스트라 `cause` 를 **파싱조차 하지 않는다**. `TODOS.md` 에
>   **PR 10 의 명시적 선행**으로 승격했다
>
> **PR 7b 가 새로 남기는 것 (전수 열거 — 착수 조건에 없던 항목이다)**. 게이트 1 이 BLOCKER 처방으로
> fail-closed 가드를 넣으면서 생긴 한계이고, 숨기지 않고 `TODOS.md` 에 등재한다.
> - **C6 다중 워크플로우 스킴 미지원** — 대상 프로젝트의 스킴이 이 워크플로우 **하나만** 쓸 때에만
>   `migrate` 를 허용한다(400 fail-closed). 워커의 이관 대상 쿼리에 **이슈 타입 조건이 없어서**,
>   스킴이 `(scheme_id, issue_type_id) → workflow_id` 인데 한 프로젝트가 Bug→WF1 · Task→WF2 를 쓰면
>   WF1 발행이 **WF2 이슈까지 옮긴다**. 여는 것은 이관 커맨드 확장 + 워커 쿼리 수정이라
>   **shared-kernel + issue-tracking 두 BC** 를 건드리는 T3 별건이다
> - **C7 migrate↔publish 사이 초안 변경 창** — in-flight 중복 큐잉은 막지만 초안 편집 자체는 못 막는다.
>   **유령은 안 생긴다**(되살린 상태는 발행 시 다시 빠지는 상태로 들어가 재카운트가 막는다). 남는 것은
>   「원하지 않은 1회 대량 이동」이고 관리자가 되돌릴 수 있다
> - **C5 아카이브 유령** — 아카이브 프로젝트 이슈는 **카운트에서 빼되 이관 범위에는 넣는다**. 넣지
>   않으면 조용히 사라지고, 넣으면 `bulk_operation_items` 에 `PROJECT_ARCHIVED` 로 남아 **셀 수 있다**.
>   프로젝트를 다시 활성화하면 그 이슈들이 워크플로우에 없는 상태에 남아 있는 것이 한계다

> **cross-BC 주의**. 이슈 일괄 이관의 실제 UPDATE 는 issue-tracking BC 소유다. 다중 BC 트랜잭션 금지 규칙에 따라 project-workflow 는 포트로 큐잉만 하고, 처리는 기존 `bulk_operations` 인프라가 맡는다. 기존 `BULK_TRANSITION` 은 엔진을 태우므로 **재사용할 수 없다** — 이관 대상은 이미 워크플로우에서 빠진 상태라 유효한 전환이 없어 전량 실패한다. `STATUS_MIGRATION` 타입을 따로 둔다.

## §NFR project-workflow BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 상태 전환 처리 (FSM) | 100ms | ___ | k6 (단일 트랜잭션 + pgmq 이벤트) — k6 + axe 도입 후속 |
| 스킴 매핑 조회 | 50ms | ___ | k6 — k6 + axe 도입 후속 |
| 워크플로우 다이어그램 렌더 | 1s | ___ | Playwright — k6 + axe 도입 후속 |
| pgmq 트랜잭션 롤백 정합성 | 100% | ___ | 통합 테스트 — k6 + axe 도입 후속 |
| WCAG 2.1 AA | 0 violations | ___ | axe-core — k6 + axe 도입 후속 |

> **Deferred trigger**. §NFR 5건 미실측은 silent 영구 보류 아님. trigger — (a) FR-WF-02 (스킴 매핑) 머지 후 + (b) `docs/adr/*-k6-load-testing.md` + `docs/adr/*-axe-accessibility.md` 2건 ADR 발행 시점에 측정 일괄 진행. Maxi 1인 선언으로 trigger 조정 가능.

### BC 완료 조건

> **§2 진척**. FR-WF-01 ✅ / FR-WF-02 D1~D5 ✅ 머지 #18 / D6 ✅ 머지 #31 / D7 ✅ 머지 #35 / FR-WF-03 ✅ 머지 #66 (테스트 토큰 정본화 #69) / **FR-WF-04 D1~D5 ✅ 머지 #392·#393 / D6~D7 ✅ #400 (로드맵 PR 8 — PR 9·10 은 다이어그램·발행으로 FR-WF-07 소관) · FR-WF-05 D1~D5 ✅ #395 / D6~D7 ✅ #398 · FR-WF-06 D1·D2·D4·D5 ✅ #404 (D3 비해당 확정) / D6~D7 ✅ #407 (D7 은 스펙 deviation — §2.6 각주) · FR-WF-07 D1·D3 ✅ (로드맵 PR 6) / D2·D5 ✅ (로드맵 PR 7 — 이관 실행) / D4 ✅ (로드맵 PR 7b — #417 결선) / D6 부분 (초안 전환·발행·복원 — 로드맵 PR 10a) / D7 부분 / D8 ⬜** — 후속. Wave 6(S1~S8 통합테스트 + ADR/SDD) · C1 detekt 정합 · §NFR deferred trigger 도달 시 측정

- [ ] §2 (FR-WF 7개) 모두 `[x]` 마킹 — **2026-08-18 재실측: 미완 27건**. WF-01 D1~D7 · WF-02 D1~D7 · WF-03 D1/D2/D4/D5(D3/D6/D7 비해당)까지 18/18 `[x]` 로 닫혀 있었으나, 워크플로우 편집기 FR 4건(WF-04~07)이 신설되며 D 마커 27개가 새로 열렸다. **이 게이트는 2026-07-27 에 한 번 닫혔다가 범위 확대로 다시 열린 것**이다 — 조용히 닫아 두지 않는다.
  **2026-08-26 재실측(PR #407 시점) — 미완은 §2.7 FR-WF-07 의 D1~D7 뿐이다.** WF-04·05·06 이 전부 닫혔다.
  **같은 날 재실측(초안·발행 PR 시점)** — §2.7 에 D8(xyflow 다이어그램)이 신설돼 마커가 8개가 됐고,
  그중 D1·D3 가 닫혔다. 미완은 **D2·D4·D5·D6·D7·D8** 여섯이며 D2·D4·D5 는 부분 완료다(잔여가 전부
  issue-tracking BC 소관이라 「한 PR = 한 BC」 규칙상 분리됐다). 전수는 §2.7 각주에 적었다.
  **2026-08-27 재실측(이관 실행 PR 시점)** — 로드맵 PR 7 이 D2(쓰기 포트 계약 + 이관 대상 산출
  규칙)와 D5(이관 후 전량 이동)를 닫았다. 미완은 **D4·D6·D7·D8** 이고, 그중 D4 는 결선 한 자리만
  남은 부분 완료다 — 발행 경로가 이관 포트를 실제로 부르는 것은 **로드맵 PR 7b** 다. 전수는 §2.7
  각주에 적었다.
  개수 대신 전수로 적는다 — 「N건」은 눈가리개이고, 실제로 `grep '^- \[ \] D'` 는 §1.2 의
  「**D**ATA.md … 숙지」 줄을 D 마커로 오탐한다
- [ ] §NFR 측정표 모든 항목 임계 통과 (위 deferred trigger 충족 후) — 미측정. 측정표 5행 실측값이 전부 `___`. deferred trigger (b) 미충족 — `docs/adr/`·`docs/decisions/` 어디에도 `*-k6-load-testing.md`·`*-axe-accessibility.md` 없음 (2026-07-27 실측). k6 부하 · Playwright 렌더 · axe-core 5항목 측정 필요
- [x] pgmq ADR (§A.3 #1) 발행 완료 — 2026-07-27 실측: `docs/adr/2026-05-22-pgmq-postgres-image.md` 실재 (일자 2026-05-22). `docs/decisions/` 에는 없음
- [x] CHANGELOG.md 정리 — 2026-07-27 실측: 저장소 루트 `CHANGELOG.md` §[Unreleased] BC 요약 표에 `project-workflow | 3 (WF 3) | 2026-05-22 ~ 07-11 | 9 (#10~#66 외)` 행 존재
- [ ] README.md §7 변경 이력에 "project-workflow BC 완료 — YYYY-MM-DD" 추가 — 미측정. 2026-07-27 실측: `docs/plan/README.md §7` 3행(2026-05-20 ×2, 2026-07-17) 중 project-workflow 언급 0건. BC 완료 선언 시점에 README 소관 에이전트가 추가해야 함
- [ ] Maxi 1인 선언 — "project-workflow BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
