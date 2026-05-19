# 00. 전체 개요 및 v0.5.0 변경 요약

## 0.1 v0.5.0의 의미

v0.5.0은 단순 기능 추가가 아니라 **문서 형식과 개발 모델 자체의 전환점**이다.

### 형식 전환: docx → Markdown

| 영역 | 이전 (v0.1~v0.4.1) | v0.5.0~ |
|---|---|---|
| 문서 형식 | docx 단일 파일 5개 | Markdown 챕터별 분리 |
| 버전 관리 | 수동 (파일명에 버전) | Git (자연스러운 diff/blame) |
| 편집 도구 | MS Word | 어떤 텍스트 에디터든 |
| Claude Code 호환 | 변환 비용 + 토큰 낭비 | 즉시 읽기 가능 |
| 챕터 간 링크 | 페이지 번호 참조 | 상대 경로 링크 |
| 다이어그램 | 외부 이미지 첨부 | Mermaid 인라인 |

### 개발 모델 전환: 팀 → 1인 + AI

| 항목 | v0.4.1까지 가정 | v0.5.0 가정 |
|---|---|---|
| 개발 인력 | 백엔드 2명 + 프론트 1명 + PM 0.5명 | **Maxi 1명** |
| 보조 도구 | 없음 | **Claude Code** (코딩 에이전트) |
| Phase 0~4 기간 | 13개월 | **6~9개월** |
| 연 인건비 | 약 1.2억원 (3.5 FTE 분담) | 약 1,500만원 (Maxi 운영 0.2 FTE 분담) + Claude 구독료 |
| BEP | 3~4년 | **약 6개월** |
| 채용 | 3명 신규 채용 필요 | 채용 불필요 |

이 변화는 22장 "Claude Code 개발 환경"에서 본격 다룬다.

## 0.2 SDD의 누적된 결정 사항

v0.1부터 v0.4.1까지 다섯 차례의 진화를 거치며 누적된 결정들. v0.5.0은 이 결정들을 보존하면서 형식만 바꾼다.

### v0.1 (초안)
- 사내 이슈 트래커 비전
- 엔터프라이즈 가정 (10K 사용자, 1억 이슈, 99.9% SLA)
- AWS + Kafka + OpenSearch + EKS 기본

### v0.2 (요구사항 정리)
- 22개 원본 요구사항을 1급 시민 FR ID로 매핑
- Component, Version, Template, Link, Watcher, Attachment, IssueHistory, IssueMove, Notification, Export 엔티티 명시
- 두 레이어 권한 모델 (프로젝트 행정 + 이슈 데이터 접근)

### v0.3 (Lean Architecture)
- 비기능 요구사항 현실화 (1K 사용자, 100M → 1M 이슈, 99.9% → 99%)
- Kafka 제거 → DB 기반 큐 (pgmq)
- OpenSearch 제거 → PostgreSQL FTS
- EKS 제거 → Docker Compose 단일 호스트
- AWS → Naver Cloud (사내 친화) 기본
- Jira 가이드 검토로 14개 기능 보강 (Timeline/Epic/백로그/Worklog/대시보드/번다운 등)
- 인프라 비용 1.16억원/년 → 195만원/년

### v0.4 (인증/Slack/개인화)
- 플러그형 AuthenticationProvider 구조 (LDAP/SAML/OIDC/Local/OAuth)
- 2FA: TOTP + 백업 코드 (필수), WebAuthn (선택). 관리자 + 민감 프로젝트 강제
- Slack: App + Bot Token, Unfurl/Slash/Interactive
- 개인화: UserProfile/UserPreferences/Favorite/RecentlyViewed/Activity/Calendar
- **제품 비전 확장**: 이슈 트래커 → 협업 워크스페이스 (Wiki 통합 예고)

### v0.4.1 (프론트엔드 아키텍처)
- React 19 + TypeScript 5 strict + Vite + pnpm 모노레포
- TanStack 패밀리 (Router/Query/Table/Virtual)
- Tailwind v4 + shadcn/ui + Radix UI
- @dnd-kit (보드/백로그/대시보드)
- Recharts (5종 차트)
- **TipTap (이슈 본문 + v0.5 위키 공통 에디터)**
- Vitest + Playwright

### v0.5.0 (현재 — Markdown + Claude Code)
- 전체 문서를 Markdown으로 통합 재작성
- 22장 Claude Code 개발 환경 신규 추가
- 개발 모델을 1인 + Claude Code로 재정의
- CLAUDE.md, Skills, Slash Commands 산출물 추가
- 일정/비용 재산정

