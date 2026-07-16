# FR-AT-07 PR-B — Fix Version 설정 통로

> slug: fr-at-07-pr-b-fix-version
> type: feature
> agent: backend-engineer
> 생성: 2026-07-16
> 선행: PR-A (#274, 머지 완료) · #275 (본문 상한 DoS 가드, 머지 완료)
> 후속: PR-C (PR_MERGED 트리거 + Git webhook)
> 마스터 스펙: [docs/specs/2026-07-15-fr-at-07-pr-merge.md](../specs/2026-07-15-fr-at-07-pr-merge.md) **§B**

## Brief

FR-AT-07(PR 머지 연동 — Fix Version 자동 설정)의 3분할 중 **PR-B**. DEC-11(Maxi 확정)에 따라
PR-A(인바운드 웹훅 prod 도달 가능화) → **PR-B(Fix Version 설정 통로)** → PR-C(PR_MERGED 트리거 + Git webhook) 순서.

PR-C가 "PR 머지 웹훅을 받으면 이슈의 Fix Version을 설정한다"를 완성하려면, 그 **설정 통로가 먼저 존재해야 한다**.
현재 automation BC에는 Fix Version을 건드릴 수단이 없다(스펙 §F3 — 기존 포트로 설정 불가).
이 PR은 그 통로만 만들고, 트리거 연결은 PR-C로 미룬다.

### 범위 (마스터 스펙 §B-1, FR-B1~B4)

| ID | 요구사항 |
|---|---|
| FR-B1 | `IssueMutationPort.setFixVersions(SetFixVersionsCommand)` — default 메서드 없음(fail-closed) |
| FR-B2 | issue-tracking 어댑터 구현 — `changeFixVersions` 유스케이스 위임 |
| FR-B3 | `ActionType.SET_FIX_VERSIONS` + config `{versionIds:[UUID...]}` (빈 배열 = 전체 해제) |
| FR-B4 | 프론트 계약 동기화 — `actionTypeSchema` z.enum + 라벨 맵 |

### classify 결과 (Maxi 확정으로 오판 정정)

`classify-task.ts`는 `type=migration / agent=db-engineer`로 판정했으나 **제목의 "마이그레이션" 키워드 매칭 오판**.
실제 비중은 Kotlin 포트/어댑터 + sealed class 파급 13지점이 본체이고 마이그레이션은 CHECK 제약 1건.
→ **Maxi 확정. `type=feature` / `agent=backend-engineer`** (automation BC 소관 에이전트).
task 단위로 db-engineer(CHECK 마이그레이션) · frontend-engineer(Zod 계약) 지정 — FR-AT-06 선례 동형.

### 사전 식별된 함정 (스펙 §B-2/§B-3 + 메모리)

1. **`hasObservableSideEffect`(RuleConflictAnalyzer:317-319)는 컴파일러가 안 잡는다.** sealed class exhaustive `when`은
   컴파일 에러로 누락을 강제하지만, 이 지점만 `it is X || it is Y` **boolean 체인**이라 누락해도 컴파일 통과 →
   PRIORITY_AMBIGUITY 충돌 탐지가 조용히 SET_FIX_VERSIONS를 무시. 회귀 테스트 필수
   ([[archunit-vacuous-rule-silent-pass]] 동종 — 통과가 검증을 의미하지 않음).
2. **프론트 Zod 미동기화 시 룰 목록 화면 전체가 깨진다.** 백엔드만 ActionType을 추가하면 해당 룰 조회 시
   `actionTypeSchema` parse 실패로 화면 붕괴([[zod-schema-strengthen-inline-mock-fanout]]).
3. **V302 편집 금지** — 이미 적용된 마이그레이션 편집은 체크섬 드리프트([[app-test-persistent-db-migration-checksum-trap]]).
   신규 마이그레이션 파일로 CHECK 갱신. V번호는 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).
4. **`expectedVersion` 없음** — 스펙 §B-2가 1회차 설계를 코드로 반증. 어댑터가 자기 트랜잭션에서
   `findByKey().version` 재조회 + `runWithOccRetry`. 호출자는 OCC를 알 필요 없음.
5. **enum 추가는 타 모듈 카운트 가드도 깬다**([[enum-add-breaks-crossmodule-count-guard]]) — `ActionTest.kt:18,22`
   `entries.size shouldBe 4` → 5 외에 전모듈 grep 필요.

### BC 격리 예외 (CLAUDE.md §핵심 패턴)

