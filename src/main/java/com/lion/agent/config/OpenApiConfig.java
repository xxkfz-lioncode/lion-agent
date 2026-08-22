package com.lion.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import io.swagger.v3.oas.models.info.Contact;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        // JWT安全Scheme名称
        String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                // 文档头部信息
                .info(new Info()
                        .title("项目接口文档")
                        .version("1.0.0")
                        .description("SpringBoot4.x OpenAPI3接口文档，生产环境请关闭文档")
                        .contact(new Contact()
                                .name("研发团队")
                                .email("dev@xxx.com")))
                // JWT认证配置，swagger-ui页面右上角可以填token
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                // 全局添加认证，所有接口默认带上Authorization请求头
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }
}