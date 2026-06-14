# FR-NT-05 D6 PR-A — 워크플로우 전이 post-action CRUD API 스펙

> slug: fr-nt-05-d6-post-action-api
> BC: project-workflow
> type: api (권한 가드 security-engineer 검토)
> 관련: FR-NT-05 D6/D7 (PR-A 백엔드 enabler), PR2 #141(notification 디스패처)

## 개요

관리자가 워크플로우 전이의 post-action(특히 `CALL_WEBHOOK`의 url/method)을 런타임에 추가/수정/삭제할 수 있는 REST API를 project-workflow BC에 신설한다. 현재 post-action은 YAML seed 전용이라 화면 설정(D6)이 불가능하다. 이 API가 D6 프론트 UI(PR-B)의 백엔드 enabler다.

## Maxi 게이트 확정 결정

- **공존(B)**: post-action을 YAML seed dirty-diff에서 제외한다. `YamlSeedService.isDirty`에서 `differsInPostActions` 호출을 제거 → 런타임 post-action이 재시드를 트리거하지 않고 평상시(YAML 무변경) 보존된다. YAML post_action은 현재 0건이라 무손실. **마이그레이션 불요.**
- **권한**: `WorkflowSchemePermission.MANAGE_SCHEME` + `WorkflowSchemeScope.Global` (WorkflowSchemeController 선례). 모든 변이 엔드포인트 진입 직후 `permissionResolver.requirePermission` Guard. fail-closed.
- **type 범위**: API는 5종(SET_FIELD/NOTIFY/ADD_WATCHER/RUN_AUTOMATION/CALL_WEBHOOK) 제네릭 지원(factory가 이미 처리). 즉시 동인은 CALL_WEBHOOK.
- **config 검증**: 영속 전 `DefaultWorkflowPostActionFactory.create(type, config)`로 검증(미지원 type·필수 키 누락 거부). CALL_WEBHOOK은 url http/https 스킴 추가 체크. **SSRF 완전 검증은 BC 격리상 발사 시점 notification `WebhookUrlValidator`가 담당**(config 시점은 형식 검증만).

## 사용자 시나리오 (Given-When-Then)

1. **post-action 추가**
   - Given. admin(MANAGE_SCHEME)이 `software-default` 워크플로우의 `open__in_progress` 전이에 webhook을 걸고 싶다.
   - When. `POST .../transitions/open__in_progress/post-actions` body `{type:"CALL_WEBHOOK", config:{url:"https://...", method:"POST"}}`.
   - Then. 검증 통과 시 `workflow_post_actions` INSERT, 캐시 무효화, `201` + `{id, type, config, displayOrder}`. 재부팅해도 보존(공존 B).

2. **목록/수정/삭제**
   - `GET .../post-actions` → 해당 전이의 post-action 목록(displayOrder ASC).
   - `PUT .../post-actions/{id}` → 해당 행 type/config/displayOrder 갱신 + 캐시 무효화.
   - `DELETE .../post-actions/{id}` → 행 삭제 + 캐시 무효화.

3. **권한 거부**
   - Given. MANAGE_SCHEME 없는 사용자.
   - When. 변이 호출.
   - Then. Guard가 `WorkflowSchemeAccessDeniedException`(403) — 리소스 조회 전(존재 probe 방지, 메모리 auth-extraction-before-resource-lookup).

4. **검증 실패**
   - 미지원 type / 필수 config 키 누락 / CALL_WEBHOOK url 빈문자열·비-http → `400` (factory 검증).

## 기능 요구사항 (FR)

- **FR1.** `workflow_post_actions` 런타임 CRUD repository(jOOQ) — findByTransition, insert, update, deleteById. transition_id는 (workflowKey, fromStateKey, toStateKey)로 해석.
- **FR2.** 전이 식별 = URL path `{workflowKey}/transitions/{transitionKey}` where transitionKey=`{fromStateKey}__{toStateKey}`(기존 transition.key 관례, learnings fixture 옵션B). 전이 미존재 → 404.
- **FR3.** 영속 전 `DefaultWorkflowPostActionFactory.create(type, config)` 검증. CALL_WEBHOOK은 url http/https 스킴 추가 체크.
- **FR4.** 모든 변이(POST/PUT/DELETE)에 `MANAGE_SCHEME + Global` Guard(컨트롤러 진입 직후, 리소스 조회 전). GET도 동일 권한(설정 화면 admin 전용).
- **FR5.** 변이 성공 후 워크플로우 캐시 무효화(전이 실행이 새 post-action을 반영하도록). 기존 캐시 무효화 메커니즘 재사용.
- **FR6.** `YamlSeedService.isDirty`에서 `differsInPostActions` 제거(공존 B) + 관련 헬퍼(`fetchPostActionTypesByTransition` 등) 미사용 시 정리. **seed의 post-action INSERT 자체는 유지하되**(최초 시드 시 YAML에 post_action 있으면 적재) dirty 비교에서만 제외 — 단, YAML이 0건이라 실질 영향은 비교 제외뿐. (구현 시 정확히: dirty 트리거에서 빼고, deleteWorkflow→reinsert 경로는 그대로. 따라서 structural 변경 시 소실은 한계.)

