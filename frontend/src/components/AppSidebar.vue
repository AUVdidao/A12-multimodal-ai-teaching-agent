<template>
  <el-menu :default-active="activePath" router class="app-sidebar">
    <el-menu-item index="/">
      <el-icon><House /></el-icon>
      <span>首页</span>
    </el-menu-item>
    <el-menu-item index="/projects">
      <el-icon><Folder /></el-icon>
      <span>项目管理</span>
    </el-menu-item>
    <el-menu-item index="/requirements">
      <el-icon><EditPen /></el-icon>
      <span>需求输入</span>
    </el-menu-item>
    <el-menu-item index="/dialog">
      <el-icon><ChatDotRound /></el-icon>
      <span>智能澄清</span>
    </el-menu-item>
    <el-menu-item index="/materials">
      <el-icon><UploadFilled /></el-icon>
      <span>资料上传</span>
    </el-menu-item>
    <el-menu-item index="/intent">
      <el-icon><Aim /></el-icon>
      <span>教学意图</span>
    </el-menu-item>
    <el-menu-item index="/plan">
      <el-icon><Tickets /></el-icon>
      <span>生成方案</span>
    </el-menu-item>
    <el-menu-item index="/preview">
      <el-icon><View /></el-icon>
      <span>结果预览</span>
    </el-menu-item>
    <el-menu-item index="/export">
      <el-icon><Download /></el-icon>
      <span>文件导出</span>
    </el-menu-item>
  </el-menu>
</template>

<script setup lang="ts">
import {
  Aim,
  ChatDotRound,
  Download,
  EditPen,
  Folder,
  House,
  Tickets,
  UploadFilled,
  View,
} from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const activePath = computed(() => route.path);
</script>
