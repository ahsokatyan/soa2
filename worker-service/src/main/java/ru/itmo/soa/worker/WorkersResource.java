package ru.itmo.soa.worker;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.util.*;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

@Path("/workers")
@Produces("application/xml")
public class WorkersResource {
    @Inject WorkerRepository repository;
    @Context UriInfo uri;
    @GET public Response list() {
        Map<String,List<String>> query=new LinkedHashMap<>(); uri.getQueryParameters().forEach(query::put);
        var page=repository.list(query);
        return Response.ok(page).header("X-Total-Count",page.totalElements).header("X-Total-Pages",page.totalPages).build();
    }
    @POST @Consumes("application/xml") public Response create(WorkerInput input) {
        var worker=repository.create(input);
        return Response.created(URI.create(uri.getAbsolutePath() + "/" + worker.id)).entity(worker).build();
    }
    @GET @Path("/{id}") public Worker get(@PathParam("id") String id) { return repository.get(Validation.workerId(id)); }
    @PUT @Path("/{id}") @Consumes("application/xml") public Worker update(@PathParam("id") String id,WorkerInput input) { return repository.update(Validation.workerId(id),input); }
    @DELETE @Path("/{id}") public Response delete(@PathParam("id") String id) { repository.delete(Validation.workerId(id)); return Response.noContent().build(); }
    @GET @Path("/salary/sum") public SalarySum sum() { return repository.sum(); }
    @GET @Path("/salary/count-less-than") public WorkerCount count(@QueryParam("value") String value) { return repository.count(WorkerQuery.positiveFloat(value)); }
    @GET @Path("/salary/unique") public SalaryList unique() { return repository.unique(); }
}
