package bio.terra.landingzone.stairway.flight.create;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import bio.terra.landingzone.common.utils.LandingZoneFlightBeanBag;
import bio.terra.landingzone.service.landingzone.azure.model.LandingZoneRequest;
import bio.terra.landingzone.stairway.flight.FlightTestUtils;
import bio.terra.landingzone.stairway.flight.LandingZoneFlightMapKeys;
import bio.terra.landingzone.stairway.flight.StepsDefinitionFactoryType;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class CreateLandingZoneFlightTest {
  private static final UUID BILLING_PROFILE_ID = UUID.randomUUID();
  private static final UUID LANDING_ZONE_ID = UUID.randomUUID();

  private CreateLandingZoneFlight createLandingZoneFlight;

  @Mock private LandingZoneFlightBeanBag mockApplicationContext;

  @Test
  void testInitializationWhenIsNotAttaching() {
    final boolean isAttaching = false;
    LandingZoneRequest defaultLandingZoneRequest = createDefaultLandingZoneRequest(isAttaching);

    FlightMap inputParameters =
        FlightTestUtils.prepareFlightInputParameters(
            Map.of(LandingZoneFlightMapKeys.LANDING_ZONE_CREATE_PARAMS, defaultLandingZoneRequest));

    createLandingZoneFlight = new CreateLandingZoneFlight(inputParameters, mockApplicationContext);

    var steps = createLandingZoneFlight.getSteps();
    assertThat(steps.size(), equalTo(5));
    validateSteps(steps, isAttaching);
  }

  @Test
  void testInitializationWhenAttaching() {
    final boolean isAttaching = true;
    LandingZoneRequest defaultLandingZoneRequest = createDefaultLandingZoneRequest(isAttaching);

    FlightMap inputParameters =
        FlightTestUtils.prepareFlightInputParameters(
            Map.of(LandingZoneFlightMapKeys.LANDING_ZONE_CREATE_PARAMS, defaultLandingZoneRequest));

    createLandingZoneFlight = new CreateLandingZoneFlight(inputParameters, mockApplicationContext);

    var steps = createLandingZoneFlight.getSteps();
    assertThat(steps.size(), equalTo(3));
    validateSteps(steps, isAttaching);
  }

  void validateSteps(List<Step> steps, boolean isAttaching) {
    assertThat(steps.stream().filter(s -> s instanceof CreateSamResourceStep).count(), equalTo(1L));
    assertThat(steps.stream().filter(s -> s instanceof GetBillingProfileStep).count(), equalTo(1L));
    if (!isAttaching) {
      assertThat(
          steps.stream().filter(s -> s instanceof CreateLandingZoneResourcesFlightStep).count(),
          equalTo(1L));
      assertThat(
          steps.stream()
              .filter(s -> s instanceof AwaitCreateLandingResourcesZoneFlightStep)
              .count(),
          equalTo(1L));
    }
    assertThat(
        steps.stream().filter(s -> s instanceof CreateAzureLandingZoneDbRecordStep).count(),
        equalTo(1L));
  }

  LandingZoneRequest createDefaultLandingZoneRequest(boolean isAttaching) {
    return new LandingZoneRequest(
        StepsDefinitionFactoryType.CROMWELL_BASE_DEFINITION_STEPS_PROVIDER_TYPE.getValue(),
        "v1",
        isAttaching ? Map.of(LandingZoneFlightMapKeys.ATTACH, "true") : Map.of(),
        BILLING_PROFILE_ID,
        Optional.of(LANDING_ZONE_ID));
  }
}