## 0.3 제품 비전 한 문장 요약

**Atlas는 사내 1,000명 규모 조직을 위한 협업 워크스페이스다. Jira의 이슈 트래킹 기능과 Confluence/Notion 수준의 위키 기능을 통합 제공하며, 매니지드 서비스 의존을 최소화하여 연 운영비 2,000만원 이내로 운영 가능하다.**

## 0.4 모듈 구성

Atlas는 두 개의 메인 모듈로 구성된다.

```
Atlas (사내 협업 워크스페이스)
├── Atlas Issues (이슈 트래킹) — v0.1~v0.5.0
│   ├── 이슈/프로젝트/워크플로우
│   ├── 자동화 엔진
│   ├── 보드/백로그/타임라인
│   ├── 대시보드/리포트
│   └── 알림/통합
└── Atlas Wiki (문서/지식 베이스) — v0.5 별도 SDD 예정
    ├── 페이지 + 사이드바 트리
    ├── 50종 블록 에디터
    ├── Database (Table/Board/Calendar/Gallery/Timeline)
    └── 백링크/검색 통합
```

두 모듈은 사용자/권한/검색/알림을 공유하지만 **데이터 모델은 독립**이다 (Jira ↔ Confluence 관계와 동일).

## 0.5 핵심 기술 결정 한눈에

| 영역 | 결정 |
|---|---|
| 백엔드 언어 | Kotlin + Spring Boot 3 (JDK 21) |
| DB | PostgreSQL 16 (데이터 + FTS + 큐 통합) |
| 캐시 | Redis 7 (단일 컨테이너) |
| 메시지 큐 | DB 기반 (pgmq) |
| 검색 | PostgreSQL FTS (tsvector + GIN) |
| 객체 저장 | MinIO 또는 NCP Object Storage |
| 인증 | Keycloak + Pluggable Provider |
| 프론트엔드 | React 19 + TypeScript 5 strict + Vite |
| UI | Tailwind v4 + shadcn/ui + Radix |
| 상태 관리 | TanStack Query + Zustand |
| 에디터 | TipTap (이슈 + 위키 공통) |
| 배포 | Docker Compose 단일 호스트 |
| 인프라 | Naver Cloud (기본), 온프레미스 / 하이브리드 가능 |
| 개발 도구 | **Claude Code** (1인 + AI 개발) |

## 0.6 비용 요약

| 항목 | 연 비용 |
|---|---|
| 인프라 (Naver Cloud Std 2vCPU/8GB + 2vCPU/4GB + Storage) | 약 195만원 |
| Claude Code 구독 (Max 20x 가정) | 약 250만원 |
| Maxi 운영 (0.2 FTE, 본업 겸직) | 약 1,500만원 |
| 라이선스/3rd-party | 약 50만원 |
| **합계 (TCO)** | **약 1,995만원/년** |
| 참고: Jira Cloud Premium 1,000명 | 약 2.5억원/년 |
| **연간 절감** | **약 2.3억원** |

## 0.7 일정 요약

```
Phase 0 (PoC):         1개월   ─ 워크플로우/AQL/LexoRank/Provider 인터페이스
Phase 1 (MVP):         2개월   ─ 이슈 CRUD, 워크플로우, LDAP/SAML, 기본 프로필
Phase 2 (Core):        2개월   ─ 스프린트, 백로그, 권한, 알림, 2FA, Slack 알림
Phase 3 (Pro):         2개월   ─ 자동화, 타임라인, 대시보드, 리포트, Slack 양방향
Phase 4 (Polish):      1.5개월 ─ SSO 확장, WebAuthn, 감사 로그, PDF, 캘린더
─────────────────────────────
GA (Issues):           T+8.5개월
Phase 5 (Wiki 별도):   +9~12개월
GA (Wiki):             T+18~21개월
```

## 0.8 다음 단계

이 챕터는 전체 개요만 다룬다. 다음 챕터부터 본격적으로 설계 내용이 나온다.

- 시스템 비전과 페르소나가 궁금하면 → [01. 비전 및 범위](01-vision.md)
- 요구사항(FR/NFR)을 확인하려면 → [02. 요구사항](02-requirements.md)
- 기술 스택과 라이브러리 선정 근거가 궁금하면 → [03. 기술 스택](03-tech-stack.md)
- 개발 환경(Claude Code) 설정이 궁금하면 → [22. Claude Code 개발 환경](22-claude-code-env.md)
