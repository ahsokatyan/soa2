package ru.itmo.soa.worker;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.Provider;
import ru.itmo.soa.common.Settings;
import java.util.*;

@Provider @PreMatching
public class Cors implements ContainerRequestFilter,ContainerResponseFilter {
    private boolean allowed(String origin) { return origin != null && Arrays.asList(Settings.get("CLIENT_ORIGINS","https://localhost:8444").split(",")).contains(origin); }
    public void filter(ContainerRequestContext request) { if(request.getMethod().equals("OPTIONS")) request.abortWith(Response.noContent().build()); }
    public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        String origin=request.getHeaderString("Origin");
        if(allowed(origin)) {
            response.getHeaders().putSingle("Access-Control-Allow-Origin",origin);
            response.getHeaders().putSingle("Access-Control-Allow-Methods","GET, POST, PUT, DELETE, OPTIONS");
            response.getHeaders().putSingle("Access-Control-Allow-Headers","Accept, Content-Type");
            response.getHeaders().putSingle("Access-Control-Expose-Headers","Location, X-Total-Count, X-Total-Pages, Retry-After");
            response.getHeaders().add("Vary","Origin");
        }
        response.getHeaders().putSingle("X-Content-Type-Options","nosniff");
    }
}
