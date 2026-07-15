import type { ApiResponse } from './health';
import { http } from './http';

export interface TeacherOption {
  id: number;
  username: string;
  displayName: string;
}

export interface CourseOption {
  id: number;
  courseCode: string;
  courseName: string;
  description?: string | null;
}

export interface ClassGroupOption {
  id: number;
  courseId: number;
  courseName: string;
  className: string;
  cohort?: string | null;
  studentCount: number;
}

export interface CollaborationReferenceData {
  teachers: TeacherOption[];
  leaders: TeacherOption[];
  students: TeacherOption[];
  courses: CourseOption[];
  classes: ClassGroupOption[];
}

export interface ClassMembership {
  id: number;
  classId: number;
  studentId: number;
  username: string;
  displayName: string;
  createdAt: string;
}

export interface CreateCoursePayload {
  courseCode: string;
  courseName: string;
  description?: string;
}

export interface CreateClassGroupPayload {
  className: string;
  cohort?: string;
  studentCount?: number;
}

export async function getCollaborationReferenceData() {
  const response = await http.get<ApiResponse<CollaborationReferenceData>>(
    '/api/v1/collaboration/reference-data',
  );
  return response.data.data;
}

export async function listCourses() {
  const response = await http.get<ApiResponse<CourseOption[]>>('/api/v1/courses');
  return response.data.data;
}

export async function createCourse(payload: CreateCoursePayload) {
  const response = await http.post<ApiResponse<CourseOption>>('/api/v1/courses', payload);
  return response.data.data;
}

export async function listClassGroups(courseId?: number) {
  const response = await http.get<ApiResponse<ClassGroupOption[]>>('/api/v1/classes', {
    params: courseId ? { courseId } : undefined,
  });
  return response.data.data;
}

export async function createClassGroup(courseId: number, payload: CreateClassGroupPayload) {
  const response = await http.post<ApiResponse<ClassGroupOption>>(
    `/api/v1/courses/${courseId}/classes`,
    payload,
  );
  return response.data.data;
}

export async function listClassMembers(classId: number) {
  const response = await http.get<ApiResponse<ClassMembership[]>>(`/api/v1/classes/${classId}/members`);
  return response.data.data;
}

export async function addClassMember(classId: number, studentId: number) {
  const response = await http.post<ApiResponse<ClassMembership>>(`/api/v1/classes/${classId}/members`, { studentId });
  return response.data.data;
}

export async function removeClassMember(classId: number, studentId: number) {
  await http.delete<ApiResponse<void>>(`/api/v1/classes/${classId}/members/${studentId}`);
}
