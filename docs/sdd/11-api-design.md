# 11. API 설계

## 11.1 원칙

| 원칙 | 의미 |
|---|---|
| REST 기본 | 자원 중심, HTTP 동사 의미 |
| 일관성 | 모든 응답이 같은 패턴 |
| OpenAPI 3 | 자동 문서 + 클라이언트 코드 생성 |
| 버전: URL prefix | `/api/v1/...` |
| JSON 본문 | application/json |
| 인증: Bearer JWT | Authorization 헤더 |
| Pagination: cursor | offset 대신 cursor 기반 |

## 11.2 핵심 엔드포인트 (요약)

### 이슈
- `GET /api/v1/issues/{key}` - 단건 조회
- `POST /api/v1/issues` - 생성
- `PATCH /api/v1/issues/{key}` - 부분 수정
- `DELETE /api/v1/issues/{key}` - 소프트 삭제
- `POST /api/v1/issues/{key}/transitions` - 상태 전환
- `POST /api/v1/issues/{key}/comments` - 댓글
- `POST /api/v1/issues/{key}/worklog` - Worklog
- `GET /api/v1/issues/{key}/history` - 이력

### 검색
- `POST /api/v1/search` - AQL 검색
- `GET /api/v1/filters` - 저장된 필터
- `POST /api/v1/filters` - 필터 저장

### 프로젝트
- `GET /api/v1/projects` - 목록
- `GET /api/v1/projects/{key}` - 단건
- `GET /api/v1/projects/{key}/components`
- `GET /api/v1/projects/{key}/versions`

### 보드/스프린트
- `GET /api/v1/boards/{id}` - 보드
- `GET /api/v1/sprints?boardId=...`
- `POST /api/v1/sprints/{id}/start`
- `POST /api/v1/sprints/{id}/complete`

### 인증
- `POST /api/v1/auth/login` - 로컬 로그인
- `POST /api/v1/auth/refresh` - 토큰 갱신
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me` - 현재 사용자
- `POST /api/v1/auth/mfa/setup` - 2FA 설정

### 사용자
- `GET /api/v1/users/{id}/profile`
- `PATCH /api/v1/users/me/profile`
- `GET /api/v1/users/me/preferences`
- `PATCH /api/v1/users/me/preferences`

### 자동화
- `GET /api/v1/automation/rules?project={key}`
- `POST /api/v1/automation/rules`
- `POST /api/v1/automation/rules/{id}/test` - 드라이런

### Webhook (Outbound)
- `POST /api/v1/webhooks` - 등록
- `GET /api/v1/webhooks/{id}/deliveries` - 발송 이력

## 11.3 응답 표준

### 성공
```json
{
  "data": { ... },
  "meta": { "page": { "next": "cursor_string" } }
}
```

### 오류 (RFC 7807 Problem Details)
```json
{
  "type": "https://atlas.docs/errors/issue-not-found",
  "title": "Issue not found",
  "status": 404,
  "detail": "Issue with key PROJ-999 does not exist",
  "instance": "/api/v1/issues/PROJ-999",
  "errorCode": "issue.not_found"
}
```

## 11.4 인증

### 11.4.1 JWT (Bearer Token)
- Access Token: 15분
- Refresh Token: 14일 (HttpOnly Cookie)
- RS256 서명

### 11.4.2 Personal Access Token (PAT, FR-API-04)
- 사용자가 직접 생성 (CLI/스크립트용)
- 만료일 + 스코프 제한
- DB에 SHA-256 해시 저장 (원본 노출은 생성 시 1회만)

## 11.5 WebSocket (STOMP)

```
ws://atlas.company.com/ws

구독:
  /user/queue/notifications  - 개인 알림
  /topic/issues/{key}        - 이슈 실시간 업데이트
  /topic/boards/{id}         - 보드 실시간 업데이트
```

JWT를 connect 헤더로 인증.

## 11.6 Rate Limiting

| 종류 | 제한 |
|---|---|
| 로그인 시도 | 5회/분 (IP) |
| 일반 API | 100 req/s (사용자) |
| 검색 API | 30 req/s (사용자) |
| Webhook 발송 | 10 req/s (URL) |

Redis 기반 토큰 버킷.

## 11.7 Slash 명령어 (FR-UX-04)

웹 UI 내부 명령:

| 명령 | 동작 |
|---|---|
| `/create` | 이슈 생성 모달 |
| `/find {쿼리}` | 검색 결과 |
| `/goto {key}` | 이슈로 이동 |
| `/my` | 내 할당 이슈 |
| `/recent` | 최근 본 |

cmdk 라이브러리로 구현.

## 11.8 OpenAPI 3 문서

- `/api/v1/docs` - Swagger UI
- `/api/v1/openapi.json` - 스펙
- springdoc-openapi로 자동 생성
- 프론트엔드 클라이언트 자동 생성 (`packages/api-client/`)

## 11.9 다음 챕터

- 권한 모델 → [12. 권한 모델](12-permissions.md)
- 보드/백로그/타임라인 → [13. UI 영역](13-board-backlog-timeline.md)
