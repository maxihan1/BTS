<!-- FR-AT-07 PR-B 스펙 — setFixVersions 포트·SET_FIX_VERSIONS 액션·설정 UI. 마스터 스펙 §B 상세화 -->

# FR-AT-07 PR-B — Fix Version 설정 통로 · 스펙

> 날짜. 2026-07-16 | PR. #276 | 상위. [마스터 스펙 §B](2026-07-15-fr-at-07-pr-merge.md) | ADR. [2026-07-16-fr-at-07-pr-b-fix-version-port](../decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md)
>
> **개정 4회차 (2026-07-16)** — Phase B 적대적 검토 **3라운드**를 거쳐 개정. 라운드마다 BLOCKER가 나왔고
> **3라운드 모두 "직전 개정이 새로 넣은 결함"을 잡았다**. 변경 내역·교훈은 §10.

마스터 스펙 §B는 *"요약만. 착수 시 `/bts`로 별도 plan/spec 상세화"* 라고 명시한다. 이 문서가 그 상세화다.

**office-hours 스킵 사유.** 요구사항이 마스터 스펙 §B(FR-B1~B4) + Maxi 확정(D3/DEC-11) + ADR D1~D6로 이미 확정적이다.
office-hours는 "만들 가치가 있는가"를 묻는 단계이며 이미 답이 나온 FR엔 부적합(선례 기록됨).
**design-shotgun 스킵 사유.** 새 화면이 아니라 기존 `ActionConfigEditor`의 조건부 렌더 블록에 기존
`VersionMultiSelect`를 끼우는 작업 — 디자인 선택지가 사실상 없다(FR-AT-06 D6/D7 선례 동형).

## 0. 이 PR이 푸는 문제

PR-C가 "PR이 머지되면 이슈의 Fix Version을 자동 설정한다"를 완성하려면 **그 설정 통로가 먼저 존재해야 한다.**
현재 automation BC에는 Fix Version을 건드릴 수단이 전혀 없다 — `IssueMutationPort`는 `setField`/`assign`/`addComment`
3개뿐이고, `setField`는 `UpdateIssueRequest`를 만드는 경로(`AutomationIssueMutationAdapter.kt:213-240`)라
**별도 서비스 메서드**인 `changeFixVersions`를 태울 수 없다(§F3).

이 PR은 통로만 만든다. 트리거(PR_MERGED)·Git webhook은 PR-C.

**이 PR만으로도 사용자 가치가 성립한다** — 기존 트리거 5종으로 "이슈가 Done으로 전이되면 Fix Version을 1.2.0으로
설정" 같은 룰을 만들 수 있다.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 룰 편집 화면에서 Fix Version 액션 만들기 (해피 패스)

```
Given 프로젝트 ALPHA에 버전 "1.2.0"(UNRELEASED)이 있고
  And  나는 ALPHA의 자동화 룰을 관리할 수 있다
When  프로젝트 설정 → 자동화 → 룰 추가 → 액션 타입에서 "수정 예정 버전 설정"을 고르면
Then  "선택한 버전으로 교체" 모드가 기본 선택된 채 버전 다중 선택 위젯이 나타난다
When  "1.2.0"을 체크하고 룰을 저장하면
Then  룰이 저장되고, 목록의 그 룰 행에 "수정 예정 버전" 배지가 보인다
```

### S2. 룰이 실제로 Fix Version을 설정한다

> **★ 개정 4회차 — `TriggerType.TRANSITION`은 존재하지 않는다.** 실제 값은 `ISSUE_CREATED` · `ISSUE_UPDATED` ·
> `ISSUE_COMMENTED` · `SCHEDULED` · `WEBHOOK` **5종**뿐이다(`TriggerType.kt:15-21`). 1~3회차 스펙의
> *"트리거 = TRANSITION to Done"* 은 **phantom** — 존재하지 않는 값을 근거로 시나리오를 썼다(learnings
> 2026-05-20 "phantom 엔티티 가설 검증 누락"과 같은 결). "Done으로 전이되면"은 아래처럼 표현한다.

```
Given 룰(트리거 = ISSUE_UPDATED + triggerConfig.fields=["status"],
          조건 = status == "Done",
          액션 = SET_FIX_VERSIONS [1.2.0])이 활성이고
  And  이슈 ALPHA-7의 Fix Version이 비어 있다
When  ALPHA-7의 status가 Done으로 바뀌면
Then  ALPHA-7의 Fix Version이 [1.2.0]이 된다
  And  rule_executions에 SUCCESS 실행 이력이 남는다
  And  이슈 히스토리에 "fixVersions" 변경이 룰 actor 명의로 기록된다
      (`IssueChangeDetector.kt:152-154`가 before/after `fixVersionIds`를 "fixVersions" 키로 비교)
```

### S3. 전체교체 시맨틱 — 기존 값을 덮는다

```
Given 이슈 ALPHA-7의 Fix Version이 [1.0.0]이고
  And  룰의 액션이 SET_FIX_VERSIONS [1.2.0] 이다
When  룰이 실행되면
Then  ALPHA-7의 Fix Version은 [1.2.0] 이다  ← [1.0.0, 1.2.0]이 아니다 (추가가 아니라 교체)
```

> **주의 (EC14).** 이미 `[1.2.0]`인 이슈에 같은 룰이 다시 실행되면 값은 그대로지만 `version`이 bump되고
> `updated_at`이 갱신된다 — S3 테스트가 멱등성을 주장하려면 이 사실을 알아야 한다.

### S4. 명시적 전체 해제

```
Given 이슈 ALPHA-7의 Fix Version이 [1.0.0]이고
  And  룰의 액션이 "수정 예정 버전 전체 해제" 모드로 저장돼 있다 (config = {"versionIds": []})
When  룰이 실행되면
Then  ALPHA-7의 Fix Version이 비워진다
```

> 전체 해제는 **의도된 기능**이다(마스터 스펙 FR-B3). 파괴적이므로 UI가 **명시적 의도 선택**을 요구한다(FR-6).

### S5. 권한 없는 actor — 액션 실패

```
Given 룰의 actor가 ALPHA-7에 대한 UPDATE 권한이 없다
When  룰이 실행되면
Then  Fix Version이 바뀌지 않고
  And  rule_executions에 FAILED(권한 거부)로 기록된다
  And  actor에게 없는 권한이 룰을 통해 우회되지 않는다
```

### S6. 타 프로젝트 버전 지정 — 액션 실패

```
Given 룰의 액션이 다른 프로젝트 BETA의 버전 UUID를 담고 있다
      (UI로는 불가능하나 YAML GitOps import / API 직접 호출로 가능)
When  룰이 실행되면
Then  Fix Version이 바뀌지 않고
  And  실패 원인이 "그 versionId가 이 프로젝트에 없음"임이 관측 가능하다  ← ★ 양성 단언 (§8.3)
```

### S7. YAML GitOps 왕복

```
Given SET_FIX_VERSIONS 액션을 가진 룰이 있다
When  프로젝트 자동화 룰을 YAML로 내보내고 다시 가져오면
Then  액션 타입과 versionIds가 손실 없이 복원된다
```

### S8. 저장 시점 방어 — 빈 목록으로 "교체"는 저장할 수 없다

```
Given 액션 타입 "수정 예정 버전 설정"을 고르고
  And  모드가 "선택한 버전으로 교체"인데 아무 버전도 체크하지 않았다
When  룰 저장을 시도하면
Then  저장이 거부되고 "교체할 버전을 1개 이상 선택하세요" 안내가 뜬다
  And  전체 해제를 원하면 "전체 해제" 모드를 명시적으로 골라야 한다
```

