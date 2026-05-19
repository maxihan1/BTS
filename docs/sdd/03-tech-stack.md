# 03. 기술 스택

## 3.1 선정 원칙

| 원칙 | 의미 |
|---|---|
| **사내 시스템 최적화** | 매니지드 서비스 의존 최소화 |
| **이식성** | 표준 Linux + Docker로 어디든 배포 |
| **운영 단순화** | 1인이 0.2 FTE로 운영 가능 |
| **성숙도** | 검증된 오픈소스, 풍부한 한국어 자료 |
| **AI 친화** | Claude Code가 효과적으로 코드 생성 가능한 스택 |

## 3.2 백엔드 스택

| 계층 | 선정 기술 | 선정 사유 |
|---|---|---|
| 언어 | Kotlin (JDK 21) | JVM 생태계 + 표현력, Spring 통합 |
| 프레임워크 | Spring Boot 3.3+ | 사실상 표준, 풍부한 자료 |
| 빌드 | Gradle (Kotlin DSL) | Kotlin 친화 |
| ORM/쿼리 | jOOQ | 타입 안전 SQL, JPA 대비 명시적 |
| DB 마이그레이션 | Flyway | 단순, 신뢰성 |
| 검증 | Bean Validation + Konform | 도메인 검증 |
| API 문서 | springdoc-openapi | OpenAPI 3 자동 생성 |
| 큐 | pgmq (PostgreSQL Extension) | DB 통합 큐 |
| AQL 파서 | ANTLR 4 | JQL 호환 파서 |
| 검색 | PostgreSQL FTS (tsvector + GIN) | 외부 의존 없음 |
| 캐시 | Redis 7 (Lettuce 클라이언트) | 표준 |
| 파일 저장 | MinIO S3 호환 또는 NCP Object Storage | 이식성 |
| 인증 | Keycloak 25 | OIDC/SAML/LDAP 모두 지원 |
| 메일 | Spring Mail + SMTP | 사내 메일 서버 통합 |
| 마크다운 | Flexmark | CommonMark + GFM |
| PDF | openhtmltopdf | 이슈 PDF 출력 |
| LexoRank | 자체 구현 (lexorank.kt) | 백로그 정렬 |
| 테스트 | JUnit 5 + Testcontainers + MockK | 통합 테스트 |

## 3.3 프론트엔드 스택

(상세는 [21. 프론트엔드 아키텍처](21-frontend.md))

| 영역 | 라이브러리 |
|---|---|
| 언어 | TypeScript 5.x (strict + noUncheckedIndexedAccess) |
| 프레임워크 | React 19 |
| 빌드 | Vite 6 |
| 패키지 매니저 | pnpm |
| 라우팅 | TanStack Router |
| 서버 상태 | TanStack Query v5 |
| 클라이언트 상태 | Zustand v5 |
| 스타일 | Tailwind CSS v4 |
| UI | shadcn/ui + Radix UI |
| 폼 | React Hook Form + Zod |
| 테이블 | TanStack Table v8 |
| 가상 스크롤 | TanStack Virtual |
| 드래그앤드롭 | @dnd-kit |
| 차트 | Recharts |
| Gantt | 자체 SVG (PoC 후 결정) |
| 그리드 레이아웃 | react-grid-layout |
| 명령 팔레트 | cmdk |
| 에디터 | TipTap (이슈 + 위키 공통) |
| HTTP | ky |
| WebSocket | @stomp/stompjs |
| 날짜 | date-fns + date-fns-tz |
| i18n | i18next |
| 인증 | oidc-client-ts |
| 테스트 | Vitest + Playwright + Testing Library |

## 3.4 인프라 / 운영

