package com.vyom.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Port Resolution Configuration Tests")
public class PortResolutionConfigurationTest {

    private String rawServerPortExpression;

    @BeforeEach
    void setUp() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        assertThat(props).isNotNull();
        rawServerPortExpression = props.getProperty("server.port");
        assertThat(rawServerPortExpression).isNotNull();
    }

    @Test
    @DisplayName("Scenario 1: Default configuration without overrides resolves to 8080")
    void testDefaultPortResolution() {
        MockEnvironment env = new MockEnvironment();
        String resolvedPort = env.resolvePlaceholders(rawServerPortExpression);
        assertThat(resolvedPort).isEqualTo("8080");
    }

    @Test
    @DisplayName("Scenario 2: Generic PORT=3000 does NOT override backend port (stays 8080)")
    void testGenericPortDoesNotOverrideBackendPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PORT", "3000");
        String resolvedPort = env.resolvePlaceholders(rawServerPortExpression);
        // Generic PORT=3000 must NOT override backend port
        assertThat(resolvedPort).isEqualTo("8080");
    }

    @Test
    @DisplayName("Scenario 3: Explicit SERVER_PORT overrides default port")
    void testServerPortOverride() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("SERVER_PORT", "9090");
        String resolvedPort = env.resolvePlaceholders(rawServerPortExpression);
        assertThat(resolvedPort).isEqualTo("9090");
    }

    @Test
    @DisplayName("Scenario 4: Explicit BACKEND_PORT takes precedence over SERVER_PORT and default")
    void testBackendPortPrecedenceOverServerPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("BACKEND_PORT", "7070");
        env.setProperty("SERVER_PORT", "9090");
        env.setProperty("PORT", "3000");
        String resolvedPort = env.resolvePlaceholders(rawServerPortExpression);
        assertThat(resolvedPort).isEqualTo("7070");
    }
}
