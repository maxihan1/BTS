# 워크플로우 초안·발행 + 기본값 복원 (FR-WF-07 로드맵 PR 6)

## Brief

FR-WF-07(워크플로우 초안·발행 + 상태 이관 마법사)의 **project-workflow BC 몫**을 낸다.
로드맵(`~/.claude/plans/cozy-hatching-otter.md`) 10 PR 중 **PR 6** 이다.

사용 중인 워크플로우를 직접 고치면 편집 중간 상태가 운영에 샌다. 초안을 따로 두고 발행할 때만
정규 테이블에 반영한다.

FR-WF-07 은 단일 PR 이 아니다 — PR 6(project-workflow) · PR 7(issue-tracking) · PR 9·10(apps/web)
넷에 걸쳐 있고, 「한 PR = 한 BC」 규칙 때문에 PR 6 과 PR 7 은 합칠 수 없다.

**티어 T3.** 마이그레이션이 포함된다(`scripts/workflow/surfaces.ts:44-45`).

## 도메인 정리

관련 ADR 2건.
- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` §D4 — 「기본값으로 복원」이 부팅 되돌림을
  대체한다. 되돌리는 주체가 이벤트에서 **사람**으로 바뀌는 것이 결정의 핵심이다
- `docs/adr/2026-08-25-workflow-transition-rule-hard-delete.md` — 발행 이력 append-only 를 선언했고,
  「규칙 편집 이력 부재」의 후속 축으로 이 FR 을 지목했다

## 착수 전 실측 정정 3건 — 계획서가 stale 했다

| 문서가 적은 것 | 실측 | 조치 |
|---|---|---|
| §2.7 D3 「**V207** workflow_drafts」 | V207 은 `transitions_multi_and_global.sql` 로 이미 점유(#395) | **V208** 로 정정 |
| 로드맵 「`IssueStatusUsagePort` 를 shared-kernel 에 **신설**」 | **이미 존재** — `application/port/IssueStatusUsagePort.kt`. 구현체도 같은 BC | 신설하지 않고 읽기로 재사용 |
| `DATA.md §4.1` 「project-workflow **V200~V205**」 | 실제 V200~V207 | `V200~V208` 로 갱신 |

## Jira 대조 (계약 §1 — 5단계 실물 조회)

§1-0 재사용 grep 결과 워크플로우 Jira 대조가 3건 있었으나 **발행·이관 행은 0건**이라 신규 조회했다.

| # | Jira Cloud 동작 | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|---|
| J1 | 이관은 상태 제거 즉시가 아니라 **발행(저장) 시점**에 시작 | "The moving process won't begin as soon as you remove a status from a workflow, but after you update the workflow to save it." | https://support.atlassian.com/jira-software-cloud/docs/move-issues-to-new-statuses-while-updating-your-workflow/ · 2026-08-26 · **Cloud** |
| J2 | 발행 시 모달에서 대상 상태를 고른다 | "In the modal that shows up, choose new statuses in the **New status** column." | 위와 동일 · 2026-08-26 · **Cloud** |
| J3 | 삭제 대상 상태의 이슈를 **볼 수 있어야** 한다 | "If you need to see what work items are in the statuses you're deleting, try searching for work items and filtering by their statuses." | 위와 동일 · 2026-08-26 · **Cloud** |
| J4 | 발행 요청이 **`statusMappings` 를 함께 받는다** | "the draft workflow includes new workflow statuses for an issue type, and mappings are provided to update issues with the original workflow status to the new workflow status" | https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-workflow-scheme-drafts/ · 2026-08-26 · **Cloud** |
| J5 | **`validateOnly`** 모드 — 검증만 돌리고 204 | "a 204 No Content response is returned if the request is only for validation and is successful" | 위와 동일 · 2026-08-26 · **Cloud** |
| J6 | 발행은 **비동기 task** | "The publish operation is asynchronous, and you should follow the location link in the response to determine the status of the task" | 위와 동일 · 2026-08-26 · **Cloud** |

### 채택 판정

**J1·J2·J4·J5 채택** — 발행을 차단하지 않고 매핑을 끼워서 발행한다. `validateOnly` 로 화면이
「어떤 매핑이 필요한가」를 미리 묻는다. Maxi 가 「지라 클라우드에서 처리하는 방식으로」를 지시했다.
**J3 채택** — 상태별 이슈 건수를 응답에 실어 화면이 보여줄 수 있게 한다.
**J6 부분 채택** → X2.

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| X1 | Jira 의 `statusMappings` 는 `issueTypeId` 별로 매핑을 가른다. BTS 는 **상태 단위로만** | BTS 는 워크플로우가 전역이고 유형별 배정은 스킴(FR-WF-02)이 담당한다. 두 축을 여기 섞으면 겹친다 |
| X2 | **발행 자체는 동기**. 비동기는 **이관에만** | 워크플로우 정의 재작성은 행 수가 작다. Jira 가 비동기인 이유는 이슈 이관이 섞여 있기 때문이고, BTS 는 그것을 `bulk_operations` 로 분리한다 |
| X3 | `validateOnly` 를 쿼리 파라미터가 아니라 **별도 엔드포인트** `POST …/publish/preview` 로 | 같은 경로가 200 과 204 를 오가면 프론트 zod 스키마(`.strict()`)의 분기가 지저분해진다 |

### 이번 PR 범위 밖 (사유 명시)

- **J1 의 「이관 실행」** — 이슈 UPDATE 는 issue-tracking BC 소유다. **PR 7** 소관.
  이번엔 막고, 무엇이 막는지(상태별 잔여 건수) 알린다
- **J2 의 모달 UI** — **PR 10** 소관

**Jira 매핑** — J1→T7 · J2→T6·T7 · J3→T6 · J4→T7 · J5→T6 · J6→X2(편차) ·
J1 이관 실행→PR 7 범위 밖 · J2 모달 UI→PR 10 범위 밖.

## 스펙

### 스키마 — `V208__workflow_drafts_and_publications.sql`

```
workflow_drafts        workflow_id UUID PK FK CASCADE · definition JSONB · base_version BIGINT
workflow_publications  id · workflow_id FK · version_no INT · definition JSONB
                       UNIQUE(workflow_id, version_no)          ← append-only
