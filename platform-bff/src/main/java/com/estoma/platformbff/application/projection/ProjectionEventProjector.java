package com.estoma.platformbff.application.projection;

public interface ProjectionEventProjector {
  void project(ProjectionEvent event);
}
