# ADR — 상태(Status)를 워크플로우 종속에서 사이트 전역 카탈로그로 올린다

> 날짜. 2026-08-18
> 상태. **채택 (Active)**
> 관련 FR. FR-WF-04 (워크플로우 CRUD + 전역 상태 카탈로그)
> 관련 문서. `docs/plan/product/project-workflow.md §2.4` · `DATA.md §4` · `docs/sdd/07-workflow-engine.md`
> 대체 관계. **대체하는 기존 ADR 없음.** 상태 모델을 정면으로 다룬 ADR 은 이번이 처음이다 — V200 이 `workflow_states` 를 워크플로우 종속으로 만든 것은 ADR 없이 스키마로만 결정돼 있었다.

## 맥락

지금 상태는 워크플로우에 종속된다. `workflow_states(workflow_id, key, name, category, display_order)`
에 `UNIQUE(workflow_id, key)` 가 걸려 있어, **같은 `in_progress` 가 워크플로우마다 별개의 행**으로
존재한다. Jira Cloud 는 반대로 상태를 사이트 전역 자산으로 두고 워크플로우가 그것을 가져다 쓴다.

이 차이가 실제로 아픈 지점은 워크플로우 바깥이다.

- `issues.current_state_key VARCHAR(50)` (issue-tracking V001:45) — **FK 없음**, BC 격리로 문자열 참조
- `board_columns.state_key VARCHAR(50)` (agile-planning V500:32) — 같음, `UNIQUE(board_id, state_key)`

두 소비자는 `key` 문자열만 보고 어느 워크플로우 소속인지 모른다. 즉 **런타임은 이미 상태 키를
전역 식별자처럼 쓰고 있는데 스키마만 종속으로 남아 있다.** 워크플로우별로 같은 키에 다른 이름을
붙이는 순간 보드와 이슈 목록이 어느 이름을 보여줄지 결정할 수 없다.

### 백필이 안전하다는 실측

전환에 앞서 시드 4종의 상태를 전수 대조했다. **키가 겹치는 곳에서 이름과 카테고리가 완전히 일치한다.**

| 키 | 등장 워크플로우 | name / category |
|---|---|---|
| `in_progress` | bug-tracking · kanban-basic · software-default | In Progress / IN_PROGRESS — 3곳 동일 |
| `done` | kanban-basic · simple · software-default | Done / DONE — 3곳 동일 |
| `closed` | bug-tracking · software-default | Closed / DONE — 2곳 동일 |

전역화하면 12개 상태(`reported` `triaged` `in_progress` `resolved` `closed` `backlog` `ready`
`done` `todo` `doing` `open` `in_review`)로 수렴한다. `display_order` 는 워크플로우마다 다르지만
이는 N:M 테이블로 내려가므로 충돌이 아니다.

**다만 마이그레이션은 이 실측을 가정하지 않는다.** 배포된 DB 가 시드와 다를 수 있고, 가정에 기대는
백필은 조용히 데이터를 뭉갠다.

## 결정

### D1. `statuses` 를 전역 카탈로그로 신설한다

```
statuses
  id UUID PK · key VARCHAR(50) UNIQUE · name VARCHAR(100) · description
  category CHECK(TODO|IN_PROGRESS|DONE) · is_system BOOL
  created_at/updated_at TIMESTAMPTZ · deleted_at TIMESTAMPTZ NULL
  UNIQUE INDEX on lower(name) WHERE deleted_at IS NULL
```

이름은 대소문자를 무시하고 유일하다 — Jira Cloud 와 같다. 같은 이름의 상태가 둘이면 사용자가
전환을 걸 때 어느 쪽인지 구분할 수 없다.

### D2. `workflow_statuses` 로 워크플로우와 상태를 N:M 으로 잇는다

```
workflow_statuses
  id UUID PK · workflow_id FK CASCADE · status_id FK RESTRICT
  display_order INT · layout_x REAL NULL · layout_y REAL NULL
  UNIQUE(workflow_id, status_id)
```

`display_order` 와 다이어그램 좌표는 **워크플로우마다 다른 값**이므로 여기 둔다. 이름과 카테고리는
전역이므로 `statuses` 에 둔다. 이 분리가 이 ADR 의 실질이다.

`status_id` 는 `RESTRICT` 다 — 어느 워크플로우가 쓰고 있는 상태는 카탈로그에서 지울 수 없다.

### D3. 상태 키는 생성 시점에 확정하고 이후 불변이다

**이 ADR 에서 가장 중요한 조항이다.** `PUT /statuses/{id}` 는 `name` · `description` · `category`
만 받고 `key` 는 받지 않는다. 키 생성은 최초 1회, 이름에서 슬러그를 뽑고 충돌 시 접미사를 붙인다.

근거는 위 §맥락의 두 소비자다. `issues.current_state_key` 와 `board_columns.state_key` 가 FK 없이
문자열로 참조하므로, 키를 바꾸면 **DB 가 막아 주지 않은 채** 이슈가 존재하지 않는 상태를 가리키게
된다. 그 이슈는 `WorkflowEngine` 이 `fromStateKey` 를 못 찾아 이후 어떤 전환도 계산할 수 없다.

