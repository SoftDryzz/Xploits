package com.xploits.bench;

/**
 * Something the bench could not do (a command rejected, a module missing, a timeout): the run is ERROR.
 * Its message is written by the bench and goes to the report as it is, so it never carries a position,
 * a command's text or another exception's message.
 */
public class BenchException extends RuntimeException {
    public BenchException(String message) {
        super(message);
    }
}
