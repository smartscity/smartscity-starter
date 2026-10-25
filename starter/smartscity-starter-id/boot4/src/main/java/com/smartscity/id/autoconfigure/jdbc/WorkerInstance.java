package com.smartscity.id.autoconfigure.jdbc;

import java.util.UUID;

/**
 * Immutable identity and optional operational hints for one generator incarnation. Host addresses
 * are diagnostic, not ownership keys. Reusing an explicit instance ID is rejected.
 *
 * @author jack
 */
public final class WorkerInstance {
  private final String instanceId;
  private final String applicationName;
  private final String hostIp;
  private final Integer hostPort;
  private final String hostname;

  /**
   * Creates one incarnation identity and validates only metadata that is present.
   *
   * @param instanceId explicit ASCII identity, or null to generate a fresh UUID
   * @param applicationName optional diagnostic name, at most 128 characters
   * @param hostIp optional diagnostic address, at most 64 characters
   * @param hostPort optional port from 1 through 65535
   * @param hostname optional diagnostic hostname, at most 255 characters
   * @throws IllegalArgumentException if supplied metadata is blank, too long or out of range
   */
  public WorkerInstance(
      String instanceId, String applicationName, String hostIp, Integer hostPort, String hostname) {
    this.instanceId =
        instanceId == null ? UUID.randomUUID().toString() : text(instanceId, 128, "instance.id");
    if (!this.instanceId.matches("[a-zA-Z0-9_.:-]{1,128}")) {
      throw new IllegalArgumentException(
          "instance.id must contain 1..128 ASCII letters, digits, underscores, dots, colons or hyphens");
    }
    this.applicationName = text(applicationName, 128, "instance.application-name");
    this.hostIp = text(hostIp, 64, "instance.host-ip");
    this.hostname = text(hostname, 255, "instance.hostname");
    if (hostPort != null && (hostPort < 1 || hostPort > 65535)) {
      throw new IllegalArgumentException("instance.host-port must be 1..65535 or absent");
    }
    this.hostPort = hostPort;
  }

  private static String text(String value, int length, String property) {
    if (value != null && (value.trim().isEmpty() || value.length() > length)) {
      throw new IllegalArgumentException(
          property + " must be nonblank and at most " + length + " characters");
    }
    return value;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public String getApplicationName() {
    return applicationName;
  }

  public String getHostIp() {
    return hostIp;
  }

  public Integer getHostPort() {
    return hostPort;
  }

  public String getHostname() {
    return hostname;
  }
}
