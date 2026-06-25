<!-- BTS 디자인 시스템 v0.1 — shadcn/ui (radix-nova) + Tailwind v4 기반 -->

# BTS 디자인 시스템 v0.1

BTS(Project Atlas)는 사내 1,000명 규모 협업 워크스페이스다. 이 문서는 그 첫 번째 디자인 시스템 정의로, **shadcn/ui radix-nova 프리셋** + **Tailwind v4 CSS-first `@theme`** 위에 구축된다. shadcn이 제공하는 CSS 변수(OKLCH 색공간)를 토큰으로 삼아, 별도 디자인 툴 없이 코드와 스펙이 단일 출처(Single Source of Truth)를 공유한다.

본 문서는 **PR #11 (FR-AU-09 로그인 폼 UI)** 구현에 필요한 토큰만 우선 정의한다. 이후 컴포넌트가 추가될 때마다 섹션을 확장한다. 다크 모드 CSS 변수는 shadcn이 `.dark` 클래스로 이미 정의했으나, 토글 UI 및 `prefers-color-scheme` 자동 감지는 후속 PR에서 활성화한다. 라이트 모드가 기본값이며 본 PR에서 의도적으로 다크 모드를 미지원한다.

---

## 1. 디자인 원칙

| # | 원칙 | 설명 |
|---|---|---|
| 1 | **명료성 (Clarity)** | 회사 업무 도구다. 사용자가 정보를 읽고 행동하는 데 걸리는 시간을 최소화한다. 장식보다 인지 속도가 우선이다. |
| 2 | **일관성 (Consistency)** | shadcn/ui 표준 패턴을 그대로 활용한다. 컴포넌트를 직접 발명하기 전에 shadcn 카탈로그에 있는지 먼저 확인한다. |
| 3 | **접근성 (Accessibility)** | WCAG AA를 준수한다. 키보드 탐색, `aria-*` 속성, 색 대비 4.5:1(본문) / 3:1(큰 텍스트)을 모든 컴포넌트에서 만족해야 한다. |
| 4 | **시각적 위계 (Visual Hierarchy)** | 타이포그래피 크기·굵기와 색의 강약으로 위계를 표현한다. 별도 색상을 추가하기 전에 `foreground` / `muted-foreground` / `primary` 조합으로 해결 가능한지 먼저 검토한다. |
| 5 | **절제 (Restraint)** | 장식을 최소화한다. 사용자의 콘텐츠가 주인공이다. 그림자, 애니메이션, 색은 의미가 있을 때만 쓴다. |

---

## 2. 컬러 토큰

### 출처

`apps/web/src/index.css` `:root` 블록의 OKLCH 변수. shadcn/ui `radix-nova` 프리셋이 생성한 값이다. Tailwind v4 `@theme inline` 블록이 이 변수들을 `--color-*` 유틸리티로 연결한다.

> OKLCH란. 색을 "밝기(L) + 채도(C) + 색조(H)"로 표현하는 현대적 색공간이다. 사람의 눈이 인식하는 밝기와 수치가 일치해서, 배경색 대비 계산이 직관적이다.

### 라이트 모드 토큰 표

