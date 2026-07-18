<!-- FR-UX-06 PR3 팔레트 교체용 — ADS v2 컬러 토큰 정본 hex 확보 결과 -->
# ADS v2 컬러 토큰 정본 hex — FR-UX-06 PR3 팔레트 교체

> 모든 값의 정본 소스는 npm 패키지 **`@atlaskit/tokens@1.4.2`** 이다.
> 이 버전을 고른 이유: BTS가 이미 독립 확인한 5개 앵커 포인트(Blue100 `#E9F2FF`, Blue1000 `#082145`, 본문텍스트 `#172B4D`, Blue700 `#0C66E4`, 그리고 "쓰지 않음"인 v1 B400 `#0052CC`가 부재)와 **완전히 일치**하는 유일한 세대이기 때문. (v5.0.0은 Blue700=#0C66E4는 맞지만 Blue1000이 #1C2B41로 어긋남. v8.0.0 이상과 v13/v16의 `palette.js`는 브랜드 리프레시라 Blue700=`#1868DB`. v16 `legacy-palette.js`는 아예 v1 세대(B400=#0052CC, 0~500 스케일)라 "쓰지 않음" 대상.)

## 정본 소스 URL (5개 파일)
- 베이스 팔레트: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/palettes/palette.js`
- 라이트 토큰맵: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/artifacts/tokens-raw/atlassian-light.js`
- 다크 토큰맵: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/artifacts/tokens-raw/atlassian-dark.js`
- 라이트 elevation: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/tokens/atlassian-light/elevation/surface.js`
- 다크 elevation: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/tokens/atlassian-dark/elevation/surface.js`

(`cdn.jsdelivr.net/npm/@atlaskit/tokens@1.4.2/...` 도 동일 파일, 동일 값. jsdelivr 쪽은 value를 hex로 이미 해석해 줌.)

---

## A. shadcn 토큰 → ADS 매핑 (라이트+다크)

| shadcn 토큰 | 라이트 hex | 다크 hex | ADS 토큰(→ 팔레트 스텝) | 출처 URL |
|---|---|---|---|---|
| `--background` | `#FFFFFF` | `#161A1D` | elevation.surface (→ Neutral0 / DarkNeutral0) | atlassian-{light,dark}/elevation/surface.js |
| `--foreground` | `#172B4D` | `#C7D1DB` | color.text (→ Neutral1000 / DarkNeutral1000) | tokens-raw/atlassian-{light,dark}.js |
| `--card` | `#FFFFFF` | `#1D2125` | elevation.surface.raised (→ Neutral0 / DarkNeutral100) | elevation/surface.js |
| `--card-foreground` | `#172B4D` | `#C7D1DB` | color.text | tokens-raw/atlassian-{light,dark}.js |
| `--popover` | `#FFFFFF` | `#22272B` | elevation.surface.overlay (→ Neutral0 / DarkNeutral200) | elevation/surface.js |
| `--popover-foreground` | `#172B4D` | `#C7D1DB` | color.text | tokens-raw/atlassian-{light,dark}.js |
| `--primary` | `#0C66E4` | `#579DFF` | color.background.brand.bold (→ Blue700 / Blue400) ⚠️ | tokens-raw/atlassian-{light,dark}.js |
| `--primary-foreground` | `#FFFFFF` | `#161A1D` | color.text.inverse (→ Neutral0 / DarkNeutral0) ⚠️ | tokens-raw/atlassian-{light,dark}.js |
| `--secondary` | `#F1F2F4` | `#22272B` | color.background.neutral 솔리드 등가 (→ Neutral200 / DarkNeutral200) ⚠️ | palette.js |
| `--secondary-foreground` | `#172B4D` | `#C7D1DB` | color.text | tokens-raw/atlassian-{light,dark}.js |
| `--muted` | `#F7F8F9` | `#1D2125` | (→ Neutral100 / DarkNeutral100) | palette.js |
| `--muted-foreground` | `#626F86` | `#8696A7` | color.text.subtlest (→ Neutral700 / DarkNeutral700) | tokens-raw/atlassian-{light,dark}.js |
| `--accent` | `#F1F2F4` | `#22272B` | (→ Neutral200 / DarkNeutral200) | palette.js |
| `--accent-foreground` | `#172B4D` | `#C7D1DB` | color.text | tokens-raw/atlassian-{light,dark}.js |
| `--destructive` | `#CA3521` | `#F87462` | color.background.danger.bold (→ Red700 / Red400) | tokens-raw/atlassian-{light,dark}.js |
| `--border` | `#DCDFE4` | `#2C333A` | color.border 솔리드 등가 (→ Neutral300 / DarkNeutral300) ⚠️ | palette.js |
| `--input` | `#DCDFE4` | `#2C333A` | color.border.input 솔리드 등가 (→ Neutral300 / DarkNeutral300) ⚠️ | palette.js |
| `--ring` | `#388BFF` | `#85B8FF` | color.border.focused (→ Blue500 / Blue300) ⚠️ | tokens-raw/atlassian-{light,dark}.js |

**⚠️ 주석 (A절)**
- `--primary` / `--primary-foreground`: ADS 다크 브랜드는 밝은 파랑 Blue400(`#579DFF`)에 **어두운 텍스트**(text.inverse=DarkNeutral0 `#161A1D`)를 얹는 방식(ADS 정본). shadcn 관례처럼 "다크에서도 Blue700 + 흰 글자"를 유지하고 싶으면 다크 `--primary`=`#0C66E4`, `--primary-foreground`=`#FFFFFF`로 두는 대안 있음. Maxi 결정 필요.
- `--secondary` / `--border` / `--input`: ADS 정본 토큰은 **알파(투명) 뉴트럴**이다 — background.neutral=Neutral200A(`#091E420F` 라이트)/DarkNeutral200A(`#A1BDD914` 다크), border=Neutral300A(`#091E4224`)/DarkNeutral300A(`#A6C5E229`). shadcn은 보통 솔리드 hex를 쓰므로 위 표에는 같은 스텝의 **솔리드 등가값**을 넣었다. 겹침 표면 위에 반투명 효과가 필요하면 알파값을 그대로 써도 됨.
- `--ring`: ADS `color.border.focused`는 **Blue500(`#388BFF`)** 이다. 그런데 §B의 `--border-focus`는 태스크가 Blue700로 지정 → 둘이 어긋남. 포커스 링 색을 하나로 통일할지 Maxi 확인 필요(아래 B절 주석 참조).
- `--muted`/`--accent`/`--secondary`는 shadcn↔ADS가 1:1이 아니라 뉴트럴 표면 스텝에 배정한 것. 기본 shadcn에서 이 셋은 값이 겹치는 경우가 많다.

---

## B. §7 상태 토큰 11종 (태스크가 팔레트 스텝을 지정)

라이트 hex는 태스크가 지정한 팔레트 스텝의 값. 다크는 ADS 관례 적용 — 뉴트럴은 **같은 번호 DarkNeutral**(ADS가 문서화한 반전 램프 규칙: Neutral100↔DarkNeutral100), 블루는 ADS 다크 토큰이 실제 매핑하는 스텝.

| 토큰 | 라이트 hex | 다크 hex | ADS 팔레트 스텝 (라이트 / 다크) | 출처 URL |
|---|---|---|---|---|
| `--bg-neutral` | `#F7F8F9` | `#1D2125` | Neutral100 / DarkNeutral100 | palette.js |
| `--bg-neutral-hover` | `#F1F2F4` | `#22272B` | Neutral200 / DarkNeutral200 | palette.js |
| `--bg-neutral-press` | `#DCDFE4` | `#2C333A` | Neutral300 / DarkNeutral300 | palette.js |
| `--bg-selected` | `#E9F2FF` | `#082145` | Blue100 / Blue1000 | palette.js + tokens-raw(dark: background.selected→Blue1000) |
| `--text-selected` | `#0C66E4` | `#579DFF` | Blue700 / Blue400 | tokens-raw(text.selected: Blue700→Blue400) |
| `--text-subtle` | `#626F86` | `#8696A7` | Neutral700 / DarkNeutral700 ⚠️ | palette.js |
| `--text-subtlest` | `#8590A2` | `#596773` | Neutral500 / DarkNeutral500 ⚠️ | palette.js |
| `--text-disabled` | `#B3B9C4` | `#454F59` | Neutral400 / DarkNeutral400 ⚠️ | palette.js |
| `--border-focus` | `#0C66E4` | `#0C66E4` (또는 ADS Blue300 `#85B8FF`) | Blue700 / Blue700(태스크) ⚠️ | palette.js |
| `--brand-hover` | `#0055CC` | `#85B8FF` | Blue800 / Blue300 | palette.js (태스크가 명시) |
| `--brand-text` | `#0C66E4` | `#579DFF` | Blue700 / Blue400 | tokens-raw(text.brand: Blue700→Blue400) |

**⚠️ 주석 (B절) — 태스크 지정 vs ADS 실제 매핑 차이**
- `--text-subtle`: 태스크=Neutral700. **ADS 실제** `color.text.subtle`는 Neutral800(`#44546F`) — 태스크가 한 스텝 밝음. 다크는 같은 번호 규칙으로 DarkNeutral700(`#8696A7`).
- `--text-subtlest`: 태스크=Neutral500. **ADS 실제** `color.text.subtlest`는 Neutral700(`#626F86`) — 태스크가 두 스텝 밝음. 다크 DarkNeutral500(`#596773`)은 어두운 표면에서 대비가 낮으니(작은 글자 가독성 주의) Maxi 확인 권장.
- `--text-disabled`: 태스크=Neutral400 솔리드(`#B3B9C4`). **ADS 실제** `color.text.disabled`는 알파 Neutral400A(`#091E424F`). 솔리드로 갈지 알파로 갈지 결정 필요.
- `--border-focus`: 태스크=Blue700. **ADS 실제** `color.border.focused`는 Blue500(라이트 `#388BFF`)/Blue300(다크 `#85B8FF`). 다크값은 태스크 미지정 — Blue700 유지(`#0C66E4`) 또는 ADS Blue300(`#85B8FF`) 중 택. §A `--ring`과 반드시 같은 색으로 통일할 것.
- `--brand-hover`, `--brand-text`, `--bg-selected`, `--text-selected`: ADS 다크 매핑과 일치(문제 없음).

---

## C. 시맨틱 토큰 4쌍 신설 (라이트+다크)

`--destructive`처럼 "솔리드 상태색"으로 쓴다는 전제로 ADS의 **`color.background.<status>.bold`** (고강조 배경)를 채택.

| 토큰 | 라이트 hex | 다크 hex | ADS 토큰(→ 팔레트 스텝) | 출처 URL |
|---|---|---|---|---|
| `--warning` | `#B65C02` | `#E2B203` | color.background.warning.bold (→ Orange700 / Yellow400) ⚠️ | tokens-raw/atlassian-{light,dark}.js |
| `--success` | `#1F845A` | `#4BCE97` | color.background.success.bold (→ Green700 / Green400) | tokens-raw/atlassian-{light,dark}.js |
| `--danger` | `#CA3521` | `#F87462` | color.background.danger.bold (→ Red700 / Red400) | tokens-raw/atlassian-{light,dark}.js |
| `--info` | `#0C66E4` | `#579DFF` | color.background.information.bold (→ Blue700 / Blue400) | tokens-raw/atlassian-{light,dark}.js |

**⚠️ 주석 (C절) — warning은 태스크와 ADS가 다름**
- 태스크는 `--warning(Yellow 계열)`이라 했지만, **ADS v2의 `warning` 시맨틱은 실제로 Orange 램프**를 쓴다: 라이트 bold=Orange700(`#B65C02`), 다크 bold=Yellow400(`#E2B203`), 라이트 subtle 배경=Orange100(`#FFF4E5`), 아이콘=Orange600(`#D97008`).
  - **Yellow로 가고 싶다면**(태스크 지정 존중): 라이트 `#CF9F02`(Yellow500) 또는 `#E2B203`(Yellow400), 다크 `#E2B203`(Yellow400). 전부 palette.js 출처.
- 텍스트 페어링 주의: 라이트 bold(어두운 오렌지/빨강/초록)에는 **흰 글자**, 다크 bold(밝은 노랑/파랑/빨강)에는 **어두운 글자**가 ADS 관례. warning은 특히 라이트=진오렌지(흰글자)/다크=밝은노랑(검은글자)로 페어링이 갈린다.
- subtle(저강조) 배경이 필요하면: 라이트 danger `#FFEDEB`(Red100)·warning `#FFF4E5`(Orange100)·success `#DFFCF0`(Green100)·info `#E9F2FF`(Blue100); 다크 danger `#391813`(Red1000)·warning `#3D2E00`(Yellow1000)·success `#133527`(Green1000)·info `#082145`(Blue1000).

---

## 부록: 확보한 v1.4.2 베이스 팔레트 (PR3 참조용 전체 램프)

출처: `https://unpkg.com/@atlaskit/tokens@1.4.2/dist/cjs/palettes/palette.js`

**Neutral (라이트 램프)**
`Neutral0 #FFFFFF` · `Neutral100 #F7F8F9` · `Neutral200 #F1F2F4` · `Neutral300 #DCDFE4` · `Neutral400 #B3B9C4` · `Neutral500 #8590A2` · `Neutral600 #758195` · `Neutral700 #626F86` · `Neutral800 #44546F` · `Neutral900 #2C3E5D` · `Neutral1000 #172B4D` · `Neutral1100 #091E42`
알파: `Neutral100A #091E4208` · `Neutral200A #091E420F` · `Neutral300A #091E4224` · `Neutral400A #091E424F` · `Neutral500A #091E427D`

**DarkNeutral (다크 램프 — 번호 오름차순으로 어두움→밝음, Neutral과 역방향)**
`DarkNeutral-100 #101214` · `DarkNeutral0 #161A1D` · `DarkNeutral100 #1D2125` · `DarkNeutral200 #22272B` · `DarkNeutral300 #2C333A` · `DarkNeutral400 #454F59` · `DarkNeutral500 #596773` · `DarkNeutral600 #738496` · `DarkNeutral700 #8696A7` · `DarkNeutral800 #9FADBC` · `DarkNeutral900 #B6C2CF` · `DarkNeutral1000 #C7D1DB` · `DarkNeutral1100 #DEE4EA`
알파: `DarkNeutral100A #BCD6F00A` · `DarkNeutral200A #A1BDD914` · `DarkNeutral300A #A6C5E229` · `DarkNeutral400A #BFDBF847`

**Blue** `100 #E9F2FF` · `200 #CCE0FF` · `300 #85B8FF` · `400 #579DFF` · `500 #388BFF` · `600 #1D7AFC` · `700 #0C66E4` · `800 #0055CC` · `900 #09326C` · `1000 #082145`

**Red** `100 #FFEDEB` · `200 #FFD2CC` · `300 #FF9C8F` · `400 #F87462` · `500 #EF5C48` · `600 #E34935` · `700 #CA3521` · `800 #AE2A19` · `900 #601E16` · `1000 #391813`

**Green** `100 #DFFCF0` · `200 #BAF3DB` · `300 #7EE2B8` · `400 #4BCE97` · `500 #2ABB7F` · `600 #22A06B` · `700 #1F845A` · `800 #216E4E` · `900 #164B35` · `1000 #133527`

**Yellow** `100 #FFF7D6` · `200 #F8E6A0` · `300 #F5CD47` · `400 #E2B203` · `500 #CF9F02` · `600 #B38600` · `700 #946F00` · `800 #7F5F01` · `900 #533F04` · `1000 #3D2E00`

**Orange** `100 #FFF4E5` · `600 #D97008` · `700 #B65C02` (warning 시맨틱이 쓰는 램프. 나머지 스텝은 필요 시 palette.js에서 추가 확보 가능)

**Purple/Teal/Magenta** (필요 시 참조) — Purple `700 #6E5DC6`, Teal `700 #1D7F8C`, Magenta `700 #AE4787` 등 전 램프 palette.js에 존재.

---

## 정본 소스 & 교차검증 요약

**정본 소스.** 대다수 값(팔레트 전 램프 + 토큰 매핑)은 **`@atlaskit/tokens@1.4.2`** 의 `dist/cjs/palettes/palette.js` 및 `dist/cjs/artifacts/tokens-raw/atlassian-{light,dark}.js` 에서 얻었다. elevation.surface만 `dist/cjs/tokens/atlassian-{light,dark}/elevation/surface.js` 사용. 이 버전은 npm 공개 패키지(Apache-2.0)라 재현·재검증 가능.

**교차검증 (앵커 5점) — 전부 통과.**
1. Blue100 `#E9F2FF` ✓ (v1.4.2 palette.js Blue100 일치)
2. Blue1000 `#082145` ✓ (v1.4.2 Blue1000 일치 — v5.0.0은 #1C2B41로 어긋나므로 v1.4.2가 정확)
3. 본문 텍스트 `#172B4D` ✓ (Neutral1000이자 `color.text`→Neutral1000)
4. Blue700 `#0C66E4` ✓ (브랜드 primary, `color.background.brand.bold`→Blue700)
5. v1 B400 `#0052CC` **부재** ✓ (v1.4.2에는 이 값 없음. 근접한 Blue800은 `#0055CC`로 구별됨 → v1 소스가 아님을 역으로 확인)

**미확보.** 없음. §A(18)·§B(11)·§C(4) = **33/33 토큰 라이트+다크 모두 확보.** 다만 값 자체가 아니라 *매핑 판단*이 필요한 지점 ⚠️ 표시(주로 §A primary 다크 페어링, 알파 vs 솔리드, §B text-subtle/subtlest/disabled·border-focus의 태스크 vs ADS 스텝 차이, §C warning의 Orange vs Yellow) — 값은 다 확보됐고 Maxi가 스텝만 택하면 됨.