한 PR = 한 BC 원칙의 예외. 이 PR은 **shared-kernel · issue-tracking · automation · apps/web** 4곳을 건드린다.
사유 = **포트 신설은 정의상 BC 경계를 가로지른다**(shared-kernel에 포트 선언 · issue-tracking에 어댑터 구현 ·
automation이 소비). 포트를 쪼개 여러 PR로 나누면 중간 PR이 **컴파일 불가 또는 미사용 죽은 코드** 상태가 된다.
→ /bts-plan 단계에서 이 사유를 §리스크에 명시 (PR #13 옵션 C 선례).

## 도메인 정리

- **BC**. automation(소비) · shared-kernel(포트 선언) · issue-tracking(어댑터 구현) · slack-integration(스텁 파급) · apps/web(Zod 계약)
  → **BC 격리 예외** — 사유는 ADR D4 (포트 신설은 정의상 경계를 가로지름, 쪼개면 중간 PR이 컴파일 불가)
- **영향 엔티티**. `Issue.fixVersionIds`(기존) · `Action`(sealed class, 서브클래스 1종 신설) · `ActionType`(enum 4→5)
- **새 용어**. "수정 예정 버전(Fix Version)" + "영향 버전(Affects Version)" → **glossary 등재 완료**(Maxi 승인, §관계/연결).
  기존 `버전 | Version` 항목이 "fix/affects 관계로 이슈에 연결"이라고만 언급하고 정작 두 관계를 정의하지 않던 갭 해소
- **기존 결정 충돌**. **없음**. FR-AT-02 ADR은 "3 메서드"를 못박지 않고 *"issue-tracking에 자동화용
  setField/assign/addComment 커맨드 경로가 없으면 신설"* 이라고만 서술 → 4번째 메서드는 자연스러운 확장
- **관련 ADR**. [docs/decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md](../decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md) **(신규 생성)** ·
  [2026-07-11-fr-at-02-automation-actions.md](../decisions/2026-07-11-fr-at-02-automation-actions.md)(포트 정본) ·
  [2026-07-15-slack-inbound-permitall-central.md](../decisions/2026-07-15-slack-inbound-permitall-central.md)(PR-A)
- **grill-with-docs**. Maxi 확정으로 **스킵** — 마스터 스펙이 적대적 검토 2회차 + Maxi 확정(D1~D6, DEC-11~17)을
  이미 거쳤고, 본 단계에서 코드 전수 대조로 대체(아래). 확정된 D3/DEC-11 재심의 회피

### 스펙 §B 주장 ↔ 코드 전수 대조 (2026-07-16)

learnings 2026-05-20 "phantom 엔티티 가설 검증 누락"의 재발 방지 — 문서가 단언한 코드 사실을 **전부 grep으로 실재 검증**.

**일치 (오차 0)**. §B-3의 13개 라인 인용 **전부** · `IssueMutationCommands.kt:34-60` · `AutomationIssueMutationAdapter.kt:100-105,118-124,162-172` ·
`IssueSnapshot` 9필드에 `version` 없음(`:47-57`) · `automation-rules.types.ts:24` · `automation-rules.types.test.ts:273` · `ActionTest.kt:18,22`

**신규 확인**.
- `changeFixVersions` **실재**(`IssueApplicationService.kt:944-969`), 요청 DTO = `AppChangeVersionsRequest(versionIds, expectedVersion)`(`IssueApplicationRequests.kt:219-222`)
- `validateVersions`(`:1636-1643`)가 `findById(id, projectId)` **프로젝트 스코프 조회** → 타 프로젝트 버전 자동 차단, `IssueLinkedVersionNotFoundException` 전파 (§B-4 EC 주장 사실)
- `assignFixVersions`(`Issue.kt:295`) = `ids.filterNotNull().distinct()` → **중복 제거 + 순서 보존**, 빈 목록이 곧 전체 해제 (FR-B3 "빈 배열 = 전체 해제"가 **별도 분기 없이 성립**)
- 어댑터는 `@Profile("prod")` — non-prod full-boot는 각 BC의 `StubIssueMutationPort`가 채움
- `SetFixVersions`/`setFixVersions` 문자열 저장소 전체 **0건** → 완전 신규

**★ 스펙이 놓친 파급 (ADR D3에 확정 기록)**. 이 중 4종은 **컴파일이 깨져** 선택의 여지가 없다.

| 지점 | 컴파일 강제 |
|---|---|
| `shared-kernel/IssueMutationPortContractTest.kt:24-37` — 익명 객체 미구현 | **깨짐** |
| **`slack-integration/StubIssueMutationPort.kt`** — `SlackInteractionService.kt:91`이 포트 소비 (스펙은 slack을 범위 밖으로 뒀음) | **깨짐** |
| `automation/StubIssueMutationPort.kt` | **깨짐** |
| 프론트 라벨맵 2곳 `ActionConfigEditor.tsx:48-53` · `AutomationRuleList.tsx:62-67` (`Record<ActionType,string>`) | **깨짐 TS2741** |
| 프론트 `parseActionConfig:342-364` · `serializeActionConfig:378-397` (`default:` 없는 switch + strict) | **깨짐 TS2366** |
| `SchemaMigrationTest.kt:654-658` — V302 CHECK 4종 하드코딩 | 안 깨짐 → V306 짝 테스트 필요 |
| KDoc "4종" 4곳(`AutomationRuleRequests.kt:75` · `AutomationRuleService.kt:646` · `AutomationRulesYaml.kt:73` · `ActionType.kt:1`) | 안 깨짐 (문서 drift) |

**★ 유일한 조용한 실패 지점 2곳** (컴파일러·테스트 둘 다 안 잡음 → 회귀 테스트로만 방어 가능).
1. `RuleConflictAnalyzer.hasObservableSideEffect:317-319` — boolean 체인. 누락 시 PRIORITY_AMBIGUITY 탐지가 조용히 무시
2. `automation-rules.types.test.ts:273` `validTypes` 배열 — 누락 시 새 타입이 그냥 미검증

**V번호**. automation 예약 구간 V300~V399, 현재 최신 **V305** → 신규 **V306** (V302 편집 금지, 체크섬 드리프트)

## 스펙

전체 스펙. [docs/specs/2026-07-16-fr-at-07-pr-b-fix-version.md](../specs/2026-07-16-fr-at-07-pr-b-fix-version.md) **(개정 4회차)**
상위. [마스터 스펙 §B](../specs/2026-07-15-fr-at-07-pr-merge.md)

**핵심 3줄 요약.**
- `IssueMutationPort`에 4번째 메서드 `setFixVersions`를 뚫고(default 없음 = fail-closed), 어댑터가 기존
  `changeFixVersions` 유스케이스에 위임한다 — 권한·검증·OCC·히스토리가 전부 기존 경로 그대로 강제된다
- automation에 `SET_FIX_VERSIONS` 액션을 추가한다 — sealed class exhaustive `when` 12지점은 컴파일러가 강제하지만
  **값의 정합성은 강제하지 않으므로** 스펙 §8.2가 12지점의 값을 전부 명시한다
- 프론트는 계약 동기화 + **설정 UI**까지 한다(Maxi 확정) — 드롭다운이 `actionTypeSchema.options` 자동 파생이라
  계약만 넣으면 사용자가 고른 뒤 입력 영역이 비고, 그 상태로 저장하면 **기존 Fix Version이 전부 지워진다**

**Maxi 확정 3건.**
1. **FR-5 설정 UI 포함** — "계약만"은 반쪽 제품(§2.1)
2. **FR-6 명시적 의도 선택** — "선택한 버전으로 교체" / "전체 해제" 2모드, 교체+빈목록은 저장 거부.
   `fixVersionsMode` **`undefined ≡ replace`**(fail-closed) — 6조합 전수 검증 통과
3. **게이트 진행** — Phase B 3라운드 후 `/bts-plan`으로

## Brainstorming Check

⚠️ **3회 iteration 전부 BLOCKER 발견 후 개정 완료** (스킬 상한 소진 → Maxi 확정으로 plan 진행)

| 라운드 | 발견 | 성격 |
|---|---|---|
| 1 | 🛑 §2.1 인과 역전 · `actionTriggers` 값 미규정 | **핵심 로직** |
| 2 | 🛑 개정이 넣은 fail-open 가드(`fixVersionsMode` undefined) · §8.6 6건 누락 · §8.1 fail-closed 오기 · EC13 자기모순 | **개정이 만든 구멍** |
| 3 | 🛑 FR-9 테스트 설계 2종 vacuous · S6 MockK 배치 vacuous · §8.6 8건 또 누락 | **검증 장치 자체가 vacuous** |

**★ 이 스펙에서 얻은 교훈 (impl/codereview로 인계).**
1. **3라운드 모두 "직전 개정이 새로 넣은 결함"을 잡았다.** 방어를 추가하는 행위 자체가 새 사각지대를 만든다.
2. **내가 phantom을 2건 만들었다** — `TriggerType.TRANSITION`(실제 5종에 없음) · `actionsToRequest`(실명
   `serializeActionsFormState`). 둘 다 learnings에 이미 있는 함정(phantom 엔티티 · 환각 API)이다.
   → **구현자는 스펙의 이름·라인을 그대로 믿지 말고 착수 시 grep으로 재확인한다.**
3. **컴파일러가 강제하는 건 분기의 존재지 값의 정합성이 아니다** — §8.2가 12지점의 값을 명시하는 이유.
4. **§8.6의 완전성은 보증되지 않는다** — grep이 열거형 KDoc을 원리적으로 못 잡는다(3연속 누락).
   §8.2가 손대는 모든 함수의 KDoc을 직접 읽어야 한다.

**핵심 설계는 3라운드 내내 불변** — ADR D1~D6은 한 번도 흔들리지 않았다. 지적은 전부 그 위의 서술/검증 설계였다.

## Plan

> **작성 방식.** `superpowers:writing-plans`(범용 스캐폴드) 대신 직접 작성. 사유 — 이 작업의 task 경계는
> **취향이 아니라 컴파일 제약이 결정**하며(아래 §분해 원칙), 그 제약은 도메인·스펙 단계의 전수 대조로 이미 확정됐다.
> 스킬 형식(메타 블록 `agent`/`files`/`depends-on` + RED/GREEN/REFACTOR + 검증)은 그대로 준수한다.

### ★ 분해 원칙 — 왜 task가 이렇게 크게 묶이는가

**포트에 추상 메서드를 추가하는 순간 구현체 4곳이 동시에 컴파일이 깨진다**(ADR D5 fail-closed = default 금지의 대가).
`Action` sealed class에 서브타입을 추가하면 exhaustive `when` 12지점이 동시에 깨진다.
→ **이들은 TDD 단위로 쪼갤 수 없다.** 쪼개면 중간 커밋에서 저장소가 컴파일되지 않는다(ADR D4).

그래서 T1·T3·T8은 "여러 파일 = 한 원자 단위"다. **task 안에서는 RED→GREEN→REFACTOR를 지키되, 원자성이
컴파일에 의해 강제되는 지점은 한 task로 묶는다.**

### ★ 모든 implementer에게 인계 (prompt 필수 포함)

1. **스펙의 이름·라인을 그대로 믿지 말고 착수 시 grep으로 재확인한다.** 이 스펙은 **phantom을 2건 만들었다가
   정정했다** — `TriggerType.TRANSITION`(실재 5종에 없음)·`actionsToRequest`(실명 `serializeActionsFormState`).
   learnings의 *phantom 엔티티*·*환각 API* 함정에 스펙 저자가 그대로 빠졌다. 라인 번호는 ±2 오차가 있을 수 있다.
2. **`git stash` 금지** — 타 세션의 휴면 stash를 오작동 pop할 수 있다.
3. **`ktlintFormat` 모듈 전체 실행 금지** — 무관 파일 70여 개를 재포맷하고 Gradle 캐시를 오염시킨다. 본인 파일만 수동 수정.
4. **검증은 태스크를 따로 invoke하고 `build/test-results/test/TEST-*.xml`로 실행 테스트 수를 실측**한다.
   묶음 태스크 + `--rerun-tasks`가 6/56클래스만 돌고 BUILD SUCCESSFUL을 낸 전례가 있다.
   쉘 종료 코드는 `set -o pipefail` + `$?`(zsh `${PIPESTATUS[0]}`는 항상 빈 문자열).
5. **본인 files 밖 수정 금지.** 자동 도구가 남의 파일을 건드렸으면 `git checkout`으로 되돌리고 보고.

---

### Task 1. 포트 `setFixVersions` + 커맨드 + prod 어댑터 + 구현체 3곳 (원자)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueMutationPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueMutationCommands.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueMutationPortContractTest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/automation/AutomationIssueMutationAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/automation/AutomationIssueMutationAdapterTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/StubIssueMutationPort.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/StubIssueMutationPort.kt`]
- depends-on: []

**RED**.
- 파일. `AutomationIssueMutationAdapterTest.kt`
- 테스트. `setFixVersions 는 changeFixVersions 에 위임하고 재조회한 version 을 expectedVersion 으로 채운다`
  — mockk로 `issueApplicationService.findByKey(actor, key)` → `version=3` 스텁, `changeFixVersions(actor, key, AppChangeVersionsRequest(ids, 3))` 호출을 `verify`
- 추가. `dryRun=true 면 applied=false·version=null 이고 setRollbackOnly 가 호출된다`
- 실패 (예상). **컴파일 실패** — `IssueMutationPort`에 `setFixVersions` 없음. (포트 추가는 구현체 4곳을 동시에
  깨므로 컴파일 실패가 이 task의 RED다.)

**GREEN**.
1. `IssueMutationCommands.kt` — `SetFixVersionsCommand(actorUserId, issueKey, versionIds: List<UUID>, dryRun)`.
   **`expectedVersion` 없음**(ADR D2). KDoc에 전체교체·빈목록=전체해제 명시
2. `IssueMutationPort.kt` — `fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult`. **default 없음**
3. `AutomationIssueMutationAdapter.kt` — `setField`/`assign`과 **동형**.
   ```kotlin
   override fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult {
       val actor = ActorId(cmd.actorUserId); val key = IssueKey(cmd.issueKey)
       val version = runWithOccRetry(key, cmd.dryRun) {
           val expectedVersion = issueApplicationService.findByKey(actor, key).version
           issueApplicationService.changeFixVersions(actor, key, AppChangeVersionsRequest(cmd.versionIds, expectedVersion)).version
       }
       return toResult(key.value, cmd.dryRun, version)
   }
   ```
4. `automation/StubIssueMutationPort.kt` — **fail-safe**(KDoc `:23-26`). 기본 성공 + command 기록 + `failNextCallsWith` 반영
5. `slack/StubIssueMutationPort.kt` — **fail-closed**(KDoc `:27`). 미시드 호출 시 `IllegalStateException`.
   기존 `setField`(slack 미사용인데 계약상 구현) 패턴 그대로
6. `IssueMutationPortContractTest.kt` — 익명 객체에 `setFixVersions` 추가

> **★ 두 스텁은 패턴이 정반대다.** 뭉뚱그려 둘 다 fail-closed로 만들면 **automation 슬라이스 테스트가 깨진다**.

**REFACTOR**.
- §8.6 중 이 task 소관 KDoc 동기화 — `IssueMutationPort.kt:5,28`(3 메서드→4, 열거에 "수정 예정 버전 설정" 추가) ·
  `IssueMutationCommands.kt:84` · `IssueMutationPortContractTest.kt:14,24,25`(테스트명 포함) ·
  `slack/StubIssueMutationPort.kt:23`("세 쌍"→"네 쌍")·`:28` · `AutomationIssueMutationAdapter.kt:29`

**검증**.
```bash
set -o pipefail
./gradlew :modules:shared-kernel:test :modules:issue-tracking:test --tests '*AutomationIssueMutationAdapterTest*' --tests '*IssueMutationPortContractTest*'; echo "EXIT=$?"
./gradlew :modules:automation:compileTestKotlin :modules:slack-integration:compileTestKotlin; echo "EXIT=$?"   # 스텁 2곳 컴파일 회복 확인
```

---

### Task 2. V306 — `action_type` CHECK 5종 + 컬럼 코멘트 재발행

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V306__automation_actions_set_fix_versions.sql`, `backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: [1]

> **★ depends-on [1]은 "커밋 순서"가 아니라 실제 컴파일 의존이다** (리뷰 지적 반영).
> T1이 `IssueMutationPort`에 추상 메서드를 추가하는 순간 **`:modules:automation` 테스트 소스셋 전체가**
> `StubIssueMutationPort` 미구현으로 깨진다. 이 task의 검증 명령
> (`./gradlew :modules:automation:test --tests '*SchemaMigrationTest*'`)은 그 소스셋을 컴파일하므로,
> T1과 같은 wave에 뜨면 **자기와 무관한 컴파일 에러를 받고 BLOCKED**된다.
> bts-impl의 wave 엣지는 `depends-on` + `files 교집합`뿐이고 **Gradle 모듈 개념이 없다**(`bts-impl/SKILL.md:39-41`)
> — 산문 주석은 파서가 읽지 않으므로 `depends-on`으로 못박아야 효력이 있다([[bts-plan-wave-gradle-module-compile]]).

**RED**.
- 파일. `SchemaMigrationTest.kt`
- 기존 `:654` `V302 유효한 action_type 4종은 INSERT 허용`을 **5종으로 갱신**(테스트명 포함 — 방치하면 거짓 이름).
  `SET_FIX_VERSIONS` INSERT를 목록에 추가
- 추가. `action_type CHECK 는 미지의 값을 거부한다` (음성 가드 — `'BOGUS'` INSERT → 제약 위반)
- 실패 (예상). `SET_FIX_VERSIONS` INSERT가 `ck_automation_actions_action_type` 위반으로 거부

**GREEN**. `V306__automation_actions_set_fix_versions.sql`
```sql
ALTER TABLE automation_actions DROP CONSTRAINT ck_automation_actions_action_type;
ALTER TABLE automation_actions ADD CONSTRAINT ck_automation_actions_action_type
    CHECK (action_type IN ('SET_FIELD','ASSIGN','ADD_COMMENT','CALL_WEBHOOK','SET_FIX_VERSIONS'));
COMMENT ON COLUMN automation_actions.action_type IS
    '액션 종류 — CHECK 5종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS)';
```

> **★ V302 편집 절대 금지**(체크섬 드리프트 — `:modules:app:test`는 5433 영속 DB).
> **★ `COMMENT ON COLUMN` 재발행 필수** — `V302:45`가 "4종"으로 박아둔 **살아있는 DB 객체**다. 빠뜨리면 운영 DB에 drift 영구 잔존.
> **★ 착수 시 V번호 재확인**(`ls backend/modules/automation/src/main/resources/db/migration/automation/`) — 현재 최신 V305.

**REFACTOR**. `SchemaMigrationTest.kt:51,651` 주석 "4종"→"5종"

**검증**. `./gradlew :modules:automation:test --tests '*SchemaMigrationTest*'; echo "EXIT=$?"`

---

### Task 3. `ActionType.SET_FIX_VERSIONS` + `Action.SetFixVersionsAction` + **12지점 전수** (원자)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/ActionType.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/Action.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationActionRepository.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/AutomationRuleResponses.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/AutomationRuleRequests.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleConflictAnalyzer.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/ActionExecutor.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/gitops/AutomationYamlCodec.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/gitops/AutomationRulesYaml.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/ActionTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/AutomationActionRepositoryTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutorTest.kt`]
- depends-on: [1, 2]

> **★ files 추가 5건** (리뷰 지적 반영). 2회차 plan은 REFACTOR가 지시한 `AutomationRuleRequests.kt` ·
> `AutomationRuleService.kt` · `AutomationRulesYaml.kt`를 files에서 빠뜨려, implementer가 **"REFACTOR 포기(drift 잔존)"
> 아니면 "선언 외 파일 수정 → BLOCKED"** 둘 중 하나를 강요받는 구조였다. `AutomationActionRepositoryTest.kt` ·
> `ActionExecutorTest.kt`는 **§8.6 무주공산**(어느 task의 files에도 없던 drift 대상)이자 아래 RED의 poison 왕복 테스트 자리다.

> depends-on 사유. **[1]** `ActionExecutor.dispatchAction`이 `issueMutationPort.setFixVersions`를 호출한다(코드 의존).
> **[2]** `AutomationActionRepository` round-trip 테스트가 DB에 `SET_FIX_VERSIONS`를 INSERT하므로 V306 CHECK가 먼저 필요하다.

**RED**.
- 파일. `ActionTest.kt`
- `ActionType.entries.size shouldBe 5` (`:18`) + `entries.toSet()`에 `SET_FIX_VERSIONS` 추가 (`:22-28`)
- `fromJson 은 SET_FIX_VERSIONS config 의 versionIds 를 파싱한다` + `versionIds 키가 없으면 ActionConfigInvalidException` (EC9)
  + `UUID 형식이 아니면 실패` (EC10) + `빈 배열은 허용한다` (EC1)
- **★ `AutomationActionRepositoryTest.kt` — DB 왕복 테스트 (poison 방어)**.
  `SET_FIX_VERSIONS 액션은 replaceForRule → findByRuleId 왕복에서 versionIds 가 보존된다` + **빈 배열 왕복**도.
  > 2회차 plan은 이 테스트에 **소관 task가 없었다**. 스펙 §8.2가 *"이 파일에 ArrayNode 선례가 없다 … 어긋나면
  > 그 룰의 **모든** 액션이 로드 불가(poison)"* 로 **가장 위험하다고 지목한 `actionConfigJson` ↔ `fromJson` DB 왕복이
  > 커버리지 0**이었다(T7의 YAML 왕복은 `AutomationYamlCodec` — 다른 경로라 대체 불가).
- 실패 (예상). `ActionType.SET_FIX_VERSIONS` 없음 → 컴파일 실패

**GREEN**. **★ 스펙 §8.2 표의 값을 그대로 옮긴다. 컴파일러는 분기의 존재만 강제하고 값은 안 본다.**

| 파일 | 함수 | 값 |
|---|---|---|
| `ActionType.kt` | enum | `SET_FIX_VERSIONS` 추가 |
| `Action.kt` | sealed subclass | `data class SetFixVersionsAction(val versionIds: List<UUID>) : Action()` |
| `Action.kt:86-91` | `fromJson` | `parseSetFixVersions(node)` — `versionIds` **필수 키**, 배열, 원소 UUID |
| `RuleConflictAnalyzer.kt:103-106` | `actionTriggers` | **`false`** ★ |
| `RuleConflictAnalyzer.kt:317-319` | `hasObservableSideEffect` | **`\|\| it is Action.SetFixVersionsAction` 추가** ★ boolean 체인 — **컴파일러 미강제** |
| `RuleConflictAnalyzer.kt:414-417` | `requiredPermission` | `IssuePermission.UPDATE` |
| `RuleConflictAnalyzer.kt:426-429` | `actionKindLabel` | `"수정 예정 버전 설정"` |
| `ActionExecutor.kt:199-213` | `dispatchAction` | `issueMutationPort.setFixVersions(SetFixVersionsCommand(...))` |
| `ActionExecutor.kt:289-292` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` |
| `AutomationActionRepository.kt:113-116` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` |
| `AutomationActionRepository.kt:130-152` | `actionConfigJson` | `node.putArray("versionIds")` + 각 UUID `.toString()`으로 `add` ★ **이 파일에 ArrayNode 선례 없음**. `fromJson`의 **정확한 역함수**여야 한다 — 어긋나면 그 룰의 **모든** 액션이 로드 불가(poison, KDoc `:122-124`) |
| `AutomationRuleResponses.kt:190-193` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` |
| `AutomationRuleResponses.kt:197-209` | `actionConfigOf` | `mapOf("versionIds" to action.versionIds.map(UUID::toString))` |
| `AutomationYamlCodec.kt:245-248` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` |
| `AutomationYamlCodec.kt:256-272` | `actionConfigMap` | `mapOf("versionIds" to ...)` |

> **★ `actionTriggers = false`의 근거를 반드시 코드 주석/KDoc에 남긴다.** `changeFixVersions`(`IssueApplicationService.kt:944-969`)에
> **`eventPublisher.publish`가 없어**(대조군 `updateIssue:544`엔 있음) ISSUE_UPDATED를 유발할 경로가 런타임에
> 존재하지 않는다. **`triggersIssueUpdated(target, "fixVersions")`를 쓰면 안 된다** — `RuleConflictAnalyzer.kt:88-97`
> KDoc이 성문화한 *phantom edge* 사고(AssignAction에서 이미 겪음)의 재발이다.

**REFACTOR**.
- §8.6 이 task 소관 — `ActionType.kt:1`·`:11-14`(불릿 5번째) · `AutomationRuleRequests.kt:75` ·
  `AutomationRuleService.kt:646` · `AutomationRulesYaml.kt:73` · `RuleConflictAnalyzer.kt:85`
- **★ grep이 못 잡는 열거형 KDoc** — `RuleConflictAnalyzer.kt:315`(*"부수효과 액션(SET_FIELD/ASSIGN/ADD_COMMENT)"*) ·
  `:328-330`(권한 매핑 열거). **본인이 값을 바꾼 함수의 KDoc은 직접 읽고 갱신한다.**

**검증**.
```bash
set -o pipefail
./gradlew :modules:automation:test --tests '*ActionTest*'; echo "EXIT=$?"
./gradlew :modules:automation:compileKotlin; echo "EXIT=$?"   # 12지점 전부 뚫렸는지
```

---

### Task 4. FR-9 백엔드 회귀 2종 — 컴파일러가 못 잡는 지점

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/application/RuleConflictAnalyzerFieldPriorityTest.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleConflictAnalyzer.kt`]
- depends-on: [3]

> **★ `RuleConflictAnalyzer.kt`가 files에 있는 이유 = vacuous 검증(아래 §8.7 절차)** (리뷰 지적 반영).
> 2회차 plan은 이 파일을 T4 files에서 빼놓고 *"일부러 위반을 넣어 fail 확인"* 을 시켰다 → implementer의 선택지가
> **① 선언 외 파일 수정 → BLOCKED / ② 검증 생략 → BLOCKED** 둘뿐인 deadlock이었다.
> 최악은 인센티브다 — **mutation은 되돌리므로 최종 diff에 안 남아, 규칙을 지키는 implementer만 BLOCKED되고
> 무시하는 쪽이 통과한다.** T3와 files 교집합이 생겨 자동 직렬화되므로(`depends-on: [3]`과 동일 효과) 안전하다.
> **최종 diff에 `RuleConflictAnalyzer.kt` 변경이 남으면 안 된다** — mutation은 반드시 되돌린다.

> **★ 이 task가 이 PR에서 가장 중요하다.** T3의 12지점 중 `hasObservableSideEffect`(boolean 체인)와
> `actionTriggers`(값)는 **틀려도 컴파일·기존 테스트가 전부 통과**한다. 이 테스트가 유일한 방어선이다.
> **설계가 2번 vacuous로 판명나 3번째에 확정됐다 — 아래 형태를 그대로 따른다.**

**RED**.

**(a) `actionTriggers` 회귀 — 반드시 self-loop 형태.**
- 테스트. `SET_FIX_VERSIONS 액션은 ISSUE_UPDATED 를 유발하지 않는다 (self-loop CYCLE 미검출)`
- 룰 **1개**. `triggerType = ISSUE_UPDATED` + `triggerConfig.fields ⊇ ["fixVersions"]` + 액션 `SetFixVersionsAction`
- 단언. `analyze()` 결과에 **CYCLE 없음**
- ✗ **금지 설계**. *"SET_FIX_VERSIONS 룰 + ISSUE_UPDATED 룰 2개"* → `CycleDetector`는 back-edge DFS라
  **A→B 엣지 1개는 사이클이 아니다** → 버그를 넣어도 통과(vacuous)
- ✅ self-loop면 잘못된 구현(`triggersIssueUpdated(target,"fixVersions")`)이 **A→A 자기 엣지 → CYCLE 검출 → FAIL**.
  `edgesFrom:76-80`이 `rules`에 자기 자신을 포함하고 KDoc `:28`이 *"self-loop(A → A)도 유효한 사이클"* 이라 명시

**(b) `hasObservableSideEffect` 회귀 — `SetFixVersionsAction`만 가진 룰 2개.**
- 테스트. `SET_FIX_VERSIONS 만 가진 두 룰은 PRIORITY_AMBIGUITY 로 검출된다`
- 룰 **2개**. 같은 **non-WEBHOOK** `triggerType`, 각각 **`SetFixVersionsAction` _만_ 보유**(다른 액션 0개)
- 단언. `analyze()` 결과에 **PRIORITY_AMBIGUITY 있음**
- ✗ **금지 설계**. *"같은 필드를 노리는 동순위 룰 2개"* → ① **"동순위"는 존재하지 않는 개념**(`AutomationRule`에
  `priority` 필드 없음. 실제 게이트는 `coFire:230-239` = 같은 triggerType + WEBHOOK 아님) ② *"같은 필드"* 면
  FIELD_CONFLICT가 먼저 잡혀 `priorityAmbiguity:304`의 `pairIds !in conflictedPairs`가 false → **억제됨**
  ③ `SetFieldAction`이 하나라도 있으면 `any{}`가 **먼저 true를 반환해 새 분기를 안 태운다**(vacuous)

**GREEN**. T3에서 이미 구현됨 — 이 task는 **회귀 가드만** 추가한다.

**★ vacuous 검증 (§8.7 — 필수)**. 두 테스트 각각에 대해 **일부러 위반을 넣어 FAIL을 눈으로 확인**한 뒤 되돌린다.
- (a) `actionTriggers`의 `SetFixVersionsAction -> false`를 `triggersIssueUpdated(target, "fixVersions")`로 바꿔 → **FAIL 확인** → 되돌림
- (b) `hasObservableSideEffect`에서 `|| it is Action.SetFixVersionsAction`를 제거 → **FAIL 확인** → 되돌림
- **확인 못 하면 BLOCKED 보고.** "통과했다"가 "검증했다"를 의미하지 않는다.

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictAnalyzer*'; echo "EXIT=$?"`

---

### Task 5. S2·S3·S4·S5 automation 통합 테스트 (실 DB)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/ActionExecutionEndToEndIntegrationTest.kt`]
- depends-on: [3]

**RED**.
- S2. 룰 실행 → Fix Version 설정 + `rule_executions` **SUCCESS**
  - **★ 트리거는 `ISSUE_UPDATED` + `fields=["status"]` + 조건.** `TriggerType.TRANSITION`은 **존재하지 않는다**(실재 5종 = `ISSUE_CREATED`/`ISSUE_UPDATED`/`ISSUE_COMMENTED`/`SCHEDULED`/`WEBHOOK`)
- S3. 전체교체 — `[1.0.0]` → `[1.2.0]` (추가 아님). EC14 인지(무변경 재실행도 version bump)
- S4. `versionIds=[]` → 전체 해제
- S5. 권한 없는 actor → FAILED(**권한 거부로 분류**됨 — `classifyPortFailure:258`이 `IssueMutationPermissionDeniedException`만 타입 분류)
- 실패 (예상). 액션 미배선

**GREEN**. T1·T3에서 구현됨 — 통합 경로 배선만.

**REFACTOR**. §8.6 — `ActionExecutionEndToEndIntegrationTest.kt:54,226,229` "4종"→"5종"

**검증**. `./gradlew :modules:automation:test --tests '*ActionExecutionEndToEnd*'; echo "EXIT=$?"`

---

### Task 6. S6 양성 단언 — 타 프로젝트 버전 (issue-tracking 실 DB)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueVersionLinksIntegrationTest.kt`]
- depends-on: [1]

> **★ 왜 issue-tracking인가.** `IssueLinkedVersionNotFoundException`은 issue-tracking BC 타입이라
> **automation 테스트에서는 Gradle 클래스패스상 참조 자체가 불가능**하다(`automation/build.gradle.kts:39`가
> `:modules:shared-kernel`만 의존). *ArchUnit이 막는 게 아니다* — `AutomationBcArchTest.kt:31`은
> `DoNotIncludeTests()`로 테스트를 제외하므로 `testImplementation` 추가가 통과해버린다.
>
> **★ 왜 `AutomationIssueMutationAdapterTest`가 아닌가.** 그건 MockK 단위 테스트(`:56`)라
> `every { ... } throws ...`로 **자기가 심은 스텁을 자기가 단언**하게 되고 `validateVersions`가 한 줄도 안 돈다 —
> §8.3이 비판한 vacuity에 그대로 걸린다.

**RED**.
- 테스트. `타 프로젝트 버전을 fixVersions 로 지정하면 그 versionId 를 담은 예외로 거부된다`
- 기존 선례(`:379,412`) 형태. 실 DB에 프로젝트 2개 + 각자 버전 시드
- **양성 단언**. `shouldThrow<IssueLinkedVersionNotFoundException> { ... }.versionId shouldBe betaVersionId`
  (`IssueExceptions.kt:130` — `class IssueLinkedVersionNotFoundException(val versionId: UUID)`, **public val**)
- **추가 단언**. 이슈의 `fixVersionIds`가 **변경되지 않았음**을 DB에서 확인
- ✗ *"rule_executions에 FAILED로 기록된다"* 만 단언하면 **vacuous** — `classifyPortFailure`가 EC3·EC4·EC6·EC7을
  전부 `"FAILED"` 한 문자열로 수렴시키므로(`:318`), **`validateVersions`를 통째로 지워도 통과한다**

**GREEN**. 기존 `validateVersions`가 이미 처리 — 회귀 가드만.

**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueVersionLinksIntegrationTest*'; echo "EXIT=$?"`

---

### Task 7. S7 YAML GitOps 왕복

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationGitOpsRoundTripTest.kt`]
- depends-on: [3]

**RED**. `SET_FIX_VERSIONS 액션은 export→import 왕복에서 versionIds 가 보존된다`
**GREEN**. T3의 `AutomationYamlCodec` 갱신으로 통과
**검증**. `./gradlew :modules:automation:test --tests '*AutomationGitOpsRoundTrip*'; echo "EXIT=$?"`

---

### Task 8. 프론트 계약 — z.enum + FormState + parse/serialize + 라벨맵 (원자)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.types.ts`, `apps/web/src/api/automation-rules.types.test.ts`, `apps/web/src/components/automation/AutomationRuleList.tsx`, `apps/web/src/components/automation/ActionConfigEditor.tsx`]
- depends-on: []

