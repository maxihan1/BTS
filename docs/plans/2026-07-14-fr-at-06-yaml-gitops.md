# FR-AT-06 YAML 가져오기/내보내기 (GitOps) — 백엔드 (D1~D5)

> slug: fr-at-06-yaml-gitops
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-14

## Brief

자동화 규칙(트리거·조건·액션)을 YAML로 내보내고(`GET /api/v1/automation/export`) YAML을
올려 일괄 생성/갱신하는(`POST /api/v1/automation/import`) GitOps 기능. 이번 PR은 백엔드
D1~D5(도메인·YAML 스키마 명세·데이터 모델 활용·엔드포인트·round-trip 테스트). UI(D6/D7)는 후속 PR.

classify: type=backend, agent=backend-engineer (classify-task가 '스키마' 키워드로 migration
오분류 → product doc D3=활용/no migration, D4 책임=backend-engineer 근거로 교정).

## 도메인 정리

- **BC**: automation (`backend/modules/automation/`, `com.bts.automation`)
- **영향 엔티티**: 신규 없음. 기존 `AutomationRule`(+ `Trigger`/`Condition`/`Action`) 애그리게이트를 YAML로 직렬화/역직렬화. D3 = 활용 확인 (신규 마이그레이션 없음).
- **저장 구조**: 규칙 1개 = `automation_rules` 1행 + `automation_actions` N행(position 순) + `automation_conditions` 0..1행 (V300/V302/V304). YAML 문서 하나가 이 세 조각을 담아야 round-trip 성립.
- **새 유비쿼터스 개념**: "규칙 YAML (GitOps)" — 자동화 규칙을 사람이 읽는 YAML로 표현해 버전 관리·이식. glossary 추가 후보(Maxi 승인 대기).
- **기존 결정 충돌**: 없음. 프로덕션 YAML 선례 = project-workflow `YamlSeedService`(내부 전용 `ObjectMapper(YAMLFactory())`로 기본 JSON mapper 오염 방지 — 이 패턴 이식).
- **관련 ADR**: FR-AT-01~05 ADR 소비. 신규 ADR 후보 = FR-AT-06 import 시맨틱(스펙 확정 후 생성).

### 도메인 모델 참조 (스키마 설계 근거)

- `AutomationRule`: id(UUID·앱 생성), projectKey, name(≤200), enabled, triggerType(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK), triggerConfig(JSON 문자열), actions(순차), condition(nullable sealed 트리), webhookTokenHash(WEBHOOK 전용·export 제외), nextFireAt(SCHEDULED 전용·파생), createdBy, actorUserId(기본=createdBy), createdAt/updatedAt, deletedAt(소프트삭제), version(OCC). **priority 필드 없음**.
- `Condition`: sealed And/Or/Not/Comparison. 와이어 = JSONLogic 부분집합. `MAX_DEPTH=10`·`MAX_NODES=100`·`FIELD_WHITELIST`(issue.key/type/status/priority/assignee/reporter/labels/summary/projectKey)·`fromJson(toJson())==this` 정규형.
- `Action`: sealed SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK. config wire **비대칭** — 요청=JSON 문자열, 응답=Map. trigger config·condition은 요청·응답 모두 문자열.
- 권한: `MANAGE_AUTOMATION`(shared-kernel `AutomationPermissionResolver.hasManageAutomation`, fail-closed). 기존 컨트롤러 인가 순서 = actor 추출(401) → 권한(403) → 리소스.

### 참고 패턴 (search-export-import BC)

- `ExportCellSanitizer` — formula injection 방어(YAML도 값에 `=+-@` 시작 시 방어 검토).
- `ImportJobService` — fail-closed 권한 게이트(파일 저장 이전 fail-fast).
- 단, search-export-import는 CSV/XLSX/Jira 대상 대용량 비동기(pgmq+MinIO). FR-AT-06은 규칙 수가 작아 **동기 처리**가 적합(대용량 잡 프레임워크 재사용 불필요).

### 스펙에서 확정할 행위 결정 (→ /bts-spec)

1. **규칙 식별 / import 시맨틱** — UUID id 기준 upsert vs name 기준 upsert vs create-only. (round-trip·GitOps 멱등성의 핵심)
2. **부분 실패 처리** — fail-closed 전량 롤백 vs 규칙별 보고.
3. **export 범위** — 프로젝트 단위 확정(기존 컨트롤러 이미 project-scoped). 비활성 규칙·webhook 규칙 포함 여부.
4. **엔드포인트 경로** — 기존 base가 `/api/v1/projects/{projectKey}/automation/rules` → export/import도 project-scoped 하위(`.../rules/export`, `.../rules/import`)로 정렬.

## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-06-yaml-gitops.md](../specs/2026-07-14-fr-at-06-yaml-gitops.md)

확정 결정(Maxi).
- import 시맨틱 = **UUID id 기준 upsert** (id 존재→갱신, 미존재→id 보존 생성, id 부재→새 UUID 생성).
- 부분 실패 = **atomic fail-closed** (단일 트랜잭션, 하나라도 실패→전량 롤백).
- 경로 = **프로젝트 스코프 하위** (`GET/POST /api/v1/projects/{projectKey}/automation/rules/export·import`). product doc flat 경로에서 deviation → 문서 동기화.

핵심 시나리오 3줄.
- export = 프로젝트 전 규칙(활성+비활성)을 YAML로(토큰 미포함·결정적 순서).
- import = YAML upsert(UUID 식별)·검증 도메인 재사용·원자성.
- round-trip + 멱등(동일 YAML 2회→2회차 전량 update).

도메인 확장 = `AutomationRule` 팩토리에 id·enabled 보존 변형(멱등성·비활성 round-trip 전제). 신규 마이그레이션 0.

## Brainstorming Check

✅ 통과 (1회 self-adversarial). gap 4건(비활성 round-trip·export 결정성·하이드레이션·id 보존) 반영. 테스트 함정 인계(크기상한 실서블릿·YAML mapper 격리·prod 조립 부팅).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
