<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NSelect, NSpin, NSteps, NStep } from 'naive-ui'
import { CheckmarkCircle, CloseCircle } from '@vicons/ionicons5'
import { ApiError, installApi } from '@/api'
import type { InstallCheck } from '@/api'
import { setInstalledCached } from '@/router'
import { notify } from '@/utils/notify'
import { browserTimeZone, listTimeZones } from '@/utils/time'
import ThemeToggle from '@/components/ThemeToggle.vue'

const router = useRouter()

const loading = ref(true)
const loadErr = ref('')
const installed = ref(false)
const checks = ref<InstallCheck[]>([])
const step = ref(1)
const submitting = ref(false)
const done = ref(false)

const form = ref({
  username: 'admin',
  password: '',
  password2: '',
  site_name: 'SMS Web Gateway',
  timezone: browserTimeZone(),
})
const fieldErr = ref<Record<string, string>>({})
const tzOptions = listTimeZones().map((z) => ({ label: z, value: z }))

const allOk = computed(() => checks.value.length > 0 && checks.value.every((c) => c.ok))

async function load(): Promise<void> {
  loading.value = true
  loadErr.value = ''
  try {
    const s = await installApi.status()
    installed.value = s.installed
    checks.value = s.checks ?? []
  } catch (e) {
    loadErr.value = e instanceof Error ? e.message : '无法获取安装状态'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function validate(): boolean {
  const err: Record<string, string> = {}
  const f = form.value
  if (!/^[A-Za-z0-9_.-]{3,32}$/.test(f.username)) err.username = '用户名为 3–32 位字母、数字或 _ . -'
  if (f.password.length < 10) err.password = '密码至少 10 位'
  if (f.password2 !== f.password) err.password2 = '两次输入的密码不一致'
  if (!f.site_name.trim()) err.site_name = '请输入系统名称'
  fieldErr.value = err
  return Object.keys(err).length === 0
}

async function submit(): Promise<void> {
  if (!validate() || submitting.value) return
  submitting.value = true
  try {
    await installApi.install({
      username: form.value.username.trim(),
      password: form.value.password,
      site_name: form.value.site_name.trim(),
      timezone: form.value.timezone,
    })
    done.value = true
    installed.value = true
    setInstalledCached(true)
    step.value = 3
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.code === 'already_installed' || e.status === 409) {
        installed.value = true
      } else if (e.status === 400) {
        const f = e.fields
        const next: Record<string, string> = {}
        for (const k of Object.keys(f)) next[k] = String(f[k])
        fieldErr.value = next
      }
    }
    notify.fromError(e, '安装失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <theme-toggle class="theme" />
    <div class="card wide">
      <h1>安装向导</h1>

      <div v-if="loading" class="center"><n-spin /></div>

      <template v-else-if="loadErr">
        <n-alert type="error" :bordered="false">{{ loadErr }}</n-alert>
        <n-button style="margin-top: 12px" @click="load">重试</n-button>
      </template>

      <template v-else-if="installed && !done">
        <n-alert type="warning" :bordered="false" title="安装向导已自动禁用">
          系统已经完成安装初始化，出于安全原因，安装向导已自动禁用。如需重新安装，请在服务器命令行执行重置命令。
        </n-alert>
        <n-button type="primary" style="margin-top: 16px" @click="router.push('/login')">前往登录</n-button>
      </template>

      <template v-else>
        <n-steps :current="step" size="small" style="margin: 8px 0 20px">
          <n-step title="环境检测" />
          <n-step title="管理员设置" />
          <n-step title="完成" />
        </n-steps>

        <div v-if="step === 1">
          <ul class="checks">
            <li v-for="c in checks" :key="c.key">
              <n-icon :component="c.ok ? CheckmarkCircle : CloseCircle" :color="c.ok ? 'var(--ok)' : 'var(--danger)'" size="20" />
              <span class="lbl">{{ c.label }}</span>
              <span class="muted detail">{{ c.detail }}</span>
            </li>
            <li v-if="!checks.length" class="muted">服务端未返回环境检测项。</li>
          </ul>
          <n-alert v-if="checks.length && !allOk" type="warning" :bordered="false" style="margin-top: 12px">
            存在未通过的检测项，请先修复后重新检测。
          </n-alert>
          <div class="actions">
            <n-button @click="load">重新检测</n-button>
            <n-button type="primary" :disabled="checks.length > 0 && !allOk" @click="step = 2">下一步</n-button>
          </div>
        </div>

        <n-form v-else-if="step === 2" label-placement="top" @submit.prevent="submit">
          <n-form-item label="管理员用户名" :validation-status="fieldErr.username ? 'error' : undefined" :feedback="fieldErr.username">
            <n-input v-model:value="form.username" autocomplete="username" />
          </n-form-item>
          <n-form-item label="密码（至少 10 位）" :validation-status="fieldErr.password ? 'error' : undefined" :feedback="fieldErr.password">
            <n-input v-model:value="form.password" type="password" show-password-on="click" autocomplete="new-password" />
          </n-form-item>
          <n-form-item label="确认密码" :validation-status="fieldErr.password2 ? 'error' : undefined" :feedback="fieldErr.password2">
            <n-input v-model:value="form.password2" type="password" show-password-on="click" autocomplete="new-password" />
          </n-form-item>
          <n-form-item label="系统名称" :validation-status="fieldErr.site_name ? 'error' : undefined" :feedback="fieldErr.site_name">
            <n-input v-model:value="form.site_name" />
          </n-form-item>
          <n-form-item label="默认时区" :validation-status="fieldErr.timezone ? 'error' : undefined" :feedback="fieldErr.timezone">
            <n-select v-model:value="form.timezone" :options="tzOptions" filterable />
          </n-form-item>
          <div class="actions">
            <n-button @click="step = 1">上一步</n-button>
            <n-button type="primary" :loading="submitting" @click="submit">完成安装</n-button>
          </div>
        </n-form>

        <div v-else>
          <n-alert type="success" :bordered="false" title="安装完成">
            管理员账号已创建，系统初始化完成，安装向导已自动锁定禁用。
          </n-alert>
          <n-button type="primary" style="margin-top: 16px" @click="router.push('/login')">前往登录</n-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.wide {
  width: 100%;
  max-width: 560px;
  padding: 24px 28px;
  animation: rise 0.3s ease;
}
h1 {
  margin: 0 0 8px;
  font-size: 22px;
}
.center {
  display: flex;
  justify-content: center;
  padding: 40px;
}
.checks {
  list-style: none;
  padding: 0;
  margin: 0;
}
.checks li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--border);
}
.lbl {
  font-weight: 500;
}
.detail {
  margin-left: auto;
  font-size: 12px;
  text-align: right;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
