<!-- FR-AT-07 PR-B Fix Version 설정 통로 — 전용 포트 메서드·OCC 파라미터 부재·파급 범위 결정 -->

# ADR — FR-AT-07 PR-B. Fix Version 설정 통로 (`setFixVersions` 포트 + `SET_FIX_VERSIONS` 액션)

> 날짜. 2026-07-16 | PR. #276 | BC. shared-kernel · issue-tracking · automation · slack-integration(스텁) · apps/web | 상태. 채택

## 맥락

FR-AT-07(PR 머지 연동 — Fix Version 자동 설정)은 DEC-11(Maxi 확정)에 따라 3분할됐다.
PR-A(#274, 인바운드 웹훅 prod 도달 가능화 — 머지 완료) → **PR-B(이 ADR)** → PR-C(PR_MERGED 트리거 + Git webhook).

PR-C가 "PR이 머지되면 이슈의 Fix Version을 자동 설정한다"를 완성하려면 그 **설정 통로가 먼저 존재해야 한다**.
현재 automation BC에는 Fix Version을 건드릴 수단이 없다 — 마스터 스펙 §F3이 진단한 구조적 사실이며, 본 PR 착수 시점에
코드로 재확인했다.

### 접지 — 코드가 말하는 사실 (전수 대조, 2026-07-16)

마스터 스펙 §B가 인용한 13개 라인은 **오차 0으로 현재 코드와 일치**한다. 그 위에서 확인된 사실.

| 사실 | 근거 |
|---|---|
| `IssueMutationPort`는 3 메서드(`setField`/`assign`/`addComment`), default 구현 없음 | `IssueMutationPort.kt:61-104` |
| 기존 커맨드에 **OCC 파라미터가 애초에 없다** | `IssueMutationCommands.kt:34-40`(SetFieldCommand), `:55-60`(AssignCommand) |
| 어댑터가 매 시도마다 자기 트랜잭션에서 `findByKey().version`을 재조회해 채운다 | `AutomationIssueMutationAdapter.kt:100-105, 118-124` |
| OCC 충돌은 `runWithOccRetry`가 1회 재시도 (각 시도 = 독립 물리 트랜잭션) | 같은 파일 `:162-172`, `:178-195` |
| `ActionExecutor`가 보는 `IssueSnapshot`은 **9필드이고 `version`이 없다** | `IssueSnapshot.kt:47-57` |
| `changeFixVersions` 유스케이스는 **이미 존재**한다 | `IssueApplicationService.kt:944-969` |
| 그 요청 DTO는 `AppChangeVersionsRequest(versionIds, expectedVersion)` | `IssueApplicationRequests.kt:219-222` |
| 타 프로젝트/부재 버전은 `validateVersions`가 차단 | `IssueApplicationService.kt:1636-1643` — `versionRepository.findById(id, projectId)` 실패 시 `IssueLinkedVersionNotFoundException` |
| 빈 목록 = 전체 해제가 **분기 없이 성립** | `Issue.kt:295` — `assignFixVersions(ids) = copy(fixVersionIds = ids.filterNotNull().distinct())` |

## 결정

### D1. Fix Version 통로 = **전용 포트 메서드 `setFixVersions`** (마스터 스펙 D3, Maxi 확정 승계)

`setField(field="fixVersions", value="[...]")`로 기존 메서드에 얹지 않는다.

**정당한 근거 2가지.**

1. **전체교체 시맨틱 + 복수 versionId를 타입으로 드러낸다.** `SetFieldCommand.value: String?`은 "필드 하나에 값 하나"
   계약이다. 여기에 JSON 배열을 문자열로 인코딩해 숨기면 그 계약이 깨지고, 호출자는 타입만 보고 "리스트를 통째로
   교체한다"는 사실을 알 수 없다.
2. **구조적 사실** — fixVersions는 `updateIssue`가 아니라 **별도 서비스 메서드**(`changeFixVersions`) 경로다(§F3).
   `setField`의 `buildUpdateRequest`(`:213-240`)는 `UpdateIssueRequest`를 만드는 함수이므로 애초에 이 경로를 태울 수 없다.

**기각된 근거(기록).** 1회차 스펙의 *"setField에 리스트를 숨기면 OCC 파라미터가 사라진다"* 는 **코드로 반증됐다** —
기존 커맨드엔 OCC 파라미터가 애초에 없다(위 접지 표). 결론은 유지하되 근거는 교체한다.

### D2. `SetFixVersionsCommand`에 **`expectedVersion`을 두지 않는다** (마스터 스펙 §B-2, BLOCKER B6 해소)

```kotlin
data class SetFixVersionsCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val versionIds: List<UUID>,   // 전체교체 시맨틱. 빈 목록 = 전체 해제
    val dryRun: Boolean,
)
```

**근거.** 호출자(`ActionExecutor`)는 `IssueSnapshot`(9필드, `version` 없음)에서 값을 얻으므로 **유효한
`expectedVersion`을 조달할 경로가 아예 없다**. 필드를 두면 항상 null인 죽은 분기가 되고, 훗날 "값이 있으니 재조회를
생략하자"는 최적화가 들어오는 순간 **진짜 TOCTOU**(검사 시점과 사용 시점 사이에 값이 바뀌는 경쟁 조건)가 된다.
OCC는 어댑터가 자기 트랜잭션 안에서 재조회해 채우고 `runWithOccRetry`가 재시도한다 — `setField`/`assign`과 **동형**.
호출자는 OCC를 알 필요가 없다.

`changeFixVersions`가 `AppChangeVersionsRequest.expectedVersion`을 **요구한다는 점은 이 결정과 충돌하지 않는다** —
어댑터가 `findByKey().version`으로 채운다(기존 두 메서드와 정확히 같은 패턴).

### D3. 파급 범위 = **automation 13지점 + 스펙 미기재 5종** (본 PR에서 신규 확정)

마스터 스펙 §B-3의 "6파일 ~13지점"은 **automation 모듈 내부만 센 수치**다. 전수 조사 결과 그 밖에 아래가 파급된다.
이 중 4종은 **컴파일이 깨지므로 선택의 여지가 없다**(누락 시 빌드 실패).

| 지점 | 성격 | 컴파일 강제 |
|---|---|---|
| `shared-kernel/IssueMutationPortContractTest.kt:24-37` | 익명 객체가 새 추상 메서드 미구현 | **깨짐** |
| `slack-integration/StubIssueMutationPort.kt` | **스펙이 slack-integration을 범위 밖으로 뒀으나**, `SlackInteractionService.kt:91`이 포트를 소비해 스텁이 존재 | **깨짐** |
| `automation/StubIssueMutationPort.kt` | 같은 이유 | **깨짐** |
| `apps/web` 라벨맵 2곳 (`ActionConfigEditor.tsx:48-53`, `AutomationRuleList.tsx:62-67`) | `Record<ActionType, string>`은 전 멤버 키를 요구 | **깨짐 (TS2741)** |
| `apps/web` `parseActionConfig:342-364` / `serializeActionConfig:378-397` | `default:` 없는 switch + `strict: true` | **깨짐 (TS2366)** |
| `SchemaMigrationTest.kt:654-658` | V302 CHECK 4종 하드코딩 | 안 깨짐 — V306 짝 테스트 필요 |
| `ActionTest.kt:18,22` | `entries.size shouldBe 4` | 안 깨짐(테스트 실패로 표면화) |
| `automation-rules.types.test.ts:273` | `validTypes` 배열 | **안 깨짐 — 조용히 미검증** |
| KDoc "4종" 4곳 | 문서 drift | 안 깨짐 |

**★ `RuleConflictAnalyzer.hasObservableSideEffect:317-319`는 컴파일러가 강제하지 않는다.** 그 지점만
`it is Action.SetFieldAction || it is Action.AssignAction || it is Action.AddCommentAction` **boolean 체인**이라
새 서브타입을 빠뜨려도 컴파일이 통과하고, 런타임에도 예외 없이 `false`로 흡수돼 **PRIORITY_AMBIGUITY 충돌 탐지가
조용히 `SET_FIX_VERSIONS`를 무시**한다. 나머지 12지점은 `else` 없는 exhaustive `when`이라 컴파일러가 잡아준다.
→ **회귀 테스트 필수**. [[archunit-vacuous-rule-silent-pass]]와 동종 — "통과했다"가 "검증했다"를 의미하지 않는다.

### D4. BC 격리 예외 — 5개 영역을 한 PR에서 건드린다

CLAUDE.md §핵심 패턴의 "한 PR = 한 BC" 원칙에 대한 **명시적 예외**로 선언한다.

**사유.** 포트 신설은 **정의상 BC 경계를 가로지른다** — shared-kernel에 계약을 선언하고, issue-tracking이 구현하고,
automation이 소비하는 구조 자체가 3개 모듈을 동시에 요구한다(포트 KDoc `:44-54` "의존 방향" 절이 이 구조를 명시).
포트는 **default 구현이 금지**돼 있으므로(fail-closed, 아래 D5) 메서드 추가는 모든 구현체를 같은 커밋에서 갱신해야
컴파일이 성립한다. 쪼개면 중간 PR이 **컴파일 불가** 상태가 되어 main이 깨진다.

slack-integration은 **의도한 확장이 아니라 강제된 파급**이다 — 스텁 1개 파일에 미사용 메서드 구현을 추가할 뿐,
slack의 기능/동작은 변하지 않는다.

선례. PR #13 옵션 C(frontend PR이 same-BC backend view layer를 함께 수정), FR-AT-02 PR #256(포트 최초 도입 시
shared-kernel + issue-tracking + automation 동시 변경).

