# project-workflow 상태 이관 결선 + 발행 루프 (FR-WF-07 D4 · 로드맵 PR 7b)

> 티어: T2
> slug: workflow-status-migration-wiring
> type: api
> agent: backend-engineer
> 생성: 2026-08-31

> **T2 라 spec 을 이 파일 `## 스펙` 절로 흡수한다** — 별도 `docs/specs/` 파일을 만들지 않는다.

## Brief

**사용자 원문** — 「로드맵 PR 7b — 상태 이관 결선. `WorkflowPublishService` 가
`IssueStatusMigrationPort` 를 실제로 호출하게 한다.」

**classify** — `type=api` · `agent=backend-engineer` · `primary_bc=project-workflow` ·
`slug` 는 가독성을 위해 `workflow-status-migration-wiring` 으로 바꿔 썼다(판별식은 plan 파일명
형식 `YYYY-MM-DD-slug.md` 만 보고 classify 캐시와 대조하지 않는다 — 실측).

**★선언 티어는 T2 이고 classify 출력과 다르다.** classify 가 `tier=T1` 을 냈으나 표면이
`BE_MAIN` + `API`(신규 엔드포인트)라 표에 따르면 T2 다. `/bts` 판정 5문 ②(Maxi 지정 우선)로
**T2 를 선언**한다. classify 가 티어를 표면에서 추론하지 않는 것은 별건 결함이고 이 PR 범위 밖이다.
게이트 2 요약에 선언 티어와 실측 티어를 나란히 싣는다.

**정본** — 승인된 실행 계획 `~/.claude/plans/enchanted-brewing-bee.md` ·
로드맵 `~/.claude/plans/cozy-hatching-otter.md` PR 7b 절(`:348`) ·
착수 조건 각주 `docs/plan/product/project-workflow.md` §2.7 ·
장부 `TODOS.md` 부채 144(`:1629`) · 부채 143(`:1603`).

### FR — FR-WF-07 의 D4 잔여분

`docs/plan/product/project-workflow.md:151` 의 D4 는 「초안 CRUD · 발행 · 기본값 복원 · 이관 포트」인데
**결선 한 자리만 남아 있다**. PR #414 로 포트·어댑터·워커·`cause` 표시가 전부 들어왔으나
호출자가 없어 사용자에게 보이는 변화가 0 이다. 이 PR 이 D4 를 닫는다.

### 왜 지금 이것인가

`IssueStatusMigrationPort` 는 실재하고, `WorkflowStatusMigrationAdapter` 도 실재하고, pgmq 워커도
돈다. 그런데 `WorkflowPublishService` 는 여전히 빠지는 상태에 이슈가 남으면 409 로 막고 건수만
응답에 실을 뿐이다 — 관리자가 이관을 시작할 입구가 없다. Obsidian `learnings.md:758` 이 적은
「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」가 정확히 이 상태다.

### 확정 결정 3건 (Maxi 승인 · 계획 단계)

| # | 결정 | 근거 |
|---|---|---|
| D2 | **별도 `migrate` 엔드포인트.** `POST /publish` 는 의미 불변 | 발행 API 가 「발행하지 않고 202」를 돌려주는 상태를 안 만든다. 부수 효과로 트랜잭션이 BC 를 안 넘는다 |
| D3 | **결선 먼저.** `cause` 필터 3 BC · VIEW 오진은 범위 밖 | 마법사 UI 가 PR 10 이라 실사용 유입구가 아직 닫혀 있다. 후속은 PR 10 의 명시적 선행으로 장부 등재 |
| D4 | **`replaceDefinition` 직후 재카운트 + 롤백.** 잔여 창은 한계로 명시 | BC 를 안 넘고 뒤쪽 창의 범위를 줄인다. 부채 143 은 「축소」로 갱신하고 닫았다고 쓰지 않는다 |

### 범위

**넣는 것** — ① 결선 ② `workflowId → projectKeys` 3단 JOIN(결선의 전제) ③ `countIssuesInStatus`
프로젝트 스코프 ④ 뒤쪽 창 축소 ⑤ 권한 검사 순서 판정(C-10) ⑥ 포트 KDoc 계약 2축 red-first
⑦ C-8 배포 순서·롤백 문서.

**빼는 것 (장부 등재 후 이월)** — `cause="STATUS_MIGRATION"` 소비자 필터(search-export-import ·
notification · slack-integration 3 BC) · VIEW 미보유 이슈 `NOT_FOUND` 오진(`TODOS.md:1655`) ·
PR 7 잔여 7건(`TODOS.md:1650-1657`). 전부 다른 BC 라 「한 PR = 한 BC」에 걸린다.

## Jira 대조 (← /bts-spec 채움)

미충전. `/bts-spec` 이 실제 출처로 채울 때까지 이 한 건은 판별식에서 red 로 남는다 — 의도된 상태다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
