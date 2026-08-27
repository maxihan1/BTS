# issue-tracking 상태 이관 실행 (FR-WF-07 D2·D4·D5 · 로드맵 PR 7)

> 티어: T3
> slug: issue-tracking-status-migration
> type: migration
> agent: db-engineer
> 생성: 2026-08-27

> **spec 은 별도 파일이다** (T3 규칙) — `docs/specs/2026-08-27-issue-tracking-status-migration.md`

## Brief

**사용자 원문** — 「로드맵 PR 7 — issue-tracking 이관 실행」

**classify** — `type=migration` · `agent=db-engineer` · `slug=issue-tracking-status-migration` ·
선언 티어 **T3**. 근거는 표면 3중첩이다 — `MIGRATION`(V038) · `BE_MAIN`·`API`(issue-tracking) ·
포트 계약(설계 선택에 따라 `SHARED_KERNEL`). `/bts` 판정 규칙 ① 최고 티어.

**정본** — `~/.claude/plans/cozy-hatching-otter.md` PR 7 절(`:314`) ·
`docs/plan/product/project-workflow.md` §2.7(`:142`) · `.claude/STATE.md`(PR #411 이 세운 계약 6건).

### FR — FR-WF-07 의 D2·D4·D5 잔여분

- **D2 잔여** — cross-BC 포트 계약(이관 큐잉). 읽기 포트는 이미 있고 **쓰기**가 이 PR 몫
- **D4 잔여** — 이관 큐잉 호출. 지금은 이슈가 남으면 409 로 막고 상태별 건수만 응답에 싣는다
- **D5 잔여** — 「이관 후 이슈 상태 전량 이동」 테스트

### 범위 (로드맵 PR 7 절)

- `IssueStatusUsagePort` 에 `enqueueStatusMigration` 추가 + issue-tracking 어댑터 구현
- `BulkOperationType.STATUS_MIGRATION` 추가 — 엔진 우회 직접 재작성 + `issue_change_item` 이력 ·
  `BulkOperationProcessor` 분기
- `POST /api/v1/issues/{key}/transition` 이 `transitionId` 수용 + `toStatusKey` 모호 시 409
- `GET /api/v1/issues/{key}/transitions` 응답에 `transitionId` 추가
- issue-tracking **V038**

### 함께 닫아야 할 부채

- **143** — 이관 판정과 교체 사이 TOCTOU
- `countIssuesInStatus` 의 프로젝트 스코프 — 권한을 넓히기 전에 선행

### 착수 시점 실측 5건 (spec 이 판정할 것 · 재조사 불요)

1. **로드맵과 실제 구현이 어긋난다.** 로드맵은 「shared-kernel `com.bts.shared.issue.IssueStatusUsagePort`
   신설」이라 적었으나 실재하는 것은 `project-workflow/src/main/kotlin/com/bts/workflow/application/port/IssueStatusUsagePort.kt`
   이다. 어댑터(`adapter/outbound/IssueStatusUsageAdapter.kt`)가 jOOQ 동적 참조 `DSL.table("issues")`
   로 읽고, KDoc 이 근거를 적어 뒀다 — 「읽기 전용 스칼라 count 만 하는 것이 이 BC 의 선례
   (`IssueTypeUsagePort`)」. **PR 7 이 필요한 것은 쓰기라 그 면제가 그대로 넘어오지 않는다.**
   로컬 포트 유지 vs shared-kernel 승격이 이 PR 의 설계 갈림길이고 ADR 후보다.
2. **`shared-kernel` 에 `*UsagePort` 가 0건이다.** 로드맵 §손댈 파일 표가 지목한
   `shared-kernel/.../issue/IssueStatusUsagePort.kt` 는 실재하지 않는다. 표를 그대로 믿고
   「이미 있다」로 계획하면 어긋난다.
3. **마이그레이션이 필수다 — 추측이 아니다.** `V008__bulk_operations.sql:17` 에
   `CONSTRAINT chk_bulk_operations_operation_type CHECK (operation_type IN ('BULK_EDIT','BULK_TRANSITION'))`
   이 실재한다. 마지막 번호가 `V037__projects_archived_at.sql` 이므로 **V038** 이 맞다.
4. **`BulkOperationType` 은 현재 2값이다** — `BULK_EDIT` · `BULK_TRANSITION`
   (`issue-tracking/.../bulk/domain/BulkOperationType.kt:11`). exhaustive `when` 소비처와
   DB CHECK·DTO·프론트 유니온의 수동 갱신 지점 전수는 spec 이 센다.
5. **`countIssuesInStatus` 의 KDoc 이 이 PR 을 이름으로 예약해 뒀다** — 「프로젝트 → 스킴 →
   워크플로우 3단을 거슬러 정밀하게 좁히는 것은 로드맵 **PR 7** 의 일이다. 그때까지는 과하게
   막는 쪽을 택한다」. 부채 「프로젝트 스코프」가 곧 이 문장이다.

## 도메인 정리 (← /bts-spec §1 채움)

## Jira 대조 (계약 §1 — 0단계 재사용 승계)

§1-0 재사용 grep 결과 **직전 형제 PR 6 의 대조가 같은 표면**이었다
(`docs/plans/2026-08-26-workflow-draft-publish.md:40`). 그 표가 이관 실행을 **「PR 7 소관」으로
명시 이월**해 뒀으므로 아래 3행을 **출처 URL·조회일 그대로 승계**한다. 새로 조회하지 않는다 —
같은 표면을 두 번 조사하지 않는 것이 §1-0 이다.

| # | Jira Cloud 동작 | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|---|
| J1 | 이관은 상태 제거 즉시가 아니라 **발행(저장) 시점**에 시작 | "The moving process won't begin as soon as you remove a status from a workflow, but after you update the workflow to save it." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |
| J3 | 삭제 대상 상태의 이슈를 **볼 수 있어야** 한다 | "If you need to see what work items are in the statuses you're deleting, try searching for work items and filtering by their statuses." | 위와 동일 · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |
| J4 | 발행 요청이 **`statusMappings` 를 함께 받는다** | "the draft workflow includes new workflow statuses for an issue type, and mappings are provided to update issues with the original workflow status to the new workflow status" | https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-workflow-scheme-drafts/ · 2026-08-26 · **Cloud** (PR 6 조회분 승계) |

**PR 6 이 이 PR 로 넘긴 행** — 「J1 의 「이관 실행」 — 이슈 UPDATE 는 issue-tracking BC 소유다.
**PR 7** 소관」(`docs/plans/2026-08-26-workflow-draft-publish.md:70`). 이 PR 이 그 행을 닫는다.
PR 6 의 편차 X2(「발행 자체는 동기 · 비동기는 이관에만」)가 이 PR 의 설계 전제다.

### 아직 조회하지 않은 표면 (← /bts-spec §1-1 이 실물 조회할 것)

- **`POST /api/v1/issues/{key}/transition` 의 `transitionId` 수용.** Jira 대응은
  `POST /rest/api/3/issue/{issueIdOrKey}/transitions` 의 `transition.id` 로 보이나 **미조회다.**
  선행 PR 4 의 스펙(`docs/specs/2026-08-20-backend-workflow-transition-id-multi-global.md`)에
  Atlassian 인용이 **0건**이라 승계할 행이 없다 — 판별식 도입일 이전 문서라 대상이 아니었다.
- **일괄 이관의 실패 항목 처리.** `bulk_operation_items` 의 FAILED 기록이 Jira 의 어떤 동작에
  대응하는지 미조회.

**생략이 아니라 「아직 조회하지 않았다」는 기록이다** — 계약 §1 「비-UI 타입도 대상이다」가
그 둘을 다른 기록으로 남기라고 요구한다.

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
