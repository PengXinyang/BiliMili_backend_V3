package com.bilimili.buaa13;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 bootstrap 在 k8s profile 下的加载结果，不连接外部 Nacos。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SPRING_PROFILES_ACTIVE=k8s", "NACOS_CONFIG_ENABLED=false", "NACOS_DISCOVERY_ENABLED=false",
        "spring.cloud.discovery.enabled=false", "spring.cloud.service-registry.auto-registration.enabled=false",
        "POD_IP=10.244.0.42"
})
class KubernetesConfigurationTest {
    @Autowired Environment environment;

    @Test void clusterAddressesAndPodIpOverrideLocalDefaults() {
        assertTrue(java.util.Arrays.asList(environment.getActiveProfiles()).contains("k8s"));
        assertEquals("nacos:8848", environment.getProperty("spring.cloud.nacos.discovery.server-addr"));
        assertEquals("nacos:8848", environment.getProperty("spring.cloud.nacos.config.server-addr"));
        assertEquals("10.244.0.42", environment.getProperty("spring.cloud.nacos.discovery.ip"));
        assertEquals("http://message-service:8084", environment.getProperty("bilimili.message-url"));
        assertEquals("ws://message-service:8084", environment.getProperty("bilimili.message-ws-url"));
        assertEquals("reactive", environment.getProperty("spring.main.web-application-type"));
        assertEquals("false", environment.getProperty("spring.cloud.gateway.discovery.locator.enabled"));
    }
}
