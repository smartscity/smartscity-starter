package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import com.smartscity.id.autoconfigure.jdbc.*;
import com.smartscity.id.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "id.test.mysql.url", matches = ".+")
class MySqlIntegrationTest {
    @TempDir Path dir;
    static String url;
    @BeforeAll static void schema() throws Exception {
        url = System.getProperty("id.test.mysql.url");
        // Explicit opt-in isolated database; never use application configuration implicitly.
        assertTrue(url.contains("smartscity_id_test"), "Use an isolated test database");
        String sql = new String(MySqlIntegrationTest.class.getResourceAsStream("/db/mysql.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, "root", ""); Statement s = c.createStatement()) {
            for (String part : sql.split(";")) if (!part.trim().isEmpty()) s.execute(part.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
        }
    }
    IdProperties config(String group) {
        IdProperties p = new IdProperties(); p.setGroup(group); p.setBufferSize(64);
        p.getDatabase().setUrl(url); p.getDatabase().setUsername("root"); return p;
    }
    @Test void realDatabaseStartClockValidationMismatchAndOffline() throws Exception {
        String group = "mysql_" + System.nanoTime(); IdProperties p = config(group);
        long raw;
        try (IdGenerator g = new IdGeneratorFactory().create(p, null)) {
            raw = g.snapshot().getRawWorkerId(); assertTrue(g.getUniqueId() > 0);
            assertTrue(g.snapshot().isReady());
            IdProperties wrong = config(group); wrong.setProfile("high-throughput");
            assertEquals(IdException.Reason.CONFIGURATION, assertThrows(IdException.class,
                    () -> new IdGeneratorFactory().create(wrong, null)).getReason());
        }
        assertEquals("OFFLINE", state(raw));
    }
    @Test @Timeout(30) void overlappingProcessesAndKill9DoNotReuseRawWorker() throws Exception {
        String group = "process_" + System.nanoTime(); Path a = dir.resolve("a"), b = dir.resolve("b");
        Process first = child(group, a, dir.resolve("never")); Process second = null;
        try {
            awaitFile(first, a); second = child(group, b, null); assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, second.exitValue()); awaitFile(second, b);
            List<String> x = Files.readAllLines(a), y = Files.readAllLines(b);
            assertNotEquals(x.get(0), y.get(0)); Set<String> ids = new HashSet<>(x.subList(1, x.size()));
            for (String id : y.subList(1, y.size())) assertTrue(ids.add(id));
            first.destroyForcibly(); assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertEquals("ONLINE", state(Long.parseLong(x.get(0))), "kill-9 bypasses the shutdown hook");
            assertEquals("OFFLINE", state(Long.parseLong(y.get(0))));
        } finally { first.destroyForcibly(); if (second != null) second.destroyForcibly(); }
    }
    @Test @Timeout(30) void normalProcessShutdownHookMarksOffline() throws Exception {
        String group = "hook_" + System.nanoTime(); Path out = dir.resolve("hook");
        Process process = child(group, out, dir.resolve("never"));
        try {
            awaitFile(process, out); long raw = Long.parseLong(Files.readAllLines(out).get(0));
            process.destroy(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals("OFFLINE", state(raw));
        } finally { process.destroyForcibly(); }
    }
    @Test @Timeout(30) @EnabledOnOs({OS.LINUX, OS.MAC})
    void stoppedProcessResumesWithoutReusingIdsDuringOverlappingStartup() throws Exception {
        String group = "suspend_" + System.nanoTime();
        Path a = dir.resolve("paused"), signal = dir.resolve("resume"), b = dir.resolve("overlap");
        Process first = child(group, a, signal), second = null;
        try {
            awaitFile(first, a);
            signal(first, "STOP");
            Thread.sleep(1200); // Real OS pause across a second boundary; no host clock mutation.
            second = child(group, b, null);
            assertTrue(second.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, second.exitValue());
            awaitFile(second, b);
            Files.createFile(signal); signal(first, "CONT");
            assertTrue(first.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, first.exitValue());
            List<String> before = Files.readAllLines(a), overlap = Files.readAllLines(b);
            assertNotEquals(before.get(0), overlap.get(0));
            Set<String> ids = new HashSet<>();
            for (String id : before.subList(1, before.size())) assertTrue(ids.add(id));
            for (String id : overlap.subList(1, overlap.size())) assertTrue(ids.add(id));
            List<String> after = Files.readAllLines(dir.resolve("paused.after"));
            assertEquals(128, after.size());
            for (String id : after) assertTrue(ids.add(id));
            assertEquals("OFFLINE", state(Long.parseLong(before.get(0))));
        } finally {
            first.destroyForcibly(); first.waitFor(5, TimeUnit.SECONDS);
            if (second != null) { second.destroyForcibly(); second.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    private void signal(Process process, String signal) throws Exception {
        Process command = new ProcessBuilder("/bin/kill", "-" + signal, Long.toString(process.pid())).start();
        try {
            assertTrue(command.waitFor(2, TimeUnit.SECONDS)); assertEquals(0, command.exitValue());
        } finally { command.destroyForcibly(); }
    }
    @Test void runningSdkDoesNotOpenDatabaseConnections() throws Exception {
        IdProperties p = config("local_" + System.nanoTime());
        DriverDataSource delegate = new DriverDataSource(p.getDatabase(), 2000);
        java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource tracked = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        p.getDatabase().setUrl(null);
        try (IdGenerator generator = new IdGeneratorFactory().create(p, tracked)) {
            int startup = connections.get(); assertTrue(startup >= 3);
            for (int i = 0; i < 256; i++) generator.getUniqueId();
            generator.snapshot(); assertEquals(startup, connections.get());
        }
    }
    private Process child(String group, Path output, Path signal) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SdkProcess.class.getName(), url, group, output.toString()));
        if (signal != null) command.add(signal.toString());
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve(output.getFileName()+".log").toFile()).start();
    }
    private void awaitFile(Process process, Path output) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!Files.exists(output) && process.isAlive() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(Files.exists(output), "Child failed; see " + output + ".log");
    }
    private String state(long raw) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "root", ""); PreparedStatement ps = c.prepareStatement("SELECT state FROM smartscity_id_worker WHERE id=?")) {
            ps.setLong(1, raw); try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); }
        }
    }
}
