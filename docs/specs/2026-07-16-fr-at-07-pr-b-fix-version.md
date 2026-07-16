<!-- FR-AT-07 PR-B 스펙 — setFixVersions 포트·SET_FIX_VERSIONS 액션·설정 UI. 마스터 스펙 §B 상세화 -->

# FR-AT-07 PR-B — Fix Version 설정 통로 · 스펙

> 날짜. 2026-07-16 | PR. #276 | 상위. [마스터 스펙 §B](2026-07-15-fr-at-07-pr-merge.md) | ADR. [2026-07-16-fr-at-07-pr-b-fix-version-port](../decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md)
>
> **개정 2회차 (2026-07-16)** — Phase B 적대적 검토가 BLOCKER 2 / CONCERN 5 / 모호 4를 발견해 전면 개정. 변경 내역은 §10.

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

```
Given 위 룰(트리거 = TRANSITION to Done, 액션 = SET_FIX_VERSIONS [1.2.0])이 활성이고
  And  이슈 ALPHA-7의 Fix Version이 비어 있다
When  ALPHA-7이 Done으로 전이되면
Then  ALPHA-7의 Fix Version이 [1.2.0]이 된다
  And  rule_executions에 SUCCESS 실행 이력이 남는다
  And  이슈 히스토리에 "fixVersions" 변경이 룰 actor 명의로 기록된다
```

### S3. 전체교체 시맨틱 — 기존 값을 덮는다

```
Given 이슈 ALPHA-7의 Fix Version이 [1.0.0]이고
  And  룰의 액션이 SET_FIX_VERSIONS [1.2.0] 이다
When  룰이 실행되면
Then  ALPHA-7의 Fix Version은 [1.2.0] 이다  ← [1.0.0, 1.2.0]이 아니다 (추가가 아니라 교체)
```

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

### 2.3. FR-7의 근거 — 로딩/에러가 곧 사고 경로다

`useVersions`(`hooks/use-versions.ts:83-89`)는 `useQuery`를 그대로 반환하므로 로딩/에러 시 `data`가 `undefined`다.
`options={versions ?? []}`로 때우면 —

```tsx
// VersionMultiSelect.tsx:89-92
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
```

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
| EC13 | UI: `useVersions` 로딩/에러 | disabled shell + 문구. **저장 가능 상태로 두지 않는다** | 프론트 FR-7 |
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
- [ ] `automation/StubIssueMutationPort` + **`slack-integration/StubIssueMutationPort`** 갱신 (settable + fail-closed 기존 패턴)
- [ ] V306 마이그레이션

### 8.2. ★ 12지점의 **값**을 명시한다 (컴파일러는 분기만 강제)

