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

### 시스템
- `MANAGE_USERS` - 사용자 관리
- `MANAGE_PROVIDERS` - 인증 Provider 관리
- `VIEW_AUDIT_LOG` - 감사 로그
- `ADMIN_SYSTEM` - 시스템 관리

## 12.4 이슈 보안 수준 (FR-PM-06)

특정 이슈를 추가로 제한:

```kotlin
data class IssueSecurityLevel(
    val id: Long,
    val projectId: Long,
    val name: String,        // "내부용", "임원만"
    val description: String,
    val allowedRoles: List<Long>,
)
```

이슈 생성/수정 시 보안 수준 지정. `VIEW_ISSUE` 권한이 있어도 보안 수준 통과 못 하면 못 봄.

## 12.5 필드 수준 권한 (FR-PM-07)

특정 필드를 특정 역할만 볼 수 있게:

```yaml
field_security:
  - field: salary_impact     # 커스텀 필드
    visible_to: [HR, Manager]
    editable_by: [HR]
```

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
