package ru.itmo.soa.verification;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import ru.itmo.soa.common.JdbcDatabase;

/** Runs only against the dedicated loopback cluster created by postgres-local.ps1. */
@EnabledIfEnvironmentVariable(named="SOA_JDBC_DRIVER", matches="org\\.postgresql\\.Driver")
class PostgresIT {
    private final String url=System.getenv("SOA_JDBC_URL");
    private String password;
    @BeforeEach void localOnly() throws Exception {
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15432/s367056?"), "Never run database verification against Helios");
        password=Files.readString(Path.of(System.getenv("SOA_JDBC_PASSWORD_FILE")));
    }
    @Test void dedicatedSchemaAndNonSuperuser() throws Exception {
        var database=JdbcDatabase.fromSettings();
        try(var connection=database.open(); var query=connection.createStatement(); var rows=query.executeQuery("SELECT current_schema(), current_user, rolsuper FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(rows.next()); assertEquals("soa_lab2",rows.getString(1));
            assertEquals("studs",rows.getString(2)); assertFalse(rows.getBoolean(3));
        }
    }
    @Test void accessProbeLeavesNoTable() throws Exception {
        var database=JdbcDatabase.fromSettings();
        assertFalse(database.checkAccess().isBlank());
        try(var connection=database.open(); var query=connection.createStatement(); var rows=query.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='soa_lab2' AND table_name LIKE '__soa_probe_%'")) {
            assertTrue(rows.next()); assertEquals(0,rows.getInt(1));
        }
    }
    @Test void wrongPasswordFailsWithoutFallback() {
        var error=assertThrows(SQLException.class,()->DriverManager.getConnection(url,"studs",password+"-wrong"));
        assertEquals("28P01",error.getSQLState());
    }
    @Test void unreachableDatabaseFailsWithoutFallback() throws Exception {
        int port;
        try(var socket=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        String unreachable="jdbc:postgresql://127.0.0.1:"+port+"/s367056?connectTimeout=1";
        var error=assertThrows(SQLException.class,()->DriverManager.getConnection(unreachable,"studs",password));
        assertTrue(error.getSQLState().startsWith("08"));
    }
    @Test void passwordFileOverridesEnvironmentPassword() throws Exception {
        String previous=System.getProperty("soa.jdbc.password");
        try {
            System.setProperty("soa.jdbc.password","deliberately-wrong-inline-password");
            try(var connection=JdbcDatabase.fromSettings().open()) { assertTrue(connection.isValid(2)); }
        } finally {
            if(previous == null) System.clearProperty("soa.jdbc.password"); else System.setProperty("soa.jdbc.password",previous);
        }
    }
    @Test void insufficientSchemaPrivilegesFailClearly() {
        String previous=System.getProperty("soa.jdbc.schema");
        try {
            System.setProperty("soa.jdbc.schema","soa_no_create");
            var error=assertThrows(SQLException.class,()->JdbcDatabase.fromSettings().checkAccess());
            assertEquals("42501",error.getSQLState());
        } finally {
            if(previous == null) System.clearProperty("soa.jdbc.schema"); else System.setProperty("soa.jdbc.schema",previous);
        }
    }
    @Test void missingPasswordFileDoesNotUseInlineFallback() {
        String previous=System.getProperty("soa.jdbc.password.file");
        try {
            System.setProperty("soa.jdbc.password.file",Path.of("target","nonexistent-password-"+java.util.UUID.randomUUID()).toString());
            assertThrows(IllegalStateException.class,JdbcDatabase::fromSettings);
        } finally {
            if(previous == null) System.clearProperty("soa.jdbc.password.file"); else System.setProperty("soa.jdbc.password.file",previous);
        }
    }
}
