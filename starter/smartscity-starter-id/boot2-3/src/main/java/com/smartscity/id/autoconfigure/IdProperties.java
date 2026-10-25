package com.smartscity.id.autoconfigure;

import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.IdLayout;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds Spring configuration and validates immutable generator options before registration.
 *
 * @author jack
 */
@ConfigurationProperties("smartscity.id")
public class IdProperties {
  /** Whole-second epoch shared by generation, decoding and registry validation. */
  private Instant epoch = IdLayout.DEFAULT_EPOCH;

  /** Optional explicit widths; configure all three to override the profile preset. */
  private final Bits bits = new Bits();

  /** Whether automatic generator construction and optional observers are enabled. */
  private boolean enabled = true;

  /** Width preset: standard, high-throughput or javascript; javascript also requires ZooKeeper. */
  private String profile = "standard";

  /** Registry group name, using 1-128 ASCII letters, digits, underscores or hyphens. */
  private String group = "default";

  /** Sequence width from 15 through 17 for the javascript preset. */
  private int javascriptSequenceBits = 15;

  /** Ring capacity, a power of two from 2 through 16777216. */
  private int bufferSize = 16384;

  /** Occupied-capacity percentage from 1 through 99 that wakes the producer. */
  private int lowWaterPercent = 50;

  /** Total caller wait budget from zero through one second. */
  private Duration waitTimeout = Duration.ofMillis(10);

  /** Permitted clock deviation, at least one millisecond. */
  private Duration clockTolerance = Duration.ofSeconds(1);

  /** Caller startup budget, from 100 milliseconds through 300 seconds. */
  private Duration startupTimeout = Duration.ofSeconds(10);

  /** Shared external cleanup budget, from one millisecond through 30 seconds. */
  private Duration shutdownTimeout = Duration.ofSeconds(2);

  /** Legacy binding retained for compatibility; registration never retries an occupied worker. */
  private int workerAttempts = 1024;

  /** JDBC connection selection and database dialect settings. */
  private final Database database = new Database();

  /** Additional session ownership settings for the javascript profile. */
  private final Zookeeper zookeeper = new Zookeeper();

  /** Incarnation identity and optional diagnostic metadata. */
  private final Instance instance = new Instance();

  public Instant getEpoch() {
    return epoch;
  }

  public void setEpoch(Instant value) {
    epoch = value;
  }

  public Bits getBits() {
    return bits;
  }

  /**
   * Explicit format overrides validated together before registration.
   *
   * @author jack
   */
  public static class Bits {
    /** Positive timestamp width in seconds; configure together with worker and sequence. */
    private Integer timestamp;

    /** Positive encoded-worker width; configure together with timestamp and sequence. */
    private Integer worker;

    /** Positive sequence width; total widths must fit 63 bits, or 53 for javascript. */
    private Integer sequence;

    public Integer getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(Integer value) {
      timestamp = value;
    }

    public Integer getWorker() {
      return worker;
    }

    public void setWorker(Integer value) {
      worker = value;
    }

    public Integer getSequence() {
      return sequence;
    }

    public void setSequence(Integer value) {
      sequence = value;
    }
  }

  public Instance getInstance() {
    return instance;
  }

  /**
   * Incarnation identity and optional process hints written during registration.
   *
   * @author jack
   */
  public static class Instance {
    /** Unique incarnation ID; defaults to a new UUID for each generator. */
    private String id;

    /** Diagnostic application name, at most 128 characters; defaults to spring.application.name. */
    private String applicationName;

    /** Optional diagnostic address, at most 64 characters; never an ownership key. */
    private String hostIp;

    /** Optional diagnostic port from 1 through 65535. */
    private Integer hostPort;

    /** Diagnostic host name, at most 255 characters; defaults to HOSTNAME when available. */
    private String hostname;

    public String getId() {
      return id;
    }

    public void setId(String value) {
      id = value;
    }

    public String getApplicationName() {
      return applicationName;
    }

    public void setApplicationName(String value) {
      applicationName = value;
    }

    public String getHostIp() {
      return hostIp;
    }

    public void setHostIp(String value) {
      hostIp = value;
    }

    public Integer getHostPort() {
      return hostPort;
    }

    public void setHostPort(Integer value) {
      hostPort = value;
    }

    public String getHostname() {
      return hostname;
    }

    public void setHostname(String value) {
      hostname = value;
    }
  }

