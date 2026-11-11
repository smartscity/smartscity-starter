package com.smartscity.samples.observability;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer example; supply the owning user guide's application configuration explicitly.
 *
 * @author jack
 */
@SpringBootApplication
@RestController
public class ObservabilityApplication {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(ObservabilityApplication.class);
    app.setDefaultProperties(
        java.util.Collections.singletonMap("spring.application.name", "observability-sample"));
    app.run(args);
  }

  @PostMapping("/echo")
  public Map<String, Object> echo(@RequestBody Map<String, Object> value) {
    return value;
  }
}
