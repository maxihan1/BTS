# ADR: 프로젝트 멤버십 모델 — BC 소유권 · 역할 · 부트스트랩

> 날짜: 2026-06-01
> 상태: 채택
> 관련 FR: FR-PM-01 (프로젝트 행정 — 관리자/멤버 관리), 백엔드 D1~D5
> 관련 BC: identity-access
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md), [05. 데이터 모델](../sdd/05-data-model.md)

## 맥락

FR-PM-01은 "프로젝트 관리자가 멤버를 초대/제거한다"를 구현한다. 그러나 착수 시점 코드 상태를 조사한 결과 다음이 확인됐다.

- `projects` 테이블은 **issue-tracking** 모듈에 실재한다 (`V001__issues_initial.sql`, id 타입 **UUID**, 컬럼 `key/name/key_sequence/created_at/updated_at/deleted_at`). 단 도메인 엔티티·`lead_id`(프로젝트 리드 칸)는 없다.
- **프로젝트 생성 기능(API/서비스)은 미구현**이다. `projects`에 행을 넣는 곳은 개발용 seed(`data-dev.sql`)와 테스트 fixture뿐.
- `ProjectRole`·권한 가드(`@PreAuthorize`)·권한 스킴은 전부 **미구현**. 포트 계층(`IssuePermissionResolver`, `WorkflowSchemePermissionResolver`)만 stub로 존재.
- `User`는 identity-access 모듈, id 타입 **UUID** (`V001__users.sql`).
- SDD 12.6은 역할 8종(OrgAdmin/ProjectAdmin/ProjectLead/ProjectMember/Reporter/Assignee/Watcher + 커스텀)을, SDD 05.3은 `Project.id`를 BIGINT로 설계했으나 **둘 다 실제 코드와 괴리**(실제는 UUID, 역할 0개).

이 상태에서 "관리자만 멤버를 초대할 수 있다"를 그대로 구현하면 **최초 관리자가 생기는 경로가 없는 닭-달걀 문제**가 발생한다.

## 결정

### D1. project_memberships는 identity-access 모듈이 소유한다

권한 평가(프로젝트 행정 권한)는 identity-access BC의 책임([identity-access.md §책임](../../Maxi_wiki/BTS/domain/identity-access.md)). 멤버십은 권한의 1차 소스이므로 identity-access가 소유한다.

```
project_memberships(
    id           UUID PK,
    project_id   UUID NOT NULL,   -- issue-tracking projects 참조 (FK 없음, 아래 D2)
    user_id      UUID NOT NULL,   -- identity-access users(id) FK
    role         VARCHAR NOT NULL CHECK (role IN ('PROJECT_ADMIN','MEMBER')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)   -- 한 사용자는 한 프로젝트에 멤버십 1개
)
```

### D2. project_id는 cross-BC 참조 — FK를 걸지 않는다

`projects`는 issue-tracking 모듈의 Flyway 마이그레이션이 만든다. identity-access 마이그레이션이 다른 모듈 테이블에 외래키를 직접 거는 것은 BC 격리 원칙(CLAUDE.md §핵심 패턴 "BC 격리 — 다른 BC 직접 import 금지")과 모듈별 마이그레이션 구조에 어긋난다. 따라서 `project_id`는 **FK 없는 UUID 참조**로 두고, 존재 검증은 애플리케이션 레벨에서 수행한다.

- `user_id`는 같은 모듈 `users(id)` 참조이므로 **FK를 건다**(같은 BC 내부, 무결성 강제 가능).
- `project_id` 무결성은 **`ProjectDirectory` 포트**(identity-access 내부 인터페이스)로 검증. 구현 `JdbcProjectDirectory`는 같은 DB의 `projects`를 read-only 조회(`SELECT 1 FROM projects WHERE id=:id AND deleted_at IS NULL`). issue-tracking 코드 import는 없고 DB 레벨 read만 — issue-tracking도 거꾸로 `issues.reporter_id`로 `users`를 FK 없이 참조하는 대칭 선례 존재.

**Deployment invariant (C3, 리뷰 반영)**. 이 read-only 조회는 identity-access와 issue-tracking이 **동일 PostgreSQL 인스턴스·동일 스키마(`public`)를 공유**한다는 배포 토폴로지에 의존한다(현 Naver Cloud 단일 호스트 Docker Compose 충족). 두 BC를 별도 DB로 분리하면 `ProjectDirectory`를 SPI 호출 또는 이벤트 기반으로 교체해야 한다. 또한 두 앱이 같은 DB에 독립 Flyway를 돌리므로, `projects` 테이블 부재 시 `JdbcProjectDirectory`는 명확한 예외를 던진다(silent false 금지). `projects`가 의존하는 컬럼은 `id`, `deleted_at` 둘뿐이라 schema drift 표면은 최소이나, 통합테스트가 이를 가드한다.

### D3. ProjectRole은 2종으로 최소 정의한다

`ProjectRole = { PROJECT_ADMIN, MEMBER }`.

- `PROJECT_ADMIN` — 멤버 초대/제거/역할 변경 + (후속) 프로젝트 설정 변경.
- `MEMBER` — 일반 멤버.

SDD 12.6의 8종 역할과 권한 매트릭스/스킴은 **후속 FR-PM-02~07**이 담당한다. FR-PM-01의 단어("관리자/멤버 관리")에 맞춰 의도적으로 최소화한다(CLAUDE.md §2 단순성 — 요청 범위 밖 추상화 금지). `ProjectLead`는 권한 차이가 아직 정의되지 않아 도입 시 과설계가 되므로 보류.

