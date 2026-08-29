package com.estoma.platformbff.api;

import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
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
      "/api/v1/operations/{operationId}", "/api/v1/openapi", "/health", "/version",
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
    boolean accepted = method == RequestMethod.POST && (handler.getBeanType().getPackageName()
        .contains(".api.admin") || handler.getBeanType().getPackageName().contains(".api.wash")
        || handler.getMethod().getName().equals("requestRecovery")
        || handler.getMethod().getName().equals("registerVisit"));
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operationId", handler.getBeanType().getSimpleName() + "_"
        + handler.getMethod().getName() + "_" + method.name().toLowerCase() + "_"
        + path.replaceAll("[^A-Za-z0-9]+", "_"));
    if (!ANONYMOUS.contains(path)) {
      operation.put("security", List.of(Map.of("bearerAuth", List.of())));
    }
    Type requestType = requestType(handler);
    if (requestType != null) {
      operation.put("requestBody", Map.of("required", true, "content", Map.of(
          MediaType.APPLICATION_JSON_VALUE, Map.of("schema", schemaFor(requestType)))));
    }
    List<Map<String, Object>> parameters = new ArrayList<>();
    if (accepted) {
      parameters.add(Map.of("name", "Idempotency-Key", "in", "header", "required", true,
          "schema", Map.of("type", "string")));
    }
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
    operation.put("responses", Map.of(
        success, Map.of("description", accepted ? "Accepted" : "Successful response", "content", Map.of(
            MediaType.APPLICATION_JSON_VALUE, Map.of("schema", successSchema))),
        "default", Map.of("description", "Problem response", "content", Map.of(
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            Map.of("schema", Map.of("$ref", "#/components/schemas/Problem"))))));
    return operation;
  }

  private static Type requestType(HandlerMethod handler) {
    for (Parameter parameter : handler.getMethod().getParameters()) {
      if (parameter.isAnnotationPresent(RequestBody.class)) return parameter.getParameterizedType();
    }
    return null;
  }

  private static Type responseType(HandlerMethod handler) {
    Type result = handler.getMethod().getGenericReturnType();
    if (result instanceof ParameterizedType parameterized
        && rawClass(parameterized.getRawType()) == ResponseEntity.class) {
      return parameterized.getActualTypeArguments()[0];
    }
    return result;
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
      properties.put(component.getName(), schemaFor(component.getGenericType()));
      required.add(component.getName());
    }
    return Map.of("type", "object", "additionalProperties", false, "required", required,
        "properties", properties);
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
