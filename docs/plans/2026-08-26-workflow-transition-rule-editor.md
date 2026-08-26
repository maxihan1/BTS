# 전환 규칙(validator) 편집 다이얼로그 + E2E (FR-WF-06 D6·D7)

> 티어: T2
> slug: workflow-transition-rule-editor
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-26

> **파일명에 FR ID 를 넣지 않는다.** `scripts/doc-index/scan-docs.mjs` 의 「본문 언급 승격」이
> 행 단위 전부-아니면-전무라, 파일명에 `fr-wf-06` 이 든 문서가 하나 생기면 FR-WF-06 행의
> **모든 열**에서 승격이 꺼진다(#400 plan 이 FR-WF-04 에서 실측 · 부채 123). 착수 시 slug 를
> `fr-wf-06-d6-d7-transition-rule-editor` → 현재값으로 정정했다. 본문 FR ID 표기는 규칙대로 지킨다.

## Brief

Maxi 원문 — 「FR-WF-06 D6·D7(전환 규칙 편집 UI + E2E) 진행해줘」.

`classify-task.ts` 는 `type=qa · tier=T1 · agent=qa-engineer` 를 돌려줬다 — **오분류**다.
「E2E」 토큰에 걸렸고 실제 표면은 `apps/web/src`(다이얼로그) + `backend/**/main`(부채 3건) 혼합이다.
`/bts` 판정 ②(Maxi 지정 우선)로 **T2 선언**. 이 오분류는 `chore/classify-task-misroute-*` 계열의
알려진 양식이라 별도 부채로 세우지 않는다 — 다만 게이트 2 요약에 선언/실측 티어를 나란히 싣는다.

### 범위 (Maxi 승인 — AskUserQuestion 「장부 지시대로 1·2·3 포함」)

| # | 항목 | 티어 | 근거 |
|---|---|---|---|
| D6 | 전환 규칙 편집 다이얼로그 (전환별 validator CRUD) | T2 | FR-WF-06 §2.6 |
| D7 | E2E — 규칙을 걸면 전환이 막히고, 풀면 통과한다 | T1 | FR-WF-06 §2.6 |
| 부채 1 | 프레임워크 예외 3종이 `{error:{code,message}}` 봉투 밖으로 샘 | T2 | 장부 「D6 착수와 같은 PR 에서」 |
| 부채 2 | `ValidatorResponse.editable` 부재 | T2 | 장부 「**D6 착수 시 첫 task**」 |
| 부채 3 | 편집 불가 조건 문서↔코드 불일치 | T1 | 부채 2 와 짝 |

**범위 밖 (장부에 남긴다).** 부채 4(`config` JSONB 크기·키 제한 — 장부가 「D6 **이후**」로 명시) ·
부채 5(변경 로그 행위자 — MDC/감사 로그 작업에 묶임) · 부채 6(사본 5종 — 동작 영향 0).

### 선행 상태 (실측 — 문서가 아니라 코드·PR 로 확인)

- **D1·D2·D4·D5 ✅ PR #404** (`f5bb3533d`, 2026-08-26 머지). D3 **비해당 확정** — `workflow_validators`
  는 V200 기존 테이블이고 #404 의 마이그레이션은 0건이다. 이번 PR 도 마이그레이션 0건이다.
- `ValidatorController` **4 엔드포인트 실재** — `@RequestMapping("/api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators")`
  · `GET` list · `POST` create · `PUT /{id}` · `DELETE /{id}`. (「파일 존재 ≠ 기능 존재」 교훈에 따라
  HTTP 매핑을 세어서 판정했다 — learnings 2026-07-17 / PR #279)
- 형제 `PostActionController` 가 같은 경로 규약으로 이미 존재한다 — 부채 1 은 **양쪽**에 건다.
- ~~프론트에 validator/post-action 편집 UI 는 0건~~ → **오실측이었다. 게이트 1 리뷰가 정정했다.**
  `--include` 옵션 오류로 grep 이 실행되지 않은 상태에서 내린 판단이었고, 실제로는 **형제
  post-action 편집 UI 가 전부 구현돼 있다** — `components/workflow/PostActionFormDialog.tsx` ·
  `PostActionConfigSection.tsx` · `api/post-actions.ts` · `hooks/use-post-actions.ts` ·
  `i18n/post-action-labels.ts` · `mocks/post-action-handlers.ts` · `e2e/workflow-post-action.spec.ts`
  (FR-NT-05). validator 편집 UI 만 없다. **이 PR 은 새 설계가 아니라 형제 대칭 구현이다.**

### 착수 시 반영한 함정·교훈

- **MSW lexical 가짜그린** (learnings 2026-06-25 / PR #187) — 단위·MSW·E2E 3겹이 동시에 가짜그린일
  수 있다. `ValidatorDtos.kt` 실물을 읽고 계약을 맞춘다.
- **브라우저 눈확인** — `jira-parity-contract` §6 이 시각 변경 PR 에 요구한다. #400 이 미실시로
  남겼고 그때 배지 색이 통째로 사라지는 결함이 실제로 있었다. 이번엔 라이트/다크 눈확인을 한다.
- **`apps/web/e2e/visual/__screenshots__/`** 는 untracked 로 그대로 둔다 (#400 결정 승계).

## 도메인 정리

**BC.** `project-workflow` 단일 (부채 1·2·3) + `apps/web` (D6·D7). 프론트는 BC 가 아니므로
「한 PR = 한 BC」 규칙 위배가 아니다. 다른 BC 를 직접 import 하는 코드는 0건이고 pgmq 이벤트도
새로 발행하지 않는다.

**영향 엔티티.**

| 엔티티 | 이 PR 이 하는 일 | 스키마 |
|---|---|---|
| `workflow_validators` (V200) | 읽기·쓰기 (#404 가 만든 CRUD 를 화면이 소비) | **무변경** |
| `workflow_post_actions` (V200) | 부채 1 의 예외 핸들러만 — 데이터 접근 없음 | **무변경** |

**마이그레이션 0건.** #404 가 D3 을 비해당으로 확정한 근거가 그대로 유효하다.

**새 용어 3건 — Maxi 승인 대기 (게이트 1).**

| 용어 | 정의 제안 | glossary 기존 항목과의 관계 |
|---|---|---|
| 전환 규칙 | Transition Rule. 전환에 걸리는 validator + post-action 의 총칭 | 기존 **게이트**(「전환에 걸린 조건 (권한/필드/검증)」)가 validator 만 가리켜 post-action 을 못 담는다. 「게이트 ⊂ 전환 규칙」으로 두거나 게이트를 이 정의로 넓히는 두 길이 있다 |
| 평가 시점 | Phase. `AVAILABILITY`(전환 버튼을 감춘다) / `EXECUTION`(눌렀을 때 막는다) | 신규. Jira 의 Condition/Validator 구분에 대응하는 값이다 (SDD §7.5) |
| 편집 가능 여부 | Editable. 이 API 로 만들고 고칠 수 있는 type 인지 | 신규. 부채 2 가 응답 필드로 세운다 |

**관련 ADR 3건 — 충돌 없음.**

- [`2026-08-25-workflow-transition-rule-hard-delete`](../adr/2026-08-25-workflow-transition-rule-hard-delete.md)
  — 규칙 삭제는 하드 삭제다. D6 삭제 UI 는 되돌릴 수 없음을 확인 다이얼로그로 알린다.
- [`2026-05-21-workflow-validator-terminology`](../adr/2026-05-21-workflow-validator-terminology.md)
  — **구현 클래스 이름**을 정했지 런타임 `type` 문자열을 정하지 않았다. UI 라벨은 클래스 이름이 아니라
  `type` 문자열에 붙는다.
- [`2026-08-18-workflow-transition-id-identity`](../adr/2026-08-18-workflow-transition-id-identity.md)
  — `transitionKey` 세그먼트는 전환 id(UUID) 또는 종전 합성 키다. 화면은 **UUID 만** 쓴다(§제약 C4).

**기존 결정 승계.** `CustomExpression` 편집 제외는 #404 게이트 1 의 Maxi 결정이다 — 근거는
`SpelEvaluator` 의 「일반 사용자가 API 를 통해 임의 표현식을 전달하는 경로를 절대로 만들지 않는다」와
Jira Cloud 내장 validator 9종에 자유 표현식 입력칸이 없다는 실측이다. D6 은 이 결정을 **뒤집지 않고
화면에 드러낸다**.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. 규칙을 건다.**
- Given 시스템 관리자가 `/admin/workflows/software-default` 편집기의 전환 목록을 보고 있다
- When 전환 행의 「규칙」을 눌러 다이얼로그를 열고 type `RequiredField` · `field=resolution` 을 저장한다
- Then 규칙 목록에 그 행이 뜨고, 평가 시점 배지가 **`실행 시 차단`**(EXECUTION) 으로 보인다

**S2. 규칙을 고친다.**
- Given S1 의 규칙이 목록에 있다
- When 그 행의 「편집」으로 `field` 를 `assignee` 로 바꿔 저장한다
- Then 목록이 갱신된 값을 보여준다 (mutation 후 refetch 로 화면이 실제로 바뀌는 것까지 본다)

**S3. 규칙을 푼다.**
- Given S1 의 규칙이 목록에 있다
- When 「삭제」를 누르고 **되돌릴 수 없다는 확인**에 동의한다
- Then 행이 사라지고 목록이 빈 상태 문구를 보여준다

**S4. 편집할 수 없는 규칙이 목록에 있다.**
- Given 시드에 `CustomExpression` 행이 이미 들어 있다
- When 규칙 다이얼로그를 연다
- Then 그 행은 **숨겨지지 않고 보이되** 편집 버튼이 비활성이고 이유가 표시된다.
  삭제는 가능하다 (백엔드가 삭제를 막지 않는다)
- And 새 규칙 추가의 type 선택지에 `CustomExpression` 이 **없다**

**S5. 손으로 깨뜨린 행이 섞여 있다.**
- Given DB 에 `type=RequiredField` 인데 `config` 에 `field` 가 없는 행이 있다
- When 규칙 목록을 연다
- Then 목록 전체는 200 이고 그 행만 평가 시점이 **`판정 불가`** 로 보이며 편집이 비활성이다
  (`phase=null` ↔ `editable=false` 가 짝을 이룬다)

**S6. 저장이 거부된다.**
- Given 규칙 추가 폼에서 필수 config 키를 비워 둔다
- When 저장을 누른다
- Then 백엔드 400 `WORKFLOW_VALIDATOR_INVALID` 의 메시지가 다이얼로그 안에 뜨고 다이얼로그는 닫히지 않는다

**S7. 규칙이 이슈 화면에 반영된다.** ~~(D7 화면 계약)~~ → **E2E 범위 밖 · 명시적 위임**
(Maxi 결정, 2026-08-26 · 구현 중 실측으로 표현 수단이 무너졌다)
- Given `not-status-category`(AVAILABILITY) 규칙이 어떤 전환에 걸려 있다
- When 그 워크플로우를 쓰는 이슈의 상태 드롭다운을 연다
- Then 그 전환이 후보 목록에 없다. 규칙을 풀면 다시 나온다

> **★ 왜 E2E 가 이것을 재지 않는가.** 착수 시 이 시나리오를 `/transitions/plan` 응답 픽스처로 표현하기로
> 했으나(제약 C3), Task 8 이 **그 엔드포인트를 화면이 한 번도 부르지 않는다**는 것을 실측했다 —
> `planTransition` 호출부는 API 정의와 자기 단위 테스트 밖에 **0건**이고, 이슈 상태 드롭다운의 실제
> 출처는 `useIssueTransitions` → `GET /api/v1/issues/{key}/transitions` 다. 소비자 0건인 픽스처 위에
> 시나리오를 세우면 드롭다운에 대해 **아무것도 증명하지 못하는 가짜 그린**이 된다.
>
> **대신 두 곳이 이미 덮는다.** ① 「규칙이 후보를 감추는 계산」 —
> `ValidatorEngineIntegrationTest` 의 `not-status-category 를 걸면 목록에서 사라지고 지우면 다시 나온다`
> (Testcontainers 실 DB + 실 엔진) ② 「화면이 받은 목록을 그대로 그린다」 — #398 이 만든
> `apps/web/e2e/issue-transition.spec.ts` S8(FR-WF-05 D7). 새 E2E 는 ②의 중복이 된다.
>
> `mocks/issue-handlers.ts` 의 `getTransitionsHandler` 에 시나리오 플래그를 넣는 길도 있었으나,
> **그 핸들러를 쓰는 E2E 가 7개**라 영향 범위가 넓고 새로 증명되는 것이 ②와 겹쳐 택하지 않았다.

### Jira 대조

**① 대응 화면.** Jira Cloud (2025) → 설정 → 이슈 → 워크플로우 → 편집 → **전환 선택 → 우측 패널의
`Conditions` / `Validators` / `Post functions` 3탭**. 각 탭이 규칙 목록 + 「Add」 + 행별 편집/삭제를
갖는 같은 구조다.

**② 조작감 갭.**

| 조작 | Jira Cloud | BTS 현재 | 이 PR |
|---|---|---|---|
| 규칙 진입 | 전환 선택 → 우측 패널 3탭 | **없음** | 전환 행 → 「규칙」 → 다이얼로그 |
| 조건/검증기 구분 | 사용자가 **탭으로 직접 고른다** | `type` 이 `phase` 를 결정하고 사용자는 못 고른다 | **갭을 유지하고 배지로 알린다** (아래 ★) |
| 규칙 종류 선택 | 종류 목록 + 종류별 설정 폼 | 없음 | 편집 가능 3종 + type 별 config 폼 |
| 후처리(post-function) | 같은 패널의 3번째 탭 | API 는 있고 화면 없음 | **범위 밖** — FR-WF-06 D6 정의가 validator 다 |
| 순서 변경 | 드래그 | `displayOrder` 필드만 존재 | 폼 입력값으로만 — 드래그는 범위 밖 |

★ **의도적으로 유지하는 갭.** Jira 는 Condition 과 Validator 를 사용자가 탭으로 나눠 고르지만
BTS 는 `type` 이 `phase` 를 결정한다 — `RequiredField` 만 EXECUTION 이고 나머지 3종은 SPI 기본값
AVAILABILITY 를 상속한다. 화면이 탭을 흉내내면 **사용자가 고를 수 없는 축으로 목록을 가르는**
거짓 UI 가 된다. 대신 응답이 주는 `phase` 를 행 배지로 보여 「이 규칙이 버튼을 감추는지 눌렀을 때
막는지」를 읽게 한다. `ValidatorDtos` KDoc 이 `phase` 를 응답에 실은 이유가 정확히 이것이다.

**③ 즉사 계약(§2) 교차.**

| 계약 | 이 PR 의 대응 |
|---|---|
| `role="dialog"` 고유 label | 신규 다이얼로그에 고유 `aria-label` 부여. 기존 편집기는 `TransitionFormDialog`·`StatusPickerDialog` 2종이라 셋이 한 화면에서 충돌하지 않게 이름을 다르게 둔다 |
| `<h1>` 단 하나 | 다이얼로그는 `h1` 을 만들지 않는다 — 편집기 `h1`(워크플로우 이름)이 유일해야 한다 |
| 관리 메뉴 기본 펼침 | 건드리지 않는다 |
| 나머지 6종 | 이 PR 의 표면 밖 |

**④ 재사용 자산(§4) — 형제 post-action UI 를 그대로 승계한다.** 프리미티브(`dialog`·`button`·
`select`·`input`·`badge`·`EmptyState`)뿐 아니라 **구조 전체**가 이미 있다.

| validator 쪽 신규 | 승계할 형제 (FR-NT-05) | 승계하는 것 |
|---|---|---|
| `ValidatorConfigSection.tsx` | `PostActionConfigSection.tsx` | `isSystemAdmin` 게이팅(아니면 `null` 반환) · 전환 `<select>` · 표 레이아웃 · 행별 `aria-label` 패턴 |
| `ValidatorFormDialog.tsx` | `PostActionFormDialog.tsx` | 다이얼로그 골격 · 프리필 · 에러 표시 |
| `api/validators.ts` | `api/post-actions.ts` | zod 계약 · `dataOf` · **`nestedErrorBodySchema`**(`{error:{code,message}}` 파서) |
| `hooks/use-validators.ts` | `use-post-actions.ts` | `*_KEYS.list(workflowKey, transitionKey)` 팩토리 · **invalidate-only**(플리커 회피) |
| `i18n/validator-labels.ts` | `post-action-labels.ts` | `dialog`·`form`·`section`·`list`·`error` 5키 구조 |
| `mocks/validator-handlers.ts` | `post-action-handlers.ts` | stateful 인메모리 관례 |
| `e2e/workflow-validator.spec.ts` | `workflow-post-action.spec.ts` | T1 노출 · T2 CRUD 전체 · T3 비admin 미노출 · **T4 2행 프리필 격리** |

**★ 진입점도 형제 옆이다** (Maxi 결정, 게이트 1). `routes/workflows.$key.tsx`(워크플로우 상세)에
`PostActionConfigSection` 과 나란히 둔다 — 「전환 규칙」이 한 화면에 모이고, 관리자가 검증기와
후처리를 같은 맥락에서 본다. `/admin/workflows/$workflowKey` 편집기(#400)에는 붙이지 않는다.

**★ 형제가 안 가진 것 3가지** — `phase` 배지 · `editable`(형제는 하드코딩) · 편집 가능 type 3종 선택
(형제는 `CALL_WEBHOOK` 하나만).

**★ zod 관례가 두 벌로 갈려 있다.** `api/post-actions.ts` 는 `.strict()` 를 안 걸고
`workflows-admin.types.ts` 는 base 에 건다. validator 는 **후자**를 따른다 — 백엔드가 필드를 더해도
계약 갭이 런타임에 드러난다. 형제를 그 관례로 옮기는 것은 이 PR 범위 밖이다.

### 기능 요구사항 (FR)

| ID | 요구 | 근거 |
|---|---|---|
| FR-1 | 전환 목록의 각 행에서 **전환 규칙 다이얼로그**를 연다 | D6 |
| FR-2 | 다이얼로그는 그 전환의 validator 를 `displayOrder` 순으로 보여준다. **편집 불가 행도 숨기지 않는다** | 백엔드 계약 (「반쪽 목록은 관리자를 속인다」) |
| FR-3 | 각 행에 `type` · 필수 config 값 · **평가 시점 배지** · 편집/삭제를 보여준다 | Jira 대조 ★ |
| FR-4 | 규칙 추가 — type 선택 후 그 type 의 config 폼을 그린다. **선택지는 `editable=true` 인 type 만** | 부채 2 |
| FR-5 | 규칙 수정 — 기존 값을 채운 같은 폼. `editable=false` 행은 편집 진입이 비활성이고 이유를 보여준다 | 부채 2 · S4 |
| FR-6 | 규칙 삭제 — 하드 삭제임을 알리는 확인을 거친다 | ADR hard-delete |
| FR-7 | 400·403·404 를 다이얼로그 안에서 사람이 읽을 수 있는 문구로 보여주고 다이얼로그를 닫지 않는다 | S6 |
| FR-8 | **`ValidatorResponse.editable: Boolean`** 을 추가한다. 허용 목록 판정을 함수 하나로 뽑아 서비스의 거부 경로와 응답 변환이 **같은 한 벌**을 부른다 | 부채 2 |
| FR-9 | 인스턴스화 실패 행은 `phase = null` 과 짝을 맞춰 `editable = false` 다 | 부채 2 · S5 |
| FR-10 | `ValidatorExceptionHandler` · `PostActionExceptionHandler` **양쪽**에 프레임워크 예외 3종을 `{error:{code,message}}` 봉투로 추가한다 | 부채 1 |
| FR-11 | 「허용 목록은 `editable` 분기가 정본」을 계약 문서에 적고 KDoc 사본을 지운다 | 부채 3 |
| FR-12 | E2E — 규칙 CRUD 화면 계약 + `editable=false` 비활성 + AVAILABILITY 규칙이 이슈 드롭다운에서 전환을 감춘다 | D7 (Maxi 승인 범위) |

### 비기능 요구사항 (NFR)

| 항목 | 기준 |
|---|---|
| 권한 | 모든 경로가 `MANAGE_SCHEME` + Global. **GET 포함** — 가드가 리소스 조회보다 먼저다(403 이 존재 probe 가 되지 않게) |
| 접근성 | 다이얼로그 고유 `aria-label` · 폼 컨트롤 `<label>` 결합 · 평가 시점 배지는 색만으로 뜻을 전하지 않는다(텍스트 병기) · WCAG AA |
| 시각 | `DESIGN.md` 토큰만 사용. elevation 은 `ring-1 ring-foreground/10` 관례 |
| 성능 | 규칙 목록은 전환 단위라 행 수가 작다. 별도 목표 없음 |
| 계약 안정성 | `editable` 은 **추가** 필드다. 기존 소비자 0건이라 파괴 변경이 아니다 |

### API 인터페이스 (REST)

**경로 (#404 실물 — 신규 엔드포인트 0건).**

```
GET    /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators      200 {data:[…]}
POST   /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators      201 {data:{…}}
PUT    /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators/{id} 200 {data:{…}}
DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators/{id} 204
```

**응답 DTO — 이 PR 이 `editable` 한 필드를 더한다.**

| 필드 | 타입 | 비고 |
|---|---|---|
| `id` | UUID | |
| `type` | String | `RequiredField` · `permission-check` · `not-status-category` · `CustomExpression` |
| `config` | Map | type 별 키가 다르다 |
| `displayOrder` | Int | |
| `phase` | String? | `AVAILABILITY` / `EXECUTION` / **`null`**(인스턴스화 실패) |
| **`editable`** | **Boolean** | **신규.** 허용 목록 판정 결과. `phase=null` 이면 항상 `false` |

**요청 DTO.** `ValidatorRequest(type, config, displayOrder)` — 무변경.

**❓ 발견 (G1) — `editable` 판정이 인스턴스를 두 번 만들지 않게 한다.** `phase` 와 `editable` 은
**같은 인스턴스**에서 나온다. 지금 `ValidatorController.phaseOf` 가 행마다 `factory.create` 를
한 번 부르고 있으므로, 판정 함수는 **`type`/`config` 가 아니라 이미 만든 인스턴스를 받는다** —
`fun isEditable(v: WorkflowValidator): Boolean`. 서비스의 거부 경로도 dry-run 으로 만든 인스턴스를
그 함수에 넘긴다. 이렇게 해야 부채 장부의 「값은 이미 공짜다」가 실제로 공짜가 되고, 400 과 응답이
**구조적으로** 어긋날 수 없다. 인스턴스화가 실패한 행은 넘길 인스턴스가 없으므로
`phase=null` · `editable=false` 가 자동으로 짝을 이룬다 (FR-9).

**에러 표 — 굵은 3행이 이 PR 이 더하는 것(부채 1).**

| 상황 | 상태 | code |
|---|---|---|
| 권한 없음 | 403 | `WORKFLOW_SCHEME_ACCESS_DENIED` |
| config·type 부적합 | 400 | `WORKFLOW_VALIDATOR_INVALID` |
| 편집 불가 type | 400 | `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE` |
| 전환·규칙 미존재 | 404 | `WORKFLOW_VALIDATOR_NOT_FOUND` |
| **비-UUID `{id}`** | **400** | **신규 — `MethodArgumentTypeMismatchException`** |
| **본문 형식 오류(깨진 JSON · `type` 누락)** | **400** | **신규 — `HttpMessageNotReadableException`** |
| **미인증** | **401** | **신규 — `ResponseStatusException`** (아래 ✅) |

**✅ 확인 (G3) — 401 은 advice 에 도달한다.** 이 401 은 Spring Security 필터가 아니라
`CurrentActor.current()` 가 **컨트롤러 메서드 실행 중**에 던진다(`ManageSchemeGuard.requireManageScheme`
→ `CurrentActor`). 따라서 `@RestControllerAdvice(basePackages=…)` 가 잡을 수 있다 — 필터 체인에서
났다면 DispatcherServlet 앞이라 advice 로 못 잡고 이 처방 자체가 성립하지 않았다. 착수 전에 실측해
확인했다.

**❓ 발견 (G2) — post-action 쪽 3종은 화면 소비자 없이 추가된다.** post-action 편집 UI 는 이 PR 의
범위 밖이라 그쪽 3종 핸들러에는 당장 부르는 화면이 없다. 그래도 **양쪽에 함께 넣는다** — 장부가
「양쪽」이라 적은 이유는 형제 표면이 갈리는 것을 막는 것이고, 한쪽만 고치면 다음 사람이 두 파일을
비교하며 어느 쪽이 정본인지 다시 판단해야 한다. 테스트도 양쪽에 대칭으로 둔다(완료 기준 3번).

★ **`search-export-import` 의 `ProblemDetail`(RFC 7807) 을 이식하지 않는다.** 그쪽은 봉투가 달라
이식하면 이 BC 의 `{error:{code,message}}` 계약이 깨진다. 봉투는 `com.bts.workflow.web.ErrorResponse`
/`ErrorBody` 한 벌을 그대로 쓴다 (#404 게이트 1 결정).

### 데이터 모델 변경

**없음. 마이그레이션 0건.** `workflow_validators` 는 V200 기존 테이블이고 이 PR 은 읽기·쓰기만 한다.
jOOQ 재생성도 불필요하다.

### 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| E1 | `phase=null` 행 | 배지 `판정 불가` · 편집 비활성 · 삭제 가능. 목록 전체는 200 |
| E2 | `editable=false` 행 | 목록에 **보인다**. 편집 비활성 + 이유. 삭제 가능 |
| E3 | 백엔드가 프론트 모르는 새 type 을 준다 | 그 행을 **읽기 전용으로 표시**한다. 폼을 추측해 그리지 않는다 (§제약 C1) |
| E4 | 규칙 0건 | 빈 상태 문구 + 「규칙 추가」 |
| E5 | 저장 중 403 (권한이 도중에 회수됨) | 다이얼로그 유지 + 권한 오류 문구 |
| E6 | 같은 전환에 같은 type 을 2번 건다 | 백엔드가 막지 않는다 — 화면도 막지 않고 두 행으로 보여준다 |
| E7 | `config` 폼에 백엔드가 안 읽는 키가 들어간다 | 저장은 **성공**한다(부채 4 미해소 · 범위 밖). 폼이 그 키를 만들지 않는 것으로 회피한다 |
| E8 | 전환 삭제 후 열려 있던 규칙 다이얼로그 | 404 문구를 보이고 목록을 refetch 한다 |
| **E9** | **수정할 행의 `config` 에 폼이 모르는 키가 이미 있다** | **그 키를 보존한다** (❓ 발견 G4 · §제약 C6) |

### 제약 조건

- **C1. 프론트는 type 목록을 하드코딩하지 않는다.** 선택지는 응답의 `editable` 로 정한다. 다만
  **type 별 config 폼 스키마는 프론트가 가질 수밖에 없다**(폼을 그려야 하므로). SDD §7.3 이 이미
  경고한 자리다 — 「이 열이 편집 화면 입력 폼의 계약이다. 화면이 타입별 키를 자기 코드에 들면 그것이
  팩토리와 갈리는 두 번째 목록이 되고, 갈린 사실은 저장 시점 400 으로만 드러난다」.
  **★ 그 사본은 이미 프로덕션에 있다.** `PostActionConfigSection.tsx` 가 `CALL_WEBHOOK` 의
  `url`/`method` 를 손으로 들고 있고, 편집 가능 판정까지
  `action.type === 'CALL_WEBHOOK' && extractWebhookValues(action.config) !== null` 로 하드코딩한다 —
  **부채 2 가 막으려는 것의 post-action 판본**이다. 판별식 파일 머리 주석이 이것을 이미 결함으로
  적어 뒀다(「팩토리가 키를 하나 바꾸면 화면만 조용히 틀려진다」).
  **처방.** ① 모르는 type 은 폼을 추측하지 않고 읽기 전용으로 degrade 한다(낡아도 거짓말하지 않는다)
  ② `scripts/workflow/validator-type-catalog.test.ts` 에 **프론트 폼 스키마 ↔ SDD 표** 축을
  **양쪽 표면(validator §7.3 · post-action §7.4)** 으로 추가한다 (Maxi 결정, 게이트 1) — validator 만
  대조하면 새 축이 알려진 결함을 한 표면 남긴 채 초록이 되고, 그것이 `partial-column-parser-lets-unread-column-rot`
  양식의 재생산이다. **post-action 쪽 하드코딩 자체를 고치는 것은 범위 밖**이고, 축이 그것을 red 로
  드러내면 그 red 를 부채로 등재한다(축은 표↔스키마 일치를 보므로 형제도 지금 상태로 통과할 수 있다 —
  Task 7 이 착수 시 실측한다).
- **C2.** 다이얼로그는 `h1` 을 만들지 않는다.
- **C3.** MSW 핸들러에 **validator 평가 로직을 만들지 않는다** — 백엔드 엔진의 두 번째 사본이 되고
  아무도 대조하지 않는다. ~~AVAILABILITY 효과는 `/transitions/plan` 응답 픽스처로 표현한다~~
  → **그 표현 수단은 성립하지 않았다**(위 S7 각주). AVAILABILITY 효과는 E2E 가 재지 않고 백엔드에
  위임한다. **평가 로직 금지는 그대로 유효하고, 실제로 spec·MSW 모두에 0줄이다.**
- **C4.** 화면은 `transitionKey` 자리에 **전환 id(UUID) 만** 싣는다. 종전 합성 키는 구 경로 호환용이라
  새 소비자가 쓰면 「유일하지 않음 → 404」 경로를 새로 연다.
- **C6.** **수정 폼은 로드한 `config` 를 baseline 으로 들고 아는 키만 덮어써서 전체를 보낸다.**
  PUT 은 표현 전체 교체라 폼이 아는 키만 담아 보내면 **모르는 키가 조용히 지워진다** — 손으로 넣은
  값이나 나중 버전에서 추가한 키가 편집 한 번에 사라지는 자리다 (❓ 발견 G4).
- **C7.** **`displayOrder` 는 사용자가 직접 입력하지 않는다.** 새 규칙은 `현재 최대값 + 1`,
  기존 행은 로드한 값을 그대로 되돌려 보낸다. 순서 변경 UI 는 범위 밖이므로 입력칸을 만들면
  「고칠 수 있는 것처럼 보이지만 순서에 의미가 드러나지 않는」 칸이 된다 (❓ 발견 G5).
- **C5.** 부채 4·5·6 은 건드리지 않는다.

### 측정 가능한 완료 기준

| # | 기준 | 확인 |
|---|---|---|
| 1 | `ValidatorResponse` 에 `editable` 이 실린다 | `ValidatorControllerTest` — 편집 가능 3종 `true` · `CustomExpression` `false` · 깨진 행 `false` |
| 2 | 허용 목록 판정이 **한 벌**이다 | 뮤테이션 — 판정 함수를 뒤집으면 400 테스트와 응답 테스트가 **함께** red |
| 3 | 프레임워크 예외 3종이 봉투를 탄다 | `ValidatorControllerTest` + `PostActionControllerTest` 양쪽에 3종 × 2 = 6 단언 |
| 4 | 규칙 CRUD 가 화면에서 동작한다 | 유닛(RTL) — 추가·수정·삭제 후 목록 갱신 |
| 5 | `editable=false` 가 화면에 반영된다 | 유닛 — 편집 비활성 + 추가 선택지에서 제외 |
| 6 | E2E 화면 계약 | `apps/web/e2e/workflow-validator.spec.ts` — S1·S2·S3·S4 + 프리필 격리. **S7 은 위임**(위 각주) |
| 7 | 프론트 폼 스키마가 SDD 표와 일치한다 | `validator-type-catalog.test.ts` 새 축 — 한쪽만 고치면 red |
| 8 | 브라우저 눈확인 | 라이트/다크 × (기본 · 빈 · 에러) 상태. 결과를 게이트 2 요약에 |
| 9 | 회귀 0 | `./gradlew :modules:project-workflow:test` · `pnpm verify` · `pnpm test:workflow` · 기존 `workflow-editor.spec.ts` 동반 실행 |

## Sanity Check

스펙을 4항목(누락 요구사항 · 모호한 표현 · 가정 누락 · 엣지 케이스 미커버)으로 흔들어 **gap 5건**을
찾았다. 1건은 실측으로 해소했고 4건은 1회 보강해 스펙 본문에 반영했다 — 보강 지점마다
`❓ 발견` / `✅ 확인` 라벨을 남겼다. **Maxi 결정이 필요한 잔여 gap 은 없다.**

| # | 항목 | 종류 | 처리 |
|---|---|---|---|
| G1 | `editable` 판정이 `phase` 와 별도로 인스턴스를 또 만드는지 스펙이 말하지 않았다 | 가정 누락 | 보강 — 판정 함수가 **이미 만든 인스턴스를 받는다**(§API). 400 과 응답이 구조적으로 어긋날 수 없다 |
| G2 | post-action 쪽 예외 3종은 이 PR 에 소비 화면이 없다 | 모호 | 보강 — 형제 표면 갈림 방지가 목적임을 명시하고 테스트를 대칭으로 둔다 |
| G3 | **401 을 `@RestControllerAdvice` 가 잡을 수 있는가** — 필터 체인에서 나면 못 잡는다 | 실현 가능성 | **실측 해소** — `CurrentActor.current()` 가 컨트롤러 실행 중에 던진다. 처방 성립 |
| G4 | 수정 시 폼이 모르는 `config` 키가 PUT 전체 교체로 지워진다 | 엣지 미커버 | 보강 — E9 + 제약 C6(baseline 병합) |
| G5 | `displayOrder` 를 사용자가 어떻게 정하는지 불명확 | 모호 | 보강 — 제약 C7(입력칸 없음 · 최대값+1) |

**남은 최대 위험은 gap 이 아니라 제약 C1 이다.** 프론트는 type 별 config 폼 스키마를 가질 수밖에
없고 그것이 팩토리와 갈리는 두 번째 목록이 된다 — SDD §7.3 이 D6 을 향해 미리 적어 둔 경고이고,
메모리 `two-lists-never-check-each-other` 의 지배 결함 양식 그대로다. 처방(모르는 type 은 읽기 전용
degrade + `validator-type-catalog.test.ts` 에 프론트 축 추가)을 완료 기준 7번으로 세웠다.

## Plan

> **순서의 근거.** 부채 장부가 `editable` 을 「D6 착수 시 첫 task」로 지정했다. 프론트가 그것 없이
> 시작하면 편집 가능 타입 목록을 자기 코드에 베끼게 되고, 그 사본이 이 PR 이 막으려는 결함이다.
> Task 1 이 계약을 세운 뒤에야 프론트가 그 계약을 소비할 수 있다.

### Task 1. `editable` 판정을 한 벌로 세우고 응답에 싣는다 (부채 2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorEditability.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorDtos.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorAdminServiceTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/web/ValidatorControllerTest.kt`]
- depends-on: []

**RED**:
- 파일. `ValidatorControllerTest.kt` · `ValidatorAdminServiceTest.kt`
- 테스트.
  ```kotlin
  // ValidatorControllerTest — 응답 쪽
  @Test fun `목록 응답이 편집 가능 3종에 editable=true 를 싣는다`()
  @Test fun `CustomExpression 행은 editable=false 로 나온다`()
  @Test fun `인스턴스화가 실패하는 행은 phase=null 과 editable=false 가 짝을 이룬다`()
  // ValidatorAdminServiceTest — 거부 쪽이 같은 판정을 부르는지
  @Test fun `거부 판정과 응답 판정이 같은 함수를 부른다`()  // 아래 뮤테이션이 본단언
  ```
- 실패 메시지 (예상). `ValidatorResponse` 에 `editable` 프로퍼티 없음 → 컴파일 실패.
  `ValidatorEditability.kt` 부재 → `Unresolved reference: isEditable`.

**GREEN**:
- 신규 `ValidatorEditability.kt` — `internal fun isEditable(validator: WorkflowValidator): Boolean`.
  **인자는 `type`/`config` 가 아니라 이미 만들어진 인스턴스다.** `ValidatorController.phaseOf` 가
  행마다 `factory.create` 를 이미 한 번 부르고 있으므로 그 인스턴스를 재사용한다 — 부채 장부의
  「값은 이미 공짜다」가 실제로 공짜가 되는 자리다. 판정은 `is` 검사이고 문자열 비교가 아니다
  (#404 결정 — 문자열을 적으면 팩토리 분기·`override val type` 에 이은 세 번째 사본이 된다).
- `ValidatorAdminService` 의 거부 경로가 dry-run 인스턴스를 **같은 함수**에 넘기도록 고친다.
- `ValidatorDtos` 에 `editable: Boolean` 추가. `from(row, phase)` → `from(row, phase, editable)`.
- `ValidatorController.toResponse` 가 인스턴스를 한 번 만들어 `phase` 와 `editable` 을 함께 뽑는다.

**REFACTOR**:
- `phaseOf` 를 「인스턴스 1회 생성 → (phase, editable) 한 쌍 반환」으로 정리. `IllegalArgumentException`
  만 잡는 기존 계약(`runCatching` 금지 이유가 KDoc 에 있다)을 그대로 지킨다.

**검증**:
- `./gradlew :modules:project-workflow:test --tests '*Validator*'`
- **뮤테이션 (GREEN 선커밋 뒤)**. `isEditable` 의 반환을 뒤집는다 → **400 테스트와 응답 테스트가
  함께 red** 여야 한다. **한쪽만 red 면 두 벌이 남아 있다는 뜻**이므로 그 자리에서 되돌리고 원인을
  찾는다. 미커밋 상태로 뮤테이션하지 않는다 — 원복이 곧 소실이다.

### Task 2. 프레임워크 예외 3종을 도메인 에러 봉투에 담는다 (부채 1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorExceptionHandler.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/web/PostActionExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/web/ValidatorControllerTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/web/PostActionControllerTest.kt`]
- depends-on: [1]   # ValidatorControllerTest.kt 를 Task 1 과 공유한다 (파일 겹침 → 직렬)

**RED**:
- 파일. `ValidatorControllerTest.kt` · `PostActionControllerTest.kt` — **양쪽 대칭**
- 테스트. 두 표면 각각에 세 가지를 전수로 둔다.
  ```kotlin
  @Test fun `비-UUID id 는 400 과 error 봉투로 나간다`()          // MethodArgumentTypeMismatchException
  @Test fun `깨진 JSON 본문은 400 과 error 봉투로 나간다`()        // HttpMessageNotReadableException
  @Test fun `미인증 요청은 401 과 error 봉투로 나간다`()           // ResponseStatusException
  ```
- 실패 메시지 (예상). 응답 본문에 `$.error.code` 가 없다 — 스프링 기본 형식(`timestamp`/`path`)이
  나온다. 401 은 `CurrentActor.current()` 가 **컨트롤러 실행 중**에 던지므로 advice 로 잡히지만
  현재 핸들러가 없어 기본 형식으로 샌다(스펙 §API ✅ G3 실측).

**GREEN**:
- 양쪽 advice 에 `@ExceptionHandler` 3종 추가. 봉투는 `com.bts.workflow.web.ErrorResponse`/`ErrorBody`
  **한 벌 그대로**.
- ★ `search-export-import` 의 `ProblemDetail`(RFC 7807) 을 **이식하지 않는다** — 봉투가 달라
  이식하면 이 BC 계약이 깨진다.
- 에러 코드는 기존 명명(`WORKFLOW_VALIDATOR_*` / post-action 대응)과 같은 계열로 둔다.

**REFACTOR**:
- 두 핸들러의 3종이 문자열까지 같아지면 공용 헬퍼로 접는다. **다만 advice 자체는 합치지 않는다** —
  `basePackages` 가 다르고 합치면 다른 컨트롤러의 매핑까지 가져간다.

**검증**:
- `./gradlew :modules:project-workflow:test --tests '*ValidatorControllerTest' --tests '*PostActionControllerTest'`
- 계약 문서 에러 표에 401 행과 「본문 형식 오류」 400 행이 추가돼야 한다 (Task 3 이 함께 본다)

### Task 3. 「허용 목록은 `editable` 분기가 정본」을 계약 문서에 못박는다 (부채 3)

**메타**.
- agent: `backend-engineer`
- files: [`docs/sdd/07-workflow-engine.md`, `docs/plans/2026-08-25-backend-workflow-transition-rule-crud.md`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminService.kt`]
- depends-on: [1]

**RED**:
- 이 task 의 red 는 테스트가 아니라 **문서↔코드 차집합**이다. `validator-type-catalog.test.ts` 를
  먼저 돌려 현재 GREEN 임을 확인하고(이 task 는 표를 깨지 않아야 한다), 그 다음 R3-11 이 지목한
  서술을 실제로 찾아 인용한다 — 「편집 불가 = `type=CustomExpression`」로 적힌 자리 전수.
- 실패 조건. 문서가 **한 값**(`CustomExpression`)을 말하고 코드는 **허용 목록**(3종)을 쓴다.
  지금은 type 이 4종이라 두 문장이 우연히 같은 뜻이고, 다섯 번째가 생기는 날 문서가 거짓이 된다.

**GREEN**:
- 세 자리를 같은 문장으로 맞춘다 — 「편집 가능 여부의 정본은 `isEditable` 분기다. 목록에 없는
  type 은 **모르는 것이므로 거부**된다(새 type 이 자동으로 편집 불가가 된다)」.
- `ValidatorAdminService` KDoc 의 허용 목록 사본을 지우고 `isEditable` 을 가리킨다.
- SDD §7.3 에 `editable` 열 또는 각주를 더한다 — **판별식이 읽는 세 열(`타입 식별자`·`구현 클래스`·
  `필수 config 키`)의 파싱을 깨지 않는 형태**여야 한다.

**REFACTOR**: 없음 (문서 정합 task).

**검증**:
- `pnpm test:workflow` — `validator-type-catalog.test.ts` 가 여전히 GREEN (표 파싱을 안 깼다)
- `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh`

### Task 4. 프론트 API 계층 — 형제 `api/post-actions.ts` 대칭

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/validators.ts`, `apps/web/src/api/__tests__/validators.test.ts`]
- depends-on: [1]   # `editable` 필드 계약이 먼저 서야 한다

**RED**:
- 파일. `apps/web/src/api/__tests__/validators.test.ts` (형제 `post-actions.test.ts` 구조 승계)
- 테스트.
  ```ts
  it('validatorResponseSchema 가 백엔드 6필드를 받는다', …)   // id·type·config·displayOrder·phase·editable
  it('editable 이 빠진 응답을 거부한다', …)                    // 계약 갭이 런타임에 드러나게
  it('phase=null 을 받아들인다', …)                            // 인스턴스화 실패 행
  it('알 수 없는 필드가 오면 거부한다', …)                      // .strict() 봉인
  it('400 본문의 error.code 를 뽑아낸다', …)                    // nestedErrorBodySchema 승계
  ```
- 실패 메시지 (예상). `apps/web/src/api/validators.ts` 부재 → import 실패.

**GREEN**:
- 형제 `api/post-actions.ts` 를 **구조 그대로** 옮긴다 — `basePath(workflowKey, transitionKey)` ·
  `dataOf` · **`nestedErrorBodySchema`**(`{error:{code,message}}` 파서, 부채 1 이 넓히는 그 봉투다).
- ★ **`.strict()` 는 건다** — 형제는 안 걸지만 `workflows-admin.types.ts` 관례를 따른다(스펙 §Jira 대조 ④).
- **type 별 config 폼 스키마**를 여기 둔다 — 제약 C1 의 불가피한 사본이고 Task 7 이 SDD §7.3 과 대조한다.
- 경로 세그먼트는 **전환 id(UUID)** 만 싣는다 (제약 C4 · 형제 주석이 같은 이유를 이미 적었다).

**REFACTOR**: 형제와 겹치는 헬퍼가 3개를 넘으면 공용으로 뽑되, **형제 파일을 이 PR 에서 고치지 않는다**
(범위 밖 · 형제 테스트를 인질로 잡는다).

**검증**:
- `pnpm --filter web test -- validators`
- ★ zod 스키마를 `ValidatorDtos.kt` 실물과 **눈으로 대조**한다 (learnings 2026-06-25 / PR #187 —
  MSW·유닛·E2E 3겹이 동시에 가짜그린일 수 있다).

### Task 5. 훅 + MSW 핸들러 — 형제 대칭

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-validators.ts`, `apps/web/src/mocks/validator-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/src/hooks/__tests__/use-validators.test.tsx`]
- depends-on: [4]

**RED**:
- 테스트.
  ```tsx
  it('전환을 고르면 그 전환의 목록을 가져온다', …)
  it('추가 성공 후 목록 쿼리가 무효화된다', …)          // invalidate-only (플리커 회피 — 형제 관례)
  it('삭제 성공 후 목록에서 사라진다', …)
  ```
- 실패 메시지 (예상). `use-validators` 부재 → import 실패. MSW `/validators` 경로 미등록 → 요청 실패.

**GREEN**:
- `use-validators.ts` — `VALIDATOR_KEYS.list(workflowKey, transitionKey)` 팩토리 + `useValidators` ·
  `useAddValidator` · `useUpdateValidator` · `useDeleteValidator`. **invalidate-only** (형제와 같은 이유).
- `validator-handlers.ts` — 4 경로. `handlers.ts` 에 등록한다(**등록을 빠뜨리면 핸들러가 있어도 안 탄다**).
- 픽스처에 **편집 가능 3종 각각 · `CustomExpression` 1행 · 깨진 config 1행**을 둔다 — S4·S5 가 화면에서
  재현되려면 목이 그 행을 실제로 갖고 있어야 한다.
- ★ **평가 로직을 만들지 않는다** (제약 C3). 저장·조회만 한다.

**REFACTOR**: 픽스처의 type 문자열을 한 곳에서 정의해 스펙 간 오타를 막는다.

**검증**: `pnpm --filter web test -- use-validators validator-handlers`

### Task 6. `ValidatorConfigSection` + `ValidatorFormDialog` (D6 본체)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/workflow/ValidatorConfigSection.tsx`, `apps/web/src/components/workflow/ValidatorFormDialog.tsx`, `apps/web/src/i18n/validator-labels.ts`, `apps/web/src/routes/workflows.$key.tsx`, `apps/web/src/components/workflow/__tests__/ValidatorConfigSection.test.tsx`, `apps/web/src/components/workflow/__tests__/ValidatorFormDialog.test.tsx`]
- depends-on: [5]

**RED** (동반 테스트 명세 — ui 시각 트랙):
- 테스트.
  ```tsx
  it('isSystemAdmin 이 아니면 렌더하지 않는다', …)                        // 형제 게이팅 승계
  it('전환을 고르면 규칙 목록이 뜬다', …)
  it('편집 불가 행이 목록에 보이고 편집이 비활성이며 이유가 뜬다', …)      // S4 · editable 소비
  it('추가 폼의 type 선택지에 CustomExpression 이 없다', …)               // editable=true 만
  it('phase 배지가 텍스트로 병기된다 (색만으로 뜻을 전하지 않는다)', …)     // 접근성
  it('phase=null 행은 판정 불가 배지 + 편집 비활성이다', …)                // S5
  it('모르는 type 은 폼을 그리지 않고 읽기 전용으로 보여준다', …)           // 제약 C1 degrade
  it('저장 400 이 다이얼로그 안에 뜨고 다이얼로그가 닫히지 않는다', …)       // S6
  it('삭제는 되돌릴 수 없음을 확인받는다', …)                             // ADR hard-delete
  it('2행 중 A 를 편집하면 A 값이 프리필되고 B 값이 새지 않는다', …)         // ★ 형제 E2E T4 가 잡은 회귀
  ```
- 실패 메시지 (예상). `ValidatorConfigSection` 부재 → 렌더 실패.

**GREEN**:
- **형제 `PostActionConfigSection` 구조를 그대로 따른다** — `user?.isSystemAdmin !== true` 면 `null`,
  전환 `<select>`(값은 전환 id UUID), 표, 행별 `aria-label`.
- `ValidatorFormDialog` 는 형제 다이얼로그 골격 + **고유 `aria-label`**(즉사 계약).
  `h1` 을 만들지 않는다 (제약 C2).
- 행 배지는 `phase` 를 **텍스트로 병기**한다 — `실행 시 차단`(EXECUTION) / `후보에서 감춤`(AVAILABILITY) /
  `판정 불가`(null).
- 수정 시 **로드한 `config` 를 baseline 으로 들고 아는 키만 덮어써서 전체를 보낸다** (제약 C6).
  ★ 형제는 `config: { url, method }` 로 전체 교체해 미지 키를 지운다 — **그 동작을 복제하지 않는다.**
- `displayOrder` 입력칸을 만들지 않는다. 새 규칙은 최대값+1 (제약 C7).
- `routes/workflows.$key.tsx` 에 `PostActionConfigSection` 과 **나란히** 배치한다.

**REFACTOR**: type 별 config 폼을 한 컴포넌트의 분기로 두고 스키마는 Task 4 의 것을 소비한다 —
폼이 자기 키 목록을 따로 들지 않게 한다.

**검증**:
- `pnpm --filter web test -- ValidatorConfigSection ValidatorFormDialog` · `pnpm verify`
- 기존 E2E **동반 실행**: `apps/web/e2e/workflow-post-action.spec.ts` (**같은 라우트를 공유한다** —
  §5 사전 grep 결과. 형제 섹션이 밀리거나 셀렉터가 충돌하면 여기서 red 가 난다) ·
  `apps/web/e2e/workflow-editor.spec.ts`
- 눈확인: 규칙 섹션 — 기본 · 빈(규칙 0건) · 에러(400) 3상태 × 라이트/다크

### Task 7. 폼 스키마 ↔ SDD 표 대조 축 — **양쪽 표면** (제약 C1 처방)

**메타**.
- agent: `qa-engineer`
- files: [`scripts/workflow/validator-type-catalog.test.ts`]
- depends-on: [6]

**RED**:
- 새 축을 먼저 쓰고 **일부러 끊어** red 를 본다. 프론트 config 폼 스키마에서 키 하나를 지우면
  판별식이 **그 키를 이름으로 지목**해야 한다. 「개수가 다르다」로만 나오면 판정이 약한 것이다.
- 실패 메시지 (예상). 처음엔 축이 없어 GREEN — 그래서 **비-공허 확인이 이 task 의 본체**다.

**GREEN**:
- 축을 **양쪽 표면**으로 세운다 (Maxi 결정, 게이트 1) — validator 폼 스키마 ↔ SDD §7.3,
  post-action 폼 스키마 ↔ SDD §7.4. validator 만 대조하면 판별식 주석이 이미 결함으로 적어 둔
  `PostActionConfigSection` 의 `url`/`method` 하드코딩을 새 축이 빠뜨린다.
- 기존 축의 파서 구조를 그대로 쓴다 — SDD 표 파싱은 이미 있고 프론트 스키마 쪽 파서만 더한다.
  **양방향 차집합 0**(표에만 있는 키 · 스키마에만 있는 키 둘 다) + 「필수/선택」 구분(표의 `(선택)` 접미 ↔
  스키마 optional).
- ★ **비-공허 짝(line 688 부근)에 새 파서를 등록한다.** 기존 테스트가 「뽑아낸 집합이 하나도 비어
  있지 않다」로 파서 death 를 잡는데, 새 파서를 거기 넣지 않으면 그것이 0건을 뱉어도 아무도 모른다 —
  판별식이 자기가 잡으려는 결함을 스스로 재생산하는 자리다.
- ★ post-action 축이 착수 즉시 red 를 내면 **그 red 는 이 PR 이 고치지 않는다**(범위 밖). 부채로
  등재하고 축은 그 상태를 드러낸 채 둔다 — 통과시키려고 축을 약하게 만들지 않는다.

**REFACTOR**: 파일 머리 주석의 「축 4개」 표에 새 축을 반영한다 — **개수 문구를 그대로 두면 거짓이 된다**.

**검증**:
- `pnpm test:workflow`
- **비-공허 (GREEN 선커밋 뒤)**. ① 프론트 스키마에서 키 1개 제거 → red · 그 키가 메시지에 이름으로
  나오는지 ② SDD 표에서 행 1개 제거 → red ③ 새 파서가 0건을 뱉게 만들어 → **비-공허 짝이 잡는지**.
  셋 다 확인 후 되돌린다.

### Task 8. D7 E2E — 형제 `workflow-post-action.spec.ts` 구조 승계

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/workflow-validator.spec.ts`, `apps/web/src/mocks/workflow-handlers.ts`]
- depends-on: [6]

**RED**:
- 시나리오. 형제 spec 의 T1~T4 골격에 validator 고유분을 얹는다.
  T1 섹션이 SYSTEM_ADMIN 에게 노출 · T2 추가→목록→수정→삭제 전체 CRUD(S1·S3) ·
  T3 비admin 미노출 · T4 **2행 프리필 격리**(형제가 실제로 잡은 회귀) ·
  T5 `CustomExpression` 행은 편집 비활성(S4). ~~T6 AVAILABILITY…(S7)~~ → **미작성 · 위임**(S7 각주 참조).
- 실패 메시지 (예상). 규칙 섹션을 못 찾는다 → Task 6 이전에는 red.

**GREEN**:
- 로그인 헬퍼는 형제와 같은 공용 정본(`auth-fixtures.ts`)을 쓴다. `isSystemAdmin` 토글도 형제 방식
  (`addInitScript` 로 localStorage 플래그 선설정)을 그대로 따른다.
- ~~T6 은 `workflow-handlers.ts` 의 `/transitions/plan` 응답 픽스처로 표현한다~~ → **취소.** 그 엔드포인트에
  화면 소비자가 0건임이 실측됐다(S7 각주). `workflow-handlers.ts` 는 **무변경**이다.
- ★ `serviceWorkers:'block'` 금지 (메모리 `e2e-msw-serviceworker-block`).
- ★ 라벨 substring 주의 — 형제 spec 이 같은 라우트를 쓰므로 셀렉터가 겹치지 않게 `exact: true` 로 집는다.

**REFACTOR**: 형제 spec 과 공용 헬퍼가 3회 이상 반복되면 `fixtures/` 로 뽑는다.

**검증**:
- `pnpm --filter web test:e2e -- workflow-validator workflow-post-action workflow-editor`
  (**형제 spec 동반 필수** — 같은 라우트를 공유한다)
- 눈확인 결과와 함께 게이트 2 요약에 싣는다

## Plan 메타

| 항목 | 값 |
|---|---|
| task 수 | 8 |
| 예상 wave | 5 — ①1 ②2·3·4 ③5 ④6 ⑤7·8 (의존 그래프 무순환 확인) |
| 구현 규율 | TDD red-first (백엔드 Task 1·2) + ui 시각 검증 트랙 (Task 4~6) + 판별식 비-공허 (Task 7) |
| 추가 검증 | `./gradlew :modules:project-workflow:test ktlintCheck detekt` · `pnpm verify` · `pnpm test:workflow` · `pnpm --filter web test:e2e` · `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh` |
| 마이그레이션 | **0건** — jOOQ 재생성 불필요 |
| 뮤테이션 지점 | Task 1(판정 뒤집기 — 양쪽 red 확인) · Task 7(스키마 키 제거 · SDD 행 제거 · 파서 0건) |
| 눈확인 | Task 6 — 기본·빈·에러 3상태 × 라이트/다크 |
| ★ 형제 동반 실행 | `workflow-post-action.spec.ts` — validator 섹션이 **같은 라우트**(`/workflows/$key`)에 들어가므로 형제가 깨지는지 매 단계 본다 |

## 리뷰 결과

**렌즈 2종** (`type == "ui"` · T2) — `plan-design-review` + `plan-eng-review`, 한 응답 병렬 발행.
gstack 부가 절차(telemetry·proactive·routing 주입·mockup 생성·codex outside voice)는 **돌리지 않았다** —
특히 `CLAUDE.md` 에 gstack routing 규칙을 주입하는 단계는 이 저장소의 헌법 파일을 오염시킨다.
두 렌즈의 **판정 내용**만 수행했고, 주장은 코드 실측으로 대조했다.

### 판정 — BLOCKER 4건 (전부 해소 후 진행)

| # | 렌즈 | 판정 | 처리 |
|---|---|---|---|
| B1 | eng | **BLOCKER** — plan 의 재사용 자산 조사가 **틀렸다**. 「프론트 편집 UI 0건」은 `--include` 옵션 오류로 grep 이 **실행되지 않은** 상태에서 내린 판단이었고, 실제로는 형제 post-action UI 가 전부 있다 | ✅ Brief·스펙 §Jira 대조 ④ 정정 · Task 4~8 을 **형제 대칭 구현**으로 전면 재작성 |
| B2 | design | **BLOCKER** — 진입점이 형제와 갈린다. 형제는 `/workflows/$key`(상세), plan 은 `/admin/workflows/$workflowKey`(편집기). 「전환 규칙」이 두 화면으로 흩어진다 | ✅ **Maxi 결정 — 형제 옆 `/workflows/$key`**. 편집기에는 붙이지 않는다 |
| B3 | eng | **BLOCKER** — Task 7 축이 validator 만 대조하면, 판별식 주석이 **이미 결함으로 적어 둔** post-action 의 `url`/`method` 하드코딩을 새 축이 빠뜨린다. `partial-column-parser-lets-unread-column-rot` 의 재생산 | ✅ **Maxi 결정 — 양쪽 표면**(§7.3 + §7.4). 비-공허 짝에 새 파서 등록도 명시 |
| B4 | eng | **BLOCKER(전제 오류)** — 「post-action 예외 3종은 소비 화면이 없다」가 거짓. `api/post-actions.ts` 의 `nestedErrorBodySchema` 가 그 봉투를 **이미 파싱**한다 | ✅ 부채 1 의 정당성이 강해졌다 — 가정 문구를 스펙에서 제거 |

### 실측으로 **해소**된 의심 3건

| # | 의심 | 실측 결과 |
|---|---|---|
| R1 | 부채 1 의 401 을 `@RestControllerAdvice(basePackages)` 가 잡을 수 있는가 — 못 잡으면 처방 자체가 성립하지 않는다 | **잡는다.** `WorkflowSchemeAccessDeniedException` 은 shared-kernel `com.bts.shared.permission` 에서 던져지는데 `basePackages=["com.bts.workflow.validator"]` advice 가 그것을 403 으로 내고 테스트가 통과 중이다 — `basePackages` 는 예외 선언 위치가 아니라 **요청을 처리한 컨트롤러** 기준임이 이 저장소에서 실증돼 있다 |
| R2 | D7 범위 축소의 위임 대상이 실제로 그 행동을 덮는가 | **덮는다.** `ValidatorEngineIntegrationTest` 가 `engine.plan` throw → 삭제 후 `toStateKey` 반환(EXECUTION) · `availableToStateKeys()` 에서 사라짐 → 삭제 후 `contains`(AVAILABILITY). Testcontainers 실 엔진이고 **FR D7 문구를 문자 그대로** 덮는다 |
| R3 | 마이그레이션 0건 주장 | **맞다.** `workflow_validators` 는 V200 기존 테이블, `editable` 은 응답 계산 필드라 컬럼이 아니다 |

### 주의 (BLOCKER 아님 — 진행하되 기록)

1. **`editable` 이 validator 에만 생긴다.** 형제 post-action 은 `type === 'CALL_WEBHOOK' && extractWebhookValues(...) !== null`
   하드코딩으로 남아 두 표면의 편집 가능 판정 방식이 갈린다. **범위 밖**이고 Task 7 축이 그 상태를
   드러낸다 — 드러난 red 는 부채로 등재한다.
2. **`config` 전체 교체 동작이 형제와 다르다.** 형제는 `config: { url, method }` 로 미지 키를 지운다.
   validator 는 제약 C6(baseline 병합)으로 보존한다 — **의도한 비대칭**이고 이유를 C6 에 적었다.
   형제를 고치는 것은 부채 4(장부가 「D6 이후」로 미룸) 와 함께 볼 일이다.
3. **zod `.strict()` 관례가 두 벌이다.** `api/post-actions.ts` 는 안 걸고 `workflows-admin.types.ts` 는 건다.
   validator 는 후자를 따르되 형제를 옮기지 않는다(형제 테스트를 인질로 잡는다).
4. **wave 5 는 유지한다.** 형제 승계로 Task 4·5 의 실질 작업량이 줄어 직렬 비용이 낮아졌다. Task 4→5→6 은
   계약 의존이라 쪼개도 순서가 그대로다.
5. **스펙 deviation 은 전수 동기화 대상이다.** D7 이 FR 문구의 「막힌다/통과한다」를 E2E 로 직접 덮지 않고
   백엔드 D5 에 위임한다 — `docs/rules/fr-sync-checklist.md` 에 따라 `docs/plan/product/project-workflow.md`
   §2.6 D7 항목에 위임 사실을 같은 PR 에서 적는다 (게이트 2 요약에도 싣는다).

### 결과

**BLOCKER 4건 전부 해소 · 주의 5건 기록.** plan 을 고쳐 반영했다 — Brief 오실측 정정 ·
스펙 §Jira 대조 ④ 재사용 자산 표 신설 · 제약 C1 갱신 · Task 4~8 전면 재작성 · Plan 메타에 형제 동반
실행 행 추가. **게이트 1 로 넘긴다.**
