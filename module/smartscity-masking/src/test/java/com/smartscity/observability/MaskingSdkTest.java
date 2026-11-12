package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exercises pure Java business use, defensive copying and shared policy concurrency.
 *
 * @author jack
 */
class MaskingSdkTest {
  @Test
  void immutableBusinessPolicyNeedsNoFramework() throws Exception {
    List<String> fields = new ArrayList<>(Arrays.asList("account"));
    List<String> paths = new ArrayList<>(Arrays.asList("/payment/account"));
    List<String> literals = new ArrayList<>(Arrays.asList("abc,def", "token"));
    MaskingPolicy policy = new MaskingPolicy(true, fields, paths, literals);
    fields.clear();
    paths.clear();
    literals.clear();
    assertTrue(policy.masksField("ACCOUNT"));
    assertTrue(policy.masksPath("/payment/account"));
    assertFalse(policy.masksField(null));
    assertEquals("account=***", policy.text().sanitize("account=123"));
    assertEquals("password=***", policy.text().sanitize("password=abc,def"));
    assertEquals("***=***", policy.text().sanitize("token=secret"));
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("org.springframework.context.ApplicationContext"));
    assertThrows(ClassNotFoundException.class, () -> Class.forName("org.slf4j.Logger"));
    java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      ArrayList<java.util.concurrent.Future<?>> tasks =
          new ArrayList<java.util.concurrent.Future<?>>();
      for (int n = 0; n < 20; n++) {
        tasks.add(
            pool.submit(
                () -> {
                  for (int k = 0; k < 100; k++) {
                    assertEquals("account=***", policy.text().sanitize("account=123"));
                    assertEquals("password=***", policy.text().sanitize("password=abc,def"));
                  }
                }));
      }
      for (java.util.concurrent.Future<?> task : tasks) {
        task.get(5, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void invalidPoliciesFailBeforeUseAndDisabledPolicyStillBoundsInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MaskingPolicy(true, List.of("bad key"), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MaskingPolicy(true, List.of(), List.of("/bad~2path"), List.of()));
    MaskingPolicy off =
        new MaskingPolicy(false, List.of("account"), List.of(""), List.of("secret"));
    assertFalse(off.masksField("account"));
    assertFalse(off.masksPath(""));
    assertEquals("secret", off.text().sanitize("secret"));
    assertEquals(
        "[omitted:limit]",
        off.text().sanitize(String.join("", java.util.Collections.nCopies(65537, "x"))));
  }
}