> **원자 사유.** `actionTypeSchema` z.enum에 값을 넣는 순간 `Record<ActionType,string>` 라벨맵 2곳(TS2741)과
> `default:` 없는 switch 2곳(TS2366)이 **동시에 컴파일이 깨진다**. 백엔드와 무관(계약 미러)이라 depends-on 없음.
>
> **★ `ActionConfigEditor.tsx`가 files에 있는 이유** (리뷰 지적 반영). 2회차 plan은 *"라벨맵 **2곳**이 동시에 깨진다"* 고
> 써놓고 files엔 `AutomationRuleList.tsx` 1곳만 넣었다 — 나머지 `ACTION_TYPE_LABELS`(`ActionConfigEditor.tsx:48-53`)가
> 선언 밖이라 **T8은 자기 검증(`pnpm typecheck`)을 구조적으로 통과할 수 없었다**(T9는 다음 wave).
> **이 task는 그 파일에서 `ACTION_TYPE_LABELS`만 건드린다** — 위젯/렌더는 T9 소관.
> T9와 files 교집합이 생겨 자동 직렬화된다(T9의 `depends-on: [8]`과 동일 효과).

**RED**.
- `automation-rules.types.test.ts`
- `:273` `validTypes` 배열에 `'SET_FIX_VERSIONS'` 추가 ★ **컴파일러 미강제 — 빠뜨리면 새 타입이 조용히 미검증**
- `parseActionConfig 는 versionIds 를 파싱하고 빈 배열이면 clear 모드로 복원한다`
- `serializeActionConfig 는 clear 모드면 versionIds:[] 를, 그 외엔 선택 목록을 낸다`
- **`fixVersionsMode 가 undefined 면 replace 로 취급한다`** ★ fail-closed
- 실패 (예상). `SET_FIX_VERSIONS`가 `actionTypeSchema`에 없음

