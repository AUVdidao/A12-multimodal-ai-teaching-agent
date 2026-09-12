<template>
  <section class="lf-app" :class="{ 'lf-app--workspace': workspace }">
    <header class="lf-topbar">
      <button class="lf-brand" aria-label="LessonForge 首页" @click="goMissions">
        <span class="lf-brand__logo"><img :src="lessonForgeLogo" alt="" /></span>
      </button>
      <span v-if="!workspace" class="lf-topbar__divider" />
      <span v-if="!workspace" class="lf-topbar__context">{{ contextLabel }}</span>
    </header>
    <div class="lf-frame-body" :style="{ '--lf-app-nav-width': `${navigationWidth}px` }">
      <aside v-if="!workspace" class="lf-app-nav">
        <button v-if="showNewMission" class="lf-nav-action lf-nav-action--primary" :class="{ 'is-active': active === 'new' }" @click="goNew"><span>＋</span><span>New Mission</span></button>
        <button class="lf-nav-action" :class="{ 'is-active': active === 'missions' }" @click="goMissions"><span class="lf-nav-icon">⚑</span><span>Missions</span></button>
        <button class="lf-nav-action" :class="{ 'is-active': active === 'search' }" type="button" @click="openSearch"><span class="lf-nav-icon">⌕</span><span>Search</span></button>
        <div class="lf-nav-section">
          <div class="lf-nav-section__title"><span>Recent</span><span class="lf-nav-section__count">{{ recentMissions.length }}</span></div>
          <button v-for="mission in recentMissions" :key="mission.id" class="lf-recent-item" @click="goMission(mission.id)">{{ mission.title }}</button>
          <div v-if="!recentMissions.length" class="lf-nav-empty">No missions</div>
        </div>
        <div class="lf-nav-account">
        <button class="lf-nav-user" type="button" :aria-expanded="accountMenuOpen" aria-haspopup="menu" @click="accountMenuOpen = !accountMenuOpen"><span class="lf-avatar lf-avatar--small">{{ userInitial }}</span><span>{{ userName }}</span><span class="lf-nav-user__more">···</span></button>
          <div v-if="accountMenuOpen" class="lf-account-menu lf-account-menu--nav" role="menu">
            <div class="lf-account-menu__identity"><strong>{{ userName }}</strong><span>{{ roleLabel }}</span></div>
            <button v-if="auth.activeRole !== 'RESEARCHER'" type="button" role="menuitem" data-test="account-settings" @click="goSettings">设置</button>
            <button type="button" role="menuitem" data-test="account-logout" @click="handleLogout">退出登录</button>
          </div>
        </div>
      </aside>
      <button
        v-if="!workspace"
        class="lf-frame-resize-handle"
        type="button"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整左侧导航栏宽度"
        data-test="resize-app-nav"
        :aria-valuenow="navigationWidth"
        aria-valuemin="168"
        aria-valuemax="320"
        title="拖动调整左侧导航栏宽度"
        @pointerdown="startNavigationResize"
        @keydown.left.prevent="resizeNavigationBy(-16)"
        @keydown.right.prevent="resizeNavigationBy(16)"
      />
      <main class="lf-frame-content"><slot /></main>
      </div>
      <div v-if="searchOpen" class="lf-search-overlay" role="presentation" @click.self="closeSearch">
        <section class="lf-search-dialog" role="dialog" aria-modal="true" aria-labelledby="lf-search-title">
          <div class="lf-search-dialog__header">
            <div>
              <div class="lf-eyebrow">SEARCH</div>
              <h2 id="lf-search-title">Search Missions</h2>
            </div>
            <button class="lf-icon-button" type="button" aria-label="关闭搜索" @click="closeSearch">×</button>
          </div>
          <input v-model="searchQuery" class="lf-search-input" type="search" placeholder="Search recent Missions…" autofocus />
          <div v-if="searchQuery.trim() && !searchResults.length" class="lf-search-empty">No matching Missions.</div>
          <button v-for="mission in searchResults" :key="mission.id" class="lf-search-result" type="button" @click="goMission(mission.id)">
            <strong>{{ mission.title }}</strong>
            <small>{{ mission.description }}</small>
          </button>
          <p v-if="!searchQuery.trim()" class="lf-search-hint">Search is limited to Missions already loaded for this signed-in account.</p>
        </section>
      </div>
    </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { LessonForgeMission } from '@/types/lessonForge';
import lessonForgeLogo from '@/assets/lessonforge-logo-full.png';
import { useAuthStore } from '@/stores/auth';

