# 23. Atlas Wiki (v0.5 예고)

> 이 챕터는 v0.5에서 별도 SDD로 분리될 Atlas Wiki의 개요다.
> 본격 설계는 v0.5.x에서 진행한다.

## 23.1 비전

Notion / Confluence 수준의 위키를 Atlas에 통합. 사내 1,000명의 지식 관리 도구.

## 23.2 핵심 기능 (Phase 5~7)

### Phase 5 (Wiki MVP)
- 페이지 CRUD + 계층 구조
- 사이드바 트리 네비게이션
- 기본 블록 (Heading, Paragraph, List, Code, Quote, Divider 등 15종)
- 댓글
- 검색 (Issues와 통합)
- 즐겨찾기, 최근 본

### Phase 6 (Wiki Core)
- Database (Table/Board/Calendar/Gallery/Timeline 5뷰)
- 50종 블록 완성 (Embed, Callout, Toggle, Synced 등)
- 백링크
- Atlas Issues 임베드 (AQL Query Block)
- 페이지 템플릿
- 페이지 권한 (상속 + 오버라이드)

### Phase 7 (Wiki Pro)
- 실시간 협업 편집 (Yjs CRDT)
- 협업 커서 + Presence
- 페이지 버전 관리 + diff
- AI 기반 요약/검색 (선택)

## 23.3 Issues와 Wiki의 관계

Jira ↔ Confluence 관계와 동일:
- **독립된 데이터 모델**: Issues와 Wiki는 별개 엔티티
- **공유 자원**: User, Permission, Search, Notification, Comment
- **상호 임베드**: Wiki에 AQL Query Block으로 Issues 임베드, Issues 본문에 Wiki 페이지 링크

## 23.4 기술적 결정 (예상)

| 영역 | 결정 |
|---|---|
| 에디터 | TipTap (Issues와 공통) |
| 블록 저장 | JSONB (`page.blocks`) |
| Database 데이터 | 별도 테이블 (`db_record`) |
| 검색 | PostgreSQL FTS (Issues와 통합) |
| 실시간 협업 | Yjs + WebSocket (Phase 7) |
| 권한 | Issues 권한 모델 확장 |

## 23.5 다음 단계

- 본격 SDD 작성 (v0.5.x)
- TipTap 50종 블록 PoC (Phase 0 검증)
- Database 5뷰 PoC
- Yjs 실시간 협업 PoC (Phase 7 전)

## 23.6 끝

상세 설계는 별도 문서에서. 본 v0.5.0 SDD는 Issues 영역에 집중.
