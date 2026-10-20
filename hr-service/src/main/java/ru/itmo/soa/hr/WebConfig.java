package ru.itmo.soa.hr;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
@Configuration @EnableWebMvc @ComponentScan("ru.itmo.soa.hr")
public class WebConfig implements WebMvcConfigurer {
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("/assets/");
        registry.addResourceHandler("/docs/**").addResourceLocations("/docs/");
        registry.addResourceHandler("/index.html").addResourceLocations("/");
    }
}
