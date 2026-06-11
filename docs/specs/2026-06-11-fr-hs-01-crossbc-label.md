# FR-HS-01 보강 — cross-BC 라벨 박제 (완전 Jira식) — 스펙

> slug: fr-hs-01-crossbc-label · BC: issue-tracking(주) + shared-kernel + identity-access · 2026-06-11
> 선행: FR-HS-01 (PR #115) · 후속: FR-HS-02 조회 UI (PR2)

## 배경 / 목표

이슈 변경 이력의 cross-BC 필드(`assignee`, `securityLevel`)는 FR-HS-01에서 `label=null`로 박제됐다(기록 시 cross-BC 호출 회피). 이 PR은 Jira Cloud처럼 **변경 당시 표시명을 기록 시점에 박제**한다. 목표: 사용자가 나중에 이름을 바꾸거나 보안등급이 개명·삭제돼도, 과거 이력은 **당시 표시명**을 보존한다(감사 무결성).

`IssueChangeDetector`는 이미 from/to **값**을 채운다(assignee=user UUID, securityLevel=level UUID). 이 PR은 그 값을 표시명으로 resolve해 from/to **label**을 채운다.

## 사용자 시나리오 (Given-When-Then)

- **S1 담당자 변경 박제**: Given 담당자가 "홍길동"인 이슈, When Bob이 담당자를 "김철수"로 변경, Then 이력 item의 `fromLabel="홍길동"`, `toLabel="김철수"`(변경 당시 표시명)로 박제된다.
- **S2 당시값 보존**: Given S1 이후 "김철수"가 표시명을 "김영희"로 변경, When 과거 이력을 조회(FR-HS-02), Then 해당 item은 여전히 `toLabel="김철수"`로 남는다.
- **S3 담당자 해제**: Given 담당자가 "홍길동"인 이슈, When 담당자 해제(unassign), Then `fromLabel="홍길동"`, `toLabel=null`(값 없음 → label 없음).
- **S4 보안등급 변경 박제**: Given securityLevel "사내한정", When "기밀"로 변경, Then `fromLabel="사내한정"`, `toLabel="기밀"`.
- **S5 cross-BC 조회 실패 graceful**: Given 담당자 user가 hard-delete돼 표시명 조회 불가, When 변경 이력 기록, Then 해당 side의 label=null로 유지되고 **기록 자체는 정상 진행**(이력 기록을 막지 않음).
- **S6 다필드 변경**: Given 한 PATCH에서 assignee+priority+securityLevel 동시 변경, When 기록, Then assignee/securityLevel은 cross-BC 표시명 박제, priority는 기존대로(스칼라 값=표시), 모두 한 change group에 묶인다.

## 기능 요구사항 (FR)

- **FR1**: `IssueChangeLabelResolver`가 `assignee` 필드의 from/to value(user UUID)를 사용자 표시명으로 resolve해 label을 채운다. 표시명 = `display_name` 우선, null이면 `username` fallback (Jira식 + 기존 audit 선례 정합).
- **FR2**: `IssueChangeLabelResolver`가 `securityLevel` 필드의 from/to value(level UUID)를 `IssueSecurityLevel.name`으로 resolve해 label을 채운다.
- **FR3**: cross-BC 조회는 shared-kernel 포트를 통한다(직접 import 금지, BC 격리 유지).
  - `UserLookupPort`(shared-kernel)에 userId→표시명 **역방향 batch 조회** 추가. 구현체 `UserLookupAdapter`(identity-access)가 users LEFT JOIN 없이 단일 SELECT로 표시명 일괄 조회.
  - `IssueSecurityDirectory`(shared-kernel)에 levelId→레벨명 조회 추가. 구현체 `IdentityAccessIssueSecurityDirectory`(identity-access).
- **FR4**: 조회 실패(미존재·삭제·파싱불가·예외)는 해당 side label=null로 graceful degrade. 예외를 던지거나 원시 UUID를 label에 노출하지 않는다(기존 LabelResolver 정책 동일).
- **FR5**: FR-HS-01 기존 테스트 중 "assignee/securityLevel label=null 검증" 케이스를 "표시명 박제 검증"으로 갱신한다.

## 비기능 요구사항 (NFR)

- **NFR1 트랜잭션**: cross-BC 표시명 조회는 readOnly 포트로, 기록 호출자 트랜잭션에 참여(별도 트랜잭션 분리 없음). 박제와 이슈 변경이 같은 트랜잭션에서 커밋(FR-HS-01 "한 트랜잭션" 규칙 유지).
- **NFR2 성능**: 한 변경에서 assignee/securityLevel은 각 from/to 최대 2개 UUID. batch 시그니처(Set→Map)로 N+1 방지. 단일 PATCH당 cross-BC 추가 쿼리 ≤ 2.
- **NFR3 BC 격리**: issue-tracking은 shared-kernel 인터페이스에만 의존. identity-access 직접 import 금지. ArchUnit 경계 룰 유지.
- **NFR4 보안**: 표시명 조회는 권한 무관 read(변경은 이미 권한 통과). 표시명 외 정보(이메일 등) 노출 금지.

## 변경 인터페이스 (포트 시그니처 — plan에서 최종 확정)

```kotlin
// shared-kernel: com.bts.shared.user.UserLookupPort (역방향 추가)
fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String>
// 반환: 실재 user만. 표시명 = display_name ?: username. 미존재 id는 키 제외.
// default 메서드로 추가 → 기존 fake 구현 다수 보호 (메모리 interface-extension-default-method)

// shared-kernel: com.bts.shared.permission.IssueSecurityDirectory (레벨명 추가)
fun findLevelNames(levelIds: Set<UUID>): Map<UUID, String>
// default 메서드. 반환: 실재 레벨만. 미존재 제외.
```

`IssueChangeLabelResolver`는 위 두 포트를 생성자 주입받아 `assignee`/`securityLevel` 케이스에서 호출. 기존 생성자에 파라미터 추가 → 기존 테스트/배선 갱신(메모리 plan-files-constructor-injection-existing-tests).

## 데이터 모델 변경

- **테이블 변경 없음**. `issue_change_item.from_label`/`to_label` 컬럼은 이미 존재(V018). 박제 값만 채워진다. 마이그레이션 불필요 → db-engineer 불요.

## 엣지 케이스

- E1. assignee=null(unassign): toValue=null → toLabel=null. fromValue(이전 UUID)는 표시명 resolve.
- E2. 신규 담당 지정(이전 null): fromValue=null → fromLabel=null. toValue resolve.
- E3. user hard-delete 후 변경: resolve 실패 → label=null, 기록 진행(S5).
- E4. securityLevel 삭제됨: 레벨명 조회 실패 → label=null.
- E5. value가 UUID 파싱 불가(데이터 이상): label=null(기존 parseUuidOrNull 패턴).
- E6. default 메서드 미오버라이드 fake: 빈 Map 반환 → 모든 label=null이지만 NPE 없음(fail-safe 방향).
- E7. 같은 user를 from/to 양쪽(이론상 no-op): detector가 no-op이면 item 자체 미생성(기존 동작 불변).

## 제약 조건

- ADR `2026-06-11-issue-change-history-model` §결정1 "cross-BC 호출 회피" + LabelResolver KDoc "cross-BC 조회 금지"를 deviation으로 갱신. 같은 PR에서 ADR 보강 단락 작성.
- `status` 필드(project-workflow BC)는 이 PR 범위 밖(Maxi: assignee·securityLevel만). stateKey passthrough 유지.
- actor 표시명(group 레벨)은 이 PR 범위 밖 — FR-HS-02 조회에서 처리.
- 전수 동기화: product 문서 §5.1.1 D2 노트 수정, Obsidian/메모리 미러.

## 측정 가능한 완료 기준

1. assignee 변경 통합테스트: from/to_label에 변경 당시 표시명 박제 확인.
2. securityLevel 변경 통합테스트: 레벨명 박제 확인.
3. cross-BC 조회 실패 시 label=null + 이력 row 정상 INSERT 확인(S5 회귀).
4. unassign(E1)/신규지정(E2) label 비대칭 확인.
5. 다필드 동시 변경(S6) 한 group 박제 확인.
6. FR-HS-01 기존 테스트(label=null 가정) 전부 갱신, 풀 테스트 0 fail.
7. ArchUnit BC 격리 룰 통과(issue-tracking→identity-access 직접 의존 0).
8. ADR 갱신 + verify-master-plan.sh 통과.
