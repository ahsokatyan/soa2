package ru.itmo.soa.hr;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;
import jakarta.servlet.Filter;
public class Initializer extends AbstractAnnotationConfigDispatcherServletInitializer {
    protected Class<?>[] getRootConfigClasses() { return null; }
    protected Class<?>[] getServletConfigClasses() { return new Class<?>[]{WebConfig.class}; }
    protected String[] getServletMappings() { return new String[]{"/"}; }
    protected Filter[] getServletFilters() { return new Filter[]{new HttpsFilter()}; }
}
