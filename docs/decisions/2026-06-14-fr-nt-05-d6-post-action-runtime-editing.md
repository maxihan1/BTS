# ADR: FR-NT-05 D6 — 워크플로우 post-action 런타임 편집 + YAML seed 공존

> 날짜: 2026-06-14
> 상태: Accepted (Maxi 게이트 확정)
> 관련 FR: FR-NT-05 D6 (워크플로우 post-action 설정 UI)
> 관련 PR: PR-A (project-workflow post-action CRUD API)

## 맥락

FR-NT-05 D6(워크플로우 post-action 설정 프론트 UI)를 만들려면 관리자가 전이의 post-action(특히 `CALL_WEBHOOK`)을 런타임에 편집할 수 있어야 한다. 그러나 현재 post-action은 `YamlSeedService`가 부팅 시 `workflow_post_actions`에 시드하는 전용 경로뿐이고 런타임 편집 API가 없다.

**핵심 충돌(코드 실측).** `YamlSeedService.isDirty`가 `differsInPostActions(existing, dto)`를 포함한다. 이는 YAML 정의 전이별로 DB의 post-action type 목록과 YAML 목록을 비교한다(`dbTypes != dtoTypes`). 따라서 런타임 API로 YAML 시드 전이에 post-action을 추가하면 DB(추가분 포함)가 YAML(0건)과 달라져 `isDirty=true` → `deleteWorkflow`(CASCADE) → reinsert → **런타임 post-action이 다음 부팅 시 전멸**한다.

## 결정

### 1. post-action 런타임 편집 CRUD API 신설 (project-workflow BC)
`GET/POST/PUT/DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions`. 권한 `MANAGE_SCHEME + Global`(WorkflowSchemeController 선례, fail-closed). config 검증은 `DefaultWorkflowPostActionFactory.create()` 재사용. CALL_WEBHOOK은 url http/https 스킴 추가 체크.

### 2. seed 공존 = post-action을 dirty-diff에서 제외 (옵션 B)
`YamlSeedService.isDirty`에서 `differsInPostActions` 항을 제거한다. → 런타임 post-action이 재시드를 트리거하지 않아 평상시(YAML 무변경) 보존된다. YAML post_action은 현재 4워크플로우 모두 0건이라 무손실. **마이그레이션 불요.**

대안 기각.
- **A) source 컬럼(SEED/RUNTIME) 분리**: 마이그레이션 + structural reinsert 시 transition_id 재생성으로 RUNTIME 행 보존→복원 로직까지 필요해 복잡도·범위 과대.
- **C) seed를 멱등 upsert로 전환**: YamlSeedService 핵심 로직 대공사, 기존 4워크플로우 재시드 회귀 위험.

### 3. SSRF 검증 분담
config 저장 시점은 형식(http/https) 검증만. 완전 SSRF(내부망 차단)는 **발사 시점 notification `WebhookUrlValidator`**가 담당(egress 경계). project-workflow는 외부 HTTP를 발사하지 않으므로 config 저장만으로 실 노출이 없다. BC 격리상 notification validator를 import하지 않는다.

### 4. post-action은 비캐시 (DB-direct)
전이 실행 시 `DefaultWorkflowDefinitionRepository.findPostActions`(WorkflowEngine 경유)가 DB를 직접 조회한다. `WorkflowCache`의 `Workflow` aggregate에는 post-action 필드가 없다(states/transitions/validator만 캐싱). 따라서 변이 후 캐시 무효화는 불필요하다(codereview CONCERN-1).

## 알려진 한계 (수용)

- **structural reinsert 시 소실**: YAML의 state/transition 구조가 바뀌면(드묾, 의도적 워크플로우 재정의) `deleteWorkflow`→reinsert로 transition_id가 재생성되어 해당 워크플로우의 런타임 post-action이 CASCADE 삭제된다. 평상시(YAML 무변경)엔 보존. admin이 재설정하는 것이 합리적이라 수용하고 문서화.
- **감사 로그 부재**: webhook config 변경 주체 감사(FR-AU-10)는 cross-BC라 PR-A 범위 밖, 후속 추적.

## 영향 / 후속

- 본 API가 FR-NT-05 D6 프론트 UI(PR-B)의 백엔드 enabler. D6/D7은 PR-B에서 완료, 그때까지 FR-NT-05 `[~]`.
- 런타임 post-action 편집은 5종 type(SET_FIELD/NOTIFY/ADD_WATCHER/RUN_AUTOMATION/CALL_WEBHOOK) 제네릭 지원. 즉시 동인은 CALL_WEBHOOK(FR-NT-05).