| CSS 변수 | OKLCH 값 | 한국어 역할 | 사용 위치 예시 |
|---|---|---|---|
| `--background` | `oklch(1 0 0)` | 페이지 배경 (순백) | `<body>`, 페이지 전체 |
| `--foreground` | `oklch(0.145 0 0)` | 기본 텍스트 (거의 검정) | 본문, 제목 |
| `--card` | `oklch(1 0 0)` | 카드 배경 | `<Card>` 컴포넌트 배경 |
| `--card-foreground` | `oklch(0.145 0 0)` | 카드 내부 텍스트 | 카드 안 본문 |
| `--popover` | `oklch(1 0 0)` | 팝오버/드롭다운 배경 | `<Select>`, `<DropdownMenu>` |
| `--popover-foreground` | `oklch(0.145 0 0)` | 팝오버 텍스트 | 드롭다운 항목 |
| `--primary` | `oklch(0.205 0 0)` | 핵심 CTA 배경 (진한 회색-검정) | 로그인 버튼, 주요 액션 |
| `--primary-foreground` | `oklch(0.985 0 0)` | CTA 위 텍스트 (밝은 흰색) | 로그인 버튼 레이블 |
| `--secondary` | `oklch(0.97 0 0)` | 보조 액션 배경 (밝은 회색) | 취소 버튼, 보조 버튼 |
| `--secondary-foreground` | `oklch(0.205 0 0)` | 보조 액션 텍스트 | 취소 버튼 레이블 |
| `--muted` | `oklch(0.97 0 0)` | 비활성 영역 배경 | 비활성 탭, 비활성 입력 배경 |
| `--muted-foreground` | `oklch(0.556 0 0)` | 보조 텍스트 (중간 회색) | placeholder, 힌트 텍스트, 도움말 |
| `--accent` | `oklch(0.97 0 0)` | 강조 영역 배경 | 호버 상태 배경, 선택된 항목 배경 |
| `--accent-foreground` | `oklch(0.205 0 0)` | 강조 영역 텍스트 | 호버 상태 텍스트 |
| `--destructive` | `oklch(0.577 0.245 27.325)` | 위험 액션 (붉은 계열) | 에러 메시지, 삭제 버튼 |
| `--border` | `oklch(0.922 0 0)` | 경계선 (밝은 회색) | 카드 테두리, 구분선 |
| `--input` | `oklch(0.922 0 0)` | 입력 필드 테두리 | `<Input>` 기본 테두리 |
| `--ring` | `oklch(0.708 0 0)` | 포커스 링 색상 | 키보드 포커스 시 외곽선 |

### 사용 가이드

- **primary** — 페이지당 한 개의 핵심 CTA에만 쓴다. 로그인 폼에서는 "로그인" 버튼.
- **destructive** — 되돌릴 수 없는 위험 액션(계정 삭제 등) 또는 에러 메시지에 쓴다. 경고만 할 때는 `muted-foreground`를 우선 검토한다.
- **muted / muted-foreground** — 부가 정보, placeholder, 힌트에 쓴다. 본문 가독성이 요구되는 곳에는 쓰지 않는다.
- **임의 색상 추가 금지** — 위 토큰 외 색이 필요할 경우 이 문서에 신규 토큰을 먼저 등록한 뒤 사용한다.

### Syntax Highlight 토큰 (FR-SR-02 AQL 입력창 — PR #190~)

AQL 쿼리 입력창의 syntax highlight용 색상 5종. `--chart-1~5`는 chroma=0 회색조라 색 구분 불가하므로 별도 토큰으로 신규 등록한다. **기능적 색 구분**이 목적이므로 BTS 팔레트 예외적으로 유채색 도입이 정당화된다. 채도(C)는 0.13~0.17 범위로 채해 차분한 BTS 톤을 유지한다.

`index.css` 등록 위치. `:root` 블록 및 `.dark` 블록 — `@theme inline`에 `--color-syntax-*`로 연결.

#### 라이트 모드 Syntax 토큰

| CSS 변수 | OKLCH 값 | 색상 | 역할 | Tailwind 유틸 |
|---|---|---|---|---|
| `--syntax-keyword` | `oklch(0.38 0.15 250)` | 파란색 | AND / OR / NOT / IN / ORDER BY / ASC / DESC | `text-syntax-keyword` |
| `--syntax-field` | `oklch(0.36 0.14 290)` | 보라색 | status / label / summary / priority | `text-syntax-field` |
| `--syntax-operator` | `oklch(0.44 0.13 55)` | 황갈색 | = / != / ~ | `text-syntax-operator` |
| `--syntax-string` | `oklch(0.40 0.14 145)` | 녹색 | `"따옴표 문자열"` | `text-syntax-string` |
| `--syntax-number` | `oklch(0.44 0.17 25)` | 적갈색 | 정수 리터럴 | `text-syntax-number` |

#### 다크 모드 Syntax 토큰

| CSS 변수 | OKLCH 값 | 색상 |
|---|---|---|
| `--syntax-keyword` (`.dark`) | `oklch(0.72 0.15 250)` | 하늘색 |
| `--syntax-field` (`.dark`) | `oklch(0.75 0.13 290)` | 연보라 |
| `--syntax-operator` (`.dark`) | `oklch(0.76 0.13 65)` | 연황색 |
| `--syntax-string` (`.dark`) | `oklch(0.73 0.14 145)` | 연녹색 |
| `--syntax-number` (`.dark`) | `oklch(0.75 0.16 25)` | 연적색 |

