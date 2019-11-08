package sensordata;

public final class SensorDataUtils {
  public static boolean isValidMetric(Metric m) {
    return m.getValue() >= 0.0;
  }
}
