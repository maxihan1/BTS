# FR-WT-01 — Watcher 추가/제거 + 자동 Watcher (백엔드 D1~D5) — 스펙

> BC: issue-tracking | type: api | slug: fr-wt-01-watchers | 2026-06-16
> 명세 정본: `docs/plan/product/issue-tracking.md §4.3.1`
> Maxi 확정(2026-06-16): 타인 추가 가능(Jira식) + 자동 Watcher 표준(해제 허용·재배정 유지)

## 개요

이슈 Watcher(이슈 변경 알림 수신자) 목록을 소유·관리한다. 사용자는 이슈를 watch/unwatch 하고, 권한이 있으면 타인을 watcher로 추가/제거할 수 있다. 이슈 생성 시 reporter, 배정 시 assignee가 자동으로 watcher가 된다.

**경계**: 본 FR은 watcher **목록 소유(추가/제거/저장/조회)**만 책임진다. watcher를 읽어 실제 알림을 발송하는 수신자 해석은 **FR-NT-03(notification BC)** 경계다(ADR `2026-06-12-notification-inapp-channel-delivery` §결정3). 본 PR은 알림 발송을 건드리지 않는다.

## 사용자 시나리오 (Given-When-Then)

1. **본인 watch**. Given 이슈 VIEW 권한이 있는 사용자, When `POST /watchers`(대상 생략=본인), Then 본인이 watcher 목록에 추가되고 카운트 +1.
2. **본인 unwatch**. Given 본인이 watch 중, When `DELETE /watchers/{본인id}`, Then 목록에서 제거되고 카운트 -1.
3. **타인 추가**. Given UPDATE(편집) 권한이 있는 사용자, When `POST /watchers {userId: 타인}`, Then 그 사용자가 watcher로 추가.
4. **타인 추가 권한 없음**. Given VIEW만 있는 사용자, When `POST /watchers {userId: 타인}`, Then 403.
5. **자동 watch (생성)**. Given 사용자가 이슈 생성, When createIssue, Then reporter(=생성자)가 자동 watcher. 생성과 동시에 assignee가 지정(명시 또는 기본배정)되면 그 assignee도 자동 watcher.
6. **자동 watch (배정)**. Given 이슈에 assignee 배정, When `PATCH /assignee`로 non-null 배정, Then 새 assignee가 자동 watcher(멱등).
7. **재배정**. Given assignee A → B로 재배정, When 재배정, Then B 추가, **A는 watcher로 유지**(자동 제거 안 함).
8. **멱등 추가**. Given 이미 watch 중, When 동일 사용자 재추가, Then 중복 없이 성공(카운트 불변).
9. **존재하지 않는 사용자**. Given 실재하지 않는 userId, When 타인 추가, Then 422.
10. **목록 조회**. Given VIEW 권한, When `GET /watchers`, Then watcher 목록 + 총 카운트 + 본인 watch 여부(isWatching).

## 기능 요구사항 (FR)

- **FR-1** Watcher = (issue, user) 관계. 한 이슈에 같은 user는 최대 1회(멱등).
- **FR-2** `GET /watchers`: watcher 목록(userId + displayName, **created_at ASC 추가순**) + count + isWatching(요청자 기준). 별도 엔드포인트(이슈 상세 IssueResponse에 통합하지 않음 — cross-cutting 회피, 프론트 D6가 직접 호출).
- **FR-3** `POST /watchers`: body `{ userId? }`. 생략 시 actor 본인. 멱등(이미 있으면 no-op 성공).
- **FR-4** `DELETE /watchers/{userId}`: 멱등(없어도 성공). 
- **FR-5** 권한 분기 — 대상 userId == actor → **VIEW**, 대상 userId != actor → **UPDATE**. GET/목록 → VIEW.
- **FR-6** 타인 추가 시 `UserLookupPort.exists(userId)` 실재 검증. false → 422. (본인·자동 watcher는 검증 생략 — actor는 인증됨, assignee는 changeAssignee가 이미 검증.)
- **FR-7** 자동 watcher 진입점 3종(assignee 배정의 모든 통로 일관) — ① `createIssue`: reporter + (resolvedAssignee non-null이면) assignee. ② `changeAssignee`: non-null 신규 assignee. ③ `updateIssue` 컴포넌트 기반 자동 재배정(L799-807): resolved non-null이면 assignee. 모두 이슈 변경과 **동일 트랜잭션**, 멱등. unassign(null)은 watcher 무변경. **cloneIssue는 본 PR 범위 제외**(ADR `2026-06-02-issue-clone-semantics`가 watcher 복사를 FR-IS-06 후속으로 이연 + 명세 §4.3.1 미명시 → 클론본 자동 watch는 후속 결정).
- **FR-8** displayName은 issue-tracking이 보관하지 않음 → `UserLookupPort.findDisplayNamesByIds`로 조회(reporter 표시명 선례). 미해소 id는 graceful(null/원시 처리).