#### Syntax 토큰 사용 가이드

- AQL `AqlHighlighter` 컴포넌트에서만 사용한다. 일반 본문 텍스트에 사용 금지.
- 토큰 타입 → Tailwind 클래스 매핑. `KEYWORD` → `text-syntax-keyword`, `FIELD` → `text-syntax-field`, `OPERATOR` → `text-syntax-operator`, `STRING` → `text-syntax-string`, `NUMBER` → `text-syntax-number`.
- `PAREN` / `COMMA` / `PLAIN` 토큰은 `text-foreground` (기본 텍스트 색) 그대로.
- 위치 오류(SEARCH_SYNTAX_ERROR) underline은 `text-destructive` 토큰 재사용.

### 다크 모드 토큰 (참고용 — 본 PR 미활성)

`.dark` 클래스 변수는 `index.css`에 정의되어 있으나 토글 UI가 없어 현재 적용되지 않는다. 후속 PR에서 활성화 예정이며 값은 변경하지 않는다.

---

## 3. 타이포그래피

### 폰트 스택

| 역할 | 폰트 | 적용 방법 |
|---|---|---|
| 기본 (영문/숫자) | `Geist Variable` (`@fontsource-variable/geist`) | `index.css` `@import` + `--font-sans: 'Geist Variable', sans-serif` |
| 한국어 fallback | `Apple SD Gothic Neo` (macOS/iOS), `Malgun Gothic` (Windows), `Pretendard` (웹 권장 — 별도 설치 시), `system-ui` | `sans-serif` 제네릭이 OS 시스템 폰트로 연결됨 |
| 제목 (heading) | `--font-heading` → `--font-sans`와 동일 | 현재 분리 없음. 추후 별도 폰트 도입 시 이 변수 재정의 |
| 고정폭 (mono) | `--font-mono` 토큰 (아래 상세) | `index.css` `@theme inline` + `font-mono` Tailwind 유틸 |

> 한국어 fallback 선택 이유. Geist는 라틴 문자 전용이라 한국어 글리프가 없다. OS 기본 시스템 폰트(macOS: Apple SD Gothic Neo, Windows: Malgun Gothic)가 가장 빠르게 로드된다. Pretendard는 가독성이 뛰어나지만 별도 웹폰트 설치가 필요하므로 후속 PR에서 추가 여부를 결정한다.

### `--font-mono` 토큰 (FR-SR-02 AQL overlay 정렬 — PR #190~)

AQL syntax highlight에서 `textarea`와 overlay `<pre>`의 **폰트가 다르면 글자 폭이 어긋나 하이라이트가 밀린다.** 두 요소가 동일한 `--font-mono`를 참조해 정렬 문제를 원천 차단한다.

```
--font-mono: 'D2Coding', 'Sarasa Mono K', 'Noto Sans Mono CJK KR',
             ui-monospace, 'Cascadia Code', 'Fira Code', 'Consolas',
             'Courier New', monospace;
```

**폰트 스택 선택 근거.**

| 순위 | 폰트 | 이유 |
|---|---|---|
| 1 | `D2Coding` | 한국 개발자 표준 한글 mono 폰트. ASCII + 완성형 한글 글리프 포함. 설치 시 최우선. |
| 2 | `Sarasa Mono K` | CJK(한/중/일) 지원 고품질 mono. 미설치 시 fallback. |
| 3 | `Noto Sans Mono CJK KR` | Google Fonts 계열 — CDN 가능. |
| 4 | `ui-monospace` | macOS San Francisco Mono (Retina 최적화). |
| 5 | `Cascadia Code` / `Fira Code` | Windows Terminal 기본 / 개발자 친화. |
| 6 | `Consolas` / `Courier New` | 최후 fallback (모든 OS 포함). |
| 7 | `monospace` | 브라우저 제네릭 최종 fallback. |

> 한글 전용 mono 주의. Geist Mono는 라틴 전용이라 한글 글리프 없음. Geist Variable도 마찬가지. `--font-mono`를 별도 토큰으로 분리해 `--font-sans` 스택과 독립 관리한다.

