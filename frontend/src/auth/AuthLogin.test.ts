import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { err, ok } from '@/shared/result'
import type { AuthResult } from '@/auth/store'

const { login, push } = vi.hoisted(() => ({
  login: vi.fn<(email: string, password: string) => Promise<AuthResult>>(),
  push: vi.fn<(to: string) => Promise<void>>(),
}))

vi.mock('@/auth/store', () => ({ useAuthStore: () => ({ login }) }))
vi.mock('@/router', () => ({ default: { push } }))

import AuthLogin from './AuthLogin.vue'

async function submit(email: string, password: string) {
  const wrapper = mount(AuthLogin, { global: { stubs: { RouterLink: true } } })
  await wrapper.get('input[name="email"]').setValue(email)
  await wrapper.get('input[name="password"]').setValue(password)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  return wrapper
}

describe('AuthLogin', () => {
  beforeEach(() => {
    login.mockReset()
    push.mockReset()
    push.mockResolvedValue(undefined)
  })

  it('validates locally before calling the store', async () => {
    const wrapper = await submit('not-an-email', '')

    expect(wrapper.get('[data-testid="email-error"]').text()).toBe('Email is invalid')
    expect(wrapper.get('[data-testid="password-error"]').text()).toBe('Password is required')
    expect(login).not.toHaveBeenCalled()
  })

  it('logs in and navigates home on success', async () => {
    login.mockResolvedValue(ok({ accessToken: 'a', refreshToken: 'r' }))

    await submit('a@b.com', 'Test123!@')

    expect(login).toHaveBeenCalledWith('a@b.com', 'Test123!@')
    expect(push).toHaveBeenCalledWith('/')
  })

  it('shows the server message when the request fails without field errors', async () => {
    login.mockResolvedValue(err({ status: 401, message: 'Invalid credentials', fields: {} }))

    const wrapper = await submit('a@b.com', 'wrong')

    expect(wrapper.get('[data-testid="form-error"]').text()).toBe('Invalid credentials')
    expect(push).not.toHaveBeenCalled()
  })

  it('shows server field errors under the fields instead of a general message', async () => {
    login.mockResolvedValue(
      err({
        status: 400,
        message: 'Invalid request content.',
        fields: { email: 'Email is invalid' },
      }),
    )

    const wrapper = await submit('a@b.com', 'Test123!@')

    expect(wrapper.get('[data-testid="email-error"]').text()).toBe('Email is invalid')
    expect(wrapper.find('[data-testid="form-error"]').exists()).toBe(false)
  })
})
