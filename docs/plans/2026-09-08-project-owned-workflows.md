# 프로젝트 소유 워크플로우 — 구현 계획

티어: T3
타입: migration

스펙 `docs/specs/2026-09-08-project-owned-workflows.md`. **신설 FR-WF-08** (정본 145 → 146, Maxi 2026-09-08 확정).

> **티어 선언은 기계가 읽는다.** `scripts/workflow/tier-floor.test.ts` 가 이 파일의 `티어:` 줄을
> 파싱해 「마이그레이션을 건드렸는데 선언이 T0/T1 인가」를 막는다(ⓑ Flyway T3 하한). 이 줄을 지우면
> plan 이 있어도 **선언 부재**로 읽혀 red 다 — PR ① 착수 때 실제로 그렇게 걸렸다.
> 전 PR 중 가장 높은 티어를 적는다. PR ③ 이 shared-kernel 을 건드려 역시 T3 다.

## Jira 대조

근거 표와 정정은 스펙 `## Jira 대조` 가 정본이다. 여기서는 **채택 항목 ↔ task 짝**만 적는다(차집합 0).

| Jira 채택 항목 | 대응 task |
|---|---|
| team-managed 워크플로우는 그 프로젝트 소유 ([support.atlassian.com](https://support.atlassian.com/jira-software-cloud/docs/manage-how-work-flows-in-your-team-managed-project/)) | 1-1 · 1-2 (`project_id` 컬럼) |
| 편집 진입은 **프로젝트 설정** (`Space settings → Work types → Edit workflow`) | 4-1 · 4-2 · 4-4 |
| 공유 워크플로우는 전역 관리자만 편집 ([support.atlassian.com](https://support.atlassian.com/jira/kb/use-the-edit-workflows-permission-in-company-managed-projects-in-jira-cloud/)) | 2-2 · 3-1 · 3-2 (`project_id IS NULL` → SYSTEM_ADMIN) |
| 전역 템플릿 복제 → 프로젝트 전용 사본이 편집 가능해진다 (Atlassian 권장 우회) | 2-1 · 2-2 (`duplicate` 가 `project_id` 를 실어야 한다) |
| 「Role: Administrator」가 워크플로우 관리 권한 | 3-1 (기존 `MANAGE_WORKFLOW` 매트릭스 재사용 — 신설 없음) |

★**복제 경로가 task 에 빠져 있었다.** Jira 가 권장 우회로 명시하는 「전역 템플릿을 복제해 자기 프로젝트 소유로 만든다」가 이 설계의 **주 사용 경로**인데, `POST /workflows/{key}/duplicate` 가 `project_id` 를 실어 주지 않으면 복제본도 전역이 되어 여전히 못 고친다. Task 2-1·2-2 의 verify 에 넣었다.

## PR 분할

BC 격리(한 PR = 한 BC)와 「마이그레이션은 되돌리기 어렵다」를 함께 지키려고 4개로 가른다. 앞 PR 이 뒤 PR 의 전제이므로 순서를 바꾸지 않는다.

| PR | BC | 티어 | 내용 | 되돌리기 |
|---|---|---|---|---|
| ① | project-workflow | T3 | FR-WF-08 등재 + 마이그레이션 — `project_id` 2컬럼 + 부분 유니크 재편 | 어렵다. 단독 PR |
| ② | project-workflow | T2 | 스코프 결정 + CRUD 판정 전환 + 목록 필터 | 코드만 |
| ③ | identity-access + shared-kernel | T3 | 공용 `WorkflowScope` 신설 + 정의 resolver 스코프 도입 | 코드만 |
| ④ | apps/web | T1 | 프로젝트 설정 라우트 2종 + 가드 + E2E | 코드만 |

★①을 ②와 합치지 않는다. 컬럼만 추가하고 아무도 안 읽는 상태는 안전하지만, 판정 전환까지 한 PR 에 넣으면 롤백이 마이그레이션 되돌리기가 된다.

---

## PR ① 마이그레이션 (T3)

### Task 1-1. `workflows.project_id` 추가 + 부분 유니크 재편
- `V209__workflows_project_ownership.sql`
- `project_id UUID NULL REFERENCES ...` — FK 를 걸지 **말 것**. `projects` 는 다른 BC 소유다(BC 격리). UUID 컬럼 + 인덱스만.
- 기존 `key UNIQUE` 제약 DROP → 부분 유니크 2개.
  - `WHERE project_id IS NULL` → `key` 단독
  - `WHERE project_id IS NOT NULL` → `(project_id, key)`
- 소프트 삭제와의 상호작용을 본다 — `V206__soft_delete_partial_unique_keys.sql` 이 이미 `deleted_at IS NULL` 조건을 쓴다. **세 조건이 겹치는 형태**가 되므로 V206 의 인덱스를 그대로 두면 안 된다. 검증: 같은 키를 전역 1건 + 프로젝트 2건 + 삭제된 1건으로 넣어 본다.
- **verify**: 마이그레이션 재실행 멱등 · 기존 시드 4건 전부 `project_id IS NULL` · 위 4조합 INSERT 성공/충돌이 의도대로

### Task 1-2. `workflow_schemes.project_id` 추가
- 같은 마이그레이션 파일. `workflow_schemes.key VARCHAR(30) NOT NULL UNIQUE` 도 동형 재편.
- `ix_workflow_schemes_key_active`(V201:41) 가 `deleted_at IS NULL` 부분 인덱스다 — 같이 본다.
- **verify**: V201 시드 4건 `project_id IS NULL` · 표준 스킴 잠금(`is_default`) 동작 불변

### Task 1-3. jOOQ 재생성 + 스냅샷
- `project-workflow` 는 jOOQ 사용 BC. `init_codegen.sql` 미러 갱신 필요.
- **verify**: `./gradlew :modules:project-workflow:generateJooq` 후 컴파일 통과

### Task 1-4. 마이그레이션 검증 테스트
- Testcontainers 로 실 스택. 「컬럼이 생겼다」가 아니라 **부분 유니크가 실제로 무는지**를 잰다.
- **verify**: red 1회 확인 후 green

---

## PR ② 스코프 결정 + CRUD 판정 (T2)

### Task 2-1. 도메인에 소유 개념 추가
- `WorkflowScheme` · `Workflow` 에 `projectId: UUID?`.
- **verify**: 기존 생성자 호출부 전량 컴파일 (기본값 `null` 로 두면 조용히 전역이 된다 — **기본값을 주지 말고** 컴파일 에러로 호출부를 전수 방문한다. 폭발 반경을 먼저 재고, 174곳 급이면 확장함수로 완화)

### Task 2-2. 컨트롤러 스코프 결정
- `WorkflowSchemeController` 7곳의 `WorkflowSchemeScope.Global` 하드코딩 제거.
- 규칙 — 대상 스킴의 `project_id` 가 `NULL` 이면 `Global`, 값이 있으면 `Project(그 프로젝트 키)`.
- 생성은 요청 바디의 `projectKey` 유무로 가른다.
- ★**복제가 주 사용 경로다.** `POST /workflows/{key}/duplicate` 가 대상 `projectKey` 를 받아 사본에 실어야 한다. 안 실으면 복제본도 전역이 되어 여전히 못 고친다 — Jira 가 권장 우회로 명시하는 경로가 그 자리에서 죽는다.
- **verify**: red-first. PROJECT_ADMIN 이 자기 프로젝트 스킴을 만들면 200, 남의 프로젝트면 403, 전역이면 403. 전역 워크플로우를 자기 프로젝트로 복제하면 200 이고 **사본의 `project_id` 가 그 프로젝트**다

### Task 2-3. 목록 필터
- `appService.list()` 가 전량을 준다 → 「전역 + 내가 멤버인 프로젝트 것」으로 좁힌다.
- ★`ProjectWorkflowSchemeController.listAssignableSchemes` 도 같은 필터를 타야 한다. 지금은 `appService.list()` 전량이다(`:172`).
- **verify**: 프로젝트 A 어드민에게 프로젝트 B 스킴이 안 보인다

### Task 2-4. 스코프 결정 전수 판별식 (스펙 §6-1)
- CRUD 엔드포인트 목록 ↔ 스코프 결정 호출 목록을 **차집합**으로 대조. `Global` 하드코딩 잔존 0 을 단언.
- **verify**: 한 곳을 일부러 `Global` 로 되돌려 red 1회

### Task 2-5. 키 해석 호출부 전수 판별식 (스펙 §6-2)

★**실측으로 규모가 줄었다.** `WorkflowKeyResolver` 는 이미 두 메서드 다 `projectKey` 를 첫 인자로 받는다(`resolveStart` `:57` · `resolveExisting` `:87`). 프로젝트 → 배정 스킴 → 이슈 타입 → 워크플로우 순으로 풀기 때문에, issue-tracking 은 **워크플로우를 맨키로 특정한 적이 없다.** 30 참조 / 11 파일 / 3 BC 라는 숫자는 「고쳐야 할 호출부」가 아니라 「이미 프로젝트 스코프인 호출부」다.

- 따라서 이 task 는 **변경이 아니라 동결**이다. 「키 단독 조회 경로가 새로 생기지 않았다」를 판별식으로 못박는다.
- 대상 — `WorkflowRepository` 계열에서 `findByKey(key)` 처럼 프로젝트 없이 워크플로우를 특정하는 자리. 전역 템플릿 조회는 정당하므로 **의도를 명시**한 호출만 허용하고 나머지는 red.
- **verify**: 비-공허 짝 — 프로젝트 없는 조회를 한 곳 심어 red 1회

---

## PR ③ 공용 스코프 + 워크플로우 정의 권한 (T3 · 보안 · shared-kernel)

★티어가 T2 → **T3** 로 올라갔다. Task 3-0 이 `shared-kernel` 을 건드리기 때문이다(작업 티어표 — shared-kernel 은 T3). ADR 이 필요하고 리뷰에 ceo 가 붙는다.

### Task 3-0. shared-kernel 공용 `WorkflowScope` 신설 (스펙 D8 확정)
- 중립 이름의 스코프 타입 하나를 `com.bts.shared.permission` 에 둔다. `Global` · `Project(key)` 2분기.
- `WorkflowSchemeScope` 사용처를 치환한다(1회). **`WorkflowSchemeScope` 를 일반화해 재사용하지 않는다** — 이름에 `Scheme` 이 남으면 `WorkflowDefinitionPermission` KDoc 이 경고한 개념 혼동을 그대로 일으킨다. 그 KDoc 때문에 enum 은 이미 둘로 갈라져 있다.
- **금지**: 정의 resolver 전용 스코프 타입을 따로 두는 것. 두 타입은 서로를 검사하지 않는 두 목록이 된다.
- **verify**: `WorkflowSchemeScope` 잔존 0 · 두 resolver 가 같은 타입을 받는다

### Task 3-1. `WorkflowDefinitionPermissionResolver` 에 스코프 도입
- 지금은 인자가 `(actorId, permission)` 뿐이다(`:34-38`). Task 3-0 의 공용 `WorkflowScope` 를 받게 넓힌다.
- **verify**: 스펙 §4 D6 표 4행이 각각 red-first

### Task 3-2. DELETE 는 SYSTEM_ADMIN 유지
- D6 표대로 `DELETE` 만 전역 판정을 남긴다.
- **verify**: PROJECT_ADMIN 이 자기 프로젝트 워크플로우를 지우려 하면 403

### Task 3-3. 권한 매트릭스 ↔ 엔드포인트 짝 판별식 (스펙 §6-3)
- D6 표를 기계가 읽어 실제 판정과 대조. 표만 고치고 코드가 안 따라오는 drift 차단.
- **verify**: 표의 한 행을 바꿔 red 1회

---

## PR ④ 프로젝트 설정 UI (T1)

### Task 4-1. 라우트 2종 추가
- `/projects/$projectKey/settings/workflows` — 목록
- `/projects/$projectKey/settings/workflows/$workflowKey` — 편집기
- 가드는 형제 라우트를 승계해 **`requireAuthAndPasswordChanged`** 를 쓴다(`router.ts:529`).
- ★**프론트 어드민 가드를 새로 만들지 않는다.** 실측 — 프로젝트 설정 라우트 12종에 프로젝트 스코프 어드민 가드가 하나도 없다. `ProjectTree.tsx:155` 주석도 「GAP-1 전 인증자 표시 — 게이팅 없음」이라 적고 있다. 권한은 백엔드 403 이 담당하고 화면은 안내 카드로 받는 것이 이 저장소의 지배 관례다(`ForbiddenSchemeCard` 선례).
- **verify**: 비-어드민 멤버가 들어가면 403 안내 카드가 뜬다(빈 화면·무한 로딩 아님)

### Task 4-2. 기존 편집기 재사용 (스펙 §4 D3)
- `<WorkflowEditorPage workflowKey={...} />` 를 그대로 마운트. 라우트 어댑터는 `admin.workflows.$workflowKey.tsx`(32줄)와 같은 두께.
- **금지**: 편집기 내부에 프로젝트 분기 추가. 복제.
- **verify**: 편집기 컴포넌트 diff 0 줄

### Task 4-3. 스킴 관리도 프로젝트 설정으로
- 기존 `/projects/$projectKey/settings/workflow-scheme`(배정 전용)에 **생성·편집**을 얹는다. `WorkflowSchemeSidebar` · `MappingTable` · `SchemeMetaPanel` 재사용.
- **verify**: 프로젝트 어드민이 스킴을 만들고 그 자리에서 배정까지 된다

### Task 4-4. 사이드바 링크
- `ProjectTree.tsx` `SETTINGS_LINKS` 12종 → 13종(「워크플로우」 추가).
- ★그 배열에 개수가 주석으로 박혀 있다(`설정 그룹 서브링크 12종`). 같이 고친다.
- **verify**: 링크 렌더 + 이동

### Task 4-5. E2E
- 프로젝트 어드민으로 로그인 → 설정 → 워크플로우 생성 → 편집 → 저장 → 스킴에 매핑 → 이슈에서 그 상태가 보인다.
- ★유닛은 `useNavigate`·권한 훅이 목이라 통과한다. 실제 URL·권한 응답을 보는 E2E 가 유일한 증거다.
- **verify**: 비-어드민 멤버 시나리오도 함께(403 화면)

---

## 착수 전 확인 — **전부 해소됐다. 착수 가능.**

1. ~~`WorkflowKeyResolver` 호출부 규모~~ — **해소.** 이미 전 호출부가 `projectKey` 를 넘긴다(위 Task 2-5). 변경 대상이 아니라 동결 대상이다.
2. ~~스코프 타입 공용화 형태~~ — **확정(Maxi 2026-09-08).** shared-kernel 공용 `WorkflowScope` 신설. 스펙 D8 · Task 3-0. ADR 대상은 그대로다.
3. ~~기존 프로젝트 설정 라우트의 어드민 가드~~ — **해소.** 프론트에 프로젝트 어드민 가드가 없다(설정 12종 전부 `requireAuthAndPasswordChanged`). 신설하지 않고 백엔드 403 + 안내 카드 관례를 따른다(위 Task 4-1).

## FR 동기화 — PR ① 에 포함 (스펙 D7 확정)

### Task 1-0. FR-WF-08 등재 + 카운트 전수 동기화
- `docs/rules/fr-sync-checklist.md` **전 항목**을 따른다. 이 계획에 대상 개수를 새기지 않는다 — 개수 리터럴이 drift 원천이다.
- 145 → 146. 마스터플랜 · SDD · `product/<bc>.md` · CLAUDE.md 헤더 · fr-index 등.
- **verify**: `bash scripts/verify-master-plan.sh` EXIT=0. 실패(EXIT 4)면 누락된 정본을 **같은 PR 에서** 채운다
- ★이 task 를 PR ① 에 넣는 이유 — 마이그레이션 PR 이 이 FR 의 첫 착지다. 뒤 PR 로 미루면 그 사이 `verify-master-plan` 이 계속 빨간불이다.
