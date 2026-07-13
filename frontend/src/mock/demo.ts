export type ProjectStage =
  | 'REQUIREMENT_CLARIFYING'
  | 'MATERIAL_ANALYZING'
  | 'INTENT_CONFIRMED'
  | 'DRAFT_READY';

export interface DemoProject {
  id: number;
  projectName: string;
  subtitle: string;
  courseName: string;
  textbook: string;
  chapterTitle: string;
  targetStudents: string;
  lessonDuration: string;
  mode: string;
  status: ProjectStage;
  progress: number;
  updatedAt: string;
  nextTask: string;
  accent: 'purple' | 'green' | 'orange' | 'blue' | 'red' | 'cyan';
}

export interface DemoMaterial {
  name: string;
  type: string;
  size: string;
  purpose: string;
  status: '解析完成' | '解析中' | '待解析' | '解析失败';
  uploadedAt: string;
}

export interface DemoKnowledge {
  score: number;
  title: string;
  content: string;
  source: string;
  location: string;
  keywords: string[];
}

export const demoProjects: DemoProject[] = [
  {
    id: 1,
    projectName: '人工智能基础概念与应用',
    subtitle: '理解人工智能的基本概念、发展历程与典型应用',
    courseName: '人工智能基础',
    textbook: '人工智能导论课程大纲',
    chapterTitle: '人工智能的基本概念与发展历程',
    targetStudents: '大学本科一年级',
    lessonDuration: '2 课时（90 分钟）',
    mode: '标准模式',
    status: 'REQUIREMENT_CLARIFYING',
    progress: 68,
    updatedAt: '2025-05-27 14:32',
    nextTask: '完善教学需求',
    accent: 'purple',
  },
  {
    id: 2,
    projectName: '初中生物 · 细胞的结构',
    subtitle: '认识细胞的基本结构与功能',
    courseName: '初中生物',
    textbook: '七年级下册',
    chapterTitle: '细胞的结构',
    targetStudents: '初一学生（约 90 人）',
    lessonDuration: '1 课时（45 分钟）',
    mode: '质量优先',
    status: 'MATERIAL_ANALYZING',
    progress: 42,
    updatedAt: '2025-05-26 14:12',
    nextTask: '上传课堂实录视频',
    accent: 'green',
  },
  {
    id: 3,
    projectName: '高中数学 · 立体几何',
    subtitle: '空间几何体的结构与证明',
    courseName: '高中数学',
    textbook: '必修第二册',
    chapterTitle: '立体几何',
    targetStudents: '高一学生（约 100 人）',
    lessonDuration: '2 课时（90 分钟）',
    mode: '标准模式',
    status: 'INTENT_CONFIRMED',
    progress: 35,
    updatedAt: '2025-05-25 20:44',
    nextTask: '构建知识图谱',
    accent: 'orange',
  },
  {
    id: 4,
    projectName: '初中化学 · 氧化还原反应',
    subtitle: '氧化还原反应的概念与应用',
    courseName: '初中化学',
    textbook: '九年级上册',
    chapterTitle: '氧化还原反应',
    targetStudents: '初三学生（约 110 人）',
    lessonDuration: '1 课时（45 分钟）',
    mode: '经济模式',
    status: 'MATERIAL_ANALYZING',
    progress: 22,
    updatedAt: '2025-05-25 18:05',
    nextTask: '添加核心资料',
    accent: 'blue',
  },
  {
    id: 5,
    projectName: '高中语文 · 古诗词鉴赏',
    subtitle: '诗词意境与表达技巧',
    courseName: '高中语文',
    textbook: '选择性必修下册',
    chapterTitle: '古诗词鉴赏',
    targetStudents: '高二学生（约 85 人）',
    lessonDuration: '2 课时（90 分钟）',
    mode: '标准模式',
    status: 'DRAFT_READY',
    progress: 100,
    updatedAt: '2025-05-24 21:30',
    nextTask: '检查生成内容',
    accent: 'red',
  },
];

