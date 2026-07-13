<template>
  <div class="a12-shell">
    <aside class="a12-sidebar">
      <div class="brand">
        <div class="brand-mark">A</div>
        <div>
          <strong>A12 教学智能体</strong>
          <span>多模态 AI 互动式教学</span>
        </div>
      </div>

      <nav class="nav">
        <RouterLink class="nav-main active" to="/summary"><span>⌂</span>工作台</RouterLink>

        <p>教学项目</p>
        <RouterLink to="/projects"><span>□</span>教学项目</RouterLink>
        <RouterLink to="/summary"><span>☑</span>我的任务 <b>12</b></RouterLink>
        <RouterLink to="/materials"><span>◷</span>最近访问</RouterLink>
        <RouterLink to="/knowledge"><span>▣</span>回收站</RouterLink>

        <p>资源中心</p>
        <RouterLink to="/materials"><span>▧</span>资料库</RouterLink>
        <RouterLink to="/knowledge"><span>⊞</span>知识库</RouterLink>
        <RouterLink to="/intent"><span>⌘</span>模板中心</RouterLink>

        <p>智能工具</p>
        <RouterLink to="/summary"><span>✣</span>AI 助手</RouterLink>
        <RouterLink to="/materials"><span>⌁</span>教案分析</RouterLink>
        <RouterLink to="/intent"><span>☰</span>学情洞察</RouterLink>
      </nav>

      <div class="health">
        <span>系统状态</span>
        <strong><i /> 服务正常</strong>
      </div>
    </aside>

    <section class="a12-main">
      <header class="topbar">
        <h1>{{ topTitle }}</h1>
        <div class="top-actions">
          <label class="search">
            <span>⌕</span>
            <input placeholder="搜索项目、资料、知识..." />
            <kbd>⌘K</kbd>
          </label>
          <button class="icon-btn has-badge">♢<b>8</b></button>
          <button class="icon-btn">?</button>
          <button class="user"><span>张</span><strong>张老师</strong><i>⌄</i></button>
        </div>
      </header>

      <main class="content">
        <RequirementSummary v-if="page === 'summary'" />
        <MaterialWorkspace v-else-if="page === 'materials'" />
        <KnowledgeRetrieval v-else-if="page === 'knowledge'" />
        <TeachingIntent v-else />
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent } from 'vue';
import { RouterLink, useRoute } from 'vue-router';

const route = useRoute();
const page = computed(() => {
  if (route.path.includes('materials')) return 'materials';
  if (route.path.includes('knowledge')) return 'knowledge';
  if (route.path.includes('intent')) return 'intent';
  return 'summary';
});
const topTitle = computed(() => {
  if (page.value === 'knowledge') return '本地知识检索';
  if (page.value === 'intent') return '教学意图确认';
  return '教师工作台';
});

