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
- **⚠️ 범위는 D-5 가 한정한다.** 아래 D-5 를 반드시 함께 읽을 것. "경로와 무관하게"는
  `createIssue` 를 타는 **모든** 경로를 뜻하지 않는다.

### D-5. D-4 의 적용 범위 — ~~**REST 생성 경로만**~~ → **사람의 편집 행위 전부**. Import 제외

> **★2026-08-10 개정.** 원문 제목의 「REST 생성 경로만」은 **더 이상 사실이 아니다.**
> 그때는 `createIssue` 한 함수만 보고 내린 결정이었는데, 그 뒤 실측으로 **같은 양식의 배정
> 통로가 둘 더** 드러났다(Clone · changeComponents 자동배정). 셋 다 「사람의 편집 행위로
> 담당자가 확정된다」는 성질이 같은데 알림만 갈렸다 — **경로에 따라 통보 여부가 달라지는**
> 것은 사용자가 이유를 알 수 없는 비대칭이다.
>
> **바뀐 것은 적용 범위이고, 설계(fail-safe 기본 false)는 그대로다.** 아래 「설계」 절의
> 근거는 여전히 유효하다 — 오히려 통로가 셋으로 늘면서 「새 생산자가 알림을 켠 채로
> 태어나면 안 된다」가 더 중요해졌다.

`IssueAssigned` 는 **사람의 편집 행위로 담당자가 확정되는 모든 경로**에서 발행한다.
각 경로는 `notifyAssignment` 게이트를 갖고, **기본값은 전부 false** 이며 진입 컨트롤러만 `true` 를 넘긴다.

| 경로 | 진입점 | 발행 | 게이트 | 봉합 시점 |
|---|---|---|---|---|
| REST 생성 | `IssueController.create` | ✅ | `AppCreateIssueRequest.notifyAssignment` | 2026-07-31 (원안) |
| Clone 복제 | `IssueController.clone` → `cloneIssue` | ✅ | `CloneIssueRequest.notifyAssignment` | 2026-08-09 |
| 컴포넌트 교체 자동배정 | `IssueController.changeComponents` | ✅ | `AppChangeComponentsRequest.notifyAssignment` | 2026-08-10 |
| 담당자 직접 변경 | `changeAssignee` | ✅ | **게이트 없음 (무조건)** | 원래부터 |
| Import 반입 | `IssueImportAdapter` | ❌ | `AssigneeIntent.None` 으로 자동배정 자체를 끔 | 2026-08-09 |

**★`changeAssignee` 만 게이트가 없는 것은 의도된 것이다.** 그 함수는 **배정 자체가 목적**이라
모든 생산자가 알림을 의도한다. 나머지 셋은 배정이 **부수효과**로 일어나므로 게이트를 둔다.
이 구분을 지우고 「전부 무조건 발행」으로 통일하지 말 것 — 대량 경로가 생기는 순간 알림함이 마비된다.

**★Import 의 처방이 바뀌었다 (2026-08-09).** 원래는 `notifyAssignment` 기본값 false 에
기대어 「발행 안 함」이었는데, 그것만으로는 **원본에 없던 담당자가 생기는** 별개 결함
(자동 배정이 그대로 남음)이 닫히지 않았다. 이제 `AssigneeIntent.None` 으로 **자동 배정 자체를
끈다.** 그 결과 아래 「근거」의 「이슈 1건당 2회」 시나리오는 **도달 불가**가 됐다 —
기본값 false 를 유지하는 근거는 이제 **defense-in-depth** 다.

**근거 — 스펙 단계 실측(G1).** Import 는 `createIssue` 로 이슈를 만든 **직후**
`applyAssigneeIfPresent`(`:593-607`)가 `changeAssignee` 를 호출해 원본 담당자를 다시 지정한다.
`changeAssignee` 는 이미 `IssueAssigned` 를 발행하므로(`:836`), D-4 를 무제한 적용하면

1. `createIssue` 의 `resolveDefaultAssignee` 결과로 **1회** (신규 — 곧 덮어쓰일 임시 담당자)
2. `changeAssignee` 의 원본 담당자 적용으로 **1회** (기존)

= **이슈 1건당 2회**, 그중 첫 번째는 **사실이 아닌 알림**이다. 알림 억제 장치는 없다(grep 0건).
대량 반입에서 알림함이 마비된다.

**설계 — fail-safe 기본값.** 발행 여부를 `AppCreateIssueRequest` 의 필드로 표현하고
**기본값을 「미발행」으로 둔다.**

```kotlin
/** IssueAssigned 발행 여부. 기본 false — 새 생산자가 생겨도 알림이 조용히 켜지지 않는다(D-5). */
val notifyAssignment: Boolean = false,
```

REST 컨트롤러만 `true` 를 넘긴다. Import 는 기본값을 그대로 받아 아무 변경이 없다.
기본값을 반대로 두면(기본 true + Import 가 opt-out) 미래의 신규 생산자가
**알림을 켠 채로 태어난다** — 메모리 `preseeded-event-producer-activates-notifications` 의 재발.

**기각안.** `IssueImportAdapter` 에서 이벤트를 사후 필터링 — 발행은 이미 일어난 뒤라
구독자 쪽에 억제 로직이 필요해지고 BC 경계를 넘는다.

