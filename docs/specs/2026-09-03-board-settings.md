# 보드 설정 — 지라 Cloud 패리티 (설정 화면 전체 · 카드 레이아웃 포함)

> 티어 T3(예상) · type feature · BC agile-planning · **PR 미배정 — 별도 PR**
> 관련 — 이 문서는 **#444(컬럼:상태 1:N)의 형제**다. #444 는 `Columns` 탭 하나를 담당하고
> 이 문서는 **나머지 설정 표면 전체**를 담당한다.
> 지시 근거 — Maxi(2026-09-03) 「보드 설정 기능이 통채로 빠져 있는듯」 ·
> 「지라클라우드와 동일한 스펙으로 맞춰야 되겠다 이건 별도 pr로 진행할게 대신 스펙에는 추가해줘」

## 1. 문제

**보드 설정 화면이 존재하지 않는다.** `apps/web/src/routes/` 에 보드 라우트는
`projects.$projectKey.board.tsx` **하나뿐**이고 설정 화면이 없다. 백엔드에 설정 엔드포인트가
일부 있으나 **사용자가 도달할 UI 가 없어 사실상 없는 기능**이다 —
learnings 2026-07-17 「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」의
UI 판본이다.

## Jira 대조 (실물 조회 · 2026-09-03)

### 근거 표

| # | 원문 인용 | 출처 · 구분 |
|---|---|---|
| **J7** | 진입 경로 — *"From your board, select **more** () then **Configure board**."* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud |
| **J8** | 권한 — *"To configure the board and any of its settings, you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | 동일 · Cloud |
| **J9** | `Columns` — *"edit the mapping of workflow statuses to columns of a board"* | 동일 · Cloud |
| **J10** | `Swimlanes` — *"Configure swimlanes on a board to help you distinguish tasks of different categories"* | 동일 · Cloud |
| **J11** | `Quick filters` — *"Configure quick filters on a board to help you switch between work types"* | 동일 · Cloud |
| **J12** | `Card layout` — *"Customize the layout, colors, and fields on the cards on your board"* | 동일 · Cloud |
| **J13** | `Estimation and tracking` — *"Configure how you estimate work and track time"* | 동일 · Cloud |
| **J14** | `Working days` — *"Configure the timezone, and your team's standard working and non-working days"* | 동일 · Cloud |
| **J15** | `Issue detail view` — *"Customize the work item to show more fields, hide fields, and rearrange"* | 동일 · Cloud |
| **J16** 🔴 | 카드 레이아웃 경로 — *"Next to your board's name in the sidebar, select **More actions** (•••), then **Board settings**. Expand **Layout** in the sidebar, then select **Card layout**."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) · Cloud |
| **J17** 🔴 | **추가 필드 상한 3개** — *"You can configure cards on a board to display up to three additional fields."* | 동일 · Cloud |
| **J18** 🔴 | **백로그와 활성 스프린트가 서로 다른 설정을 갖는다** — *"The fields can be different for the backlog and Active sprints, if you are using a Scrum board."* | 동일 · Cloud |
| **J19** | 고정 레이아웃 — *"The work item summary is always at the top on the board and backlog. Any custom fields added to the card are next. Then details about the work item, including work type, priority, assignee, and estimate."* | 동일 · Cloud |
| **J20** | *"You can also enable **Days in column** to visually indicate how long a work item's in a column."* | 동일 · Cloud |
| **J21** | 카드 색 — *"You can base your card colors on work types, priorities, assignees, or JQL."* | 동일 · Cloud |

### BTS 현황 대조 (실측 2026-09-03)

| 지라 탭 | BTS 백엔드 | BTS 화면 | 판정 |
|---|---|---|---|
| **Columns** (J9) | `PATCH /boards/{id}/columns/{columnId}` — **WIP 제한만** | 없음 | **#444 가 담당** (매핑 편집 · 컬럼 CRUD) |
| **Swimlanes** (J10) | `PATCH /boards/{id}` 의 `swimlaneField` | **없음** — 바꿀 UI 가 없다 | **갭 A** |
| **Quick filters** (J11) | `BoardQuickFilterService` · `V504__board_quick_filters.sql` | `QuickFilterChips` · `SaveQuickFilterDialog` **있음** | **충족** (설정 화면 안으로 모을지는 편차 판단) |
| **Card layout** (J12·J16~J21) | **없음** | **없음** | **갭 B — Maxi 가 지목한 것** |
| **Estimation and tracking** (J13) | **없음**(`estimation` 키워드 0건) | 없음 | **갭 C** |
| **Working days** (J14) | **없음**(`workingDays` 0건) | 없음 | **갭 D** |
| **Issue detail view** (J15) | **없음** | 없음 | **갭 E** |
| **진입 경로·권한** (J7·J8) | 보드 API 는 `loadBoardWithCreate` 게이트 | **설정 라우트 자체가 없다** | **갭 F — 이 스펙의 뼈대** |

**#346(B2)이 카드 응답 필드를 유형·라벨·추정으로 이미 넓혔고 #349(F14)가 화면 밀도를 했다.**
즉 카드가 **무엇을 담을 수 있는지**는 갖춰졌고, 없는 것은 **「무엇을 보일지 고르는 설정」**이다.
갭 B 는 새 필드를 만드는 일이 아니라 **선택 저장소와 그 UI** 를 만드는 일이다.

