<template>
  <img
    class="a12-asset-icon"
    :src="iconSources[name]"
    alt=""
    aria-hidden="true"
    :style="{ width: `${size}px`, height: `${size}px` }"
  />
</template>

<script lang="ts">
export type A12AssetIconName =
  | 'atom'
  | 'bell'
  | 'book'
  | 'check-circle'
  | 'clock'
  | 'document'
  | 'folder'
  | 'home'
  | 'info'
  | 'layers'
  | 'letter-a'
  | 'lightbulb'
  | 'math'
  | 'pencil'
  | 'plus-circle'
  | 'question-help'
  | 'search'
  | 'sparkle'
  | 'sprout'
  | 'target'
  | 'users'
  | 'flask';
</script>

<script setup lang="ts">
import atom from '@/assets/a12-ui/atom-icon.svg';
import bell from '@/assets/a12-ui/bell-icon.svg';
import book from '@/assets/a12-ui/book-icon.svg';
import checkCircle from '@/assets/a12-ui/check-circle-icon.svg';
import clock from '@/assets/a12-ui/clock-icon.svg';
import document from '@/assets/a12-ui/document-icon.svg';
import folder from '@/assets/a12-ui/folder-icon.svg';
import home from '@/assets/a12-ui/home-icon.svg';
import info from '@/assets/a12-ui/info-icon.svg';
import layers from '@/assets/a12-ui/layers-icon.svg';
import letterA from '@/assets/a12-ui/letter-a-icon.svg';
import lightbulb from '@/assets/a12-ui/lightbulb-icon.svg';
import math from '@/assets/a12-ui/math-icon.svg';
import pencil from '@/assets/a12-ui/pencil-icon.svg';
import plusCircle from '@/assets/a12-ui/plus-circle-icon.svg';
import questionHelp from '@/assets/a12-ui/question-help-icon.svg';
import search from '@/assets/a12-ui/search-icon.svg';
import sparkle from '@/assets/a12-ui/sparkle-icon.svg';
import sprout from '@/assets/a12-ui/sprout-icon.svg';
import target from '@/assets/a12-ui/target-icon.svg';
import users from '@/assets/a12-ui/users-icon.svg';
import flask from '@/assets/a12-ui/flask-icon.svg';

withDefaults(
  defineProps<{
    name: A12AssetIconName;
    size?: number;
  }>(),
  {
    size: 20,
  },
);

const iconSources: Record<A12AssetIconName, string> = {
  atom,
  bell,
  book,
  'check-circle': checkCircle,
  clock,
  document,
  folder,
  home,
  info,
  layers,
  'letter-a': letterA,
  lightbulb,
  math,
  pencil,
  'plus-circle': plusCircle,
  'question-help': questionHelp,
  search,
  sparkle,
  sprout,
  target,
  users,
  flask,
};
</script>

<style scoped>
.a12-asset-icon {
  display: block;
  flex: 0 0 auto;
  object-fit: contain;
}
</style>
