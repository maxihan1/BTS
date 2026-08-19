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

> **게이트 1 승인 반영 (2026-08-19).** 리뷰 처방 R1~R8 을 전부 흡수했다. task 7 → **8**,
> 마이그레이션 테스트는 클래스 1개로 합쳤다(R4). 변경분은 각 task 에 `[R*]` 로 표시했다.

전 task 공통. **RED 는 저절로 나지 않는다** — Flyway `locations` 가 recursive 스캔이라 파일을 BC 폴더에
넣는 것만으로는 아무 테스트도 빨개지지 않는다(learnings 2026-05-28). 각 task 의 RED 는 테스트가
**명시적으로** 만든다.

### Task 1. V203 — `statuses` · `workflow_statuses` 신설

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V203__add_global_status_catalog.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203ToV205MigrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203ToV205MigrationTest.kt` (신규 · `V200MigrationTest.kt` 패턴 · 이미지
  `quay.io/tembo/pg16-pgmq:latest`). **[R4] Task 1~3 이 이 한 클래스를 공유한다** — 컨테이너 1회 기동으로
  V200~V205 체인을 `target("205")` 로 한 번에 적용하고 스펙 C5 를 겸한다
- 테스트: 테이블 2종 존재 · `statuses.key` UNIQUE · `uq_statuses_lower_name` 부분 UNIQUE(`deleted_at IS NULL`) ·
  `category` CHECK 3종 · `workflow_statuses` FK 2개의 삭제 규칙(`CASCADE` / `RESTRICT`) ·
  `UNIQUE(workflow_id, status_id)` · FK 인덱스 2개 · 전 타임스탬프 `timestamptz` (N5)
- 실패 메시지 (예상): `relation "statuses" does not exist`

**GREEN**: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V203__add_global_status_catalog.sql` — 스펙 §데이터 모델 변경 V203 DDL 그대로

**REFACTOR**: `COMMENT ON TABLE/COLUMN` 을 V200 서식대로 부착

**검증**: `./gradlew :modules:project-workflow:test --tests '*V203ToV205MigrationTest'`

### Task 2. V204 — 백필 + 유일성 가드 3종

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V204__backfill_status_catalog.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203ToV205MigrationTest.kt`]
- depends-on: [1]

**RED** — 같은 클래스에 테스트 4종을 더한다.
- ① 시드 4종 모양의 `workflow_states` 17행을 심고 V204 적용 → `statuses` 12행 · `workflow_statuses` 17행 ·
  키별 `(name, category)` 일치 · `display_order` 보존
- ② 같은 `key` 에 다른 `(name, category)` 2행 → `RAISE EXCEPTION` (메시지에 위반 키)
- ③ **[R2] 역방향** — 다른 `key` 2개가 같은 `lower(name)` (예: `done`/Done · `complete`/Done)
  → `RAISE EXCEPTION` (메시지에 이름 + 충돌 키 목록). 이게 없으면 `uq_statuses_lower_name` 이
  원시 PG 오류로 배포를 멈추고 **원인을 말해주지 않는다**
- ④ **[R5] 길이** — 51자 `key` 를 심고 V204 → `RAISE EXCEPTION` (`VARCHAR(50)` 초과를 사유와 함께).
  `workflow_states.key` 는 `TEXT`(V200:23)라 폭이 좁아지는 구간이다
- **근거.** ADR D4 는 「가드가 실제로 동작하는지 일부러 위반 데이터로 red 1회 확인」을 요구한다.
  위반을 넣어보지 않은 가드는 장식이다 (`[[invariant-satisfied-by-helptext-not-logic]]`)

**GREEN**: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V204__backfill_status_catalog.sql`
- 순서 ① 가드 3종(키→이름·카테고리 1:1 · 이름→키 역방향 · 키 길이) → ② `statuses` 승격(`is_system=FALSE`)
  → ③ `workflow_statuses` 채움(`display_order` 이전)
- **INSERT only.** 기존 행 UPDATE·DELETE 0 (N1) · `ON CONFLICT DO NOTHING` 으로 멱등 (E8)

**REFACTOR**: 가드 3종을 `DO $$ … $$` 한 블록으로 묶고 「왜 실패시키는가」를 주석 1문단으로 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*V203ToV205MigrationTest'`