  /**
   * Validates binding values and captures the immutable local generation configuration.
   *
   * @return options independent of subsequent property mutation
   * @throws IllegalArgumentException if lifecycle budgets or profile settings are invalid
   * @throws com.smartscity.id.api.IdException if the identity format or generation options are
   *     invalid
   */
  public GeneratorOptions options() {
    if (!group.matches("[a-zA-Z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("Invalid ID group");
    }
    if (workerAttempts < 1
        || workerAttempts > 65536
        || startupTimeout.toMillis() < 100
        || startupTimeout.toMillis() > 300000
        || shutdownTimeout.toMillis() < 1
        || shutdownTimeout.toMillis() > 30000) {
      throw new IllegalArgumentException("Invalid lifecycle budgets");
    }
    BitsAllocator allocator;
    switch (profile) {
      case "standard":
        allocator = BitsAllocator.standard();
        break;
      case "high-throughput":
        allocator = BitsAllocator.highThroughput();
        break;
      case "javascript":
        allocator = BitsAllocator.javascript(javascriptSequenceBits);
        break;
      default:
        throw new IllegalArgumentException("Unknown ID profile: " + profile);
    }
    if (this.bits.timestamp != null || this.bits.worker != null || this.bits.sequence != null) {
      if (this.bits.timestamp == null || this.bits.worker == null || this.bits.sequence == null) {
        throw new IllegalArgumentException(
            "Set all of smartscity.id.bits.timestamp, worker and sequence");
      }
      allocator =
          new BitsAllocator(
              new IdLayout(epoch, this.bits.timestamp, this.bits.worker, this.bits.sequence));
    } else {
      allocator =
          new BitsAllocator(
              new IdLayout(
                  epoch,
                  allocator.getTimeBits(),
                  allocator.getWorkerBits(),
                  allocator.getSequenceBits()));
    }
    if (profile.equals("javascript")
        && allocator.getTimeBits() + allocator.getWorkerBits() + allocator.getSequenceBits() > 53) {
      throw new IllegalArgumentException("JavaScript ID layout must not exceed 53 bits");
    }
    return GeneratorOptions.builder()
        .bits(allocator)
        .capacity(bufferSize)
        .lowWaterPercent(lowWaterPercent)
        .waitTimeout(waitTimeout)
        .clockTolerance(clockTolerance)
        .build();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getProfile() {
    return profile;
  }

  public void setProfile(String v) {
    profile = v;
  }

  public String getGroup() {
    return group;
  }

  public void setGroup(String v) {
    group = v;
  }

  public int getJavascriptSequenceBits() {
    return javascriptSequenceBits;
  }

  public void setJavascriptSequenceBits(int v) {
    javascriptSequenceBits = v;
  }

  public int getBufferSize() {
    return bufferSize;
  }

  public void setBufferSize(int v) {
    bufferSize = v;
  }

  public int getLowWaterPercent() {
    return lowWaterPercent;
  }

  public void setLowWaterPercent(int v) {
    lowWaterPercent = v;
  }

  public Duration getWaitTimeout() {
    return waitTimeout;
  }

  public void setWaitTimeout(Duration v) {
    waitTimeout = v;
  }

  public Duration getClockTolerance() {
    return clockTolerance;
  }

  public void setClockTolerance(Duration v) {
    clockTolerance = v;
  }

  public Duration getStartupTimeout() {
    return startupTimeout;
  }

  public void setStartupTimeout(Duration v) {
    startupTimeout = v;
  }

  public Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  public void setShutdownTimeout(Duration v) {
    shutdownTimeout = v;
  }

  public int getWorkerAttempts() {
    return workerAttempts;
  }

  public void setWorkerAttempts(int v) {
    workerAttempts = v;
  }

  public Database getDatabase() {
    return database;
  }

  public Zookeeper getZookeeper() {
    return zookeeper;
  }

  /**
   * JDBC binding for mutually exclusive URL connections or application DataSources.
   *
   * @author jack
   */
  public static class Database {
    /** Opts in to resolving the application's unique or primary DataSource. */
    private boolean useApplicationDataSource;

    public boolean isUseApplicationDataSource() {
      return useApplicationDataSource;
    }

    public void setUseApplicationDataSource(boolean value) {
      useApplicationDataSource = value;
    }

    /** SQL dialect: mysql, postgresql or tidb. */
    private String type = "mysql";

    /** Dedicated JDBC URL; mutually exclusive with an application DataSource. */
    private String url;

    /** Dedicated connection username, as required by the driver and account. */
    private String username;

    /** Dedicated connection secret; must not be logged or used as a metric label. */
    private String password;

    /** Optional JDBC driver class; defaults to driver discovery. */
    private String driverClassName;

    public String getType() {
      return type;
    }

    public void setType(String v) {
      type = v;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String v) {
      url = v;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String v) {
      username = v;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String v) {
      password = v;
    }

    public String getDriverClassName() {
      return driverClassName;
    }

    public void setDriverClassName(String v) {
      driverClassName = v;
    }
  }

  /**
   * Session coordination settings required by the javascript profile.
   *
   * @author jack
   */
  public static class Zookeeper {
    /** Nonempty ZooKeeper connection string required by the javascript profile. */
    private String connectString;

    /** Absolute path with letter, digit, underscore or hyphen segments. */
    private String root = "/smartscity-id";

    /** Optional authentication scheme; configure together with authentication data. */
    private String authScheme;

    /** Optional authentication data; configure together with the scheme and never log it. */
    private String auth;

    /** Requested session timeout from one through 300 seconds; the server may negotiate it. */
    private Duration sessionTimeout = Duration.ofSeconds(15);

    public String getConnectString() {
      return connectString;
    }

    public void setConnectString(String v) {
      connectString = v;
    }

    public String getRoot() {
      return root;
    }

    public void setRoot(String v) {
      root = v;
    }

    public Duration getSessionTimeout() {
      return sessionTimeout;
    }

    public void setSessionTimeout(Duration v) {
      sessionTimeout = v;
    }

    public String getAuthScheme() {
      return authScheme;
    }

    public void setAuthScheme(String v) {
      authScheme = v;
    }

    public String getAuth() {
      return auth;
    }

    public void setAuth(String v) {
      auth = v;
    }
  }
}
