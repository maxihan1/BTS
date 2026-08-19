# [migration] project-workflow — 전역 상태 카탈로그 + 시드 부트스트랩 전환

> 티어: T3
> slug: migration-project-workflow-global-status-catalog
> type: migration
> agent: db-engineer
> 생성: 2026-08-19

## Brief

워크플로우 편집기 로드맵(정본 `~/.claude/plans/cozy-hatching-otter.md` §PR 분해) 10 PR 중 **2번**.
선행 PR 1(#391 — ADR 4건 + FR-WF-04~07 등재)은 머지 완료.

FR — FR-WF-04(워크플로우 CRUD) · FR-WF-05(전환 ID·다중·전역 전환) · FR-WF-06(전환 규칙 편집) ·
FR-WF-07(Draft/Publish + 상태 이관)의 **DB 토대**. 이 PR 자체는 API 를 추가하지 않는다.

산출물 4종.
- `V203__add_global_status_catalog.sql` — `statuses` · `workflow_statuses` 신설
- `V204__backfill_status_catalog.sql` — `workflow_states` → `statuses` 승격 + `workflow_statuses` 채움.
  키당 `(name, category)` 유일성 가드, 위반 시 `RAISE EXCEPTION`
- `V205__workflows_add_version_origin.sql` — `workflows` 에 `version`·`origin`·`deleted_at`·`is_locked` 추가
- `YamlSeedService.kt` 개편 — `ApplicationReadyEvent` 유지, **해당 key 의 workflow 행이 없을 때만** 삽입.
  `isDirty()` 비교·`deleteWorkflow`→재삽입 경로 제거. 삽입 시 `origin='SEED'`.
  YAML 원본은 「기본값 복원」 소스로 **유지**(삭제 금지)

불변 계약 — `GET /api/v1/workflows/{key}` 응답 형태를 바꾸지 않는다(`{key,name,description,states[],transitions[]}`).
내부만 `statuses` + `workflow_statuses` 2단 join 으로 교체하고, 새 필드(`transitions[].id`·`kind`)는 **추가**만 한다.
`apps/web` 무손상이 이 PR 의 조건이다.

classify 결과 — type=migration · agent=db-engineer · tier=T3(Maxi 지정 · 판정 규칙 ②) · primary_bc=null(설계상
migration 타입은 BC 무관 반환) · 실제 BC 는 **project-workflow** 단일.

## 도메인 정리

**BC.** `project-workflow` 단일. `classify.primary_bc` 는 `null` 이지만 이는 설계상 `migration` 타입이
BC 무관을 반환하기 때문이다(`classify-task.ts:480`) — 오분류가 아니다. 변경 경로 전량이
`backend/modules/project-workflow/**` 안에 있다.

**영향 엔티티.**

| 엔티티 | 변화 |
|---|---|
| `statuses` (신규) | 사이트 전역 상태 카탈로그. `key` 전역 UNIQUE · `lower(name)` 부분 UNIQUE |
| `workflow_statuses` (신규) | 워크플로우 ↔ 상태 N:M. 워크플로우마다 다른 값(`display_order` · 다이어그램 좌표)을 담는다 |
| `workflows` | 컬럼 4종 추가 — `version` · `origin` · `deleted_at` · `is_locked` |
| `workflow_states` | **유지.** 백필 원본이자 `workflow_transitions` FK 의 참조 대상. DROP 은 로드맵 마지막 PR |
| `YamlSeedService` | 「변경 감지 시 삭제 후 재삽입」 → 「없을 때만 삽입」 |

**새 용어.** 없다. 「상태(Status)」·「워크플로우」·「전환」은 `glossary.md §워크플로우 / 자동화` 에 이미
있고, 이 PR 은 그 용어의 **저장 위치**만 바꾼다. `glossary.md` 갱신 불필요.

**기존 결정 충돌.** 없다. 이 PR 은 2026-08-18 ADR 2건을 **구현**한다.

- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — D1(`statuses` 신설) · D2(`workflow_statuses` N:M) ·
  D3(키 불변) · D4(백필 유일성 가드 + red 1회) · D5(`workflow_states` 즉시 DROP 금지)
- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` — D2(YAML 은 빈 DB 부트스트랩 전용) ·
  D3(표준 4종도 편집 허용 · `origin='SEED'` 표기)
- 참조. `docs/adr/2026-05-26-bc-migration-prefix-policy.md`(V200~V299 대역) · `DATA.md §4`(3단 분할) · `§7`(FK 인덱스)

**ADR 정정 1건.** `-global-status-catalog.md` §영향이 「`src/generated/jooq/` 는 git 커밋 대상」이라고
적었으나 `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외한다(실측 — `git ls-files '*/src/generated/*'`
= 1파일, issue-tracking `.editorconfig` 뿐). 이 PR 에서 해당 문단을 1줄 정정한다.

**신규 도메인 개념 없음** → `grill-with-docs` 미호출.

## 스펙

정본. [`docs/specs/2026-08-19-migration-project-workflow-global-status-catalog.md`](../specs/2026-08-19-migration-project-workflow-global-status-catalog.md)

핵심 시나리오 3줄.

1. 운영자가 DB 에서 워크플로우를 고치고 **재기동해도 고친 값이 살아 있다** (종전에는 시드가 되돌렸다).
2. 배포된 DB 는 V204 백필로, 빈 DB 는 시드의 이중 기록으로 **같은 카탈로그**(`statuses` 12행 ·
   `workflow_statuses` 17행)에 도달한다.
3. 상태 키가 이름·카테고리와 1:1 이 아니면 V204 가 `RAISE EXCEPTION` 으로 **배포를 멈춘다**.

**Maxi 확정 D1 (2026-08-19).** 읽기 경로 전환은 이 PR 에서 뺀다(A안). 근거는 실측 — 워크플로우 상태를
원시 SQL 로 직접 심는 테스트가 **32파일**(issue-tracking 21 · project-workflow 10 · 그 외 1)이라, 읽기를
바꾸면 그 픽스처들이 만든 워크플로우가 상태 0개로 읽혀 2개 BC 를 가로지르는 테스트 이주가 「가장
위험한 PR」에 겹친다. 대신 새 테이블이 write-only 가 되는 사각을 막는 **대조 판별식**(스펙 C4)을 필수
산출물로 넣는다. 읽기 전환은 PR 3 에서 픽스처 헬퍼 1개와 함께 한다.

## Sanity Check

**✅ 통과** — 보강 3건(빈 DB 최초 부팅 시 시드 이중 기록 · 전역 키 재사용 시 이름 보존 · write-only
사각을 막는 대조 판별식)을 1회 반영했고, 도달 불가 케이스 1건(`lower(name)` 충돌)은 가짜 그린을 피해
**의도적으로 테스트를 만들지 않고** PR 3 로 넘겼다. Maxi 결정 필요 항목은 D1 하나였고 확정됐다.
상세는 스펙 §Sanity Check.

## Plan

전 task 공통. **RED 는 저절로 나지 않는다** — Flyway `locations` 가 recursive 스캔이라 파일을 BC 폴더에
넣는 것만으로는 아무 테스트도 빨개지지 않는다(learnings 2026-05-28). 각 task 의 RED 는 테스트가
**명시적으로** 만든다.

### Task 1. V203 — `statuses` · `workflow_statuses` 신설

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V203__add_global_status_catalog.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203MigrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203MigrationTest.kt` (신규 · `V200MigrationTest.kt` 패턴 그대로 — 이미지 `quay.io/tembo/pg16-pgmq:latest`, `target("203")`)
- 테스트: 테이블 2종 존재 · `statuses.key` UNIQUE · `uq_statuses_lower_name` 부분 UNIQUE(`deleted_at IS NULL`) ·
  `category` CHECK 3종 · `workflow_statuses` FK 2개의 삭제 규칙(`CASCADE` / `RESTRICT`) ·
  `UNIQUE(workflow_id, status_id)` · FK 인덱스 2개 · 전 타임스탬프 `timestamptz` (N5)
- 실패 메시지 (예상): `relation "statuses" does not exist` — V203 파일 부재

**GREEN**:
- 파일: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V203__add_global_status_catalog.sql`
- 스펙 §데이터 모델 변경 V203 DDL 그대로. `layout_x`/`layout_y` 는 `REAL` NULL

**REFACTOR**:
- `COMMENT ON TABLE/COLUMN` 을 V200 서식대로 부착(이 저장소의 마이그레이션 관례)

**검증**: `./gradlew :modules:project-workflow:test --tests '*V203MigrationTest'`

### Task 2. V204 — 백필 + 유일성 가드

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V204__backfill_status_catalog.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V204BackfillMigrationTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V204BackfillMigrationTest.kt` (신규)
- 테스트 2종.
  - ① V203 까지 적용 → 시드 4종 모양의 `workflow_states` 17행을 심는다 → V204 적용
    → `statuses` 12행 · `workflow_statuses` 17행 · 키별 `(name, category)` 일치 · `display_order` 보존
  - ② 같은 `key` 에 다른 `(name, category)` 2행을 심는다 → V204 적용 → `RAISE EXCEPTION`
    (예외 메시지에 위반 키 문자열이 담긴다)
- 실패 메시지 (예상): ① `statuses` 0행 ② 예외 없이 통과 — 둘 다 V204 부재 탓
- **근거.** ADR D4 는 「가드가 실제로 동작하는지 일부러 위반 데이터로 red 를 1회 확인」을 요구한다.
  위반을 넣어보지 않은 가드는 장식이다 (`[[invariant-satisfied-by-helptext-not-logic]]`)

**GREEN**:
- 파일: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V204__backfill_status_catalog.sql`
- 순서 ① 가드(`key` 로 묶어 `(name, category)` 유일 조합이 2개 이상이면 `RAISE EXCEPTION`, 메시지에 위반 키·상충 조합)
  → ② `statuses` 승격(`is_system=FALSE`) → ③ `workflow_statuses` 채움(`display_order` 이전)
- **INSERT only.** 기존 행 UPDATE·DELETE 0 (N1). `ON CONFLICT DO NOTHING` 으로 멱등 (E8)

**REFACTOR**:
- 가드를 `DO $$ … $$` 블록으로 묶고 주석에 「왜 실패시키는가」를 1문단으로 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*V204BackfillMigrationTest'`

### Task 3. V205 — `workflows` 컬럼 4종 + `origin='SEED'` 표기

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V205__workflows_add_version_origin.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V205MigrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V205MigrationTest.kt` (신규)
- 테스트: 컬럼 4종 존재 + 기본값(`version=0` · `origin='CUSTOM'` · `is_locked=false` · `deleted_at` NULL 허용) ·
  `ck_workflows_origin` CHECK 가 `'OTHER'` 삽입을 거부 · 표준 4키를 심어 두면 V205 적용 후 `origin='SEED'`,
  그 밖 키는 `'CUSTOM'` 유지
- 실패 메시지 (예상): `column "origin" does not exist`

**GREEN**:
- 파일: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V205__workflows_add_version_origin.sql`
- `ALTER TABLE … ADD COLUMN … DEFAULT` 4종 + CHECK + 표준 4키 `UPDATE` (스펙 V205 DDL 그대로)

**REFACTOR**:
- `COMMENT ON COLUMN` 4개 부착 — 특히 `origin` 에 「기본값 복원 대상 식별」 근거를 적는다

**검증**: `./gradlew :modules:project-workflow:test --tests '*V205MigrationTest'`

### Task 4. `YamlSeedService` — 「없을 때만 삽입」으로 축소

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`]
- depends-on: [3]

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt` (기존 파일에 추가)
- 테스트:
  - `재기동해도 DB 에서 고친 워크플로우 이름이 유지된다` — `seedAll()` 1회 → `workflows.name` 을
    「우리 개발 워크플로우」로 UPDATE → `seedAll()` 재호출 → 이름이 그대로다
  - `삽입되는 워크플로우의 origin 은 SEED 다`
- 실패 메시지 (예상): 첫 테스트가 `expected '우리 개발 워크플로우' but was '소프트웨어 개발 기본 워크플로우'`
  — 현재 `isDirty()` 가 이름 차이를 감지해 `deleteWorkflow` → 재삽입한다

**GREEN**:
- 파일: `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`
- `applyIfChanged` → 「`findByKey` 가 null 일 때만 `insertWorkflow`」로 축소. 삽입 시 `origin='SEED'`
- 제거 대상. `isDirty` · `differsInName` · `differsInStateSet` · `differsInStateDetails` ·
  `differsInTransitions` · `differsInValidators` · `fetchValidatorTypesByTransition` ·
  `fetchTransitionStateKeys` · `deleteWorkflow` · `detachMappingsByWorkflowId`/`reinsertMappings` 호출
- **유지.** `mappingRepository.repairDefaultMappings()`(빈 DB 백필 · 루프 밖 1회) · `seedSingle` ·
  Konform 검증 · validator/postAction dry-run · fail-fast 부팅 차단(N7) · YAML 원본 파일

**REFACTOR**:
- 파일 헤더 1줄 주석과 클래스 KDoc 의 「dirty-diff 비교 후 재적재」 서술을 새 정책으로 교체
- 제거로 고아가 된 import 만 정리한다
- **주의.** `SchemeIssueTypeMappingRepository` 주입 자체는 남긴다 — `repairDefaultMappings` 가 계속 쓴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*YamlSeedServiceTest'`

### Task 5. 시드 이중 기록 — `statuses` · `workflow_statuses` 동시 적재

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogIntegrationTest.kt`]
- depends-on: [1, 4]

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogIntegrationTest.kt` (신규)
- 테스트 3종.
  - ① 빈 DB 에서 `seedAll()` → `statuses` 12행 · `workflow_statuses` 17행 (E1 · C2 와 같은 기대값)
  - ② `statuses` 에 `key='in_progress', name='진행 중'` 을 미리 넣고 `seedAll()` → 이름이 「진행 중」 그대로다
    (F9 · E3 — 운영자가 바꾼 이름을 시드가 덮지 않는다)
  - ③ `seedAll()` 2회 호출해도 `workflow_statuses` 가 17행 그대로다 (멱등 · E4)
- 실패 메시지 (예상): ① `statuses` 0행 — 시드가 아직 `workflow_states` 에만 쓴다

**GREEN**:
- 파일: `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`
- `insertWorkflow` 의 상태 루프 1회에서 **둘 다** 기록한다.
  ① `workflow_states` (기존 — `workflow_transitions` FK 가 아직 이 테이블을 참조한다)
  ② `statuses` `ON CONFLICT (key) DO NOTHING` 후 id 조회 → `workflow_statuses`
  `ON CONFLICT (workflow_id, status_id) DO NOTHING`
- 같은 `@Transactional` 경계 안이라 실패 시 전체 롤백 (F8)

**REFACTOR**:
- 상태 1건 적재를 `insertStatusForWorkflow(workflowId, state)` 로 뽑아 두 기록이 **한 자리**에 남게 한다
  — 갈라지면 정확히 C4 판별식이 잡으려는 drift 가 된다

**검증**: `./gradlew :modules:project-workflow:test --tests '*SeedStatusCatalogIntegrationTest'`
(새 테이블 접근은 jOOQ 생성물이 필요하나 `compileKotlin` 이 `generateJooq` 에 `dependsOn` 걸려 있어 자동 재생성된다.
`src/generated/jooq/` 는 `.gitignore` 대상이라 **커밋하지 않는다**)

### Task 6. C4 대조 판별식 — 두 서랍의 상태 집합이 갈라지면 red

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/StatusCatalogParityTest.kt`]
- depends-on: [5]

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/StatusCatalogParityTest.kt` (신규)
- 테스트: `seedAll()` 직후 **모든 워크플로우**에 대해 `workflow_states` 의 키 집합과
  `workflow_statuses ⨝ statuses` 의 키 집합이 같고 `(name, category, display_order)` 도 일치한다.
  워크플로우 키·건수를 하드코딩하지 않고 **전수 열거**로 센다(「N건」은 눈가리개다)
- **뮤테이션 red 1회.** GREEN 확인 후 `insertStatusForWorkflow` 의 ② 경로를 일부러 끊어 이 테스트가
  빨개지는 것을 본다. 원복 뒤 `git status` 를 **눈으로** 확인한다 (`[[bts-git-add-path-base-in-worktree]]`)
- **근거.** D1 로 읽기 전환을 미뤄 새 테이블이 이 PR 동안 write-only 다. 아무도 안 읽는 데이터는
  틀려도 조용하다 (`[[two-lists-never-check-each-other]]`) — 이 판별식이 그 사각을 막는 유일한 장치다

**GREEN**: Task 5 가 이미 통과시킨다. 이 task 의 산출물은 **판별식 자체**다

**REFACTOR**:
- 뮤테이션 절차(무엇을 끊어 red 를 봤는지)를 테스트 파일 상단 주석에 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*StatusCatalogParityTest'`

### Task 7. ADR 정정 — jOOQ 생성물은 커밋 대상이 아니다

**메타**.
- agent: `db-engineer`
- files: [`docs/adr/2026-08-18-workflow-global-status-catalog.md`]
- depends-on: []

**RED**: 없음 — 문서 1줄 정정. 이 저장소의 문서 task 는 red 를 만들지 않는다

**GREEN**:
- §영향 / 부정·위험의 「`src/generated/jooq/` 는 git 커밋 대상이라 마이그레이션마다 …후 커밋한다」를
  실측대로 고친다. 근거 — `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외하고,
  `git ls-files '*/src/generated/*'` 는 1파일(issue-tracking `.editorconfig`)뿐이다.
  대체 문구는 「빌드마다 `generateJooq` 로 재생성되며 커밋 대상이 아니다」

**REFACTOR**: 없음

**검증**: `node scripts/build-doc-index.mjs --check` PASS · `bash scripts/verify-master-plan.sh` EXIT 0

## Plan 메타

- **task 수** 7 · **예상 wave 4** — W1 `[1, 3, 7]` · W2 `[2, 4]` · W3 `[5]` · W4 `[6]`
  (Task 4·5 는 `YamlSeedService.kt` 를 공유해 파일 겹침으로 자동 직렬화된다)
- **구현 규율** TDD red-first. T3 이므로 `test:` → `feat:`/`fix:` 커밋 순서가 대조된다.
  Task 7 만 문서라 red 가 없고, 그 사실을 위에 명시했다
- **추가 검증** (전 task 통과 후 1회)
  - `./gradlew :modules:project-workflow:test :modules:issue-tracking:test ktlintCheck detekt` (backend/ 에서)
  - `./gradlew :modules:app:test` — 실제 Postgres(5433) 필요. `docker-compose -f infra/docker-compose.dev.yml up -d postgres` 선행
  - `pnpm --filter web test` — `apps/web` 0파일 변경 확인 겸용
  - `bash scripts/verify-master-plan.sh` (EXIT 4 면 차단) · `node scripts/build-doc-index.mjs --check`
  - `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
    (worktree 에서 `pnpm test:workflow` 는 심볼릭 `node_modules` 때문에 죽는다)
- **범위 밖 (D1)** 읽기 경로 3파일(`WorkflowRepository` · `DefaultWorkflowDefinitionRepository` ·
  `PostActionTransitionResolver`)과 `workflow_states` DROP 은 이 PR 이 건드리지 않는다

## 리뷰 결과 (← /bts-review-plan 채움)
