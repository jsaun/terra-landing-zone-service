package bio.terra.landingzone.testutils;

import bio.terra.landingzone.db.model.LandingZone;
import java.util.Map;
import java.util.UUID;

public class TestFixtures {
  public static LandingZone createLandingZone(
      UUID landingZoneId,
      String resourceGroupId,
      String definition,
      String version,
      String displayName,
      String description,
      Map<String, String> properties) {
    return new LandingZone(
        landingZoneId, resourceGroupId, definition, version, displayName, description, properties);
  }
}