<template>
  <AuthForm
    title="Sign in"
    submit-label="Sign in"
    autocomplete="current-password"
    :schema="LoginSchema"
    :submit="auth.login"
  >
    No account yet?
    <RouterLink to="/register" class="text-emerald-400 hover:underline">Register</RouterLink>
  </AuthForm>
</template>

<script setup lang="ts">
import AuthForm from '@/auth/AuthForm.vue'
import { useAuthStore } from '@/auth/store'
import { LoginSchema } from '@/auth/validation'

const auth = useAuthStore()
</script>
