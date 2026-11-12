package com.smartscity.observability.http.jakarta;

import com.smartscity.observability.CaptureBudget;
import com.smartscity.observability.http.ExchangeObservation;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.tracing.CorrelationScope;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/**
 * Servlet-generation adapter; the exchange attribute survives error and async redispatch.
 *
 * @author jack
 */
public final class ObservationFilter implements Filter {
  public static final String ATTRIBUTE = ObservationFilter.class.getName() + ".state";
  private final ObservationRuntime runtime;

  public ObservationFilter(ObservationRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    State state = (State) req.getAttribute(ATTRIBUTE);
    if (state == null) {
      String header = req.getHeader("traceparent");
      java.util.Enumeration<String> headers = req.getHeaders("traceparent");
      if (headers != null && headers.hasMoreElements()) {
        headers.nextElement();
        if (headers.hasMoreElements()) {
          header = null;
        }
      }
      ExchangeObservation exchange =
          new ExchangeObservation(
              runtime,
              runtime.server(header, req.getHeader("X-Smartscity-Caller"), req.getRemoteAddr()),
              req.getRequestURI(),
              req.getRemoteAddr(),
              false);
      state = new State(exchange);
      req.setAttribute(ATTRIBUTE, state);
    }
    Request wrappedRequest = new Request(req, state);
    Response wrappedResponse = new Response(res, state, req.getRequestURI());
    state.response = wrappedResponse;
    try (CorrelationScope scope = CorrelationScope.open(state.exchange.context())) {
      try {
        chain.doFilter(wrappedRequest, wrappedResponse);
      } catch (IOException | ServletException | RuntimeException | Error e) {
        state.exchange.fail();
        state.thrown = true;
        throw e;
      } finally {
        if (req.isAsyncStarted()) {
          try {
            req.getAsyncContext().addListener(new Completion(state));
          } catch (IllegalStateException e) {
            state.finish();
          }
        } else if (!state.thrown && !state.errorSent) {
          state.finish();
        }
      }
    }
  }

  /**
   * Request-lifetime state; mutable terminal information is owned by this exchange only.
   *
   * @author jack
   */
  public static final class State {
    private final ExchangeObservation exchange;
    private volatile Response response;
    private volatile boolean requestComplete;
    private volatile boolean thrown;
    private volatile boolean errorSent;

    private State(ExchangeObservation e) {
      exchange = e;
    }

    public void finish() {
      Response r = response;
      exchange.finish(r == null ? 0 : r.getStatus(), requestComplete, !thrown);
    }
  }

  private final class Request extends HttpServletRequestWrapper {
    private final State state;
    private ServletInputStream stream;
    private BufferedReader reader;
    private boolean usedStream;

    Request(HttpServletRequest req, State state) {
      super(req);
      this.state = state;
    }

    private ServletInputStream input() throws IOException {
      if (stream == null) {
        ServletInputStream delegate = super.getInputStream();
        CaptureBudget.Capture capture =
            state.exchange.request(
                getRequestURI(), getContentType(), getHeader("Content-Encoding"));
        if (capture == null) {
          stream = delegate;
          return stream;
        }
        stream =
            new ServletInputStream() {
              long count;

              @Override
              public int read() throws IOException {
                int v = delegate.read();
                if (v < 0) {
                  state.requestComplete = true;
                } else {
                  count++;
                  if (capture != null) {
                    capture.append(v);
                  }
                  check();
                }
                return v;
              }

              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                int n = delegate.read(b, off, len);
                if (n < 0) {
                  state.requestComplete = true;
                } else {
                  count += n;
                  if (capture != null) {
                    capture.append(b, off, n);
                  }
                  check();
                }
                return n;
              }

              private void check() {
                if (getContentLengthLong() >= 0 && count == getContentLengthLong()) {
                  state.requestComplete = true;
                }
              }

              @Override
              public boolean isFinished() {
                return delegate.isFinished();
              }

              @Override
              public boolean isReady() {
                return delegate.isReady();
              }

              @Override
              public void setReadListener(ReadListener listener) {
                delegate.setReadListener(listener);
              }

              @Override
              public void close() throws IOException {
                delegate.close();
              }
            };
      }
      return stream;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (reader != null) {
        throw new IllegalStateException("Reader already selected");
      }
      usedStream = true;
      return input();
    }

