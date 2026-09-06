import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const state = vi.hoisted(() => ({ authenticated: false }))

vi.mock('@/auth/store', () => ({
  useAuthStore: () => ({
    get isAuthenticated() {
      return state.authenticated
    },
  }),
}))

import AppNavbar from './AppNavbar.vue'

function render(authenticated: boolean) {
  state.authenticated = authenticated
  return mount(AppNavbar, { global: { stubs: { RouterLink: true } } })
}

describe('AppNavbar', () => {
  it('shows sign in and register links when logged out', () => {
    const wrapper = render(false)

    const links = wrapper.findAll('router-link-stub').map((link) => link.attributes('to'))
    expect(links).toEqual(['/login', '/register'])
    expect(wrapper.find('[aria-label="Profile"]').exists()).toBe(false)
  })

  it('shows only the profile link when logged in', () => {
    const wrapper = render(true)

    expect(wrapper.find('[aria-label="Profile"]').exists()).toBe(true)
    expect(wrapper.findAll('router-link-stub')).toHaveLength(0)
  })
})
