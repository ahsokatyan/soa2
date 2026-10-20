package ru.itmo.soa.hr;

import org.springframework.stereotype.Component;
import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.logging.*;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

@Component
public class WorkerClient {
    private final HttpClient client;
    private final String base,token;
    public WorkerClient() {
        base=Settings.get("WORKER_URL","https://localhost:8443/workers/api").replaceAll("/+$","");
        if(!"https".equalsIgnoreCase(URI.create(base).getScheme())) throw new IllegalStateException("Worker URL must use HTTPS");
        token=Settings.get("INTERNAL_TOKEN","");
        if(token.isBlank()) throw new IllegalStateException("SOA_INTERNAL_TOKEN is required");
        try {
            var store=KeyStore.getInstance("PKCS12");
            try(var input=Files.newInputStream(Path.of(Settings.get("TRUSTSTORE",".runtime/certs/truststore.p12")))) { store.load(input,Settings.get("STORE_PASSWORD","local-lab-password").toCharArray()); }
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
            var ssl=SSLContext.getInstance("TLS"); ssl.init(null,trust.getTrustManagers(),null);
            client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch(Exception e) { throw new IllegalStateException("Cannot initialize Worker TLS truststore",e); }
    }
    public <T> T post(String path,Class<T> type,String root) {
        try {
            var request=HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).header("Accept","application/xml")
                .header("X-Internal-Token",token).POST(HttpRequest.BodyPublishers.noBody()).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
            String contentType=response.headers().firstValue("Content-Type").orElse("");
            if(!contentType.toLowerCase().startsWith("application/xml")) throw upstream();
            if(response.statusCode() >= 200 && response.statusCode() < 300) return Xml.read(response.body(),type,root);
            if(response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 409 || response.statusCode() == 422) {
                var error=Xml.read(response.body(),ErrorBody.class,"error");
                throw new ApiException(response.statusCode(),error.code,error.message,error.violations);
            }
            throw upstream();
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw upstream(); }
        catch(java.io.IOException e) { Logger.getLogger(WorkerClient.class.getName()).log(Level.WARNING,"Worker service unavailable",e); throw upstream(); }
        catch(ApiException e) { if(e.code.equals("MALFORMED_XML")) throw upstream(); throw e; }
    }
    private ApiException upstream() { return new ApiException(502,"WORKER_SERVICE_UNAVAILABLE","Worker-сервис недоступен или вернул некорректный ответ"); }
}