### Task 3. V205 — `workflows` 컬럼 4종 + `origin='SEED'` 표기

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V205__workflows_add_version_origin.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V203ToV205MigrationTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/StandardWorkflowKeyParityTest.kt`]
- depends-on: [1]

**RED**:
- 마이그레이션 클래스에 추가 — 컬럼 4종 존재 + 기본값(`version=0` · `origin='CUSTOM'` · `is_locked=false` ·
  `deleted_at` NULL 허용) · `ck_workflows_origin` 이 `'OTHER'` 를 거부 · 표준 4키는 `origin='SEED'`,
  그 밖 키는 `'CUSTOM'` 유지
- **[R3]** `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/StandardWorkflowKeyParityTest.kt` (신규 · 컨테이너 불필요) — V205 SQL 텍스트에서
  키 목록을 파싱해 `YamlSeedService.standardWorkflowKeys`(`YamlSeedService.kt:194`)와 **집합 비교**.
  차집합이 있으면 red. 두 목록이 서로를 안 보면 5번째 표준 워크플로우에서 조용히 갈라진다
  (`[[two-lists-never-check-each-other]]`)
- 실패 메시지 (예상): `column "origin" does not exist`

**GREEN**: `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V205__workflows_add_version_origin.sql` — 스펙 V205 DDL 그대로

**REFACTOR**: `COMMENT ON COLUMN` 4개. `origin` 에는 「기본값 복원 대상 식별」 근거를 적는다

**검증**: `./gradlew :modules:project-workflow:test --tests '*V203ToV205MigrationTest' --tests '*StandardWorkflowKeyParityTest'`

### Task 4. `YamlSeedService` — 「없을 때만 삽입」으로 축소 + 구 계약 테스트 반전

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedValidatorPostActionTest.kt`]
- depends-on: [3]

**RED**:
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt` — 「`seedAll()` 1회 → `workflows.name` 을 「우리 개발 워크플로우」로
  UPDATE → `seedAll()` 재호출 → 이름 유지」 · 「삽입되는 워크플로우의 `origin` 은 `SEED` 다」
- **[R1] 구 계약 테스트 2건을 삭제하지 않고 뒤집는다.** 지금 이 둘은 **없앨 동작을 단언**한다.
  - `YamlSeedServiceTest.kt:312` `YAML 변경 시 dirty diff 감지 후 재적재한다` (시나리오 3)
  - `YamlSeedValidatorPostActionTest.kt:277` `validator 가 변경된 YAML 재시드 시 isDirty 가 true 여서 재적재가 발생한다` (시나리오 8)
  → 각각 「YAML 을 바꿔도 **기존 DB 는 그대로다**」로 반전한다. 삭제하면 이 PR 이 바꾼 동작의 증인이 0이 된다
- 실패 메시지 (예상): 첫 테스트가 `expected '우리 개발 워크플로우' but was '소프트웨어 개발 기본 워크플로우'`

**GREEN**: `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`
- `applyIfChanged` → 「`findByKey` 가 null 일 때만 `insertWorkflow`」. 삽입 시 `origin='SEED'`
- 제거. `isDirty` · `differsInName` · `differsInStateSet` · `differsInStateDetails` ·
  `differsInTransitions` · `differsInValidators` · `fetchValidatorTypesByTransition` ·
  `fetchTransitionStateKeys` · `deleteWorkflow` · `detachMappingsByWorkflowId`/`reinsertMappings` 호출
- **유지.** `mappingRepository.repairDefaultMappings()` · `seedSingle` · Konform · dry-run · fail-fast(N7) · YAML 원본

**REFACTOR**: 파일 헤더 1줄 주석과 클래스 KDoc 의 「dirty-diff 후 재적재」 서술 교체 · 고아 import 만 정리 ·
`SchemeIssueTypeMappingRepository` 주입은 **남긴다**(`repairDefaultMappings` 가 쓴다)

**검증**: `./gradlew :modules:project-workflow:test --tests '*YamlSeedServiceTest' --tests '*YamlSeedValidatorPostActionTest'`

### Task 5. 시드 이중 기록 — `statuses` · `workflow_statuses` 동시 적재

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogIntegrationTest.kt`]
- depends-on: [1, 4]

**RED** — `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogIntegrationTest.kt` (신규)
- ① 빈 DB 에서 `seedAll()` → `statuses` 12행 · `workflow_statuses` 17행 (E1)
- ② `statuses` 에 `key='in_progress', name='진행 중'` 을 미리 넣고 `seedAll()` → 이름이 「진행 중」 그대로 (F9·E3)
- ③ `seedAll()` 2회 호출해도 `workflow_statuses` 17행 유지 (멱등 · E4)
- 실패 메시지 (예상): ① `statuses` 0행