const RequirementSummary = defineComponent({
  name: 'RequirementSummary',
  setup() {
    const sections = [
      { tone: 'violet', icon: '▣', title: '课程基础信息', desc: '课程的基本信息与面向对象', lines: ['课程名称： 人工智能基础概念与应用', '课程类型： 本科通识课', '授课对象： 全校本科生（非计算机专业）', '学时安排： 16 学时（8 次课，每次 2 学时）'] },
      { tone: 'green', icon: '◎', title: '教学目标', desc: '通过本课程希望学生达成的知识、能力与素养目标', lines: ['理解人工智能的基本概念、发展历程与核心技术', '掌握机器学习、深度学习的基本原理与典型应用', '能够分析现实场景中的 AI 应用案例并提出见解', '培养学生的数字素养与负责任的 AI 使用意识'] },
      { tone: 'blue', icon: '▧', title: '内容组织', desc: '课程内容结构与重点安排', lines: ['模块一： 人工智能导论', '模块二： 机器学习基础', '模块三： 深度学习初步', '模块四： 自然语言处理与计算机视觉', '模块五： AI 伦理与未来展望'] },
      { tone: 'orange', icon: '◌', title: '互动设计', desc: '课堂互动、讨论与实践活动设计', lines: ['每节课设置随堂问答与知识点投票', '2 次小组讨论与案例分析汇报', '1 次动手实践： 使用开源工具完成 AI 应用体验', '期末项目： 结合生活场景设计 AI 应用方案'] },
      { tone: 'purple', icon: '◫', title: '输出内容', desc: '期望生成的教学资源与材料', lines: ['教案与课件（PPT）', '学生讲义与阅读材料', '课堂互动题库与讨论题目', '作业与项目模板、评分标准'] },
    ];
    return { sections };
  },
  template: `
    <section class="page summary-page">
      <button class="back">‹ 返回</button>
      <div class="title-row">
        <div><h2>需求摘要确认 <span class="tag violet">待确认</span></h2><p><b>项目：</b> 人工智能基础概念与应用 <em /> <b>创建时间：</b> 2025年5月27日 14:32</p></div>
      </div>
      <div class="two-col summary-grid">
        <section class="panel summary-panel">
          <article v-for="section in sections" :key="section.title" class="summary-section">
            <div class="section-id">
              <span class="round-icon" :class="section.tone">{{ section.icon }}</span>
              <div><h3>{{ section.title }}</h3><p>{{ section.desc }}</p></div>
            </div>
            <div class="editable-box">
              <button>编辑</button>
              <ul><li v-for="line in section.lines" :key="line">{{ line }}</li></ul>
            </div>
          </article>
          <p class="hint">以上内容由 AI 基于您提供的原始需求生成，请确认或修改后再继续。</p>
        </section>
        <aside class="side-stack">
          <section class="panel confirm-card">
            <h3>确认状态</h3>
            <div class="warn-box"><strong>待确认</strong><span>请确认需求摘要，确认后将进入教学设计阶段</span></div>
            <h3>需求来源</h3>
            <dl>
              <div><dt>来源文档</dt><dd>《人工智能基础概念与应用_教学需求V1.0》 <a>查看</a></dd></div>
              <div><dt>文档版本</dt><dd>v1.0</dd></div>
              <div><dt>上传时间</dt><dd>2025年5月26日 18:45</dd></div>
              <div><dt>生成模式</dt><dd>标准模式 <span class="mini-tag">可调整</span></dd></div>
              <div><dt>更新时间</dt><dd>2025年5月27日 14:32</dd></div>
            </dl>
            <h3 class="with-line">操作</h3>
            <div class="action-row"><button class="ghost">▧ 保存修改</button><button class="primary">✓ 确认教学需求</button></div>
            <p class="lock">确认后将锁定当前需求并进入下一阶段，后续修改需重新发起</p>
          </section>
          <section class="panel next-card">
            <h3>▣ 下一阶段解锁预告</h3>
            <p>确认后将为您解锁以下内容</p>
            <ul><li>教学设计方案生成（课程大纲、教学流程、活动设计）</li><li>教学资源智能生成（课件、讲义、题库、案例等）</li><li>学情分析与个性化建议</li></ul>
            <strong>预计耗时 2-5 分钟，完成后可在「教学项目」中查看进度</strong>
          </section>
        </aside>
      </div>
    </section>
  `,
});

