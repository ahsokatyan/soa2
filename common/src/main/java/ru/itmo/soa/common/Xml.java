package ru.itmo.soa.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.*;
import javax.xml.stream.*;

public final class Xml {
    public static final XmlMapper MAPPER;
    static {
        var input = XMLInputFactory.newFactory();
        input.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        input.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        MAPPER = new XmlMapper(new XmlFactory(input));
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MAPPER.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        MAPPER.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
    private Xml() {}
    public static byte[] bytes(Object value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (IOException e) { throw new IllegalStateException("XML serialization failed", e); }
    }
    public static <T> T read(byte[] bytes, Class<T> type, String root) {
        if (bytes.length > 1_048_576) throw new ApiException(400, "XML_TOO_LARGE", "XML превышает 1 МБ");
        try {
            var reader = MAPPER.getFactory().getXMLInputFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                    if (reader.getEventType() == XMLStreamConstants.DTD) throw new XMLStreamException("DTD forbidden");
                }
                if (!root.equals(reader.getLocalName())) throw new XMLStreamException("Expected " + root);
            } finally { reader.close(); }
            return MAPPER.readValue(bytes, type);
        } catch (IOException | XMLStreamException | IllegalArgumentException e) {
            throw new ApiException(400, "MALFORMED_XML", "Некорректный XML: проверьте корневой элемент, поля, типы и даты");
        }
    }
}