**GREEN**: `insertWorkflow` 의 상태 루프 1회에서 둘 다 기록
- ① `workflow_states` (기존 — `workflow_transitions` FK 가 아직 참조한다)
- ② `statuses` `ON CONFLICT (key) DO NOTHING` → id 조회 → `workflow_statuses`
  `ON CONFLICT (workflow_id, status_id) DO NOTHING`
- 같은 `@Transactional` 경계 → 실패 시 전체 롤백 (F8)
- **[R8]** 적재 완료 `log.info` 에 `statuses`·`workflow_statuses` 건수를 더한다. 부팅 로그만 보고
  카탈로그가 채워졌는지 알 수 있어야 한다

**REFACTOR**: 상태 1건 적재를 `insertStatusForWorkflow(workflowId, state)` 로 뽑아 두 기록을 **한 자리**에 둔다

**검증**: `./gradlew :modules:project-workflow:test --tests '*SeedStatusCatalogIntegrationTest'`

### Task 6. **[R6]** 카탈로그 보정 경로 — 롤백이 비운 매핑을 되채운다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogRepairTest.kt`]
- depends-on: [5]

**RED** — `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/SeedStatusCatalogRepairTest.kt` (신규)
- `seedAll()` 로 채운 뒤 `workflow_statuses` 를 전량 DELETE(구 코드로 롤백했다가 롤포워드한 상태를 재현)
  → `seedAll()` 재호출 → **17행이 되채워진다**
- 실패 메시지 (예상): 0행 유지 — 새 시드는 「workflow 행이 있으면 삽입 안 함」이라 영영 안 채운다

**GREEN**: `seedAll()` 말미(루프 밖 1회)에 `repairStatusCatalog()` 호출
- workflow 행은 있는데 그 워크플로우의 `workflow_statuses` 가 비었으면 `workflow_states` 기준으로 채운다
- **선례를 그대로 따른다** — `YamlSeedService.kt:230` 의 `mappingRepository.repairDefaultMappings()` 가
  같은 자리에서 같은 이유(유실 보정)로 이미 돌고 있다. 새 패턴이 아니다

**REFACTOR**: 두 보정 호출을 나란히 두고 「왜 루프 밖 1회인가」를 주석 1줄로 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*SeedStatusCatalogRepairTest'`

### Task 7. C4 대조 판별식 — 두 서랍의 상태 집합이 갈라지면 red

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/StatusCatalogParityTest.kt`]
- depends-on: [6]

**RED**:
- `seedAll()` 직후 **모든 워크플로우**에 대해 `workflow_states` 의 키 집합과 `workflow_statuses ⨝ statuses` 의
  키 집합이 같고 `(name, category, display_order)` 도 일치한다. 워크플로우 키·건수를 하드코딩하지 않고
  **전수 열거**로 센다(「N건」은 눈가리개다)
- **뮤테이션 red 1회.** GREEN 확인 후 `insertStatusForWorkflow` 의 ② 경로를 일부러 끊어 빨개지는 것을 본다.
  원복 뒤 `git status` 를 **눈으로** 확인한다 (`[[bts-git-add-path-base-in-worktree]]`)
- **근거.** D1 로 읽기 전환을 미뤄 새 테이블이 이 PR 동안 write-only 다. 아무도 안 읽는 데이터는 틀려도
  조용하다 (`[[two-lists-never-check-each-other]]`)

**GREEN**: Task 5·6 이 이미 통과시킨다. 이 task 의 산출물은 **판별식 자체**다

**REFACTOR**: 뮤테이션 절차(무엇을 끊어 red 를 봤는지)를 파일 상단 주석에 남긴다

**검증**: `./gradlew :modules:project-workflow:test --tests '*StatusCatalogParityTest'`

### Task 8. 문서 — ADR 정정 + 운영 공백 고지

**메타**.
- agent: `db-engineer`
- files: [`docs/adr/2026-08-18-workflow-global-status-catalog.md`,
  `docs/specs/2026-08-19-migration-project-workflow-global-status-catalog.md`]
- depends-on: []

**RED**: 없음 — 문서. 이 저장소의 문서 task 는 red 를 만들지 않는다

**GREEN**:
- ADR §영향의 「`src/generated/jooq/` 는 git 커밋 대상이라 …후 커밋한다」를 실측대로 고친다.
  근거 — `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외하고 `git ls-files '*/src/generated/*'` 는
  1파일(issue-tracking `.editorconfig`)뿐이다. 대체 문구는 「빌드마다 `generateJooq` 로 재생성되며 커밋 대상이 아니다」
