package com.estoma.platformbff.api.wash;

import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort.Student;
import com.estoma.platformbff.application.wash.ExceptionalAuthorizationQueryService;
import com.estoma.platformbff.application.wash.ExceptionalAuthorizationQueryService.Item;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wash/exceptional-authorizations")
public class ExceptionalAuthorizationQueryController {
  private final ExceptionalAuthorizationQueryService queries;
  public ExceptionalAuthorizationQueryController(ExceptionalAuthorizationQueryService queries) { this.queries = queries; }
  @GetMapping("/students")
  public List<Student> search(@RequestParam String query) { return queries.search(query); }
  @GetMapping
  public List<Item> list(@RequestParam UUID studentAccountId, @RequestParam LocalDate serviceDate) {
    return queries.list(studentAccountId, serviceDate);
  }
}