**GREEN**.
1. `actionTypeSchema` z.enum에 `'SET_FIX_VERSIONS'`
2. `ActionConfigFormState`에 `versionIds?: string[]` + **`fixVersionsMode?: 'replace' | 'clear'`(UI 전용, 직렬화 안 됨)**
3. `parseActionConfig` — 기존 관례(`typeof` 가드)대로 **배열 여부 + 원소 string 여부** 검사.
   `versionIds` 있고 비었으면 `clear`, 있으면 `replace`로 모드 복원
4. `serializeActionConfig` — **`undefined ≡ replace` 방향**
   ```ts
   case 'SET_FIX_VERSIONS':
     return config.fixVersionsMode === 'clear'
       ? JSON.stringify({ versionIds: [] })
       : JSON.stringify({ versionIds: config.versionIds ?? [] })
   ```
5. `AutomationRuleList.tsx:62-67` `actionTypeLabels` — `SET_FIX_VERSIONS: '수정 예정 버전'`(배지용, 짧게)

**REFACTOR**. §8.6 — `automation-rules.types.ts:22` · **`:287`**(`ActionConfigFormState` KDoc "4종"→"5종" +
**`fixVersionsMode`가 와이어 대응 없는 최초 필드임을 명시** — 안 적으면 다음 사람이 config에 실어 보낸다) ·
`AutomationRuleList.tsx:61` · `automation-rules.types.test.ts:268,272`
**★ 오탐 주의**. `automation-rules.types.ts:40`의 "4종"은 **`ConflictType`** — 무관, 건드리지 말 것.