- **[R7]** 스펙 §제약 조건에 운영 공백을 명시한다 — 「이 PR 이후 시드 YAML 편집은 **빈 DB 최초 부팅에만**
  효력이 있다. 기존 DB 반영 수단은 로드맵 PR 8~10 의 편집 UI 이며, 그 전에는 직접 SQL 뿐이다.」
  같은 문장을 PR 본문에도 싣는다

**REFACTOR**: 없음

**검증**: `node scripts/build-doc-index.mjs --check` PASS · `bash scripts/verify-master-plan.sh` EXIT 0

## Plan 메타

- **task 수** 8 · **예상 wave 4** — W1 `[1, 8]` · W2 `[2, 3]` · W3 `[4]` → `[5]` → `[6]` · W4 `[7]`
  (Task 4·5·6 은 `YamlSeedService.kt` 를 공유해 파일 겹침으로 자동 직렬화된다. Task 1~3 은
  `V203ToV205MigrationTest.kt` 를 공유하므로 Task 2·3 도 Task 1 뒤에서 직렬이다)
- **구현 규율** TDD red-first. T3 이므로 `test:` → `feat:`/`fix:` 커밋 순서가 대조된다.
  Task 8 만 문서라 red 가 없고, 그 사실을 위에 명시했다
- **추가 검증** (전 task 통과 후 1회)
  - `./gradlew :modules:project-workflow:test :modules:issue-tracking:test ktlintCheck detekt` (backend/ 에서)
  - `./gradlew :modules:app:test` — 실제 Postgres(5433) 필요. `docker-compose -f infra/docker-compose.dev.yml up -d postgres` 선행
  - `pnpm --filter web test` — `apps/web` 0파일 변경 확인 겸용
  - `bash scripts/verify-master-plan.sh` (EXIT 4 면 차단) · `node scripts/build-doc-index.mjs --check`
  - `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
    (worktree 에서 `pnpm test:workflow` 는 심볼릭 `node_modules` 때문에 죽는다)
- **범위 밖 (D1)** 읽기 경로 3파일(`WorkflowRepository` · `DefaultWorkflowDefinitionRepository` ·
  `PostActionTransitionResolver`)과 `workflow_states` DROP 은 이 PR 이 건드리지 않는다

## 리뷰 결과

### 렌즈 1 — `/plan-eng-review` (2026-08-19)

**결과. 주의 5건 (P1 2 · P2 2 · P3 1) · BLOCKER 0.** 전 findings 는 근거 줄을 인용해 확인했다.

| # | 심각도 | 확신 | 발견 |
|---|---|---|---|
| F1 | P1 | 9/10 | **기존 테스트 2건이 이 PR 이 없애는 계약을 단언한다** |
| F2 | P1 | 8/10 | **V204 유일성 가드가 한 방향만 본다** |
| F3 | P2 | 9/10 | V205 의 표준 4키 목록이 코드 상수의 사본이다 |
| F4 | P2 | 7/10 | Testcontainers 클래스 4개 = 컨테이너 4회 기동 |
| F5 | P3 | 7/10 | `key` 타입 폭이 좁아진다(TEXT → VARCHAR(50)) |

**F1 — 기존 테스트 2건이 제거될 계약을 단언한다. (REGRESSION RULE 적용 — 선택이 아니다)**

- `backend/modules/project-workflow/src/test/.../seed/YamlSeedServiceTest.kt:312`
  `fun \`YAML 변경 시 dirty diff 감지 후 재적재한다\`()` — 시나리오 3
- `backend/modules/project-workflow/src/test/.../seed/YamlSeedValidatorPostActionTest.kt:277`
  `fun \`validator 가 변경된 YAML 재시드 시 isDirty 가 true 여서 재적재가 발생한다\`()` — 시나리오 8

Task 4 가 `isDirty` 를 제거하면 이 둘은 **반드시 red 가 된다.** 계획은 이 사실을 적지 않았다.
그대로 두면 구현 중에 「모르는 실패」로 만나고, 급히 지우면 **새 계약을 지키는 테스트가 0** 이 된다.

