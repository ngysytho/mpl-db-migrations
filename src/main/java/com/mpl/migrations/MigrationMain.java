package com.mpl.migrations;

import liquibase.*;
import liquibase.changelog.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Standalone migration entry point. No Spring classes or API lifecycle involved. */
public final class MigrationMain {
    public static final String MASTER = "db/changelog/db.changelog-master.yaml";
    private static final Set<String> COMMANDS = Set.of("verify-history", "verify", "validate", "status", "updateSQL", "update", "rollbackSQL", "rollback");
    public static void main(String[] args) {
        try { execute(args, System.getenv(), new PrintWriter(System.out, true)); }
        catch (Exception failure) {
            // JDBC/Liquibase exceptions can include connection information or data. Do not dump them.
            System.err.println("Migration refused or failed (" + failure.getClass().getSimpleName() + "). Check target, history, grants and reviewed release inputs.");
            System.exit(1);
        }
    }
    static void guard(String[] args, Map<String,String> env) {
        if (args.length < 1 || !COMMANDS.contains(args[0])) throw new IllegalArgumentException("Unsupported command");
        boolean rollback = args[0].startsWith("rollback");
        if (args.length != (rollback ? 2 : 1) || (rollback && !args[1].matches("[1-9][0-9]*"))) throw new IllegalArgumentException("Expected positive rollback count only");
        String url = required(env, "DB_URL"), user = required(env, "DB_MIGRATION_USERNAME");
        // Single host, one database, public schema only. No JDBC credentials/options in URL.
        if (!url.matches("jdbc:postgresql://(?:[A-Za-z0-9_.-]+|\\[[0-9a-fA-F:]+\\]):[0-9]+/user_service_db(?:\\?sslmode=(?:disable|require|verify-ca|verify-full))?"))
            throw new IllegalArgumentException("Only explicit user_service_db target permitted");
        if (Set.of("postgres", "app_runtime", "keycloak").contains(user) || user.equals(env.get("DB_USERNAME"))) throw new IllegalArgumentException("Dedicated migrator required");
        required(env, "DB_MIGRATION_PASSWORD");
        String contexts=env.getOrDefault("MIGRATION_CONTEXTS", "production");
        if (!Set.of("production", "local", "test", "local,development-seed").contains(contexts)) throw new IllegalArgumentException("Unsupported migration contexts");
        if (contexts.contains("development-seed")) {
            if (!"true".equals(env.get("MPL_DEVELOPMENT_SEED_APPROVED"))) throw new IllegalArgumentException("Development seed requires explicit opt-in");
            UUID.fromString(required(env, "KEYCLOAK_DEV_SEED_USER_ID"));
        }
        if ("rollback".equals(args[0])) {
            if (!("rollback:" + args[1] + ":user_service_db").equals(env.get("MPL_ROLLBACK_APPROVAL"))) throw new IllegalArgumentException("Explicit rollback approval required");
            required(env, "MPL_BACKUP_REFERENCE"); required(env, "MPL_ROLLBACK_SQL_SHA256");
        }
    }
    public static DatabaseChangeLog changelog() throws Exception { return changelog(Map.of()); }
    static DatabaseChangeLog changelog(Map<String,String> env) throws Exception {
        try (var resources = new ClassLoaderResourceAccessor()) {
            var params = new ChangeLogParameters();
            params.set("developmentSeedKeycloakUserId", env.getOrDefault("KEYCLOAK_DEV_SEED_USER_ID", "not-configured"));
            return ChangeLogParserFactory.getInstance().getParser(MASTER, resources).parse(MASTER, params, resources);
        }
    }
    static void verifyHistory(Connection connection, Map<String,String> env) throws Exception {
        var changes = new HashMap<String,ChangeSet>();
        for (var change : changelog(env).getChangeSets()) changes.put(change.getId()+"|"+change.getAuthor()+"|"+change.getFilePath(), change);
        try (var statement = connection.createStatement(); var table = statement.executeQuery("SELECT to_regclass('public.databasechangelog')")) {
            table.next(); if (table.getString(1) == null) return;
        }
        try (var statement = connection.createStatement(); var history = statement.executeQuery("SELECT id,author,filename,md5sum FROM public.databasechangelog ORDER BY orderexecuted")) {
            Set<String> seen = new HashSet<>();
            while (history.next()) {
                String key = history.getString(1)+"|"+history.getString(2)+"|"+history.getString(3);
                var change = changes.get(key);
                if (change == null || !seen.add(key)) throw new IllegalStateException("Unknown or duplicate migration identity");
                String checksum = history.getString(4);
                if (checksum == null || !checksum.equals(change.generateCheckSum(ChecksumVersion.latest()).toString()))
                    throw new IllegalStateException("Migration checksum mismatch; never clear or synchronize history");
            }
        }
    }
    public static void execute(String[] args, Map<String,String> env, Writer output) throws Exception {
        guard(args, env);
        try (var connection = DriverManager.getConnection(env.get("DB_URL"), env.get("DB_MIGRATION_USERNAME"), env.get("DB_MIGRATION_PASSWORD"))) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement(); var target = statement.executeQuery("SELECT current_database(), rolsuper FROM pg_roles WHERE rolname=current_user")) {
                if (!target.next() || !"user_service_db".equals(target.getString(1)) || target.getBoolean(2)) throw new IllegalStateException("Unsafe target or superuser");
            }
            verifyHistory(connection, env);
            if (args[0].equals("verify-history")) { output.write("Migration history identities and checksums verified\n"); output.flush(); return; }
            connection.setReadOnly(false);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName("public"); database.setLiquibaseSchemaName("public");
            try (var resources = new ClassLoaderResourceAccessor(); var liquibase = new Liquibase(MASTER, resources, database)) {
                liquibase.setChangeLogParameter("developmentSeedKeycloakUserId", env.getOrDefault("KEYCLOAK_DEV_SEED_USER_ID", "not-configured"));
                var contexts = new Contexts(env.getOrDefault("MIGRATION_CONTEXTS", "production")); var labels = new LabelExpression();
                switch (args[0]) {
                    case "validate" -> liquibase.validate();
                    case "verify" -> {
                        liquibase.validate();
                        if (!liquibase.listUnrunChangeSets(contexts, labels).isEmpty()) throw new IllegalStateException("Required migrations remain pending");
                        output.write("All required migrations applied\n");
                    }
                    case "status" -> liquibase.reportStatus(true, contexts, labels, output);
                    case "updateSQL" -> liquibase.update(contexts, labels, output);
                    case "update" -> liquibase.update(contexts, labels);
                    case "rollbackSQL", "rollback" -> {
                        int count = Integer.parseInt(args[1]);
                        var sql = new StringWriter(); liquibase.rollback(count, contexts, labels, sql);
                        if (args[0].equals("rollbackSQL")) { output.write(sql.toString()); System.err.println("Reviewed SQL SHA-256: " + sqlHash(sql.toString())); }
                        else {
                            if (!sqlHash(sql.toString()).equals(env.get("MPL_ROLLBACK_SQL_SHA256"))) throw new IllegalStateException("Rollback SQL differs from reviewed plan");
                            liquibase.rollback(count, contexts, labels);
                        }
                    }
                    default -> throw new IllegalArgumentException();
                }
                output.flush();
            }
        }
    }
    static String sqlHash(String sql) throws Exception {
        String normalized = sql.lines().filter(line -> !line.strip().startsWith("--") && !line.isBlank()).map(String::strip).reduce("", (a,b) -> a+b+"\n");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }
    static String required(Map<String,String> env, String name) {
        String value = env.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name); return value;
    }
}
