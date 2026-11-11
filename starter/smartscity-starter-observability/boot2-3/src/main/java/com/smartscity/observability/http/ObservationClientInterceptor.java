package com.smartscity.observability.http;

import com.smartscity.observability.CaptureBudget;
import com.smartscity.observability.CorrelationContext;
import com.smartscity.observability.tracing.CorrelationScope;
import com.smartscity.observability.tracing.TracerAccess;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Observes RestTemplate bodies without eager reads; a proxy preserves Spring 5/6 status ABI.
 *
 * @author jack
 */
public final class ObservationClientInterceptor implements ClientHttpRequestInterceptor {
  private final ObservationRuntime runtime;

  public ObservationClientInterceptor(ObservationRuntime r) {
    runtime = r;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String host = request.getURI().getHost();
    String path = request.getURI().getPath();
    TracerAccess.Child providerChild = null;
    CorrelationContext context;
    try {
      if (!runtime.properties().getCorrelation().isEnabled()) {
        context = null;
      } else if (runtime.tracer() != null) {
        context = runtime.tracer().current(runtime.service(), host);
        if (context == null || !runtime.tracer().ownsClientRequest(request)) {
          providerChild = runtime.tracer().child(runtime.service(), host);
          context = providerChild.context();
        }
      } else {
        context = runtime.client(host);
      }
    } catch (RuntimeException e) {
      runtime.recordFailure();
      context = null;
    }
    ExchangeObservation exchange = new ExchangeObservation(runtime, context, path, host, true);
    if (providerChild != null) {
      TracerAccess.Child child = providerChild;
      exchange.onCompletion(child::finish);
    }
    try (CorrelationScope scope = CorrelationScope.open(context)) {
      CaptureBudget.Capture captured =
          exchange.request(
              path,
              request.getHeaders().getFirst("Content-Type"),
              request.getHeaders().getFirst("Content-Encoding"));
      if (captured != null) {
        captured.append(body, 0, body.length);
      }
      if (context != null
          && runtime.properties().getCorrelation().getOutboundHosts().contains(host)) {
        request.getHeaders().set("traceparent", context.traceparent());
        request.getHeaders().set("X-Smartscity-Caller", runtime.service());
      }
      ClientHttpResponse response = execution.execute(request, body);
      return wrap(response, exchange, path);
    } catch (IOException | RuntimeException | Error e) {
      exchange.fail();
      exchange.finish(0, true, false);
      throw e;
    } finally {
      if (providerChild != null) {
        try {
          providerChild.close();
        } catch (RuntimeException e) {
          runtime.recordFailure();
        }
      }
    }
  }

  private ClientHttpResponse wrap(
      ClientHttpResponse response, ExchangeObservation exchange, String path) {
    return (ClientHttpResponse)
        Proxy.newProxyInstance(
            ClientHttpResponse.class.getClassLoader(),
            new Class<?>[] {ClientHttpResponse.class},
            new java.lang.reflect.InvocationHandler() {
              private InputStream stream;
              private boolean eof;
              private boolean intact = true;
              private final AtomicBoolean closed = new AtomicBoolean();

              private int status() {
                try {
                  Object status =
                      ClientHttpResponse.class.getMethod("getStatusCode").invoke(response);
                  return (Integer)
                      ClientHttpResponse.class
                          .getMethod("getStatusCode")
                          .getReturnType()
                          .getMethod("value")
                          .invoke(status);
                } catch (ReflectiveOperationException e) {
                  return 0;
                }
              }

              @Override
              public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                  throws Throwable {
                if (method.getName().equals("getBody")) {
                  if (stream == null) {
                    InputStream original;
                    try {
                      original = response.getBody();
                    } catch (IOException | RuntimeException | Error e) {
                      exchange.fail();
                      exchange.finish(status(), true, false);
                      throw e;
                    }
                    CaptureBudget.Capture capture =
                        exchange.response(
                            path,
                            response.getHeaders().getFirst("Content-Type"),
                            response.getHeaders().getFirst("Content-Encoding"));
                    stream =
                        new FilterInputStream(original) {
                          @Override
                          public int read() throws IOException {
                            try {
                              int b = in.read();
                              if (b < 0) {
                                done();
                              } else if (capture != null) {
                                capture.append(b);
                              }
                              return b;
                            } catch (IOException e) {
                              failure();
                              throw e;
                            }
                          }

                          @Override
                          public int read(byte[] b, int off, int len) throws IOException {
                            try {
                              int n = in.read(b, off, len);
                              if (n < 0) {
                                done();
                              } else if (capture != null) {
                                capture.append(b, off, n);
                              }
                              return n;
                            } catch (IOException e) {
                              failure();
                              throw e;
                            }
                          }

                          @Override
                          public long skip(long n) throws IOException {
                            long skipped = super.skip(n);
                            if (skipped > 0) {
                              intact = false;
                            }
                            return skipped;
                          }

                          @Override
                          public synchronized void reset() throws IOException {
                            super.reset();
                            intact = false;
                          }

                          private void done() {
                            eof = true;
                            exchange.finish(status(), true, intact);
                          }

                          private void failure() {
                            exchange.fail();
                            exchange.finish(status(), true, false);
                          }

                          @Override
                          public void close() throws IOException {
                            try {
                              super.close();
                            } finally {
                              exchange.finish(status(), true, eof && intact);
                            }
                          }
                        };
                  }
                  return stream;
                }
                if (method.getName().equals("close")) {
                  if (closed.compareAndSet(false, true)) {
                    try {
                      response.close();
                    } finally {
                      exchange.finish(status(), true, eof && intact);
                    }
                  }
                  return null;
                }
                try {
                  return method.invoke(response, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              }
            });
  }
}
