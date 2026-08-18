<!-- FR-SL-06 채널 ↔ 프로젝트 매핑 백엔드 코어 스펙 (D1~D5) -->

# FR-SL-06 채널 ↔ 프로젝트 매핑 — 스펙

> BC. slack-integration (주) + notification (브로드캐스트 producer 1개)
> FR. FR-SL-06 (product/slack-integration §2.3, SDD 09 §9.1.1)
> 작성. 2026-07-13

## 스코프

FR-SL-06 백엔드 코어(D1~D5)를 **설정/라우팅 2 PR로 분할**(Maxi 2026-07-13, FR-SL-05 PR1/PR2 관례). prod 조립이 새 cross-BC 포트 소비의 prod 어댑터를 같은 PR에 요구(NoSuchBean 부팅 차단)하므로, 각 PR이 포트+어댑터를 함께 실어 독립 prod 부팅.

- **PR-A (이 PR, 설정/CRUD)**. FR1·FR2·FR3·FR7 + team_id 해석 + 권한 prod 어댑터(identity-access, PROJECT_ADMIN) + non-prod 스텁 + prod 조립. → "프로젝트 관리자가 매핑을 설정할 수 있다".
- **PR-B (후속, 라우팅)**. FR4(브로드캐스터)·FR5(채널 워커)·FR6(dedup)·FR9(보안 게이트) + 보안 prod 어댑터(issue-tracking). → "설정된 매핑이 실제로 채널에 게시된다".
- **후속 PR (D6/D7)**. UI(프로젝트 설정 → Slack 채널) · Playwright E2E.

이 스펙은 FR-SL-06 백엔드 전체 정본. 아래 FR/시나리오는 PR-A/PR-B 라벨로 구분.

BTS §작업 기준: 이 PR의 백엔드는 완제품 품질(테스트·보안·에러처리 완비). "UI는 나중"은 스코프 분할일 뿐 품질 미룸이 아니다.

## 배경 — 현행 지형 (검증 완료)

- 이벤트 소스 `q_issue_events`는 **projectKey를 보유**하지만 notification 전용 **경쟁 소비 큐**라 slack이 두 번째 consumer로 붙을 수 없다(companion KDoc 명시: fan-out은 용도별 큐 분리).
- 현행 Slack 경로(`notification.SlackChannelSender` → `q_slack_deliveries` → `slack.SlackDeliveryWorker`)는 **수신자 단위 DM**이며 projectId가 없다.
- 채널 게시는 **이벤트당 1회(수신자 무관)** 라 DM 경로(per-recipient)와 granularity가 다르다 → 별도 큐/워커 필요.

## 확정된 아키텍처 결정 (Maxi, 2026-07-13)

1. **라우팅 팬아웃 = notification 브로드캐스트**. notification `NotificationWorker.dispatch()`가 projectKey가 있는 이벤트마다 **새 큐 `q_slack_channel_broadcasts`**로 이벤트당 1회 JSON을 발행. slack BC의 새 워커가 소비 → `slack_channel_project_map`(projectKey+eventType) 조회 → 채널당 1회 `chat.postMessage`. FR-SL-02 `SlackChannelSender` JSON 경계 패턴 재사용.
2. **CRUD 권한 = 프로젝트 관리자**. 신규 cross-BC 포트 `SlackChannelMappingPermissionResolver`(shared-kernel, `AutomationPermissionResolver` 미러: Boolean fail-closed, actorId:UUID + projectKey:String, prod adapter=identity-access `@Profile("prod")`, non-prod=consumer stub). 거부 시 소비자가 일반 403.
3. **매핑 키 = projectKey(String)**. 라우팅 시점 projectKey→UUID cross-BC 조회를 없애기 위해 매핑을 projectKey로 스코프. product 명세의 `project_id` 컬럼은 **projectKey deviation**(AutomationPermissionResolver/automation_rules.project_key 동형).
4. **채널 라우팅은 NotificationPolicy(사용자 정책)와 독립**. 채널 게시 여부는 매핑의 event_filter가 유일한 통제. 이벤트당 브로드캐스트는 정책/수신자와 무관하게 발행되고, 채널 워커가 event_filter로 최종 필터.
5. **보안등급 이슈 제외 (Maxi 2026-07-13, fail-closed)**. 채널 피드는 뷰어별 이슈 권한을 우회하므로, **보안등급이 걸린 이슈는 채널 게시에서 제외**한다. 워커가 issueKey마다 신규 cross-BC 포트로 보안 제한 여부를 확인해 제한/불명이면 skip(제목·키 유출 차단). issueKey 없는 이벤트(sprint 등)는 이슈-스코프가 아니므로 게이트 대상 아님.

