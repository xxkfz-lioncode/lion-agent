package com.lion.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lion Agent 智能问答系统启动类
 */
@SpringBootApplication
@MapperScan("com.lion.agent.mapper")
public class LionAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LionAgentApplication.class, args);
    }
}
