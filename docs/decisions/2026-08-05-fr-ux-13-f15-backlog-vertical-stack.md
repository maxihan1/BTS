# FR-UX-13 F15 — 스프린트 시작/완료를 백엔드 변경 없이 다이얼로그화한다

> 상태. 채택 (2026-08-05)
> 관련 FR. FR-UX-13 (F15)
> BC. agile-planning (프론트 소비 · 백엔드 변경 0줄)
> PR. #343

## 맥락

백로그 화면의 스프린트 시작/완료는 지금 확인 절차 없이 버튼에서 곧장 실행된다
(`BacklogBoard.tsx:194-199` — `startSprint.mutate` / `completeSprint.mutate` 직결).
F15 는 지라 패리티를 위해 두 동작에 다이얼로그를 붙인다.

문제는 **백엔드 계약이 지라의 다이얼로그가 요구하는 입력을 받지 않는다**는 것이다.

| 엔드포인트 | 실측 시그니처 | 위치 |
|---|---|---|
| `POST /api/v1/sprints/{id}/start` | **파라미터 0** — path variable `id` 뿐 | `SprintController.kt:200-201` |
| `POST /api/v1/sprints/{id}/complete` | **파라미터 0** — path variable `id` 뿐 | `SprintController.kt:221-222` |
| `PATCH /api/v1/sprints/{id}` | `UpdateSprintRequest` — `name`·`goal`·`startDate`·`endDate` 3-state partial + **`version` 필수** | `SprintController.kt:149` |
| `DELETE /api/v1/sprints/{id}/issues/{issueKey}` | **멱등** — 이미 제거된 키도 204 | `SprintController.kt` |
| `POST /api/v1/sprints/{id}/issues` | `AssignIssueRequest`. **COMPLETED 스프린트면 409** | `SprintController.kt:244` |

도메인은 `PLANNED → ACTIVE → COMPLETED` **단방향 FSM** 이고 위반 시 409
(`Sprint.kt` — `InvalidSprintTransitionException`).

## 결정

**백엔드 변경 0줄을 유지하고, 프론트가 기존 엔드포인트를 조합한다** (Maxi 확정 2026-08-05).

### D1. 시작 다이얼로그 — `PATCH` → `start` 2단계

시작 다이얼로그에서 시작일·종료일·목표를 편집할 수 있게 한다. 제출 시.

1. **값이 바뀐 경우에만** `PATCH /sprints/{id}` (바뀐 필드만 3-state 로 전송)
2. 이어서 `POST /sprints/{id}/start`

값이 하나도 안 바뀌었으면 1을 건너뛰고 2만 보낸다 — 불필요한 버전 증가와 낙관적 잠금
충돌면을 만들지 않기 위함이다.

**`version` 은 별도 조회 없이 확보된다.** 백로그 조회 응답의 스프린트 메타가 이미
`version`·`goal`·`startDate`·`endDate` 를 싣고 있다 (`api/backlog.ts:52-68` `sprintMetaSchema`).

**중간 실패 상태가 이 결정의 유일한 비용이다.** `PATCH` 는 성공했는데 `start` 가 실패하면
스프린트는 **수정됐지만 시작되지 않은** 상태로 남는다. 이 상태는 사용자에게 명시해야 하며
(스펙에서 문구·재시도 경로를 정한다), 「전부 실패했다」는 안내는 **거짓말**이 된다.

### D2. 완료 다이얼로그 — 이관 후 완료

완료 다이얼로그는 미완료 이슈 목록과 이관 대상 선택을 제공한다. 대상은 **백로그** 또는
**아직 완료되지 않은 다른 스프린트**(PLANNED·ACTIVE)다.

- 백로그로 → 이슈당 `DELETE /sprints/{id}/issues/{key}` **1회**
- 다른 스프린트로 → 이슈당 `DELETE` + `POST /sprints/{targetId}/issues` **2회**

**순서가 계약이다 — 이관을 먼저, 완료를 나중에.** `POST /{id}/issues` 는 COMPLETED 대상에
409 를 반환하므로(`SprintController.kt:244` 문서화된 계약) 완료 후 이관은 구조적으로 불가능하다.

#### ★ 보강 (2026-08-05 스펙 단계 실측) — 완료된 스프린트의 이슈는 **영구 동결**된다

