# FR-SL-05 인터랙티브 메시지 (버튼/메뉴) — 스펙

> 날짜. 2026-07-12
> BC. slack-integration (`com.bts.slack`)
> 선행. FR-SL-04(Slash — 인바운드 서명검증·ack·response_url), FR-SL-03(Unfurl — Events·역매핑), FR-AT-02(IssueMutationPort), FR-BD-01(IssueTransitionPort)
> 관련 SDD. §9.3.5 · product §3.3
> Maxi 스코프 결정(2026-07-12). 풀세트(완료+담당자+상세보기+코멘트 모달) · 완료=resolution 선택 모달 · V703 감사 테이블 신설

## 개요

> **PR 분할 (Maxi 확정 2026-07-12).** FR-SL-05는 2 PR로 구현한다.
> - **PR1 (이 스펙/plan)** — 인바운드 인터랙티브 인프라 전체(`POST /slack/interactions`·서명검증·payload 파서 2종·모달 열기/제출·메시지 갱신·V703 감사·신규 `IssueCompletionOptionsPort`) + **완료로 표시(resolution 모달)** + **상세보기(url)**.
> - **PR2 (후속)** — **담당자 변경(users_select)** + **코멘트 추가(모달)**. PR1의 인프라(컨트롤러·파서·모달·메시지 클라이언트·역매핑·V703) 재사용, 액션 2종만 추가.
> - FR 카운트 불변(FR-SL-05 1개, 2 PR에 걸쳐 구현 — FR-SL-01/02 D-phase 분할 선례). D6/D7 UI=해당없음, E2E는 각 PR이 자기 액션 커버.

Slack 알림 메시지(FR-SL-02 담당자 배정 DM)에 액션 요소를 붙이고, 사용자가 Slack 안에서 버튼/메뉴/모달로 이슈를 직접 조작한다. 클릭 → Slack이 `POST /slack/interactions`로 payload 전송 → BTS가 서명검증 → Slack 사용자 역매핑 → 권한 게이트(cross-BC 쓰기 포트) → 이슈 변경 → 원본 메시지 갱신.

### 액션 세트 (Maxi 확정 — 풀세트)

| 요소 | Slack 타입 | 인터랙션 | 처리 |
|---|---|---|---|
| 상세보기 | `button` (url) | 없음(링크 오픈) | BTS 웹 이슈 페이지로 이동. 콜백 no-op ack |
| 완료로 표시 | `button` | `block_actions` → `views.open`(resolution 모달) | 모달 제출(`view_submission`) → `IssueTransitionPort.transition` |
| 담당자 변경 | `users_select` | `block_actions`(selected_user) | 역매핑 → `IssueMutationPort.assign` |
| 코멘트 추가 | `button` | `block_actions` → `views.open`(코멘트 모달) | 모달 제출(`view_submission`) → `IssueMutationPort.addComment` |

## 사용자 시나리오 (Given-When-Then)

**S1. 완료로 표시 (resolution 모달).**
- Given. Slack에 연결된 사용자가 담당자 배정 DM(버튼 포함)을 받았고 이슈에 대해 전환 권한 보유.
- When. `[완료로 표시]` 클릭 → resolution 선택 모달에서 `Fixed` 선택 후 제출.
- Then. 이슈가 DONE 상태로 전환(resolution=Fixed 기록)되고, 원본 DM이 `✅ 완료 처리됨(Fixed)`으로 갱신.

**S2. 담당자 변경 (users_select).**
- Given. 연결된 사용자, 담당자 변경 권한 보유.
- When. `담당자` 셀렉트에서 다른 Slack 사용자 선택.
- Then. 선택된 Slack 사용자를 BTS 사용자로 역매핑 → 담당자 배정 → 메시지 `담당자: OOO` 갱신. 역매핑 실패(연결 안 된 사용자) 시 ephemeral "그 사용자는 Atlas 계정이 연결되어 있지 않습니다".

**S3. 코멘트 추가 (모달).**
- Given. 연결된 사용자, 댓글 권한 보유.
- When. `[코멘트 추가]` → 모달에 텍스트 입력 후 제출.
- Then. 이슈에 댓글 추가(작성자=매핑된 BTS 사용자) → 메시지에 `💬 코멘트 추가됨` 확인.

**S4. 미연결 사용자.**
- Given. Slack 사용자가 BTS 계정 미연결.
- When. 임의 버튼 클릭.
- Then. ephemeral "계정 연결이 필요합니다(`/settings/slack` 안내)". 이슈 변경 없음. (FR-SL-04 D6 동형 — 명시적 호출이므로 침묵 금지)

