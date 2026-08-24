# 워크플로우 관리 목록 + 목록 모드 편집기 (FR-WF-04 D6·D7)

> 티어: T2
> slug: fr-wf-04-d6-admin-workflows-editor
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-24

## Brief

Maxi 원문 — 「FR-WF-04 D6·D7 — /admin/workflows 워크플로우 관리 목록 + 목록 모드 편집기(상태
추가·제거·순서 변경, 전환 이름 편집) 프론트 구현 + E2E 스펙 작성」. 로드맵
`~/.claude/plans/cozy-hatching-otter.md` **PR 8**. E2E 는 **코드만 작성하고 실행하지 않는다**.

`classify-task` — type=ui · agent=frontend-engineer · primary_bc=project-workflow.

### ★ 티어 정정 (T1 → T2)

착수 시 선언은 **T1** 이었다(`apps/web/src` 전용). 구현을 마치고 push 할 때
`scripts/workflow/tier-floor.test.ts` ⓐ 가 막았다 — `scripts/workflow/surfaces.ts:74` 가
**`apps/web/src/router.ts` 를 `SEC_FE`(보안 표면)로 등록**하고 있고, 보안 표면은 T2 미만이
불가하기 때문이다. 라우트를 주소에 다는 유일한 경로가 그 파일이므로 범위를 줄여 피할 수
없다. `/bts` 판정 5문 ⑤(자동 승격 금지·사람이 결정)에 따라 Maxi 확인 후 **T2 로 승격**했고
이 파일이 그 결과다.

함께 red 였던 `git-fixture-isolation` 은 **파생**이다 — 그 판별식이 자식 프로세스로
`tier-floor` 를 돌리고 실패 메시지가 자식 이름을 직접 지목한다(문서화된 함정).

### 선행 PR 5 를 건너뛴 근거

로드맵 표는 PR 8 의 선행을 「3,4,5」로 적었으나 **PR 5(전환 규칙 validator CRUD)는 비어
있다**(FR-WF-06 D1·D2·D4·D5 전부 `[ ]`). 건너뛴 근거 3건.

1. 로드맵 PR 8 항목 전문에 validator 언급 **0건**.
2. `docs/plan/product/project-workflow.md` §2.4 의 D6 정의도 「목록 + 목록 모드 편집기(상태
   추가·제거·순서)」로 규칙을 안 담는다. 전환 규칙 편집 UI 는 **FR-WF-06 D6** 별도 항목이다.
3. PR 8 이 부르는 엔드포인트가 전수 실재한다(§스펙 참조).

로드맵 표는 `3,4 (★5 아님)` 로 고치고 근거 주석을 달았다.

## 도메인 정리

- **워크플로우 정의**와 **워크플로우 스킴**은 다른 것이다. 정의는 상태·전환의 집합이고,
  스킴은 이슈 유형별로 어느 정의를 쓸지 배정하는 표다(FR-WF-02, 이미 구현됨).
  이번 화면은 **정의** 쪽이다.
- **상태는 사이트 전역 카탈로그**다(FR-WF-04 D1~D3). 워크플로우는 카탈로그의 상태를
  **편성**해 쓴다 — 넣고 빼는 것은 편성이지 상태 자체의 생성·삭제가 아니다.
- **전환의 identity 는 `transitionId`** 다(FR-WF-05). 같은 상태쌍에 이름이 다른 전환이
  여럿 있을 수 있으므로 목록에서 **이름이 1급**이고 경로는 보조다.
- `kind` 3종 — `NORMAL`(출발 상태 있음) · `GLOBAL`(모든 상태에서) · `INITIAL`(이슈 생성 시).
  뒤 둘은 출발 상태가 **없다**.

## 스펙

### 백엔드 계약 (실측 — 컨트롤러 원본 대조)

| 조작 | 엔드포인트 | 요청 | 응답 |
|---|---|---|---|
| 목록·단건 | `GET /api/v1/workflows[/{key}]` | — | `{data: WorkflowView[]\|WorkflowView}` |
| 생성 | `POST /api/v1/workflows` | `{key,name,description?,statuses[]}` | 201 `{data:{key}}` |
| 수정 | `PUT /api/v1/workflows/{key}` | `{name,description?}` — **key 없음** | `{data:null}` |
| 삭제 | `DELETE /api/v1/workflows/{key}` | — | 204 무본문 |
| 복제 | `POST /{key}/duplicate` | `{key,name}` | 201 `{data:{key}}` |
| 상태 카탈로그 | `GET/POST /api/v1/statuses` | `{key,name,description?,category}` | `StatusResponse[]` / `{id,key}` |
| 편성 추가 | `POST /{key}/statuses` | `{statusId,displayOrder}` | 201 **무본문** |
| 편성 제거 | `DELETE /{key}/statuses/{statusId}` | — | 204 무본문 |
| 순서 변경 | `PUT /{key}/statuses/order` | `{statusIds[]}` — **전부** | `{data:null}` |
| 전환 CRUD | `POST/PUT/DELETE /{key}/transitions[/{id}]` | `{toStatusKey,name,fromStatusKey?,kind?}` | `TransitionResponse` / 204 |

계약에서 갈리는 세 자리를 코드로 못박는다.

