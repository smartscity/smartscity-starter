package com.smartscity.id.autoconfigure;

import com.smartscity.id.core.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("smartscity.id")
public class IdProperties {
    private boolean enabled = true;
    private String profile = "standard", group = "default";
    private int javascriptSequenceBits = 15, bufferSize = 16384, lowWaterPercent = 50;
    private Duration waitTimeout = Duration.ofMillis(10), clockTolerance = Duration.ofSeconds(1);
    private Duration startupTimeout = Duration.ofSeconds(10), shutdownTimeout = Duration.ofSeconds(2);
    private int workerAttempts = 1024;
    private final Database database = new Database();
    private final Zookeeper zookeeper = new Zookeeper();
    public GeneratorOptions options() {
        if (!group.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid ID group");
        if (workerAttempts < 1 || workerAttempts > 65536 || startupTimeout.toMillis() < 100
                || startupTimeout.toMillis() > 300000 || shutdownTimeout.toMillis() < 1
                || shutdownTimeout.toMillis() > 30000) throw new IllegalArgumentException("Invalid lifecycle budgets");
        BitsAllocator bits;
        switch (profile) {
            case "standard": bits = BitsAllocator.standard(); break;
            case "high-throughput": bits = BitsAllocator.highThroughput(); break;
            case "javascript": bits = BitsAllocator.javascript(javascriptSequenceBits); break;
            default: throw new IllegalArgumentException("Unknown ID profile: " + profile);
        }
        return GeneratorOptions.builder().bits(bits).capacity(bufferSize).lowWaterPercent(lowWaterPercent)
                .waitTimeout(waitTimeout).clockTolerance(clockTolerance).build();
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getProfile() { return profile; }
    public void setProfile(String v) { profile = v; }
    public String getGroup() { return group; }
    public void setGroup(String v) { group = v; }
    public int getJavascriptSequenceBits() { return javascriptSequenceBits; }
    public void setJavascriptSequenceBits(int v) { javascriptSequenceBits = v; }
    public int getBufferSize() { return bufferSize; }
    public void setBufferSize(int v) { bufferSize = v; }
    public int getLowWaterPercent() { return lowWaterPercent; }
    public void setLowWaterPercent(int v) { lowWaterPercent = v; }
    public Duration getWaitTimeout() { return waitTimeout; }
    public void setWaitTimeout(Duration v) { waitTimeout = v; }
    public Duration getClockTolerance() { return clockTolerance; }
    public void setClockTolerance(Duration v) { clockTolerance = v; }
    public Duration getStartupTimeout() { return startupTimeout; }
    public void setStartupTimeout(Duration v) { startupTimeout = v; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration v) { shutdownTimeout = v; }
    public int getWorkerAttempts() { return workerAttempts; }
    public void setWorkerAttempts(int v) { workerAttempts = v; }
    public Database getDatabase() { return database; }
    public Zookeeper getZookeeper() { return zookeeper; }
    public static class Database {
        private String type = "mysql", url, username, password, driverClassName;
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { password = v; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String v) { driverClassName = v; }
    }
    public static class Zookeeper {
        private String connectString, root = "/smartscity-id", authScheme, auth;
        private Duration sessionTimeout = Duration.ofSeconds(15);
        public String getConnectString() { return connectString; }
        public void setConnectString(String v) { connectString = v; }
        public String getRoot() { return root; }
        public void setRoot(String v) { root = v; }
        public Duration getSessionTimeout() { return sessionTimeout; }
        public void setSessionTimeout(Duration v) { sessionTimeout = v; }
        public String getAuthScheme() { return authScheme; }
        public void setAuthScheme(String v) { authScheme = v; }
        public String getAuth() { return auth; }
        public void setAuth(String v) { auth = v; }
    }
}
