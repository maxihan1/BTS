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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
