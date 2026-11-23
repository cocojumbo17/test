package ua.probe.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RetryPolicyTest {

    @Test
    public void normalIntervalAfterASuccessfulRead() {
        assertEquals(30_000, RetryPolicy.nextDelayMs(0));
    }

    @Test
    public void quickRetriesDoubleAfterEachFailure() {
        assertEquals(2_000, RetryPolicy.nextDelayMs(1));
        assertEquals(4_000, RetryPolicy.nextDelayMs(2));
        assertEquals(8_000, RetryPolicy.nextDelayMs(3));
        assertEquals(16_000, RetryPolicy.nextDelayMs(4));
    }

    @Test
    public void retriesNeverExceedTheNormalInterval() {
        assertEquals(30_000, RetryPolicy.nextDelayMs(5));
        assertEquals(30_000, RetryPolicy.nextDelayMs(1_000));
    }
}
