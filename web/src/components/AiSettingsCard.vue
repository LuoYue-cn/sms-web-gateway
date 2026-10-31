<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NModal, NRadio, NRadioGroup, NSpace, NSwitch } from 'naive-ui'
import { aiApi, ApiError } from '@/api'
import type { AiConfig, AiDetailLevel, AiPreview } from '@/api'
import { notify } from '@/utils/notify'

const cfg = ref<AiConfig | null>(null)
const form = reactive({ enabled: false, base_url: '', model: '', level: 2 as AiDetailLevel, api_key: '' })
const fieldErr = ref<Record<string, string>>({})
const saving = ref(false)
const testing = ref(false)
const testMsg = ref<{ ok: boolean; text: string } | null>(null)
const previewing = ref(false)
const preview = ref<AiPreview | null>(null)
const showPreview = ref(false)

function fill(c: AiConfig): void {
  cfg.value = c
  form.enabled = c.ai_enabled
  form.base_url = c.ai_base_url
  form.model = c.ai_model
  form.level = c.ai_detail_level
  form.api_key = ''
}

onMounted(async () => {
  try {
    fill(await aiApi.get())
  } catch (e) {
    notify.fromError(e, '加载 AI 设置失败')
  }
})

const dirty = computed(() => {
  const c = cfg.value
  if (!c) return false
  return form.enabled !== c.ai_enabled || form.base_url !== c.ai_base_url || form.model !== c.ai_model || form.level !== c.ai_detail_level || form.api_key !== ''
})

const LEVELS: { v: AiDetailLevel; title: string; desc: string }[] = [
  { v: 0, title: '只发聚合数字', desc: '收发数量、时段分布、发送成功率等。不含任何短信内容、发件方和号码。' },
  { v: 1, title: '聚合数字 + 发件来源与类别', desc: '在上面的基础上加入短信签名（如“京东”）或打码号码，以及类别计数。不含正文。' },
  { v: 2, title: '再加脱敏后的正文摘要', desc: '最多 40 条、每条 90 字。验证码、金额、手机号、卡号尾号、链接、邮箱、长数字都会先替换成占位符；验证码短信不带正文。' },
]