**사용 위치.**

- AQL `AqlHighlighter` — `textarea`와 overlay `<pre>` 모두 `font-mono` 클래스 적용 필수.
- 기타 코드 블록 컴포넌트(향후) — `--font-mono` 동일 토큰 재사용.

### 타입 스케일

| Tailwind 클래스 | 크기 | 한국어 권장 줄높이 | 주요 용도 |
|---|---|---|---|
| `text-xs` | 12px | `leading-4` (1.0rem) | 태그, 뱃지, 극소형 주석 |
| `text-sm` | 14px | `leading-5` (1.25rem) | label, 입력 필드 텍스트, 에러 메시지, 보조 설명 |
| `text-base` | 16px | `leading-6` (1.5rem) | 본문 기본값 |
| `text-lg` | 18px | `leading-7` (1.75rem) | 카드 제목, 섹션 소제목 |
| `text-xl` | 20px | `leading-7` (1.75rem) | 페이지 제목 (소) |
| `text-2xl` | 24px | `leading-8` (2rem) | 페이지 제목 (중) |
| `text-3xl` | 30px | `leading-9` (2.25rem) | 페이지 제목 (대) |

> 한국어 권장 줄높이. 한글은 영문보다 글자 높이가 크다. 기본 줄높이(`leading-normal`, 1.5)를 유지하되, 작은 크기(`text-xs`, `text-sm`)에서는 `leading-4` / `leading-5`로 명시해 행간이 너무 넓어지지 않게 한다.

### 폰트 굵기

| Tailwind 클래스 | 용도 |
|---|---|
| `font-normal` (400) | 본문, 입력 필드 값 |
| `font-medium` (500) | label, 버튼, 강조 텍스트 |
| `font-semibold` (600) | 카드 제목, 섹션 헤딩 |
| `font-bold` (700) | 페이지 주 제목 (드물게) |

### 사용 가이드

- 본문 = `text-sm` 또는 `text-base` + `font-normal`
- label = `text-sm font-medium text-foreground`
- 입력 placeholder = `text-sm text-muted-foreground`
- 에러 메시지 = `text-sm text-destructive`
- 카드 제목 = `text-lg font-semibold`
- 페이지 제목 = `text-2xl font-semibold` 또는 `text-3xl font-bold`
- **본문 최소 크기 = 14px (`text-sm`)**. 이보다 작은 크기는 한국어 가독성이 현저히 저하되므로 본문에 사용 금지.

---

## 4. 간격 (Spacing)

### Tailwind 기본 스케일

Tailwind v4 기본값. `1` unit = `0.25rem` = `4px`.

| 토큰 | rem | px | 용도 |
|---|---|---|---|
| `0` | 0 | 0 | 초기화 |
| `1` | 0.25rem | 4px | 아이콘 내부 미세 간격 |
| `2` | 0.5rem | 8px | 인라인 요소 간 간격 |
| `3` | 0.75rem | 12px | 버튼 상하 패딩, label~input 간격 |
| `4` | 1rem | 16px | 컴포넌트 내부 패딩, 섹션 내 요소 간격 |
| `6` | 1.5rem | 24px | 카드 패딩, 섹션 간 간격 |
| `8` | 2rem | 32px | 페이지 섹션 간 간격 |
| `12` | 3rem | 48px | 페이지 주요 블록 간 간격 |

### 폼 간격 가이드 (로그인 폼 기준)

- 카드 패딩: `p-6` (24px)
- label → input 간격: `space-y-2` (8px)
- 필드 그룹 간 간격: `space-y-4` (16px)
- 버튼 상단 여백: `mt-6` (24px)

### 터치 타깃

- 모바일 터치 타깃 최소 44px × 44px. 버튼 기본 높이 `h-9` (36px)이므로 모바일에서는 `h-11` (44px) 또는 패딩 보강으로 맞춘다.
- shadcn `<Button>` 기본 높이는 `h-9`. 로그인 버튼처럼 핵심 CTA는 `h-10` (40px) 이상 권장.

---

## 5. 라운드 (Border Radius)

### shadcn `--radius` 변수

`--radius: 0.625rem` (10px) 를 기준으로 비례 파생.

