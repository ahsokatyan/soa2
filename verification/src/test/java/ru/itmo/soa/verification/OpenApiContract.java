package ru.itmo.soa.verification;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Checks actual XML against required fields, primitive types, enums, bounds and roots in openapi.yaml. */
final class OpenApiContract {
    final JsonNode spec;
    final Set<String> covered = new TreeSet<>();
    OpenApiContract() throws Exception { spec = new ObjectMapper(new YAMLFactory()).readTree(Path.of("..","openapi.yaml").toFile()); }
    JsonNode resolve(JsonNode schema) {
        if(schema.has("$ref")) return spec.at(schema.get("$ref").asText().substring(1));
        return schema;
    }
    Document parse(byte[] data) throws Exception {
        var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }
    Document verify(String method,String route,int status,byte[] body) throws Exception {
        var operation=spec.path("paths").path(route).path(method.toLowerCase()); assertFalse(operation.isMissingNode(),method + " " + route);
        covered.add(method.toLowerCase() + " " + route);
        var response=operation.path("responses").path(Integer.toString(status)); if(response.isMissingNode()) response=operation.path("responses").path("default");
        assertFalse(response.isMissingNode(),"Undocumented status " + status);
        response=resolve(response);
        if(status == 204) { assertEquals(0,body.length); return null; }
        var schema=response.path("content").path("application/xml").path("schema"); assertFalse(schema.isMissingNode());
        var doc=parse(body); JsonNode rootSchema=resolve(schema);
        while(rootSchema.has("allOf") && !rootSchema.has("xml")) rootSchema=resolve(rootSchema.path("allOf").get(0));
        assertEquals(rootSchema.path("xml").path("name").asText(),doc.getDocumentElement().getTagName(),"XML root");
        validate(doc.getDocumentElement(),schema); return doc;
    }
    private void validate(Element element,JsonNode original) {
        var schema=resolve(original);
        if(schema.has("allOf")) for(var item:schema.path("allOf")) validate(element,item);
        if(schema.path("type").asText().equals("object")) {
            for(var required:schema.path("required")) {
                var property=schema.path("properties").path(required.asText());
                if(property.path("type").asText().equals("array") && !property.path("xml").path("wrapped").asBoolean(false)) continue;
                assertNotNull(child(element,required.asText()),"Missing " + element.getTagName() + "." + required.asText());
            }
            schema.path("properties").fields().forEachRemaining(property -> {
                var rule=property.getValue(); String name=property.getKey();
                if(rule.path("type").asText().equals("array")) {
                    String wrapper=rule.path("xml").path("name").asText(name); boolean wrapped=rule.path("xml").path("wrapped").asBoolean(false);
                    var itemRule=rule.path("items"); String itemName=itemRule.path("xml").path("name").asText(name);
                    Element parent=wrapped ? child(element,wrapper) : element;
                    if(parent != null) for(var item:children(parent,itemName)) validate(item,itemRule);
                } else { var value=child(element,name); if(value != null) validate(value,rule); }
            });
            return;
        }
        String value=element.getTextContent(),type=schema.path("type").asText();
        if(schema.has("enum")) { var allowed=new ArrayList<String>(); schema.path("enum").forEach(item -> allowed.add(item.asText())); assertTrue(allowed.contains(value),"Enum: " + value); }
        if(type.equals("integer") || type.equals("number")) {
            double number=Double.parseDouble(value); assertTrue(Double.isFinite(number));
            if(type.equals("integer")) { long integer=Long.parseLong(value); if(schema.path("format").asText().equals("int32")) assertTrue(integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE); }
            if(schema.has("minimum")) assertTrue(schema.path("exclusiveMinimum").asBoolean(false) ? number > schema.path("minimum").asDouble() : number >= schema.path("minimum").asDouble(),"Minimum " + element.getTagName());
            if(schema.has("maximum")) assertTrue(number <= schema.path("maximum").asDouble());
        }
        if(type.equals("string")) {
            if(schema.has("minLength")) assertTrue(value.length() >= schema.path("minLength").asInt());
            if(schema.path("format").asText().equals("date")) java.time.LocalDate.parse(value);
            if(schema.path("format").asText().equals("date-time")) java.time.OffsetDateTime.parse(value);
        }
    }
    static Element child(Element parent,String name) { return children(parent,name).stream().findFirst().orElse(null); }
    static List<Element> children(Element parent,String name) { var result=new ArrayList<Element>(); for(Node item=parent.getFirstChild();item != null;item=item.getNextSibling()) if(item instanceof Element element && element.getTagName().equals(name)) result.add(element); return result; }
    static String text(Element parent,String path) { Element value=parent; for(String key:path.split("\\.")) { value=child(value,key); if(value == null) return null; } return value.getTextContent(); }
}
