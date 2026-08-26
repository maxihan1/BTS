# PR #404 잔여 부채 6건 등재 + `project-workflow` 영역 신설

> 티어: T2
> slug: debt-404-residual-six
> type: chore
> agent: backend-engineer
> 생성: 2026-08-26

## Brief

**FR 없음 — FR수 불변 143.** 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 ·
신규 의존성 0 · 신규 API 0. 장부(`TODOS.md`) · 매핑(`docs/plans/2026-08-12-debt24-master.md`) ·
대시보드 분류 상수 전용 작업이다.

**대상.** PR #404 게이트 2 라운드 3 에서 **기각하거나 미룬 6건**을 장부에 등재한다. 그 라운드의
반영 8건은 이미 닫혔고, 기각 사유는 `docs/plans/2026-08-25-backend-workflow-transition-rule-crud.md`
의 `## 리뷰 결과 (PR 단위)` 라운드 3 절에 남아 있다. **그 절은 plan 문서 안의 산문이라
`debt-ledger-mapping` 이 보지 않는다** — 산문에만 두면 이 저장소가 이름 붙인
「두 목록이 서로를 검사하지 않는다」를 그대로 재생산하게 되므로 장부로 옮긴다.

| 번호 | 제목 | 라운드 3 판정 |
|---|---|---|
| **130** | project-workflow — 프레임워크 예외 3종이 도메인 에러 봉투 밖으로 샌다 | PRE_EXISTING (모듈 전체) |
| **131** | project-workflow — `ValidatorResponse` 에 편집 가능 여부가 없어 D6 가 목록을 손으로 든다 | D6 착수 시 재검토 |
| **132** | project-workflow — 편집 불가 조건이 코드는 허용 목록, 계약 문서는 `CustomExpression` 한 값 | 현재 동작은 정확 |
| **133** | project-workflow — `config` JSONB 가 크기·키 제한 없이 저장된다 | 권한 뒤라 상승 경로 아님 |
| **134** | project-workflow — 전환 규칙 변경 로그에 행위자가 없다 | 형제와 같은 상태 · MDC 부재 |
| **135** | project-workflow — 전환 규칙 표면에 남은 사본 5종 | 동작 영향 0 |

## 도메인 정리

### 왜 「TODOS.md 에 6줄 추가」로 안 끝나는가

부채 항목은 장부 단독으로 존재할 수 없다. 판별식 5종이 네 파일을 서로 묶어 놨다.

| 파일 | 판별식이 요구하는 것 |
|---|---|
| `TODOS.md` | 항목마다 **「쉬운 말」·「방치하면」 두 줄**(각 20자↑) · 영역 접두 · 자기 카테고리 절 안 |
| `docs/plans/2026-08-12-debt24-master.md` | §전수 매핑 행 — 장부와 **양방향 차집합 0** |
| `scripts/build-dashboard.mjs` | `AREA_CATEGORIES` — 실제 영역 집합과 **양방향 일치** |
| `scripts/workflow/todos-plain-language-contract.test.ts` | `EXPECTED` — 배정이 사람 판단이라 **두 번째 목록**을 두고 대조 |

### `project-workflow` 영역이 매핑에 없다

`AREA_CATEGORIES` 는 8개다 — `apps/web` · `issue-tracking` · `search-export-import` ·
`identity-access` · `도구` · `워크플로우` · `인프라` · `문서`.

`TODOS.md` 에 `project-workflow` 접두 항목이 5건 있지만 **전부 `✅ 해소`** 다. 두 줄 계약과
카테고리 대조는 `CONTRACTED_STATUSES = ['미착수','보류']` 만 보므로 해소분은 판정을 안 탄다 —
그래서 매핑이 비어 있는 채로 초록이었다.

> **`워크플로우` 접두로 대신할 수 없다.** 그 접두는 `CATEGORIES.guard`(개발 안전장치)로 가고,
> 실제 41건이 전부 판별식·스킬 체인·리뷰 렌즈 얘기다. project-workflow **BC** 를 뜻하지 않는다.

**선례가 판별식 주석에 있다.** `identity-access` 도 2026-08-24 에 처음 등재되며 같은 절차를 밟았고,
그 주석이 배정 근거까지 적어 뒀다 — 「인증/권한 BC 이지만 사용자에게는 「기능이 되느냐」로
나타나므로 `기능 동작` 이다」. 같은 논리로 `project-workflow` 도 `CATEGORIES.feature` 다.
BC 4종(`issue-tracking` · `search-export-import` · `identity-access` · `project-workflow`)이
모두 `기능 동작` 으로 모이는 것도 일관된다.

## 스펙

### red 실측 (착수 전 2026-08-26)

`TODOS.md` 에 `## ⬜ project-workflow — RED 프로브 (임시 · 미착수 · T2)` 를 한 건 넣고 판별식을
돌렸다. **5개 판정이 red** 였다.

