import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const { logout } = vi.hoisted(() => ({ logout: vi.fn<() => Promise<void>>() }))

vi.mock('@/auth/store', () => ({ useAuthStore: () => ({ logout }) }))

import UserMenu from './UserMenu.vue'

let wrapper: VueWrapper

async function open() {
  wrapper = mount(UserMenu, { attachTo: document.body })
  await wrapper.get('button[aria-label="Profile"]').trigger('keydown', { key: 'Enter' })
  await flushPromises()
}

function menuItem(text: string): HTMLElement {
  const item = [...document.body.querySelectorAll<HTMLElement>('[role="menuitem"]')].find((el) =>
    el.textContent?.includes(text),
  )
  if (!item) throw new Error(`menu item "${text}" not found`)
  return item
}

describe('UserMenu', () => {
  beforeEach(() => {
    logout.mockReset()
    logout.mockResolvedValue(undefined)
  })

  afterEach(() => {
    wrapper.unmount()
  })

  it('opens with the User label and a Sign out item', async () => {
    await open()

    const menu = document.body.querySelector('[role="menu"]')
    expect(menu?.textContent).toContain('User')
    expect(menuItem('Sign out')).toBeTruthy()
  })

  it('logs out when Sign out is selected', async () => {
    await open()

    menuItem('Sign out').click()
    await flushPromises()

    expect(logout).toHaveBeenCalledTimes(1)
  })
})
