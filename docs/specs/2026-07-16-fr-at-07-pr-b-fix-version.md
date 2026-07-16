<!-- FR-AT-07 PR-B 스펙 — setFixVersions 포트·SET_FIX_VERSIONS 액션·설정 UI. 마스터 스펙 §B 상세화 -->

# FR-AT-07 PR-B — Fix Version 설정 통로 · 스펙

> 날짜. 2026-07-16 | PR. #276 | 상위. [마스터 스펙 §B](2026-07-15-fr-at-07-pr-merge.md) | ADR. [2026-07-16-fr-at-07-pr-b-fix-version-port](../decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md)

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

**이 PR만으로도 사용자 가치가 성립한다** — 기존 트리거 5종(ISSUE_CREATED/ISSUE_UPDATED/TRANSITION/SCHEDULE/WEBHOOK)으로
"이슈가 Done으로 전이되면 Fix Version을 1.2.0으로 설정" 같은 룰을 만들 수 있다.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 룰 편집 화면에서 Fix Version 액션 만들기 (해피 패스)

```
Given 프로젝트 ALPHA에 버전 "1.2.0"(UNRELEASED)이 있고
  And  나는 ALPHA의 자동화 룰을 관리할 수 있다
When  프로젝트 설정 → 자동화 → 룰 추가 → 액션 타입에서 "수정 예정 버전 설정"을 고르면
Then  그 아래에 프로젝트 버전 다중 선택 위젯이 나타난다
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
  And  이슈 히스토리에 변경이 룰 actor 명의로 기록된다
```

### S3. 전체교체 시맨틱 — 기존 값을 덮는다

```
Given 이슈 ALPHA-7의 Fix Version이 [1.0.0]이고
  And  룰의 액션이 SET_FIX_VERSIONS [1.2.0] 이다
When  룰이 실행되면
Then  ALPHA-7의 Fix Version은 [1.2.0] 이다  ← [1.0.0, 1.2.0]이 아니다 (추가가 아니라 교체)
```

### S4. 빈 목록 = 전체 해제

```
Given 이슈 ALPHA-7의 Fix Version이 [1.0.0]이고
  And  룰의 액션이 SET_FIX_VERSIONS [] (아무 버전도 선택 안 함) 이다
When  룰이 실행되면
Then  ALPHA-7의 Fix Version이 비워진다
```

