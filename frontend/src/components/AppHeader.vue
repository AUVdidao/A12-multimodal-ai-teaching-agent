<template>
  <div class="app-header">
    <div class="app-header__title">
      <h1>{{ title }}</h1>
    </div>

    <div class="app-header__search" role="search">
      <A12AssetIcon name="search" :size="23" />
      <input disabled aria-label="全局搜索" placeholder="搜索项目、资料、知识..." />
      <kbd>⌘K</kbd>
    </div>

    <div class="app-header__actions">
      <button class="icon-button notification-button" type="button" aria-label="通知中心尚未开放" disabled title="通知中心将在后续阶段开放">
        <A12AssetIcon name="bell" :size="29" />
      </button>
      <button class="icon-button" type="button" aria-label="帮助中心尚未开放" disabled title="帮助中心将在后续阶段开放">
        <A12AssetIcon name="question-help" :size="29" />
      </button>
      <el-dropdown trigger="click" @command="handleCommand">
        <button class="user-chip" type="button" aria-label="打开用户菜单">
          <span>{{ avatarText }}</span>
          <span class="user-chip__identity">
            <strong>{{ auth.user?.displayName || '用户' }}</strong>
            <small>{{ roleLabel }}</small>
          </span>
          <i aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="role in auth.user?.roles || []"
              :key="role"
              :command="`role:${role}`"
              :disabled="role === auth.activeRole"
            >
              切换为{{ roleName(role) }}
            </el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { UserRole } from '@/api/auth';
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { roleHome } from '@/router';
import { useAuthStore } from '@/stores/auth';
import { ElMessage } from 'element-plus';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const title = computed(() => String(route.meta.title || '教学工作台'));
const avatarText = computed(() => (auth.user?.displayName || '用户').slice(0, 1));
const roleLabel = computed(() => roleName(auth.activeRole || 'TEACHER'));

function roleName(role: UserRole) {
  return ({ TEACHER: '教师', LEADER: '教研负责人', STUDENT: '学生' })[role];
}

async function handleCommand(command: string) {
  if (command === 'logout') {
    await auth.logout();
    await router.replace('/login');
    return;
  }
  if (!command.startsWith('role:')) return;
  const role = command.slice(5) as UserRole;
  try {
    await auth.switchRole(role);
    await router.replace(roleHome(role));
    ElMessage.success(`已切换为${roleName(role)}`);
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '身份切换失败');
  }
}
</script>
