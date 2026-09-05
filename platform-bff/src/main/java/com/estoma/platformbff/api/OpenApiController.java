package com.estoma.platformbff.api;

import com.estoma.platformbff.api.admin.AdminController;
import com.estoma.platformbff.contracts.http.Required;
import com.estoma.platformbff.contracts.http.RequiredNullable;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Machine-readable OpenAPI 3.1 inventory generated from the live controller mappings and their
 * concrete Java HTTP DTOs. This makes controller/spec route drift impossible and keeps schemas
 * tied to the response and request types that actually cross the public boundary.
 */
@RestController
@RequestMapping("/api/v1")
public class OpenApiController {
  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");
  private static final Set<String> ANONYMOUS = Set.of(
      "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/password-recovery",
      "/api/v1/auth/password-recovery/validate", "/api/v1/auth/password-recovery/reset",
      "/api/v1/operations/{operationId}", "/api/v1/openapi", "/api/v1/jornadas", "/health", "/version",
      "/actuator/health", "/actuator/health/**", "/actuator/prometheus");

  private final RequestMappingHandlerMapping mappings;

  public OpenApiController(
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
    this.mappings = mappings;
  }

  @GetMapping(value = "/openapi", produces = "application/vnd.oai.openapi+json")
  public Map<String, Object> document() {
    Map<String, Object> paths = new TreeMap<>();
    mappings.getHandlerMethods().forEach((mapping, handler) -> add(paths, mapping.getPatternValues(),
        mapping.getMethodsCondition().getMethods(), handler));
    return Map.of(
        "openapi", "3.1.1",
        "info", Map.of("title", "Estoma Platform BFF API", "version", "v1"),
        "servers", List.of(Map.of("url", "/")),
        "paths", paths,
        "components", Map.of(
            "securitySchemes", Map.of("bearerAuth", Map.of("type", "http", "scheme", "bearer",
                "bearerFormat", "JWT")),
            "schemas", Map.of(
                "AcceptedOperation", acceptedOperationSchema(),
                "Problem", problemSchema())));
  }

  @SuppressWarnings("unchecked")
  private static void add(Map<String, Object> paths, Set<String> patterns,
      Set<RequestMethod> methods, HandlerMethod handler) {
    for (String path : patterns) {
      if (!isDocumented(path, handler)) continue;
      Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(path,
          ignored -> new LinkedHashMap<>());
      for (RequestMethod method : methods) {
        item.put(method.name().toLowerCase(), operation(path, method, handler));
      }
    }
  }

  private static boolean isDocumented(String path, HandlerMethod handler) {
    return handler.getBeanType().getPackageName().startsWith("com.estoma.platformbff.api")
        || path.equals("/health") || path.equals("/version") || path.equals("/actuator/health")
        || path.equals("/actuator/prometheus");
  }

  private static Map<String, Object> operation(String path, RequestMethod method,
      HandlerMethod handler) {
    AdminController.RouteContract admin = AdminController.routeFor(method.name(), path);
    boolean accepted = acceptedResponse(handler);
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operationId", handler.getBeanType().getSimpleName() + "_"
        + handler.getMethod().getName() + "_" + method.name().toLowerCase() + "_"
        + path.replaceAll("[^A-Za-z0-9]+", "_"));
    if (!ANONYMOUS.contains(path)) {
      operation.put("security", List.of(Map.of("bearerAuth", List.of())));
    }
    Type requestType = requestType(handler);
    if (admin != null) {
      operation.put("requestBody", Map.of("required", true, "content", Map.of(
          MediaType.APPLICATION_JSON_VALUE, Map.of("schema", admin.requestSchema().openApi()))));
      operation.put("summary", admin.publicOperation());
      operation.put("description", "Owner: " + admin.owner() + "; owner operation: "
          + admin.messageType() + ".");
      if (!admin.serverDerivedActorFields().isEmpty()) {
        operation.put("x-server-derived-actor-fields", admin.serverDerivedActorFields().stream().sorted().toList());
      }
    } else if (requestType != null) {
      operation.put("requestBody", Map.of("required", true, "content", Map.of(
          MediaType.APPLICATION_JSON_VALUE, Map.of("schema", schemaFor(requestType)))));
    }
    if (handler.getMethod().getName().equals("entryLookup")) {
      operation.put("description", "Manual STUDENT_ENROLLMENT lookup is provider-ready; QR lookup is "
          + "explicitly gated until the owner-backed QR projection is available and returns 503.");
      operation.put("x-provider-ready", Map.of("STUDENT_ENROLLMENT", true, "QR", false));
    }
    List<Map<String, Object>> parameters = new ArrayList<>();
    requestHeaders(handler, parameters);
    requestParameters(handler, parameters);
    Matcher matcher = PATH_VARIABLE.matcher(path);
    while (matcher.find()) {
      String name = matcher.group(1);
      parameters.add(Map.of("name", name, "in", "path", "required", true,
          "schema", name.endsWith("Id") ? Map.of("type", "string", "format", "uuid")
              : Map.of("type", "string")));
    }
    if (!parameters.isEmpty()) operation.put("parameters", List.copyOf(parameters));