```

**append-only 를 트리거로 강제한다.** 「경로를 만들지 않는다」는 애플리케이션 약속은 아무도
검사하지 않는다. 단 `pg_trigger_depth()` 로 CASCADE 삭제는 통과시킨다 — 막으면 워크플로우를
영영 지울 수 없다.

### API

| 메서드 | 경로 | 권한 |
|---|---|---|
| GET/PUT/DELETE | `/api/v1/workflows/{key}/draft` | UPDATE |
| POST | `/api/v1/workflows/{key}/publish/preview` | UPDATE |
| POST | `/api/v1/workflows/{key}/publish` | **PUBLISH** |
| POST | `/api/v1/workflows/{key}/reset-to-default` | UPDATE |

`WorkflowDefinitionPermission.PUBLISH` 는 이미 정의돼 있었고 KDoc 이 「로드맵 PR 6 이 소비한다」고
적어 뒀다 — 이번에 실제로 소비한다.

### 핵심 결정

- **초안은 JSONB.** 테이블 복제 0 · 「발행 전엔 런타임에 안 샌다」가 구조로 보장된다(읽기 경로가
  그 테이블을 아예 조회하지 않는다)
- **초안 저장과 발행이 같은 검증을 지난다** — 기존 `Workflow.of()` 재사용. 두 경로가 갈리면
  「초안은 저장됐는데 발행에서 터지는」 막다른 길이 생긴다
- **`base_version` 은 처음 한 번만 기록한다.** 저장마다 갱신하면 낙관적 락이 무력해진다
- **발행은 diff 가 아니라 전량 교체.** 초안의 전환에는 아직 identity 가 없어 「같은 전환」을
  추정해야 하는데, 전량 교체는 그 추정을 하지 않는다
- **발행이 상태를 만들지 않는다.** 카탈로그에 없는 상태를 가리키면 거절 — 만들면 카탈로그의
  이름 유일 규칙을 우회하는 두 번째 경로가 생긴다

## Plan

| # | 내용 | red 가 무엇을 잡나 |
|---|---|---|
| T1 | V208 + codegen 미러 | 두 테이블 DDL·제약. append-only 트리거와 CASCADE 통과 |
| T2 | 초안 도메인 + 저장소 | 잘못된 초안이 `Workflow.of()` 에서 막히는가 · JSONB 왕복 |
| T3 | **발행 전 런타임 불변** | 초안을 고쳐도 엔진이 옛 정의를 쓰는가 ← 이 FR 의 핵심 |
| T4 | 발행 + 캐시 무효화 | 호출을 일부러 빼서 red 1회 확인 |
| T5 | 낙관적 락 409 | 뒤늦은 발행이 409 인가 |
| T6 | publish/preview | 빠지는 상태 + 상태별 건수가 응답에 실리는가 |
| T7 | 이관 필요 판정 | 이슈가 남은 상태가 있으면 막고 건수를 알려주는가 |
| T8 | 발행 이력 append-only | `version_no` 증가 · UPDATE/DELETE 거부 |
| T9 | 기본값 복원 | `origin='SEED'` 만 · YAML 이 **초안에만** 로드되는가 |

## 구현 결과

### 판별식 2개를 손댔다

**① `CacheInvalidationCoverageTest` 를 넓혔다.** `publish` 를 「무효화를 빠뜨렸다」로 잡았는데,
실제로는 `cache.withWriteLock(key) { }` 을 쓰고 그 함수가 block 직후 반드시 `invalidate(key)` 를
부른다 — 직접 호출보다 강한 보장이다(advisory lock 겸함). **허용목록에 넣지 않고** 판별식이 그
형태를 인정하게 했다. 허용목록은 「무효화가 불필요한 함수」의 자리이지 「다른 방식으로 무효화하는
함수」의 자리가 아니다 — 오탐을 허용목록으로 덮으면 그 줄이 미래의 진짜 누락까지 통과시킨다.
넓힌 뒤 **일부러 끊어 red 를 1회 확인**했다.

**② 프론트 `workflow-admin-error.test.ts` 의 `HANDLER_FILES` 에 새 핸들러를 등재했다.**
그 배열에 넣지 않으면 새 파일의 코드가 통째로 안 보여 차집합이 **조용히 0 으로 통과**한다
(테스트 KDoc :21 에 실사고 기록). 등재가 실제로 작동하는지 뮤테이션으로 확인했다 — 프론트에서
`WORKFLOW_PUBLISH_MAPPING_REQUIRED` 를 지우자 판별식이 정확히 그것을 집어냈다.

### 함정 3건

- **`init_codegen.sql` 미러를 강제하는 판별식이 없다.** `build.gradle.kts:212` 주석은
  `CodegenMirrorParityTest` 가 막는다고 적었으나 **그 테스트는 실재하지 않는다**(전 모듈 grep —
  이름이 나오는 곳은 주석 5건뿐). 거짓 주석을 바로잡고 부채로 등재했다
- **ktlint(140)와 detekt(120)의 최대 줄 길이가 다르다.** `ktlintFormat` 이 합친 줄을 detekt 가
  거부해 왕복이 난다. 해당 함수는 블록 본문으로 바꾸거나 타입 이름을 줄여 두 기준 모두에서 벗어났다
- **테스트 셋업 순서가 load-bearing 이다.** `issue_types`(cross-BC FK 대상)를 마이그레이션보다 먼저
  만들면 Flyway 가 `Found non-empty schema(s) "public" but no schema history table` 로 통째로 거부한다

### 남은 것

- **PR 7** (issue-tracking) — 이관 큐잉 포트 · `BulkOperationType.STATUS_MIGRATION` · 이관 실행
- **PR 9·10** (apps/web) — xyflow 다이어그램 편집기(D8) · 발행 다이얼로그 · 이관 마법사 · E2E

관련 FR — FR-WF-07. 관련 ADR — 위 §도메인 정리 2건.
