# 이슈 보안 수준 — Jira식 스킴 구조 채택 (FR-PM-06)

> 상태: 채택 | 날짜: 2026-06-06 | 영역: identity-access (권한) | 관련 FR: FR-PM-06
> 선행: FR-PM-05(Browse/View) · FR-PM-08(SYSTEM_ADMIN) · FR-PM-09(사용자 그룹)

## 맥락

SDD §12.4 초안은 이슈 보안 수준을 단순 모델로 정의했다.

```kotlin
data class IssueSecurityLevel(
    val id: Long, val projectId: Long, val name: String,
    val description: String, val allowedRoles: List<Long>,
)
```

이는 (1) 등급이 `projectId`에 직접 매달려 프로젝트 간 재사용 불가, (2) 멤버가 `allowedRoles`(역할 ID 목록)뿐이라 보고자·담당자·그룹·특정 사용자를 표현 못 함, (3) PK가 `Long`이라 BTS의 UUID 체계와 불일치하는 한계가 있었다.

2026-06-06 도메인 grill에서 Maxi는 **"Jira Cloud Issue Security와 동일하게"**를 명시 결정했다.

## 결정

**Jira Cloud Issue Security 구조를 그대로 채택한다.**

1. **스킴 계층 도입** — `이슈 보안 스킴(IssueSecurityScheme) → 보안 등급(IssueSecurityLevel) → 등급 멤버(SecurityLevelMember)`. 스킴은 전역이며 여러 프로젝트가 공유한다(`project_issue_security_schemes`로 프로젝트당 0~1개 적용).
2. **멤버 타입 다형 5종** — `MemberType ∈ {REPORTER, ASSIGNEE, USER, PROJECT_ROLE, GROUP}`. `member_value`는 USER/GROUP=UUID, PROJECT_ROLE=`PROJECT_ADMIN`|`MEMBER`, REPORTER/ASSIGNEE=null. (GROUP은 FR-PM-09 사용자 그룹 소비.)
3. **권한 분리** — 스킴·등급·멤버 관리 = `SYSTEM_ADMIN`(Jira 전역 관리자, `isSystemAdmin` 재사용). 프로젝트에 스킴 적용 = `PROJECT_ADMIN`(역할 직접 확인). 이슈에 등급 지정 = `SET_ISSUE_SECURITY` 전용 권한코드 신설.
4. **판정** — VIEW_ISSUE 매트릭스 통과 **AND** (등급 없음 OR 등급 멤버에 actor 충족). 미통과 시 **404 존재 숨김**(FR-PM-05 `assertViewIssueOrNotFound` 일관, probe 차단).
5. **관리자 우회 없음** — SYSTEM_ADMIN/PROJECT_ADMIN도 등급 멤버가 아니면 못 본다. 등급 멤버십이 유일한 통과 경로. 민감 이슈를 진짜 격리(Jira 동일).
6. **UUID 체계** — 모든 PK는 UUID(BTS 표준). 등급은 스킴 종속(`scheme_id`), `projectId` 직접 종속 폐기.

## 대안 검토

- **단순 모델 유지(allowedRoles)** — 기각. 그룹·보고자·담당자 표현 불가, Maxi의 "Jira 동일" 결정 위배.
- **등급을 프로젝트에 직접(스킴 계층 생략)** — 기각. 여러 프로젝트가 등급을 공유하는 Jira의 재사용 가치 상실, Maxi가 스킴 계층 포함 명시 선택.
- **이슈 지정 = EDIT_ISSUE 재사용** — 기각. Jira는 `Set Issue Security` 전용 권한으로 분리, Maxi가 전용 권한 선택.
- **관리자 우회 허용** — 기각. 민감 이슈 격리 약화, Jira 동일 원칙.

## 결과

- 신규 테이블 4종(identity-access V016): `issue_security_schemes`/`issue_security_levels`/`issue_security_level_members`/`project_issue_security_schemes`. + `issues.security_level_id`(issue-tracking, PR-B).
- 신규 권한코드 `SET_ISSUE_SECURITY`(role_permissions 기본 스킴 PROJECT_ADMIN 시드, `PermissionSchemaMigrationTest` 12→13).
- 판정은 identity-access `IdentityAccessIssuePermissionResolver` 확장(cross-BC `IssueSecurityLookup` 포트로 이슈 데이터 read) — **PR-B**.
- **2 PR 분할**: PR-A(identity-access 관리 인프라, PR #86) → PR-B(issue-tracking 컬럼·이슈지정·판정 결선). FR-PM-09가 그룹 CRUD만 먼저 한 리듬과 동일.
- SDD §12.4 재작성, §12.3에 `SET_ISSUE_SECURITY` 추가.
