package ru.itmo.soa.hr;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

@RestController @RequestMapping(value="/api/hr",produces="application/xml")
public class HrController {
    private final WorkerClient worker;
    public HrController(WorkerClient worker) { this.worker=worker; }
    @PostMapping("/move/{workerId}/{from}/{to}")
    public ResponseEntity<byte[]> move(@PathVariable("workerId") String workerId,@PathVariable("from") String from,@PathVariable("to") String to) {
        int id=Validation.workerId(workerId); long source=Validation.positiveLong(from,"id-from"),target=Validation.positiveLong(to,"id-to");
        if(source == target) throw new ApiException(409,"ORGANIZATION_MOVE_CONFLICT","Исходная и целевая организации совпадают");
        return xml(worker.post("/internal/move/" + id + "/" + source + "/" + target,Worker.class,"worker"));
    }
    @PostMapping("/index/all/{org}/{coeff}")
    public ResponseEntity<byte[]> index(@PathVariable("org") String org,@PathVariable("coeff") String coeff) {
        long id=Validation.positiveLong(org,"org-id"); double value=Validation.coefficient(coeff);
        return xml(worker.post("/internal/index/" + id + "/" + value,IndexResult.class,"indexResult"));
    }
    static ResponseEntity<byte[]> xml(Object value) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(Xml.bytes(value)); }
}
