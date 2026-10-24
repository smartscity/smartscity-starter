package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Registers a per-instance hook; Spring close and JVM shutdown share the same idempotent path. */
final class ManagedIdGenerator implements IdGenerator {
    private final IdGenerator delegate;
    private final Thread hook;
    private final AtomicBoolean closed = new AtomicBoolean();
    ManagedIdGenerator(IdGenerator delegate) {
        this.delegate = delegate;
        hook = new Thread(this::close, "smartscity-id-shutdown");
        try { Runtime.getRuntime().addShutdownHook(hook); }
        catch (RuntimeException | Error e) {
            try { delegate.close(); } catch (RuntimeException | Error cleanup) { if (cleanup != e) e.addSuppressed(cleanup); }
            throw e;
        }
    }
    public long getUniqueId() { return delegate.getUniqueId(); }
    public IdParts parse(long id) { return delegate.parse(id); }
    public IdSnapshot snapshot() { return delegate.snapshot(); }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { delegate.close(); }
        finally {
            if (Thread.currentThread() != hook) {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException ignored) { /* JVM already shutting down. */ }
            }
        }
    }
}
