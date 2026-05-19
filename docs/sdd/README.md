# Project Atlas — Software Design Document

**v0.5.0 (Markdown Edition)**

사내 협업 워크스페이스 (Issues + Wiki) 설계 문서

## 메타 정보

| 항목 | 값 |
|---|---|
| 버전 | v0.5.0 |
| 작성일 | 2026-05-19 |
| 작성자 | Maxi |
| 개발 모델 | 1인 단독 (Maxi + Claude Code) |
| 대상 규모 | 사용자 1,000명, 동접 100명 |
| 배포 모델 | 사내망 + VPN 외부 접근 |
| SLA 목표 | 99% (월 다운타임 7시간 허용) |
| 문서 형식 | Markdown (Git 버전 관리, Claude Code 친화) |

## 챕터 목차

### Part I. 개요 및 요구사항

- [00. 전체 개요 및 v0.5.0 변경 요약](00-overview.md)
- [01. 비전 및 범위](01-vision.md)
- [02. 요구사항 (FR/NFR)](02-requirements.md)

### Part II. 기술 결정

- [03. 기술 스택](03-tech-stack.md)
- [04. 시스템 아키텍처](04-architecture.md)
- [05. 데이터 모델](05-data-model.md)

### Part III. 도메인 로직

- [06. 핵심 도메인 시나리오](06-scenarios.md)
- [07. 워크플로우 엔진](07-workflow-engine.md)
- [08. 자동화 엔진](08-automation-engine.md)

### Part IV. 통합 및 인터페이스

- [09. 알림 시스템 + Slack 통합](09-notifications-slack.md)
- [10. 검색 / Export / Import (AQL)](10-search-export-import.md)
- [11. API 설계](11-api-design.md)
- [12. 권한 모델](12-permissions.md)

### Part V. UI/UX 영역

- [13. 보드 / 백로그 / 타임라인](13-board-backlog-timeline.md)
- [14. 대시보드 및 리포트](14-dashboard-reports.md)
- [15. 마이그레이션 전략 (Jira → Atlas)](15-migration.md)

### Part VI. 운영

- [16. 인프라 / 배포 / 운영](16-infrastructure.md)
- [17. 로드맵](17-roadmap.md)
- [18. 부록 (용어집, 변경 이력)](18-appendix.md)

### Part VII. v0.4 추가 영역

- [19. 인증 시스템](19-authentication.md)
- [20. 개인화](20-personalization.md)
- [21. 프론트엔드 아키텍처](21-frontend.md)

### Part VIII. Claude Code 개발 환경 (v0.4.2 신규)

- [22. Claude Code 개발 환경](22-claude-code-env.md)

### Part IX. v0.5 예고

- [23. Atlas Wiki (별도 SDD 예정)](23-wiki-preview.md)

## 변경 이력

| 버전 | 날짜 | 주요 변경 |
|---|---|---|
| v0.1 | 2026-05-19 | 초안 |
| v0.2 | 2026-05-19 | 22개 요구사항 1급 시민 매핑 |
| v0.3 | 2026-05-19 | Lean Architecture (Kafka/OpenSearch/EKS 제거), Jira 보강 14개 기능 |
| v0.4 | 2026-05-19 | 인증/2FA/Slack 본격 통합/개인화 |
| v0.4.1 | 2026-05-19 | 프론트엔드 아키텍처 (21장) |
| **v0.5.0** | **2026-05-19** | **Markdown 통합 재작성, 22장 Claude Code 개발 환경 추가, 1인+AI 개발 모델 반영** |

## 관련 자료

- [CLAUDE.md](../../CLAUDE.md) — Claude Code 매 세션 자동 로드 가이드
- [.claude/skills/](../../.claude/skills/) — 도메인별 Skills
- [.claude/commands/](../../.claude/commands/) — Slash 명령어
- [docs/adr/](../adr/) — Architecture Decision Records (향후 추가)

## 읽는 순서 추천

**처음 보는 사람**: 00 → 01 → 02 → 04 → 17 (전체 그림 파악)

**개발자 (구현 시작)**: 22 → CLAUDE.md → 03 → 05 → 11 → 해당 도메인 챕터

**아키텍트 검토**: 03 → 04 → 16 → 21 → 22

**프로젝트 매니저**: 01 → 02 → 17 → 16.4 (TCO)
