<!-- 이슈 생성 시 담당자·우선순위·라벨 1회 제출 — 권한 범위·자동배정 충돌·편집게이트·알림 4건 확정 -->

# ADR — 이슈 생성 3필드(담당자·우선순위·라벨) 1회 제출

> 날짜: 2026-07-31
> 상태: 채택 (FR-UX-09 B1, PR #328)
> BC: issue-tracking
> 관련 SDD: [11. API 설계](../sdd/11-api-design.md)
> 정본: `docs/plan/product/personalization.md` §4.7 FR-UX-09 D4/D5
> plan: [plan](../plans/2026-07-31-fr-ux-09-b1-create-issue-fields.md)

## 맥락

`POST /api/v1/issues` 는 담당자·우선순위·라벨을 받지 않는다. 프론트가 그 값을 확정하려면
`PATCH /{key}`(priority+labels) + `PATCH /{key}/assignee` 를 이어 붙여야 하고,
**중간 실패 시 반쯤 만들어진 이슈가 남는다** (CLAUDE.md §작업 기준 — 완제품 위반).

### 착수 전 실측이 뒤집은 전제 3건

1. **정본의 「PATCH 3회」는 부정확 — 실제 2회.** `priority`·`labels` 는 범용 `PATCH /{key}` 하나에
   함께 들어간다(`UpdateIssueRequest.kt:77,79`). 전용 엔드포인트 0개. 문제 자체는 그대로 성립.
2. **도메인은 이미 3필드를 완비.** `Issue.create` 가 `priority`(`Issue.kt:188`)·`labels`(`:189`)·
   `assigneeId`(`:192`) 를 받고 `validatePriority`·`validateAndNormalizeLabels` 로 검증까지 한다.
   → **도메인·DB 변경 0.** 작업은 REST DTO + `IssueApplicationService.createIssue` 배선.
3. **생성 경로에 담당자 결정이 이미 있다.** `resolveDefaultAssignee`(`IssueApplicationService.kt:260`)
   — 컴포넌트 리드 중 이름 오름차순 첫 번째(FR-IS-03 auto-assign).

### 권한 비대칭 (결정의 배경)

| 경로 | 권한 | 범위 |
|---|---|---|
| `createIssue` | `CREATE` | `IssueScope.Project` (`:225`) |
| `updateIssue` (priority·labels) | `UPDATE` | `IssueScope.Issue` (`:519`) |
| `changeAssignee` | `UPDATE` | `IssueScope.Issue` (`:800`) |

별도 `ASSIGN` 권한은 없다 (`shared-kernel/.../IssuePermission.kt:39,42`).

## 결정 (2026-07-31 Maxi 확정 4건)

### D-1. 추가 권한 검사 **없음** — `CREATE` 만으로 충분

3필드가 non-null 이어도 `UPDATE` 를 추가로 요구하지 **않는다**. 생성은 필드를 채우는 행위로 본다.

- **기각.** `securityLevelId` 선례(`:239-247`, non-null 시 `SET_SECURITY` 를
  `IssueScope.Project` 로 검사)를 3필드에 확장하는 안. KDoc `:1523` 의
  *"권한 평가 범위. 생성은 Project, 수정은 Issue."* 가 그 규칙을 이미 명문화하고 있으나,
  Maxi 는 생성 편의를 우선했다.
- **결과로 남는 성질.** `securityLevelId` 는 추가 게이트가 있고 3필드는 없어,
  **같은 함수 안에서 optional 필드의 규칙이 갈린다.** 의도된 비대칭으로 기록한다.

### D-2. `JsonNullable` 3단계 — 자동 배정과 명시 지정 구분

| 요청 형태 | 동작 |
|---|---|
| `assigneeId` 키 **생략** | `resolveDefaultAssignee` 유지 (기존 동작 = 무회귀) |
| `assigneeId: null` **명시** | 자동 배정 **비활성**, 미할당으로 확정 |
| `assigneeId: <uuid>` | 그 사용자로 확정 (자동 배정 비활성) |

- **근거.** 2단계로는 "자동 배정을 끄고 미할당으로 두기"를 표현할 수 없다.
  `UpdateIssueRequest` 의 `securityLevelId`·`startDate`·`dueDate` 가 이미 `JsonNullable` 3-state 를
  쓰므로 신규 개념이 아니다.
- **`priority`·`labels`.** 자동 결정 로직이 없으므로 `JsonNullable` 불필요 —
  생략/null 이면 `Issue.create` 의 기본값(`PRIORITY_DEFAULT` / `emptyList()`)에 맡긴다.

### D-3. FR-PM-07 필드 편집 게이트 — 생성 경로에 **미적용**

`assertEditableOrForbidden` 을 `createIssue` 에 도입하지 않는다. `createIssue` 는 현재
이 함수를 한 번도 호출하지 않으며, `summary`·`description` 등 기존 필드도 무게이트다.
그 관례를 따른다.

- **받아들인 대가 (의도된 것).** FR-PM-07 로 `assigneeId` 편집이 잠긴 사용자가
  **생성 시점에는 그 값을 설정할 수 있다.** 수정 경로(`changeAssignee:814`)만 잠기고
  생성 경로는 열려 있는 비대칭이 남는다.
- **D-1 과의 결합 (★ 기록 필수).** D-1(추가 권한 없음) + D-3(편집 게이트 없음)을 합치면
  `CREATE` 만 보유한 actor 가 **어떤 추가 검사도 없이** 3필드를 설정한다.
  두 결정을 각각 되돌리면 이 성질도 각각 완화된다.

### D-4. `IssueAssigned` 이벤트 — 자동·명시 **모두 발행**

생성 시 담당자가 확정되면 경로와 무관하게 `IssueAssigned` 를 발행한다.

- **근거.** "담당자가 정해지면 알린다"는 규칙을 전 경로에서 하나로 통일한다.
  자동 배정이 조용히 지나가던 기존 사각지대도 함께 닫힌다.
- **★ 무회귀 전제 파기 (명시).** 이 결정으로 **기존 생성 요청의 알림 동작이 바뀐다.**
  지금까지 auto-assign 으로 담당자가 정해져도 `IssueAssigned` 는 발행되지 않았다.
  PR 의 불변량에서 "알림 무회귀"를 내리고, 회귀 테스트를 그 전제로 재작성한다.
- **경계 조건.** 담당자가 **확정되지 않은 경우**(자동 배정 결과 null + 명시 생략)는 발행하지 않는다.
  발행 판정식은 "최종 `assigneeId` 가 non-null 인가" 단일 술어로 둔다.
- **관련 사고 메모리.** `preseeded-event-producer-activates-notifications`
  — 사전 시드된 이벤트 생산자가 알림을 의도치 않게 활성화한 전례. 팬아웃 검증을 D5 에 포함한다.

## 영향

- 도메인·DB·마이그레이션 **0**
- 변경 지점 = `CreateIssueRequest`(REST DTO) · `AppCreateIssueRequest` · `IssueApplicationService.createIssue`
- 알림 **동작 변경 있음** (D-4) — issue-tracking 이 발행, 소비는 notification BC (이벤트 계약 무변경)
- cross-BC 프로덕션 의존 **0**

## 후속 (별건)

- **생성 경로 필드 게이트 부재** — D-3 이 남긴 비대칭. `summary` 등 기존 필드까지 포함해
  `createIssue` 전반에 FR-PM-07 을 적용할지는 폭발 반경이 커 별도 PR. TODOS 등재.
- **optional 필드 권한 규칙 이원화** — D-1 이 남긴 성질. `securityLevelId` 만 게이트가 있다.