### D5. 포트의 default 구현 금지 원칙을 **유지**한다 (재확인)

`setFixVersions`에 `default` 구현을 주면 slack/automation 스텁과 계약 테스트가 안 깨져 파급이 줄어든다.
**그럼에도 주지 않는다.**

**근거.** `IssueMutationPort` KDoc `:11-16`이 선언한 fail-closed 계약 — *"권한을 강제하는 쓰기 경로이므로 adapter
부재 시 부팅 자체가 실패해야 한다. 빈 default 구현을 허용하면 adapter 미결선 상태에서 자동화 액션이 silent-drop 될
위험이 있다 — 규칙 사용자는 '액션이 실행됐다'고 오인하지만 실제로는 아무 일도 일어나지 않는 사고로 이어진다."*
Fix Version 자동 설정이 조용히 실패하면 릴리스 노트가 틀린다. 컴파일 에러 4건은 **비용이 아니라 이 계약이 작동한다는 증거**다.

일반 원칙([[interface-extension-default-method]] — 공유 인터페이스 메서드 추가는 default가 fail-safe)의 **의도적 예외**이며,
그 예외 사유가 포트 KDoc에 이미 성문화돼 있다.

### D6. 마이그레이션 = **신규 V306** (V302 편집 금지)

`automation_actions.action_type` CHECK 제약(`V302__automation_actions.sql`)에 `SET_FIX_VERSIONS`를 추가한다.
**V302를 편집하지 않는다** — 이미 적용된 마이그레이션 편집은 Flyway 체크섬 드리프트를 일으킨다
([[app-test-persistent-db-migration-checksum-trap]] — `:modules:app:test`는 5433 영속 DB를 쓴다).