→ **처방.** Task 4 의 files 에 두 파일을 넣고, 두 테스트를 **뒤집어** 새 계약의 회귀 테스트로 만든다
(「YAML 을 바꿔도 기존 DB 는 그대로다」). 삭제가 아니라 뒤집는 것이다 — 이 PR 이 바꾼 동작의 유일한 증인이다.

**F2 — V204 가드가 `key → (name, category)` 만 보고 `lower(name) → key` 는 안 본다.**

ADR D1 이 `statuses` 에 `UNIQUE INDEX on lower(name) WHERE deleted_at IS NULL` 을 요구한다.
서로 **다른 키**가 같은 이름을 갖는 데이터(예: `done`/Done 과 `complete`/Done)에서는 계획의 가드가
통과하고, 그 다음 `INSERT` 가 인덱스에 걸려 `duplicate key value violates unique constraint
"uq_statuses_lower_name"` 로 죽는다. **배포가 멈추는 건 같은데 메시지가 원인을 말해주지 않는다.**

시드 4종에는 이런 데이터가 없다(이름 12개 전부 다름 — 실측). 하지만 ADR 은 「마이그레이션은 이 실측을
가정하지 않는다」를 명문화했고, 그 정신은 두 방향 모두에 적용된다.

→ **처방.** V204 가드에 2번째 검사를 넣는다 — `lower(name)` 으로 묶어 서로 다른 `key` 가 2개 이상이면
`RAISE EXCEPTION`, 메시지에 이름과 충돌 키 목록. Task 2 의 RED 에 이 케이스를 1건 추가한다(도달 가능하다 —
테스트가 데이터를 심으면 된다).

**F3 — V205 의 `WHERE key IN ('software-default','bug-tracking','simple','kanban-basic')` 는 사본이다.**

정본은 `backend/modules/project-workflow/src/main/.../seed/YamlSeedService.kt:194`
`private val standardWorkflowKeys = listOf(...)` 다. 두 목록은 서로를 검사하지 않는다 — 5번째 표준
워크플로우가 생기면 마이그레이션만 조용히 뒤처지고 `origin` 이 `CUSTOM` 으로 남아 「기본값 복원」 대상에서
빠진다. 저장소가 반복해 물린 양식이다 (`[[two-lists-never-check-each-other]]`).

→ **처방.** Task 3 에 대조 단언 1개 — V205 SQL 텍스트에서 키 목록을 파싱해 `standardWorkflowKeys` 와
집합 비교. 차집합이 있으면 red.

**F4 — 마이그레이션 테스트 클래스가 4개면 Testcontainers 가 4번 뜬다.**

`V200MigrationTest` 는 `@Container @JvmStatic` 로 클래스당 컨테이너 1개다. 계획대로면 V203·V204·V205·
체인(C5) 이 각각 컨테이너를 띄운다. 착수 시 러너 실측이 **load 3.90배**(`verify-runner-health.sh`)였다.

→ **처방.** Task 1~3 의 검증을 **한 클래스**(`V203ToV205MigrationTest`)로 합치고 `target("205")` 로
체인을 한 번에 적용한다. C5(체인 적용)가 별도 산출물 없이 충족된다. 컨테이너 4회 → 1회.

**F5 — `workflow_states.key` 는 `TEXT`(V200:23), 새 `statuses.key` 는 `VARCHAR(50)`.**

폭이 좁아지므로 50자를 넘는 키가 있으면 백필이 `value too long for type character varying(50)` 로 죽는다.
현재 데이터에는 없지만(최장 `in_progress` 11자) F2 와 같은 이유로 가정하지 않는다.

→ **처방.** V204 가드에 길이 검사를 얹어 사유를 말하게 한다. `VARCHAR(50)` 자체는 ADR·소비자
(`issues.current_state_key VARCHAR(50)`)와 맞으므로 **바꾸지 않는다.**

#### 테스트 커버리지 지도

