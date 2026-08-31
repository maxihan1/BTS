# 보드 CRUD 회수 — 이름 변경 · 삭제 API + 스위처 상시 노출 (로드맵 A1)

> 티어: T2
> slug: board-crud-recovery
> type: api
> agent: backend-engineer
> 생성: 2026-08-31

## Brief

**사용자 원문.** 보드 CRUD 회수 (로드맵 A1) — 보드 이름 변경 PATCH + 보드 삭제 DELETE API 신설 ·
보드 스위처 상시 노출 + 「보드 만들기」 진입점 · 보드 `⋯` 메뉴(이름 변경·삭제) + E2E.
BC = agile-planning 단독. 마이그레이션 0.

**classify 결과.** type=`api` · agent=`backend-engineer` · tier=**T2** · primary_bc=`agile-planning`.
표면 근거 — `backend/modules/agile-planning/src/main/**`(BE_MAIN) + 신규 REST 엔드포인트 2개(API)
+ `apps/web/src`(FE_SRC) 혼합이라 최고 티어 T2 가 지배한다.

**FR.** FR-BD-01 의 미회수 조항(**FR-BD-01-2** 「수정/삭제는 후속」)을 닫는다.
**신규 FR 아님 · FR 총수 불변 143** (PR #175 「범위 확장이나 FR 총수 불변」 선례와 동형).

- **FR-BD-01-2a** 보드 이름 변경. `PATCH /api/v1/boards/{id}` 부분 갱신. 권한 CREATE
- **FR-BD-01-2b** 보드 소프트 삭제. `DELETE /api/v1/boards/{id}`. 권한 SOFT_DELETE
- **FR-BD-01-2c** 보드 생성 진입점을 보드 존재 여부와 무관하게 제공
- **FR-BD-01-2d** 권한 미보유 시 관리 액션을 **렌더하지 않는다** (disabled 아님)

**승계 출처 (재작성 금지).**
- 스펙 + Task 1~6 정본 — `~/.claude/plans/1-serialized-sketch.md` (2026-08-25)
- 상위 로드맵 + 병행 판정 — `~/.claude/plans/mellow-mixing-avalanche.md` (2026-08-31 실측)

**착수 시 반영할 실측 3건.**
1. `ConfirmDialog` 는 PR #410 으로 **제어 컴포넌트**가 됐다 — `open`/`onOpenChange` 제어이고
   `onConfirm` 이 다이얼로그를 닫지 않는다. 선행 플랜 Task 6 은 그 이전 계약을 참조하므로 갱신 대상이다.
2. `jira-research-guard` 의 `CUTOFF = '2026-08-27'` — 이 파일은 검사 대상이다.
   「Jira 대조」 절 **안에** 허용 도메인 출처 URL 이 있어야 한다.
3. `board.tsx` 분기 좌표 재실측 완료 — `boards.length === 0` 은 `:498`, `>= 2` 는 `:518`.

**병행 제약.** 다른 세션이 PR #414(FR-WF-07 D2·D4·D5 · issue-tracking BC)를 진행 중이다.
파일 교차 0 을 실측 확인했다 — `apps/web`·`agile-planning` 접촉 **0파일**.

## Jira 대조 (전 타입 필수)

**조회 방식 — 계약 §1-0 재사용 승계다. 이번 세션은 실물을 조회하지 않았다.**
아래 J1~J6 은 **선행 플랜 `1-serialized-sketch.md`(조회일 2026-08-25)** 가 남긴 근거를
계약 §1-0 「같은 표면을 두 번 조사하지 않는다」에 따라 출처·조회일 그대로 승계한 것이다.
이 세션의 실행 주체에게 web 조회 도구가 없으므로 **새 조회는 수행하지 않았다**
(`.claude/agents/frontend-engineer.md` — 「web 도구가 없으므로 직접 조회하지 말고, 근거가
부족하면 구현을 멈추고 보고한다」). A1 의 범위는 선행 플랜이 조회한 범위와 **동일**하다 —
새로 건드리는 조작이 없어 추가 조회 대상이 0건이다. **스프린트 편집·삭제는 A2 범위**이므로
이 문서가 근거를 갖지 않는다.

### 근거 표 — 전부 Cloud (company-managed)

| # | 원문 근거 | 출처 | 조회일 |
|---|---|---|---|
| **J1** | 보드는 프로젝트당 **N개** 가질 수 있다. 1개 고정은 team-managed 쪽 제약이다 | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J2** | 보드 생성 진입점은 ① 사이드바 프로젝트 hover `+` ② 전역 Boards 디렉터리 **2곳**이고, 보드 존재 여부와 무관하게 상시 제공된다 | [Create a board](https://support.atlassian.com/jira-software-cloud/docs/create-a-board/) | 2026-08-25 |
| **J3** | 보드 **이름 변경**은 설정 화면에서 이름 옆 연필 인라인 편집으로 한다 | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J4** | 보드 **삭제**는 Boards 디렉터리 행 `⋯` → Delete 다. 설정 화면이 아니다. **이슈는 남는다** | [How to Delete a Software Board in Jira Cloud](https://support.atlassian.com/jira/kb/how-to-delete-a-software-board-in-jira-cloud/) | 2026-08-25 |
| **J5** | 권한 — 설정은 Board admin 또는 Jira admin, 삭제는 Board admin 또는 **Project admin**. 권한이 없으면 **메뉴 항목 자체가 부재**하다(비활성이 아니다) | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J6** | 저장된 필터 기반 보드 생성 — 소스로 프로젝트 또는 저장된 필터를 고른다 | [Create a board based on filters](https://support.atlassian.com/jira-software-cloud/docs/create-a-board-based-on-filters/) | 2026-08-25 |

### 채택 판정

| # | 판정 | 사유 |
|---|---|---|
| J1 | **채택** | 스위처 노출 조건을 `>= 2` 에서 `>= 1` 로 완화한다. 보드가 1개여도 N개 모델임을 드러낸다 |
| J2 | **채택 (형태 변경)** | 진입점 2곳을 **스위처 드롭다운 하나로 접는다** — X1 참조 |
| J3 | **채택 (위치 변경)** | 설정 화면이 없으므로 `⋯` 메뉴에 둔다 — X2 참조 |
| J4 | **채택 (위치 변경)** | 디렉터리가 없으므로 `⋯` 메뉴에 둔다. **「이슈는 남는다」는 그대로 채택** — 소프트 삭제 |
| J5 | **채택 (권한 근사)** | 「권한 없으면 렌더하지 않는다」를 **글자 그대로 채택**한다. 권한 주체는 X3 참조 |
| J6 | **범위 밖** | BC 격리상 `project_key` 가 문자열로 고정돼 있고 AQL·search BC 와 얽힌다. **패리티 포기 후보**로 남긴다 |

### 의도적 편차

- **X1 — 진입점을 스위처 하나로 접는다.** BTS 에는 전역 Boards 디렉터리도, 사이드바 프로젝트
  hover `+` 도 **없다**. 계약 §1-5 에 따라 Jira 를 흉내내지 않고 **ADS v2 드롭다운 패턴을 준용**한다.
- **X2 · X3 — 삭제 위치와 권한.** 선행 플랜의 deviation D-2 · D-1 을 그대로 승계한다.
  위치는 디렉터리 부재로 `⋯` 메뉴다. 권한은 BTS 에 per-board 관리자 개념이 없어
  `IssuePermission.SOFT_DELETE` 로 근사하며, 도입하려면 `created_by` 마이그레이션 +
  shared-kernel 포트라 **T3 승격**이 된다.
  ⚠️ **같은 BC 안에 비대칭이 생긴다** — `SprintApplicationService.softDelete` 는 `CREATE` 를 쓴다.
  보드만 한 단계 높은 이유는 「보드 = 여러 사람이 공유하는 뷰」이기 때문이다.
  로드맵 D 의 보드 관리자 ADR 에서 스프린트 쪽과 함께 재정렬한다.
- **X4 — Scrum/Kanban 타입 선택 없음.** 3단계(로드맵 C·D) 범위다.

### 조회하지 못한 것

없다. A1 범위 전체가 선행 플랜의 조회 범위 안에 있다.
**단, 이 문서의 근거는 2026-08-25 시점이다** — Jira Cloud 문서가 그 뒤 바뀌었는지는 확인하지 않았다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
