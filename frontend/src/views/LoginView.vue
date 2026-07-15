<template>
  <main class="auth-page">
    <section class="auth-brand-panel">
      <div class="auth-brand-panel__brand">
        <UiBrandMark :size="72" />
        <div>
          <strong>A12 教学智能体</strong>
          <span>多模态 AI 互动式教学</span>
        </div>
      </div>
      <div class="auth-brand-panel__copy">
        <p>协同教学工作空间</p>
        <h1>从教学任务到课堂发布，统一沉淀每一次专业协作。</h1>
        <div class="auth-brand-panel__flow" aria-label="协同流程">
          <span>任务下发</span><i />
          <span>教师共创</span><i />
          <span>审核发布</span><i />
          <span>学生问答</span>
        </div>
      </div>
      <div class="auth-brand-panel__status"><i /> 系统服务正常</div>
    </section>

    <section class="auth-form-panel">
      <div class="auth-form-card">
        <header>
          <span class="auth-form-card__eyebrow">A12 COLLABORATION</span>
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建学生账号' }}</h2>
          <p>{{ mode === 'login' ? '登录后进入与你当前身份匹配的工作空间。' : '公开注册仅开放学生身份。' }}</p>
        </header>

        <div class="auth-mode-switch" role="tablist" aria-label="认证方式">
          <button :class="{ 'is-active': mode === 'login' }" type="button" @click="mode = 'login'">账号登录</button>
          <button :class="{ 'is-active': mode === 'register' }" type="button" @click="mode = 'register'">学生注册</button>
        </div>

        <section v-if="mode === 'login' && demoMode" class="auth-demo-accounts" aria-label="演示账号">
          <div class="auth-demo-accounts__heading">
            <strong>演示账号</strong>
            <span>点击即可填入，仅在演示构建中显示</span>
          </div>
          <div class="auth-demo-accounts__grid">
            <button
              v-for="account in demoAccounts"
              :key="`${account.username}-${account.role}`"
              type="button"
              :class="{ 'is-selected': form.username === account.username && form.activeRole === account.role }"
              @click="useDemoAccount(account)"
            >
              <strong>{{ account.label }}</strong>
              <span>{{ account.username }}</span>
            </button>
          </div>
        </section>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item v-if="mode === 'register'" label="姓名">
            <el-input v-model="form.displayName" size="large" maxlength="100" placeholder="请输入姓名" />
          </el-form-item>
          <el-form-item label="账号">
            <el-input v-model="form.username" size="large" maxlength="50" autocomplete="username" placeholder="请输入账号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="请输入密码"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item v-if="mode === 'login'" label="进入身份">
            <el-select v-model="form.activeRole" size="large" class="auth-role-select">
              <el-option label="教师" value="TEACHER" />
              <el-option label="教研负责人" value="LEADER" />
              <el-option label="学生" value="STUDENT" />
            </el-select>
          </el-form-item>

          <p v-if="errorMessage" class="auth-form-card__error" role="alert">{{ errorMessage }}</p>
          <el-button class="auth-submit" type="primary" size="large" :loading="submitting" @click="submit">
            {{ mode === 'login' ? '登录工作空间' : '注册并进入学习空间' }}
          </el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import type { UserRole } from '@/api/auth';
import UiBrandMark from '@/components/ui/UiBrandMark.vue';
import { roleHome } from '@/router';
import { useAuthStore } from '@/stores/auth';
import { reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

interface DemoAccount {
  label: string;
  username: string;
  password: string;
  role: UserRole;
}

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const mode = ref<'login' | 'register'>('login');
const submitting = ref(false);
const errorMessage = ref('');
const demoMode = import.meta.env.VITE_DEMO_MODE === 'true';
const demoAccounts: DemoAccount[] = [
  { label: '教师', username: 'teacher', password: 'Teacher123!', role: 'TEACHER' },
  { label: '教研负责人', username: 'leader', password: 'Leader123!', role: 'LEADER' },
  { label: '学生', username: 'student', password: 'Student123!', role: 'STUDENT' },
  { label: '多角色账号', username: 'multi', password: 'Multi123!', role: 'TEACHER' },
];
const form = reactive({
  displayName: '',
  username: demoMode ? demoAccounts[0].username : '',
  password: demoMode ? demoAccounts[0].password : '',
  activeRole: 'TEACHER' as UserRole,
});

watch(mode, (nextMode) => {
  errorMessage.value = '';
  if (nextMode === 'login' && demoMode) {
    useDemoAccount(demoAccounts[0]);
    return;
  }
  form.username = '';
  form.password = '';
});

function useDemoAccount(account: DemoAccount) {
  form.username = account.username;
  form.password = account.password;
  form.activeRole = account.role;
  errorMessage.value = '';
}

async function submit() {
  errorMessage.value = '';
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入账号和密码。';
    return;
  }
  if (mode.value === 'register' && !form.displayName.trim()) {
    errorMessage.value = '请输入姓名。';
    return;
  }

  submitting.value = true;
  try {
    const user = mode.value === 'login'
      ? await auth.login({ username: form.username, password: form.password, activeRole: form.activeRole })
      : await auth.register({ username: form.username, password: form.password, displayName: form.displayName });
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '';
    await router.replace(redirect && redirect.startsWith('/') ? redirect : roleHome(user.activeRole));
  } catch (error: any) {
    errorMessage.value = error?.response?.data?.message || '暂时无法完成认证，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}
</script>
