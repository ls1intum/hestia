package com.workshopper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class SessionLockedException extends RuntimeException {
    public SessionLockedException(String message) {
        super(message);
    }
}
