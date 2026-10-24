package com.smartscity.id.api;

/** One SDK instance owns one worker. Concurrent completion order is not ID order. */
public interface IdGenerator extends AutoCloseable {
    long getUniqueId();
    IdParts parse(long id);
    IdSnapshot snapshot();
    @Override void close();
}