~~**남는 비대칭 (기록).** `cloneIssue` 는 `includeAssignee=true` 로 담당자를 설정하면서도
`IssueCreated` 만 발행한다(`:365`). "담당자가 정해지면 알린다"가 clone 에는 적용되지 않는다.
**별건 후속**으로 남긴다.~~

> **✅ 2026-08-09 해소 (clone) · 2026-08-10 해소 (changeComponents).** 위 「남는 비대칭」은
> 둘 다 닫혔다. 위 표를 정본으로 볼 것.
>
> **★이 항목이 남긴 교훈.** 「별건 후속으로 남긴다」로 적힌 비대칭은 **적어 두는 것만으로는
> 닫히지 않는다.** 실제로 clone 은 이 문장이 쓰인 뒤 9일을 그대로 살아 있었고,
> 그 사이 `changeComponents` 라는 **같은 양식의 세 번째 통로**가 아무도 모르게 존재했다.
> 후자는 clone 항목의 적대적 반증이 **형제 진입점을 전수로 훑다가** 잡았다 —
> 한 지점을 고칠 때 「같은 성질의 지점이 더 있는가」를 전수로 묻지 않으면 반쪽 봉합이 된다.

## 영향

- 도메인·DB·마이그레이션 **0**
- 변경 지점 = `CreateIssueRequest`(REST DTO) · `AppCreateIssueRequest` · `IssueApplicationService.createIssue`
- 알림 **동작 변경 있음** (D-4) — issue-tracking 이 발행, 소비는 notification BC (이벤트 계약 무변경)
- cross-BC 프로덕션 의존 **0**

## 후속 (별건)

- **생성 경로 필드 게이트 부재** — D-3 이 남긴 비대칭. `summary` 등 기존 필드까지 포함해
  `createIssue` 전반에 FR-PM-07 을 적용할지는 폭발 반경이 커 별도 PR. TODOS 등재.
- **optional 필드 권한 규칙 이원화** — D-1 이 남긴 성질. `securityLevelId` 만 게이트가 있다.

### D-6. 도메인 `require` 실패의 500 — **생성 경로만** 400 보장 (2026-07-31 Maxi 확정)

구현 중 실측으로 드러난 선재 결함에 대한 범위 결정이다.

**실측 3건.**
1. `CreateIssueRequest`/`UpdateIssueRequest` 의 `List<@Size(max = 50) String>` **컨테이너 원소 제약은
   동작하지 않는다.** 51자 라벨이 400 이 아니라 도메인까지 내려간다
   (`IssueApplicationServiceTest.kt:955` 가 *"도메인 검증"* 이라 적어둔 게 증거).
2. `IssueExceptionHandler` 에 `IllegalArgumentException` 핸들러가 **없다**.
   기존 핸들러 2개(`BulkOperationExceptionHandler:99`·`EpicChildExceptionHandler:104`)는
   각자 자기 패키지 스코프라 `IssueController` 를 덮지 않는다 → **500**.
3. 라벨 **개수** 제한(`@field:Size(max = 20)`)은 정상 동작한다. 원소 제약만 무효다.

**결정.** 생성 경로는 `CreateIssueRequest.isLabelsValid`(`@AssertTrue`)로 **길이 + 공백-only**
둘 다 400 으로 막는다. 수정 경로(`PATCH`)의 동일한 선재 500 은 **TODOS 등재 후 이연**.

- **기각.** 전역 `IllegalArgumentException → 400` 핸들러 — 진짜 버그까지 400 으로 위장해
  살아있어야 할 500 을 숨긴다(`catch-all-exceptionhandler-swallows-responsestatusexception` 계열).
- **동작하지 않는 어노테이션은 복사하지 않았다.** 형제와 문자 단위로 맞추면 새 경로도 같은 500 을
  물려받는다. 「선례 일치」보다 「실제 동작」이 우선이다.
- **공백-only 는 별도 조건이다.** 길이만 막았을 때 `"   "` 가 여전히 500 이었다(E6 RED 로 실증).
  빈 문자열(`""`)은 도메인이 필터링하므로 400 대상이 아니다 — 대비 축으로 함께 단언했다.

## 구현 실측 (PR #328 완료 시점)

| 항목 | 값 |
|---|---|
| FR 수 | 불변 **139** (`verify-master-plan.sh` EXIT 0) |
| 마이그레이션 | **0** |
| 프론트 `apps/web` | **0 파일** |
| `package.json` | diff **0** |
| cross-BC | **0 파일** (issue-tracking 단일) |
| 응답 스키마 | diff **0** (`OpenApiContractTest.C1b`) |
| **알림 동작** | **무회귀 아님** — D-4 로 `IssueAssigned` 신규 발행(REST 한정) |
| 테스트 | 307 클래스 / **3245건** / 실패 0 |
| ktlint · detekt | EXIT **0** (`--rerun-tasks`) |
| TDD `test:`→`feat:` | **8쌍** 기계 검증 |
| 뮤테이션 | **M1·M2·M3 전량 red**, 원복 후 green |

**D-3 준수 실측.** `createIssue` 내 `assertEditableOrForbidden` 호출 **0건**
(파일 전체 3건 — `changeAssignee` 등 수정 경로에만. 양성 대조군).
**C1 준수 실측.** 응용 계층의 `JsonNullable` import **0건** / 코드 사용 **0건**
(KDoc 언급 6건은 결합이 아니다). 컨트롤러엔 1건 — 양성 대조군.
