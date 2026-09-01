package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelopeSource;

/** Stable, path-free failure vocabulary for filesystem nodes. */
public final class FilesystemNodeException extends RuntimeException implements ErrorEnvelopeSource {
    public enum Reason {
        INVALID_INPUT, PROFILE_UNAVAILABLE, AUTHORITY_REFUSED, OUTSIDE_ROOT, SYMLINK_REFUSED,
        NOT_FOUND, CONFLICT, TOO_LARGE, INVALID_ENCODING, SATURATED, TIMEOUT, TEMPORARY_IO,
        SECURITY_UNSUPPORTED, ATOMIC_REPLACE_UNSUPPORTED, AMBIGUOUS_FINAL_MOVE
    }

    private final Reason reason;

    private FilesystemNodeException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    static FilesystemNodeException of(Reason reason) { return new FilesystemNodeException(reason); }
    static FilesystemNodeException of(Reason reason, Throwable ignored) { return new FilesystemNodeException(reason); }

    @Override public ErrorCode errorCode() {
        return switch (reason) {
            case INVALID_INPUT, OUTSIDE_ROOT, INVALID_ENCODING -> ErrorCode.INVALID_REQUEST;
            case AUTHORITY_REFUSED -> ErrorCode.ACCESS_DENIED;
            case NOT_FOUND -> ErrorCode.UNKNOWN_RESOURCE;
            case CONFLICT -> ErrorCode.CONFLICT;
            case TOO_LARGE, SATURATED -> ErrorCode.REQUEST_LIMIT_EXCEEDED;
            case TIMEOUT, TEMPORARY_IO, AMBIGUOUS_FINAL_MOVE -> ErrorCode.REQUEST_INTERRUPTED;
            case PROFILE_UNAVAILABLE, SYMLINK_REFUSED, SECURITY_UNSUPPORTED, ATOMIC_REPLACE_UNSUPPORTED -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