### 브로드캐스트 배치 (구현 refinement)
- 브로드캐스트는 `dispatch(event)`의 정책 early-return(`matches.isEmpty()`) **이전**에, projectKey가 있으면 무조건 발행(사용자 정책·수신자와 독립 — 결정 #4).
- 브로드캐스트 producer는 per-recipient `NotificationChannelSender`가 아니라 이벤트당 1회 호출되는 **신규 컴포넌트 `SlackChannelBroadcaster`**. title은 기존 `buildTitleBody(event)` 재사용(중복 로직 금지).
- 채널 게시는 액션 버튼 없는 평범한 카드(`render(title, issueKey)`). 완료 버튼(FR-SL-05)은 담당자 DM 전용.

## 사용자 시나리오 (Given-When-Then)

### S1. 프로젝트 관리자가 채널 매핑 생성
- **Given** actor가 프로젝트 `PROJ`의 관리 권한을 보유
- **When** `POST /api/v1/slack/channel-mappings {projectKey:"PROJ", channelId:"C123", eventTypes:["issue.created","issue.transitioned"]}`
- **Then** 매핑이 저장되고 201 + 매핑 뷰 반환. 이후 `PROJ`의 생성/전환 이벤트가 채널 `C123`에 게시된다.

### S2. 비관리자 거부 (fail-closed)
- **Given** actor가 `PROJ` 관리 권한 없음 (또는 미해석 projectKey)
- **When** 매핑 CRUD 호출
- **Then** 일반 **403**(내부 사정 비노출). 매핑 미변경.

### S3. 이벤트 → 채널 게시 (happy)
- **Given** `PROJ`↔`C123` 매핑(event_filter⊇`issue.transitioned`), 봇이 `C123`에 설치됨
- **When** `PROJ-7` 상태 전환 이벤트가 `q_issue_events`에 발행됨
- **Then** notification이 `q_slack_channel_broadcasts`에 1회 브로드캐스트 → slack 워커가 `C123`에 Block Kit 카드 1회 게시. 재전달돼도 채널당 dedup으로 중복 게시 없음.

### S4. 이벤트 필터 미포함 → 게시 안 함
- **Given** 매핑 event_filter = {`issue.created`}만
- **When** `issue.commented` 이벤트 발생
- **Then** 브로드캐스트는 발행되나 워커가 event_filter 불일치로 해당 채널 skip(게시 0). 큐 메시지는 정상 삭제.

### S5. 매핑 없는 프로젝트
- **Given** `NOPE` 프로젝트에 매핑 0건
- **When** `NOPE` 이벤트 발생
- **Then** 브로드캐스트 발행 → 워커가 매핑 0건 확인 → 게시 0, 메시지 삭제(no-op). 오류 아님.

### S6. 다대다 — 한 이벤트 → 여러 채널
- **Given** `PROJ`가 `C1`(전환), `C2`(전환+댓글) 두 채널에 매핑
- **When** `PROJ` 전환 이벤트
- **Then** `C1`, `C2` 각각 1회 게시(채널별 독립 dedup). 한 채널 실패가 다른 채널 게시를 막지 않는다.

### S7. 봇이 채널에 없음 → 영구 실패
- **Given** 매핑된 `C9`에 봇 미설치(`not_in_channel`/`channel_not_found`)
- **When** 게시 시도
- **Then** 영구 실패로 분류 → 로그 후 메시지 삭제(무의미 재시도 안 함). 다른 채널·이벤트에 영향 없음.

### S8. 매핑 수정/삭제
- **Given** 기존 매핑
- **When** `PATCH .../{id} {eventTypes:[...]}` 또는 `DELETE .../{id}` (프로젝트 관리자 게이트)
- **Then** event_filter 갱신 또는 매핑 제거. 삭제 후 해당 채널로의 이후 게시 중단. 과거 게시는 보존(소급 삭제 없음).

## 기능 요구사항 (FR)

- **FR1**. `slack_channel_project_map` 테이블(V704). 컬럼: id(uuid pk), team_id, project_key, channel_id, channel_name(nullable), event_types(text[], 비어있지 않음), created_at, updated_at. UNIQUE(team_id, project_key, channel_id).
- **FR2**. 매핑 CRUD API(`/api/v1/slack/channel-mappings`), 각 연산 프로젝트 관리자 게이트(list 포함, fail-closed 403).
  - `POST` 생성 (projectKey, channelId, channelName?, eventTypes[≥1]).
  - `GET ?projectKey=` 프로젝트별 목록.
  - `PATCH /{id}` event_types/channel 갱신.
  - `DELETE /{id}` 삭제.
- **FR3**. eventTypes 입력 검증 — 알려진 `NotificationEventType.wireValue` 집합만 허용(미지값 400), 최소 1개.
- **FR4**. notification 브로드캐스트 producer — `dispatch(event)`에서 projectKey가 존재하면 `q_slack_channel_broadcasts`에 이벤트당 1회 JSON 발행(수신자/정책과 독립). payload: {projectKey, eventType, issueKey?, title, occurredAt, dedupKey}. slack 코드 import 0 (JSON 경계).
- **FR5**. slack 채널 워커 — `q_slack_channel_broadcasts` 폴링(@Scheduled). projectKey+eventType으로 매핑 조회 → **보안 게이트(FR9)** 통과 시 → 매칭 채널마다: 채널 dedup 확인 → 봇 토큰 해석(team_id) → Block Kit 렌더(버튼 없는 `render`) → `chat.postMessage` → 결과별 큐 생명주기(Sent=dedup 기록 후 delete / PermanentFailure=delete / RetryableFailure=재전달, read_ct>MAX면 archive). notification 도메인 타입 import 0.
- **FR6**. 채널 게시 dedup — (projectKey,eventType,issueKey,occurredAt,channelId) 기반 키로 effectively-once. 전송 성공 **후에만** 기록(전송 전 기록 시 실패분 유실 방지 — FR-SL-02 B4 회귀 방어).
- **FR7**. cross-BC 포트 `SlackChannelMappingPermissionResolver.hasManageChannelMapping(actorId, projectKey): Boolean`. prod adapter=identity-access(@Profile prod, MANAGE 판정). non-prod=slack 소비 모듈 fail-safe stub. nullable 주입/`?:return` fail-open 금지.
- **FR8**. prod 조립(:modules:app) — 새 포트 adapter 2종 배선 + slack 채널 워커 @Scheduled 결선. 부팅 검증 통과.
- **FR9 (보안 게이트, fail-closed)**. 신규 cross-BC 포트 `IssueSecurityClassificationPort.isSecurityRestricted(issueKey): Boolean`. 워커는 issueKey가 있는 이벤트에서 이 포트를 호출해 **`true`(제한) 또는 판정 불명이면 해당 이벤트의 채널 게시 전부 skip**(메시지는 정상 삭제, 유출 차단). prod adapter=issue-tracking(@Profile prod, `security_level_id` non-null→제한). non-prod=consumer stub(제한 없음=게시 허용). issueKey 없는 이벤트는 게이트 우회(이슈-스코프 아님). fail-open 금지(non-null 주입).

## 비기능 요구사항 (NFR)

- **NFR1 (멱등)**. 큐 at-least-once 위에서 채널당 effectively-once. 같은 이벤트 재전달 시 채널 중복 게시 0.
- **NFR2 (BC 격리)**. notification↔slack 간 도메인 타입 직접 import 0(JSON 큐 경계). 권한은 shared-kernel 포트 경계. ArchUnit green.
- **NFR3 (fail-closed 보안)**. 권한 판정 불명(미해석 키·비멤버·adapter 부재)은 전부 거부. 봇 토큰은 로그·응답 비노출(FR-SL-01/02 3중 미노출 관례).
- **NFR4 (격리성)**. 한 채널 게시 실패가 같은 배치의 다른 채널/이벤트를 굶기지 않음(per-message·per-channel 흡수, WebhookDispatchWorker/SlackDeliveryWorker 동형).
- **NFR5 (성능/규모)**. 1K 사용자·프로젝트당 소수 채널 가정. projectKey 이벤트마다 브로드캐스트 1건 추가 발행(신규 큐 부하) 허용. 매핑 조회는 (project_key,event_type) 인덱스.

## API 인터페이스 (REST)

인증. `@AuthenticationPrincipal Jwt?` (JWT 전용, SlackConnectionController 관례). 401=미인증, 403=비관리자.

| 메서드 | 경로 | 바디/쿼리 | 응답 |
|---|---|---|---|
| POST | `/api/v1/slack/channel-mappings` | `{projectKey, channelId, channelName?, eventTypes[≥1]}` | 201 매핑 뷰 |
| GET | `/api/v1/slack/channel-mappings?projectKey=` | — | 200 매핑 목록 |
| PATCH | `/api/v1/slack/channel-mappings/{id}` | `{channelId?, channelName?, eventTypes?}` | 200 매핑 뷰 |
| DELETE | `/api/v1/slack/channel-mappings/{id}` | — | 204 |

매핑 뷰. `{id, projectKey, channelId, channelName, eventTypes[], createdAt, updatedAt}`. team_id는 응답 비노출(설치 컨텍스트에서 해석).

## 데이터 모델 변경 (V704)

```
slack_channel_project_map(
  id uuid pk,
  team_id text not null,
  project_key text not null,
  channel_id text not null,
  channel_name text null,
  event_types text[] not null,   -- NotificationEventType.wireValue 값들, 비어있지 않음
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(team_id, project_key, channel_id)
)
index on (project_key)   -- 워커 라우팅 조회
```
채널 dedup은 기존 `slack_delivery_log`(dedupKey 저장소) 재사용(채널 스코프 키로 DM 키와 비충돌) 또는 전용 로그 — plan에서 확정. init_codegen.sql 미러 갱신(DATA.md).

## Cross-BC 계약

### 브로드캐스트 큐 `q_slack_channel_broadcasts` (JSON)
```json
{ "projectKey": "PROJ", "eventType": "issue.transitioned", "issueKey": "PROJ-7",
  "title": "PROJ-7 상태가 변경되었습니다", "occurredAt": "<iso-8601>", "dedupKey": "<hash>" }
```
producer=notification, consumer=slack. 큐 생성 소유권은 plan에서 확정(FR-SL-02는 slack V701 생성 + notification 테스트용 vanilla 예외 create; 동형 채택).

### 권한 포트 `SlackChannelMappingPermissionResolver` (shared-kernel)
```kotlin
interface SlackChannelMappingPermissionResolver {
  fun hasManageChannelMapping(actorId: UUID, projectKey: String): Boolean  // fail-closed
}
```

### 보안 게이트 포트 `IssueSecurityClassificationPort` (shared-kernel, 신규)
```kotlin
interface IssueSecurityClassificationPort {
  // true=보안등급 걸림(채널 게시 제외). prod=issue-tracking security_level_id non-null.
  // 판정 불명/adapter 오류는 호출자가 fail-closed(skip)로 처리.
  fun isSecurityRestricted(issueKey: String): Boolean
}
```
prod adapter=issue-tracking(@Profile prod). non-prod=slack consumer stub(항상 false=게시 허용). `IssueVisibilityPort`(뷰어 후보 집합 필터)·`IssueSecurityDirectory`(등급-스킴 소속)와 목적이 달라 재사용 불가 → 신규 포트.

### team_id 해석 (단일 설치 가정)
매핑 생성 시 channelId가 속한 워크스페이스 team_id는 **유일 `SlackInstall`에서 해석**(FR-SL-01 단일 워크스페이스 지향, team_id UNIQUE). 설치 0건이면 생성 거부(409/400 — plan 결정). 다중 설치 지원은 후속(요청 body에 teamId 추가).

## 엣지 케이스

- **EC1**. eventTypes 빈 배열/미지 wireValue → 400(생성·수정 공통).
- **EC2**. 중복 매핑(같은 team+project+channel) → 409(UNIQUE) 또는 멱등 upsert(plan 결정, 기본 409).
- **EC3**. 브로드캐스트 payload projectKey 부재 → notification이 발행 안 함(채널 라우팅 불가 이벤트).
- **EC4**. 봇 미설치(team 매핑 없음) → 워커 skip(발송 불가), 메시지 삭제.
- **EC5**. `not_in_channel`/`channel_not_found` → 영구 실패, 재시도 안 함.
- **EC6**. 429/5xx/네트워크 → 재시도(vt 만료 재전달), read_ct>MAX면 dead-letter archive.
- **EC7**. JSON 파싱 실패(poison) → read_ct>MAX면 archive, 미만이면 재전달.
- **EC8**. 매핑 삭제 후 in-flight 브로드캐스트 → 워커 조회 시 매핑 없음 → skip(정상).
- **EC9**. 동일 (project,event)에 N채널 → 각 채널 독립 게시·독립 dedup·독립 실패 격리.
- **EC10**. issueKey 없는 이벤트(sprint.started 등, projectKey 有) → 제목만으로 게시(이슈 링크 없음). 렌더러가 issueKey null 허용(기존 `render(title, null)`). 보안 게이트(FR9) 우회(이슈-스코프 아님).
- **EC11**. 보안등급 이슈(FR9 `true`) 또는 보안 판정 불명 → 해당 이벤트 채널 게시 전부 skip, 메시지 삭제(유출 차단). 재시도 안 함.
- **EC12**. team_id 미해석(설치 0건) 상태에서 매핑 생성 → 거부(설치 필요 안내).

## 제약 조건

- BC 격리 — notification/slack 도메인 타입 직접 import 금지, JSON·포트 경계만.
- 봇 토큰 3중 미노출 유지.
- pgmq 워커 무 @Transactional(self-invocation/rollback 오염 방지, 기존 워커 동형).
- Flyway 기적용 마이그레이션 편집 금지(V704 신규만). init_codegen.sql 미러.
- 신규 @Scheduled 워커 → @EnableScheduling 결선 확인(module-first-scheduled 함정).
- 신규 cross-BC 포트 **2종** 소비(권한·보안게이트) → OpenApi/full-boot @MockBean 회귀 확인(new-crossbc-dep 함정, 전 로드 슬라이스 mock).
- prod 조립 부팅 재검증 필수(cross-BC @Component 추가 PR — prod-assembly-boot-verification, rebase 후 :modules:app:test).
- 보안 게이트는 fail-closed(판정 불명=skip). allow-all default 금지(IssueVisibilityPort 정신).

## 측정 가능한 완료 기준

1. 매핑 CRUD 4연산 동작 + 프로젝트 관리자 게이트(비관리자 403, 미인증 401) — 단위+HTTP 통합 테스트.
2. eventTypes 검증(미지/빈 → 400).
3. notification이 projectKey 이벤트마다 `q_slack_channel_broadcasts`에 정확히 1회 발행(수신자 0이어도 발행) — 통합 테스트.
4. slack 워커가 event_filter 매칭 채널에만 게시, 다대다 팬아웃, 채널당 effectively-once(재전달 중복 0) — Testcontainers pgmq 통합 테스트(happy/미매핑/필터불일치/영구실패/재시도/다채널).
5. cross-BC 포트 2종 fail-closed(권한: 미해석 키 거부 / 보안: 제한·불명 시 skip) — 단위 테스트.
6. 보안등급 이슈는 채널 게시 제외(FR9) — 통합 테스트(제한 이슈 → 게시 0).
7. ArchUnit green(BC 격리·포트 경계), detekt/ktlint green.
8. :modules:app prod 조립 부팅 통과(포트 adapter 2종 + 워커 스케줄 결선).
9. 전수 동기화(fr-index D단계 체크 · product §2.3 · SDD 필요시 · verify-master-plan) — merge 전.

## Brainstorming Check

✅ 통과 (1회 iteration). 집중 자기검증으로 발견한 gap 처리 완료.
- **구현 refinement 4건** 스펙 반영. 브로드캐스트 배치(정책 early-return 이전)·신규 `SlackChannelBroadcaster` 컴포넌트(NotificationChannelSender 아님)·버튼 없는 렌더·notification 큐 존재 mirror 마이그레이션.
- **team_id 해석** 단일 설치 가정 문서화(EC12).
- **보안 결정 Maxi 확정** — 보안등급 이슈 제외(fail-closed). 신규 포트 `IssueSecurityClassificationPort`(FR9) 추가. 기존 포트로 대체 불가 확인(IssueVisibilityPort·IssueSecurityDirectory 목적 상이).
- 잔여 gap 없음. 다음 단계(plan)로 진행 가능.
