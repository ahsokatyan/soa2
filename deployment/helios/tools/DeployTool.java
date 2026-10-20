import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import ru.itmo.soa.common.JdbcDatabase;

/** Deployment utility using shared JDBC settings. Compiled locally with --release 17. */
public final class DeployTool {
    private static Path root;
    private static final String DOMAIN="runtime/payara-domains/soa";
    private static int workerPort,clientPort,adminPort,offset;
    private static final List<String> created=new ArrayList<>();
    private static HttpClient client;
    public static void main(String[] args) throws Exception {
        if(args.length < 2) throw new IllegalArgumentException("Usage: DeployTool command installation-directory [worker client admin offset]");
        root=Path.of(args[1]).toAbsolutePath().normalize();
        if(!Files.isRegularFile(root.resolve("config/helios.conf"))) throw new IllegalArgumentException("Not an SOA installation: " + root);
        if(Runtime.version().feature() < 17) throw new IllegalStateException("Java 17 or newer is required");
        if(args.length >= 4) { workerPort=port(args[2]); clientPort=port(args[3]); }
        if(args.length >= 6) { adminPort=port(args[4]); offset=Integer.parseInt(args[5]); }
        switch(args[0]) {
            case "verify" -> verify();
            case "db-password" -> databasePassword();
            case "check-db" -> checkDatabase();
            case "configure" -> configure();
            case "ports" -> checkPorts();
            case "prepare-deploy" -> prepareDeploy();
            case "stop-tomcat" -> stopTomcat();
            case "wait-worker" -> waitFor(workerBase()+"/workers","workerPage");
            case "wait-client" -> waitFor(clientBase()+"/client-config.js",null);
            case "status" -> { request("GET",workerBase()+"/workers",null,200,"workerPage"); request("GET",clientBase()+"/client-config.js",null,200,null); System.out.println("Both HTTPS applications respond; certificate and hostname verification passed."); }
            case "smoke" -> smoke();
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }
    private static int port(String value) { int result=Integer.parseInt(value); if(result < 1024 || result > 65535) throw new IllegalArgumentException("Use a port between 1024 and 65535"); return result; }
    private static void databasePassword() throws Exception {
        Console console=System.console();
        if(console == null) throw new IllegalStateException("Run sh bin/db-password.sh in an interactive SSH terminal (ssh -t if needed); password input is never echoed");
        char[] first=console.readPassword("PostgreSQL password: ");
        char[] second=console.readPassword("Repeat password: ");
        Path temporary=null;
        try {
            if(first == null || second == null || first.length == 0 || !Arrays.equals(first,second))
                throw new IllegalArgumentException("Passwords are empty, cancelled or do not match; existing password was not changed");
            Path directory=path("runtime/secrets"); Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,".db-password-",".tmp");
            if(Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary,new String(first),StandardCharsets.UTF_8);
            Files.move(temporary,path("runtime/secrets/db-password"),StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Database password saved privately; no password is included in the release archive.");
        } finally {
            if(first != null) Arrays.fill(first,'\0'); if(second != null) Arrays.fill(second,'\0');
            if(temporary != null) Files.deleteIfExists(temporary);
        }
    }
    private static void checkDatabase() throws Exception {
        try {
            var database=JdbcDatabase.fromSettings();
            String version=database.checkAccess();
            System.out.println("PostgreSQL connection, schema " + database.schema() + ", DDL/DML and identity privileges verified. Server: " + version);
        } catch(java.sql.SQLException e) {
            throw new IllegalStateException("PostgreSQL check failed (SQLSTATE " + e.getSQLState() + "). Check host/port/database/user/password and CREATE/USAGE permissions for JDBC_SCHEMA. No H2 fallback. Password and server error details are not printed.");
        }
    }
    private static Path path(String relative) { Path result=root.resolve(relative).normalize(); if(!result.startsWith(root) || result.equals(root)) throw new IllegalArgumentException("Path outside installation"); return result; }
    private static void verify() throws Exception {
        for(String line:Files.readAllLines(path("SHA256SUMS"))) {
            if(line.isBlank()) continue;
            String[] parts=line.split("  ",2); if(parts.length != 2) throw new IOException("Invalid checksum manifest");
            Path file=path(parts[1]); var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int count; while((count=input.read(buffer)) != -1) digest.update(buffer,0,count); }
            if(!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(parts[0])) throw new IOException("Checksum mismatch: " + parts[1]);
        }
        System.out.println("Release SHA-256 checksums verified.");
    }
    private static void checkPorts() throws Exception {
        var sockets=new ArrayList<ServerSocket>();
        // Active application/admin ports plus Payara's shifted IIOP/OSGi ports.
        int[] values={workerPort,clientPort,adminPort,3700+offset,3820+offset,3920+offset,6666+offset};
        var unique=new HashSet<Integer>();
        try {
            for(int value:values) {
                port(Integer.toString(value)); if(!unique.add(value)) throw new IOException("Duplicate configured port: " + value);
                var socket=new ServerSocket(); sockets.add(socket); socket.setReuseAddress(false);
                try { socket.bind(new InetSocketAddress("127.0.0.1",value)); }
                catch(IOException e) { throw new IOException("Cannot bind local port " + value + ". Stop an existing instance or change config/helios.conf.",e); }
            }
        } finally { for(var socket:sockets) socket.close(); }
        System.out.println("Required local ports can be bound: " + Arrays.toString(values));
    }
    private static void configure() throws Exception {
        checkPorts();
        for(String folder:List.of("runtime/certs","runtime/secrets","runtime/data","runtime/logs","runtime/admin-home","runtime/tomcat/conf","runtime/tomcat/webapps","runtime/tomcat/temp","runtime/tomcat/work","runtime/tomcat/logs")) Files.createDirectories(path(folder));
        copyTree(path("servers/payara6/glassfish/domains/domain1"),path(DOMAIN));
        copyTree(path("servers/apache-tomcat-10.1.61/conf"),path("runtime/tomcat/conf"));
        secret("runtime/secrets/internal-token"); String password=secret("runtime/secrets/store-password");
        if(!Files.exists(path("runtime/certs/servers.p12"))) {
            keytool("-genkeypair","-alias","soa","-keyalg","RSA","-keysize","2048","-validity","3650","-dname","CN=localhost,OU=SOA Lab2,O=ITMO","-ext","SAN=dns:localhost,ip:127.0.0.1,dns:helios.cs.ifmo.ru,dns:se.ifmo.ru","-storetype","PKCS12","-keystore",path("runtime/certs/servers.p12").toString(),"-storepass",password,"-keypass",password,"-noprompt");
        }
        keytool("-exportcert","-rfc","-alias","soa","-keystore",path("runtime/certs/servers.p12").toString(),"-storepass",password,"-file",path("runtime/certs/localhost.crt").toString());
        if(!Files.exists(path("runtime/certs/truststore.p12"))) keytool("-importcert","-noprompt","-alias","soa","-file",path("runtime/certs/localhost.crt").toString(),"-keystore",path("runtime/certs/truststore.p12").toString(),"-storetype","PKCS12","-storepass",password);
        keytool("-importkeystore","-noprompt","-srckeystore",path("runtime/certs/servers.p12").toString(),"-srcstoretype","PKCS12","-srcstorepass",password,"-srcalias","soa","-destkeystore",path(DOMAIN+"/config/keystore.p12").toString(),"-deststorepass","changeit","-destkeypass","changeit","-destalias","soa");
        configureDomain();
        var doc=newDocument(); var server=doc.createElement("Server"); server.setAttribute("port","-1"); doc.appendChild(server);
        var service=append(server,"Service","name","Catalina");
        var connector=append(service,"Connector","port",Integer.toString(clientPort),"address","127.0.0.1","protocol","org.apache.coyote.http11.Http11NioProtocol","SSLEnabled","true","scheme","https","secure","true","maxThreads","40","maxConnections","200");
        var ssl=append(connector,"SSLHostConfig","protocols","TLSv1.2,+TLSv1.3");
        append(ssl,"Certificate","certificateKeystoreFile",path("runtime/certs/servers.p12").toString(),"certificateKeystorePassword",password,"certificateKeystoreType","PKCS12","certificateKeyAlias","soa","type","RSA");
        var engine=append(service,"Engine","name","Catalina","defaultHost","localhost");
        append(engine,"Host","name","localhost","appBase","webapps","unpackWARs","true","autoDeploy","false");
        save(doc,path("runtime/tomcat/conf/server.xml"));
        System.out.println("Configured Payara domain soa and Tomcat base for Java " + Runtime.version().feature());
        System.out.println("Application HTTPS ports: " + workerPort + ", " + clientPort + "; loopback admin: " + adminPort);
        System.out.println("Database is external PostgreSQL, configured via SOA_JDBC_*; no local H2 fallback.");
    }
    private static void configureDomain() throws Exception {
        Path file=path(DOMAIN+"/config/domain.xml"); var doc=read(file); var config=element(doc,"/domain/configs/config[@name='server-config']");
        var grid=element(doc,"/domain/hazelcast-runtime-configuration"); grid.setAttribute("start-port",Integer.toString(5900+offset)); grid.setAttribute("das-port",Integer.toString(4900+offset)); grid.setAttribute("das-bind-address","127.0.0.1"); grid.setAttribute("auto-increment-port","false"); grid.setAttribute("cluster-group-name","soa2-s367056");
        Element localGrid=child(config,"hazelcast-config-specific-configuration"); if(localGrid == null) localGrid=append(config,"hazelcast-config-specific-configuration"); localGrid.setAttribute("enabled","false");
        for(var listener:elements(config,"./network-config/network-listeners/network-listener")) {
            listener.setAttribute("address","127.0.0.1");
            switch(listener.getAttribute("name")) {
                case "http-listener-1" -> { listener.setAttribute("enabled","false"); listener.setAttribute("port",Integer.toString(8080+offset)); }
                case "http-listener-2" -> { listener.setAttribute("port",Integer.toString(workerPort)); listener.setAttribute("enabled","true"); }
                case "admin-listener" -> listener.setAttribute("port",Integer.toString(adminPort));
            }
        }
        element(config,"./network-config/protocols/protocol[@name='http-listener-2']/ssl").setAttribute("cert-nickname","soa");
        var iiop=elements(config,"./iiop-service/iiop-listener"); int[] original={3700,3820,3920};
        for(int i=0;i<iiop.size();i++) { iiop.get(i).setAttribute("address","127.0.0.1"); iiop.get(i).setAttribute("port",Integer.toString(original[i]+offset)); }
        for(var jmx:elements(config,"./admin-service/jmx-connector")) { jmx.setAttribute("address","127.0.0.1"); jmx.setAttribute("port",Integer.toString(8686+offset)); jmx.setAttribute("enabled","false"); }
        for(var property:elements(config,"./system-property[@name='JMS_PROVIDER_PORT']")) property.setAttribute("value",Integer.toString(7676+offset));
        // No JMS is used by either application. REMOTE prevents Payara's
        // embedded broker/proxy from binding an extra wildcard-address socket.
        var jms=elements(config,"./jms-service");
        if(jms.isEmpty()) append(config,"jms-service","type","REMOTE");
        else jms.get(0).setAttribute("type","REMOTE");
        for(var host:elements(config,"./jms-service/jms-host")) host.setAttribute("host","127.0.0.1");
        var java=element(config,"./java-config"); java.setAttribute("debug-options","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address="+(9009+offset));
        for(var option:elements(java,"./jvm-options")) {
            String value=option.getTextContent();
            if(value.startsWith("-Xmx") || value.startsWith("-Xms") || value.startsWith("-XX:MaxMetaspaceSize=") || value.startsWith("-XX:ActiveProcessorCount=") || value.startsWith("-Dosgi.shell.telnet.port=") || value.startsWith("-Dfile.encoding=") || value.startsWith("-Djava.net.preferIPv4Stack=")) java.removeChild(option);
        }
        for(String option:List.of("-Xms64m","-Xmx384m","-XX:MaxMetaspaceSize=256m","-XX:ActiveProcessorCount=2","-Dosgi.shell.telnet.port="+(6666+offset),"-Dfile.encoding=UTF-8","-Djava.net.preferIPv4Stack=true")) append(java,"jvm-options").setTextContent(option);
        for(var pool:elements(config,"./thread-pools/thread-pool")) {
            pool.setAttribute("max-thread-pool-size",pool.getAttribute("name").equals("admin-thread-pool") ? "8" : "32");
            pool.setAttribute("min-thread-pool-size","2");
        }
        for(var property:elements(doc,"/domain/property[@name='administrative.domain.name']")) property.setAttribute("value","soa");
        save(doc,file);
    }
    private static String secret(String file) throws Exception {
        Path target=path(file); if(!Files.exists(target)) { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); Files.writeString(target,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)); }
        try { Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch(UnsupportedOperationException ignored) {}
        return Files.readString(target).trim();
    }
    private static void keytool(String... args) throws Exception {
        var command=new ArrayList<String>(); String binary=System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        command.add(Path.of(System.getProperty("java.home"),"bin",binary).toString()); command.addAll(List.of(args));
        var process=new ProcessBuilder(command).inheritIO().start(); if(process.waitFor() != 0) throw new IOException("keytool failed");
    }
    private static void copyTree(Path source,Path target) throws Exception {
        if(!Files.isDirectory(source)) throw new IOException("Missing server distribution: " + source);
        try(var stream=Files.walk(source)) { for(var item:stream.toList()) { Path destination=target.resolve(source.relativize(item)); if(Files.isDirectory(item)) Files.createDirectories(destination); else if(!Files.exists(destination)) Files.copy(item,destination); } }
    }
    private static void prepareDeploy() throws Exception {
        Path file=path(DOMAIN+"/config/domain.xml"); var doc=read(file);
        for(var item:elements(doc,"/domain/servers/server/application-ref[@ref='workers'] | /domain/applications/application[@name='workers']")) item.getParentNode().removeChild(item);
        Files.copy(file,path("runtime/domain.before-deploy.xml"),StandardCopyOption.REPLACE_EXISTING); save(doc,file);
        Path old=path(DOMAIN+"/applications/workers");
        if(Files.exists(old)) {
            Path backup=path("runtime/deployment-backups/workers-"+Instant.now().toEpochMilli()); Files.createDirectories(backup.getParent()); Files.move(old,backup);
            System.out.println("Previous generated deployment saved to " + backup);
        }
    }
    private static void stopTomcat() throws Exception {
        Path file=path("runtime/tomcat.pid"); if(!Files.exists(file)) { System.out.println("No Tomcat PID recorded."); return; }
        long pid=Long.parseLong(Files.readString(file).trim()); var handle=ProcessHandle.of(pid);
        if(handle.isEmpty() || !handle.get().isAlive()) { System.out.println("Tomcat is already stopped."); return; }
        String command=handle.get().info().commandLine().orElse("");
        if(command.isBlank()) {
            Process ps;
            if(System.getProperty("os.name").startsWith("Windows")) {
                String script="[Console]::OutputEncoding=[Text.Encoding]::UTF8; (Get-CimInstance Win32_Process -Filter 'ProcessId="+pid+"').CommandLine";
                ps=new ProcessBuilder("powershell.exe","-NoProfile","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).start();
            } else ps=new ProcessBuilder("ps","-p",Long.toString(pid),"-o","args=").start();
            command=new String(ps.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            if(ps.waitFor() != 0) throw new IOException("Cannot inspect recorded Tomcat PID " + pid);
        }
        if(!command.contains("org.apache.catalina.startup.Bootstrap") || !command.replace('\\','/').contains(path("runtime/tomcat").toString().replace('\\','/'))) throw new IOException("PID does not identify this installation's Tomcat; refusing to stop it");
        handle.get().destroy(); handle.get().onExit().get(30,TimeUnit.SECONDS); System.out.println("Tomcat stopped.");
    }
    private static String workerBase() { return "https://localhost:"+workerPort+"/workers/api"; }
    private static String clientBase() { return "https://localhost:"+clientPort; }
    private static HttpClient client() throws Exception {
        if(client == null) {
            var store=KeyStore.getInstance("PKCS12"); try(var input=Files.newInputStream(path("runtime/certs/truststore.p12"))) { store.load(input,Files.readString(path("runtime/secrets/store-password")).trim().toCharArray()); }
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store); var ssl=SSLContext.getInstance("TLS"); ssl.init(null,trust.getTrustManagers(),null);
            client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(2)).build();
        } return client;
    }
    private static String request(String method,String url,String body,int status,String rootName) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("Accept",rootName == null ? "*/*" : "application/xml");
        if(body != null) builder.header("Content-Type","application/xml");
        var result=client().send(builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(result.statusCode() != status) throw new IOException(method+" "+url+" returned "+result.statusCode()+": "+result.body());
        if(rootName != null && !read(result.body()).getDocumentElement().getTagName().equals(rootName)) throw new IOException("Unexpected XML root for "+url);
        return result.body();
    }
    private static void waitFor(String url,String rootName) throws Exception {
        Exception last=null;
        for(int i=0;i<60;i++) { try { request("GET",url,null,200,rootName); System.out.println("Ready: "+url); return; } catch(Exception error) { last=error; Thread.sleep(500); } }
        throw new IOException("Application did not become ready: "+url,last);
    }
    private static void smoke() throws Exception {
        int passed=0;
        try {
            request("GET",workerBase()+"/workers?page=1&size=2&sort=name,asc&sort=id,desc",null,200,"workerPage"); passed++;
            String payload="<worker><name>helios-check-"+UUID.randomUUID()+"</name><coordinates><x>12</x><y>34</y></coordinates><salary>100</salary><startDate>2025-09-01</startDate><position>MANAGER</position><status>REGULAR</status><organizationId>10</organizationId></worker>";
            String createdXml=request("POST",workerBase()+"/workers",payload,201,"worker"); String id=read(createdXml).getElementsByTagName("id").item(0).getTextContent(); created.add(id); passed++;
            request("GET",workerBase()+"/workers/"+id,null,200,"worker"); passed++;
            request("PUT",workerBase()+"/workers/"+id,payload.replace("<salary>100</salary>","<salary>120</salary>"),200,"worker"); passed++;
            request("GET",workerBase()+"/workers/salary/sum",null,200,"salarySum"); passed++;
            request("GET",workerBase()+"/workers/salary/count-less-than?value=121",null,200,"workerCount"); passed++;
            request("GET",workerBase()+"/workers/salary/unique",null,200,"salaries"); passed++;
            String moved=request("POST",clientBase()+"/api/hr/move/"+id+"/10/20",null,200,"worker");
            if(!read(moved).getElementsByTagName("organizationId").item(0).getTextContent().equals("20")) throw new IOException("Move failed"); passed++;
            // Coefficient 1 preserves other workers' salaries on repeated checks.
            request("POST",clientBase()+"/api/hr/index/all/20/1",null,200,"indexResult"); passed++;
            request("DELETE",workerBase()+"/workers/"+id,null,204,null); created.remove(id); passed++;
            request("GET",workerBase()+"/workers/"+id,null,404,"error");
            request("POST",workerBase()+"/workers",payload.replace("<salary>100</salary>","<salary>0</salary>"),422,"error");
            String spec=request("GET",clientBase()+"/docs/openapi.yaml",null,200,null);
            String publicWorker=System.getenv().getOrDefault("SOA_PUBLIC_WORKER_URL",workerBase());
            String publicHr=System.getenv().getOrDefault("SOA_PUBLIC_HR_URL",clientBase()+"/api");
            if(!spec.contains(publicWorker) || !spec.contains(publicHr)) throw new IOException("Swagger server URLs do not match deployment");
            System.out.println("PASS: "+passed+"/10 public operations; XML errors 404/422; Swagger deployment URLs; TLS trust and hostnames verified.");
            System.out.println("Known page int32 limit remains unchanged by agreement.");
        } finally {
            for(String id:List.copyOf(created)) try { request("DELETE",workerBase()+"/workers/"+id,null,204,null); } catch(Exception error) { System.err.println("Could not clean test worker "+id+": "+error.getMessage()); }
        }
    }
    private static DocumentBuilder builder() throws Exception {
        var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true); factory.setFeature("http://xml.org/sax/features/external-general-entities",false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false); return factory.newDocumentBuilder();
    }
    private static Document newDocument() throws Exception { return builder().newDocument(); }
    private static Document read(Path file) throws Exception { return builder().parse(file.toFile()); }
    private static Document read(String xml) throws Exception { return builder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))); }
    private static List<Element> elements(Node parent,String expression) throws Exception {
        var nodes=(NodeList)XPathFactory.newInstance().newXPath().evaluate(expression,parent,XPathConstants.NODESET); var result=new ArrayList<Element>(); for(int i=0;i<nodes.getLength();i++) result.add((Element)nodes.item(i)); return result;
    }
    private static Element element(Node parent,String expression) throws Exception { return elements(parent,expression).stream().findFirst().orElseThrow(()->new IOException("Missing configuration element: "+expression)); }
    private static Element child(Element parent,String name) { for(Node item=parent.getFirstChild();item != null;item=item.getNextSibling()) if(item instanceof Element element && element.getTagName().equals(name)) return element; return null; }
    private static Element append(Element parent,String name,String... attributes) {
        var element=parent.getOwnerDocument().createElement(name); for(int i=0;i<attributes.length;i+=2) element.setAttribute(attributes[i],attributes[i+1]); parent.appendChild(element); return element;
    }
    private static void save(Document document,Path file) throws Exception {
        var factory=TransformerFactory.newInstance(); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true); var transformer=factory.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8"); transformer.setOutputProperty(OutputKeys.INDENT,"yes");
        try(var output=Files.newOutputStream(file)) { transformer.transform(new DOMSource(document),new StreamResult(output)); }
    }
}