```
코드 경로                                          커버리지
[+] V203 (DDL)
  └── 테이블·제약·인덱스·타입                      [★★★] Task 1 RED
[+] V204 (백필)
  ├── 정상 백필 12/17행                            [★★★] Task 2 RED ①
  ├── key→(name,category) 위반                     [★★★] Task 2 RED ② (가드 red 확인)
  ├── lower(name) 역방향 충돌                      [GAP → F2]  ← 처방 반영 시 해소
  └── key 길이 초과                                [GAP → F5]  ← 처방 반영 시 해소
[+] V205 (컬럼 + origin)
  ├── 컬럼·기본값·CHECK                            [★★★] Task 3 RED
  └── 표준키 목록 사본 정합                        [GAP → F3]  ← 처방 반영 시 해소
[+] YamlSeedService
  ├── 없을 때만 삽입 (재기동 보존)                 [★★★] Task 4 RED
  ├── origin='SEED'                                [★★ ] Task 4 RED
  ├── 이중 기록 12/17행                            [★★★] Task 5 RED ①
  ├── 기존 전역 상태 이름 보존                     [★★★] Task 5 RED ②
  ├── 멱등 (2회 호출)                              [★★★] Task 5 RED ③
  ├── 두 서랍 대조 + 뮤테이션                      [★★★] Task 6
  └── 구 계약 테스트 2건 반전                      [GAP → F1]  ← REGRESSION, 필수
[·] 읽기 경로 3파일                                 무변경 (D1) — 기존 테스트가 그대로 지킨다

커버리지 12/16 (75%) → 처방 4건 반영 시 16/16
```

#### 실패 양식 (프로덕션에서 어떻게 깨지나)

| 경로 | 현실적 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| V204 백필 | 키 이름 충돌 | F2 반영 후 O | `RAISE EXCEPTION` | 배포 중단 + 원인 메시지 |
| V204 백필 | 키 50자 초과 | F5 반영 후 O | 〃 | 〃 |
| 시드 이중 기록 | 한쪽만 기록돼 카탈로그가 갈라짐 | Task 6 | 없음(판별식이 CI 에서 잡음) | 없음 — **조용하다.** 그래서 Task 6 이 필수다 |
| 시드 삽입 | `lower(name)` 인덱스 충돌로 부팅 실패 | 도달 불가(E5) | fail-fast 부팅 차단 | 앱이 안 뜬다 — PR 3 요구사항으로 이월 |

**치명 사각 0건.** 「조용한 실패」 후보 1건(카탈로그 갈라짐)은 Task 6 판별식이 덮는다.

#### 범위 밖으로 확정 (NOT in scope)

- **읽기 경로 2단 join 교체** — Maxi D1. 픽스처 32파일 동반 이주라 PR 3.
- **`workflow_states` DROP** — `DATA.md §4` 3단 분할 · ADR D5. 로드맵 마지막 PR.
- **`workflow_transitions` 재구성(`kind`·다중 전환·`transitionId`)** — PR 4.
- **권한(`WorkflowDefinitionPermission`)** — CRUD 가 없으므로 이 PR 에 지킬 표면이 없다. PR 3.
- **`WorkflowCache.invalidate` 배선** — 쓰기 경로가 이 PR 에 없다. PR 3.
- **`workflow_drafts` · `workflow_publications`** — PR 6.

#### 이미 있는 것 (재사용 확인)

