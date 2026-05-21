// Vitest 셋업 smoke test — 렌더 환경이 정상 동작하는지 검증
import { render } from '@testing-library/react'
import { App } from './App'

it('renders without crashing', () => {
  render(<App />)
  expect(document.body).toBeTruthy()
})
