# 전역 상태 카탈로그 + 시드 부트스트랩 전환 — 스펙

> slug: migration-project-workflow-global-status-catalog · type: migration · agent: db-engineer · 티어: T3
> plan: [`docs/plans/2026-08-19-migration-project-workflow-global-status-catalog.md`](../plans/2026-08-19-migration-project-workflow-global-status-catalog.md)
> PR: #392 · 작성 2026-08-19 · 기준 HEAD `ec756a6d0`
> FR: FR-WF-04 (DB 토대) · 선행 ADR `docs/adr/2026-08-18-workflow-global-status-catalog.md` · `-db-as-source-of-truth.md`

## 요약

워크플로우 편집기 로드맵 10 PR 중 **2번**. 상태를 워크플로우 종속에서 **사이트 전역 카탈로그**로
올릴 자리(`statuses` · `workflow_statuses`)를 만들고, 기존 `workflow_states` 를 그 자리로 백필하며,
`YamlSeedService` 가 재기동마다 DB 를 되돌리던 경로를 끊는다.

**이 PR 은 API 를 추가하지 않고 화면도 바꾸지 않는다.** CRUD 는 PR 3, 전환 재구성은 PR 4 다.

### Maxi 확정 2026-08-19 (D1)

**읽기 경로 전환은 이 PR 에서 뺀다 (A안 채택 · B「로드맵대로 이번 PR 에서 전환」· C「전환+폴백」 기각).**

로드맵 §PR 분해 PR 2 는 「내부가 `statuses` + `workflow_statuses` 2단 join 으로 바뀔 뿐」이라고
적었으나, 실측이 그 문장의 비용을 뒤집었다.

| 실측 | 값 |
|---|---|
| 워크플로우 상태를 원시 SQL/jOOQ 로 **직접 심는 테스트 파일** | **32** (issue-tracking 21 · project-workflow 10 · 나머지 1) |
| 그 픽스처가 상태를 만드는 경로 | `INSERT INTO workflow_states …` 직접 |

읽기 경로를 이 PR 에서 바꾸면 위 픽스처가 만든 워크플로우는 **상태 0개**로 읽혀 전환 엔진이
`fromStateKey` 를 찾지 못한다. 즉 「가장 위험한 PR」에 2개 BC 를 가로지르는 테스트 대량 이주가
겹친다. 실패 시 스키마 결함인지 픽스처 결함인지 분리가 어렵다.

**대신 A안의 조건.** 새 테이블이 이 PR 동안 write-only 가 되므로, 백필이 틀려도 아무도 안 읽어
조용히 지나가는 사각이 생긴다. 그래서 **두 서랍의 상태 집합을 매번 대조하는 판별식을 필수 산출물로
넣는다**(§측정 가능한 완료 기준 C4). 읽기 전환은 CRUD 가 어차피 새 테이블에 쓰는 **PR 3** 에서
픽스처 헬퍼 1개와 함께 한다.

### 로드맵·ADR 정정 2건 (실측)

