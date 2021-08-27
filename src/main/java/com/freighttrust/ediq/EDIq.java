package com.freighttrust.ediq;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stax.StAXSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.xlate.edi.schema.Schema;
import io.xlate.edi.schema.SchemaFactory;
import io.xlate.edi.stream.EDIInputFactory;
import io.xlate.edi.stream.EDINamespaces;
import io.xlate.edi.stream.EDIStreamConstants;
import io.xlate.edi.stream.EDIStreamEvent;
import io.xlate.edi.stream.EDIStreamReader;

public class EDIq {

    static final String OPTION_HELP = "ediq [OPTION]... [FILE]";
    static final String MSG_MISSING_FILE = "File must be specified; use '-' for standard input";

    static final String OPT_EPATH = "epath";
    static final String OPT_FORMAT = "format";
    static final String OPT_SCHEMA = "schema-file";

    PrintStream out = System.out; // NOSONAR

    static class InvalidCommandLineException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static void main(String[] args) {
        try {
            new EDIq().execute(args);
        } catch (InvalidCommandLineException e) {
            System.exit(1);
        }
    }

    void execute(String[] args) {
        CommandLine cmd = setupCommandLine(args);
        String input = cmd.getArgs()[0];
        String epath = cmd.getOptionValue(OPT_EPATH);
        String schemaFile = cmd.getOptionValue(OPT_SCHEMA);
        boolean format = cmd.hasOption(OPT_FORMAT);

        EDIInputFactory ediFactory = EDIInputFactory.newFactory();
        TransformerFactory trxFactory = TransformerFactory.newInstance();
        trxFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); // Security
        trxFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); // Security
        EDIStreamReader ediReader = null;

        try (InputStream stream = "-".equals(input) ? System.in : new FileInputStream(new File(input))) {
            ediReader = ediFactory.createEDIStreamReader(stream);

            if (schemaFile != null) {
                Schema transactionSchema = SchemaFactory.newFactory().createSchema(new FileInputStream(schemaFile));

                ediReader = ediFactory.createFilteredReader(ediReader, reader -> {
                    if (reader.getEventType() == EDIStreamEvent.START_TRANSACTION) {
                        reader.setTransactionSchema(transactionSchema);
                    }
                    return true;
                });
            }

            XMLStreamReader xmlReader = ediFactory.createXMLStreamReader(ediReader);
            xmlReader.next();
            Map<String, Character> delimiters = ediReader.getDelimiters();

            Transformer transformer = trxFactory.newTransformer();
            DOMResult dom = new DOMResult();
            transformer.transform(new StAXSource(xmlReader), dom);
            Document document = (Document) dom.getNode();
            XPath xPath = XPathFactory.newInstance().newXPath();
            NodeList matches = (NodeList) xPath.evaluate(epath, document, XPathConstants.NODESET);

            for (int i = 0, m = matches.getLength(); i < m; i++) {
                StringBuilder output = new StringBuilder();
                Node node = matches.item(i);

                if (EDINamespaces.LOOPS.equals(node.getNamespaceURI())) {
                    serializeLoop(node, delimiters, format, output);
                } else if (EDINamespaces.SEGMENTS.equals(node.getNamespaceURI())) {
                    serializeSegment(node, delimiters, output);
                } else if (EDINamespaces.COMPOSITES.equals(node.getNamespaceURI())) {
                    serializeComposite(node, delimiters, output);
                } else if (EDINamespaces.ELEMENTS.equals(node.getNamespaceURI())) {
                    serializeElement(node, output);
                }

                out.println(output);
            }
        } catch (Exception e) {
            e.printStackTrace(); // NOSONAR (we want to print to standard error)
        } finally {
            if (ediReader != null) {
                try {
                    ediReader.close();
                } catch (IOException e) {
                    e.printStackTrace(); // NOSONAR (we want to print to standard error)
                }
            }
        }
    }

    CommandLine setupCommandLine(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder()
                                .longOpt(OPT_EPATH)
                                .hasArg()
                                .required()
                                .desc("evaluate the XPath expression")
                                .build());
        options.addOption(Option.builder()
                                .longOpt(OPT_SCHEMA)
                                .hasArg()
                                .desc("transaction schema (optional)")
                                .build());
        options.addOption(Option.builder()
                                .longOpt(OPT_FORMAT)
                                .desc("format output, one segment per line")
                                .build());

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            abortCommand(options, e.getMessage());
            throw new InvalidCommandLineException();
        }

        if (cmd.getArgList().isEmpty()) {
            abortCommand(options, MSG_MISSING_FILE);
            throw new InvalidCommandLineException();
        }

        return cmd;
    }

    void abortCommand(Options options, String message) {
        out.println(message);
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(OPTION_HELP, options);
    }

    /**
     * Reconstruct using the original delimiters and append the loop to the
     * output.
     *
     * @param loop
     *            node composed of segments and/or other loop nodes
     * @param delimiters
     *            original delimiters received in the EDI data
     * @param format
     *            indicates whether each segment will be written to a separate
     *            line in the output
     * @param output
     *            result buffer to which the loop is reconstructed
     */
    void serializeLoop(Node loop, Map<String, Character> delimiters, boolean format, StringBuilder output) {
        NodeList nodes = loop.getChildNodes();

        for (int i = 0, m = nodes.getLength(); i < m; i++) {
            Node node = nodes.item(i);

            if (EDINamespaces.LOOPS.equals(node.getNamespaceURI())) {
                serializeLoop(node, delimiters, format, output);
            } else if (EDINamespaces.SEGMENTS.equals(node.getNamespaceURI())) {
                serializeSegment(node, delimiters, output);
                if (format) {
                    output.append(System.lineSeparator());
                }
            }
        }
    }

    /**
     * Reconstruct using the original delimiters and append the segment to the
     * output.
     *
     * @param segment
     *            node composed of simple elements and/or composite elements
     * @param delimiters
     *            original delimiters received in the EDI data
     * @param output
     *            result buffer to which the segment is reconstructed
     */
    void serializeSegment(Node segment, Map<String, Character> delimiters, StringBuilder output) {
        NodeList elements = segment.getChildNodes();

        output.append(segment.getLocalName());
        output.append(delimiters.get(EDIStreamConstants.Delimiters.DATA_ELEMENT));

        String previousElementName = null;

        for (int i = 0, m = elements.getLength(); i < m; i++) {
            Node element = elements.item(i);

            if (i > 0) {
                if (element.getLocalName().equals(previousElementName)) {
                    output.append(delimiters.get(EDIStreamConstants.Delimiters.REPETITION));
                } else {
                    output.append(delimiters.get(EDIStreamConstants.Delimiters.DATA_ELEMENT));
                }
            }

            if (EDINamespaces.COMPOSITES.equals(element.getNamespaceURI())) {
                serializeComposite(element, delimiters, output);
            } else if (EDINamespaces.ELEMENTS.equals(element.getNamespaceURI())) {
                serializeElement(element, output);
            }

            previousElementName = element.getLocalName();
        }

        output.append(delimiters.get(EDIStreamConstants.Delimiters.SEGMENT));
    }

    /**
     * Reconstruct using the original delimiter and append the composite to the
     * output.
     *
     * @param composite
     *            node composed of simple elements
     * @param delimiters
     *            original delimiters received in the EDI data
     * @param output
     *            result buffer to which the composite is reconstructed
     */
    void serializeComposite(Node composite, Map<String, Character> delimiters, StringBuilder output) {
        NodeList components = composite.getChildNodes();

        for (int i = 0, m = components.getLength(); i < m; i++) {
            if (i > 0) {
                output.append(delimiters.get(EDIStreamConstants.Delimiters.COMPONENT_ELEMENT));
            }

            serializeElement(components.item(i), output);
        }
    }

    /**
     * Appends the value of a simple element to the result output
     *
     * @param element
     *            node representing a simple EDI element
     * @param output
     *            result buffer
     */
    void serializeElement(Node element, StringBuilder output) {
        output.append(element.getTextContent());
    }
}