> 이 시맨틱은 **의도된 기능**이다(마스터 스펙 FR-B3 "빈 배열 = 전체 해제"). 다만 사용자가 실수로 빈 채 저장하면
> 파괴적이므로 **UI가 이 사실을 알린다**(FR-6).

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
Then  Fix Version이 바뀌지 않고 rule_executions에 FAILED로 기록된다
```

### S7. YAML GitOps 왕복

```
Given SET_FIX_VERSIONS 액션을 가진 룰이 있다
When  프로젝트 자동화 룰을 YAML로 내보내고 다시 가져오면
Then  액션 타입과 versionIds가 손실 없이 복원된다
```

## 2. 기능 요구사항 (FR)

마스터 스펙 §B-1의 FR-B1~B4를 승계하고, 본 상세화에서 FR-5~FR-8을 추가한다.

| ID | 요구사항 | 근거 |
|---|---|---|
| **FR-1** (§B FR-B1) | `IssueMutationPort.setFixVersions(SetFixVersionsCommand): MutationResult` — **default 구현 없음** | ADR D1·D5 |
| **FR-2** (§B FR-B2) | `AutomationIssueMutationAdapter`가 `IssueApplicationService.changeFixVersions`에 위임. OCC는 어댑터가 `findByKey().version` 재조회로 채우고 `runWithOccRetry` 1회 재시도 | ADR D2 |
| **FR-3** (§B FR-B3) | `ActionType.SET_FIX_VERSIONS` + `Action.SetFixVersionsAction(versionIds: List<UUID>)` sealed subclass. config = `{"versionIds": ["uuid", ...]}` | ADR D1 |
| **FR-4** (§B FR-B4) | 프론트 계약 동기화 — `actionTypeSchema` z.enum + 라벨맵 2곳 + `parse/serializeActionConfig` + `ActionConfigFormState.versionIds` | ADR D3 |
| **FR-5** | **`SET_FIX_VERSIONS` 설정 UI** — `ActionConfigEditor`에 버전 다중 선택 위젯. 기존 `useVersions(projectKey)` + `VersionMultiSelect variant="fix"` 재사용 | **Maxi 확정(아래 §2.1)** |
| **FR-6** | 설정 UI가 **빈 선택 = 전체 해제**임을 사용자에게 알린다 | S4 파괴성 |
| **FR-7** | `automation_actions.action_type` CHECK에 `SET_FIX_VERSIONS` 추가 — **신규 V306** | ADR D6 |
| **FR-8** | **조용한 실패 2곳에 회귀 테스트** — `hasObservableSideEffect`(boolean 체인) · `validTypes` 배열 | ADR D3 ★ |

### 2.1. FR-5의 근거 — "계약만"이 왜 안 되나 (Maxi 확정)

마스터 스펙 FR-B4는 "계약 동기화 + 라벨 맵"까지만 규정했다. 그러나 코드를 보면 그것만으로는 **깨진 제품**이 된다.

액션 타입 드롭다운은 하드코딩이 아니라 **`actionTypeSchema.options`에서 자동 파생**된다.

```tsx
// ActionConfigEditor.tsx:571-583
{actionTypeSchema.options.map((type) => (
  <option key={type} value={type}>{ACTION_TYPE_LABELS[type]}</option>
))}
```

→ z.enum에 값을 넣는 순간 **사용자 드롭다운에 자동 등장**한다. 그런데 config 입력 폼은 `default` 없는 조건부 렌더
4개뿐(`:586-599`)이라, 사용자가 고르면 **입력 영역이 빈 채로 남는다**.

**그리고 그 상태로 저장하면 파괴적이다.** `serializeActionConfig`를 컴파일만 통과시키려 `return '{}'` 스텁으로
채우면 `versionIds`가 빈 배열이 되고 — 빈 배열은 **전체 해제**(S4)다. 즉 *Fix Version을 설정하려고 만든 룰이
기존 Fix Version을 전부 지운다.*

→ **Maxi 확정. 설정 UI를 이 PR에 포함한다.** 재사용 자산이 이미 전부 존재해 비용이 작다 —
`useVersions(projectKey)`(`hooks/use-versions.ts:83-89`) · `VersionMultiSelect`(`components/issue/VersionMultiSelect.tsx`,
`variant`/`value`/`options`/`onChange`/`disabled`만 받는 순수 표현 컴포넌트) · `ActionListEditor:157`이 이미
`projectKey`를 `ActionConfigEditor`에 전달 중.

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 | 검증 |
|---|---|---|
| NFR-1 | **권한 우회 0** — 룰 actor의 UPDATE 권한이 없으면 반드시 차단. 어댑터는 도메인 유스케이스에 위임만 하고 자체 권한 검증을 하지 않는다(위임 대상이 이미 fail-closed) | S5 통합 테스트 |
| NFR-2 | **BC 격리 유지** — automation은 issue-tracking 타입을 import하지 않는다. 커맨드/결과는 shared-kernel 타입(`UUID`/`List<UUID>`)만 | ArchUnit 기존 룰 |
| NFR-3 | **shared-kernel Jackson 비의존** — `SetFixVersionsCommand`는 `List<UUID>`(BC 공통 분모). JSON 파싱은 automation의 `Action.fromJson`이 수행 | `IssueMutationCommands.kt` KDoc 원칙 |
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

### 4.2. 액션 config (automation) — 신규

```yaml
# YAML GitOps 표현
- type: SET_FIX_VERSIONS
  config:
    versionIds:
      - "3f2b...-...."
      - "8a1c...-...."