| 영역 | 선정 |
|---|---|
| 배포 | Docker Compose (단일 호스트) |
| 리버스 프록시 | Nginx |
| 인프라 (기본) | Naver Cloud Standard 2vCPU/8GB + 2vCPU/4GB |
| 인프라 (대안) | 온프레미스 / 하이브리드 / AWS Seoul |
| 모니터링 | Grafana Cloud Free 또는 자체 Grafana + Prometheus |
| 로그 | Loki |
| 외부 Uptime | UptimeRobot (5분 간격) |
| CI/CD | GitHub Actions 또는 GitLab CI |
| 컨테이너 레지스트리 | 사내 또는 GitHub Container Registry |
| 보안 스캔 | Dependabot + Trivy |

## 3.5 핵심 결정 1: PostgreSQL FTS (vs OpenSearch)

### 3.5.1 배경

v0.2에서는 OpenSearch 클러스터를 검색 엔진으로 가정했다. 이는 1억 건 규모를 가정한 선택이었으나, v0.3에서 NFR이 100만 건으로 재조정되면서 PostgreSQL FTS로 대체했다.

### 3.5.2 구현 방식

```sql
-- tsvector 컬럼 (자동 생성)
ALTER TABLE issue ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(key, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(summary, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(description, '')), 'B')
  ) STORED;

-- GIN 인덱스
CREATE INDEX idx_issue_search ON issue USING GIN(search_vector);

-- 검색 쿼리
SELECT * FROM issue
WHERE search_vector @@ plainto_tsquery('simple', '로그인 오류')
ORDER BY ts_rank(search_vector, plainto_tsquery('simple', '로그인 오류')) DESC;
```

### 3.5.3 한글 검색 보강

| 기법 | 설명 |
|---|---|
| pg_trgm | 부분 문자열 매칭 (`LIKE '%키워드%'` 가속) |
| 사용자 사전 | 자주 쓰는 약어/동의어를 application 레이어에서 확장 |
| 자동완성 | tsquery prefix 매칭 + pg_trgm 유사도 |

### 3.5.4 확장 트리거

PostgreSQL FTS로 시작하되, 다음 조건 충족 시 확장 검토:

| 조건 | 대응 |
|---|---|
| AQL 검색 p95 > 1초 지속 | Meilisearch 추가 (월 +2~3만원) |
| 이슈 수 > 500만 건 | OpenSearch 클러스터 추가 |
| 오타 허용 검색 강력 요구 | Meilisearch (typo tolerance 우수) |
| 다국어 확장 | OpenSearch (다양한 analyzer) |

## 3.6 핵심 결정 2: DB 기반 큐 (vs Kafka)

### 3.6.1 배경

v0.2에서 Kafka 3 브로커 클러스터를 메시지 큐로 가정했다. 이는 초당 수천 이벤트 처리를 위한 선택이었으나, v0.3에서 RPS 3~10으로 재조정되면서 DB 기반 큐(pgmq)로 대체했다.

### 3.6.2 pgmq 사용 예시

```sql
-- 큐 생성 (한 번)
SELECT pgmq.create('notification_queue');

-- 메시지 발행 (애플리케이션 트랜잭션 내부)
SELECT pgmq.send(
  'notification_queue',
  jsonb_build_object(
    'event', 'issue.created',
    'issue_id', NEW.id,
    'actor_id', NEW.reporter_id
  )
);

-- Worker가 메시지 처리 (SKIP LOCKED)
SELECT * FROM pgmq.read('notification_queue', 30, 10);
```

### 3.6.3 DB 큐의 장점

| 장점 | 설명 |
|---|---|
| 트랜잭션 일관성 | 이슈 저장과 알림 발송이 같은 트랜잭션 (Outbox 패턴 불필요) |
| 운영 단순화 | 추가 인프라 컴포넌트 없음 |
| 디버깅 용이 | SQL로 큐 상태 직접 확인 |
| 백업 통합 | DB 백업에 큐 상태 포함 |

### 3.6.4 확장 트리거

초당 100건 이상 이벤트 지속 시 Redis Streams 또는 Kafka 검토.

## 3.7 핵심 결정 3: 단일 에디터 (TipTap)

### 3.7.1 결정

이슈 본문, 댓글, v0.5 위키 페이지 모두 **TipTap**을 단일 에디터로 사용한다.