## 2. 기능 요구사항 (FR)

| ID | 요구사항 | 근거 |
|---|---|---|
| **FR-1** (§B FR-B1) | `IssueMutationPort.setFixVersions(SetFixVersionsCommand): MutationResult` — **default 구현 없음** | ADR D1·D5 |
| **FR-2** (§B FR-B2) | `AutomationIssueMutationAdapter`가 `IssueApplicationService.changeFixVersions`에 위임. OCC는 어댑터가 `findByKey().version` 재조회로 채우고 `runWithOccRetry` 1회 재시도 | ADR D2 |
| **FR-3** (§B FR-B3) | `ActionType.SET_FIX_VERSIONS` + `Action.SetFixVersionsAction(versionIds: List<UUID>)`. config = `{"versionIds": ["uuid", ...]}`, `versionIds` **필수 키** | ADR D1 |
| **FR-4** (§B FR-B4) | 프론트 계약 동기화 — `actionTypeSchema` z.enum + 라벨맵 2곳 + `parse/serializeActionConfig` + `ActionConfigFormState` | ADR D3 |
| **FR-5** | **`SET_FIX_VERSIONS` 설정 UI** — `useVersions(projectKey)` + `VersionMultiSelect variant="fix"` 재사용 | Maxi 확정 §2.1 |
| **FR-6** | **명시적 의도 선택** — "선택한 버전으로 교체" / "수정 예정 버전 전체 해제" 2모드. 교체 모드 + 빈 목록이면 **저장 거부**(S8) | Maxi 확정 §2.2 |
| **FR-7** | `useVersions` **로딩/에러를 명시 처리** — `ProjectMemberSelect` 선례(disabled shell + 문구)를 따른다 | §2.3 |
| **FR-8** | `automation_actions.action_type` CHECK에 `SET_FIX_VERSIONS` 추가 — **신규 V306** | ADR D6 |
| **FR-9** | **컴파일러가 못 잡는 지점에 회귀 테스트** — `hasObservableSideEffect` · `actionTriggers` · `validTypes` 배열 | §2.4 ★ |

### 2.1. FR-5의 근거 — "계약만"이 왜 안 되나 (Maxi 확정)

액션 타입 드롭다운은 하드코딩이 아니라 **`actionTypeSchema.options`에서 자동 파생**된다.

```tsx
// ActionConfigEditor.tsx:571-583
{actionTypeSchema.options.map((type) => (
  <option key={type} value={type}>{ACTION_TYPE_LABELS[type]}</option>
))}
```

→ z.enum에 값을 넣는 순간 **사용자 드롭다운에 자동 등장**한다. 그런데 config 입력 폼은 `default` 없는 조건부 렌더
4개뿐(`:586-599`)이라, 전용 위젯을 만들지 않으면 사용자가 고른 뒤 **입력 영역이 빈 채로 남는다**.

계약만 동기화하는 선택지는 **드롭다운에서 이 액션을 숨기는 코드를 따로 넣어야** 성립하는데, 그건 "기능을 숨기는 코드"다.
→ **Maxi 확정. 설정 UI를 이 PR에 포함한다.** 재사용 자산이 전부 존재해 비용이 작다 —
`useVersions(projectKey)`(`hooks/use-versions.ts:83-89`) · `VersionMultiSelect`(`variant`/`value`/`options`/`onChange`/`disabled`만
받는 순수 표현 컴포넌트) · `ActionListEditor:157`이 이미 `projectKey`를 `ActionConfigEditor`에 전달 중.

### 2.2. FR-6의 근거 — 위험은 "빈 스텁"이 아니라 "그럴듯한 구현"에 있다 ★ (개정 2회차)

> **1회차 스펙의 서술은 코드로 반증됐다.** 1회차는 *"`serializeActionConfig`를 `return '{}'` 스텁으로 채우면
> 빈 배열이 저장돼 전체 해제된다"* 고 적었다. **틀렸다.** `Action.fromJson`은 실행 시점이 아니라 **룰 저장 시점**에
> 호출된다(`AutomationRuleService.kt:723` REST 경로 · `:884` YAML import 경로). `versionIds`가 필수 키(FR-3)이므로
> `'{}'`는 `ActionConfigInvalidException` → **저장 거부** → 아무것도 지워지지 않는다. 1회차 §2.1은 자신의 EC9와도
> 정면 모순이었다.

**진짜 위험 경로는 정반대다.**

```tsx
// ActionConfigEditor.tsx:119-123
function defaultConfigForType(type: ActionType): ActionConfigFormState {
  if (type === 'SET_FIELD') { return { field: DEFAULT_SET_FIELD, value: '' } }
  return parseActionConfig(type, {})     // ← SET_FIX_VERSIONS가 여기로 떨어지면
}
```

기존 관례(`?? ''` / `?? null` / `?? []`)를 그대로 따라 `parseActionConfig`가 `{ versionIds: [] }`를 반환하고
`serializeActionConfig`가 `JSON.stringify({ versionIds: config.versionIds ?? [] })`를 내면 —
**드롭다운에서 고르기만 하고 저장하면 `{"versionIds":[]}`가 파싱을 통과해 저장되고, 실행 시 기존 Fix Version을
전부 지운다.** 즉 **"올바르게 보이는 구현"이 위험한 쪽이고, 빈 스텁이 오히려 안전한 쪽**이다.

**→ Maxi 확정. 명시적 의도 선택(2모드).** 사고로 빈 저장이 불가능해지고, 의도적 해제는 명시적이 된다.

**모드는 UI 전용 파생 상태다 — 와이어 포맷·백엔드는 무변경.**

| 상황 | 모드 | 직렬화 |
|---|---|---|
| 액션 타입 새로 선택 | `replace`(기본) + 빈 목록 → **저장 거부**(S8) | — |
| 버전 1개 이상 체크 | `replace` | `{"versionIds": ["..."]}` |
| "전체 해제" 명시 선택 | `clear` | `{"versionIds": []}` |
| 기존 룰 로드 — `versionIds` 비어 있음 | `clear`로 복원 | — |
| 기존 룰 로드 — `versionIds` 있음 | `replace`로 복원 | — |

`defaultConfigForType`은 **`SET_FIELD`처럼 `SET_FIX_VERSIONS`도 명시 분기**한다 — 기존 KDoc이 그 선례의 사유를
*"select가 유효한 초기값을 갖도록"* 이라 밝혔고, 여기서도 같은 이유(폼이 유효한 초기 모드를 갖도록)다.
`parseActionConfig(type, {})`로 떨어뜨리면 `versionIds` 부재 → `clear` 모드로 오판된다.

#### ★ `fixVersionsMode`의 `undefined` 폴백 = `replace` (fail-closed) — 개정 3회차

`ActionConfigFormState`는 단일 optional 유니온이므로 `fixVersionsMode`도 **optional일 수밖에 없다**.
그래서 **`undefined`일 때 어느 쪽으로 붙는지를 못박지 않으면 B1이 그대로 재현된다.**

```ts
// ✗ 이렇게 쓰면 undefined가 가드를 그냥 통과해 → {"versionIds":[]} → 전체 해제
if (config.fixVersionsMode === 'replace' && (config.versionIds ?? []).length === 0) reject()
```

**규정.** `undefined ≡ 'replace'`. 파괴적 동작(`clear`)은 **명시적으로 그렇게 적혀 있을 때만** 성립한다.

```ts
// ✅ 저장 가드 — clear가 아니면 전부 replace로 취급 (fail-closed)
if (config.fixVersionsMode !== 'clear' && (config.versionIds ?? []).length === 0) reject()

// ✅ 직렬화 — 같은 방향
return config.fixVersionsMode === 'clear'
  ? JSON.stringify({ versionIds: [] })
  : JSON.stringify({ versionIds: config.versionIds ?? [] })
```