```

```json
{ "versionIds": ["3f2b-...", "8a1c-..."] }
```

- `versionIds` **필수 키**. 부재/타입 불일치는 파싱 실패(기존 `Action.fromJson` 계약과 동형)
- 빈 배열 **허용**(= 전체 해제, S4)
- **개수 상한 없음** — 기존 REST 경로(`ChangeVersionsRequest.versionIds: List<UUID> = emptyList()`)가 상한을 두지
  않으므로 새 상한을 발명하지 않는다. 룰 config는 프로젝트 관리자가 작성하는 신뢰 입력이고, 상한을 두면 두 경로가
  갈라진다

### 4.3. REST — **신규 엔드포인트 없음**

자동화 룰 API(`POST/PATCH /api/v1/projects/{key}/automation/rules`)는 액션을 `{type, config}` 제네릭 형태로 받으므로
**엔드포인트 변경이 없다**. `type` 문자열이 `ActionType.valueOf`로 파싱되고(`AutomationRuleService.kt:719`), 새 enum 값이
추가되면 자동으로 통과한다.

## 5. 데이터 모델 변경

**신규 `V306__automation_actions_set_fix_versions.sql`** — `automation_actions.action_type` CHECK 재정의.

```sql
ALTER TABLE automation_actions DROP CONSTRAINT ck_automation_actions_action_type;
ALTER TABLE automation_actions ADD CONSTRAINT ck_automation_actions_action_type
    CHECK (action_type IN ('SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS'));
