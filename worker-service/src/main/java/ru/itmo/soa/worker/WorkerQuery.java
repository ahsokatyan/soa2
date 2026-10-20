package ru.itmo.soa.worker;

import java.time.LocalDate;
import java.util.*;
import ru.itmo.soa.common.*;
import ru.itmo.soa.common.Models.*;

final class WorkerQuery {
    static final Map<String, String> COLUMNS;
    static {
        var fields = new LinkedHashMap<String, String>();
        fields.put("id", "id"); fields.put("name", "name"); fields.put("coordinates.x", "coord_x"); fields.put("coordinates.y", "coord_y");
        fields.put("creationDate", "creation_date"); fields.put("salary", "salary"); fields.put("startDate", "start_date");
        fields.put("position", "position"); fields.put("status", "status"); fields.put("person.height", "person_height");
        fields.put("person.eyeColor", "eye_color"); fields.put("organizationId", "organization_id");
        COLUMNS = Collections.unmodifiableMap(fields);
    }
    final int page, size;
    final String where, order;
    final List<Object> values = new ArrayList<>();
    WorkerQuery(Map<String, List<String>> query) {
        for (String key : query.keySet()) if (!COLUMNS.containsKey(key) && !Set.of("page", "size", "sort").contains(key)) throw ApiException.bad("Неизвестный параметр: " + key);
        page = integer(single(query, "page", "1"), 1, Integer.MAX_VALUE, "page");
        size = integer(single(query, "size", "20"), 1, 100, "size");
        var predicates = new ArrayList<String>();
        for (var field : COLUMNS.entrySet()) if (query.containsKey(field.getKey())) {
            predicates.add(field.getValue() + " = ?"); values.add(parse(field.getKey(), single(query, field.getKey(), null)));
        }
        where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        var sorts = new ArrayList<String>();
        boolean sortsId = false;
        for (String sort : query.getOrDefault("sort", List.of())) {
            String[] parts = sort.split(",", -1);
            if (parts.length != 2 || !COLUMNS.containsKey(parts[0]) || !Set.of("asc", "desc").contains(parts[1])) throw ApiException.bad("sort должен иметь вид поле,asc или поле,desc");
            sorts.add(COLUMNS.get(parts[0]) + " " + parts[1] + " NULLS LAST");
            sortsId |= parts[0].equals("id");
        }
        if (!sortsId) sorts.add("id ASC");
        order = " ORDER BY " + String.join(", ", sorts);
    }
    private static String single(Map<String,List<String>> query, String key, String fallback) {
        var values = query.get(key);
        if (values == null) return fallback;
        if (values.size() != 1 || values.get(0) == null || values.get(0).isEmpty()) throw ApiException.bad("Параметр " + key + " должен быть задан один раз и не быть пустым");
        return values.get(0);
    }
    private static int integer(String text, int min, int max, String field) {
        try { int value = Integer.parseInt(text); if (value >= min && value <= max) return value; }
        catch (NumberFormatException ignored) {}
        throw ApiException.bad("Некорректное значение " + field);
    }
    private static Object parse(String field, String text) {
        try {
            return switch (field) {
                case "id" -> Validation.workerId(text);
                case "organizationId" -> Validation.positiveLong(text, field);
                case "coordinates.x" -> Long.parseLong(text);
                case "coordinates.y" -> integer(text, -100, Integer.MAX_VALUE, field);
                case "creationDate", "startDate" -> LocalDate.parse(text);
                case "salary" -> positiveFloat(text);
                case "person.height" -> Validation.coefficient(text);
                case "position" -> Position.valueOf(text).name();
                case "status" -> Status.valueOf(text).name();
                case "person.eyeColor" -> Color.valueOf(text).name();
                default -> text;
            };
        } catch (IllegalArgumentException | java.time.DateTimeException e) { throw ApiException.bad("Некорректное значение фильтра " + field); }
    }
    static float positiveFloat(String text) {
        if (text == null) throw ApiException.bad("Параметр value обязателен");
        try { float value = Float.parseFloat(text); if (Float.isFinite(value) && value > 0) return value; }
        catch (NumberFormatException ignored) {}
        throw ApiException.bad("Зарплата должна быть конечным числом больше 0");
    }
}