| Tailwind 클래스 | 실제 값 | 계산식 | 용도 |
|---|---|---|---|
| `rounded-sm` | 6px | `var(--radius) * 0.6` | 소형 배지, 태그 |
| `rounded-md` | 8px | `var(--radius) * 0.8` | 입력 필드, 소형 버튼 |
| `rounded-lg` | 10px | `var(--radius)` | 버튼(기본), 카드, 드롭다운 |
| `rounded-xl` | 14px | `var(--radius) * 1.4` | 모달, 대형 카드 |
| `rounded-2xl` | 18px | `var(--radius) * 1.8` | 토스트, 알림 패널 |

### 일관성 규칙

- 폼 컴포넌트(`<Input>`, `<Select>`, `<Button>`) 모두 `rounded-md` 기본.
- `<Card>` 컴포넌트는 `rounded-lg` 기본 (shadcn 기본값).
- 같은 폼 안에서 라운드 값이 섞이지 않도록 한다.

---

## 6. 그림자 (Shadow)

| Tailwind 클래스 | 용도 |
|---|---|
| `shadow-sm` | 로그인 카드, 폼 컨테이너 (미세한 입체감) |
| `shadow-md` | 드롭다운 메뉴, 팝오버 |
| `shadow-lg` | 모달, 대형 오버레이 |
| `shadow-none` | 플랫 섹션, 테두리만 있는 카드 |

**로그인 폼 권장.** `<Card className="shadow-sm">` — 페이지 배경과 폼을 살짝 분리하되 과도한 입체감 없음.

---

## 7. 다크 모드 정책

### 본 PR (#11) — 라이트 모드 전용

- `index.css`의 `.dark` 블록 변수는 shadcn init이 자동 생성했으나 토글 메커니즘이 없다.
- `prefers-color-scheme: dark` 미감지. 모든 사용자에게 라이트 모드가 표시된다.
- 다크 모드 관련 코드(토글 버튼, `next-themes`, `ThemeProvider` 등)는 본 PR scope 밖이다.

### 후속 PR 계획

- `prefers-color-scheme` 자동 감지 + 명시 토글 버튼 추가.
- `class="dark"` 전략 (shadcn 기본 방식) 또는 `data-theme` 속성 — 후속 PR에서 결정.
- 이미 `.dark` 토큰이 정의되어 있으므로 CSS 재작성 없이 활성화 가능.

---

## 8. 접근성 (WCAG AA)

### 색 대비

| 토큰 조합 | 대략적 대비비 | 판정 | 용도 |
|---|---|---|---|
| `foreground` on `background` | ~19:1 | AA ✅ | 본문 |
| `primary-foreground` on `primary` | ~17:1 | AA ✅ | 로그인 버튼 |
| `muted-foreground` on `background` | ~4.6:1 | AA ✅ (최소) | placeholder, 힌트 |
| `destructive` on `background` | ~4.5:1 | AA ✅ (최소) | 에러 메시지 |

> 실제 대비비는 브라우저 DevTools 또는 [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/)로 검증한다. OKLCH 값은 색공간 특성상 sRGB 환산 후 계산해야 정확하다.

### Syntax Highlight 토큰 대비 검증 (FR-SR-02 — WCAG AA 4.5:1 기준)

배경 기준. 라이트 = `--background` `oklch(1 0 0)` (Y_rel = 1.000), 다크 = `--background` `oklch(0.145 0 0)` (Y_rel ≈ 0.018).

**계산 방법.** WCAG 상대 밝기 공식 `(L1 + 0.05) / (L2 + 0.05)`. OKLCH L → CIE Y_rel 변환 식. `Y = ((L×100 + 16) / 116)^3`. 채도(C)가 있는 색은 색조에 따라 Y가 ±10~15% 달라지므로 보수적 안전마진을 포함해 계산한다.

#### 라이트 모드 (`oklch(1 0 0)` 배경, Y=1.000)

