package ru.itmo.soa.hr;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import java.util.logging.*;
import ru.itmo.soa.common.*;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> error(Exception error,HttpServletRequest request) {
        ApiException api;
        if(error instanceof ApiException known) api=known;
        else if(error instanceof HttpMediaTypeNotAcceptableException) api=new ApiException(406,"NOT_ACCEPTABLE","Поддерживается только Accept application/xml");
        else if(error instanceof HttpMediaTypeNotSupportedException) api=new ApiException(415,"UNSUPPORTED_MEDIA_TYPE","Неподдерживаемый формат тела запроса");
        else if(error instanceof NoResourceFoundException || error instanceof NoHandlerFoundException) api=ApiException.missing("Ресурс не найден");
        else if(error instanceof HttpRequestMethodNotSupportedException) api=new ApiException(405,"METHOD_NOT_ALLOWED","Метод запроса не поддерживается");
        else { Logger.getLogger(Errors.class.getName()).log(Level.SEVERE,"Request failed",error); api=new ApiException(500,"INTERNAL_SERVER_ERROR","Внутренняя ошибка HR-сервиса"); }
        var builder=ResponseEntity.status(api.status).contentType(MediaType.APPLICATION_XML);
        if(api.status == 503) builder.header("Retry-After","30");
        return builder.body(Xml.bytes(api.body(request.getRequestURI())));
    }
}
