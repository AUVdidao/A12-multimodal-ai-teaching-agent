<template>
  <main class="lf-auth-page" data-auth-page="register">
    <div class="lf-auth-shell">
      <header class="lf-auth-brand">
        <span class="lf-auth-brand__logo"><img :src="lessonForgeLogo" alt="LessonForge" /></span>
      </header>

      <section class="lf-auth-card lf-auth-card--register" aria-labelledby="register-title">
        <div class="lf-auth-card__intro">
          <h1 id="register-title">创建你的 LessonForge 账号</h1>
        </div>

        <form class="lf-auth-form" @submit.prevent="submit">
          <label class="lf-auth-field">
            <span>姓名</span>
            <input v-model="form.displayName" name="displayName" autocomplete="name" placeholder="你的姓名" :disabled="submitting" />
          </label>
          <label class="lf-auth-field">
            <span>账号</span>
            <input v-model="form.username" name="username" autocomplete="username" placeholder="邮箱或账号" :disabled="submitting" />
          </label>
          <label class="lf-auth-field">
            <span>密码</span>
            <input v-model="form.password" name="password" type="password" autocomplete="new-password" placeholder="至少 8 位字符" :disabled="submitting" />
          </label>
          <label class="lf-auth-field">
            <span>确认密码</span>
            <input v-model="form.confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" :disabled="submitting" />
          </label>
          <div class="lf-auth-field-group">
            <span class="lf-auth-field-group__label">你的身份</span>
            <div class="lf-auth-role-grid" role="radiogroup" aria-label="注册身份">
              <button
                v-for="option in roleOptions"
                :key="option.value"
                class="lf-auth-role"
                :class="{ 'is-selected': form.role === option.value }"
                type="button"
                role="radio"
                :aria-checked="form.role === option.value"
                :disabled="submitting"
                @click="form.role = option.value"
              >
                <strong>{{ option.label }}</strong>
                <small>{{ option.description }}</small>
              </button>
            </div>
          </div>

          <p v-if="errorMessage" class="lf-auth-error" role="alert">{{ errorMessage }}</p>
          <button class="lf-auth-submit" type="submit" :disabled="submitting">
            <span v-if="submitting" class="lf-auth-spinner" aria-hidden="true" />
            {{ submitting ? '正在创建…' : '创建账号' }}
          </button>
        </form>

        <p class="lf-auth-switch">
          已有账号？
          <RouterLink :to="loginLink">返回登录</RouterLink>
        </p>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import type { RegisterPayload } from '@/api/auth';
import lessonForgeLogo from '@/assets/lessonforge-logo-full.png';
import { roleHome } from '@/router';
import { useAuthStore } from '@/stores/auth';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const submitting = ref(false);
const errorMessage = ref('');
const roleOptions: Array<{ value: RegisterPayload['role']; label: string; description: string }> = [
  { value: 'TEACHER', label: '教师', description: '创建课程与课件' },
  { value: 'LEADER', label: '教研人员', description: '组织教研与审核' },
];
const form = reactive({ displayName: '', username: '', password: '', confirmPassword: '', role: 'TEACHER' as RegisterPayload['role'] });
const loginLink = { name: 'lessonforge-login', query: { redirect: route.query.redirect } };

function messageFor(error: any) {
  const status = error?.response?.status;
  if (status === 409) return '这个账号已经存在，请直接登录。';
  if (!error?.response) return '暂时无法连接服务，请检查网络后重试。';
  return error?.response?.data?.message || '暂时无法创建账号，请稍后重试。';
}

async function submit() {
  errorMessage.value = '';
  if (!form.displayName.trim() || !form.username.trim() || !form.password) {
    errorMessage.value = '请完整填写信息。';
    return;
  }
  if (form.password.length < 8) {
    errorMessage.value = '密码至少需要 8 位字符。';
    return;
  }
  if (form.password !== form.confirmPassword) {
    errorMessage.value = '两次输入的密码不一致。';
    return;
  }

  submitting.value = true;
  try {
    const user = await auth.register({
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      password: form.password,
      role: form.role,
    });
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : roleHome(user.activeRole);
    await router.replace(redirect);
  } catch (error) {
    errorMessage.value = messageFor(error);
  } finally {
    submitting.value = false;
  }
}
</script>
