package com.mpl.migrations;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MigrationIT {
    @Test void cleanUpdateRerunHistoryGuardAndReviewedRollback() throws Exception {
        try (var postgres=new PostgreSQLContainer("postgres:17.6-bookworm").withDatabaseName("user_service_db")) {
            postgres.start();
            try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement()) {
                s.execute("CREATE ROLE app_migrator LOGIN PASSWORD 'disposable-migration-test'");
                s.execute("ALTER SCHEMA public OWNER TO app_migrator");
            }
            var env=new HashMap<>(Map.of("DB_URL",postgres.getJdbcUrl().split("\\?",2)[0],"DB_MIGRATION_USERNAME","app_migrator","DB_MIGRATION_PASSWORD","disposable-migration-test"));
            MigrationMain.execute(new String[]{"validate"},env,new StringWriter());
            MigrationMain.execute(new String[]{"updateSQL"},env,new StringWriter());
            assertThrows(IllegalStateException.class,()->MigrationMain.execute(new String[]{"verify"},env,new StringWriter()));
            MigrationMain.execute(new String[]{"update"},env,new StringWriter());
            MigrationMain.execute(new String[]{"update"},env,new StringWriter());
            MigrationMain.execute(new String[]{"verify-history"},env,new StringWriter());
            MigrationMain.execute(new String[]{"verify"},env,new StringWriter());
            try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement()) {
                try(var rs=s.executeQuery("SELECT count(*) FROM databasechangelog")){rs.next();assertEquals(5,rs.getInt(1));}
                s.execute("UPDATE databasechangelog SET filename='wrong/path.yaml' WHERE orderexecuted=1");
                assertThrows(IllegalStateException.class,()->MigrationMain.execute(new String[]{"update"},env,new StringWriter()));
                s.execute("UPDATE databasechangelog SET filename='db/changelog/changes/001-create-users-table.yaml' WHERE orderexecuted=1");
                s.execute("UPDATE databasechangelog SET md5sum='9:wrong' WHERE orderexecuted=1");
                assertThrows(IllegalStateException.class,()->MigrationMain.execute(new String[]{"update"},env,new StringWriter()));
                s.execute("UPDATE databasechangelog SET md5sum='9:ebdaba9c023e551921faaad10fc35700' WHERE orderexecuted=1");
            }
            var sql=new StringWriter();MigrationMain.execute(new String[]{"rollbackSQL","1"},env,sql);
            assertTrue(sql.toString().contains("DROP TABLE user_registration_rate_limits"));
            assertThrows(IllegalArgumentException.class,()->MigrationMain.execute(new String[]{"rollback","1"},env,new StringWriter()));
            env.put("MPL_ROLLBACK_APPROVAL","rollback:1:user_service_db");env.put("MPL_BACKUP_REFERENCE","disposable-test");
            env.put("MPL_ROLLBACK_SQL_SHA256","wrong");
            assertThrows(IllegalStateException.class,()->MigrationMain.execute(new String[]{"rollback","1"},env,new StringWriter()));
            env.put("MPL_ROLLBACK_SQL_SHA256",MigrationMain.sqlHash(sql.toString()));
            MigrationMain.execute(new String[]{"rollback","1"},env,new StringWriter());
            MigrationMain.execute(new String[]{"update"},env,new StringWriter());
            MigrationMain.execute(new String[]{"verify-history"},env,new StringWriter());
            MigrationMain.execute(new String[]{"verify"},env,new StringWriter());
        }
    }
}
