package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only process entry. Data goes to files, not logs.
 *
 * @author jack
 */
public final class SdkProcess {
  public static void main(String[] args) throws Exception {
    IdProperties p = new IdProperties();
    p.setGroup(args[1]);
    p.setBufferSize(256);
    p.getDatabase().setUrl(args[0]);
    p.getDatabase().setUsername("root");
    try (IdGenerator g = new IdGeneratorFactory().create(p, null)) {
      List<String> output = new ArrayList<>();
      output.add(Long.toString(g.snapshot().getRawWorkerId()));
      for (int i = 0; i < 128; i++) {
        output.add(Long.toString(g.getUniqueId()));
      }
      Path pending = Paths.get(args[2] + ".pending");
      Files.write(pending, output);
      Files.move(pending, Paths.get(args[2]), StandardCopyOption.ATOMIC_MOVE);
      if (args.length > 3) {
        Path signal = Paths.get(args[3]);
        while (!Files.exists(signal)) {
          Thread.sleep(10);
        }
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
          after.add(Long.toString(g.getUniqueId()));
        }
        Files.write(Paths.get(args[2] + ".after"), after);
      }
    }
  }
}
