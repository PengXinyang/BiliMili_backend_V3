package com.bilimili.buaa13.config;

import com.bilimili.buaa13.config.filter.JwtAuthenticationTokenFilter;
import com.bilimili.buaa13.tools.JsonWebTokenTool;
import com.bilimili.buaa13.tools.RedisTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用实际 SecurityConfig 和 JWT 过滤器；不启动数据库及服务发现。 */
@SpringJUnitWebConfig(SecurityConfigurationTest.TestConfig.class)
class SecurityConfigurationTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void publicEndpointRemainsAccessible() throws Exception {
        mvc.perform(get("/article/get")).andExpect(status().isOk());
    }
    @Test void anonymousProtectedAndInternalRequestsAreRejected() throws Exception {
        mvc.perform(get("/protected")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/message/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/personal/info")).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedRequestCanReachController() throws Exception {
        mvc.perform(get("/protected").with(user("demo"))).andExpect(status().isOk());
    }
    @Test void invalidBearerIsRejectedByActualJwtFilter() throws Exception {
        mvc.perform(get("/protected").header("Authorization", "Bearer invalid-test")).andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthenticationTokenFilter.class, ProbeController.class})
    static class TestConfig {
        @Bean UserDetailsService userDetailsService() { return mock(UserDetailsService.class); }
        @Bean JsonWebTokenTool jsonWebTokenTool() { return mock(JsonWebTokenTool.class); }
        @Bean RedisTemplate<String,Object> redisTemplate() { return mock(RedisTemplate.class); }
        @Bean RedisTool redisTool() { return mock(RedisTool.class); }
    }
    @RestController
    static class ProbeController {
        @RequestMapping({"/protected", "/internal/message/session", "/user/personal/info", "/article/get"})
        String probe() { return "ok"; }
    }
}
