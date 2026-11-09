package com.mpl.migrations;
import org.junit.jupiter.api.Test;
import liquibase.ChecksumVersion;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MigrationSafetyTest {
    @Test void preservedAppliedIdentitiesAndChecksums() throws Exception {
        var changes = MigrationMain.changelog().getChangeSets();
        var baseline = Files.readAllLines(Path.of("tests/baseline-databasechangelog.csv"));
        assertEquals(6, changes.size()); // five schema changes plus opt-in seed
        for (int i=1;i<baseline.size();i++) {
            if (baseline.get(i).isBlank()) continue;
            var row=baseline.get(i).split(","); var change=changes.get(i-1);
            assertEquals(row[0],change.getId()); assertEquals(row[1],change.getAuthor());
            assertEquals(row[2],change.getFilePath()); assertEquals(row[3],change.generateCheckSum(ChecksumVersion.latest()).toString());
        }
    }
    @Test void rejectsWrongDatabaseAndRuntimeCredentialsAndUnsafeCommands() {
        var env=new HashMap<>(Map.of("DB_URL","jdbc:postgresql://localhost:5432/user_service_db","DB_MIGRATION_USERNAME","app_migrator","DB_MIGRATION_PASSWORD","test"));
        assertDoesNotThrow(()->MigrationMain.guard(new String[]{"validate"},env));
        env.put("DB_URL","jdbc:postgresql://localhost:5432/keycloak_db");
        assertThrows(IllegalArgumentException.class,()->MigrationMain.guard(new String[]{"update"},env));
        env.put("DB_URL","jdbc:postgresql://localhost:5432/user_service_db");env.put("DB_MIGRATION_USERNAME","app_runtime");
        assertThrows(IllegalArgumentException.class,()->MigrationMain.guard(new String[]{"update"},env));
        env.put("DB_MIGRATION_USERNAME","app_migrator");
        for (String command:List.of("dropAll","clearCheckSums","changelogSync","rollback"))
            assertThrows(IllegalArgumentException.class,()->MigrationMain.guard(new String[]{command},env));
        assertThrows(IllegalArgumentException.class,()->MigrationMain.guard(new String[]{"rollback","1"},env));
    }
    @Test void rollbackHashIgnoresGeneratedCommentsButDetectsChangedSql() throws Exception {
        assertEquals(MigrationMain.sqlHash("-- time one\nDROP TABLE users;\n"),MigrationMain.sqlHash("-- time two\nDROP TABLE users;\n"));
        assertNotEquals(MigrationMain.sqlHash("DROP TABLE users;"),MigrationMain.sqlHash("DROP TABLE user_identity_sync;"));
    }
}
