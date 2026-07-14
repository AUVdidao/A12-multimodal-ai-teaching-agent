package com.auvdidao.a12teachingagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "a12.security")
public class A12SecurityProperties {

    private boolean enabled = true;
    private int sessionHours = 12;
    private boolean demoSeedEnabled;
    private Demo demo = new Demo();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }

    public boolean isDemoSeedEnabled() {
        return demoSeedEnabled;
    }

    public void setDemoSeedEnabled(boolean demoSeedEnabled) {
        this.demoSeedEnabled = demoSeedEnabled;
    }

    public Demo getDemo() {
        return demo;
    }

    public void setDemo(Demo demo) {
        this.demo = demo;
    }

    public static class Demo {

        private String leaderPassword;
        private String teacherPassword;
        private String studentPassword;
        private String multiPassword;

        public String getLeaderPassword() {
            return leaderPassword;
        }

        public void setLeaderPassword(String leaderPassword) {
            this.leaderPassword = leaderPassword;
        }

        public String getTeacherPassword() {
            return teacherPassword;
        }

        public void setTeacherPassword(String teacherPassword) {
            this.teacherPassword = teacherPassword;
        }

        public String getStudentPassword() {
            return studentPassword;
        }

        public void setStudentPassword(String studentPassword) {
            this.studentPassword = studentPassword;
        }

        public String getMultiPassword() {
            return multiPassword;
        }

        public void setMultiPassword(String multiPassword) {
            this.multiPassword = multiPassword;
        }
    }
}
