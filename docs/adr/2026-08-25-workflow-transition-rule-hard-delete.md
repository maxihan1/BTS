<!-- 전환 규칙(workflow_validators · workflow_post_actions) 의 행 삭제를 하드 삭제로 인가하는 결정 -->

# ADR — 전환 규칙(검증기·후처리) 행 삭제는 하드 삭제로 간다

> 날짜. 2026-08-25
> 상태. **제안 (Proposed) — Maxi 확정 대기.** 근거 규칙이 「ADR + **Maxi 확인** 필수」이므로 ADR 단독으로는 요건이 닫히지 않는다. 게이트 2 승인 시 「채택 (Accepted)」으로 올린다.
> 관련 FR. FR-WF-06 (전환 규칙 편집 — validator CRUD API) · 형제 표면인 post-action 은 FR-NT-05 D6 때 들어왔다
> BC. project-workflow
> 근거 규칙. `DATA.md §1` **2번** — *"`DELETE` 는 항상 `WHERE` + 소프트 삭제 우선 — 하드 삭제는 **ADR + Maxi 확인** 필수"* · 같은 규칙의 `DEVELOPMENT.md` 사본은 **§1.2 7번**(§1.2 는 §1.1 에서 이어지는 7~10 번이라 7 번이 맞다). 정본 등재는 `DATA.md §3` 하드 삭제 허용 영역
> 선행 ADR. [global-permission-grants](../decisions/2026-07-17-global-permission-grants.md) (FR-PM-10 D-5 — 「설정/관계 행의 회수는 hard delete」선례) · [fr-ux-02-favorites](../decisions/2026-06-24-fr-ux-02-favorites.md) (FR-UX-02 D5 — 토글 해제 선례) · [workflow-transition-id-identity](2026-08-18-workflow-transition-id-identity.md) (규칙이 매달리는 전환의 identity)
> 스펙/plan. [plan](../plans/2026-08-25-backend-workflow-transition-rule-crud.md)
> 관련 스키마. `backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V200__init_workflow.sql`

## 맥락

FR-WF-06 이 `workflow_validators` 에 CRUD API 를 얹으면서 **행 단위 사용자 삭제 경로**가 처음 생겼다.
구현은 물리 삭제다.

```kotlin
// TransitionRuleRepository.kt — 삭제 구현
dsl.deleteFrom(table)
    .where(idField.eq(id))
    .execute()
```

`WHERE` 는 있으나 소프트 삭제가 아니다. 그런데 `workflow_validators` 는 **`DATA.md §3` 하드 삭제 허용
영역 목록에 없고**, 이 표면을 인가하는 ADR 도 없었다(`docs/adr`·`docs/decisions` 전수 grep 0 건).
`DATA.md §1` 은 5원칙 위반을 「즉시 PR BLOCKER」로 못박았으므로 게이트 2 가 이 PR 을 막았다.

### 이 PR 이 만든 결함이 아니라 드러낸 결함이다

형제 테이블 `workflow_post_actions` 는 **`origin/main` 에서 이미 같은 방식으로 물리 삭제한다**
(`PostActionRepository.deleteById` — `deleteFrom(WORKFLOW_POST_ACTIONS).where(ID.eq(id))`).
즉 미등재 상태는 FR-NT-05 D6(post-action 런타임 편집) 때부터 있었고, FR-WF-06 이 같은 모양의
두 번째 표면을 만들면서 눈에 띈 것뿐이다.

**그래서 이 ADR 은 두 테이블을 함께 인가한다.** validator 만 등재하면 다음 사람이 post-action 을
보고 "저건 왜 없지" 를 다시 묻고, 같은 판단을 한 번 더 재생산한다 — 사본을 만들지 않는다.

### 두 테이블은 구조가 같다 (V200)

| 컬럼 | `workflow_validators` | `workflow_post_actions` |
|---|---|---|
| `id` | UUID PK | UUID PK |
| `transition_id` | UUID NOT NULL, **`ON DELETE CASCADE`** | UUID NOT NULL, **`ON DELETE CASCADE`** |
| `type` / `config` | TEXT / JSONB | TEXT / JSONB |
| `display_order` | INTEGER NOT NULL DEFAULT 0 | INTEGER NOT NULL DEFAULT 0 |
| `created_at` / `updated_at` | TIMESTAMPTZ | TIMESTAMPTZ |
| **`deleted_at`** | **없음** | **없음** |

`deleted_at` 은 V200(초판)부터 없었다. **본 PR 은 스키마를 바꾸지 않는다** — FR-WF-06 은 product
명세에서 D3(마이그레이션)을 「비해당」으로 선언하고 기존 테이블에 CRUD 만 얹는다.

## 결정

### D-1. 전환 규칙 행 삭제는 하드 삭제로 간다 — 설정 행이지 인용 대상이 아니다

전환 규칙은 **관리자가 켜고 끄는 설정 행**이다. 「이 전환에 해결책 필수 검증을 건다/푼다」는 토글이고,
푼 것을 다시 걸면 새 행 하나면 된다.