| 문서 | 적힌 것 | 실측 | 처리 |
|---|---|---|---|
| 로드맵 §PR 분해 PR 2 · §손댈 핵심 파일 | 읽기 경로를 PR 2 에서 2단 join 으로 교체 | 픽스처 32파일 동반 이주 필요 | **D1 로 PR 3 이관.** 로드맵은 저장소 밖 파일이라 이 스펙이 정본 |
| ADR `-global-status-catalog.md` §영향 | 「`src/generated/jooq/` 는 git 커밋 대상이라 마이그레이션마다 generateJooq 후 커밋한다」 | `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외. `git ls-files '*/src/generated/*'` = **1파일**(issue-tracking `.editorconfig` 뿐) | **ADR 해당 문단을 이 PR 에서 1줄 정정.** 생성물은 커밋하지 않고 빌드마다 재생성한다 |

## 사용자 시나리오 (Given-When-Then)

이 PR 은 관리자 화면이 없다. 시나리오는 **운영자가 보는 동작**으로 쓴다.

**S1. 재기동이 DB 수정을 더는 덮지 않는다 (이 PR 의 핵심)**
- Given. `software-default` 워크플로우가 DB 에 있고, 운영자가 `workflows.name` 을 「우리 개발 워크플로우」로 바꿨다.
- When. 애플리케이션을 재기동한다 (`YamlSeedService.seedAll()` 이 `ApplicationReadyEvent` 로 실행).
- Then. 이름은 「우리 개발 워크플로우」 그대로다. YAML 의 원래 이름으로 되돌아가지 않는다.
- 종전 동작. `isDirty()` 가 이름 차이를 감지해 `deleteWorkflow` → 재삽입 → **운영자 수정 소실**.

**S2. 빈 DB 최초 부팅은 지금과 똑같이 4종이 들어온다**
- Given. 마이그레이션만 적용된 빈 DB (workflows 0행).
- When. 최초 부팅.
- Then. 표준 4종이 적재되고, 같은 트랜잭션에서 `statuses` 12행 · `workflow_statuses` 17행이 함께 생긴다.

**S3. 배포된 DB 는 마이그레이션만으로 새 카탈로그가 채워진다**
- Given. 표준 4종이 이미 들어 있는 기존 DB (시드는 S1 정책상 아무것도 삽입하지 않는다).
- When. V203~V205 적용.
- Then. `workflow_states` 를 승격한 `statuses` 12행 · `workflow_statuses` 17행이 생기고, 기존 행은 하나도 지워지지 않는다.

**S4. 상태 키가 이름·카테고리와 1:1 이 아니면 배포가 멈춘다**
- Given. 어떤 DB 에서 `in_progress` 가 워크플로우 A 에선 `In Progress/IN_PROGRESS`, B 에선 `진행중/TODO` 다.
- When. V204 적용.
- Then. `RAISE EXCEPTION` 으로 마이그레이션이 실패하고 배포가 멈춘다. 조용히 한쪽을 골라 다른 쪽 이름을 뭉개지 않는다.

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| F1 | `statuses` 전역 상태 카탈로그 테이블을 신설한다. `key` 전역 UNIQUE · `lower(name)` 부분 UNIQUE(`deleted_at IS NULL`) · `category` CHECK 3종 |
| F2 | `workflow_statuses` N:M 테이블을 신설한다. `workflow_id` FK CASCADE · `status_id` FK RESTRICT · `UNIQUE(workflow_id, status_id)` · `display_order` · `layout_x/y` NULL |
| F3 | `workflow_states` 전량을 `statuses` 로 승격하고 `workflow_statuses` 를 채운다. **INSERT 만 한다** — 기존 행 삭제·수정 0 |
| F4 | 백필은 `key → (name, category)` 가 1:1 이 아니면 `RAISE EXCEPTION` 으로 실패한다 (ADR D4) |
| F5 | `workflows` 에 `version BIGINT NOT NULL DEFAULT 0` · `origin TEXT NOT NULL DEFAULT 'CUSTOM'` · `deleted_at TIMESTAMPTZ NULL` · `is_locked BOOLEAN NOT NULL DEFAULT FALSE` 를 추가한다 |
| F6 | 기존 표준 4키(`software-default` `bug-tracking` `simple` `kanban-basic`)의 `origin` 을 `SEED` 로 표기한다 (「기본값 복원」 대상 식별 — ADR D3·D4) |
| F7 | `YamlSeedService` 는 **해당 key 의 workflow 행이 없을 때만** 삽입한다. `isDirty()` · `differsIn*` 5종 · `deleteWorkflow` → 재삽입 경로를 제거한다 |
| F8 | 시드 삽입 시 `workflow_states` 와 `statuses`/`workflow_statuses` 를 **같은 트랜잭션에서 함께 기록**한다. 전자는 `workflow_transitions` FK 가 아직 참조하므로 유지, 후자는 빈 DB 와 기존 DB 의 카탈로그 상태를 같게 만들기 위해 필수 |
| F9 | 시드의 `statuses` 삽입은 `ON CONFLICT (key) DO NOTHING` 후 id 조회다. 이미 있는 전역 상태를 재사용해 **운영자가 바꾼 이름을 덮지 않는다** (ADR D3 「이름은 자유, 키는 불변」) |
| F10 | 시드가 삽입하는 workflow 행의 `origin` 은 `SEED` 다 |
| F11 | 읽기 경로(`WorkflowRepository` · `DefaultWorkflowDefinitionRepository` · `PostActionTransitionResolver`)는 **이 PR 에서 바꾸지 않는다** (D1) |
| F12 | ADR `-global-status-catalog.md` 의 jOOQ 커밋 문장을 실측대로 정정한다 |

## 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| N1 | 마이그레이션은 온라인 안전. 테이블 신설 + `DEFAULT` 있는 컬럼 추가(PG11+ 테이블 재작성 없음)만 쓴다. 기존 컬럼 타입 변경·DROP 0 |
| N2 | `workflow_states` 는 이 PR 에서 DROP 하지 않는다 (`DATA.md §4` add → backfill → drop 3단 · ADR D5) |
| N3 | BC 격리 — 변경은 `project-workflow` 단일. 다른 BC 의 프로덕션 코드 0줄 |
| N4 | FK 컬럼마다 인덱스를 만든다 (`DATA.md §7` — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다) |
| N5 | 모든 타임스탬프는 `TIMESTAMPTZ` (`DATA.md §4` — `TIMESTAMP without time zone` 금지) |
| N6 | 마이그레이션 번호는 `project-workflow` 대역 V200~V299 안 (V203·V204·V205) |
| N7 | 시드는 실패 시 부팅을 차단하는 기존 fail-fast 정책을 유지한다 |

## API 인터페이스 (REST)

**변경 없음.** 이 PR 은 엔드포인트를 추가·수정·삭제하지 않는다.

`GET /api/v1/workflows/{key}` 응답은 `{ key, name, description, states[], transitions[] }` 그대로다
— 읽기 경로를 건드리지 않으므로(F11) 형태 유지가 구조적으로 보장된다. 따라서 `apps/web` 의
`api/workflows.ts` Zod 스키마 · `mocks/workflow-fixtures.ts` · `hooks/use-workflows.ts` ·
관련 e2e 는 **0파일 변경**이다.

## 데이터 모델 변경

### V203 — `statuses` · `workflow_statuses` 신설

```sql
CREATE TABLE statuses (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    category    TEXT         NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_statuses_lower_name ON statuses (lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE workflow_statuses (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    status_id     UUID        NOT NULL REFERENCES statuses (id)  ON DELETE RESTRICT,
    display_order INTEGER     NOT NULL DEFAULT 0,
    layout_x      REAL,
    layout_y      REAL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, status_id)
);
CREATE INDEX idx_workflow_statuses_workflow ON workflow_statuses (workflow_id);
CREATE INDEX idx_workflow_statuses_status   ON workflow_statuses (status_id);
```

### V204 — 백필 + 유일성 가드

순서. ① 가드 검사 → ② `statuses` 승격 → ③ `workflow_statuses` 채움.

가드는 `workflow_states` 를 `key` 로 묶어 `(name, category)` 유일 조합이 2개 이상이면
`RAISE EXCEPTION` 한다. 예외 메시지에 **위반 키와 상충 조합**을 담는다 — 운영자가 어느 행을
고쳐야 하는지 메시지만 보고 알 수 있어야 한다.

`display_order` 는 `workflow_states.display_order` 를 그대로 옮긴다(워크플로우별 값이므로 N:M 쪽).
`is_system` 은 백필분 전량 `FALSE`.

### V205 — `workflows` 컬럼 4종 + `origin` 표기

```sql
ALTER TABLE workflows
    ADD COLUMN version    BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN origin     TEXT        NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN is_locked  BOOLEAN     NOT NULL DEFAULT FALSE;

ALTER TABLE workflows ADD CONSTRAINT ck_workflows_origin CHECK (origin IN ('SEED', 'CUSTOM'));

UPDATE workflows SET origin = 'SEED'
 WHERE key IN ('software-default', 'bug-tracking', 'simple', 'kanban-basic');
```

### 실측 기대값 (시드 4종 기준)

| 대상 | 값 | 근거 |
|---|---|---|
| `statuses` 행 | **12** | 4 YAML 상태 키 합집합 실측 — `open in_progress in_review done closed reported triaged resolved backlog ready todo doing` |
| `(key, name, category)` 유일 조합 | **12** | 키당 1:1 — 충돌 0건 (V204 가드가 통과할 것이라는 뜻이지, 가정하지 않는다) |
| `lower(name)` 유일값 | **12** | 이름 충돌 0건 |
| `workflow_statuses` 행 | **17** | bug-tracking 5 + kanban-basic 4 + simple 3 + software-default 5 |

## 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 빈 DB 최초 부팅 — V204 가 백필할 `workflow_states` 가 0행 | 시드가 `workflow_states` 와 `statuses`/`workflow_statuses` 를 **함께** 기록(F8). 이게 없으면 빈 DB 와 기존 DB 의 카탈로그가 달라진다 |
| E2 | 기존 DB — 시드가 「없을 때만 삽입」이라 아무것도 안 함 | V204 백필이 유일한 채움 경로. 그래서 백필과 시드 양쪽에 같은 기대값 검증을 건다 |
| E3 | 시드 YAML 에 새 워크플로우 키가 추가되고, 그 상태 키가 이미 전역에 있음 | `ON CONFLICT (key) DO NOTHING` 으로 기존 전역 상태를 재사용. 운영자가 바꾼 이름이 보존된다(F9) |
| E4 | 같은 워크플로우에 같은 상태를 두 번 넣으려 함 | `UNIQUE(workflow_id, status_id)` 가 막는다. 시드 삽입은 `ON CONFLICT DO NOTHING` |
| E5 | 서로 다른 키인데 이름이 대소문자만 다름 | `lower(name)` 부분 UNIQUE 가 막는다. **이 PR 에서는 도달 불가** — 상태를 만드는 경로가 시드뿐이고 시드 12개는 이름이 전부 다르다. 따라서 테스트를 만들지 않고 PR 3(CRUD)의 요구사항으로 넘긴다 |
| E6 | 워크플로우가 스킴에 물려 있어 FK RESTRICT | 이 PR 은 workflow 를 지우지 않는다(삭제 경로 제거가 곧 F7). `detachMappings`→재연결 경로는 호출되지 않는다 |
| E7 | `workflow_states` 와 새 카탈로그가 어긋남 | 판별식 C4 가 시드 직후 두 집합을 대조해 red 를 낸다. 이 PR 의 write-only 사각을 막는 장치 |
| E8 | 마이그레이션 재적용(Flyway 는 1회지만 테스트에서 반복) | V204 는 `ON CONFLICT DO NOTHING` 으로 멱등. V205 `UPDATE` 도 멱등 |

## 제약 조건

- `DATA.md §4` — add → backfill → drop 3단 분할. 이 PR 은 add + backfill 까지.
- `DATA.md §7` — FK 컬럼 인덱스 수동 생성.
- BC 대역 V200~V299 (`docs/adr/2026-05-26-bc-migration-prefix-policy.md`).
- Flyway `locations` 는 replace + **recursive 스캔**이다. BC 폴더에 파일을 넣는 것만으로는 자연 red 가
  나지 않는다 — 새 마이그레이션의 red 는 **테스트가 명시적으로 만든다**(learnings 2026-05-28).
- `src/generated/jooq/` 는 `.gitignore` 대상이라 **커밋하지 않는다**. 새 테이블을 쓰는 코드가 있으면
  `generateJooq` 로 로컬 재생성이 선행돼야 컴파일된다.
- Testcontainers 이미지는 `quay.io/tembo/pg16-pgmq:latest` (V004 pgmq 확장 요구 — V200MigrationTest 선례).
- 프로덕션 프로파일 부팅 테스트(`:modules:app:test`)는 **실제 Postgres(5433)** 를 요구한다.
  `docker-compose -f infra/docker-compose.dev.yml up -d postgres` 선행.

### 운영 공백 고지 (R7 · 게이트 1 승인분)

**이 PR 이후 시드 YAML 편집은 「빈 DB 최초 부팅」에만 효력이 있다.** 기존 DB 에 반영하는 수단은
로드맵 PR 8~10 의 편집 UI 이며, 그 전에는 직접 SQL 뿐이다.

종전에는 `workflows/*.yaml` 을 고쳐 배포하면 `isDirty()` 가 감지해 재적재했다. ADR
`2026-08-18-workflow-db-as-source-of-truth` D2 가 그 경로를 의도적으로 없앤다(자동 되돌림이 곧
운영자 수정 소실이었다). 대체 수단인 「기본값으로 복원」 버튼은 PR 6, 편집 화면은 PR 8~10 이다.

그 사이 기간에 표준 워크플로우를 바꿔야 하면 DB 를 직접 고친다 — 그리고 이제 그 수정은
**재기동해도 살아남는다**. 그것이 이 PR 의 목적이다.

## 측정 가능한 완료 기준

| # | 기준 | 확인 방법 |
|---|---|---|
| C1 | **red-first ①** 재기동이 DB 수정을 덮지 않는다 | `workflows.name` 을 바꾼 뒤 `seedAll()` 재호출 → 바뀐 이름 유지. 개편 전 코드에서 이 테스트가 **red** 인 것을 먼저 본다 |
| C2 | **red-first ②** 백필 결과가 기대값과 같다 | 시드 4종이 든 DB 에 V204 적용 → `statuses` 12행 · `workflow_statuses` 17행 · 키별 (name, category) 일치 |
| C3 | **red-first ③** 가드가 실제로 막는다 | 같은 키에 다른 (name, category) 를 심고 V204 적용 → `RAISE EXCEPTION`. 위반을 넣어보지 않은 가드는 장식이다 (ADR D4) |
| C4 | **불변식 판별식** 두 서랍의 상태 집합이 같다 | 시드 직후 모든 워크플로우에서 `{workflow_states.key}` == `{statuses.key via workflow_statuses}` + (name, category, display_order) 일치. 일부러 한쪽만 기록해 red 1회 확인 |
| C5 | 마이그레이션 체인 전량 적용 | Testcontainers 로 V200~V205 를 한 번에 적용하는 테스트가 통과 |
| C6 | 회귀 0 | `./gradlew :modules:project-workflow:test :modules:issue-tracking:test ktlintCheck detekt` BUILD SUCCESSFUL |
| C7 | 프론트 무손상 | `apps/web` 변경 **0파일** · `pnpm --filter web test` 통과 |
| C8 | 문서 정합 | `bash scripts/verify-master-plan.sh` EXIT 0 · `node scripts/build-doc-index.mjs --check` PASS |
| C9 | 판별식 회귀 0 | `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` — 직전 기준 406 통과 유지 |

## Sanity Check

① 누락된 요구사항 · ② 모호한 표현 · ③ 가정 누락 · ④ 엣지 케이스 미커버 4항목으로 스스로 흔들었다.

**❓ 발견 → 보강 3건 (1회)**

- **③ 가정 누락 — 빈 DB 최초 부팅.** 초안은 「V204 가 백필한다」로만 적어 빈 DB 를 다루지 않았다.
  시드가 새 테이블을 함께 기록하지 않으면 빈 DB(카탈로그 0행)와 기존 DB(12행)가 갈린다. → **F8 · E1 신설**.
- **③ 가정 누락 — 전역 키 재사용.** 시드가 `statuses` 에 무조건 INSERT 하면 운영자가 바꾼 이름을
  덮거나 UNIQUE 로 부팅이 죽는다. → **F9 · E3 신설**.
- **④ 미커버 — write-only 사각.** D1 로 읽기 전환을 미루면 새 테이블을 아무도 안 읽는다. → **C4 판별식**을
  완료 기준으로 승격하고 뮤테이션(한쪽만 기록) red 1회를 명시.

**의도적으로 만들지 않은 것 1건** — E5(`lower(name)` 충돌)는 이 PR 에서 **도달 불가**다. 상태 생성
경로가 시드뿐이고 12개 이름이 전부 다르다. 도달 불가 조합을 지키는 테스트는 가짜 그린이므로
(`[[unreachable-state-fixture-is-fake-green]]`) 만들지 않고 PR 3 요구사항으로 넘긴다.

**✅ 통과** — Maxi 결정 필요 항목은 D1 하나였고 2026-08-19 에 A안으로 확정됐다. 남은 gap 없음.