**검증**. `pnpm vitest run src/api/automation-rules.types.test.ts; echo "EXIT=$?"` + `pnpm typecheck`(tsconfig.app.json)

---

### Task 9. 프론트 설정 UI — `SetFixVersionsFields` + 모드 + S8 저장 거부

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/ActionConfigEditor.tsx`, `apps/web/src/components/automation/ActionConfigEditor.test.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`]
- depends-on: [8]

> **★ files 추가 1건 — `AutomationRuleFormDialog.test.tsx`** (1회차 dispatch 후 controller가 mutation 실증으로 발견).
> B3/B5와 **같은 계열의 4번째 결함**이었다. 1회차 plan은 S8을 *"저장이 거부된다"* 로 규정해놓고 files엔
> **저장 경로를 렌더할 테스트 파일을 안 넣었다** → implementer는 `validateActions`를 순수 함수로 export해
> `ActionConfigEditor.test.tsx`에서 단위 검증하는 우회를 택할 수밖에 없었다(합리적 선택, 파일범위 준수).
> **결과는 vacuous였다** — controller가 `onValid`의 `if (validationErrors.length > 0) return` 을 지우고 돌리니
> `src/components/automation` **197/197 전부 통과**(mutation은 되돌림). 즉 가드의 *존재*는 고정됐지만
> *저장 경로와의 연결*은 아무도 안 잡아, 그 한 줄을 지우면 plan이 load-bearing이라 못박은 B1
> (빈 배열이 `{"versionIds":[]}`로 나가 **기존 Fix Version 전체 삭제**)이 조용히 부활한다.

**RED**. `ActionConfigEditor.test.tsx`
- `SET_FIX_VERSIONS 를 고르면 교체 모드가 기본 선택되고 버전 목록이 뜬다` (S1)
- **`교체 모드 + 빈 목록이면 저장이 거부된다`** (S8) ★
- **★ 2회차 추가 — `AutomationRuleFormDialog.test.tsx` wiring 테스트(위 vacuous 해소)**.
  다이얼로그를 실제로 렌더해 `SET_FIX_VERSIONS`+`replace`+빈 목록 상태로 **제출**하고,
  **서버 mutation이 호출되지 않음**(`expect(mutateSpy).not.toHaveBeenCalled()`)과 에러 문구 노출을 단언.
  대조군으로 **유효 입력이면 mutation이 호출됨**도 함께(양성 단언 — 없으면 "항상 호출 안 됨"으로도 통과).
  ✗ `validateActions` 순수 함수 단위 테스트만으로는 **불충분**(연결을 안 잡음). 순수 함수 테스트는 유지하되 **추가**한다.
  ★ **vacuous 검증 필수** — 작성 후 `onValid`의 `if (validationErrors.length > 0) return` 을 일부러 지워
  **FAIL 확인 → 되돌린다**. 확인 못 하면 BLOCKED 보고. 최종 diff에 그 mutation이 남으면 안 된다.
- `전체 해제 모드면 버전 목록이 숨겨진다`
- `useVersions 로딩/에러면 disabled shell + 문구가 뜨고, 저장은 막지 않는다` (EC13)
- `replace→clear→replace 왕복에도 선택이 보존된다`

**GREEN**.
1. `defaultConfigForType`에 **`SET_FIX_VERSIONS` 명시 분기** — `{ fixVersionsMode: 'replace', versionIds: [] }`
   ★ `parseActionConfig(type, {})`로 떨어뜨리면 `versionIds` 부재 → **`clear` 모드로 오판**된다
   (`SET_FIELD`가 이미 같은 이유로 명시 분기 — KDoc `:117` *"select가 유효한 초기값을 갖도록"*)
2. ~~`ACTION_TYPE_LABELS`~~ → **T8이 이미 처리**(T8 files로 이관 — T8이 typecheck를 통과하려면 필수였음). 이 task는 건드리지 않는다
3. `SetFixVersionsFields` 신규 — 모드 라디오 2개 + `useVersions(projectKey)` + `VersionMultiSelect variant="fix"`
   - **`VersionMultiSelect` 수정 금지**(제약 5) — `variant="fix"`가 이미 "수정 버전" 문구 제공. 고치면 `IssueMetaPanel.tsx:346-356` 회귀
   - 로딩/에러 = `ProjectMemberSelect.tsx:17-18,109` 선례(disabled shell + `TEXT.loading`/`TEXT.error`). **새 패턴 발명 금지**
   - `clear` 모드 → `VersionMultiSelect` **렌더 안 함**(disabled로 두면 체크한 게 조용히 버려짐). `versionIds`는 폼 상태에 보존
4. 조건부 렌더 블록(`:586-599`)에 `{value.type === 'SET_FIX_VERSIONS' && <SetFixVersionsFields ... />}`
5. **S8 저장 거부 기제 신설** — `AutomationRuleFormDialog`
   - ★ **`actionsToRequest`는 존재하지 않는 함수다**(스펙이 지어낸 이름). 실명 **`serializeActionsFormState`**(`:197-198`)
   - 그 함수는 `(actions) => ActionRequestInput[]` **순수 매핑이라 에러 채널이 없다**. RHF `errors`(`:445,533,567`)도
     `actions`를 안 덮는다(`actions`는 RHF 스키마 밖 별도 state, `:579`)
   - → 제출 직전 `validateActions(actions): {index, message}[]` + actions용 에러 state. **위반 행 index를 특정**해 문구
   - ★ 이 가드는 **load-bearing**이다 — 없으면 `undefined`/`[]`가 `{"versionIds":[]}`로 나가 전체 해제(B1 재현)

**REFACTOR**. §8.6 — `ActionConfigEditor.tsx:47,540` · `ActionConfigEditor.test.tsx:41` ·
`AutomationRuleFormDialog.tsx:2,576` "4종"→"5종"

**검증**. `pnpm vitest run src/components/automation; echo "EXIT=$?"` + `pnpm typecheck` + `pnpm lint`

---

### Task 10. 전수 동기화 스윕 + 문서 + 최종 회귀

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/automation.md`]
- depends-on: [4, 5, 6, 7, 9]