#### ★ `fixVersionsMode`는 와이어 대응이 없는 최초의 필드 — KDoc에 명시한다

`ActionConfigFormState` KDoc(`automation-rules.types.ts:284-296`)은 현재 **모든 필드를 와이어 필드로 열거**한다
(SET_FIELD→`field`/`value`, ASSIGN→`assigneeId`, …). `fixVersionsMode`는 그 규칙의 **첫 예외**(순수 UI 상태)이므로
KDoc에 그 사실을 적는다 — 적지 않으면 다음 구현자가 와이어 필드로 오인해 config에 실어 보낸다.

#### `clear` 모드일 때 버전 목록 위젯

`clear` 모드에서는 `VersionMultiSelect`를 **렌더하지 않는다**(숨김). disabled로 남겨두면 사용자가 체크한 버전이
직렬화 시 조용히 버려져 "체크했는데 저장이 안 됨"으로 보인다. `replace → clear → replace` 왕복 시
`config.versionIds`는 **폼 상태에 그대로 보존**한다(모드만 바뀔 뿐 선택을 파괴하지 않는다).

### 2.3. FR-7의 근거 — 로딩/에러가 곧 사고 경로다

`useVersions`(`hooks/use-versions.ts:83-89`)는 `useQuery`를 그대로 반환하므로 로딩/에러 시 `data`가 `undefined`다.
`options={versions ?? []}`로 때우면 —

```tsx
// VersionMultiSelect.tsx:89-91
const selectedVersions = value.map((id) => options.find((ver) => ver.id === id))
                              .filter((ver): ver is Version => ver !== undefined)
```

**이미 저장된 versionIds의 칩이 전부 사라진다.** 사용자는 "아무것도 선택 안 됨"으로 오인하고 저장 → 전체 해제.
FR-6의 모드 선택만으로는 이 경로를 못 막는다(모드는 `replace`인 채 목록만 비어 보임).

**선례를 따른다** — `ProjectMemberSelect.tsx:17-22`가 `TEXT.loading='멤버 목록을 불러오는 중...'` /
`TEXT.error='멤버 목록을 불러오지 못했습니다.'` + disabled shell로 처리한다. 같은 파일 옆의 형제 컴포넌트이므로
새 패턴을 발명하지 않는다.

### 2.4. FR-9의 근거 — 컴파일러가 강제하는 건 "분기의 존재"지 "값의 정합성"이 아니다 ★

Kotlin 2.0.10에서 sealed 대상 `when`은 문/식 모두 exhaustive를 강제하므로, **12지점은 누락 시 빌드가 깨진다.**
그러나 **분기를 뚫어놓고 값을 틀리게 넣으면 컴파일러는 침묵한다.** §8.2가 12지점의 **값**을 전부 명시하는 이유다.

컴파일러가 아예 못 잡는 3곳.

1. `RuleConflictAnalyzer.hasObservableSideEffect:317-319` — `it is X || it is Y` **boolean 체인**. 누락 시
   PRIORITY_AMBIGUITY 충돌 탐지가 조용히 무시.
2. `RuleConflictAnalyzer.actionTriggers:98-107` — 분기는 강제되나 **반환값이 틀려도 조용하다**(§8.2 참조).
3. `automation-rules.types.test.ts:273` `validTypes` 배열 — 누락 시 새 타입이 그냥 미검증.

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 | 검증 |
|---|---|---|
| NFR-1 | **권한 우회 0** — 룰 actor의 UPDATE 권한이 없으면 반드시 차단. 어댑터는 도메인 유스케이스에 위임만 하고 자체 권한 검증을 하지 않는다(위임 대상이 이미 fail-closed) | S5 통합 테스트 |
| NFR-2 | **BC 격리 유지** — automation은 issue-tracking 타입을 import하지 않는다 | ArchUnit 기존 룰 |
| NFR-3 | **shared-kernel Jackson 비의존** — `SetFixVersionsCommand`는 `List<UUID>`(BC 공통 분모). JSON 파싱은 automation의 `Action.fromJson` | `IssueMutationCommands.kt` KDoc 원칙 |
| NFR-4 | 트리거→액션 처리 지연 p95 < 5s (automation BC 게이트 승계) | 기존 측정표 |

## 4. 인터페이스

### 4.1. 포트 (shared-kernel) — 신규

```kotlin
// IssueMutationCommands.kt
data class SetFixVersionsCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val versionIds: List<UUID>,   // 전체교체. 빈 목록 = 전체 해제
    val dryRun: Boolean,
)

// IssueMutationPort.kt — 4번째 메서드 (default 없음)
fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult
```

**`expectedVersion` 없음** — ADR D2. `ActionExecutor`가 보는 `IssueSnapshot`은 9필드이고 `version`이 없어
유효한 값을 조달할 경로 자체가 없다. 두면 항상 null인 죽은 분기 → 훗날 TOCTOU의 씨앗.

**`MutationResult.version`을 어떻게 채우나 (개정 2회차 — 1회차 미규정).**
`changeFixVersions`(`IssueApplicationService.kt:968`)는 `repo.findByKeyWithType(key)` **재조회 결과**를 반환하고,
`replaceVersionLinks` → `bumpVersionOrZero`(`IssueRepository.kt:2181`)가 `VERSION = expectedVersion + 1`로 bump하므로
반환 `IssueResponse.version`은 **커밋 후 신 버전**이다. → `setField:95-107`/`assign:115-126`과 **완전 동형**으로
`.version`을 그대로 쓴다. 새 패턴을 만들지 않는다.

### 4.2. 액션 config (automation) — 신규

```yaml
- type: SET_FIX_VERSIONS
  config:
    versionIds: ["3f2b-...", "8a1c-..."]
```

- `versionIds` **필수 키**. 부재/타입 불일치 → `ActionConfigInvalidException` → **룰 저장 거부**(기존 `fromJson` 계약 동형)
- 빈 배열 **허용**(= 전체 해제, S4)
- 원소는 UUID 문자열. 형식 위반 → 파싱 실패
- **개수 상한 없음** — 기존 REST 경로(`ChangeVersionsRequest.kt:20` `versionIds: List<UUID> = emptyList()`)가 상한을
  두지 않으므로 새 상한을 발명하지 않는다(두 경로가 갈라지면 그 자체가 결함)

### 4.3. REST — **신규 엔드포인트 없음**

자동화 룰 API는 액션을 `{type, config}` 제네릭 형태로 받고 `ActionType.valueOf(input.type)`
(`AutomationRuleService.kt:719`)로 파싱하므로 **엔드포인트 변경이 없다**.

## 5. 데이터 모델 변경

**신규 `V306__automation_actions_set_fix_versions.sql`** — `automation_actions.action_type` CHECK 재정의.

```sql
ALTER TABLE automation_actions DROP CONSTRAINT ck_automation_actions_action_type;
ALTER TABLE automation_actions ADD CONSTRAINT ck_automation_actions_action_type
    CHECK (action_type IN ('SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS'));

-- ★ 컬럼 코멘트 재발행 필수 — V302:45가 '4종'으로 박아뒀고 이건 살아있는 DB 객체다.
-- 재발행하지 않으면 V302 편집 금지(제약 2)와 무관하게 운영 DB에 4종 drift가 영구히 남는다.
COMMENT ON COLUMN automation_actions.action_type IS
    '액션 종류 — CHECK 5종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS)';
```

제약명 `ck_automation_actions_action_type`은 `V302:34`와 일치함을 확인했다.

