package com.example.sample;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class LombokDto {
    private final String id;
    private final String key;
}
