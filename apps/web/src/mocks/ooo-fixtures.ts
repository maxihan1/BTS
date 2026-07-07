// 부재중(Out of Office) BC 테스트 fixture — ooo-handlers.ts store 초기 시드 (FR-PR-03)
import { aliceUser, bobUser } from './auth-fixtures'

/**
 * 부재중 store 레코드 — 백엔드 `user_ooo` 테이블 컬럼(FR-PR-03 스펙 §데이터 모델)과 1:1.
 * `delegateName`은 저장값이 아니라 조회 시 파생(LEFT JOIN users)이므로 store에는 담지 않는다
 * (ooo-handlers.ts의 `resolveDelegateName`이 파생한다).
 * row 없음(신규 사용자)은 startsAt/endsAt/delegateUserId/message 모두 null로 표현한다
 * (EC1 — 강제 생성 안 함, status-fixtures.StatusFixture 선례).
 */
export interface OooFixture {
  userId: string
  startsAt: string | null
  endsAt: string | null
  delegateUserId: string | null
  message: string | null
}

/** alice 부재중 fixture — 초기 시드는 미설정(all-null, EC1 신규 사용자 대표). */
export const ALICE_OOO_FIXTURE: OooFixture = {
  userId: aliceUser.userId,
  startsAt: null,
  endsAt: null,
  delegateUserId: null,
  message: null,
}

/** bob 부재중 fixture — 초기 시드는 미설정(all-null). */
export const BOB_OOO_FIXTURE: OooFixture = {
  userId: bobUser.userId,
  startsAt: null,
  endsAt: null,
  delegateUserId: null,
  message: null,
}

/** 부재중 store 초기 시드 배열 — ooo-handlers.ts가 이 값으로 store를 채운다. */
export const OOO_FIXTURES: readonly OooFixture[] = [
  ALICE_OOO_FIXTURE,
  BOB_OOO_FIXTURE,
]