const MaterialWorkspace = defineComponent({
  name: 'MaterialWorkspace',
  setup() {
    const files = [
      ['人工智能基础（第3版）.pdf', 'PDF', '42.6 MB', '教材依据', '解析完成', '今天 10:30'],
      ['AI+教育应用案例集.pptx', 'PPTX', '28.3 MB', '案例素材', '解析完成', '今天 10:28'],
      ['机器学习流程图.png', 'PNG', '1.2 MB', '图片素材', '解析完成', '今天 10:22'],
      ['深度学习基础讲解视频.mp4', 'MP4', '156.8 MB', '知识补充', '解析中', '今天 10:15'],
      ['AI 伦理与安全综述.docx', 'DOCX', '2.1 MB', '教材依据', '待解析', '今天 10:10'],
      ['ChatGPT 使用指南.pdf', 'PDF', '18.7 MB', '案例素材', '解析失败', '今天 10:05'],
      ['神经网络结构示意图.jpg', 'JPG', '3.6 MB', '图片素材', '待解析', '今天 09:58'],
      ['课堂讨论参考问题集.txt', 'TXT', '0.8 MB', '知识补充', '解析完成', '今天 09:50'],
    ];
    return { files };
  },
  template: `
    <section class="page material-page">
      <div class="crumbs">教学项目 <span>›</span> 人工智能基础概念与应用 <span>›</span> 参考资料与原理解析</div>
      <div class="page-top"><div><h2>人工智能基础概念与应用</h2><p>参考资料与原理解析</p></div><div><button class="ghost small">项目概览</button><button class="ghost small">← 返回工作台</button></div></div>
      <ol class="stepper five"><li class="active"><b>1</b><strong>上传资料</strong><span>导入课程相关资料</span></li><li><b>2</b><strong>标记用途</strong><span>标注资料使用场景</span></li><li><b>3</b><strong>解析摘要</strong><span>AI 解析与摘要生成</span></li><li><b>4</b><strong>知识检索</strong><span>构建本项目知识索引</span></li><li><b>5</b><strong>意图确认</strong><span>确认纳入项目知识库</span></li></ol>
      <div class="material-grid">
        <section class="panel upload-box">
          <h3>上传资料</h3><p>支持文档、图片、音视频等多种格式，单个文件最大 200MB</p>
          <div class="drop"><span>↥</span><strong>拖拽文件到此处，或点击上传</strong><small>支持 PDF、PPT、DOCX、XLSX、TXT、MD、PNG、JPG、MP4 等格式</small><a>↪ 从资料库选择</a></div>
        </section>
        <section class="panel purpose-box"><h3>用途标签说明 <a>管理标签</a></h3><ul><li><b class="violet-dot" /> <strong>教材依据</strong><span>作为课程内容的理论依据或知识来源</span></li><li><b class="orange-dot" /> <strong>案例素材</strong><span>用于案例教学、课堂讨论或情境分析</span></li><li><b class="green-dot" /> <strong>图片素材</strong><span>用于图示说明、课件展示或视觉辅助</span></li><li><b class="blue-dot" /> <strong>知识补充</strong><span>扩展知识、背景信息或延伸阅读资料</span></li></ul></section>
        <section class="panel files-box">
          <h3>资料列表（8）</h3>
          <table><thead><tr><th>文件名称</th><th>类型</th><th>大小</th><th>用途</th><th>解析状态</th><th>上传时间</th><th>操作</th></tr></thead><tbody><tr v-for="f in files" :key="f[0]"><td><i class="file-icon">{{ f[1].slice(0,1) }}</i>{{ f[0] }}</td><td>{{ f[1] }}</td><td>{{ f[2] }}</td><td><span class="pill">{{ f[3] }}</span></td><td><span class="status" :class="{bad:f[4]==='解析失败', mid:f[4]==='解析中'}">{{ f[4] }}</span></td><td>{{ f[5] }}</td><td>⊙ ···</td></tr></tbody></table>
          <footer>共 8 项 <span>‹</span><b>1</b><span>›</span></footer>
        </section>
        <section class="panel analysis-box"><h3>解析摘要预览（人工智能基础（第3版）.pdf） <span class="status">解析完成</span></h3><h4>核心内容</h4><p>本书系统介绍了人工智能的基本概念、发展历程与核心技术，涵盖机器学习、深度学习、自然语言处理、计算机视觉等关键领域，并结合典型应用场景进行分析。</p><h4>知识点提炼</h4><div class="chips"><span>人工智能定义与发展</span><span>机器学习基本方法</span><span>监督学习 vs 无监督学习</span><span>深度学习原理</span><span>神经网络结构</span><span>模型训练与评估</span><span>典型应用场景</span></div><h4>适用场景</h4><div class="chips"><span>课堂讲授</span><span>知识讲解</span><span>概念梳理</span><span>课后阅读</span></div><a class="detail-link">查看完整解析 →</a></section>
      </div>
    </section>
  `,
});

