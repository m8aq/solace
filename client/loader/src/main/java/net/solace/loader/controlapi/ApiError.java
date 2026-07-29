package net.solace.loader.controlapi;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class ApiError {
    private final String code;
    private final String message;
}
