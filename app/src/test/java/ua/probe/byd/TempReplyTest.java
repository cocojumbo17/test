package ua.probe.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TempReplyTest {

    @Test
    public void parsesCabinAndOutsideTemperatures() {
        TempReply reply = TempReply.parse("TEMPS in=15 out=2");

        assertEquals(Integer.valueOf(15), reply.inside);
        assertEquals(Integer.valueOf(2), reply.outside);
        assertEquals("ok", reply.status());
    }

    @Test
    public void parsesNegativeTemperatures() {
        TempReply reply = TempReply.parse("TEMPS in=-5 out=-12");

        assertEquals(Integer.valueOf(-5), reply.inside);
        assertEquals(Integer.valueOf(-12), reply.outside);
    }

    @Test
    public void dropsBydErrorCodes() {
        TempReply reply = TempReply.parse("TEMPS in=-2147482648 out=195");

        assertNull(reply.inside);
        assertNull(reply.outside);
        assertFalse(reply.hasValues());
        assertEquals("немає значень", reply.status());
    }

    @Test
    public void keepsTheValidValueWhenTheOtherIsAnErrorCode() {
        TempReply reply = TempReply.parse("TEMPS in=65535 out=7");

        assertNull(reply.inside);
        assertEquals(Integer.valueOf(7), reply.outside);
        assertTrue(reply.hasValues());
    }

    @Test
    public void reportsTheErrorReason() {
        TempReply reply = TempReply.parse("TEMPS error=SecurityException: denied");

        assertFalse(reply.hasValues());
        assertEquals("SecurityException: denied", reply.status());
    }

    @Test
    public void formattedValuesParseBack() {
        TempReply reply = TempReply.parse(TempReply.formatValues(15, 2));

        assertEquals(Integer.valueOf(15), reply.inside);
        assertEquals(Integer.valueOf(2), reply.outside);
    }

    @Test
    public void formattedErrorParsesBack() {
        assertEquals("boom", TempReply.parse(TempReply.formatError("boom")).status());
    }

    @Test
    public void recognisesOnlyReplyLines() {
        assertTrue(TempReply.isReplyLine("TEMPS in=1 out=2"));
        assertFalse(TempReply.isReplyLine("ShellMain uid=2000"));
        assertFalse(TempReply.isReplyLine(null));
    }
}
