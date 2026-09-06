<template>
  <header class="flex h-12 items-center justify-end gap-2 border-b px-4">
    <Button v-if="auth.isAuthenticated" variant="ghost" size="icon" as-child>
      <a href="#" aria-label="Profile"><UserRound /></a>
    </Button>
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
import { UserRound } from '@lucide/vue'
import { Button } from '@/shared/ui/button'
import { useAuthStore } from '@/auth/store'

const auth = useAuthStore()
</script>