1. **`updateWorkflow` 는 `key` 를 안 보낸다.** 백엔드 DTO 에 그 필드가 없고, 이슈·자동화·검색이
   문자열로 참조해 바뀌면 조용히 끊긴다(키 불변 원칙 ADR).
2. **`GLOBAL`·`INITIAL` 은 `fromStatusKey` 키 자체를 뺀다.** `null` 을 실어 보내면 400 이라
   전역 전환을 영영 못 만든다.
3. **편성 추가(201)·제거(204)·삭제(204)는 본문이 없다.** `res.json()` 을 부르면 터진다.

Zod 스키마는 **백엔드 DTO 를 직접 읽고** 맞췄다 — 부채 103(스키마가 서버 계약보다 엄격해
드롭다운이 통째로 사라진 건)이 추측으로 쓴 스키마에서 났기 때문이다.

### Jira 대조 (`jira-parity-contract.md` §1)

| Jira Cloud | BTS | 판정 |
|---|---|---|
| 설정 → 문제 → 워크플로 (목록) | `/admin/workflows` | 동일 |
| 워크플로 편집기 텍스트/목록 모드 | 상태·전환 탭 | 동일 (다이어그램 모드는 PR 9) |
| 상태는 사이트 전역, 이름 유일 | 전역 카탈로그 | 동일 |
| 전환 규칙(조건/검증기/후처리) | — | **범위 밖** — FR-WF-06 D6 |

조작감 갭은 이번에 전부 닫는다 — 목록 열기 · 이름/설명 수정 · 상태 넣기(검색 피커) ·
빼기(확인) · 순서(드래그) · 전환 이름.

### §2 즉사 계약 대응

- **사이드바 라벨은 `'워크플로우 관리'`.** `'워크플로우'` 로 줄이면 기존 `'워크플로우 스킴'` 의
  substring 이라 Playwright `getByRole(name:)` 부분일치가 둘을 함께 잡아 strict mode 로 죽는다.
  ★ `i18n/__tests__/nav-labels.test.ts` FR15 substring 판별식은 `navLabels` 키만 훑어 이
  리터럴을 **잡아 주지 않는다**. 사람이 지켜야 하는 자리라 주석 + E2E 단언으로 이중으로 건다.
- 다이얼로그 제목은 화면에서 고유. **별도 `aria-label` 프롭을 두지 않는다** — Radix 가
  `DialogTitle` 을 `aria-labelledby` 로 걸고 그것이 `aria-label` 을 이긴다(실측).
- 페이지당 `<h1>` 1개.

### §4 재사용 자산

`command`+`popover` 를 합성해 `combobox.tsx` 를 뽑고, `empty-state`·`use-media-query`·
`@dnd-kit`(설치됨)을 재사용한다. 새로 만드는 프리미티브는 `confirm-dialog.tsx` 하나뿐이다.

## Sanity Check

- **읽기와 쓰기가 다른 식별자를 쓴다.** 조회 응답 `states[]` 에는 `key` 만 있고 편성 API 는
  `statusId` 를 받는다. 조인을 빠뜨리면 화면엔 보이는데 빼거나 옮길 수 없다 →
  `joinStatusIds` 한 곳에 모은다.
- **캐시가 둘로 갈릴 뻔했다.** `use-workflows.ts` 의 `['workflows']` 키가 모듈 비공개라
  관리 쪽이 자기 키를 만들 수밖에 없었다 → 키를 export 하고 동일성을 판별식으로 못박는다.
- **목이 두 출처로 갈릴 뻔했다.** `workflow-handlers` GET 이 정적 픽스처를 돌려주면 쓰기가
  조회에 안 보인다 → GET 도 같은 인메모리 저장소를 읽게 한다.
- **도달 불가 픽스처 금지.** 픽스처의 기존 상태는 전부 전환이 가리켜 바로 뺄 수 없다.
  「추가 → 제거」가 성공 경로를 태우는 유일한 순서이므로 어디에도 안 쓰이는 `blocked` 를
  카탈로그에 심는다.

## Plan

| # | 작업 | 검증 |
|---|---|---|
| 1 | API 클라이언트 + Zod(백엔드 DTO 대조) | red-first · 18 단언 |
| 2 | 훅 13종 + 에러 매핑(코드 양방향 차집합) + 라벨 정본 | red-first · 15 단언 |
| 3 | 프리미티브 `combobox` · `confirm-dialog` | red-first · 14 단언 |
| 4 | 상태 있는 MSW 목 (쓰기→GET 가시성) | red-first · 17 단언 |
| 5 | 편집기 5종 + 인수 테스트(D6 정의 그대로) | red-first · 11 단언 |
| 6 | 라우트 3종 + 진입점 + 개수 판별식 동반 수정 | 전체 회귀 |
| 7 | E2E 스펙 4시나리오 | **작성만** (Maxi 지시) |

각 단계 red 를 실제로 눈으로 본 뒤 구현했다.

## 리뷰 결과

게이트 1 은 이 파일 승인으로 갈음한다(구현 선행 · 티어 정정 경위는 §Brief). 독립 리뷰 2종은
`/bts-codereview` 에서 발행한다.
