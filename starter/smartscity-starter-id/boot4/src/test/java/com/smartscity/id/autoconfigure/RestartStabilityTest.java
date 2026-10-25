package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies non-reuse through repeated startup, process termination and restart.
 *
 * @author jack
 */
@EnabledIfSystemProperty(named = "id.test.restart", matches = "true")
class RestartStabilityTest {
  @TempDir Path directory;

  @Test
  @Timeout(120)
  void thousandSdkRestartsReleaseConnectionsThreadsAndShutdownHookReferences() throws Exception {
    RestartDatabase database = new RestartDatabase();
    String group = "cycles_" + System.nanoTime();
    Set<Long> ids = new HashSet<>();
    Set<Long> rawWorkers = new HashSet<>();
    Set<Long> workers = new HashSet<>();

    List<WeakReference<IdGenerator>> closed = new ArrayList<>();
    for (int cycle = 0; cycle < 1000; cycle++) {
      closed.add(cycle(database, group, ids, rawWorkers, workers));
      assertEquals(0, database.active.get());
      if (cycle % 100 == 99) {
        RestartDatabase.noSdkThreads();
      }
    }
    assertEquals(16000, ids.size());
    assertEquals(1000, rawWorkers.size());
    assertEquals(1000, workers.size());
    assertEquals(1000, database.rows(group, "STOPPED"));
    assertEquals(0, database.rows(group, "ACTIVE"));
    // Public reachability probe: a registered hook retaining a closed generator fails this check.
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (closed.stream().anyMatch(reference -> reference.get() != null)
        && System.nanoTime() < end) {
      System.gc();
      Thread.sleep(50);
    }
    assertTrue(
        closed.stream().allMatch(reference -> reference.get() == null),
        "Closed generators retained, possibly by hooks");
    RestartDatabase.noSdkThreads();
    assertEquals(0, database.active.get());
    System.out.println(
        "RESTART sdkCycles=1000 ids=16000 uniqueWorkers=1000 retained=0 connections=0 sdkThreads=0");
  }

  private WeakReference<IdGenerator> cycle(
      RestartDatabase database,
      String group,
      Set<Long> ids,
      Set<Long> rawWorkers,
      Set<Long> workers)
      throws Exception {
    IdGenerator generator =
        new IdGeneratorFactory().create(database.suppliedConfig(group), database.tracked);
    try {
      assertTrue(rawWorkers.add(generator.snapshot().getRawWorkerId()));
      assertTrue(workers.add(generator.snapshot().getWorkerId()));
      int startupConnections = database.opened.get();
      for (int i = 0; i < 16; i++) {
        assertTrue(ids.add(generator.getUniqueId()), "Duplicate across SDK restart");
      }
      assertEquals(startupConnections, database.opened.get(), "Runtime JDBC access");
    } finally {
      generator.close();
    }
    generator.close();
    assertEquals(
        IdException.Reason.CLOSED,
        assertThrows(IdException.class, generator::getUniqueId).getReason());
    return new WeakReference<>(generator);
  }

  @Test
  @Timeout(150)
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void rollingProcessesSurviveTwentyAlternatingTermAndKillReplacements() throws Exception {
    RestartDatabase database = new RestartDatabase();
    String group = "rolling_" + System.nanoTime();
    Set<Long> ids = new HashSet<>();
    Set<Long> workers = new HashSet<>();

    List<Process> processes = new ArrayList<>();
    try {
      Process current = child(database, group, 0);
      processes.add(current);
      long raw = read(current, 0, ids, workers);
      for (int round = 1; round <= 20; round++) {
        Process next = child(database, group, round);
        processes.add(next);
        long nextRaw = read(next, round, ids, workers);
        assertTrue(current.isAlive(), "Previous SDK must overlap the new SDK startup");
        assertTrue(nextRaw > raw);
        boolean force = round % 2 == 0;
        if (force) {
          current.destroyForcibly();
        } else {
          current.destroy();
        }
        assertTrue(current.waitFor(8, TimeUnit.SECONDS), "Old SDK did not exit");
        assertEquals(force ? "ACTIVE" : "STOPPED", database.state(raw));
        current = next;
        raw = nextRaw;
      }
      Files.createFile(directory.resolve("20.release"));
      assertTrue(current.waitFor(8, TimeUnit.SECONDS));
      assertEquals(0, current.exitValue());
      List<String> after = Files.readAllLines(directory.resolve("20.ids.after"));
      assertEquals(128, after.size());
      for (String id : after) {
        assertTrue(ids.add(Long.parseLong(id)), "Duplicate after last replacement");
      }
      assertEquals("STOPPED", database.state(raw));
      assertEquals(11, database.rows(group, "STOPPED"));
      assertEquals(10, database.rows(group, "ACTIVE"));
      assertEquals(2816, ids.size());
      assertEquals(21, workers.size());
      System.out.println(
          "ROLLING replacements=20 processes=21 term=10 kill=10 ids=2816 duplicates=0");
    } finally {
      for (Process process : processes) {
        process.destroyForcibly();
      }
      for (Process process : processes) {
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Child process leaked");
      }
    }
    assertEquals(0, database.active.get());
    RestartDatabase.noSdkThreads();
  }

  private Process child(RestartDatabase database, String group, int index) throws Exception {
    return new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Xmx128m",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            SdkProcess.class.getName(),
            database.url,
            group,
            directory.resolve(index + ".ids").toString(),
            directory.resolve(index + ".release").toString())
        .redirectErrorStream(true)
        .redirectOutput(directory.resolve(index + ".txt").toFile())
        .start();
  }

  private long read(Process process, int index, Set<Long> ids, Set<Long> workers) throws Exception {
    Path output = directory.resolve(index + ".ids");
    RestartDatabase.await(
        () -> Files.exists(output) || !process.isAlive(), 12, "Child startup timed out");
    assertTrue(Files.exists(output), "Child startup failed: " + directory.resolve(index + ".txt"));
    List<String> lines = Files.readAllLines(output);
    assertEquals(129, lines.size());
    long raw = Long.parseLong(lines.get(0));
    assertTrue(workers.add(raw));
    for (String id : lines.subList(1, lines.size())) {
      assertTrue(ids.add(Long.parseLong(id)), "Duplicate across processes");
    }
    return raw;
  }
}
