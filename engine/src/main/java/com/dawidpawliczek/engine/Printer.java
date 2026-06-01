package com.dawidpawliczek.engine;

public class Printer {
    private final String message;

    public Printer(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void printMessage() {
        System.out.println(message);
    }
}