| 토큰 | OKLCH | Y_rel(근사) | 대비비 | 판정 |
|---|---|---|---|---|
| `--syntax-keyword` | `oklch(0.38 0.15 250)` | 0.095 | `1.05 / 0.145 ≈ 7.2:1` | AA ✅ |
| `--syntax-field` | `oklch(0.36 0.14 290)` | 0.082 | `1.05 / 0.132 ≈ 8.0:1` | AA ✅ |
| `--syntax-operator` | `oklch(0.44 0.13 55)` | 0.138 | `1.05 / 0.188 ≈ 5.6:1` | AA ✅ |
| `--syntax-string` | `oklch(0.40 0.14 145)` | 0.113 | `1.05 / 0.163 ≈ 6.4:1` | AA ✅ |
| `--syntax-number` | `oklch(0.44 0.17 25)` | 0.135 | `1.05 / 0.185 ≈ 5.7:1` | AA ✅ |

> `--syntax-operator` (황갈 55°)는 주황/노랑 계열이라 동일 L에서 Y가 높아지는 경향(노랑 계열 CIE Y 과대). L=0.44로 보수적으로 낮춰 AA 마진 확보. L=0.46 이상은 경계선에 근접하므로 이 값을 고정한다.

#### 다크 모드 (`oklch(0.145 0 0)` 배경, Y≈0.018)

| 토큰 | OKLCH | Y_rel(근사) | 대비비 | 판정 |
|---|---|---|---|---|
| `--syntax-keyword` | `oklch(0.72 0.15 250)` | 0.437 | `0.487 / 0.068 ≈ 7.1:1` | AA ✅ |
| `--syntax-field` | `oklch(0.75 0.13 290)` | 0.483 | `0.533 / 0.068 ≈ 7.8:1` | AA ✅ |
| `--syntax-operator` | `oklch(0.76 0.13 65)` | 0.499 | `0.549 / 0.068 ≈ 8.1:1` | AA ✅ |
| `--syntax-string` | `oklch(0.73 0.14 145)` | 0.450 | `0.500 / 0.068 ≈ 7.4:1` | AA ✅ |
| `--syntax-number` | `oklch(0.75 0.16 25)` | 0.483 | `0.533 / 0.068 ≈ 7.8:1` | AA ✅ |

> 최소 대비비. 라이트 5.6:1 (`--syntax-operator`), 다크 7.1:1 (`--syntax-keyword`). 전 토큰 WCAG AA 4.5:1 충족 확인.

### 키보드 탐색

- 탭 순서: `username input` → `password input` → `provider select` → `로그인 button`
- `Shift+Tab`으로 역방향 탐색 가능.
- `Enter` 키로 폼 제출 (button type="submit").
- `Esc` 키로 드롭다운/모달 닫기 (shadcn 기본 동작).
- 포커스 링: `ring-2 ring-offset-2 ring-ring` (shadcn 기본). 절대 `outline-none`만으로 포커스를 제거하지 않는다.

### 입력 필드 접근성 필수 속성

모든 `<Input>` 컴포넌트에 아래 속성이 있어야 한다.

```
<label htmlFor="username">사용자명</label>
<input
  id="username"
  aria-invalid={!!errors.username}
  aria-describedby="username-error"
/>
{errors.username && (
  <p id="username-error" role="alert" className="text-sm text-destructive">
    {errors.username.message}
  </p>
)}
```

- `aria-invalid="true"` — 검증 실패 시 스크린리더에 에러 상태 알림.
- `aria-describedby` — 에러 메시지 요소 ID를 연결해 스크린리더가 메시지를 읽는다.
- `role="alert"` — 에러 메시지가 동적으로 나타날 때 스크린리더에 즉시 알림.

### ARIA 레이블

- 아이콘 전용 버튼(텍스트 없음): `aria-label="로그아웃"` 필수.
- 로그인 폼 전체: `<form aria-label="로그인 폼">` 또는 `<form aria-labelledby="login-heading">`.
- `<Card>`: 의미 없는 장식 div가 아닌 경우 `role`을 생략하고 내부 heading으로 위계 표현.

### 스크린리더 친화 label 텍스트

- "입력" 같은 추상적 label 금지. "사용자명", "비밀번호", "인증 제공자" 처럼 명확하게.
- 버튼: "로그인", "로그아웃", "취소" — 동사 + 명확한 목적어.
- placeholder는 label 대체 불가. label과 placeholder 모두 제공한다.

---

## 9. shadcn/ui 활용 가이드

### 본 PR에 설치된 컴포넌트 5종

