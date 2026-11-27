package com.bilimili.buaa13.config;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("local-storage")
public class LocalMediaConfig implements WebMvcConfigurer {
    @Value("${storage.local-root:/data/media}") private String root;
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(root).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/media/**").addResourceLocations(location);
    }
}