이름만 바꾸는 것은 완전히 안전하다 — 이슈·보드·검색·내보내기 어디도 키만 보기 때문이다.
**「이름은 자유, 키는 불변」이 사용자에게 보이는 계약**이다.

### D4. 백필은 유일성 가드를 동반한다

`workflow_states` → `statuses` 승격 시 `key → (name, category)` 가 1:1 이 아니면 마이그레이션을
`RAISE EXCEPTION` 으로 실패시킨다. 조용히 하나를 골라 나머지를 버리면 어느 워크플로우의 상태
이름이 말없이 바뀐다.

가드가 실제로 동작하는지는 **일부러 충돌 데이터를 심어 red 를 1회 확인**한다. 위반을 넣어보지 않은
가드는 조용히 통과하는 장식일 수 있다.

### D5. `workflow_states` 는 즉시 지우지 않는다

`DATA.md §4` 의 add → backfill → drop 3단 분할을 따른다. 신설·백필과 DROP 을 같은 마이그레이션에
넣지 않는다. DROP 은 새 경로가 실사용으로 검증된 뒤 마지막 단계에서 한다.

## 근거

1. **런타임이 이미 전역으로 쓰고 있다.** 스키마만 종속이라 둘이 어긋난 상태였다. 전역화는 새 개념을
   들이는 것이 아니라 이미 있는 사용법에 스키마를 맞추는 일이다.
2. **보드 컬럼 매핑이 안정된다.** `board_columns.state_key` 가 가리키는 상태가 워크플로우마다 다른
   행일 수 있다는 모호함이 사라진다.
3. **상태 재사용이 Jira Cloud 의 실제 UX 다.** 「진행 중」을 한 번 만들고 모든 워크플로우에서 고르는
   것이 사용자가 기대하는 동작이다. 워크플로우마다 새로 만들게 하면 곧 「진행중」 「진행 중」 「작업중」이
   난립하고 보드 매핑이 깨진다.
4. **키 불변 규칙이 이 전환을 무해하게 만든다.** 전역화의 위험은 전부 「키가 바뀌면」에서 온다.
   키를 못 바꾸게 하면 남는 것은 이득뿐이다.

## 기각한 대안

**워크플로우 종속 유지 + 이름/카테고리 편집만** — `workflow_states` 를 그대로 두고 그 안에서만
추가·수정·삭제·순서변경을 지원하는 안. 마이그레이션이 최소이고 파급이 작다.

기각 사유. 같은 이름의 상태가 워크플로우 수만큼 별개 행으로 늘어난다. 사용자가 워크플로우 A 에서
「검토 중」의 이름을 고쳐도 워크플로우 B 의 「검토 중」은 그대로라, **같아 보이는 것이 같지 않은**
상태가 된다. 보드 컬럼은 키로 매핑하므로 두 상태가 한 컬럼에 섞여 들어간다. Jira Cloud 와의
차이가 UI 표피가 아니라 데이터 모델에 남아 이후 모든 화면에서 예외 처리를 낳는다.

## 영향

### 긍정

- 상태를 한 번 만들어 여러 워크플로우에서 재사용한다.
- 보드 컬럼·이슈 필터가 가리키는 상태의 의미가 하나로 확정된다.
- 상태 이름 변경이 전역 1회로 끝난다.

### 부정 / 위험

- **마이그레이션이 크다.** 신설 2 · 백필 1 · DROP 1 로 4단계이고, `WorkflowRepository` 의 join 이
  2단으로 깊어진다. `PostActionTransitionResolver` · `DefaultWorkflowDefinitionRepository` ·
  `YamlSeedService` 도 함께 바뀐다.
- **jOOQ 생성물 재생성이 필요하다.** 단 `src/generated/jooq/` 는 **커밋 대상이 아니다** —
  `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외하고, `compileKotlin` 이 `generateJooq` 에
  `dependsOn` 이라 빌드마다 자동 재생성된다(2026-08-19 실측 정정 — `git ls-files '*/src/generated/*'` 는
  issue-tracking `.editorconfig` 1건뿐이다). 로컬에서 새 테이블이 안 잡히면
  `./gradlew :modules:project-workflow:generateJooq` 를 직접 부른다.
- **읽기 API 응답 형태는 바꾸지 않는다.** `GET /api/v1/workflows/{key}` 는 지금과 같은
  `{ key, name, description, states[], transitions[] }` 를 유지한다. 이 약속이 없으면 프론트의
  Zod 스키마 · MSW 픽스처 · e2e 4건이 스키마 전환 단계에서 함께 깨진다. 새 필드는 추가만 한다.

## 대안 채택 조건

- 조직이 프로젝트별로 완전히 격리된 상태 이름을 요구하면 → 전역 카탈로그 위에 프로젝트 스코프
  별칭(alias) 계층을 얹는다. 키는 여전히 전역이므로 소비자는 영향받지 않는다.

## 관련

- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` — 정본 이전
- `docs/adr/2026-08-18-workflow-transition-id-identity.md` — 전환이 `workflow_statuses` 를 참조하게 된다
- `DATA.md §4` — 마이그레이션 3단 분할 · BC 번호 대역 V200~V299
- `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V001__issues_initial.sql:45` — `current_state_key`
- `backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V500__boards.sql:32` — `board_columns.state_key`
