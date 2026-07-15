package com.auvdidao.a12teachingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class A12TeachingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(A12TeachingAgentApplication.class, args);
    }
}
