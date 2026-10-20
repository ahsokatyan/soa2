package ru.itmo.soa.worker;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

@Provider @Consumes("application/xml") @Produces("application/xml")
public class XmlProvider implements MessageBodyReader<WorkerInput>, MessageBodyWriter<Object> {
    public boolean isReadable(Class<?> type,Type generic,Annotation[] annotations,MediaType media) { return type == WorkerInput.class; }
    public WorkerInput readFrom(Class<WorkerInput> type,Type generic,Annotation[] annotations,MediaType media,MultivaluedMap<String,String> headers,InputStream input) throws IOException {
        return Xml.read(input.readNBytes(1_048_577),WorkerInput.class,"worker");
    }
    public boolean isWriteable(Class<?> type,Type generic,Annotation[] annotations,MediaType media) { return type.getName().startsWith("ru.itmo.soa.common.Models$"); }
    public long getSize(Object value,Class<?> type,Type generic,Annotation[] annotations,MediaType media) { return -1; }
    public void writeTo(Object value,Class<?> type,Type generic,Annotation[] annotations,MediaType media,MultivaluedMap<String,Object> headers,OutputStream output) throws IOException { output.write(Xml.bytes(value)); }
}
