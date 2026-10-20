package ru.itmo.soa.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import ru.itmo.soa.common.Settings;

@RestController
public class ClientConfig {
    @GetMapping(value={"/docs/openapi.yaml","/docs/internal-api.yaml"},produces="application/yaml;charset=UTF-8")
    public ResponseEntity<byte[]> openapi(HttpServletRequest request) throws Exception {
        String file=request.getRequestURI().endsWith("/internal-api.yaml") ? "internal-api.yaml" : "openapi.yaml";
        String publicWorker=Settings.get("PUBLIC_WORKER_URL","https://localhost:8443/workers/api");
        String publicHr=Settings.get("PUBLIC_HR_URL",new URI(request.getScheme(),null,request.getServerName(),request.getServerPort(),request.getContextPath() + "/api",null,null).toString());
        try(var input=request.getServletContext().getResourceAsStream("/docs/" + file)) {
            String yaml=new String(input.readAllBytes(),StandardCharsets.UTF_8)
                .replace("https://localhost:8443/workers/api",publicWorker)
                .replace("https://localhost:8444/api",publicHr);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).body(yaml.getBytes(StandardCharsets.UTF_8));
        }
    }
    @GetMapping(value="/",produces="text/html;charset=UTF-8")
    public ResponseEntity<byte[]> index(HttpServletRequest request) throws Exception {
        try(var input=request.getServletContext().getResourceAsStream("/index.html")) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(input.readAllBytes());
        }
    }
    @GetMapping(value="/client-config.js",produces="application/javascript")
    public ResponseEntity<String> config() throws Exception {
        var values=Map.of("workerBase",Settings.get("PUBLIC_WORKER_URL","https://localhost:8443/workers/api"),"hrBase","/api");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("window.SOA_CONFIG = " + new ObjectMapper().writeValueAsString(values) + ";");
    }
}
