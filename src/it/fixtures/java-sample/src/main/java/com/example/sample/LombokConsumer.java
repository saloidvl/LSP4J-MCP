package com.example.sample;

public class LombokConsumer {
    public String describe() {
        LombokDto dto = LombokDto.builder().id("1").key("k").build();
        return dto.getId() + dto.getKey();
    }
}
