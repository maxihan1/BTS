# 12. 권한 모델

## 12.1 두 레이어 권한 (v0.2 결정)

| 레이어 | 책임 | 예시 |
|---|---|---|
| **행정 권한** | 프로젝트 설정 변경 | 워크플로우 편집, 권한 스킴 변경 |
| **이슈 데이터 접근** | 이슈 보기/수정/삭제 | Browse, Edit, Delete |

이를 분리하는 이유: 외부 이해관계자가 이슈는 볼 수 있되 프로젝트 설정은 못 바꾸도록.

## 12.2 권한 스킴 (Permission Scheme)

권한 스킴은 권한 → 사용자/역할/그룹 매핑.

```yaml
scheme:
  name: "기본 스킴"
  permissions:
    - permission: BROWSE_PROJECT
      grants: [ProjectMember, ProjectLead, OrgAdmin]
    - permission: CREATE_ISSUE
      grants: [ProjectMember, ProjectLead]
    - permission: EDIT_ISSUE
      grants: [ProjectMember, ProjectLead]  # 자기 이슈만 (특별 규칙)
    - permission: DELETE_ISSUE
      grants: [ProjectLead, OrgAdmin]
    - permission: TRANSITION_ISSUE
      grants: [Assignee, Reporter, ProjectMember]
    - permission: MANAGE_AUTOMATION
      grants: [ProjectAdmin]
    - permission: ADMIN_PROJECT
      grants: [ProjectLead, OrgAdmin]
```

## 12.3 권한 종류

### 프로젝트 행정
- `ADMIN_PROJECT` - 프로젝트 설정 변경
- `MANAGE_WORKFLOW` - 워크플로우 편집
- `MANAGE_AUTOMATION` - 자동화 규칙 관리
- `MANAGE_COMPONENTS` - 컴포넌트 추가/제거
- `MANAGE_VERSIONS` - 버전 추가/제거
- `MANAGE_PERMISSIONS` - 권한 스킴 변경
- `MANAGE_CUSTOM_FIELDS` - 커스텀 필드 정의 추가/수정/삭제 (FR-IS-10, PROJECT_ADMIN 전용)
- `MANAGE_FIELD_PERMISSIONS` - 필드 수준 권한 규칙 추가/수정/삭제 (§12.5 FR-PM-07, PROJECT_ADMIN 전용)

### 이슈 접근
- `BROWSE_PROJECT` - 프로젝트 조회
- `VIEW_ISSUE` - 이슈 보기 (보안 수준 필터링)
- `CREATE_ISSUE` - 이슈 생성
- `EDIT_ISSUE` - 이슈 수정
- `DELETE_ISSUE` - 이슈 삭제
- `TRANSITION_ISSUE` - 상태 전이
- `COMMENT_ISSUE` - 댓글
- `ASSIGN_ISSUE` - 담당자 변경
- `RESOLVE_ISSUE` - 해결 처리
- `ATTACH_FILE` - 첨부
- `LINK_ISSUE` - 링크
- `SET_ISSUE_SECURITY` - 이슈에 보안 등급 지정/변경 (FR-PM-06, Jira "Set Issue Security")

### 시스템
- `MANAGE_USERS` - 사용자 관리
- `MANAGE_PROVIDERS` - 인증 Provider 관리
- `VIEW_AUDIT_LOG` - 감사 로그
- `ADMIN_SYSTEM` - 시스템 관리

## 12.4 이슈 보안 수준 (FR-PM-06)

**Jira Cloud Issue Security와 동일 구조** (ADR [2026-06-06-issue-security-level-scheme-model](../decisions/2026-06-06-issue-security-level-scheme-model.md)). 특정 이슈에 등급을 붙여 추가 제한한다 — `VIEW_ISSUE` 권한이 있어도 그 등급의 멤버가 아니면 못 본다(미통과 시 404 존재 숨김, §12.3 BROWSE/VIEW와 일관).