**S5. 무권한.**
- Given. 연결됐으나 해당 이슈 전환 권한 없음.
- When. 완료 모달 제출.
- Then. `IssueTransitionPort.transition`이 **shared-kernel 타입 예외** `IssueTransitionPermissionDeniedException` throw → 모달에 `response_action:{errors}` 인라인 오류("권한이 없습니다") + 모달 유지. 이슈 변경 0. V703 `outcome=PERMISSION_DENIED`.

**S6. OCC 충돌 (모달 제출 지연).**
- Given. 완료 모달을 연 뒤 다른 사용자가 같은 이슈를 변경(version 증가).
- When. 모달 제출 → `expectedVersion`(모달 오픈 시점 값) 불일치.
- Then. 어댑터가 shared-kernel 타입 예외 `IssueOptimisticLockException` throw → 모달에 `response_action:{errors}`("이슈가 그 사이 변경되었습니다. 다시 시도해 주세요") + 모달 유지. 전환 미적용. V703 `outcome=CONFLICT`.

**S5b. 모달 오픈 시 무권한/미가시.**
- Given. 연결됐으나 완료 옵션을 조회할 수 없음(BROWSE 미충족/미가시).
- When. `[완료로 표시]` 클릭 → `IssueCompletionOptionsPort.getCompletionOptions`가 null.
- Then. 모달 열지 않음 + block_actions `response_url`로 ephemeral 안내. V703 `outcome=PERMISSION_DENIED`. (fail-closed — 이슈 존재/제목 미노출)

**S7. 위조/서명 실패.**
- Given. 잘못된 `X-Slack-Signature` 또는 secret 미설정.
- When. 요청 수신.
- Then. 빈 401. 비밀값·원문·예외 message 미노출(FR-SL-03/04 NFR 동형).

## 기능 요구사항 (FR)

- **F1. 수신 엔드포인트.** `POST /slack/interactions`. `application/x-www-form-urlencoded`의 단일 `payload` 필드(URL-encoded JSON). `@RequestBody String rawBody`로 원문 수신 → `SlackSignatureVerifier.isValid(ts, sig, rawBody)`로 **먼저** 검증 → 통과 후 form-decode하여 `payload` 추출 → JSON 파싱. (FR-SL-04 D1 동형 — @RequestParam 병용 금지)
- **F2. payload 타입 분기.** `block_actions`(버튼/셀렉트)와 `view_submission`(모달 제출) 두 종. `type` 필드로 분기. 알 수 없는 타입 → 200 no-op(Slack 재시도 유발 방지).
- **F3. 상세보기.** `button` with `url`(BTS 웹 이슈 URL). Slack이 링크만 열고 payload는 오지만 `action_id=atlas_view`는 서버에서 no-op 200 ack.
- **F4. 완료로 표시 (모달 오픈).** `atlas_complete` block_actions 수신 → **동기**로 (a) team_id로 봇 토큰 조회 (b) `IssueCompletionOptionsPort.getCompletionOptions` 조회 — **null이면**(무권한/미가시) 모달 안 열고 block_actions `response_url`로 ephemeral + V703(S5b) (c) `views.open(trigger_id, 모달)` — **3초 내**(trigger_id 만료). private_metadata에 **issueKey·expectedVersion·channel·ts만** 박제. **`toStateKey`는 private_metadata에 넣지 않고**, done 전환 다중 시 모달 `static_select` 선택 → 제출 payload `state_values`에서 읽음(단일 전환이면 셀렉트 생략하되 state_values로 통일). 사유. 오픈 시점엔 사용자 선택 미확정이라 박제 시 불일치(리뷰 CONCERN).
- **F5. 완료 모달 제출.** `atlas_complete_modal` view_submission → **동기** `IssueTransitionPort.transition(actorUserId=역매핑, issueKey, toStateKey=state_values, expectedVersion=private_metadata, resolutionId=선택)` (단일 cross-BC 쓰기 — 3초 내 현실적). 실패는 **shared-kernel 타입 예외**로 분류 → 권한거부(`IssueTransitionPermissionDeniedException`)·OCC(`IssueOptimisticLockException`) 시 `response_action:{errors}`(모달 인라인 오류, 모달 유지) + V703(PERMISSION_DENIED/CONFLICT). 성공 시 빈 200(모달 닫기) + 원본 메시지 `chat.update` + V703 SUCCESS. **view_submission엔 response_url이 없으므로**(block_actions만 보유) ephemeral 대신 response_action/chat.update로 결과 전달(리뷰 BLOCKER 해소 — Option A 동기).
- **F6. (PR2) 담당자 변경.** `atlas_assign` users_select → 역매핑 → `IssueMutationPort.assign`. block_actions는 `response_url` 보유라 ack200+@Async 가능. **PR2 후속.**
- **F7. (PR2) 코멘트 추가 (모달).** `atlas_comment` → `views.open` 텍스트 모달 → view_submission → `IssueMutationPort.addComment`. **PR2 후속.**
- **F8. 액션 주체 = 역매핑된 BTS 사용자.** 모든 액션은 `SlackUserMappingRepository.findUserIdBySlackUserId(slackUserId, teamId)`로 해석한 BTS 사용자 권한으로 실행(FR-SL-03/04 동형). actor는 cross-BC 커맨드의 `actorUserId`로 전달(위조 차단 — request body 미수용).
- **F9. 버튼 렌더 (PR1 = 상세보기 + 완료).** `SlackBlockKitRenderer` 확장 — FR-SL-02 담당자 배정 DM에 actions 블록(상세보기 url 버튼 + 완료 버튼) 추가. action_id·value(issueKey) 박제. **게이팅** — `SlackDeliveryWorker`가 모든 알림을 단일 `render()`로 렌더하므로, actions 블록은 `eventType==ISSUE_ASSIGNED`일 때만 전용 렌더 메서드로 붙인다(무조건 render() 추가 시 멘션·댓글 등 모든 DM에 버튼 오배치 — 리뷰 CONCERN). 담당자 select·코멘트 버튼은 **PR2**.
- **F10. 미연결/무권한 처리.** 미연결(역매핑 null) → ephemeral 계정연결 안내 + V703 UNMAPPED. 무권한 → 타입 예외 분류 후 response_action/ephemeral. 둘 다 fail-closed(이슈 변경 0).
- **F11. 감사 로그 (V703).** 모든 인터랙션(성공/미연결/무권한/충돌/오류)을 `slack_interaction_log`에 기록. best-effort(로그 실패가 액션을 막지 않음). **단 PERMISSION_DENIED는 기록 실패 시 WARN 별도 흔적**(N3 차단 증거 우선순위).
- **F12. 메시지 갱신.** view_submission은 response_url 없음 → private_metadata의 channel·ts로 `chat.update`(봇 토큰). block_actions 실패 안내는 payload의 `response_url` ephemeral.
- **F13. 본문 크기 상한 (DoS 가드).** 서명검증(HMAC) **전에** rawBody 크기 상한 검사(SL-04 `SlackCommandsController` 동형, 초과 시 빈 413). interactions payload는 전체 message blocks 포함이라 slash보다 크므로 상한 넉넉히(예: 64KB) 잡되 방어적 천장 유지.

