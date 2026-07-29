<template>
  <div class="app-header">
    <RouterLink v-if="isHome" class="app-header__brand" :to="{ name: 'home' }" aria-label="A12 教学智能体首页">
      <UiBrandMark :size="38" />
      <span>
        <strong>A12 教学智能体</strong>
        <small>多模态 AI 互动式教学</small>
      </span>
    </RouterLink>
    <RouterLink
      v-else-if="isStudentInteraction"
      class="app-header__back-home"
      :to="{ name: 'home' }"
      aria-label="返回首页"
    >
      <A12AssetIcon name="home" :size="20" />
      <span>返回首页</span>
    </RouterLink>
    <div v-else-if="title" class="app-header__title">
      <h1>{{ title }}</h1>
    </div>

    <form class="app-header__search" role="search" @submit.prevent="submitSearch">
      <A12AssetIcon name="search" :size="23" />
      <input
        ref="searchInput"
        v-model="searchQuery"
        aria-label="全局搜索"
        placeholder="搜索项目、任务、问答..."
      />
      <kbd>Ctrl K</kbd>
      <button class="header-search-submit" type="submit" aria-label="提交搜索" title="搜索">
        <A12AssetIcon name="search" :size="18" />
      </button>
    </form>

    <div class="app-header__actions">
      <button class="icon-button header-search-trigger" type="button" aria-label="打开全局搜索" title="全局搜索" @click="openSearch">
        <A12AssetIcon name="search" :size="24" />
      </button>
      <button class="icon-button" type="button" aria-label="打开帮助" title="帮助" @click="helpVisible = true">
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

    <el-drawer v-model="helpVisible" title="帮助" size="min(420px, 100%)" direction="rtl">
      <section class="help-panel" aria-label="当前角色可用流程">
        <p>当前仅展示已经可用的流程入口。</p>
        <ul>
          <li v-for="item in helpItems" :key="item.title">
            <strong>{{ item.title }}</strong>
            <span>{{ item.description }}</span>
          </li>
        </ul>
      </section>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import type { UserRole } from '@/api/auth';
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import UiBrandMark from '@/components/ui/UiBrandMark.vue';
import { roleHome } from '@/router';
import { useAuthStore } from '@/stores/auth';
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const title = computed(() => String(route.meta.title ?? '教学工作台'));
const isHome = computed(() => route.name === 'home');
const isStudentInteraction = computed(() => (
  route.meta.scene === 'STUDENT_INTERACTION'
  || ['teacher-questions', 'leader-questions'].includes(String(route.name))
));
const avatarText = computed(() => (auth.user?.displayName || '用户').slice(0, 1));
const roleLabel = computed(() => roleName(auth.activeRole || 'TEACHER'));
const searchInput = ref<HTMLInputElement>();
const searchQuery = ref('');
const helpVisible = ref(false);
const helpItems = computed(() => {
  if (auth.activeRole === 'LEADER') {
    return [
      { title: '课程与班级', description: '维护课程、班级并分配教学任务。' },
      { title: '成果审批与发布', description: '处理分配给你的审批，并发布已通过的成果。' },
      { title: '问答查看', description: '只读查看自己发布范围内的学生问答。' },
    ];
  }
  if (auth.activeRole === 'STUDENT') {
    return [
      { title: '学习任务', description: '查看所在班级已经发布的学习内容。' },
      { title: '我的问答', description: '对已发布学习任务提问，并查看教师回答。' },
    ];
  }
  return [
    { title: '教学项目', description: '创建并推进归属你的教学项目。' },
    { title: '教学任务与审批', description: '处理分配给你的任务，并提交或查看成果审批。' },
    { title: '项目问答', description: '查看自己项目的问题，回答或关闭已处理的问题。' },
  ];
});

onMounted(() => window.addEventListener('keydown', handleSearchShortcut));
onBeforeUnmount(() => window.removeEventListener('keydown', handleSearchShortcut));

function roleName(role: UserRole) {
  return ({ TEACHER: '教师', LEADER: '教研负责人', STUDENT: '学生' })[role];
}

function submitSearch() {
  const query = searchQuery.value.trim();
  void router.push({ name: 'global-search', query: query ? { q: query } : {} });
}

function openSearch() {
  void router.push({ name: 'global-search' });
}

function handleSearchShortcut(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLocaleLowerCase() !== 'k') return;
  event.preventDefault();
  if (window.matchMedia('(max-width: 1100px)').matches) {
    openSearch();
    return;
  }
  searchInput.value?.focus();
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

<style scoped>
.app-header__brand {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  color: inherit;
  text-decoration: none;
}

.app-header__brand > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.app-header__brand strong {
  overflow: hidden;
  color: var(--color-text);
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__brand small {
  color: var(--color-text-secondary);
  font-size: 11px;
}

.app-header__back-home {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  color: var(--color-text);
  font-size: 16px;
  font-weight: 650;
  text-decoration: none;
}

.app-header__back-home:hover {
  color: var(--color-primary);
}

.app-header__back-home:focus-visible {
  border-radius: 4px;
  outline: 3px solid var(--color-primary-border);
  outline-offset: 3px;
}

.header-search-submit {
  display: grid;
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ui-muted);
  cursor: pointer;
}

.header-search-trigger {
  display: none;
}

.header-search-submit:hover,
.header-search-submit:focus-visible {
  color: var(--color-primary);
}

.header-search-submit:focus-visible,
.icon-button:focus-visible {
  outline: 3px solid var(--color-primary-border);
  outline-offset: 2px;
}

.help-panel {
  color: var(--color-text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.help-panel > p {
  margin: 0 0 18px;
}

.help-panel ul {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--color-border);
}

.help-panel li {
  display: grid;
  gap: 4px;
  padding: 15px 0;
  border-bottom: 1px solid var(--color-border);
}

.help-panel strong {
  color: var(--color-text);
  font-size: 14px;
}

.help-panel span {
  color: var(--color-text-secondary);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .header-search-trigger {
    display: grid;
  }
}

@media (max-width: 640px) {
  .app-header__actions .icon-button {
    display: grid;
  }

  .help-panel {
    font-size: 13px;
  }
}
</style>
