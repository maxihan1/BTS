// 백로그 에픽 선택 컨트롤의 테스트 계약 — 짝 테스트 2파일이 쓰는 셀렉터 자산의 단일 정본
import { screen } from '@testing-library/react'
import { backlogLabels } from '@/i18n/backlog-labels'

/**
 * 에픽 선택 컨트롤의 a11y role.
 *
 * `BacklogEpicPanel` 이 shadcn `Checkbox`(radix 기반, `role="checkbox"`)를 쓴다는
 * **현재 사실**을 고정한다. 제품 계약이 아니라 **선택한 프리미티브의 부산물**이다 —
 * `docs/design/jira-parity-contract.md` 에 에픽 컨트롤 role 명시는 0건이고,
 * 같은 성격의 `ColumnSelector` 는 Radix 메뉴라 `menuitemcheckbox` 로 **이미 갈려 있다**.
 *
 * ★ 이 값이 바뀌면 **두 소비처가 동시에** red 여야 한다.
 *   한쪽만 red 면 어딘가에 복사본이 남은 것이고, 둘 다 green 이면 셀렉터가 어느 단언에도
 *   물리지 않은 것이라 더 나쁘다.
 *
 * ⚠️ **그 보증은 조건부다 — 필터바 쪽 감지기는 단 한 줄이다.**
 *   실제로 red 가 되는 유일한 단언은 `BacklogFilterBar.test.tsx` 의
 *   `getByRole(EPIC_CONTROL_ROLE, { name: 미배정 })` 이고, 그 「미배정」 체크박스는
 *   `FilterBar` 소유라 **에픽 컨트롤이 아니다**(선재, PR #344). 나머지 부재 단언
 *   (`queryEpicControls()` → 0건)은 존재하지 않는 role 을 0개 세므로 값이 바뀌어도 green 이다.
 *   따라서 패널이 **정당하게** `menuitemcheckbox` 로 가는 날 그 한 줄은 **거짓 red** 가 되고,
 *   자연스러운 대응(그 줄만 `'checkbox'` 리터럴로 되돌리기)이 곧 **영구초록의 부활**이다.
 *   그때는 리터럴 복원이 아니라 「미배정」 role 을 별도 상수로 분리해라 —
 *   `docs/plan/product/personalization.md` §4.11 후속 ⑩.
 */
export const EPIC_CONTROL_ROLE = 'checkbox' as const

/** 짝 테스트 전용 에픽 키 픽스처 — MSW 시드 값이 아니다 */
export const EPIC_ALPHA = 'ATLAS-100'
export const EPIC_BETA = 'ATLAS-200'
/**
 * 이름 해석에 실패했거나 조회 상한(`EPIC_NAME_LOOKUP_LIMIT` = 50)을 넘은 에픽.
 * F16-6 은 이때 **키를 그대로** 보이라고 한다.
 *
 * (구 복사본 2벌은 이 설명이 이미 갈라져 있었다 — 한쪽은 「조회 상한을 넘은」,
 *  다른 쪽은 「아직 로딩 중인」. 값보다 설명이 먼저 드리프트한 실례다.)
 */
export const EPIC_UNRESOLVED = 'ATLAS-900'

/** 이름이 해석된 에픽의 표시 이름 — 접근명이 키가 아니라 이름임을 재는 자리다 */
export const EPIC_ALPHA_NAME = '결제 개편'
export const EPIC_BETA_NAME = '알림 리팩터'

/** 「에픽 없음」 표시명 — 정본(`i18n/backlog-labels.ts`)에서 읽는다. 리터럴 재타이핑 금지 */
export const NO_EPIC_LABEL = backlogLabels.filter.noEpic

/**
 * 에픽 선택 컨트롤 후보를 이름별로 전부 긁는다. 해석 이름·미해석 키·센티널 라벨 전부.
 *
 * `screen` 을 인자로 받지 않고 직접 import 한다 — `@testing-library/react` 의 `screen` 은
 * `document.body` 에 바인딩된 싱글턴이고 두 소비처가 동일하게 쓴다. 주입은 이득 없는 의식이다.
 */
export function queryEpicControls(): HTMLElement[] {
  const names = [
    EPIC_ALPHA_NAME,
    EPIC_BETA_NAME,
    EPIC_ALPHA,
    EPIC_BETA,
    EPIC_UNRESOLVED,
    NO_EPIC_LABEL,
  ]
  return names.flatMap((name) => screen.queryAllByRole(EPIC_CONTROL_ROLE, { name }))
}