- **외부 영구 인용이 없다.** Slack·이메일·외부 문서가 validator/post-action 의 `id` 를 참조하지 않는다.
  행이 사라져서 깨지는 인용이 없다. 이슈 키(`PROJ-123`, `DATA.md §1.1`)와 **결정적으로 다른 점이
  이것**이다 — 이슈 키는 외부에 영구히 인용되므로 `issue_key_redirect` 로 옛 키까지 보존한다.
- **재생성은 새 행으로 충분하다.** 규칙의 정체성은 `id` 가 아니라 `(transition_id, type, config)` 이고,
  같은 규칙을 다시 걸면 같은 의미의 행이 새 `id` 로 생긴다. 되살릴 상태가 없다.
- **선례와 같은 성격이다.** `favorites`(즐겨찾기 해제) · `user_keymap`(기본값 복원) ·
  `global_permission_grants`(권한 회수) 와 같은 부류다 — 전부 「토글/설정 행이라 복구 가치가 낮고
  외부 인용이 없다」를 근거로 하드 삭제를 인가받았다.

### D-2. `workflow_post_actions` 를 같은 결정으로 함께 인가한다

같은 테이블 모양 · 같은 성격(전환에 매달린 설정 행) · 같은 리포지토리 기반
(`TransitionRuleRepository` 를 두 리포지토리가 함께 상속한다). 정책을 가를 근거가 없으므로 한 줄로
함께 등재한다. `DATA.md §3` 등재도 두 테이블을 한 항목에 담는다.

### D-3. `deleted_at` 컬럼을 새로 만들지 않는다 — 스키마 무변경

소프트 삭제를 택하면 두 테이블에 `deleted_at` 을 추가하는 마이그레이션(V207 급)이 필요하고,
`init_codegen.sql` 미러 + jOOQ 재생성 + 기존 조회 전량 수정이 따라온다. FR-WF-06 은 D3 을 「비해당」
으로 선언한 범위이므로 **범위를 넘는다**. 비용은 §대안 검토 A 에 적는다.

### D-4. CASCADE 와 행 단위 경로의 정책을 일치시킨다

V200 이 이미 `transition_id UUID NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE` 로
선언했다. **전환을 지우면 그 전환의 규칙 행은 지금도 물리 삭제된다.** 사용자가 규칙 하나를 지우는
경로만 다른 정책(소프트 삭제)을 쓰면, 같은 데이터가 삭제 경로에 따라 남기도 하고 사라지기도 한다 —
그 비대칭을 정당화할 근거가 없다. 두 경로를 하드 삭제로 맞춘다.

### D-5. 사라진 행에 대한 조용한 성공은 금지한다 (이미 충족)

`global_permission_grants` D-5 가 세운 「삭제 행 수 0 이면 404」 원칙을 승계한다. 현재 구현은
`ValidatorAdminService.delete` 가 `ensureValidatorBelongsToTransition(id, transitionId)` 로 **삭제 전에**
존재·소속을 검사하고 `ValidatorNotFoundException` 을 던지므로, repository 의 `deleteById` 가 no-op 로
빠지는 경로는 사용자에게 200 으로 보이지 않는다. post-action 경로도 같은 모양이다.
**새 코드를 요구하지 않는다** — 본 ADR 은 그 성질이 유지되어야 함을 기록만 한다.

## 대안 검토

### A. 소프트 삭제 — `deleted_at` 추가 (기각)

원칙상 1순위이고, 기각하려면 값을 치러야 한다. 실제 비용이 이렇다.

1. **조회마다 `deleted_at IS NULL` 이 붙는다.** `DATA.md §3` 이 명시하듯 BTS 에는 공통
   `SoftDeleteFilter` 래퍼가 없고 술어를 repository 마다 **손으로** 붙인다. 전환 규칙은 엔진의
   hot path(`WorkflowEngine` 이 전환마다 규칙을 읽는다)와 관리 API 양쪽에서 읽히므로, 한 곳만
   빠뜨려도 **꺼 놓은 규칙이 되살아나 전환을 막는다** — 조용한 실패가 아니라 조용한 차단이다.
2. **`display_order` 계산에 죽은 행이 섞인다.** 순서는 `display_order` 정수인데, 삭제된 행이 남아
   있으면 순서 재계산·중복 판정·다음 순번 산출이 전부 「산 행만」을 다시 정의해야 한다. 정렬 키가
   가시성 술어에 의존하기 시작하면 규칙이 두 배로 늘어난다.
3. **껐다 켜면 유령 행이 쌓인다.** 관리자가 규칙을 토글하는 것이 정상 사용이다. 열 번 껐다 켜면
   죽은 행 열 개가 남고, 그 행들은 아무도 조회하지 않으며 정리 정책도 없다. 보존의 대가로 얻는
   정보는 「누가 언제 껐나」인데 그것은 `deleted_at` 하나로는 어차피 답이 안 나온다(누가·왜가 없다).
