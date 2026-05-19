# 21. 프론트엔드 아키텍처

## 21.1 설계 원칙

| 원칙 | 의미 |
|---|---|
| 표준화 | 검증된 라이브러리 조합으로 학습 곡선 최소화 |
| 타입 안전 | TypeScript strict + noUncheckedIndexedAccess, 런타임 검증 (Zod) |
| 접근성 | WCAG 2.1 AA, 키보드/스크린리더 완전 지원 |
| 성능 | 100명 동접에서 부드러운 UX (LCP 2.5초, INP 200ms) |
| 일관성 | 디자인 시스템 기반, 컴포넌트 재사용 |
| 이식성 | v0.5 Wiki와 동일 스택 공유 |
| AI 친화 | Claude Code가 효과적으로 생성/수정 가능 |

## 21.2 핵심 스택

| 계층 | 기술 |
|---|---|
| 언어 | TypeScript 5 (strict + noUncheckedIndexedAccess) |
| 프레임워크 | React 19 |
| 빌드 | Vite 6 |
| 패키지 매니저 | pnpm (모노레포) |
| 라우팅 | TanStack Router |
| 서버 상태 | TanStack Query v5 |
| 클라이언트 상태 | Zustand v5 |
| 스타일 | Tailwind CSS v4 |
| 컴포넌트 (헤드리스) | Radix UI |
| 컴포넌트 (UI) | shadcn/ui |
| 아이콘 | lucide-react |
| 폼 | React Hook Form + Zod |
| HTTP | ky |
| WebSocket | @stomp/stompjs |
| 날짜 | date-fns + date-fns-tz |
| 국제화 | i18next |
| 인증 | oidc-client-ts |
| 테스트 | Vitest + Playwright + Testing Library |

## 21.3 UI 라이브러리 카탈로그

### 데이터 표시
| 용도 | 라이브러리 |
|---|---|
| 테이블 (헤드리스) | TanStack Table v8 |
| 가상 스크롤 | TanStack Virtual |
| 트리 뷰 | react-arborist |
| 페이지네이션 | TanStack Query (cursor) |

### 인터랙션
| 용도 | 라이브러리 |
|---|---|
| 드래그앤드롭 | @dnd-kit (보드/백로그/대시보드) |
| 그리드 레이아웃 | react-grid-layout |
| 명령 팔레트 | cmdk |
| 키보드 단축키 | react-hotkeys-hook |
| 자동완성 | cmdk |
| 토스트 | sonner |
| 모달/다이얼로그 | Radix Dialog |
| 툴팁/팝오버 | Radix Tooltip/Popover |

### 시각화
| 용도 | 라이브러리 |
|---|---|
| 일반 차트 | Recharts (번다운/번업/벨로시티/CFD) |
| Gantt | 자체 SVG (PoC 후 결정) |
| 미니 차트 | react-sparklines |

### 에디터
| 용도 | 라이브러리 |
|---|---|
| Markdown 에디터 | TipTap (이슈/댓글/위키 공통) |
| Markdown 렌더링 | react-markdown + remark-gfm |
| 코드 하이라이팅 | Shiki |
| 수식 | KaTeX (v0.5 위키) |

### 파일
| 용도 | 라이브러리 |
|---|---|
| 업로드 | react-dropzone |
| PDF 미리보기 | react-pdf |
| 동영상 | video.js 또는 native |
| CSV 파싱 | papaparse |

## 21.4 디자인 시스템

### 디자인 토큰 (Tailwind v4 + CSS Variables)

```css
@theme {
  /* 색상 */
  --color-brand-50:  #eff6ff;
  --color-brand-500: #2e75b6;  /* Atlas Blue */
  --color-brand-900: #1e3a5f;

  /* 우선순위 */
  --color-priority-highest: #dc2626;
  --color-priority-high:    #ea580c;
  --color-priority-medium:  #facc15;
  --color-priority-low:     #65a30d;
  --color-priority-lowest:  #6b7280;

  /* 폰트 */
  --font-sans: "Pretendard Variable", -apple-system, sans-serif;
  --font-mono: "D2Coding", "Consolas", monospace;
}
```

### 테마
- Light / Dark / System (OS 따라감)
- 깜빡임 방지 (Theme provider blocking script)

### Atlas 자체 컴포넌트
- IssueCard, IssueLink, IssueStatusBadge, PriorityBadge
- UserAvatar (상태/부재 포함), UserMention
- MarkdownEditor, MarkdownRenderer
- KanbanColumn, KanbanCard, BoardSwimlane
- TimelineBar, TimelineDependency
- GadgetGrid, Gadget (기본 클래스)
- BurndownChart, VelocityChart, CFDChart

## 21.5 프로젝트 구조

```
atlas/
├── apps/
│   ├── web/                # 메인 (Issues + Wiki 호스트)
│   └── admin/              # 관리자
├── packages/
│   ├── ui/                 # shadcn 컴포넌트
│   ├── design-tokens/      # Tailwind 설정
│   ├── api-client/         # 타입 안전 API
│   ├── auth/               # OIDC, 토큰
│   ├── icons/              # 아이콘
│   ├── markdown/           # TipTap
│   ├── i18n/               # 번역 리소스
│   └── utils/
├── features/               # 기능별
│   ├── issues/
│   ├── boards/
│   ├── timeline/
│   ├── dashboard/
│   ├── automation/
│   ├── slack/
│   ├── personal/
│   └── wiki/               # v0.5
└── tools/
```

### 기능 모듈 내부