> **★ plan 파일을 files에서 제거** (리뷰 지적 반영). controller가 같은 파일에 task 체크박스를 쓰므로
> (`bts-impl/SKILL.md:195`) 동시 write 충돌이 난다.
> **★ 코드 파일 스윕 권한 문제 해소.** 2회차 plan의 T10은 files가 문서 2개뿐이라 *"무주공산이면 여기서 처리"* 를
> 자처하고도 **코드를 한 줄도 못 고치는** 구조였다. 무주공산 2건(`ActionExecutorTest.kt`·`AutomationActionRepositoryTest.kt`)은
> **T3 files로 이관 완료** → 이 task는 이제 **발견·보고만** 하고, 코드 수정이 필요하면 **소관 task 재dispatch를 controller에 요청**한다.

**작업**.
1. **§8.6 잔여 스윕** — grep **2종**을 돌려 T1·T3·T8·T9가 놓친 게 없는지 확인
   ```bash
   grep -rn "4종\|4개\|네 개\|3 메서드\|세 메서드\|세 쌍" backend/modules apps/web   # 개수형
   grep -rn "SET_FIELD/ASSIGN\|SetFieldAction.*AssignAction\|필드 변경.*담당자" backend/modules apps/web  # 열거형
   ```
   > **★ 이 목록의 완전성은 보증되지 않는다** — grep은 열거형 KDoc을 원리적으로 못 잡는다(스펙 §8.6이 3연속 누락한 이유).
   > 발견 시 **소관 task 파일이면 그 task로 되돌리고**, 무주공산이면 여기서 처리.
