package ru.itmo.soa.common;

import java.util.ArrayList;
import ru.itmo.soa.common.Models.*;

public final class Validation {
    private Validation() {}
    public static long positiveLong(String text, String field) {
        try { long value = Long.parseLong(text); if (value > 0) return value; }
        catch (NumberFormatException ignored) {}
        throw ApiException.bad(field + " должен быть положительным целым числом");
    }
    public static int workerId(String text) {
        long value = positiveLong(text, "worker-id");
        if (value > Integer.MAX_VALUE) throw ApiException.bad("worker-id выходит за границы int32");
        return (int) value;
    }
    public static double coefficient(String text) {
        try { double value = Double.parseDouble(text); if (Double.isFinite(value) && value > 0) return value; }
        catch (NumberFormatException ignored) {}
        throw ApiException.bad("coeff должен быть конечным числом больше 0");
    }
    public static void worker(WorkerInput value) {
        var errors = new ArrayList<Violation>();
        if (value.name == null || value.name.isEmpty()) errors.add(new Violation("name", "не должно быть пустым"));
        if (value.coordinates == null) errors.add(new Violation("coordinates", "обязательное поле"));
        else {
            if (value.coordinates.x == null) errors.add(new Violation("coordinates.x", "обязательное поле int64"));
            if (value.coordinates.y == null || value.coordinates.y <= -101) errors.add(new Violation("coordinates.y", "должно быть целым числом больше -101"));
        }
        if (value.salary != null && (!Float.isFinite(value.salary) || value.salary <= 0)) errors.add(new Violation("salary", "должно быть конечным числом больше 0"));
        if (value.startDate == null) errors.add(new Violation("startDate", "обязательная дата YYYY-MM-DD"));
        if (value.organizationId == null || value.organizationId <= 0) errors.add(new Violation("organizationId", "должно быть целым числом больше 0"));
        if (value.person != null) {
            if (value.person.height == null || !Double.isFinite(value.person.height) || value.person.height <= 0) errors.add(new Violation("person.height", "должно быть конечным числом больше 0"));
            if (value.person.eyeColor == null) errors.add(new Violation("person.eyeColor", "обязательный цвет глаз"));
        }
        if (!errors.isEmpty()) throw new ApiException(422, "WORKER_VALIDATION_ERROR", "Данные работника нарушают ограничения модели", errors);
    }
}