automation BC의 V번호 예약 구간은 V300~V399(DATA.md §4 BC별 100단위)이고 현재 최신은 **V305**  → 신규 **V306**.
PR-C가 V306~V309를 쓸 것으로 스펙 §C-7이 예고했으므로, PR-C 착수 시 번호를 한 칸 밀어야 한다(이 ADR이 V306을 선점).
머지 직전 V번호 재확인 필수([[migration-vnumber-concurrent-branch-collision]]).

## 결과

**얻는 것.** PR-C가 소비할 Fix Version 설정 통로. 사용자는 이 PR만으로도 "이슈가 X 릴리스에서 고쳐진다"를 자동화 룰로
설정할 수 있다(트리거는 기존 5종 사용).

**감수하는 것.**
- 5개 영역 동시 변경(D4) — BC 격리 예외를 명시적으로 선언해 상쇄.
- `hasObservableSideEffect`의 boolean 체인은 이 PR에서도 **구조적으로 고치지 않는다**(exhaustive when으로 리팩토링하면
  폭발 반경이 커짐). 회귀 테스트로 방어하되, 6번째 액션 타입이 추가될 때 같은 함정이 재발한다 → **후속 후보**로 기록.

**후속 (이 PR 범위 밖).**
- `hasObservableSideEffect`를 exhaustive `when`으로 전환해 컴파일러가 강제하게 만들기(구조적 함정 제거).
- `automation-rules.types.test.ts:273`의 `validTypes` 배열이 조용히 미검증되는 구조 — Zod enum에서 파생시키면 drift 차단
  ([[fixture-옵션-B-패턴]] 동종 — mirror data가 helper를 호출하게).
- FR-AT-04 UI의 status/type 드롭다운 확장(기존 후속과 동일 묶음).

## 관련

- 마스터 스펙. `docs/specs/2026-07-15-fr-at-07-pr-merge.md` §B
- plan. `docs/plans/2026-07-16-fr-at-07-pr-b-fix-version.md`
- 포트 정본 ADR. `docs/decisions/2026-07-11-fr-at-02-automation-actions.md` (D1 액션 도메인 모델)
- 선행. `docs/decisions/2026-07-15-slack-inbound-permitall-central.md` (PR-A)
- SDD. 08장 §8.4 (액션 종류)