4. **범위를 넘는다.** 마이그레이션 + `init_codegen.sql` 미러 + jOOQ 재생성이 붙고, FR-WF-06 이
   「D3 비해당」으로 선언한 범위가 깨진다.

**기각.** 값은 3~4 이고 얻는 것은 「되살릴 일이 없는 설정 행의 시체」다.

### B. 하드 삭제 + 감사 로그 병행 (채택하지 않음 — 후속 대상으로 남긴다)

삭제를 물리로 하되 `audit_logs`(append-only) 에 「누가 언제 어떤 규칙을 뗐나」를 남기는 안.
소프트 삭제의 세 비용을 전부 피하면서 추적성만 얻는다는 점에서 **A 보다 낫다**.

채택하지 않는 이유는 두 가지다. ① 워크플로우 편집 전반(상태·전환·규칙)의 감사 축이 아직 없어
규칙만 감사하면 반쪽이다. ② FR-WF-07 이 **초안·발행 + 발행 이력 append-only** 를 이미 선언했고
(`docs/plan/product/project-workflow.md §2.7 D1`), 규칙 편집 이력은 그 축에 얹히는 것이 자연스럽다.
지금 별도 감사를 붙이면 FR-WF-07 이 그것을 다시 걷어낸다.

**후속 대상.** 잔여 위험 1 에 적는다.

### C. 현행 유지 — 하드 삭제하되 등재하지 않는다 (기각)

코드 변경이 0 이라 가장 싸지만, 이것이 정확히 게이트 2 가 막은 상태다. `DATA.md §1` 5원칙은
「위반 시 즉시 PR BLOCKER」이고 예외는 **`DATA.md §3` 에 등재된 것만**이다. 등재 없는 하드 삭제는
규칙 위반이 아니라 **규칙이 보이지 않는 상태**이며, 다음 사람이 이 코드를 선례로 삼아 같은 삭제를
또 복사한다 — post-action 이 이미 그렇게 됐다.

**기각.** 본 ADR + `DATA.md §3` 등재가 그 상태를 닫는다.

## 결과 / 트레이드오프

- **산출물.** 본 ADR + `DATA.md §3` 하드 삭제 허용 영역 한 줄(두 테이블 동시 등재). **코드·스키마
  변경 0** — 절차 인가이지 동작 변경이 아니다.
- **장점.** validator 와 post-action 이 같은 근거 위에 서고, 전환 삭제(CASCADE)와 행 삭제의 정책이
  일치한다. `origin/main` 에 이미 있던 미등재 표면도 함께 닫힌다.
- **비용.** 「누가 언제 어떤 규칙을 뗐나」는 재구성할 수 없다(대안 B 미채택). 규칙을 잘못 지우면
  복구는 다시 만드는 것뿐이다 — 설정 행이라 비용이 낮다는 것이 D-1 의 전제다.
- **무효화 없음.** V200 스키마 · FR-WF-06 D3(비해당) 선언 · `TransitionRuleRepository` 공통화
  결정 전부 유효하다.

## 잔여 위험

1. **규칙 편집 이력 부재** (대안 B). 지금은 삭제 흔적이 남지 않는다. FR-WF-07(초안·발행, 발행 이력
   append-only) 착수 시 규칙 편집을 그 축에 포함할지 재검토한다.
2. **잘못 지운 규칙의 복구 수단 없음** (D-1). 되살리기가 아니라 다시 만들기다. 전환 규칙은 수가
   적고(전환당 수 개) 재입력이 짧아 감수한다. 규칙 수가 수백 단위로 늘면 재검토 대상이다.
3. **D-5 성질은 코드로 강제되지 않는다.** 「삭제 전 존재 검사」는 지금 서비스 층 관례일 뿐이고,
   이를 지키는 판별자가 따로 없다. 누가 `deleteById` 를 직접 부르면 no-op 가 200 으로 보인다.
4. **`DATA.md §3` 등재는 사람만 대조한다.** 허용 목록 ↔ 실제 `deleteFrom` 호출 집합을 맞추는
   판별식이 없다. 다음 미등재 하드 삭제도 이번처럼 리뷰가 걸릴 때까지 보이지 않는다.

## 관련

- FR-WF-06 (전환 규칙 편집) — `docs/plan/product/project-workflow.md §2.6`
- FR-WF-07 (초안·발행 + 발행 이력) — `§2.7`, 대안 B 의 후속 축
- FR-PM-10 [global-permission-grants](../decisions/2026-07-17-global-permission-grants.md) — D-5 hard delete 선례 · 「조용한 성공 금지」 원칙
- FR-UX-02 [fr-ux-02-favorites](../decisions/2026-06-24-fr-ux-02-favorites.md) — D5 토글 해제 하드 삭제 선례
- FR-PF-03 [fr-pf-03-keymap-customize](../decisions/2026-07-08-fr-pf-03-keymap-customize.md) — 기본값 복원 시 override 행 제거 선례
- FR-WF-05 [workflow-transition-id-identity](2026-08-18-workflow-transition-id-identity.md) — 규칙이 매달리는 전환의 identity
