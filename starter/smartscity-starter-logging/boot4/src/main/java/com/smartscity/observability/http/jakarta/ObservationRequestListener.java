package com.smartscity.observability.http.jakarta;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

/**
 * Final fallback for errors or completion races without changing container dispatch behavior.
 *
 * @author jack
 */
public final class ObservationRequestListener implements ServletRequestListener {
  @Override
  public void requestDestroyed(ServletRequestEvent event) {
    Object state = event.getServletRequest().getAttribute(ObservationFilter.ATTRIBUTE);
    if (state instanceof ObservationFilter.State) {
      ((ObservationFilter.State) state).finish();
    }
  }
}