- **V302 편집 금지** — 이미 적용된 마이그레이션 편집은 Flyway 체크섬 드리프트(`:modules:app:test`는 5433 영속 DB)
- automation 예약 구간 V300~V399, 현재 최신 V305 → **V306**. 머지 직전 V번호 재확인 필수
- 테이블/컬럼 추가 없음

## 6. 엣지 케이스

| ID | 상황 | 기대 동작 | 방어 위치 |
|---|---|---|---|
| EC1 | `versionIds` 빈 배열 | **전체 해제**(정상 동작). UI는 명시적 `clear` 모드로만 도달 가능(FR-6) | 도메인 `assignFixVersions` — 분기 불필요 |
| EC2 | 중복 UUID (`[A, A, B]`) | 중복 제거 → `[A, B]`. 에러 아님 | `Issue.kt:295` `.distinct()` |
| EC3 | 타 프로젝트 버전 UUID | 실패 → `rule_executions` FAILED. **원인이 관측 가능해야 함**(§8.3) | `validateVersions:1636-1643` — `findById(id, projectId)` 프로젝트 스코프 |
| EC4 | 존재하지 않는/소프트 삭제된 버전 | EC3과 동일 경로 (`VersionRepository.kt:77-86`이 `DELETED_AT.isNull` 필터) | 같음 |
| EC5 | 룰 actor에 UPDATE 권한 없음 | `IssueAccessDeniedException` → 어댑터가 `IssueMutationPermissionDeniedException`으로 번역 → FAILED(권한 거부로 **분류됨**) | `runAttempt:186-190` |
| EC6 | 이슈 부재/소프트 삭제 | `IssueNotFoundException` → FAILED | `changeFixVersions:950` |
| EC7 | OCC 충돌 | 1회 재시도. 재실패 시 `IssueVersionConflictException` → FAILED | `runWithOccRetry:162-172` |
| EC8 | `dryRun=true` | 권한·버전 검증은 실제 수행, 커밋/이벤트 미발행. `applied=false`, `version=null` | `runAttempt`의 `setRollbackOnly()` |
| EC9 | config에 `versionIds` 키 부재 | 파싱 실패 → **룰 저장 시점에 거부**(실행 시점 아님) | `Action.fromJson` ← `AutomationRuleService.kt:723`·`:884` |
| EC10 | UUID 형식이 아닌 문자열 | 파싱 실패 → 룰 저장 거부 | 같음 |
| EC11 | **ARCHIVED 버전 지정** | **설정된다(통과).** `VersionRepository.findById:77-86`은 `DELETED_AT.isNull`만 보고 **status를 필터하지 않는다**(`IssueRepository.kt:1441` KDoc이 명시). 기존 이슈 편집 경로와 동일 — **새 정책을 만들지 않는다** | `validateVersions` (개정 2회차: 1회차의 "통과 여부를 따름"은 검증 불가 문장이라 단언으로 교체) |
| EC12 | UI: 교체 모드 + 빈 목록 저장 시도 | **저장 거부**(S8) | 프론트 FR-6 |
| EC13 | UI: `useVersions` 로딩/에러 | **disabled shell + 문구**(`ProjectMemberSelect:109` KDoc — *"select를 비활성화하고 안내 문구를 보여준다(빈 목록으로 은폐하지 않음)"*). 위젯이 렌더되지 않으므로 `onChange`가 발화하지 않고 `config.versionIds`는 로드값 그대로 보존된다 → C2 사고 경로가 **이 자체로 닫힌다**. **저장은 막지 않는다** — 막으면 룰 이름만 고치려는 사용자까지 차단(개정 3회차: 2회차의 "저장 가능 상태로 두지 않는다"는 선례 밖 신규 요구 + 회귀 유발이라 철회) | 프론트 FR-7 |
| EC14 | 이미 `[1.2.0]`인 이슈에 `SET_FIX_VERSIONS [1.2.0]` 재실행 | 성공. 단 **`version`이 bump되고 `updated_at`이 갱신된다**(`replaceVersionLinks:2154`가 무조건 먼저 bump — `updateIssue:517-519`의 무변경 단락이 이 경로엔 없다). 히스토리는 `IssueHistoryRecorder.kt:64-65`의 `detected.isEmpty()` 단락으로 안 남는다 | (동작 기록 — 변경 대상 아님) |

> **★ "422" 표기 금지** (마스터 스펙 §B-4 / 부록 A C-i). 422는 `IssueController` 동기 REST 매핑 전용이다.
> automation은 pgmq 워커에서 **비동기** 실행되므로 HTTP 응답 자체가 없다 — 실패는 `rule_executions` FAILED로만 관측된다.

## 7. 제약

1. **PR-C 범위 침범 금지** — `TriggerType.PR_MERGED`·Git webhook·`automation` permitAll·`BTS_AUTOMATION_ENCRYPTION_KEY` 제외.
2. **V302 편집 금지** (§5).
3. **포트 default 구현 금지** (ADR D5) — 컴파일 에러 4건은 이 계약이 작동한다는 증거다.
4. **`hasObservableSideEffect`를 exhaustive `when`으로 리팩토링하지 않는다** — 폭발 반경이 큰 구조 변경. 이 PR은
   회귀 테스트로 방어하고 구조 개선은 후속(ADR §후속).
5. **`VersionMultiSelect`를 수정하지 않는다** — `variant="fix"`가 이미 "수정 버전" 문구를 제공한다. 수정하면
   이슈 상세 화면(`IssueMetaPanel.tsx:346-356`)에 회귀 위험.
6. **`ActionExecutor.classifyPortFailure`를 이 PR에서 확장하지 않는다** — 실패 분류 세분화는 별건(§8.3 각주).
7. FR 총수 **123 불변** (D단계 작업, 신규 FR 아님).

## 8. 측정 가능한 완료 기준

### 8.1. 백엔드 — 신규 코드
- [ ] `SetFixVersionsCommand` + `IssueMutationPort.setFixVersions` (default 없음)
- [ ] `IssueMutationPortContractTest`가 **4 메서드**를 검증하도록 갱신 (테스트명·KDoc 포함)
- [ ] `AutomationIssueMutationAdapter.setFixVersions` — `changeFixVersions` 위임 + `findByKey().version` 재조회 + `runWithOccRetry` (§4.1)
- [ ] `ActionType.SET_FIX_VERSIONS` + `Action.SetFixVersionsAction(versionIds: List<UUID>)` + `parseSetFixVersions`
- [ ] **두 스텁은 패턴이 정반대다 — 각자의 기존 패턴을 따른다** ★ (개정 3회차 정정. 2회차의 "fail-closed 기존 패턴"은 automation에 대해 **거짓**이었고, 그대로 구현하면 기존 슬라이스 테스트가 깨진다)
  - `automation/StubIssueMutationPort` — **fail-safe**. KDoc `:23-26`이 *"기본 동작은 항상 성공 … 웹 계층/워커 슬라이스 테스트가 이 stub을 명시 설정하지 않아도 자동화 실행 경로가 예외 없이 통과한다"* 로 성문화. `setFixVersions`도 **기본 성공 + `failNextCallsWith` 토글**
  - `slack-integration/StubIssueMutationPort` — **fail-closed**. KDoc `:27`이 *"미시드 기본값 = 명시 오류"*. `setFixVersions`도 **미시드 호출 시 `IllegalStateException`**. slack은 이 메서드를 쓰지 않지만 인터페이스 계약상 구현 필요(기존 `setField`가 이미 같은 처지)
- [ ] V306 마이그레이션

### 8.2. ★ 12지점의 **값**을 명시한다 (컴파일러는 분기만 강제)