## 비기능 요구사항 (NFR)

- **보안**. admin 전용(MANAGE_SCHEME). config의 url은 형식 검증만, SSRF는 발사 시점(notification) 책임 — config 시점 SSRF 완전검증은 BC 격리 위반이라 안 함(방어는 egress에 둠). 권한 예외 message HTTP 누출 금지(메모리 fr-pm-04-guard-exception-message-http-leak — detail 일반 메시지).
- **BC 격리**. project-workflow 내부. notification의 WebhookUrlValidator import 금지. config 검증은 자체 factory.
- **데이터**. workflow_post_actions 스키마 변경 0(기존 컬럼 사용) → 마이그레이션 0, init_codegen 미러 불요.
- **회귀**. 기존 YamlSeedService 4워크플로우 시드/재시드 동작 회귀 0(differsInPostActions 제거가 state/transition dirty 판정에 영향 없음 — 독립 분기).

## API 인터페이스 (REST)

```
GET    /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
POST   /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
PUT    /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}
DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}

POST/PUT body: {"type":"CALL_WEBHOOK","config":{"url":"https://...","method":"POST"},"displayOrder":0}
응답: {"id":"<uuid>","type":"CALL_WEBHOOK","config":{...},"displayOrder":0}
```

## 데이터 모델 변경

없음. `workflow_post_actions`(V200) 기존 컬럼 사용. 마이그레이션 0, init_codegen 미러 불요.

## 엣지 케이스

- transitionKey 형식 오류(`__` 없음)·전이 미존재 → 404.
- post-action id 미존재(PUT/DELETE) → 404.
- 미지원 type / config 필수키 누락 → 400.
- CALL_WEBHOOK url 빈문자열/비-http 스킴 → 400.
- displayOrder 누락 → 기본값(말단 append 또는 0).
- 동시 편집(OCC) — 범위 외(post-action은 단순 행, 버전 컬럼 없음). 마지막 쓰기 우선.
- seed 공존: YAML structural 변경 시 런타임 post-action 소실(알려진 한계, 문서화).

## 제약 조건

- DEVELOPMENT.md §1 전수 준수. SQL ?바인딩(jOOQ). KDoc/한국어 헤더. TDD.
- 권한 Guard는 WorkflowSchemeController 패턴 그대로(actor 추출→requirePermission→리소스 조회).
- 신규 의존성 0.

## 측정 가능한 완료 기준

1. POST/GET/PUT/DELETE 통합 시나리오(Testcontainers) — CALL_WEBHOOK 추가→조회→수정→삭제 + 캐시 무효화 검증.
2. 권한 거부(MANAGE_SCHEME 없음 → 403, 리소스 조회 전).
3. 검증 실패(미지원 type/필수키 누락/비-http url → 400).
4. **공존 회귀 테스트** — 런타임 post-action 추가 후 YamlSeedService 재시드 호출 → 런타임 행 보존(differsInPostActions 제거 효과). 기존 4워크플로우 재시드 회귀 0.
5. ktlint/detekt green, 모듈 전체 test green.

## Brainstorming Check

✅ 통과 (직접 sanity check). 구현 시 주의(Maxi 결정 불요).
- **캐시 무효화 배선** — `WorkflowController` `/cache/invalidate` 선례의 실제 서비스 메서드를 찾아 변이 후 호출(impl서 grep 확정).
- **FR6 정밀** — `isDirty`에서 `differsInPostActions` 한 줄 제거 + 미사용 `fetchPostActionTypesByTransition` detekt 대비 정리. insertWorkflow의 post-action 적재 경로는 유지(YAML 0건 no-op). structural reinsert 소실=한계 문서화.
- **감사 로그(FR-AU-10)** — post-action 변경 감사는 cross-BC라 PR-A 범위 외(후속).
- **GET 권한** — 기존 GET /workflows는 비보호지만, post-action 조회는 admin 설정 화면용이라 MANAGE_SCHEME 적용(webhook URL 민감).