async function save(): Promise<boolean> {
  fieldErr.value = {}
  saving.value = true
  try {
    fill(
      await aiApi.update({
        ai_enabled: form.enabled,
        ai_base_url: form.base_url.trim(),
        ai_model: form.model.trim(),
        ai_detail_level: form.level,
        ...(form.api_key ? { ai_api_key: form.api_key.trim() } : {}),
      }),
    )
    notify.success('AI 设置已保存')
    return true
  } catch (e) {
    if (e instanceof ApiError && e.status === 400) fieldErr.value = e.fields
    notify.fromError(e, '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function clearKey(): Promise<void> {
  try {
    fill(await aiApi.update({ clear_api_key: true }))
    notify.success('已清除 API Key')
  } catch (e) {
    notify.fromError(e, '操作失败')
  }
}

async function test(): Promise<void> {
  if (dirty.value && !(await save())) return
  testing.value = true
  testMsg.value = null
  try {
    const r = await aiApi.test()
    testMsg.value = { ok: r.ok, text: r.ok ? `${r.message}（模型 ${r.model ?? form.model}）` : r.message }
  } catch (e) {
    testMsg.value = { ok: false, text: e instanceof ApiError ? e.displayMessage : '测试失败' }
  } finally {
    testing.value = false
  }
}

async function openPreview(): Promise<void> {
  if (dirty.value && !(await save())) return
  previewing.value = true
  try {
    preview.value = await aiApi.preview()
    showPreview.value = true
  } catch (e) {
    notify.fromError(e, '生成预览失败')
  } finally {
    previewing.value = false
  }
}

const keyText = computed(() => {
  const c = cfg.value
  if (!c || !c.key_set) return '尚未设置'
  return c.key_source === 'env' ? '已设置（来自服务器环境变量 SMSGATE_AI_API_KEY）' : '已设置（已保存，不会再显示）'
})
</script>

<template>
  <section id="ai" class="card sec">
    <h3>AI 日报</h3>
    <n-alert type="info" :bordered="false" class="mb">
      填写任意 <strong>OpenAI 兼容</strong>的服务（OpenAI、DeepSeek、通义千问、智谱、Moonshot、本地 Ollama 等）。
      短信内容属于隐私：<strong>永远不会发送短信原文、完整号码、联系人姓名和验证码</strong>，下面可以选择最多发到哪一档，开启前建议先点「预览将发送的内容」。
    </n-alert>

    <n-form label-placement="top" class="grid">
      <n-form-item label="服务地址（Base URL）" :validation-status="fieldErr.ai_base_url ? 'error' : undefined" :feedback="fieldErr.ai_base_url || '例如 https://api.openai.com/v1 或 http://127.0.0.1:11434/v1（Ollama）。会自动补 /chat/completions'">
        <n-input v-model:value="form.base_url" placeholder="https://api.openai.com/v1" />
      </n-form-item>
      <n-form-item label="模型名称" :validation-status="fieldErr.ai_model ? 'error' : undefined" :feedback="fieldErr.ai_model || '例如 gpt-4o-mini、deepseek-chat、qwen-plus'">
        <n-input v-model:value="form.model" placeholder="gpt-4o-mini" />
      </n-form-item>
      <n-form-item label="API Key" :validation-status="fieldErr.ai_api_key ? 'error' : undefined" :feedback="fieldErr.ai_api_key || keyText">
        <n-space :wrap="false" style="width: 100%">
          <n-input v-model:value="form.api_key" type="password" show-password-on="click" placeholder="留空表示保持不变" autocomplete="new-password" style="flex: 1; min-width: 180px" />
          <n-button v-if="cfg?.key_source === 'db'" quaternary type="error" @click="clearKey">清除</n-button>
        </n-space>
      </n-form-item>
      <n-form-item label="启用 AI 日报">
        <n-switch v-model:value="form.enabled" />
        <span class="muted hint">关闭时生成的是不依赖 AI 的规则模板日报</span>
      </n-form-item>
    </n-form>

    <div class="lvl-title">发送给 AI 的内容</div>
    <n-radio-group v-model:value="form.level" class="lvls">
      <label v-for="l in LEVELS" :key="l.v" class="lvl" :class="{ on: form.level === l.v }">
        <n-radio :value="l.v" />
        <span>
          <strong>{{ l.title }}</strong>
          <small class="muted">{{ l.desc }}</small>
        </span>
      </label>
    </n-radio-group>

    <n-alert v-if="testMsg" :type="testMsg.ok ? 'success' : 'error'" :bordered="false" class="mt">{{ testMsg.text }}</n-alert>

    <div class="actions">
      <n-button :loading="previewing" @click="openPreview">预览将发送的内容</n-button>
      <n-button :loading="testing" :disabled="!form.base_url || !form.model" @click="test">测试连接</n-button>
      <n-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</n-button>
    </div>
    <div class="muted small">
      每天自动生成：在宝塔「计划任务」里添加 Shell 脚本，每天早上执行
      <code>php /www/wwwroot/你的站点/bin/daily_report.php</code>（生成前一天的报告）。也可以在「AI 日报」页手动生成。
      想让 API Key 不进数据库，可以改用环境变量 <code>SMSGATE_AI_API_KEY</code>。
    </div>

    <n-modal v-model:show="showPreview" preset="card" title="将发送给 AI 的完整内容" style="max-width: 760px; width: 94vw">
      <template v-if="preview">
        <n-alert type="info" :bordered="false" class="mb">
          这是今天（{{ preview.date }}）的数据按当前档位（{{ preview.detail_level }}）生成的真实请求内容，共 {{ preview.chars }} 个字符。API Key 在请求头里，不在其中。
          这里只是预览，不会联网。
        </n-alert>
        <div v-for="(m, i) in preview.messages" :key="i" class="pv">
          <div class="role">{{ m.role === 'system' ? '系统指令' : '数据' }}</div>
          <pre class="sms-text">{{ m.content }}</pre>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.sec {
  padding: 18px 20px;
  margin-bottom: 16px;
}
.sec h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.mb {
  margin-bottom: 14px;
}
.mt {
  margin-top: 12px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.hint {
  margin-left: 10px;
  font-size: 12px;
}
.lvl-title {
  margin: 4px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.lvls {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.lvl {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
}
.lvl.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.lvl span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.lvl small {
  font-size: 12px;
  line-height: 1.5;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin: 14px 0 8px;
}
.small {
  font-size: 12px;
  line-height: 1.7;
}
code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--panel-2);
  word-break: break-all;
}
.pv + .pv {
  margin-top: 12px;
}
.role {
  font-size: 12px;
  color: var(--text-2);
  margin-bottom: 4px;
}
pre {
  margin: 0;
  max-height: 280px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--panel-2);
  font-size: 12px;
  line-height: 1.55;
}
@media (max-width: 720px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .actions {
    justify-content: stretch;
  }
  .actions > * {
    flex: 1;
  }
}
</style>