## 3. 범위 제안 — 한 PR 에 다 넣지 않는다

갭이 6개(A~F)이고 각각 독립 테이블·API·화면을 가진다. **#444 와 같은 이유로 분할한다.**

| PR | 범위 | 근거 |
|---|---|---|
| **#444** (진행 중) | `Columns` — 컬럼:상태 1:N · 컬럼 CRUD · `moveCard` 계약 | 이미 착수 |
| **PR-S1** | **갭 F — 보드 설정 화면 뼈대** + 갭 A(스윔레인) · 퀵필터 이전 | 라우트·권한·탭 골격이 먼저 서야 나머지가 들어갈 자리가 생긴다. 백엔드가 이미 있는 두 개(스윔레인·퀵필터)를 태워 골격을 실증한다 |
| **PR-S2** | **갭 B — 카드 레이아웃** (J16~J21) | Maxi 가 지목한 것. 뼈대 위에 얹는다 |
| **PR-S3** | 갭 C·D·E (추정·근무일·이슈 상세 뷰) | 각각 도메인이 다르다. 추정은 스프린트 번다운과, 근무일은 타임라인과 얽힌다 |

**PR-S1 을 먼저 두는 이유** — 설정 화면이 없는 상태에서 카드 레이아웃 API 만 만들면 갭 B 도
「도달할 UI 가 없는 기능」이 되어 지금과 같은 문제를 반복한다.

## 4. 갭 B (카드 레이아웃) 스펙 초안

Maxi 가 지목한 항목이므로 이것만 요구사항 수준으로 적는다. 나머지 갭은 §3 의 PR 단위로 각자 스펙을 갖는다.

### 요구사항

| # | 요구사항 | 근거 |
|---|---|---|
| **C1** | 보드마다 카드에 표시할 **추가 필드**를 고른다. **상한 3개** | J17 |
| **C2** | 선택은 **보드 화면과 백로그가 각각 따로** 저장된다 | J18 — *"The fields can be different for the backlog and Active sprints"* |
| **C3** | **요약(제목)은 항상 최상단**이고 설정 대상이 아니다. 커스텀 필드가 그다음, 그 아래 유형·우선순위·담당자·추정 | J19 |
| **C4** | 선택 가능한 필드는 **카드 응답이 이미 싣는 것**에 한정한다 — `typeKey` · `labels` · `originalEstimateSeconds` · `assignee` · `priority`. 없는 필드를 고르게 하면 응답 확장이 선행돼야 한다 | #346(B2) 실측 |
| **C5** | 설정이 없는 보드는 **오늘과 같은 화면**을 낸다(무회귀 기본값) | — |
| **C6** | `Days in column` 인디케이터 | J20 — **이번 범위에 넣을지 판단 필요.** 컬럼 진입 시각을 저장하지 않으므로 **새 데이터가 필요하다**(이슈 전환 이력에서 파생 가능한지 확인 요) |
| **C7** | 카드 색 규칙(유형·우선순위·담당자·JQL 기준) | J21 — **JQL 기준은 search BC 소관**이라 BC 격리상 이번 범위 밖. 나머지 3종만 후보 |

### 데이터 모델 초안

```sql
CREATE TABLE board_card_fields (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID        NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    scope         VARCHAR(16) NOT NULL,   -- 'BOARD' | 'BACKLOG'  (C2 · J18)
    field_key     VARCHAR(50) NOT NULL,   -- 'typeKey' | 'labels' | …  (C4)
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (board_id, scope, field_key)
);
```

상한 3개(C1)는 **DB 가 아니라 애플리케이션이 강제**한다 — `CHECK` 로는 행 수를 못 센다.
서식 정본은 `V203__add_global_status_catalog.sql:36-60`(`workflow_statuses`).

### 확정되지 않은 것

- **C6 `Days in column` 의 데이터 출처.** 컬럼 진입 시각을 어디서 얻는가. 이슈 전환 이력이
  `issue-tracking` 소유라 BC 격리상 포트가 필요할 수 있다. **착수 전 실측 필요.**
- **C7 카드 색의 JQL 기준.** `search` BC 소관이고 X1(`board-crud-recovery` 계열)이 같은 이유로
  저장 필터 참조를 이미 배제했다. 같은 판단을 승계할지 확인 필요.
- **퀵필터를 설정 화면으로 옮길지.** 오늘 BTS 는 보드 화면 안에 칩으로 두고 지라는 설정 탭에 둔다.
  옮기면 기존 UX 가 나빠질 수 있다(칩이 한 번에 안 보임) — **의도적 편차 후보**.

## 5. 이 문서의 지위

**스펙 초안이다.** §3 의 PR-S1~S3 각각이 착수 시점에 `/bts` 체인을 다시 타고 자기 스펙을 갖는다.
이 문서는 **지라 근거(J7~J21)와 BTS 갭 실측을 지금 확보해 두는 것**이 목적이다 —
근거가 손에 있을 때 적어야 나중에 재조회 비용을 안 낸다(계약 §1-0 재사용 대상이 된다).

착수 순서와 우선순위는 Maxi 가 정한다.
