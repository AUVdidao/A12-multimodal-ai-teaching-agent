# LessonForge 阶段 A 数据库备份证据

## 备份目标

- 数据库容器：lessonforge-bridge-postgres-1
- 数据库：lessonforge
- PostgreSQL：16.14
- 备份格式：pg_dump custom format，压缩
- 备份文件：D:/pri_work/LessonForge-backups/lessonforge-baseline-20260911T122124Z.dump
- 文件用途：整改前回退/恢复证据；文件包含数据库敏感数据，必须保持私有，不提交、不对外分发。
- 未执行 restore。

## 生成证据

- pg_dump 退出码：0
- 文件大小：1,361,373 bytes
- SHA-256：F7A5820D0C9468DC272126D71B3530FF1FFEC67C63EB342BD7CFFF97F1766305
- 文件头：PGDMP
- archive created at：2026-09-11 12:21:25 UTC
- TOC entries：189
- compression：gzip
- format：CUSTOM
- dumped from database version：16.14
- dumped by pg_dump version：16.14

## 可验证性

使用现有 PostgreSQL 容器内的 pg_restore --list，通过标准输入读取该 dump，成功解析 archive header、TOC entries 和版本信息，未向容器文件系统写入文件。

第一次校验尝试使用了不接受的输入文件参数，返回参数错误；随后改用标准输入校验成功。该错误没有改变数据库、容器、volume 或备份文件内容。

## 安全边界

本阶段没有执行 migration、UPDATE、DELETE、DROP、TRUNCATE、restore、容器重启或替换，也没有输出 API key、Bearer token、密码、加密密文或完整连接串。

## 阶段 A 结论

备份：VERIFIED。

回退前置条件：满足。

阶段 B 允许的写入范围：仅可在目标确认后构建新镜像，并只应用缺失的前向 migration 011/012；若目标数据库、备份或 migration 校验出现异常，必须停止并报告 BLOCKED。
