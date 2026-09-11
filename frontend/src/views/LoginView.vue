<template>
  <main class="lf-auth-page" data-auth-page="login">
    <div class="lf-auth-art" :style="{ backgroundImage: `url(${loginBackground})` }" aria-hidden="true" />

    <section class="lf-auth-panel" aria-labelledby="login-title">
      <div class="lf-auth-card">
        <header class="lf-auth-card__brand">
          <span class="lf-auth-card__logo"><img :src="lessonForgeLogo" alt="LessonForge" /></span>
        </header>

        <div class="lf-auth-card__intro">
          <p class="lf-auth-kicker">WELCOME BACK</p>
          <h1 id="login-title">欢迎回来</h1>
          <p>登录你的账号，继续创造更好的课堂</p>
        </div>

        <form class="lf-auth-form" @submit.prevent="submit">
          <label class="lf-auth-field">
            <span>账号</span>
            <input
              v-model="form.username"
              name="username"
              autocomplete="username"
              placeholder="邮箱或账号"
              :disabled="submitting"
              @input="errorMessage = ''"
            />
          </label>
          <label class="lf-auth-field">
            <span>密码</span>
            <input
              v-model="form.password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="输入密码"
              :disabled="submitting"
              @input="errorMessage = ''"
            />
          </label>

          <p v-if="errorMessage" class="lf-auth-error" role="alert">{{ errorMessage }}</p>
          <button class="lf-auth-submit" type="submit" :disabled="submitting">
            <span v-if="submitting" class="lf-auth-spinner" aria-hidden="true" />
            {{ submitting ? '正在登录…' : '登录' }}
          </button>
        </form>

        <p class="lf-auth-switch">
          还没有账号？
          <RouterLink :to="registerLink">创建账号</RouterLink>
        </p>
      </div>
    </section>

    <p class="lf-auth-footer">LessonForge · AI × Education</p>
  </main>
</template>

<script setup lang="ts">
import loginBackground from '@/assets/lessonforge-login-background.png';
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
const form = reactive({ username: 'admin', password: 'admin' });
const registerLink = { name: 'lessonforge-register', query: { redirect: route.query.redirect } };

function messageFor(error: any) {
  const status = error?.response?.status;
  if (status === 401 || status === 403) return '账号或密码错误，请重试。';
  if (!error?.response) return '暂时无法连接服务，请检查网络后重试。';
  return error?.response?.data?.message || '暂时无法完成登录，请稍后重试。';
}

async function submit() {
  errorMessage.value = '';
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入账号和密码。';
    return;
  }

  submitting.value = true;
  try {
    const user = await auth.login({ username: form.username.trim(), password: form.password });
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