| 파일:라인 | 함수 | `SetFixVersionsAction`의 값 | 근거 |
|---|---|---|---|
| `Action.kt:86-91` | `fromJson` | `parseSetFixVersions(node)` | FR-3 |
| `RuleConflictAnalyzer.kt:103-106` | `actionTriggers` | **`false`** ★ | `changeFixVersions:944-969`에 **`eventPublisher.publish`가 없다**(대조군 `updateIssue:544`엔 있음) → ISSUE_UPDATED 트리거를 유발할 경로가 **런타임에 존재하지 않는다**. `AssignAction:104 -> false`와 동형. **`triggersIssueUpdated(target, "fixVersions")`를 쓰면 안 된다** — `RuleConflictAnalyzer.kt:88-97` KDoc이 성문화한 *phantom edge* 사고(AssignAction에서 이미 한 번 겪음)의 재발이다 |
| `RuleConflictAnalyzer.kt:317-319` | `hasObservableSideEffect` | **포함(`|| it is Action.SetFixVersionsAction`)** ★ boolean 체인 — 컴파일러 미강제 | 이슈를 실제로 바꾸는 액션 |
| `RuleConflictAnalyzer.kt:414-417` | `requiredPermission` | `IssuePermission.UPDATE` | `changeFixVersions:949`가 `assertPermission(UPDATE)` |
| `RuleConflictAnalyzer.kt:426-429` | `actionKindLabel` | `"수정 예정 버전 설정"` | 라벨맵과 문구 일치 |
| `ActionExecutor.kt:199-213` | `dispatchAction` | `issueMutationPort.setFixVersions(SetFixVersionsCommand(...))` | FR-2 |
| `ActionExecutor.kt:289-292` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationActionRepository.kt:113-116` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationActionRepository.kt:130-152` | `actionConfigJson` | `{"versionIds": ["uuid문자열", ...]}` — **`fromJson`의 정확한 역함수** ★ **이 파일에 ArrayNode 선례가 없다**(기존 4종은 `put`/`set`/중첩 ObjectNode뿐) → `node.putArray("versionIds")` 후 각 UUID를 `.toString()`으로 `add`. UUID는 **문자열**로 직렬화한다(`AssignAction`의 `node.put("assigneeId", uuid.toString())` 관례 동형) | `:122-124` KDoc — *"여기서 어긋나면 `findByRuleId` 역직렬화가 실패한다"* → 그 룰의 **모든** 액션이 로드 불가(poison) |
| `AutomationRuleResponses.kt:190-193` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationRuleResponses.kt:197-209` | `actionConfigOf` | `mapOf("versionIds" to ...)` | 프론트 `parseActionConfig` 입력 |
| `AutomationYamlCodec.kt:245-248` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationYamlCodec.kt:256-272` | `actionConfigMap` | `mapOf("versionIds" to ...)` | S7 왕복 |

### 8.3. ★ 양성 단언 — "FAILED면 통과"는 vacuous다

`ActionExecutor.classifyPortFailure:256-261`은 **권한 거부만 타입으로 분류**하고 나머지(EC3·EC4·EC6·EC7)를 전부
`FAILURE_GENERIC = "FAILED"`(`:318`)로 수렴시킨다. 따라서 S6 완료 기준을 *"rule_executions에 FAILED로 기록"* 으로만
두면 **`validateVersions`를 통째로 지워도 통과한다**(다른 이유로 실패하기만 하면 되니까).

→ **S6 테스트는 "타 프로젝트 versionId 때문에 실패했다"를 양성 단언한다** — `IssueLinkedVersionNotFoundException`이
**그 versionId를 담고** 던져지는지 직접 단언하고(`IssueExceptions.kt:130` — `class IssueLinkedVersionNotFoundException(val versionId: UUID)`
로 **public val** 보유 → `.versionId shouldBe betaVersionId` 가능), 이슈의 fixVersions가 **변경되지 않았음**을 함께 확인한다.
(마스터 스펙 §A-5가 PR-A에 요구한 *"'401이 아님' 폐기"* 규율의 PR-B 적용.)

**★ S6는 issue-tracking 모듈의 _실 DB_ 테스트에 둔다** (개정 4회차 정정).

`IssueLinkedVersionNotFoundException`은 issue-tracking BC 타입이라 automation 테스트에서 참조할 수 없다.
**차단 기제는 Gradle 클래스패스다** — `backend/modules/automation/build.gradle.kts:39`가 `:modules:shared-kernel`만
의존하고 issue-tracking 의존이 없다. (3회차의 *"ArchUnit이 차단"* 은 **거짓**이었다 — `AutomationBcArchTest.kt:31`이
`ImportOption.DoNotIncludeTests()`로 **테스트를 검사 대상에서 제외**한다. 이 오기가 무해하지 않은 이유. "ArchUnit이
막는다"고 믿은 구현자가 `testImplementation(project(":modules:issue-tracking"))`를 추가하면 **컴파일도 되고 ArchUnit도
통과한다** — 근거가 막으려는 바로 그 행동을 못 막는다.)

