package ru.itmo.soa.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.time.LocalDate;
import java.util.List;

public final class Models {
    private Models() {}
    public enum Position { DIRECTOR, MANAGER, COOK }
    public enum Status { FIRED, HIRED, RECOMMENDED_FOR_PROMOTION, REGULAR }
    public enum Color { GREEN, RED, YELLOW, BROWN }
    public static class Coordinates { public Long x; public Integer y; }
    public static class Person { public Double height; public Color eyeColor; }
    @JacksonXmlRootElement(localName = "worker")
    public static class WorkerInput {
        public String name;
        public Coordinates coordinates;
        public Float salary;
        public LocalDate startDate;
        public Position position;
        public Status status;
        public Person person;
        public Long organizationId;
    }
    @JacksonXmlRootElement(localName = "worker")
    public static class Worker extends WorkerInput { public Integer id; public LocalDate creationDate; }
    @JacksonXmlRootElement(localName = "workerPage")
    public static class WorkerPage {
        public int page, size, totalPages;
        public long totalElements;
        @JacksonXmlElementWrapper(localName = "workers")
        @JacksonXmlProperty(localName = "worker")
        public List<Worker> workers;
    }
    @JacksonXmlRootElement(localName = "salarySum")
    public static class SalarySum { public double value; public SalarySum() {} public SalarySum(double value) { this.value = value; } }
    @JacksonXmlRootElement(localName = "workerCount")
    public static class WorkerCount { public long value; public WorkerCount() {} public WorkerCount(long value) { this.value = value; } }
    @JacksonXmlRootElement(localName = "salaries")
    public static class SalaryList {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "salary")
        public List<Float> values;
        public SalaryList() {} public SalaryList(List<Float> values) { this.values = values; }
    }
    @JacksonXmlRootElement(localName = "indexResult")
    public static class IndexResult {
        public long organizationId; public int updatedWorkers;
        public IndexResult() {} public IndexResult(long org, int count) { organizationId = org; updatedWorkers = count; }
    }
    public static class Violation {
        public String field, message;
        public Violation() {} public Violation(String field, String message) { this.field = field; this.message = message; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JacksonXmlRootElement(localName = "error")
    public static class ErrorBody {
        public int status; public String code, message, path, timestamp;
        @JacksonXmlElementWrapper(localName = "violations")
        @JacksonXmlProperty(localName = "violation")
        public List<Violation> violations;
    }
}
