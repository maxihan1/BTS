# FR-UX-09 B1 — 이슈 생성 시 담당자·우선순위·라벨 1회 제출 확정

> slug: fr-ux-09-b1-create-issue-fields
> type: feature
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-07-31

## Brief

**사용자 원문.**
FR-UX-09 B1 — 이슈 생성 시 담당자·우선순위·라벨을 1회 제출로 확정한다. 지금은 create 후 PATCH 3회를
이어 붙여야 하고 **중간 실패 시 반쯤 만들어진 이슈가 남는다**(완제품 기준 위반).
`CreateIssueRequest.kt` 에 nullable 3필드 추가 + `IssueController` create 핸들러 +
`IssueApplicationService` create 경로. 전부 optional 추가라 기존 요청 무회귀.

**정본.** `docs/plan/product/personalization.md` §4.7 FR-UX-09 — **D4/D5**.
FR-UX-09 는 승계 PR 3건(F2 모달 · F3 진입점 3곳 · **B1 백엔드**)으로 구성되며 본 PR 은 그중 B1 이다.

**B1 의 지위.** 2026-07-29 정정 — 2026-07-28 Maxi 결정 #3 의 *"B1 = chore"* 를 승계·정정해
**이 FR 의 D4/D5** 로 승격. 조용한 변경이 아니라 원 결정의 명시적 승계다.

**classify 결과.**
| 항목 | 값 |
|---|---|
| type | `feature` |
| agent | `backend-engineer` |
| primary_bc | `issue-tracking` |
| slug (자동) | `fr-ux-09-b1-1-issue-tracking-createissuerequest-3` → 관례 맞춰 정정 |

### 착수 전 실측으로 확인된 사실

- `CreateIssueRequest.kt` 실경로 = `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`
  (정본이 적은 `com/atlas/bts/issuetracking/...` 아님)
- 정본 인용 `:27-39` **정확** — data class 범위가 실제로 27~39행
- 현재 DTO 보유 필드 = `projectKey` · `typeId` · `summary` · `description` · `componentIds` ·
  `securityLevelId` · `customFields` → **`assigneeId`·`priority`·`labels` 3필드 부재 확인**
- `IssueController.kt` · `IssueApplicationService.kt` 모두 `com/bts/issue/` 하위에 실재

### 선행 조건

- **선행 FR.** §4.5 FR-UX-07 (활성 프로젝트 컨텍스트) — **#320 로 완료**. 차단 없음
- 승계 관계상 F2(생성 모달)·F3(진입점 3곳)가 이 API 를 소비하므로 **B1 이 먼저**여야 한다.
  순서를 뒤집으면 프론트가 PATCH 3회 방식으로 만들어졌다가 재작업된다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