- `카테고리 매핑이 실제 영역 집합과 양방향으로 같다` → `매핑에 없는 영역: project-workflow`
- `실파일 렌더에서 영역이 배정된 카테고리 이름 아래 나온다`
- `카테고리 표시 순서가 선언 순서와 같다`
- `실파일 렌더에 '분류 없음' 묶음이 없다`
- `★★장부에만 있고 마스터 계획에 없는 항목이 0 이다`

프로브는 원복했다. **이 다섯이 이번 작업의 판정자다** — 넷은 영역 신설이, 하나는 마스터 행 추가가 닫는다.

### 등재할 6건 — 내용

**130. 프레임워크 예외 3종이 도메인 에러 봉투 밖으로 샌다**
비-UUID `{id}`(`MethodArgumentTypeMismatchException`) · 깨진 JSON 본문(`HttpMessageNotReadableException`) ·
미인증(`ResponseStatusException` 401)이 `{error:{code,message}}` 를 벗어나 Spring 기본 응답으로 나간다.
`project-workflow` **모듈 전체에 그 3종 advice 가 0건**이고 형제 `PostActionExceptionHandler` 도
`main` 에서 도메인 예외 3종만 다룬다 — PR #404 가 만든 구멍이 아니다. 다만 그 PR 이 새 엔드포인트
4개를 그 관례 위에 얹어 노출 범위가 넓어졌다.
> `search-export-import` 에 선례가 있으나 **`ProblemDetail`(RFC 7807) 반환이라 봉투가 다르다.**
> 그대로 이식하면 이 BC 의 `ErrorResponse` 계약이 깨진다 — 선례를 확인 없이 따랐으면 새 결함이 됐다.

**131. `ValidatorResponse` 에 편집 가능 여부가 없다**
목록은 편집 불가 타입 행까지 돌려주는데(의도 — 반쪽 목록은 관리자를 속인다) 응답에 그 사실이 없다.
허용 목록은 `ValidatorAdminService` 의 `is` 검사 한 줄에만 있고 문서·SDD 열·판별식 축 어디에도 없다.
D6 편집 UI 는 편집 불가 행을 읽기 전용으로 그려야 하므로 그 목록을 **손으로 들게 된다.**
Task 14 가 `phase` 를 노출하며 받아들인 논거와 같은 형태다.

**132. 편집 불가 조건이 코드와 계약 문서에서 다르게 표현된다**
코드는 **허용 목록**(`RequiredField` · `permission-check` · `not-status-category` 밖은 전부 거부),
계약 문서 3곳(에러 표 · FR-6 · 시나리오 S4)은 **`type=CustomExpression`** 한 값으로 적는다.
팩토리 분기가 마침 4개라 지금은 두 표현이 일치한다. **다섯 번째 type 이 생기는 순간 문서가 거짓이 된다** —
allowlist 는 fail-closed 라 새 type 이 자동으로 편집 불가가 되는데, 그 계약을 읽은 클라이언트는
생성 가능하다고 기대한다.

**133. `config` JSONB 가 크기·키 제한 없이 저장된다**
`ValidatorRequest.config` 에 `@Valid`·`@Size`·키 개수 상한이 없다. 팩토리 dry-run 은 자기가 아는 키만
읽고 나머지는 검증 없이 그대로 JSONB 에 저장돼 GET 으로 돌아온다(테스트가 「여분 키가 섞여도 통과」로
의도를 고정). `MANAGE_SCHEME`/Global 뒤라 권한 상승 경로는 아니고 저장·가용성 관심사다.
> 부수 이득 — 모르는 키를 거부하면 오타(`categroy`)가 **400** 이 된다. 지금은 저장된 뒤
> 인스턴스화 실패로 `phase=null` 이 되어 조용히 안 도는 규칙이 된다.

**134. 전환 규칙 변경 로그에 행위자가 없다**
`ValidatorAdminService` 의 생성·수정·삭제 로그가 무엇을 바꿨는지는 적지만 **누가** 바꿨는지는 안 적는다.
전환 규칙은 그 자체가 보안 통제다 — `permission-check` validator 는 누가 이슈를 전환할 수 있는지를
막는 문이고, 그것을 **떼는 것**도 이 API 로 가능하다. 행위자는 두 프레임 위
`ManageSchemeGuard.requireManageScheme()` 가 방금 해석했는데 서비스로 안 넘어온다.
모듈 전체에 MDC 필터가 없어 주변에서 채워지지도 않는다. 형제 `PostActionAdminService` 도 같은 상태다.

**135. 전환 규칙 표면에 남은 사본 5종**
① `PostActionControllerTest` 의 403 스텁 4벌 — 같은 PR 이 만든 형제 `ValidatorControllerTest` 는
`denyPermission()` 헬퍼로 접었는데 확장한 쪽에는 적용 안 됐다 ② 편집 허용 목록이 한 파일 안에
KDoc 2곳 + 코드 1곳 ③ 403 응답 본문이 두 예외 핸들러에 같은 코드·같은 메시지로 2벌
④ 두 팩토리 헤더 주석이 `when` 분기 개수를 적는다(같은 PR 이 KDoc 에서는 「분기가 정본」이라며
목록을 지웠는데 헤더의 개수는 남았다) ⑤ `TransitionRuleRepository.findByTransitionId` 의 람다 지역변수가
함수 파라미터 `transitionId` 를 가린다.