| 자산 | 계획의 처리 |
|---|---|
| `V200MigrationTest` (Testcontainers + Flyway target) | 패턴 그대로 재사용 — **F4 로 클래스 통합 권고** |
| `YamlSeedService` 의 Konform 검증 · dry-run · fail-fast | 유지 (Task 4 가 명시) |
| `mappingRepository.repairDefaultMappings()` | 유지 — 빈 DB 백필은 여전히 필요 |
| `SchemeIssueTypeMappingRepository.detach/reinsert` | **호출만** 제거. 리포지토리는 남는다(PR 3 이 쓸 수 있다) |
| ADR 4건 (#391) | 새 ADR 만들지 않고 구현 — **판단 타당**. 신규 결정이 없다 |

#### 병렬화

| 단계 | 건드리는 모듈 | 의존 |
|---|---|---|
| 마이그레이션 3종 (T1·T2·T3) | `project-workflow/resources/db` · `test/db` | — |
| 시드 (T4·T5·T6) | `project-workflow/main/seed` · `test/seed` | T1·T3 |
| 문서 (T7) | `docs/adr` | — |

레인 A(마이그레이션) · 레인 B(문서)는 병렬. 레인 C(시드)는 A 완료 후. **파일 충돌 0** — 세 레인이 서로 다른
디렉터리다. 단, F4 처방을 받으면 T1~T3 가 한 파일이 되어 레인 A 는 직렬 1건이 된다.

#### 이 리뷰가 계획에 제안하는 변경 (게이트 1 승인 대상)

- [ ] **R1 (P1)** — Task 4 files 에 `YamlSeedValidatorPostActionTest.kt` 추가 + 구 계약 테스트 2건을 **반전**
- [ ] **R2 (P1)** — Task 2 에 `lower(name)` 역방향 가드 + RED 케이스 1건 추가
- [ ] **R3 (P2)** — Task 3 에 V205 키 목록 ↔ `standardWorkflowKeys` 대조 단언 추가
- [ ] **R4 (P2)** — Task 1~3 을 `V203ToV205MigrationTest` 한 클래스로 통합 (컨테이너 4회 → 1회)
- [ ] **R5 (P3)** — V204 가드에 `key` 길이 검사 추가

**절차 편차 2건 (의도적).**
① 이 렌즈는 발견마다 개별 `AskUserQuestion` 을 요구하지만, `/bts` 가 「BLOCKER 는 합산한 뒤 게이트 1 에서
한 번에 판정」을 지시한다 — 프로젝트 절차가 상위라 5건을 게이트 1 로 모았다.
② outside voice(codex)는 `codex_reviews=disabled` 라 건너뛰었다. 규칙상 Claude 서브에이전트 대체도 하지 않는다.
③ QA 테스트 계획 산출물은 쓰지 않았다 — 이 PR 에 UI·수동 QA 표면이 0 이다.


### 렌즈 2 — `/plan-ceo-review` (2026-08-19 · 모드 HOLD SCOPE)

**모드 근거.** 승인된 10 PR 로드맵 안의 마이그레이션이고 범위 결정(D1)이 이미 났다. 확장 제안 0건 —
이 PR 은 토대이지 제품 결정이 아니다. 새 개념 도입도 0(표준 SQL 테이블 2 + 컬럼 4)이라 혁신 토큰을 쓰지 않는다.

**결과. 주의 3건 (P1 1 · P2 1 · P3 1) · BLOCKER 0.**

**C1 [P1] (8/10) — 앱만 롤백하면 상태 카탈로그가 조용히 영구 공백이 된다.**

이 PR 이 배포된 뒤 운영자가 DB 에서 워크플로우를 고치는 것이 **정상 동작**이 된다(그게 이 PR 의 목적이다).
그 상태에서 앱을 이전 버전으로 롤백하면 구 `YamlSeedService` 가 되살아나 다음을 한다.

```
구 코드 applyIfChanged (YamlSeedService.kt:325-327)
   isDirty = true (운영자가 고쳤으니까)
        ↓
   detachMappingsByWorkflowId → deleteWorkflow(key) → insertWorkflow(dto)
        ↓
   workflows 행 삭제 → workflow_statuses 가 FK CASCADE 로 함께 삭제
        ↓
   새 workflow 행 삽입 — 구 코드는 workflow_statuses 를 모른다 → 매핑 0행
        ↓
   [다시 롤포워드] 새 시드는 「workflow 행이 있으면 삽입 안 함」 → 영영 안 채운다
```

`statuses` 전역 행은 남는다(아무도 지우지 않는다). 남는 것은 **매핑만 빈 상태**이고, 이 PR 의 판별식
(Task 6)은 CI 에서만 돌아 **운영 DB 에서는 아무도 눈치채지 못한다.** 조용한 실패다.

→ **처방 (R6).** 시드 말미에 보정 경로를 1개 둔다 — 「workflow 행은 있는데 그 워크플로우의
`workflow_statuses` 가 비었으면 `workflow_states` 기준으로 채운다」. **선례가 이미 있다** —
`YamlSeedService.kt:230` 의 `mappingRepository.repairDefaultMappings()` 가 정확히 같은 자리(루프 밖 1회)에서
같은 이유(빈 DB·유실 보정)로 돈다. 새 패턴이 아니라 있는 패턴을 한 번 더 쓰는 것이다.

**C2 [P2] (9/10) — 운영 계약이 바뀌는데 대체 수단이 8 PR 뒤다.**

지금까지는 「시드 YAML 을 고쳐 배포하면 표준 워크플로우가 바뀐다」가 사실상의 운영 수단이었다.
이 PR 이후 그 경로는 **없어진다**(ADR D2 의 의도). 대체 수단인 편집 UI 는 로드맵 PR 8~10, 즉 8개 뒤다.
그 사이 표준 워크플로우를 바꾸려면 직접 SQL 뿐이다.

기술적으로는 옳은 결정이고 ADR 이 이미 승인했다. 문제는 **아무 데도 안 적혀 있다는 것** — 계획서·스펙·PR 본문
어디에도 이 공백 기간이 없다. 3개월 뒤 누가 YAML 을 고치고 「반영이 안 된다」로 한나절을 태운다.

→ **처방 (R7).** PR 본문과 계획서에 「이 PR 이후 YAML 편집은 빈 DB 최초 부팅에만 효력이 있다.
기존 DB 반영 수단은 PR 8~10 의 편집 UI 이며 그 전에는 직접 SQL」을 명시. 코드 변경 0.

**C3 [P3] (8/10) — 이중 기록의 관측성이 없다.**

기존 시드는 적재 결과를 `log.info("워크플로우 '{}' 적재 완료 — states: {}, transitions: {} …")` 로 남긴다.
새로 채우는 `statuses`/`workflow_statuses` 건수는 로그에 없다. 부팅 로그만 보고 카탈로그가 채워졌는지
알 수 없다.

→ **처방 (R8).** 같은 로그 라인에 `statuses`·`workflow_statuses` 건수를 더한다. 1줄.

#### 범위 판정

| 항목 | 판정 |
|---|---|
| 확장 제안 | **0건.** 토대 PR 이라 제품 표면이 없다 |
| 축소 제안 | **0건.** D1 로 이미 좁혔다 |
| 되돌리기 등급 | **한쪽 문(one-way)에 가깝다** — 마이그레이션은 forward-only 이고 C1 이 그 대가다. R6 가 되돌리기 비용을 크게 낮춘다 |
| 혁신 토큰 | **0개 소모.** 표준 SQL·기존 패턴만 |
| 6개월 뒤 관점 | 이 PR 없이는 편집기 로드맵 전체가 성립하지 않는다. 방향 정합 |

### 게이트 2 (1차) — Maxi 확정 B (2026-08-19)

`/review` 가 BTS 체크리스트의 **CRITICAL 「init_codegen.sql 미러」** 1건을 냈고, Maxi 가 **B(지금 미러 추가)**
를 택했다. `/bts` 규정대로 concerns 를 붙여 [5] 구현 → [6] 재리뷰로 되돌아갔다.

**실측 근거.** `project-workflow` 만 코드젠 입력이 `TC_INITSCRIPT=…/V200__init_workflow.sql` 한 파일이었다.
다른 4개 모듈(issue-tracking · agile-planning · notification · search-export-import)은 전부
`db/codegen/init_codegen.sql` 구조 미러를 쓴다. 그래서 V201 이후 스키마가 jOOQ 생성물에 영영 안 잡혔고,
이 PR 은 원시 SQL 로 우회하고 있었다.

**추가 산출물 4종.**
1. `db/codegen/init_codegen.sql` 신설 — V200 + V203 + V205 구조 미러. V201·V202 는 의도적 제외
   (종전에도 코드젠 밖이고 소비처가 그 전제로 쓰여 있다).
2. `build.gradle.kts` 코드젠 입력을 미러로 교체.
3. **미러 정합 판별식** — `V203ToV205MigrationTest` 의 「codegen 미러가 마이그레이션 스키마와 일치한다」.
   파서를 쓰지 않고 **실제 PostgreSQL 두 곳에 적용해** `information_schema` 로 컬럼·타입을 대조한다.
   뮤테이션(`statuses.is_system` 제거) red 1회 확인 후 원복. 관례상 이 미러는 손으로 유지하는 사본이고
   **지금까지 어떤 판별식도 그것을 검사하지 않았다** — 저장소의 지배 결함 양식이라 같이 닫았다.
4. `YamlSeedService` 의 카탈로그 접근을 원시 SQL → **jOOQ 타입 접근**으로 되돌림
   (`STATUSES` · `WORKFLOW_STATUSES` · `WORKFLOWS.ORIGIN` 상수가 생성됐다).

**부채 등재 1건.** 나머지 4개 모듈은 여전히 미러 대조가 없다 → 부채 `54`(장부 + 마스터 §전수 매핑 동시 등재).
한 PR = 한 BC 규칙상 모듈별 후속 PR 로 나눈다.