| 파일:라인 | 함수 | `SetFixVersionsAction`의 값 | 근거 |
|---|---|---|---|
| `Action.kt:86-91` | `fromJson` | `parseSetFixVersions(node)` | FR-3 |
| `RuleConflictAnalyzer.kt:103-106` | `actionTriggers` | **`false`** ★ | `changeFixVersions:944-969`에 **`eventPublisher.publish`가 없다**(대조군 `updateIssue:544`엔 있음) → ISSUE_UPDATED 트리거를 유발할 경로가 **런타임에 존재하지 않는다**. `AssignAction:105 -> false`와 동형. **`triggersIssueUpdated(target, "fixVersions")`를 쓰면 안 된다** — `RuleConflictAnalyzer.kt:88-97` KDoc이 성문화한 *phantom edge* 사고(AssignAction에서 이미 한 번 겪음)의 재발이다 |
| `RuleConflictAnalyzer.kt:317-319` | `hasObservableSideEffect` | **포함(`|| it is Action.SetFixVersionsAction`)** ★ boolean 체인 — 컴파일러 미강제 | 이슈를 실제로 바꾸는 액션 |
| `RuleConflictAnalyzer.kt:414-417` | `requiredPermission` | `IssuePermission.UPDATE` | `changeFixVersions:949`가 `assertPermission(UPDATE)` |
| `RuleConflictAnalyzer.kt:426-429` | `actionKindLabel` | `"수정 예정 버전 설정"` | 라벨맵과 문구 일치 |
| `ActionExecutor.kt:199-213` | `dispatchAction` | `issueMutationPort.setFixVersions(SetFixVersionsCommand(...))` | FR-2 |
| `ActionExecutor.kt:289-292` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationActionRepository.kt:113-116` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationActionRepository.kt:130-152` | `actionConfigJson` | `{"versionIds": [...]}` — **`fromJson`의 정확한 역함수** ★ | `:122-124` KDoc — *"여기서 어긋나면 `findByRuleId` 역직렬화가 실패한다"* → 그 룰의 **모든** 액션이 로드 불가(poison) |
| `AutomationRuleResponses.kt:190-193` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationRuleResponses.kt:197-209` | `actionConfigOf` | `mapOf("versionIds" to ...)` | 프론트 `parseActionConfig` 입력 |
| `AutomationYamlCodec.kt:245-248` | `actionTypeOf` | `ActionType.SET_FIX_VERSIONS` | — |
| `AutomationYamlCodec.kt:257-270` | `actionConfigMap` | `mapOf("versionIds" to ...)` | S7 왕복 |

### 8.3. ★ 양성 단언 — "FAILED면 통과"는 vacuous다

`ActionExecutor.classifyPortFailure:256-261`은 **권한 거부만 타입으로 분류**하고 나머지(EC3·EC4·EC6·EC7)를 전부
`FAILURE_GENERIC = "FAILED"`(`:318`)로 수렴시킨다. 따라서 S6 완료 기준을 *"rule_executions에 FAILED로 기록"* 으로만
두면 **`validateVersions`를 통째로 지워도 통과한다**(다른 이유로 실패하기만 하면 되니까).

→ **S6 테스트는 "타 프로젝트 versionId 때문에 실패했다"를 양성 단언한다** — 어댑터/서비스 경계에서
`IssueLinkedVersionNotFoundException`이 **그 versionId를 담고** 던져지는지 직접 단언하고, 이슈의 fixVersions가
**변경되지 않았음**을 함께 확인한다. (마스터 스펙 §A-5가 PR-A에 요구한 *"'401이 아님' 폐기"* 규율의 PR-B 적용.)

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
- [ ] S2·S3·S4·S5 통합 테스트 (실 DB, Testcontainers)
- [ ] **S6 양성 단언** (§8.3)
- [ ] S7 YAML 왕복
- [ ] S8 + EC13 프론트 단위 테스트
- [ ] **FR-9 회귀 3종** — `hasObservableSideEffect` 포함 · `actionTriggers == false` · `validTypes` 5종
- [ ] `ActionTest.kt` `entries.size` 4 → 5 + 목록
- [ ] `SchemaMigrationTest` — 기존 `V302 유효한 action_type 4종은 INSERT 허용`(`:654`)은 **5종으로 갱신**(테스트명 포함, 방치하면 거짓 이름) + SET_FIX_VERSIONS INSERT 허용 + 미지의 값 거부

### 8.6. 전수 동기화 (CLAUDE.md §명세/범위 변경 시 전수 동기화)
- [ ] "3 메서드/세 메서드" 계열 — `IssueMutationPort.kt:5,28` · `IssueMutationCommands.kt:84` · `IssueMutationPortContractTest.kt:14,25` · `slack/StubIssueMutationPort.kt:28` · `AutomationIssueMutationAdapter.kt:5,29`
- [ ] "4종" → "5종" — `ActionType.kt:1` · `AutomationRuleRequests.kt:75` · `AutomationRuleService.kt:646` · `AutomationRulesYaml.kt:73`
- [ ] 프론트 KDoc — `automation-rules.types.ts:22` · `ActionConfigEditor.tsx:47,540` · `AutomationRuleList.tsx:61` · `automation-rules.types.test.ts:268`
- [ ] 테스트명 drift — `ActionConfigEditor.test.tsx:41` · `ActionExecutorTest.kt:288` · `AutomationActionRepositoryTest.kt:134` · `ActionExecutionEndToEndIntegrationTest.kt:229` · `ActionTest.kt:1,16,17,257`
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
