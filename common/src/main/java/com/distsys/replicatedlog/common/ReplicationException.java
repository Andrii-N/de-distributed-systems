package com.distsys.replicatedlog.common;

public final class ReplicationException extends RuntimeException {
    
    public ReplicationException(String message) {
        super(message);
    }

    public ReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}