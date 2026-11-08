package com.aichat.Config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.aichat.mcp.McpProperties;

/** 打开 app.mcp.* 的配置绑定。 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {
}