2. `docs/plan/product/automation.md` §2.7 — D1~D5·D7 체크박스 `[x]`(D6는 UI가 이 PR에 포함되므로 함께) + 완료 요약 단락
3. **FR 총수 123 불변 확인** — D단계 작업이라 FR 추가 없음
4. `bash scripts/verify-master-plan.sh` 통과
5. **전체 회귀** — 태스크 따로 invoke + `TEST-*.xml` 실행 수 실측
   ```bash
   set -o pipefail
   ./gradlew :modules:shared-kernel:test :modules:issue-tracking:test :modules:automation:test :modules:slack-integration:test; echo "EXIT=$?"
   ./gradlew :modules:automation:ktlintCheck :modules:automation:detekt; echo "EXIT=$?"
   pnpm typecheck && pnpm lint && pnpm test; echo "EXIT=$?"
   ```
6. **prod 조립 재검증** — cross-BC `@Component` 추가는 `:modules:app` 9BC 조립에서만 표면화
   ```bash
   ./gradlew :modules:app:test; echo "EXIT=$?"
   ```
   > ★ `:modules:app:test`는 **5433 영속 DB**를 쓴다 — V306이 처음 적용되는 지점.

**검증**. 위 5·6의 EXIT=0 + `TEST-*.xml` 실행 수가 단독 실행과 일치

---

## Plan 메타

### task 체크박스 (controller가 마킹 — `bts-impl/SKILL.md:195`)

- [x] T1. 포트 `setFixVersions` + 커맨드 + 어댑터 + 구현체 3곳 — PASS (`05c394da5` test → `07619dc58` feat → `915319e80` refactor)
- [x] T2. V306 CHECK 5종 + 컬럼 코멘트 — PASS (`2342f043c` test → `b2194242b` feat → `21ab3edd5` refactor; SchemaMigrationTest 63/63, XML 실측)
- [ ] T3. `ActionType`/`Action` + 12지점 전수
- [ ] T4. FR-9 백엔드 회귀 2종 ★
- [ ] T5. S2~S5 automation 통합
- [x] T6. S6 양성 단언 (issue-tracking 실 DB) — PASS (`8cfd23219` test → `f1f1c3a96` feat[empty, 회귀가드]; IssueVersionLinksIntegrationTest 13/13, 신규 versionId 양성단언 통과)
- [ ] T7. S7 YAML 왕복
- [x] T8. 프론트 계약 — PASS (`d008e49c0` test → `334604095` feat → `6d68999cf` refactor)
- [x] T9. 프론트 설정 UI — PASS (`185a4e1c0` test → `2b3c2d25b` feat → `45162dcd9` refactor → **`c27460980` test(wiring guard, DRIFT 재작업)**)
  - 1회차 DRIFT. S8 가드가 **vacuous**였다 — controller가 `onValid`의 차단 라인을 지우고 돌리니 **197/197 전부 통과**.
    원인은 implementer가 아니라 **plan files 결함**(저장 경로를 렌더할 `AutomationRuleFormDialog.test.tsx` 미포함) → files 확대 후 재dispatch.
  - 2회차 PASS. wiring 테스트(차단 단언 + **대조군 양성 단언**) 추가 후 **같은 mutation에 1 failed | 198 passed (199), EXIT=1** —
    controller가 직접 재실증. 구현파일 diff 0(mutation 잔여 없음) · typecheck/lint EXIT=0 · stash 스택 무오염 확인.
- [ ] T10. 전수 동기화 스윕 + 문서 + 최종 회귀

