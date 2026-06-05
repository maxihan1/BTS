# ADR: 이슈 접근 권한 — BROWSE/VIEW 분리 + 매트릭스 이관 + 미인가 단건 404

> 날짜: 2026-06-05
> 상태: 채택 (FR-PM-05, PR #85)
> 관련 FR: FR-PM-05 (이슈 접근 — Browse, View)
> 관련 BC: identity-access (소유, 판정기) + issue-tracking (포트 호출/스코프)
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md) §12.3 이슈 접근, §12.4 보안 수준(FR-PM-06)
> 선행: FR-PM-02 [issue-permission-scheme-model](2026-06-02-issue-permission-scheme-model.md)

## 맥락

FR-PM-05는 "이슈 접근(Browse/View)"을 통제한다. 착수 시점 코드 ground-truth:

- `IssuePermission` enum 6종: VIEW/CREATE/UPDATE/TRANSITION/SOFT_DELETE/HARD_DELETE. **VIEW 단일**이 목록·단건을 모두 표현.
- prod `IdentityAccessIssuePermissionResolver`(FR-PM-02)는 VIEW를 `toCodeOrNull() = null`로 매핑 → "프로젝트 멤버면 무조건 통과"하는 **임시 정책**. resolver KDoc이 직접 명시: *"VIEW — FR-PM-05(가시성 제어)에서 role_permissions 매트릭스로 이관한다. 이관 시 toCodeOrNull 매핑 추가 + 이 KDoc 정책 메모 제거."*
- `IssueApplicationService.listIssues`는 `IssueScope.Project`에 VIEW 검증(프로젝트 단위 all-or-nothing), `findByKey`는 `IssueScope.Issue`에 VIEW 검증 **후** 조회 → 미인가 시 `IssueAccessDeniedException`(403)을 던지고 그 다음 404(존재 probe 노출).
- SDD 12.3은 이슈 접근에 **두 권한 코드**를 정본으로 정의: `BROWSE_PROJECT`(프로젝트 조회) + `VIEW_ISSUE`(이슈 보기, 보안 수준 필터링).
- SDD 12.4는 per-issue 보안 수준(진짜 "비공개 이슈")을 **FR-PM-06**으로 명시 분리.

## 결정

### D1. IssuePermission.VIEW → BROWSE + VIEW 분리 (SDD 12.3 충실)

`IssuePermission` enum에 `BROWSE`를 추가해 SDD 12.3의 두 코드와 1:1 정렬한다.

| IssuePermission | permission_code | 게이트 대상 |
|---|---|---|
| `BROWSE` (신규) | `BROWSE_PROJECT` | 프로젝트 이슈 목록 조회 (`listIssues`) |
| `VIEW` | `VIEW_ISSUE` | 이슈 단건 상세 조회 (`findByKey`) |

- `listIssues`는 `assertPermission(actor, BROWSE, IssueScope.Project)`로 전환.
- `findByKey`는 `assertPermission(actor, VIEW, IssueScope.Issue)` 유지(코드만 VIEW_ISSUE로 매트릭스 판정).
- prod resolver `toCodeOrNull()`에 `BROWSE → BROWSE_PROJECT`, `VIEW → VIEW_ISSUE` 매핑 추가 → "멤버면 통과" 임시 정책 제거.

**대안(기각)**: 단일 VIEW_ISSUE로 목록+단건 통합. 더 적은 코드지만 SDD 12.3과 어긋나고 "외부 이해관계자가 이슈는 보되 프로젝트는 못 봄"(SDD 12.1) 같은 Browse/View 구분이 사라진다.

### D2. 미인가 단건 조회 = 404 (존재 숨김, Jira 방식)

`findByKey`에서 VIEW_ISSUE 미인가 시 403이 아니라 **404**(이슈 미존재와 동일 응답)로 반환한다. 권한 없는 이슈의 존재 여부 probe를 차단한다(보안상 정석). plan D6 "권한 없는 이슈 404" 충족.

- 인증 추출은 리소스 조회보다 먼저(메모리 `auth-extraction-before-resource-lookup`): 미인증 → 401, 인가 실패/미존재 → 404로 통일.
- 회귀 테스트: 미인증→401, 비멤버/미인가→404, 존재하지만 인가→200.

### D3. 시드 = 기본 스킴에 BROWSE_PROJECT + VIEW_ISSUE 부여 (역할 2종)

FR-PM-02의 `role_permissions` 매트릭스에 신규 코드 2종을 시드한다. `PROJECT_ADMIN`/`MEMBER` 모두 BROWSE_PROJECT + VIEW_ISSUE 보유(읽기는 멤버 기본권). 시드 마이그레이션 추가 시 `PermissionSchemaMigrationTest` 정확 카운트 단언이 깨지므로 **카운트 갱신 동반**(메모리 `fr-pm-permission-seed-migration-test-coupling` 반복 함정).

### D4. per-issue 가시성(보안 수준)은 FR-PM-06으로 분리

FR-PM-05는 **프로젝트 단위 매트릭스 판정**까지다. 한 프로젝트 안에서 BROWSE_PROJECT/VIEW_ISSUE는 균일(보유=전체 보임, 미보유=전체 안 보임). 개별 이슈를 차등 숨기는 "비공개 이슈"(보안 수준)는 SDD 12.4대로 FR-PM-06. plan D4의 "jOOQ Condition 필터"는 본 FR에서 **프로젝트 인가 범위 한정** 수준(미인가 프로젝트 이슈 배제)으로 구현하고, per-issue 술어 확장은 FR-PM-06이 흡수한다.

## 결과

- enum/코드/시드/판정기/스코프/응답코드 일괄 정렬 → resolver KDoc 임시 정책 메모 제거.
- IssueScope.Global은 본 FR도 미사용(메모리 `issue-scope-global-prod-hard-deny` 함정 회피 — Global 신규 결선 없음).
- 검증은 prod 프로파일 Testcontainers 통합테스트(허용/거부)가 ground-truth. non-prod AlwaysAllow는 마스킹하므로 prod 통합으로만 표면화.
