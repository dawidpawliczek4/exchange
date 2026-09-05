<template>
  <main class="flex h-dvh items-center justify-center bg-[#101014] text-[#d1d4dc]">
    <form
      class="w-full max-w-sm space-y-4 rounded-lg border border-neutral-800 bg-[#16161c] p-6"
      novalidate
      @submit.prevent="onSubmit"
    >
      <h1 class="text-lg font-semibold">{{ title }}</h1>

      <label class="block space-y-1 text-sm">
        <span>Email</span>
        <input
          v-model="email"
          name="email"
          type="email"
          autocomplete="email"
          class="w-full rounded border border-neutral-700 bg-[#101014] px-3 py-2 outline-none focus:border-emerald-500"
        />
        <span v-if="fields.email" data-testid="email-error" class="block text-xs text-red-400">
          {{ fields.email }}
        </span>
      </label>

      <label class="block space-y-1 text-sm">
        <span>Password</span>
        <input
          v-model="password"
          name="password"
          type="password"
          :autocomplete="autocomplete"
          class="w-full rounded border border-neutral-700 bg-[#101014] px-3 py-2 outline-none focus:border-emerald-500"
        />
        <span
          v-if="fields.password"
          data-testid="password-error"
          class="block text-xs text-red-400"
        >
          {{ fields.password }}
        </span>
      </label>

      <p v-if="message" data-testid="form-error" class="text-sm text-red-400">{{ message }}</p>

      <button
        type="submit"
        :disabled="pending"
        class="w-full rounded bg-emerald-700 px-3 py-2 text-sm font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
      >
        {{ submitLabel }}
      </button>

      <p class="text-center text-xs text-neutral-500"><slot /></p>
    </form>
  </main>
</template>

<script setup lang="ts">
import { useAuthForm } from '@/auth/useAuthForm'
import type { AuthResult } from '@/auth/store'
import type { AuthSchema } from '@/auth/validation'

const props = defineProps<{
  title: string
  submitLabel: string
  autocomplete: 'current-password' | 'new-password'
  schema: AuthSchema
  submit: (email: string, password: string) => Promise<AuthResult>
}>()

const { email, password, fields, message, pending, onSubmit } = useAuthForm(
  props.schema,
  props.submit,
)
</script>
