# FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할) RecipientResolver — 스펙

> BC: notification · type: backend · 생성: 2026-06-19
> 선행: FR-NT-01(#118 정책/평가엔진), FR-NT-02(#126/#137/#139 EventRecipientResolver·발송코어)
> product: docs/plan/product/notification-dashboard.md §2.3 · SDD §9.1

## 개요

`EventRecipientResolver`의 `else → skip (FR-NT-03 대상)` 분기를 실제 cross-BC 조회로 채운다. enum `RecipientRole`(9개)·정책 평가 엔진·`NotificationWorker`·dedup·actor 제외 파이프라인은 이미 완성되어 있고, 본 작업은 **수신자 역할 → 실제 userId 집합 해석 + 발송 전 보안수준 visibility 필터**를 추가한다. 신규 DB 테이블/마이그레이션 없음 (전부 기존 테이블 조회).

## 범위 (Maxi 확정 2026-06-19)

| RecipientRole | 이번 작업 | 데이터 출처 | 원본 BC |
|---|---|---|---|
| MENTIONED / REPORTER / ASSIGNEE | (FR-NT-02 완료) | — | — |
| **WATCHER** | ✅ | `issue_watchers` (FR-WT-01) | issue-tracking |
| **COMPONENT_LEAD** | ✅ | `issue_components` ⋈ `components.lead_user_id` (다대다 → 여러 lead) | issue-tracking |
| **PREVIOUS_ASSIGNEE** | ✅ (직전 1명) | `issue_change_item` field='assignee' 최근 변경 from_value | issue-tracking |
| **PROJECT_MEMBER** | ✅ | `project_memberships` 전체 | identity-access |
| **PROJECT_ADMIN** | ✅ | `project_memberships` role='PROJECT_ADMIN' | identity-access |
| RULE_OWNER | ❌ skip 유지 | automation BC 미존재 | — |

- PROJECT_LEAD enum 미추가. product 'Lead' = COMPONENT_LEAD로 해석.
- RULE_OWNER 정책 시드(V401)는 존재하나 해석 단계에서 skip + 디버그 로그(시드 부트스트랩 ADR 결정 3 일관). 향후 FR-AT에서 결선.

## 사용자 시나리오 (Given-When-Then)

- **S1 워처 알림**. Given 이슈 ATLAS-1에 워처 3명 등록, 정책에 (issue.transitioned × WATCHER × IN_APP) 활성. When 전이 이벤트 소비. Then 워처 3명(actor 제외)에게 인앱 알림 1건씩.
- **S2 컴포넌트 리드**. Given 이슈가 컴포넌트 A(lead=u1)·B(lead=u2)에 속함, 정책 (issue.created × COMPONENT_LEAD). When 생성 이벤트. Then u1·u2 수신. lead 미지정 컴포넌트는 건너뜀.
- **S3 이전 담당자**. Given 담당자가 u1→u2로 변경된 이력, 정책 (issue.assigned류 × PREVIOUS_ASSIGNEE). When 이벤트. Then 직전 담당자 u1 1명만 수신(전체 과거 아님).
- **S4 프로젝트 멤버/관리자**. Given 프로젝트 ATLAS 멤버 10명(관리자 2명), 정책 (... × PROJECT_MEMBER) 또는 (... × PROJECT_ADMIN). When 이벤트. Then 각각 멤버 10명 / 관리자 2명 수신.
- **S5 보안수준 필터**. Given 이슈에 security level 설정(멤버 중 4명만 열람 가능), 정책 (... × PROJECT_MEMBER 10명). When 이벤트. Then 해석된 10명 중 **열람 가능한 4명만** 알림, 나머지 6명 제외(제목/본문 누출 차단).
- **S6 역할 중복·자기 제외**. Given 같은 사용자가 reporter이자 watcher, actor가 본인. When 이벤트. Then dedup으로 1건, actor 본인은 제외(기존 파이프라인).
- **S7 RULE_OWNER skip**. Given 정책에 RULE_OWNER 행 존재. When 이벤트. Then 수신자 0(디버그 로그), 다른 역할은 정상 처리.

## 기능 요구사항 (FR)

- **FR1** `EventRecipientResolver.resolveRole`의 `else` 분기를 WATCHER / COMPONENT_LEAD / PREVIOUS_ASSIGNEE / PROJECT_MEMBER / PROJECT_ADMIN 5개 분기로 확장. RULE_OWNER는 명시적 skip(로그) 유지.
- **FR2** issue-tracking 기반 역할(WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE)은 기존 `IssueRecipientLookupPort.findRecipients(issueKey)` 1회 호출로 확장된 `IssueRecipients`에서 읽는다(N+1 방지, 기존 lazy 패턴 확장).
- **FR3** project 기반 역할(PROJECT_MEMBER/PROJECT_ADMIN)은 신규 `ProjectRecipientLookupPort.findProjectRecipients(projectKey)` 1회 호출로 해석. projectKey→projectId 변환은 adapter 내부 책임(`ProjectDirectory.resolveKeyToId` 재사용).
- **FR4** 해석된 전체 수신자 집합을 발송 전 신규 `IssueVisibilityPort.filterVisible(issueKey, userIds)`로 배치 필터링해 이슈 열람 권한 없는 사용자를 제외(보안수준 누출 차단). 이슈가 없는(projectKey만 있는) 이벤트는 필터 비대상.
- **FR5** 포트 조회는 **정책 매치에 해당 역할군이 있을 때만** 수행(역할 없으면 조회 skip, 효율).
- **FR6** actor 제외 + (userId, channel) dedup은 기존 `resolve()` 말미 파이프라인을 그대로 통과(추가 변경 없음).

## 포트 인터페이스 (cross-BC, shared-kernel)

ArchUnit BC 격리: notification은 issue-tracking/identity-access 내부 패키지 직접 import 금지. shared-kernel 포트 + 원본 BC adapter.

### P1. IssueRecipientLookupPort 확장 (기존 포트, IssueRecipients 필드 추가)
```kotlin
data class IssueRecipients(
    val reporterId: UUID?,
    val assigneeId: UUID?,
    val watcherIds: List<UUID> = emptyList(),        // 신규
    val componentLeadIds: List<UUID> = emptyList(),  // 신규
    val previousAssigneeId: UUID? = null,            // 신규 (직전 1명)
)
```
- `findRecipients(issueKey)`는 default 메서드 유지(인터페이스 확장 시 기존 fake/구현 보호, [[interface-extension-default-method]]).
- 구현: `IssueRecipientLookupAdapter`(issue-tracking)가 `IssueWatcherRepository`·`ComponentRepository`(신규 메서드)·`IssueChangeHistoryRepository`를 추가 주입해 한 번에 채움. 동일 issueId 재사용.

### P2. ProjectRecipientLookupPort 신규 (shared-kernel)
```kotlin
interface ProjectRecipientLookupPort {
    fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty()
}
data class ProjectRecipients(val memberIds: List<UUID>, val adminIds: List<UUID>) {
    companion object { fun empty() = ProjectRecipients(emptyList(), emptyList()) }
}
```
- 구현: identity-access adapter. `ProjectDirectory.resolveKeyToId(projectKey)` → projectId → `ProjectMembershipRepository.listByProject(projectId)` → role 분기. projectKey 미해결(삭제 프로젝트) → empty.

### P3. IssueVisibilityPort 신규 (shared-kernel, 보안)
```kotlin
interface IssueVisibilityPort {
    /** issueKey를 볼 수 있는 userId만 반환(보안수준 필터). 보안수준 미설정 이슈는 전부 통과. */
    fun filterVisible(issueKey: String, candidateUserIds: Set<UUID>): Set<UUID> = candidateUserIds
}
```
- 구현 BC와 default/fail-safe 방향은 **plan에서 security-engineer가 확정**(보안수준 판정 로직이 issue-tracking인지 identity-access인지 + adapter 부재 시 prod 부팅 가드). 기본 동작: 보안수준 미설정 이슈는 전체 통과, 설정 이슈는 각 user가 해당 level 멤버인지 판정. [[crossbc-resolver-nullable-fail-open]] 주의 — allow-all default는 비-prod 한정, prod는 adapter 필수.

## 데이터 모델 변경

없음. 신규 마이그레이션 0. 전부 기존 테이블(`issue_watchers`·`components`·`issue_components`·`issue_change_item`·`project_memberships`·`projects`) 읽기 전용 조회. `ComponentRepository`에 "issueId → 컴포넌트 lead userId 목록" 조회 메서드 1개 추가(같은 BC 내부).

## 엣지 케이스

- 정책에 issue 기반/project 기반 역할이 하나도 없으면 해당 포트 조회 skip.
- watcher/member 목록에 actor 포함 → 기존 actor 제외.
- 같은 user 다중 역할 → 기존 dedup.
- 보안수준 제한 이슈 + 광역 역할 → 권한 없는 수신자 제외(S5).
- **멘션 수신자도 visibility 필터 대상**(명시 호출이라도 보안수준 우선 — Maxi "모든 수신자" 결정). 단 멘션은 작성자가 권한 있는 사람만 호출하는 게 정상이라 실제 제외는 드묾.
- projectKey 미해결(삭제 프로젝트) → 빈 멤버(알림 누락, fail-safe).
- 이슈에 컴포넌트 없음/lead 미지정 → componentLeadIds 빈.
- assignee 변경 이력 0(한 번도 안 바뀜) → previousAssigneeId null.
- previousAssignee `from_value` 형식(UUID 텍스트 vs 'NONE' 리터럴)은 impl에서 실측 확인 후 파싱(미할당→할당 전이의 from_value 처리 포함).
- RULE_OWNER 정책 → skip + 디버그 로그(다른 역할 정상).

## 비기능 요구사항 (NFR)

- visibility 필터·project/issue 포트는 **배치(수신자 전체 1회 판정)**로 N+1 방지.
- 알림 지연 p95 < 1s(기존 §NFR) 영향 최소 — 이벤트당 추가 쿼리는 소수.
- 수신자 조회 실패(adapter 부재/이슈 미존재) → 빈 수신자(알림 누락이 과발송보다 안전). visibility는 보안이라 prod adapter 필수(부팅 가드).
- ArchUnit BC 격리 룰 통과(notification → shared-kernel만 의존).
- detekt/ktlint 통과, baseline 동결만(신규 위반 코드 수정).

## 제약 조건

- 한 PR = notification BC + shared-kernel 포트 + issue-tracking/identity-access adapter(포트 구현은 BC 격리 예외로 같은 PR 허용, 선례 FR-NT-02 IssueRecipientLookupPort).
- enum/시드/스키마 변경 0 → FR 카운트·정본 동기화 불필요(범위 deviation 없음). DB 마이그레이션 0.
- previousAssigneeId 단수(직전 1명), Maxi 확정.

## 측정 가능한 완료 기준

- [ ] 5개 역할 단위 테스트(각 역할 해석 + 빈/다중/중복 케이스).
- [ ] visibility 필터 단위 + 통합 테스트(보안수준 설정 이슈에서 권한 없는 수신자 제외, S5).
- [ ] 포트 3종(P1 확장·P2·P3) adapter 통합 테스트(Testcontainers 실 repo + 시드).
- [ ] RULE_OWNER skip 회귀 테스트(기존 EventRecipientResolverTest 갱신).
- [ ] N+1 방지 검증(역할군별 포트 1회 호출).
- [ ] ArchUnit BC 격리 + detekt/ktlint 그린.
- [ ] product §2.3 D1~D5 [x] 마킹(D6 정책 페이지 확장·D7 E2E는 후속 — 아래 분리 결정 참조).

## D6/D7(프론트/E2E) 분리 여부

product §2.3은 D6(정책 페이지 확장)·D7(E2E)를 포함하나, FR-NT-03의 본질은 **백엔드 수신자 해석**이다. recipient_role은 FR-NT-01 D6 관리자 정책 페이지(#124)에서 이미 CRUD 가능(enum 9종 노출). FR-NT-03이 새 UI를 요구하는지는 plan/리뷰에서 확정 — 백엔드(D1~D5) 우선 완결, D6/D7은 별도 판단(FR-NT-01/02 패턴: 백엔드 PR + 프론트 PR 분할).

## Brainstorming Check (← Phase B 채움)