| 컴포넌트 | 파일 위치 | 주요 역할 |
|---|---|---|
| `<Button>` | `src/components/ui/button.tsx` | CTA, 폼 제출, 보조 액션 |
| `<Input>` | `src/components/ui/input.tsx` | 텍스트 입력 (username, password) |
| `<Label>` | `src/components/ui/label.tsx` | 입력 필드 레이블 |
| `<Form>` | `src/components/ui/form.tsx` | React Hook Form 연동 래퍼 |
| `<Card>` | `src/components/ui/card.tsx` | 로그인 폼 컨테이너 |

### LoginForm 컴포넌트 계층 구조

```
<div class="min-h-screen flex items-center justify-center bg-background">
  <Card class="w-full max-w-sm shadow-sm">
    <CardHeader>
      <CardTitle>로그인</CardTitle>          ← text-2xl font-semibold
      <CardDescription>                    ← text-sm text-muted-foreground
        BTS 계정으로 로그인하세요.
      </CardDescription>
    </CardHeader>
    <CardContent>
      <Form>                               ← RHF FormProvider
        <FormField name="username">
          <FormItem>
            <FormLabel>사용자명</FormLabel>  ← text-sm font-medium
            <FormControl>
              <Input type="text" />        ← rounded-md border-input
            </FormControl>
            <FormMessage />               ← text-sm text-destructive, role="alert"
          </FormItem>
        </FormField>

        <FormField name="password">
          <FormItem>
            <FormLabel>비밀번호</FormLabel>
            <FormControl>
              <Input type="password" />
            </FormControl>
            <FormMessage />
          </FormItem>
        </FormField>

        <FormField name="provider">
          <FormItem>
            <FormLabel>인증 제공자</FormLabel>
            <Select defaultValue="local">
              <SelectTrigger />
              <SelectContent>
                <SelectItem value="local">사내 계정 (Local)</SelectItem>
                <SelectItem value="ldap-corp">LDAP (사내 디렉토리)</SelectItem>
              </SelectContent>
            </Select>
          </FormItem>
        </FormField>

        <Button type="submit" class="w-full mt-6">
          로그인
        </Button>
      </Form>
    </CardContent>
  </Card>
</div>
```

### 버튼 variant 사용 기준

| variant | Tailwind 결과 | 용도 |
|---|---|---|
| `default` | `bg-primary text-primary-foreground` | 핵심 CTA (로그인 버튼) |
| `secondary` | `bg-secondary text-secondary-foreground` | 보조 액션 (취소) |
| `destructive` | `bg-destructive text-white` | 위험 액션 (삭제) |
| `outline` | `border border-input bg-background` | 3순위 액션 |
| `ghost` | 배경 없음, 호버 시 `bg-accent` | 아이콘 버튼, 헤더 메뉴 |
| `link` | 밑줄 텍스트 | 인라인 텍스트 링크 |

### 컴포넌트 커스터마이즈 정책

shadcn/ui는 소스를 직접 프로젝트에 복사하는 "vendoring" 방식이다. `src/components/ui/` 파일을 직접 수정할 수 있다. 단.

- 수정 범위를 최소화한다. 스타일 조정은 Tailwind 유틸리티 prop으로 먼저 시도한다.
- 수정한 경우 해당 파일 상단에 변경 이유를 주석으로 기록한다.
- shadcn 업스트림 업데이트 시 diff 충돌이 날 수 있음을 인지하고 수정한다.

---

## 10. 한국어 / 영어 카피 가이드

### 기본 원칙

- 모든 사용자 노출 텍스트는 **한국어** 우선이다.
- 텍스트 상수는 `src/i18n/ko.ts`에 모은다 (Task 14에서 생성 예정).
- 하드코딩 문자열을 컴포넌트 JSX 안에 직접 쓰지 않는다.

### 에러 메시지 톤

- 사용자 책임을 묻지 않는다.
- 올바른 예. "사용자명 또는 비밀번호가 올바르지 않습니다."
- 잘못된 예. "잘못 입력하셨습니다." / "비밀번호를 다시 확인해주세요."
- 시스템 오류 시. "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."

### 버튼 카피

- 동사 + 명확한 목적어로 작성한다.
- 올바른 예. "로그인", "로그아웃", "취소", "확인", "저장"
- 잘못된 예. "OK", "Submit", "Yes", "처리" (모호한 동사)

