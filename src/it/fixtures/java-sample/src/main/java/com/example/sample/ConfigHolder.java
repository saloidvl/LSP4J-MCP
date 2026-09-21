package com.example.sample;

public class ConfigHolder {
    private final CommonConfigDto common;

    public ConfigHolder(CommonConfigDto common) {
        this.common = common;
    }

    public CommonConfigDto getCommon() {
        return common;
    }
}