## Sanity Check

- **왜 지금인가.** 6건 다 「나중에」로 미룬 것이고, 미룬 사유가 plan 산문에만 있다. 산문은 판별식이
  안 본다 — D6 착수 때 이 6건을 다시 발견하고 같은 판단을 처음부터 되풀이하게 된다.
- **더 간단한 길은 없나.** 영역 신설 없이 기존 8개에 넣는 방법을 검토했고 **기각**했다.
  `워크플로우`(guard)는 판별식·스킬 체인을 뜻하고 `문서`(docs)는 6건 중 1건에만 맞다.
  대시보드 분류가 틀리면 「지금 남은 빚」이라는 그 화면의 목적 자체가 훼손된다.
- **범위를 넘지 않나.** `scripts/**` 2파일을 건드려 실측 T2 가 됐다(`SURFACES: TEST, GUARD_CI, DOC`).
  Maxi 가 게이트 1 에서 승격을 승인했다. 그 2파일 수정은 **영역 신설에 필요한 최소**이고, 판별식이
  「같은 커밋에서 두 목록을 함께 고쳐라」로 명시한 것을 따르는 것이다.
- **개수를 적지 않는다.** 이 문서의 6건은 표로 **전수 열거**했다. 직전 PR(#405)에서 「4모듈」이라는
  개수를 들고 다니다 5번째를 놓친 자리가 있다.

## Plan

### Task 1. `AREA_CATEGORIES` 에 `project-workflow` 신설 + 기대 목록 동반 갱신

- files: [`scripts/build-dashboard.mjs`, `scripts/workflow/todos-plain-language-contract.test.ts`]
- depends-on: []

두 파일을 **같은 커밋**에서 고친다. `EXPECTED` 는 「검사 대상 상수에서 읽으면 동어반복」이라
의도적으로 둔 두 번째 목록이고, 판별식 주석이 「표를 의도적으로 바꾸려면 이 목록도 같은 커밋에서
고쳐라」로 못박았다. 배정은 `CATEGORIES.feature` 이고 근거를 `identity-access` 선례와 같은 형태의
주석으로 남긴다.

> 이 단계만으로는 `unused: ['project-workflow']` 로 여전히 red 다 — 실제 항목이 있어야 양방향이
> 닫힌다. **Task 1 과 2 는 한 쌍이고 중간 상태가 초록인 구간이 없다.**

### Task 2. `TODOS.md` 에 6건 등재

- files: [`TODOS.md`]
- depends-on: [1]

`# 기능 동작` 절에 넣는다(`project-workflow` → `feature` 배정의 귀결). 항목마다 두 줄 계약 +
`**무엇**` + `**처방**` + `**왜 지금이 아니라 부채인가**` 를 기존 항목과 같은 형태로 적는다.
제목 접두는 `project-workflow — ` 로 고정한다.

### Task 3. 마스터 §전수 매핑에 6행

- files: [`docs/plans/2026-08-12-debt24-master.md`]
- depends-on: [2]

번호 **130~135**(현재 최대 129). 형식은 `| **<번호>** | ⬜ | <영역> — <제목> | **미배정** | <영역> |`.
제목은 `TODOS.md` 헤딩에서 마커와 괄호를 뺀 본문과 **같아야** 한다 — 장부 판별식이 정규화 후 대조한다.

### Task 4. 판별식 전량 + 대시보드 재생성 확인

- files: []
- depends-on: [3]

`node --experimental-strip-types --test 'scripts/**/*.test.ts'` 전량 green ·
`node scripts/build-dashboard.mjs` 로 `분류 없음` 묶음 0 · `verify-master-plan.sh` EXIT 0 ·
`build-doc-index.mjs --check` drift 0.
**Task 1 을 되돌려 red 를 한 번 더 본다** — 영역 신설이 실제 판정자인지 확인하는 짝이다.

## Plan 메타

- **task 수**. 4
- **구현 규율**. red 를 착수 전에 이미 봤다(§스펙 red 실측). Task 1·2 는 한 쌍이라 중간 green 이 없고,
  Task 4 가 되돌리기 뮤테이션으로 판정력을 재확인한다.
- **회귀 표면 0**. 프로덕션 코드를 안 건드린다. `build-dashboard.mjs` 는 대시보드 생성기이고
  출력(`docs/progress.html`)의 분류 묶음이 하나 늘어나는 것이 유일한 동작 변화다.
- **추가 검증**. `node scripts/build-dashboard.mjs` 후 `progress.html` 의 카테고리 순서가
  선언 순서와 같은지(판별식이 강제).

## 리뷰 결과

(← /bts-review-plan · 게이트 2 에서 채운다)