```
이슈 보안 스킴(IssueSecurityScheme)   ── 등급들의 묶음 (전역, 여러 프로젝트 공유)
   └─ 보안 등급(IssueSecurityLevel)    ── "내부용", "임원만" (스킴당 여러 개, 기본 등급 지정 가능)
        └─ 등급 멤버(SecurityLevelMember) ── 멤버 타입 다형 5종
프로젝트에 스킴 적용                    ── project_issue_security_schemes (프로젝트당 0~1, PROJECT_ADMIN)
이슈에 등급 지정                        ── SET_ISSUE_SECURITY 권한자 (생성/편집 시)
```

```kotlin
data class IssueSecurityScheme(val id: UUID, val name: String, val description: String?)            // 전역, name UNIQUE
data class IssueSecurityLevel(val id: UUID, val schemeId: UUID, val name: String,
                              val description: String?, val isDefault: Boolean)                       // 스킴당 name UNIQUE, 기본 등급 ≤1
data class SecurityLevelMember(val id: UUID, val levelId: UUID,
                               val memberType: MemberType, val memberValue: String?)                 // 다형
enum class MemberType { REPORTER, ASSIGNEE, USER, PROJECT_ROLE, GROUP }
// memberValue: USER/GROUP=UUID, PROJECT_ROLE='PROJECT_ADMIN'|'MEMBER', REPORTER/ASSIGNEE=null
```

- **멤버 타입**. REPORTER(이슈 보고자) / ASSIGNEE(현재 담당자) / USER(특정 사용자) / PROJECT_ROLE(프로젝트 역할) / GROUP(사용자 그룹 §12.6.1, FR-PM-09).
- **관리 권한**. 스킴·등급·멤버 = `SYSTEM_ADMIN`(전역, Jira 사이트 관리자). 프로젝트에 스킴 적용 = `PROJECT_ADMIN`(역할 직접 확인). 이슈에 등급 지정 = `SET_ISSUE_SECURITY`(§12.3).
- **관리자 우회 없음**. SYSTEM_ADMIN/PROJECT_ADMIN도 등급 멤버가 아니면 못 본다 — 등급 멤버십이 유일한 통과 경로(민감 이슈 진짜 격리).
- **등급 없는 이슈**. 기존 `VIEW_ISSUE` 매트릭스만 적용(추가 제한 없음).
- **구현 단계**. 관리 인프라(스킴/등급/멤버/프로젝트 적용)는 identity-access(PR-A). `issues.security_level_id` 컬럼 + 이슈 지정 + 판정 결선(`IdentityAccessIssuePermissionResolver` 확장)은 issue-tracking 결선(PR-B).

## 12.5 필드 수준 권한 (FR-PM-07)