```

- **V302 편집 금지** — 이미 적용된 마이그레이션 편집은 Flyway 체크섬 드리프트(`:modules:app:test`는 5433 영속 DB)
- automation 예약 구간 V300~V399, 현재 최신 V305 → **V306**. 머지 직전 V번호 재확인 필수
- 테이블/컬럼 추가 없음. `action_config`는 기존 JSONB 그대로

## 6. 엣지 케이스

| ID | 상황 | 기대 동작 | 방어 위치 |
|---|---|---|---|
| EC1 | `versionIds` 빈 배열 | **전체 해제**(정상 동작, 에러 아님) | 도메인 `assignFixVersions` — 분기 불필요 |
| EC2 | 중복 UUID (`[A, A, B]`) | 중복 제거 → `[A, B]`. 에러 아님 | `Issue.kt:295` `.distinct()` |
| EC3 | 타 프로젝트 버전 UUID | 실패. `IssueLinkedVersionNotFoundException` **타입 그대로 전파** → `rule_executions` FAILED | `validateVersions` — `findById(id, projectId)` 프로젝트 스코프 |
| EC4 | 존재하지 않는/삭제된 버전 UUID | EC3과 동일 경로 | 같음 |
| EC5 | 룰 actor에 UPDATE 권한 없음 | `IssueAccessDeniedException` → 어댑터가 `IssueMutationPermissionDeniedException`으로 번역 → FAILED | `runAttempt:186-190` 기존 경로 |
| EC6 | 이슈 부재/소프트 삭제 | `IssueNotFoundException` → FAILED | `changeFixVersions:950` |
| EC7 | OCC 충돌 | 1회 재시도. 재실패 시 `IssueVersionConflictException` → FAILED | `runWithOccRetry:162-172` |
| EC8 | `dryRun=true` | 권한·버전 검증은 실제로 수행하되 커밋/이벤트 미발행. `applied=false`, `version=null` | `runAttempt`의 `setRollbackOnly()` |
| EC9 | config에 `versionIds` 키 부재 | 파싱 실패 → 룰 저장 시점에 거부 | `Action.fromJson` |
| EC10 | UUID 형식이 아닌 문자열 | 파싱 실패 | 같음 |
| EC11 | UI에서 아무 버전도 안 고르고 저장 | **저장은 허용**(EC1이 정상 기능). 단 FR-6에 따라 UI가 "전체 해제" 의미를 알림 | 프론트 |
| EC12 | ARCHIVED 버전 | `VersionMultiSelect`가 기본 숨김(이미 선택된 것은 표시). 백엔드는 `validateVersions` 통과 여부를 따름 — **새 정책을 만들지 않고 기존 이슈 편집 경로와 동일하게 둔다** | 기존 컴포넌트 |

> **★ "422" 표기 금지** (마스터 스펙 §B-4 / 부록 A C-i). 422는 `IssueController` 동기 REST 매핑 전용이다.
> automation은 pgmq 워커에서 **비동기** 실행되므로 HTTP 응답 자체가 없다 — 실패는 `rule_executions`의 FAILED로만 관측된다.

## 7. 제약

1. **PR-C 범위 침범 금지** — `TriggerType.PR_MERGED`·Git webhook·`automation` permitAll·`BTS_AUTOMATION_ENCRYPTION_KEY`는
   이 PR에 넣지 않는다.
2. **V302 편집 금지** (§5).
3. **포트 default 구현 금지** (ADR D5) — 컴파일 에러 4건은 이 계약이 작동한다는 증거다.
4. **`hasObservableSideEffect`를 exhaustive `when`으로 리팩토링하지 않는다** — 폭발 반경이 큰 구조 변경. 이 PR은
   회귀 테스트로 방어하고 구조 개선은 후속(ADR §후속).
5. `VersionMultiSelect`를 **수정하지 않는다** — `variant="fix"`가 이미 "수정 버전" 문구를 제공한다. 수정하면
   이슈 상세 화면(`IssueMetaPanel:334-352`)에 회귀 위험.
6. FR 총수 **123 불변** (D단계 작업, 신규 FR 아님).

## 8. 측정 가능한 완료 기준

### 백엔드
- [ ] `SetFixVersionsCommand` + `IssueMutationPort.setFixVersions` (default 없음)
- [ ] `IssueMutationPortContractTest`가 **4 메서드**를 검증하도록 갱신 (테스트명 포함)
- [ ] `AutomationIssueMutationAdapter.setFixVersions` — `changeFixVersions` 위임 + OCC 재조회 + `runWithOccRetry`
- [ ] `ActionType.SET_FIX_VERSIONS` + `Action.SetFixVersionsAction` + `fromJson` 파서
- [ ] exhaustive `when` **12지점** 갱신 (컴파일러가 강제 — 누락 시 빌드 실패)
- [ ] **`hasObservableSideEffect:317-319`에 `SetFixVersionsAction` 추가 + 회귀 테스트** ★ 컴파일러 미강제
- [ ] `RuleConflictAnalyzer.requiredPermission` → `IssuePermission.UPDATE` (SET_FIELD/ASSIGN과 동형)
- [ ] `automation/StubIssueMutationPort` + **`slack-integration/StubIssueMutationPort`** 갱신
- [ ] `ActionTest.kt` `entries.size` 4 → 5 + 목록
- [ ] V306 마이그레이션 + `SchemaMigrationTest` 짝 테스트(5종 INSERT 허용 + 미지의 값 거부)
- [ ] KDoc "4종" → "5종" 4곳
- [ ] S2·S3·S4·S5·S6 통합 테스트 (실 DB, Testcontainers)
- [ ] S7 YAML 왕복 테스트

### 프론트
- [ ] `actionTypeSchema` z.enum + `ActionConfigFormState.versionIds`
- [ ] `parseActionConfig` / `serializeActionConfig` — `versionIds` **실제 왕복**(빈 스텁 금지 ★)
- [ ] 라벨맵 2곳 (`ActionConfigEditor:48-53` 드롭다운 / `AutomationRuleList:62-67` 배지)
- [ ] `SetFixVersionsFields` — `useVersions` + `VersionMultiSelect variant="fix"` 재사용
- [ ] FR-6 — 빈 선택 = 전체 해제 안내 문구
- [ ] **`validTypes` 배열(`automation-rules.types.test.ts:273`) 갱신** ★ 컴파일러 미강제
- [ ] `pnpm typecheck` 0 (tsconfig.app.json 기준) · `pnpm lint` 0 · 유닛 회귀 0

### 검증 방식 (★ false-green 방지)
- 회귀는 **태스크를 따로 invoke**하고 `build/test-results/test/TEST-*.xml`로 **실행된 테스트 수를 실측**해 대조한다.
  묶음 태스크 + `--rerun-tasks`가 일부만 돌고 BUILD SUCCESSFUL을 낸 전례가 있다.
- 쉘 종료 코드는 `set -o pipefail` + `$?`로 확인한다(zsh `${PIPESTATUS[0]}`는 항상 빈 문자열 → 눈으로 훑으면
  "에러 아님"처럼 보인다).
- **FR-8의 두 회귀 테스트는 반드시 "일부러 위반을 넣어 fail을 확인"한 뒤 통과시킨다** — vacuous 통과 방지.

## 9. 이 PR이 만들지 않는 것 (PR-C / 후속)

- `TriggerType.PR_MERGED`, `POST /api/v1/webhooks/git`, 서명 검증, targetBranch 필터 → **PR-C**
- `hasObservableSideEffect` 구조 개선(exhaustive when 전환) → 후속
- `validTypes` 배열을 Zod enum에서 파생시켜 drift를 구조적으로 차단 → 후속
- FR-AT-04 UI의 status/type 드롭다운 확장 → 기존 후속 묶음