## 비기능 요구사항 (NFR)

- **N1. 서명검증 우선.** 모든 인바운드는 서명검증 통과 후에만 파싱·처리. 실패=빈 401, 비밀/원문/예외 미노출.
- **N2. 3초 룰.** 모달 오픈(views.open)·완료 제출 transition **모두 동기**(trigger_id/응답 3초 내 — 단일 cross-BC 쓰기라 현실적). **PR1엔 @Async 없음** — self-invocation 함정(SlackAsyncConfig/SlashCommandService KDoc 경고) 회피. block_actions·view_submission 동기 핸들러는 top-level try/catch로 항상 200(빈 ack)·비밀/원문 미노출 로그. (PR2 담당자/댓글은 response_url 보유 block_actions라 ack200+@Async 별도.)
- **N3. 권한 위반 차단율 100%.** 모든 쓰기는 fail-closed cross-BC 포트 경유(포트가 권한 강제). V703로 차단 증거 기록.
- **N4. 봇 토큰 미노출.** views.open/chat.update의 봇 토큰은 요청에만 싣고 로그/예외/반환 미노출(SlackMessageClient 선례).
- **N5. best-effort UX.** 네트워크/Slack 실패는 사용자 재시도로 흡수. 별도 pgmq 큐/워커 없음(동기 모달 + @Async 처리).

## API 인터페이스

```
POST /slack/interactions
  Content-Type: application/x-www-form-urlencoded
  Headers: X-Slack-Signature, X-Slack-Request-Timestamp
  Body: payload=<URL-encoded JSON>
  → 200 (block_actions: 즉시 ack / view_submission: {} 또는 response_action)
  → 401 (서명 실패)
```

## 데이터 모델 변경

**V703 (신규) — `slack_interaction_log`.** (V번호는 머지 직전 재확인 — 멀티세션)

```sql
CREATE TABLE slack_interaction_log (
    id            UUID PRIMARY KEY,
    team_id       TEXT NOT NULL,
    slack_user_id TEXT NOT NULL,
    bts_user_id   UUID,                 -- 미연결이면 null
    action_type   TEXT NOT NULL,        -- COMPLETE / ASSIGN / COMMENT / VIEW
    issue_key     TEXT,
    outcome       TEXT NOT NULL,        -- SUCCESS / UNMAPPED / PERMISSION_DENIED / CONFLICT / ERROR
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_slack_interaction_log_created ON slack_interaction_log (created_at DESC);
```

