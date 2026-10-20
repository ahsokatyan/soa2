package ru.itmo.soa.hr;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import ru.itmo.soa.common.*;
public class HttpsFilter implements Filter {
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        var http=(HttpServletRequest) request; var output=(HttpServletResponse) response;
        output.setCharacterEncoding("UTF-8");
        output.setHeader("X-Content-Type-Options","nosniff");
        if(!http.isSecure()) {
            output.setStatus(403); output.setContentType("application/xml;charset=UTF-8");
            output.getOutputStream().write(Xml.bytes(new ApiException(403,"HTTPS_REQUIRED","Доступ разрешён только по HTTPS").body(http.getRequestURI()))); return;
        }
        chain.doFilter(request,response);
    }
}