const props = withDefaults(defineProps<{ workspace?: boolean; active?: 'new' | 'missions' | 'search' | 'settings'; recentMissions?: LessonForgeMission[]; searchMissions?: LessonForgeMission[]; contextLabel?: string; showNewMission?: boolean }>(), { workspace: false, active: 'missions', recentMissions: () => [], searchMissions: () => [], contextLabel: 'Teacher workspace', showNewMission: true });
const router = useRouter();
const auth = useAuthStore();
const searchOpen = ref(false);
const searchQuery = ref('');
const accountMenuOpen = ref(false);
const NAVIGATION_WIDTH_KEY = 'lessonforge:app-navigation-width';
const NAVIGATION_DEFAULT_WIDTH = 192;
const NAVIGATION_MIN_WIDTH = 168;
const NAVIGATION_MAX_WIDTH = 320;
const navigationWidth = ref(NAVIGATION_DEFAULT_WIDTH);
let navigationResizeStartX = 0;
let navigationResizeStartWidth = NAVIGATION_DEFAULT_WIDTH;
const isResearcher = computed(() => auth.activeRole === 'RESEARCHER');
const userName = computed(() => {
  const displayName = auth.user?.displayName?.trim();
  if (displayName && !/(?:demo|演示)/i.test(displayName)) return displayName;
  const username = auth.user?.username?.trim();
  return username?.split('@')[0] || 'Teacher';
});
const userInitial = computed(() => userName.value.slice(0, 1).toUpperCase());
const roleLabel = computed(() => auth.activeRole === 'RESEARCHER' ? '教研员' : auth.activeRole === 'LEADER' ? '负责人' : '教师');
const searchResults = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase();
  if (!query) return [];
  return props.searchMissions.filter((mission) => `${mission.title} ${mission.description}`.toLocaleLowerCase().includes(query)).slice(0, 5);
});
function navigationStorageKey() {
  return `${NAVIGATION_WIDTH_KEY}:${auth.user?.id ?? 'anonymous'}`;
}
function clampNavigationWidth(value: number) {
  return Math.min(NAVIGATION_MAX_WIDTH, Math.max(NAVIGATION_MIN_WIDTH, value));
}
function restoreNavigationWidth() {
  try {
    const stored = Number(window.localStorage.getItem(navigationStorageKey()));
    navigationWidth.value = Number.isFinite(stored) && stored > 0 ? clampNavigationWidth(stored) : NAVIGATION_DEFAULT_WIDTH;
  } catch {
    navigationWidth.value = NAVIGATION_DEFAULT_WIDTH;
  }
}
function persistNavigationWidth() {
  try { window.localStorage.setItem(navigationStorageKey(), String(navigationWidth.value)); } catch { /* storage can be unavailable in private/webview contexts */ }
}
function startNavigationResize(event: PointerEvent) {
  if (event.button !== 0) return;
  event.preventDefault();
  navigationResizeStartX = event.clientX;
  navigationResizeStartWidth = navigationWidth.value;
  document.body.classList.add('lf-is-resizing');
  window.addEventListener('pointermove', handleNavigationResize);
  window.addEventListener('pointerup', stopNavigationResize, { once: true });
}
function handleNavigationResize(event: PointerEvent) {
  navigationWidth.value = clampNavigationWidth(navigationResizeStartWidth + event.clientX - navigationResizeStartX);
}
function resizeNavigationBy(delta: number) {
  navigationWidth.value = clampNavigationWidth(navigationWidth.value + delta);
  persistNavigationWidth();
}
function stopNavigationResize() {
  if (navigationWidth.value !== navigationResizeStartWidth) persistNavigationWidth();
  document.body.classList.remove('lf-is-resizing');
  window.removeEventListener('pointermove', handleNavigationResize);
  window.removeEventListener('pointerup', stopNavigationResize);
}
onMounted(restoreNavigationWidth);
watch(() => auth.user?.id, restoreNavigationWidth);
onBeforeUnmount(stopNavigationResize);
function goMissions() {
  searchOpen.value = false;
  accountMenuOpen.value = false;
  router.push({ name: isResearcher.value ? 'lessonforge-researcher-reviews' : 'lessonforge-missions' });
}
function goNew() { searchOpen.value = false; accountMenuOpen.value = false; router.push({ name: 'lessonforge-new' }); }
function goMission(id: string) {
  searchOpen.value = false;
  accountMenuOpen.value = false;
  router.push({ name: isResearcher.value ? 'lessonforge-researcher-review' : 'lessonforge-mission', params: { missionId: id } });
}
function goSettings() { searchOpen.value = false; accountMenuOpen.value = false; router.push({ name: 'ai-credentials' }); }
function openSearch() { searchQuery.value = ''; searchOpen.value = true; }
function closeSearch() { searchOpen.value = false; }
async function handleLogout() {
  accountMenuOpen.value = false;
  try { await auth.logout(); }
  finally { await router.replace({ name: 'lessonforge-login' }); }
}
</script>
