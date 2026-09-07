<template>
  <header class="flex h-12 items-center justify-end gap-2 border-b px-4">
    <UserMenu v-if="auth.isAuthenticated" />
    <template v-else>
      <Button variant="ghost" size="sm" as-child>
        <RouterLink to="/login">Sign in</RouterLink>
      </Button>
      <Button size="sm" as-child>
        <RouterLink to="/register">Register</RouterLink>
      </Button>
    </template>
  </header>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { Button } from '@/shared/ui/button'
import { useAuthStore } from '@/auth/store'
import UserMenu from '@/layout/UserMenu.vue'

const auth = useAuthStore()
</script>
