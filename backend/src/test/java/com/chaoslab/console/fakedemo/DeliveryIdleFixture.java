package com.chaoslab.console.fakedemo;

/** Harmless unrelated Java sentinel for release shutdown tests; never packaged. */
public final class DeliveryIdleFixture {
    private DeliveryIdleFixture() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 0) throw new IllegalArgumentException("No arguments");
        Thread.sleep(600_000);
    }
}
