package com.example.sample;

public class GreeterImpl implements Greeter {

    @Override
    public String greet(String name) {
        return MessageHelper.buildMessage(name);
    }
}
