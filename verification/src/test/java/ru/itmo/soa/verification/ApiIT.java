package ru.itmo.soa.verification;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.itmo.soa.verification.OpenApiContract.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.w3c.dom.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS) @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIT {
    private HttpClient client;
    private OpenApiContract contract;
    private final String worker=System.getProperty("worker.url","https://localhost:8443/workers/api");
    private final String hr=System.getProperty("hr.url","https://localhost:8444/api");
    private final String prefix="contract-" + UUID.randomUUID();
    private final List<String> ids=new ArrayList<>();
    private String a,b,c,d;
    private HttpResponse<byte[]> last;
    @BeforeAll void connect() throws Exception {
        var trust=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(Path.of(System.getProperty("test.truststore","../.runtime/certs/truststore.p12")))) { trust.load(input,System.getenv().getOrDefault("SOA_STORE_PASSWORD","local-lab-password").toCharArray()); }
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var ssl=SSLContext.getInstance("TLS"); ssl.init(null,managers.getTrustManagers(),null);
        client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).build(); contract=new OpenApiContract();
        var empty=request("GET","/workers",worker,"/workers",null,200);
        assertEquals("0",text(empty.getDocumentElement(),"totalElements"),"Run verification on an empty local database: tests must not change existing user data");
    }
    private String input(String name,String salary,int org) {
        return "<worker><name>" + name + "</name><coordinates><x>9223372036854775807</x><y>-100</y></coordinates>" + (salary == null ? "" : "<salary>" + salary + "</salary>") + "<startDate>2025-09-01</startDate><position>MANAGER</position><status>REGULAR</status><person><height>180.5</height><eyeColor>GREEN</eyeColor></person><organizationId>" + org + "</organizationId></worker>";
    }
    private Document request(String method,String route,String base,String path,String body,int expected) throws Exception { return request(method,route,base,path,body,expected,"application/xml",body == null ? null : "application/xml"); }
    private Document request(String method,String route,String base,String path,String body,int expected,String accept,String contentType) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(12)).header("Accept",accept);
        if(contentType != null) builder.header("Content-Type",contentType);
        last=client.send(builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected,last.statusCode(),method + " " + path + ": " + new String(last.body(),StandardCharsets.UTF_8));
        if(expected != 204) assertTrue(last.headers().firstValue("Content-Type").orElse("").startsWith("application/xml"));
        return contract.verify(method,route,last.statusCode(),last.body());
    }
    private Element get(String id) throws Exception { return request("GET","/workers/{worker-id}",worker,"/workers/" + id,null,200).getDocumentElement(); }
    private String create(String name,String salary,int org) throws Exception {
        var root=request("POST","/workers",worker,"/workers",input(name,salary,org),201).getDocumentElement();
        String id=text(root,"id"); ids.add(id); assertTrue(last.headers().firstValue("Location").orElse("").endsWith("/workers/" + id)); return id;
    }
    @Test @Order(1) void createAndRead() throws Exception {
        a=create(prefix + "-A","100",10); b=create(prefix + "-B","200",10); c=create(prefix + "-C","100",10); d=create(prefix + "-D",null,10);
        assertEquals(prefix + "-A",text(get(a),"name")); assertEquals(LocalDate.now().toString(),text(get(a),"creationDate"));
        assertNull(text(get(d),"salary"));
    }
    @Test @Order(2) void everyFilterAndCombination() throws Exception {
        var filters=new LinkedHashMap<String,String>();
        filters.put("id",a); filters.put("name",prefix + "-A"); filters.put("coordinates.x","9223372036854775807"); filters.put("coordinates.y","-100");
        filters.put("creationDate",text(get(a),"creationDate")); filters.put("salary","100"); filters.put("startDate","2025-09-01");
        filters.put("position","MANAGER"); filters.put("status","REGULAR"); filters.put("person.height","180.5"); filters.put("person.eyeColor","GREEN"); filters.put("organizationId","10");
        var params=new ArrayList<String>();
        for(var filter:filters.entrySet()) {
            String encoded=filter.getKey() + "=" + URLEncoder.encode(filter.getValue(),StandardCharsets.UTF_8); params.add(encoded);
            var page=request("GET","/workers",worker,"/workers?" + encoded,null,200).getDocumentElement();
            assertTrue(Long.parseLong(text(page,"totalElements")) >= 1,filter.getKey());
        }
        var page=request("GET","/workers",worker,"/workers?" + String.join("&",params),null,200).getDocumentElement(); assertEquals("1",text(page,"totalElements"));
        assertEquals("1",last.headers().firstValue("X-Total-Count").orElseThrow()); assertEquals("1",last.headers().firstValue("X-Total-Pages").orElseThrow());
        var none=request("GET","/workers",worker,"/workers?id=" + a + "&name=does-not-exist",null,200).getDocumentElement(); assertEquals("0",text(none,"totalElements")); assertEquals("0",text(none,"totalPages"));
    }
    @Test @Order(3) void sortingAndPagination() throws Exception {
        var page=request("GET","/workers",worker,"/workers?organizationId=10&sort=salary,desc&sort=name,asc&size=2&page=1",null,200).getDocumentElement();
        var workers=children(child(page,"workers"),"worker"); assertEquals(2,workers.size()); assertEquals(b,text(workers.get(0),"id")); assertEquals(a,text(workers.get(1),"id"));
        var next=request("GET","/workers",worker,"/workers?organizationId=10&sort=salary,desc&sort=name,asc&size=2&page=2",null,200).getDocumentElement();
        assertEquals(c,text(children(child(next,"workers"),"worker").get(0),"id")); assertEquals(d,text(children(child(next,"workers"),"worker").get(1),"id"));
        var beyond=request("GET","/workers",worker,"/workers?page=2147483647&size=100",null,200).getDocumentElement(); assertTrue(children(child(beyond,"workers"),"worker").isEmpty());
        for(String field:List.of("id","name","coordinates.x","coordinates.y","creationDate","salary","startDate","position","status","person.height","person.eyeColor","organizationId")) request("GET","/workers",worker,"/workers?sort=" + field + ",asc",null,200);
    }
    @Test @Order(4) void queryErrors() throws Exception {
        for(String query:List.of("page=0","size=101","size=0","page=abc","sort=nope,asc","sort=name,down","salary=0","salary=NaN","position=INVALID","coordinates.x=9223372036854775808","coordinates.y=-101","startDate=2026-02-30","person.height=Infinity","id=2147483648","name=","page=1&page=2","unexpected=1")) request("GET","/workers",worker,"/workers?" + query,null,400);
    }
    @Test @Order(5) void xmlAndValidationErrors() throws Exception {
        for(String bad:List.of("<worker>","<other/>",input("x","100",10).replace("2025-09-01","2026-02-30"),input("x","100",10).replace("<x>9223372036854775807</x>","<x>1.5</x>"),input("x","100",10).replace("MANAGER","ALIEN"),input("x","100",10).replace("<name>x</name>","<name>x</name><id>22</id>"),"<!DOCTYPE worker [<!ENTITY x SYSTEM 'file:///C:/Windows/win.ini'>]><worker><name>&x;</name></worker>")) request("POST","/workers",worker,"/workers",bad,400);
        for(String invalid:List.of("<worker/>",input("","100",10),input("x","0",10),input("x","-1",10),input("x","100",10).replace("<y>-100</y>","<y>-101</y>"),input("x","100",10).replace("180.5","0"),input("x","100",10).replace("<eyeColor>GREEN</eyeColor>",""))) {
            var error=request("POST","/workers",worker,"/workers",invalid,422).getDocumentElement(); assertNotNull(child(error,"violations"));
        }
        request("POST","/workers",worker,"/workers",input("x","100",999),404);
        request("POST","/workers",worker,"/workers","{}",415,"application/xml","application/json");
        request("GET","/workers",worker,"/workers",null,406,"application/json",null);
    }
    @Test @Order(6) void updateAndNullableFields() throws Exception {
        var before=get(a);
        var updated=request("PUT","/workers/{worker-id}",worker,"/workers/" + a,input(prefix + "-updated","150",10),200).getDocumentElement();
        assertEquals(a,text(updated,"id")); assertEquals(text(before,"creationDate"),text(updated,"creationDate")); assertEquals("150.0",text(updated,"salary"));
        String minimal="<worker><name>" + prefix + "-D</name><coordinates><x>-9223372036854775808</x><y>0</y></coordinates><startDate>2025-09-01</startDate><organizationId>10</organizationId></worker>";
        var nullable=request("PUT","/workers/{worker-id}",worker,"/workers/" + d,minimal,200).getDocumentElement();
        for(String field:List.of("salary","position","status","person")) assertNull(child(nullable,field));
        request("PUT","/workers/{worker-id}",worker,"/workers/" + a,input("invalid","-1",10),422);
        assertEquals(prefix + "-updated",text(get(a),"name"));
    }
    @Test @Order(7) void salaryOperations() throws Exception {
        var sum=request("GET","/workers/salary/sum",worker,"/workers/salary/sum",null,200).getDocumentElement(); assertEquals(450.0,Double.parseDouble(text(sum,"value")),0.001);
        var count=request("GET","/workers/salary/count-less-than",worker,"/workers/salary/count-less-than?value=150",null,200).getDocumentElement(); assertEquals("1",text(count,"value"));
        var unique=request("GET","/workers/salary/unique",worker,"/workers/salary/unique",null,200).getDocumentElement(); assertEquals(List.of("100.0","150.0","200.0"),children(unique,"salary").stream().map(Element::getTextContent).toList());
        for(String suffix:List.of("","?value=0","?value=NaN","?value=Infinity","?value=-1")) request("GET","/workers/salary/count-less-than",worker,"/workers/salary/count-less-than" + suffix,null,400);
    }
    @Test @Order(8) void hrMoveAndConflicts() throws Exception {
        var before=get(a);
        var moved=request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/" + a + "/10/30",null,200).getDocumentElement();
        assertEquals("30",text(moved,"organizationId")); assertEquals(text(before,"salary"),text(moved,"salary")); assertEquals(text(before,"position"),text(moved,"position"));
        for(String path:List.of("/hr/move/" + a + "/10/20","/hr/move/" + a + "/30/30")) request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,path,null,409);
        request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/" + a + "/30/999",null,404);
        request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/0/10/20",null,400);
        request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/2147483647/10/20",null,404);
        request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/1/10/20",null,406,"application/json",null);
    }
    @Test @Order(9) void hrIndexAndAtomicFailure() throws Exception {
        request("POST","/hr/move/{worker-id}/{id-from}/{id-to}",hr,"/hr/move/" + d + "/10/30",null,200);
        var indexed=request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/30/1.1",null,200).getDocumentElement(); assertEquals("1",text(indexed,"updatedWorkers")); assertEquals(165,Double.parseDouble(text(get(a),"salary")),0.001); assertNull(text(get(d),"salary"));
        var huge=create(prefix + "-huge","3.0e38",30);
        String before=text(get(a),"salary"),big=text(get(huge),"salary");
        request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/30/2",null,422);
        assertEquals(before,text(get(a),"salary")); assertEquals(big,text(get(huge),"salary"));
        request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/30/1e-300",null,422); assertEquals(before,text(get(a),"salary"));
        for(String value:List.of("0","-1","NaN","Infinity","abc")) request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/30/" + value,null,400);
        request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/999/1.1",null,404);
        request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/20/1.1",null,200);
        request("POST","/hr/index/all/{org-id}/{coeff}",hr,"/hr/index/all/30/1.1",null,406,"application/json",null);
    }
    @Test @Order(10) void deletionAndNotFound() throws Exception {
        request("DELETE","/workers/{worker-id}",worker,"/workers/" + c,null,204); ids.remove(c);
        request("GET","/workers/{worker-id}",worker,"/workers/" + c,null,404);
        request("DELETE","/workers/{worker-id}",worker,"/workers/" + c,null,404);
        request("GET","/workers/{worker-id}",worker,"/workers/abc",null,400);
        request("PUT","/workers/{worker-id}",worker,"/workers/2147483647",input("x","100",10),404);
        request("DELETE","/workers/{worker-id}",worker,"/workers/-1",null,400);
    }
    @Test @Order(11) void corsAndInternalAccess() throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(worker + "/workers")).header("Origin",URI.create(hr).getScheme() + "://" + URI.create(hr).getAuthority()).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(204,response.statusCode()); assertEquals(URI.create(hr).getScheme() + "://" + URI.create(hr).getAuthority(),response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var forbidden=client.send(HttpRequest.newBuilder(URI.create(worker + "/internal/index/30/2")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()); assertEquals(403,forbidden.statusCode());
    }
    @Test @Order(12) void clientAndSwaggerAreServedLocally() throws Exception {
        String origin=URI.create(hr).getScheme() + "://" + URI.create(hr).getAuthority();
        for(String path:List.of("/","/assets/app.js","/assets/style.css","/client-config.js","/docs/index.html","/docs/openapi.yaml","/docs/swagger/swagger-ui-bundle.js","/docs/swagger/swagger-ui.css")) {
            var response=client.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(200,response.statusCode(),path); assertFalse(response.body().isEmpty());
            if(path.equals("/")) { assertTrue(response.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("charset=utf-8")); assertTrue(response.body().contains("Работники и организации")); }
        }
        var missing=client.send(HttpRequest.newBuilder(URI.create(origin + "/api/not-found")).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(404,missing.statusCode()); assertTrue(missing.headers().firstValue("Content-Type").orElse("").startsWith("application/xml"));
    }
    @Test @Order(13) void allPublicOperationsCovered() {
        var expected=new TreeSet<String>(); contract.spec.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames().forEachRemaining(method -> { if(Set.of("get","post","put","delete").contains(method)) expected.add(method + " " + path.getKey()); }));
        assertEquals(10,expected.size()); assertEquals(expected,contract.covered);
    }
    @AfterAll void cleanup() throws Exception {
        if(client == null) return;
        for(String id:List.copyOf(ids)) client.send(HttpRequest.newBuilder(URI.create(worker + "/workers/" + id)).DELETE().build(),HttpResponse.BodyHandlers.discarding());
        if(contract != null) Files.writeString(Path.of("target","openapi-coverage.txt"),String.join(System.lineSeparator(),contract.covered));
    }
}
