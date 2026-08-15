package com.wuwa.config.manager.data;

public final class OperationException extends Exception {
    public OperationException(String message) {
        super(message);
    }

    public OperationException(String message, Throwable cause) {
        super(message, cause);
    }
}

