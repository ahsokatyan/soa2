package ru.itmo.soa.worker;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

@Path("/internal") @Produces("application/xml")
public class InternalResource {
    @Inject WorkerRepository repository;
    private void authorize(String token) {
        String expected=Settings.get("INTERNAL_TOKEN","");
        if(expected.isBlank() || token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) throw new ApiException(403,"INTERNAL_ACCESS_DENIED","Служебная операция доступна только HR-сервису");
    }
    @POST @Path("/move/{worker}/{from}/{to}")
    public Worker move(@HeaderParam("X-Internal-Token") String token,@PathParam("worker") String worker,@PathParam("from") String from,@PathParam("to") String to) {
        authorize(token); return repository.move(Validation.workerId(worker),Validation.positiveLong(from,"id-from"),Validation.positiveLong(to,"id-to"));
    }
    @POST @Path("/index/{org}/{coeff}")
    public IndexResult index(@HeaderParam("X-Internal-Token") String token,@PathParam("org") String org,@PathParam("coeff") String coeff) {
        authorize(token); return repository.index(Validation.positiveLong(org,"org-id"),Validation.coefficient(coeff));
    }
}
