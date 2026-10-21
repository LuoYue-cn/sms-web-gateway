<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NInput } from 'naive-ui'
import { ApiError } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { errorText, notify } from '@/utils/notify'
import ThemeToggle from '@/components/ThemeToggle.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const lockMsg = ref('')
const netErr = computed(() => route.query.err === 'network')

onMounted(() => {
  if (auth.isAuthed) void router.replace('/')
})

async function submit(): Promise<void> {
  if (!username.value.trim() || !password.value || loading.value) return
  loading.value = true
  lockMsg.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    const r = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
    await router.replace(r)
  } catch (e) {
    if (e instanceof ApiError && (e.status === 429 || e.code === 'too_many_attempts')) {
      lockMsg.value = e.displayMessage
    }
    // 登录失败本身就返回 401，不能走 fromError（它会忽略 401 unauthenticated），这里显式展示 error.message
    notify.error(errorText(e, '登录失败'))
    password.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <theme-toggle class="theme" />
    <form class="card box" @submit.prevent="submit">
      <h1>{{ auth.siteName }}</h1>
      <p class="muted sub">登录管理后台</p>
      <n-alert v-if="netErr" type="warning" :bordered="false" style="margin-bottom: 12px">无法连接服务器，请检查网络或稍后重试。</n-alert>
      <n-alert v-if="lockMsg" type="error" :bordered="false" style="margin-bottom: 12px">{{ lockMsg }}</n-alert>
      <n-form label-placement="top" :show-feedback="false" @submit.prevent="submit">
        <n-form-item label="用户名">
          <n-input v-model:value="username" autofocus autocomplete="username" placeholder="管理员用户名" :input-props="{ name: 'username' }" />
        </n-form-item>
        <n-form-item label="密码" style="margin-top: 12px">
          <n-input
            v-model:value="password"
            type="password"
            show-password-on="click"
            autocomplete="current-password"
            placeholder="密码"
            :input-props="{ name: 'password' }"
            @keyup.enter="submit"
          />
        </n-form-item>
      </n-form>
      <n-button type="primary" block attr-type="submit" :loading="loading" :disabled="!username.trim() || !password" style="margin-top: 20px" @click="submit">
        登录
      </n-button>
    </form>
  </div>
</template>