**✗ `AutomationIssueMutationAdapterTest`에 두면 안 된다** (3회차의 확정을 철회). 그 테스트는 MockK 단위 테스트다
(`:56` `private val issueApplicationService: IssueApplicationService = mockk()`). 거기서 S6를 쓰면
`every { ... } throws IssueLinkedVersionNotFoundException(betaId)` — **자기가 심은 스텁을 자기가 단언**하는 것이라
`validateVersions:1636-1643`가 **한 줄도 실행되지 않는다**. §8.3이 스스로 내건 기준(*"`validateVersions`를 통째로
지워도 통과한다"*)에 **정확히 그대로 걸린다.** 검증 장치를 만들려다 vacuous 장치를 만드는 꼴이다.

**✅ 정본 위치.** 실 DB로 이 동작을 검증하는 기존 자리가 이미 있다 —
`IssueVersionLinksIntegrationTest.kt:379,412`(타 프로젝트/삭제 버전 → `IssueLinkedVersionNotFoundException`) ·
`IssueChangeVersionsServiceTest.kt:224-225,459-460`. **S6는 이 선례 형태로 작성한다**(Testcontainers 실 DB에서
`validateVersions`가 실제로 돌고, 이슈의 fixVersions가 변경되지 않았음을 DB에서 확인).
S2~S5는 automation 통합 테스트(`rule_executions` 관측)에 그대로 둔다.

> 실패 분류 세분화(`classifyPortFailure`에 버전 오류 타입 추가)는 **이 PR 범위 밖**(제약 6). 사용자가
> `rule_executions`에서 EC3과 EC6을 구분하지 못하는 UX 문제는 **후속 후보**로 §9에 기록.

### 8.4. 프론트
- [ ] `actionTypeSchema` z.enum + `ActionConfigFormState`에 `versionIds?: string[]` + `fixVersionsMode?: 'replace' | 'clear'`(UI 전용, 직렬화 안 됨)
- [ ] `defaultConfigForType`에 **`SET_FIX_VERSIONS` 명시 분기** — `{ fixVersionsMode: 'replace', versionIds: [] }` (§2.2 ★ `parseActionConfig(type, {})`로 떨어뜨리면 `clear` 오판)
- [ ] `parseActionConfig` — 기존 관례(`typeof` 가드) 따라 **배열 여부 + 원소 string 여부** 검사. `versionIds` 비어 있으면 `clear`, 있으면 `replace`로 모드 복원
- [ ] `serializeActionConfig` — `clear` → `{versionIds: []}` / `replace` → `{versionIds: [...]}`
- [ ] 라벨맵 2곳 (`ActionConfigEditor:48-53` 드롭다운 / `AutomationRuleList:62-67` 배지)
- [ ] `SetFixVersionsFields` — 모드 라디오 + `VersionMultiSelect variant="fix"` + `useVersions` 로딩/에러 shell(FR-7)
- [ ] 교체 모드 + 빈 목록 → 저장 거부(S8)
- [ ] **`validTypes` 배열(`automation-rules.types.test.ts:273`) 갱신** ★ 컴파일러 미강제
- [ ] `pnpm typecheck` 0 (**tsconfig.app.json 기준** — CI가 그걸 씀) · `pnpm lint` 0 · 유닛 회귀 0

### 8.5. 테스트
- [ ] S2·S3·S4·S5 통합 테스트 (**automation 모듈**, 실 DB Testcontainers, `rule_executions` 관측)
- [ ] **S6 양성 단언 — issue-tracking 모듈**(`AutomationIssueMutationAdapter` 테스트). NFR-2 때문에 automation에 둘 수 없다 (§8.3)
- [ ] S7 YAML 왕복
- [ ] S8 + EC13 프론트 단위 테스트.
  **★ 개정 4회차 — 3회차가 지정한 `actionsToRequest:198`은 존재하지 않는 함수다**(내가 지어낸 이름 — learnings
  "Claude의 환각(잘못된 라이브러리/API)" 그대로). 실명은 **`serializeActionsFormState`**(`AutomationRuleFormDialog.tsx:197-198`).
  - 그 함수는 `(actions) => ActionRequestInput[]` **순수 매핑이라 에러 채널이 없다** → S8 거부를 여기서 표현할 수 없다.
  - 이 다이얼로그의 기존 검증 2갈래(react-hook-form `errors` `:445,533,567` / 서버 `submitError` `:435,603`) 중
    **어느 쪽도 `actions`를 덮지 않는다** — `actions`는 RHF 스키마 밖의 별도 state다(`:579` `<ActionListEditor value={actions} onChange={setActions} />`).
  - → **actions 전용 검증 기제를 신설해야 한다**(제출 직전 `validateActions(actions): {index, message}[]` 후
    actions용 에러 state에 반영). **위반한 행 index를 특정**해 문구를 띄운다 — 액션이 여러 개일 때 어느 것이
    문제인지 모르면 사용자가 고칠 수 없다.
  - ★ 이 가드는 **load-bearing**이다 — 가드 없이 직렬화만 구현하면 `undefined`/`[]` 조합이 `{"versionIds":[]}`로
    그대로 나가 §2.2가 막으려던 파괴적 저장이 재현된다.
- [ ] **FR-9 회귀 3종** — `hasObservableSideEffect` 포함 · `actionTriggers == false` · `validTypes` 5종.
  ★ 앞 둘은 `RuleConflictAnalyzer`의 **private 함수**라 직접 호출 불가 → **공개 API `analyze()` 경유 간접 단언**.
  **★ 개정 4회차 — 3회차가 확정한 설계 2종은 둘 다 vacuous였다(버그를 넣어도 통과). 아래가 정정본이다.**

  **(a) `actionTriggers` 회귀 — 반드시 self-loop 형태로.**
  - ✗ 3회차 설계(*"SET_FIX_VERSIONS 룰 + ISSUE_UPDATED 룰 2개 → CYCLE 미검출 단언"*)는 **항상 통과한다**.
    `CycleDetector`는 back-edge DFS라 A→B 엣지 1개는 사이클이 아니다 — 버그를 넣어도 엣지만 하나 늘 뿐 CYCLE이 안 뜬다.
  - ✅ 정정. **SET_FIX_VERSIONS 룰 _자신_이 `triggerType = ISSUE_UPDATED` + `triggerConfig.fields ⊇ ["fixVersions"]`**
    인 룰 **1개**를 두고 `analyze()`가 **CYCLE을 반환하지 않음**을 단언한다. `edgesFrom:76-80`이 `rules`에 자기 자신을
    포함하고 KDoc `:28`이 *"self-loop(A → A)도 유효한 사이클로 취급한다"* 고 명시하므로 —
    잘못된 구현(`triggersIssueUpdated(target, "fixVersions")`)이면 **A→A 자기 엣지 → CYCLE 검출 → 테스트 FAIL**,
    올바른 구현(`false`)이면 엣지 없음 → PASS. 버그를 정확히 잡아낸다.

  **(b) `hasObservableSideEffect` 회귀 — `SetFixVersionsAction`만 가진 룰 2개로.**
  - ✗ 3회차 설계(*"같은 필드를 노리는 **동순위** 룰 2개"*)는 두 겹으로 깨진다.
    ① **"동순위"는 존재하지 않는 개념** — `AutomationRule`에 `priority` 필드가 없다(실제 게이트는 `coFire:230-239`
    = 같은 `triggerType` + WEBHOOK 아님). ② *"같은 필드를 노리는"* → FIELD_CONFLICT가 먼저 잡혀
    `priorityAmbiguity:304`의 `pairIds !in conflictedPairs`가 false → **PRIORITY_AMBIGUITY가 억제**된다.
    설령 피하더라도 `SetFieldAction`이 하나라도 있으면 `hasObservableSideEffect:317-319`의 `it is Action.SetFieldAction ||`
    가 **먼저 true를 반환해 새 분기를 아예 안 태운다**(vacuous).
  - ✅ 정정. **같은 non-WEBHOOK `triggerType`을 가진 룰 2개가 각각 `SetFixVersionsAction` _만_ 보유**(다른 액션 0개)
    → `analyze()`가 **PRIORITY_AMBIGUITY를 검출함**을 단언. 누락 구현이면 `any{}`가 false → 미검출 → FAIL.
- [ ] `ActionTest.kt` `entries.size` 4 → 5 + 목록
- [ ] `SchemaMigrationTest` — 기존 `V302 유효한 action_type 4종은 INSERT 허용`(`:654`)은 **5종으로 갱신**(테스트명 포함, 방치하면 거짓 이름) + SET_FIX_VERSIONS INSERT 허용 + 미지의 값 거부

### 8.6. 전수 동기화 (CLAUDE.md §명세/범위 변경 시 전수 동기화)
> **★ 개정 3회차.** 2회차의 이 목록은 스스로 "전수"라 부르면서 **6건을 누락**했다(C4 미종결). `verify-master-plan.sh`는
> 이 KDoc 카운트를 잡지 못하므로 drift가 그대로 머지된다 → **착수 전 아래를 `grep -rn "4종\|4개\|네 개\|3 메서드\|세 메서드"` 로 재확인**한다.
>
> **★ 오탐 주의 — 건드리면 안 되는 "4종".** `automation-rules.types.ts:40`의 *"규칙 충돌 타입 enum — backend
> `ConflictType` 4종(CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY·…)"* 은 **`ActionType`과 무관**하다. grep이
> 잡아내지만 **수정 대상이 아니다**.
>
> **★★ 개정 4회차 — 이 목록은 3회 연속 "전수"를 자칭하며 매번 누락했다** (1회차 누락 → 2회차 "전수" + 6건 누락
> → 3회차 "전수" + 8건 누락). **근본 원인이 드러났다. `grep "4종\|4개"` 처방은 _열거형 KDoc_ 을 원리적으로 못 잡는다** —
> `RuleConflictAnalyzer.kt:315`의 *"부수효과 액션(SET_FIELD/ASSIGN/ADD_COMMENT)"* 처럼 **개수를 안 쓰고 이름만 나열한**
> 주석이 그렇다. 하필 그게 §8.2가 값을 바꾸라고 지시한 바로 그 함수들의 KDoc이다.
>
> **→ 착수 시 grep을 2종으로 돌린다.**
> ```bash
> grep -rn "4종\|4개\|네 개\|3 메서드\|세 메서드\|세 쌍" backend/modules apps/web   # 개수 표기
> grep -rn "SET_FIELD/ASSIGN\|SetFieldAction.*AssignAction\|필드 변경.*담당자" backend/modules apps/web  # 열거형
> ```
> 그래도 목록의 완전성을 보증하지 못한다 — **구현자는 §8.2가 손대는 모든 함수의 KDoc을 직접 읽고 판단한다.**

- [ ] "3 메서드/세 메서드" 계열 — `IssueMutationPort.kt:5,28` · `IssueMutationCommands.kt:84` · `IssueMutationPortContractTest.kt:14,24,25`(`:24`는 테스트명) · `slack/StubIssueMutationPort.kt:28` · `AutomationIssueMutationAdapter.kt:29` (2회차의 `:5`는 오인용 — `:5`는 import 행)
- [ ] "4종" → "5종" — `ActionType.kt:1` · `ActionType.kt:11-14`(KDoc 불릿 4개 → 5번째 추가) · `AutomationRuleRequests.kt:75` · `AutomationRuleService.kt:646` · `AutomationRulesYaml.kt:73` · **`RuleConflictAnalyzer.kt:85`**("sealed [Action] 의 4개 하위 타입") ← 누락분
- [ ] 프론트 KDoc — `automation-rules.types.ts:22` · **`automation-rules.types.ts:287`**("4종 액션의 config 필드를 optional 유니온 하나에 담는다" — **FR-4/§8.4가 고치는 바로 그 `ActionConfigFormState` KDoc**, `fixVersionsMode` 예외도 여기 명시) ← 누락분 · `ActionConfigEditor.tsx:47,540` · `AutomationRuleList.tsx:61` · **`AutomationRuleFormDialog.tsx:2`**(파일 L1 주석 "액션 리스트(4종)") ← 누락분 · **`AutomationRuleFormDialog.tsx:576`**("액션 — 4종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK)") ← 누락분 · `automation-rules.types.test.ts:268`
- [ ] 테스트명·파일주석 drift — `ActionConfigEditor.test.tsx:41` · `ActionExecutorTest.kt:1,288` · `AutomationActionRepositoryTest.kt:43,134` · `ActionExecutionEndToEndIntegrationTest.kt:54,226,229` · `ActionTest.kt:1,16,17,257` · `automation-rules.types.test.ts:272` · `SchemaMigrationTest.kt:51,651` (**4회차 추가분** — `ActionExecutorTest:1` · `AutomationActionRepositoryTest:43` · `ActionExecutionEndToEndIntegrationTest:54,226` · `SchemaMigrationTest:51`)
- [ ] **열거형 KDoc — grep이 못 잡는 것** ★ 4회차 신규
  - **`RuleConflictAnalyzer.kt:315`** — *"부수효과 액션(SET_FIELD/ASSIGN/ADD_COMMENT)을 하나라도 보유하면 true"* ← §8.2가 값을 바꾸라는 `hasObservableSideEffect`의 KDoc
  - **`RuleConflictAnalyzer.kt:328-330`** — `## 권한 매핑` 열거 ← §8.2가 값을 바꾸라는 `requiredPermission`의 KDoc
  - **`slack/StubIssueMutationPort.kt:23`** — *"세 쌍을 각각 세터로 직접 시드한다"* ← §8.1이 4메서드로 늘리라는 바로 그 파일
- [ ] **`V306`에 `COMMENT ON COLUMN` 재발행** ★ 4회차 신규 — `V302:45`가 `COMMENT ON COLUMN automation_actions.action_type IS '액션 종류 — CHECK 4종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK)'`. 이건 **살아있는 DB 객체**라 V302 편집 금지(제약 2)와 무관하게 V306이 재발행하지 않으면 **운영 DB에 "4종" drift가 영구히 남는다**
- [ ] `docs/plan/product/automation.md` §2.7 D단계 체크박스 · `bash scripts/verify-master-plan.sh` 통과

### 8.7. ★ 검증 방식 (false-green 방지 — learnings 2026-07-15)
- 회귀는 **태스크를 따로 invoke**하고 `build/test-results/test/TEST-*.xml`로 **실행된 테스트 수를 실측**해 대조한다
  (묶음 태스크 + `--rerun-tasks`가 6/56클래스만 돌고 BUILD SUCCESSFUL을 낸 전례).
- 쉘 종료 코드는 `set -o pipefail` + `$?`로 확인한다(zsh `${PIPESTATUS[0]}`는 **항상 빈 문자열**).
- **FR-9의 회귀 3종은 반드시 "일부러 위반을 넣어 fail을 확인"한 뒤 통과시킨다** — vacuous 통과 방지.

## 9. 이 PR이 만들지 않는 것 (PR-C / 후속)

- `TriggerType.PR_MERGED`, `POST /api/v1/webhooks/git`, 서명 검증, targetBranch 필터 → **PR-C**
- `hasObservableSideEffect` 구조 개선(exhaustive when 전환) → 후속
- **`classifyPortFailure` 실패 분류 세분화** — 현재 EC3/EC4/EC6/EC7이 `rule_executions`에서 전부 `"FAILED"` 한 문자열로
  수렴해 사용자가 원인을 구분 못 한다(§8.3) → **후속 후보(신규 발견)**
- `validTypes` 배열을 Zod enum에서 파생시켜 drift 구조적 차단 → 후속
- FR-AT-04 UI의 status/type 드롭다운 확장 → 기존 후속 묶음

## 10. 개정 이력

### 4회차 (2026-07-16) — Phase B 3회차 반영 (**검증 장치 자체가 vacuous했다**)

> **★ 이 스펙의 핵심 교훈.** 3라운드 모두 **"직전 개정이 새로 넣은 결함"** 을 잡았고, 그 성격이 라운드마다 바뀌었다.
> 1·2회차는 *핵심 로직에 구멍*, 3회차는 **그 구멍을 막겠다고 세운 검증 장치 자체가 vacuous**.
> §8.3은 vacuous 테스트를 없애려 신설됐는데 지정한 위치가 vacuous였고(B2), FR-9는 컴파일러 사각지대를 지키려
> 신설됐는데 테스트 설계가 버그를 넣어도 통과했으며(B1), §8.6은 drift를 막으려 신설됐는데 처방한 grep이
> 열거형 KDoc을 원리적으로 못 잡았다(B3). **방어를 추가하는 행위 자체가 새 사각지대를 만든다.**

| 지적 | 조치 |
|---|---|
| 🛑 **FR-9 테스트 설계 2종이 둘 다 vacuous** — CYCLE은 A→B 엣지 1개론 사이클이 아니라 버그를 넣어도 통과 / PRIORITY_AMBIGUITY는 "동순위"가 **존재하지 않는 개념**(`priority` 필드 없음)이고 `SetFieldAction`이 있으면 `any{}`가 먼저 단락 | §8.5 재작성 — **self-loop 형태**(룰 자신이 ISSUE_UPDATED+fields⊇["fixVersions"]) / **`SetFixVersionsAction`만 가진 룰 2개** |
| 🛑 **S6를 MockK 어댑터 테스트에 배치** — 자기가 심은 스텁을 자기가 단언, `validateVersions`가 한 줄도 안 돎. §8.3 자신의 vacuity 기준에 걸림 | **실 DB 선례**(`IssueVersionLinksIntegrationTest.kt:379,412` · `IssueChangeVersionsServiceTest.kt:224-225,459-460`) 형태로 정정 |
| 🛑 **§8.6이 3연속 "전수" 자칭 + 매번 누락**(1→6→8건). 근본 원인 = grep이 **열거형 KDoc**(`RuleConflictAnalyzer.kt:315,328-330`)을 원리적으로 못 잡음 | 누락 8건 추가 + **grep 2종**(개수형/열거형) + *"목록의 완전성을 보증하지 못한다 — §8.2가 손대는 모든 함수의 KDoc을 직접 읽어라"* 명시 |
| ⚠️ **`TriggerType.TRANSITION`은 존재하지 않는다** — S1~S3이 phantom 값을 근거로 작성됨(learnings "phantom 엔티티"와 동종) | S2를 `ISSUE_UPDATED` + `fields=["status"]` + 조건으로 정정 |
| ⚠️ **`actionsToRequest`는 존재하지 않는 함수** — 내가 지어낸 이름(learnings "Claude의 환각") | 실명 `serializeActionsFormState:197-198`으로 정정 + **그 함수는 순수 매핑이라 에러 채널이 없음** → actions 전용 검증 기제 신설 요구 |
| ⚠️ **§8.3의 "ArchUnit이 차단"은 거짓** — `AutomationBcArchTest.kt:31`이 `DoNotIncludeTests()`로 테스트를 제외. 실제 차단은 **Gradle 클래스패스** | 근거 교체 + *"ArchUnit이 막는다고 믿으면 `testImplementation` 추가가 통과해버린다"* 명시 |
| ⚠️ **V302:45 `COMMENT ON COLUMN`이 "4종"** — 살아있는 DB 객체라 V306이 재발행 안 하면 운영 DB에 drift 영구 잔존 | §5 SQL에 `COMMENT ON COLUMN` 추가 + §8.6 체크리스트 |
| ✗ 라인 정정 | slack 스텁 KDoc `:28`→**`:27`** · `ProjectMemberSelect:110`→**`:109`** · `VersionMultiSelect:89-92`→**`:89-91`** |
| ✅ 2회차 지적 중 닫힌 것 | `undefined ≡ replace` **6조합 전수 검증 통과**(파괴적 조합 0) · §8.1 스텁 분리 정확 · EC13↔FR-7 모순 해소 · §8.2 13행 전부 정확(`:104`/`:256-272` 정정 맞음) |

### 3회차 (2026-07-16) — Phase B 2회차 반영 (개정본 자체의 결함)

> **교훈. B1을 닫은 그 자리에 같은 함정을 다시 팠다.** 2회차가 도입한 `fixVersionsMode?`가 optional인데 `undefined`
> 폴백을 규정하지 않아, `=== 'replace'` 가드가 열린 채 통과 → **B1과 정확히 같은 실패 형태**. 새 방어를 추가할 때
> 그 방어 자체의 미규정 상태가 원래 결함을 되살릴 수 있다.

| 지적 | 조치 |
|---|---|
| 🛑 **`fixVersionsMode` undefined 폴백 미규정** — 가드가 fail-open. B1 재현 | §2.2에 **`undefined ≡ replace`** + 가드 `mode !== 'clear'` + 직렬화 방향 명문화 |
| 🛑 **§8.6이 "전수"라면서 6건 누락**(C4 미종결) | `RuleConflictAnalyzer.kt:85` · `automation-rules.types.ts:287` · `AutomationRuleFormDialog.tsx:2,576` · `automation-rules.types.test.ts:272` · `SchemaMigrationTest.kt:651` 추가 + 착수 전 grep 재확인 지시 |
| ⚠️ **§8.1 "fail-closed 기존 패턴"이 automation에 거짓** — 두 스텁이 정반대. 그대로 하면 기존 슬라이스 테스트 파괴 | automation = **fail-safe**(KDoc `:23-26`) / slack = **fail-closed**(KDoc `:28`)로 분리 명시 |
| ⚠️ **EC13 ↔ FR-7 자기모순** — "저장 막기"는 선례 밖 + 룰 이름만 고치려는 사용자 차단(회귀) | "저장은 막지 않는다"로 철회. disabled shell만으로 사고 경로가 닫히는 이유 명시 |
| ⚠️ `fixVersionsMode`가 와이어 대응 없는 최초 필드 | KDoc 명시 요구를 §2.2·§8.6에 추가 |
| ❓ `clear` 모드의 위젯 상태 | **위젯 숨김 + versionIds 폼 상태 보존** 명시 |
| ❓ `actionConfigJson`의 `List<UUID>` 기법 (이 파일에 ArrayNode 선례 없음) | §8.2에 `putArray` + UUID→문자열 명시 |
| ❓ S6 테스트 모듈 — NFR-2와 충돌 | **issue-tracking 모듈**(어댑터 테스트)로 확정. automation에 두면 ArchUnit이 차단 |
| ❓ S8 검증 위치 / FR-9 private 함수 | `actionsToRequest:198` 경계 + 위반 행 특정 / `analyze()` 경유 간접 단언으로 확정 |
| ✗ 라인 정정 | `AssignAction:105`→**`:104`** · `AutomationYamlCodec:257-270`→**`:256-272`** · `AutomationIssueMutationAdapter.kt:5`(import 행) 제거 |

### 2회차 (2026-07-16) — Phase B 적대적 검토 반영

| 지적 | 조치 |
|---|---|
| 🛑 **B1. §2.1의 인과가 뒤집힘** — `fromJson`은 저장 시점 호출이라 `'{}'` 스텁은 저장 거부됨. EC9와 자기모순. 진짜 위험은 `defaultConfigForType` → `{versionIds:[]}` → 파싱 통과 저장 | §2.2 전면 재작성 + **FR-6(명시적 의도 선택, Maxi 확정)** 신설 + §8.4에 `defaultConfigForType` 명시 분기 요구 |
| 🛑 **B2. `actionTriggers` 반환값 미규정** — 컴파일러는 분기만 강제. 침묵하면 구현자가 phantom edge 재발 | **§8.2 신설** — 12지점의 **값**을 전부 명시. `actionTriggers = false`를 코드 근거(`publish` 부재)와 함께 못박음 |
| ⚠️ C1. S6이 vacuous — 모든 실패가 `"FAILED"`로 수렴 | **§8.3 양성 단언** 신설 + 분류 세분화를 §9 후속으로 |
| ⚠️ C2. `useVersions` 로딩/에러 미규정 — options=[] 시 기존 칩 소실 → 오인 저장 | **FR-7 신설**(§2.3), `ProjectMemberSelect` 선례 명시 |
| ⚠️ C3. EC12(ARCHIVED)가 검증 불가 문장 | **EC11로 재작성 — "설정된다(통과)"로 단언** + 코드 근거 |
| ⚠️ C4. 전수 동기화 지점 대량 누락 | **§8.6 신설** — "3 메서드" 계열 · 프론트 KDoc · 테스트명 drift 추가 |
| ⚠️ C5. 무변경에도 version bump | **EC14로 기록** |
| ❓1. `MutationResult.version` 조달법 | **§4.1에 명시** (재조회 결과의 신 버전, 기존 2메서드와 동형) |
| ❓2. SchemaMigrationTest 갱신/추가 | **§8.5에 "기존 `:654`를 5종으로 갱신(테스트명 포함)"** 으로 확정 |
| ❓3. FR-6의 형태 | **Maxi 확정 — 2모드 라디오 + 교체 모드 빈 목록 저장 거부** |
| ❓4. `parseActionConfig` 방어 형태 | **§8.4에 배열/원소 타입 가드 명시** |
| ✅ 라인 인용 정확성 | `IssueMetaPanel.tsx:334-352` → **`:346-356`** 으로 정정(제약 5) |