이슈의 특정 필드(코어 + 커스텀)를 특정 **사용자 그룹**(§12.6.1)만 열람/편집하게 제어한다. 예: "급여 영향도(커스텀 필드)는 HR 그룹만 열람, 편집도 HR만". (ADR [2026-06-08-field-level-permissions](../decisions/2026-06-08-field-level-permissions.md), PR-A #97.)

```kotlin
// 프로젝트별 규칙 1건 = (프로젝트, 필드, 그룹, 접근수준)
data class FieldPermission(
    val projectId: UUID,
    val fieldKind: FieldKind,       // CORE(코어 필드) | CUSTOM(커스텀 필드 — 동명 key 네임스페이스 분리)
    val fieldKey: String,           // 코어 필드명(화이트리스트) 또는 커스텀 필드 key
    val groupId: UUID,              // user_groups 참조(§12.6.1 FR-PM-09)
    val accessLevel: FieldAccessLevel,  // VIEW | EDIT (EDIT ⊃ VIEW)
)
```

- **역할 축 = 사용자 그룹**. ProjectRole 2종이나 멤버 5타입(§12.4) 대신 그룹 단일 축. SDD의 `visible_to: [HR, Manager]`를 그룹으로 직역.
- **그릇 = 프로젝트별 단순 테이블**(`field_permissions`, identity-access V018). 스킴 계층(§12.2/§12.4) 미채택 — 필드 권한은 프로젝트 간 공유 수요가 적음.
- **opt-in 제한**. 필드에 규칙이 0건이면 자유(기존 동작). 1건이라도 있으면 그 필드는 제한 모드 — VIEW/EDIT 행 그룹 멤버만 열람, EDIT 행 그룹 멤버만 편집.
- **관리자 우회 없음**. 규칙 지정 그룹 멤버가 유일 통과 경로. PROJECT_ADMIN/SYSTEM_ADMIN도 그룹 멤버가 아니면 값을 못 본다(§12.4 보안 수준과 동일 원칙). 단 규칙 CRUD는 `MANAGE_FIELD_PERMISSIONS`(§12.3) 보유자가 수행 — 값 열람과 규칙 관리는 분리.
- **시행**. 이슈 응답 직렬화 시 열람 불가 필드 마스킹(커스텀 키 제거 · nullable 코어 null · `restrictedFields` 응답). 이슈 편집 시 편집 불가 필드 변경 거부(403, no-op 통과). 판정은 cross-BC `FieldPermissionResolver` 포트(shared-kernel) → identity-access prod 구현.
- **대상 필드**. 코어 7종(summary·description·priority·labels·environment·impact·assigneeId) + 커스텀 필드. `securityLevelId`(§12.4 SET_ISSUE_SECURITY)·`componentIds`(MANAGE_COMPONENTS) 등은 별도 통제라 제외(이중 통제 회피). non-null 코어(summary·priority)는 편집 제어만(열람은 항상 노출).
- **범위**. PR-A(백엔드) 완료. 프론트 UI(숨김 필드 렌더 차단 + 규칙 관리 화면)·E2E는 PR-B.

## 12.6 역할 (Role)

- `OrgAdmin` - 조직 전체 관리자
- `ProjectAdmin` - 프로젝트 관리자
- `ProjectLead` - 프로젝트 리드
- `ProjectMember` - 프로젝트 멤버
- `Reporter` - 보고자 (이슈별 동적)
- `Assignee` - 담당자 (이슈별 동적)
- `Watcher` - Watcher (이슈별 동적)
- 커스텀 역할 가능

### 12.6.1 사용자 그룹 (FR-PM-09)

전역(시스템 단위) 사용자 그룹. 여러 사용자를 묶어 권한 부여(§12.2)·보안 수준 멤버(§12.4)·그룹 멘션(§9)의 단위로 재사용한다.

```kotlin
data class UserGroup(
    val id: UUID,
    val name: String,        // 전역 유니크. 예: "임원", "보안팀"
    val description: String,
)
```

- **멤버십**. `group_memberships(group_id, user_id)` — 사용자 ↔ 그룹 N:M, 전역(프로젝트 무관).
- **관리**. 시스템 관리자(`SYSTEM_ADMIN`, §12.6 OrgAdmin / FR-PM-08)만 그룹 CRUD + 멤버 추가/제거.
- **소비처**. 이슈 보안 수준 멤버(§12.4 FR-PM-06), 권한 스킴 grants(§12.2), 그룹 멘션(§9). 각 소비 FR이 그룹을 참조한다.
- **LDAP 그룹 동기화**(§19.8). 네이티브 그룹을 우선 도입. `user_external_accounts.groups` 문자열 ↔ 정규 그룹 매핑·주기 동기화는 별도 후속 FR.
- **범위**. FR-PM-09는 백엔드 인프라(엔티티 + 멤버십 + CRUD API)만. 관리 UI는 후속.

## 12.7 권한 평가 (Spring Security 통합)

```kotlin
@Component("issueAuthz")
class IssueAuthorization(
    private val permissionService: PermissionService,
) {
    fun canView(key: String): Boolean {
        val user = SecurityContextHolder.currentUser()
        val issue = issueRepository.findByKey(key) ?: return false
        return permissionService.hasPermission(
            user = user,
            permission = "VIEW_ISSUE",
            context = PermissionContext.forIssue(issue),
        )
    }
}

// 사용
@PreAuthorize("@issueAuthz.canEdit(#key)")
fun update(@PathVariable key: String, ...) { ... }
```

## 12.8 권한 평가 성능

- 사용자 권한은 Redis 캐시 (TTL 5분)
- 권한 변경 시 캐시 무효화
- AQL 검색 시 권한 필터를 SQL JOIN으로 처리

## 12.9 다음 챕터

- API → [11. API 설계](11-api-design.md)
- 인증 → [19. 인증 시스템](19-authentication.md)
