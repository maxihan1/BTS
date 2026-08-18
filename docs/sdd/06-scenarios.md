# 06. 핵심 도메인 시나리오

이 챕터는 주요 사용 시나리오를 시퀀스 흐름으로 정리한다.

## 6.1 이슈 생성 + 알림

```
사용자 → API: POST /api/v1/issues
API → IssueService.create(cmd)
  → IssueKeyGenerator.next(projectId) → "PROJ-124"
  → WorkflowService.initialStatus() → "todo"
  → Repository.save(issue)
  → EventPublisher.publish(IssueCreatedEvent) → pgmq
  ← 응답: IssueResponse(key="PROJ-124", ...)

Worker (pgmq.read):
  → NotificationService.fanOut(event)
    → Reporter/Assignee/Watchers/ComponentLead 알림
    → 채널별 발송 (이메일, 인앱, Slack)
```

## 6.2 워크플로우 상태 전환

```
사용자 → API: POST /api/v1/issues/PROJ-124/transitions
  body: {transitionId: "start-progress"}
→ WorkflowService.transit(issue, transitionId, user, ctx)
  1. From 검증: 현재 상태가 transition.from에 포함되는지
  2. Validators 실행 (RequiredField, Permission 등)
  3. 상태 변경: issue.copy(statusId = transition.to)
  4. Post-functions (SetField, AddWatcher, Notify 등)
  5. IssueTransitionedEvent 발행
  6. 자동화 엔진이 이벤트 받아 추가 규칙 실행
```

## 6.3 자동화 규칙 실행

```
이슈 변경 이벤트 → AutomationEngine
  1. 트리거 매칭 (event_type, project_id)
  2. 조건 평가 (JSONLogic 또는 SpEL)
  3. 액션 실행 순서대로
     - 동기: SetField, AddComment
     - 비동기: CallWebhook, Notify
  4. 실행 이력 저장 (디버깅용)
  5. 실패 시 재시도 (지수 백오프)
```

## 6.4 검색 (AQL)

```
사용자 → API: GET /api/v1/search?aql=...
API → AqlService.execute(aql, user)
  1. AQL 파서 (ANTLR 4) → AST
  2. 권한 적용 (사용자가 볼 수 없는 프로젝트 제외)
  3. AST → SQL (jOOQ DSL)
  4. PostgreSQL 실행 (FTS + 일반 인덱스)
  5. 결과 반환 + 페이지네이션
```

## 6.5 스프린트 시작 + 번다운

```
PM → API: POST /api/v1/sprints/{id}/start
  → SprintService.start(sprintId)
    → 상태 PLANNED → ACTIVE
    → 시작 시점 이슈 스코프 확정 (sprint_issues 멤버십, 스냅샷 테이블 없음)
    → 번다운 차트의 "Ideal Line" 시작점 (총 스코프 = Σ original_estimate_seconds)
  → GET /api/v1/sprints/{id}/burndown 요청 시: worklog started_at 누적을 on-the-fly로 재구성 → 번다운 데이터
```

## 6.6 Slack Slash 명령

```
Slack 사용자: /atlas create "버그 제목"
Slack → Atlas Webhook
  → SlackCommandHandler.handle(payload)
    → Slack user → Atlas user 매핑 확인
    → 채널 → 프로젝트 매핑 확인
    → 이슈 생성
    → Slack 응답: "PROJ-125 생성됨" + 링크
```

## 6.7 LDAP 로그인 (자동 프로비저닝)

```
사용자 → 로그인 (john.doe + 비밀번호)
→ AuthenticationManager
  1. john.doe의 도메인으로 Provider 선택 (LDAP)
  2. LDAP bind 시도
  3. 성공 → LDAP에서 사용자 정보 가져옴
  4. Atlas DB에서 user_identity 검색
     - 없으면: User 자동 생성 + UserIdentity 추가
     - 있으면: 정보 동기화 (이름, 부서, 매니저)
  5. JWT 발급
```

## 6.8 다음 챕터

- 워크플로우 엔진 디테일 → [07. 워크플로우 엔진](07-workflow-engine.md)
- 자동화 엔진 디테일 → [08. 자동화 엔진](08-automation-engine.md)
- 인증 흐름 디테일 → [19. 인증 시스템](19-authentication.md)
