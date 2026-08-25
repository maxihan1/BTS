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
- [ ] D6. 프론트 — 전환 규칙 편집 다이얼로그 (책임. frontend-engineer)
- [ ] D7. E2E — 규칙을 걸면 전환이 막히고, 풀면 통과한다 (책임. qa-engineer)

> **D3 비해당 확정** (PR #404, 2026-08-25). 착수 시 재확인한 결과 예정대로였다 — `workflow_validators`/`workflow_post_actions` 는 V200 기존 테이블이라 이 PR 의 **마이그레이션은 0건**이고, 스키마 변경 없이 CRUD 만 얹었다.

### §2.7 FR-WF-07 — 워크플로우 초안·발행 + 상태 이관 마법사

**우선순위**. 필수 | **선행**. §2.5 | **Plan slug**. `workflow/draft-publish-migration`

> 사용 중인 워크플로우를 직접 고치면 편집 중간 상태가 운영에 샌다. 초안(JSONB)을 따로 두고 발행할 때만 정규 테이블에 반영한다. 발행 시 빠지는 상태에 이슈가 남아 있으면 **어디로 옮길지 묻는 마법사**를 띄운다 — 지금은 FK 가 없어 상태를 지우면 이슈가 유령 상태를 가리키고 그 이슈는 이후 어떤 전환도 계산할 수 없다.

- [ ] D1. 도메인 — 초안 표현(JSONB) · 발행 이력 append-only · 기본값 복원의 의미 (책임. backend-engineer + Maxi)
- [ ] D2. 명세 — 낙관적 락(base_version) 충돌 409 · 이관 대상 산출 규칙 · cross-BC 포트 계약 (책임. backend-engineer)
- [ ] D3. 마이그레이션 — V207 `workflow_drafts` · `workflow_publications` (책임. db-engineer)
- [ ] D4. 백엔드 — 초안 CRUD · 발행 · 기본값 복원 · `IssueStatusUsagePort`(shared-kernel) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 발행 전 런타임 불변 · 동시 발행 409 · 이관 후 이슈 상태 전량 이동 (책임. backend-engineer)
- [ ] D6. 프론트 — 발행 다이얼로그 · 상태 이관 마법사 · 기본값 복원 (책임. frontend-engineer)
- [ ] D7. E2E — 상태를 빼고 발행하면 마법사가 뜨고 이관 후 발행된다 (책임. qa-engineer)

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

> **§2 진척**. FR-WF-01 ✅ / FR-WF-02 D1~D5 ✅ 머지 #18 / D6 ✅ 머지 #31 / D7 ✅ 머지 #35 / FR-WF-03 ✅ 머지 #66 (테스트 토큰 정본화 #69) / **FR-WF-04 D1~D5 ✅ 머지 #392·#393 / D6~D7 ✅ #400 (로드맵 PR 8 — PR 9·10 은 다이어그램·발행으로 FR-WF-07 소관) · FR-WF-05 D1~D5 ✅ #395 / D6~D7 ✅ #398 · FR-WF-06 D1·D2·D4·D5 ✅ #404 (D3 비해당 확정) / D6~D7 ⬜ · FR-WF-07 ⬜ 미착수 (2026-08-18 신설)** — 후속. Wave 6(S1~S8 통합테스트 + ADR/SDD) · C1 detekt 정합 · §NFR deferred trigger 도달 시 측정

- [ ] §2 (FR-WF 7개) 모두 `[x]` 마킹 — **2026-08-18 재실측: 미완 27건**. WF-01 D1~D7 · WF-02 D1~D7 · WF-03 D1/D2/D4/D5(D3/D6/D7 비해당)까지 18/18 `[x]` 로 닫혀 있었으나, 워크플로우 편집기 FR 4건(WF-04~07)이 신설되며 D 마커 27개가 새로 열렸다. **이 게이트는 2026-07-27 에 한 번 닫혔다가 범위 확대로 다시 열린 것**이다 — 조용히 닫아 두지 않는다
- [ ] §NFR 측정표 모든 항목 임계 통과 (위 deferred trigger 충족 후) — 미측정. 측정표 5행 실측값이 전부 `___`. deferred trigger (b) 미충족 — `docs/adr/`·`docs/decisions/` 어디에도 `*-k6-load-testing.md`·`*-axe-accessibility.md` 없음 (2026-07-27 실측). k6 부하 · Playwright 렌더 · axe-core 5항목 측정 필요
- [x] pgmq ADR (§A.3 #1) 발행 완료 — 2026-07-27 실측: `docs/adr/2026-05-22-pgmq-postgres-image.md` 실재 (일자 2026-05-22). `docs/decisions/` 에는 없음
- [x] CHANGELOG.md 정리 — 2026-07-27 실측: 저장소 루트 `CHANGELOG.md` §[Unreleased] BC 요약 표에 `project-workflow | 3 (WF 3) | 2026-05-22 ~ 07-11 | 9 (#10~#66 외)` 행 존재
- [ ] README.md §7 변경 이력에 "project-workflow BC 완료 — YYYY-MM-DD" 추가 — 미측정. 2026-07-27 실측: `docs/plan/README.md §7` 3행(2026-05-20 ×2, 2026-07-17) 중 project-workflow 언급 0건. BC 완료 선언 시점에 README 소관 에이전트가 추가해야 함
- [ ] Maxi 1인 선언 — "project-workflow BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
