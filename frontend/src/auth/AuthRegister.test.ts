import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { ok } from '@/shared/result'
import type { AuthResult } from '@/auth/store'

const { register, push } = vi.hoisted(() => ({
  register: vi.fn<(email: string, password: string) => Promise<AuthResult>>(),
  push: vi.fn<(to: string) => Promise<void>>(),
}))

vi.mock('@/auth/store', () => ({ useAuthStore: () => ({ register }) }))
vi.mock('@/router', () => ({ default: { push } }))

import AuthRegister from './AuthRegister.vue'

async function submit(email: string, password: string) {
  const wrapper = mount(AuthRegister, { global: { stubs: { RouterLink: true } } })
  await wrapper.get('input[name="email"]').setValue(email)
  await wrapper.get('input[name="password"]').setValue(password)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  return wrapper
}

describe('AuthRegister', () => {
  beforeEach(() => {
    register.mockReset()
    push.mockReset()
    push.mockResolvedValue(undefined)
  })

  it('enforces the password length locally before calling the store', async () => {
    const wrapper = await submit('a@b.com', 'short1')

    expect(wrapper.get('[data-testid="password-error"]').text()).toBe(
      'Password must be between 8 and 72 characters',
    )
    expect(register).not.toHaveBeenCalled()
  })

  it('registers and navigates home on success', async () => {
    register.mockResolvedValue(ok({ accessToken: 'a', refreshToken: 'r' }))

    await submit('a@b.com', 'Test123!@')

    expect(register).toHaveBeenCalledWith('a@b.com', 'Test123!@')
    expect(push).toHaveBeenCalledWith('/')
  })
})