    String success = accepted ? "202" : "200";
    Map<String, Object> successSchema = accepted
        ? Map.of("$ref", "#/components/schemas/AcceptedOperation")
        : schemaFor(responseType(handler));
    Map<String, Object> responses = new LinkedHashMap<>();
    responses.put(success, Map.of("description", accepted ? "Accepted" : "Successful response",
        "content", Map.of(MediaType.APPLICATION_JSON_VALUE, Map.of("schema", successSchema))));
    if (handler.getMethod().getName().equals("appointmentAvailability")) {
      operation.put("description", "Owner-derived cache-aside snapshot. A miss or stale snapshot "
          + "returns 503 with Retry-After after durably scheduling an asynchronous refresh.");
      responses.put("503", Map.of(
          "description", "Owner snapshot is being refreshed",
          "headers", Map.of("Retry-After", Map.of("required", true,
              "schema", Map.of("type", "integer", "minimum", 1))),
          "content", Map.of(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              Map.of("schema", Map.of("$ref", "#/components/schemas/Problem")))));
    }
    responses.put("default", Map.of("description", "Problem response", "content", Map.of(
        MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        Map.of("schema", Map.of("$ref", "#/components/schemas/Problem")))));
    operation.put("responses", responses);
    return operation;
  }

  private static Type requestType(HandlerMethod handler) {
    for (Parameter parameter : handler.getMethod().getParameters()) {
      if (parameter.isAnnotationPresent(RequestBody.class)) return parameter.getParameterizedType();
    }
    return null;
  }

  /** Headers are part of each handler's public contract, independently of its response status. */
  private static void requestHeaders(HandlerMethod handler, List<Map<String, Object>> parameters) {
    for (Parameter parameter : handler.getMethod().getParameters()) {
      RequestHeader header = parameter.getAnnotation(RequestHeader.class);
      if (header == null) continue;
      String name = header.name().isBlank() ? header.value() : header.name();
      parameters.add(Map.of("name", name, "in", "header", "required", header.required(),
          "schema", schemaFor(parameter.getParameterizedType())));
    }
  }

  /** Query parameters are part of the public surface just like path variables and idempotency headers. */
  private static void requestParameters(HandlerMethod handler, List<Map<String, Object>> parameters) {
    for (Parameter parameter : handler.getMethod().getParameters()) {
      RequestParam query = parameter.getAnnotation(RequestParam.class);
      if (query == null) continue;
      String name = query.name().isBlank()
          ? (query.value().isBlank() ? parameter.getName() : query.value()) : query.name();
      boolean required = query.required() && query.defaultValue().equals(
          org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);
      Map<String, Object> schema = new LinkedHashMap<>(schemaFor(parameter.getParameterizedType()));
      applyValidation(parameter, schema);
      if (!query.defaultValue().equals(org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE)) {
        schema.put("default", defaultValue(query.defaultValue(), parameter.getType()));
      }
      parameters.add(Map.of("name", name, "in", "query", "required", required, "schema", schema));
    }
  }

  private static Type responseType(HandlerMethod handler) {
    Type result = handler.getMethod().getGenericReturnType();
    if (result instanceof ParameterizedType parameterized
        && rawClass(parameterized.getRawType()) == ResponseEntity.class) {
      return parameterized.getActualTypeArguments()[0];
    }
    return result;
  }

  private static boolean acceptedResponse(HandlerMethod handler) {
    return rawClass(responseType(handler)) == AcceptedOperationResponse.class;
  }

  private static Map<String, Object> schemaFor(Type type) {
    if (type == null) return Map.of();
    Class<?> raw = rawClass(type);
    if (raw == null || raw == Object.class) return Map.of();
    if (raw == String.class || raw == Character.class || raw.isEnum()) {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "string");
      if (raw.isEnum()) {
        schema.put("enum", Arrays.stream(raw.getEnumConstants())
            .map(value -> ((Enum<?>) value).name()).toList());
      }
      return schema;
    }
    if (raw == UUID.class) return Map.of("type", "string", "format", "uuid");
    if (raw == Instant.class) return Map.of("type", "string", "format", "date-time");
    if (raw == LocalDate.class) return Map.of("type", "string", "format", "date");
    if (raw == boolean.class || raw == Boolean.class) return Map.of("type", "boolean");
    if (raw.isPrimitive() || Number.class.isAssignableFrom(raw)) {
      return Map.of("type", raw == float.class || raw == double.class || raw == Float.class
          || raw == Double.class ? "number" : "integer");
    }
    if (raw.isArray()) return Map.of("type", "array", "items", schemaFor(raw.getComponentType()));
    if (Collection.class.isAssignableFrom(raw)) {
      return Map.of("type", "array", "items", schemaFor(typeArgument(type, 0)));
    }
    if (Map.class.isAssignableFrom(raw)) {
      Type values = typeArgument(type, 1);
      return values == null ? Map.of("type", "object", "additionalProperties", true)
          : Map.of("type", "object", "additionalProperties", schemaFor(values));
    }
    if (raw.isRecord()) return recordSchema(raw);
    return Map.of("type", "object");
  }

  private static Map<String, Object> recordSchema(Class<?> recordType) {
    Map<String, Object> properties = new TreeMap<>();
    List<String> required = new ArrayList<>();
    for (RecordComponent component : recordType.getRecordComponents()) {
      Map<String, Object> property = new LinkedHashMap<>(schemaFor(component.getGenericType()));
      applyValidation(component, property);
      boolean requiredNullable = component.isAnnotationPresent(RequiredNullable.class);
      boolean mandatory = component.getType().isPrimitive()
          || component.isAnnotationPresent(NotNull.class)
          || component.isAnnotationPresent(NotBlank.class)
          || component.isAnnotationPresent(NotEmpty.class)
          || component.isAnnotationPresent(Required.class) || requiredNullable;
      properties.put(component.getName(), mandatory && !requiredNullable ? property : nullable(property));
      if (mandatory) required.add(component.getName());
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    if (!required.isEmpty()) schema.put("required", required);
    schema.put("properties", properties);
    return schema;
  }

  private static Map<String, Object> nullable(Map<String, Object> schema) {
    Object type = schema.get("type");
    if (type instanceof String stringType) {
      schema.put("type", List.of(stringType, "null"));
      return schema;
    }
    return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
  }

  private static void applyValidation(RecordComponent component, Map<String, Object> schema) {
    if (component.isAnnotationPresent(NotBlank.class)) schema.put("minLength", 1);
    Size size = component.getAnnotation(Size.class);
    if (size != null) {
      boolean collection = Collection.class.isAssignableFrom(component.getType()) || component.getType().isArray();
      if (size.min() > 0) schema.put(collection ? "minItems" : "minLength", size.min());
      if (size.max() < Integer.MAX_VALUE) schema.put(collection ? "maxItems" : "maxLength", size.max());
    }
    Min min = component.getAnnotation(Min.class);
    if (min != null) schema.put("minimum", min.value());
    Max max = component.getAnnotation(Max.class);
    if (max != null) schema.put("maximum", max.value());
    if (component.isAnnotationPresent(Positive.class)) schema.put("exclusiveMinimum", 0);
    if (component.isAnnotationPresent(PositiveOrZero.class)) schema.put("minimum", 0);
    jakarta.validation.constraints.Pattern pattern = component.getAnnotation(jakarta.validation.constraints.Pattern.class);
    if (pattern != null) {
      String expression = pattern.regexp();
      if (expression.matches("[A-Z0-9_]+(\\|[A-Z0-9_]+)+")) {
        schema.put("enum", List.of(expression.split("\\|")));
      } else {
        schema.put("pattern", expression);
      }
    }
  }

  private static void applyValidation(Parameter parameter, Map<String, Object> schema) {
    jakarta.validation.constraints.Min min = parameter.getAnnotation(jakarta.validation.constraints.Min.class);
    if (min != null) schema.put("minimum", min.value());
    jakarta.validation.constraints.Max max = parameter.getAnnotation(jakarta.validation.constraints.Max.class);
    if (max != null) schema.put("maximum", max.value());
    jakarta.validation.constraints.Size size = parameter.getAnnotation(jakarta.validation.constraints.Size.class);
    if (size != null && size.max() < Integer.MAX_VALUE) schema.put("maxLength", size.max());
    if (parameter.isAnnotationPresent(jakarta.validation.constraints.NotBlank.class)) schema.put("minLength", 1);
  }

  private static Object defaultValue(String value, Class<?> type) {
    if (type == int.class || type == Integer.class) return Integer.parseInt(value);
    if (type == long.class || type == Long.class) return Long.parseLong(value);
    if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
    return value;
  }


  private static Type typeArgument(Type type, int index) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getActualTypeArguments().length > index) {
      return parameterized.getActualTypeArguments()[index];
    }
    return null;
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> clazz) return clazz;
    if (type instanceof ParameterizedType parameterized) return rawClass(parameterized.getRawType());
    return null;
  }

  private static Map<String, Object> acceptedOperationSchema() {
    return Map.of("type", "object", "additionalProperties", false,
        "required", List.of("operationId", "status", "pollPath", "submittedAt"), "properties", Map.of(
            "operationId", schemaFor(UUID.class), "status", schemaFor(String.class),
            "pollPath", schemaFor(String.class), "submittedAt", schemaFor(Instant.class)));
  }

  private static Map<String, Object> problemSchema() {
    return Map.of("type", "object", "additionalProperties", false,
        "required", List.of("type", "title", "status", "code", "detail", "traceId"), "properties", Map.of(
            "type", schemaFor(String.class), "title", schemaFor(String.class),
            "status", schemaFor(Integer.class), "code", schemaFor(String.class),
            "detail", schemaFor(String.class), "traceId", schemaFor(String.class)));
  }
}
