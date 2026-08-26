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
- 프론트에 validator/post-action 편집 UI 는 **0건**. #400 이 편집기 탭 셸을 남겼다.

### 착수 시 반영한 함정·교훈

- **MSW lexical 가짜그린** (learnings 2026-06-25 / PR #187) — 단위·MSW·E2E 3겹이 동시에 가짜그린일
  수 있다. `ValidatorDtos.kt` 실물을 읽고 계약을 맞춘다.
- **브라우저 눈확인** — `jira-parity-contract` §6 이 시각 변경 PR 에 요구한다. #400 이 미실시로
  남겼고 그때 배지 색이 통째로 사라지는 결함이 실제로 있었다. 이번엔 라이트/다크 눈확인을 한다.
- **`apps/web/e2e/visual/__screenshots__/`** 는 untracked 로 그대로 둔다 (#400 결정 승계).

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