### 주요 카피 목록 (로그인 폼 기준)

| i18n 키 (예정) | 한국어 텍스트 | 사용 위치 |
|---|---|---|
| `auth.login.title` | 로그인 | 카드 제목 |
| `auth.login.description` | BTS 계정으로 로그인하세요. | 카드 설명 |
| `auth.login.username` | 사용자명 | 입력 label |
| `auth.login.password` | 비밀번호 | 입력 label |
| `auth.login.provider` | 인증 제공자 | select label |
| `auth.login.provider.local` | 사내 계정 (Local) | select 옵션 |
| `auth.login.provider.ldap` | LDAP (사내 디렉토리) | select 옵션 |
| `auth.login.submit` | 로그인 | 제출 버튼 |
| `auth.login.submit.loading` | 로그인 중... | 로딩 상태 버튼 |
| `auth.error.invalid_credentials` | 사용자명 또는 비밀번호가 올바르지 않습니다. | 401 에러 |
| `auth.error.mfa_required` | 추가 인증이 필요합니다. 관리자에게 문의하세요. | MFA 에러 |
| `auth.error.server_error` | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. | 5xx 에러 |
| `auth.logout` | 로그아웃 | 헤더 메뉴 |

---

## 11. 반응형 전략

### 브레이크포인트

| 이름 | 범위 | Tailwind 접두사 |
|---|---|---|
| sm | ~640px (모바일) | `sm:` |
| md | ~768px (태블릿) | `md:` |
| lg | ~1024px (노트북) | `lg:` |
| xl | ~1280px (데스크탑) | `xl:` |

### 로그인 폼 반응형 동작

BTS는 사내 협업 도구로 **데스크탑 우선**이다. 모바일은 2순위이며 본 PR에서 기본 대응만 한다.

| 브레이크포인트 | 로그인 카드 너비 | 레이아웃 |
|---|---|---|
| sm (모바일) | `w-full` (좌우 여백 `px-4`) | 전체 너비 |
| md (태블릿) | `max-w-sm` (384px) | 중앙 정렬 |
| lg~xl (데스크탑) | `max-w-sm` (384px) | 중앙 정렬 |

실제 클래스 예시. `<Card className="w-full sm:max-w-sm mx-auto">`.

### 터치 타깃 (모바일)

- 버튼 최소 높이 `h-11` (44px) — 모바일에서 `<Button className="h-11">` 또는 `sm:h-9` 분기.
- 입력 필드 최소 높이 44px — shadcn `<Input>` 기본 `h-9` (36px)이므로 모바일에서 `h-11` 보강.

---

## 12. 후속 확장 항목 (Out of Scope — 본 PR)

아래 항목은 본 PR에서 의도적으로 다루지 않는다. 후속 PR에서 이 문서를 업데이트하며 확장한다.

- **다크 모드 토글** — `ThemeProvider` 도입 + `prefers-color-scheme` 자동 감지.
- **i18n (한/영 전환)** — `react-i18next` 또는 동등한 라이브러리. 현재는 한국어 상수 파일(`ko.ts`)만.
- **모션/애니메이션 가이드** — 현재는 shadcn 기본 `tw-animate-css`. 커스텀 전환 효과는 별도 토큰화.
- **차트 / 데이터 시각화** — `chart-1` ~ `chart-5` 토큰이 정의되어 있으나 컴포넌트 없음.
- **일러스트레이션** — 현재 아이콘은 `lucide-react`만. 커스텀 SVG/일러스트 가이드라인 별도.
- **아이콘 시스템 확장** — Lucide 외 커스텀 아이콘 추가 시 별도 섹션 등록.
- **모바일 전용 레이아웃** — 현재 데스크탑 우선 + 최소 반응형. 전용 모바일 UX는 별도 스펙.
- **Storybook 컴포넌트 카탈로그** — 후속 PR.
- **폰트 확장** — Pretendard(한글) 웹폰트 추가 여부 후속 PR에서 결정.

---

본 문서는 PR #11 (FR-AU-09 로그인 폼 UI)와 함께 도입되는 BTS 디자인 시스템 v0.1. 후속 PR에서 컴포넌트가 확장될 때 본 문서를 업데이트.
