package ru.itmo.soa.worker;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.logging.*;
import ru.itmo.soa.common.*;

@Provider
public class Errors implements ExceptionMapper<Exception> {
    @Context UriInfo uri;
    public Response toResponse(Exception error) {
        ApiException api;
        if(error instanceof ApiException known) api=known;
        else if(error instanceof WebApplicationException web) {
            int status=web.getResponse().getStatus();
            api=new ApiException(status,switch(status) { case 400 -> "BAD_REQUEST"; case 404 -> "RESOURCE_NOT_FOUND"; case 406 -> "NOT_ACCEPTABLE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE"; default -> "HTTP_ERROR"; },switch(status) { case 406 -> "Поддерживается только Accept application/xml"; case 415 -> "Требуется Content-Type application/xml"; default -> "Запрос не может быть обработан"; });
        } else { Logger.getLogger(Errors.class.getName()).log(Level.SEVERE,"Request failed",error); api=new ApiException(500,"INTERNAL_SERVER_ERROR","Внутренняя ошибка сервиса"); }
        var response=Response.status(api.status).type("application/xml; charset=UTF-8").entity(Xml.bytes(api.body(uri.getRequestUri().getRawPath())));
        if(api.status == 503) response.header("Retry-After",30);
        return response.build();
    }
}