### D4. 부트스트랩 — 멤버 0명 프로젝트의 첫 멤버는 자동 PROJECT_ADMIN

프로젝트 생성 기능이 없는 현 상태에서 최초 관리자 경로를 다음 불변식으로 시동한다.

- 멤버가 **0명**인 프로젝트에 첫 멤버를 추가하면, 요청된 role과 무관하게 그 멤버는 **PROJECT_ADMIN**으로 부여된다.
- **안전장치 (리뷰 B2 반영)**. 이 부트스트랩 첫 추가는 (a) **JWT 인증 전용**(PAT 봇 거부 — 봇 자동화로 임의 프로젝트 ADMIN 탈취 차단, B1), (b) **자기 자신만**(userId == actorId — 타인을 첫 ADMIN으로 꽂는 악용 차단), (c) **audit emit 필수**(권한 상승 사건 추적, C5)를 만족해야 한다. 타인 추가나 PAT 요청은 부트스트랩으로 처리하지 않고 비멤버 요청으로서 404(존재 숨김)로 응답한다.
- 멤버가 **1명 이상**이면, 멤버 추가/제거/역할 변경은 해당 프로젝트의 **PROJECT_ADMIN만** 수행 가능(JWT/PAT 동일, D5). 비멤버에게는 프로젝트 존재를 숨겨 404로 응답한다(B3 — 세션 IDOR 404 선례 일치).
- 마지막 PROJECT_ADMIN은 자신을 제거하거나 MEMBER로 강등할 수 없다(프로젝트가 관리자 없는 상태로 빠지는 것 방지). 이 검사와 부트스트랩 카운트는 프로젝트 단위 advisory lock 하에서 평가해 read-then-write race(둘 다 ADMIN / admin 0명)를 차단한다(C1/C2).

**잔존 위험 (의식적 수용)**. 자기자신 제한 + JWT 전용으로 좁혔으나, "멤버 0명 프로젝트"가 prod에 존재하면 그 프로젝트를 아는 인증 사용자가 자신을 ADMIN으로 만들 수 있다. 현재는 프로젝트 생성 API가 없어 `projects` 행이 dev seed/테스트로만 생기므로 prod 노출이 작다. **프로젝트 생성 FR 도입 전까지 "멤버 0명 프로젝트"가 prod에 노출되지 않도록 seed 격리**를 운영 가드로 둔다. 프로젝트 생성 API(생성자=자동 ADMIN)가 들어오면 이 부트스트랩은 "생성 시 생성자 멤버십 1행 삽입"으로 대체되어 창문이 닫힌다.

### D5. 가드는 멤버십 자기참조로 평가한다

멤버 변경 API의 권한은 외부 권한 스킴(FR-PM-02) 없이 `project_memberships` 자기참조로 평가한다. "요청 actor가 대상 project_id에 PROJECT_ADMIN 멤버십을 보유하는가". 서비스 레벨 가드(멤버십 조회)로 구현한다. PAT/JWT 모두 actorId를 제공하므로 동일하게 평가하되, actor 추출 경로가 다르다(JWT=`jwt.subject`, PAT=`SecurityContext` principal String→UUID, B1). 비멤버는 존재 숨김 위해 404로 응답한다(B3).

### D6. 멤버십은 hard delete (DATA.md §1 7번 ADR 요건 충족)

멤버 제거는 행을 물리 삭제(hard delete)한다. 이슈 키 같은 외부 영구 인용 대상이 아니고, 제거된 멤버십은 재가입 시 새 행으로 충분하다. DATA.md §1 7번이 "하드 삭제는 ADR 필수"를 요구하므로 본 항목으로 명문화한다. `user_id` FK는 `ON DELETE CASCADE`라 사용자 삭제 시 멤버십도 정리된다.

## 결과 / 트레이드오프

- **장점**. BC 격리 유지. 외부 권한 시스템 없이 자기완결적. 프로젝트 생성 FR과 독립적으로 멤버십 CRUD 완성. 후속 FR 확장 여지(role enum 추가, 권한 매트릭스).
- **비용**. `project_id` 무결성을 DB FK가 아닌 애플리케이션 레벨로 보장 → 검증 누락 시 orphan 멤버십 위험. spec에서 검증 경로를 명시하고 테스트로 가드한다.
- **부트스트랩의 한계**. "멤버 0명 프로젝트 첫 추가는 누구나 가능"은 프로젝트 생성 가드가 없는 현 상태의 임시 정합. 프로젝트 생성 FR 도입 시 "생성자만 첫 멤버" 로 강화된다. 이 한계를 spec §엣지 케이스와 plan §리스크에 명시한다.
- **SDD 괴리 정정**. SDD 05.3(BIGINT) / 12.6(8종 역할)은 stale. 실제 UUID + 2종 역할 채택. SDD 본문 갱신은 문서 정리 작업으로 후속(이 ADR이 현재 정본).

## 해결됨 (spec + 리뷰에서 확정)

- `project_id` 존재 검증 — `ProjectDirectory` read-only 포트 (D2).
- repository 패턴 — NamedParameterJdbcTemplate (jOOQ 미사용, 조사 확정).
- API 표면 — `/api/v1/projects/{projectId}/members` CRUD, snake_case 에러코드 8종(spec).
- PAT 정책(B1) — CRUD 허용, 부트스트랩 JWT 전용.
- 부트스트랩 안전장치(B2) — 자기자신 + JWT + audit.
- 정보노출(B3) — 비멤버 404 통일.
- 동시성(C1/C2) — 프로젝트 단위 advisory lock.
- audit(C5) — 이번 PR 포함, AuthAuditLogService 재사용.
