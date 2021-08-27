package com.freighttrust.ediq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EDIqTest {

    EDIq target;
    String filePath;
    String schemaPath;

    @BeforeEach
    void setUp() throws URISyntaxException {
        target = new EDIq();
        filePath = Paths.get(getClass().getResource("/simple999.edi").toURI()).toString();
        schemaPath = Paths.get(getClass().getResource("/EDISchema999.xml").toURI()).toString();
    }

    @Test
    void testMissingFilePrintsMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        assertThrows(EDIq.InvalidCommandLineException.class, () -> target.execute(new String[] { "--epath", "//*" }));
        assertEquals(EDIq.MSG_MISSING_FILE + '\n', new String(output.toByteArray()));
    }

    @Test
    void testInvalidOptionsPrintsMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        assertThrows(EDIq.InvalidCommandLineException.class, () -> target.execute(new String[] { "--junk", "//*" }));
        assertEquals("Unrecognized option: --junk" + '\n', new String(output.toByteArray()));
    }

    @Test
    void testSelectComponentsCTX01_02() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        target.execute(new String[] { "--epath", "//*[local-name() = 'CTX01-02']", filePath });
        assertEquals("123456789\n2\n3\n987654321\n", new String(output.toByteArray()));
    }

    @ParameterizedTest
    @CsvSource({
        "--format, IK4*2*782*1~\\n\\n", // Additional new line
        " , IK4*2*782*1~\\n",
    })
    void testSchemaInputSelectLoopL0003(String formatOpt, String expectation) {
        // L0003 is the IK4 loop
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        List<String> args = new ArrayList<>(Arrays.asList("--epath", "//*[local-name() = 'L0003']", "--schema-file", schemaPath, filePath));
        if (formatOpt != null) {
            args.add(formatOpt);
        }
        target.execute(args.toArray(new String[args.size()]));
        assertEquals(expectation.replaceAll("\\\\n", "\n"), new String(output.toByteArray()));
    }

    @Test
    void testSelectNestedLoopsWithSchema() {
        // L0002 contains loop L0003
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        target.execute(new String[] { "--format", "--epath", "//*[local-name() = 'L0002']", "--schema-file", schemaPath, filePath });
        assertEquals("IK3*CLM*22**8~\n"
                + "CTX*CLM01:123456789~\n"
                + "IK4*2*782*1~\n"
                + "\n"
                + "IK3*REF*57**3~\n"
                + "CTX*SITUATIONAL TRIGGER^SITUATIONAL TRIGGER:2^SITUATIONAL TRIGGER:3*CLM*43**5:3*1325~\n"
                + "CTX*CLM01:987654321~\n"
                + "\n", new String(output.toByteArray()));
    }

    @Test
    void testSelectAllAK2Segments() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        target.execute(new String[] { "--epath", "//*[local-name() = 'AK2']", filePath });
        assertEquals("AK2*837*0001~\n"
                + "AK2*837*0002~\n"
                + "AK2*837*0003~\n", new String(output.toByteArray()));
    }

    @Test
    void testSelectCompositeElementCTX05() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        target.out = new PrintStream(output, true);
        target.execute(new String[] { "--epath", "//*[local-name() = 'CTX05']", filePath });
        assertEquals("5:3\n", new String(output.toByteArray()));
    }
}
