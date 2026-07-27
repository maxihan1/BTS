# ADR: 댓글 수정·삭제의 모더레이션 정책 — 수정은 작성자 한정, 삭제는 `SOFT_DELETE` 보유자까지

> 날짜: 2026-07-27
> 상태: 채택 (Accepted)
> 관련 FR: **FR-CO-02** (댓글 수정 + 삭제) — 카운트 불변 131
> 선행 ADR: `2026-07-27-fr-co-comment-feature.md` §D2 · 잔여위험 1 — 이 결정을 여기로 넘긴 곳
> Plan: `docs/plans/2026-07-27-fr-co-02.md`
> PR: #316
> BC: issue-tracking 단일

## 맥락 (Context)

선행 ADR 은 FR-CO 를 2분할하면서 **"누가 남의 댓글을 지울 수 있나"** 를 의도적으로 미결로 남겼다
(§D2 말미 — *"CO-02 는 이 선례를 따를지(대칭) 모더레이션을 도입할지(비대칭)를 자기 ADR 에서 결정한다.
이 ADR 은 예고만 한다."*). 이 ADR 이 그 결정이다.

### 실측 1 — Worklog 선례는 작성자 한정이 맞다

| 지점 | 코드 |
|---|---|
| `WorklogService.update:254` | `if (existing.authorId != actor.value) throw IssueAccessDeniedException(...)` |
| `WorklogService.delete:328` | 동일 |
| 클래스 KDoc `:58-59` | *"worklog update/delete 는 작성자(authorId == actor)만 허용한다"* |

**관리자 우회 분기는 0건이다.** 선례 기록(`domain/issue-tracking.md:20`)이 정확했다.

### 실측 2 — 그런데 선례가 전이되지 않는다 (생산자 비대칭)

메모리 [[sibling-precedent-validation-placement-depends-on-producer-count]] 의 판별식을 재적용했다.
FR-CO-01 D7 이 **본문 길이 검증 위치**에서 이미 한 번 걸린 그 판별식이고, 이번엔 **권한 정책**에서
같은 축이 다시 갈린다.

| 생산자 | Worklog | Comment |
|---|---|---|
| REST (사람) | `create` | `create` |
| Import | `createImported` | `createImported` |
| **automation** | **0건** (전수 grep 결과 automation 모듈에 worklog 언급 자체가 없음) | **`AddCommentAction` 존재** |

자동화 댓글의 저작자를 끝까지 추적했다 (추정 아님).

```
ActionExecutor.kt:159   ExecutionEnv(rule.actorUserId, …)
ActionExecutor.kt:236   issueMutationPort.addComment(AddCommentCommand(env.actorId, key, body, dryRun))
AutomationIssueMutationAdapter.kt:153   commentApplicationService.create(actor, key, body)
CommentApplicationService.kt:99         authorId = actor   ← create 가 강제 (FR-CO-01 D4)
```

**결론.** 자동화가 만든 댓글의 저작자는 **룰 소유자(`rule.actorUserId`)** 다. 작성자 한정을 그대로
적용하면 이 댓글들은 **룰 소유자 한 사람만** 지울 수 있다. 룰 하나가 여러 프로젝트에 걸쳐 댓글을
생산하는데, 정작 그 이슈를 보는 사람들에게는 삭제 수단이 없다. Worklog 에는 이 생산자가 없어서
이 문제 자체가 존재하지 않았다 — **선례가 조용했던 이유는 답이 같아서가 아니라 질문이 없어서다.**

### 실측 3 — 새 권한을 만들 필요가 없다

선행 ADR §D3 이 `ADD_COMMENT` enum 신설을 **폭발 반경**(shared-kernel enum + prod resolver 분기 +
권한 시드 마이그레이션 + `PermissionSchemaMigrationTest` 커플링 + `enum-add-breaks-crossmodule-count-guard`
회귀) 때문에 기각했다. 모더레이션에 새 enum 이 필요했다면 같은 벽에 부딪혔을 것이다.

부딪히지 않는다. 기존 `IssuePermission.SOFT_DELETE` 가 그대로 쓸 수 있는 자리에 있다.

```
IssuePermission.SOFT_DELETE
  → IdentityAccessIssuePermissionResolver.kt:178   "DELETE_ISSUE"
  → V008__permission_schemes_and_role_permissions.sql:66   PROJECT_ADMIN 단독 부여
     (:64 주석 — "MEMBER: CREATE_ISSUE + EDIT_ISSUE (2행, DELETE 제외)")
```

**`SOFT_DELETE` 는 현재 시드에서 정확히 PROJECT_ADMIN 을 뜻한다.** 즉 "PROJECT_ADMIN 우회 허용" 과
"`SOFT_DELETE` 재사용" 은 지금 같은 것이고, 후자로 표현하면 **신규 enum 0 · 신규 마이그레이션 0 ·
신규 resolver 분기 0** 이다.

## 결정 (Decision)

### D1. 수정은 **작성자 한정**, 삭제는 **작성자 + `SOFT_DELETE` 보유자** (Maxi 확정 = B안)

| 연산 | 권한 게이트 | 근거 |
|---|---|---|
| `PATCH` (수정) | `IssuePermission.UPDATE` + `existing.authorId == actor` **필수** | 저작 위조 표면을 열지 않는다 |
| `DELETE` (삭제) | `IssuePermission.UPDATE` + (`existing.authorId == actor` **또는** `SOFT_DELETE` 보유) | 모더레이션 수단 확보 |

**왜 수정과 삭제를 가르는가.** 실제로 필요한 것은 "지우기" 다 — 부적절한 댓글 제거, 퇴사자 댓글
정리, 자동화가 남긴 댓글 청소. "남의 글 고치기" 는 그 필요를 채우지 못하면서 **기록의 신뢰만 깎는다.**
관리자가 타인 명의의 글 내용을 바꿀 수 있으면, 그 글이 원래 무엇이었는지 아무도 알 수 없다.

이는 FR-CO-01 §D4 와 **같은 방향**이다. D4 는 저작자 위조를 "필드를 두고 무시" 가 아니라
"필드가 없음" 으로 닫았다. 여기서도 관리자에게 수정 권한을 주지 **않는 것**으로 같은 표면을 닫는다.

Jira 도 `Edit All Comments` / `Delete All Comments` 를 별도 권한으로 나눈다. 업계 관행과도 일치한다.

### D2. `SOFT_DELETE` 를 **재사용**한다 — 신규 enum 기각

| 대안 | 판정 |
|---|---|
| **A. `SOFT_DELETE` 재사용** | ✅ **채택.** 실측 3 의 체인이 이미 PROJECT_ADMIN 을 가리킨다. 신규 코드 0 |
| B. `MODERATE_COMMENT` enum 신설 | ❌ **기각.** 선행 ADR §D3 · PR #314 의 `VIEW_SCHEME` 기각과 동일 사유 |

**감수하는 것.** `SOFT_DELETE` 의 KDoc 은 *"이슈 소프트 삭제 권한"* 이고 매트릭스 코드도
`DELETE_ISSUE` 다. 댓글 삭제에 쓰면 **이름이 의미보다 좁다.** 이 의미 확장을 KDoc 에 명시한다
(`IssuePermission.kt` 의 §검증 엔드포인트 표에 행 추가). 권한 스킴이 프로젝트별로 커스터마이즈되면
"이슈는 못 지우는데 댓글은 지우는" 역할을 만들 수 없다는 제약이 생긴다 — 그 요구가 실제로 오면 B 를
재검토한다.

### D3. 삭제는 **소프트**, 마이그레이션 **0건**

스키마가 이미 갖춰져 있다 (`V035__comments.sql`).

| 컬럼 | 상태 |
|---|---|
| `deleted_at TIMESTAMPTZ NULL` | `:13` 존재. `COMMENT ON COLUMN :19` — *"NULL=활성, NOT NULL=삭제됨"* |
| `updated_at TIMESTAMPTZ NOT NULL` | `:12` 존재 |
| 부분 인덱스 | `:22` `WHERE deleted_at IS NULL` — 활성 댓글 목록 조회가 이미 삭제분을 제외 |

`CommentRepository.listByIssue:64` 가 `COMMENTS.DELETED_AT.isNull` 필터를 이미 적용한다. 따라서
**삭제된 댓글은 목록에서 자동으로 사라지고, 목록 API 계약은 변경 0** 이다 (선행 ADR §D6 "목록
페이지네이션 없음 = 회귀 0" 과 같은 성격).

`Comment` 도메인은 전 필드 `val` 이고 소프트 삭제를 도메인에 노출하지 않는다(KDoc 명시, worklog 선례).
**이 방침을 유지한다** — `softDelete` 는 리포지토리 내부에서만 처리하고 도메인에 `deletedAt` 필드를
추가하지 않는다.

## 검증 (Verification)

| 대상 | 방법 |
|---|---|
| D1 수정 = 작성자 한정 | 제3자·PROJECT_ADMIN **모두** 403 임을 음성 판정자로 고정. **PROJECT_ADMIN 이 403 인 것이 핵심 케이스** — 여기가 green 이면 C안으로 새어나간 것 |
| D1 삭제 = 작성자 + `SOFT_DELETE` | 3클래스 전수 — 작성자 200 · `SOFT_DELETE` 보유자 200 · 둘 다 아닌 `UPDATE` 보유자 403 |
| D1 자동화 댓글 | 룰 소유자가 아닌 PROJECT_ADMIN 이 자동화 생성 댓글을 삭제할 수 있음을 통합 테스트로 고정 (실측 2 가 든 근거가 실제로 해소되는지) |
| D2 신규 enum 0 | `IssuePermission` enum 값 개수 불변 · 시드 마이그레이션 추가 0 |
| D3 마이그레이션 0 | `backend/modules/issue-tracking/src/main/resources/db/migration/` diff 0 |
| 목록 계약 무변경 | 기존 `CommentControllerIntegrationTest` 목록 테스트 **무수정 green** |
| 봉인 실효 | 뮤테이션 — 수정 경로의 `authorId` 비교 제거 / 삭제 경로의 `SOFT_DELETE` 분기를 무조건 true 로 주입. 각각 red 확인 |

**음성 가드 주의.** 메모리 [[negative-guard-needs-body-discriminator]] — "여전히 403" 만으로는
vacuous 하다. 403 의 본문 판별자(어떤 권한·어떤 scope 로 거부됐는지)까지 단언한다.

## 결과 (Consequences)

**좋아지는 것.**
- 부적절 댓글·퇴사자 댓글·자동화 잔여 댓글에 대한 **운영 대응 수단**이 생긴다.
- 저작 위조 표면은 **열리지 않는다** — 관리자도 남의 글 내용을 바꿀 수 없다.
- `IssuePermission` 이 커지지 않는다. 9 모듈 재컴파일·시드 마이그레이션·테스트 커플링 회피.

**나빠지는 것 / 감수하는 것.**
- **Worklog 와 비대칭**이 생긴다 — 작업로그는 관리자가 못 지우는데 댓글은 지운다. 정당한 비대칭
  (생산자 구성이 다르다) 이지만, 사용자에게는 일관성 없어 보일 수 있다. 후속 정합성 검토 대상.
- `SOFT_DELETE` 가 이름보다 넓은 의미를 갖는다 (D2 감수 항목).
- 삭제 경로의 권한 분기가 2갈래라 테스트 행렬이 늘어난다 (3클래스).

### 잔여 위험

| # | 항목 | 처리 |
|---|---|---|
| 1 | **Worklog 비대칭** — 같은 논리를 적용하면 Worklog 삭제에도 모더레이션이 필요한가? Worklog 는 automation 생산자가 없어 근거가 약하지만, 퇴사자 잔여 기록 문제는 동일하다 | `TODOS.md` 등재. FR-WL 소관 |
| 2 | **Import 댓글의 저작자 귀속** — `createImported` 는 원본 저작자를 보존한다. 그 저작자가 BTS 사용자로 매핑되지 않는 경우 작성자 한정 수정은 **아무도 못 한다**(삭제는 D1 로 해소됨). 수정 불가가 의도인지 미확인 | 스펙 단계에서 Import 매핑 fallback 확인 후 판정 |
| 3 | **수정 이력 미보존** — `updated_at` 만 갱신하고 이전 본문은 남기지 않는다. "수정됨" 표시 여부와 이력 보존은 스펙 단계 결정 | `/bts-spec` D 항목 |
| 4 | `bodyHtml` 미소비 (FR-CO-01 잔여) — 프론트가 `body` 원문을 텍스트로 렌더해 Markdown 서식이 적용되지 않는다. 수정 UI 를 만들면 **입력과 표시의 불일치가 더 눈에 띈다** | 이 PR 에서 함께 판단할지 스펙 단계에서 결정 |

## 함정 기록 (다음 사람을 위해)

1. **형제 선례의 "침묵" 을 동의로 읽지 말 것.** Worklog 에 모더레이션이 없는 것은 "필요 없다고
   판단해서" 가 아니라 **자동화 생산자가 없어 질문이 제기되지 않아서** 다. 선례를 인용하기 전에
   "그 선례가 이 조건을 실제로 겪었나" 를 확인한다. FR-CO-01 D7 에 이어 **같은 판별식이 두 번째로
   갈렸다.**
2. **새 권한이 필요한지 판단하기 전에 기존 enum 의 시드 부여 대상을 확인할 것.** `SOFT_DELETE` 는
   이름이 `DELETE_ISSUE` 라 댓글과 무관해 보이지만, 시드상 정확히 PROJECT_ADMIN 이었다.
   **enum 이름이 아니라 매트릭스 코드 → 시드 행까지 따라가야** 재사용 가능 여부가 보인다.
3. **"PROJECT_ADMIN 우회" 라는 표현이 함정이다 — 단, "역할 직접 검사는 BTS 에 없다" 도 틀렸다.**
   초안이 그렇게 적었다가 실측에서 반증됐다. `ProjectSecuritySchemeService.kt:170` 이
   `membership?.role != ProjectRole.PROJECT_ADMIN` 으로 **역할을 직접 검사**한다(프로덕션 99 발생).

   그런데 그 KDoc `:39-43` 이 사유를 밝힌다 — *"프로젝트 행정 권한코드(`ADMIN_PROJECT`)가
   `role_permissions` 에 시드되어 있지 않다(V008~V014 확인). 따라서 매트릭스 대신 멤버십을 조회해
   직접 확인한다"*. **선호 패턴이 아니라 시드 공백에 대한 우회책이다.**

   댓글은 그 공백이 없다 — `DELETE_ISSUE` 가 실제로 시드돼 있다(실측 3). 따라서 권한 경로가
   **사용 가능하고, 그래서 선호된다**(메모리 `crossbc-permission-resolver-not-role-lookup`).
   결정을 역할이 아니라 **권한 이름**으로 표현해야 구현이 선을 넘지 않는다.

   교훈은 두 겹이다 — (a) 결정은 권한으로 표현할 것, (b) **"BTS 에 X 가 없다" 류의 전칭 단언은
   grep 하고 쓸 것.** 이 ADR 초안이 정확히 그걸 어겼다.