## 비기능 요구사항 (NFR)

- **NFR-1** BC 격리 — `user_id` FK 미적용(identity-access 소유, assignee_id 선례). 사용자 데이터는 UserLookupPort로만 접근.
- **NFR-2** 멱등성 — DB UNIQUE(issue_id, user_id) + `ON CONFLICT DO NOTHING`. 동시 추가 race 안전.
- **NFR-3** 권한 우선 — 권한 체크를 리소스(이슈) 조회보다 먼저(존재 probe 차단, 첨부 선례). 단 actor 추출은 그보다 먼저([[auth-extraction-before-resource-lookup]]).

## API 인터페이스 (REST)

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `/api/v1/issues/{key}/watchers` | VIEW | 200 `{ watchers: [{userId, displayName}], count, isWatching }` |
| POST | `/api/v1/issues/{key}/watchers` | 본인=VIEW / 타인=UPDATE | 201 (멱등 재추가 포함 — 구현은 항상 201) |
| DELETE | `/api/v1/issues/{key}/watchers/{userId}` | 본인=VIEW / 타인=UPDATE | 204 |

- POST body: `{ "userId": "uuid" }` (옵션, 생략 시 actor).
- 에러: 404(이슈 미존재/소프트삭제) · 403(권한 없음) · 422(userId 실재 안 함).

## 데이터 모델 변경

신규 테이블 `issue_watchers` (마이그레이션 **V024**, 경로 `.../db/migration/issue-tracking/`, init_codegen.sql 미러).

```sql
CREATE TABLE issue_watchers (
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,                 -- cross-BC, FK 미적용(BC 격리)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)                  -- 멱등 보장
);
CREATE INDEX idx_issue_watchers_issue ON issue_watchers (issue_id);
```

- 복합 PK (issue_id, user_id) = UNIQUE 겸 멱등. user_id 역조회 인덱스는 FR-NT-03 소관이라 본 PR 제외(YAGNI).
- ON DELETE CASCADE — 이슈 하드삭제 시 정리(조인테이블 선례 [[join-table-fk-cascade-testcontainers-cleanup]]).

## 엣지 케이스

- 이미 watch 중 재추가 → 멱등 성공, 카운트 불변.
- watch 안 함 unwatch → 204(영향 0행).
- 이슈 소프트삭제/미존재 → 404(존재 probe 차단).
- 권한 없음 → 403.
- 존재하지 않는 userId 타인 추가 → 422.
- 재배정 시 이전 assignee → watcher 유지(자동 제거 안 함).
- unassign(assignee=null) → watcher 무변경.

## 제약 조건

- DEVELOPMENT.md §1 절대 규칙 준수(소프트삭제·트랜잭션 경계·non-null).
- 알림 발송 로직 추가 금지(FR-NT-03 경계).
- 신규 외부 의존성 0.

## 측정 가능한 완료 기준

- watch/unwatch/타인추가/타인제거/목록조회 5개 흐름 통합테스트 통과.
- 자동 watcher 2종(생성·배정) + 재배정 유지 + unassign 무변경 회귀 테스트.
- 권한 분기(self=VIEW, 타인=UPDATE) + 422/403/404 음성 테스트.
- 멱등성(중복추가·없는것삭제) 테스트.
- `./gradlew :backend:modules:issue-tracking:test ktlintCheck detekt` 그린.

## Brainstorming Check

✅ 통과 (직접 sanity check, gap 3건 발견 후 보강).
- gap1: createIssue 시 지정된 assignee 자동 watch 누락 → FR-7·시나리오5 보강.
- gap2: cloneIssue 자동 watch 경계 모호 → 본 PR 범위 제외 명시(ADR 이연).
- gap3: GET 정렬 미정의 → created_at ASC 명시.
- 확인됨: UserLookupPort.exists/findDisplayNamesByIds 둘 다 실재(IssueApplicationService 선례). 멱등은 DB UNIQUE+ON CONFLICT. 자동 watch는 repo 직접 호출(self-invocation 무관).