### wave (bts-impl 알고리즘 실제 결과 — `depends-on` + `files 교집합`만)

> **★ 2회차 plan의 wave 목록은 한 wave도 맞지 않았다** (리뷰 지적). 산문으로 적은 "T2는 T1과 같은 wave 금지"는
> **파서가 읽지 않아 효력이 0**이었다 → T2에 `depends-on: [1]`을 명시해 기계적으로 강제했다. 아래는 정정본이다.

**★ 아래는 손으로 적은 게 아니라 `bts-impl/SKILL.md:38-43` 알고리즘을 그대로 돌린 실측 결과다**
(직전 2회는 손계산이 전부 틀렸다 — 산문 wave 목록을 신뢰하지 말 것).

| wave | task | agent | 근거 |
|---|---|---|---|
| **1** | T1 · T8 | backend · frontend | 둘 다 `depends-on: []`, files 교집합 ∅ (backend vs apps/web) |
| **2** | T2 · T6 · T9 | db · backend · frontend | T2←[1] / **T6←[1]** / T9←[8]. 교집합 ∅. Gradle 모듈도 분리(automation-test / issue-tracking-test / 없음) |
| **3** | T3 | backend | ←[1,2] |
| **4** | T4 · T5 · T7 | backend ×3 | 전부 ←[3]. files 교집합 ∅ |
| **5** | T10 | backend | ←[4,5,6,7,9]. T8은 T9를 통해 **전이적으로 커버** |

**자동 직렬화 엣지 (files 교집합 — 의도한 것)**. `T3 → T4`(`RuleConflictAnalyzer.kt` — vacuous 검증용) ·
`T8 → T9`(`ActionConfigEditor.tsx` — 라벨맵 vs 위젯). 둘 다 이미 `depends-on`으로도 연결돼 있어 무영향.
**cycle 없음.**

- **task 수**. 10
- **TDD 강제**. yes — 단 **T1·T3·T8은 "컴파일 실패 = RED"** (포트/sealed class/z.enum 추가는 구현체를 동시에 깨므로
  테스트만 먼저 커밋하는 형태가 불가능. ADR D4·D5의 구조적 귀결)
- **병렬 dispatch 주의**. **`apps/web` 파일이 있으므로 pre-commit lint-staged race가 발화 가능**하다
  (`.lintstagedrc.json`이 `apps/web/**/*.{ts,tsx,js,jsx}`만 대상 — PR-A 때는 apps/web 0파일이라 구조적으로 불가능했음).
  T8·T9가 다른 task와 같은 wave에 있으면 **자기 파일만 stage**하고, race 발생 시 quiescent 시점에 커밋 분리 복구
- **추가 검증**. ktlint · detekt · typecheck(tsconfig.app.json) · vitest · **`:modules:app:test`(prod 조립)**
- **E2E**. 이 PR 범위 밖 — 기존 automation E2E 25건은 T10에서 회귀 확인만

## 리뷰 결과

### plan-eng-review (2026-07-16) — 적대적 eng 리뷰

**리뷰 구성 결정.** 스킬 규칙은 `feature` + `task≥3` → `/autoplan`(CEO·design·eng·DX 4종)이나 **4종 중 3종이 공허**하다 —
CEO("만들 가치가 있나")는 Maxi가 DEC-11로 확정했고, DX는 공개 API 변경 0, design은 새 화면 0(기존 다이얼로그 확장).
→ **eng 집중 리뷰**([[bts-review-plan-autoplan-overkill]] 선례 동형).

**🛑 BLOCKER 5건 — 전부 수정 완료.**

| # | 지적 | 조치 |
|---|---|---|
| B1 | **프론트 files 경로 2개가 실재하지 않음** — `__tests__/` 디렉토리 없음(이 repo는 colocated). implementer가 엉뚱한 곳에 빈 파일 생성 + **`git log -- <files>` 경로 필터가 빈 결과 → `TDD_VIOLATION` → 무한 재dispatch** | 경로 정정(`api/automation-rules.types.test.ts` · `components/automation/ActionConfigEditor.test.tsx`) |
| B2 | **T3 files가 자기 REFACTOR 대상 3파일 누락** — implementer가 "REFACTOR 포기(drift 잔존)" 아니면 "선언 외 수정 → BLOCKED" 강요 | `AutomationRuleRequests.kt`·`AutomationRuleService.kt`·`AutomationRulesYaml.kt` 추가 |
| B3 | **T8 자기모순** — "라벨맵 2곳이 동시에 깨진다"고 써놓고 files엔 1곳. **T8이 자기 검증(`pnpm typecheck`)을 구조적으로 통과 불가** | `ActionConfigEditor.tsx` 추가(라벨맵만 소관), T9의 중복 지시 제거 |
| B4 | **T2가 wave 1에 T1과 같이 뜸** — wave 엣지는 `depends-on`+`files 교집합`뿐이고 **모듈 개념 없음**(`bts-impl/SKILL.md:39-41`). 산문 주석은 효력 0 → T2가 T1이 깨놓은 automation 테스트 소스셋을 컴파일해 BLOCKED | T2에 **`depends-on: [1]`** 명시(실제 컴파일 의존) |
| B5 | **T4 vacuous 검증이 deadlock** — mutation 대상 `RuleConflictAnalyzer.kt`가 T3 소관. ①선언 외 수정→BLOCKED ②검증 생략→BLOCKED. **최악의 인센티브 — 규칙 지키는 쪽만 BLOCKED되고 무시하는 쪽이 통과**(mutation은 되돌려서 diff에 안 남음) | T4 files에 `RuleConflictAnalyzer.kt` 추가 + "최종 diff에 남으면 안 됨" 명시 |

**⚠️ CONCERN 4건 — 전부 수정 완료.**
- **wave 목록이 알고리즘 결과와 한 wave도 불일치** → 위상정렬 실제 결과로 재작성 + 기계 검증(아래)
- **§8.6 무주공산 2건**(`ActionExecutorTest.kt`·`AutomationActionRepositoryTest.kt`가 어느 files에도 없음) + **T10이 스윕을 자처하면서 코드 파일 수정 권한 0** → 무주공산을 T3 files로 이관, T10은 발견·보고만
- **최고 위험 지점(`actionConfigJson` poison)에 테스트 소관 0** — 스펙이 "가장 위험"이라 지목한 `actionConfigJson`↔`fromJson` **DB 왕복**을 어느 task도 안 덮었음(T7은 YAML이라 다른 경로) → T3 RED에 `AutomationActionRepositoryTest` 왕복 추가
- **task 체크박스 0개** → controller 마킹 대상 추가. T10 files에서 plan 파일 제거(동시 write 충돌)

**✅ 반증된 우려 (기록).**
- **"컴파일 실패 = RED"는 bts-impl과 충돌하지 않는다.** TDD 판정은 **커밋 순서만** 본다(`SKILL.md:166` — `test:` hash가
  `feat:` hash보다 먼저). 빌드 게이트 없음. `.husky/pre-commit`은 `apps/web/**` eslint만이라 Kotlin은 대상 밖.
  → **내가 경고한 위험은 헛다리였고, 진짜 위험(경로 오타)은 못 봤다.**
- **T3은 쪼갤 축이 없다** — `Action.kt:86-91` `fromJson`이 `when (actionType)` exhaustive라 **enum만 추가해도 즉시 깨진다**.
  enum/subclass 분리 불가. "각 task 2-5분" 위반이나 **컴파일 제약이라 정당**.
- wave 4의 T4·T5·T6·T7 files 교집합 ∅ · 백엔드 경로 전부 실재 · 라인 번호 표본 전수 일치 · T10의 T8 누락은 전이적 커버(무해).

**BLOCKER: 없음** (5건 전부 해소).
