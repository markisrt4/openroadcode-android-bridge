package org.openroadcode.androidbridge;

/** Environment keeps the existing data endpoint alive without enabling location or motion. */
record SensorActivityPlan(boolean serve, boolean navigation, boolean location) {
  static SensorActivityPlan of(boolean navigationRequested, boolean environmentRequested,
                               boolean physicalSource, boolean locationPermission) {
    boolean navigation = navigationRequested && (!physicalSource || locationPermission);
    return new SensorActivityPlan(environmentRequested || navigation, navigation,
        navigation && physicalSource);
  }
}
