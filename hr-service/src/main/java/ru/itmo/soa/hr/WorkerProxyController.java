package ru.itmo.soa.hr;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Browser-facing Worker API on the same HTTPS origin as the UI. */
@RestController
public class WorkerProxyController {
    private final WorkerClient worker;
    public WorkerProxyController(WorkerClient worker) { this.worker=worker; }

    @RequestMapping(value={"/api/workers","/api/workers/{id}","/api/workers/salary/sum",
        "/api/workers/salary/count-less-than","/api/workers/salary/unique"},
        method={RequestMethod.GET,RequestMethod.HEAD,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE,RequestMethod.OPTIONS})
    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length() + "/api".length());
        var headers=new LinkedHashMap<String,String>();
        for(String name:List.of("Accept","Content-Type")) {
            String value=request.getHeader(name);
            if(value != null) headers.put(name,value);
        }
        var upstream=worker.forward(request.getMethod(),path,request.getQueryString(),headers,request.getInputStream().readAllBytes());
        var response=ResponseEntity.status(upstream.statusCode());
        for(String name:List.of("Content-Type","X-Total-Count","X-Total-Pages","Retry-After","Allow"))
            upstream.headers().firstValue(name).ifPresent(value -> response.header(name,value));
        upstream.headers().firstValue("Location").ifPresent(value -> response.header("Location",worker.publicLocation(value,request.getContextPath())));
        return response.body(upstream.body());
    }
}