**신규 cross-BC 읽기 포트 (shared-kernel).**
- `IssueCompletionOptionsPort.getCompletionOptions(issueKey, viewerUserId): IssueCompletionOptions?` — fail-closed. `{ version, doneTransitions: List<(toStateKey, label)>, resolutions: List<(id, label)> }`. issue-tracking 어댑터가 구현. (viewer가 못 보면 null)
- 담당자 후보는 Slack `users_select` 네이티브 → 후보 포트 불필요.

**신규 transition 실패분류 타입 예외 (shared-kernel).** — 리뷰 BLOCKER 해소.
- `IssueTransitionPermissionDeniedException`, `IssueOptimisticLockException`(OCC)을 shared-kernel에 신설. `IssueTransitionPort`의 계약 예외로 KDoc 명시. issue-tracking `IssueTransitionAdapter`가 내부 도메인 예외(`IssueAccessDeniedException`·`IssueVersionConflictException` 등)를 이 타입으로 **번역해 던진다**(`IssueMutationPermissionDeniedException` 번역 선례 동형). slack은 예외 클래스명 문자열 매칭 없이 타입으로 PERMISSION_DENIED/CONFLICT/ERROR 분류. (기존 agile-planning 소비자는 generic catch라 무영향 — 하위호환.)

**재사용(신규 아님).** `IssueTransitionPort`(완료), `IssueMutationPort.assign/addComment`(담당자/댓글), `IssueUnfurlPort`(상세 URL·메시지 렌더), `SlackUserMappingRepository`(역매핑), `SlackSignatureVerifier`, `SlackInstallRepository`(봇 토큰), `SlackMessageClient`(views.open/chat.update 확장).

## 엣지 케이스

- **E1. url 버튼 payload.** 상세보기는 링크만 열지만 Slack이 block_actions를 보낼 수 있음 → `atlas_view`는 no-op 200(dispatch 경고 방지).
- **E2. trigger_id 만료.** views.open이 3초 초과 시 Slack 오류 → best-effort 로깅, 사용자 재클릭 유도.
- **E3. OCC 충돌(S6).** 모달 오픈 시점 version과 제출 시점 불일치 → 전환 예외 → ephemeral 재시도 안내.
- **E4. 역매핑 다중/부재.** 미연결(부재) → ephemeral. (역매핑은 V702 UNIQUE라 다중 없음)
- **E5. 봇 토큰 부재/미설치.** team_id로 SlackInstall 조회 실패 → 액션 불가, best-effort 로깅.
- **E6. resolution 불요 워크플로우.** doneTransitions만 있고 resolutions 빈 목록 → 모달에서 resolution 섹션 생략, 전환은 resolutionId=null.
- **E7. 알 수 없는 action_id/callback_id.** 200 no-op(Slack 재시도 방지).

## 제약 조건

- **C1. BC 격리.** slack ↔ issue-tracking 코드 import 0. shared-kernel 포트 경유(신규 IssueCompletionOptionsPort + 기존 재사용).
- **C2. prod SecurityConfig permitAll(`/slack/interactions`)은 배포 조립 후속.** FR-SL-04 D8 동형 — 이번 PR은 slack test-boot SecurityConfig로만 검증. 중앙 등록(events·commands·interactions·install·automation webhook 통합)은 배포 조립 후속.
- **C3. actor 위조 차단.** actorUserId는 역매핑 결과로만 채움. request body/param 미수용.
- **C4. Jackson 비의존 포트.** 신규 포트 반환은 원시 타입·shared VO만(SharedKernelBoundaryArchTest).
- **C5. 멀티세션.** V703 번호·Flyway 경로는 머지 직전 재확인(동시 FR-AT-02 세션).

## 측정 가능한 완료 기준

- [ ] `POST /slack/interactions` — block_actions(완료/담당자/코멘트/상세보기) + view_submission(완료/코멘트 모달) 전 경로 단위·통합 green.
- [ ] 서명 실패 → 401, 유효 서명 → 처리(TestRestTemplate 실서블릿).
- [ ] 미연결/무권한/OCC 충돌 → 각 ephemeral + 이슈 변경 0 + V703 outcome 기록.
- [ ] views.open 동기(3초)·transition/assign/comment @Async·chat.update 경로 검증.
- [ ] slack·shared-kernel·issue-tracking 3모듈 컴파일·테스트 green + :modules:app prod 조립 부팅(신규 IssueCompletionOptionsPort 어댑터 결선 포함).
- [ ] E2E — 가짜 Slack 인터랙션(유효 서명·모달 왕복) happy/미연결/무권한.