409 하나가 아니라 **두 겹**이다. 스펙 작성 중 발견해 직접 실측 확인했다.

1. `SprintRepository.unassignIssue` 는 조건부 DELETE 다 —
   `WHERE … AND EXISTS (SELECT 1 FROM sprints WHERE id = ? AND status <> 'COMPLETED')`.
   COMPLETED 스프린트에서는 **아무 행도 지우지 않고 204** 를 반환한다. 실패가 아니라 **침묵**이다.
2. `V503__sprints.sql` — `CONSTRAINT sprint_issues_issue_key_unique UNIQUE (issue_key)`.
   한 이슈는 전역적으로 한 스프린트에만 속하고, 스키마 주석이 "다른 스프린트로 재할당하려면
   먼저 제거해야 한다"고 못박는다. **그 제거가 1번에서 막힌다.**

즉 완료된 스프린트에 남은 이슈는 **꺼낼 수도 옮길 수도 없다.** 되돌릴 수단이 없으므로
「이관 먼저」는 UX 취향이 아니라 **안전 요구**이며, 「부분 실패 시 완료 중단」과
「`truncated` 면 완료 차단」(Maxi 확정 2026-08-05 · 상한 `BOARD_CARD_FETCH_LIMIT = 1000`)도
전부 이 사실에서 파생된다.

덧붙여 `hooks/use-backlog.ts:210-211` 의 기존 주석 *"완료 후 미완성 이슈는 백엔드에서
backlog로 이동시키므로"* 는 **거짓이다** — `SprintApplicationService.kt:282-290` 은 스프린트
상태만 뒤집고 이슈는 건드리지 않는다. 이 PR 에서 주석을 바로잡는다.

**COMPLETED 스프린트는 이관 대상 목록에서 제외한다.** 넣으면 사용자가 고를 수 있는 선택지가
반드시 409 로 끝난다.

**부분 실패가 정상 경로다.** N개 이슈 중 M개가 실패할 수 있고, 이때 완료를 강행하면 실패한
이슈들이 완료된 스프린트에 갇힌다(이후 이관 불가). 실패 시 완료를 진행하지 않는 것이
기본이며, 구체적 문구와 재시도 단위는 스펙에서 정한다.

`DELETE` 가 **멱등**(이미 제거된 키도 204)이라 재시도는 안전하다.

## 대안과 기각 사유

| 대안 | 기각 사유 |
|---|---|
| `POST /{id}/start` 에 body 추가 | 백엔드 BC(agile-planning) 변경이 들어와 이 PR 이 프론트 전용에서 벗어나고 D3~D5(데이터 모델·백엔드·백엔드 테스트) 단계가 되살아난다. Maxi 확정으로 배제 |
| `complete` 에 이관 파라미터 추가 | 위와 동일. 정본 §4.11 도 "범위 밖"으로 이미 판단 |
| 시작 다이얼로그를 확인 전용으로 | 요청 1회로 단순하지만 기간 설정이 별도 편집 경로로 밀려 지라 대비 단계가 늘어난다. Maxi 가 기각 |
| 미완료 이슈를 백로그로만 이관 | 실패 처리가 단순해지지만 다음 스프린트로 넘기는 흔한 흐름에서 사용자가 수동으로 끌어다 놓아야 한다. Maxi 가 기각 |

## 결과

- 백엔드 변경 **0줄**. D3·D4·D5 는 "해당 없음"으로 확정된다.
- 프론트가 **다단계 요청의 부분 실패**를 처음으로 정면으로 다룬다. 성공/실패 이분법 토스트로는
  표현할 수 없는 상태가 두 곳(시작 2단계 · 완료 N+1단계) 생긴다.
- 후속으로 백엔드가 `start` body 나 `complete` 이관 파라미터를 갖게 되면 이 조합은 단일 요청으로
  대체될 수 있다. 그때 이 ADR 을 대체(superseded)한다.

## 신규 용어

**없음.** `스프린트`·`백로그`·`에픽` 은 `Maxi_wiki/BTS/glossary.md:19-21,117` 에 이미 있고,
「세로 스택」·「시작 다이얼로그」는 UI 배치 용어라 유비쿼터스 언어 대상이 아니다.
`domain/agile-planning.md` 의 "스프린트 라이프사이클 (계획/시작/종료/회고)" 서술도 그대로 유효하다.