```
features/issues/
├── api/                # TanStack Query 훅
│   ├── queries.ts
│   ├── mutations.ts
│   └── schemas.ts      # Zod
├── components/         # 이 기능 전용
├── hooks/
├── stores/             # Zustand (필요 시)
├── pages/              # 라우트 매핑
└── index.ts            # public API
```

### 의존성 규칙

- apps/web → features/*, packages/*
- features/* → packages/*
- features/* 간 직접 import 금지 (apps에서 통합)
- ESLint 규칙으로 위반 차단

## 21.6 상태 관리

| 상태 | 도구 | 예시 |
|---|---|---|
| 서버 상태 | TanStack Query | 이슈, 사용자 |
| URL 상태 | TanStack Router | 필터, 정렬, 탭 |
| 전역 클라이언트 | Zustand | 테마, 사이드바 |
| 로컬 | useState | 폼, 다이얼로그 |
| 폼 | React Hook Form | 입력 필드 |

자세한 패턴은 `.claude/skills/atlas-frontend-component/SKILL.md` 참조.

## 21.7 API 통신

### 클라이언트 (packages/api-client)

- OpenAPI 스펙에서 타입 자동 생성
- ky 기반 HTTP
- JWT 자동 첨부 (beforeRequest hook)
- 401 자동 refresh (afterResponse hook)

### TanStack Query 패턴

- 쿼리 키 표준화: `['issues', key]`
- Query Options 패턴
- 낙관적 업데이트 (댓글, 즐겨찾기 등)
- Infinite Query (활동 피드)
- WebSocket 통합 (수신 시 invalidate)

### Zod 검증

API 응답은 항상 Zod로 런타임 검증.

## 21.8 WebSocket (STOMP)

- @stomp/stompjs + reconnecting-websocket
- 자동 재연결 (지수 백오프: 5s → 60s)
- 네트워크 복귀 시 즉시 재연결
- 사용: Inbox 실시간, 보드 협업, 댓글 실시간

## 21.9 폼

- React Hook Form + Zod resolver
- Auto-save (2초 debounce, LocalStorage + 서버 동기화)
- 저장 상태 표시 ("저장됨" / "저장 중..." / "오프라인")

## 21.10 에디터 (TipTap)

### 단계별

| 단계 | 기능 |
|---|---|
| Phase 1 (이슈 본문) | Markdown 단축, Heading, List, Code Block, Bold/Italic, Link, Quote |
| Phase 2 (멘션 + 첨부) | @ 멘션, 이미지 인라인, 이슈 키 자동 링크 |
| Phase 3 (테이블) | Table, Task List |
| v0.5 (위키) | 50종 블록 |
| Phase 4+ (협업) | Yjs CRDT |

### 컴포넌트 추상화

```tsx
import { AtlasEditor } from '@atlas/markdown';

<AtlasEditor
  variant="issue-body"  // 'issue-body' | 'comment' | 'wiki'
  value={content}
  onChange={setContent}
  features={['mention', 'attachment', 'issue-link']}
/>
```

### 저장 형식

- 내부: TipTap JSON
- 저장: Markdown (tiptap-markdown 확장)
- 이유: Git 호환, 외부 도구 친화, 마이그레이션 표준

## 21.11 인증 클라이언트

- OIDC: Authorization Code + PKCE
- Access Token: sessionStorage
- Refresh Token: HttpOnly Cookie
- Silent Refresh: iframe + prompt=none
- 라우트 보호: TanStack Router beforeLoad

## 21.12 국제화 (i18n)

- 한국어 (ko, 기본) / English (en)
- 네임스페이스 분리: common, issues, boards, settings
- Lazy loading
- 의미 키 사용 (`issue.create` 같은)

## 21.13 접근성

- WCAG 2.1 Level AA
- Radix UI 기반 (기본 접근성)
- 색상 대비 4.5:1
- 키보드 네비게이션 완전 지원
- 스크린리더 ARIA
- 검증: axe-core (Vitest), Lighthouse (CI)

## 21.14 성능

| 목표 | 값 |
|---|---|
| 메인 청크 | 200KB gzip 이내 |
| LCP | 2.5초 |
| INP | 200ms |
| CLS | 0.1 |

### 기법
- React Compiler (자동 메모이제이션)
- 라우트 단위 코드 분할
- 가상 스크롤 (TanStack Virtual)
- 동적 import (Recharts, TipTap 등 무거운 것)
- TanStack Query 캐싱 (staleTime 적절)
- 이미지 lazy + AVIF/WebP

## 21.15 테스트

### 피라미드 (1인+AI 환경 조정)
- 단위 60% (Vitest)
- 통합 25% (Testing Library + MSW)
- E2E 15% (Playwright) ← 비중 ↑

### 시각 회귀
- Playwright Visual Comparison (선택)
- 주요 컴포넌트 스크린샷

## 21.16 빌드 / 배포

- Vite production build (Rollup)
- 환경별 .env (.env.dev, .env.staging, .env.production)
- 환경 변수 prefix: VITE_*
- Source Map: production은 Sentry 업로드
- CI: GitHub Actions / GitLab CI
- 배포: Nginx 정적 서빙

## 21.17 PWA

- Web App Manifest
- Service Worker (Workbox): 정적 캐시, 오프라인 fallback
- Web Push (Phase 4+)
- 반응형 + 터치 최적화 (44x44px)

## 21.18 다음 챕터

- 구체적 코딩 가이드 → `.claude/skills/atlas-frontend-component/SKILL.md`
- 디자인 시스템 → 22장의 Skills 참조
- 22장 → [22. Claude Code 환경](22-claude-code-env.md)