const KnowledgeRetrieval = defineComponent({
  name: 'KnowledgeRetrieval',
  setup() {
    const results = [
      ['95%', '过拟合的定义与解决方法', '过拟合（Overfitting）是指模型在训练数据上表现良好，但在新数据上泛化能力差的现象。解决方法包括增加训练数据、使用正则化、降低模型复杂度、交叉验证与数据增强。', ['过拟合', '泛化', '正则化', '交叉验证']],
      ['88%', '防止过拟合的正则化技术', '正则化是防止过拟合的有效手段，常见方法包括 L1 正则化（Lasso）和 L2 正则化（Ridge）。', ['正则化', 'L1', 'L2', '权重惩罚']],
      ['82%', '过拟合与欠拟合的对比', '过拟合和欠拟合是模型训练中常见的两种问题。过拟合表现为模型复杂度过高，欠拟合表现为模型复杂度过低。', ['过拟合', '欠拟合', '模型复杂度', '偏差方差']],
    ];
    return { results };
  },
  template: `
    <section class="page knowledge-page">
      <button class="back">‹ 返回项目</button>
      <div class="knowledge-head"><h2>人工智能基础概念与应用 <span class="tag green">运行中</span></h2><p>在本地知识库中执行确定性检索，查找精确匹配的知识内容</p></div>
      <ol class="stepper four"><li><b>1</b><strong>项目配置</strong></li><li class="active"><b>2</b><strong>本地知识检索</strong></li><li><b>3</b><strong>生成与增强</strong></li><li><b>4</b><strong>应用与反馈</strong></li></ol>
      <div class="metric-row"><article class="panel metric"><span>▣</span><div><small>已索引资料</small><strong>128</strong><em>较昨日 +8</em></div></article><article class="panel metric"><span>▤</span><div><small>知识片段</small><strong>3,456</strong><em>较昨日 +156</em></div></article><article class="panel metric"><span>⌕</span><div><small>检索方式</small><strong>确定性检索</strong><em>关键词精确匹配</em></div></article></div>
      <section class="panel search-panel"><h3>确定性检索</h3><p>在本地知识库中精确匹配关键词，返回最相关的知识片段</p><div class="query"><label>⌕ <input value="机器学习中的过拟合是什么，如何解决？" /></label><button>执行检索</button></div><div class="filters"><button>全部资料⌄</button><button>精确匹配⌄</button><label><i /> 区分大小写</label><a>◷ 检索历史</a></div></section>
      <section class="results-top"><h3>检索结果 <span>（共 8 条）</span></h3><div><button>按相关度排序⌄</button><button>⇩ 导出结果</button></div></section>
      <div class="result-list"><article v-for="r in results" :key="r[0]" class="panel result"><div class="score"><strong>{{ r[0] }}</strong><span>匹配度</span></div><div><h3>{{ r[1] }}</h3><p>{{ r[2] }}</p><footer><span>来源资料： <b>机器学习基础（第2版）.pdf</b></span><span>第5章　页码 156-158</span><em>关键词：</em><b v-for="k in r[3]" :key="k">{{ k }}</b></footer></div></article></div>
    </section>
  `,
});

