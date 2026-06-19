package com.example.sample;

public class App {

    public static void main(String[] args) {
        Greeter greeter = new GreeterImpl();
        String message = greeter.greet("World");
        System.out.println(message);
    }
}
