package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.priority.AppointmentType;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.resource.CabinType;
import java.util.List;
import java.util.Optional;

public interface ResourcePriorityRepository {

  Optional<ResourcePriority> findByTypePair(AppointmentType appointmentType, CabinType cabinType);

  List<ResourcePriority> findAll();

  void insert(ResourcePriority priority);

  boolean insertIfAbsent(ResourcePriority priority);
}