    @Override
    public BufferedReader getReader() throws IOException {
      if (usedStream) {
        throw new IllegalStateException("Stream already selected");
      }
      if (reader == null) {
        String encoding = getCharacterEncoding();
        reader =
            new BufferedReader(
                new InputStreamReader(input(), encoding == null ? "ISO-8859-1" : encoding));
      }
      return reader;
    }
  }

  private final class Response extends HttpServletResponseWrapper {
    private final State state;
    private final String path;
    private ServletOutputStream stream;
    private PrintWriter writer;

    Response(HttpServletResponse res, State state, String path) {
      super(res);
      this.state = state;
      this.path = path;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      if (!runtime.captures(path)) {
        return super.getOutputStream();
      }
      if (stream == null) {
        ServletOutputStream delegate = super.getOutputStream();
        stream =
            new ServletOutputStream() {
              @Override
              public void write(int v) throws IOException {
                delegate.write(v);
                CaptureBudget.Capture c =
                    state.exchange.response(path, getContentType(), getHeader("Content-Encoding"));
                if (c != null) {
                  c.append(v);
                }
              }

              @Override
              public void write(byte[] b, int off, int len) throws IOException {
                delegate.write(b, off, len);
                CaptureBudget.Capture c =
                    state.exchange.response(path, getContentType(), getHeader("Content-Encoding"));
                if (c != null) {
                  c.append(b, off, len);
                }
              }

              @Override
              public void flush() throws IOException {
                delegate.flush();
              }

              @Override
              public void close() throws IOException {
                delegate.close();
              }

              @Override
              public boolean isReady() {
                return delegate.isReady();
              }

              @Override
              public void setWriteListener(WriteListener listener) {
                delegate.setWriteListener(listener);
              }
            };
      }
      return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
      if (!runtime.captures(path)) {
        return super.getWriter();
      }
      if (writer == null) {
        PrintWriter delegate = super.getWriter();
        java.io.Writer capture =
            new java.io.OutputStreamWriter(
                new java.io.OutputStream() {
                  @Override
                  public void write(int value) {
                    byte[] one = {(byte) value};
                    write(one, 0, 1);
                  }

                  @Override
                  public void write(byte[] bytes, int offset, int length) {
                    CaptureBudget.Capture c =
                        state.exchange.response(
                            path, getContentType(), getHeader("Content-Encoding"));
                    if (c != null) {
                      c.append(bytes, offset, length);
                    }
                  }
                },
                java.nio.charset.StandardCharsets.UTF_8);
        writer =
            new PrintWriter(
                new java.io.Writer() {
                  @Override
                  public void write(char[] chars, int offset, int length) throws IOException {
                    delegate.write(chars, offset, length);
                    capture.write(chars, offset, length);
                    capture.flush();
                  }

                  @Override
                  public void flush() throws IOException {
                    delegate.flush();
                    capture.flush();
                  }

                  @Override
                  public void close() throws IOException {
                    delegate.close();
                    capture.close();
                  }
                }) {
              @Override
              public boolean checkError() {
                return delegate.checkError() || super.checkError();
              }
            };
      }
      return writer;
    }

    @Override
    public void resetBuffer() {
      super.resetBuffer();
      state.exchange.resetResponse();
    }

    @Override
    public void reset() {
      super.reset();
      stream = null;
      writer = null;
      state.errorSent = false;
      state.exchange.resetResponse();
    }

    @Override
    public void sendError(int status) throws IOException {
      state.exchange.resetResponse();
      state.errorSent = true;
      super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      state.exchange.resetResponse();
      state.errorSent = true;
      super.sendError(status, message);
    }
  }

  private static final class Completion implements AsyncListener {
    private final State state;

    Completion(State s) {
      state = s;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      state.finish();
    }

    @Override
    public void onError(AsyncEvent event) {
      state.exchange.fail();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      state.exchange.fail();
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
      event.getAsyncContext().addListener(this);
    }
  }
}