const TeachingIntent = defineComponent({
  name: 'TeachingIntent',
  template: `
    <section class="page intent-page">
      <ol class="stepper six"><li class="done"><b>✓</b><strong>M1 项目导入</strong></li><li class="active"><b>2</b><strong>教学意图确认</strong></li><li><b>3</b><strong>教学内容生成</strong></li><li><b>4</b><strong>教学方案生成</strong></li><li><b>5</b><strong>教学资源生成</strong></li><li><b>6</b><strong>方案预览与发布</strong></li></ol>
      <div class="two-col intent-grid">
        <main class="panel intent-editor">
          <div class="project-line"><span class="round-icon violet">▣</span><div><small>项目名称</small><h2>人工智能基础概念与应用 <button>✎</button></h2><p>面向大学本科一年级学生，理解人工智能的基本概念、发展历程与典型应用，建立初步的 AI 素养。</p></div></div>
          <section class="intent-block"><div><span class="round-icon violet">◎</span><h3>生成目标</h3><p>本项目希望达成的核心教学目标（可多选）</p></div><div class="checks"><label class="checked">知识理解</label><label class="checked">概念掌握</label><label class="checked">应用能力</label><label>思维提升</label><label>价值塑造</label></div></section>
          <section class="intent-block"><div><span class="round-icon blue">▤</span><h3>内容依据</h3><p>教学内容的主要来源与依据</p></div><div class="select-card">教育部高等学校人工智能专业教学指导分委员会《人工智能导论》课程大纲（2023）⌄<div class="tag-row"><span>中国新一代人工智能发展规划（2017） ×</span><span>斯坦福大学《AI 100》课程大纲 ×</span></div><button class="ghost small">＋ 添加依据</button></div></section>
          <section class="intent-block"><div><span class="round-icon green">●</span><h3>教学组织</h3><p>面向学生、学时安排与教学形式</p></div><div class="three-fields"><label>面向对象 <b>大学本科一年级⌄</b></label><label>总学时 <b>16 学时⌄</b></label><label>教学形式 <b>线上线下混合式教学⌄</b></label></div></section>
          <section class="intent-block"><div><span class="round-icon orange">◌</span><h3>输出类型</h3><p>期望产出的教学方案与资源</p></div><div class="checks"><label class="checked">教学大纲</label><label class="checked">教学PPT</label><label class="checked">课堂活动</label><label class="checked">习题与测评</label><label class="checked">案例库</label><label>参考资料</label></div></section>
          <section class="intent-block"><div><span class="round-icon gray">▤</span><h3>备注说明（可选）</h3></div><textarea placeholder="请输入补充说明，如教学重点、使用限制等（200字以内）" /><small>0/200</small></section>
          <div class="bottom-actions"><button class="ghost">▣ 保存草稿</button><button class="primary">✓ 确认教学意图</button></div>
        </main>
        <aside class="side-stack">
          <section class="panel intent-status"><h3>教学意图状态</h3><div class="warn-box"><strong>待确认</strong><span>请确认以上信息以继续生成教学内容</span></div><dl><div><dt>创建时间</dt><dd>2025-05-27 15:24</dd></div><div><dt>创建人</dt><dd>张老师</dd></div><div><dt>最后更新</dt><dd>2025-05-27 15:24</dd></div></dl></section>
          <section class="panel evidence"><h3>依据证据（3） <a>全部展开 ›</a></h3><article><b>1</b><div><h4>《人工智能导论》课程大纲（2023） <span>官方文件</span></h4><p><strong>匹配理由：</strong>明确了人工智能基础概念、发展历程与应用场景为课程核心内容，与项目目标高度一致。</p><a>查看更多 →</a></div></article><article><b>2</b><div><h4>中国新一代人工智能发展规划（2017） <span>政策文件</span></h4><p><strong>匹配理由：</strong>提供了人工智能发展的国家战略背景与应用方向，支撑课程的价值塑造目标。</p><a>查看更多 →</a></div></article><article><b>3</b><div><h4>斯坦福大学《AI 100》课程大纲 <span>课程资料</span></h4><p><strong>匹配理由：</strong>国际知名高校通识课程，内容体系完整，可作为教学内容组织与案例设计参考。</p><a>查看更多 →</a></div></article><footer>找不到合适依据？ <a>去资料库搜索 ⌕</a></footer></section>
        </aside>
      </div>
    </section>
  `,
});
</script>
