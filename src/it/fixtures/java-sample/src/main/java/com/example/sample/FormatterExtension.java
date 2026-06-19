package com.example.sample;

public class FormatterExtension extends FormatterBase {

    @Override
    public String format(String text) {
        return "[" + text + "]";
    }
}