### 3.7.2 사유

| 사유 | 설명 |
|---|---|
| 성숙도 | ProseMirror 기반, Notion/GitLab/Linear 등 사용 |
| React 19 공식 지원 | 즉시 사용 가능 |
| 확장성 | Extension 시스템으로 단계적 기능 추가 |
| 협업 편집 | Yjs CRDT 통합 가능 (Phase 4+) |
| Markdown 호환 | 저장은 Markdown, 편집은 Rich |
| 단일 학습 | 한 에디터만 학습하면 모든 영역 사용 |

### 3.7.3 단계별 확장

| 단계 | 활성 기능 |
|---|---|
| Phase 1 (이슈 본문) | Heading 3종, List, Code Block, Bold/Italic, Link, Quote, Markdown 단축 |
| Phase 2 (멘션 + 첨부) | @ 멘션, 이미지 인라인, 이슈 키 자동 링크 |
| Phase 3 (테이블 + 체크리스트) | Table, Task List |
| v0.5 (위키 전체) | 50종 블록 (Toggle, Callout, Embed, Database 등) |
| Phase 4+ (협업) | Yjs CRDT, 실시간 커서 |

## 3.8 핵심 결정 4: 인프라 선택 (Naver Cloud 기본)

### 3.8.1 환경별 비용 비교 (1,000명 기준)

| 환경 | 월 비용 | 연 비용 | 운영 부담 |
|---|---|---|---|
| AWS Seoul | 20만원 | 240만원 | 낮음 |
| **Naver Cloud** ⭐ | **16만원** | **195만원** | **낮음** |
| 온프레미스 | 0~7만원 | 0~80만원 | 중간 |
| 하이브리드 (운영=사내, 백업=NCP) | 1.5만원 | 18만원 | 중간 |

### 3.8.2 Naver Cloud 선택 이유

- 국내 리전, 사내망 친화 (한국 데이터 잔류)
- 동급 사양에서 AWS보다 저렴
- 한국어 지원, 사내 IT팀이 이미 사용 중인 경우 많음
- 사내 IDC 가용 시 즉시 하이브리드로 전환 가능

### 3.8.3 권장 사양

| 역할 | 사양 | 비고 |
|---|---|---|
| App 서버 | Standard 2 vCPU / 8GB | Spring Boot + Worker + Nginx |
| Data 서버 | Standard 2 vCPU / 4GB | PostgreSQL + Redis + Keycloak |
| Block Storage (DB) | 200GB SSD | 3년 누적 가정 |
| Block Storage (App) | 50GB SSD | OS + 앱 + 로그 |
| Object Storage | 500GB | 첨부 + 백업 |

## 3.9 핵심 결정 5: AI 친화 스택

Claude Code 환경을 고려한 추가 결정:

| 결정 | 사유 |
|---|---|
| TypeScript strict + noUncheckedIndexedAccess | Claude 코드의 런타임 오류를 컴파일 시점에 잡음 |
| Zod 런타임 검증 | 타입과 런타임 불일치 자동 감지 |
| 명확한 에러 메시지 | Claude가 오류 보고 + 수정 사이클을 빠르게 |
| OpenAPI 코드 생성 | 백엔드 ↔ 프론트엔드 타입 자동 동기화 |
| Docker Compose | 로컬 환경 재현성 + Claude가 환경 설정 가능 |
| 명시적 모듈 경계 | Claude가 컨텍스트 분리해 작업 가능 |
| 풍부한 테스트 | Claude의 코드 검증 안전망 |

자세한 내용은 [22. Claude Code 개발 환경](22-claude-code-env.md) 참조.

## 3.10 다음 챕터

- 이 스택이 어떻게 조립되는지 → [04. 시스템 아키텍처](04-architecture.md)
- 데이터 모델 → [05. 데이터 모델](05-data-model.md)
- 인프라 운영 디테일 → [16. 인프라 / 배포 / 운영](16-infrastructure.md)