export const requirementFields = [
  ['课程主题', '人工智能基础概念与应用', true],
  ['教学目标', '理解概念、掌握典型应用', true],
  ['授课对象', '大学一年级计算机专业', true],
  ['基础水平', '有编程基础，对 AI 了解不多', true],
  ['课时长度', '2 课时', true],
  ['重点难点', 'AI 定义、发展历程、典型应用', true],
  ['教学风格', '活泼，案例为主', true],
  ['互动设计', '课堂问答互动', true],
  ['输出内容', 'PPT 课件、Word 教案、互动内容', true],
] as const;

export const demoMaterials: DemoMaterial[] = [
  { name: '人工智能基础（第3版）.pdf', type: 'PDF', size: '42.6 MB', purpose: '教材依据', status: '解析完成', uploadedAt: '今天 10:30' },
  { name: 'AI+教育应用案例集.pptx', type: 'PPTX', size: '28.3 MB', purpose: '案例素材', status: '解析完成', uploadedAt: '今天 10:28' },
  { name: '机器学习流程图.png', type: 'PNG', size: '1.2 MB', purpose: '图片素材', status: '解析完成', uploadedAt: '今天 10:22' },
  { name: '深度学习基础讲解视频.mp4', type: 'MP4', size: '156.8 MB', purpose: '知识补充', status: '解析中', uploadedAt: '今天 10:15' },
  { name: 'AI伦理与安全综述.docx', type: 'DOCX', size: '2.1 MB', purpose: '教材依据', status: '待解析', uploadedAt: '今天 10:10' },
  { name: 'ChatGPT 使用指南.pdf', type: 'PDF', size: '18.7 MB', purpose: '案例素材', status: '解析失败', uploadedAt: '今天 10:05' },
];

export const demoKnowledge: DemoKnowledge[] = [
  {
    score: 95,
    title: '过拟合的定义与解决方法',
    content: '过拟合是模型在训练数据上表现良好，但在新数据上泛化能力差的现象。解决方法包括增加训练数据、正则化、降低模型复杂度和交叉验证。',
    source: '机器学习基础（第2版）.pdf',
    location: '第5章，页码 156-158',
    keywords: ['过拟合', '泛化', '正则化', '交叉验证'],
  },
  {
    score: 88,
    title: '防止过拟合的正则化技术',
    content: 'L1 与 L2 正则化通过对权重施加约束降低模型复杂度，使训练过程更加稳定。',
    source: '统计学习方法.pdf',
    location: '第3章，页码 89-92',
    keywords: ['正则化', 'L1', 'L2', '权重惩罚'],
  },
  {
    score: 82,
    title: '过拟合与欠拟合的对比',
    content: '过拟合通常表现为训练误差低、测试误差高；欠拟合则表现为模型复杂度不足，训练误差和测试误差都较高。',
    source: '人工智能导论.pdf',
    location: '第4章，页码 112-114',
    keywords: ['过拟合', '欠拟合', '模型复杂度'],
  },
];

export function getDemoProject(projectId?: string | number) {
  const id = Number(projectId || 1);
  return demoProjects.find((project) => project.id === id) || demoProjects[0];
}

export function stageLabel(status: ProjectStage | string) {
  const labels: Record<string, string> = {
    CREATED: '需求澄清中',
    REQUIREMENT_CLARIFYING: '需求澄清中',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料解析中',
    MATERIAL_ANALYZING: '资料解析中',
    INTENT_CONFIRMED: '意图已确认',
    GENERATED: '内容已生成',
    FINALIZED: '已定稿',
    DRAFT_READY: '已定稿',
  };
  return labels[status] || status;
}

export function projectRoute(projectId?: string | number, target = 'overview') {
  const id = projectId || 1;
  if (target === 'overview') return `/projects/${id}`;
  if (target === 'requirements') return `/projects/${id}/requirements`;
  return `/${target}?projectId=${id}`;
}
